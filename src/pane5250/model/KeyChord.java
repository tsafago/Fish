package pane5250.model;

import java.awt.event.*;
import java.util.*;
import javax.swing.KeyStroke;

/** The action key's reported location is part of its identity; modifier sides are not. */
public record KeyChord(int code,int modifiers,int location) {
    private static final Map<String,Integer> LOCATIONS=Map.of("standard",KeyEvent.KEY_LOCATION_STANDARD,
            "numpad",KeyEvent.KEY_LOCATION_NUMPAD,"left",KeyEvent.KEY_LOCATION_LEFT,
            "right",KeyEvent.KEY_LOCATION_RIGHT,"unknown",KeyEvent.KEY_LOCATION_UNKNOWN);
    private static final int MASK=InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK|InputEvent.ALT_DOWN_MASK
            |InputEvent.ALT_GRAPH_DOWN_MASK|InputEvent.META_DOWN_MASK;
    public KeyChord {
        modifiers&=MASK;
        if ((modifiers&InputEvent.ALT_GRAPH_DOWN_MASK)!=0) modifiers&=~(InputEvent.CTRL_DOWN_MASK|InputEvent.ALT_DOWN_MASK);
        if (code==0 || location<0 || location>4) throw new IllegalArgumentException("Invalid key identity.");
        if (!actionKey(code))
            throw new IllegalArgumentException("Press an action key as well as a modifier.");
        if (code==KeyEvent.VK_WINDOWS || (modifiers&InputEvent.META_DOWN_MASK)!=0
                || modifiers==InputEvent.ALT_DOWN_MASK && (code==KeyEvent.VK_F4 || code==KeyEvent.VK_SPACE)
                || modifiers==InputEvent.CTRL_DOWN_MASK && code==KeyEvent.VK_ESCAPE)
            throw new IllegalArgumentException("Windows keys, Ctrl+Escape, Alt+F4 and Alt+Space belong to Windows.");
    }
    public static boolean actionKey(int code) {
        return code!=0 && !List.of(KeyEvent.VK_CONTROL,KeyEvent.VK_SHIFT,KeyEvent.VK_ALT,KeyEvent.VK_META,KeyEvent.VK_ALT_GRAPH).contains(code);
    }
    public static KeyChord from(KeyEvent event) {
        return normalized(event.getKeyCode(),event.getModifiersEx(),event.getKeyLocation());
    }
    private static KeyChord normalized(int code,int modifiers,int location) {
        if (List.of(KeyEvent.VK_KP_UP,KeyEvent.VK_KP_DOWN,KeyEvent.VK_KP_LEFT,KeyEvent.VK_KP_RIGHT).contains(code)) {
            code=switch(code) {
                case KeyEvent.VK_KP_UP -> KeyEvent.VK_UP;
                case KeyEvent.VK_KP_DOWN -> KeyEvent.VK_DOWN;
                case KeyEvent.VK_KP_LEFT -> KeyEvent.VK_LEFT;
                default -> KeyEvent.VK_RIGHT;
            };
            location=KeyEvent.KEY_LOCATION_NUMPAD;
        }
        return new KeyChord(code,modifiers,location);
    }
    public static KeyChord parse(String text) {
        String value=text.strip();
        int location=-1, at=value.lastIndexOf('@');
        if (at>=0) {
            location=LOCATIONS.getOrDefault(value.substring(at+1).toLowerCase(Locale.ROOT),-1);
            if (location<0) throw new IllegalArgumentException("Use @standard, @numpad, @left, @right or @unknown.");
            value=value.substring(0,at).strip();
        }
        if (value.endsWith("NUMPAD_ENTER")) {
            value=value.substring(0,value.length()-12)+"ENTER";
            if (location>=0 && location!=KeyEvent.KEY_LOCATION_NUMPAD)
                throw new IllegalArgumentException("NUMPAD_ENTER must use the numpad location.");
            location=KeyEvent.KEY_LOCATION_NUMPAD;
        }
        KeyStroke key=KeyStroke.getKeyStroke(value);
        if (key==null || key.getKeyCode()==0 || key.isOnKeyRelease())
            throw new IllegalArgumentException("Invalid chord: "+text+". Try ctrl ENTER@numpad.");
        int code=key.getKeyCode();
        if (location<0) location=intrinsicNumpad(code) ? KeyEvent.KEY_LOCATION_NUMPAD : KeyEvent.KEY_LOCATION_STANDARD;
        return normalized(code,key.getModifiers(),location);
    }
    private static boolean intrinsicNumpad(int code) {
        return code>=KeyEvent.VK_NUMPAD0 && code<=KeyEvent.VK_NUMPAD9
                || List.of(KeyEvent.VK_ADD,KeyEvent.VK_SUBTRACT,KeyEvent.VK_MULTIPLY,KeyEvent.VK_DIVIDE,
                        KeyEvent.VK_DECIMAL,KeyEvent.VK_SEPARATOR,KeyEvent.VK_KP_UP,KeyEvent.VK_KP_DOWN,
                        KeyEvent.VK_KP_LEFT,KeyEvent.VK_KP_RIGHT).contains(code);
    }
    public static List<KeyChord> list(String text) {
        if (text.isBlank()) return List.of();
        List<KeyChord> result=new ArrayList<>();
        for (String line:text.split("\\R",-1)) {
            if (line.isBlank()) throw new IllegalArgumentException("Use one chord per line, without empty lines.");
            KeyChord chord=parse(line);
            if (result.contains(chord)) throw new IllegalArgumentException("Duplicate chord: "+chord);
            result.add(chord);
        }
        return List.copyOf(result);
    }
    public static List<String> supportedKeys() {
        List<String> names=new ArrayList<>();
        for (var field:KeyEvent.class.getFields()) if (field.getName().startsWith("VK_")) {
            String name=field.getName().substring(3);
            try { parse(name); names.add(name); }
            catch (IllegalArgumentException unsupported) { /* Only parser-accepted action keys are shown. */ }
        }
        names.add("NUMPAD_ENTER");
        return names.stream().distinct().sorted().toList();
    }
    public static List<String> locations() { return LOCATIONS.keySet().stream().sorted().map(s -> "@"+s).toList(); }
    public static String text(Collection<KeyChord> keys) { return String.join("\n",keys.stream().map(KeyChord::toString).toList()); }
    public static List<KeyChord> legacy(String value) {
        if (value.isBlank()) return List.of();
        KeyChord key=parse(value);
        if (key.location()!=KeyEvent.KEY_LOCATION_STANDARD || !List.of(KeyEvent.VK_ENTER,KeyEvent.VK_UP,
                KeyEvent.VK_DOWN,KeyEvent.VK_LEFT,KeyEvent.VK_RIGHT,KeyEvent.VK_HOME,KeyEvent.VK_END,
                KeyEvent.VK_PAGE_UP,KeyEvent.VK_PAGE_DOWN,KeyEvent.VK_INSERT,KeyEvent.VK_DELETE).contains(key.code()))
            return List.of(key);
        return List.of(key,new KeyChord(key.code(),key.modifiers(),KeyEvent.KEY_LOCATION_NUMPAD));
    }
    public boolean typingKey() {
        return modifiers==0 && (code>=KeyEvent.VK_A && code<=KeyEvent.VK_Z
                || code>=KeyEvent.VK_0 && code<=KeyEvent.VK_9 || code>=KeyEvent.VK_NUMPAD0 && code<=KeyEvent.VK_NUMPAD9
                || List.of(KeyEvent.VK_SPACE,KeyEvent.VK_COMMA,KeyEvent.VK_PERIOD,KeyEvent.VK_MINUS,KeyEvent.VK_EQUALS,
                        KeyEvent.VK_SLASH,KeyEvent.VK_BACK_SLASH,KeyEvent.VK_SEMICOLON,KeyEvent.VK_QUOTE,
                        KeyEvent.VK_OPEN_BRACKET,KeyEvent.VK_CLOSE_BRACKET,KeyEvent.VK_BACK_QUOTE,KeyEvent.VK_DECIMAL).contains(code));
    }
    public KeyChord standardIfUnknown() {
        return location==KeyEvent.KEY_LOCATION_UNKNOWN ? new KeyChord(code,modifiers,KeyEvent.KEY_LOCATION_STANDARD) : this;
    }
    @Override public String toString() {
        String stroke=KeyStroke.getKeyStroke(code,modifiers).toString().replace("pressed ","");
        return stroke+switch(location) {
            case KeyEvent.KEY_LOCATION_NUMPAD -> "@numpad";
            case KeyEvent.KEY_LOCATION_LEFT -> "@left";
            case KeyEvent.KEY_LOCATION_RIGHT -> "@right";
            case KeyEvent.KEY_LOCATION_UNKNOWN -> "@unknown";
            default -> "";
        };
    }
    public String label() {
        String mods=InputEvent.getModifiersExText(modifiers);
        return (mods.isEmpty() ? "" : mods+"+")+KeyEvent.getKeyText(code)
                +switch(location) {
                    case KeyEvent.KEY_LOCATION_NUMPAD -> " (keypad)";
                    case KeyEvent.KEY_LOCATION_LEFT -> " (left)";
                    case KeyEvent.KEY_LOCATION_RIGHT -> " (right)";
                    case KeyEvent.KEY_LOCATION_UNKNOWN -> " (unlocated)";
                    default -> "";
                };
    }
}
