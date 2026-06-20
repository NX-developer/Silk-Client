package cc.silk.module.modules.render;

import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.setting.NumberSetting;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;

public final class NightVision extends Module {
    private static final NumberSetting brightness = new NumberSetting("Brightness", 1.0, 15.0, 5.0, 0.5);
    private Double previousGamma = null;

    public NightVision() {
        super("Night Vision", "See in the dark like it's day", Category.RENDER);
        addSettings(brightness);
    }

    @Override
    public void onEnable() {
        super.onEnable();
        if (mc == null || mc.options == null) return;

        previousGamma = mc.options.getGamma().getValue();
        mc.options.getGamma().setValue(brightness.getValue());

        if (mc.player != null) {
            mc.player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.NIGHT_VISION,
                200,
                1,
                false,
                false,
                false
            ));
        }
    }

    @Override
    public void onDisable() {
        super.onDisable();
        if (mc == null || mc.options == null) return;

        if (previousGamma != null) {
            mc.options.getGamma().setValue(previousGamma);
            previousGamma = null;
        }

        if (mc.player != null) {
            mc.player.removeStatusEffect(StatusEffects.NIGHT_VISION);
        }
    }
}
