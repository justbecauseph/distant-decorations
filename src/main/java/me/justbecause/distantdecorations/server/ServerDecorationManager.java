package me.justbecause.distantdecorations.server;

import me.justbecause.distantdecorations.DistantDecorations;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.network.c2s.C2SClientHello;
import me.justbecause.distantdecorations.network.c2s.C2SSubscriptionUpdate;
import me.justbecause.distantdecorations.server.storage.ServerDecorationWorldIndex;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerDecorationManager {
    private static final ServerDecorationManager INSTANCE = new ServerDecorationManager();

    private final Map<ResourceKey<Level>, ServerDecorationWorldIndex> worldIndices = new ConcurrentHashMap<>();
    private final Map<ResourceKey<Level>, ServerDecorationWorldIndex> pendingRecoveryIndices = new ConcurrentHashMap<>();

    private ServerDecorationManager() {}

    public static ServerDecorationManager getInstance() {
        return INSTANCE;
    }

    public void init() {
        ServerPlayNetworking.registerGlobalReceiver(C2SClientHello.TYPE, (payload, context) -> {
            ServerNetworkManager.getInstance().handleClientHello(context.player(), payload);
        });

        ServerPlayNetworking.registerGlobalReceiver(C2SSubscriptionUpdate.TYPE, (payload, context) -> {
            ServerNetworkManager.getInstance().handleSubscriptionUpdate(context.player(), payload);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerNetworkManager.getInstance().onPlayerJoin(handler.getPlayer());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerNetworkManager.getInstance().onPlayerLeave(handler.getPlayer());
        });

        ServerLevelEvents.LOAD.register((server, level) -> {
            Path rootDir = server.getWorldPath(LevelResource.ROOT);
            String dimPath = level.dimension().identifier().getNamespace() + "_" + level.dimension().identifier().getPath();
            Path storageDir = rootDir.resolve("data").resolve("distantdecorations").resolve(dimPath);
            ServerDecorationWorldIndex index = new ServerDecorationWorldIndex(level, storageDir);
            worldIndices.put(level.dimension(), index);
            DistantDecorations.LOGGER.info("Initialized distant decorations index for {}", level.dimension().identifier());
        });

        ServerLevelEvents.UNLOAD.register((server, level) -> {
            handleLevelUnload(level.dimension());
        });

        ServerChunkEvents.CHUNK_LOAD.register((serverLevel, chunk, generated) -> {
            ServerDecorationWorldIndex index = worldIndices.get(serverLevel.dimension());
            if (index != null) {
                index.reconcileChunk(chunk);
            }
        });

        ServerTickEvents.END_LEVEL_TICK.register(world -> {
            ServerNetworkManager.getInstance().tick(world);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            handleServerStopping();
        });
    }

    public boolean handleLevelUnload(ResourceKey<Level> dimension) {
        ServerDecorationWorldIndex index = worldIndices.remove(dimension);
        if (index == null) {
            return true;
        }
        boolean closed = index.close();
        if (!closed) {
            DistantDecorations.LOGGER.error("Failed to close world index cleanly during unload for dimension {}; retaining in recovery queue", dimension.identifier());
            pendingRecoveryIndices.put(dimension, index);
            return false;
        } else {
            pendingRecoveryIndices.remove(dimension);
            return true;
        }
    }

    public boolean retryPendingRecovery(ResourceKey<Level> dimension) {
        ServerDecorationWorldIndex index = pendingRecoveryIndices.get(dimension);
        if (index == null) {
            return true;
        }
        if (index.close()) {
            DistantDecorations.LOGGER.info("Successfully recovered and persisted world index for dimension {}", dimension.identifier());
            pendingRecoveryIndices.remove(dimension);
            return true;
        }
        return false;
    }

    public boolean handleServerStopping() {
        boolean allClean = true;
        for (Map.Entry<ResourceKey<Level>, ServerDecorationWorldIndex> entry : worldIndices.entrySet()) {
            ResourceKey<Level> dim = entry.getKey();
            ServerDecorationWorldIndex index = entry.getValue();
            if (!index.close()) {
                DistantDecorations.LOGGER.error("Failed to persist world index during server shutdown for dimension {}", dim.identifier());
                pendingRecoveryIndices.put(dim, index);
                allClean = false;
            }
        }
        worldIndices.clear();

        var it = pendingRecoveryIndices.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().close()) {
                it.remove();
            } else {
                allClean = false;
            }
        }

        if (!pendingRecoveryIndices.isEmpty()) {
            DistantDecorations.LOGGER.error("CRITICAL: Server stopping with unpersisted decorations in {} dimension(s): {}",
                pendingRecoveryIndices.size(), pendingRecoveryIndices.keySet());
        } else {
            DistantDecorations.LOGGER.info("All Distant Decorations world indices successfully persisted on shutdown.");
        }

        return allClean;
    }

    public Map<ResourceKey<Level>, ServerDecorationWorldIndex> getPendingRecoveryIndices() {
        return java.util.Collections.unmodifiableMap(pendingRecoveryIndices);
    }

    public void registerIndexForTesting(ResourceKey<Level> dimension, ServerDecorationWorldIndex index) {
        worldIndices.put(dimension, index);
    }

    public void clearForTesting() {
        worldIndices.clear();
        pendingRecoveryIndices.clear();
    }

    @Nullable
    public ServerDecorationWorldIndex getIndex(ServerLevel level) {
        return worldIndices.get(level.dimension());
    }

    @Nullable
    public ServerDecorationWorldIndex getIndex(ResourceKey<Level> dimension) {
        return worldIndices.get(dimension);
    }

    @Nullable
    public DecorationRecord publish(ServerLevel level, BlockPos pos) {
        ServerDecorationWorldIndex index = getIndex(level);
        return index != null ? index.publish(pos, null) : null;
    }

    @Nullable
    public DecorationRecord publish(ServerLevel level, BlockPos pos, BlockEntity be) {
        ServerDecorationWorldIndex index = getIndex(level);
        return index != null ? index.publish(pos, be) : null;
    }

    public boolean remove(ServerLevel level, BlockPos pos) {
        ServerDecorationWorldIndex index = getIndex(level);
        return index != null && index.remove(pos);
    }
}
