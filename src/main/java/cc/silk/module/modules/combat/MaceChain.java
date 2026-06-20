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
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;

/**
 * MaceChain - Advanced chain combo module for mace combat.
 *
 * Features:
 * - Chain tracking with combo counter
 * - Auto-pearl for re-engaging after kills
 * - Wind charge momentum for extended combos
 * - Elytra chain for aerial mace strikes
 * - Anti-cheat bypass with latency-aware timing
 * - Smart target switching
 */
public final class MaceChain extends Module {

    // ─── Settings ────────────────────────────────────────────────────────────
    private final ModeSetting chainMode = new ModeSetting("Chain Mode", "Standard", "Standard", "Aggressive", "Passive");

    // Chain Settings
    private final NumberSetting chainResetDelay = new NumberSetting("Chain Reset Delay (ms)", 500, 5000, 2000, 100);
    private final NumberSetting maxChainLength = new NumberSetting("Max Chain Length", 2, 20, 8, 1);
    private final NumberSetting comboWindow = new NumberSetting("Combo Window (ms)", 200, 2000, 800, 50);

    // Combat Settings
    private final NumberSetting attackRange = new NumberSetting("Attack Range", 3, 6, 4.5, 0.1);
    private final NumberSetting preAttackDelay = new NumberSetting("Pre-Attack Delay (ms)", 0, 300, 80, 10);
    private final NumberSetting postAttackDelay = new NumberSetting("Post-Attack Delay (ms)", 0, 500, 150, 10);
    private final NumberSetting reachDistance = new NumberSetting("Reach Distance", 2, 5, 3.5, 0.1);

    // Elytra Chain
    private final BooleanSetting useElytraChain = new BooleanSetting("Elytra Chain", true);
    private final NumberSetting elytraChainHeight = new NumberSetting("Elytra Chain Height", 2, 15, 5, 0.5);
    private final NumberSetting elytraChainSpeed = new NumberSetting("Elytra Chain Speed", 0.5, 3.0, 1.5, 0.1);

    // Pearl Re-engage
    private final BooleanSetting autoPearlReengage = new BooleanSetting("Auto Pearl Re-engage", true);
    private final NumberSetting pearlReengageRange = new NumberSetting("Pearl Re-engage Range", 10, 30, 20, 1);

    // Wind Charge
    private final BooleanSetting useWindCharge = new BooleanSetting("Use Wind Charge", true);
    private final NumberSetting windChargeMomentum = new NumberSetting("Wind Charge Momentum", 0.5, 3.0, 1.5, 0.1);

    // Target Settings
    private final NumberSetting targetSwitchDelay = new NumberSetting("Target Switch Delay (ms)", 100, 1000, 300, 50);
    private final BooleanSetting targetPlayers = new BooleanSetting("Target Players", true);
    private final BooleanSetting targetMobs = new BooleanSetting("Target Mobs", false);
    private final BooleanSetting autoSwitch = new BooleanSetting("Auto Switch Mace", true);

    // Bypass Settings
    private final NumberSetting latencyBuffer = new NumberSetting("Latency Buffer", 0.5, 3.0, 1.0, 0.1);
    private final BooleanSetting randomizeTimings = new BooleanSetting("Randomize Timings", true);
    private final NumberSetting timingVariance = new NumberSetting("Timing Variance (%)", 0, 50, 15, 5);

    // ─── State ───────────────────────────────────────────────────────────────
    private enum ChainState {
        IDLE,
        CHAIN_ATTACK,
        CHAIN_ELYTRA_SETUP,
        CHAIN_ELYTRA_Glide,
        CHAIN_WIND_CHARGE,
        CHAIN_PEARL_REENGAGE,
        CHAIN_COOLDOWN,
        CHAIN_CLEANUP
    }

    private ChainState currentState = ChainState.IDLE;
    private final TimerUtil stateTimer = new TimerUtil();
    private final TimerUtil chainTimer = new TimerUtil();
    private final TimerUtil targetSwitchTimer = new TimerUtil();

    // Chain tracking
    private int chainCount = 0;
    private long lastAttackTime = 0;
    private Entity currentTarget = null;
    private Entity lastTarget = null;

    // Inventory state
    private int originalSlot = -1;
    private int maceSlot = -1;
    private int elytraSlot = -1;
    private int pearlSlot = -1;
    private int windChargeSlot = -1;

    // Rotation state
    private float savedYaw = 0;
    private float savedPitch = 0;
    private boolean rotationSaved = false;

    // Timing
    private long lastPingUpdate = 0;
    private int cachedPing = 0;
    private long lastHitTime = 0;

    public MaceChain() {
        super("Mace Chain", "Advanced chain combo system for mace combat", -1, Category.COMBAT);
        this.addSettings(
                chainMode,
                chainResetDelay, maxChainLength, comboWindow,
                attackRange, preAttackDelay, postAttackDelay, reachDistance,
                useElytraChain, elytraChainHeight, elytraChainSpeed,
                autoPearlReengage, pearlReengageRange,
                useWindCharge, windChargeMomentum,
                targetSwitchDelay, targetPlayers, targetMobs, autoSwitch,
                latencyBuffer, randomizeTimings, timingVariance
        );
    }

    // ─── Event Handler ───────────────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent event) {
        if (isNull() || mc.currentScreen != null) return;

        updatePing();
        updateChainState();

        switch (currentState) {
            case IDLE -> handleIdle();
            case CHAIN_ATTACK -> handleChainAttack();
            case CHAIN_ELYTRA_SETUP -> handleElytraSetup();
            case CHAIN_ELYTRA_Glide -> handleElytraGlide();
            case CHAIN_WIND_CHARGE -> handleWindCharge();
            case CHAIN_PEARL_REENGAGE -> handlePearlReengage();
            case CHAIN_COOLDOWN -> handleCooldown();
            case CHAIN_CLEANUP -> handleCleanup();
        }
    }

    // ─── State Machine ───────────────────────────────────────────────────────
    private void handleIdle() {
        if (chainCount >= maxChainLength.getValueInt()) {
            currentState = ChainState.CHAIN_CLEANUP;
            stateTimer.reset();
            return;
        }

        Entity target = findBestTarget();
        if (target == null) return;

        double distance = mc.player.getPos().distanceTo(target.getPos());
        if (distance > attackRange.getValue() + 2) {
            if (autoPearlReengage.getValue() && pearlSlot != -1 && distance <= pearlReengageRange.getValue()) {
                currentTarget = target;
                currentState = ChainState.CHAIN_PEARL_REENGAGE;
                stateTimer.reset();
            }
            return;
        }

        if (!isValidTarget(target)) return;

        currentTarget = target;
        saveRotation();
        cacheHotbarSlots();
        findItemSlots();

        currentState = ChainState.CHAIN_ATTACK;
        stateTimer.reset();
    }

    private void handleChainAttack() {
        if (currentTarget == null || !currentTarget.isAlive()) {
            lastTarget = currentTarget;
            currentState = ChainState.CHAIN_CLEANUP;
            stateTimer.reset();
            return;
        }

        double distance = mc.player.getPos().distanceTo(currentTarget.getPos());
        if (distance > attackRange.getValue()) {
            handleDistanceExceeded();
            return;
        }

        long adjustedPreDelay = getAdjustedDelay(preAttackDelay.getValueInt());
        if (!stateTimer.hasElapsedTime(adjustedPreDelay)) return;

        lookAtTarget(currentTarget);

        if (autoSwitch.getValue() && !hasMace()) {
            if (maceSlot != -1) {
                switchToSlot(maceSlot);
            }
        }

        if (hasMace()) {
            ((MinecraftClientAccessor) mc).invokeDoAttack();
            lastAttackTime = System.currentTimeMillis();
            lastHitTime = System.currentTimeMillis();
            chainCount++;

            long adjustedPostDelay = getAdjustedDelay(postAttackDelay.getValueInt());
            stateTimer.reset();

            if (shouldUseElytraChain()) {
                currentState = ChainState.CHAIN_ELYTRA_SETUP;
            } else if (shouldUseWindCharge()) {
                currentState = ChainState.CHAIN_WIND_CHARGE;
            } else {
                currentState = ChainState.CHAIN_COOLDOWN;
            }
        }
    }

    private void handleDistanceExceeded() {
        if (autoPearlReengage.getValue() && pearlSlot != -1) {
            double distance = mc.player.getPos().distanceTo(currentTarget.getPos());
            if (distance <= pearlReengageRange.getValue()) {
                currentState = ChainState.CHAIN_PEARL_REENGAGE;
                stateTimer.reset();
                return;
            }
        }

        if (useElytraChain.getValue() && elytraSlot != -1) {
            currentState = ChainState.CHAIN_ELYTRA_SETUP;
            stateTimer.reset();
            return;
        }

        currentState = ChainState.CHAIN_CLEANUP;
        stateTimer.reset();
    }

    private void handleElytraSetup() {
        long delay = getAdjustedDelay(200);
        if (!stateTimer.hasElapsedTime(delay)) return;

        if (elytraSlot != -1) {
            equipItem(Items.ELYTRA, elytraSlot);
        }

        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        currentState = ChainState.CHAIN_ELYTRA_Glide;
        stateTimer.reset();
    }

    private void handleElytraGlide() {
        if (currentTarget == null || !currentTarget.isAlive()) {
            currentState = ChainState.CHAIN_CLEANUP;
            stateTimer.reset();
            return;
        }

        lookAtTarget(currentTarget);

        Vec3d playerPos = mc.player.getPos();
        Vec3d targetPos = currentTarget.getPos();
        double distance = playerPos.distanceTo(targetPos);
        double heightDiff = playerPos.y - targetPos.y;

        if (distance <= attackRange.getValue() && heightDiff >= -1 && heightDiff <= elytraChainHeight.getValue() + 2) {
            swapToChestplate();

            if (maceSlot != -1) {
                switchToSlot(maceSlot);
            }

            if (hasMace() && distance <= 4.5) {
                ((MinecraftClientAccessor) mc).invokeDoAttack();
                lastAttackTime = System.currentTimeMillis();
                lastHitTime = System.currentTimeMillis();
                chainCount++;
            }

            currentState = ChainState.CHAIN_COOLDOWN;
            stateTimer.reset();
            return;
        }

        if (stateTimer.hasElapsedTime(3000)) {
            currentState = ChainState.CHAIN_CLEANUP;
            stateTimer.reset();
        }
    }

    private void handleWindCharge() {
        long delay = getAdjustedDelay(100);
        if (!stateTimer.hasElapsedTime(delay)) return;

        if (windChargeSlot != -1) {
            switchToSlot(windChargeSlot);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        currentState = ChainState.CHAIN_COOLDOWN;
        stateTimer.reset();
    }

    private void handlePearlReengage() {
        long delay = getAdjustedDelay(150);
        if (!stateTimer.hasElapsedTime(delay)) return;

        if (pearlSlot != -1) {
            lookUp();
            switchToSlot(pearlSlot);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        currentState = ChainState.CHAIN_COOLDOWN;
        stateTimer.reset();
    }

    private void handleCooldown() {
        long chainResetTime = getAdjustedDelay(chainResetDelay.getValueInt());
        if (stateTimer.hasElapsedTime(chainResetTime)) {
            currentState = ChainState.CHAIN_CLEANUP;
            stateTimer.reset();
        }
    }

    private void handleCleanup() {
        restoreRotation();

        if (originalSlot != -1) {
            mc.player.getInventory().selectedSlot = originalSlot;
            originalSlot = -1;
        }

        chainCount = 0;
        currentTarget = null;
        currentState = ChainState.IDLE;
        stateTimer.reset();
    }

    // ─── Chain State Update ──────────────────────────────────────────────────
    private void updateChainState() {
        if (chainCount > 0 && System.currentTimeMillis() - lastAttackTime > chainResetDelay.getValueInt()) {
            chainCount = 0;
        }
    }

    // ─── Target Selection ────────────────────────────────────────────────────
    private Entity findBestTarget() {
        if (mc.world == null) return null;

        Entity bestTarget = null;
        double bestScore = Double.MIN_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!isValidTarget(entity)) continue;

            double distance = mc.player.getPos().distanceTo(entity.getPos());
            if (distance > attackRange.getValue() + 5) continue;

            double healthScore = entity instanceof LivingEntity living ? (100 - living.getHealth()) : 50;
            double distanceScore = Math.max(0, 100 - distance * 5);
            double heightScore = entity.getPos().y > mc.player.getPos().y ? 20 : 0;

            double score = healthScore + distanceScore + heightScore;

            if (score > bestScore) {
                bestScore = score;
                bestTarget = entity;
            }
        }

        return bestTarget;
    }

    private boolean isValidTarget(Entity entity) {
        if (entity == null || entity == mc.player || entity == mc.cameraEntity) return false;
        if (!(entity instanceof LivingEntity living)) return false;
        if (!living.isAlive() || living.isDead()) return false;
        if (Teams.isTeammate(entity)) return false;
        if (FriendManager.isFriend(entity.getUuid())) return false;

        if (entity instanceof PlayerEntity) return targetPlayers.getValue();
        if (!targetMobs.getValue()) return false;
        return !(entity instanceof PassiveEntity) && !(entity instanceof Tameable);
    }

    // ─── Combat Logic ────────────────────────────────────────────────────────
    private boolean shouldUseElytraChain() {
        if (!useElytraChain.getValue()) return false;
        if (elytraSlot == -1) return false;
        if (mc.player.isOnGround()) return false;
        return chainCount > 0 && chainCount % 2 == 0;
    }

    private boolean shouldUseWindCharge() {
        if (!useWindCharge.getValue()) return false;
        if (windChargeSlot == -1) return false;
        return chainCount > 0 && chainCount % 3 == 0;
    }

    private boolean hasMace() {
        return mc.player.getMainHandStack().getItem() == Items.MACE;
    }

    // ─── Inventory Helpers ───────────────────────────────────────────────────
    private void findItemSlots() {
        maceSlot = findItemSlot(Items.MACE);
        elytraSlot = findItemSlot(Items.ELYTRA);
        pearlSlot = findItemSlot(Items.ENDER_PEARL);
        windChargeSlot = findItemSlot(Items.WIND_CHARGE);
    }

    private int findItemSlot(Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) return i;
        }
        return -1;
    }

    private void equipItem(Item item, int slot) {
        if (slot == -1) return;

        int prevSlot = mc.player.getInventory().selectedSlot;
        mc.player.getInventory().selectedSlot = slot;
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.player.getInventory().selectedSlot = prevSlot;
    }

    private void swapToChestplate() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isChestplate(stack)) {
                equipItem(stack.getItem(), i);
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

    private void switchToSlot(int slot) {
        if (slot >= 0 && slot < 9) {
            mc.player.getInventory().selectedSlot = slot;
        }
    }

    private void cacheHotbarSlots() {
        if (originalSlot == -1) {
            originalSlot = mc.player.getInventory().selectedSlot;
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
        mc.player.setPitch(clamp(pitch, -90, 90));
    }

    private void lookUp() {
        mc.player.setPitch(80.0f);
    }

    // ─── Anti-Cheat Bypass Helpers ───────────────────────────────────────────
    private void updatePing() {
        long now = System.currentTimeMillis();
        if (now - lastPingUpdate > 1000) {
            if (mc.getNetworkHandler() != null && mc.player != null) {
                var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
                if (entry != null) {
                    cachedPing = entry.getLatency();
                }
            }
            lastPingUpdate = now;
        }
    }

    private long getAdjustedDelay(int baseDelay) {
        float pingMs = Math.max(cachedPing, 0);
        long delay = (long) (baseDelay * latencyBuffer.getValue() + pingMs * 0.2);

        if (randomizeTimings.getValue() && timingVariance.getValue() > 0) {
            double variance = timingVariance.getValue() / 100.0;
            long randomOffset = (long) (delay * variance * (Math.random() * 2 - 1));
            delay += randomOffset;
        }

        return Math.max(0, delay);
    }

    // ─── Utility ─────────────────────────────────────────────────────────────
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────
    @Override
    public void onEnable() {
        chainCount = 0;
        lastAttackTime = 0;
        lastHitTime = 0;
        currentTarget = null;
        lastTarget = null;
        originalSlot = -1;
        currentState = ChainState.IDLE;
        stateTimer.reset();
        chainTimer.reset();
        targetSwitchTimer.reset();
    }

    @Override
    public void onDisable() {
        restoreRotation();
        if (originalSlot != -1) {
            mc.player.getInventory().selectedSlot = originalSlot;
        }
        chainCount = 0;
        currentTarget = null;
        currentState = ChainState.IDLE;
    }

    @Override
    public int getKey() {
        return -1;
    }

    @Override
    public void setKey(int key) {
    }
}
