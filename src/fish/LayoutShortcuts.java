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
    public static Path executable() throws IOException {
        if (Boolean.getBoolean("fish.testMode")) {
            String override=System.getProperty("fish.test.executable");
            if (override!=null && !override.isBlank()) return Path.of(override.strip()).toAbsolutePath().normalize();
        }
        load();
        return Path.of(executable0());
    }
    public static Path desktop() throws IOException { load(); return Path.of(desktop0()); }
    public static Path startMenu() throws IOException { load(); return Path.of(startMenu0()); }
    public static Path startup() throws IOException {
        if (Boolean.getBoolean("fish.testMode")) {
            String override=System.getProperty("fish.test.startupDir");
            if (override!=null && !override.isBlank()) return Path.of(override.strip()).toAbsolutePath().normalize();
        }
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
    public static Path commonStartMenu() throws IOException { load(); return Path.of(commonStartMenu0()); }
    public static Path commonDesktop() throws IOException { load(); return Path.of(commonDesktop0()); }
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
        Path temporary=Files.createTempFile(output.getParent(),"Fish-layout-",".lnk");
        try {
            create0(temporary.toString(),target.toString(),args,target.getParent().toString());
            Files.move(temporary,output);
        } finally { Files.deleteIfExists(temporary); }
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
        Path temporary=Files.createTempFile(output.getParent(),"Fish-app-",".lnk");
        try {
            create0(temporary.toString(),target.toString(),"",target.getParent().toString());
            Files.move(temporary,output,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
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
                        Files.deleteIfExists(normalizedEntry);
                    }
                }
            }
        }
        if (desired.isEmpty()) return;
        load();
        for (Map.Entry<Path,SavedLayout> item:desired.entrySet()) {
            Path output=item.getKey();
            String args=arguments(item.getValue().id(),normalizedData);
            Path temporary=Files.createTempFile(startupDir,"Fish-autostart-",".lnk");
            try {
                create0(temporary.toString(),target.toString(),args,target.getParent().toString());
                Files.move(temporary,output,StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
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
                        if (Files.deleteIfExists(entry)) removed++;
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
    private static native String[] inspect0(String shortcut) throws IOException;
}
