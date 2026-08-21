package me.justbecause.distantdecorations.api.client;

import com.mojang.blaze3d.vertex.PoseStack;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.api.DecorationType;
import me.justbecause.distantdecorations.client.spatial.ProjectionMetrics;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;

public interface DecorationClientRenderer<T> {
    DecorationType<T> type();

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
