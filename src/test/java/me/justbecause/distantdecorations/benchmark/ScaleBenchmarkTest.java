package me.justbecause.distantdecorations.benchmark;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.client.render.RenderBudget;
import me.justbecause.distantdecorations.client.spatial.ClientDecorationRegion;
import me.justbecause.distantdecorations.client.spatial.ClientDecorationWorld;
import me.justbecause.distantdecorations.client.spatial.DecorationRenderCell;
import me.justbecause.distantdecorations.client.spatial.ProjectionMetrics;
import me.justbecause.distantdecorations.server.storage.ServerDecorationRegion;
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

public class ScaleBenchmarkTest {

    @Test
    public void benchmark50kDecorationsSpatialCullingAndBudgeting() {
        final int TOTAL_DECORATIONS = 50_000;
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        Identifier typeId = Identifier.fromNamespaceAndPath("minecraft", "painting");

        ClientDecorationWorld world = new ClientDecorationWorld(dim);
        List<DecorationRecord> records = new ArrayList<>(TOTAL_DECORATIONS);

        long startGen = System.nanoTime();
        for (int i = 0; i < TOTAL_DECORATIONS; i++) {
            // Strictly unique positions within a 32x32 chunk area (512x512 blocks)
            int x = i % 512;
            int z = (i / 512) % 512;
            int y = 60 + (i / (512 * 512));

            BlockPos pos = new BlockPos(x, y, z);
            DecorationId id = new DecorationId(typeId, dim, pos);
            AABB bounds = new AABB(x, y, z, x + 2, y + 2, z + 0.1);
            records.add(new DecorationRecord(id, bounds, 1L, new byte[]{1, 2, 3, 4}));
        }
        long genTimeMs = (System.nanoTime() - startGen) / 1_000_000;

        // Ingest into region
        long startIngest = System.nanoTime();
        world.putSnapshot(0, 0, 1L, records);
        long ingestTimeMs = (System.nanoTime() - startIngest) / 1_000_000;

        assertEquals(TOTAL_DECORATIONS, world.getTotalDecorationsCount());

        // Simulate 100 frames of camera movement and query
        Vec3 cameraPos = new Vec3(256.0, 70.0, -100.0);
        ProjectionMetrics metrics = ProjectionMetrics.of(cameraPos, 1920, 1080, 70.0);
        RenderBudget budget = new RenderBudget();
        budget.setMaxSubmissionsPerFrame(2000);
        budget.setMinProjectedPixelSize(1.0);

        long startQuery = System.nanoTime();
        int frames = 100;
        int totalRenderCandidates = 0;

        for (int f = 0; f < frames; f++) {
            List<RenderBudget.RenderCandidate> frameCandidates = new ArrayList<>();
            ClientDecorationRegion region = world.getRegion(0, 0);
            assertNotNull(region);

            for (DecorationRenderCell cell : region.getNonEmptyCells()) {
                AABB cellBox = cell.getBounds();

                // Coarse cell distance check
                double cellDistSq = metrics.getDistanceSq(cellBox);
                if (cellDistSq > 1024.0 * 1024.0) continue;

                for (DecorationRecord record : cell.getRecords()) {
                    double distSq = metrics.getDistanceSq(record.bounds());
                    if (distSq > budget.getMaxRenderDistance() * budget.getMaxRenderDistance()) continue;

                    double pixelSize = metrics.calculateProjectedPixelSize(record.bounds());
                    if (pixelSize < budget.getMinProjectedPixelSize()) continue;

                    double score = budget.calculatePriority(pixelSize, distSq);
                    frameCandidates.add(new RenderBudget.RenderCandidate(record, pixelSize, distSq, score));
                }
            }

            frameCandidates.sort(RenderBudget.PRIORITY_COMPARATOR);
            if (frameCandidates.size() > budget.getMaxSubmissionsPerFrame()) {
                frameCandidates = frameCandidates.subList(0, budget.getMaxSubmissionsPerFrame());
            }

            totalRenderCandidates += frameCandidates.size();
        }

        long totalQueryTimeMs = (System.nanoTime() - startQuery) / 1_000_000;
        double avgFrameTimeMs = (double) totalQueryTimeMs / frames;

        System.out.println(String.format(
            "=== Scale Benchmark Results ===\n" +
            "Total Decorations: %d\n" +
            "Ingest Time: %d ms\n" +
            "100 Frames Query Time: %d ms (Avg: %.2f ms / frame)\n" +
            "Candidates per frame: %d\n",
            TOTAL_DECORATIONS, ingestTimeMs, totalQueryTimeMs, avgFrameTimeMs, totalRenderCandidates / frames
        ));

        // Average frame query latency for 50,000 decorations should be under 15ms
        assertTrue(avgFrameTimeMs < 15.0, "Average query latency too high: " + avgFrameTimeMs + " ms");
    }

    @Test
    public void benchmarkServerRegionChunkLookupScale() {
        ServerDecorationRegion region = new ServerDecorationRegion(0, 0);
        Identifier typeId = Identifier.fromNamespaceAndPath("minecraft", "painting");
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));

        final int COUNT = 20_000;
        for (int i = 0; i < COUNT; i++) {
            int x = i % 512;
            int z = (i / 512) % 512;
            int y = 64 + (i / (512 * 512));
            BlockPos pos = new BlockPos(x, y, z);
            DecorationId id = new DecorationId(typeId, dim, pos);
            AABB bounds = new AABB(pos.getX(), y, pos.getZ(), pos.getX() + 1, y + 1, pos.getZ() + 0.1);
            region.addOrUpdate(new DecorationRecord(id, bounds, 1L, new byte[]{0}));
        }

        assertEquals(COUNT, region.size());

        long startLookup = System.nanoTime();
        for (int cx = 0; cx < 32; cx++) {
            for (int cz = 0; cz < 32; cz++) {
                List<DecorationRecord> inChunk = region.getDecorationsInChunk(cx, cz);
                assertNotNull(inChunk);
            }
        }
        long lookupTimeUs = (System.nanoTime() - startLookup) / 1_000;

        System.out.println("32x32 Chunk lookup time for 20k records: " + lookupTimeUs + " µs");
        assertTrue(lookupTimeUs < 50_000, "Chunk lookups took too long: " + lookupTimeUs + " µs");
    }
}
