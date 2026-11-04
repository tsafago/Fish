package pane5250.model;

import java.awt.event.KeyEvent;
import java.util.*;

/** Retains original definitions without choosing a winning profile for incompatible keys. */
public final class KeyboardMigration {
    public record Result(List<Profile> profiles,Map<String,String> bindings,Map<String,String> recovery) {}
    private KeyboardMigration() {}
    public static Result migrate(List<Profile> profiles,Map<String,String> saved) {
        Map<String,String> archive=new TreeMap<>();
        Map<KeyChord,String> effective=new LinkedHashMap<>(KeyBindings.effective(Map.of()));
        Map<KeyChord,Set<String>> targets=new LinkedHashMap<>();
        Map<KeyChord,List<String>> sources=new LinkedHashMap<>();
        saved.forEach((action,text) -> {
            Shortcuts.Command command=KeyBindings.command(action);
            archive.put("shortcut."+action,text);
            boolean retainedDefault=command.host()==null && !text.isBlank()
                    && Objects.equals(javax.swing.KeyStroke.getKeyStroke(text),javax.swing.KeyStroke.getKeyStroke(command.stroke()));
            if (!retainedDefault) effective.values().removeIf(action::equals);
            for (KeyChord chord:KeyChord.legacy(text)) add(targets,sources,chord,action,"Application: "+action+" = "+text);
        });
        for (Profile profile:profiles) if (!profile.keymap().isEmpty()) {
            new HostKeys(profile.keymap());
            String prefix="profile."+profile.id()+".";
            archive.put(prefix+"name",profile.name());
            profile.keymap().forEach((key,value) -> {
                archive.put(prefix+"key."+key,value);
                List<KeyChord> keys=KeyChord.legacy(key);
                // Old profile Enter overrides never reached the old hard-coded keypad Enter path.
                KeyChord first=keys.getFirst();
                if (first.code()==KeyEvent.VK_ENTER && first.modifiers()==0) keys=List.of(first);
                String action=value.isBlank() ? KeyBindings.UNBOUND : Shortcuts.hostTitle(org.tn5250j.keyboard.KeyMnemonic.valueOf(value));
                for (KeyChord chord:keys) add(targets,sources,chord,action,profile.name()+" ["+profile.id()+"]: "+key+" = "+value);
            });
        }
        Set<KeyChord> unbound=new LinkedHashSet<>();
        List<String> conflicts=new ArrayList<>();
        targets.forEach((chord,actions) -> {
            effective.remove(chord);
            if (actions.size()>1) {
                unbound.add(chord);
                conflicts.add(chord+" is inactive:\n  "+String.join("\n  ",sources.get(chord)));
            } else {
                String action=actions.iterator().next();
                if (action.equals(KeyBindings.UNBOUND)) unbound.add(chord);
                else effective.put(chord,action);
            }
        });
        Map<String,String> overrides=new LinkedHashMap<>();
        KeyBindings.defaultsByAction().forEach((action,defaults) -> {
            if (action.equals(KeyBindings.UNBOUND)) return;
            List<KeyChord> keys=effective.entrySet().stream().filter(e -> e.getValue().equals(action))
                    .map(Map.Entry::getKey).sorted(Comparator.comparing(KeyChord::toString)).toList();
            if (!new HashSet<>(keys).equals(new HashSet<>(defaults)) || saved.containsKey(action))
                overrides.put(action,KeyChord.text(keys));
        });
        if (!unbound.isEmpty()) overrides.put(KeyBindings.UNBOUND,KeyChord.text(unbound));
        if (!conflicts.isEmpty()) archive.put("report",
                "Some old keyboard definitions disagreed during migration. These keys were left inactive, not assigned to a winning profile.\n"
                +"Assign them in Preferences > Keyboard, then remove them from Unbound keys. Original definitions remain below.\n\n"
                +String.join("\n\n",conflicts));
        KeyBindings.effective(overrides);
        return new Result(profiles.stream().map(p -> p.withKeys(Map.of())).toList(),Map.copyOf(overrides),Map.copyOf(archive));
    }
    private static void add(Map<KeyChord,Set<String>> targets,Map<KeyChord,List<String>> sources,KeyChord chord,String action,String source) {
        targets.computeIfAbsent(chord,k -> new LinkedHashSet<>()).add(action);
        sources.computeIfAbsent(chord,k -> new ArrayList<>()).add(source);
    }
    public static void validateRecovery(Map<String,String> values) {
        values.forEach((key,value) -> {
            if (key.equals("report")) {
                if (value.length()>100_000) throw new IllegalArgumentException("Keyboard recovery report is too large.");
            } else if (key.startsWith("shortcut.")) {
                KeyBindings.command(key.substring(9)); KeyChord.legacy(value);
            } else if (key.startsWith("profile.") && key.length()>45) {
                UUID.fromString(key.substring(8,44));
                String field=key.substring(45);
                if (field.equals("name")) {
                    if (value.isBlank() || value.length()>80) throw new IllegalArgumentException("Invalid keyboard recovery profile name.");
                } else if (field.startsWith("key.")) HostKeys.parse(field.substring(4)+" = "+value);
                else throw new IllegalArgumentException("Unknown keyboard recovery property: "+key);
            } else throw new IllegalArgumentException("Unknown keyboard recovery property: "+key);
        });
    }
    public static String report(Map<String,String> recovery) {
        if (recovery.isEmpty()) return "No legacy keyboard definitions.";
        StringBuilder text=new StringBuilder(recovery.getOrDefault("report","Old keyboard definitions were migrated to one application-wide mapping."));
        text.append("\n\nRetained original definitions (also in exported keyboard.legacy.* properties):\n");
        new TreeMap<>(recovery).forEach((key,value) -> { if (!key.equals("report")) text.append(key).append(" = ").append(value).append('\n'); });
        return text.toString();
    }
}
