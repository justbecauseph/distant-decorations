package me.justbecause.distantdecorations.spatial;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.client.render.RenderBudget;
import me.justbecause.distantdecorations.client.spatial.ClientDecoration;
import me.justbecause.distantdecorations.client.spatial.ClientDecorationRegion;
import me.justbecause.distantdecorations.client.spatial.ClientDecorationWorld;
import me.justbecause.distantdecorations.client.spatial.DecorationRenderCell;
import me.justbecause.distantdecorations.client.spatial.ProjectionMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SpatialIndexTest {

    @Test
    public void testRenderCellBoundsAndRecords() {
        DecorationRenderCell cell = new DecorationRenderCell(0, 0);
        assertTrue(cell.isEmpty());
        assertEquals(0, cell.size());

        Identifier type = Identifier.fromNamespaceAndPath("test", "painting");
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        DecorationId id = new DecorationId(type, dim, new BlockPos(10, 64, 20));
        AABB bounds = new AABB(10.0, 64.0, 20.0, 12.0, 66.0, 20.1);
        DecorationRecord record = new DecorationRecord(id, bounds, 1L, new byte[]{});

        cell.addOrUpdate(record);
        assertFalse(cell.isEmpty());
        assertEquals(1, cell.size());
        assertEquals(bounds, cell.getBounds());

        cell.remove(id);
        assertTrue(cell.isEmpty());
    }

    @Test
    public void testRegionPartitioning() {
        ClientDecorationRegion region = new ClientDecorationRegion(0, 0, 100L);
        assertEquals(0, region.regionX());
        assertEquals(0, region.regionZ());
        assertEquals(100L, region.revision());

        Identifier type = Identifier.fromNamespaceAndPath("test", "frame");
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));

        DecorationId id1 = new DecorationId(type, dim, new BlockPos(5, 70, 5));
        DecorationRecord record1 = new DecorationRecord(id1, new AABB(5, 70, 5, 6, 71, 5.1), 1L, new byte[]{});

        DecorationId id2 = new DecorationId(type, dim, new BlockPos(100, 70, 100));
        DecorationRecord record2 = new DecorationRecord(id2, new AABB(100, 70, 100, 101, 71, 100.1), 2L, new byte[]{});

        region.addOrUpdate(record1);
        region.addOrUpdate(record2);

        assertEquals(2, region.size());
        int nonEmptyCount = 0;
        for (DecorationRenderCell cell : region.getCells()) {
            if (!cell.isEmpty()) nonEmptyCount++;
        }
        assertEquals(2, nonEmptyCount);

        region.remove(id1);
        assertEquals(1, region.size());
        nonEmptyCount = 0;
        for (DecorationRenderCell cell : region.getCells()) {
            if (!cell.isEmpty()) nonEmptyCount++;
        }
        assertEquals(1, nonEmptyCount);
    }

    @Test
    public void testClientDecorationWorldSnapshotsAndDeltas() {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        ClientDecorationWorld world = new ClientDecorationWorld(dim);

        Identifier type = Identifier.fromNamespaceAndPath("test", "painting");
        DecorationId id1 = new DecorationId(type, dim, new BlockPos(10, 64, 10));
        DecorationRecord record1 = new DecorationRecord(id1, new AABB(10, 64, 10, 12, 66, 10.1), 1L, new byte[]{});

        world.putSnapshot(0, 0, 1L, List.of(record1));
        assertEquals(1, world.getTotalDecorationsCount());

        DecorationId id2 = new DecorationId(type, dim, new BlockPos(20, 64, 20));
        DecorationRecord record2 = new DecorationRecord(id2, new AABB(20, 64, 20, 22, 66, 20.1), 2L, new byte[]{});

        world.applyDelta(0, 0, 2L, List.of(record2), List.of(id1));
        assertEquals(1, world.getTotalDecorationsCount());
        assertNotNull(world.getRegion(0, 0));
        assertTrue(world.getRegion(0, 0).getAllRecords().contains(record2));
        assertFalse(world.getRegion(0, 0).getAllRecords().contains(record1));

        world.unloadRegion(0, 0);
        assertNull(world.getRegion(0, 0));
        assertEquals(0, world.getTotalDecorationsCount());
    }

    @Test
    public void testMultipartSnapshotAssembly() {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        ClientDecorationWorld world = new ClientDecorationWorld(dim);
        Identifier type = Identifier.fromNamespaceAndPath("test", "painting");

        DecorationRecord r1 = new DecorationRecord(new DecorationId(type, dim, new BlockPos(10, 64, 10)), new AABB(10, 64, 10, 12, 66, 10.1), 1L, new byte[]{1});
        DecorationRecord r2 = new DecorationRecord(new DecorationId(type, dim, new BlockPos(20, 64, 20)), new AABB(20, 64, 20, 22, 66, 20.1), 1L, new byte[]{2});
        DecorationRecord r3 = new DecorationRecord(new DecorationId(type, dim, new BlockPos(30, 64, 30)), new AABB(30, 64, 30, 32, 66, 30.1), 1L, new byte[]{3});
        DecorationRecord r4 = new DecorationRecord(new DecorationId(type, dim, new BlockPos(40, 64, 40)), new AABB(40, 64, 40, 42, 66, 40.1), 1L, new byte[]{4});

        // 3-part snapshot for region (1, 1)
        // Part 0
        world.putSnapshotPart(1, 1, 5L, 0, 3, List.of(r1));
        assertNull(world.getRegion(1, 1), "Region should not be exposed until all parts arrive");

        // Part 1
        world.putSnapshotPart(1, 1, 5L, 1, 3, List.of(r2, r3));
        assertNull(world.getRegion(1, 1), "Region should not be exposed until all parts arrive");

        // Part 2 (Final part)
        world.putSnapshotPart(1, 1, 5L, 2, 3, List.of(r4));
        assertNotNull(world.getRegion(1, 1), "Region should now be atomically visible");
        assertEquals(4, world.getRegion(1, 1).size());
        assertEquals(5L, world.getRegion(1, 1).revision());
        assertTrue(world.getRegion(1, 1).getAllRecords().contains(r1));
        assertTrue(world.getRegion(1, 1).getAllRecords().contains(r4));
    }

    @Test
    public void test1200RecordSnapshotOutOfOrderAssembly() {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        ClientDecorationWorld world = new ClientDecorationWorld(dim);
        Identifier type = Identifier.fromNamespaceAndPath("test", "painting");

        final int TOTAL_RECORDS = 1200;
        List<DecorationRecord> allRecords = new ArrayList<>(TOTAL_RECORDS);
        for (int i = 0; i < TOTAL_RECORDS; i++) {
            BlockPos pos = new BlockPos(i % 512, 64, (i / 512) % 512);
            allRecords.add(new DecorationRecord(
                new DecorationId(type, dim, pos),
                new AABB(pos.getX(), 64, pos.getZ(), pos.getX() + 1, 65, pos.getZ() + 0.1),
                10L,
                new byte[]{(byte) (i & 0xFF)}
            ));
        }

        List<DecorationRecord> part0 = allRecords.subList(0, 400);
        List<DecorationRecord> part1 = allRecords.subList(400, 800);
        List<DecorationRecord> part2 = allRecords.subList(800, 1200);

        // Receive in out-of-order sequence: Part 2 -> Part 0 -> Part 1
        world.putSnapshotPart(0, 0, 10L, 2, 3, part2);
        assertNull(world.getRegion(0, 0), "Region should not be visible after part 2 of 3");

        world.putSnapshotPart(0, 0, 10L, 0, 3, part0);
        assertNull(world.getRegion(0, 0), "Region should not be visible after parts 2 and 0 of 3");

        world.putSnapshotPart(0, 0, 10L, 1, 3, part1);
        assertNotNull(world.getRegion(0, 0), "Region should now be complete and visible");
        assertEquals(TOTAL_RECORDS, world.getRegion(0, 0).size());
        assertEquals(10L, world.getRegion(0, 0).revision());
    }

    @Test
    public void testSnapshotR10Part0DeltaR11SnapshotR10Parts1And2() {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        ClientDecorationWorld world = new ClientDecorationWorld(dim);
        Identifier type = Identifier.fromNamespaceAndPath("test", "painting");

        final int TOTAL_RECORDS = 1200;
        List<DecorationRecord> allRecords = new ArrayList<>(TOTAL_RECORDS);
        for (int i = 0; i < TOTAL_RECORDS; i++) {
            BlockPos pos = new BlockPos(i % 512, 64, (i / 512) % 512);
            allRecords.add(new DecorationRecord(
                new DecorationId(type, dim, pos),
                new AABB(pos.getX(), 64, pos.getZ(), pos.getX() + 1, 65, pos.getZ() + 0.1),
                10L,
                new byte[]{(byte) (i & 0xFF)}
            ));
        }

        List<DecorationRecord> part0 = allRecords.subList(0, 400);
        List<DecorationRecord> part1 = allRecords.subList(400, 800);
        List<DecorationRecord> part2 = allRecords.subList(800, 1200);

        // 1. Part 0 arrives (revision 10)
        world.putSnapshotPart(0, 0, 10L, 0, 3, part0);
        assertNull(world.getRegion(0, 0));

        // 2. Delta arrives at revision 11 (adds 1 new record, removes 1 from part 0)
        DecorationId toRemove = part0.get(0).id();
        DecorationRecord newRecord = new DecorationRecord(
            new DecorationId(type, dim, new BlockPos(300, 70, 300)),
            new AABB(300, 70, 300, 302, 72, 300.1),
            11L,
            new byte[]{99}
        );
        world.applyDelta(0, 0, 11L, List.of(newRecord), List.of(toRemove));

        // 3. Parts 1 and 2 arrive (revision 10)
        world.putSnapshotPart(0, 0, 10L, 1, 3, part1);
        world.putSnapshotPart(0, 0, 10L, 2, 3, part2);

        // Region should now be assembled with full R10 baseline AND buffered R11 delta applied!
        assertNotNull(world.getRegion(0, 0));
        assertEquals(TOTAL_RECORDS, world.getRegion(0, 0).size());
        assertEquals(11L, world.getRegion(0, 0).revision());
        assertTrue(world.getRegion(0, 0).getAllRecords().contains(newRecord));
        assertFalse(world.getRegion(0, 0).getAllDecorations().stream().anyMatch(d -> d.id().equals(toRemove)));
    }

    @Test
    public void testSnapshotR10FollowedByStaleSnapshotR9() {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        ClientDecorationWorld world = new ClientDecorationWorld(dim);
        Identifier type = Identifier.fromNamespaceAndPath("test", "painting");

        DecorationRecord r1 = new DecorationRecord(new DecorationId(type, dim, new BlockPos(10, 64, 10)), new AABB(10, 64, 10, 12, 66, 10.1), 10L, new byte[]{1});
        DecorationRecord r2 = new DecorationRecord(new DecorationId(type, dim, new BlockPos(20, 64, 20)), new AABB(20, 64, 20, 22, 66, 20.1), 10L, new byte[]{2});

        world.putSnapshot(0, 0, 10L, List.of(r1, r2));
        assertEquals(2, world.getRegion(0, 0).size());
        assertEquals(10L, world.getRegion(0, 0).revision());

        // Stale snapshot at revision 9
        DecorationRecord oldRec = new DecorationRecord(new DecorationId(type, dim, new BlockPos(50, 64, 50)), new AABB(50, 64, 50, 52, 66, 50.1), 9L, new byte[]{9});
        world.putSnapshot(0, 0, 9L, List.of(oldRec));

        // R9 must be rejected; R10 is retained
        assertEquals(2, world.getRegion(0, 0).size());
        assertEquals(10L, world.getRegion(0, 0).revision());
        assertTrue(world.getRegion(0, 0).getAllRecords().contains(r1));
        assertFalse(world.getRegion(0, 0).getAllRecords().contains(oldRec));
    }

    @Test
    public void testStandaloneDeltaForUnloadedRegionDoesNotSynthesizeIncompleteRegion() {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        ClientDecorationWorld world = new ClientDecorationWorld(dim);
        Identifier type = Identifier.fromNamespaceAndPath("test", "painting");

        DecorationRecord r = new DecorationRecord(new DecorationId(type, dim, new BlockPos(10, 64, 10)), new AABB(10, 64, 10, 12, 66, 10.1), 5L, new byte[]{1});
        world.applyDelta(5, 5, 5L, List.of(r), List.of());

        assertNull(world.getRegion(5, 5), "Delta on unloaded region must not create incomplete region");
    }

    @Test
    public void testProjectionMetricsPixelCalculation() {
        Vec3 cameraPos = new Vec3(0, 64, 0);
        int viewportWidth = 1920;
        int viewportHeight = 1080;
        double fovDegrees = 70.0;

        ProjectionMetrics metrics = ProjectionMetrics.of(cameraPos, viewportWidth, viewportHeight, fovDegrees);

        // Object 100 meters away with diameter 2 meters
        AABB bounds = new AABB(0, 64, 100, 2, 66, 100.1);
        double pixelSize = metrics.calculateProjectedPixelSize(bounds);

        // At 100m, a 2m object with 771.2 focal length should be ~15-20 pixels
        assertTrue(pixelSize > 10.0 && pixelSize < 40.0, "Expected pixel size around 15-25px, got: " + pixelSize);

        // Object 2000 meters away with diameter 1 meter (< 1px projected size)
        AABB farBounds = new AABB(0, 64, 2000, 1, 65, 2000.1);
        double farPixelSize = metrics.calculateProjectedPixelSize(farBounds);
        assertTrue(farPixelSize < 1.0, "Far object should be subpixel, got: " + farPixelSize);
    }

    @Test
    public void testRenderBudgetCandidateSorting() {
        Identifier type = Identifier.fromNamespaceAndPath("test", "frame");
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));

        DecorationId id1 = new DecorationId(type, dim, new BlockPos(10, 64, 10));
        DecorationRecord r1 = new DecorationRecord(id1, new AABB(10, 64, 10, 11, 65, 10.1), 1L, new byte[]{});

        DecorationId id2 = new DecorationId(type, dim, new BlockPos(500, 64, 500));
        DecorationRecord r2 = new DecorationRecord(id2, new AABB(500, 64, 500, 501, 65, 500.1), 1L, new byte[]{});

        RenderBudget.RenderCandidate cNearLarge = new RenderBudget.RenderCandidate(new ClientDecoration(r1), 50.0, 100.0, 50.0 * 1000.0 - 10.0);
        RenderBudget.RenderCandidate cFarSmall = new RenderBudget.RenderCandidate(new ClientDecoration(r2), 2.0, 500000.0, 2.0 * 1000.0 - 707.0);

        List<RenderBudget.RenderCandidate> list = new ArrayList<>();
        list.add(cFarSmall);
        list.add(cNearLarge);

        list.sort(RenderBudget.PRIORITY_COMPARATOR);

        assertEquals(cNearLarge, list.get(0));
        assertEquals(cFarSmall, list.get(1));
    }
}

