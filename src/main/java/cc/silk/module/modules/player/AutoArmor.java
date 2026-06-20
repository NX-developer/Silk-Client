package cc.silk.module.modules.player;

import cc.silk.event.impl.player.TickEvent;
import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.setting.BooleanSetting;
import cc.silk.module.setting.NumberSetting;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

public final class AutoArmor extends Module {
    private static final NumberSetting delay = new NumberSetting("Delay", 50, 500, 150, 10);
    private static final BooleanSetting preferProtection = new BooleanSetting("Prefer Protection", true);
    private static final BooleanSetting openInventory = new BooleanSetting("Open Inventory", true);

    private long lastClick = 0;
    private boolean wasInventoryOpen = false;

    public AutoArmor() {
        super("Auto Armor", "Equips best armor from inventory", -1, Category.PLAYER);
        addSettings(delay, preferProtection, openInventory);
    }

    @EventHandler
    private void onTickEvent(TickEvent event) {
        if (isNull()) return;
        if (mc.player == null || mc.currentScreen != null) return;
        if (System.currentTimeMillis() - lastClick < (long) delay.getValue()) return;

        for (int armorSlot = 5; armorSlot <= 8; armorSlot++) {
            ItemStack currentArmor = mc.player.getInventory().getStack(armorSlot - 5);
            ItemStack bestArmor = findBestArmorForSlot(armorSlot - 5);

            if (bestArmor == null) continue;
            if (currentArmor.isEmpty() || getArmorValue(bestArmor) > getArmorValue(currentArmor)) {
                equipArmor(armorSlot, bestArmor);
                return;
            }
        }
    }

    private ItemStack findBestArmorForSlot(int armorType) {
        ItemStack best = null;
        int bestValue = -1;

        for (int i = 9; i < 45; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem armorItem)) continue;

            int slotType = getArmorSlotType(armorItem);
            if (slotType != armorType) continue;

            int value = getArmorValue(stack);
            if (value > bestValue) {
                bestValue = value;
                best = stack;
            }
        }
        return best;
    }

    private int getArmorSlotType(ArmorItem armorItem) {
        return switch (armorItem.getSlotType()) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
        };
    }

    private int getArmorValue(ItemStack stack) {
        if (!(stack.getItem() instanceof ArmorItem armorItem)) return 0;

        int baseProtection = armorItem.getProtection();
        int durability = stack.getMaxDamage();
        int enchantBonus = preferProtection.getValue() ? getEnchantmentLevel(stack) * 10 : 0;

        return baseProtection * 100 + durability + enchantBonus;
    }

    private int getEnchantmentLevel(ItemStack stack) {
        var enchantments = stack.getEnchantments();
        int level = 0;
        for (int i = 0; i < enchantments.size(); i++) {
            level += enchantments.getLevel(i);
        }
        return level;
    }

    private void equipArmor(int armorSlot, ItemStack bestArmor) {
        if (openInventory.getValue() && mc.currentScreen == null) {
            mc.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(mc.player));
            wasInventoryOpen = true;
        }

        for (int i = 9; i < 45; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack == bestArmor) {
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId,
                    i,
                    0,
                    SlotActionType.PICKUP,
                    mc.player
                );
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId,
                    armorSlot == 0 ? 5 : armorSlot == 1 ? 6 : armorSlot == 2 ? 7 : 8,
                    0,
                    SlotActionType.PICKUP,
                    mc.player
                );
                lastClick = System.currentTimeMillis();
                if (wasInventoryOpen) {
                    mc.setScreen(null);
                    wasInventoryOpen = false;
                }
                return;
            }
        }
    }

    @Override
    public void onEnable() {
        super.onEnable();
        lastClick = 0;
        wasInventoryOpen = false;
    }

    @Override
    public void onDisable() {
        super.onDisable();
        if (wasInventoryOpen && mc.currentScreen != null) {
            mc.setScreen(null);
            wasInventoryOpen = false;
        }
    }
}
