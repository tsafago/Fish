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
                || !Files.isDirectory(output.getParent()))
            throw new IOException("The shortcut folder is unavailable: "+output.getParent());
        if (Files.exists(output,LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(output.toString());
        String args=arguments(layout,directory);
        load();
        // IPersistFile.Save replaces an existing link. Publish a finished link with a no-replace move instead.
        Path staging=Files.createTempFile(output.getParent(),".fish-", ".lnk");
        try {
            create0(staging.toString(),target.toString(),args,target.getParent().toString());
            Files.move(staging,output);
        } finally { Files.deleteIfExists(staging); }
    }

    private static Path numberedLink(Path folder,String name,int number) {
        String file=SavedLayout.shortcutFileName(name);
        return folder.resolve(number==1 ? file : file.substring(0,file.length()-4)+" ("+number+").lnk");
    }

    private static Path createAvailable(Path folder,Path exe,Path data,SavedLayout shortcut) throws IOException {
        for (int n=1;;n++) {
            Path file=numberedLink(folder,shortcut.name(),n);
            try { create(file,exe,data,shortcut.id()); return file; }
            catch (FileAlreadyExistsException collision) { /* Keep both shortcuts. */ }
        }
    }

    @FunctionalInterface public interface Save { void run() throws IOException; }

    /** Publish the link(s) before committing their backing recipe; undo this export if the commit fails. */
    public static Path export(Path data,SavedLayout shortcut,Save commit) throws IOException {
        Path exe=executable(),desk=createAvailable(desktop(),exe,data,shortcut),signIn=null;
        try {
            if (shortcut.autostart()) {
                Path folder=Files.createDirectories(startup());
                signIn=createAvailable(folder,exe,data,shortcut);
            }
            commit.run();
            return desk;
        } catch (IOException | RuntimeException failure) {
            for (Path link:new Path[]{signIn,desk}) {
                if (link==null || !matchesOwnedLink(link,data,shortcut.id())) continue;
                try { deleteShortcut(link); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
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
        Set<Path> desired=new HashSet<>();
        if (layouts!=null) {
            for (SavedLayout layout:layouts) {
                if (!layout.autostart()) continue;
                for (int n=1;;n++) {
                    Path destination=numberedLink(startupDir,layout.name(),n);
                    if (matchesOwnedLink(destination,normalizedData,layout.id())) {
                        if (Path.of(inspect(destination)[0]).equals(target)) { desired.add(destination); break; }
                        // Fish moved. Replace only this workspace's verified Startup link.
                        deleteShortcut(destination);
                    }
                    try { create(destination,target,normalizedData,layout.id()); desired.add(destination); break; }
                    catch (FileAlreadyExistsException collision) { /* Skip unrelated links, including other workspaces. */ }
                }
            }
        }
        try (var entries=Files.list(startupDir)) {
            for (Path entry:entries.toList()) {
                if (!entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lnk")) continue;
                Path normalizedEntry=entry.toAbsolutePath().normalize();
                if (!desired.contains(normalizedEntry) && matchesWorkspaceLayoutLink(normalizedEntry,normalizedData))
                    deleteShortcut(normalizedEntry);
            }
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
        return matchesOwnedLink(shortcut,normalizedData,null);
    }
    private static boolean matchesOwnedLink(Path shortcut,Path data,UUID id) {
        if (!Files.isRegularFile(shortcut,LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            String[] info=inspect(shortcut);
            if (info==null || info.length<2 || !Path.of(info[0]).getFileName().toString().equalsIgnoreCase("Fish.exe")
                    || !info[1].startsWith("--layout ")) return false;
            String[] parts=info[1].split(" ",3);
            UUID actual=SavedLayout.reference(parts[1]);
            if (id!=null && !actual.equals(id)) return false;
            if (parts.length==3) return info[1].equalsIgnoreCase(arguments(actual,data));
            Path defaultBase=Path.of(java.util.Optional.ofNullable(System.getenv("APPDATA")).orElse(System.getProperty("user.home")));
            return Main.defaultDirectory(defaultBase).toAbsolutePath().normalize().equals(data.toAbsolutePath().normalize());
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
