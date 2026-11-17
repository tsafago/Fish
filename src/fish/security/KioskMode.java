package fish.security;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import fish.LayoutShortcuts;
import fish.NativeLibrary;

/**
 * Machine-wide Kiosk mode stored in {@code HKLM\SOFTWARE\Fish\KioskMode} ({@code REG_DWORD}).
 * Any Windows user can read the flag; modifying it requires Administrator privileges via UAC.
 */
public final class KioskMode {
    public static final int UAC_CANCELLED = -1223;
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Boolean testOverride;
    private static volatile Predicate<Boolean> testToggleHandler;
    private static volatile BooleanSupplier testAdminAuthorizer;

    private KioskMode() {}

    public static boolean isEnabled() {
        if (Boolean.getBoolean("fish.testMode")) {
            Boolean override = testOverride;
            if (override != null) return override;
            String prop = System.getProperty("fish.test.kiosk");
            if (prop != null && !prop.isBlank()) return Boolean.parseBoolean(prop.strip());
        }
        try {
            NativeLibrary.load();
            return readRegistry0();
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static boolean isUserAdmin() {
        try {
            NativeLibrary.load();
            return isUserAdmin0();
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Direct HKLM write for an already-elevated {@code Fish.exe --admin-kiosk enable|disable} invocation. */
    public static void writeDirect(boolean enabled) throws IOException {
        if (Boolean.getBoolean("fish.testMode") && testOverride != null) {
            testOverride = enabled;
            fireChanged();
            return;
        }
        NativeLibrary.load();
        writeRegistry0(enabled);
        fireChanged();
    }

    /**
     * Enables or disables Kiosk mode, prompting for Windows Administrator approval via UAC when needed.
     *
     * @return {@code true} when changed to {@code enabled}; {@code false} if the UAC prompt was canceled.
     */
    public static boolean setEnabledWithAdmin(boolean enabled) throws IOException {
        if (Boolean.getBoolean("fish.testMode")) {
            Predicate<Boolean> handler = testToggleHandler;
            if (handler != null) {
                boolean ok = handler.test(enabled);
                if (ok) fireChanged();
                return ok;
            }
            if (testOverride != null || System.getProperty("fish.test.kiosk") != null) {
                testOverride = enabled;
                fireChanged();
                return true;
            }
        }
        NativeLibrary.load();
        if (isUserAdmin0()) {
            writeRegistry0(enabled);
            fireChanged();
            return true;
        }
        Path exe = LayoutShortcuts.executable();
        String name = exe.getFileName() == null ? "" : exe.getFileName().toString().toLowerCase(Locale.ROOT);
        int rc;
        if (name.equals("fish.exe")) {
            Path cwd = exe.getParent() == null ? Path.of(".") : exe.getParent();
            rc = runElevatedWait0(exe.toString(), "--admin-kiosk " + (enabled ? "enable" : "disable"), cwd.toString(), false);
        } else {
            String args = "add HKLM\\SOFTWARE\\Fish /v KioskMode /t REG_DWORD /d " + (enabled ? "1" : "0") + " /f /reg:64";
            rc = runElevatedWait0("reg.exe", args, "", false);
        }
        if (rc == UAC_CANCELLED) return false;
        if (rc != 0) throw new IOException("Administrator Kiosk command failed (exit code " + rc + ").");
        if (readRegistry0() != enabled) throw new IOException("Kiosk mode registry setting did not update.");
        fireChanged();
        return true;
    }

    /** Requests Windows Administrator approval via UAC before allowing privileged actions such as Kiosk factory reset. */
    public static boolean authorizeAdminAction() throws IOException {
        if (Boolean.getBoolean("fish.testMode")) {
            BooleanSupplier auth = testAdminAuthorizer;
            if (auth != null) return auth.getAsBoolean();
            if (testOverride != null || System.getProperty("fish.test.kiosk") != null) return true;
        }
        NativeLibrary.load();
        if (isUserAdmin0()) return true;
        Path exe = LayoutShortcuts.executable();
        String name = exe.getFileName() == null ? "" : exe.getFileName().toString().toLowerCase(Locale.ROOT);
        int rc;
        if (name.equals("fish.exe")) {
            Path cwd = exe.getParent() == null ? Path.of(".") : exe.getParent();
            rc = runElevatedWait0(exe.toString(), "--admin-verify", cwd.toString(), false);
        } else {
            rc = runElevatedWait0("cmd.exe", "/c exit 0", "", false);
        }
        if (rc == UAC_CANCELLED) return false;
        if (rc != 0) throw new IOException("Administrator verification failed (exit code " + rc + ").");
        return true;
    }

    /** Launches an arbitrary command elevated via Windows UAC and waits for exit code ({@link #UAC_CANCELLED} if canceled). */
    public static int runElevated(Path executable, String arguments, Path workingDirectory, boolean showWindow) throws IOException {
        NativeLibrary.load();
        return runElevatedWait0(
                executable.toString(),
                arguments == null ? "" : arguments,
                workingDirectory == null ? "" : workingDirectory.toString(),
                showWindow);
    }

    /** Deletes all {@code Fish/*} entries stored in Windows Credential Manager for the current user. */
    public static int deleteAllStoredCredentials() {
        try {
            NativeLibrary.load();
            return deleteAllFishCredentials0();
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return 0;
        }
    }

    public static void addListener(Runnable listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    public static void fireChanged() {
        for (Runnable listener : LISTENERS) listener.run();
    }

    /** Hermetic test hooks (honored only when {@code -Dfish.testMode=true}). */
    public static void setTestOverride(Boolean enabled) {
        testOverride = enabled;
        fireChanged();
    }

    public static void setTestToggleHandler(Predicate<Boolean> handler) {
        testToggleHandler = handler;
    }

    public static void setTestAdminAuthorizer(BooleanSupplier authorizer) {
        testAdminAuthorizer = authorizer;
    }

    private static native boolean readRegistry0();
    private static native void writeRegistry0(boolean enabled) throws IOException;
    private static native boolean isUserAdmin0();
    private static native int runElevatedWait0(String executable, String arguments, String directory, boolean showWindow) throws IOException;
    private static native int deleteAllFishCredentials0();
}
