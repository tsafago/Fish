package pane5250.ui;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.tn5250j.keyboard.KeyMnemonic;

public final class AppCommand extends AbstractAction {
    private final Runnable run;
    private final BooleanSupplier available;
    public final String title,description,defaultStroke;
    public String shortcut;
    public KeyMnemonic hostKey;
    private final String keywords;
    public AppCommand(String title,String description,String stroke,Runnable run,BooleanSupplier available,String... aliases) {
        super(title);
        this.title=title; this.description=description; this.run=run; this.available=available;
        defaultStroke=stroke;
        keywords=(title+" "+description+" "+String.join(" ",aliases)).toLowerCase(Locale.ROOT);
        putValue(SHORT_DESCRIPTION,description);
        setStroke(stroke);
        refresh();
    }
    public void setStroke(String stroke) {
        KeyStroke key=stroke==null || stroke.isBlank() ? null : KeyStroke.getKeyStroke(stroke);
        shortcut=describeStroke(stroke);
        putValue(ACCELERATOR_KEY,key);
        putValue(SHORT_DESCRIPTION,description+(shortcut.isEmpty() ? "" : " ("+shortcut+")"));
    }
    public static String describeStroke(String stroke) {
        KeyStroke key=stroke==null || stroke.isBlank() ? null : KeyStroke.getKeyStroke(stroke);
        if (key==null) return "";
        String modifiers=InputEvent.getModifiersExText(key.getModifiers());
        return (modifiers.isEmpty() ? "" : modifiers+"+")+KeyEvent.getKeyText(key.getKeyCode());
    }
    public void refresh() { setEnabled(available.getAsBoolean()); }
    public boolean matches(String query) {
        return Arrays.stream(query.toLowerCase(Locale.ROOT).strip().split("\\s+")).allMatch(keywords::contains);
    }
    @Override public void actionPerformed(ActionEvent e) {
        refresh();
        if (isEnabled()) run.run();
    }
}
