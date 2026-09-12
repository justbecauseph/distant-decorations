package me.justbecause.distantdecorations.server;

import io.netty.buffer.Unpooled;
import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.network.c2s.C2SClientHello;
import me.justbecause.distantdecorations.network.c2s.C2SSubscriptionUpdate;
import me.justbecause.distantdecorations.network.s2c.S2CRegionDelta;
import me.justbecause.distantdecorations.network.s2c.S2CRegionSnapshot;
import me.justbecause.distantdecorations.network.s2c.S2CRegionUnload;
import me.justbecause.distantdecorations.server.storage.ServerDecorationRegion;
import me.justbecause.distantdecorations.server.storage.ServerDecorationWorldIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ServerStorageAndNetworkTest {

    @Test
    public void testServerDecorationRegionStreamRoundTrip() throws Exception {
        ServerDecorationRegion region = new ServerDecorationRegion(2, -3);

        Identifier type = Identifier.fromNamespaceAndPath("test", "painting");
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        BlockPos pos = new BlockPos(2 * 512 + 10, 64, -3 * 512 + 20);

        DecorationId id = new DecorationId(type, dim, pos);
        AABB bounds = new AABB(pos.getX(), 64.0, pos.getZ(), pos.getX() + 2, 66.0, pos.getZ() + 0.1);
        DecorationRecord record = new DecorationRecord(id, bounds, 100L, new byte[]{1, 2, 3});

        region.addOrUpdate(record);
        assertEquals(1, region.size());
        assertTrue(region.isDirty());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        region.writeToStream(dos);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        ServerDecorationRegion deserialized = ServerDecorationRegion.readFromStream(dis);

        assertEquals(region.regionX(), deserialized.regionX());
        assertEquals(region.regionZ(), deserialized.regionZ());
        assertEquals(region.revision(), deserialized.revision());
        assertEquals(1, deserialized.size());

        List<DecorationRecord> inChunk = deserialized.getDecorationsInChunk(pos.getX() >> 4, pos.getZ() >> 4);
        assertEquals(1, inChunk.size());
        assertEquals(record, inChunk.get(0));
    }

    @Test
    public void testC2SClientHelloPacketRoundTrip() {
        C2SClientHello hello = new C2SClientHello(
            1,
            List.of(Identifier.fromNamespaceAndPath("test", "type1"), Identifier.fromNamespaceAndPath("test", "type2")),
            128
        );

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        C2SClientHello.CODEC.encode(buf, hello);

        C2SClientHello decoded = C2SClientHello.CODEC.decode(buf);
        buf.release();

        assertEquals(hello.protocolVersion(), decoded.protocolVersion());
        assertEquals(hello.supportedTypes(), decoded.supportedTypes());
        assertEquals(hello.requestedRadiusChunks(), decoded.requestedRadiusChunks());
    }

    @Test
    public void testC2SSubscriptionUpdatePacketRoundTrip() {
        C2SSubscriptionUpdate update = new C2SSubscriptionUpdate(50, -100, 64);

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        C2SSubscriptionUpdate.CODEC.encode(buf, update);

        C2SSubscriptionUpdate decoded = C2SSubscriptionUpdate.CODEC.decode(buf);
        buf.release();

        assertEquals(update.centerChunkX(), decoded.centerChunkX());
        assertEquals(update.centerChunkZ(), decoded.centerChunkZ());
        assertEquals(update.requestedRadiusChunks(), decoded.requestedRadiusChunks());
    }

    @Test
    public void testS2CRegionSnapshotPacketRoundTrip() {
        Identifier type = Identifier.fromNamespaceAndPath("test", "frame");
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        DecorationId id = new DecorationId(type, dim, new BlockPos(100, 64, 200));
        DecorationRecord record = new DecorationRecord(id, new AABB(100, 64, 200, 102, 66, 200.1), 5L, new byte[]{9, 8, 7});

        S2CRegionSnapshot snapshot = new S2CRegionSnapshot(1, 2, 5L, 0, 1, List.of(record));

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        S2CRegionSnapshot.CODEC.encode(buf, snapshot);

        S2CRegionSnapshot decoded = S2CRegionSnapshot.CODEC.decode(buf);
        buf.release();

        assertEquals(snapshot.regionX(), decoded.regionX());
        assertEquals(snapshot.regionZ(), decoded.regionZ());
        assertEquals(snapshot.revision(), decoded.revision());
        assertEquals(snapshot.partIndex(), decoded.partIndex());
        assertEquals(snapshot.partCount(), decoded.partCount());
        assertEquals(snapshot.records(), decoded.records());
    }

    @Test
    public void testS2CRegionDeltaPacketRoundTrip() {
        Identifier type = Identifier.fromNamespaceAndPath("test", "frame");
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        DecorationId id1 = new DecorationId(type, dim, new BlockPos(100, 64, 200));
        DecorationRecord record = new DecorationRecord(id1, new AABB(100, 64, 200, 102, 66, 200.1), 10L, new byte[]{4, 5});
        DecorationId id2 = new DecorationId(type, dim, new BlockPos(200, 64, 300));

        S2CRegionDelta delta = new S2CRegionDelta(1, 2, 10L, List.of(record), List.of(id2));

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        S2CRegionDelta.CODEC.encode(buf, delta);

        S2CRegionDelta decoded = S2CRegionDelta.CODEC.decode(buf);
        buf.release();

        assertEquals(delta.regionX(), decoded.regionX());
        assertEquals(delta.regionZ(), decoded.regionZ());
        assertEquals(delta.revision(), decoded.revision());
        assertEquals(delta.additionsAndUpdates(), decoded.additionsAndUpdates());
        assertEquals(delta.removals(), decoded.removals());
    }

    @Test
    public void testS2CRegionUnloadPacketRoundTrip() {
        S2CRegionUnload unload = new S2CRegionUnload(-4, 8);

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        S2CRegionUnload.CODEC.encode(buf, unload);

        S2CRegionUnload decoded = S2CRegionUnload.CODEC.decode(buf);
        buf.release();

        assertEquals(unload.regionX(), decoded.regionX());
        assertEquals(unload.regionZ(), decoded.regionZ());
    }

    @Test
    public void testMaintenanceIntervalDoesNotFlushEveryTick() throws Exception {
        Path tempDir = Files.createTempDirectory("dd_test_maintenance");
        try {
            ServerDecorationWorldIndex index = new ServerDecorationWorldIndex(null, tempDir);
            ServerDecorationRegion r = index.getOrCreateRegion(0, 0);

            Identifier type = Identifier.fromNamespaceAndPath("test", "painting");
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
            DecorationRecord rec = new DecorationRecord(
                new DecorationId(type, dim, new BlockPos(10, 64, 10)),
                new AABB(10, 64, 10, 11, 65, 10.1),
                1L,
                new byte[]{1, 2}
            );
            r.addOrUpdate(rec);
            assertTrue(r.isDirty(), "Region must be dirty after record insertion");

            Path regionFile = tempDir.resolve("r.0.0.dat");
            assertFalse(Files.exists(regionFile), "Region file must not exist before flush");

            // Ticks 1 to 99: Periodic maintenance must NOT run
            for (int t = 1; t <= 99; t++) {
                index.tick(Collections.emptySet());
                assertTrue(r.isDirty(), "Region must remain dirty on tick " + t);
                assertFalse(Files.exists(regionFile), "File must not be written on tick " + t);
            }

            // Tick 100: Maintenance interval fires, dirty region is flushed to disk!
            index.tick(Collections.emptySet());
            assertFalse(r.isDirty(), "Region must be clean after tick 100 flush");
            assertTrue(Files.exists(regionFile), "Region file must now exist on disk");

            // Verify disk content round-trip
            ServerDecorationWorldIndex reloadedIndex = new ServerDecorationWorldIndex(null, tempDir);
            ServerDecorationRegion reloadedRegion = reloadedIndex.getOrCreateRegion(0, 0);
            assertEquals(1, reloadedRegion.size());
            assertEquals(1, reloadedIndex.getTotalIndexedDecorations());
        } finally {
            // Clean up temp directory
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    @Test
    public void testBlockedRegionDoesNotThrowDuringNetworkTickAndPermitsHealthyJobs() throws Exception {
        ServerDecorationManager manager = ServerDecorationManager.getInstance();
        ServerNetworkManager netManager = ServerNetworkManager.getInstance();
        manager.clearForTesting();
        netManager.clearForTesting();

        Path tempDir = Files.createTempDirectory("dd_test_network_containment");
        try {
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("test", "network_containment_dim"));
            ServerDecorationWorldIndex index = new ServerDecorationWorldIndex(null, tempDir);
            manager.registerIndexForTesting(dim, index);

            // Region (1, 1) is blocked (simulate prior quarantine or read error)
            index.blockRegionForTesting(1, 1);
            long blockedKey = ServerDecorationWorldIndex.packRegionKey(1, 1);

            // Region (2, 2) is healthy and populated
            long healthyKey = ServerDecorationWorldIndex.packRegionKey(2, 2);
            ServerDecorationRegion healthyRegion = index.getOrCreateRegion(2, 2);
            Identifier type = Identifier.fromNamespaceAndPath("test", "painting");
            BlockPos pos = new BlockPos(2 * 512 + 10, 64, 2 * 512 + 10);
            DecorationId id = new DecorationId(type, dim, pos);
            AABB bounds = new AABB(pos.getX(), 64.0, pos.getZ(), pos.getX() + 1, 65.0, pos.getZ() + 0.1);
            healthyRegion.addOrUpdate(new DecorationRecord(id, bounds, 1L, new byte[]{1, 2, 3}));

            UUID playerId = UUID.randomUUID();
            netManager.registerTestSubscription(playerId, dim, true);

            // Enqueue both the blocked region and the healthy region
            netManager.enqueuePendingJobForTesting(playerId, blockedKey);
            netManager.enqueuePendingJobForTesting(playerId, healthyKey);

            // Tick 1: Network tick MUST NOT throw RegionStorageException!
            assertDoesNotThrow(() -> netManager.tick(dim), "Network tick must contain RegionStorageException");

            // Assertions for blocked region
            assertFalse(netManager.isRegionSyncedForTesting(playerId, blockedKey), "Blocked region must NOT be marked synced");
            assertFalse(netManager.isRegionStreamingForTesting(playerId, blockedKey), "Blocked region must NOT be marked streaming");
            assertEquals(1, netManager.getFailedAttemptsForTesting(playerId, blockedKey), "Blocked region should have 1 failed attempt recorded");
            assertTrue(netManager.isRegionPendingForTesting(playerId, blockedKey), "Blocked region should be deferred back to pending jobs");

            // Assertions for healthy region: should have progressed to synced
            assertTrue(netManager.isRegionSyncedForTesting(playerId, healthyKey), "Healthy region should progress to synced in the tick");
            assertFalse(netManager.isRegionPendingForTesting(playerId, healthyKey), "Healthy region should no longer be pending");

            // Tick 2: Blocked region is in retry cooldown (3000ms), so ticking immediately must NOT re-attempt it
            netManager.tick(dim);
            assertEquals(1, netManager.getFailedAttemptsForTesting(playerId, blockedKey), "Cooldown must prevent premature retry");

            // Clear cooldown to simulate backoff expiration and tick 2nd time
            netManager.clearRetryCooldownForTesting(playerId, blockedKey);
            netManager.tick(dim);
            assertEquals(2, netManager.getFailedAttemptsForTesting(playerId, blockedKey), "Attempt 2 should be recorded");
            assertTrue(netManager.isRegionPendingForTesting(playerId, blockedKey));

            // Clear cooldown and tick 3rd time
            netManager.clearRetryCooldownForTesting(playerId, blockedKey);
            netManager.tick(dim);
            assertEquals(3, netManager.getFailedAttemptsForTesting(playerId, blockedKey), "Attempt 3 should be recorded");
            assertTrue(netManager.isRegionPendingForTesting(playerId, blockedKey));

            // Clear cooldown and tick 4th time: exceeds MAX_REGION_JOB_RETRIES (3) -> job is abandoned and not re-enqueued
            netManager.clearRetryCooldownForTesting(playerId, blockedKey);
            netManager.tick(dim);
            assertEquals(4, netManager.getFailedAttemptsForTesting(playerId, blockedKey), "Attempt 4 should record failure");
            assertFalse(netManager.isRegionPendingForTesting(playerId, blockedKey), "Abandoned job must not be re-enqueued after exceeding max retries");
        } finally {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }
}

