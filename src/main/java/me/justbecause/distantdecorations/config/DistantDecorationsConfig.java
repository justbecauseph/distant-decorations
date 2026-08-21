package me.justbecause.distantdecorations.config;

import net.minecraft.resources.Identifier;

public final class DistantDecorationsConfig {

    private static volatile boolean masterEnabled = !Boolean.getBoolean("distantdecorations.disabled");
    private static volatile boolean benchmarkMode = Boolean.getBoolean("distantdecorations.benchmark_mode");
    private static volatile int clientSubscriptionRadiusChunks = Integer.getInteger("distantdecorations.subscription_radius_chunks", 512);
    private static volatile double minProjectedPixelSize = Double.parseDouble(System.getProperty("distantdecorations.min_projected_pixel_size", "0.25"));
    private static volatile double maxRenderDistance = Double.parseDouble(System.getProperty("distantdecorations.max_render_distance", String.valueOf(clientSubscriptionRadiusChunks * 16.0)));

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

    public static int getClientSubscriptionRadiusChunks() {
        return clientSubscriptionRadiusChunks;
    }

    public static void setClientSubscriptionRadiusChunks(int radiusChunks) {
        clientSubscriptionRadiusChunks = Math.max(16, radiusChunks);
        maxRenderDistance = clientSubscriptionRadiusChunks * 16.0;
    }

    public static double getMinProjectedPixelSize() {
        return minProjectedPixelSize;
    }

    public static void setMinProjectedPixelSize(double pixelSize) {
        minProjectedPixelSize = Math.max(0.05, pixelSize);
    }

    public static double getMaxRenderDistance() {
        return maxRenderDistance;
    }

    public static void setMaxRenderDistance(double distance) {
        maxRenderDistance = Math.max(16.0, distance);
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
