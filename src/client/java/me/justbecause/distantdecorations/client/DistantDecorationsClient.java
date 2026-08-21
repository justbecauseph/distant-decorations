package me.justbecause.distantdecorations.client;

import me.justbecause.distantdecorations.DistantDecorations;
import me.justbecause.distantdecorations.client.network.ClientNetworkManager;
import me.justbecause.distantdecorations.client.render.DecorationRenderManager;
import me.justbecause.distantdecorations.config.DistantDecorationsConfig;
import me.justbecause.distantdecorations.telemetry.TelemetryMetrics;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class DistantDecorationsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DistantDecorations.LOGGER.info("Distant Decorations Client initializing...");

        ClientNetworkManager.getInstance().init();
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> DecorationRenderManager.getInstance().renderFrame(context));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                com.mojang.brigadier.builder.LiteralArgumentBuilder.<FabricClientCommandSource>literal("ddc")
                    .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<FabricClientCommandSource>literal("stats")
                        .executes(ctx -> {
                            var mc = Minecraft.getInstance();
                            if (mc.level != null) {
                                var world = DecorationRenderManager.getInstance().getWorld(mc.level.dimension());
                                int decos = world != null ? world.getTotalDecorationsCount() : 0;
                                int regions = world != null ? world.getLoadedRegions().size() : 0;
                                long visible = TelemetryMetrics.clientRenderedDecorations;
                                long suppressed = TelemetryMetrics.clientLiveSuppressions;
                                long distance = TelemetryMetrics.clientDistanceRejected;
                                long subpixel = TelemetryMetrics.clientSubpixelRejected;
                                long farLod = TelemetryMetrics.clientFarLodScaledRenders;
                                long frustum = TelemetryMetrics.clientObjectsFrustumRejected;
                                long budget = TelemetryMetrics.clientBudgetRejected;

                                int subRadius = DistantDecorationsConfig.getClientSubscriptionRadiusChunks();
                                double maxDist = DistantDecorationsConfig.getMaxRenderDistance();
                                double minPixel = DistantDecorationsConfig.getMinProjectedPixelSize();

                                ctx.getSource().sendFeedback(Component.literal(
                                    String.format(
                                        "§a[Distant Decorations Client Stats]§r\n" +
                                        "• Config: %d chunks sub-radius | %.0f blocks max-dist | %.2f px min-size\n" +
                                        "• Synced Regions: %d | Total Client Decorations: %d\n" +
                                        "• Last Frame Visible/Rendered: %d\n" +
                                        "• Last Frame Far-LOD Scaled: %d\n" +
                                        "• Last Frame Suppressed (Live): %d\n" +
                                        "• Last Frame Culled (Distance): %d\n" +
                                        "• Last Frame Culled (Subpixel): %d\n" +
                                        "• Last Frame Culled (Frustum): %d\n" +
                                        "• Last Frame Culled (Budget): %d",
                                        subRadius, maxDist, minPixel,
                                        regions, decos, visible, farLod, suppressed, distance, subpixel, frustum, budget
                                    )
                                ));
                            }
                            return 1;
                        })
                    )
                    .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle")
                        .executes(ctx -> {
                            boolean next = !DistantDecorationsConfig.isMasterEnabled();
                            DistantDecorationsConfig.setMasterEnabled(next);
                            ctx.getSource().sendFeedback(Component.literal("§a[Distant Decorations Client]§r Master enabled: " + next));
                            return 1;
                        })
                    )
            );
        });
    }
}
