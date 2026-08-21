package me.justbecause.distantdecorations.client;

import me.justbecause.distantdecorations.DistantDecorations;
import net.fabricmc.api.ClientModInitializer;

public class DistantDecorationsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DistantDecorations.LOGGER.info("Distant Decorations Client initializing...");
    }
}
