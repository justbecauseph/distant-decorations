package me.justbecause.distantdecorations.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.api.DecorationType;
import me.justbecause.distantdecorations.api.client.ClientDecorationRegistry;
import me.justbecause.distantdecorations.api.client.DecorationClientRenderer;
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
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.gameRenderer == null) {
            return;
        }

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

        TelemetryMetrics.beginClientFrame();
        candidateList.clear();

        double maxDistSq = budget.getMaxRenderDistance() * budget.getMaxRenderDistance();
        double minPixelSize = budget.getMinProjectedPixelSize();

        // 1. Region & Cell Frustum Culling
        for (ClientDecorationRegion region : world.getAllRegions()) {
            if (region.isEmpty()) {
                continue;
            }

            if (frustum != null && !frustum.isVisible(region.bounds())) {
                continue;
            }

            for (DecorationRenderCell cell : region.getNonEmptyCells()) {
                TelemetryMetrics.clientCellsChecked++;

                if (frustum != null && !frustum.isVisible(cell.getBounds())) {
                    TelemetryMetrics.clientCellsFrustumRejected++;
                    continue;
                }

                // 2. Object Frustum Culling & Projected-Size Filtering
                for (DecorationRecord record : cell.getRecords()) {
                    TelemetryMetrics.clientObjectsChecked++;

                    AABB bounds = record.bounds();
                    double distSq = metrics.getDistanceSq(bounds);
                    if (distSq > maxDistSq) {
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
                    if (handoffTracker.isSuppressed(record.id(), level)) {
                        TelemetryMetrics.clientLiveSuppressions++;
                        continue;
                    }

                    double priority = budget.calculatePriority(projectedPixelSize, distSq);
                    candidateList.add(new RenderBudget.RenderCandidate(record, projectedPixelSize, distSq, priority));
                }
            }
        }

        if (candidateList.isEmpty()) {
            return;
        }

        // 4. Priority Sorting & Budget Enforcing
        candidateList.sort(RenderBudget.PRIORITY_COMPARATOR);

        int maxSubmissions = budget.getMaxSubmissionsPerFrame();
        int submissionsCount = Math.min(candidateList.size(), maxSubmissions);
        if (candidateList.size() > maxSubmissions) {
            TelemetryMetrics.clientBudgetRejected += (candidateList.size() - maxSubmissions);
        }

        // 5. Notify renderers: beginFrame
        Set<DecorationClientRenderer<?>> activeRenderers = new HashSet<>();
        for (int i = 0; i < submissionsCount; i++) {
            RenderBudget.RenderCandidate candidate = candidateList.get(i);
            DecorationClientRenderer<?> renderer = ClientDecorationRegistry.getRenderer(candidate.record().id().type());
            if (renderer != null) {
                activeRenderers.add(renderer);
            }
        }

        for (DecorationClientRenderer<?> renderer : activeRenderers) {
            renderer.beginFrame(camera, frustum, metrics);
        }

        // 6. Submit Render Calls
        for (int i = 0; i < submissionsCount; i++) {
            RenderBudget.RenderCandidate candidate = candidateList.get(i);
            DecorationRecord record = candidate.record();
            DecorationClientRenderer<?> renderer = ClientDecorationRegistry.getRenderer(record.id().type());
            if (renderer != null) {
                renderTyped(renderer, record, camera, poseStack, submitNodeCollector, metrics, candidate.projectedPixelSize());
                TelemetryMetrics.clientRenderedDecorations++;
                TelemetryMetrics.CLIENT_TOTAL_RENDERED.incrementAndGet();
            }
        }

        // 7. Flush renderers
        for (DecorationClientRenderer<?> renderer : activeRenderers) {
            renderer.flush(camera, poseStack, submitNodeCollector);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void renderTyped(
        DecorationClientRenderer<T> renderer,
        DecorationRecord record,
        Camera camera,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        ProjectionMetrics metrics,
        double projectedPixelSize
    ) {
        DecorationType<T> type = renderer.type();
        T data = type.fromBytes(record.payload());
        renderer.render(record, data, camera, poseStack, submitNodeCollector, metrics, projectedPixelSize);
    }
}
