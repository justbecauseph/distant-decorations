# Distant Decorations

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-brightgreen.svg)](https://minecraft.net/)
[![Fabric API](https://img.shields.io/badge/Fabric%20API-0.158.0%2B26.2-blue.svg)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MPL--2.0-blue.svg)](LICENSE.md)

**Distant Decorations** is a high-performance Fabric framework for Minecraft 26.2 that lets participating mods persist and render decorative block-entity visuals far beyond vanilla chunk distances. Its default client subscription range is 512 chunks (8,192 blocks), with a server-authoritative maximum of 1,024 chunks (16,384 blocks). Integrations such as Fast Paintings and Camerapture provide their own lightweight server metadata providers and distant client renderers.

By decoupling visual representation from ticking block entities and leveraging hierarchical spatial indexing, projected-size culling, and bounded top-K selection, Distant Decorations keeps distant-decoration traversal in the low-millisecond range even with large synchronized datasets—seamlessly coexisting with long-distance terrain renderers like **Voxy**.

---

## 🌟 Key Features

- **Extreme Visibility Range**: Extends decoration visibility up to 512 chunks (8,192 blocks) by default, with server support for subscriptions up to 1,024 chunks (16,384 blocks), independent of vanilla chunk render distance.
- **Hierarchical Spatial Indexing**:
  - **4×4 Chunk Cells (64×64 blocks)**: Aggregate bounding box calculation for instantaneous coarse frustum culling.
  - **32×32 Chunk Regions (512×512 blocks)**: Monotonic revision tracking, dirty-region flushing (every 5s), and idle eviction (60s).
- **Dynamic Level of Detail (LOD) & Far-Visual Scaling**:
  - **Provider-Aware Projected-Size Culling**: Allows renderers to specify custom subpixel culling thresholds via `cullBelowProjectedPixelSize()` (defaults to global `0.10 px`, configurable down to `0.01 px`).
  - **Far-LOD Footprint Support**: Core delivers precise projection metrics and non-culled submissions so provider renderers can scale subpixel distant quads around their center to maintain a rasterizable $\sim 1\text{ px}$ visual footprint (e.g. clamped up to $16\times$) without altering physical world AABBs.
- **Flicker-Free Live Handoff**:
  - Dynamic distance threshold ($64 \times \text{entityDistanceScaling}$ blocks) and a 200 ms chunk-load grace period ensuring seamless transitions between live block entity renderers and Distant Decorations.
  - Real-time mutation capture (`MixinBlockEntity`) tracking block entity updates (`setChanged`) and removals in active chunks.
- **OBE Coexistence**: Designed to cleanly coexist with **OptimisedBlockEntities (OBE)** by operating completely independently of ticking block entity lifecycles.
- **Chunky Integration**: Safely intercepts asynchronous chunk generation from [Chunky](https://modrinth.com/mod/chunky) and marshals reconciliation back onto the server main thread to atomically commit newly generated block entities to the world index.
- **GPU & Network Throttling**:
  - Distance & screen-space prioritized budget limiter (`projectedPixelSize * 1000 - distance`) using bounded top-K selection ($O(N \log K)$).
  - Bandwidth-throttled (128 KiB/tick) chunked multipart snapshot packet streaming (nearest-first) with atomic client-side assembly.
  - Ingestion-time payload pre-decoding: zero payload deserialization or `ByteBuf` allocation in the render loop.

---

## 🧩 Provider Architecture

Distant Decorations is designed as a modular core framework. Content mods register their own lightweight metadata providers on the server and custom renderers on the client:

| Mod | Integration Architecture |
| :--- | :--- |
| **[Fast Paintings](https://github.com/justbecauseph/fast-paintings)** | Registers a painting provider and renderer leveraging vanilla atlas sprites with single-quad `FAR` LOD and far-LOD visual footprint scaling. |
| **[Camerapture](https://github.com/justbecauseph/camerapture)** | Registers a picture frame provider and renderer that strictly requests $32\times32$ thumbnails from `ClientPictureStore` with far-LOD visual scaling. |
| **[Voxy](https://modrinth.com/mod/voxy)** | Full depth-tested compatibility via `LevelRenderEvents.COLLECT_SUBMITS` and non-interfering storage backends. |

---

## ⚙️ Configuration & Diagnostics

Distant Decorations provides runtime diagnostics commands and system property configuration:

### Diagnostic Commands
- **Client**:
  - `/ddc stats`: Displays active configuration, synced region/decoration counts, and last-frame breakdown (`Visible/Rendered`, `Far-LOD Scaled`, `Suppressed (Live)`, `Culled (Distance)`, `Culled (Subpixel)`, `Culled (Frustum)`, and `Culled (Budget)`).
  - `/ddc toggle`: Toggles distant decoration rendering on or off at runtime.
- **Server**:
  - `/dd stats`: Displays indexed decoration counts, loaded dimensions, active client subscriptions, and dirty region tracking.
  - `/dd toggle`: Master server toggle for decoration synchronization.

### Configuration Properties
| JVM Property | Default | Description |
| :--- | :--- | :--- |
| `distantdecorations.subscription_radius_chunks` | `512` | Client subscription radius in chunks (decoupled from vanilla render distance). |
| `distantdecorations.min_projected_pixel_size` | `0.10` | Global subpixel culling threshold in screen pixels (floored at `0.01`). |
| `distantdecorations.max_render_distance` | `radius * 16.0` | Absolute max rendering distance in blocks (defaults to `8192.0` at 512 chunks). |
| `distantdecorations.disabled` | `false` | Master kill-switch to disable DD processing. |
| `distantdecorations.provider.<namespace>.<path>.disabled` | `false` | Disables a specific decoration provider/renderer by identifier. |
| `distantdecorations.provider.<namespace>.disabled` | `false` | Disables all decoration providers from a given namespace. |

---

## 🛠️ Developer API

Distant Decorations provides a clean, extensible API allowing third-party mods to register custom decoration types, server providers, and client renderers.

### 1. Define a Decoration Type & Provider (Server-Side)

```java
public record MyCustomData(Identifier texture, Direction facing, int width, int height, int packedLight) {}

public class MyCustomProvider implements DecorationProvider<MyCustomData> {
    public static final DecorationType<MyCustomData> TYPE = new DecorationType<>(
        Identifier.fromNamespaceAndPath("mymod", "custom_deco"),
        (data, buf) -> {
            buf.writeIdentifier(data.texture());
            buf.writeByte(data.facing().get2DDataValue());
            buf.writeVarInt(data.width());
            buf.writeVarInt(data.height());
            buf.writeInt(data.packedLight());
        },
        buf -> new MyCustomData(
            buf.readIdentifier(),
            Direction.from2DDataValue(buf.readByte()),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readInt()
        )
    );

    @Override
    public DecorationType<MyCustomData> type() { return TYPE; }

    @Override
    public boolean matches(BlockEntity be) { return be instanceof MyCustomBlockEntity; }

    @Override
    public MyCustomData capture(ServerLevel level, BlockPos pos, BlockEntity be) {
        MyCustomBlockEntity custom = (MyCustomBlockEntity) be;
        return new MyCustomData(custom.getTexture(), custom.getFacing(), custom.getWidth(), custom.getHeight(), getPackedLight(level, pos));
    }

    @Override
    public AABB calculateBounds(ServerLevel level, BlockPos pos, MyCustomData data) {
        return new AABB(pos);
    }
}
```

### 2. Implement Client Renderer (Client-Side)

```java
public class MyCustomRenderer implements DecorationClientRenderer<MyCustomData> {
    @Override
    public DecorationType<MyCustomData> type() { return MyCustomProvider.TYPE; }

    @Override
    public double cullBelowProjectedPixelSize() {
        // Opt into custom subpixel threshold for far-LOD scaling
        return 0.01;
    }

    @Override
    public void render(
        DecorationRecord record,
        MyCustomData data,
        Camera camera,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        ProjectionMetrics metrics,
        double projectedPixelSize
    ) {
        BlockPos anchor = record.pos();
        Vec3 camPos = camera.position();

        poseStack.pushPose();
        poseStack.translate(anchor.getX() - camPos.x, anchor.getY() - camPos.y, anchor.getZ() - camPos.z);
        poseStack.translate(0.5, 0.5, 0.5);

        // Optional Far-LOD visual scaling for subpixel quads
        double targetMinPixelSize = 1.0;
        if (projectedPixelSize > 0 && projectedPixelSize < targetMinPixelSize) {
            double visualScale = Math.min(16.0, targetMinPixelSize / projectedPixelSize);
            poseStack.scale((float) visualScale, (float) visualScale, 1.0F);
            TelemetryMetrics.clientFarLodScaledRenders++;
        }

        RenderType renderType = RenderTypes.entityCutout(data.texture());
        submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            // Submit vertex geometry
        });

        poseStack.popPose();
    }
}
```

### 3. Registration

```java
// Common / Server Mod Initializer
DecorationRegistry.registerProvider(new MyCustomProvider());

// Client Mod Initializer
ClientDecorationRegistry.registerRenderer(new MyCustomRenderer());
```

---

## 📦 Building & Testing from Source

```bash
git clone https://github.com/justbecauseph/distant-decorations.git
cd distant-decorations
./gradlew build
```

To run the full client test environment with Voxy, Sodium, and Spark preloaded:

```bash
./gradlew runIntegrationClient
```

The compiled mod JAR will be located in `build/libs/`.

---

## 📄 License

Distant Decorations is licensed under the [Mozilla Public License 2.0 (MPL-2.0)](LICENSE.md).
