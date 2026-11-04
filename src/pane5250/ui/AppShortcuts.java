package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import org.tn5250j.keyboard.KeyMnemonic;
import pane5250.model.*;

/** Window-scoped dispatch precedes Swing traversal and the terminal key listener. */
final class AppShortcuts implements KeyEventDispatcher {
    private final Workspace workspace;
    private final List<AppCommand> commands;
    private Map<String,String> overrides=Map.of();
    private Map<KeyChord,String> bindings=Map.of();
    private final Set<PhysicalKey> claimed=new HashSet<>();
    private final Set<KeyChord> retired=new HashSet<>();
    private Set<KeyChord> unbound=Set.of();
    private boolean suppressTyped;
    private record PhysicalKey(int code,int location) {
        static PhysicalKey from(KeyEvent event) { return new PhysicalKey(event.getKeyCode(),event.getKeyLocation()); }
    }
    AppShortcuts(Workspace workspace,List<AppCommand> commands,Map<String,String> saved) {
        this.workspace=workspace; this.commands=commands;
        workspace.appearanceSettings().retiredShortcuts().forEach(value -> retired.addAll(KeyChord.legacy(value)));
        apply(saved,workspace.profileList());
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
        workspace.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) { claimed.clear(); suppressTyped=false; }
        });
    }
    Map<String,String> overrides() { return overrides; }
    Set<KeyMnemonic> reassignedHostActions() {
        Set<KeyMnemonic> result=new HashSet<>();
        for (AppCommand command:commands)
            if (command.hostKey!=null && overrides.containsKey(command.title)) result.add(command.hostKey);
        return Set.copyOf(result);
    }
    static String defaultStroke(AppCommand command) { return KeyBindings.defaultText(command.title); }
    void validate(Map<String,String> values,List<Profile> profiles) { KeyBindings.effective(values); }
    void apply(Map<String,String> values,List<Profile> profiles) {
        Map<KeyChord,String> next=KeyBindings.effective(values);
        for (AppCommand command:commands) command.setStroke(strokes(command,next,values));
        overrides=KeyBindings.normalize(values); bindings=next;
        unbound=Set.copyOf(KeyChord.list(overrides.getOrDefault(KeyBindings.UNBOUND,"")));
        claimed.clear(); suppressTyped=false;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        Component source=event.getComponent();
        Window owner=source instanceof Window w ? w : SwingUtilities.getWindowAncestor(source);
        if (owner!=workspace) return false;
        if (event.getID()==KeyEvent.KEY_RELEASED) {
            if (!claimed.remove(PhysicalKey.from(event))) return false;
        } else if (event.getID()==KeyEvent.KEY_TYPED) {
            if (!suppressTyped) return false;
        } else if (event.getID()==KeyEvent.KEY_PRESSED) {
            if (!KeyChord.actionKey(event.getKeyCode())) return false;
            suppressTyped=false;
            if (source instanceof TerminalView && event.getKeyCode()==KeyEvent.VK_ENTER && event.getModifiersEx()==0
                    && workspace.active().recoverableInputError()) {
                claimed.add(PhysicalKey.from(event));
                suppressTyped=true;
                workspace.active().send(KeyMnemonic.RESET);
                event.consume(); return true;
            }
            KeyChord chord;
            try { chord=KeyChord.from(event); }
            catch (IllegalArgumentException reserved) { return false; }
            String title=KeyBindings.lookup(bindings,event,unbound);
            AppCommand command=title==null ? null : commands.stream().filter(c -> c.title.equals(title)).findFirst().orElseThrow();
            if (source instanceof JTextComponent) return false;
            if (command!=null && command.hostKey!=null && !(source instanceof TerminalView)) return false;
            boolean explicitlyUnbound=unbound.contains(chord) || unbound.contains(chord.standardIfUnknown());
            if (command==null && !explicitlyUnbound && !retired.contains(chord) && !retired.contains(chord.standardIfUnknown())) return false;
            if ((explicitlyUnbound || command==null) && !(source instanceof TerminalView)) return false;
            claimed.add(PhysicalKey.from(event));
            suppressTyped=true;
            if (command!=null) command.actionPerformed(new ActionEvent(workspace,ActionEvent.ACTION_PERFORMED,"shortcut"));
        } else return false;
        event.consume(); return true;
    }
    void dispose() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this); claimed.clear();
    }
    void refreshLabels() {
        for (AppCommand command:commands) command.setStroke(strokes(command,bindings,overrides));
    }
    private static String strokes(AppCommand command,Map<KeyChord,String> effective,Map<String,String> overrides) {
        List<KeyChord> order=overrides.containsKey(command.title) ? KeyChord.list(overrides.get(command.title))
                : KeyBindings.defaultsByAction().getOrDefault(command.title,List.of());
        return KeyChord.text(order.stream().filter(key -> command.title.equals(effective.get(key))).toList());
    }
    void show() { workspace.preferences("Keyboard"); }
    JDialog dialog() { return workspace.preferencesDialog("Keyboard"); }
}
