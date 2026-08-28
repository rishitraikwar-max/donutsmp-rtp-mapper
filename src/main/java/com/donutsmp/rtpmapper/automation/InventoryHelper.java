package com.donutsmp.rtpmapper.automation;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

/** Handles auto-trash, auto-eat, auto-totem, container auto-loot, and emergency escape. */
public final class InventoryHelper {
    private static final Set<String> JUNK_ITEM_IDS = Set.of(
            "minecraft:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:deepslate",
            "minecraft:tuff",
            "minecraft:dirt",
            "minecraft:gravel",
            "minecraft:diorite",
            "minecraft:andesite",
            "minecraft:granite",
            "minecraft:netherrack",
            "minecraft:basalt",
            "minecraft:blackstone",
            "minecraft:flint",
            "minecraft:sandstone",
            "minecraft:red_sandstone",
            "minecraft:calcite",
            "minecraft:smooth_basalt"
    );

    private static final Set<Item> VALUABLE_LOOT_ITEMS = Set.of(
            Items.TOTEM_OF_UNDYING,
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.GOLDEN_APPLE,
            Items.SPAWNER,
            Items.BEACON,
            Items.NETHERITE_INGOT,
            Items.NETHERITE_BLOCK,
            Items.NETHERITE_SCRAP,
            Items.ANCIENT_DEBRIS,
            Items.DIAMOND_BLOCK,
            Items.DIAMOND,
            Items.END_CRYSTAL,
            Items.OBSIDIAN,
            Items.EXPERIENCE_BOTTLE,
            Items.ELYTRA,
            Items.ENDER_PEARL,
            Items.GOLD_BLOCK,
            Items.GOLD_INGOT,
            Items.IRON_BLOCK,
            Items.IRON_INGOT,
            Items.NETHERITE_SWORD,
            Items.NETHERITE_PICKAXE,
            Items.NETHERITE_HELMET,
            Items.NETHERITE_CHESTPLATE,
            Items.NETHERITE_LEGGINGS,
            Items.NETHERITE_BOOTS,
            Items.DIAMOND_SWORD,
            Items.DIAMOND_PICKAXE,
            Items.DIAMOND_HELMET,
            Items.DIAMOND_CHESTPLATE,
            Items.DIAMOND_LEGGINGS,
            Items.DIAMOND_BOOTS
    );

    private static long lastTrashTimeMillis = 0;
    private static long lastEatCheckMillis = 0;
    private static long lastTotemCheckMillis = 0;
    private static long lastLootCheckMillis = 0;
    private static long lastEmergencyCheckMillis = 0;
    private static boolean isAutoEating = false;
    private static int previousSlotBeforeEating = -1;

    private InventoryHelper() {
    }

    public static void tick(
            Minecraft client,
            boolean autoTrash,
            boolean autoEat,
            boolean autoTotem,
            boolean autoLoot,
            boolean autoEmergencyEscape
    ) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            return;
        }

        long now = System.currentTimeMillis();

        if (autoEmergencyEscape && now - lastEmergencyCheckMillis > 100) {
            lastEmergencyCheckMillis = now;
            handleEmergencyEscape(client, player);
        }

        if (autoTotem && now - lastTotemCheckMillis > 250) {
            lastTotemCheckMillis = now;
            handleAutoTotem(client, player);
        }

        if (autoEat) {
            handleAutoEat(client, player, now);
        }

        if (autoTrash && now - lastTrashTimeMillis > 500) {
            lastTrashTimeMillis = now;
            handleAutoTrash(client, player);
        }

        if (autoLoot && now - lastLootCheckMillis > 150) {
            lastLootCheckMillis = now;
            handleAutoLoot(client, player);
        }
    }

    private static void handleEmergencyEscape(Minecraft client, LocalPlayer player) {
        boolean inDanger = player.isInLava()
                || (player.level() != null && player.getY() < player.level().getMinY() + 3)
                || (player.fallDistance > 12.0f && !player.isFallFlying());

        if (!inDanger) {
            return;
        }

        // Try throwing Ender Pearl first
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.ENDER_PEARL)) {
                player.getInventory().setSelectedSlot(i);
                player.setXRot(-60.0f); // Aim upward
                client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                player.displayClientMessage(
                        Component.literal("[RTP Mapper] 🚨 Emergency Escape: Threw Ender Pearl to safety!")
                                .withStyle(ChatFormatting.RED),
                        false
                );
                return;
            }
        }

        // Try eating Chorus Fruit
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.CHORUS_FRUIT)) {
                player.getInventory().setSelectedSlot(i);
                client.options.keyUse.setDown(true);
                player.displayClientMessage(
                        Component.literal("[RTP Mapper] 🚨 Emergency Escape: Consuming Chorus Fruit!")
                                .withStyle(ChatFormatting.YELLOW),
                        false
                );
                return;
            }
        }
    }

    private static void handleAutoTotem(Minecraft client, LocalPlayer player) {
        ItemStack offhand = player.getOffhandItem();
        if (offhand.is(Items.TOTEM_OF_UNDYING)) {
            return;
        }

        // Search main inventory (slots 9 to 44) for a totem
        for (int slot = 9; slot < 45; slot++) {
            ItemStack stack = player.inventoryMenu.getSlot(slot).getItem();
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                // Swap with offhand slot 45
                client.gameMode.handleInventoryMouseClick(
                        player.inventoryMenu.containerId,
                        slot,
                        40, // 40 represents offhand swap button
                        ClickType.SWAP,
                        player
                );
                return;
            }
        }
    }

    private static void handleAutoEat(Minecraft client, LocalPlayer player, long now) {
        if (isAutoEating) {
            if (player.getFoodData().getFoodLevel() >= 20 || !player.isUsingItem()) {
                isAutoEating = false;
                client.options.keyUse.setDown(false);
                if (previousSlotBeforeEating >= 0 && previousSlotBeforeEating < 9) {
                    player.getInventory().setSelectedSlot(previousSlotBeforeEating);
                }
            } else {
                client.options.keyUse.setDown(true);
            }
            return;
        }

        if (now - lastEatCheckMillis < 500) {
            return;
        }
        lastEatCheckMillis = now;

        boolean needsFood = player.getFoodData().getFoodLevel() <= 16
                || (player.getHealth() < player.getMaxHealth() && player.getFoodData().needsFood());

        if (!needsFood) {
            return;
        }

        // Check offhand first
        if (isEdibleFood(player.getOffhandItem())) {
            isAutoEating = true;
            previousSlotBeforeEating = -1;
            client.options.keyUse.setDown(true);
            return;
        }

        // Check hotbar slots 0 to 8
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isEdibleFood(stack)) {
                isAutoEating = true;
                previousSlotBeforeEating = player.getInventory().getSelectedSlot();
                player.getInventory().setSelectedSlot(i);
                client.options.keyUse.setDown(true);
                return;
            }
        }
    }

    private static boolean isEdibleFood(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        // Avoid eating poisonous foods
        if (stack.is(Items.ROTTEN_FLESH) || stack.is(Items.POISONOUS_POTATO)
                || stack.is(Items.SPIDER_EYE) || stack.is(Items.PUFFERFISH) || stack.is(Items.CHORUS_FRUIT)) {
            return false;
        }
        return stack.has(DataComponents.FOOD);
    }

    private static void handleAutoTrash(Minecraft client, LocalPlayer player) {
        // Count empty slots
        int emptySlots = 0;
        for (int i = 9; i < 45; i++) {
            if (player.inventoryMenu.getSlot(i).getItem().isEmpty()) {
                emptySlots++;
            }
        }

        // If inventory has 4 or fewer free slots, throw out junk
        if (emptySlots <= 4) {
            for (int i = 9; i < 45; i++) {
                ItemStack stack = player.inventoryMenu.getSlot(i).getItem();
                if (!stack.isEmpty()) {
                    Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (id != null && JUNK_ITEM_IDS.contains(id.toString())) {
                        client.gameMode.handleInventoryMouseClick(
                                player.inventoryMenu.containerId,
                                i,
                                1, // throw entire stack
                                ClickType.THROW,
                                player
                        );
                        return; // drop one stack per cycle to avoid packet flooding
                    }
                }
            }
        }
    }

    private static void handleAutoLoot(Minecraft client, LocalPlayer player) {
        if (!(client.screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        AbstractContainerMenu menu = containerScreen.getMenu();
        if (menu == null || menu == player.inventoryMenu) {
            return;
        }

        // Slots before player inventory starts (the container slots)
        int containerSlotCount = menu.slots.size() - 36;
        if (containerSlotCount <= 0) {
            return;
        }

        for (int i = 0; i < containerSlotCount; i++) {
            Slot slot = menu.getSlot(i);
            ItemStack stack = slot.getItem();
            if (isValuableLoot(stack)) {
                client.gameMode.handleInventoryMouseClick(
                        menu.containerId,
                        i,
                        0,
                        ClickType.QUICK_MOVE,
                        player
                );
                return; // One transfer per tick
            }
        }
    }

    private static boolean isValuableLoot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        if (VALUABLE_LOOT_ITEMS.contains(item)) {
            return true;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        if (id != null && id.getPath().contains("shulker_box")) {
            return true;
        }
        return false;
    }
}
