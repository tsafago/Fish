package pane5250.model;

import java.util.*;
import java.awt.event.*;
import org.tn5250j.keyboard.KeyMnemonic;

/** One application-wide map. Explicit assignments take a key from its default owner. */
public final class KeyBindings {
    public static final String UNBOUND="Unbound keys";
    private static final Map<String,List<KeyChord>> DEFAULTS=defaults();
    private KeyBindings() {}
    private static Map<String,List<KeyChord>> defaults() {
        Map<String,List<KeyChord>> result=new LinkedHashMap<>();
        for (Shortcuts.Command action:Shortcuts.commands()) {
            List<KeyChord> keys=new ArrayList<>();
            if (action.host()==null) {
                if (!action.stroke().isBlank()) keys.add(KeyChord.parse(action.stroke()));
                for (String alias:Shortcuts.aliases().getOrDefault(action.title(),List.of())) keys.add(KeyChord.parse(alias));
            } else {
                HostKeys.defaultBindings().entrySet().stream().filter(e -> e.getValue()==action.host())
                        .sorted(Comparator.comparing(e -> e.getKey().toString())).forEach(e -> keys.addAll(KeyChord.legacy(e.getKey().toString())));
            }
            result.put(action.title(),List.copyOf(new LinkedHashSet<>(keys)));
        }
        result.put(UNBOUND,List.of());
        return Collections.unmodifiableMap(result);
    }
    public static Map<String,List<KeyChord>> defaultsByAction() { return DEFAULTS; }
    public static String defaultText(String action) { return KeyChord.text(DEFAULTS.getOrDefault(action,List.of())); }
    public static Shortcuts.Command command(String title) {
        return Shortcuts.commands().stream().filter(c -> c.title().equals(title)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown keyboard action: "+title));
    }
    public static Map<String,String> normalize(Map<String,String> overrides) {
        Map<String,String> result=new LinkedHashMap<>();
        overrides.forEach((action,keys) -> {
            if (!DEFAULTS.containsKey(action)) throw new IllegalArgumentException("Unknown keyboard action: "+action);
            List<KeyChord> chords=KeyChord.list(keys);
            if (!action.equals(UNBOUND) && command(action).host()==null && chords.stream().anyMatch(KeyChord::typingKey))
                throw new IllegalArgumentException("Unmodified typing keys cannot invoke application actions: "+action);
            result.put(action,KeyChord.text(chords));
        });
        return Map.copyOf(result);
    }
    public static Map<KeyChord,String> effective(Map<String,String> overrides) {
        Map<String,String> normalized=normalize(overrides);
        Map<KeyChord,String> explicit=new LinkedHashMap<>();
        normalized.forEach((action,keys) -> {
            for (KeyChord key:KeyChord.list(keys)) {
                String previous=explicit.putIfAbsent(key,action);
                if (previous!=null) throw new IllegalArgumentException(key+" is assigned to both "+previous+" and "+action+". Remove one assignment.");
            }
        });
        Map<KeyChord,String> result=new LinkedHashMap<>();
        DEFAULTS.forEach((action,keys) -> {
            if (!normalized.containsKey(action)) for (KeyChord key:keys) {
                if (explicit.containsKey(key)) continue;
                String previous=result.putIfAbsent(key,action);
                if (previous!=null) throw new IllegalStateException("Conflicting default: "+key+" / "+previous+" / "+action);
            }
        });
        explicit.forEach((key,action) -> { if (!action.equals(UNBOUND)) result.put(key,action); });
        return Map.copyOf(result);
    }
    public static String lookup(Map<KeyChord,String> keys,KeyEvent event) {
        return lookup(keys,event,Set.of());
    }
    public static String lookup(Map<KeyChord,String> keys,KeyEvent event,Set<KeyChord> unbound) {
        KeyChord chord=KeyChord.from(event);
        if (unbound.contains(chord)) return null;
        String action=keys.get(chord);
        return action!=null ? action : keys.get(chord.standardIfUnknown());
    }
    public static Map<KeyChord,KeyMnemonic> host(Map<String,String> overrides) {
        Map<KeyChord,KeyMnemonic> result=new HashMap<>();
        effective(overrides).forEach((key,action) -> {
            KeyMnemonic host=command(action).host();
            if (host!=null) result.put(key,host);
        });
        return Map.copyOf(result);
    }
    public static List<String> defaultClaims(Map<String,String> overrides) {
        List<String> claims=new ArrayList<>();
        Map<KeyChord,String> defaults=effective(Map.of());
        normalize(overrides).forEach((action,text) -> {
            for (KeyChord key:KeyChord.list(text)) {
                String previous=defaults.get(key);
                if (previous!=null && !previous.equals(action)) claims.add(key+": "+previous+" -> "+action);
            }
        });
        return List.copyOf(claims);
    }
}
