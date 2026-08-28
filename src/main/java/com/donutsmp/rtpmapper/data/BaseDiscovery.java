package com.donutsmp.rtpmapper.data;

import java.util.List;
import java.util.Objects;

/** Immutable record of a detected player base or spawner cluster. */
public record BaseDiscovery(
        long timestampMillis,
        double x,
        double y,
        double z,
        String dimension,
        List<String> detectedBlocks,
        boolean raided
) {
    public BaseDiscovery(long timestampMillis, double x, double y, double z, String dimension, List<String> detectedBlocks) {
        this(timestampMillis, x, y, z, dimension, detectedBlocks, false);
    }

    public BaseDiscovery {
        Objects.requireNonNull(dimension, "dimension");
        detectedBlocks = detectedBlocks == null ? List.of() : List.copyOf(detectedBlocks);
    }

    public BaseDiscovery withRaided(boolean newRaided) {
        return new BaseDiscovery(timestampMillis, x, y, z, dimension, detectedBlocks, newRaided);
    }

    public String summary() {
        if (detectedBlocks.isEmpty()) {
            return raided ? "Base (Raided)" : "Base Structure";
        }
        return (raided ? "[RAIDED] " : "") + String.join(", ", detectedBlocks);
    }
}
