package com.donutsmp.rtpmapper.gui;

public interface RtpMapperUiModel {
    MapperStatusView status();

    MapperSettingsView settings();

    MiningSettingsView miningSettings();

    MiningStatusView miningStatus();

    MapperStatisticsView statistics(DataScope scope);

    ChartPointProvider points(DataScope scope);

    UiActionResult startMapping();

    UiActionResult startHunt();

    UiActionResult stopMapping();

    UiActionResult clearAllData();

    UiActionResult exportCsv();

    UiActionResult applySettings(MapperSettingsView settings);

    UiActionResult applyMiningSettings(MiningSettingsView settings);

    HunterSettingsView hunterSettings();

    java.util.List<com.donutsmp.rtpmapper.data.BaseDiscovery> discoveredBases();

    java.util.List<net.minecraft.core.BlockPos> nearbyTargetBlockPositions();

    UiActionResult toggleBaseRaided(com.donutsmp.rtpmapper.data.BaseDiscovery base);

    UiActionResult applyHunterSettings(HunterSettingsView settings);

    UiActionResult startMining();

    UiActionResult stopMining();

    UiActionResult emergencyStop();
}
