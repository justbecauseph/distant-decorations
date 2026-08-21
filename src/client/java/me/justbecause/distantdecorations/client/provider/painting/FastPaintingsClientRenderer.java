package me.justbecause.distantdecorations.client.provider.painting;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.api.DecorationType;
import me.justbecause.distantdecorations.api.client.DecorationClientRenderer;
import me.justbecause.distantdecorations.client.spatial.ProjectionMetrics;
import me.justbecause.distantdecorations.provider.painting.DistantPaintingData;
import me.justbecause.distantdecorations.provider.painting.FastPaintingsProvider;
import me.justbecause.distantdecorations.telemetry.TelemetryMetrics;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class FastPaintingsClientRenderer implements DecorationClientRenderer<DistantPaintingData> {
    private static final Identifier BACK_SPRITE_LOCATION = Identifier.withDefaultNamespace("back");

    private @Nullable TextureAtlas paintingsAtlas;

    @Override
    public DecorationType<DistantPaintingData> type() {
        return FastPaintingsProvider.TYPE;
    }

    @Override
    public void beginFrame(Camera camera, Frustum frustum, ProjectionMetrics metrics) {
        if (this.paintingsAtlas == null) {
            try {
                this.paintingsAtlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.PAINTINGS);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void render(
        DecorationRecord record,
        DistantPaintingData data,
        Camera camera,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        ProjectionMetrics metrics,
        double projectedPixelSize
    ) {
        if (this.paintingsAtlas == null || projectedPixelSize < 1.0) {
            return;
        }

        TextureAtlasSprite frontSprite = this.paintingsAtlas.getSprite(data.variant());
        TextureAtlasSprite backSprite = this.paintingsAtlas.getSprite(BACK_SPRITE_LOCATION);
        if (frontSprite == null || backSprite == null) {
            return;
        }

        BlockPos anchor = record.id().anchor();
        Vec3 cameraPos = camera.position();
        Direction facing = data.facing();
        int width = data.width();
        int height = data.height();

        double horizontalOffset = (width % 2 == 0) ? 0.5 : 0.0;
        double verticalOffset = (height % 2 == 0) ? 0.5 : 0.0;

        poseStack.pushPose();
        // Camera-relative translation for precision at extreme distances
        poseStack.translate(anchor.getX() + 0.5 - cameraPos.x, anchor.getY() + 0.5 - cameraPos.y, anchor.getZ() + 0.5 - cameraPos.z);
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - facing.get2DDataValue() * 90));
        poseStack.translate(horizontalOffset, verticalOffset, 0.46875);

        RenderType renderType = RenderTypes.entitySolidZOffsetForward(backSprite.atlasLocation());
        int light = data.packedLight();

        if (projectedPixelSize < 16.0) {
            // FAR LOD (1 single front quad)
            TelemetryMetrics.FAST_PAINTINGS_FAR_RENDERS.incrementAndGet();
            renderFar(poseStack, submitNodeCollector, renderType, light, width, height, frontSprite);
        } else {
            // SIMPLIFIED LOD (6 quads)
            TelemetryMetrics.FAST_PAINTINGS_SIMPLIFIED_RENDERS.incrementAndGet();
            renderSimplified(poseStack, submitNodeCollector, renderType, light, width, height, frontSprite, backSprite);
        }

        poseStack.popPose();
    }

    private void renderFar(
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        RenderType renderType,
        int lightCoords,
        int width,
        int height,
        TextureAtlasSprite front
    ) {
        submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            float x0 = width / 2.0F;
            float x1 = -width / 2.0F;
            float y0 = height / 2.0F;
            float y1 = -height / 2.0F;

            float frontU0 = front.getU0();
            float frontU1 = front.getU1();
            float frontV0 = front.getV0();
            float frontV1 = front.getV1();

            vertex(pose, buffer, x0, y1, frontU0, frontV1, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x1, y1, frontU1, frontV1, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x1, y0, frontU1, frontV0, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x0, y0, frontU0, frontV0, -0.03125F, 0, 0, -1, lightCoords);
        });
    }

    private void renderSimplified(
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        RenderType renderType,
        int lightCoords,
        int width,
        int height,
        TextureAtlasSprite front,
        TextureAtlasSprite back
    ) {
        submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            float x0 = width / 2.0F;
            float x1 = -width / 2.0F;
            float y0 = height / 2.0F;
            float y1 = -height / 2.0F;

            float frontU0 = front.getU0();
            float frontU1 = front.getU1();
            float frontV0 = front.getV0();
            float frontV1 = front.getV1();

            float topBottomU0 = back.getU0();
            float topBottomU1 = back.getU1();
            float topBottomV0 = back.getV0();
            float topBottomV1 = back.getV(0.0625F);
            float leftRightU0 = back.getU0();
            float leftRightU1 = back.getU(0.0625F);
            float leftRightV0 = back.getV0();
            float leftRightV1 = back.getV1();

            // 1. Front face
            vertex(pose, buffer, x0, y1, frontU0, frontV1, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x1, y1, frontU1, frontV1, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x1, y0, frontU1, frontV0, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x0, y0, frontU0, frontV0, -0.03125F, 0, 0, -1, lightCoords);

            // 2. Back face
            vertex(pose, buffer, x0, y0, back.getU1(), back.getV0(), 0.03125F, 0, 0, 1, lightCoords);
            vertex(pose, buffer, x1, y0, back.getU0(), back.getV0(), 0.03125F, 0, 0, 1, lightCoords);
            vertex(pose, buffer, x1, y1, back.getU0(), back.getV1(), 0.03125F, 0, 0, 1, lightCoords);
            vertex(pose, buffer, x0, y1, back.getU1(), back.getV1(), 0.03125F, 0, 0, 1, lightCoords);

            // 3. Top edge
            vertex(pose, buffer, x0, y0, topBottomU0, topBottomV0, -0.03125F, 0, 1, 0, lightCoords);
            vertex(pose, buffer, x1, y0, topBottomU1, topBottomV0, -0.03125F, 0, 1, 0, lightCoords);
            vertex(pose, buffer, x1, y0, topBottomU1, topBottomV1, 0.03125F, 0, 1, 0, lightCoords);
            vertex(pose, buffer, x0, y0, topBottomU0, topBottomV1, 0.03125F, 0, 1, 0, lightCoords);

            // 4. Bottom edge
            vertex(pose, buffer, x0, y1, topBottomU0, topBottomV0, 0.03125F, 0, -1, 0, lightCoords);
            vertex(pose, buffer, x1, y1, topBottomU1, topBottomV0, 0.03125F, 0, -1, 0, lightCoords);
            vertex(pose, buffer, x1, y1, topBottomU1, topBottomV1, -0.03125F, 0, -1, 0, lightCoords);
            vertex(pose, buffer, x0, y1, topBottomU0, topBottomV1, -0.03125F, 0, -1, 0, lightCoords);

            // 5. Right edge
            vertex(pose, buffer, x0, y0, leftRightU1, leftRightV0, 0.03125F, -1, 0, 0, lightCoords);
            vertex(pose, buffer, x0, y1, leftRightU1, leftRightV1, 0.03125F, -1, 0, 0, lightCoords);
            vertex(pose, buffer, x0, y1, leftRightU0, leftRightV1, -0.03125F, -1, 0, 0, lightCoords);
            vertex(pose, buffer, x0, y0, leftRightU0, leftRightV0, -0.03125F, -1, 0, 0, lightCoords);

            // 6. Left edge
            vertex(pose, buffer, x1, y0, leftRightU1, leftRightV0, -0.03125F, 1, 0, 0, lightCoords);
            vertex(pose, buffer, x1, y1, leftRightU1, leftRightV1, -0.03125F, 1, 0, 0, lightCoords);
            vertex(pose, buffer, x1, y1, leftRightU0, leftRightV1, 0.03125F, 1, 0, 0, lightCoords);
            vertex(pose, buffer, x1, y0, leftRightU0, leftRightV0, 0.03125F, 1, 0, 0, lightCoords);
        });
    }

    private static void vertex(
        PoseStack.Pose pose,
        VertexConsumer buffer,
        float x,
        float y,
        float u,
        float v,
        float z,
        int nx,
        int ny,
        int nz,
        int lightCoords
    ) {
        buffer.addVertex(pose, x, y, z)
            .setColor(-1)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(lightCoords)
            .setNormal(pose, nx, ny, nz);
    }
}
