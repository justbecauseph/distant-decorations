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
    private final Map<Path, ServerDecorationWorldIndex> pendingRecoveryByStorage = new ConcurrentHashMap<>();

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
            openIndex(level, storageDir);
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

    @Nullable
    public ServerDecorationWorldIndex openIndex(@Nullable ServerLevel level, ResourceKey<Level> dimension, Path storageDir) {
        Path normalizedStorage = storageDir.toAbsolutePath().normalize();
        ServerDecorationWorldIndex pending = pendingRecoveryByStorage.get(normalizedStorage);
        if (pending != null) {
            DistantDecorations.LOGGER.info("Resolving pending recovery for storage {} before opening index for {}",
                normalizedStorage, dimension.identifier());
            if (pending.close()) {
                pendingRecoveryByStorage.remove(normalizedStorage, pending);
                DistantDecorations.LOGGER.info("Successfully resolved pending recovery for storage {}", normalizedStorage);
            } else {
                DistantDecorations.LOGGER.error("Cannot open index for {} at {}: previous index has unresolved pending writes in recovery queue",
                    dimension.identifier(), normalizedStorage);
                return null;
            }
        }

        ServerDecorationWorldIndex index = new ServerDecorationWorldIndex(level, storageDir);
        worldIndices.put(dimension, index);
        DistantDecorations.LOGGER.info("Initialized distant decorations index for {}", dimension.identifier());
        return index;
    }

    @Nullable
    public ServerDecorationWorldIndex openIndex(ServerLevel level, Path storageDir) {
        return openIndex(level, level.dimension(), storageDir);
    }

    @Nullable
    public ServerDecorationWorldIndex openIndex(ResourceKey<Level> dimension, Path storageDir) {
        return openIndex(null, dimension, storageDir);
    }

    public boolean handleLevelUnload(ResourceKey<Level> dimension) {
        ServerDecorationWorldIndex index = worldIndices.remove(dimension);
        if (index == null) {
            return true;
        }
        Path normalizedStorage = index.getStorageDir().toAbsolutePath().normalize();
        boolean closed = index.close();
        if (!closed) {
            DistantDecorations.LOGGER.error("Failed to close world index cleanly during unload for dimension {}; retaining in recovery queue for storage {}",
                dimension.identifier(), normalizedStorage);
            pendingRecoveryByStorage.put(normalizedStorage, index);
            return false;
        } else {
            pendingRecoveryByStorage.remove(normalizedStorage, index);
            return true;
        }
    }

    public boolean retryPendingRecovery(Path storageDir) {
        Path normalizedStorage = storageDir.toAbsolutePath().normalize();
        ServerDecorationWorldIndex index = pendingRecoveryByStorage.get(normalizedStorage);
        if (index == null) {
            return true;
        }
        if (index.close()) {
            DistantDecorations.LOGGER.info("Successfully recovered and persisted world index for storage {}", normalizedStorage);
            pendingRecoveryByStorage.remove(normalizedStorage, index);
            return true;
        }
        return false;
    }

    public boolean handleServerStopping() {
        boolean allClean = true;
        for (Map.Entry<ResourceKey<Level>, ServerDecorationWorldIndex> entry : worldIndices.entrySet()) {
            ResourceKey<Level> dim = entry.getKey();
            ServerDecorationWorldIndex index = entry.getValue();
            Path normalizedStorage = index.getStorageDir().toAbsolutePath().normalize();
            if (!index.close()) {
                DistantDecorations.LOGGER.error("Failed to persist world index during server shutdown for dimension {}", dim.identifier());
                pendingRecoveryByStorage.put(normalizedStorage, index);
                allClean = false;
            }
        }
        worldIndices.clear();

        var it = pendingRecoveryByStorage.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().close()) {
                it.remove();
            } else {
                allClean = false;
            }
        }

        if (!pendingRecoveryByStorage.isEmpty()) {
            DistantDecorations.LOGGER.error("CRITICAL: Server stopping with unpersisted decorations in {} storage path(s): {}",
                pendingRecoveryByStorage.size(), pendingRecoveryByStorage.keySet());
        } else {
            DistantDecorations.LOGGER.info("All Distant Decorations world indices successfully persisted on shutdown.");
        }

        return allClean && pendingRecoveryByStorage.isEmpty();
    }

    public Map<Path, ServerDecorationWorldIndex> getPendingRecoveryIndices() {
        return java.util.Collections.unmodifiableMap(pendingRecoveryByStorage);
    }

    public boolean isStoragePendingRecovery(Path storageDir) {
        return pendingRecoveryByStorage.containsKey(storageDir.toAbsolutePath().normalize());
    }

    public void registerIndexForTesting(ResourceKey<Level> dimension, ServerDecorationWorldIndex index) {
        worldIndices.put(dimension, index);
    }

    public void registerRecoveryForTesting(Path storageDir, ServerDecorationWorldIndex index) {
        pendingRecoveryByStorage.put(storageDir.toAbsolutePath().normalize(), index);
    }

    public void clearForTesting() {
        worldIndices.clear();
        pendingRecoveryByStorage.clear();
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
