package me.justbecause.distantdecorations.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import me.justbecause.distantdecorations.api.client.ClientDecorationRegistry;
import me.justbecause.distantdecorations.api.client.DecorationClientRenderer;
import me.justbecause.distantdecorations.client.spatial.ClientDecoration;
import me.justbecause.distantdecorations.client.spatial.ClientDecorationRegion;
import me.justbecause.distantdecorations.client.spatial.ClientDecorationWorld;
import me.justbecause.distantdecorations.client.spatial.DecorationRenderCell;
import me.justbecause.distantdecorations.client.spatial.ProjectionMetrics;
import me.justbecause.distantdecorations.telemetry.TelemetryMetrics;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DecorationRenderManager {
    private static final DecorationRenderManager INSTANCE = new DecorationRenderManager();

    private final Map<ResourceKey<Level>, ClientDecorationWorld> clientWorlds = new ConcurrentHashMap<>();
    private final LiveHandoffTracker handoffTracker = new LiveHandoffTracker();
    private final RenderBudget budget = new RenderBudget();
    private final List<RenderBudget.RenderCandidate> candidateList = new ArrayList<>(2048);
    private final PriorityQueue<RenderBudget.RenderCandidate> topKHeap = new PriorityQueue<>(2048, RenderBudget.MIN_HEAP_COMPARATOR);
    private final Set<DecorationClientRenderer<?>> activeRenderers = new HashSet<>();

    private DecorationRenderManager() {}

    public static DecorationRenderManager getInstance() {
        return INSTANCE;
    }

    public ClientDecorationWorld getOrCreateWorld(ResourceKey<Level> dimension) {
        return clientWorlds.computeIfAbsent(dimension, ClientDecorationWorld::new);
    }

    public ClientDecorationWorld getWorld(ResourceKey<Level> dimension) {
        return clientWorlds.get(dimension);
    }

    public LiveHandoffTracker getHandoffTracker() {
        return handoffTracker;
    }

    public RenderBudget getBudget() {
        return budget;
    }

    public void clearAll() {
        clientWorlds.clear();
        handoffTracker.clear();
    }

    public void renderFrame(LevelRenderContext context) {
        if (!me.justbecause.distantdecorations.config.DistantDecorationsConfig.isMasterEnabled()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.gameRenderer == null) {
            return;
        }

        TelemetryMetrics.beginClientFrame();

        ClientDecorationWorld world = clientWorlds.get(level.dimension());
        if (world == null || world.getTotalDecorationsCount() == 0) {
            return;
        }

        Camera camera = mc.gameRenderer.mainCamera();
        if (camera == null) {
            return;
        }

        Vec3 cameraPos = camera.position();
        int viewportWidth = mc.getWindow().getWidth();
        int viewportHeight = mc.getWindow().getHeight();
        double fov = camera.getFov() > 0 ? camera.getFov() : (double) mc.options.fov().get();

        ProjectionMetrics metrics = ProjectionMetrics.of(cameraPos, viewportWidth, viewportHeight, fov);
        Frustum frustum = camera.getCullFrustum();
        SubmitNodeCollector submitNodeCollector = context.submitNodeCollector();
        PoseStack poseStack = context.poseStack();

        candidateList.clear();
        topKHeap.clear();
        activeRenderers.clear();

        double maxDistSq = budget.getMaxRenderDistance() * budget.getMaxRenderDistance();
        double minPixelSize = budget.getMinProjectedPixelSize();
        int maxSubmissions = budget.getMaxSubmissionsPerFrame();
        boolean useHeap = false;

        // 1. Region & Cell Frustum Culling
        for (ClientDecorationRegion region : world.getAllRegions()) {
            if (region.isEmpty()) {
                continue;
            }

            AABB regionBounds = region.bounds();
            if (regionBounds == null) {
                continue;
            }

            if (frustum != null && !frustum.isVisible(regionBounds)) {
                continue;
            }

            for (DecorationRenderCell cell : region.getCells()) {
                if (cell.isEmpty()) {
                    continue;
                }
                TelemetryMetrics.clientCellsChecked++;

                if (frustum != null && !frustum.isVisible(cell.getBounds())) {
                    TelemetryMetrics.clientCellsFrustumRejected++;
                    continue;
                }

                // 2. Object Frustum Culling & Projected-Size Filtering
                for (ClientDecoration deco : cell.getDecorations()) {
                    if (!deco.isValid()) {
                        continue;
                    }
                    TelemetryMetrics.clientObjectsChecked++;

                    AABB bounds = deco.bounds();
                    double distSq = metrics.getDistanceSq(bounds);
                    if (distSq > maxDistSq) {
                        TelemetryMetrics.clientDistanceRejected++;
                        continue;
                    }

                    if (frustum != null && !frustum.isVisible(bounds)) {
                        TelemetryMetrics.clientObjectsFrustumRejected++;
                        continue;
                    }

                    double projectedPixelSize = metrics.calculateProjectedPixelSize(bounds);
                    if (projectedPixelSize < minPixelSize) {
                        TelemetryMetrics.clientSubpixelRejected++;
                        continue;
                    }

                    // 3. Live-BE Suppression Check
                    if (handoffTracker.isSuppressed(deco.id(), level, cameraPos)) {
                        TelemetryMetrics.clientLiveSuppressions++;
                        continue;
                    }

                    double priority = budget.calculatePriority(projectedPixelSize, distSq);
                    RenderBudget.RenderCandidate candidate = new RenderBudget.RenderCandidate(deco, projectedPixelSize, distSq, priority);

                    // 4. Bounded Top-K Selection
                    if (!useHeap) {
                        if (candidateList.size() < maxSubmissions) {
                            candidateList.add(candidate);
                        } else {
                            useHeap = true;
                            topKHeap.addAll(candidateList);
                            candidateList.clear();
                            if (priority > topKHeap.peek().priorityScore()) {
                                topKHeap.poll();
                                topKHeap.add(candidate);
                            }
                            TelemetryMetrics.clientBudgetRejected++;
                        }
                    } else {
                        if (priority > topKHeap.peek().priorityScore()) {
                            topKHeap.poll();
                            topKHeap.add(candidate);
                        }
                        TelemetryMetrics.clientBudgetRejected++;
                    }
                }
            }
        }

        if (useHeap) {
            candidateList.addAll(topKHeap);
            topKHeap.clear();
        }

        if (candidateList.isEmpty()) {
            return;
        }

        candidateList.sort(RenderBudget.PRIORITY_COMPARATOR);

        // 5. Notify renderers: beginFrame
        for (DecorationClientRenderer<?> renderer : ClientDecorationRegistry.getRenderers()) {
            renderer.beginFrame(camera, frustum, metrics);
        }

        // 6. Submit Render Calls (Zero payload deserialization!)
        for (RenderBudget.RenderCandidate candidate : candidateList) {
            ClientDecoration deco = candidate.decoration();
            DecorationClientRenderer<Object> renderer = deco.renderer();
            Object payload = deco.decodedPayload();
            if (renderer != null && payload != null) {
                activeRenderers.add(renderer);
                renderer.render(deco.record(), payload, camera, poseStack, submitNodeCollector, metrics, candidate.projectedPixelSize());
                TelemetryMetrics.clientRenderedDecorations++;
                TelemetryMetrics.CLIENT_TOTAL_RENDERED.incrementAndGet();
            }
        }

        // 7. Flush renderers
        for (DecorationClientRenderer<?> renderer : activeRenderers) {
            renderer.flush(camera, poseStack, submitNodeCollector);
        }
    }
}

