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

        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                net.minecraft.commands.Commands.literal("dd")
                    .then(net.minecraft.commands.Commands.literal("stats")
                        .executes(ctx -> {
                            long regions = me.justbecause.distantdecorations.telemetry.TelemetryMetrics.SERVER_REGIONS.get();
                            long decos = me.justbecause.distantdecorations.telemetry.TelemetryMetrics.SERVER_INDEXED_DECORATIONS.get();
                            long adds = me.justbecause.distantdecorations.telemetry.TelemetryMetrics.SERVER_ADDS.get();
                            long removes = me.justbecause.distantdecorations.telemetry.TelemetryMetrics.SERVER_REMOVES.get();
                            long snapshots = me.justbecause.distantdecorations.telemetry.TelemetryMetrics.SERVER_SNAPSHOTS_SENT.get();
                            long deltas = me.justbecause.distantdecorations.telemetry.TelemetryMetrics.SERVER_DELTAS_SENT.get();
                            long bytesSent = me.justbecause.distantdecorations.telemetry.TelemetryMetrics.SERVER_METADATA_BYTES_SENT.get();

                            ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                                String.format(
                                    "§6[Distant Decorations Server Stats]§r\n" +
                                    "• Loaded Regions: %d\n" +
                                    "• Indexed Decorations: %d\n" +
                                    "• Total Adds / Removals: %d / %d\n" +
                                    "• Sent Snapshots / Deltas: %d / %d\n" +
                                    "• Bandwidth Sent: %.2f KiB",
                                    regions, decos, adds, removes, snapshots, deltas, bytesSent / 1024.0
                                )
                            ), false);
                            return 1;
                        })
                    )
                    .then(net.minecraft.commands.Commands.literal("toggle")
                        .executes(ctx -> {
                            boolean next = !me.justbecause.distantdecorations.config.DistantDecorationsConfig.isMasterEnabled();
                            me.justbecause.distantdecorations.config.DistantDecorationsConfig.setMasterEnabled(next);
                            ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("§6[Distant Decorations]§r Master enabled: " + next), true);
                            return 1;
                        })
                    )
            );
        });
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
