package pane5250;

import java.nio.file.*;

/** Source-built Windows bridge shared by windowing and Kerberos. */
public final class NativeLibrary {
    private static boolean loaded;
    private NativeLibrary() {}
    public static synchronized void load() {
        if (loaded) return;
        try {
            Path location=Path.of(NativeLibrary.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dll=Files.isDirectory(location) ? Path.of("build","fish-window.dll").toAbsolutePath()
                    : location.getParent().resolve("fish-window.dll");
            System.load(Path.of(System.getProperty("java.home"),"bin","jawt.dll").toString());
            System.load(dll.toString());
            loaded=true;
        } catch (java.net.URISyntaxException | UnsatisfiedLinkError e) {
            throw new IllegalStateException("Windows integration could not load. Keep the complete Fish folder together: "+e.getMessage(),e);
        }
    }
}
