# Distant Decorations — Port & Execution Progress

## Executive Summary & Status

- **Current Branch**: `port/minecraft-26.3`
- **Starting Revision**: `91238d40ed70779f0e5172c0808fe0b7fc03bb79` (derived from inspected revision `1a7fba91c3561a3264e6d2ffce68b146f02f5930`)
- **Current Phase**: Phase 0 Complete, Phase 1 & Phase 1.1 Complete (Persistence, Lifecycle, Dependency, and Test Hardening).
- **Review Gate**: Keeping Minecraft at `26.2` for baseline verification before initiating Phase 2 (26.3 dependency/API port).
- **Toolchain Tuple Reconciliation**:
  - JDK: `25.0.4+7-LTS` (Azul Zulu, `C:\Program Files\Zulu\zulu-25`)
  - Gradle: `9.5.1`
  - Loom: `1.17.20` (`1.17-SNAPSHOT`)
  - Target Minecraft: `26.2`
  - Mod Version: `0.2.0`
  - *Initial Phase 0 Inspected Baseline*: Fabric Loader `0.19.3` / Fabric API `0.158.0+26.2`
  - *Reviewed Baseline (commit `24410a0`)*: Fabric Loader `0.19.5` / Fabric API `0.160.0+26.2`

---

## Phase 0 — Baseline & Dependency Isolation

### 1. Baseline Test Execution
- **Original Unit Tests**: 29 tests across 5 test classes:
  - `CoreApiTest`: 5 tests
  - `ScaleBenchmarkTest`: 4 tests
  - `ProviderTest`: 3 tests
  - `ServerStorageAndNetworkTest`: 7 tests
  - `SpatialIndexTest`: 10 tests
  - *Result*: All 29 passed.
- **Original GameTests**: 4 tests executed in batch 0:
  - `net.minecraft.gametest.framework.BuiltinTestFunctions.ALWAYS_PASS` (Minecraft built-in)
  - `testProviderCaptureAndPublish` (DD)
  - `testBlockEntityLifecycleRemovalDoesNotDeletePersistentRecord` (DD)
  - `testVoxyCoexistence` (DD)
  - *Result*: All 4 passed.
- **Flaws Identified in Baseline Tests**:
  - `testProviderCaptureAndPublish` registered a dummy provider where `matches()` unconditionally returned `false`, only asserting that the index was non-null. It never actually captured or published a decoration record.
  - `testVoxyCoexistence` merely verified `index != null` and `level.dimension() != null`, never verifying Voxy rendering, depth composition, or presence.
  - Voxy mixin threw warning during startup: `@Mixin target org.popcraft.chunky.platform.FabricWorld was not found common.voxy.mixins.json:chunky.MixinFabricWorld from mod voxy`.
  - Spark background profiler started automatically during GameTests.

### 2. Optional Dependency Leakage [S1]
- **Finding**: In `build.gradle`, `voxy`, `sodium`, and `spark` were declared under `runtimeOnly`.
- **Impact**:
  - Leaked into `runtimeClasspath`, loading 59 mods during standard development / test runs.
  - Leaked into the published Maven POM (`pom-default.xml`) as `<scope>runtime</scope>` dependencies and into Gradle `module.json` under `runtimeElements`. Any downstream consumer declaring a dependency on `distant-decorations` would pull in Voxy, Sodium, and Spark transitively.
- **Remediation**:
  - Gated benchmark dependencies behind `enableBenchmarkMods` property in `build.gradle` (default `false`).
  - Verified `generatePomFileForMavenJavaPublication` and `generateMetadataFileForMavenJavaPublication`: POM and module metadata strictly contain only `fabric-loader` and `fabric-api`.
  - Verified `runGameTest`: mod count reduced from 59 to 44, mixin warnings and profiler overhead completely eliminated.

---

## Phase 1 — Baseline Safety & Truthful Tests

### 1. Command Authorization & Switch Semantics [S2]
- **Finding**:
  - `DistantDecorations.java` registered `/dd toggle` with no permission predicate. Ordinary players on a multiplayer server could disable the entire framework.
  - `/ddc toggle` in `DistantDecorationsClient.java` invoked `DistantDecorationsConfig.setMasterEnabled(next)`. In integrated single-player environments, this mutated the server master switch, unintentionally halting server indexing and provider filtering.
- **Remediation**:
  - Restricted `/dd toggle` to `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` (operator level 2).
  - Explicitly kept `/dd stats` public for client/operator telemetry diagnostics.
  - Added `clientRenderingEnabled` (with `isClientRenderingEnabled()` and `setClientRenderingEnabled()`) in `DistantDecorationsConfig`.
  - Updated `/ddc toggle` to toggle `clientRenderingEnabled` only, leaving server `masterEnabled` untouched.
  - Updated `DecorationRenderManager.renderFrame` to check both `isMasterEnabled()` and `isClientRenderingEnabled()`.

### 2. Failure-Safe Persistence [S3]
- **Finding**:
  - `ServerDecorationWorldIndex.saveRegionToFile()` had a `void` return type, catching all exceptions and swallowing failures.
  - `performMaintenance()` called `saveRegionToFile(region)` followed immediately by `iterator.remove()`. If writing or moving the region file failed, dirty unwritten decorations were permanently discarded from memory.
  - `loadRegionFromFile()` caught read errors and returned `null`, identical to a missing file. `getOrCreateRegion()` then created an empty region which, when saved, would overwrite the corrupted file and destroy forensics.
  - Filesystems without atomic move support would fail outright on `StandardCopyOption.ATOMIC_MOVE`.
- **Remediation**:
  - Changed `saveRegionToFile(ServerDecorationRegion)` to return `boolean`.
  - Added fallback in `saveRegionToFile` for `AtomicMoveNotSupportedException` to standard `StandardCopyOption.REPLACE_EXISTING`.
  - Updated `performMaintenance()`: if `region.isDirty()` and `!saveRegionToFile(region)`, eviction is skipped and the region is retained in memory with a warning log.
  - Updated `loadRegionFromFile()`: when a region file is corrupt or unreadable, it is quarantined to `r.X.Z.dat.corrupt.<timestamp>` rather than quietly ignored.
  - Updated `saveAll()` and `close()` to return `boolean` reflecting whether all regions persisted cleanly.

### 3. Truthful Provider & GameTests [S8]
- **Remediation**:
  - Updated `DistantDecorationsIntegrationGameTest`:
    - `testProviderCaptureAndPublish`: Rewritten to use a fixture provider matching `Blocks.BARREL`. Verifies real provider matching, decoration publishing, correct coordinate anchor and type registration in the world index, verify that re-publishing identical unchanged state is a no-op (same record reference, revision unchanged), and verifies that index removal removes the record and increments revision.
    - Provider registration cleanup: added `DecorationRegistry.unregisterProvider(Identifier)` and cleaned up test providers in `finally` blocks to prevent test pollution across batches.
    - `testVoxyCoexistence`: Renamed to accurately describe its scope, avoiding false claims of GPU/Voxy depth composition testing.

---

## Phase 1.1 — Focused Persistence, Dependency & Test Hardening

Following peer review of commit `c89dfac`, a focused Phase 1.1 pass was executed while strictly retaining Minecraft at `26.2` to resolve persistence edge-cases, structural dependency isolation, and regression rigor before proceeding to Phase 2.

### 1. Persistence Quarantine Failure & Overwrite Prevention (P1)
- **Finding**: If `Files.move(..., corruptPath)` failed during region loading (e.g. disk locks, permissions), `loadRegionFromFile()` returned `null`. `getOrCreateRegion()` interpreted `null` as an ordinary cache miss, created a blank `ServerDecorationRegion`, and subsequent flushes overwrote the un-quarantined corrupt file on disk, permanently destroying forensic evidence.
- **Remediation**:
  - Implemented `RegionLoadStatus` (`SUCCESS`, `MISSING`, `QUARANTINED`, `QUARANTINE_FAILED`, `READ_ERROR`) and `RegionLoadResult`.
  - Introduced `blockedRegions` tracking set in `ServerDecorationWorldIndex`.
  - If a file is corrupt and quarantine fails, the region key is added to `blockedRegions` and a `RegionStorageException` is thrown.
  - `getOrCreateRegion()` refuses to initialize or replace a blocked region.
  - `saveRegionToFile()` checks `blockedRegions` and refuses to write, preventing any rogue save from overwriting the corrupt file.
  - `publishTyped()` and `reconcileChunk()` catch `RegionStorageException` gracefully without crashing the server or tick loop.
  - Quarantine filenames use collision-resistant naming (`r.X.Z.dat.corrupt.<timestamp>.<uuid>`) and avoid `REPLACE_EXISTING` during quarantine moves to prevent accidental clobbering of earlier quarantine archives.
  - `moveFileToQuarantine()` hook exposed for deterministic testing of quarantine move failures.

### 2. ServerDecorationManager Lifecycle Recovery Ownership (P1)
- **Finding**: `ServerLevelEvents.UNLOAD` called `worldIndices.remove(level.dimension())` before calling `index.close()`. If `close()` failed, the index was already dropped from memory, permanently losing unsaved state with no retry mechanism. Additionally, `ServerLifecycleEvents.SERVER_STOPPING` cleared `worldIndices` unconditionally without tracking or reporting unpersisted dimensions.
- **Remediation**:
  - Introduced `pendingRecoveryIndices` queue in `ServerDecorationManager`.
  - Extracted `handleLevelUnload(dimension)`: if `index.close()` fails on level unload, the index is removed from live lookups but retained in `pendingRecoveryIndices`.
  - Implemented `retryPendingRecovery(dimension)` to allow bounded recovery attempts.
  - Extracted `handleServerStopping()`: attempts to close all live indices and retries all pending recovery indices. If any fail, a critical error is logged reporting all unpersisted dimensions, and returns `false`.

### 3. Structural, Unconditional Benchmark Dependency Isolation (P2)
- **Finding**: Declaring `voxy`, `sodium`, and `spark` under `runtimeOnly` behind `-PenableBenchmarkMods=true` still polluted `from components.java` (`runtimeElements`) whenever the property was enabled.
- **Remediation**:
  - Established a standalone `benchmarkRuntime` configuration in `build.gradle` (`canBeResolved = true; canBeConsumed = false`).
  - Gated benchmark dependencies strictly to `benchmarkRuntime`, keeping `runtimeClasspath` and `components.java` completely decoupled.
  - Added `tasks.matching { it.name == "runIntegrationClient" }.configureEach { classpath += configurations.benchmarkRuntime }` to supply benchmark mods solely to the benchmark client run task.
  - Added an active `pom.withXml` assertion in `publishing.publications.mavenJava` that fails the build if `voxy`, `sodium`, or `spark` ever enter the publication POM.
  - Verified with both `-PenableBenchmarkMods=false` and `-PenableBenchmarkMods=true`: both `pom-default.xml` and Gradle `module.json` contain strictly `fabric-loader` and `fabric-api`.

### 4. Strengthened Regression Tests & Truthful GameTest
- **`CommandAuthorizationRegressionTest`**:
  - Extracted `DistantDecorationsClient.registerClientCommands(dispatcher)` as a public static method.
  - Added tests executing the actual Brigadier client command `/ddc toggle` using a `FabricClientCommandSource` dispatcher.
  - Verified `/ddc toggle` under both server switch preconditions (`masterEnabled=true` and `masterEnabled=false`), asserting that `clientRenderingEnabled` toggles while `masterEnabled` remains unchanged.
  - Verified exact minimum operator permission: `Permissions.COMMANDS_MODERATOR` (level 1) is rejected with syntax exception, while `Permissions.COMMANDS_GAMEMASTER` (level 2) succeeds.
- **`StorageSafetyRegressionTest`**:
  - Added `testCorruptFileWithQuarantineFailurePreventsOverwrite()`: verifies that when quarantine move fails, `getOrCreateRegion()` throws `RegionStorageException`, the region is blocked, `saveRegionToFile()` refuses write, and the corrupt file remains byte-for-byte identical on disk.
  - Added `testServerDecorationManagerUnloadFailureRecovery()`: verifies unload failure retains index in `pendingRecoveryIndices` and subsequent `retryPendingRecovery()` successfully saves and clears the queue.
  - Added `testServerDecorationManagerShutdownHandlesPendingRecovery()`: verifies shutdown lifecycle persistence.
- **`DistantDecorationsIntegrationGameTest`**:
  - Added explicit payload decode assertion (`"barrel-data-payload"`).
  - Added exact bounding box assertion (`record.bounds()`).
  - Added assertion verifying that `region.revision()` does not increment on unchanged publish no-ops.
  - Truthfully renamed `testMultiDimensionWorldIndexInitialization` to `testWorldIndexInitialization` to accurately state that it tests world index initialization for the server level.

---

## Current Test Inventory & Verification Matrix

| Test Suite | File | Tests Run | Result | Notes |
|---|---|---|---|---|
| Core API | `CoreApiTest.java` | 5 | PASS | Type encoding, ID equality/hash, payload limits, network & stream codecs |
| Scale Benchmark | `ScaleBenchmarkTest.java` | 4 | PASS | Snapshot materialization, disk I/O, thumbnail invariant, render traversal |
| Provider Test | `ProviderTest.java` | 3 | PASS | Painting & picture frame data serialization, registry registration |
| Server Storage & Net | `ServerStorageAndNetworkTest.java` | 7 | PASS | C2S/S2C packet roundtrips, region streams, maintenance tick throttle |
| Spatial Index | `SpatialIndexTest.java` | 10 | PASS | Frustum culling, cell partitioning, multipart assembly, top-K selection |
| Command Authorization | `CommandAuthorizationRegressionTest.java` | 6 | PASS | Brigadier client toggle (both server switch states), exact GAMEMASTER permission boundary |
| Storage Safety | `StorageSafetyRegressionTest.java` | 6 | PASS | Dirty retention, quarantine move, quarantine failure overwrite block, manager unload recovery queue |
| **Total Unit Tests** | | **41** | **PASS** | `BUILD SUCCESSFUL in 12s` |
| Integration GameTests | `DistantDecorationsIntegrationGameTest.java` | 4 | PASS | Minecraft `ALWAYS_PASS`, truthful barrel publish/payload/bounds/revision/remove, lifecycle persistence, index init |
| **Total Automated Tests** | | **45** | **PASS** | Complete unit and GameTest suite passing |

---

## Contract Inventory & Risk Assessment

### 1. Java Provider API Contract [S11]
- **Interface**: `DecorationClientRenderer<T>` and `DecorationProvider<T>`
- **Exposed Types**: `net.minecraft.client.renderer.SubmitNodeCollector`, `com.mojang.blaze3d.vertex.PoseStack`, `me.justbecause.distantdecorations.client.spatial.ProjectionMetrics`.
- **Port Strategy**: Preserve existing signatures. Notice `ProjectionMetrics` is in the public interface package; do not move without versioning.

### 2. Wire Protocol Contract [S5, S6, S7]
- **Current Version**: `ServerNetworkManager.PROTOCOL_VERSION = 1`
- **Envelope Assessment**:
  - `S2CRegionSnapshot`, `S2CRegionDelta`, `S2CRegionUnload` currently do **not** include the `ResourceKey<Level> dimension` in their wire payload.
  - In `ClientNetworkManager`, messages are routed to `client.level.dimension()`. During in-flight inter-dimensional teleports, packets meant for the previous dimension may be applied to the new dimension.
  - In Phase 4, we will evaluate adding dimension and subscription generation to packets and bumping `PROTOCOL_VERSION` to 2.

### 3. Disk Storage Format Contract [S12]
- **Current Format**: `ServerDecorationRegion.FORMAT_VERSION = 1`
- **Magic**: `0x4445434F` (`DECO`)
- **Port Strategy**: Keep format 1 strictly preserved during the 26.3 port so existing world directories upgrade without loss.

### 4. Rendering Extraction vs Submission Boundary [S9, S10]
- **Current Hook**: `LevelRenderEvents.COLLECT_SUBMITS`
- **Fabric 26.3 Assessment**: `COLLECT_SUBMITS` is retained in Fabric API 26.3, but Fabric guidelines recommend preparing stable render state during extraction (`LevelExtractionEvents`) to avoid reading live world / camera / block entities during submission. Phase 5 will refine this boundary cleanly.

---

## Git Commit History on `port/minecraft-26.3`

1. `8f8099a` — `test: establish standalone DD baseline and lifecycle fixtures`
2. `a813e9f` — `build: isolate optional benchmark dependencies and publication metadata`
3. `80a8f80` — `fix: authorize DD server controls and retain failed-save regions`
4. `c89dfac` — `docs: record Phase 0 baseline and Phase 1 regression evidence in PROGRESS.md`

---

## Review Gate Sign-off (Phase 0, Phase 1 & Phase 1.1)

- [x] Baseline and regression test counts recorded and passing (41 unit tests, 4 GameTests = 45 automated tests).
- [x] Benchmark dependencies structurally isolated in `benchmarkRuntime` configuration; POM and module metadata verified clean under both `-PenableBenchmarkMods=false` and `-PenableBenchmarkMods=true`.
- [x] Command authorization verified via Brigadier for `/dd toggle` (exact GAMEMASTER level 2 requirement) and `/ddc toggle` (independent client switch under both server states).
- [x] Storage failure safety verified: dirty retention on failed save, corrupt file quarantine, quarantine failure overwrite prevention with byte-for-byte preservation, manager unload recovery queue.
- [x] Truthful test fixtures: barrel provider asserts decoded payload string, exact bounds, and revision invariance on unchanged publish.
- [x] Toolchain baseline discrepancy reconciled in documentation (initial `0.19.3`/`0.158.0+26.2` vs reviewed `0.19.5`/`0.160.0+26.2`).
- [x] Minecraft version strictly preserved at `26.2` for baseline gate.
- [x] Ready for Phase 2 toolchain and dependency bump to 26.3 upon user approval.
