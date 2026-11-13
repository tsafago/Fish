package fish.ui;

import java.util.*;
import java.util.function.BooleanSupplier;
import javafx.beans.property.*;
import javafx.scene.control.*;
import org.tn5250j.keyboard.KeyMnemonic;
import fish.model.KeyChord;

public final class AppCommand {
    private final Runnable run;
    private final BooleanSupplier available;
    public final String title,description,defaultStroke;
    public String shortcut;
    public KeyMnemonic hostKey;
    private final String keywords;
    private final BooleanProperty enabled=new SimpleBooleanProperty(true);
    private final BooleanProperty selected=new SimpleBooleanProperty(false);
    private final StringProperty primaryShortcut=new SimpleStringProperty("");
    private final StringProperty tooltipText=new SimpleStringProperty("");
    private java.util.function.Consumer<String> performed=ignored -> {};

    public AppCommand(String title,String description,String stroke,Runnable run,BooleanSupplier available,String... aliases) {
        this.title=title; this.description=description; this.run=run; this.available=available;
        defaultStroke=stroke;
        keywords=(title+" "+description+" "+String.join(" ",aliases)).toLowerCase(Locale.ROOT);
        setStroke(stroke);
        refresh();
    }
    public void setStroke(String stroke) {
        shortcut=describeStroke(stroke);
        String primary=stroke==null || stroke.isBlank() ? "" : KeyChord.list(stroke).getFirst().label();
        primaryShortcut.set(primary);
        tooltipText.set(description+(primary.isEmpty() ? "" : " ("+primary+")"));
    }
    public static String describeStroke(String stroke) {
        return stroke==null || stroke.isBlank() ? "" : String.join(" / ",KeyChord.list(stroke).stream().map(KeyChord::label).toList());
    }
    public boolean isEnabled() { return enabled.get(); }
    public ReadOnlyBooleanProperty enabledProperty() { return enabled; }
    public boolean isSelected() { return selected.get(); }
    public void setSelected(boolean value) { selected.set(value); }
    public BooleanProperty selectedProperty() { return selected; }
    public String primaryShortcut() { return primaryShortcut.get(); }
    public ReadOnlyStringProperty primaryShortcutProperty() { return primaryShortcut; }
    public String tooltipText() { return tooltipText.get(); }
    public ReadOnlyStringProperty tooltipTextProperty() { return tooltipText; }
    public void refresh() { enabled.set(available.getAsBoolean()); }
    void performed(java.util.function.Consumer<String> listener) { performed=listener; }
    public boolean matches(String query) {
        return Arrays.stream(query.toLowerCase(Locale.ROOT).strip().split("\\s+")).allMatch(keywords::contains);
    }
    public void execute() {
        refresh();
        if (isEnabled()) { run.run(); performed.accept(title); }
    }
    public MenuItem createMenuItem(String displayText) {
        MenuItem item=new MenuItem();
        bindMenuItem(item,displayText);
        item.setOnAction(e -> execute());
        return item;
    }
    public CheckMenuItem createCheckMenuItem(String displayText) {
        CheckMenuItem item=new CheckMenuItem();
        bindMenuItem(item,displayText);
        item.selectedProperty().bindBidirectional(selected);
        item.setOnAction(e -> execute());
        return item;
    }
    private void bindMenuItem(MenuItem item,String displayText) {
        item.getProperties().put("fish.command",this);
        item.getProperties().put("fish.title",title);
        item.disableProperty().bind(enabled.not());
        Runnable updateText=() -> {
            String accel=primaryShortcut.get();
            item.setText(accel.isEmpty() ? displayText : displayText+"   "+accel);
        };
        primaryShortcut.addListener((obs,oldV,newV) -> updateText.run());
        updateText.run();
    }
}
