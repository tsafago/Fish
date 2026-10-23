package pane5250.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.*;

public final class Ui {
    public static final Color BACKGROUND = new Color(0x101213), PANEL = new Color(0x171a1c);
    public static final Color LINE = new Color(0x383f43), TEXT = new Color(0xdde3e5);
    public static final Color MUTED = new Color(0xa5afb6), FOCUS = new Color(0x87bbc8);
    public static final Color ERROR = new Color(0xffa3a3);
    private Ui() {}

    public static void install() throws Exception {
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        Font font = new Font("Segoe UI", Font.PLAIN, 12);
        for (Object key : java.util.Collections.list(UIManager.getDefaults().keys())) {
            if (key.toString().endsWith(".font")) UIManager.put(key, font);
        }
        for (String name : new String[]{"Panel","MenuBar","Menu","MenuItem","PopupMenu","TabbedPane",
                "OptionPane","Label","Button","CheckBox","ComboBox","List","ScrollPane","Viewport",
                "TextField","PasswordField","TextArea","Spinner","SplitPane"}) {
            UIManager.put(name + ".background", PANEL);
            UIManager.put(name + ".foreground", TEXT);
        }
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("PasswordField.caretForeground", TEXT);
        UIManager.put("TextArea.caretForeground", TEXT);
        UIManager.put("List.selectionBackground", new Color(0x344851));
        UIManager.put("List.selectionForeground", Color.WHITE);
        UIManager.put("Menu.selectionBackground", new Color(0x344851));
        UIManager.put("MenuItem.selectionBackground", new Color(0x344851));
        UIManager.put("Button.focus", FOCUS);
        UIManager.put("SplitPane.dividerSize", 4);
        UIManager.put("SplitPane.border", BorderFactory.createEmptyBorder());
        UIManager.put("ToolTip.background", PANEL);
        UIManager.put("ToolTip.foreground", TEXT);
    }
    public static JButton button(String text, int mnemonic, Runnable action) {
        JButton button = new JButton(text);
        button.setMnemonic(mnemonic);
        button.addActionListener(e -> action.run());
        button.setFocusPainted(true);
        return button;
    }
    public static void error(Component owner, String message) {
        JOptionPane.showMessageDialog(owner, message, "Pane5250", JOptionPane.ERROR_MESSAGE);
    }
    public static Border padding(int size) { return BorderFactory.createEmptyBorder(size,size,size,size); }
}
