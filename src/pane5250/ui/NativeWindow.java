package pane5250.ui;

import java.awt.*;
import java.nio.file.*;
import javax.swing.JFrame;

/** Small Windows-only bridge; the OS, not the EDT, owns interactive move/resize. */
final class NativeWindow {
    private static boolean loaded;
    private final JFrame window;
    private final long handle;
    NativeWindow(JFrame window) {
        this.window=window;
        if (!loaded) {
            try {
                Path location=Path.of(NativeWindow.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                Path dll=Files.isDirectory(location) ? Path.of("build","fish-window.dll").toAbsolutePath()
                        : location.getParent().resolve("fish-window.dll");
                System.load(Path.of(System.getProperty("java.home"),"bin","jawt.dll").toString());
                System.load(dll.toString());
                loaded=true;
            } catch (java.net.URISyntaxException | UnsatisfiedLinkError e) {
                throw new IllegalStateException("Windows integration could not load. Keep the complete Fish folder together: "+e.getMessage(),e);
            }
        }
        handle=install0(window);
    }
    void regions(Rectangle drag,Rectangle maximize) {
        if (!window.isDisplayable()) return;
        if (!regions0(handle,window.getWidth(),window.getHeight(),new int[]{
                drag.x,drag.y,drag.width,drag.height,maximize.x,maximize.y,maximize.width,maximize.height}))
            throw new IllegalStateException("Windows integration lost its window handle.");
    }
    void beginMove() { move0(handle); }
    void toggleMaximize() { maximize0(handle); }
    private static native long install0(Window window);
    private static native boolean regions0(long handle,int width,int height,int[] rectangles);
    private static native void move0(long handle);
    private static native void maximize0(long handle);
    private static native int hitTest0(long handle,int x,int y);
    private static native long style0(long handle);
}
