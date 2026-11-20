package fish.install;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import fish.LayoutShortcuts;
import fish.security.KioskMode;

/**
 * Per-user (SmartScreen-free, non-admin) and system-wide (UAC Administrator) installer helpers
 * for portable Fish app-images.
 */
public final class UserInstaller {
    public static final String UNINSTALL_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Fish";
    public static final String SYSTEM_UNINSTALL_KEY = "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Fish";
    private UserInstaller() {}

    public static Path defaultInstallDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        Path base = local != null && !local.isBlank()
                ? Path.of(local) : Path.of(System.getProperty("user.home"), "AppData", "Local");
        return defaultInstallDirectory(base);
    }

    public static Path defaultInstallDirectory(Path localAppData) {
        return localAppData.resolve("Programs").resolve("Fish").toAbsolutePath().normalize();
    }

    public static Path defaultSystemInstallDirectory() {
        String pf = System.getenv("ProgramFiles");
        Path base = pf != null && !pf.isBlank() ? Path.of(pf) : Path.of("C:\\Program Files");
        return base.resolve("Fish").toAbsolutePath().normalize();
    }

    public static boolean isSystemInstalledLocation(Path appDir) {
        if (appDir == null) return false;
        return appDir.toAbsolutePath().normalize().equals(defaultSystemInstallDirectory());
    }

    public static Optional<Path> currentAppImage() {
        try {
            Path exe = LayoutShortcuts.executable().toAbsolutePath().normalize();
            if (exe.getFileName() != null && exe.getFileName().toString().equalsIgnoreCase("Fish.exe")
                    && Files.isRegularFile(exe, LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(exe.getParent().resolve("runtime"))) {
                return Optional.of(exe.getParent());
            }
        } catch (IOException | RuntimeException ignored) {}
        return Optional.empty();
    }

    public static boolean isInstalledLocation(Path appDir) {
        if (appDir == null) return false;
        Path norm = appDir.toAbsolutePath().normalize();
        if (norm.equals(defaultInstallDirectory())) return true;
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            Path legacyLocal = Path.of(local, "Fish").toAbsolutePath().normalize();
            if (norm.equals(legacyLocal)) return true;
        }
        return false;
    }

    public static void validateImage(Path imageDir) throws IOException {
        if (imageDir == null || !Files.isDirectory(imageDir))
            throw new IOException("Portable Fish application directory is unavailable.");
        if (!Files.isRegularFile(imageDir.resolve("Fish.exe"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(imageDir.resolve("runtime"))
                || !Files.isRegularFile(imageDir.resolve("app").resolve("Fish.jar"))) {
            throw new IOException("Source folder is not a complete Fish application image: " + imageDir);
        }
    }

    public static Path install(Path sourceImage, Path destination, boolean startMenuShortcut,
                               boolean desktopShortcut, boolean registerHkcu) throws IOException {
        validateImage(sourceImage);
        Path src = sourceImage.toAbsolutePath().normalize();
        Path dest = destination.toAbsolutePath().normalize();
        // Settings registers the PowerShell uninstaller. Share its inventory writer and
        // ownership checks rather than creating a second kind of script installation.
        Path script = src.resolve("Install-Fish.ps1");
        if (!Files.isRegularFile(script)) throw new IOException("Install-Fish.ps1 is missing from the portable image.");
        List<String> cmd = new ArrayList<>(List.of(powerShell().toString(), "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-Quiet", "-Source", src.toString(),
                "-Destination", dest.toString()));
        if (!startMenuShortcut) cmd.add("-NoStartMenuShortcut");
        if (desktopShortcut) cmd.add("-DesktopShortcut");
        if (!registerHkcu) cmd.add("-NoRegistry");
        Process worker = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try {
            String output = new String(worker.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (worker.waitFor() != 0) throw new IOException("Fish installation failed: " + output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Fish installation.", e);
        }
        Path exe = dest.resolve("Fish.exe");
        if (!Files.isRegularFile(exe)) throw new IOException("Fish installation did not produce " + exe);
        return exe;
    }

    private static Path powerShell() {
        return Path.of(Optional.ofNullable(System.getenv("SystemRoot")).orElse("C:\\Windows"),
                "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    }

    /** Elevates via Windows UAC (`runas`) to install Fish system-wide into Program Files for all users. */
    public static Path installSystemWide(Path sourceImage, Path destination, boolean startMenuShortcut,
                                         boolean desktopShortcut) throws IOException {
        validateImage(sourceImage);
        Path src = sourceImage.toAbsolutePath().normalize();
        Path dest = destination.toAbsolutePath().normalize();
        if (src.equals(dest) || dest.startsWith(src))
            throw new IOException("Choose a system-wide install destination separate from the running folder.");
        Path script = src.resolve("Install-Fish.ps1");
        if (!Files.isRegularFile(script)) {
            Path fallback = Path.of("Install-Fish.ps1").toAbsolutePath().normalize();
            if (Files.isRegularFile(fallback)) script = fallback;
            else throw new IOException("Install-Fish.ps1 not found alongside Fish.exe: " + script);
        }
        StringBuilder args = new StringBuilder();
        args.append("-NoProfile -ExecutionPolicy Bypass -File ").append(quoteArg(script.toString()))
                .append(" -AllUsers -Quiet -Source ").append(quoteArg(src.toString()))
                .append(" -Destination ").append(quoteArg(dest.toString()));
        if (!startMenuShortcut) args.append(" -NoStartMenuShortcut");
        if (desktopShortcut) args.append(" -DesktopShortcut");
        int exit = KioskMode.runElevated(powerShell(), args.toString(), src, false);
        if (exit != 0) {
            throw new IOException(exit == KioskMode.UAC_CANCELLED
                    ? "Administrator permission was cancelled or declined."
                    : "System-wide installation exited with code " + exit + ".");
        }
        Path targetExe = dest.resolve("Fish.exe");
        if (!Files.isRegularFile(targetExe))
            throw new IOException("System-wide installation did not produce " + targetExe);
        return targetExe;
    }

    private static String quoteArg(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    public static void launchInstalled(Path executable, Path dataDir) throws IOException {
        Path exe = executable.toAbsolutePath().normalize();
        if (!Files.isRegularFile(exe, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Installed Fish.exe not found: " + exe);
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        if (dataDir != null) {
            cmd.add("--data-dir");
            cmd.add(dataDir.toAbsolutePath().normalize().toString());
        }
        new ProcessBuilder(cmd).directory(exe.getParent().toFile()).start();
    }

}
