package pane5250.model;

import java.awt.event.*;
import java.util.*;
import javax.swing.KeyStroke;
import org.tn5250j.keyboard.KeyMnemonic;

/** Shared data/validation for the UI, workspace migration and offline configuration. */
public final class Shortcuts {
    public static final String SETTINGS="Settings...";
    public static final Set<String> SETTINGS_ALIASES=Set.of("Preferences...","Appearance...","Fonts...","Pane information...",
            "Window and focus...","Application settings...","Shortcuts...","Check for updates...","Theme designer...");
    public record Command(String title,String stroke,KeyMnemonic host) {}
    public record Migration(List<Profile> profiles,Map<String,String> overrides,List<String> notices) {}
    private Shortcuts() {}
    private static final Map<String,String> PREVIOUS=Map.ofEntries(
            Map.entry("Copy selection / screen","ctrl alt shift C"),Map.entry("Paste into fields","ctrl alt shift V"),
            Map.entry("Add pane","ctrl alt H"),Map.entry("Close pane","ctrl alt W"),
            Map.entry("Next pane","ctrl alt RIGHT"),Map.entry("Previous pane","ctrl alt LEFT"),
            Map.entry("Fit host screen","ctrl alt 0"),Map.entry("Zoom in","ctrl alt EQUALS"),
            Map.entry("Zoom out","ctrl alt MINUS"),Map.entry("Appearance...","ctrl alt T"),
            Map.entry("Quit","ctrl alt Q"),Map.entry("Switch profile...","ctrl alt O"));
    private static final List<Command> COMMANDS=buildCommands();
    public static List<Command> commands() { return COMMANDS; }
    private static List<Command> buildCommands() {
        List<Command> result=new ArrayList<>();
        String[][] app={
            {"Connect / disconnect","ctrl alt R"},{"Reconnect","ctrl alt shift R"},{"Profiles...","ctrl alt P"},
            {"Switch profile...","ctrl O"},{"Insert profile username","ctrl alt U"},{"Insert remembered password","ctrl alt shift P"},
            {"Quit","ctrl Q"},{"Copy selection / screen","ctrl C"},{"Paste into fields","ctrl V"},{"Shortcuts...",""},
            {"Add pane","ctrl N"},{"Swap panes...","ctrl alt D"},{"Close pane","ctrl W"},
            {"Next pane","ctrl TAB"},{"Previous pane","ctrl shift TAB"},
            {"Decrease divider","ctrl alt OPEN_BRACKET"},{"Increase divider","ctrl alt CLOSE_BRACKET"},
            {"Save layout to profile","ctrl alt S"},{"Restore profile layout","ctrl alt L"},
            {"Fit host screen","ctrl 0"},{"Zoom in","ctrl EQUALS"},{"Zoom out","ctrl MINUS"},{"Appearance...",""},
            {"Preferences...",""},{"Settings...","ctrl COMMA"},
            {"Fonts...",""},{"Pane information...",""},{"Window and focus...",""},{"Theme designer...",""},{"Flux animated text",""},
            {"Full manual / Getting Started",""},{"Getting started tutorial",""},{"Configuration for external tools",""},
            {"Tutorial: Appearance",""},{"Tutorial: Non-Kerberos profile",""},{"Tutorial: Keyboard shortcuts",""},{"Tutorial: Pane tips",""},
            {"Keyboard reference",""},{"Compatibility and connection safety",""},{"Connect panes on startup",""},
            {"Application settings...",""},{"Check for updates...",""},{"Last pane notice...",""},
            {"Open menus","ctrl alt M"},{"Window menu","ctrl alt SPACE"},{"Maximize / restore window",""},{"Minimize window",""},
            {"Send host key...","ctrl alt F"}
        };
        for (String[] row:app) result.add(new Command(row[0],row[1],null));
        for (AppTheme theme:AppTheme.values()) result.add(new Command("Theme: "+theme,"",null));
        for (int i=1;i<=8;i++) result.add(new Command("Focus pane "+i,"ctrl alt "+i,null));
        for (KeyMnemonic host:HostKeys.hostActions()) result.add(new Command(hostTitle(host),hostStroke(host),host));
        return List.copyOf(result);
    }
    public static String hostTitle(KeyMnemonic key) {
        if (key.name().matches("PF\\d+")) return "Host F"+key.name().substring(2);
        if (List.of(KeyMnemonic.RESET,KeyMnemonic.ATTN,KeyMnemonic.SYSREQ).contains(key)) return key.name();
        if (List.of(KeyMnemonic.ENTER,KeyMnemonic.FIELD_EXIT,KeyMnemonic.FIELD_PLUS,KeyMnemonic.FIELD_MINUS,
                KeyMnemonic.CLEAR,KeyMnemonic.HELP,KeyMnemonic.PAGE_UP,KeyMnemonic.PAGE_DOWN).contains(key))
            return key.name().replace('_',' ');
        return "Host "+key.name().replace('_',' ');
    }
    private static String hostStroke(KeyMnemonic host) {
        return HostKeys.defaultBindings().entrySet().stream().filter(e -> e.getValue()==host)
                .map(e -> e.getKey().toString()).min(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())).orElse("");
    }
    public static String defaultStroke(String title) {
        return commands().stream().filter(c -> c.title.equals(title)).findFirst()
                .map(Command::stroke).orElseThrow(() -> new IllegalArgumentException("Unknown shortcut action: "+title));
    }
    public static KeyStroke stroke(String text) {
        if (text.isBlank()) return null;
        KeyStroke key=KeyStroke.getKeyStroke(text.strip());
        if (key==null || key.getKeyCode()==0 || key.isOnKeyRelease())
            throw new IllegalArgumentException("Invalid shortcut: "+text+". Use a Swing stroke such as ctrl TAB.");
        if ((key.getModifiers()&InputEvent.META_DOWN_MASK)!=0
                || key.equals(KeyStroke.getKeyStroke("alt F4")) || key.equals(KeyStroke.getKeyStroke("alt SPACE")))
            throw new IllegalArgumentException("Windows keys, Alt+F4 and Alt+Space belong to the operating system.");
        int code=key.getKeyCode();
        if (List.of(KeyEvent.VK_CONTROL,KeyEvent.VK_SHIFT,KeyEvent.VK_ALT,KeyEvent.VK_META,KeyEvent.VK_ALT_GRAPH).contains(code))
            throw new IllegalArgumentException("Choose an action key as well as a modifier.");
        if (key.getModifiers()==0 && (code>=KeyEvent.VK_A && code<=KeyEvent.VK_Z
                || code>=KeyEvent.VK_0 && code<=KeyEvent.VK_9 || code>=KeyEvent.VK_NUMPAD0 && code<=KeyEvent.VK_NUMPAD9
                || List.of(KeyEvent.VK_SPACE,KeyEvent.VK_COMMA,KeyEvent.VK_PERIOD,KeyEvent.VK_MINUS,KeyEvent.VK_EQUALS,
                        KeyEvent.VK_SLASH,KeyEvent.VK_BACK_SLASH,KeyEvent.VK_SEMICOLON,KeyEvent.VK_QUOTE,
                        KeyEvent.VK_OPEN_BRACKET,KeyEvent.VK_CLOSE_BRACKET,KeyEvent.VK_BACK_QUOTE).contains(code)))
            throw new IllegalArgumentException("Unmodified typing keys cannot be application shortcuts.");
        return key;
    }
    public static void validate(Map<String,String> values,List<Profile> profiles) {
        KeyBindings.effective(values);
    }
    private static void validateLegacy(Map<String,String> values,List<Profile> profiles) {
        Map<String,Command> names=new HashMap<>();
        commands().forEach(c -> names.put(c.title,c));
        Set<KeyMnemonic> reassigned=new HashSet<>();
        for (var entry:values.entrySet()) {
            Command command=names.get(entry.getKey());
            if (command==null) throw new IllegalArgumentException("Unknown shortcut action: "+entry.getKey());
            if (command.host!=null) reassigned.add(command.host);
            stroke(entry.getValue());
        }
        Map<KeyStroke,String> occupied=new HashMap<>();
        Map<UUID,Map<KeyStroke,KeyMnemonic>> hostBindings=new HashMap<>();
        for (Profile profile:profiles) hostBindings.put(profile.id(),new HostKeys(profile.keymap(),reassigned).bindings());
        for (Command command:commands()) {
            if (command.title.equals("Preferences...") || command.title.equals(SETTINGS)) continue;
            if (command.host!=null && !values.containsKey(command.title)) continue;
            KeyStroke key=stroke(values.getOrDefault(command.title,command.stroke));
            if (key==null) continue;
            String previous=occupied.putIfAbsent(key,command.title);
            if (previous!=null) throw new IllegalArgumentException(key+" is already assigned to "+previous+".");
            for (Profile profile:profiles) {
                KeyMnemonic host=hostBindings.get(profile.id()).get(key);
                if (host==null && key.equals(KeyStroke.getKeyStroke("ENTER")) && !reassigned.contains(KeyMnemonic.FIELD_EXIT))
                    host=KeyMnemonic.FIELD_EXIT;
                if (host!=null) throw new IllegalArgumentException(key+" is used by host "+host+" in "+profile.name()
                        +". Unbind or reassign that host action first.");
            }
        }
    }
    public static Map<String,List<String>> aliases() {
        return Map.of("Copy selection / screen",List.of("ctrl INSERT"),
                "Paste into fields",List.of("ctrl shift INSERT","shift PAGE_DOWN"),
                "Zoom in",List.of("ctrl shift EQUALS","ctrl ADD"),
                "Zoom out",List.of("ctrl SUBTRACT"),"Fit host screen",List.of("ctrl NUMPAD0"));
    }
    public static Map<KeyStroke,String> aliasBindings(Map<String,String> values,List<Profile> profiles,Set<String> retired) {
        Map<KeyStroke,String> result=new LinkedHashMap<>();
        Set<KeyStroke> occupied=new HashSet<>();
        Set<KeyMnemonic> reassigned=new HashSet<>();
        for (Command command:commands()) {
            if (command.host!=null && values.containsKey(command.title)) reassigned.add(command.host);
            if (command.host==null || values.containsKey(command.title)) {
                KeyStroke key=stroke(values.getOrDefault(command.title,command.stroke));
                if (key!=null) occupied.add(key);
            }
        }
        Set<KeyStroke> host=new HashSet<>();
        for (Profile profile:profiles) host.addAll(new HostKeys(profile.keymap(),reassigned).bindings().keySet());
        for (String value:retired) occupied.add(stroke(value));
        aliases().forEach((action,keys) -> {
            if (!Objects.equals(stroke(values.getOrDefault(action,defaultStroke(action))),stroke(defaultStroke(action)))) return;
            for (String value:keys) {
                KeyStroke key=stroke(value);
                if (!occupied.contains(key) && !host.contains(key)) result.put(key,action);
            }
        });
        return Map.copyOf(result);
    }
    public static Migration migrate(List<Profile> profiles,Map<String,String> saved,Set<String> retired) {
        List<String> notices=new ArrayList<>();
        List<Profile> migrated=new ArrayList<>(profiles);
        Map<String,String> values=new LinkedHashMap<>(saved);
        Set<KeyStroke> explicitGlobal=new HashSet<>();
        saved.values().stream().filter(s -> !s.isBlank()).forEach(s -> explicitGlobal.add(stroke(s)));
        for (int i=0;i<migrated.size();i++) {
            Profile profile=migrated.get(i);
            Map<String,String> keys=new LinkedHashMap<>(profile.keymap());
            for (String changed:List.of("ESCAPE","ctrl R","PAUSE","alt F1","shift INSERT","ctrl PAUSE")) {
                KeyStroke key=KeyStroke.getKeyStroke(changed);
                boolean explicit=keys.keySet().stream().anyMatch(s -> KeyStroke.getKeyStroke(s).equals(key));
                if (!explicit && explicitGlobal.contains(key)) {
                    keys.put(changed,"");
                    notices.add("New host default "+changed+" left unbound in "+profile.name()+" to preserve an explicit application shortcut.");
                }
            }
            migrated.set(i,profile.withKeys(keys));
        }
        for (Command command:commands()) {
            if (!PREVIOUS.containsKey(command.title) || saved.containsKey(command.title) || command.stroke.isBlank()) continue;
            KeyStroke proposed=stroke(command.stroke);
            boolean conflict=explicitGlobal.contains(proposed) || retired.stream().anyMatch(s -> Objects.equals(KeyStroke.getKeyStroke(s),proposed))
                    || migrated.stream().anyMatch(p -> new HostKeys(p.keymap()).bindings().containsKey(proposed));
            if (conflict) {
                values.put(command.title,PREVIOUS.get(command.title));
                notices.add("Kept "+command.title+" on "+PREVIOUS.get(command.title)+" because its new default conflicts with a retained binding.");
            }
        }
        validateLegacy(values,migrated);
        return new Migration(List.copyOf(migrated),Map.copyOf(values),List.copyOf(notices));
    }
}
