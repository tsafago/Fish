package fish.ui;

import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import fish.model.KeyChord;

/** Exists only inside an explicit recorder. Never observes other text/password fields. */
public final class KeyRecorder extends BorderPane {
    public final TextField capture=new TextField("Press one chord");
    private KeyChord chosen;
    private Runnable captured=() -> {},cleared=() -> {},cancel=() -> {};

    public KeyRecorder() {
        setPadding(new Insets(4));
        capture.setEditable(false);
        capture.setAccessibleText("Record one exact chord");
        capture.setPrefColumnCount(32);
        capture.addEventFilter(KeyEvent.ANY,event -> {
            if (captureFxEvent(event)) event.consume();
        });
        capture.addEventFilter(ScrollEvent.SCROLL,event -> {
            if (captureScrollEvent(event)) event.consume();
        });
        Label hint=new Label("Tab, Enter, Escape and mouse wheel (e.g. Ctrl+Scroll) can be recorded. Alt+C cancels; type that chord in the editor.");
        hint.setWrapText(true);
        Button clearBtn=Ui.button("Clear / record again",() -> {
            chosen=null;
            cleared.run();
            capture.setText("Press one chord");
            capture.requestFocus();
        });
        setTop(hint);
        BorderPane.setMargin(hint,new Insets(0,0,10,0));
        setCenter(capture);
        setBottom(clearBtn);
        BorderPane.setMargin(clearBtn,new Insets(10,0,0,0));
    }

    public boolean captureFxEvent(KeyEvent event) {
        int location=event.getCode()!=null && event.getCode().isKeypadKey()
                ? java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD : java.awt.event.KeyEvent.KEY_LOCATION_STANDARD;
        return captureFxEvent(event,location);
    }

    public boolean captureFxEvent(KeyEvent event,int location) {
        if (event.getEventType()==KeyEvent.KEY_PRESSED && KeyChord.actionKey(event.getCode())) {
            if (event.getCode()==KeyCode.C && event.isAltDown() && !event.isControlDown() && !event.isShiftDown() && !event.isMetaDown()) {
                cancel.run();
                return true;
            }
            if (chosen==null) {
                try {
                    chosen=KeyChord.from(event,location);
                    capture.setText(chosen+"  /  "+chosen.label());
                } catch (IllegalArgumentException e) {
                    chosen=null;
                    capture.setText(e.getMessage());
                    return false;
                }
            }
            return true;
        } else if (event.getEventType()==KeyEvent.KEY_RELEASED && chosen!=null && KeyChord.actionKey(event.getCode())) {
            try {
                if (event.getCode().getCode()==chosen.code() && location==chosen.location()) captured.run();
            } catch (IllegalArgumentException reserved) { return false; }
            return true;
        } else if (event.getEventType()==KeyEvent.KEY_TYPED && chosen!=null) {
            return true;
        }
        return false;
    }

    public boolean captureScrollEvent(ScrollEvent event) {
        if (event==null || event.isInertia()) return false;
        if (chosen==null) {
            try {
                KeyChord wheel=KeyChord.from(event);
                if (wheel==null) return false;
                chosen=wheel;
                capture.setText(chosen+"  /  "+chosen.label());
                captured.run();
            } catch (IllegalArgumentException e) {
                chosen=null;
                capture.setText(e.getMessage());
                return false;
            }
        }
        return true;
    }

    public KeyChord chosen() { return chosen; }

    public static Stage dialog(Window owner,Consumer<KeyChord> accept) {
        KeyRecorder recorder=new KeyRecorder();
        Stage stage=Ui.dialogStage(owner,"Record shortcut",true);
        Button cancelBtn=Ui.button("Cancel",stage::close);
        Button okBtn=Ui.button("Use chord",() -> {
            if (recorder.chosen!=null) accept.accept(recorder.chosen);
            stage.close();
        });
        okBtn.setDefaultButton(true);
        okBtn.setDisable(true);

        HBox actions=new HBox(8,cancelBtn,okBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        BorderPane wrapper=new BorderPane();
        wrapper.setPadding(new Insets(16));
        wrapper.setCenter(recorder);
        wrapper.setBottom(actions);
        BorderPane.setMargin(actions,new Insets(16,0,0,0));

        recorder.cancel=stage::close;
        recorder.captured=() -> {
            okBtn.setDisable(false);
            okBtn.requestFocus();
        };
        recorder.cleared=() -> okBtn.setDisable(true);

        Ui.decorateDialog(stage,wrapper,stage::close,460,220);
        // The caption's normal Escape action must not steal a chord being recorded.
        stage.addEventFilter(KeyEvent.KEY_PRESSED,event -> {
            if (event.getTarget()==recorder.capture && event.getCode()==KeyCode.ESCAPE && recorder.captureFxEvent(event))
                event.consume();
        });
        stage.setOnShown(e -> recorder.capture.requestFocus());
        stage.getProperties().put("fish.recorder",recorder);
        stage.getProperties().put("fish.accept",okBtn);
        return stage;
    }
}
