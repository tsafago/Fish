package fish;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Locale;

/** Source-built Windows OS bridge for Kerberos SSPI, Shell links, and verified updates. */
public final class NativeLibrary {
    private static boolean loaded;
    private NativeLibrary() {}

    /**
     * Resolves the running Fish JAR or classes directory without assuming {@link CodeSource#getLocation()} is non-null.
     * Under JDK 25 AOT class linking ({@code -XX:AOTCache}), {@code CodeSource.getLocation()} returns {@code null}
     * whenever the build-machine classpath path recorded in {@code Fish.aot} does not exist on the target computer.
     */
    public static Path codeLocation(Class<?> anchor) {
        Class<?> target=anchor!=null ? anchor : NativeLibrary.class;
        URL codeSourceUrl=null;
        try {
            ProtectionDomain pd=target.getProtectionDomain();
            CodeSource cs=pd==null ? null : pd.getCodeSource();
            codeSourceUrl=cs==null ? null : cs.getLocation();
        } catch (SecurityException ignored) {}
        String command=null;
        try { command=ProcessHandle.current().info().command().orElse(null); }
        catch (RuntimeException ignored) {}
        return resolveCodeLocation(
                System.getProperty("jpackage.app-path"),
                command,
                System.getProperty("java.class.path"),
                codeSourceUrl);
    }

    public static Path resolveCodeLocation(String jpackageAppPath,String processCommand,String classPath,URL codeSourceUrl) {
        Path fromLauncher=launcherAppLocation(jpackageAppPath);
        if (fromLauncher!=null) return fromLauncher;

        if (processCommand!=null && !processCommand.isBlank()) {
            try {
                Path cmdPath=Path.of(processCommand.strip());
                Path name=cmdPath.getFileName();
                if (name!=null && name.toString().equalsIgnoreCase("Fish.exe")) {
                    Path fromProcess=launcherAppLocation(cmdPath.toString());
                    if (fromProcess!=null) return fromProcess;
                }
            } catch (InvalidPathException ignored) {}
        }

        Path firstJar=null;
        Path firstDir=null;
        if (classPath!=null && !classPath.isBlank()) {
            for (String entry:classPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                String trimmed=entry.strip();
                if (trimmed.isEmpty()) continue;
                try {
                    Path p=Path.of(trimmed).toAbsolutePath().normalize();
                    Path fileName=p.getFileName();
                    if (Files.isRegularFile(p)) {
                        if (fileName!=null && fileName.toString().equalsIgnoreCase("Fish.jar")) return p;
                        if (firstJar==null && fileName!=null
                                && fileName.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                            firstJar=p;
                        }
                    } else if (firstDir==null && Files.isDirectory(p)) {
                        firstDir=p;
                    }
                } catch (InvalidPathException ignored) {}
            }
        }

        if (codeSourceUrl!=null) {
            try {
                Path candidate=Path.of(codeSourceUrl.toURI()).toAbsolutePath().normalize();
                if (Files.exists(candidate)) return candidate;
            } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException | SecurityException ignored) {}
        }

        if (firstJar!=null) return firstJar;
        if (firstDir!=null) return firstDir;
        return Path.of("build","classes").toAbsolutePath().normalize();
    }

    private static Path launcherAppLocation(String launcherPath) {
        if (launcherPath==null || launcherPath.isBlank()) return null;
        try {
            Path exe=Path.of(launcherPath.strip()).toAbsolutePath().normalize();
            Path parent=exe.getParent();
            if (parent==null) return null;
            Path appJar=parent.resolve("app").resolve("Fish.jar");
            if (Files.isRegularFile(appJar)) return appJar;
            Path siblingJar=parent.resolve("Fish.jar");
            if (Files.isRegularFile(siblingJar)) return siblingJar;
            Path appDir=parent.resolve("app");
            if (Files.isDirectory(appDir)) return appDir;
        } catch (InvalidPathException ignored) {}
        return null;
    }

    public static synchronized void load() {
        if (loaded) return;
        try {
            Path location=codeLocation(NativeLibrary.class);
            Path folder=Files.isDirectory(location) ? location : location.getParent();
            Path dll=(folder!=null && Files.isRegularFile(folder.resolve("fish-native.dll")))
                    ? folder.resolve("fish-native.dll")
                    : Path.of("build","fish-native.dll").toAbsolutePath().normalize();
            System.load(dll.toString());
            loaded=true;
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            throw new IllegalStateException("Windows integration could not load. Keep the complete Fish folder together: "+e.getMessage(),e);
        }
    }
}
