package me.justbecause.distantdecorations.mixin.chunky;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.justbecause.distantdecorations.integration.chunky.ChunkyIngestService;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.popcraft.chunky.mixin.ServerChunkCacheMixin;
import org.popcraft.chunky.platform.FabricWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

@Mixin(value = FabricWorld.class, remap = false)
public class MixinFabricWorld {
    @WrapOperation(
        method = "getChunkAtAsync",
        at = @At(
            value = "INVOKE",
            target = "Lorg/popcraft/chunky/mixin/ServerChunkCacheMixin;invokeGetChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;"
        )
    )
    private CompletableFuture<ChunkResult<ChunkAccess>> captureGeneratedChunk(
        ServerChunkCacheMixin instance,
        int x,
        int z,
        ChunkStatus chunkStatus,
        boolean create,
        Operation<CompletableFuture<ChunkResult<ChunkAccess>>> original
    ) {
        var future = original.call(instance, x, z, chunkStatus, create);
        return future.thenApply(res -> {
            res.ifSuccess(chunk -> {
                if (chunk instanceof LevelChunk worldChunk) {
                    ChunkyIngestService.onChunkCompleted(worldChunk);
                }
            });
            return res;
        });
    }
}
