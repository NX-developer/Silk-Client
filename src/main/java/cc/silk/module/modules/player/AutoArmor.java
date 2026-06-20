package cc.silk.module.modules.player;

import cc.silk.event.impl.player.TickEvent;
import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.setting.BooleanSetting;
import cc.silk.module.setting.NumberSetting;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

public final class AutoArmor extends Module {
    private static final NumberSetting delay = new NumberSetting("Delay", 50, 500, 150, 10);
    private static final BooleanSetting preferProtection = new BooleanSetting("Prefer Protection", true);

    private long lastClick = 0;

    public AutoArmor() {
        super("Auto Armor", "Equips best armor from inventory", -1, Category.PLAYER);
        addSettings(delay, preferProtection);
    }

    @EventHandler
    private void onTickEvent(TickEvent event) {
        if (isNull()) return;
        if (mc.player == null || mc.currentScreen != null) return;
        if (System.currentTimeMillis() - lastClick < (long) delay.getValue()) return;

        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : slots) {
            ItemStack currentArmor = mc.player.getEquippedStack(slot);
            ItemStack bestArmor = findBestArmorForSlot(slot);

            if (bestArmor == null) continue;
            if (currentArmor.isEmpty() || getArmorValue(bestArmor) > getArmorValue(currentArmor)) {
                equipArmor(slot, bestArmor);
                return;
            }
        }
    }

    private ItemStack findBestArmorForSlot(EquipmentSlot targetSlot) {
        ItemStack best = null;
        int bestValue = -1;

        for (int i = 9; i < 45; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem)) continue;

            EquipmentSlot slot = mc.player.getPreferredEquipmentSlot(stack);
            if (slot != targetSlot) continue;

            int value = getArmorValue(stack);
            if (value > bestValue) {
                bestValue = value;
                best = stack;
            }
        }
        return best;
    }

    private int getArmorValue(ItemStack stack) {
        int durability = stack.getMaxDamage();
        int enchantBonus = preferProtection.getValue() ? getEnchantmentLevel(stack) * 10 : 0;
        return durability + enchantBonus;
    }

    private int getEnchantmentLevel(ItemStack stack) {
        int level = 0;
        for (var entry : stack.getEnchantments().getEnchantmentEntries()) {
            level += entry.getIntValue();
        }
        return level;
    }

    private void equipArmor(EquipmentSlot slot, ItemStack bestArmor) {
        int armorSlotIndex = switch (slot) {
            case HEAD -> 5;
            case CHEST -> 6;
            case LEGS -> 7;
            case FEET -> 8;
            default -> -1;
        };
        if (armorSlotIndex == -1) return;

        for (int i = 9; i < 45; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack == bestArmor) {
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId,
                    i, 0, SlotActionType.PICKUP, mc.player
                );
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId,
                    armorSlotIndex, 0, SlotActionType.PICKUP, mc.player
                );
                lastClick = System.currentTimeMillis();
                return;
            }
        }
    }

    @Override
    public void onEnable() {
        super.onEnable();
        lastClick = 0;
    }
}
