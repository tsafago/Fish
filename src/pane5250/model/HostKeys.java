package pane5250.model;

import java.awt.event.*;
import java.util.*;
import javax.swing.KeyStroke;
import org.tn5250j.keyboard.KeyMnemonic;

public class HostKeys {
    private static final Map<KeyStroke, KeyMnemonic> DEFAULTS = defaults();
    private final Map<KeyStroke, KeyMnemonic> bindings;
    private final Set<KeyMnemonic> globallyReassigned;
    public HostKeys(Map<String, String> overrides) {
        this(overrides,Set.of());
    }
    public HostKeys(Map<String, String> overrides,Set<KeyMnemonic> globallyReassigned) {
        this.globallyReassigned=Set.copyOf(globallyReassigned);
        bindings = new HashMap<>(DEFAULTS);
        Set<KeyStroke> seen=new HashSet<>();
        overrides.forEach((stroke, value) -> {
            KeyStroke key = KeyStroke.getKeyStroke(stroke);
            if (key == null || key.getKeyCode() == 0 || key.isOnKeyRelease()) throw new IllegalArgumentException("Invalid pressed key stroke: " + stroke);
            if (!seen.add(key)) throw new IllegalArgumentException("Duplicate equivalent host stroke: "+stroke);
            int modifiers = key.getModifiers();
            if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0 && (modifiers & InputEvent.ALT_DOWN_MASK) != 0)
                throw new IllegalArgumentException("Ctrl+Alt is reserved for application shortcuts: " + stroke);
            if (key.equals(KeyStroke.getKeyStroke("alt SPACE")) || key.equals(KeyStroke.getKeyStroke("alt F4")))
                throw new IllegalArgumentException("Alt+Space and Alt+F4 are reserved for window controls: " + stroke);
            if ((modifiers & InputEvent.META_DOWN_MASK)!=0) throw new IllegalArgumentException("Windows keys belong to the operating system.");
            if (value.isBlank()) { bindings.remove(key); return; }
            KeyMnemonic mnemonic;
            try { mnemonic = KeyMnemonic.valueOf(value); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown host action: " + value, e); }
            if (!hostActions().contains(mnemonic)) throw new IllegalArgumentException("Not a host action: " + value);
            bindings.put(key, mnemonic);
        });
        bindings.values().removeIf(globallyReassigned::contains);
    }
    public static Map<KeyStroke,KeyMnemonic> defaultBindings() { return DEFAULTS; }
    public Map<KeyStroke,KeyMnemonic> bindings() { return Map.copyOf(bindings); }
    public KeyMnemonic lookup(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.VK_ENTER && event.getKeyLocation() == KeyEvent.KEY_LOCATION_NUMPAD
                && event.getModifiersEx() == 0) return globallyReassigned.contains(KeyMnemonic.FIELD_EXIT) ? null : KeyMnemonic.FIELD_EXIT;
        return bindings.get(KeyStroke.getKeyStrokeForEvent(event));
    }
    public static Map<String, String> parse(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals < 1) throw new IllegalArgumentException("Use KEY = ACTION, one binding per line.");
            String key = line.substring(0, equals).strip(), value = line.substring(equals + 1).strip();
            if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate key: " + key);
        }
        new HostKeys(result);
        return Map.copyOf(result);
    }
    public static java.util.List<KeyMnemonic> hostActions() {
        return Arrays.stream(KeyMnemonic.values()).filter(k -> k.ordinal() <= KeyMnemonic.NEW_LINE.ordinal()
                && k != KeyMnemonic.COPY && k != KeyMnemonic.PASTE && !k.name().startsWith("MARK_")).toList();
    }
    public static String describeDefaults() {
        return "F1-F12: F1-F12     Shift+F1-F12: F13-F24\n"
                + "Enter: Enter     Keypad Enter / Ctrl+Enter: Field Exit\n"
                + "Keypad +/-: Field +/-     Escape: Attention\n"
                + "Ctrl+R: Reset     Enter during a local input error: Reset only\n"
                + "Shift+Escape: System Request     Pause: Clear\n"
                + "Ctrl+Backspace: Clear     Alt+F1 / Ctrl+H: Help\n"
                + "Shift+Insert: Dup     Ctrl+Pause: Host print\n"
                + "Page Up/Down: Roll     Tab/Shift+Tab: Next/previous field\n"
                + "Ctrl+C/V: Copy/paste     Ctrl+Tab / Ctrl+Shift+Tab: Next/previous pane\n"
                + "Ctrl+N/W: Add/close pane     Ctrl+0 and Ctrl+plus/minus: Fit/zoom\n"
                + "Edit > Keyboard > Shortcuts changes application or host actions.\n\n"
                + "Overrides use Swing strokes and host action names, for example:\n"
                + "ctrl ENTER = FIELD_EXIT\nshift F1 = PF13\nF12 = PF12\n"
                + "Use a blank action to disable a host binding: ctrl C =";
    }
    private static Map<KeyStroke, KeyMnemonic> defaults() {
        Map<KeyStroke, KeyMnemonic> map = new HashMap<>();
        String[][] keys = {{"ENTER","ENTER"},{"ctrl ENTER","FIELD_EXIT"},{"TAB","TAB"},{"shift TAB","BACK_TAB"},
                {"ESCAPE","ATTN"},{"shift ESCAPE","SYSREQ"},{"ctrl R","RESET"},{"PAUSE","CLEAR"},{"ctrl BACK_SPACE","CLEAR"},
                {"alt F1","HELP"},{"ctrl H","HELP"},{"shift INSERT","DUP_FIELD"},{"ctrl PAUSE","PRINT"},
                {"PAGE_UP","PAGE_UP"},{"PAGE_DOWN","PAGE_DOWN"},{"ctrl PAGE_UP","ROLL_LEFT"},
                {"ctrl PAGE_DOWN","ROLL_RIGHT"},{"UP","UP"},{"DOWN","DOWN"},{"LEFT","LEFT"},{"RIGHT","RIGHT"},
                {"BACK_SPACE","BACK_SPACE"},{"DELETE","DELETE"},{"INSERT","INSERT"},{"HOME","HOME"},
                {"END","END_OF_FIELD"},{"ctrl END","ERASE_EOF"},{"ADD","FIELD_PLUS"},{"SUBTRACT","FIELD_MINUS"}};
        for (String[] pair : keys) map.put(KeyStroke.getKeyStroke(pair[0]), KeyMnemonic.valueOf(pair[1]));
        for (int i = 1; i <= 12; i++) {
            map.put(KeyStroke.getKeyStroke("F" + i), KeyMnemonic.valueOf("PF" + i));
            map.put(KeyStroke.getKeyStroke("shift F" + i), KeyMnemonic.valueOf("PF" + (i + 12)));
        }
        return Map.copyOf(map);
    }
}
