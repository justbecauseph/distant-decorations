package me.justbecause.distantdecorations.client.render;

import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.client.spatial.ClientDecoration;
import me.justbecause.distantdecorations.config.DistantDecorationsConfig;

import java.util.Comparator;

public final class RenderBudget {
    private int maxSubmissionsPerFrame = 2000;

    public record RenderCandidate(
        ClientDecoration decoration,
        double projectedPixelSize,
        double distanceSq,
        double priorityScore
    ) {
        public DecorationRecord record() {
            return decoration.record();
        }
    }

    public static final Comparator<RenderCandidate> PRIORITY_COMPARATOR = (a, b) -> Double.compare(b.priorityScore(), a.priorityScore());
    public static final Comparator<RenderCandidate> MIN_HEAP_COMPARATOR = (a, b) -> Double.compare(a.priorityScore(), b.priorityScore());

    public int getMaxSubmissionsPerFrame() {
        return maxSubmissionsPerFrame;
    }

    public void setMaxSubmissionsPerFrame(int maxSubmissionsPerFrame) {
        this.maxSubmissionsPerFrame = Math.max(1, maxSubmissionsPerFrame);
    }

    public double getMinProjectedPixelSize() {
        return DistantDecorationsConfig.getMinProjectedPixelSize();
    }

    public void setMinProjectedPixelSize(double minProjectedPixelSize) {
        DistantDecorationsConfig.setMinProjectedPixelSize(minProjectedPixelSize);
    }

    public double getMaxRenderDistance() {
        return DistantDecorationsConfig.getMaxRenderDistance();
    }

    public void setMaxRenderDistance(double maxRenderDistance) {
        DistantDecorationsConfig.setMaxRenderDistance(maxRenderDistance);
    }

    public double calculatePriority(double projectedPixelSize, double distanceSq) {
        // Larger screen size gets primary priority; distance acts as a gentle tie-breaker
        return projectedPixelSize * 1000.0 - Math.sqrt(distanceSq);
    }
}
