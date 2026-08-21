package me.justbecause.distantdecorations.client.spatial;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public record ProjectionMetrics(
    Vec3 cameraPos,
    int viewportWidth,
    int viewportHeight,
    double fovYRadians,
    double projectionFactor
) {
    public static ProjectionMetrics of(Vec3 cameraPos, int viewportWidth, int viewportHeight, double fovDegrees) {
        double fovYRadians = Math.toRadians(Math.max(1.0, Math.min(170.0, fovDegrees)));
        double halfFovTan = Math.tan(fovYRadians * 0.5);
        double projectionFactor = (viewportHeight * 0.5) / Math.max(1e-4, halfFovTan);
        return new ProjectionMetrics(cameraPos, viewportWidth, viewportHeight, fovYRadians, projectionFactor);
    }

    public double calculateProjectedPixelSize(AABB bounds) {
        double centerX = (bounds.minX + bounds.maxX) * 0.5;
        double centerY = (bounds.minY + bounds.maxY) * 0.5;
        double centerZ = (bounds.minZ + bounds.maxZ) * 0.5;

        double dx = centerX - cameraPos.x;
        double dy = centerY - cameraPos.y;
        double dz = centerZ - cameraPos.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < 1e-4) {
            return Math.max(viewportWidth, viewportHeight);
        }
        double dist = Math.sqrt(distSq);

        double sizeX = bounds.maxX - bounds.minX;
        double sizeY = bounds.maxY - bounds.minY;
        double sizeZ = bounds.maxZ - bounds.minZ;
        double diameter = Math.sqrt(sizeX * sizeX + sizeY * sizeY + sizeZ * sizeZ);

        return (diameter / dist) * projectionFactor;
    }

    public double calculateProjectedPixelSize(Vec3 center, double diameter) {
        double dx = center.x - cameraPos.x;
        double dy = center.y - cameraPos.y;
        double dz = center.z - cameraPos.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < 1e-4) {
            return Math.max(viewportWidth, viewportHeight);
        }
        double dist = Math.sqrt(distSq);
        return (diameter / dist) * projectionFactor;
    }

    public double getDistanceSq(Vec3 pos) {
        double dx = pos.x - cameraPos.x;
        double dy = pos.y - cameraPos.y;
        double dz = pos.z - cameraPos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double getDistanceSq(AABB bounds) {
        double centerX = (bounds.minX + bounds.maxX) * 0.5;
        double centerY = (bounds.minY + bounds.maxY) * 0.5;
        double centerZ = (bounds.minZ + bounds.maxZ) * 0.5;
        return getDistanceSq(new Vec3(centerX, centerY, centerZ));
    }
}
