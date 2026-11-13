package pane5250.ui;

import java.awt.*;
import pane5250.NativeLibrary;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.util.function.Consumer;

/** Small Windows-only bridge; the OS, not the EDT, owns interactive move/resize. */
final class NativeWindow {
    private final JFrame window;
    private final long handle;
    private final Consumer<Boolean> hover;
    NativeWindow(JFrame window,Consumer<Boolean> hover) {
        this.window=window;
        this.hover=hover;
        load();
        handle=install0(window,this);
    }
    private void maximizeHover(boolean value) {
        SwingUtilities.invokeLater(() -> { if (window.isDisplayable()) hover.accept(value); });
    }
    private static void load() {
        NativeLibrary.load();
    }
    static void flatten(Window window) {
        load();
        flat0(window);
    }
    void regions(Rectangle drag,Rectangle maximize) {
        if (!window.isDisplayable()) return;
        if (!regions0(handle,window.getWidth(),window.getHeight(),new int[]{
                drag.x,drag.y,drag.width,drag.height,maximize.x,maximize.y,maximize.width,maximize.height,
                window.getMinimumSize().width,window.getMinimumSize().height}))
            throw new IllegalStateException("Windows integration lost its window handle.");
    }
    void beginMove() { move0(handle); }
    void toggleMaximize() { maximize0(handle); }
    private static native long install0(Window window,NativeWindow bridge);
    private static native boolean regions0(long handle,int width,int height,int[] rectangles);
    private static native void move0(long handle);
    private static native void maximize0(long handle);
    private static native int hitTest0(long handle,int x,int y);
    private static native long style0(long handle);
    private static native void flat0(Window window);
    private static native int[] geometry0(long handle);
    private static native int[] frameBounds0(long handle,boolean maximized);
    private static native int[] clientBounds0(Window window);
    private static native int[] minimum0(long handle);
}
