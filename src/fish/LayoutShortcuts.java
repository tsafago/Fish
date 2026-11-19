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

    public record Change(Path desktopLink,String warning) {}

    /** A null before means create, a null after means delete. The caller serializes the workspace commit. */
    public static Change change(Path data,SavedLayout before,SavedLayout after,Save commit) throws IOException {
        UUID id=(before==null ? Objects.requireNonNull(after) : before).id();
        if (after!=null && !after.id().equals(id)) throw new IllegalArgumentException("Shortcut ID changed.");
        Path desk=desktop(),exe=executable();
        LinkEdit edit=new LinkEdit(data,id);
        Path published;
        try {
            boolean rename=before!=null && after!=null && !before.name().equals(after.name());
            List<Path> desktopLinks=before==null ? List.of() : ownedLinks(desk,data,id);
            if (rename || after==null) for (Path link:desktopLinks) edit.retire(link);
            published=after==null ? null : rename || desktopLinks.isEmpty()
                    ? edit.publish(desk,exe,after) : desktopLinks.getFirst();

            if (before!=null || after.autostart()) {
                Path folder=startup();
                if (Files.isSameFile(desk,Files.createDirectories(folder)))
                    throw new IOException("Desktop and Startup must be separate folders.");
                List<Path> startupLinks=before==null ? List.of() : ownedLinks(folder,data,id);
                boolean keep=after!=null && after.autostart();
                for (int i=0;i<startupLinks.size();i++)
                    if (!keep || rename || i>0) edit.retire(startupLinks.get(i));
                if (keep && (rename || startupLinks.isEmpty())) edit.publish(folder,exe,after);
            }
            commit.run();
        } catch (IOException | RuntimeException failure) {
            edit.rollbackOrThrow(failure);
            throw failure;
        }
        return new Change(published,edit.clean());
    }

    // Keep originals (including custom Shell properties) until the recipe is durable. No move replaces a path.
    private static final class LinkEdit {
        final Path data;
        final UUID id;
        final List<Path> created=new ArrayList<>();
        record Backup(Path original,Path file) {}
        final List<Backup> backups=new ArrayList<>();
        LinkEdit(Path data,UUID id) { this.data=data; this.id=id; }
        Path publish(Path folder,Path exe,SavedLayout after) throws IOException {
            Path file=createAvailable(folder,exe,data,after);
            created.add(file);
            return file;
        }
        void retire(Path original) throws IOException {
            Path backup=original.resolveSibling(".fish-"+UUID.randomUUID()+".bak");
            Files.move(original,backup);
            backups.add(new Backup(original,backup));
            // Verify *after* taking the file out of circulation, in case Explorer replaced it during the scan.
            if (!matchesOwnedLink(backup,data,id)) throw new IOException("Shortcut changed during the operation: "+original);
        }
        void rollbackOrThrow(Exception failure) throws IOException {
            List<String> problems=new ArrayList<>();
            for (Path file:created.reversed()) {
                try {
                    if (Files.notExists(file,LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!matchesOwnedLink(file,data,id)) throw new IOException("file was replaced");
                    // Recheck identity after the move; never unlink an unrelated replacement.
                    retire(file);
                    deleteShortcut(backups.getLast().file());
                    backups.removeLast();
                } catch (IOException | RuntimeException e) { problems.add(file+" ("+e.getMessage()+")"); }
            }
            for (Backup entry:backups.reversed()) {
                try { Files.move(entry.file(),entry.original()); }
                catch (IOException | RuntimeException e) { problems.add(entry.file()+" → "+entry.original()+" ("+e.getMessage()+")"); }
            }
            if (!problems.isEmpty()) throw new IOException(failure.getMessage()+". Could not restore every link: "+String.join("; ",problems),failure);
        }
        String clean() {
            List<String> left=new ArrayList<>();
            for (Backup entry:backups) {
                Path backup=entry.file();
                try {
                    if (!matchesOwnedLink(backup,data,id)) throw new IOException("Backup changed");
                    deleteShortcut(backup);
                } catch (IOException | RuntimeException e) { left.add(backup.toString()); }
            }
            return left.isEmpty() ? "" : "Saved, but these backup files could not be removed: "+String.join(", ",left);
        }
    }

    private static List<Path> ownedLinks(Path folder,Path data,UUID id) throws IOException {
        return workspaceLinks(folder,data).stream().filter(link -> id.equals(link.id())).map(OwnedLink::file).toList();
    }

    private record OwnedLink(Path file,Path target,UUID id) {}
    private static List<OwnedLink> workspaceLinks(Path folder,Path data) throws IOException {
        List<OwnedLink> result=new ArrayList<>();
        try (var entries=Files.newDirectoryStream(folder,"*.lnk")) {
            for (Path entry:entries) {
                if (!Files.readAttributes(entry,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isRegularFile()) continue;
                String[] info;
                try { info=inspect(entry); }
                catch (IOException e) {
                    // Non-links can have a .lnk extension. An unreadable real link, however, may be ours.
                    try (var in=Files.newInputStream(entry)) {
                        if (Arrays.equals(in.readNBytes(20),SHELL_LINK_HEADER))
                            throw new IOException("Close the program using "+entry+" and retry.",e);
                    }
                    continue;
                }
                UUID actual=ownedId(info,data);
                if (actual!=null) result.add(new OwnedLink(entry,Path.of(info[0]),actual));
            }
        } catch (DirectoryIteratorException e) { throw e.getCause(); }
        result.sort(Comparator.comparing(OwnedLink::file));
        return result;
    }
    private static final byte[] SHELL_LINK_HEADER=HexFormat.of().parseHex("4c0000000114020000000000c000000000000046");
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
        if (layouts==null || layouts.isEmpty()) return;
        Path target=executable.toAbsolutePath().normalize();
        if (!target.getFileName().toString().equalsIgnoreCase("Fish.exe") || !Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))
            return;
        Path startupDir=startup().toAbsolutePath().normalize();
        Files.createDirectories(startupDir);
        Path normalizedData=directory.toAbsolutePath().normalize();
        var existing=workspaceLinks(startupDir,normalizedData);
        for (SavedLayout layout:layouts) {
            LinkEdit edit=new LinkEdit(normalizedData,layout.id());
            try {
                boolean found=false;
                for (OwnedLink link:existing) {
                    if (!link.id().equals(layout.id())) continue;
                    if (layout.autostart() && !found && link.target().equals(target)) found=true;
                    else edit.retire(link.file());
                }
                if (layout.autostart() && !found) edit.publish(startupDir,target,layout);
            } catch (IOException | RuntimeException failure) {
                edit.rollbackOrThrow(failure);
                throw failure;
            }
            String warning=edit.clean();
            if (!warning.isEmpty()) throw new IOException(warning);
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
            UUID actual=ownedId(inspect(shortcut),data);
            return actual!=null && (id==null || actual.equals(id));
        }
        catch (IOException e) { return false; }
    }
    private static UUID ownedId(String[] info,Path data) {
        try {
            if (info==null || info.length<2 || !Path.of(info[0]).getFileName().toString().equalsIgnoreCase("Fish.exe")
                    || !info[1].startsWith("--layout ")) return null;
            String[] parts=info[1].split(" ",3);
            UUID actual=SavedLayout.reference(parts[1]);
            if (parts.length==3)
                return parts[2].startsWith("--data-dir \"") && info[1].equalsIgnoreCase(arguments(actual,data)) ? actual : null;
            Path defaultBase=Path.of(java.util.Optional.ofNullable(System.getenv("APPDATA")).orElse(System.getProperty("user.home")));
            return Main.defaultDirectory(defaultBase).toAbsolutePath().normalize().equals(data.toAbsolutePath().normalize()) ? actual : null;
        } catch (RuntimeException e) {
            return null;
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
