package fish.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.util.Duration;
import fish.model.ColorRole;

public final class TransientNotice extends BorderPane {
    public static final int LIFETIME=2800;
    private final Label text=new Label();
    private final HBox actions=new HBox(4);
    private final Timeline timer=new Timeline(new KeyFrame(Duration.millis(LIFETIME),e -> dismiss()));
    private final Timeline toastTimer=new Timeline(new KeyFrame(Duration.millis(1500),e -> dismiss()));
    private final Button close;
    private boolean toast;

    public TransientNotice(Runnable returnFocus) {
        getStyleClass().add("fish-notice");
        actions.setAlignment(Pos.CENTER_RIGHT);
        close=Ui.iconButton("Dismiss notice","close",ColorRole.FOOTER_TEXT,() -> {
            boolean focused=isCloseFocused();
            dismiss();
            if (focused) returnFocus.run();
        });
        actions.getChildren().add(close);
        setCenter(text);
        setRight(actions);
        BorderPane.setAlignment(text,Pos.CENTER_LEFT);
        setVisible(false);
        setManaged(false);
    }

    private boolean isCloseFocused() { return close.isFocused(); }

    public void setErrorStyle(boolean error) {
        text.setStyle("-fx-text-fill: "+(error ? Ui.hex(ColorRole.STATUS_ERROR) : Ui.hex(ColorRole.FOOTER_TEXT))+";");
    }

    public Button closeButton() { return close; }
    public String messageText() { return text.getText(); }

    public Button action(String title,Runnable run) {
        Button button=Ui.button(title,run);
        button.setStyle("-fx-padding: 3 7 3 7;");
        actions.getChildren().add(actions.getChildren().size()-1,button);
        return button;
    }

    public void showMessage(String value,boolean transientMessage) {
        toastTimer.stop();
        toast=false;
        setMouseTransparent(false);
        setRight(actions);
        text.setText(value);
        text.setTooltip(new Tooltip(value));
        setVisible(true);
        setManaged(true);
        timer.stop();
        Ui.animateSubtle(this,0.0,-2.0,85);
        if (transientMessage) timer.playFromStart();
    }

    void showToast(String value) {
        if (!toast || !isVisible() || !value.equals(text.getText())) {
            timer.stop();
            toast=true;
            setMouseTransparent(true);
            setRight(null);
            text.setText(value);
            text.setTooltip(null);
            setErrorStyle(false);
            Ui.resetMotion(this);
            setVisible(true);
            setManaged(true);
        }
        toastTimer.playFromStart();
    }

    boolean dismissToast() {
        if (!toast || !isVisible()) return false;
        dismiss();
        return true;
    }

    public void dismiss() {
        timer.stop();
        toastTimer.stop();
        Ui.resetMotion(this);
        setVisible(false);
        setManaged(false);
    }

    public void dispose() {
        timer.stop();
        toastTimer.stop();
    }
}
