package me.justbecause.distantdecorations.provider.camerapture;

import me.justbecause.distantdecorations.DistantDecorations;
import me.justbecause.distantdecorations.api.DecorationProvider;
import me.justbecause.distantdecorations.api.DecorationType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.UUID;

public final class CameraptureProvider implements DecorationProvider<DistantPictureFrameData> {
    public static final Identifier TYPE_ID = DistantDecorations.id("camerapture");

    public static final DecorationType<DistantPictureFrameData> TYPE = new DecorationType<>(
        TYPE_ID,
        (data, buf) -> {
            buf.writeUUID(data.pictureId());
            buf.writeEnum(data.facing());
            buf.writeVarInt(data.width());
            buf.writeVarInt(data.height());
            buf.writeVarInt(data.rotation());
            buf.writeBoolean(data.glow());
            buf.writeInt(data.packedLight());
        },
        buf -> {
            UUID id = buf.readUUID();
            Direction facing = buf.readEnum(Direction.class);
            int width = buf.readVarInt();
            int height = buf.readVarInt();
            int rotation = buf.readVarInt();
            boolean glow = buf.readBoolean();
            int light = buf.readInt();
            return new DistantPictureFrameData(id, facing, width, height, rotation, glow, light);
        }
    );

    @Override
    public DecorationType<DistantPictureFrameData> type() {
        return TYPE;
    }

    @Override
    public boolean matches(BlockEntity blockEntity) {
        if (blockEntity == null) return false;
        String className = blockEntity.getClass().getName();
        return className.equals("me.chrr.camerapture.block.PictureFrameBlockEntity") ||
               className.contains("PictureFrameBlockEntity");
    }

    @Override
    @Nullable
    public DistantPictureFrameData capture(ServerLevel level, BlockPos pos, BlockEntity be) {
        try {
            Class<?> clazz = be.getClass();
            Method getItemStack = clazz.getMethod("getItemStack");
            Method getFacing = clazz.getMethod("getFacing");
            Method getFrameWidth = clazz.getMethod("getFrameWidth");
            Method getFrameHeight = clazz.getMethod("getFrameHeight");
            Method getRotation = clazz.getMethod("getRotation");
            Method isPictureGlowing = clazz.getMethod("isPictureGlowing");

            ItemStack stack = (ItemStack) getItemStack.invoke(be);
            if (stack == null || stack.isEmpty()) {
                return null;
            }

            UUID pictureId = extractPictureId(stack);
            if (pictureId == null) {
                return null;
            }

            Direction facing = (Direction) getFacing.invoke(be);
            if (facing == null) {
                facing = Direction.NORTH;
            }
            int width = (int) getFrameWidth.invoke(be);
            int height = (int) getFrameHeight.invoke(be);
            int rotation = (int) getRotation.invoke(be);
            boolean glow = (boolean) isPictureGlowing.invoke(be);
            int packedLight = getPackedLight(level, pos);

            return new DistantPictureFrameData(pictureId, facing, width, height, rotation, glow, packedLight);
        } catch (Exception e) {
            DistantDecorations.LOGGER.debug("Could not capture PictureFrameBlockEntity at {}", pos, e);
            return null;
        }
    }

    @Nullable
    private UUID extractPictureId(ItemStack stack) {
        // 1. Try reflection for Camerapture's PictureData record on custom components
        try {
            Class<?> camClass = Class.forName("me.chrr.camerapture.Camerapture");
            Object dataCompType = camClass.getField("PICTURE_DATA").get(null);
            Method getComponent = ItemStack.class.getMethod("get", Class.forName("net.minecraft.core.component.DataComponentType"));
            Object pictureData = getComponent.invoke(stack, dataCompType);
            if (pictureData != null) {
                Method idMethod = pictureData.getClass().getMethod("id");
                return (UUID) idMethod.invoke(pictureData);
            }
        } catch (Exception ignored) {
        }

        // 2. Fallback: inspect CustomData component if present
        try {
            Object customData = stack.get(DataComponents.CUSTOM_DATA);
            if (customData != null) {
                Method getUnsafe = customData.getClass().getMethod("getUnsafe");
                Object tag = getUnsafe.invoke(customData);
                if (tag instanceof net.minecraft.nbt.CompoundTag compoundTag) {
                    return compoundTag.read("id", UUIDUtil.AUTHLIB_CODEC).orElse(null);
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    @Override
    public AABB calculateBounds(ServerLevel level, BlockPos pos, DistantPictureFrameData data) {
        return calculateBounds(pos, data.facing(), data.width(), data.height());
    }

    public static AABB calculateBounds(BlockPos worldPosition, Direction facing, int width, int height) {
        double thickness = 0.0625; // 1/16 block
        double minX, maxX, minZ, maxZ;
        switch (facing) {
            case SOUTH -> {
                minX = 0.0;
                maxX = width;
                minZ = 0.0;
                maxZ = thickness;
            }
            case EAST -> {
                minX = 0.0;
                maxX = thickness;
                minZ = 1.0 - width;
                maxZ = 1.0;
            }
            case WEST -> {
                minX = 1.0 - thickness;
                maxX = 1.0;
                minZ = 0.0;
                maxZ = width;
            }
            default -> { // NORTH
                minX = 1.0 - width;
                maxX = 1.0;
                minZ = 1.0 - thickness;
                maxZ = 1.0;
            }
        }
        return new AABB(
            worldPosition.getX() + minX, worldPosition.getY(), worldPosition.getZ() + minZ,
            worldPosition.getX() + maxX, worldPosition.getY() + height, worldPosition.getZ() + maxZ
        );
    }
}
