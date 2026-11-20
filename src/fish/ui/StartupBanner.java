package fish.ui;

import fish.NativeLibrary;

/** Optional native feedback while the interactive launch owner boots JavaFX. */
public final class StartupBanner {
    private StartupBanner() {}
    private static long handle;
    private static boolean attempted;

    public static synchronized void openOnce() {
        if (attempted) return; // A recovery/retry in this JVM is not another cold launch.
        attempted=true;
        try {
            NativeLibrary.load();
            handle=open0();
        } catch (RuntimeException | LinkageError ignored) {
            // Art must never prevent an otherwise usable workspace from opening.
        }
    }

    public static synchronized void dismiss() {
        if (handle==0) return;
        close0(handle);
        handle=0;
    }

    private static native long open0();
    private static native void close0(long handle);
}
