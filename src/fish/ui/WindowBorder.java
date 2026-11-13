package fish.ui;

import fish.NativeLibrary;
import fish.model.ColorRole;

/** Applies native 1-physical-pixel Windows 11 DWM border colors without WNDPROC subclassing. */
public final class WindowBorder {
    private static Boolean nativeAvailable;
    private static boolean lastWorkspaceOutline = true;

    private WindowBorder() {}

    public static boolean sync(boolean workspaceOutline) {
        lastWorkspaceOutline = workspaceOutline;
        if (!isWindows11OrNewer()) return false;
        try {
            if (nativeAvailable == null) {
                NativeLibrary.load();
                nativeAvailable = Boolean.TRUE;
            }
            if (!nativeAvailable) return false;
            return syncProcessWindows0(workspaceOutline, Ui.color(ColorRole.DIVIDER).getRGB());
        } catch (Throwable ignored) {
            nativeAvailable = Boolean.FALSE;
            return false;
        }
    }

    public static boolean refresh() {
        return sync(lastWorkspaceOutline);
    }

    public static boolean isWindows11() {
        String os = System.getProperty("os.name", "");
        return os.startsWith("Windows 11") || os.startsWith("Windows 12") || os.startsWith("Windows Server 2025");
    }

    private static boolean isWindows11OrNewer() {
        String os = System.getProperty("os.name", "");
        return os.regionMatches(true, 0, "Windows", 0, 7);
    }

    private static native boolean syncProcessWindows0(boolean workspaceOutline, int dividerRgb);
}
