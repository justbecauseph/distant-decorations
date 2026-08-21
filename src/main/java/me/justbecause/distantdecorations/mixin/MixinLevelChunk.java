package me.justbecause.distantdecorations.mixin;

import me.justbecause.distantdecorations.server.ServerDecorationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class MixinLevelChunk {

    @Shadow
    @Final
    Level level;

    @Inject(method = "addAndRegisterBlockEntity", at = @At("RETURN"))
    private void dd$onAddAndRegisterBlockEntity(BlockEntity blockEntity, CallbackInfo ci) {
        if (this.level instanceof ServerLevel serverLevel && blockEntity != null) {
            ServerDecorationManager.getInstance().publish(serverLevel, blockEntity.getBlockPos(), blockEntity);
        }
    }

    @Inject(method = "setBlockEntity", at = @At("RETURN"))
    private void dd$onSetBlockEntity(BlockEntity blockEntity, CallbackInfo ci) {
        if (this.level instanceof ServerLevel serverLevel && blockEntity != null) {
            ServerDecorationManager.getInstance().publish(serverLevel, blockEntity.getBlockPos(), blockEntity);
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void dd$onRemoveBlockEntity(BlockPos pos, CallbackInfo ci) {
        if (this.level instanceof ServerLevel serverLevel && pos != null) {
            ServerDecorationManager.getInstance().remove(serverLevel, pos);
        }
    }
}
