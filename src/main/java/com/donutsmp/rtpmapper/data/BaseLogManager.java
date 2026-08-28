package com.donutsmp.rtpmapper.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Manages persistent logging, deduplication, and raided status of discovered player bases. */
public final class BaseLogManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("BaseLogManager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DIRECTORY_NAME = "rtpmapper";
    private static final String JSON_FILE = "bases.json";
    private static final String CSV_FILE = "bases.csv";
    private static final double DEDUPLICATION_RADIUS_BLOCKS = 32.0;

    private final Path directory;
    private final Path jsonPath;
    private final Path csvPath;
    private final List<BaseDiscovery> discoveries = new ArrayList<>();

    public BaseLogManager() {
        this(FabricLoader.getInstance().getConfigDir().resolve(DIRECTORY_NAME));
    }

    public BaseLogManager(Path directory) {
        this.directory = directory.toAbsolutePath();
        this.jsonPath = this.directory.resolve(JSON_FILE);
        this.csvPath = this.directory.resolve(CSV_FILE);
        load();
    }

    public synchronized List<BaseDiscovery> allBases() {
        return Collections.unmodifiableList(new ArrayList<>(discoveries));
    }

    public synchronized boolean recordDiscovery(double x, double y, double z, String dimension, List<String> blocks) {
        return recordDiscovery(x, y, z, dimension, blocks, true);
    }

    public synchronized boolean recordDiscovery(double x, double y, double z, String dimension, List<String> blocks, boolean persistToFile) {
        // Check for nearby duplicate in the same dimension
        for (BaseDiscovery existing : discoveries) {
            if (existing.dimension().equalsIgnoreCase(dimension)) {
                double dx = existing.x() - x;
                double dz = existing.z() - z;
                if (Math.hypot(dx, dz) <= DEDUPLICATION_RADIUS_BLOCKS) {
                    return false; // Already logged this base
                }
            }
        }

        BaseDiscovery discovery = new BaseDiscovery(
                System.currentTimeMillis(),
                x,
                y,
                z,
                dimension,
                blocks,
                false
        );
        discoveries.add(discovery);
        if (persistToFile) {
            save();
        }
        return true;
    }

    public synchronized boolean toggleRaided(double x, double z, String dimension) {
        for (int i = 0; i < discoveries.size(); i++) {
            BaseDiscovery base = discoveries.get(i);
            if (base.dimension().equalsIgnoreCase(dimension)) {
                double dx = base.x() - x;
                double dz = base.z() - z;
                if (Math.hypot(dx, dz) <= DEDUPLICATION_RADIUS_BLOCKS) {
                    boolean updatedRaided = !base.raided();
                    discoveries.set(i, base.withRaided(updatedRaided));
                    save();
                    return updatedRaided;
                }
            }
        }
        return false;
    }

    public synchronized boolean isNearbyRaided(double x, double z, String dimension) {
        for (BaseDiscovery base : discoveries) {
            if (base.raided() && base.dimension().equalsIgnoreCase(dimension)) {
                double dx = base.x() - x;
                double dz = base.z() - z;
                if (Math.hypot(dx, dz) <= DEDUPLICATION_RADIUS_BLOCKS) {
                    return true;
                }
            }
        }
        return false;
    }

    private synchronized void load() {
        discoveries.clear();
        if (!Files.exists(jsonPath)) {
            return;
        }
        try {
            String content = Files.readString(jsonPath, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonArray()) {
                return;
            }
            JsonArray array = parsed.getAsJsonArray();
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    JsonObject obj = element.getAsJsonObject();
                    long time = obj.has("timestamp") ? obj.get("timestamp").getAsLong() : System.currentTimeMillis();
                    double x = obj.has("x") ? obj.get("x").getAsDouble() : 0;
                    double y = obj.has("y") ? obj.get("y").getAsDouble() : 0;
                    double z = obj.has("z") ? obj.get("z").getAsDouble() : 0;
                    String dim = obj.has("dimension") ? obj.get("dimension").getAsString() : "minecraft:overworld";
                    boolean raided = obj.has("raided") && obj.get("raided").getAsBoolean();
                    List<String> blocks = new ArrayList<>();
                    if (obj.has("blocks") && obj.get("blocks").isJsonArray()) {
                        for (JsonElement b : obj.getAsJsonArray("blocks")) {
                            blocks.add(b.getAsString());
                        }
                    }
                    discoveries.add(new BaseDiscovery(time, x, y, z, dim, blocks, raided));
                }
            }
            LOGGER.info("[RTP Mapper] Loaded {} discovered bases", discoveries.size());
        } catch (Exception e) {
            LOGGER.warn("[RTP Mapper] Failed to load bases.json: {}", e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(directory);
            JsonArray array = new JsonArray();
            StringBuilder csv = new StringBuilder("Timestamp,ISO8601,X,Y,Z,Dimension,Raided,DetectedBlocks\n");

            for (BaseDiscovery base : discoveries) {
                JsonObject obj = new JsonObject();
                obj.addProperty("timestamp", base.timestampMillis());
                obj.addProperty("x", base.x());
                obj.addProperty("y", base.y());
                obj.addProperty("z", base.z());
                obj.addProperty("dimension", base.dimension());
                obj.addProperty("raided", base.raided());
                JsonArray blocksArray = new JsonArray();
                base.detectedBlocks().forEach(blocksArray::add);
                obj.add("blocks", blocksArray);
                array.add(obj);

                String iso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(base.timestampMillis()));
                String blocksStr = "\"" + String.join(";", base.detectedBlocks()) + "\"";
                csv.append(base.timestampMillis()).append(",")
                        .append(iso).append(",")
                        .append(String.format(java.util.Locale.ROOT, "%.1f", base.x())).append(",")
                        .append(String.format(java.util.Locale.ROOT, "%.1f", base.y())).append(",")
                        .append(String.format(java.util.Locale.ROOT, "%.1f", base.z())).append(",")
                        .append(base.dimension()).append(",")
                        .append(base.raided()).append(",")
                        .append(blocksStr).append("\n");
            }

            Files.writeString(jsonPath, GSON.toJson(array), StandardCharsets.UTF_8);
            Files.writeString(csvPath, csv.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("[RTP Mapper] Failed to save base logs: {}", e.getMessage());
        }
    }
}
