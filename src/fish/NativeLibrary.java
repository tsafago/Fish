package fish;

import java.nio.file.*;

/** Source-built Windows OS bridge for Kerberos SSPI, Shell links, and verified updates. */
public final class NativeLibrary {
    private static boolean loaded;
    private NativeLibrary() {}
    public static synchronized void load() {
        if (loaded) return;
        try {
            Path location=Path.of(NativeLibrary.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dll=Files.isDirectory(location) ? Path.of("build","fish-native.dll").toAbsolutePath()
                    : location.getParent().resolve("fish-native.dll");
            System.load(dll.toString());
            loaded=true;
        } catch (java.net.URISyntaxException | UnsatisfiedLinkError e) {
            throw new IllegalStateException("Windows integration could not load. Keep the complete Fish folder together: "+e.getMessage(),e);
        }
    }
}
