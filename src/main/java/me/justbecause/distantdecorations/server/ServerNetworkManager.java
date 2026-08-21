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

public final class ServerNetworkManager {
    private static final ServerNetworkManager INSTANCE = new ServerNetworkManager();

    public static final int PROTOCOL_VERSION = 1;
    public static final int DEFAULT_MAX_RADIUS_CHUNKS = 128; // 4 regions
    public static final int MAX_SNAPSHOTS_PER_TICK = 2;
    public static final int MAX_RECORDS_PER_SNAPSHOT = 500;

    private static final class PlayerSubscription {
        final ServerPlayer player;
        int centerChunkX;
        int centerChunkZ;
        int radiusChunks = 64;
        final Set<Long> activeRegions = ConcurrentHashMap.newKeySet();
        final List<Long> pendingSnapshotQueue = Collections.synchronizedList(new ArrayList<>());
        int snapshotsSentThisTick = 0;

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
        sub.radiusChunks = Math.min(hello.requestedRadiusChunks(), DEFAULT_MAX_RADIUS_CHUNKS);
        updateSubscriptions(player, player.getBlockX() >> 4, player.getBlockZ() >> 4, sub.radiusChunks);
    }

    public void handleSubscriptionUpdate(ServerPlayer player, C2SSubscriptionUpdate update) {
        PlayerSubscription sub = subscriptions.computeIfAbsent(player.getUUID(), k -> new PlayerSubscription(player));
        sub.radiusChunks = Math.min(update.requestedRadiusChunks(), DEFAULT_MAX_RADIUS_CHUNKS);
        updateSubscriptions(player, update.centerChunkX(), update.centerChunkZ(), sub.radiusChunks);
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

        synchronized (sub.pendingSnapshotQueue) {
            sub.pendingSnapshotQueue.clear();
            sub.pendingSnapshotQueue.addAll(newRegionsToStream);
        }
    }

    public void tick(ServerLevel level) {
        for (PlayerSubscription sub : subscriptions.values()) {
            if (sub.player.level() != level || !sub.player.isAlive()) {
                continue;
            }

            sub.snapshotsSentThisTick = 0;
            ServerDecorationWorldIndex index = ServerDecorationManager.getInstance().getIndex(level);
            if (index == null) {
                continue;
            }

            synchronized (sub.pendingSnapshotQueue) {
                while (!sub.pendingSnapshotQueue.isEmpty() && sub.snapshotsSentThisTick < MAX_SNAPSHOTS_PER_TICK) {
                    long key = sub.pendingSnapshotQueue.remove(0);
                    int rx = (int) (key >> 32);
                    int rz = (int) key;

                    ServerDecorationRegion region = index.getOrCreateRegion(rx, rz);
                    sub.activeRegions.add(key);

                    List<DecorationRecord> records = new ArrayList<>(region.getAllRecords());
                    // If records exceed MAX_RECORDS_PER_SNAPSHOT, chunk them
                    if (records.size() <= MAX_RECORDS_PER_SNAPSHOT) {
                        if (ServerPlayNetworking.canSend(sub.player, S2CRegionSnapshot.TYPE)) {
                            ServerPlayNetworking.send(sub.player, new S2CRegionSnapshot(rx, rz, region.revision(), records));
                        }
                    } else {
                        for (int i = 0; i < records.size(); i += MAX_RECORDS_PER_SNAPSHOT) {
                            List<DecorationRecord> chunkRecords = records.subList(i, Math.min(records.size(), i + MAX_RECORDS_PER_SNAPSHOT));
                            if (ServerPlayNetworking.canSend(sub.player, S2CRegionSnapshot.TYPE)) {
                                ServerPlayNetworking.send(sub.player, new S2CRegionSnapshot(rx, rz, region.revision(), chunkRecords));
                            }
                        }
                    }

                    sub.snapshotsSentThisTick++;
                    TelemetryMetrics.SERVER_SNAPSHOTS_SENT.incrementAndGet();
                }
            }
        }
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
