package me.justbecause.distantdecorations.client.provider.camerapture;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.api.DecorationType;
import me.justbecause.distantdecorations.api.client.DecorationClientRenderer;
import me.justbecause.distantdecorations.client.spatial.ProjectionMetrics;
import me.justbecause.distantdecorations.provider.camerapture.CameraptureProvider;
import me.justbecause.distantdecorations.provider.camerapture.DistantPictureFrameData;
import me.justbecause.distantdecorations.telemetry.TelemetryMetrics;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

public final class CameraptureClientRenderer implements DecorationClientRenderer<DistantPictureFrameData> {
    private static final Identifier FALLBACK_TEXTURE = Identifier.withDefaultNamespace("textures/block/oak_planks.png");

    @Override
    public DecorationType<DistantPictureFrameData> type() {
        return CameraptureProvider.TYPE;
    }

    @Override
    public void beginFrame(Camera camera, Frustum frustum, ProjectionMetrics metrics) {
        CameraptureThumbnailScheduler.getInstance().tick();
    }

    @Override
    public void render(
        DecorationRecord record,
        DistantPictureFrameData data,
        Camera camera,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        ProjectionMetrics metrics,
        double projectedPixelSize
    ) {
        if (projectedPixelSize < 1.0) {
            return;
        }

        Identifier textureId = CameraptureThumbnailScheduler.getInstance().getThumbnailTexture(data.pictureId());
        if (textureId == null) {
            // Request thumbnail (NEVER FULL)
            double priority = projectedPixelSize * 1000.0 - metrics.getDistanceSq(record.bounds());
            CameraptureThumbnailScheduler.getInstance().requestThumbnail(data.pictureId(), priority);
            textureId = FALLBACK_TEXTURE;
        } else {
            TelemetryMetrics.CAMERAPTURE_CACHE_HITS.incrementAndGet();
            TelemetryMetrics.CAMERAPTURE_THUMBNAIL_RENDERS.incrementAndGet();
        }

        BlockPos anchor = record.id().anchor();
        Vec3 cameraPos = camera.position();
        Direction facing = data.facing();
        int width = data.width();
        int height = data.height();

        poseStack.pushPose();
        // Camera-relative translation for floating point stability
        poseStack.translate(anchor.getX() - cameraPos.x, anchor.getY() - cameraPos.y, anchor.getZ() - cameraPos.z);

        // Transform matching PictureFrameBlockEntityRenderer submit
        double minX = 0.0, minZ = 0.0;
        switch (facing) {
            case SOUTH -> minX = 0.0;
            case EAST -> minZ = 1.0 - width;
            case WEST -> minX = 1.0 - 0.0625;
            default -> minX = 1.0 - width; // NORTH
        }

        poseStack.translate(minX, 0.0, minZ);
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - facing.get2DDataValue() * 90));

        if (data.rotation() > 0) {
            poseStack.translate(width * 0.5, height * 0.5, 0.0);
            poseStack.mulPose(Axis.ZP.rotationDegrees(data.rotation() * 90));
            poseStack.translate(-width * 0.5, -height * 0.5, 0.0);
        }

        RenderType renderType = RenderTypes.entityCutout(textureId);
        int light = data.glow() ? 0xF000F0 : data.packedLight();

        final float finalWidth = width;
        final float finalHeight = height;

        submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            vertex(pose, buffer, 0, finalHeight, 0.0F, 1.0F, 0.0625F, light);
            vertex(pose, buffer, finalWidth, finalHeight, 1.0F, 1.0F, 0.0625F, light);
            vertex(pose, buffer, finalWidth, 0, 1.0F, 0.0F, 0.0625F, light);
            vertex(pose, buffer, 0, 0, 0.0F, 0.0F, 0.0625F, light);
        });

        poseStack.popPose();
    }

    private static void vertex(
        PoseStack.Pose pose,
        VertexConsumer buffer,
        float x,
        float y,
        float u,
        float v,
        float z,
        int lightCoords
    ) {
        buffer.addVertex(pose, x, y, z)
            .setColor(-1)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(lightCoords)
            .setNormal(pose, 0, 0, 1);
    }
}
