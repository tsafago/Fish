package fish.ui;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import fish.model.SavedLayout;

/** Real Shell links, confined to a synthetic Windows profile inside the suite's workspace. */
final class ShortcutFixture implements AutoCloseable {
    final Path desktop,startup,exe;
    private final Map<String,String> before=new HashMap<>();
    ShortcutFixture(Path root) throws Exception {
        desktop=Files.createDirectories(root.resolve("Desktop")).toAbsolutePath();
        startup=Files.createDirectories(root.resolve("Startup")).toAbsolutePath();
        exe=Files.writeString(root.resolve("Fish.exe"),"never executed").toAbsolutePath();
        property("fish.testMode","true");
        property("fish.test.desktopDir",desktop.toString());
        property("fish.test.startupDir",startup.toString());
        property("fish.test.executable",exe.toString());
    }
    private void property(String key,String value) { before.put(key,System.getProperty(key)); System.setProperty(key,value); }
    @Override public void close() {
        before.forEach((key,value) -> { if (value==null) System.clearProperty(key); else System.setProperty(key,value); });
    }
    static SavedLayout export(Workspace w,String name,boolean autostart) throws Exception {
        AtomicReference<String> error=new AtomicReference<>();
        AtomicReference<Path> file=new AtomicReference<>();
        FxTestSupport.fxRun(() -> w.exportDesktopShortcut(name,autostart,file::set,error::set));
        FxTestSupport.await(() -> file.get()!=null || error.get()!=null,"Shortcut export did not finish");
        if (error.get()!=null) throw new AssertionError(error.get());
        if (!file.get().startsWith(Path.of(System.getProperty("fish.test.desktopDir"))) || !Files.isRegularFile(file.get()))
            throw new AssertionError("Expected a native link in the fixture Desktop: "+file);
        return FxTestSupport.fx(() -> w.savedLayouts().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow());
    }
}
