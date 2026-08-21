package me.justbecause.distantdecorations.provider.painting;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

import java.util.Objects;

public record DistantPaintingData(
    Identifier variant,
    Direction facing,
    int width,
    int height,
    int packedLight
) {
    public DistantPaintingData {
        Objects.requireNonNull(variant, "variant cannot be null");
        Objects.requireNonNull(facing, "facing cannot be null");
    }
}
