package cc.silk.module.modules.combat;

import cc.silk.event.impl.player.TickEvent;
import cc.silk.mixin.MinecraftClientAccessor;
import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.modules.misc.Teams;
import cc.silk.module.setting.BooleanSetting;
import cc.silk.module.setting.ModeSetting;
import cc.silk.module.setting.NumberSetting;
import cc.silk.utils.friend.FriendManager;
import cc.silk.utils.math.TimerUtil;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

/**
 * Advanced AutoMace module for anarchy servers.
 *
 * Scenario 1 (Full Combo): Pearl → WindCharge → ElytraGlide → ArmorSwap → MaceStrike
 * Scenario 2 (Fallback): Passive fall control with reach-based auto-attack
 *
 * Anti-cheat bypass: latency-based delays, legit packet timing, no flight flags
 */
public final class AutoMace extends Module {

    // ─── Settings ────────────────────────────────────────────────────────────
    private final ModeSetting mode = new ModeSetting("Mode", "Full Combo", "Full Combo", "Fallback Only", "Auto Only");

    // Scenario 1 - Full Combo
    private final NumberSetting pearlLookAngle = new NumberSetting("Pearl Look Angle", 60, 90, 80, 1);
    private final NumberSetting windChargeDelay = new NumberSetting("Wind Charge Delay (ms)", 50, 500, 150, 10);
    private final NumberSetting elytraSwapDelay = new NumberSetting("Elytra Swap Delay (ms)", 50, 400, 120, 10);
    private final NumberSetting armorSwapHeight = new NumberSetting("Armor Swap Height (blocks)", 1, 10, 3, 0.5);
    private final NumberSetting maceStrikeDelay = new NumberSetting("Mace Strike Delay (ms)", 0, 200, 50, 5);

    // Scenario 2 - Fallback
    private final NumberSetting fallbackMinFall = new NumberSetting("Fallback Min Fall", 3, 40, 8, 1);
    private final NumberSetting fallbackMaxReach = new NumberSetting("Fallback Max Reach", 3, 6, 4.5, 0.1);
    private final BooleanSetting fallbackAutoAttack = new BooleanSetting("Fallback Auto Attack", true);

    // Common
    private final NumberSetting targetRange = new NumberSetting("Target Range", 2, 30, 15, 1);
    private final NumberSetting latencyMultiplier = new NumberSetting("Latency Multiplier", 0.5, 3.0, 1.2, 0.1);
    private final BooleanSetting targetPlayers = new BooleanSetting("Target Players", true);
    private final BooleanSetting targetMobs = new BooleanSetting("Target Mobs", false);
    private final BooleanSetting autoSwitch = new BooleanSetting("Auto Switch Mace", true);
    private final BooleanSetting antiSuicide = new BooleanSetting("Anti Suicide", true);
    private final BooleanSetting silentSwap = new BooleanSetting("Silent Swap", true);

    // ─── State Machine ───────────────────────────────────────────────────────
    private enum ComboPhase {
        IDLE,
        PEARL_LOOK,
        PEARL_THROW,
        WIND_CHARGE,
        TELEPORT_WAIT,
        ELYTRA_EQUIP,
        GLIDE_APPROACH,
        ARMOR_SWAP_PREPARE,
        ARMOR_SWAP_EXECUTE,
        MACE_STRIKE,
        CLEANUP
    }

    // ─── State Fields ────────────────────────────────────────────────────────
    private ComboPhase currentPhase = ComboPhase.IDLE;
    private final TimerUtil phaseTimer = new TimerUtil();
    private final TimerUtil globalTimer = new TimerUtil();

    private int originalHotbarSlot = -1;
    private int elytraHotbarSlot = -1;
    private int chestplateHotbarSlot = -1;
    private int maceHotbarSlot = -1;
    private int pearlHotbarSlot = -1;
    private int windChargeHotbarSlot = -1;

    private float savedYaw = 0;
    private float savedPitch = 0;
    private boolean rotationSaved = false;

    private double fallStartY = -1;
    private boolean isFalling = false;
    private boolean hasAttacked = false;

    private Entity currentTarget = null;
    private boolean isFullComboMode = false;

    // Latency simulation
    private int cachedPing = 0;

    public AutoMace() {
        super("Auto Mace", "Advanced mace combat with pearl-windcharge-elytra chain and fallback", -1, Category.COMBAT);
        this.addSettings(
                mode,
                pearlLookAngle, windChargeDelay, elytraSwapDelay, armorSwapHeight, maceStrikeDelay,
                fallbackMinFall, fallbackMaxReach, fallbackAutoAttack,
                targetRange, latencyMultiplier, targetPlayers, targetMobs, autoSwitch, antiSuicide, silentSwap
        );
    }

    // ─── Event Handler ───────────────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent event) {
        if (isNull() || mc.currentScreen != null) return;

        updatePing();
        currentTarget = mc.targetedEntity;

        isFullComboMode = switch (mode.getMode()) {
            case "Full Combo" -> canExecuteFullCombo();
            case "Fallback Only" -> false;
            case "Auto Only" -> false;
            default -> canExecuteFullCombo();
        };

        if (isFullComboMode) {
            executeFullCombo();
        } else if (mode.getMode().equals("Full Combo") || mode.getMode().equals("Fallback Only")) {
            executeFallback();
        } else if (mode.getMode().equals("Auto Only")) {
            executeAutoOnly();
        }
    }

    // ─── Scenario 1: Full Combo ──────────────────────────────────────────────
    private boolean canExecuteFullCombo() {
        if (antiSuicide.getValue() && mc.player.getHealth() < 6) return false;
        if (!hasRequiredItems()) return false;
        Entity target = mc.targetedEntity;
        return isValidTarget(target) && mc.player.getPos().distanceTo(target.getPos()) <= targetRange.getValue();
    }

    private boolean hasRequiredItems() {
        return findItemSlot(Items.ENDER_PEARL) != -1
                && findItemSlot(Items.WIND_CHARGE) != -1
                && findItemSlot(Items.ELYTRA) != -1
                && findItemSlot(Items.MACE) != -1;
    }

    private void executeFullCombo() {
        switch (currentPhase) {
            case IDLE -> startCombo();
            case PEARL_LOOK -> phasePearlLook();
            case PEARL_THROW -> phasePearlThrow();
            case WIND_CHARGE -> phaseWindCharge();
            case TELEPORT_WAIT -> phaseTeleportWait();
            case ELYTRA_EQUIP -> phaseElytraEquip();
            case GLIDE_APPROACH -> phaseGlideApproach();
            case ARMOR_SWAP_PREPARE -> phaseArmorSwapPrepare();
            case ARMOR_SWAP_EXECUTE -> phaseArmorSwapExecute();
            case MACE_STRIKE -> phaseMaceStrike();
            case CLEANUP -> phaseCleanup();
        }
    }

    private void startCombo() {
        if (currentTarget == null || !isValidTarget(currentTarget)) {
            currentPhase = ComboPhase.IDLE;
            return;
        }

        saveRotation();
        cacheHotbarSlots();

        pearlHotbarSlot = findItemSlot(Items.ENDER_PEARL);
        windChargeHotbarSlot = findItemSlot(Items.WIND_CHARGE);
        elytraHotbarSlot = findItemSlot(Items.ELYTRA);
        maceHotbarSlot = findItemSlot(Items.MACE);

        if (pearlHotbarSlot == -1 || windChargeHotbarSlot == -1 || elytraHotbarSlot == -1 || maceHotbarSlot == -1) {
            resetPhase();
            return;
        }

        currentPhase = ComboPhase.PEARL_LOOK;
        phaseTimer.reset();
    }

    private void phasePearlLook() {
        float targetAngle = pearlLookAngle.getValueFloat();
        float currentPitch = mc.player.getPitch();

        if (currentPitch < targetAngle - 1) {
            float newPitch = Math.min(currentPitch + getRotationSpeed(), targetAngle);
            mc.player.setPitch(newPitch);
        } else {
            mc.player.setPitch(targetAngle);
            currentPhase = ComboPhase.PEARL_THROW;
            phaseTimer.reset();
        }
    }

    private void phasePearlThrow() {
        long delay = getLatencyAdjustedDelay(windChargeDelay.getValueInt());
        if (!phaseTimer.hasElapsedTime(delay)) return;

        switchToSlot(pearlHotbarSlot);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.player.swingHand(Hand.MAIN_HAND);

        currentPhase = ComboPhase.WIND_CHARGE;
        phaseTimer.reset();
    }

    private void phaseWindCharge() {
        long delay = getLatencyAdjustedDelay(50);
        if (!phaseTimer.hasElapsedTime(delay)) return;

        mc.player.setPitch(90.0f);

        switchToSlot(windChargeHotbarSlot);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.player.swingHand(Hand.MAIN_HAND);

        currentPhase = ComboPhase.TELEPORT_WAIT;
        phaseTimer.reset();
    }

    private void phaseTeleportWait() {
        long maxWait = getLatencyAdjustedDelay(2000);
        if (phaseTimer.getElapsedTime() > maxWait) {
            currentPhase = ComboPhase.CLEANUP;
            phaseTimer.reset();
            return;
        }

        boolean teleported = mc.player.getPos().distanceTo(mc.player.getLastRenderPos()) > 16
                || phaseTimer.hasElapsedTime(getLatencyAdjustedDelay(400));

        if (teleported || phaseTimer.hasElapsedTime(getLatencyAdjustedDelay(500))) {
            currentPhase = ComboPhase.ELYTRA_EQUIP;
            phaseTimer.reset();
        }
    }

    private void phaseElytraEquip() {
        long delay = getLatencyAdjustedDelay(elytraSwapDelay.getValueInt());
        if (!phaseTimer.hasElapsedTime(delay)) return;

        equipElytra();

        if (!silentSwap.getValue()) {
            switchToSlot(elytraHotbarSlot);
        }

        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        currentPhase = ComboPhase.GLIDE_APPROACH;
        phaseTimer.reset();
    }

    private void phaseGlideApproach() {
        if (currentTarget == null || !currentTarget.isAlive()) {
            currentPhase = ComboPhase.CLEANUP;
            phaseTimer.reset();
            return;
        }

        Vec3d playerPos = mc.player.getPos();
        Vec3d targetPos = currentTarget.getPos();
        double distance = playerPos.distanceTo(targetPos);
        double heightDiff = playerPos.y - targetPos.y;

        lookAtTarget(currentTarget);

        if (distance <= fallbackMaxReach.getValue() + 1.0 && heightDiff >= -1 && heightDiff <= armorSwapHeight.getValue() + 2) {
            currentPhase = ComboPhase.ARMOR_SWAP_PREPARE;
            phaseTimer.reset();
            return;
        }

        if (phaseTimer.hasElapsedTime(5000)) {
            currentPhase = ComboPhase.CLEANUP;
            phaseTimer.reset();
        }
    }

    private void phaseArmorSwapPrepare() {
        long delay = getLatencyAdjustedDelay(maceStrikeDelay.getValueInt());
        if (!phaseTimer.hasElapsedTime(delay)) return;

        switchToSlot(maceHotbarSlot);

        currentPhase = ComboPhase.ARMOR_SWAP_EXECUTE;
        phaseTimer.reset();
    }

    private void phaseArmorSwapExecute() {
        swapElytraToChestplate();

        if (mc.player.fallDistance > 0 || mc.player.getVelocity().y < 0) {
            currentPhase = ComboPhase.MACE_STRIKE;
            phaseTimer.reset();
        } else if (phaseTimer.hasElapsedTime(300)) {
            currentPhase = ComboPhase.MACE_STRIKE;
            phaseTimer.reset();
        }
    }

    private void phaseMaceStrike() {
        if (currentTarget == null || !currentTarget.isAlive()) {
            currentPhase = ComboPhase.CLEANUP;
            phaseTimer.reset();
            return;
        }

        double distance = mc.player.getPos().distanceTo(currentTarget.getPos());
        if (distance <= 4.5) {
            switchToSlot(maceHotbarSlot);
            ((MinecraftClientAccessor) mc).invokeDoAttack();
            hasAttacked = true;
        }

        currentPhase = ComboPhase.CLEANUP;
        phaseTimer.reset();
    }

    private void phaseCleanup() {
        if (originalHotbarSlot != -1 && !silentSwap.getValue()) {
            mc.player.getInventory().selectedSlot = originalHotbarSlot;
        }
        restoreRotation();
        resetPhase();
    }

    // ─── Scenario 2: Fallback ────────────────────────────────────────────────
    private void executeFallback() {
        if (isNull()) return;

        boolean onGround = mc.player.isOnGround();
        boolean falling = mc.player.getVelocity().y < -0.1;
        double currentY = mc.player.getY();

        if (onGround) {
            if (isFalling) {
                isFalling = false;
                fallStartY = -1;
                hasAttacked = false;
            }
            return;
        }

        if (!isFalling && falling) {
            isFalling = true;
            fallStartY = currentY;
            hasAttacked = false;
        }

        if (!isFalling || !falling) return;
        if (hasAttacked) return;

        double fallDist = fallStartY == -1 ? 0 : Math.max(0, fallStartY - currentY);
        if (fallDist < fallbackMinFall.getValue()) return;

        Entity target = mc.targetedEntity;
        if (!isValidTarget(target)) return;

        double distance = mc.player.getPos().distanceTo(target.getPos());
        if (distance > fallbackMaxReach.getValue()) return;

        if (mc.player.getPos().y <= target.getPos().y + 2.5
                && mc.player.getPos().y >= target.getPos().y - 1.5) {

            if (!hasMace() && autoSwitch.getValue()) {
                int maceSlot = findItemSlot(Items.MACE);
                if (maceSlot != -1) {
                    if (originalHotbarSlot == -1) originalHotbarSlot = mc.player.getInventory().selectedSlot;
                    switchToSlot(maceSlot);
                }
            }

            if (fallbackAutoAttack.getValue() && hasMace()) {
                ((MinecraftClientAccessor) mc).invokeDoAttack();
                hasAttacked = true;
            }
        }
    }

    // ─── Auto Only Mode ──────────────────────────────────────────────────────
    private void executeAutoOnly() {
        if (isNull() || mc.player.isOnGround()) return;
        if (mc.player.getVelocity().y >= -0.1) return;

        Entity target = mc.targetedEntity;
        if (!isValidTarget(target)) return;

        double distance = mc.player.getPos().distanceTo(target.getPos());
        double fallDist = fallStartY == -1 ? 0 : Math.max(0, fallStartY - mc.player.getY());

        if (!isFalling) {
            isFalling = true;
            fallStartY = mc.player.getY();
            hasAttacked = false;
        }

        if (fallDist >= 3 && distance <= 4.5 && mc.player.getPos().y <= target.getPos().y + 2.5) {
            if (!hasMace() && autoSwitch.getValue()) {
                int maceSlot = findItemSlot(Items.MACE);
                if (maceSlot != -1) {
                    if (originalHotbarSlot == -1) originalHotbarSlot = mc.player.getInventory().selectedSlot;
                    switchToSlot(maceSlot);
                }
            }

            if (hasMace() && !hasAttacked) {
                ((MinecraftClientAccessor) mc).invokeDoAttack();
                hasAttacked = true;
            }
        }
    }

    // ─── Inventory Helpers ───────────────────────────────────────────────────
    private void equipElytra() {
        if (elytraHotbarSlot == -1) return;

        if (silentSwap.getValue()) {
            int prevSlot = mc.player.getInventory().selectedSlot;
            mc.player.getInventory().selectedSlot = elytraHotbarSlot;
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.getInventory().selectedSlot = prevSlot;
        } else {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        }
    }

    private void swapElytraToChestplate() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isChestplate(stack)) {
                if (silentSwap.getValue()) {
                    int prevSlot = mc.player.getInventory().selectedSlot;
                    mc.player.getInventory().selectedSlot = i;
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    mc.player.getInventory().selectedSlot = prevSlot;
                } else {
                    mc.player.getInventory().selectedSlot = i;
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }
                return;
            }
        }
    }

    private boolean isChestplate(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        return item == Items.DIAMOND_CHESTPLATE || item == Items.IRON_CHESTPLATE
                || item == Items.GOLDEN_CHESTPLATE || item == Items.LEATHER_CHESTPLATE
                || item == Items.CHAINMAIL_CHESTPLATE || item == Items.NETHERITE_CHESTPLATE;
    }

    private boolean hasMace() {
        return mc.player.getMainHandStack().getItem() == Items.MACE;
    }

    private int findItemSlot(Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) return i;
        }
        return -1;
    }

    private void switchToSlot(int slot) {
        if (slot >= 0 && slot < 9) {
            mc.player.getInventory().selectedSlot = slot;
        }
    }

    private void cacheHotbarSlots() {
        if (originalHotbarSlot == -1) {
            originalHotbarSlot = mc.player.getInventory().selectedSlot;
        }
    }

    // ─── Rotation Helpers ────────────────────────────────────────────────────
    private void saveRotation() {
        if (!rotationSaved) {
            savedYaw = mc.player.getYaw();
            savedPitch = mc.player.getPitch();
            rotationSaved = true;
        }
    }

    private void restoreRotation() {
        if (rotationSaved) {
            mc.player.setYaw(savedYaw);
            mc.player.setPitch(savedPitch);
            rotationSaved = false;
        }
    }

    private void lookAtTarget(Entity target) {
        if (target == null) return;
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d targetPos = target.getPos().add(0, target.getStandingEyeHeight() * 0.85, 0);
        Vec3d diff = targetPos.subtract(eyePos);

        float yaw = (float) (Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90);
        float pitch = (float) Math.toDegrees(-Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)));

        mc.player.setYaw(yaw);
        mc.player.setPitch(MathHelper.clamp(pitch, -90f, 90f));
    }

    private float getRotationSpeed() {
        return 45.0f;
    }

    // ─── Anti-Cheat Bypass Helpers ───────────────────────────────────────────
    private void updatePing() {
        if (mc.player != null && mc.getNetworkHandler() != null) {
            var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            if (entry != null) {
                cachedPing = entry.getLatency();
            }
        }
    }

    private long getLatencyAdjustedDelay(int baseDelay) {
        float pingMs = Math.max(cachedPing, 0);
        return (long) (baseDelay * latencyMultiplier.getValue() + pingMs * 0.25);
    }

    private boolean isPlayerMoving() {
        return mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
                || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed();
    }

    // ─── Target Validation ───────────────────────────────────────────────────
    private boolean isValidTarget(Entity entity) {
        if (entity == null || entity == mc.player || entity == mc.cameraEntity) return false;
        if (!(entity instanceof LivingEntity living)) return false;
        if (!living.isAlive() || living.isDead()) return false;
        if (Teams.isTeammate(entity)) return false;
        if (FriendManager.isFriend(entity.getUuid())) return false;
        if (mc.player.getPos().distanceTo(entity.getPos()) > targetRange.getValue()) return false;

        if (entity instanceof PlayerEntity) return targetPlayers.getValue();
        if (!targetMobs.getValue()) return false;
        return !(entity instanceof PassiveEntity) && !(entity instanceof Tameable);
    }

    // ─── State Management ────────────────────────────────────────────────────
    private void resetPhase() {
        currentPhase = ComboPhase.IDLE;
        phaseTimer.reset();
    }

    private void resetAll() {
        resetPhase();
        originalHotbarSlot = -1;
        elytraHotbarSlot = -1;
        chestplateHotbarSlot = -1;
        maceHotbarSlot = -1;
        pearlHotbarSlot = -1;
        windChargeHotbarSlot = -1;
        fallStartY = -1;
        isFalling = false;
        hasAttacked = false;
        currentTarget = null;
        restoreRotation();
    }

    @Override
    public void onEnable() {
        resetAll();
        globalTimer.reset();
    }

    @Override
    public void onDisable() {
        resetAll();
    }

    @Override
    public int getKey() {
        return -1;
    }

    @Override
    public void setKey(int key) {
    }
}
