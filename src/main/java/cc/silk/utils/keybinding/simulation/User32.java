package cc.silk.utils.keybinding.simulation;

import com.sun.jna.Library;
import com.sun.jna.Native;
import org.lwjgl.glfw.GLFW;

public interface User32 extends Library {
    User32 INSTANCE = isWindows() ? Native.load("user32", User32.class) : null;
    int MOUSEEVENTF_LEFTDOWN = 0x0002;
    int MOUSEEVENTF_LEFTUP = 0x0004;

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);
}
