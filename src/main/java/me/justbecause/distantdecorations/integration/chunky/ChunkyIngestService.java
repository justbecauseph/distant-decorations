package me.justbecause.distantdecorations.integration.chunky;

import me.justbecause.distantdecorations.server.ServerDecorationManager;
import me.justbecause.distantdecorations.server.storage.ServerDecorationWorldIndex;
import me.justbecause.distantdecorations.telemetry.TelemetryMetrics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ChunkyIngestService {
    private ChunkyIngestService() {}

    public static void onChunkCompleted(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        TelemetryMetrics.SERVER_CHUNKY_SCANS.incrementAndGet();

        // Ensure commits run safely on the server thread
        if (serverLevel.getServer().isSameThread()) {
            commitChunk(serverLevel, chunk);
        } else {
            serverLevel.getServer().execute(() -> commitChunk(serverLevel, chunk));
        }
    }

    private static void commitChunk(ServerLevel level, LevelChunk chunk) {
        ServerDecorationWorldIndex index = ServerDecorationManager.getInstance().getIndex(level);
        if (index != null) {
            index.reconcileChunk(chunk);
        }
    }
}
