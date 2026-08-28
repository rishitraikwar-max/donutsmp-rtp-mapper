package com.donutsmp.rtpmapper.hud;

import com.donutsmp.rtpmapper.data.BaseDiscovery;
import com.donutsmp.rtpmapper.gui.HunterSettingsView;
import com.donutsmp.rtpmapper.gui.MapperStatusView;
import com.donutsmp.rtpmapper.gui.MiningStatusView;
import com.donutsmp.rtpmapper.gui.RtpMapperUiModel;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Tactical in-game HUD overlay displaying live hunting, durability, totem, and mapping stats. */
public final class RtpMapperHud {
    private static final Identifier ELEMENT_ID = Identifier.fromNamespaceAndPath("rtpmapper", "status");
    private static final int BACKGROUND = 0xD610141B;
    private static final int BORDER = 0xE04A5666;
    private static final int TITLE_HUNT = 0xFF6EE7A8; // Green
    private static final int TITLE_MAP = 0xFF57D3FF;  // Cyan
    private static final int TEXT = 0xFFE5EAF0;
    private static final int MUTED = 0xFFA4AFBD;
    private static final int WARN = 0xFFFF7474;
    private static final int PADDING = 6;
    private static final int LINE_SPACING = 10;

    private RtpMapperHud() {
    }

    public static void register(RtpMapperUiModel model) {
        Objects.requireNonNull(model, "model");
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                ELEMENT_ID,
                (graphics, deltaTracker) -> render(graphics, model)
        );
    }

    private static void render(GuiGraphics graphics, RtpMapperUiModel model) {
        MapperStatusView mapperStatus = model.status();
        MiningStatusView miningStatus = model.miningStatus();
        HunterSettingsView hunterSettings = model.hunterSettings();

        boolean mapperActive = mapperStatus.running();
        boolean miningActive = miningStatus.running();

        if (!mapperActive && !miningActive) {
            return;
        }

        if (!model.settings().showHud() && !hunterSettings.showHunterHud()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        Font font = minecraft.font;
        List<String> lines = new ArrayList<>();
        int titleColor = miningActive ? TITLE_HUNT : TITLE_MAP;

        if (miningActive) {
            lines.add("⚔️ BASE HUNTER ACTIVE");
        } else {
            lines.add("📍 RTP DATA MAPPER");
        }

        // Position & Distance
        double distFromSpawn = Math.hypot(player.getX(), player.getZ());
        lines.add(String.format(Locale.ROOT, "Pos: X: %,.0f, Y: %,.0f, Z: %,.0f (%,.0fk)",
                player.getX(), player.getY(), player.getZ(), distFromSpawn / 1000.0));

        // Tool & Durability
        ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty() && mainHand.isDamageableItem()) {
            int maxDamage = mainHand.getMaxDamage();
            int currentDamage = mainHand.getDamageValue();
            int percent = Math.max(0, (maxDamage - currentDamage) * 100 / maxDamage);
            String toolName = mainHand.getItem().getName(mainHand).getString();
            lines.add(String.format(Locale.ROOT, "Tool: %s (%d%%)", toolName, percent));
        }

        // Totems
        int bagTotems = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                bagTotems++;
            }
        }
        boolean offhandTotem = player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        lines.add(String.format(Locale.ROOT, "Totems: %s (%d in bag)",
                offhandTotem ? "1 offhand" : "none in offhand", bagTotems));

        // Discovered bases count
        List<BaseDiscovery> bases = model.discoveredBases();
        if (!bases.isEmpty()) {
            long raidedCount = bases.stream().filter(BaseDiscovery::raided).count();
            lines.add(String.format(Locale.ROOT, "Bases Logged: %d (%d raided)", bases.size(), raidedCount));
        }

        // ESP / Nearest Base Target Radar
        if (hunterSettings.espTracers()) {
            List<net.minecraft.core.BlockPos> targets = model.nearbyTargetBlockPositions();
            if (targets != null && !targets.isEmpty()) {
                net.minecraft.core.BlockPos nearest = null;
                double nearestDistSq = Double.MAX_VALUE;
                for (net.minecraft.core.BlockPos pos : targets) {
                    double distSq = pos.distToCenterSqr(player.getX(), player.getY(), player.getZ());
                    if (distSq < nearestDistSq) {
                        nearestDistSq = distSq;
                        nearest = pos;
                    }
                }
                if (nearest != null) {
                    double dist = Math.sqrt(nearestDistSq);
                    int dy = nearest.getY() - player.getBlockY();
                    String vertStr = dy == 0 ? "level" : (dy < 0 ? Math.abs(dy) + "m below" : dy + "m above");
                    String blockName = player.level().getBlockState(nearest).getBlock().getName().getString();
                    lines.add(String.format(Locale.ROOT, "🎯 Target: %s (%.1fm, %s)", blockName, dist, vertStr));
                }
            }
        }

        // Next Action / Status
        if (miningActive) {
            lines.add("Baritone: " + (miningStatus.state().isEmpty() ? "Pathing / Hunting" : miningStatus.state()));
        } else {
            lines.add(nextActionLine(mapperStatus));
        }

        int contentWidth = 0;
        for (String line : lines) {
            contentWidth = Math.max(contentWidth, font.width(line));
        }
        int x = 8;
        int y = 8;
        int width = contentWidth + PADDING * 2;
        int height = PADDING * 2 + lines.size() * LINE_SPACING;

        graphics.fill(x, y, x + width, y + height, BACKGROUND);
        graphics.renderOutline(x, y, width, height, BORDER);

        for (int index = 0; index < lines.size(); index++) {
            int color;
            if (index == 0) {
                color = titleColor;
            } else if (index == lines.size() - 1) {
                color = MUTED;
            } else {
                color = TEXT;
            }
            graphics.drawString(
                    font,
                    lines.get(index),
                    x + PADDING,
                    y + PADDING + index * LINE_SPACING,
                    color,
                    false
            );
        }
    }

    private static String nextActionLine(MapperStatusView status) {
        String friendlyState = status.state().replace('_', ' ');
        if ((status.state().equals("COOLDOWN") || status.state().equals("WAITING_TO_SEND"))
                && Double.isFinite(status.secondsUntilNextAction())) {
            return String.format(
                    Locale.ROOT,
                    "Next RTP: %.1fs  ·  %s",
                    Math.max(0.0, status.secondsUntilNextAction()),
                    friendlyState
            );
        }
        return "State: " + friendlyState;
    }
}
