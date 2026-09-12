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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.AccessDeniedException;
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
    public void testAccessDeniedProducesReadErrorWithoutQuarantine() throws Exception {
        Path regionFile = tempDir.resolve("r.10.10.dat");
        byte[] originalBytes = new byte[]{1, 2, 3, 4, 5};
        Files.write(regionFile, originalBytes);

        ServerDecorationWorldIndex failingIndex = new ServerDecorationWorldIndex(null, tempDir) {
            @Override
            protected InputStream openInputStream(Path path) throws IOException {
                throw new AccessDeniedException(path.toString(), null, "Simulated access denied");
            }
        };

        var result = failingIndex.loadRegionFromFileWithResult(10, 10);
        assertEquals(ServerDecorationWorldIndex.RegionLoadStatus.READ_ERROR, result.status());
        assertInstanceOf(AccessDeniedException.class, result.cause());

        // Verify NO quarantine file was created
        try (var stream = Files.list(tempDir)) {
            List<Path> quarantined = stream.filter(p -> p.getFileName().toString().contains(".corrupt.")).toList();
            assertTrue(quarantined.isEmpty(), "No quarantine file must be created for read/access errors");
        }

        // Region must be marked blocked
        assertTrue(failingIndex.isRegionBlocked(10, 10), "Region must be marked blocked on read error");

        // getOrCreateRegion must throw RegionStorageException
        assertThrows(ServerDecorationWorldIndex.RegionStorageException.class,
            () -> failingIndex.getOrCreateRegion(10, 10),
            "getOrCreateRegion must throw RegionStorageException for blocked region");

        // loadRegionFromFile must throw RegionStorageException
        assertThrows(ServerDecorationWorldIndex.RegionStorageException.class,
            () -> failingIndex.loadRegionFromFile(10, 10),
            "loadRegionFromFile must throw RegionStorageException for READ_ERROR");

        // saveRegionToFile must refuse
        ServerDecorationRegion testRegion = new ServerDecorationRegion(10, 10);
        testRegion.addOrUpdate(createDummyRecord(10 * 512, 10 * 512));
        assertFalse(failingIndex.saveRegionToFile(testRegion), "saveRegionToFile must return false for blocked region");

        // Original file must remain intact
        assertArrayEquals(originalBytes, Files.readAllBytes(regionFile), "Original file must not be modified");
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

        // Block dirty region to simulate save failure on level unload without mutating storage directory
        index.blockRegionForTesting(2, 2);

        // Level unload should fail cleanly and place the index into pendingRecoveryByStorage
        boolean unloadClean = manager.handleLevelUnload(dim);
        assertFalse(unloadClean, "Unload should report false when persistence fails");
        assertNull(manager.getIndex(dim), "Unloaded dimension must no longer be in active worldIndices");
        assertTrue(manager.isStoragePendingRecovery(managerStorage),
            "Failed storage must be retained in pendingRecoveryByStorage");

        // Unblock region to allow successful recovery
        index.unblockRegion(2, 2);

        // Retry pending recovery by storage path
        boolean retryClean = manager.retryPendingRecovery(managerStorage);
        assertTrue(retryClean, "Retry pending recovery should succeed after fixing storage issue");
        assertFalse(manager.isStoragePendingRecovery(managerStorage),
            "Recovered storage must be removed from pendingRecoveryByStorage");

        // File should now be saved on disk
        Path savedFile = managerStorage.resolve("r.2.2.dat");
        assertTrue(Files.exists(savedFile), "Region file must exist after recovery save");
    }

    @Test
    public void testRecoveryOwnershipIndependentAcrossSaveSessionsForSameDimension() throws Exception {
        ServerDecorationManager manager = ServerDecorationManager.getInstance();
        manager.clearForTesting();

        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("test", "shared_dim"));
        Path dirA = tempDir.resolve("storage_a");
        Path dirB = tempDir.resolve("storage_b");
        Path dirC = tempDir.resolve("storage_c");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);
        Files.createDirectories(dirC);

        // Session 1: Index A on dirA fails unload
        ServerDecorationWorldIndex indexA = new ServerDecorationWorldIndex(null, dirA);
        ServerDecorationRegion regA = indexA.getOrCreateRegion(1, 1);
        regA.addOrUpdate(createDummyRecord(512, 512));
        manager.registerIndexForTesting(dim, indexA);
        indexA.blockRegionForTesting(1, 1); // simulate save failure
        boolean unloadA = manager.handleLevelUnload(dim);
        assertFalse(unloadA);
        assertTrue(manager.isStoragePendingRecovery(dirA));
        assertEquals(1, manager.getPendingRecoveryIndices().size());

        // Session 2: Index B on dirB (same dimension) unloads cleanly
        ServerDecorationWorldIndex indexB = new ServerDecorationWorldIndex(null, dirB);
        manager.registerIndexForTesting(dim, indexB);
        boolean unloadB = manager.handleLevelUnload(dim);
        assertTrue(unloadB);
        // CRITICAL: Clean close of index B must NOT remove index A from recovery!
        assertTrue(manager.isStoragePendingRecovery(dirA), "Index A must still be retained in recovery after index B clean unload");
        assertFalse(manager.isStoragePendingRecovery(dirB));
        assertEquals(1, manager.getPendingRecoveryIndices().size());

        // Session 3: Index C on dirC (same dimension) also fails unload
        ServerDecorationWorldIndex indexC = new ServerDecorationWorldIndex(null, dirC);
        ServerDecorationRegion regC = indexC.getOrCreateRegion(3, 3);
        regC.addOrUpdate(createDummyRecord(3 * 512, 3 * 512));
        manager.registerIndexForTesting(dim, indexC);
        indexC.blockRegionForTesting(3, 3); // simulate save failure
        boolean unloadC = manager.handleLevelUnload(dim);
        assertFalse(unloadC);
        assertTrue(manager.isStoragePendingRecovery(dirA), "Index A must still be in recovery");
        assertTrue(manager.isStoragePendingRecovery(dirC), "Index C must also be in recovery");
        assertFalse(manager.isStoragePendingRecovery(dirB), "Index B should not be in recovery");
        assertEquals(2, manager.getPendingRecoveryIndices().size());
    }

    @Test
    public void testSingleWriterPreventsReopeningStorageWithUnresolvedPendingWrites() throws Exception {
        ServerDecorationManager manager = ServerDecorationManager.getInstance();
        manager.clearForTesting();

        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("test", "single_writer_dim"));
        Path storageDir = tempDir.resolve("single_writer_storage");
        Files.createDirectories(storageDir);

        // Index A on storageDir fails unload
        ServerDecorationWorldIndex indexA = new ServerDecorationWorldIndex(null, storageDir);
        ServerDecorationRegion regA = indexA.getOrCreateRegion(5, 5);
        regA.addOrUpdate(createDummyRecord(5 * 512, 5 * 512));
        manager.registerIndexForTesting(dim, indexA);
        indexA.blockRegionForTesting(5, 5); // simulate save failure
        boolean unload = manager.handleLevelUnload(dim);
        assertFalse(unload);
        assertTrue(manager.isStoragePendingRecovery(storageDir));

        // Attempt to open index on the same storage while recovery cannot resolve
        ServerDecorationWorldIndex opened = manager.openIndex(dim, storageDir);
        assertNull(opened, "openIndex must return null when previous index has unresolved pending writes");
        assertTrue(manager.isStoragePendingRecovery(storageDir));

        // Now fix the underlying problem on index A by unblocking region
        indexA.unblockRegion(5, 5);

        // Attempt to open index again: it resolves pending recovery for index A, flushes writes, and opens new index
        ServerDecorationWorldIndex openedClean = manager.openIndex(dim, storageDir);
        assertNotNull(openedClean, "openIndex should succeed once pending recovery is resolved");
        assertFalse(manager.isStoragePendingRecovery(storageDir), "Storage should no longer be pending recovery");

        // Verify index A's data was persisted to disk
        Path regionFile = storageDir.resolve("r.5.5.dat");
        assertTrue(Files.exists(regionFile), "Region file from previous session must be flushed to disk");
    }

    @Test
    public void testServerDecorationManagerCleanShutdown() throws Exception {
        ServerDecorationManager manager = ServerDecorationManager.getInstance();
        manager.clearForTesting();

        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("test", "test_dim_clean_shutdown"));
        Path managerStorage = tempDir.resolve("manager_storage_clean_shutdown");
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

    @Test
    public void testServerDecorationManagerShutdownFlushesPendingRecovery() throws Exception {
        ServerDecorationManager manager = ServerDecorationManager.getInstance();
        manager.clearForTesting();

        Path recoveryStorage = tempDir.resolve("recovery_storage_shutdown");
        Files.createDirectories(recoveryStorage);

        ServerDecorationWorldIndex pendingIndex = new ServerDecorationWorldIndex(null, recoveryStorage);
        ServerDecorationRegion region = pendingIndex.getOrCreateRegion(8, 8);
        region.addOrUpdate(createDummyRecord(8 * 512, 8 * 512));
        assertTrue(region.isDirty());

        // Seed directly into pendingRecoveryByStorage
        manager.registerRecoveryForTesting(recoveryStorage, pendingIndex);
        assertTrue(manager.isStoragePendingRecovery(recoveryStorage));

        // Shutdown flushes pending recovery indices
        boolean shutdownClean = manager.handleServerStopping();
        assertTrue(shutdownClean, "Shutdown should succeed after successfully flushing pending recovery");
        assertTrue(manager.getPendingRecoveryIndices().isEmpty(), "Pending recovery should be empty after successful flush");
        assertTrue(Files.exists(recoveryStorage.resolve("r.8.8.dat")), "Pending recovery region must be persisted to disk");
    }

    private byte[] writeMalformedRegionFile(Path path, int rx, int rz, String typeIdentifier, String dimensionIdentifier) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeInt(0x4445434F); // MAGIC
        dos.writeInt(1);          // FORMAT_VERSION
        dos.writeInt(rx);
        dos.writeInt(rz);
        dos.writeLong(1L);        // revision
        dos.writeInt(1);          // record count = 1

        // Record - DecorationId:
        dos.writeUTF(typeIdentifier);
        dos.writeUTF(dimensionIdentifier);
        dos.writeInt(rx * 512 + 10); // anchor X
        dos.writeInt(64);            // anchor Y
        dos.writeInt(rz * 512 + 10); // anchor Z

        // Record - bounds:
        dos.writeDouble(rx * 512 + 10);
        dos.writeDouble(64.0);
        dos.writeDouble(rz * 512 + 10);
        dos.writeDouble(rx * 512 + 11);
        dos.writeDouble(65.0);
        dos.writeDouble(rz * 512 + 10.1);

        // Record - revision & payload:
        dos.writeLong(1L);
        byte[] payload = new byte[]{1, 2, 3};
        dos.writeInt(payload.length);
        dos.write(payload);

        dos.flush();
        byte[] bytes = baos.toByteArray();
        Files.write(path, bytes);
        return bytes;
    }

    @Test
    public void testMalformedStoredDecorationTypeIdentifierIsQuarantined() throws IOException {
        Path regionFile = tempDir.resolve("r.12.13.dat");
        byte[] originalBytes = writeMalformedRegionFile(regionFile, 12, 13, "INVALID DECO TYPE UPPERCASE & SPACES!", "minecraft:overworld");

        ServerDecorationWorldIndex index = new ServerDecorationWorldIndex(null, tempDir);
        var result = index.loadRegionFromFileWithResult(12, 13);

        // Must be diagnosed as quarantined corruption, no raw IdentifierException escaping
        assertEquals(ServerDecorationWorldIndex.RegionLoadStatus.QUARANTINED, result.status());
        assertNotNull(result.cause());
        assertTrue(result.cause().getMessage().contains("Malformed stored decoration type identifier"),
            "Cause message should describe malformed identifier: " + result.cause().getMessage());

        // The corrupt file must have been moved/quarantined from original path
        assertFalse(Files.exists(regionFile), "Malformed region file should be moved from original path");

        // Verify quarantined file preserves original bytes
        try (var stream = Files.list(tempDir)) {
            List<Path> quarantined = stream.filter(p -> p.getFileName().toString().startsWith("r.12.13.dat.corrupt.")).toList();
            assertFalse(quarantined.isEmpty(), "Quarantined file must exist");
            assertArrayEquals(originalBytes, Files.readAllBytes(quarantined.get(0)),
                "Quarantined file must preserve original malformed bytes for forensics");
        }

        // loadRegionFromFile must return null for quarantined region
        assertNull(index.loadRegionFromFile(12, 13));

        // getOrCreateRegion must safely return a clean new region without throwing
        ServerDecorationRegion cleanRegion = assertDoesNotThrow(() -> index.getOrCreateRegion(12, 13));
        assertNotNull(cleanRegion);
        assertEquals(0, cleanRegion.size());
    }

    @Test
    public void testMalformedStoredDimensionIdentifierIsQuarantined() throws IOException {
        Path regionFile = tempDir.resolve("r.14.15.dat");
        byte[] originalBytes = writeMalformedRegionFile(regionFile, 14, 15, "test:painting", "INVALID DIMENSION WITH SPACES!");

        ServerDecorationWorldIndex index = new ServerDecorationWorldIndex(null, tempDir);
        var result = index.loadRegionFromFileWithResult(14, 15);

        // Must be diagnosed as quarantined corruption
        assertEquals(ServerDecorationWorldIndex.RegionLoadStatus.QUARANTINED, result.status());
        assertNotNull(result.cause());
        assertTrue(result.cause().getMessage().contains("Malformed stored dimension identifier"),
            "Cause message should describe malformed dimension identifier: " + result.cause().getMessage());

        // File must be moved to quarantine
        assertFalse(Files.exists(regionFile), "Malformed region file must be moved from original path");

        // Verify quarantine file preserves original bytes
        try (var stream = Files.list(tempDir)) {
            List<Path> quarantined = stream.filter(p -> p.getFileName().toString().startsWith("r.14.15.dat.corrupt.")).toList();
            assertFalse(quarantined.isEmpty(), "Quarantined file must exist");
            assertArrayEquals(originalBytes, Files.readAllBytes(quarantined.get(0)));
        }

        // loadRegionFromFile returns null
        assertNull(index.loadRegionFromFile(14, 15));
    }

    @Test
    public void testMalformedIdentifierWithQuarantineFailureBlocksRegionAndRefusesSave() throws Exception {
        Path regionFile = tempDir.resolve("r.16.17.dat");
        byte[] originalBytes = writeMalformedRegionFile(regionFile, 16, 17, "INVALID_UPPERCASE", "minecraft:overworld");

        // Create index subclass simulating quarantine move failure
        ServerDecorationWorldIndex failingIndex = new ServerDecorationWorldIndex(null, tempDir) {
            @Override
            protected void moveFileToQuarantine(Path source, Path target) throws IOException {
                throw new IOException("Simulated quarantine move failure on malformed identifier file");
            }
        };

        // getOrCreateRegion must throw RegionStorageException, NOT a raw IdentifierException
        var thrown = assertThrows(ServerDecorationWorldIndex.RegionStorageException.class,
            () -> failingIndex.getOrCreateRegion(16, 17),
            "getOrCreateRegion must throw RegionStorageException when quarantine move fails on malformed file");
        assertTrue(thrown.getMessage().contains("corrupt file remains on disk because quarantine failed"));

        // Region must be marked blocked
        assertTrue(failingIndex.isRegionBlocked(16, 17), "Region must be marked blocked after quarantine failure");

        // The original malformed file MUST remain at the original path with identical bytes
        assertTrue(Files.exists(regionFile), "Malformed file must still exist at original path");
        assertArrayEquals(originalBytes, Files.readAllBytes(regionFile), "Original bytes must remain completely intact");

        // Replacement saves must be refused
        ServerDecorationRegion rogueRegion = new ServerDecorationRegion(16, 17);
        rogueRegion.addOrUpdate(createDummyRecord(16 * 512, 17 * 512));
        boolean saveResult = failingIndex.saveRegionToFile(rogueRegion);
        assertFalse(saveResult, "saveRegionToFile must return false for a blocked region");

        // Original malformed bytes on disk must never be overwritten
        assertArrayEquals(originalBytes, Files.readAllBytes(regionFile), "Disk file must never be overwritten");
    }
}
