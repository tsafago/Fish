package org.tn5250j;

import java.awt.Toolkit;
import java.util.Properties;
import java.util.function.Consumer;
import javax.swing.JOptionPane;
import org.tn5250j.framework.tn5250.Screen5250;
import org.tn5250j.framework.tn5250.tnvt;

/**
 * Pane5250's deliberately small controller adapter for the pinned GPL TN5250J engine.
 * No upstream GUI, scripting, host command execution or global session manager is loaded.
 */
public final class Session5250 {
    private final String name;
    private final Screen5250 screen = new Screen5250();
    private final tnvt engine;
    private final Consumer<String> notice;
    public Session5250(String name, String codePage, java.io.OutputStream output, Consumer<String> notice) {
        this.name = name;
        this.notice = notice;
        engine = new tnvt(this, screen, true, true);
        engine.setCodePage(codePage);
        screen.setVT(engine);
        engine.attachOutput(output);
    }
    public String getSessionName() { return name; }
    public Properties getConnectionProperties() { return new Properties(); }
    public Screen5250 getScreen() { return screen; }
    public tnvt getVT() { return engine; }
    public void accept(byte[] record) { engine.acceptRecord(record); }
    public void fireSessionChanged(int state) {}
    public void fireScanned(String command, String remainder) {
        throw new IllegalStateException("Host command scanning is disabled.");
    }
    public void signalBell() { Toolkit.getDefaultToolkit().beep(); }
    public String showSystemRequest() {
        return JOptionPane.showInputDialog(null, "System Request (empty sends the host request):",
                name + " - System Request", JOptionPane.PLAIN_MESSAGE);
    }
}
