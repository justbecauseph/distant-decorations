package me.justbecause.distantdecorations.client.render;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationProvider;
import me.justbecause.distantdecorations.api.DecorationRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LiveHandoffTracker {
    private static final LiveHandoffTracker INSTANCE = new LiveHandoffTracker();

    public static LiveHandoffTracker getInstance() {
        return INSTANCE;
    }

    public enum HandoffState {
        DISTANT,
        WAITING_FOR_LIVE,
        LIVE
    }

    private final Map<Long, Long> chunkLoadTimes = new ConcurrentHashMap<>();
    private static final long WAITING_GRACE_PERIOD_MS = 200; // 200ms grace period for BE sync packets

    public void onChunkLoaded(int chunkX, int chunkZ) {
        chunkLoadTimes.put(ChunkPos.pack(chunkX, chunkZ), System.currentTimeMillis());
    }

    public void onChunkUnloaded(int chunkX, int chunkZ) {
        chunkLoadTimes.remove(ChunkPos.pack(chunkX, chunkZ));
    }

    public void clear() {
        chunkLoadTimes.clear();
    }

    /**
     * Determines whether the distant decoration at the given anchor should be suppressed in favor of the live block entity.
     * Implements the 3-state machine: DISTANT -> WAITING_FOR_LIVE -> LIVE.
     */
    public boolean isSuppressed(DecorationId id, ClientLevel level) {
        BlockPos pos = id.anchor();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
            // Remote / unloaded chunk -> DISTANT -> render distant decoration
            return false;
        }

        ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return false;
        }

        BlockEntity be = chunk.getBlockEntity(pos);
        if (be != null) {
            DecorationProvider<?> provider = DecorationRegistry.findProvider(be);
            if (provider == null || provider.type().id().equals(id.type())) {
                // Real matching BE exists in loaded chunk -> LIVE -> suppress distant renderer
                return true;
            }
        }

        // Chunk is loaded, but BE not yet in block entity map (WAITING_FOR_LIVE)
        // Check if chunk was loaded recently to avoid 1-frame flickering
        Long loadTime = chunkLoadTimes.get(ChunkPos.pack(chunkX, chunkZ));
        if (loadTime != null && (System.currentTimeMillis() - loadTime) < WAITING_GRACE_PERIOD_MS) {
            // Waiting for BE sync, keep rendering distant decoration
            return false;
        }

        return false;
    }

    public HandoffState getState(DecorationId id, ClientLevel level) {
        BlockPos pos = id.anchor();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
            return HandoffState.DISTANT;
        }

        ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return HandoffState.DISTANT;
        }

        BlockEntity be = chunk.getBlockEntity(pos);
        if (be != null) {
            DecorationProvider<?> provider = DecorationRegistry.findProvider(be);
            if (provider == null || provider.type().id().equals(id.type())) {
                return HandoffState.LIVE;
            }
        }

        return HandoffState.WAITING_FOR_LIVE;
    }
}
