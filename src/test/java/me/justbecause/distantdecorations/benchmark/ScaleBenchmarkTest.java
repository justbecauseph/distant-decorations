package me.justbecause.distantdecorations.benchmark;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.client.render.RenderBudget;
import me.justbecause.distantdecorations.client.spatial.ClientDecoration;
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
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.*;

public class ScaleBenchmarkTest {

    @Test
    public void benchmark50kDecorationsSpatialCullingAndTopKSelection() {
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

        // Simulate 100 frames of camera movement and spatial culling + top-K selection query
        Vec3 cameraPos = new Vec3(256.0, 70.0, -100.0);
        ProjectionMetrics metrics = ProjectionMetrics.of(cameraPos, 1920, 1080, 70.0);
        RenderBudget budget = new RenderBudget();
        int maxSubmissions = 2000;
        budget.setMaxSubmissionsPerFrame(maxSubmissions);
        budget.setMinProjectedPixelSize(1.0);

        long startQuery = System.nanoTime();
        int frames = 100;
        int totalRenderCandidates = 0;

        List<RenderBudget.RenderCandidate> candidateList = new ArrayList<>(2048);
        PriorityQueue<RenderBudget.RenderCandidate> topKHeap = new PriorityQueue<>(2048, RenderBudget.MIN_HEAP_COMPARATOR);

        for (int f = 0; f < frames; f++) {
            candidateList.clear();
            topKHeap.clear();
            boolean useHeap = false;

            ClientDecorationRegion region = world.getRegion(0, 0);
            assertNotNull(region);

            for (DecorationRenderCell cell : region.getCells()) {
                if (cell.isEmpty()) continue;
                AABB cellBox = cell.getBounds();

                // Coarse cell distance check
                double cellDistSq = metrics.getDistanceSq(cellBox);
                if (cellDistSq > 1024.0 * 1024.0) continue;

                for (ClientDecoration deco : cell.getDecorations()) {
                    double distSq = metrics.getDistanceSq(deco.bounds());
                    if (distSq > budget.getMaxRenderDistance() * budget.getMaxRenderDistance()) continue;

                    double pixelSize = metrics.calculateProjectedPixelSize(deco.bounds());
                    if (pixelSize < budget.getMinProjectedPixelSize()) continue;

                    double score = budget.calculatePriority(pixelSize, distSq);
                    RenderBudget.RenderCandidate candidate = new RenderBudget.RenderCandidate(deco, pixelSize, distSq, score);

                    if (!useHeap) {
                        if (candidateList.size() < maxSubmissions) {
                            candidateList.add(candidate);
                        } else {
                            useHeap = true;
                            topKHeap.addAll(candidateList);
                            candidateList.clear();
                            if (score > topKHeap.peek().priorityScore()) {
                                topKHeap.poll();
                                topKHeap.add(candidate);
                            }
                        }
                    } else {
                        if (score > topKHeap.peek().priorityScore()) {
                            topKHeap.poll();
                            topKHeap.add(candidate);
                        }
                    }
                }
            }

            if (useHeap) {
                candidateList.addAll(topKHeap);
            }
            candidateList.sort(RenderBudget.PRIORITY_COMPARATOR);

            totalRenderCandidates += candidateList.size();
        }

        long totalQueryTimeMs = (System.nanoTime() - startQuery) / 1_000_000;
        double avgFrameTimeMs = (double) totalQueryTimeMs / frames;

        System.out.println(String.format(
            "=== Spatial Indexing & Top-K Microbenchmark Results ===\n" +
            "Total Decorations: %d\n" +
            "Ingest Time: %d ms\n" +
            "100 Frames Traversal & Top-K Time: %d ms (Avg: %.2f ms / frame)\n" +
            "Selected Candidates per frame: %d\n",
            TOTAL_DECORATIONS, ingestTimeMs, totalQueryTimeMs, avgFrameTimeMs, totalRenderCandidates / frames
        ));

        // Average frame query latency for 50,000 decorations should be well under 10ms
        assertTrue(avgFrameTimeMs < 10.0, "Average query latency too high: " + avgFrameTimeMs + " ms");
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

    @Test
    public void benchmarkDense20kDecorationsSingleCellSnapshotIngestion() {
        final int COUNT = 20_000;
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        Identifier typeId = Identifier.fromNamespaceAndPath("minecraft", "painting");

        ClientDecorationWorld world = new ClientDecorationWorld(dim);
        List<DecorationRecord> records = new ArrayList<>(COUNT);

        // Put all 20,000 decorations inside a single cell (e.g. 0..63 x and z)
        for (int i = 0; i < COUNT; i++) {
            int x = i % 60;
            int z = (i / 60) % 60;
            int y = 60 + (i / 3600);
            BlockPos pos = new BlockPos(x, y, z);
            DecorationId id = new DecorationId(typeId, dim, pos);
            AABB bounds = new AABB(x, y, z, x + 1, y + 1, z + 0.1);
            records.add(new DecorationRecord(id, bounds, 1L, new byte[]{1}));
        }

        long startIngest = System.nanoTime();
        world.putSnapshot(0, 0, 1L, records);
        long ingestTimeMs = (System.nanoTime() - startIngest) / 1_000_000;

        System.out.println(String.format("Dense 20k decorations in single cell snapshot ingestion time: %d ms", ingestTimeMs));

        assertEquals(COUNT, world.getTotalDecorationsCount());
        ClientDecorationRegion region = world.getRegion(0, 0);
        assertNotNull(region);
        assertNotNull(region.bounds());

        // Linear bulk ingestion of 20,000 dense items should take well under 100ms
        assertTrue(ingestTimeMs < 100, "Dense snapshot ingestion took too long: " + ingestTimeMs + " ms");
    }
}

