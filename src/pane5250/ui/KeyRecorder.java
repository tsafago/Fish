package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;
import javax.swing.*;
import pane5250.model.KeyChord;

/** Exists only inside an explicit recorder. Never observes other text/password fields. */
final class KeyRecorder extends JPanel implements KeyEventDispatcher {
    final JTextField capture=new JTextField("Press one chord",34);
    private KeyChord chosen;
    private Runnable captured=() -> {},cleared=() -> {},cancel=() -> {};
    KeyRecorder() {
        super(new BorderLayout(0,12));
        capture.setEditable(false); capture.setFocusTraversalKeysEnabled(false);
        capture.getAccessibleContext().setAccessibleName("Record one exact chord");
        add(capture,BorderLayout.CENTER);
        add(new JLabel("Tab, Enter and Escape can be recorded. Alt+C cancels; type that chord in the editor."),BorderLayout.NORTH);
        add(Ui.button("Clear / record again",KeyEvent.VK_L,() -> {
            chosen=null; cleared.run(); capture.setText("Press one chord"); capture.requestFocusInWindow();
        }),BorderLayout.SOUTH);
    }
    @Override public void addNotify() { super.addNotify(); KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this); }
    @Override public void removeNotify() { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this); super.removeNotify(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return event.getComponent()==capture && captureEvent(event);
    }
    boolean captureEvent(KeyEvent event) {
        if (event.getID()==KeyEvent.KEY_PRESSED && KeyChord.actionKey(event.getKeyCode())) {
            if (event.getKeyCode()==KeyEvent.VK_C && event.getModifiersEx()==InputEvent.ALT_DOWN_MASK) cancel.run();
            else if (chosen==null) {
                try { chosen=KeyChord.from(event); capture.setText(chosen+"  /  "+chosen.label()); }
                catch (IllegalArgumentException e) { chosen=null; capture.setText(e.getMessage()); return false; }
            }
        } else if (event.getID()==KeyEvent.KEY_RELEASED && chosen!=null && KeyChord.actionKey(event.getKeyCode())) {
            try { if (KeyChord.from(event).code()==chosen.code() && event.getKeyLocation()==chosen.location()) captured.run(); }
            catch (IllegalArgumentException reserved) { return false; }
        }
        event.consume(); return true;
    }
    KeyChord chosen() { return chosen; }
    static JDialog dialog(Window owner,Consumer<KeyChord> accept) {
        KeyRecorder recorder=new KeyRecorder();
        JDialog dialog=Ui.promptDialog(owner,"Record shortcut",recorder,"Use chord",true,() -> {
            if (recorder.chosen!=null) accept.accept(recorder.chosen);
        });
        recorder.cancel=dialog::dispose;
        dialog.getRootPane().getDefaultButton().setEnabled(false);
        recorder.captured=() -> {
            dialog.getRootPane().getDefaultButton().setEnabled(true);
            dialog.getRootPane().getDefaultButton().requestFocusInWindow();
        };
        recorder.cleared=() -> dialog.getRootPane().getDefaultButton().setEnabled(false);
        return dialog;
    }
}
