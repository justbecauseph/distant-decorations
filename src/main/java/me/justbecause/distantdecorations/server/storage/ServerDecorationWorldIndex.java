package me.justbecause.distantdecorations.server.storage;

import me.justbecause.distantdecorations.DistantDecorations;
import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationProvider;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.api.DecorationRegistry;
import me.justbecause.distantdecorations.server.ServerNetworkManager;
import me.justbecause.distantdecorations.telemetry.TelemetryMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerDecorationWorldIndex {
    public static final int CHUNKS_PER_REGION_AXIS = 32;
    public static final long RESIDENCY_TIMEOUT_MS = 60_000L; // 60 seconds
    public static final int MAX_DIRTY_FLUSH_PER_CYCLE = 10;

    private final ServerLevel level;
    private final Path storageDir;
    private final Map<Long, ServerDecorationRegion> loadedRegions = new ConcurrentHashMap<>();

    public ServerDecorationWorldIndex(ServerLevel level, Path storageDir) {
        this.level = level;
        this.storageDir = storageDir;
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            DistantDecorations.LOGGER.error("Failed to create storage directory for {}", level.dimension().identifier(), e);
        }
    }

    public static long packRegionKey(int regionX, int regionZ) {
        return (((long) regionX) << 32) | (regionZ & 0xFFFFFFFFL);
    }

    public static int chunkToRegionCoord(int chunkCoord) {
        return chunkCoord >> 5; // divide by 32
    }

    public ServerLevel getLevel() {
        return level;
    }

    public ServerDecorationRegion getOrCreateRegion(int regionX, int regionZ) {
        return loadedRegions.computeIfAbsent(packRegionKey(regionX, regionZ), k -> {
            ServerDecorationRegion loaded = loadRegionFromFile(regionX, regionZ);
            if (loaded != null) {
                TelemetryMetrics.SERVER_REGIONS.incrementAndGet();
                TelemetryMetrics.SERVER_INDEXED_DECORATIONS.addAndGet(loaded.size());
                return loaded;
            }
            ServerDecorationRegion newRegion = new ServerDecorationRegion(regionX, regionZ);
            TelemetryMetrics.SERVER_REGIONS.incrementAndGet();
            return newRegion;
        });
    }

    @Nullable
    public ServerDecorationRegion getRegion(int regionX, int regionZ) {
        return loadedRegions.get(packRegionKey(regionX, regionZ));
    }

    public Collection<ServerDecorationRegion> getLoadedRegions() {
        return Collections.unmodifiableCollection(loadedRegions.values());
    }

    @Nullable
    public DecorationRecord publish(BlockPos pos, @Nullable BlockEntity blockEntity) {
        if (blockEntity == null) {
            blockEntity = level.getBlockEntity(pos);
        }
        if (blockEntity == null) {
            return null;
        }

        DecorationProvider<?> provider = DecorationRegistry.findProvider(blockEntity);
        if (provider == null) {
            return null;
        }

        return publishTyped(provider, pos, blockEntity);
    }

    @SuppressWarnings("unchecked")
    private <T> DecorationRecord publishTyped(DecorationProvider<T> provider, BlockPos pos, BlockEntity be) {
        T data = provider.capture(level, pos, be);
        if (data == null) {
            return null;
        }

        AABB bounds = provider.calculateBounds(level, pos, data);
        byte[] payload = provider.type().toBytes(data);

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        int rx = chunkToRegionCoord(chunkX);
        int rz = chunkToRegionCoord(chunkZ);

        ServerDecorationRegion region = getOrCreateRegion(rx, rz);
        DecorationId id = new DecorationId(provider.type().id(), level.dimension(), pos);

        // Change detection: If unchanged, NO-OP!
        DecorationRecord existing = region.getRecord(id);
        if (existing != null && existing.bounds().equals(bounds) && Arrays.equals(existing.payload(), payload)) {
            return existing;
        }

        long revision = region.incrementRevision();
        DecorationRecord record = new DecorationRecord(id, bounds, revision, payload);
        region.addOrUpdate(record);

        TelemetryMetrics.SERVER_ADDS.incrementAndGet();
        TelemetryMetrics.SERVER_INDEXED_DECORATIONS.set(getTotalIndexedDecorations());

        // Notify network manager
        ServerNetworkManager.getInstance().broadcastDelta(level.dimension(), rx, rz, region.revision(), List.of(record), Collections.emptyList());

        return record;
    }

    public boolean remove(BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        int rx = chunkToRegionCoord(chunkX);
        int rz = chunkToRegionCoord(chunkZ);

        ServerDecorationRegion region = getRegion(rx, rz);
        if (region == null) {
            return false;
        }

        List<DecorationRecord> inChunk = region.getDecorationsInChunk(chunkX, chunkZ);
        List<DecorationId> toRemove = new ArrayList<>();
        for (DecorationRecord record : inChunk) {
            if (record.id().anchor().equals(pos)) {
                toRemove.add(record.id());
            }
        }

        if (toRemove.isEmpty()) {
            return false;
        }

        region.incrementRevision();
        for (DecorationId id : toRemove) {
            region.remove(id);
            TelemetryMetrics.SERVER_REMOVES.incrementAndGet();
        }
        TelemetryMetrics.SERVER_INDEXED_DECORATIONS.set(getTotalIndexedDecorations());

        ServerNetworkManager.getInstance().broadcastDelta(level.dimension(), rx, rz, region.revision(), Collections.emptyList(), toRemove);
        return true;
    }

    public void reconcileChunk(LevelChunk chunk) {
        TelemetryMetrics.SERVER_CHUNK_RECON_SCANS.incrementAndGet();

        ChunkPos chunkPos = chunk.getPos();
        int rx = chunkToRegionCoord(chunkPos.x());
        int rz = chunkToRegionCoord(chunkPos.z());
        ServerDecorationRegion region = getOrCreateRegion(rx, rz);

        Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();
        Set<BlockPos> presentSupportedPositions = new HashSet<>();

        for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockEntity be = entry.getValue();
            DecorationProvider<?> provider = DecorationRegistry.findProvider(be);
            if (provider != null) {
                presentSupportedPositions.add(pos);
                publish(pos, be);
            }
        }

        // Check if any indexed decoration in this chunk no longer has a matching BE
        List<DecorationRecord> existingInChunk = region.getDecorationsInChunk(chunkPos.x(), chunkPos.z());
        List<DecorationId> removed = new ArrayList<>();
        for (DecorationRecord existing : existingInChunk) {
            if (!presentSupportedPositions.contains(existing.id().anchor())) {
                removed.add(existing.id());
            }
        }

        if (!removed.isEmpty()) {
            region.incrementRevision();
            for (DecorationId id : removed) {
                region.remove(id);
                TelemetryMetrics.SERVER_REMOVES.incrementAndGet();
            }
            TelemetryMetrics.SERVER_INDEXED_DECORATIONS.set(getTotalIndexedDecorations());
            ServerNetworkManager.getInstance().broadcastDelta(level.dimension(), rx, rz, region.revision(), Collections.emptyList(), removed);
        }
    }

    public void tick(Set<Long> activeSubscribedRegions) {
        long now = System.currentTimeMillis();
        int flushedCount = 0;

        Iterator<Map.Entry<Long, ServerDecorationRegion>> iterator = loadedRegions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, ServerDecorationRegion> entry = iterator.next();
            long key = entry.getKey();
            ServerDecorationRegion region = entry.getValue();

            // 1. Periodic dirty flushing
            if (region.isDirty() && flushedCount < MAX_DIRTY_FLUSH_PER_CYCLE) {
                saveRegionToFile(region);
                flushedCount++;
            }

            // 2. Region residency eviction
            boolean isSubscribed = activeSubscribedRegions.contains(key);
            if (!isSubscribed && (now - region.getLastAccessTime()) > RESIDENCY_TIMEOUT_MS) {
                if (region.isDirty()) {
                    saveRegionToFile(region);
                }
                iterator.remove();
                TelemetryMetrics.SERVER_REGIONS.decrementAndGet();
                TelemetryMetrics.SERVER_INDEXED_DECORATIONS.addAndGet(-region.size());
            }
        }
    }

    public int getTotalIndexedDecorations() {
        int total = 0;
        for (ServerDecorationRegion region : loadedRegions.values()) {
            total += region.size();
        }
        return total;
    }

    private Path getRegionFilePath(int rx, int rz) {
        return storageDir.resolve("r." + rx + "." + rz + ".dat");
    }

    @Nullable
    private ServerDecorationRegion loadRegionFromFile(int rx, int rz) {
        Path path = getRegionFilePath(rx, rz);
        if (!Files.exists(path)) {
            return null;
        }
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            return ServerDecorationRegion.readFromStream(dis);
        } catch (Exception e) {
            DistantDecorations.LOGGER.error("Failed to load region file {} for {}", path, level.dimension().identifier(), e);
            return null;
        }
    }

    public void saveRegionToFile(ServerDecorationRegion region) {
        if (!region.isDirty()) {
            return;
        }
        Path path = getRegionFilePath(region.regionX(), region.regionZ());
        Path tempPath = storageDir.resolve("r." + region.regionX() + "." + region.regionZ() + ".dat.tmp");
        try {
            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tempPath)))) {
                region.writeToStream(dos);
            }
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            region.markClean();
        } catch (Exception e) {
            DistantDecorations.LOGGER.error("Failed to save region file {} for {}", path, level.dimension().identifier(), e);
        }
    }

    public void saveAll() {
        for (ServerDecorationRegion region : loadedRegions.values()) {
            saveRegionToFile(region);
        }
    }

    public void close() {
        saveAll();
        loadedRegions.clear();
    }
}

