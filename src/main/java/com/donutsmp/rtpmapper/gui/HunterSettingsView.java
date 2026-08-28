package com.donutsmp.rtpmapper.gui;

import com.donutsmp.rtpmapper.config.RtpMapperConfig;

public record HunterSettingsView(
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
    public static HunterSettingsView fromConfig(RtpMapperConfig config) {
        return new HunterSettingsView(
                config.autoTrash(),
                config.autoLoot(),
                config.logBases(),
                config.soundAlerts(),
                config.discordWebhookUrl(),
                config.autoEat(),
                config.autoTotem(),
                config.itemSaver(),
                config.itemSaverThreshold(),
                config.antiStuckWatchdog(),
                config.antiStuckTimeoutSeconds(),
                config.showHunterHud(),
                config.skipRaidedBases(),
                config.espTracers(),
                config.autoEmergencyEscape()
        );
    }
}
