package me.justbecause.distantdecorations.client;

import me.justbecause.distantdecorations.DistantDecorations;
import me.justbecause.distantdecorations.client.network.ClientNetworkManager;
import me.justbecause.distantdecorations.client.render.DecorationRenderManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public class DistantDecorationsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DistantDecorations.LOGGER.info("Distant Decorations Client initializing...");
        ClientNetworkManager.getInstance().init();
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> DecorationRenderManager.getInstance().renderFrame(context));
    }
}
