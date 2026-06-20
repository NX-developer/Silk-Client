package cc.silk.module.modules.combat;

import cc.silk.event.impl.player.TickEvent;
import cc.silk.mixin.MinecraftClientAccessor;
import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.modules.misc.Teams;
import cc.silk.module.setting.BooleanSetting;
import cc.silk.module.setting.NumberSetting;
import cc.silk.utils.friend.FriendManager;
import cc.silk.utils.math.TimerUtil;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.Fluids;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.SwordItem;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;

/**
 * Legit Killaura - Anti-cheat and admin-proof killaura module.
 *
 * Features:
 * - Advanced Fluid & Raytrace: Ignores water, blocks on solid blocks
 * - Lava & Hitbox Analysis: Targets visible body parts above lava
 * - Smooth Aiming: Human-like rotation interpolation
 * - Legit Timing: Randomized attack delays with CPS variation
 */
public final class LegitKillaura extends Module {

    // ─── Settings ────────────────────────────────────────────────────────────
    // Targeting
    private final NumberSetting range = new NumberSetting("Range", 1.0, 6.0, 4.5, 0.1);
    private final NumberSetting fov = new NumberSetting("FOV", 30, 360, 180, 5);
    private final BooleanSetting targetPlayers = new BooleanSetting("Target Players", true);
    private final BooleanSetting targetMobs = new BooleanSetting("Target Mobs", false);
    private final BooleanSetting weaponsOnly = new BooleanSetting("Weapons Only", true);

    // Rotation
    private final NumberSetting smoothSpeed = new NumberSetting("Smooth Speed", 1, 20, 10, 1);
    private final NumberSetting rotationRandomness = new NumberSetting("Rotation Randomness", 0, 5, 1.5, 0.5);
    private final NumberSetting aimFOV = new NumberSetting("Aim FOV", 1, 30, 10, 1);

    // Timing
    private final NumberSetting minCPS = new NumberSetting("Min CPS", 1, 20, 8, 1);
    private final NumberSetting maxCPS = new NumberSetting("Max CPS", 1, 20, 12, 1);
    private final NumberSetting reactionTime = new NumberSetting("Reaction Time (ms)", 0, 200, 50, 10);
    private final NumberSetting randomDelay = new NumberSetting("Random Delay (ms)", 0, 100, 30, 5);

    // Raytrace
    private final BooleanSetting ignoreWater = new BooleanSetting("Ignore Water", true);
    private final BooleanSetting blockThroughWalls = new BooleanSetting("Block Through Walls", true);
    private final BooleanSetting lavaCheck = new BooleanSetting("Lava Check", true);

    // Advanced
    private final BooleanSetting smartTargeting = new BooleanSetting("Smart Targeting", true);
    private final NumberSetting switchDelay = new NumberSetting("Switch Delay (ms)", 100, 1000, 300, 50);

    // ─── State ───────────────────────────────────────────────────────────────
    private Entity currentTarget = null;
    private final TimerUtil attackTimer = new TimerUtil();
    private final TimerUtil switchTimer = new TimerUtil();
    private final TimerUtil reactionTimer = new TimerUtil();

    private float targetYaw = 0;
    private float targetPitch = 0;
    private boolean isRotating = false;

    // Timing state
    private long nextAttackTime = 0;
    private long lastAttackTime = 0;
    private boolean waitingForReaction = false;
    private boolean onTarget = false;

    // Cached values
    private int cachedPing = 0;
    private long lastPingUpdate = 0;

    public LegitKillaura() {
        super("Legit Killaura", "Human-like killaura with fluid-aware raytrace", -1, Category.COMBAT);
        this.addSettings(
                range, fov, targetPlayers, targetMobs, weaponsOnly,
                smoothSpeed, rotationRandomness, aimFOV,
                minCPS, maxCPS, reactionTime, randomDelay,
                ignoreWater, blockThroughWalls, lavaCheck,
                smartTargeting, switchDelay
        );
    }

    // ─── Event Handler ───────────────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent event) {
        if (isNull() || mc.currentScreen != null) return;

        updatePing();
        updateCooldown();

        if (weaponsOnly.getValue() && !isHoldingWeapon()) {
            clearTarget();
            return;
        }

        // Find best target
        Entity newTarget = findBestTarget();

        // Handle target switching
        if (newTarget != currentTarget) {
            if (newTarget != null && switchTimer.hasElapsedTime(switchDelay.getValueInt())) {
                currentTarget = newTarget;
                switchTimer.reset();
            } else if (newTarget == null) {
                clearTarget();
            }
        }

        if (currentTarget == null || !isValidTarget(currentTarget)) {
            clearTarget();
            return;
        }

        // Check line of sight
        if (!hasLineOfSight(currentTarget)) {
            return;
        }

        // Calculate rotation to target
        Vec3d aimPoint = getBestAimPoint(currentTarget);
        if (aimPoint == null) {
            return;
        }

        // Apply smooth rotation
        applySmoothRotation(aimPoint);

        // Check if on target
        onTarget = isOnTarget(currentTarget);

        // Handle attack timing
        handleAttackTiming();
    }

    // ─── Target Finding ──────────────────────────────────────────────────────
    private Entity findBestTarget() {
        Entity bestTarget = null;
        double bestScore = Double.MIN_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!isValidTarget(entity)) continue;

            double distance = mc.player.getPos().distanceTo(entity.getPos());
            if (distance > range.getValue()) continue;

            // Skip entities fully submerged in lava
            if (lavaCheck.getValue() && isEntityFullyInLava(entity)) continue;

            // Check if target is in FOV
            float[] rotation = calculateRotation(entity.getPos().add(0, entity.getStandingEyeHeight() * 0.5, 0));
            double fovDistance = getFOVDistance(rotation[0], rotation[1]);
            if (fovDistance > fov.getValue() / 2.0) continue;

            // Check line of sight
            if (!hasLineOfSight(entity)) continue;

            // Calculate score (distance + angle)
            double score = 100 - distance * 5 - fovDistance * 0.5;

            // Bonus for current target (stickiness)
            if (entity == currentTarget && smartTargeting.getValue()) {
                score += 20;
            }

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

    // ─── Line of Sight (Fluid-Aware Raytrace) ───────────────────────────────
    private boolean hasLineOfSight(Entity target) {
        if (mc.player == null || mc.world == null) return false;

        Vec3d eyePos = mc.player.getEyePos();
        Vec3d targetPos = getBestAimPoint(target);
        if (targetPos == null) return false;

        // Always use FluidHandling.NONE - we handle water/lava separately
        BlockHitResult result = mc.world.raycast(new RaycastContext(
                eyePos,
                targetPos,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                mc.player
        ));

        if (result.getType() == HitResult.Type.MISS) {
            return true;
        }

        BlockPos hitPos = result.getBlockPos();
        BlockState hitState = mc.world.getBlockState(hitPos);

        // Water is transparent - always ignore for line of sight
        if (hitState.getFluidState().isOf(Fluids.WATER)) {
            return true;
        }

        // Lava is opaque - always blocks line of sight
        if (hitState.getFluidState().isOf(Fluids.LAVA)) {
            return false;
        }

        // Check for transparent blocks (glass, ice, leaves, etc.)
        if (isTransparentBlock(hitState)) {
            return true;
        }

        // Solid opaque block - respect the setting
        return blockThroughWalls.getValue();
    }

    private boolean isTransparentBlock(BlockState state) {
        return state.isOf(Blocks.GLASS) || state.isOf(Blocks.GLASS_PANE)
                || state.isOf(Blocks.ICE) || state.isOf(Blocks.PACKED_ICE)
                || state.isOf(Blocks.BARRIER) || state.isOf(Blocks.LIGHT)
                || state.isOf(Blocks.SEA_LANTERN) || state.isOf(Blocks.GLOWSTONE)
                || state.isOf(Blocks.OAK_LEAVES) || state.isOf(Blocks.SPRUCE_LEAVES)
                || state.isOf(Blocks.BIRCH_LEAVES) || state.isOf(Blocks.JUNGLE_LEAVES)
                || state.isOf(Blocks.ACACIA_LEAVES) || state.isOf(Blocks.DARK_OAK_LEAVES)
                || state.isOf(Blocks.AZALEA_LEAVES) || state.isOf(Blocks.FLOWERING_AZALEA_LEAVES);
    }

    // ─── Lava & Hitbox Analysis ──────────────────────────────────────────────
    private Vec3d getBestAimPoint(Entity target) {
        if (target == null || mc.world == null || mc.player == null) return null;

        // If target is fully submerged in lava, do not attack
        if (lavaCheck.getValue() && isEntityFullyInLava(target)) {
            return null;
        }

        // If target is partially in lava, scan only visible body parts
        if (lavaCheck.getValue() && isEntityInLava(target)) {
            return getLavaVisiblePoint(target);
        }

        // Standard body part scan
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d[] bodyParts = getBodyPartPositions(target);

        Vec3d bestPoint = null;
        double bestScore = Double.MIN_VALUE;

        for (Vec3d point : bodyParts) {
            if (isPointVisible(point) && !isPointInLava(point)) {
                double distance = eyePos.distanceTo(point);
                double score = 100 - distance * 10;

                // Prefer head/neck shots
                double headDist = target.getPos().add(0, target.getStandingEyeHeight(), 0).distanceTo(point);
                if (headDist < 0.15) score += 15;
                else {
                    double chestDist = target.getPos().add(0, target.getStandingEyeHeight() * 0.7, 0).distanceTo(point);
                    if (chestDist < 0.15) score += 10;
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestPoint = point;
                }
            }
        }

        return bestPoint;
    }

    private Vec3d[] getBodyPartPositions(Entity target) {
        Vec3d pos = target.getPos();
        double h = target.getStandingEyeHeight();

        return new Vec3d[]{
                pos.add(0, h, 0),                      // Head
                pos.add(0, h * 0.85, 0),               // Neck
                pos.add(0, h * 0.7, 0),                // Upper chest
                pos.add(0, h * 0.5, 0),                // Lower chest / torso
                pos.add(0, h * 0.3, 0),                // Waist
                pos.add(0, h * 0.15, 0),               // Upper legs
                pos.add(0, 0.1, 0),                    // Feet
                pos.add(0.25, h * 0.5, 0),             // Right arm
                pos.add(-0.25, h * 0.5, 0),            // Left arm
                pos.add(0.15, h * 0.1, 0.15),          // Right leg
                pos.add(-0.15, h * 0.1, -0.15),        // Left leg
                pos.add(0.15, h * 0.1, -0.15),         // Right leg back
                pos.add(-0.15, h * 0.1, 0.15),         // Left leg back
        };
    }

    private boolean isEntityFullyInLava(Entity entity) {
        if (mc.world == null) return false;

        Box box = entity.getBoundingBox();
        int totalSamples = 0;
        int lavaSamples = 0;

        for (double x = box.minX; x <= box.maxX; x += 0.25) {
            for (double z = box.minZ; z <= box.maxZ; z += 0.25) {
                for (double y = box.minY; y <= box.maxY; y += 0.25) {
                    totalSamples++;
                    BlockPos pos = BlockPos.ofFloored(x, y, z);
                    if (mc.world.getBlockState(pos).getFluidState().isOf(Fluids.LAVA)) {
                        lavaSamples++;
                    }
                }
            }
        }

        return totalSamples > 0 && (double) lavaSamples / totalSamples >= 0.9;
    }

    private boolean isEntityInLava(Entity entity) {
        if (mc.world == null) return false;

        Box box = entity.getBoundingBox();
        for (double x = box.minX; x <= box.maxX; x += 0.25) {
            for (double z = box.minZ; z <= box.maxZ; z += 0.25) {
                for (double y = box.minY; y <= box.maxY; y += 0.25) {
                    BlockPos pos = BlockPos.ofFloored(x, y, z);
                    if (mc.world.getBlockState(pos).getFluidState().isOf(Fluids.LAVA)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Vec3d getLavaVisiblePoint(Entity target) {
        if (mc.world == null || mc.player == null) return null;

        Vec3d[] bodyParts = getBodyPartPositions(target);

        // Prioritize head, then neck, then upper chest, etc.
        for (Vec3d point : bodyParts) {
            if (!isPointInLava(point) && isPointVisible(point)) {
                return point;
            }
        }

        // Fallback: scan bounding box center from top to bottom
        Box targetBox = target.getBoundingBox();
        double centerX = (targetBox.minX + targetBox.maxX) / 2;
        double centerZ = (targetBox.minZ + targetBox.maxZ) / 2;

        for (double y = targetBox.maxY; y >= targetBox.minY; y -= 0.1) {
            Vec3d testPoint = new Vec3d(centerX, y, centerZ);
            if (!isPointInLava(testPoint) && isPointVisible(testPoint)) {
                return testPoint;
            }
        }

        return null;
    }

    private boolean isPointInLava(Vec3d point) {
        if (mc.world == null) return false;
        BlockPos pos = BlockPos.ofFloored(point);
        return mc.world.getBlockState(pos).getFluidState().isOf(Fluids.LAVA);
    }

    private boolean isPointVisible(Vec3d point) {
        if (mc.player == null || mc.world == null) return false;

        Vec3d eyePos = mc.player.getEyePos();

        BlockHitResult result = mc.world.raycast(new RaycastContext(
                eyePos,
                point,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                mc.player
        ));

        if (result.getType() == HitResult.Type.MISS) return true;

        BlockPos hitPos = result.getBlockPos();
        BlockState hitState = mc.world.getBlockState(hitPos);

        // Water is transparent - ignore
        if (hitState.getFluidState().isOf(Fluids.WATER)) return true;

        // Lava blocks visibility - opaque
        if (hitState.getFluidState().isOf(Fluids.LAVA)) return false;

        // Transparent blocks (glass, etc.) - allow visibility
        if (isTransparentBlock(hitState)) return true;

        // Solid block - check if hit point is very close to target point
        double hitDistance = eyePos.distanceTo(result.getPos());
        double targetDistance = eyePos.distanceTo(point);
        return hitDistance >= targetDistance - 0.5;
    }

    // ─── Rotation ────────────────────────────────────────────────────────────
    private float[] calculateRotation(Vec3d target) {
        Vec3d diff = target.subtract(mc.player.getEyePos());
        double distance = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float yaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(diff.y, distance));
        return new float[]{MathHelper.wrapDegrees(yaw), MathHelper.clamp(pitch, -89.0f, 89.0f)};
    }

    private double getFOVDistance(float targetYaw, float targetPitch) {
        float yawDiff = MathHelper.wrapDegrees(targetYaw - mc.player.getYaw());
        float pitchDiff = targetPitch - mc.player.getPitch();
        return Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
    }

    private void applySmoothRotation(Vec3d targetPos) {
        if (mc.player == null) return;

        float[] targetRot = calculateRotation(targetPos);
        targetYaw = targetRot[0];
        targetPitch = targetRot[1];

        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();

        float yawDiff = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;

        double distance = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);

        // Only rotate if target is within aim FOV
        if (distance > aimFOV.getValue()) {
            isRotating = false;
            return;
        }

        isRotating = true;

        // Add randomness to rotation
        float randomYawOffset = (float) (Math.random() * rotationRandomness.getValue() - rotationRandomness.getValue() / 2);
        float randomPitchOffset = (float) (Math.random() * rotationRandomness.getValue() - rotationRandomness.getValue() / 2);

        // Calculate smooth speed based on distance
        float speedFactor = smoothSpeed.getValueFloat() / 100.0f;
        float distanceFactor = (float) Math.min(distance / 30.0, 1.0);

        // Apply easing
        float eased = easeOutQuad(distanceFactor);

        // Lerp rotation
        float lerpFactor = eased * speedFactor;
        float newYaw = MathHelper.lerp(lerpFactor, currentYaw, currentYaw + yawDiff + randomYawOffset);
        float newPitch = MathHelper.lerp(lerpFactor, currentPitch, targetPitch + randomPitchOffset);

        mc.player.setYaw(newYaw);
        mc.player.setPitch(MathHelper.clamp(newPitch, -89.0f, 89.0f));
    }

    private float easeOutQuad(float t) {
        return t * (2 - t);
    }

    // ─── Attack Timing ───────────────────────────────────────────────────────
    private void handleAttackTiming() {
        if (currentTarget == null || !onTarget) {
            waitingForReaction = false;
            return;
        }

        // Handle reaction time
        if (!waitingForReaction) {
            waitingForReaction = true;
            reactionTimer.reset();
            return;
        }

        long reactionDelay = reactionTime.getValueInt();
        if (!reactionTimer.hasElapsedTime(reactionDelay)) {
            return;
        }

        // Check cooldown
        if (!attackTimer.hasElapsedTime(nextAttackTime)) {
            return;
        }

        // Perform attack
        performAttack();

        // Schedule next attack with randomized delay
        scheduleNextAttack();

        waitingForReaction = false;
    }

    private void performAttack() {
        if (mc.player == null || currentTarget == null) return;

        // Final line of sight check before attack
        if (!hasLineOfSight(currentTarget)) return;

        // Final lava checks
        if (lavaCheck.getValue()) {
            // Do not attack if fully submerged in lava
            if (isEntityFullyInLava(currentTarget)) return;
            // If partially in lava, only attack if we have a valid visible point
            if (isEntityInLava(currentTarget)) {
                Vec3d visiblePoint = getLavaVisiblePoint(currentTarget);
                if (visiblePoint == null) return;
            }
        }

        // Perform the attack
        ((MinecraftClientAccessor) mc).invokeDoAttack();
        lastAttackTime = System.currentTimeMillis();
        attackTimer.reset();
    }

    private void scheduleNextAttack() {
        // Random CPS between min and max
        double cps = minCPS.getValue() + Math.random() * (maxCPS.getValue() - minCPS.getValue());
        long baseDelay = (long) (1000.0 / cps);

        // Add random variance
        long variance = randomDelay.getValueInt();
        long randomOffset = (long) (Math.random() * variance * 2 - variance);

        nextAttackTime = Math.max(50, baseDelay + randomOffset);
    }

    private void updateCooldown() {
        // Update attack cooldown indicator
        if (mc.player != null) {
            float cooldown = mc.player.getAttackCooldownProgress(0.0f);
            // Could be used for visual feedback
        }
    }

    // ─── Utility ─────────────────────────────────────────────────────────────
    private boolean isHoldingWeapon() {
        if (mc.player == null) return false;
        Item heldItem = mc.player.getMainHandStack().getItem();
        return heldItem instanceof SwordItem || heldItem instanceof AxeItem;
    }

    private void clearTarget() {
        currentTarget = null;
        isRotating = false;
        waitingForReaction = false;
        onTarget = false;
    }

    private boolean isOnTarget(Entity target) {
        if (mc.player == null || target == null) return false;

        // Check if crosshair is on target
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
            Entity hitEntity = ((net.minecraft.util.hit.EntityHitResult) mc.crosshairTarget).getEntity();
            if (hitEntity == target) {
                // Check if rotation is close enough
                Vec3d targetPos = target.getPos().add(0, target.getStandingEyeHeight() * 0.5, 0);
                float[] targetRot = calculateRotation(targetPos);

                float yawDiff = Math.abs(MathHelper.wrapDegrees(targetRot[0] - mc.player.getYaw()));
                float pitchDiff = Math.abs(targetRot[1] - mc.player.getPitch());

                return yawDiff < 5 && pitchDiff < 5;
            }
        }

        return false;
    }

    // ─── Anti-Cheat Bypass ───────────────────────────────────────────────────
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

    // ─── Lifecycle ───────────────────────────────────────────────────────────
    @Override
    public void onEnable() {
        super.onEnable();
        clearTarget();
        attackTimer.reset();
        switchTimer.reset();
        reactionTimer.reset();
    }

    @Override
    public void onDisable() {
        super.onDisable();
        clearTarget();
    }

    @Override
    public int getKey() {
        return -1;
    }

    @Override
    public void setKey(int key) {
    }
}
