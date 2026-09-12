# Distant Decorations — Port & Execution Progress

## Executive Summary & Status

- **Current Branch**: `port/minecraft-26.3`
- **Starting Revision**: `91238d40ed70779f0e5172c0808fe0b7fc03bb79` (derived from inspected revision `1a7fba91c3561a3264e6d2ffce68b146f02f5930`)
- **Current Phase**: Phase 0 Complete, Phase 1 Regression Coverage Established & Baseline Safety Fixes Applied.
- **Review Gate**: Keeping Minecraft at `26.2` for baseline verification before initiating Phase 2 (26.3 dependency/API port).
- **Toolchain Tuple**:
  - JDK: `25.0.4+7-LTS` (Azul Zulu, `C:\Program Files\Zulu\zulu-25`)
  - Gradle: `9.5.1`
  - Loom: `1.17.20` (`1.17-SNAPSHOT`)
  - Fabric Loader: `0.19.3` / `0.19.5`
  - Target Minecraft: `26.2`
  - Fabric API: `0.158.0+26.2`
  - Mod Version: `0.2.0`

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
  - Verified `generatePomFileForMavenJavaPublication` and `generateMetadataFileForMavenJavaPublication`: POM and module metadata now strictly contain only `fabric-loader` and `fabric-api`.
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
- **Regression Coverage**:
  - Added `CommandAuthorizationRegressionTest`:
    - `testNonOperatorCannotExecuteServerToggle()`: asserts syntax error/disallowed when executed by player without permissions.
    - `testOperatorCanExecuteServerToggle()`: asserts operator successfully toggles server master state.
    - `testOrdinaryPlayerCanExecuteStatsCommand()`: asserts non-op player can read stats.
    - `testClientToggleIsIndependentOfServerMasterSwitch()`: asserts client toggle does not affect server switch.

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
- **Regression Coverage**:
  - Added `StorageSafetyRegressionTest`:
    - `testFailedSaveDoesNotEvictDirtyRegion()`: verifies dirty region retention upon save failure.
    - `testCorruptRegionFileIsQuarantined()`: verifies invalid magic/corrupt file is moved to `.corrupt.<timestamp>` and original path cleared.
    - `testSuccessfulSaveEvictsCleanRegionAfterResidencyTimeout()`: verifies normal residency eviction occurs cleanly upon successful save.

### 3. Truthful Provider & GameTests [S8]
- **Remediation**:
  - Updated `DistantDecorationsIntegrationGameTest`:
    - `testProviderCaptureAndPublish`: Rewritten to use a fixture provider matching `Blocks.BARREL`. Verifies real provider matching, decoration publishing, correct coordinate anchor and type registration in the world index, verify that re-publishing identical unchanged state is a no-op (same record reference, revision unchanged), and verifies that index removal removes the record and increments revision.
    - Provider registration cleanup: added `DecorationRegistry.unregisterProvider(Identifier)` and cleaned up test providers in `finally` blocks to prevent test pollution across batches.
    - `testVoxyCoexistence`: Renamed to `testMultiDimensionWorldIndexInitialization` to accurately state that it tests multi-dimensional server index initialization, avoiding false claims of GPU/Voxy depth composition testing.

---

## Current Test Inventory & Verification Matrix

| Test Suite | File | Tests Run | Result | Notes |
|---|---|---|---|---|
| Core API | `CoreApiTest.java` | 5 | PASS | Type encoding, ID equality/hash, payload limits, network & stream codecs |
| Scale Benchmark | `ScaleBenchmarkTest.java` | 4 | PASS | Snapshot materialization, disk I/O, thumbnail invariant, render traversal |
| Provider Test | `ProviderTest.java` | 3 | PASS | Painting & picture frame data serialization, registry registration |
| Server Storage & Net | `ServerStorageAndNetworkTest.java` | 7 | PASS | C2S/S2C packet roundtrips, region streams, maintenance tick throttle |
| Spatial Index | `SpatialIndexTest.java` | 10 | PASS | Frustum culling, cell partitioning, multipart assembly, top-K selection |
| Command Authorization | `CommandAuthorizationRegressionTest.java` | 4 | PASS | Operator permission check, stats accessibility, client/server toggle independence |
| Storage Safety | `StorageSafetyRegressionTest.java` | 3 | PASS | Dirty region retention on failed save, corrupt file quarantine, timeout eviction |
| **Total Unit Tests** | | **36** | **PASS** | `BUILD SUCCESSFUL in 10s` |
| Integration GameTests | `DistantDecorationsIntegrationGameTest.java` | 4 | PASS | Minecraft `ALWAYS_PASS`, truthful fixture publish/noop/remove, lifecycle persistence, multi-dim index |

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

---

## Review Gate Sign-off (Phase 0 & Phase 1)

- [x] Baseline test counts recorded and passing (36 unit tests, 4 GameTests).
- [x] Optional dependency leakage isolated from POM, module metadata, and runtime classpath.
- [x] Command authorization implemented and verified for `/dd toggle` and `/dd stats`.
- [x] Independent client rendering toggle established for `/ddc toggle`.
- [x] Storage failure safety verified (dirty regions retained on error; corrupt files quarantined).
- [x] Truthful test fixtures created and passing in server GameTest runner.
- [x] Minecraft version strictly preserved at `26.2` for baseline gate.
- [x] No production saves or Maven repositories modified; ready for Phase 2 toolchain bump.
