package me.justbecause.distantdecorations.api.client;

import com.mojang.blaze3d.vertex.PoseStack;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.api.DecorationType;
import me.justbecause.distantdecorations.client.spatial.ProjectionMetrics;
import me.justbecause.distantdecorations.config.DistantDecorationsConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;

public interface DecorationClientRenderer<T> {
    DecorationType<T> type();

    /**
     * Minimum projected screen pixel size below which this renderer wants DD to cull the object.
     * Default is DistantDecorationsConfig.getMinProjectedPixelSize().
     * Far-LOD enabled renderers can return a lower value (e.g. 0.01 or 0.0) to handle far-LOD impostors.
     */
    default double cullBelowProjectedPixelSize() {
        return DistantDecorationsConfig.getMinProjectedPixelSize();
    }

    /**
     * Called once at the beginning of the distant decoration render frame before any objects are submitted.
     */
    default void beginFrame(Camera camera, Frustum frustum, ProjectionMetrics metrics) {
    }

    /**
     * Renders or enqueues a distant decoration instance.
     */
    void render(
        DecorationRecord record,
        T data,
        Camera camera,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        ProjectionMetrics metrics,
        double projectedPixelSize
    );

    /**
     * Called once at the end of the distant decoration render frame to flush any batched geometry.
     */
    default void flush(Camera camera, PoseStack poseStack, SubmitNodeCollector submitNodeCollector) {
    }
}
