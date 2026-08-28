package com.donutsmp.rtpmapper.mining;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validated settings captured once for a bounded mining run. */
public record MiningSettings(List<String> blockIds, int quantity, long timeoutNanos) {
    public static final int MIN_QUANTITY = 1;
    public static final int MAX_QUANTITY = 2_304;
    public static final double MIN_TIMEOUT_MINUTES = 1.0;
    public static final double MAX_TIMEOUT_MINUTES = 120.0;
    public static final int MAX_TARGETS = 64;
    public static final List<String> DEFAULT_BLOCK_IDS = List.of(
            "minecraft:chest",
            "minecraft:trapped_chest",
            "minecraft:barrel",
            "minecraft:shulker_box",
            "minecraft:white_shulker_box",
            "minecraft:orange_shulker_box",
            "minecraft:magenta_shulker_box",
            "minecraft:light_blue_shulker_box",
            "minecraft:yellow_shulker_box",
            "minecraft:lime_shulker_box",
            "minecraft:pink_shulker_box",
            "minecraft:gray_shulker_box",
            "minecraft:light_gray_shulker_box",
            "minecraft:cyan_shulker_box",
            "minecraft:purple_shulker_box",
            "minecraft:blue_shulker_box",
            "minecraft:brown_shulker_box",
            "minecraft:green_shulker_box",
            "minecraft:red_shulker_box",
            "minecraft:black_shulker_box",
            "minecraft:dispenser",
            "minecraft:dropper",
            "minecraft:hopper",
            "minecraft:spawner",
            "minecraft:enchanting_table",
            "minecraft:anvil",
            "minecraft:chipped_anvil",
            "minecraft:damaged_anvil",
            "minecraft:brewing_stand",
            "minecraft:beacon",
            "minecraft:furnace",
            "minecraft:blast_furnace",
            "minecraft:smoker",
            "minecraft:crafter",
            "minecraft:respawn_anchor",
            "minecraft:lodestone",
            "minecraft:netherite_block",
            "minecraft:ancient_debris"
    );
    public static final int DEFAULT_QUANTITY = 64;
    public static final double DEFAULT_TIMEOUT_MINUTES = 10.0;

    private static final Pattern BLOCK_ID = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9_./-]+"
    );

    public MiningSettings {
        blockIds = normalizeBlockIds(blockIds);
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException(
                    "Mining quantity must be from " + MIN_QUANTITY + " to " + MAX_QUANTITY + "."
            );
        }
        long minimumTimeout = minutesToNanos(MIN_TIMEOUT_MINUTES);
        long maximumTimeout = minutesToNanos(MAX_TIMEOUT_MINUTES);
        if (timeoutNanos < minimumTimeout || timeoutNanos > maximumTimeout) {
            throw new IllegalArgumentException("Mining timeout is outside the supported range.");
        }
    }

    public static MiningSettings defaults() {
        return new MiningSettings(
                DEFAULT_BLOCK_IDS,
                DEFAULT_QUANTITY,
                minutesToNanos(DEFAULT_TIMEOUT_MINUTES)
        );
    }

    public static List<String> normalizeBlockIds(Collection<String> values) {
        Objects.requireNonNull(values, "blockIds");
        List<String> normalized = values.stream()
                .map(value -> Objects.requireNonNull(value, "blockId").trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .map(value -> value.indexOf(':') < 0 ? "minecraft:" + value : value)
                .peek(value -> {
                    if (value.length() > 128 || !BLOCK_ID.matcher(value).matches()) {
                        throw new IllegalArgumentException("Invalid mining block ID: " + value);
                    }
                })
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one mining block ID is required.");
        }
        if (normalized.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("At most " + MAX_TARGETS + " mining targets are supported.");
        }
        return normalized;
    }

    public static long minutesToNanos(double minutes) {
        if (!Double.isFinite(minutes)
                || minutes < MIN_TIMEOUT_MINUTES
                || minutes > MAX_TIMEOUT_MINUTES) {
            throw new IllegalArgumentException(
                    "Mining timeout must be from " + MIN_TIMEOUT_MINUTES + " to "
                            + MAX_TIMEOUT_MINUTES + " minutes."
            );
        }
        return Math.round(minutes * 60.0 * 1_000_000_000.0);
    }

    public double timeoutMinutes() {
        return timeoutNanos / (double)Duration.ofMinutes(1).toNanos();
    }
}
