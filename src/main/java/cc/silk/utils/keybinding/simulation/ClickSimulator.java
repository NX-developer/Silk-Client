package cc.silk.utils.keybinding.simulation;

import lombok.experimental.UtilityClass;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static cc.silk.SilkClient.shouldUseMouseEvent;

@UtilityClass
public final class ClickSimulator {
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Silk-ClickSimulator");
        t.setDaemon(true);
        return t;
    });

    public static void leftClick() {
        if (shouldUseMouseEvent) {
            User32.INSTANCE.mouse_event(User32.MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0);
            User32.INSTANCE.mouse_event(User32.MOUSEEVENTF_LEFTUP, 0, 0, 0, 0);
        } else {
            MinecraftClient mc = MinecraftClient.getInstance();
            KeyBinding attack = mc.options.attackKey;

            InputUtil.Key key = attack.getDefaultKey();

            KeyBinding.setKeyPressed(key, true);
            KeyBinding.onKeyPressed(key);

            EXECUTOR.schedule(() -> KeyBinding.setKeyPressed(key, false), 30, TimeUnit.MILLISECONDS);

        }
    }
}