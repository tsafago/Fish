package fish.install;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import fish.LayoutShortcuts;
import fish.Main;
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
        if (src.equals(dest) || dest.startsWith(src))
            throw new IOException("Choose an install destination separate from the running portable folder.");
        Path destExe = dest.resolve("Fish.exe");
        if (Files.exists(dest)) {
            if (!Files.isDirectory(dest)) throw new IOException("Destination exists and is not a folder: " + dest);
            if (!Files.exists(destExe)) {
                try (var entries = Files.list(dest)) {
                    if (entries.findAny().isPresent())
                        throw new IOException("Choose an empty folder or an existing Fish installation: " + dest);
                }
            }
        }
        if (ProcessHandle.allProcesses()
                .map(ph -> ph.info().command().orElse(""))
                .anyMatch(cmd -> !cmd.isEmpty() && cmd.equalsIgnoreCase(destExe.toString()))) {
            throw new IOException("Fish is currently running from " + dest + ". Close that copy first.");
        }

        Files.createDirectories(Objects.requireNonNull(dest.getParent()));
        Path staging = dest.getParent().resolve("Fish-staging-" + UUID.randomUUID().toString().substring(0, 8));
        long[] totalBytes = {0L};
        try {
            copyCleanTree(src, staging, totalBytes);
            Path targetExe = staging.resolve("Fish.exe");
            if (!Files.isRegularFile(targetExe)) throw new IOException("Staged Fish.exe missing after copy.");

            Path backup = null;
            if (Files.exists(dest)) {
                backup = dest.getParent().resolve("Fish-old-" + UUID.randomUUID().toString().substring(0, 8));
                Files.move(dest, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(staging, dest, StandardCopyOption.REPLACE_EXISTING);
                if (backup != null) deleteTreeQuietly(backup);
            } catch (IOException moveError) {
                if (!Files.exists(dest) && backup != null && Files.exists(backup)) {
                    try { Files.move(backup, dest); } catch (IOException ignored) {}
                }
                throw moveError;
            }
        } finally {
            if (Files.exists(staging)) deleteTreeQuietly(staging);
        }

        Path finalExe = dest.resolve("Fish.exe");
        if (startMenuShortcut || desktopShortcut) {
            LayoutShortcuts.reconcileApplicationShortcuts(finalExe, startMenuShortcut, desktopShortcut);
        }
        if (registerHkcu && System.getProperty("os.name", "").startsWith("Windows")) {
            long sizeKb = Math.max(1L, (totalBytes[0] + 1023L) / 1024L);
            registerUninstall(dest, finalExe, Main.VERSION, sizeKb);
            cleanSupersededUserInstallations(dest);
        }
        return finalExe;
    }

    /** Removes stale side-by-side per-user Fish directories (%LOCALAPPDATA%\Fish vs %LOCALAPPDATA%\Programs\Fish) when not running. */
    public static void cleanSupersededUserInstallations(Path keepDir) {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) return;
        Path normKeep = keepDir == null ? null : keepDir.toAbsolutePath().normalize();
        for (Path candidate : List.of(
                Path.of(local, "Programs", "Fish").toAbsolutePath().normalize(),
                Path.of(local, "Fish").toAbsolutePath().normalize())) {
            if (normKeep != null && candidate.equals(normKeep)) continue;
            Path candExe = candidate.resolve("Fish.exe");
            if (!Files.isRegularFile(candExe, LinkOption.NOFOLLOW_LINKS)) continue;
            boolean inUse = ProcessHandle.allProcesses()
                    .map(ph -> ph.info().command().orElse(""))
                    .anyMatch(cmd -> !cmd.isEmpty() && cmd.equalsIgnoreCase(candExe.toString()));
            if (!inUse) deleteTreeQuietly(candidate);
        }
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
        String systemRoot = Optional.ofNullable(System.getenv("SystemRoot")).orElse("C:\\Windows");
        Path psExe = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        StringBuilder args = new StringBuilder();
        args.append("-NoProfile -ExecutionPolicy Bypass -File ").append(quoteArg(script.toString()))
                .append(" -AllUsers -Quiet -Source ").append(quoteArg(src.toString()))
                .append(" -Destination ").append(quoteArg(dest.toString()));
        if (!startMenuShortcut) args.append(" -NoStartMenuShortcut");
        if (desktopShortcut) args.append(" -DesktopShortcut");
        int exit = KioskMode.runElevated(psExe, args.toString(), src, false);
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

    private static void copyCleanTree(Path sourceRoot, Path targetRoot, long[] totalBytes) throws IOException {
        try (var stream = Files.walk(sourceRoot)) {
            for (Path current : stream.toList()) {
                if (Files.isSymbolicLink(current)) continue;
                Path rel = sourceRoot.relativize(current);
                Path out = targetRoot.resolve(rel);
                if (Files.isDirectory(current)) {
                    Files.createDirectories(out);
                } else if (Files.isRegularFile(current)) {
                    Files.createDirectories(out.getParent());
                    try (InputStream in = Files.newInputStream(current);
                         OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        totalBytes[0] += in.transferTo(os);
                    }
                    stripZoneIdentifier(out);
                }
            }
        }
    }

    public static void stripZoneIdentifier(Path file) {
        try {
            Files.deleteIfExists(Path.of(file.toAbsolutePath() + ":Zone.Identifier"));
        } catch (IOException | InvalidPathException ignored) {}
    }

    private static void registerUninstall(Path installDir, Path exe, String version, long sizeKb) throws IOException {
        Path uninstallScript = installDir.resolve("Uninstall-Fish.ps1");
        String uninstallCmd = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File \"" + uninstallScript + "\"";
        runReg("add", UNINSTALL_KEY, "/v", "DisplayName", "/t", "REG_SZ", "/d", "Fish", "/f");
        runReg("add", UNINSTALL_KEY, "/v", "DisplayVersion", "/t", "REG_SZ", "/d", version, "/f");
        runReg("add", UNINSTALL_KEY, "/v", "Publisher", "/t", "REG_SZ", "/d", "Fish", "/f");
        runReg("add", UNINSTALL_KEY, "/v", "InstallLocation", "/t", "REG_SZ", "/d", installDir.toString(), "/f");
        runReg("add", UNINSTALL_KEY, "/v", "DisplayIcon", "/t", "REG_SZ", "/d", exe + ",0", "/f");
        runReg("add", UNINSTALL_KEY, "/v", "UninstallString", "/t", "REG_SZ", "/d", uninstallCmd, "/f");
        runReg("add", UNINSTALL_KEY, "/v", "QuietUninstallString", "/t", "REG_SZ", "/d", uninstallCmd + " -Quiet", "/f");
        runReg("add", UNINSTALL_KEY, "/v", "NoModify", "/t", "REG_DWORD", "/d", "1", "/f");
        runReg("add", UNINSTALL_KEY, "/v", "NoRepair", "/t", "REG_DWORD", "/d", "1", "/f");
        runReg("add", UNINSTALL_KEY, "/v", "EstimatedSize", "/t", "REG_DWORD", "/d", Long.toString(sizeKb), "/f");
    }

    private static void runReg(String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("reg.exe");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try {
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) {
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                p.destroyForcibly();
                throw new IOException("Could not update per-user registry metadata: " + out);
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Registry update interrupted.", e);
        }
    }

    private static void deleteTreeQuietly(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    var dos = Files.getFileAttributeView(p, java.nio.file.attribute.DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                    if (dos != null) dos.setReadOnly(false);
                } catch (IOException | UnsupportedOperationException ignored) {}
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }
}
