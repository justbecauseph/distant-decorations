# Distant Decorations — Implementation Progress

## Overall Status: v0.1 Core Refined & Validated

### Architectural & Correctness Achievements

- [x] **Multipart Region Snapshots**
  - Updated [`S2CRegionSnapshot`](file:///C:/Users/markj/source/repos/distant-decorations/src/main/java/me/justbecause/distantdecorations/network/s2c/S2CRegionSnapshot.java) with `partIndex` and `partCount`.
  - Added atomic client accumulation in [`ClientDecorationWorld`](file:///C:/Users/markj/source/repos/distant-decorations/src/client/java/me/justbecause/distantdecorations/client/spatial/ClientDecorationWorld.java) preventing partial region exposure to rendering.
- [x] **Ingestion-Time Pre-Decoding (Zero Render Deserialization)**
  - Implemented [`ClientDecoration`](file:///C:/Users/markj/source/repos/distant-decorations/src/client/java/me/justbecause/distantdecorations/client/spatial/ClientDecoration.java) to deserialize network byte payloads once at ingestion and cache renderer lookups.
  - Removed payload decoding from the render loop entirely.
- [x] **Provider Architecture Decoupling**
  - Distant Decorations is now purely the core framework & API ([`DecorationProvider`](file:///C:/Users/markj/source/repos/distant-decorations/src/main/java/me/justbecause/distantdecorations/api/DecorationProvider.java), [`DecorationClientRenderer`](file:///C:/Users/markj/source/repos/distant-decorations/src/client/java/me/justbecause/distantdecorations/api/client/DecorationClientRenderer.java), [`DecorationRegistry`](file:///C:/Users/markj/source/repos/distant-decorations/src/main/java/me/justbecause/distantdecorations/api/DecorationRegistry.java)).
  - Removed internal reflective provider registrations and duplicate thumbnail scheduler cache.
- [x] **Server Region Residency & Periodic Dirty Flushing**
  - Implemented access tracking and region residency eviction policy in [`ServerDecorationWorldIndex`](file:///C:/Users/markj/source/repos/distant-decorations/src/main/java/me/justbecause/distantdecorations/server/storage/ServerDecorationWorldIndex.java).
  - Added periodic dirty region flushing (every 5 seconds) to prevent data loss on crashes.
- [x] **Reconciliation Change Detection**
  - Made [`ServerDecorationWorldIndex.publish`](file:///C:/Users/markj/source/repos/distant-decorations/src/main/java/me/justbecause/distantdecorations/server/storage/ServerDecorationWorldIndex.java) a no-op when decoration bounds and payload are identical, eliminating chunk load churn and delta spam.
- [x] **Server-Authoritative Subscriptions & Clamping**
  - Enforced server-authoritative player position in [`ServerNetworkManager`](file:///C:/Users/markj/source/repos/distant-decorations/src/main/java/me/justbecause/distantdecorations/server/ServerNetworkManager.java) and clamped requested radius between 16 and 256 chunks.
- [x] **True Byte-Budgeted Snapshot Queue**
  - Enforced per-player tick byte budgeting (128 KiB/tick) queueing individual snapshot parts in [`ServerNetworkManager`](file:///C:/Users/markj/source/repos/distant-decorations/src/main/java/me/justbecause/distantdecorations/server/ServerNetworkManager.java).
- [x] **Persistent Region Revisions**
  - Authoritative monotonic revision tracking per region persisted in binary region files.
- [x] **Bounded Top-K Selection & Zero-Allocation Hot Path**
  - Integrated bounded min-heap top-K selection in [`DecorationRenderManager`](file:///C:/Users/markj/source/repos/distant-decorations/src/client/java/me/justbecause/distantdecorations/client/render/DecorationRenderManager.java) reducing sort complexity to $O(N \log K)$.
  - Direct cell traversal and persistent object set reuse eliminating per-frame heap allocations.
- [x] **Dynamic Vertical Region Bounds**
  - Dynamic aggregate bounding box in [`ClientDecorationRegion`](file:///C:/Users/markj/source/repos/distant-decorations/src/client/java/me/justbecause/distantdecorations/client/spatial/ClientDecorationRegion.java) supporting custom dimension vertical ranges.
- [x] **Server-Side Snapshot Barrier & Delta Ordering**
  - Implemented explicit region streaming states (`desiredRegions`, `streamingRegions`, `syncedRegions`) in [`ServerNetworkManager`](file:///C:/Users/markj/source/repos/distant-decorations/src/main/java/me/justbecause/distantdecorations/server/ServerNetworkManager.java).
  - Buffered deltas behind in-flight multipart snapshots, preventing race conditions where deltas overtake incomplete baselines.
  - Added client-side delta buffering in [`ClientDecorationWorld`](file:///C:/Users/markj/source/repos/distant-decorations/src/client/java/me/justbecause/distantdecorations/client/spatial/ClientDecorationWorld.java) for complete defense-in-depth.
- [x] **Subscription Movement Continuity**
  - Preserved active streaming regions and pending materialization jobs across movement subscription updates.
  - Unloads out-of-range regions and cancels pending jobs without discarding still-desired regions.
- [x] **Progressive Snapshot Construction Budget**
  - Replaced synchronous mass region materialization with bounded per-tick materialization jobs (`MAX_REGIONS_MATERIALIZED_PER_TICK = 2`).
  - Filtered streamed decorations by client's `supportedTypes` from `C2SClientHello`.
  - Enforced strict hard bandwidth budgeting (128 KiB/tick) with packet chunking sized by encoded bytes (~32 KiB/part).
- [x] **Linear-Time Bulk Snapshot Ingestion**
  - Added `putBulk()` in [`ClientDecorationRegion`](file:///C:/Users/markj/source/repos/distant-decorations/src/client/java/me/justbecause/distantdecorations/client/spatial/ClientDecorationRegion.java) and `addWithoutBoundsRecalc()` in [`DecorationRenderCell`](file:///C:/Users/markj/source/repos/distant-decorations/src/client/java/me/justbecause/distantdecorations/client/spatial/DecorationRenderCell.java).
  - Reduced dense cell ingestion complexity from $O(N^2)$ to $O(N)$ (20,000 dense decorations ingested in **13 ms**).
- [x] **Strict 5-Second Periodic Maintenance Cadence**
  - Enforced `MAINTENANCE_INTERVAL_TICKS = 100` (5.0s at 20 TPS) in [`ServerDecorationWorldIndex`](file:///C:/Users/markj/source/repos/distant-decorations/src/main/java/me/justbecause/distantdecorations/server/storage/ServerDecorationWorldIndex.java), preventing synchronous save loops on level ticks.
- [x] **Live-BE Handoff Chunk Event Wiring**
  - Hooked `ClientChunkEvents.CHUNK_LOAD` and `CHUNK_UNLOAD` into [`LiveHandoffTracker`](file:///C:/Users/markj/source/repos/distant-decorations/src/client/java/me/justbecause/distantdecorations/client/render/LiveHandoffTracker.java) in [`ClientNetworkManager`](file:///C:/Users/markj/source/repos/distant-decorations/src/client/java/me/justbecause/distantdecorations/client/network/ClientNetworkManager.java).
- [x] **Comprehensive Test Suite & Microbenchmarks**
  - Full unit test suite in [`CoreApiTest`](file:///C:/Users/markj/source/repos/distant-decorations/src/test/java/me/justbecause/distantdecorations/api/CoreApiTest.java), [`SpatialIndexTest`](file:///C:/Users/markj/source/repos/distant-decorations/src/test/java/me/justbecause/distantdecorations/spatial/SpatialIndexTest.java), [`ServerStorageAndNetworkTest`](file:///C:/Users/markj/source/repos/distant-decorations/src/test/java/me/justbecause/distantdecorations/server/ServerStorageAndNetworkTest.java), [`ProviderTest`](file:///C:/Users/markj/source/repos/distant-decorations/src/test/java/me/justbecause/distantdecorations/provider/ProviderTest.java).
  - Out-of-order 1200-record assembly test (`part 2 -> part 0 -> part 1 = 1200`).
  - Snapshot R10 part 0 + Delta R11 + Snapshot R10 parts 1/2 integration test.
  - Stale revision R9 rejection test.
  - Spatial indexing microbenchmark in [`ScaleBenchmarkTest`](file:///C:/Users/markj/source/repos/distant-decorations/src/test/java/me/justbecause/distantdecorations/benchmark/ScaleBenchmarkTest.java) (50,000 active decorations traversed and top-K selected in **2.47 ms/frame**; 20,000 dense single-cell records ingested in **13 ms**).

