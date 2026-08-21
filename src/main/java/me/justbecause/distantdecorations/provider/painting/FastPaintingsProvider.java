package me.justbecause.distantdecorations.provider.painting;

import me.justbecause.distantdecorations.DistantDecorations;
import me.justbecause.distantdecorations.api.DecorationProvider;
import me.justbecause.distantdecorations.api.DecorationType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

public final class FastPaintingsProvider implements DecorationProvider<DistantPaintingData> {
    public static final Identifier TYPE_ID = DistantDecorations.id("fast_painting");

    public static final DecorationType<DistantPaintingData> TYPE = new DecorationType<>(
        TYPE_ID,
        (data, buf) -> {
            buf.writeIdentifier(data.variant());
            buf.writeEnum(data.facing());
            buf.writeVarInt(data.width());
            buf.writeVarInt(data.height());
            buf.writeInt(data.packedLight());
        },
        buf -> {
            Identifier variant = buf.readIdentifier();
            Direction facing = buf.readEnum(Direction.class);
            int width = buf.readVarInt();
            int height = buf.readVarInt();
            int light = buf.readInt();
            return new DistantPaintingData(variant, facing, width, height, light);
        }
    );

    @Override
    public DecorationType<DistantPaintingData> type() {
        return TYPE;
    }

    @Override
    public boolean matches(BlockEntity blockEntity) {
        if (blockEntity == null) return false;
        String className = blockEntity.getClass().getName();
        return className.equals("me.justbecause.fastpaintings.block.entity.PaintingBlockEntity") ||
               className.contains("PaintingBlockEntity");
    }

    @Override
    @Nullable
    public DistantPaintingData capture(ServerLevel level, BlockPos pos, BlockEntity be) {
        try {
            Class<?> clazz = be.getClass();
            Method getVariant = clazz.getMethod("getVariant");
            Method getFacing = clazz.getMethod("getFacing");
            Method getPaintingWidth = clazz.getMethod("getPaintingWidth");
            Method getPaintingHeight = clazz.getMethod("getPaintingHeight");

            Object variantHolder = getVariant.invoke(be);
            if (variantHolder == null) {
                return null;
            }

            Identifier variantId;
            if (variantHolder instanceof Holder<?> holder) {
                if (holder.value() instanceof PaintingVariant pv) {
                    variantId = pv.assetId();
                } else {
                    variantId = holder.unwrapKey().map(k -> k.identifier()).orElse(Identifier.withDefaultNamespace("kebab"));
                }
            } else {
                variantId = Identifier.withDefaultNamespace("kebab");
            }

            Direction facing = (Direction) getFacing.invoke(be);
            if (facing == null) {
                facing = Direction.NORTH;
            }
            int width = (int) getPaintingWidth.invoke(be);
            int height = (int) getPaintingHeight.invoke(be);
            int packedLight = getPackedLight(level, pos);

            return new DistantPaintingData(variantId, facing, width, height, packedLight);
        } catch (Exception e) {
            DistantDecorations.LOGGER.debug("Could not capture PaintingBlockEntity at {}", pos, e);
            return null;
        }
    }

    @Override
    public AABB calculateBounds(ServerLevel level, BlockPos pos, DistantPaintingData data) {
        return calculateBoundingBox(pos, data.facing(), data.width(), data.height());
    }

    public static AABB calculateBoundingBox(BlockPos pos, Direction direction, int width, int height) {
        Vec3 attachedToWall = Vec3.atCenterOf(pos).relative(direction, -0.46875F);
        double horizontalOffset = (width % 2 == 0) ? 0.5 : 0.0;
        double verticalOffset = (height % 2 == 0) ? 0.5 : 0.0;
        Direction left = direction.getCounterClockWise();
        Vec3 position = attachedToWall.relative(left, horizontalOffset).relative(Direction.UP, verticalOffset);
        Direction.Axis axis = direction.getAxis();
        double xSize = axis == Direction.Axis.X ? 0.0625 : width;
        double ySize = height;
        double zSize = axis == Direction.Axis.Z ? 0.0625 : width;
        return AABB.ofSize(position, xSize, ySize, zSize);
    }
}
