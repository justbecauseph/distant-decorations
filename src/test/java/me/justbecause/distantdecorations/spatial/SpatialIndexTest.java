package me.justbecause.distantdecorations.spatial;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.client.render.RenderBudget;
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
        assertEquals(2, region.getNonEmptyCells().size());

        region.remove(id1);
        assertEquals(1, region.size());
        assertEquals(1, region.getNonEmptyCells().size());
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

        RenderBudget.RenderCandidate cNearLarge = new RenderBudget.RenderCandidate(r1, 50.0, 100.0, 50.0 * 1000.0 - 10.0);
        RenderBudget.RenderCandidate cFarSmall = new RenderBudget.RenderCandidate(r2, 2.0, 500000.0, 2.0 * 1000.0 - 707.0);

        List<RenderBudget.RenderCandidate> list = new ArrayList<>();
        list.add(cFarSmall);
        list.add(cNearLarge);

        list.sort(RenderBudget.PRIORITY_COMPARATOR);

        assertEquals(cNearLarge, list.get(0));
        assertEquals(cFarSmall, list.get(1));
    }
}
