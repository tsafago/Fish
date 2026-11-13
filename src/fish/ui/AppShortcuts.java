package fish.ui;

import java.util.*;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.tn5250j.keyboard.KeyMnemonic;
import fish.model.*;

/** Window-scoped JavaFX event filter preceding scene focus traversal and terminal key handlers. */
public final class AppShortcuts {
    private final Workspace workspace;
    private final List<AppCommand> commands;
    private Map<String,String> overrides=Map.of();
    private Map<KeyChord,String> bindings=Map.of();
    private final Set<PhysicalKey> claimed=new HashSet<>();
    private final Set<KeyChord> retired=new HashSet<>();
    private Set<KeyChord> unbound=Set.of();
    private boolean suppressTyped;
    private final EventHandler<KeyEvent> sceneFilter=this::filterSceneEvent;

    private record PhysicalKey(int code,int location) {}

    AppShortcuts(Workspace workspace,List<AppCommand> commands,Map<String,String> saved) {
        this.workspace=workspace; this.commands=commands;
        workspace.appearanceSettings().retiredShortcuts().forEach(value -> retired.addAll(KeyChord.legacy(value)));
        apply(saved,workspace.profileList());
    }

    void attach(Scene scene) {
        scene.addEventFilter(KeyEvent.ANY,sceneFilter);
        workspace.stage().focusedProperty().addListener((obs,was,focused) -> {
            if (!focused) { claimed.clear(); suppressTyped=false; }
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
        if (workspace.chrome()!=null) workspace.chrome().invalidateMenuWidth();
    }

    private void filterSceneEvent(KeyEvent event) {
        Node target=event.getTarget() instanceof Node n ? n : workspace.scene().getFocusOwner();
        if (dispatchKey(target,event)) event.consume();
    }

    public boolean dispatchKey(Node source,KeyEvent event) {
        int location=event.getCode()!=null && (event.getCode().isKeypadKey()
                || List.of(KeyCode.NUMPAD0,KeyCode.NUMPAD1,KeyCode.NUMPAD2,KeyCode.NUMPAD3,KeyCode.NUMPAD4,
                        KeyCode.NUMPAD5,KeyCode.NUMPAD6,KeyCode.NUMPAD7,KeyCode.NUMPAD8,KeyCode.NUMPAD9,
                        KeyCode.ADD,KeyCode.SUBTRACT,KeyCode.MULTIPLY,KeyCode.DIVIDE,KeyCode.DECIMAL).contains(event.getCode()))
                ? java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD : java.awt.event.KeyEvent.KEY_LOCATION_STANDARD;
        return dispatchKey(source,event,location);
    }

    public boolean dispatchKey(Node source,KeyEvent event,int location) {
        boolean inTerminal=isTerminalNode(source);
        if (inTerminal && (event.getCode()==KeyCode.ALT || event.getCode()==KeyCode.ALT_GRAPH)) {
            if (event.getEventType()==KeyEvent.KEY_PRESSED || event.getEventType()==KeyEvent.KEY_RELEASED) {
                return true;
            }
        }
        if (event.getEventType()==KeyEvent.KEY_RELEASED) {
            if (event.getCode()==null) return false;
            return claimed.remove(new PhysicalKey(event.getCode().getCode(),location));
        }
        if (event.getEventType()==KeyEvent.KEY_TYPED) {
            return suppressTyped;
        }
        if (event.getEventType()==KeyEvent.KEY_PRESSED) {
            if (!KeyChord.actionKey(event.getCode())) return false;
            suppressTyped=false;
            if (inTerminal && event.getCode()==KeyCode.ENTER
                    && !event.isControlDown() && !event.isShiftDown() && !event.isAltDown() && !event.isMetaDown()
                    && workspace.active()!=null && workspace.active().recoverableInputError()) {
                claimed.add(new PhysicalKey(event.getCode().getCode(),location));
                suppressTyped=true;
                workspace.active().send(KeyMnemonic.RESET);
                return true;
            }
            KeyChord chord;
            try { chord=KeyChord.from(event,location); }
            catch (IllegalArgumentException reserved) { return false; }
            if (isTextInput(source)) return false;
            String title=KeyBindings.lookup(bindings,chord,unbound);
            AppCommand command=title==null ? null : commands.stream().filter(c -> c.title.equals(title)).findFirst().orElseThrow();
            if (command!=null && command.hostKey!=null && !inTerminal) return false;
            boolean explicitlyUnbound=unbound.contains(chord) || unbound.contains(chord.standardIfUnknown());
            if (command==null && !explicitlyUnbound && !retired.contains(chord) && !retired.contains(chord.standardIfUnknown())) return false;
            if ((explicitlyUnbound || command==null) && !inTerminal) return false;
            claimed.add(new PhysicalKey(chord.code(),location));
            suppressTyped=true;
            if (command!=null) command.execute();
            return true;
        }
        return false;
    }

    private static boolean isTerminalNode(Node node) {
        for (Node cur=node;cur!=null;cur=cur.getParent()) if (cur instanceof TerminalView) return true;
        return false;
    }
    private static boolean isTextInput(Node node) {
        for (Node cur=node;cur!=null;cur=cur.getParent()) if (cur instanceof TextInputControl) return true;
        return false;
    }

    void dispose() {
        if (workspace.scene()!=null) workspace.scene().removeEventFilter(KeyEvent.ANY,sceneFilter);
        claimed.clear();
    }
    void refreshLabels() {
        for (AppCommand command:commands) command.setStroke(strokes(command,bindings,overrides));
        if (workspace.chrome()!=null) workspace.chrome().invalidateMenuWidth();
    }
    private static String strokes(AppCommand command,Map<KeyChord,String> effective,Map<String,String> overrides) {
        List<KeyChord> order=overrides.containsKey(command.title) ? KeyChord.list(overrides.get(command.title))
                : KeyBindings.defaultsByAction().getOrDefault(command.title,List.of());
        return KeyChord.text(order.stream().filter(key -> command.title.equals(effective.get(key))).toList());
    }
    void show() { workspace.preferences("Keyboard"); }
    javafx.stage.Stage dialog() { return workspace.preferencesDialog("Keyboard"); }
}
