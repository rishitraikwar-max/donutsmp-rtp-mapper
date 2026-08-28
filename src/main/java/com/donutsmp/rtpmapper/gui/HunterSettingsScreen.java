package com.donutsmp.rtpmapper.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class HunterSettingsScreen extends Screen {
    private static final int PANEL = 0xF0161A22;
    private static final int BORDER = 0xFF303846;
    private static final int TEXT = 0xFFE5EAF0;
    private static final int MUTED = 0xFF929DAD;
    private static final int ACCENT = 0xFF57D3FF;
    private static final int SUCCESS = 0xFF6EE7A8;
    private static final int ERROR = 0xFFFF6B6B;

    private final Screen parent;
    private final RtpMapperUiModel model;

    private boolean autoTrash;
    private boolean autoLoot;
    private boolean logBases;
    private boolean soundAlerts;
    private boolean autoEat;
    private boolean autoTotem;
    private boolean itemSaver;
    private boolean antiStuckWatchdog;
    private boolean showHunterHud;
    private boolean skipRaidedBases;
    private boolean espTracers;
    private boolean autoEmergencyEscape;
    private EditBox webhookUrlBox;
    private String draftWebhookUrl;
    private String actionMessage = "";
    private boolean actionSucceeded = true;
    private long actionMessageExpiresAt;

    public HunterSettingsScreen(Screen parent, RtpMapperUiModel model) {
        super(Component.literal("Base Hunter & Survival Automation"));
        this.parent = parent;
        this.model = model;
    }

    @Override
    protected void init() {
        HunterSettingsView settings = model.hunterSettings();
        if (draftWebhookUrl == null) {
            autoTrash = settings.autoTrash();
            autoLoot = settings.autoLoot();
            logBases = settings.logBases();
            soundAlerts = settings.soundAlerts();
            autoEat = settings.autoEat();
            autoTotem = settings.autoTotem();
            itemSaver = settings.itemSaver();
            antiStuckWatchdog = settings.antiStuckWatchdog();
            showHunterHud = settings.showHunterHud();
            skipRaidedBases = settings.skipRaidedBases();
            espTracers = settings.espTracers();
            autoEmergencyEscape = settings.autoEmergencyEscape();
            draftWebhookUrl = settings.discordWebhookUrl();
        }

        int panelWidth = Math.min(560, Math.max(1, width - 24));
        int panelHeight = Math.min(410, Math.max(1, height - 24));
        int panelX = (width - panelWidth) / 2;
        int panelY = (height - panelHeight) / 2;

        int rowY = panelY + 32;
        int colWidth = (panelWidth - 36) / 2;
        int col1X = panelX + 12;
        int col2X = panelX + 18 + colWidth;
        int btnHeight = 20;
        int spacing = 24;

        // Column 1
        addRenderableWidget(CycleButton.onOffBuilder(autoTrash).create(
                col1X, rowY, colWidth, btnHeight,
                Component.literal("Auto-Trash Junk"),
                (btn, value) -> autoTrash = value
        ));
        addRenderableWidget(CycleButton.onOffBuilder(autoLoot).create(
                col1X, rowY + spacing, colWidth, btnHeight,
                Component.literal("Auto-Loot Chests"),
                (btn, value) -> autoLoot = value
        ));
        addRenderableWidget(CycleButton.onOffBuilder(autoEat).create(
                col1X, rowY + spacing * 2, colWidth, btnHeight,
                Component.literal("Auto-Eat Food"),
                (btn, value) -> autoEat = value
        ));
        addRenderableWidget(CycleButton.onOffBuilder(autoTotem).create(
                col1X, rowY + spacing * 3, colWidth, btnHeight,
                Component.literal("Auto-Equip Totem"),
                (btn, value) -> autoTotem = value
        ));
        addRenderableWidget(CycleButton.onOffBuilder(antiStuckWatchdog).create(
                col1X, rowY + spacing * 4, colWidth, btnHeight,
                Component.literal("Anti-Stuck Watchdog"),
                (btn, value) -> antiStuckWatchdog = value
        ));
        addRenderableWidget(CycleButton.onOffBuilder(espTracers).create(
                col1X, rowY + spacing * 5, colWidth, btnHeight,
                Component.literal("3D ESP & Tracers"),
                (btn, value) -> espTracers = value
        ));

        // Column 2
        addRenderableWidget(CycleButton.onOffBuilder(logBases).create(
                col2X, rowY, colWidth, btnHeight,
                Component.literal("Log Bases to File"),
                (btn, value) -> logBases = value
        ));
        addRenderableWidget(CycleButton.onOffBuilder(soundAlerts).create(
                col2X, rowY + spacing, colWidth, btnHeight,
                Component.literal("In-Game Sound Alerts"),
                (btn, value) -> soundAlerts = value
        ));
        addRenderableWidget(CycleButton.onOffBuilder(itemSaver).create(
                col2X, rowY + spacing * 2, colWidth, btnHeight,
                Component.literal("Tool Durability Saver"),
                (btn, value) -> itemSaver = value
        ));
        addRenderableWidget(CycleButton.onOffBuilder(showHunterHud).create(
                col2X, rowY + spacing * 3, colWidth, btnHeight,
                Component.literal("Tactical Hunter HUD"),
                (btn, value) -> showHunterHud = value
        ));
        addRenderableWidget(CycleButton.onOffBuilder(skipRaidedBases).create(
                col2X, rowY + spacing * 4, colWidth, btnHeight,
                Component.literal("Skip Raided Bases"),
                (btn, value) -> skipRaidedBases = value
        ));
        addRenderableWidget(CycleButton.onOffBuilder(autoEmergencyEscape).create(
                col2X, rowY + spacing * 5, colWidth, btnHeight,
                Component.literal("Emergency Escape"),
                (btn, value) -> autoEmergencyEscape = value
        ));

        // Discord Webhook
        int webhookY = rowY + spacing * 6 + 4;
        webhookUrlBox = new EditBox(font, panelX + 12, webhookY + 12, panelWidth - 24, btnHeight, Component.literal("Discord Webhook URL"));
        webhookUrlBox.setMaxLength(512);
        webhookUrlBox.setValue(draftWebhookUrl);
        addRenderableWidget(webhookUrlBox);

        // Bottom Action buttons
        int bottomY = panelY + panelHeight - 26;
        addRenderableWidget(Button.builder(Component.literal("Save Settings"), btn -> saveSettings())
                .bounds(panelX + 12, bottomY, 110, btnHeight).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), btn -> minecraft.setScreen(parent))
                .bounds(panelX + 128, bottomY, 80, btnHeight).build());
    }

    private void saveSettings() {
        draftWebhookUrl = webhookUrlBox.getValue().trim();
        HunterSettingsView view = new HunterSettingsView(
                autoTrash,
                autoLoot,
                logBases,
                soundAlerts,
                draftWebhookUrl,
                autoEat,
                autoTotem,
                itemSaver,
                20,
                antiStuckWatchdog,
                45,
                showHunterHud,
                skipRaidedBases,
                espTracers,
                autoEmergencyEscape
        );
        UiActionResult result = model.applyHunterSettings(view);
        actionSucceeded = result.success();
        actionMessage = result.message();
        actionMessageExpiresAt = System.currentTimeMillis() + 4000L;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0x88000000);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);

        int panelWidth = Math.min(560, Math.max(1, width - 24));
        int panelHeight = Math.min(410, Math.max(1, height - 24));
        int panelX = (width - panelWidth) / 2;
        int panelY = (height - panelHeight) / 2;

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL);
        graphics.renderOutline(panelX, panelY, panelWidth, panelHeight, BORDER);

        graphics.drawString(font, "BASE HUNTER & SURVIVAL AUTOMATION", panelX + 12, panelY + 12, ACCENT, false);
        int webhookY = panelY + 32 + 24 * 6 + 4;
        graphics.drawString(font, "Discord Webhook URL (Optional for base/50k alerts):", panelX + 12, webhookY, MUTED, false);

        if (!actionMessage.isEmpty() && System.currentTimeMillis() < actionMessageExpiresAt) {
            graphics.drawString(font, actionMessage, panelX + 220, panelY + panelHeight - 20, actionSucceeded ? SUCCESS : ERROR, false);
        }

        super.render(graphics, mouseX, mouseY, delta);
    }
}
