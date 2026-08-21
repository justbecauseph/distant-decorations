package me.justbecause.distantdecorations.client.render;

import me.justbecause.distantdecorations.api.DecorationRecord;

import java.util.Comparator;

public final class RenderBudget {
    private int maxSubmissionsPerFrame = 2000;
    private double minProjectedPixelSize = 1.0;
    private double maxRenderDistance = 4096.0;

    public record RenderCandidate(
        DecorationRecord record,
        double projectedPixelSize,
        double distanceSq,
        double priorityScore
    ) {}

    public static final Comparator<RenderCandidate> PRIORITY_COMPARATOR = (a, b) -> Double.compare(b.priorityScore(), a.priorityScore());

    public int getMaxSubmissionsPerFrame() {
        return maxSubmissionsPerFrame;
    }

    public void setMaxSubmissionsPerFrame(int maxSubmissionsPerFrame) {
        this.maxSubmissionsPerFrame = Math.max(1, maxSubmissionsPerFrame);
    }

    public double getMinProjectedPixelSize() {
        return minProjectedPixelSize;
    }

    public void setMinProjectedPixelSize(double minProjectedPixelSize) {
        this.minProjectedPixelSize = Math.max(0.1, minProjectedPixelSize);
    }

    public double getMaxRenderDistance() {
        return maxRenderDistance;
    }

    public void setMaxRenderDistance(double maxRenderDistance) {
        this.maxRenderDistance = Math.max(16.0, maxRenderDistance);
    }

    public double calculatePriority(double projectedPixelSize, double distanceSq) {
        // Larger screen size gets primary priority; distance acts as a gentle tie-breaker
        return projectedPixelSize * 1000.0 - Math.sqrt(distanceSq);
    }
}
