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
import net.minecraft.IdentifierException;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ServerDecorationWorldIndex {
    public static final int CHUNKS_PER_REGION_AXIS = 32;
    public static final long RESIDENCY_TIMEOUT_MS = 60_000L; // 60 seconds
    public static final int MAX_DIRTY_FLUSH_PER_CYCLE = 10;

    public enum RegionLoadStatus {
        SUCCESS,
        MISSING,
        QUARANTINED,
        QUARANTINE_FAILED,
        READ_ERROR
    }

    public record RegionLoadResult(RegionLoadStatus status, @Nullable ServerDecorationRegion region, @Nullable Throwable cause) {
        public static RegionLoadResult success(ServerDecorationRegion region) {
            return new RegionLoadResult(RegionLoadStatus.SUCCESS, region, null);
        }

        public static RegionLoadResult missing() {
            return new RegionLoadResult(RegionLoadStatus.MISSING, null, null);
        }

        public static RegionLoadResult quarantined(Throwable cause) {
            return new RegionLoadResult(RegionLoadStatus.QUARANTINED, null, cause);
        }

        public static RegionLoadResult quarantineFailed(Throwable cause) {
            return new RegionLoadResult(RegionLoadStatus.QUARANTINE_FAILED, null, cause);
        }

        public static RegionLoadResult readError(Throwable cause) {
            return new RegionLoadResult(RegionLoadStatus.READ_ERROR, null, cause);
        }
    }

    public static class RegionStorageException extends RuntimeException {
        public RegionStorageException(String message) {
            super(message);
        }

        public RegionStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final ServerLevel level;
    private final Path storageDir;
    private final Map<Long, ServerDecorationRegion> loadedRegions = new ConcurrentHashMap<>();
    private final Set<Long> blockedRegions = ConcurrentHashMap.newKeySet();

    public ServerDecorationWorldIndex(ServerLevel level, Path storageDir) {
        this.level = level;
        this.storageDir = storageDir;
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            DistantDecorations.LOGGER.error("Failed to create storage directory for {}", level != null ? level.dimension().identifier() : "test", e);
        }
    }

    public static long packRegionKey(int regionX, int regionZ) {
        return (((long) regionX) << 32) | (regionZ & 0xFFFFFFFFL);
    }

    public static int chunkToRegionCoord(int chunkCoord) {
        return chunkCoord >> 5; // divide by 32
    }

    public static final int MAX_PAYLOAD_BYTES = DecorationRecord.MAX_PAYLOAD_BYTES;
    private final AtomicLong indexedDecorationCount = new AtomicLong(0);

    public ServerLevel getLevel() {
        return level;
    }

    public Path getStorageDir() {
        return storageDir;
    }

    private String getLevelIdentifier() {
        return level != null ? level.dimension().identifier().toString() : "test";
    }

    public boolean isRegionBlocked(int regionX, int regionZ) {
        return blockedRegions.contains(packRegionKey(regionX, regionZ));
    }

    public void blockRegionForTesting(int regionX, int regionZ) {
        blockedRegions.add(packRegionKey(regionX, regionZ));
    }

    public void unblockRegion(int regionX, int regionZ) {
        blockedRegions.remove(packRegionKey(regionX, regionZ));
    }

    public ServerDecorationRegion getOrCreateRegion(int regionX, int regionZ) {
        long key = packRegionKey(regionX, regionZ);
        if (blockedRegions.contains(key)) {
            throw new RegionStorageException("Region [" + regionX + ", " + regionZ + "] is blocked due to prior quarantine/read failure");
        }
        return loadedRegions.computeIfAbsent(key, k -> {
            RegionLoadResult result = loadRegionFromFileWithResult(regionX, regionZ);
            switch (result.status()) {
                case SUCCESS -> {
                    ServerDecorationRegion loaded = result.region();
                    TelemetryMetrics.SERVER_REGIONS.incrementAndGet();
                    indexedDecorationCount.addAndGet(loaded.size());
                    TelemetryMetrics.SERVER_INDEXED_DECORATIONS.set(indexedDecorationCount.get());
                    return loaded;
                }
                case MISSING -> {
                    ServerDecorationRegion newRegion = new ServerDecorationRegion(regionX, regionZ);
                    TelemetryMetrics.SERVER_REGIONS.incrementAndGet();
                    return newRegion;
                }
                case QUARANTINED -> {
                    ServerDecorationRegion newRegion = new ServerDecorationRegion(regionX, regionZ);
                    TelemetryMetrics.SERVER_REGIONS.incrementAndGet();
                    return newRegion;
                }
                case QUARANTINE_FAILED -> {
                    blockedRegions.add(key);
                    throw new RegionStorageException("Cannot initialize or replace region [" + regionX + ", " + regionZ + "]: corrupt file remains on disk because quarantine failed", result.cause());
                }
                case READ_ERROR -> {
                    blockedRegions.add(key);
                    throw new RegionStorageException("Cannot load region [" + regionX + ", " + regionZ + "]: read or access error", result.cause());
                }
                default -> throw new IllegalStateException("Unhandled region load status: " + result.status());
            }
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

        if (payload != null && payload.length > MAX_PAYLOAD_BYTES) {
            DistantDecorations.LOGGER.warn("Rejected oversized decoration payload ({} bytes > {} max) for type {} at {}", payload.length, MAX_PAYLOAD_BYTES, provider.type().id(), pos);
            return null;
        }

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        int rx = chunkToRegionCoord(chunkX);
        int rz = chunkToRegionCoord(chunkZ);

        ServerDecorationRegion region;
        try {
            region = getOrCreateRegion(rx, rz);
        } catch (RegionStorageException e) {
            DistantDecorations.LOGGER.error("Cannot publish decoration at {} because region [{}, {}] is blocked: {}", pos, rx, rz, e.getMessage());
            return null;
        }
        DecorationId id = new DecorationId(provider.type().id(), level.dimension(), pos);

        // Change detection: If unchanged, NO-OP!
        DecorationRecord existing = region.getRecord(id);
        if (existing != null && existing.bounds().equals(bounds) && Arrays.equals(existing.payload(), payload)) {
            return existing;
        }

        long revision = region.incrementRevision();
        DecorationRecord record = new DecorationRecord(id, bounds, revision, payload);
        region.addOrUpdate(record);

        if (existing == null) {
            indexedDecorationCount.incrementAndGet();
        }
        TelemetryMetrics.SERVER_ADDS.incrementAndGet();
        TelemetryMetrics.SERVER_INDEXED_DECORATIONS.set(indexedDecorationCount.get());

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
        indexedDecorationCount.addAndGet(-toRemove.size());
        TelemetryMetrics.SERVER_INDEXED_DECORATIONS.set(indexedDecorationCount.get());

        ServerNetworkManager.getInstance().broadcastDelta(level.dimension(), rx, rz, region.revision(), Collections.emptyList(), toRemove);
        return true;
    }

    public void reconcileChunk(LevelChunk chunk) {
        TelemetryMetrics.SERVER_CHUNK_RECON_SCANS.incrementAndGet();

        ChunkPos chunkPos = chunk.getPos();
        int rx = chunkToRegionCoord(chunkPos.x());
        int rz = chunkToRegionCoord(chunkPos.z());
        ServerDecorationRegion region;
        try {
            region = getOrCreateRegion(rx, rz);
        } catch (RegionStorageException e) {
            DistantDecorations.LOGGER.error("Cannot reconcile chunk {} because region [{}, {}] is blocked: {}", chunkPos, rx, rz, e.getMessage());
            return;
        }

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
            indexedDecorationCount.addAndGet(-removed.size());
            TelemetryMetrics.SERVER_INDEXED_DECORATIONS.set(indexedDecorationCount.get());
            ServerNetworkManager.getInstance().broadcastDelta(level.dimension(), rx, rz, region.revision(), Collections.emptyList(), removed);
        }
    }

    public static final int MAINTENANCE_INTERVAL_TICKS = 100; // 5.0 seconds at 20 TPS
    private int maintenanceTicks = 0;

    public void tick(Set<Long> activeSubscribedRegions) {
        if (++maintenanceTicks < MAINTENANCE_INTERVAL_TICKS) {
            return;
        }
        maintenanceTicks = 0;
        performMaintenance(activeSubscribedRegions);
    }

    public void performMaintenance(Set<Long> activeSubscribedRegions) {
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
                    boolean saved = saveRegionToFile(region);
                    if (!saved) {
                        DistantDecorations.LOGGER.warn("Retaining dirty region [{}, {}] in memory because save failed during maintenance eviction", region.regionX(), region.regionZ());
                        continue;
                    }
                }
                iterator.remove();
                indexedDecorationCount.addAndGet(-region.size());
                TelemetryMetrics.SERVER_REGIONS.decrementAndGet();
                TelemetryMetrics.SERVER_INDEXED_DECORATIONS.set(indexedDecorationCount.get());
            }
        }
    }

    public int getTotalIndexedDecorations() {
        return (int) indexedDecorationCount.get();
    }

    private Path getRegionFilePath(int rx, int rz) {
        return storageDir.resolve("r." + rx + "." + rz + ".dat");
    }

    protected InputStream openInputStream(Path path) throws IOException {
        return Files.newInputStream(path);
    }

    protected void moveFileToQuarantine(Path source, Path target) throws IOException {
        Files.move(source, target);
    }

    public RegionLoadResult loadRegionFromFileWithResult(int rx, int rz) {
        Path path = getRegionFilePath(rx, rz);
        InputStream in;
        try {
            in = openInputStream(path);
        } catch (NoSuchFileException e) {
            return RegionLoadResult.missing();
        } catch (AccessDeniedException e) {
            DistantDecorations.LOGGER.error("Access denied opening region file {} for {}: {}", path, getLevelIdentifier(), e.getMessage());
            blockedRegions.add(packRegionKey(rx, rz));
            return RegionLoadResult.readError(e);
        } catch (IOException e) {
            if (Files.notExists(path)) {
                return RegionLoadResult.missing();
            }
            DistantDecorations.LOGGER.error("I/O error opening region file {} for {}: {}", path, getLevelIdentifier(), e.getMessage());
            blockedRegions.add(packRegionKey(rx, rz));
            return RegionLoadResult.readError(e);
        } catch (SecurityException e) {
            DistantDecorations.LOGGER.error("Security exception opening region file {} for {}: {}", path, getLevelIdentifier(), e.getMessage());
            blockedRegions.add(packRegionKey(rx, rz));
            return RegionLoadResult.readError(e);
        }

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(in))) {
            ServerDecorationRegion loaded = ServerDecorationRegion.readFromStream(dis);
            return RegionLoadResult.success(loaded);
        } catch (EOFException | UTFDataFormatException | IdentifierException e) {
            return handleDiagnosedCorruption(rx, rz, path, e);
        } catch (IOException e) {
            if (isDiagnosedCorruption(e)) {
                return handleDiagnosedCorruption(rx, rz, path, e);
            } else {
                DistantDecorations.LOGGER.error("Read/I/O error parsing region file {} for {}: {}", path, getLevelIdentifier(), e.getMessage());
                blockedRegions.add(packRegionKey(rx, rz));
                return RegionLoadResult.readError(e);
            }
        }
    }

    private boolean isDiagnosedCorruption(IOException e) {
        if (e.getCause() instanceof IdentifierException) {
            return true;
        }
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        return msg.contains("Invalid region file magic")
            || msg.contains("Unsupported region format version")
            || msg.contains("Invalid or corrupted decoration payload length")
            || msg.contains("Malformed stored");
    }

    private RegionLoadResult handleDiagnosedCorruption(int rx, int rz, Path path, Throwable cause) {
        DistantDecorations.LOGGER.error("Diagnosed corrupt region file {} for {}: {}", path, getLevelIdentifier(), cause.getMessage());
        Path corruptPath = storageDir.resolve("r." + rx + "." + rz + ".dat.corrupt." + System.currentTimeMillis() + "." + UUID.randomUUID());
        try {
            moveFileToQuarantine(path, corruptPath);
            DistantDecorations.LOGGER.warn("Quarantined corrupt region file {} to {}", path, corruptPath);
            return RegionLoadResult.quarantined(cause);
        } catch (IOException moveEx) {
            DistantDecorations.LOGGER.error("Failed to quarantine corrupt region file {}: cannot move to {}", path, corruptPath, moveEx);
            blockedRegions.add(packRegionKey(rx, rz));
            return RegionLoadResult.quarantineFailed(moveEx);
        }
    }

    @Nullable
    public ServerDecorationRegion loadRegionFromFile(int rx, int rz) {
        RegionLoadResult result = loadRegionFromFileWithResult(rx, rz);
        switch (result.status()) {
            case SUCCESS -> {
                return result.region();
            }
            case MISSING, QUARANTINED -> {
                return null;
            }
            case READ_ERROR, QUARANTINE_FAILED -> {
                blockedRegions.add(packRegionKey(rx, rz));
                throw new RegionStorageException("Cannot load region [" + rx + ", " + rz + "]: " + result.status(), result.cause());
            }
            default -> throw new IllegalStateException("Unhandled load status: " + result.status());
        }
    }

    public boolean saveRegionToFile(ServerDecorationRegion region) {
        if (!region.isDirty()) {
            return true;
        }
        long key = packRegionKey(region.regionX(), region.regionZ());
        if (blockedRegions.contains(key)) {
            DistantDecorations.LOGGER.error("Refusing to save region [{}, {}]: region is blocked due to load/quarantine failure",
                region.regionX(), region.regionZ());
            return false;
        }
        Path path = getRegionFilePath(region.regionX(), region.regionZ());
        Path tempPath = storageDir.resolve("r." + region.regionX() + "." + region.regionZ() + ".dat.tmp");
        try {
            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tempPath)))) {
                region.writeToStream(dos);
            }
            try {
                Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException moveEx) {
                Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
            }
            region.markClean();
            return true;
        } catch (Exception e) {
            DistantDecorations.LOGGER.error("Failed to save region file {} for {}", path, level != null ? level.dimension().identifier() : "test", e);
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {}
            return false;
        }
    }

    public boolean saveAll() {
        boolean allSuccess = true;
        for (ServerDecorationRegion region : loadedRegions.values()) {
            if (!saveRegionToFile(region)) {
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    public boolean close() {
        boolean saved = saveAll();
        if (saved) {
            loadedRegions.clear();
        } else {
            DistantDecorations.LOGGER.error("close() failed to persist all regions cleanly for {}", level != null ? level.dimension().identifier() : "test");
        }
        return saved;
    }
}

