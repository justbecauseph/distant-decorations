# Agent Plan — Distant Decorations

## Objective

Build a new Fabric mod, working name:

```text
Distant Decorations
mod id: distantdecorations
```

Its responsibility is to render lightweight representations of BE-backed decorations **outside vanilla client chunk distance**, especially when terrain remains visible through Voxy or Distant Horizons.

Initial providers:

```text
Fast Paintings
Camerapture
```

Supporting ecosystem:

```text
Chunky → server-side index population
Voxy / DH → distant terrain
OBE → optimization of loaded/static block entities
```

The architecture must remain independent of all three.

---

# Architecture

```text
                         SERVER
                            │
               Persistent Decoration Index
                            │
        ┌───────────────────┼────────────────────┐
        │                   │                    │
     Chunky             Chunk Load          Live Mutation
    ingestion         reconciliation         hooks
        │                   │                    │
        └───────────────────┼────────────────────┘
                            │
                     Region Streaming
                            │
                            ▼
                         CLIENT
                            │
                    Spatial Render Index
                            │
                      Cell Frustum
                            │
                     Object Frustum
                            │
                   Projected Size LOD
                            │
                      Render Budget
                            │
               ┌────────────┴─────────────┐
               │                          │
        Fast Paintings               Camerapture
         FAR/SIMPLE                 THUMBNAIL ONLY
               │                          │
               └────────────┬─────────────┘
                            ▼
                       depth-tested
                            │
                 Voxy / DH / Vanilla
```

Inside vanilla chunk distance:

```text
live chunk loaded
      │
      ├─ OBE-supported static BE → terrain mesh
      ├─ Fast Paintings           → Fast Paintings BER
      ├─ Camerapture              → Camerapture BER
      └─ vanilla/other BE         → normal BER
```

---

# Critical architectural rules

The agent must preserve these invariants:

```text
1. Never keep remote Minecraft chunks loaded for rendering.
2. Never construct fake BlockEntities for distant objects.
3. Never use Voxy's database as decoration storage.
4. Never depend on OBE internals for distant rendering.
5. Never request Camerapture FULL images from the distant renderer.
6. Never scan the complete decoration index every frame.
7. Never request textures before frustum + screen-size rejection.
8. Never allow GPU work to scale with total synchronized objects.
9. Chunky is an ingestion source, not a runtime dependency.
10. OBE is a coexistence/compatibility target, not a provider backend.
```

---

# Phase 0 — Rendering spike

Before implementing networking or persistence, prove the concept.

Render a hard-coded textured quad at a position whose vanilla chunk is not loaded.

Test with:

```text
Vanilla
Sodium
Voxy
Iris
OBE
```

Validate:

```text
object visible at distant location
correct world position
terrain depth occlusion works
doesn't render through mountains/walls
camera/frustum behavior correct
no Z-fighting at wall surface
OBE does not interfere
```

### Gate

Do not proceed until:

```text
Voxy terrain + distant decoration depth ordering works
```

---

# Phase 1 — Core API

Implement:

```java
DecorationId
DecorationRecord
DecorationType
DecorationProvider<T>
DecorationClientRenderer<T>
DecorationRegistry
```

Suggested identity:

```java
record DecorationId(
    ResourceLocation type,
    ResourceKey<Level> dimension,
    BlockPos anchor
) {}
```

Record:

```java
record DecorationRecord(
    DecorationId id,
    AABB bounds,
    long revision,
    byte[] payload
) {}
```

Provider API owns payload semantics.

Core only knows:

```text
identity
bounds
revision
payload bytes
```

---

# Phase 2 — Client spatial renderer

Implement:

```text
ClientDecorationWorld
ClientDecorationRegion
DecorationRenderCell
ProjectionMetrics
RenderBudget
DecorationRenderManager
```

Recommended layout:

```text
network region: 32×32 chunks
render cell:      4×4 chunks
```

Frame pipeline:

```text
camera
  ↓
candidate render cells
  ↓
cell frustum
  ↓
object AABB frustum
  ↓
projected pixel size
  ↓
SKIP
  ↓
live-BE suppression
  ↓
priority/budget
  ↓
provider enqueue
  ↓
provider flush/batch
```

No texture/resource access before visibility acceptance.

---

# Phase 3 — Render batching API

Do not lock the renderer into:

```text
1 object = 1 draw call
```

Provider interface should support:

```java
beginFrame(...)
enqueue(...)
flush(...)
```

Fast Paintings should eventually batch aggressively.

Camerapture may initially remain per-texture/per-frame submission but must be architecturally replaceable with:

```text
thumbnail atlas
texture array
other batching strategy
```

later.

---

# Phase 4 — Persistent server index

Implement per-dimension persistent index.

Initial storage:

```text
SavedData
    └── DecorationRegion
          └── DecorationRecord[]
```

Region size:

```text
32×32 chunks
```

Operations:

```text
ADD
UPDATE
REMOVE
```

Each decoration gets monotonically increasing revision.

Each region may also maintain a region revision.

---

# Phase 5 — Runtime mutation API

Expose:

```java
DistantDecorations.publish(level, pos);
DistantDecorations.remove(level, pos);
```

Provider capture occurs on server side.

Publish flow:

```text
find provider
capture payload
calculate bounds
increment revision
update persistent index
mark dirty
notify subscribed players
```

---

# Phase 6 — Chunk-load reconciliation

Whenever a chunk becomes available:

```text
inspect BlockEntity collection
    ↓
find registered providers
    ↓
capture supported decorations
    ↓
compare against indexed records
    ↓
insert/update/remove stale state
```

Do not scan every world block.

Use BE-based lookup first.

---

# Phase 7 — Chunky integration

Chunky integration is now considered **required for v0.1 when Chunky is installed**.

Pattern:

```text
Chunky produces completed LevelChunk
        ↓
scan supported BEs
        ↓
produce immutable scan result
        ↓
server thread
        ↓
commit into Distant Decorations index
```

Use the same conceptual interception point Voxy currently uses.

Do not mutate SavedData directly from an arbitrary completion thread.

Telemetry:

```text
Chunky chunks observed
chunks scanned
BEs examined
decorations found
index adds
index updates
scan time
commit time
```

Chunky should allow one pregeneration pass to build:

```text
Minecraft world
Voxy LOD data
Distant Decorations metadata
```

---

# Phase 8 — Networking

Handshake:

```text
protocol version
supported decoration types
provider schema versions
requested radius
feature flags
```

Packets:

```text
C2SClientHello
C2SSubscriptionUpdate

S2CRegionSnapshot
S2CRegionDelta
S2CRegionUnload
```

Server decides:

```text
effectiveRadius =
min(clientRequest, serverMaximum)
```

Snapshots should stream nearest-first.

Batch records.

Never send one packet per decoration.

---

# Phase 9 — Network budgets

Implement:

```text
max bytes/player/tick
max regions/player
max snapshots/tick
max records/snapshot
max provider payload size
max synchronized decorations/player
```

Large teleport:

```text
drop old region subscriptions
subscribe near new location
stream outward progressively
```

---

# Phase 10 — Fast Paintings integration

Provider remains in the Fast Paintings repository.

Payload:

```java
record DistantPainting(
    ResourceLocation variant,
    Direction facing,
    int width,
    int height,
    int packedLight
) {}
```

Distant LOD:

```text
<1px       SKIP
small      FAR
medium     SIMPLIFIED
vanilla chunk loaded → normal Fast Paintings BER
```

Do not use FULL segmented rendering for distant records.

Do not integrate Fast Paintings with OBE.

Reason:

```text
large multi-block render bounds
anchor-section culling concerns
already has specialized projected-size LOD
already optimized
```

---

# Phase 11 — Camerapture integration

Payload:

```java
record DistantPictureFrame(
    UUID pictureId,
    Direction facing,
    int width,
    int height,
    int rotation,
    boolean glow,
    int packedLight
) {}
```

Distant rendering:

```text
SKIP
THUMBNAIL
```

Hard invariant:

```text
Distant Camerapture code MUST NEVER request FULL.
```

Metadata streaming and picture-resource fetching remain separate.

---

# Phase 12 — Camerapture thumbnail scheduler

Only request thumbnail when:

```text
visible cell
AND object frustum-visible
AND projected size above threshold
AND survives render budget
```

Prioritize:

```text
projected screen size
screen-center importance
distance
```

Deduplicate by UUID.

Bound:

```text
new thumbnail requests/sec
decoded images queued
GPU uploads/frame
upload bytes/frame
```

This prevents:

```text
50k indexed frames
→ 50k thumbnail downloads
```

---

# Phase 13 — Live-BE handoff

Client state:

```text
DISTANT
WAITING_FOR_LIVE
LIVE
```

Behavior:

```text
remote chunk
→ DISTANT

chunk packet appears
→ WAITING_FOR_LIVE

matching real BE appears
→ LIVE
→ suppress distant renderer

chunk unloads
→ DISTANT
```

Avoid one-frame disappearance caused by chunk packet / BE packet ordering.

Crossfade can come later.

---

# Phase 14 — OBE compatibility

OBE is explicitly a **near-distance optimization layer**.

Distant Decorations must not:

```text
register Fast Paintings with OBE
register Camerapture with OBE
invoke OBE models
depend on OBE APIs
inspect OBE render modes in normal operation
```

Expected behavior:

```text
Distant
-------
Distant Decorations renders object.

Vanilla-loaded
--------------
Distant Decorations suppresses itself.

Then:
OBE-supported BE → OBE may terrain-mesh it.
Fast Paintings   → own BER.
Camerapture      → own BER.
```

Test with OBE enabled and disabled.

Ensure Distant Decorations' live-BE detection still works even if OBE chooses not to submit the BE through the normal BER.

Important:

```text
live-BE suppression must test existence/state,
not whether vanilla BER actually rendered this frame.
```

---

# Phase 15 — Voxy compatibility

Prefer zero API dependency.

Use Voxy only if necessary for:

```text
render-stage timing
depth-buffer availability
```

Do not use:

```text
Voxy world storage
Voxy region format
Voxy model system
```

Voxy integration adapter should remain tiny.

---

# Phase 16 — Distant Horizons compatibility

Same rule as Voxy.

Try standard render integration first.

Only add DH-specific adapter if depth/render ordering requires it.

---

# Phase 17 — Lighting

Distant lighting is intentionally coarse.

Store:

```text
packed block/sky light at anchor
```

Refresh on:

```text
chunk reconciliation
object mutation
Chunky ingest
manual reindex
```

Never load distant chunks just to refresh lighting.

---

# Phase 18 — GPU budgets

Configuration:

```text
max distant submissions/frame
max provider submissions/frame
max texture uploads/frame
max upload bytes/frame
```

Starting experimental budget:

```text
1000–2000 distant objects/frame
```

Do not treat this as final until benchmarked.

When budget exceeded:

```text
large projected objects first
tiny/far objects dropped
```

---

# Phase 19 — Fast Paintings batching

After baseline correctness:

```text
instance data:
position
orientation
size
atlas UV
light
LOD
```

Target:

```text
many paintings
→ few GPU batches
```

Avoid per-painting draw calls where possible.

---

# Phase 20 — Camerapture batching

Initial implementation can use existing dynamic texture objects.

Only optimize further if benchmark proves necessary.

Future options:

```text
thumbnail atlas
texture arrays
packed thumbnail pages
```

Do not force dynamic Camerapture textures into OBE/block atlas machinery.

---

# Phase 21 — Telemetry

Use cumulative `long` counters + snapshot/delta API.

Server:

```text
indexed decorations
regions
adds
updates
removes
Chunky scans
chunk reconciliation scans
snapshots sent
deltas sent
metadata bytes
```

Client:

```text
synced decorations
regions
cells checked
cells frustum-rejected
objects checked
object frustum rejects
subpixel rejects
budget rejects
live-BE suppressions
rendered decorations
```

Provider:

```text
Fast Paintings FAR
Fast Paintings SIMPLIFIED

Camerapture thumbnail LOD
thumbnail renders
thumbnail requests
cache hits
uploads
upload bytes
```

GPU-oriented:

```text
quads
vertices
batches
draw calls if measurable
textures touched
```

---

# Phase 22 — Benchmark suite

Indexed records:

```text
1k
10k
25k
50k
100k
```

Visible:

```text
0
100
500
1000
2500
5000
```

Scenarios:

```text
all behind camera
mostly subpixel
dense painting gallery
dense Camerapture gallery
mixed providers
high-speed Elytra with Voxy
teleport between indexed regions
cold thumbnail cache
warm thumbnail cache
5k frames / 5k UUIDs
5k frames / 10 UUIDs
OBE on/off
Iris on/off
```

Measure:

```text
p50 frame time
p95
p99
CPU render-thread time
GPU frame time
memory
allocations
GC
network bytes/player
texture uploads
thumbnail requests
draw batches
```

---

# Phase 23 — OBE comparison test

Add one dedicated performance scenario:

```text
large vanilla BE-heavy build
```

Run:

```text
A. Sodium only
B. Sodium + OBE
C. Sodium + Distant Decorations
D. Sodium + OBE + Distant Decorations
```

Expected:

```text
inside vanilla distance:
OBE improves supported vanilla BEs

outside vanilla distance:
Distant Decorations provides sparse representation

both enabled:
no duplicate visuals
no double rendering
no significant compatibility cost
```

---

# Phase 24 — Offline indexer

After v0.1 unless required earlier.

Read existing region files without generation.

Requirements:

```text
cancelable
resumable
rate-limited
progress reporting
no chunk generation
only relevant BE NBT
```

Chunky remains preferred for pregenerated SMP deployment.

---

# v0.1 release gate

Require:

```text
Rendering spike with Voxy              ✅
Provider API                            ✅
Persistent region index                ✅
Chunk reconciliation                   ✅
Chunky ingestion                       ✅
Region networking                      ✅
Client spatial cells                   ✅
Frustum culling                        ✅
Projected-size SKIP                    ✅
Render budget                          ✅
Fast Paintings provider                ✅
Camerapture thumbnail provider         ✅
No distant FULL requests               ✅
Live-BE handoff                        ✅
OBE coexistence tested                 ✅
Voxy depth compatibility               ✅
Telemetry                              ✅
10k+ stress benchmark                  ✅
```

## Final responsibility split

```text
Chunky
    → discovers/indexes generated chunks

Voxy / DH
    → distant terrain

Distant Decorations
    → distant sparse BE-backed visuals

OBE
    → optimizes supported static BEs inside loaded chunks

Fast Paintings
    → its own optimized loaded renderer
    → Distant Decorations provider outside loaded chunks

Camerapture
    → its own optimized loaded renderer
    → thumbnail-only Distant Decorations provider outside loaded chunks
```

The key change from the previous plan is that **OBE is now explicitly part of the compatibility and benchmark matrix, but not part of Distant Decorations' implementation architecture**. That keeps each mod focused on the problem it solves best.

repos:

- C:\Users\markj\source\repos\camerapture
- C:\Users\markj\source\repos\fast-paintings
- C:\Users\markj\source\repos\fabric-api
- C:\Users\markj\source\repos\voxy
- C:\Users\markj\source\repos\OptimisedBlockEntities
- C:\Users\markj\source\repos\Chunky