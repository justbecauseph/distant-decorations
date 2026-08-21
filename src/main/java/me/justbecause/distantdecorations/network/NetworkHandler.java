package me.justbecause.distantdecorations.network;

import me.justbecause.distantdecorations.network.c2s.C2SClientHello;
import me.justbecause.distantdecorations.network.c2s.C2SSubscriptionUpdate;
import me.justbecause.distantdecorations.network.s2c.S2CRegionDelta;
import me.justbecause.distantdecorations.network.s2c.S2CRegionSnapshot;
import me.justbecause.distantdecorations.network.s2c.S2CRegionUnload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class NetworkHandler {
    private NetworkHandler() {}

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(C2SClientHello.TYPE, C2SClientHello.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(C2SSubscriptionUpdate.TYPE, C2SSubscriptionUpdate.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(S2CRegionSnapshot.TYPE, S2CRegionSnapshot.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(S2CRegionDelta.TYPE, S2CRegionDelta.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(S2CRegionUnload.TYPE, S2CRegionUnload.CODEC);
    }
}
