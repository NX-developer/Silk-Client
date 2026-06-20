package cc.silk.module.modules.movement;

import cc.silk.event.impl.player.TickEvent;
import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.setting.ModeSetting;
import cc.silk.module.setting.NumberSetting;
import meteordevelopment.orbit.EventHandler;

public final class Speed extends Module {
    private static final ModeSetting mode = new ModeSetting("Mode", "Strafe", "Strafe", "Vanilla", "Bhop");
    private static final NumberSetting speed = new NumberSetting("Speed", 0.1, 5.0, 1.5, 0.1);
    private static final NumberSetting timerSpeed = new NumberSetting("Timer", 0.8, 2.0, 1.08, 0.01);
    private static final NumberSetting jumpHeight = new NumberSetting("Jump Height", 0.1, 1.0, 0.42, 0.01);
    private boolean jumped = false;

    public Speed() {
        super("Speed", "Increases movement speed", -1, Category.MOVEMENT);
        addSettings(mode, speed, timerSpeed, jumpHeight);
    }

    @EventHandler
    private void onTickEvent(TickEvent event) {
        if (isNull()) return;
        if (mc.player == null || mc.world == null) return;
        if (!mc.player.isOnGround()) {
            jumped = false;
            return;
        }

        if (mode.isMode("Strafe")) {
            handleStrafe();
        } else if (mode.isMode("Vanilla")) {
            handleVanilla();
        } else if (mode.isMode("Bhop")) {
            handleBhop();
        }
    }

    private void handleStrafe() {
        float yaw = mc.player.getYaw();
        double forward = 1.0;
        if (mc.options.sneakKey.isPressed()) forward = -0.5;
        else if (mc.options.forwardKey.isPressed() && mc.options.backKey.isPressed()) forward = 0.0;
        else if (mc.options.backKey.isPressed()) forward = -0.5;

        double cos = Math.cos(Math.toRadians(yaw + 90.0f));
        double sin = Math.sin(Math.toRadians(yaw + 90.0f));
        double motionX = mc.player.getVelocity().x;
        double motionZ = mc.player.getVelocity().z;

        mc.player.setVelocity(
            motionX * 0.1 + forward * cos * speed.getValue(),
            mc.player.getVelocity().y,
            motionZ * 0.1 + forward * sin * speed.getValue()
        );

        mc.player.setMovementSpeed((float) (speed.getValue() * 0.1));

        if (mc.player.isOnGround() && !jumped) {
            mc.player.jump();
            mc.player.setVelocity(
                mc.player.getVelocity().x,
                jumpHeight.getValue(),
                mc.player.getVelocity().z
            );
            jumped = true;
        }
    }

    private void handleVanilla() {
        mc.player.setMovementSpeed((float) (speed.getValue() * 0.1));
        mc.options.timerKey.setPressed(timerSpeed.getValue() > 1.0);
    }

    private void handleBhop() {
        if (mc.player.isOnGround()) {
            mc.player.jump();
            mc.player.setVelocity(
                mc.player.getVelocity().x,
                jumpHeight.getValue(),
                mc.player.getVelocity().z
            );
        }

        float yaw = mc.player.getYaw();
        double cos = Math.cos(Math.toRadians(yaw + 90.0f));
        double sin = Math.sin(Math.toRadians(yaw + 90.0f));

        mc.player.setVelocity(
            cos * speed.getValue() * 0.2,
            mc.player.getVelocity().y,
            sin * speed.getValue() * 0.2
        );
    }

    @Override
    public void onEnable() {
        super.onEnable();
        jumped = false;
    }

    @Override
    public void onDisable() {
        super.onDisable();
        mc.player.setMovementSpeed(0.1f);
    }
}
