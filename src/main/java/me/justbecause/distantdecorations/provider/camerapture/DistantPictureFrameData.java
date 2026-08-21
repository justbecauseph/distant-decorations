package me.justbecause.distantdecorations.provider.camerapture;

import net.minecraft.core.Direction;

import java.util.Objects;
import java.util.UUID;

public record DistantPictureFrameData(
    UUID pictureId,
    Direction facing,
    int width,
    int height,
    int rotation,
    boolean glow,
    int packedLight
) {
    public DistantPictureFrameData {
        Objects.requireNonNull(pictureId, "pictureId cannot be null");
        Objects.requireNonNull(facing, "facing cannot be null");
    }
}
