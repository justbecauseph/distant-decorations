package me.justbecause.distantdecorations.mixin;

import me.justbecause.distantdecorations.server.ServerDecorationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class MixinBlockEntity {

    @Shadow
    protected Level level;

    @Shadow
    @Final
    protected BlockPos worldPosition;

    @Inject(method = "setChanged()V", at = @At("RETURN"))
    private void dd$onSetChanged(CallbackInfo ci) {
        if (this.level instanceof ServerLevel serverLevel) {
            BlockEntity self = (BlockEntity) (Object) this;
            ServerDecorationManager.getInstance().publish(serverLevel, this.worldPosition, self);
        }
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void dd$onSetRemoved(CallbackInfo ci) {
        if (this.level instanceof ServerLevel serverLevel) {
            ServerDecorationManager.getInstance().remove(serverLevel, this.worldPosition);
        }
    }
}
