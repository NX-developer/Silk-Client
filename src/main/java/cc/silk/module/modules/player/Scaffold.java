package cc.silk.module.modules.player;

import cc.silk.event.impl.player.TickEvent;
import cc.silk.mixin.MinecraftClientAccessor;
import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.setting.BooleanSetting;
import cc.silk.module.setting.ModeSetting;
import cc.silk.module.setting.NumberSetting;
import cc.silk.utils.math.TimerUtil;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

/**
 * Scaffold module with Legit (SafeWalk/Eagle) mode.
 *
 * Features:
 * - Edge Detection: Auto-sneak at block edges to prevent falling
 * - Silent Rotation: Legit-looking rotation for block placement
 * - Block Placement: Automatic block placement under player
 * - Anti-Cheat Bypass: Randomized delays, human-like timing
 */
public final class Scaffold extends Module {

    // ─── Settings ────────────────────────────────────────────────────────────
    private final ModeSetting mode = new ModeSetting("Mode", "Legit", "Legit", "Tower", "Fast");

    // Legit Mode Settings
    private final NumberSetting edgeDistance = new NumberSetting("Edge Distance", 0.1, 0.5, 0.25, 0.05);
    private final NumberSetting sneakDelay = new NumberSetting("Sneak Delay (ms)", 0, 100, 30, 5);
    private final NumberSetting unsneakDelay = new NumberSetting("Unsneak Delay (ms)", 0, 100, 50, 5);
    private final BooleanSetting smartRotation = new BooleanSetting("Smart Rotation", true);
    private final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 1, 20, 12, 1);

    // Tower Mode Settings
    private final NumberSetting towerSpeed = new NumberSetting("Tower Speed", 1, 20, 10, 1);
    private final BooleanSetting towerSneak = new BooleanSetting("Tower Sneak", true);

    // Common Settings
    private final BooleanSetting autoSwitch = new BooleanSetting("Auto Switch", true);
    private final BooleanSetting swingArm = new BooleanSetting("Swing Arm", true);
    private final BooleanSetting silentSwitch = new BooleanSetting("Silent Switch", true);
    private final BooleanSetting turnOffOnNoBlocks = new BooleanSetting("Turn Off No Blocks", true);

    // Anti-Cheat Bypass
    private final BooleanSetting randomizeTimings = new BooleanSetting("Randomize Timings", true);
    private final NumberSetting timingVariance = new NumberSetting("Timing Variance (%)", 0, 50, 15, 5);
    private final NumberSetting latencyBuffer = new NumberSetting("Latency Buffer", 0.5, 3.0, 1.0, 0.1);

    // ─── State ───────────────────────────────────────────────────────────────
    private enum ScaffoldState {
        IDLE,
        SNEAKING,
        PLACING,
        UNSNEAKING,
        ROTATING
    }

    private ScaffoldState currentState = ScaffoldState.IDLE;
    private final TimerUtil stateTimer = new TimerUtil();
    private final TimerUtil placeTimer = new TimerUtil();

    private int originalSlot = -1;
    private float savedYaw = 0;
    private float savedPitch = 0;
    private boolean rotationSaved = false;
    private boolean isSneaking = false;
    private boolean hasPlaced = false;

    // Edge detection
    private double lastPlayerX = 0;
    private double lastPlayerZ = 0;
    private boolean wasOnEdge = false;

    // Timing
    private int cachedPing = 0;
    private long lastPingUpdate = 0;

    // Block placement
    private BlockPos lastPlacePos = null;
    private int placeCooldown = 0;

    public Scaffold() {
        super("Scaffold", "Automatically places blocks under you while walking", -1, Category.PLAYER);
        this.addSettings(
                mode,
                edgeDistance, sneakDelay, unsneakDelay, smartRotation, rotationSpeed,
                towerSpeed, towerSneak,
                autoSwitch, swingArm, silentSwitch, turnOffOnNoBlocks,
                randomizeTimings, timingVariance, latencyBuffer
        );
    }

    // ─── Event Handler ───────────────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent event) {
        if (isNull() || mc.currentScreen != null) return;

        updatePing();

        if (turnOffOnNoBlocks.getValue() && !hasBlocks()) {
            setEnabled(false);
            return;
        }

        switch (mode.getMode()) {
            case "Legit" -> handleLegitMode();
            case "Tower" -> handleTowerMode();
            case "Fast" -> handleFastMode();
        }
    }

    // ─── Legit Mode (SafeWalk/Eagle) ─────────────────────────────────────────
    private void handleLegitMode() {
        if (!mc.player.isOnGround()) {
            if (isSneaking) {
                unsneak();
            }
            return;
        }

        boolean onEdge = isOnBlockEdge();
        double currentX = mc.player.getX();
        double currentZ = mc.player.getZ();
        boolean isMoving = mc.player.getVelocity().x != 0 || mc.player.getVelocity().z != 0;

        if (onEdge && isMoving) {
            switch (currentState) {
                case IDLE -> {
                    saveRotation();
                    currentState = ScaffoldState.ROTATING;
                    stateTimer.reset();
                }
                case ROTATING -> {
                    if (smartRotation.getValue()) {
                        performSmoothRotation();
                    } else {
                        setPlacementRotation();
                        currentState = ScaffoldState.PLACING;
                        stateTimer.reset();
                    }
                }
                case PLACING -> {
                    long adjustedDelay = getAdjustedDelay(20);
                    if (stateTimer.hasElapsedTime(adjustedDelay)) {
                        placeBlock();
                        currentState = ScaffoldState.SNEAKING;
                        stateTimer.reset();
                    }
                }
                case SNEAKING -> {
                    long sneakDelayMs = getAdjustedDelay(sneakDelay.getValueInt());
                    if (stateTimer.hasElapsedTime(sneakDelayMs)) {
                        sneak();
                        currentState = ScaffoldState.UNSNEAKING;
                        stateTimer.reset();
                    }
                }
                case UNSNEAKING -> {
                    long unsneakDelayMs = getAdjustedDelay(unsneakDelay.getValueInt());
                    if (stateTimer.hasElapsedTime(unsneakDelayMs)) {
                        unsneak();
                        restoreRotation();
                        currentState = ScaffoldState.IDLE;
                        hasPlaced = false;
                    }
                }
            }
        } else if (!onEdge && isSneaking) {
            unsneak();
            currentState = ScaffoldState.IDLE;
            restoreRotation();
        }

        lastPlayerX = currentX;
        lastPlayerZ = currentZ;
        wasOnEdge = onEdge;
    }

    // ─── Tower Mode ──────────────────────────────────────────────────────────
    private void handleTowerMode() {
        if (!mc.options.jumpKey.isPressed()) return;
        if (mc.player.isOnGround()) {
            hasPlaced = false;
        }

        if (!hasPlaced) {
            if (towerSneak.getValue() && !isSneaking) {
                sneak();
            }

            saveRotation();
            mc.player.setPitch(90.0f);

            long towerDelay = getAdjustedDelay(500 / towerSpeed.getValueInt());
            if (placeTimer.hasElapsedTime(towerDelay)) {
                placeBlock();
                hasPlaced = true;
                placeTimer.reset();
            }

            restoreRotation();
        }
    }

    // ─── Fast Mode ───────────────────────────────────────────────────────────
    private void handleFastMode() {
        if (!mc.player.isOnGround()) return;

        long fastDelay = getAdjustedDelay(50);
        if (!placeTimer.hasElapsedTime(fastDelay)) return;

        saveRotation();
        mc.player.setPitch(89.0f);
        placeBlock();
        restoreRotation();
        placeTimer.reset();
    }

    // ─── Edge Detection ──────────────────────────────────────────────────────
    private boolean isOnBlockEdge() {
        if (mc.player == null || mc.world == null) return false;

        Vec3d pos = mc.player.getPos();
        double edgeDist = edgeDistance.getValue();

        // Check if player is at the edge of a block
        double fractionalX = pos.x - Math.floor(pos.x);
        double fractionalZ = pos.z - Math.floor(pos.z);

        boolean nearEdgeX = fractionalX < edgeDist || fractionalX > (1.0 - edgeDist);
        boolean nearEdgeZ = fractionalZ < edgeDist || fractionalZ > (1.0 - edgeDist);

        // Check if there's air below the edge
        if (nearEdgeX || nearEdgeZ) {
            BlockPos below = BlockPos.ofFloored(pos.x, pos.y - 0.5, pos.z);
            BlockPos belowOffset = getOffsetPosition(pos, fractionalX, fractionalZ, edgeDist);

            if (mc.world.getBlockState(below).isAir() || mc.world.getBlockState(belowOffset).isAir()) {
                return true;
            }
        }

        return false;
    }

    private BlockPos getOffsetPosition(Vec3d pos, double fractionalX, double fractionalZ, double edgeDist) {
        double offsetX = 0;
        double offsetZ = 0;

        if (fractionalX < edgeDist) offsetX = -1;
        else if (fractionalX > (1.0 - edgeDist)) offsetX = 1;

        if (fractionalZ < edgeDist) offsetZ = -1;
        else if (fractionalZ > (1.0 - edgeDist)) offsetZ = 1;

        return BlockPos.ofFloored(pos.x + offsetX * 0.5, pos.y - 0.5, pos.z + offsetZ * 0.5);
    }

    // ─── Block Placement ─────────────────────────────────────────────────────
    private void placeBlock() {
        if (mc.player == null || mc.world == null) return;

        if (autoSwitch.getValue() && !hasBlockInHand()) {
            if (!switchToBlock()) return;
        }

        if (!hasBlockInHand()) return;

        BlockHitResult hitResult = findPlacementTarget();
        if (hitResult == null) return;

        BlockPos placePos = hitResult.getBlockPos();
        if (lastPlacePos != null && lastPlacePos.equals(placePos)) {
            return;
        }

        // Send placement packet
        if (mc.interactionManager != null) {
            ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);

            if (result.isAccepted()) {
                if (swingArm.getValue()) {
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                lastPlacePos = placePos;
                hasPlaced = true;
            }
        }
    }

    private BlockHitResult findPlacementTarget() {
        if (mc.player == null || mc.world == null) return null;

        Vec3d pos = mc.player.getPos();
        double edgeDist = edgeDistance.getValue();

        double fractionalX = pos.x - Math.floor(pos.x);
        double fractionalZ = pos.z - Math.floor(pos.z);

        // Determine placement direction based on edge proximity
        double offsetX = 0;
        double offsetZ = 0;
        Direction facing = Direction.DOWN;

        if (fractionalX < edgeDist) {
            offsetX = -0.5;
            facing = Direction.EAST;
        } else if (fractionalX > (1.0 - edgeDist)) {
            offsetX = 0.5;
            facing = Direction.WEST;
        }

        if (fractionalZ < edgeDist) {
            offsetZ = -0.5;
            facing = Direction.SOUTH;
        } else if (fractionalZ > (1.0 - edgeDist)) {
            offsetZ = 0.5;
            facing = Direction.NORTH;
        }

        // Calculate placement position
        BlockPos targetPos = BlockPos.ofFloored(pos.x + offsetX, pos.y - 1, pos.z + offsetZ);

        if (!mc.world.getBlockState(targetPos).isReplaceable()) return null;

        // Find adjacent solid block for placement support
        for (Direction dir : Direction.values()) {
            if (dir == Direction.UP) continue;

            BlockPos adjacent = targetPos.offset(dir);
            BlockState adjacentState = mc.world.getBlockState(adjacent);

            if (!adjacentState.isAir() && !adjacentState.isReplaceable()) {
                Direction placeFace = dir.getOpposite();
                Vec3d hitVec = Vec3d.ofCenter(targetPos).add(0, -0.45, 0);

                return new BlockHitResult(hitVec, placeFace, targetPos, false);
            }
        }

        return null;
    }

    // ─── Rotation ────────────────────────────────────────────────────────────
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

    private void setPlacementRotation() {
        if (mc.player == null) return;

        Vec3d pos = mc.player.getPos();
        double fractionalX = pos.x - Math.floor(pos.x);
        double fractionalZ = pos.z - Math.floor(pos.z);

        float targetPitch = 75.0f + (float) (Math.random() * 10); // 75-85 degrees down
        float targetYaw = savedYaw;

        if (fractionalX < edgeDistance.getValue()) {
            targetYaw = savedYaw + 180 + (float) (Math.random() * 20 - 10);
        } else if (fractionalX > (1.0 - edgeDistance.getValue())) {
            targetYaw = savedYaw + (float) (Math.random() * 20 - 10);
        }

        if (fractionalZ < edgeDistance.getValue()) {
            targetYaw = savedYaw + 90 + (float) (Math.random() * 20 - 10);
        } else if (fractionalZ > (1.0 - edgeDistance.getValue())) {
            targetYaw = savedYaw - 90 + (float) (Math.random() * 20 - 10);
        }

        mc.player.setYaw(targetYaw % 360);
        mc.player.setPitch(targetPitch);
    }

    private void performSmoothRotation() {
        if (mc.player == null) return;

        float targetPitch = 80.0f;
        float currentPitch = mc.player.getPitch();
        float currentYaw = mc.player.getYaw();

        float yawDiff = savedYaw - currentYaw;
        while (yawDiff > 180) yawDiff -= 360;
        while (yawDiff < -180) yawDiff += 360;

        float pitchDiff = targetPitch - currentPitch;

        float speed = rotationSpeed.getValueFloat() * 0.1f;

        if (Math.abs(yawDiff) > 1) {
            mc.player.setYaw(currentYaw + yawDiff * speed);
        }
        if (Math.abs(pitchDiff) > 1) {
            mc.player.setPitch(currentPitch + pitchDiff * speed);
        }

        if (Math.abs(yawDiff) <= 1 && Math.abs(pitchDiff) <= 1) {
            mc.player.setYaw(savedYaw);
            mc.player.setPitch(targetPitch);
            currentState = ScaffoldState.PLACING;
            stateTimer.reset();
        }
    }

    // ─── Sneak/Unsneak ──────────────────────────────────────────────────────
    private void sneak() {
        if (!isSneaking) {
            isSneaking = true;
            mc.options.sneakKey.setPressed(true);
        }
    }

    private void unsneak() {
        if (isSneaking) {
            isSneaking = false;
            mc.options.sneakKey.setPressed(false);
        }
    }

    // ─── Inventory Helpers ───────────────────────────────────────────────────
    private boolean hasBlocks() {
        if (mc.player == null) return false;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof BlockItem) return true;
        }
        return false;
    }

    private boolean hasBlockInHand() {
        if (mc.player == null) return false;
        ItemStack mainHand = mc.player.getMainHandStack();
        return mainHand.getItem() instanceof BlockItem;
    }

    private boolean switchToBlock() {
        if (mc.player == null) return false;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof BlockItem) {
                mc.player.getInventory().selectedSlot = i;
                return true;
            }
        }
        return false;
    }

    // ─── Anti-Cheat Bypass ───────────────────────────────────────────────────
    private void updatePing() {
        long now = System.currentTimeMillis();
        if (now - lastPingUpdate > 1000) {
            if (mc.getNetworkHandler() != null) {
                cachedPing = mc.getNetworkHandler().getPing();
            }
            lastPingUpdate = now;
        }
    }

    private long getAdjustedDelay(int baseDelay) {
        float pingMs = Math.max(cachedPing, 0);
        long delay = (long) (baseDelay * latencyBuffer.getValue() + pingMs * 0.15);

        if (randomizeTimings.getValue() && timingVariance.getValue() > 0) {
            double variance = timingVariance.getValue() / 100.0;
            long randomOffset = (long) (delay * variance * (Math.random() * 2 - 1));
            delay += randomOffset;
        }

        return Math.max(0, delay);
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────
    @Override
    public void onEnable() {
        currentState = ScaffoldState.IDLE;
        originalSlot = -1;
        isSneaking = false;
        hasPlaced = false;
        lastPlacePos = null;
        stateTimer.reset();
        placeTimer.reset();
    }

    @Override
    public void onDisable() {
        unsneak();
        restoreRotation();

        if (originalSlot != -1 && mc.player != null) {
            mc.player.getInventory().selectedSlot = originalSlot;
        }

        currentState = ScaffoldState.IDLE;
        isSneaking = false;
        hasPlaced = false;
    }

    @Override
    public int getKey() {
        return -1;
    }

    @Override
    public void setKey(int key) {
    }
}
