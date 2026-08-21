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

        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                com.mojang.brigadier.builder.LiteralArgumentBuilder.<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal("ddc")
                    .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal("stats")
                        .executes(ctx -> {
                            var mc = net.minecraft.client.Minecraft.getInstance();
                            if (mc.level != null) {
                                var world = me.justbecause.distantdecorations.client.render.DecorationRenderManager.getInstance().getWorld(mc.level.dimension());
                                int decos = world != null ? world.getTotalDecorationsCount() : 0;
                                int regions = world != null ? world.getLoadedRegions().size() : 0;
                                long visible = me.justbecause.distantdecorations.telemetry.TelemetryMetrics.clientRenderedDecorations;
                                long suppressed = me.justbecause.distantdecorations.telemetry.TelemetryMetrics.clientLiveSuppressions;
                                long subpixel = me.justbecause.distantdecorations.telemetry.TelemetryMetrics.clientSubpixelRejected;

                                ctx.getSource().sendFeedback(net.minecraft.network.chat.Component.literal(
                                    String.format(
                                        "§a[Distant Decorations Client Stats]§r\n" +
                                        "• Synced Regions: %d\n" +
                                        "• Total Client Decorations: %d\n" +
                                        "• Last Frame Visible/Rendered: %d\n" +
                                        "• Last Frame Suppressed (Live): %d\n" +
                                        "• Last Frame Culled (Subpixel): %d",
                                        regions, decos, visible, suppressed, subpixel
                                    )
                                ));
                            }
                            return 1;
                        })
                    )
                    .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal("toggle")
                        .executes(ctx -> {
                            boolean next = !me.justbecause.distantdecorations.config.DistantDecorationsConfig.isMasterEnabled();
                            me.justbecause.distantdecorations.config.DistantDecorationsConfig.setMasterEnabled(next);
                            ctx.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("§a[Distant Decorations Client]§r Master enabled: " + next));
                            return 1;
                        })
                    )
            );
        });
    }
}

