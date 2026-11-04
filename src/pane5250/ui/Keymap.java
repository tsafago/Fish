package pane5250.ui;

import java.util.Map;
import java.util.Set;
import org.tn5250j.keyboard.KeyMnemonic;
import pane5250.model.HostKeys;
import pane5250.model.KeyBindings;
import pane5250.model.KeyChord;
import java.awt.event.KeyEvent;

public final class Keymap extends HostKeys {
    private Map<KeyChord,KeyMnemonic> exact;
    private Set<KeyChord> unbound=Set.of();
    public Keymap(Map<String,String> overrides) { super(overrides); }
    public Keymap(Map<String,String> overrides,Set<KeyMnemonic> reassigned) { super(overrides,reassigned); }
    public static Keymap global(Map<String,String> values) {
        Keymap result=new Keymap(Map.of());
        result.exact=KeyBindings.host(values);
        result.unbound=Set.copyOf(KeyChord.list(values.getOrDefault(KeyBindings.UNBOUND,"")));
        return result;
    }
    @Override public KeyMnemonic lookup(KeyEvent event) {
        if (!KeyChord.actionKey(event.getKeyCode())) return null;
        if (exact==null) return super.lookup(event);
        KeyChord chord;
        try { chord=KeyChord.from(event); }
        catch (IllegalArgumentException reserved) { return null; }
        if (unbound.contains(chord)) return null;
        KeyMnemonic result=exact.get(chord);
        return result==null ? exact.get(chord.standardIfUnknown()) : result;
    }
    public Map<KeyChord,KeyMnemonic> exactBindings() {
        if (exact==null) throw new IllegalStateException("Location-aware bindings require the application-wide keymap.");
        return exact;
    }
}
