package com.donutsmp.rtpmapper.config;

import com.donutsmp.rtpmapper.automation.CoordinateStopGuard;
import com.donutsmp.rtpmapper.mining.MiningSettings;
import com.donutsmp.rtpmapper.region.RtpRegion;

import java.util.List;

/** Immutable, validated user configuration. */
public record RtpMapperConfig(
        double rtpIntervalSeconds,
        double teleportDetectionThresholdBlocks,
        double teleportTimeoutSeconds,
        double stabilizationSeconds,
        boolean showHud,
        boolean autoResume,
        boolean storeYCoordinate,
        boolean showGrid,
        boolean showDistanceRings,
        double pointSize,
        List<RtpRegion> selectedRegions,
        List<String> allowedServers,
        boolean stopNearCenter,
        double centerStopRadiusBlocks,
        boolean stopNearWorldBorder,
        double worldBorderMarginBlocks,
        boolean allowSingleplayerMining,
        List<String> miningAllowedServers,
        List<String> miningBlockIds,
        int miningQuantity,
        double miningTimeoutMinutes,
        boolean autoTrash,
        boolean autoLoot,
        boolean logBases,
        boolean soundAlerts,
        String discordWebhookUrl,
        boolean autoEat,
        boolean autoTotem,
        boolean itemSaver,
        int itemSaverThreshold,
        boolean antiStuckWatchdog,
        int antiStuckTimeoutSeconds,
        boolean showHunterHud,
        boolean skipRaidedBases,
        boolean espTracers,
        boolean autoEmergencyEscape
) {
    public static final double MIN_RTP_INTERVAL_SECONDS = 1.0;
    public static final double MAX_RTP_INTERVAL_SECONDS = 60.0;
    public static final double DEFAULT_RTP_INTERVAL_SECONDS = 5.0;

    public static final double MIN_TELEPORT_THRESHOLD_BLOCKS = 32.0;
    public static final double MAX_TELEPORT_THRESHOLD_BLOCKS = 60_000_000.0;
    public static final double DEFAULT_TELEPORT_THRESHOLD_BLOCKS = 512.0;

    public static final double MIN_TELEPORT_TIMEOUT_SECONDS = 5.0;
    public static final double MAX_TELEPORT_TIMEOUT_SECONDS = 300.0;
    public static final double DEFAULT_TELEPORT_TIMEOUT_SECONDS = 20.0;

    public static final double MIN_STABILIZATION_SECONDS = 0.25;
    public static final double MAX_STABILIZATION_SECONDS = 5.0;
    public static final double DEFAULT_STABILIZATION_SECONDS = 0.75;

    public static final double MIN_POINT_SIZE = 1.0;
    public static final double MAX_POINT_SIZE = 8.0;
    public static final double DEFAULT_POINT_SIZE = 2.5;

    public static final double WORLD_BORDER_LIMIT_BLOCKS = CoordinateStopGuard.WORLD_BORDER_LIMIT_BLOCKS;
    public static final double MIN_CENTER_STOP_RADIUS_BLOCKS = 0.0;
    public static final double MAX_CENTER_STOP_RADIUS_BLOCKS = CoordinateStopGuard.WORLD_CORNER_RADIUS_BLOCKS;
    public static final double DEFAULT_CENTER_STOP_RADIUS_BLOCKS = 50_000.0;
    public static final double MIN_WORLD_BORDER_MARGIN_BLOCKS = 0.0;
    public static final double MAX_WORLD_BORDER_MARGIN_BLOCKS = WORLD_BORDER_LIMIT_BLOCKS;
    public static final double DEFAULT_WORLD_BORDER_MARGIN_BLOCKS = 10_000.0;

    public static final List<String> DEFAULT_ALLOWED_SERVERS = List.of(
            "donutsmp.net",
            "*.donutsmp.net"
    );
    public static final List<String> DEFAULT_MINING_ALLOWED_SERVERS = DEFAULT_ALLOWED_SERVERS;
    public static final boolean DEFAULT_ALLOW_SINGLEPLAYER_MINING = true;
    public static final List<String> DEFAULT_MINING_BLOCK_IDS = MiningSettings.DEFAULT_BLOCK_IDS;
    public static final int MIN_MINING_QUANTITY = MiningSettings.MIN_QUANTITY;
    public static final int MAX_MINING_QUANTITY = MiningSettings.MAX_QUANTITY;
    public static final int DEFAULT_MINING_QUANTITY = MiningSettings.DEFAULT_QUANTITY;
    public static final double MIN_MINING_TIMEOUT_MINUTES = MiningSettings.MIN_TIMEOUT_MINUTES;
    public static final double MAX_MINING_TIMEOUT_MINUTES = MiningSettings.MAX_TIMEOUT_MINUTES;
    public static final double DEFAULT_MINING_TIMEOUT_MINUTES = MiningSettings.DEFAULT_TIMEOUT_MINUTES;
    public static final List<RtpRegion> DEFAULT_SELECTED_REGIONS = RtpRegion.selectableValues();

    public static final boolean DEFAULT_AUTO_TRASH = true;
    public static final boolean DEFAULT_AUTO_LOOT = true;
    public static final boolean DEFAULT_LOG_BASES = true;
    public static final boolean DEFAULT_SOUND_ALERTS = true;
    public static final String DEFAULT_DISCORD_WEBHOOK_URL = "";
    public static final boolean DEFAULT_AUTO_EAT = true;
    public static final boolean DEFAULT_AUTO_TOTEM = true;
    public static final boolean DEFAULT_ITEM_SAVER = true;
    public static final int DEFAULT_ITEM_SAVER_THRESHOLD = 20;

    public static final boolean DEFAULT_ANTI_STUCK_WATCHDOG = true;
    public static final int DEFAULT_ANTI_STUCK_TIMEOUT_SECONDS = 45;
    public static final boolean DEFAULT_SHOW_HUNTER_HUD = true;
    public static final boolean DEFAULT_SKIP_RAIDED_BASES = true;
    public static final boolean DEFAULT_ESP_TRACERS = true;
    public static final boolean DEFAULT_AUTO_EMERGENCY_ESCAPE = true;

    public RtpMapperConfig {
        requireRange("rtpIntervalSeconds", rtpIntervalSeconds,
                MIN_RTP_INTERVAL_SECONDS, MAX_RTP_INTERVAL_SECONDS);
        requireRange("teleportDetectionThresholdBlocks", teleportDetectionThresholdBlocks,
                MIN_TELEPORT_THRESHOLD_BLOCKS, MAX_TELEPORT_THRESHOLD_BLOCKS);
        requireRange("teleportTimeoutSeconds", teleportTimeoutSeconds,
                MIN_TELEPORT_TIMEOUT_SECONDS, MAX_TELEPORT_TIMEOUT_SECONDS);
        requireRange("stabilizationSeconds", stabilizationSeconds,
                MIN_STABILIZATION_SECONDS, MAX_STABILIZATION_SECONDS);
        requireRange("pointSize", pointSize, MIN_POINT_SIZE, MAX_POINT_SIZE);
        requireRange("centerStopRadiusBlocks", centerStopRadiusBlocks,
                MIN_CENTER_STOP_RADIUS_BLOCKS, MAX_CENTER_STOP_RADIUS_BLOCKS);
        requireRange("worldBorderMarginBlocks", worldBorderMarginBlocks,
                MIN_WORLD_BORDER_MARGIN_BLOCKS, MAX_WORLD_BORDER_MARGIN_BLOCKS);
        if (miningQuantity < MIN_MINING_QUANTITY || miningQuantity > MAX_MINING_QUANTITY) {
            throw new IllegalArgumentException(
                    "miningQuantity must be between " + MIN_MINING_QUANTITY
                            + " and " + MAX_MINING_QUANTITY
            );
        }
        requireRange("miningTimeoutMinutes", miningTimeoutMinutes,
                MIN_MINING_TIMEOUT_MINUTES, MAX_MINING_TIMEOUT_MINUTES);
        selectedRegions = RtpRegion.normalizeSelection(selectedRegions);
        allowedServers = ServerMatcher.normalizePatterns(allowedServers);
        miningAllowedServers = ServerMatcher.normalizeOptionalPatterns(miningAllowedServers);
        miningBlockIds = MiningSettings.normalizeBlockIds(miningBlockIds);
        discordWebhookUrl = discordWebhookUrl == null ? "" : discordWebhookUrl.trim();
        itemSaverThreshold = Math.clamp(itemSaverThreshold, 1, 500);
        antiStuckTimeoutSeconds = Math.clamp(antiStuckTimeoutSeconds, 10, 300);
    }

    public static RtpMapperConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public boolean isServerAllowed(String address) {
        return ServerMatcher.matches(address, allowedServers);
    }

    // JavaBean-style aliases keep GUI/controller call sites readable.
    public double getRtpIntervalSeconds() { return rtpIntervalSeconds; }
    public double getTeleportDetectionThresholdBlocks() { return teleportDetectionThresholdBlocks; }
    public double getTeleportTimeoutSeconds() { return teleportTimeoutSeconds; }
    public double getStabilizationSeconds() { return stabilizationSeconds; }
    public boolean isShowHud() { return showHud; }
    public boolean isAutoResume() { return autoResume; }
    public boolean isStoreYCoordinate() { return storeYCoordinate; }
    public boolean isShowGrid() { return showGrid; }
    public boolean isShowDistanceRings() { return showDistanceRings; }
    public double getPointSize() { return pointSize; }
    public List<RtpRegion> getSelectedRegions() { return selectedRegions; }
    public List<String> getAllowedServers() { return allowedServers; }
    public boolean isStopNearCenter() { return stopNearCenter; }
    public double getCenterStopRadiusBlocks() { return centerStopRadiusBlocks; }
    public boolean isStopNearWorldBorder() { return stopNearWorldBorder; }
    public double getWorldBorderMarginBlocks() { return worldBorderMarginBlocks; }
    public boolean isAllowSingleplayerMining() { return allowSingleplayerMining; }
    public List<String> getMiningAllowedServers() { return miningAllowedServers; }
    public List<String> getMiningBlockIds() { return miningBlockIds; }
    public int getMiningQuantity() { return miningQuantity; }
    public double getMiningTimeoutMinutes() { return miningTimeoutMinutes; }
    public boolean isAutoTrash() { return autoTrash; }
    public boolean isAutoLoot() { return autoLoot; }
    public boolean isLogBases() { return logBases; }
    public boolean isSoundAlerts() { return soundAlerts; }
    public String getDiscordWebhookUrl() { return discordWebhookUrl; }
    public boolean isAutoEat() { return autoEat; }
    public boolean isAutoTotem() { return autoTotem; }
    public boolean isItemSaver() { return itemSaver; }
    public int getItemSaverThreshold() { return itemSaverThreshold; }
    public boolean isAntiStuckWatchdog() { return antiStuckWatchdog; }
    public int getAntiStuckTimeoutSeconds() { return antiStuckTimeoutSeconds; }
    public boolean isShowHunterHud() { return showHunterHud; }
    public boolean isSkipRaidedBases() { return skipRaidedBases; }
    public boolean isEspTracers() { return espTracers; }
    public boolean isAutoEmergencyEscape() { return autoEmergencyEscape; }

    private static void requireRange(String name, double value, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }

    public static final class Builder {
        private double rtpIntervalSeconds = DEFAULT_RTP_INTERVAL_SECONDS;
        private double teleportDetectionThresholdBlocks = DEFAULT_TELEPORT_THRESHOLD_BLOCKS;
        private double teleportTimeoutSeconds = DEFAULT_TELEPORT_TIMEOUT_SECONDS;
        private double stabilizationSeconds = DEFAULT_STABILIZATION_SECONDS;
        private boolean showHud = true;
        private boolean autoResume;
        private boolean storeYCoordinate = true;
        private boolean showGrid = true;
        private boolean showDistanceRings = true;
        private double pointSize = DEFAULT_POINT_SIZE;
        private List<RtpRegion> selectedRegions = DEFAULT_SELECTED_REGIONS;
        private List<String> allowedServers = DEFAULT_ALLOWED_SERVERS;
        private boolean stopNearCenter;
        private double centerStopRadiusBlocks = DEFAULT_CENTER_STOP_RADIUS_BLOCKS;
        private boolean stopNearWorldBorder;
        private double worldBorderMarginBlocks = DEFAULT_WORLD_BORDER_MARGIN_BLOCKS;
        private boolean allowSingleplayerMining = DEFAULT_ALLOW_SINGLEPLAYER_MINING;
        private List<String> miningAllowedServers = DEFAULT_MINING_ALLOWED_SERVERS;
        private List<String> miningBlockIds = DEFAULT_MINING_BLOCK_IDS;
        private int miningQuantity = DEFAULT_MINING_QUANTITY;
        private double miningTimeoutMinutes = DEFAULT_MINING_TIMEOUT_MINUTES;
        private boolean autoTrash = DEFAULT_AUTO_TRASH;
        private boolean autoLoot = DEFAULT_AUTO_LOOT;
        private boolean logBases = DEFAULT_LOG_BASES;
        private boolean soundAlerts = DEFAULT_SOUND_ALERTS;
        private String discordWebhookUrl = DEFAULT_DISCORD_WEBHOOK_URL;
        private boolean autoEat = DEFAULT_AUTO_EAT;
        private boolean autoTotem = DEFAULT_AUTO_TOTEM;
        private boolean itemSaver = DEFAULT_ITEM_SAVER;
        private int itemSaverThreshold = DEFAULT_ITEM_SAVER_THRESHOLD;
        private boolean antiStuckWatchdog = DEFAULT_ANTI_STUCK_WATCHDOG;
        private int antiStuckTimeoutSeconds = DEFAULT_ANTI_STUCK_TIMEOUT_SECONDS;
        private boolean showHunterHud = DEFAULT_SHOW_HUNTER_HUD;
        private boolean skipRaidedBases = DEFAULT_SKIP_RAIDED_BASES;
        private boolean espTracers = DEFAULT_ESP_TRACERS;
        private boolean autoEmergencyEscape = DEFAULT_AUTO_EMERGENCY_ESCAPE;

        public Builder() {
        }

        private Builder(RtpMapperConfig config) {
            this.rtpIntervalSeconds = config.rtpIntervalSeconds;
            this.teleportDetectionThresholdBlocks = config.teleportDetectionThresholdBlocks;
            this.teleportTimeoutSeconds = config.teleportTimeoutSeconds;
            this.stabilizationSeconds = config.stabilizationSeconds;
            this.showHud = config.showHud;
            this.autoResume = config.autoResume;
            this.storeYCoordinate = config.storeYCoordinate;
            this.showGrid = config.showGrid;
            this.showDistanceRings = config.showDistanceRings;
            this.pointSize = config.pointSize;
            this.selectedRegions = config.selectedRegions;
            this.allowedServers = config.allowedServers;
            this.stopNearCenter = config.stopNearCenter;
            this.centerStopRadiusBlocks = config.centerStopRadiusBlocks;
            this.stopNearWorldBorder = config.stopNearWorldBorder;
            this.worldBorderMarginBlocks = config.worldBorderMarginBlocks;
            this.allowSingleplayerMining = config.allowSingleplayerMining;
            this.miningAllowedServers = config.miningAllowedServers;
            this.miningBlockIds = config.miningBlockIds;
            this.miningQuantity = config.miningQuantity;
            this.miningTimeoutMinutes = config.miningTimeoutMinutes;
            this.autoTrash = config.autoTrash;
            this.autoLoot = config.autoLoot;
            this.logBases = config.logBases;
            this.soundAlerts = config.soundAlerts;
            this.discordWebhookUrl = config.discordWebhookUrl;
            this.autoEat = config.autoEat;
            this.autoTotem = config.autoTotem;
            this.itemSaver = config.itemSaver;
            this.itemSaverThreshold = config.itemSaverThreshold;
            this.antiStuckWatchdog = config.antiStuckWatchdog;
            this.antiStuckTimeoutSeconds = config.antiStuckTimeoutSeconds;
            this.showHunterHud = config.showHunterHud;
            this.skipRaidedBases = config.skipRaidedBases;
            this.espTracers = config.espTracers;
            this.autoEmergencyEscape = config.autoEmergencyEscape;
        }

        public Builder rtpIntervalSeconds(double value) { this.rtpIntervalSeconds = value; return this; }
        public Builder teleportDetectionThresholdBlocks(double value) { this.teleportDetectionThresholdBlocks = value; return this; }
        public Builder teleportTimeoutSeconds(double value) { this.teleportTimeoutSeconds = value; return this; }
        public Builder stabilizationSeconds(double value) { this.stabilizationSeconds = value; return this; }
        public Builder showHud(boolean value) { this.showHud = value; return this; }
        public Builder autoResume(boolean value) { this.autoResume = value; return this; }
        public Builder storeYCoordinate(boolean value) { this.storeYCoordinate = value; return this; }
        public Builder showGrid(boolean value) { this.showGrid = value; return this; }
        public Builder showDistanceRings(boolean value) { this.showDistanceRings = value; return this; }
        public Builder pointSize(double value) { this.pointSize = value; return this; }
        public Builder selectedRegions(List<RtpRegion> value) { this.selectedRegions = List.copyOf(value); return this; }
        public Builder allowedServers(List<String> value) { this.allowedServers = List.copyOf(value); return this; }
        public Builder stopNearCenter(boolean value) { this.stopNearCenter = value; return this; }
        public Builder centerStopRadiusBlocks(double value) { this.centerStopRadiusBlocks = value; return this; }
        public Builder stopNearWorldBorder(boolean value) { this.stopNearWorldBorder = value; return this; }
        public Builder worldBorderMarginBlocks(double value) { this.worldBorderMarginBlocks = value; return this; }
        public Builder allowSingleplayerMining(boolean value) { this.allowSingleplayerMining = value; return this; }
        public Builder miningAllowedServers(List<String> value) { this.miningAllowedServers = List.copyOf(value); return this; }
        public Builder miningBlockIds(List<String> value) { this.miningBlockIds = List.copyOf(value); return this; }
        public Builder miningQuantity(int value) { this.miningQuantity = value; return this; }
        public Builder miningTimeoutMinutes(double value) { this.miningTimeoutMinutes = value; return this; }
        public Builder autoTrash(boolean value) { this.autoTrash = value; return this; }
        public Builder autoLoot(boolean value) { this.autoLoot = value; return this; }
        public Builder logBases(boolean value) { this.logBases = value; return this; }
        public Builder soundAlerts(boolean value) { this.soundAlerts = value; return this; }
        public Builder discordWebhookUrl(String value) { this.discordWebhookUrl = value; return this; }
        public Builder autoEat(boolean value) { this.autoEat = value; return this; }
        public Builder autoTotem(boolean value) { this.autoTotem = value; return this; }
        public Builder itemSaver(boolean value) { this.itemSaver = value; return this; }
        public Builder itemSaverThreshold(int value) { this.itemSaverThreshold = value; return this; }
        public Builder antiStuckWatchdog(boolean value) { this.antiStuckWatchdog = value; return this; }
        public Builder antiStuckTimeoutSeconds(int value) { this.antiStuckTimeoutSeconds = value; return this; }
        public Builder showHunterHud(boolean value) { this.showHunterHud = value; return this; }
        public Builder skipRaidedBases(boolean value) { this.skipRaidedBases = value; return this; }
        public Builder espTracers(boolean value) { this.espTracers = value; return this; }
        public Builder autoEmergencyEscape(boolean value) { this.autoEmergencyEscape = value; return this; }

        public RtpMapperConfig build() {
            return new RtpMapperConfig(
                    rtpIntervalSeconds,
                    teleportDetectionThresholdBlocks,
                    teleportTimeoutSeconds,
                    stabilizationSeconds,
                    showHud,
                    autoResume,
                    storeYCoordinate,
                    showGrid,
                    showDistanceRings,
                    pointSize,
                    selectedRegions,
                    allowedServers,
                    stopNearCenter,
                    centerStopRadiusBlocks,
                    stopNearWorldBorder,
                    worldBorderMarginBlocks,
                    allowSingleplayerMining,
                    miningAllowedServers,
                    miningBlockIds,
                    miningQuantity,
                    miningTimeoutMinutes,
                    autoTrash,
                    autoLoot,
                    logBases,
                    soundAlerts,
                    discordWebhookUrl,
                    autoEat,
                    autoTotem,
                    itemSaver,
                    itemSaverThreshold,
                    antiStuckWatchdog,
                    antiStuckTimeoutSeconds,
                    showHunterHud,
                    skipRaidedBases,
                    espTracers,
                    autoEmergencyEscape
            );
        }
    }
}
