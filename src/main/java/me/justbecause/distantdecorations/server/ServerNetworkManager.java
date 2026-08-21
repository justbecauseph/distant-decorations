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
    public static final int MAX_RECORDS_PER_SNAPSHOT = 500;
    public static final int DEFAULT_MAX_BYTES_PER_TICK = 131072; // 128 KiB per tick per player

    private static final class PlayerSubscription {
        final ServerPlayer player;
        int centerChunkX;
        int centerChunkZ;
        int radiusChunks = 64;
        final Set<Long> activeRegions = ConcurrentHashMap.newKeySet();
        final Queue<S2CRegionSnapshot> pendingSnapshotPackets = new ConcurrentLinkedQueue<>();

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
        PlayerSubscription sub = subscriptions.computeIfAbsent(player.getUUID(), k -> new PlayerSubscription(player));
        int requested = hello.requestedRadiusChunks();
        sub.radiusChunks = Math.max(MIN_RADIUS_CHUNKS, Math.min(requested, ABSOLUTE_MAX_RADIUS_CHUNKS));
        updateSubscriptions(player, player.getBlockX() >> 4, player.getBlockZ() >> 4, sub.radiusChunks);
    }

    public void handleSubscriptionUpdate(ServerPlayer player, C2SSubscriptionUpdate update) {
        PlayerSubscription sub = subscriptions.computeIfAbsent(player.getUUID(), k -> new PlayerSubscription(player));
        int requested = update.requestedRadiusChunks();
        sub.radiusChunks = Math.max(MIN_RADIUS_CHUNKS, Math.min(requested, ABSOLUTE_MAX_RADIUS_CHUNKS));
        // Server is strictly authoritative for player location
        int authorativeChunkX = player.getBlockX() >> 4;
        int authorativeChunkZ = player.getBlockZ() >> 4;
        updateSubscriptions(player, authorativeChunkX, authorativeChunkZ, sub.radiusChunks);
    }

    public void updateSubscriptions(ServerPlayer player, int centerChunkX, int centerChunkZ, int radiusChunks) {
        PlayerSubscription sub = subscriptions.get(player.getUUID());
        if (sub == null) {
            return;
        }

        sub.centerChunkX = centerChunkX;
        sub.centerChunkZ = centerChunkZ;
        sub.radiusChunks = radiusChunks;

        int centerRegionX = ServerDecorationWorldIndex.chunkToRegionCoord(centerChunkX);
        int centerRegionZ = ServerDecorationWorldIndex.chunkToRegionCoord(centerChunkZ);
        int regionRadius = Math.max(1, (radiusChunks + 31) / 32);

        Set<Long> targetRegions = new HashSet<>();
        List<Long> newRegionsToStream = new ArrayList<>();

        for (int rx = centerRegionX - regionRadius; rx <= centerRegionX + regionRadius; rx++) {
            for (int rz = centerRegionZ - regionRadius; rz <= centerRegionZ + regionRadius; rz++) {
                long key = ServerDecorationWorldIndex.packRegionKey(rx, rz);
                targetRegions.add(key);
                if (!sub.activeRegions.contains(key)) {
                    newRegionsToStream.add(key);
                }
            }
        }

        // Unload out-of-range regions
        Iterator<Long> activeIter = sub.activeRegions.iterator();
        while (activeIter.hasNext()) {
            long key = activeIter.next();
            if (!targetRegions.contains(key)) {
                activeIter.remove();
                int rx = (int) (key >> 32);
                int rz = (int) key;
                if (ServerPlayNetworking.canSend(player, S2CRegionUnload.TYPE)) {
                    ServerPlayNetworking.send(player, new S2CRegionUnload(rx, rz));
                }
            }
        }

        // Sort new regions nearest-first
        newRegionsToStream.sort(Comparator.comparingDouble(key -> {
            int rx = (int) (key >> 32);
            int rz = (int) (long) key;
            int dx = rx - centerRegionX;
            int dz = rz - centerRegionZ;
            return dx * dx + dz * dz;
        }));

        ServerDecorationWorldIndex index = ServerDecorationManager.getInstance().getIndex((ServerLevel) player.level());
        if (index == null) {
            return;
        }

        sub.pendingSnapshotPackets.clear();
        for (long key : newRegionsToStream) {
            int rx = (int) (key >> 32);
            int rz = (int) key;
            ServerDecorationRegion region = index.getOrCreateRegion(rx, rz);
            sub.activeRegions.add(key);

            List<DecorationRecord> records = new ArrayList<>(region.getAllRecords());
            if (records.isEmpty()) {
                sub.pendingSnapshotPackets.add(new S2CRegionSnapshot(rx, rz, region.revision(), 0, 1, Collections.emptyList()));
            } else {
                int partCount = (records.size() + MAX_RECORDS_PER_SNAPSHOT - 1) / MAX_RECORDS_PER_SNAPSHOT;
                for (int p = 0; p < partCount; p++) {
                    int start = p * MAX_RECORDS_PER_SNAPSHOT;
                    int end = Math.min(records.size(), start + MAX_RECORDS_PER_SNAPSHOT);
                    List<DecorationRecord> partRecords = new ArrayList<>(records.subList(start, end));
                    sub.pendingSnapshotPackets.add(new S2CRegionSnapshot(rx, rz, region.revision(), p, partCount, partRecords));
                }
            }
        }
    }

    public void tick(ServerLevel level) {
        ServerDecorationWorldIndex index = ServerDecorationManager.getInstance().getIndex(level);
        if (index == null) {
            return;
        }

        Set<Long> allActiveRegionsInLevel = new HashSet<>();
        for (PlayerSubscription sub : subscriptions.values()) {
            if (sub.player.level() == level) {
                allActiveRegionsInLevel.addAll(sub.activeRegions);
            }
        }

        // Run index tick for dirty region flushing and eviction
        index.tick(allActiveRegionsInLevel);

        // Process byte-budgeted snapshot streaming for players in this level
        for (PlayerSubscription sub : subscriptions.values()) {
            if (sub.player.level() != level || !sub.player.isAlive()) {
                continue;
            }

            int bytesSentThisTick = 0;
            while (!sub.pendingSnapshotPackets.isEmpty() && bytesSentThisTick < DEFAULT_MAX_BYTES_PER_TICK) {
                S2CRegionSnapshot packet = sub.pendingSnapshotPackets.poll();
                if (packet == null) {
                    break;
                }

                int estimatedBytes = estimatePacketBytes(packet);
                if (ServerPlayNetworking.canSend(sub.player, S2CRegionSnapshot.TYPE)) {
                    ServerPlayNetworking.send(sub.player, packet);
                    TelemetryMetrics.SERVER_SNAPSHOTS_SENT.incrementAndGet();
                    TelemetryMetrics.SERVER_METADATA_BYTES_SENT.addAndGet(estimatedBytes);
                    bytesSentThisTick += estimatedBytes;
                }
            }
        }
    }

    private static int estimatePacketBytes(S2CRegionSnapshot packet) {
        int bytes = 32; // base header (rx, rz, revision, partIndex, partCount, list size)
        for (DecorationRecord r : packet.records()) {
            bytes += 64 + r.payload().length; // id, coordinates, 6 doubles (48 bytes), revision, payload length + array
        }
        return bytes;
    }

    public void broadcastDelta(ResourceKey<Level> dim, int regionX, int regionZ, long revision, List<DecorationRecord> additions, List<DecorationId> removals) {
        long regionKey = ServerDecorationWorldIndex.packRegionKey(regionX, regionZ);
        S2CRegionDelta packet = new S2CRegionDelta(regionX, regionZ, revision, additions, removals);

        for (PlayerSubscription sub : subscriptions.values()) {
            if (sub.player.level().dimension() == dim && sub.activeRegions.contains(regionKey)) {
                if (ServerPlayNetworking.canSend(sub.player, S2CRegionDelta.TYPE)) {
                    ServerPlayNetworking.send(sub.player, packet);
                    TelemetryMetrics.SERVER_DELTAS_SENT.incrementAndGet();
                }
            }
        }
    }
}

