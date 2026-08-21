package me.justbecause.distantdecorations.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

public interface DecorationProvider<T> {
    DecorationType<T> type();

    boolean matches(BlockEntity blockEntity);

    @Nullable
    T capture(ServerLevel level, BlockPos pos, BlockEntity blockEntity);

    AABB calculateBounds(ServerLevel level, BlockPos pos, T data);

    default int getPackedLight(ServerLevel level, BlockPos pos) {
        int blockLight = level.getBrightness(LightLayer.BLOCK, pos);
        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        return (skyLight << 20) | (blockLight << 4);
    }
}
