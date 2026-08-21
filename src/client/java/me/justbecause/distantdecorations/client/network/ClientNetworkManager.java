package me.justbecause.distantdecorations.client.network;

import me.justbecause.distantdecorations.api.DecorationRegistry;
import me.justbecause.distantdecorations.api.DecorationType;
import me.justbecause.distantdecorations.client.render.DecorationRenderManager;
import me.justbecause.distantdecorations.client.spatial.ClientDecorationWorld;
import me.justbecause.distantdecorations.network.c2s.C2SClientHello;
import me.justbecause.distantdecorations.network.c2s.C2SSubscriptionUpdate;
import me.justbecause.distantdecorations.network.s2c.S2CRegionDelta;
import me.justbecause.distantdecorations.network.s2c.S2CRegionSnapshot;
import me.justbecause.distantdecorations.network.s2c.S2CRegionUnload;
import me.justbecause.distantdecorations.server.ServerNetworkManager;
import me.justbecause.distantdecorations.telemetry.TelemetryMetrics;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public final class ClientNetworkManager {
    private static final ClientNetworkManager INSTANCE = new ClientNetworkManager();

    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;

    private ClientNetworkManager() {}

    public static ClientNetworkManager getInstance() {
        return INSTANCE;
    }

    public void init() {
        ClientPlayNetworking.registerGlobalReceiver(S2CRegionSnapshot.TYPE, (payload, context) -> {
            ClientLevel level = context.client().level;
            if (level != null) {
                ClientDecorationWorld world = DecorationRenderManager.getInstance().getOrCreateWorld(level.dimension());
                world.putSnapshot(payload.regionX(), payload.regionZ(), payload.revision(), payload.records());
                TelemetryMetrics.CLIENT_SYNCED_REGIONS.set(world.getAllRegions().size());
                TelemetryMetrics.CLIENT_SYNCED_DECORATIONS.set(world.getTotalDecorationsCount());
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(S2CRegionDelta.TYPE, (payload, context) -> {
            ClientLevel level = context.client().level;
            if (level != null) {
                ClientDecorationWorld world = DecorationRenderManager.getInstance().getOrCreateWorld(level.dimension());
                world.applyDelta(payload.regionX(), payload.regionZ(), payload.revision(), payload.additionsAndUpdates(), payload.removals());
                TelemetryMetrics.CLIENT_SYNCED_REGIONS.set(world.getAllRegions().size());
                TelemetryMetrics.CLIENT_SYNCED_DECORATIONS.set(world.getTotalDecorationsCount());
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(S2CRegionUnload.TYPE, (payload, context) -> {
            ClientLevel level = context.client().level;
            if (level != null) {
                ClientDecorationWorld world = DecorationRenderManager.getInstance().getWorld(level.dimension());
                if (world != null) {
                    world.unloadRegion(payload.regionX(), payload.regionZ());
                    TelemetryMetrics.CLIENT_SYNCED_REGIONS.set(world.getAllRegions().size());
                    TelemetryMetrics.CLIENT_SYNCED_DECORATIONS.set(world.getTotalDecorationsCount());
                }
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            DecorationRenderManager.getInstance().clearAll();
            sendClientHello();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            DecorationRenderManager.getInstance().clearAll();
            lastChunkX = Integer.MIN_VALUE;
            lastChunkZ = Integer.MIN_VALUE;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && client.level != null) {
                int cx = client.player.getBlockX() >> 4;
                int cz = client.player.getBlockZ() >> 4;
                if (Math.abs(cx - lastChunkX) >= 8 || Math.abs(cz - lastChunkZ) >= 8) {
                    lastChunkX = cx;
                    lastChunkZ = cz;
                    sendSubscriptionUpdate(cx, cz);
                }
            }
        });
    }

    public void sendClientHello() {
        if (!ClientPlayNetworking.canSend(C2SClientHello.TYPE)) {
            return;
        }
        List<Identifier> types = new ArrayList<>();
        for (DecorationType<?> type : DecorationRegistry.getTypes()) {
            types.add(type.id());
        }
        int radiusChunks = Minecraft.getInstance().options.renderDistance().get() * 4; // request up to 4x render distance
        ClientPlayNetworking.send(new C2SClientHello(ServerNetworkManager.PROTOCOL_VERSION, types, radiusChunks));
    }

    public void sendSubscriptionUpdate(int centerChunkX, int centerChunkZ) {
        if (!ClientPlayNetworking.canSend(C2SSubscriptionUpdate.TYPE)) {
            return;
        }
        int radiusChunks = Minecraft.getInstance().options.renderDistance().get() * 4;
        ClientPlayNetworking.send(new C2SSubscriptionUpdate(centerChunkX, centerChunkZ, radiusChunks));
    }
}
