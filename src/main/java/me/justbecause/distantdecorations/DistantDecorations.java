package me.justbecause.distantdecorations;

import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.api.DecorationRegistry;
import me.justbecause.distantdecorations.network.NetworkHandler;
import me.justbecause.distantdecorations.server.ServerDecorationManager;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DistantDecorations implements ModInitializer {
    public static final String MOD_ID = "distantdecorations";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Distant Decorations initializing...");
        NetworkHandler.init();
        ServerDecorationManager.getInstance().init();
    }

    @Nullable
    public static DecorationRecord publish(ServerLevel level, BlockPos pos) {
        return ServerDecorationManager.getInstance().publish(level, pos);
    }

    @Nullable
    public static DecorationRecord publish(ServerLevel level, BlockPos pos, BlockEntity be) {
        return ServerDecorationManager.getInstance().publish(level, pos, be);
    }

    public static boolean remove(ServerLevel level, BlockPos pos) {
        return ServerDecorationManager.getInstance().remove(level, pos);
    }
}
