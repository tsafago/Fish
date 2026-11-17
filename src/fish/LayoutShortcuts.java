package fish;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import fish.model.SavedLayout;

/** Typed Windows Shell links; arguments are a saved UUID and a workspace path, never commands. */
public final class LayoutShortcuts {
    private LayoutShortcuts() {}
    private static void load() throws IOException {
        try { NativeLibrary.load(); }
        catch (IllegalStateException e) { throw new IOException("Windows shortcuts unavailable: "+e.getMessage(),e); }
    }
    private static Path testOverridePath(String propName) {
        if (!Boolean.getBoolean("fish.testMode")) return null;
        String override=System.getProperty(propName);
        return override!=null && !override.isBlank() ? Path.of(override.strip()).toAbsolutePath().normalize() : null;
    }
    public static Path executable() throws IOException {
        Path ov=testOverridePath("fish.test.executable");
        if (ov!=null) return ov;
        load();
        return Path.of(executable0());
    }
    public static Path desktop() throws IOException {
        Path ov=testOverridePath("fish.test.desktopDir");
        if (ov!=null) return ov;
        load(); return Path.of(desktop0());
    }
    public static Path startMenu() throws IOException {
        Path ov=testOverridePath("fish.test.startMenuDir");
        if (ov!=null) return ov;
        load(); return Path.of(startMenu0());
    }
    public static Path startup() throws IOException {
        Path ov=testOverridePath("fish.test.startupDir");
        if (ov!=null) return ov;
        try {
            load();
            return Path.of(startup0());
        } catch (IOException | UnsatisfiedLinkError e) {
            String appData=System.getenv("APPDATA");
            if (appData!=null && !appData.isBlank())
                return Path.of(appData,"Microsoft","Windows","Start Menu","Programs","Startup");
            throw e instanceof IOException io ? io : new IOException("Windows Startup folder unavailable.",e);
        }
    }
    public static Path commonStartMenu() throws IOException {
        Path ov=testOverridePath("fish.test.commonStartMenuDir");
        if (ov!=null) return ov;
        load(); return Path.of(commonStartMenu0());
    }
    public static Path commonDesktop() throws IOException {
        Path ov=testOverridePath("fish.test.commonDesktopDir");
        if (ov!=null) return ov;
        load(); return Path.of(commonDesktop0());
    }
    public static String arguments(UUID layout,Path directory) {
        String path=directory.toAbsolutePath().normalize().toString();
        if (path.length()>30000 || path.chars().anyMatch(c -> c<32 || c=='"'))
            throw new IllegalArgumentException("Invalid shortcut workspace path.");
        // Windows CommandLineToArgvW doubles backslashes immediately before the closing quote.
        int trailing=0; for (int i=path.length()-1;i>=0 && path.charAt(i)=='\\';i--) trailing++;
        return "--layout "+java.util.Objects.requireNonNull(layout)+" --data-dir \""+path+"\\".repeat(trailing)+"\"";
    }
    public static void create(Path destination,Path executable,Path directory,UUID layout) throws IOException {
        for (Path part:destination) if (part.toString().equals("..")) throw new IOException("Choose a shortcut destination without parent traversal.");
        Path target=executable.toAbsolutePath().normalize(),output=destination.toAbsolutePath().normalize();
        if (output.toString().length()>30000 || output.getFileName()==null) throw new IOException("Invalid shortcut destination.");
        if (!target.getFileName().toString().equalsIgnoreCase("Fish.exe") || !Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Run the installed or portable Fish.exe before exporting a shortcut. Java/source launches are not shortcut targets.");
        if (!output.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".lnk")
                || !Files.isDirectory(output.getParent()) || Files.exists(output,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Choose a new .lnk file in an existing folder; existing shortcuts are never overwritten.");
        String args=arguments(layout,directory);
        load();
        create0(output.toString(),target.toString(),args,target.getParent().toString());
    }
    public static void createApplicationLink(Path destination,Path executable) throws IOException {
        for (Path part:destination) if (part.toString().equals("..")) throw new IOException("Choose a shortcut destination without parent traversal.");
        Path target=executable.toAbsolutePath().normalize(),output=destination.toAbsolutePath().normalize();
        if (!target.getFileName().toString().equalsIgnoreCase("Fish.exe") || !Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Application shortcut target must be an existing Fish.exe.");
        if (!output.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".lnk"))
            throw new IOException("Application shortcut must end in .lnk.");
        Files.createDirectories(output.getParent());
        load();
        create0(output.toString(),target.toString(),"",target.getParent().toString());
    }
    public static boolean deleteShortcut(Path shortcut) throws IOException {
        if (shortcut==null) return false;
        Path norm=shortcut.toAbsolutePath().normalize();
        try {
            var dos=Files.getFileAttributeView(norm,java.nio.file.attribute.DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if (dos!=null) dos.setReadOnly(false);
        } catch (IOException | UnsupportedOperationException ignored) {}
        boolean removed=false;
        IOException lastError=null;
        for (int attempt=0;attempt<10;attempt++) {
            try {
                removed=Files.deleteIfExists(norm);
                lastError=null;
                break;
            } catch (FileSystemException e) {
                lastError=e;
                try { Thread.sleep(25); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (lastError!=null) throw lastError;
        if (removed) {
            try { load(); notifyDeleted0(norm.toString()); }
            catch (IOException | UnsatisfiedLinkError ignored) {}
        }
        return removed;
    }

    /**
     * If {@code chosen} sits inside an inactive local/OneDrive Desktop folder (e.g. {@code %USERPROFILE%\Desktop}
     * while OneDrive Known Folder Move redirected the active Shell Desktop to {@code OneDrive\Desktop}),
     * remaps {@code chosen} onto {@code activeDesktop} so the icon renders on the user's real Windows Desktop.
     */
    public static Path resolveDesktopDestination(Path chosen,Path activeDesktop,Path userHome,Collection<Path> oneDriveRoots) {
        if (chosen==null) return null;
        Path normChosen=chosen.toAbsolutePath().normalize();
        Path parent=normChosen.getParent();
        Path fileName=normChosen.getFileName();
        if (parent==null || fileName==null || activeDesktop==null) return normChosen;
        Path normActive=activeDesktop.toAbsolutePath().normalize();
        if (parent.equals(normActive) || !Files.isDirectory(normActive)) return normChosen;
        List<Path> candidates=new ArrayList<>();
        if (userHome!=null) candidates.add(userHome.toAbsolutePath().normalize().resolve("Desktop"));
        if (oneDriveRoots!=null) {
            for (Path root:oneDriveRoots) {
                if (root!=null) candidates.add(root.toAbsolutePath().normalize().resolve("Desktop"));
            }
        }
        for (Path inactiveDesktop:candidates) {
            if (parent.equals(inactiveDesktop)) return normActive.resolve(fileName);
        }
        return normChosen;
    }

    public static Path remapInactiveDesktop(Path chosen) {
        if (chosen==null) return null;
        try {
            Path active=desktop();
            Path testHome=testOverridePath("fish.test.userHome");
            String profileEnv=System.getenv("USERPROFILE");
            Path home=testHome!=null ? testHome
                    : profileEnv!=null && !profileEnv.isBlank()
                    ? Path.of(profileEnv)
                    : Path.of(System.getProperty("user.home","."));
            List<Path> oneDrives=new ArrayList<>();
            for (String envName:List.of("OneDrive","OneDriveCommercial","OneDriveConsumer")) {
                String val=System.getenv(envName);
                if (val!=null && !val.isBlank()) {
                    try { oneDrives.add(Path.of(val.strip())); }
                    catch (InvalidPathException ignored) {}
                }
            }
            return resolveDesktopDestination(chosen,active,home,oneDrives);
        } catch (IOException | RuntimeException e) {
            return chosen.toAbsolutePath().normalize();
        }
    }

    public static boolean areDesktopIconsHidden() {
        if (Boolean.getBoolean("fish.testMode")) {
            String prop=System.getProperty("fish.test.hideDesktopIcons");
            return prop!=null && Boolean.parseBoolean(prop);
        }
        try {
            load();
            return desktopIconsHidden0();
        } catch (IOException | UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static boolean targetsFishApplication(Path shortcut) {
        if (shortcut==null || !Files.isRegularFile(shortcut,LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            String[] info=inspect(shortcut);
            if (info==null || info.length<2 || info[0]==null) return false;
            String args=info[1]==null ? "" : info[1];
            if (args.contains("--layout ")) return false;
            Path target=Path.of(info[0]);
            return target.getFileName()!=null && target.getFileName().toString().equalsIgnoreCase("Fish.exe");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static void reconcileApplicationShortcuts(Path targetExe,boolean startMenuShortcut,boolean desktopShortcut) throws IOException {
        Path normExe=targetExe.toAbsolutePath().normalize();
        if (startMenuShortcut) {
            Path programs=startMenu();
            createApplicationLink(programs.resolve("Fish.lnk"),normExe);
            Path msiGroup=programs.resolve("Fish");
            Path msiLink=msiGroup.resolve("Fish.lnk");
            if (targetsFishApplication(msiLink)) {
                deleteShortcut(msiLink);
                try { Files.deleteIfExists(msiGroup); } catch (IOException ignored) {}
            }
        }
        Path desk=desktop();
        Path deskLink=desk.resolve("Fish.lnk");
        if (desktopShortcut || targetsFishApplication(deskLink)) {
            createApplicationLink(deskLink,normExe);
        }
    }

    public static void removeApplicationShortcuts(boolean includeAllUsers) {
        List<Path> folders=new ArrayList<>();
        try { folders.add(startMenu()); } catch (IOException ignored) {}
        try { folders.add(desktop()); } catch (IOException ignored) {}
        if (includeAllUsers) {
            try { folders.add(commonStartMenu()); } catch (IOException ignored) {}
            try { folders.add(commonDesktop()); } catch (IOException ignored) {}
        }
        for (Path folder:folders) {
            if (folder==null || !Files.isDirectory(folder)) continue;
            Path direct=folder.resolve("Fish.lnk");
            if (targetsFishApplication(direct)) {
                try { deleteShortcut(direct); } catch (IOException ignored) {}
            }
            Path group=folder.resolve("Fish");
            Path grouped=group.resolve("Fish.lnk");
            if (targetsFishApplication(grouped)) {
                try { deleteShortcut(grouped); } catch (IOException ignored) {}
                try { Files.deleteIfExists(group); } catch (IOException ignored) {}
            }
        }
    }
    public static void syncAutostart(Path directory,List<SavedLayout> layouts) throws IOException {
        syncAutostart(directory,executable(),layouts);
    }

    public static void syncAutostart(Path directory,Path executable,List<SavedLayout> layouts) throws IOException {
        Objects.requireNonNull(directory,"Workspace directory is required.");
        Path target=executable.toAbsolutePath().normalize();
        if (!target.getFileName().toString().equalsIgnoreCase("Fish.exe") || !Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))
            return;
        Path startupDir=startup().toAbsolutePath().normalize();
        Files.createDirectories(startupDir);
        Path normalizedData=directory.toAbsolutePath().normalize();
        Map<Path,SavedLayout> desired=new LinkedHashMap<>();
        if (layouts!=null) {
            for (SavedLayout layout:layouts) {
                if (!layout.autostart()) continue;
                String fileName=SavedLayout.shortcutFileName(layout.name());
                Path destination=startupDir.resolve(fileName).toAbsolutePath().normalize();
                if (desired.containsKey(destination)) {
                    String stem=fileName.substring(0,fileName.length()-4);
                    destination=startupDir.resolve(stem+" ("+layout.id().toString().substring(0,8)+").lnk").toAbsolutePath().normalize();
                }
                desired.put(destination,layout);
            }
        }
        try (var entries=Files.list(startupDir)) {
            for (Path entry:entries.toList()) {
                if (!entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lnk")) continue;
                Path normalizedEntry=entry.toAbsolutePath().normalize();
                if (matchesWorkspaceLayoutLink(normalizedEntry,normalizedData)) {
                    SavedLayout wanted=desired.get(normalizedEntry);
                    if (wanted==null || !matchesLayoutId(normalizedEntry,wanted.id())) {
                        deleteShortcut(normalizedEntry);
                    }
                }
            }
        }
        if (desired.isEmpty()) return;
        load();
        for (Map.Entry<Path,SavedLayout> item:desired.entrySet()) {
            Path output=item.getKey();
            String args=arguments(item.getValue().id(),normalizedData);
            create0(output.toString(),target.toString(),args,target.getParent().toString());
        }
    }

    public static int removeAutostartShortcuts(Path directory) {
        try {
            Path startupDir=startup();
            if (!Files.isDirectory(startupDir)) return 0;
            Path normalizedData=directory==null ? null : directory.toAbsolutePath().normalize();
            int removed=0;
            try (var entries=Files.list(startupDir)) {
                for (Path entry:entries.toList()) {
                    if (!entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lnk")) continue;
                    if (normalizedData==null ? isAnyFishLayoutLink(entry) : matchesWorkspaceLayoutLink(entry,normalizedData)) {
                        if (deleteShortcut(entry)) removed++;
                    }
                }
            }
            return removed;
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean matchesWorkspaceLayoutLink(Path shortcut,Path normalizedData) {
        try {
            String[] info=inspect(shortcut);
            if (info==null || info.length<2 || info[1]==null || !info[1].contains("--layout ")) return false;
            Path linkData=extractDataDirectory(info[1]);
            if (linkData!=null) return linkData.equals(normalizedData);
            Path defaultBase=Path.of(java.util.Optional.ofNullable(System.getenv("APPDATA")).orElse(System.getProperty("user.home")));
            return Main.defaultDirectory(defaultBase).toAbsolutePath().normalize().equals(normalizedData);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean matchesLayoutId(Path shortcut,UUID id) {
        try {
            String[] info=inspect(shortcut);
            return info!=null && info.length>=2 && info[1]!=null && info[1].contains("--layout "+id);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean isAnyFishLayoutLink(Path shortcut) {
        try {
            String[] info=inspect(shortcut);
            if (info==null || info.length<2 || info[0]==null || info[1]==null) return false;
            Path target=Path.of(info[0]);
            return target.getFileName()!=null
                    && target.getFileName().toString().equalsIgnoreCase("Fish.exe")
                    && info[1].contains("--layout ");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Path extractDataDirectory(String args) {
        int idx=args.indexOf("--data-dir ");
        if (idx<0) return null;
        String rest=args.substring(idx+"--data-dir ".length()).stripLeading();
        if (rest.isEmpty()) return null;
        String raw;
        if (rest.startsWith("\"")) {
            int end=rest.indexOf('"',1);
            raw=end>1 ? rest.substring(1,end) : rest.substring(1);
            while (raw.endsWith("\\\\")) raw=raw.substring(0,raw.length()-1);
        } else {
            int sp=rest.indexOf(' ');
            raw=sp>0 ? rest.substring(0,sp) : rest;
        }
        try { return Path.of(raw).toAbsolutePath().normalize(); }
        catch (RuntimeException e) { return null; }
    }

    public static String[] inspect(Path shortcut) throws IOException { load(); return inspect0(shortcut.toAbsolutePath().toString()); }
    private static native String executable0() throws IOException;
    private static native String desktop0() throws IOException;
    private static native String startMenu0() throws IOException;
    private static native String startup0() throws IOException;
    private static native String commonStartMenu0() throws IOException;
    private static native String commonDesktop0() throws IOException;
    private static native void create0(String destination,String target,String arguments,String workingDirectory) throws IOException;
    private static native void notifyDeleted0(String shortcut) throws IOException;
    private static native boolean desktopIconsHidden0();
    private static native String[] inspect0(String shortcut) throws IOException;
}
