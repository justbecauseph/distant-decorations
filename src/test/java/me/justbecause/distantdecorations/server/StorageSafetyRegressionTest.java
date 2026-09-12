package me.justbecause.distantdecorations.server;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.server.storage.ServerDecorationRegion;
import me.justbecause.distantdecorations.server.storage.ServerDecorationWorldIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class StorageSafetyRegressionTest {

    @TempDir
    Path tempDir;

    private DecorationRecord createDummyRecord(int x, int z) {
        Identifier type = Identifier.fromNamespaceAndPath("test", "painting");
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        BlockPos pos = new BlockPos(x, 64, z);
        return new DecorationRecord(
            new DecorationId(type, dim, pos),
            new AABB(x, 64, z, x + 1, 65, z + 1),
            1L,
            new byte[]{1, 2, 3}
        );
    }

    @Test
    public void testFailedSaveDoesNotEvictDirtyRegion() throws Exception {
        ServerDecorationWorldIndex index = new ServerDecorationWorldIndex(null, tempDir);
        ServerDecorationRegion region = index.getOrCreateRegion(5, 5);
        region.addOrUpdate(createDummyRecord(5 * 512, 5 * 512));
        assertTrue(region.isDirty(), "Region must be dirty after record addition");

        // Simulate residency timeout by setting lastAccessTime into the past
        Field lastAccessField = ServerDecorationRegion.class.getDeclaredField("lastAccessTime");
        lastAccessField.setAccessible(true);
        lastAccessField.set(region, System.currentTimeMillis() - (ServerDecorationWorldIndex.RESIDENCY_TIMEOUT_MS + 10_000L));

        // Point storage to a read-only directory or deleted path to force save failure
        Field storageDirField = ServerDecorationWorldIndex.class.getDeclaredField("storageDir");
        storageDirField.setAccessible(true);
        Path invalidDir = tempDir.resolve("non_existent_subdir").resolve("deeper");
        storageDirField.set(index, invalidDir);

        // Perform maintenance with no active subscribers
        index.performMaintenance(Collections.emptySet());

        // Dirty region MUST still be retained in memory because save failed!
        ServerDecorationRegion retainedRegion = index.getRegion(5, 5);
        assertNotNull(retainedRegion, "Dirty region must NOT be evicted if save fails!");
        assertTrue(retainedRegion.isDirty(), "Retained region must still be marked dirty");
        assertEquals(1, retainedRegion.size(), "Retained region must preserve its decorations");
    }

    @Test
    public void testCorruptRegionFileIsQuarantined() throws IOException {
        Path regionFile = tempDir.resolve("r.1.2.dat");
        // Write invalid data (not starting with 'DECO' magic)
        byte[] corruptedBytes = new byte[]{0x42, 0x41, 0x44, 0x21, 0x00, 0x01, 0x02};
        Files.write(regionFile, corruptedBytes);

        ServerDecorationWorldIndex index = new ServerDecorationWorldIndex(null, tempDir);
        ServerDecorationRegion loaded = index.loadRegionFromFile(1, 2);

        assertNull(loaded, "Loading corrupt region file must return null");

        // The corrupt file must have been moved/quarantined rather than remaining at original path
        assertFalse(Files.exists(regionFile), "Corrupt region file should be moved from original path");

        // Check that a quarantine file was created
        try (var stream = Files.list(tempDir)) {
            List<Path> quarantined = stream.filter(p -> p.getFileName().toString().startsWith("r.1.2.dat.corrupt.")).toList();
            assertFalse(quarantined.isEmpty(), "A quarantined file must exist with .corrupt. prefix");
            assertArrayEquals(corruptedBytes, Files.readAllBytes(quarantined.get(0)),
                "Quarantined file must retain original corrupted bytes for forensics");
        }
    }

    @Test
    public void testSuccessfulSaveEvictsCleanRegionAfterResidencyTimeout() throws Exception {
        ServerDecorationWorldIndex index = new ServerDecorationWorldIndex(null, tempDir);
        ServerDecorationRegion region = index.getOrCreateRegion(3, 3);
        region.addOrUpdate(createDummyRecord(3 * 512, 3 * 512));
        assertTrue(region.isDirty());

        // Age the region past residency timeout
        Field lastAccessField = ServerDecorationRegion.class.getDeclaredField("lastAccessTime");
        lastAccessField.setAccessible(true);
        lastAccessField.set(region, System.currentTimeMillis() - (ServerDecorationWorldIndex.RESIDENCY_TIMEOUT_MS + 5_000L));

        // Maintenance with no subscribers should save successfully and evict
        index.performMaintenance(Collections.emptySet());

        // Memory should no longer hold the evicted region
        assertNull(index.getRegion(3, 3), "Clean region must be evicted after residency timeout");

        // File on disk must exist and contain the saved decoration
        Path savedFile = tempDir.resolve("r.3.3.dat");
        assertTrue(Files.exists(savedFile), "Evicted region must have been written to disk before eviction");

        // Reloading from disk restores the region
        ServerDecorationRegion reloaded = index.getOrCreateRegion(3, 3);
        assertNotNull(reloaded);
        assertEquals(1, reloaded.size());
    }

    @Test
    public void testCorruptFileWithQuarantineFailurePreventsOverwrite() throws Exception {
        Path regionFile = tempDir.resolve("r.7.8.dat");
        byte[] corruptedBytes = new byte[]{0x42, 0x41, 0x44, 0x21, 0x00, 0x01, 0x02};
        Files.write(regionFile, corruptedBytes);

        // Create an index subclass that simulates quarantine move failure
        ServerDecorationWorldIndex failingIndex = new ServerDecorationWorldIndex(null, tempDir) {
            @Override
            protected void moveFileToQuarantine(Path source, Path target) throws IOException {
                throw new IOException("Simulated quarantine move failure (e.g. disk locked or access denied)");
            }
        };

        // Attempting getOrCreateRegion must throw RegionStorageException and block the region
        assertThrows(ServerDecorationWorldIndex.RegionStorageException.class,
            () -> failingIndex.getOrCreateRegion(7, 8),
            "getOrCreateRegion must throw RegionStorageException when quarantine move fails");

        // The region must be marked blocked
        assertTrue(failingIndex.isRegionBlocked(7, 8), "Region must be marked blocked after quarantine failure");

        // The original corrupt file MUST remain at the original path with identical bytes
        assertTrue(Files.exists(regionFile), "Corrupt region file must still exist at original path");
        assertArrayEquals(corruptedBytes, Files.readAllBytes(regionFile),
            "Corrupt file must NOT be modified or replaced when quarantine fails");

        // Attempting to save a newly-instantiated region for this key must be refused
        ServerDecorationRegion rogueRegion = new ServerDecorationRegion(7, 8);
        rogueRegion.addOrUpdate(createDummyRecord(7 * 512, 8 * 512));
        boolean saveResult = failingIndex.saveRegionToFile(rogueRegion);
        assertFalse(saveResult, "saveRegionToFile must return false for a blocked region");

        // Verify the original corrupt bytes are STILL completely intact on disk
        assertArrayEquals(corruptedBytes, Files.readAllBytes(regionFile),
            "Corrupt file on disk must never be overwritten by any rogue save");
    }

    @Test
    public void testServerDecorationManagerUnloadFailureRecovery() throws Exception {
        ServerDecorationManager manager = ServerDecorationManager.getInstance();
        manager.clearForTesting();

        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("test", "test_dim"));
        Path managerStorage = tempDir.resolve("manager_storage");
        Files.createDirectories(managerStorage);

        ServerDecorationWorldIndex index = new ServerDecorationWorldIndex(null, managerStorage);
        ServerDecorationRegion region = index.getOrCreateRegion(2, 2);
        region.addOrUpdate(createDummyRecord(2 * 512, 2 * 512));
        assertTrue(region.isDirty());

        manager.registerIndexForTesting(dim, index);
        assertSame(index, manager.getIndex(dim));

        // Sabotage storage directory to simulate save failure on level unload
        Field storageDirField = ServerDecorationWorldIndex.class.getDeclaredField("storageDir");
        storageDirField.setAccessible(true);
        Path invalidDir = tempDir.resolve("non_existent").resolve("deep_invalid");
        storageDirField.set(index, invalidDir);

        // Level unload should fail cleanly and place the index into pendingRecoveryIndices
        boolean unloadClean = manager.handleLevelUnload(dim);
        assertFalse(unloadClean, "Unload should report false when persistence fails");
        assertNull(manager.getIndex(dim), "Unloaded dimension must no longer be in active worldIndices");
        assertTrue(manager.getPendingRecoveryIndices().containsKey(dim),
            "Failed dimension must be retained in pendingRecoveryIndices");

        // Fix the storage directory to allow recovery
        storageDirField.set(index, managerStorage);

        // Retry pending recovery
        boolean retryClean = manager.retryPendingRecovery(dim);
        assertTrue(retryClean, "Retry pending recovery should succeed after fixing storage issue");
        assertFalse(manager.getPendingRecoveryIndices().containsKey(dim),
            "Recovered dimension must be removed from pendingRecoveryIndices");

        // File should now be saved on disk
        Path savedFile = managerStorage.resolve("r.2.2.dat");
        assertTrue(Files.exists(savedFile), "Region file must exist after recovery save");
    }

    @Test
    public void testServerDecorationManagerShutdownHandlesPendingRecovery() throws Exception {
        ServerDecorationManager manager = ServerDecorationManager.getInstance();
        manager.clearForTesting();

        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("test", "test_dim_shutdown"));
        Path managerStorage = tempDir.resolve("manager_storage_shutdown");
        Files.createDirectories(managerStorage);

        ServerDecorationWorldIndex index = new ServerDecorationWorldIndex(null, managerStorage);
        ServerDecorationRegion region = index.getOrCreateRegion(4, 4);
        region.addOrUpdate(createDummyRecord(4 * 512, 4 * 512));
        manager.registerIndexForTesting(dim, index);

        // Shutdown cleanly saves and clears
        boolean shutdownClean = manager.handleServerStopping();
        assertTrue(shutdownClean, "Shutdown should succeed when all indices save cleanly");
        assertTrue(manager.getPendingRecoveryIndices().isEmpty(), "No pending recovery indices after clean shutdown");
        assertTrue(Files.exists(managerStorage.resolve("r.4.4.dat")), "Saved file should exist after shutdown");
    }
}
