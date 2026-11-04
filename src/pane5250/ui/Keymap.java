package pane5250.ui;

import java.util.Map;
import java.util.Set;
import org.tn5250j.keyboard.KeyMnemonic;
import pane5250.model.HostKeys;

public final class Keymap extends HostKeys {
    public Keymap(Map<String,String> overrides) { super(overrides); }
    public Keymap(Map<String,String> overrides,Set<KeyMnemonic> reassigned) { super(overrides,reassigned); }
}
