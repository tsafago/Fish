package fish.ui;

import fish.NativeLibrary;
import javafx.stage.Stage;

/** Win32 hit testing for a captured JavaFX mouse gesture, not the global cursor. */
final class PaneWindowHit {
    static final long OUTSIDE=-1;

    static long handle(Stage stage) {
        if (!stage.isShowing()) return 0;
        try {
            NativeLibrary.load();
            return handle0(stage);
        } catch (IllegalStateException | LinkageError unavailable) {
            return 0;
        }
    }

    // point is in source client pixels on entry, destination client pixels (or desktop pixels) on return.
    static native long at0(long source,int[] point);
    static native void place0(long window,int x,int y);
    private static native long handle0(Stage stage);
    private PaneWindowHit() {}
}
