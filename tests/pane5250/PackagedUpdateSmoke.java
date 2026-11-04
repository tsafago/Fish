package pane5250.update;

import pane5250.Main;
import pane5250.model.UpdateSettings;

/** Public release metadata only, running inside the actual delivered private JVM. */
public final class PackagedUpdateSmoke {
    public static void main(String[] args) throws Exception {
        String version=(String)Main.class.getField("VERSION").get(null);
        if (!version.equals("0.7.2")) throw new AssertionError("Wrong packaged version: "+version);
        if (ModuleLayer.boot().findModule("java.net.http").isEmpty()
                || ModuleLayer.boot().findModule("jdk.crypto.ec").isEmpty())
            throw new AssertionError("Private runtime is missing HTTPS update modules.");
        try (Updates service=new Updates()) {
            var offered=service.check("0.7.1",UpdateSettings.Channel.PREVIEW_PATCHES,new Updates.Cancel());
            if (offered.isEmpty() || !offered.get().version().toString().equals(version))
                throw new AssertionError("Published preview is not selected from a 0.7.1 client: "+offered);
            if (service.check(version,UpdateSettings.Channel.PREVIEW_PATCHES,new Updates.Cancel()).isPresent())
                throw new AssertionError("Published version would reinstall itself.");
        }
        System.out.println("PASS packaged Fish "+version+" private JVM selects the new public preview from 0.7.1, never reinstalls itself; no downloads, auth or GUI.");
    }
}
