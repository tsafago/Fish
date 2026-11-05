package pane5250.update;

import pane5250.Main;
import pane5250.LaunchBroker;
import pane5250.model.UpdateSettings;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual private JVM: public metadata and isolated loopback launch IPC, never auth or GUI. */
public final class PackagedUpdateSmoke {
    public static void main(String[] args) throws Exception {
        String version=(String)Main.class.getField("VERSION").get(null);
        Updates.Version current=Updates.Version.parse(version);
        if (ModuleLayer.boot().findModule("java.net.http").isEmpty()
                || ModuleLayer.boot().findModule("jdk.crypto.ec").isEmpty())
            throw new AssertionError("Private runtime is missing HTTPS update modules.");
        try (Updates service=new Updates()) {
            var offered=service.check("0.7.2",UpdateSettings.Channel.PREVIEW_PATCHES,new Updates.Cancel());
            if (offered.isEmpty() || offered.get().version().compareTo(Updates.Version.parse("0.7.2"))<=0)
                throw new AssertionError("A newer public preview is not selected from a 0.7.2 client: "+offered);
            var next=service.check(version,UpdateSettings.Channel.PREVIEW_PATCHES,new Updates.Cancel());
            if (next.isPresent() && next.get().version().compareTo(current)<=0)
                throw new AssertionError("Published version would reinstall or downgrade this client.");
        }
        Path root=Files.createTempDirectory("Fish-packaged-ipc-");
        AtomicInteger windows=new AtomicInteger();
        var owner=LaunchBroker.claim(root,"packaged-"+version,() -> { windows.incrementAndGet(); return true; });
        try {
            if (!LaunchBroker.claim(root,"packaged-"+version,() -> false).forwarded() || windows.get()!=1)
                throw new AssertionError("Packaged private runtime cannot forward restricted new-window IPC.");
        } finally {
            owner.broker().close(); owner.store().close();
            Files.deleteIfExists(root.resolve("workspace.lock")); Files.delete(root);
        }
        System.out.println("PASS packaged Fish "+version+" private JVM: eligible public previews, no reinstall/downgrade, private launch IPC; no downloads, auth or GUI.");
    }
}
