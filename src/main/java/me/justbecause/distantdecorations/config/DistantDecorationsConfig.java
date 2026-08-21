package me.justbecause.distantdecorations.config;

import net.minecraft.resources.Identifier;

public final class DistantDecorationsConfig {

    private static volatile boolean masterEnabled = !Boolean.getBoolean("distantdecorations.disabled");
    private static volatile boolean benchmarkMode = Boolean.getBoolean("distantdecorations.benchmark_mode");

    private DistantDecorationsConfig() {}

    public static boolean isMasterEnabled() {
        return masterEnabled;
    }

    public static void setMasterEnabled(boolean enabled) {
        masterEnabled = enabled;
    }

    public static boolean isBenchmarkMode() {
        return benchmarkMode;
    }

    public static void setBenchmarkMode(boolean enabled) {
        benchmarkMode = enabled;
    }

    public static boolean isProviderEnabled(Identifier providerId) {
        if (!masterEnabled) {
            return false;
        }
        String prop = "distantdecorations.provider." + providerId.getNamespace() + "." + providerId.getPath() + ".disabled";
        String namespaceProp = "distantdecorations.provider." + providerId.getNamespace() + ".disabled";
        return !Boolean.getBoolean(prop) && !Boolean.getBoolean(namespaceProp);
    }
}
