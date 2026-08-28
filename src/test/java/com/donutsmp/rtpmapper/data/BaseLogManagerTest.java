package com.donutsmp.rtpmapper.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseLogManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsAndDeduplicatesBases() {
        BaseLogManager manager = new BaseLogManager(tempDir);
        assertTrue(manager.allBases().isEmpty());

        boolean recorded = manager.recordDiscovery(100.0, 64.0, 200.0, "minecraft:overworld", List.of("minecraft:chest", "minecraft:spawner"));
        assertTrue(recorded);
        assertEquals(1, manager.allBases().size());
        assertFalse(manager.allBases().get(0).raided());

        // Duplicate within 32 blocks
        boolean duplicate = manager.recordDiscovery(110.0, 64.0, 205.0, "minecraft:overworld", List.of("minecraft:chest"));
        assertFalse(duplicate);
        assertEquals(1, manager.allBases().size());

        // Toggle raided
        boolean raided = manager.toggleRaided(102.0, 198.0, "minecraft:overworld");
        assertTrue(raided);
        assertTrue(manager.allBases().get(0).raided());
        assertTrue(manager.isNearbyRaided(105.0, 201.0, "minecraft:overworld"));

        // New base far away
        boolean newBase = manager.recordDiscovery(5000.0, -20.0, -8000.0, "minecraft:overworld", List.of("minecraft:beacon"));
        assertTrue(newBase);
        assertEquals(2, manager.allBases().size());

        // Reload manager from same directory
        BaseLogManager reloaded = new BaseLogManager(tempDir);
        assertEquals(2, reloaded.allBases().size());
        assertTrue(reloaded.allBases().get(0).raided());
        assertFalse(reloaded.allBases().get(1).raided());
        assertTrue(Files.exists(tempDir.resolve("bases.json")));
        assertTrue(Files.exists(tempDir.resolve("bases.csv")));
    }
}
