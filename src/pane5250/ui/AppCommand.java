package pane5250.ui;

import java.awt.event.ActionEvent;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;

public final class AppCommand extends AbstractAction {
    private final Runnable run;
    private final BooleanSupplier available;
    public final String title,description,shortcut;
    private final String keywords;
    public AppCommand(String title,String description,String stroke,Runnable run,BooleanSupplier available,String... aliases) {
        super(title);
        this.title=title; this.description=description; this.run=run; this.available=available;
        shortcut=stroke==null ? "" : stroke.replace("ctrl ","Ctrl+").replace("alt ","Alt+").replace("shift ","Shift+");
        keywords=(title+" "+description+" "+String.join(" ",aliases)).toLowerCase(Locale.ROOT);
        putValue(SHORT_DESCRIPTION,description);
        if (stroke!=null) putValue(ACCELERATOR_KEY,KeyStroke.getKeyStroke(stroke));
        refresh();
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
