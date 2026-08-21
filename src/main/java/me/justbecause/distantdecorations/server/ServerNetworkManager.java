package me.justbecause.distantdecorations.server;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.network.c2s.C2SClientHello;
import me.justbecause.distantdecorations.network.c2s.C2SSubscriptionUpdate;
import me.justbecause.distantdecorations.network.s2c.S2CRegionDelta;
import me.justbecause.distantdecorations.network.s2c.S2CRegionSnapshot;
import me.justbecause.distantdecorations.network.s2c.S2CRegionUnload;
import me.justbecause.distantdecorations.server.storage.ServerDecorationRegion;
import me.justbecause.distantdecorations.server.storage.ServerDecorationWorldIndex;
import me.justbecause.distantdecorations.telemetry.TelemetryMetrics;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class ServerNetworkManager {
    private static final ServerNetworkManager INSTANCE = new ServerNetworkManager();

    public static final int PROTOCOL_VERSION = 1;
    public static final int MIN_RADIUS_CHUNKS = 16;
    public static final int DEFAULT_MAX_RADIUS_CHUNKS = 128; // 4 regions
    public static final int ABSOLUTE_MAX_RADIUS_CHUNKS = 256;
    public static final int MAX_RECORDS_PER_SNAPSHOT_PART = 400;
    public static final int MAX_BYTES_PER_SNAPSHOT_PART = 32768; // 32 KiB target part size
    public static final int DEFAULT_MAX_BYTES_PER_TICK = 131072; // 128 KiB per tick per player
    public static final int MAX_REGIONS_MATERIALIZED_PER_TICK = 2; // Progressive construction budget (regions)
    public static final int MAX_RECORDS_MATERIALIZED_PER_TICK = 2000; // Progressive construction budget (records)

    private static final class PlayerSubscription {
        final ServerPlayer player;
        int centerChunkX;
        int centerChunkZ;
        int radiusChunks = 64;
        boolean helloAccepted = false;
        Set<Identifier> supportedTypes = null;

        final Set<Long> desiredRegions = ConcurrentHashMap.newKeySet();
        final Set<Long> streamingRegions = ConcurrentHashMap.newKeySet();
        final Set<Long> syncedRegions = ConcurrentHashMap.newKeySet();

        final List<Long> pendingRegionJobs = Collections.synchronizedList(new ArrayList<>());
        final Queue<S2CRegionSnapshot> pendingPackets = new ConcurrentLinkedQueue<>();
        final Map<Long, List<S2CRegionDelta>> bufferedDeltas = new ConcurrentHashMap<>();

        PlayerSubscription(ServerPlayer player) {
            this.player = player;
            this.centerChunkX = player.getBlockX() >> 4;
            this.centerChunkZ = player.getBlockZ() >> 4;
        }
    }

    private final Map<UUID, PlayerSubscription> subscriptions = new ConcurrentHashMap<>();

    private ServerNetworkManager() {}

    public static ServerNetworkManager getInstance() {
        return INSTANCE;
    }

    public void onPlayerJoin(ServerPlayer player) {
        subscriptions.put(player.getUUID(), new PlayerSubscription(player));
    }

    public void onPlayerLeave(ServerPlayer player) {
        subscriptions.remove(player.getUUID());
    }

    public void handleClientHello(ServerPlayer player, C2SClientHello hello) {
        if (hello.protocolVersion() != PROTOCOL_VERSION) {
            me.justbecause.distantdecorations.DistantDecorations.LOGGER.warn(
                "Rejecting client {} with incompatible Distant Decorations protocol version: {} (expected {})",
                player.getName().getString(), hello.protocolVersion(), PROTOCOL_VERSION
            );
            return;
        }

        PlayerSubscription sub = subscriptions.computeIfAbsent(player.getUUID(), k -> new PlayerSubscription(player));
        sub.helloAccepted = true;
        sub.supportedTypes = hello.supportedTypes() != null ? new HashSet<>(hello.supportedTypes()) : Collections.emptySet();
        int requested = hello.requestedRadiusChunks();
        sub.radiusChunks = Math.max(MIN_RADIUS_CHUNKS, Math.min(requested, ABSOLUTE_MAX_RADIUS_CHUNKS));
        updateSubscriptions(player, player.getBlockX() >> 4, player.getBlockZ() >> 4, sub.radiusChunks);
    }

    public void handleSubscriptionUpdate(ServerPlayer player, C2SSubscriptionUpdate update) {
        PlayerSubscription sub = subscriptions.get(player.getUUID());
        if (sub == null || !sub.helloAccepted) {
            return;
        }

        int requested = update.requestedRadiusChunks();
        sub.radiusChunks = Math.max(MIN_RADIUS_CHUNKS, Math.min(requested, ABSOLUTE_MAX_RADIUS_CHUNKS));
        // Server is strictly authoritative for player location
        int authorativeChunkX = player.getBlockX() >> 4;
        int authorativeChunkZ = player.getBlockZ() >> 4;
        updateSubscriptions(player, authorativeChunkX, authorativeChunkZ, sub.radiusChunks);
    }

    public void updateSubscriptions(ServerPlayer player, int centerChunkX, int centerChunkZ, int radiusChunks) {
        PlayerSubscription sub = subscriptions.get(player.getUUID());
        if (sub == null || !sub.helloAccepted) {
            return;
        }

        sub.centerChunkX = centerChunkX;
        sub.centerChunkZ = centerChunkZ;
        sub.radiusChunks = radiusChunks;

        int centerRegionX = ServerDecorationWorldIndex.chunkToRegionCoord(centerChunkX);
        int centerRegionZ = ServerDecorationWorldIndex.chunkToRegionCoord(centerChunkZ);
        int regionRadius = Math.max(1, (radiusChunks + 31) / 32);

        Set<Long> newDesired = new HashSet<>();
        for (int rx = centerRegionX - regionRadius; rx <= centerRegionX + regionRadius; rx++) {
            for (int rz = centerRegionZ - regionRadius; rz <= centerRegionZ + regionRadius; rz++) {
                newDesired.add(ServerDecorationWorldIndex.packRegionKey(rx, rz));
            }
        }

        // 1. Unload out-of-range regions
        Iterator<Long> desiredIter = sub.desiredRegions.iterator();
        while (desiredIter.hasNext()) {
            long key = desiredIter.next();
            if (!newDesired.contains(key)) {
                desiredIter.remove();
                sub.syncedRegions.remove(key);
                sub.streamingRegions.remove(key);
                sub.bufferedDeltas.remove(key);
                sub.pendingRegionJobs.remove(key);
                sub.pendingPackets.removeIf(pkt -> ServerDecorationWorldIndex.packRegionKey(pkt.regionX(), pkt.regionZ()) == key);

                int rx = (int) (key >> 32);
                int rz = (int) key;
                if (ServerPlayNetworking.canSend(player, S2CRegionUnload.TYPE)) {
                    ServerPlayNetworking.send(player, new S2CRegionUnload(rx, rz));
                }
            }
        }

        // 2. Identify newly desired regions
        List<Long> newlyAdded = new ArrayList<>();
        for (long key : newDesired) {
            if (!sub.desiredRegions.contains(key)) {
                newlyAdded.add(key);
                sub.desiredRegions.add(key);
            }
        }

        // 3. Sort newly added regions nearest-first
        newlyAdded.sort(Comparator.comparingDouble(key -> {
            int rx = (int) (key >> 32);
            int rz = (int) (long) key;
            int dx = rx - centerRegionX;
            int dz = rz - centerRegionZ;
            return dx * dx + dz * dz;
        }));

        // 4. Append newly added regions to pending jobs (preserving active streaming & existing jobs)
        sub.pendingRegionJobs.addAll(newlyAdded);
    }

    public void tick(ServerLevel level) {
        ServerDecorationWorldIndex index = ServerDecorationManager.getInstance().getIndex(level);
        if (index == null) {
            return;
        }

        Set<Long> allActiveRegionsInLevel = new HashSet<>();
        for (PlayerSubscription sub : subscriptions.values()) {
            if (sub.player.level() == level && sub.helloAccepted) {
                allActiveRegionsInLevel.addAll(sub.desiredRegions);
            }
        }

        // Run index tick for dirty region flushing and eviction
        index.tick(allActiveRegionsInLevel);

        // Process progressive snapshot materialization and byte-budgeted streaming
        for (PlayerSubscription sub : subscriptions.values()) {
            if (sub.player.level() != level || !sub.player.isAlive() || !sub.helloAccepted) {
                continue;
            }

            // A. Progressive snapshot construction (bounded CPU/disk/record budget)
            int materializedRegions = 0;
            int materializedRecords = 0;
            while (materializedRegions < MAX_REGIONS_MATERIALIZED_PER_TICK && materializedRecords < MAX_RECORDS_MATERIALIZED_PER_TICK && !sub.pendingRegionJobs.isEmpty() && sub.pendingPackets.size() < 16) {
                Long nextKey;
                synchronized (sub.pendingRegionJobs) {
                    nextKey = sub.pendingRegionJobs.isEmpty() ? null : sub.pendingRegionJobs.remove(0);
                }
                if (nextKey == null) {
                    break;
                }

                if (!sub.desiredRegions.contains(nextKey) || sub.syncedRegions.contains(nextKey)) {
                    continue;
                }

                int rx = (int) (nextKey >> 32);
                int rz = (int) (long) nextKey;
                ServerDecorationRegion region = index.getOrCreateRegion(rx, rz);
                sub.streamingRegions.add(nextKey);

                Collection<DecorationRecord> allRecords = region.getAllRecords();
                List<DecorationRecord> filteredRecords = new ArrayList<>();
                for (DecorationRecord rec : allRecords) {
                    if (rec.payload() != null && rec.payload().length > DecorationRecord.MAX_PAYLOAD_BYTES) {
                        continue; // skip oversized provider payloads
                    }
                    if (sub.supportedTypes == null || sub.supportedTypes.contains(rec.id().type())) {
                        filteredRecords.add(rec);
                    }
                }

                List<S2CRegionSnapshot> parts = chunkSnapshot(rx, rz, region.revision(), filteredRecords);
                sub.pendingPackets.addAll(parts);
                materializedRegions++;
                materializedRecords += filteredRecords.size();
            }

            // B. Byte-budgeted packet transmission
            int bytesSentThisTick = 0;
            while (!sub.pendingPackets.isEmpty()) {
                S2CRegionSnapshot packet = sub.pendingPackets.peek();
                if (packet == null) {
                    break;
                }

                int estimatedBytes = estimatePacketBytes(packet);
                if (bytesSentThisTick > 0 && bytesSentThisTick + estimatedBytes > DEFAULT_MAX_BYTES_PER_TICK) {
                    // Hard byte budget reached for this tick
                    break;
                }

                sub.pendingPackets.poll();
                if (ServerPlayNetworking.canSend(sub.player, S2CRegionSnapshot.TYPE)) {
                    ServerPlayNetworking.send(sub.player, packet);
                    TelemetryMetrics.SERVER_SNAPSHOTS_SENT.incrementAndGet();
                    TelemetryMetrics.SERVER_METADATA_BYTES_SENT.addAndGet(estimatedBytes);
                    bytesSentThisTick += estimatedBytes;
                }

                // If this packet completes the snapshot for a region
                if (packet.partIndex() == packet.partCount() - 1) {
                    long key = ServerDecorationWorldIndex.packRegionKey(packet.regionX(), packet.regionZ());
                    sub.streamingRegions.remove(key);
                    sub.syncedRegions.add(key);

                    // Replay any buffered deltas that arrived while this snapshot was streaming
                    List<S2CRegionDelta> buffered = sub.bufferedDeltas.remove(key);
                    if (buffered != null) {
                        for (S2CRegionDelta delta : buffered) {
                            if (ServerPlayNetworking.canSend(sub.player, S2CRegionDelta.TYPE)) {
                                ServerPlayNetworking.send(sub.player, delta);
                                TelemetryMetrics.SERVER_DELTAS_SENT.incrementAndGet();
                            }
                        }
                    }
                }
            }
        }
    }

    private List<S2CRegionSnapshot> chunkSnapshot(int rx, int rz, long revision, List<DecorationRecord> records) {
        if (records.isEmpty()) {
            return List.of(new S2CRegionSnapshot(rx, rz, revision, 0, 1, Collections.emptyList()));
        }

        List<List<DecorationRecord>> parts = new ArrayList<>();
        List<DecorationRecord> currentPart = new ArrayList<>();
        int currentBytes = 0;

        for (DecorationRecord r : records) {
            int recordBytes = 64 + (r.payload() != null ? r.payload().length : 0);
            if (!currentPart.isEmpty() && (currentPart.size() >= MAX_RECORDS_PER_SNAPSHOT_PART || currentBytes + recordBytes > MAX_BYTES_PER_SNAPSHOT_PART)) {
                parts.add(currentPart);
                currentPart = new ArrayList<>();
                currentBytes = 0;
            }
            currentPart.add(r);
            currentBytes += recordBytes;
        }
        if (!currentPart.isEmpty()) {
            parts.add(currentPart);
        }

        int partCount = parts.size();
        List<S2CRegionSnapshot> packets = new ArrayList<>(partCount);
        for (int p = 0; p < partCount; p++) {
            packets.add(new S2CRegionSnapshot(rx, rz, revision, p, partCount, parts.get(p)));
        }
        return packets;
    }

    private static int estimatePacketBytes(S2CRegionSnapshot packet) {
        int bytes = 32; // base header (rx, rz, revision, partIndex, partCount, list size)
        for (DecorationRecord r : packet.records()) {
            bytes += 64 + (r.payload() != null ? r.payload().length : 0);
        }
        return bytes;
    }

    public void broadcastDelta(ResourceKey<Level> dim, int regionX, int regionZ, long revision, List<DecorationRecord> additions, List<DecorationId> removals) {
        long regionKey = ServerDecorationWorldIndex.packRegionKey(regionX, regionZ);

        for (PlayerSubscription sub : subscriptions.values()) {
            if (sub.player.level().dimension() != dim || !sub.helloAccepted || !sub.desiredRegions.contains(regionKey)) {
                continue;
            }

            List<DecorationRecord> filteredAdditions = new ArrayList<>();
            for (DecorationRecord add : additions) {
                if (add.payload() != null && add.payload().length > DecorationRecord.MAX_PAYLOAD_BYTES) {
                    continue;
                }
                if (sub.supportedTypes == null || sub.supportedTypes.contains(add.id().type())) {
                    filteredAdditions.add(add);
                }
            }

            if (filteredAdditions.isEmpty() && removals.isEmpty()) {
                continue;
            }

            S2CRegionDelta deltaPacket = new S2CRegionDelta(regionX, regionZ, revision, filteredAdditions, removals);

            if (sub.syncedRegions.contains(regionKey)) {
                // Region snapshot is fully synced on client, transmit delta immediately
                if (ServerPlayNetworking.canSend(sub.player, S2CRegionDelta.TYPE)) {
                    ServerPlayNetworking.send(sub.player, deltaPacket);
                    TelemetryMetrics.SERVER_DELTAS_SENT.incrementAndGet();
                }
            } else if (sub.streamingRegions.contains(regionKey)) {
                // Snapshot is in-flight! Buffer delta to transmit immediately after final snapshot part
                sub.bufferedDeltas.computeIfAbsent(regionKey, k -> Collections.synchronizedList(new ArrayList<>())).add(deltaPacket);
            }
            // If region is in pendingRegionJobs, it will be materialized with the latest revision when it runs
        }
    }
}


