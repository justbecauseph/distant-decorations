package me.justbecause.distantdecorations.benchmark;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.client.render.RenderBudget;
import me.justbecause.distantdecorations.client.spatial.ClientDecoration;
import me.justbecause.distantdecorations.client.spatial.ClientDecorationRegion;
import me.justbecause.distantdecorations.client.spatial.ClientDecorationWorld;
import me.justbecause.distantdecorations.client.spatial.DecorationRenderCell;
import me.justbecause.distantdecorations.client.spatial.ProjectionMetrics;
import me.justbecause.distantdecorations.network.s2c.S2CRegionSnapshot;
import me.justbecause.distantdecorations.server.ServerNetworkManager;
import me.justbecause.distantdecorations.server.storage.ServerDecorationRegion;
import me.justbecause.distantdecorations.server.storage.ServerDecorationWorldIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ScaleBenchmarkTest {

    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
    private static final Identifier PAINTING_TYPE = Identifier.fromNamespaceAndPath("minecraft", "painting");
    private static final Identifier CAMERAPTURE_TYPE = Identifier.fromNamespaceAndPath("camerapture", "picture_frame");

    // =========================================================================
    // 1. Client Spatial Traversal, Culling, & Top-K Microbenchmarks (1k, 10k, 50k)
    // =========================================================================

    @Test
    public void benchmarkClientRenderPipelineAcrossScales() {
        int[] datasetSizes = {1_000, 10_000, 50_000};
        int[] visibleBudgetCap = {100, 500, 2000};

        System.out.println("\n================================================================================");
        System.out.println("  CLIENT RENDER PIPELINE BENCHMARK (Traversal, Spatial Culling, Heap Top-K)");
        System.out.println("================================================================================");

        for (int totalDecos : datasetSizes) {
            ClientDecorationWorld world = new ClientDecorationWorld(OVERWORLD);
            List<DecorationRecord> records = new ArrayList<>(totalDecos);

            for (int i = 0; i < totalDecos; i++) {
                int x = i % 512;
                int z = (i / 512) % 512;
                int y = 60 + (i / (512 * 512));
                BlockPos pos = new BlockPos(x, y, z);
                DecorationId id = new DecorationId(PAINTING_TYPE, OVERWORLD, pos);
                AABB bounds = new AABB(x, y, z, x + 2, y + 2, z + 0.1);
                records.add(new DecorationRecord(id, bounds, 1L, new byte[]{1, 2, 3, 4}));
            }

            long startIngest = System.nanoTime();
            world.putSnapshot(0, 0, 1L, records);
            long ingestTimeUs = (System.nanoTime() - startIngest) / 1_000;

            for (int maxVisible : visibleBudgetCap) {
                Vec3 cameraPos = new Vec3(256.0, 70.0, -100.0);
                ProjectionMetrics metrics = ProjectionMetrics.of(cameraPos, 1920, 1080, 70.0);
                RenderBudget budget = new RenderBudget();
                budget.setMaxSubmissionsPerFrame(maxVisible);
                budget.setMinProjectedPixelSize(1.0);

                int warmupFrames = 50;
                int measuredFrames = 200;
                long[] frameTimesNs = new long[measuredFrames];

                List<RenderBudget.RenderCandidate> candidateList = new ArrayList<>(maxVisible + 64);
                PriorityQueue<RenderBudget.RenderCandidate> topKHeap = new PriorityQueue<>(maxVisible + 64, RenderBudget.MIN_HEAP_COMPARATOR);

                for (int f = 0; f < warmupFrames + measuredFrames; f++) {
                    long fStart = System.nanoTime();
                    candidateList.clear();
                    topKHeap.clear();
                    boolean useHeap = false;

                    ClientDecorationRegion region = world.getRegion(0, 0);
                    for (DecorationRenderCell cell : region.getCells()) {
                        if (cell.isEmpty()) continue;
                        if (metrics.getDistanceSq(cell.getBounds()) > 1024.0 * 1024.0) continue;

                        for (ClientDecoration deco : cell.getDecorations()) {
                            double distSq = metrics.getDistanceSq(deco.bounds());
                            if (distSq > budget.getMaxRenderDistance() * budget.getMaxRenderDistance()) continue;

                            double pixelSize = metrics.calculateProjectedPixelSize(deco.bounds());
                            if (pixelSize < budget.getMinProjectedPixelSize()) continue;

                            double score = budget.calculatePriority(pixelSize, distSq);
                            RenderBudget.RenderCandidate candidate = new RenderBudget.RenderCandidate(deco, pixelSize, distSq, score);

                            if (!useHeap) {
                                if (candidateList.size() < maxVisible) {
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

                    long fDuration = System.nanoTime() - fStart;
                    if (f >= warmupFrames) {
                        frameTimesNs[f - warmupFrames] = fDuration;
                    }
                }

                Arrays.sort(frameTimesNs);
                double p50Us = frameTimesNs[(int) (measuredFrames * 0.50)] / 1_000.0;
                double p95Us = frameTimesNs[(int) (measuredFrames * 0.95)] / 1_000.0;
                double p99Us = frameTimesNs[(int) (measuredFrames * 0.99)] / 1_000.0;
                double maxUs = frameTimesNs[measuredFrames - 1] / 1_000.0;

                System.out.printf(
                    "[%6d Decos | Cap: %4d] Ingest: %6.2f ms | Query Latency -> p50: %6.2f µs | p95: %6.2f µs | p99: %6.2f µs | max: %6.2f µs\n",
                    totalDecos, maxVisible, ingestTimeUs / 1000.0, p50Us, p95Us, p99Us, maxUs
                );

                // Frame query latency must remain well under 5ms even for 50k decorations
                assertTrue(p99Us < 5000.0, "p99 frame query latency exceeded 5ms: " + p99Us + " µs");
            }
        }
    }

    // =========================================================================
    // 2. Server Disk I/O Latency Benchmark (Synchronous Save & Load Analysis)
    // =========================================================================

    @Test
    public void benchmarkServerDiskIOAndCacheBehavior() throws Exception {
        System.out.println("\n================================================================================");
        System.out.println("  SERVER DISK I/O LATENCY BENCHMARK (Cold Load vs Warm Residency vs Save)");
        System.out.println("================================================================================");

        Path tempDir = Files.createTempDirectory("dd_bench_io");
        try {
            int[] testSizes = {1_000, 10_000, 50_000};

            for (int count : testSizes) {
                ServerDecorationWorldIndex index = new ServerDecorationWorldIndex(null, tempDir);
                ServerDecorationRegion region = index.getOrCreateRegion(0, 0);

                for (int i = 0; i < count; i++) {
                    BlockPos pos = new BlockPos(i % 512, 64, (i / 512) % 512);
                    DecorationId id = new DecorationId(PAINTING_TYPE, OVERWORLD, pos);
                    AABB bounds = new AABB(pos.getX(), 64, pos.getZ(), pos.getX() + 1, 65, pos.getZ() + 0.1);
                    region.addOrUpdate(new DecorationRecord(id, bounds, 1L, new byte[]{1, 2, 3, 4}));
                }

                // 1. Measure synchronous save time
                long startSave = System.nanoTime();
                index.saveRegionToFile(region);
                long saveTimeUs = (System.nanoTime() - startSave) / 1_000;

                Path file = tempDir.resolve("r.0.0.dat");
                long fileSizeBytes = Files.size(file);

                // 2. Measure cold read time (fresh index)
                long startColdLoad = System.nanoTime();
                ServerDecorationWorldIndex coldIndex = new ServerDecorationWorldIndex(null, tempDir);
                ServerDecorationRegion coldRegion = coldIndex.getOrCreateRegion(0, 0);
                long coldLoadTimeUs = (System.nanoTime() - startColdLoad) / 1_000;

                // 3. Measure warm memory lookup
                long startWarm = System.nanoTime();
                ServerDecorationRegion warmRegion = coldIndex.getOrCreateRegion(0, 0);
                long warmLookupTimeNs = System.nanoTime() - startWarm;

                assertEquals(count, coldRegion.size());
                assertNotNull(warmRegion);

                System.out.printf(
                    "[%6d Decos] File Size: %6.1f KiB | Sync Save: %6.2f ms | Cold Disk Load: %6.2f ms | Warm Lookup: %4d ns\n",
                    count, fileSizeBytes / 1024.0, saveTimeUs / 1000.0, coldLoadTimeUs / 1000.0, warmLookupTimeNs
                );
            }
        } finally {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    // =========================================================================
    // 3. Materialization Budget: Realistic vs Dense 50k Single Region
    // =========================================================================

    @Test
    public void benchmarkSnapshotMaterializationWorkload() {
        System.out.println("\n================================================================================");
        System.out.println("  SNAPSHOT MATERIALIZATION WORKLOAD BENCHMARK (Filtering, Chunking, Budgeting)");
        System.out.println("================================================================================");

        int[] regionCounts = {1_000, 10_000, 50_000};
        for (int total : regionCounts) {
            List<DecorationRecord> records = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                BlockPos pos = new BlockPos(i % 512, 64, (i / 512) % 512);
                DecorationId id = new DecorationId(PAINTING_TYPE, OVERWORLD, pos);
                AABB bounds = new AABB(pos.getX(), 64, pos.getZ(), pos.getX() + 1, 65, pos.getZ() + 0.1);
                records.add(new DecorationRecord(id, bounds, 10L, new byte[]{1, 2, 3, 4}));
            }

            int maxRecordsPerPart = ServerNetworkManager.MAX_RECORDS_PER_SNAPSHOT_PART;
            int maxBytesPerPart = ServerNetworkManager.MAX_BYTES_PER_SNAPSHOT_PART;

            // Warmup loop for JIT compilation
            for (int w = 0; w < 10; w++) {
                runMaterializationSim(records, maxRecordsPerPart, maxBytesPerPart);
            }

            // Measured run
            long start = System.nanoTime();
            List<S2CRegionSnapshot> packets = runMaterializationSim(records, maxRecordsPerPart, maxBytesPerPart);
            long elapsedUs = (System.nanoTime() - start) / 1_000;
            int partCount = packets.size();

            System.out.printf(
                "[%6d Decos] Materialization Time: %6.2f ms | Generated Parts: %3d packets (Avg: %d recs/pkt)\n",
                total, elapsedUs / 1000.0, partCount, total / partCount
            );

            // Materialization for 50k dense items after JIT should take less than 25ms
            assertTrue(elapsedUs < 25_000, "Materialization took too long: " + elapsedUs + " µs");
        }
    }

    private static List<S2CRegionSnapshot> runMaterializationSim(List<DecorationRecord> records, int maxRecordsPerPart, int maxBytesPerPart) {
        List<List<DecorationRecord>> parts = new ArrayList<>();
        List<DecorationRecord> currentPart = new ArrayList<>();
        int currentBytes = 0;

        for (DecorationRecord r : records) {
            int recordBytes = 64 + (r.payload() != null ? r.payload().length : 0);
            if (!currentPart.isEmpty() && (currentPart.size() >= maxRecordsPerPart || currentBytes + recordBytes > maxBytesPerPart)) {
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
            packets.add(new S2CRegionSnapshot(0, 0, 10L, p, partCount, parts.get(p)));
        }
        return packets;
    }

    // =========================================================================
    // 4. Camerapture Thumbnail Invariant vs Full-Image Payload Memory Footprint
    // =========================================================================

    @Test
    public void benchmarkCameraptureThumbnailInvariant() {
        System.out.println("\n================================================================================");
        System.out.println("  CAMERAPTURE THUMBNAIL-ONLY INVARIANT BENCHMARK");
        System.out.println("================================================================================");

        // 32x32 RGB thumbnail vs 1920x1080 Full Picture
        int thumbnailPayloadBytes = 32 * 32 * 3; // ~3 KiB
        int fullPictureBytes = 1920 * 1080 * 4; // ~8.29 MiB

        assertTrue(thumbnailPayloadBytes <= DecorationRecord.MAX_PAYLOAD_BYTES, "Thumbnail must fit within 16 KiB ceiling");
        assertTrue(fullPictureBytes > DecorationRecord.MAX_PAYLOAD_BYTES, "Full picture must exceed 16 KiB ceiling and never be sent via DD channel");

        byte[] thumbnailPayload = new byte[thumbnailPayloadBytes];
        Arrays.fill(thumbnailPayload, (byte) 128);

        DecorationId id = new DecorationId(CAMERAPTURE_TYPE, OVERWORLD, new BlockPos(100, 64, 100));
        AABB bounds = new AABB(100, 64, 100, 101, 65, 100.1);
        DecorationRecord thumbnailRecord = new DecorationRecord(id, bounds, 1L, thumbnailPayload);

        assertEquals(thumbnailPayloadBytes, thumbnailRecord.payload().length);
        System.out.printf("Camerapture 32x32 Thumbnail Payload: %6.2f KiB (Within %d KiB limit)\n", thumbnailPayloadBytes / 1024.0, DecorationRecord.MAX_PAYLOAD_BYTES / 1024);
        System.out.printf("Full-Resolution Image Payload:       %6.2f MiB (Bypassed via external picture store)\n", fullPictureBytes / (1024.0 * 1024.0));
    }
}


