package fish.ui;

import fish.model.Profile;
import java.awt.Color;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Inline spectrum for quick hue changes; the adjacent field also accepts exact neutral or tinted RGB. */
final class PrimaryColorBar extends HBox {
    final Slider hue=new Slider(0,360,0);
    final TextField hex=new TextField();
    private String selected;
    private boolean syncing;

    PrimaryColorBar(Consumer<String> onPick) {
        super(10);
        setAlignment(Pos.CENTER_LEFT);
        hue.getStyleClass().add("fish-spectrum");
        hue.setAccessibleText("Primary color hue");
        hue.setTooltip(new Tooltip("Click or drag to choose a hue. Use the arrow keys to fine-tune."));
        hue.setBlockIncrement(1);
        hue.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(hue,Priority.ALWAYS);
        hex.getStyleClass().add("fish-color-hex");
        hex.setAccessibleText("Primary color hex value");
        hex.setTooltip(new Tooltip("Enter six hex digits, for example #548DC4. Incomplete edits are not saved."));
        hex.setTextFormatter(new TextFormatter<>(c -> c.getControlNewText().matches("#?[0-9a-fA-F]{0,6}") ? c : null));
        getStyleClass().add("fish-spectrum-field");
        getChildren().add(hue);
        hue.valueProperty().addListener((o,old,v) -> {
            if (syncing) return;
            String next=Profile.formatCustomColor(Color.HSBtoRGB(Math.round(v.doubleValue())/360f,.72f,.93f));
            if (next.equals(selected)) return;
            update(next,false,true);
            onPick.accept(next);
        });
        hex.textProperty().addListener((o,old,v) -> {
            if (syncing) return;
            String candidate=v.startsWith("#") ? v : "#"+v;
            boolean complete=Profile.isCustomColor(candidate);
            Ui.markInvalid(hex,!complete);
            if (complete) {
                String next=Profile.normalizeTheme(candidate);
                if (!next.equals(selected)) {
                    update(next,true,false);
                    onPick.accept(next);
                }
            }
        });
        hex.focusedProperty().addListener((o,old,focused) -> {
            if (!focused && selected!=null) setColor(selected);
        });
        hex.setOnAction(e -> {
            if (selected!=null) setColor(selected);
            e.consume();
        });
    }

    void setColor(String color) { update(color,true,true); }

    private void update(String color,boolean setHue,boolean setHex) {
        syncing=true;
        try {
            selected=color;
            if (setHue) hue.setValue(HostPalette.hueOfRgb(Profile.parseCustomColor(color)));
            if (setHex) hex.setText(color);
            Ui.markInvalid(hex,false);
            Ui.setStyleIfChanged(hue,"-fish-primary-ink: "+color+";");
        } finally { syncing=false; }
    }
}
