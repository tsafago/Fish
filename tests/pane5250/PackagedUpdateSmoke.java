package pane5250.update;

import pane5250.Main;
import pane5250.model.UpdateSettings;

/** Public release metadata only, running inside the actual delivered private JVM. */
public final class PackagedUpdateSmoke {
    public static void main(String[] args) throws Exception {
        String version=(String)Main.class.getField("VERSION").get(null);
        if (!version.equals("0.7.1")) throw new AssertionError("Wrong packaged version: "+version);
        if (ModuleLayer.boot().findModule("java.net.http").isEmpty()
                || ModuleLayer.boot().findModule("jdk.crypto.ec").isEmpty())
            throw new AssertionError("Private runtime is missing HTTPS update modules.");
        try (Updates service=new Updates()) {
            service.check(version,UpdateSettings.Channel.PREVIEW_PATCHES,new Updates.Cancel());
        }
        System.out.println("PASS packaged Fish "+version+" private JVM and actual public HTTPS update lookup; no downloads, auth or GUI.");
    }
}
