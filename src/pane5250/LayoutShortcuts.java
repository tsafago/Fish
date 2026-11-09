package pane5250;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** Typed Windows Shell links; arguments are a saved UUID and a workspace path, never commands. */
public final class LayoutShortcuts {
    private LayoutShortcuts() {}
    private static void load() throws IOException {
        try { NativeLibrary.load(); }
        catch (IllegalStateException e) { throw new IOException("Windows shortcuts unavailable: "+e.getMessage(),e); }
    }
    public static Path executable() throws IOException { load(); return Path.of(executable0()); }
    public static Path desktop() throws IOException { load(); return Path.of(desktop0()); }
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
    public static String[] inspect(Path shortcut) throws IOException { load(); return inspect0(shortcut.toAbsolutePath().toString()); }
    private static native String executable0() throws IOException;
    private static native String desktop0() throws IOException;
    private static native void create0(String destination,String target,String arguments,String workingDirectory) throws IOException;
    private static native String[] inspect0(String shortcut) throws IOException;
}
