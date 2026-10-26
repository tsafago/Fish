package pane5250.model;

import java.util.*;
import java.nio.charset.Charset;
import org.tn5250j.framework.tn5250.KbdTypesCodePages;

public record Profile(UUID id, String name, String host, int port, String device, String username,
                      boolean automaticSignOn, boolean rememberPassword, String codePage,
                      String theme, String effect, double zoom, Map<String, String> keymap,
                      String savedLayout) {
    public static final List<String> CODE_PAGES = List.of("37", "273", "277", "278", "280",
            "284", "285", "297", "500", "870", "871", "1140", "1141", "1147", "1148");
    public static final List<String> THEMES = List.of("Phosphor", "Cool", "Warm", "Amber", "Ice", "Orchid", "High contrast");
    public static final List<String> EFFECTS = List.of("None", "Soft glow", "Prism", "Neon");

    public Profile {
        Objects.requireNonNull(id);
        name = name.strip();
        host = host.strip();
        device = device.strip().toUpperCase(Locale.ROOT);
        username = username.strip().toUpperCase(Locale.ROOT);
        keymap = Map.copyOf(keymap);
        savedLayout = Objects.requireNonNull(savedLayout);
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("Profile name must be 1-80 characters.");
        if (host.length() > 253 || host.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
            throw new IllegalArgumentException("Enter a hostname or IP address without spaces.");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535.");
        if (!device.isEmpty() && !device.matches("[A-Z#$@][A-Z0-9_#$@]{0,9}"))
            throw new IllegalArgumentException("Device must be a valid IBM i name, up to 10 characters.");
        if (!username.isEmpty() && !username.matches("[A-Z#$@][A-Z0-9_#$@]{0,9}"))
            throw new IllegalArgumentException("Username must be a valid IBM i profile name, up to 10 characters.");
        if (automaticSignOn && username.isEmpty()) throw new IllegalArgumentException("Automatic sign-on needs a username.");
        if (!CODE_PAGES.contains(codePage)) throw new IllegalArgumentException("Unsupported SBCS code page: " + codePage);
        if (!THEMES.contains(theme) || !EFFECTS.contains(effect)) throw new IllegalArgumentException("Unknown palette or effect.");
        if (!Double.isFinite(zoom) || zoom < 0.5 || zoom > 3) throw new IllegalArgumentException("Zoom must be 50-300%.");
    }

    public static Profile create(String name) {
        return new Profile(UUID.randomUUID(), name, "", 23, "", "", false, false,
                "37", "Phosphor", "None", 1, Map.of(), "");
    }

    public Profile duplicate() {
        return new Profile(UUID.randomUUID(), name.substring(0, Math.min(75, name.length())) + " copy", host, port, "", username,
                automaticSignOn, false, codePage, theme, effect, zoom, keymap, "");
    }

    public Profile withLayout(String layout) {
        return new Profile(id, name, host, port, device, username, automaticSignOn, rememberPassword,
                codePage, theme, effect, zoom, keymap, layout);
    }

    public Profile withZoom(double value) {
        return new Profile(id, name, host, port, device, username, automaticSignOn, rememberPassword,
                codePage, theme, effect, value, keymap, savedLayout);
    }
    public Profile withDisplay(String palette,String textEffect) {
        return new Profile(id,name,host,port,device,username,automaticSignOn,rememberPassword,
                codePage,palette,textEffect,zoom,keymap,savedLayout);
    }

    public Charset charset() {
        return Charset.forName("Cp" + (codePage.equals("37") ? "037" : codePage));
    }

    public Map<String, String> environment(char[] password) {
        Map<String, String> values = new LinkedHashMap<>();
        KbdTypesCodePages keyboard = switch (codePage) {
            case "37" -> KbdTypesCodePages.USB;
            case "500" -> KbdTypesCodePages.INB;
            case "870" -> KbdTypesCodePages.ROB;
            default -> Arrays.stream(KbdTypesCodePages.values()).filter(k -> k.ccsid.equals(codePage)).findFirst().orElse(null);
        };
        if (keyboard != null) {
            values.put("KBDTYPE", keyboard.kbdType);
            values.put("CODEPAGE", keyboard.codepage);
            values.put("CHARSET", keyboard.charset);
        } else if (codePage.equals("1140")) {
            values.put("KBDTYPE", "USB");
            values.put("CODEPAGE", "1140");
            values.put("CHARSET", "695");
        } else if (codePage.equals("1147") || codePage.equals("1148")) {
            values.put("KBDTYPE", codePage.equals("1147") ? "FAB" : "INB");
            values.put("CODEPAGE", codePage);
            values.put("CHARSET", "695");
        }
        if (!device.isEmpty()) values.put("DEVNAME", device);
        values.put("IBMSENDCONFREC","YES");
        if (automaticSignOn) {
            if (password.length == 0 || password.length > 128)
                throw new IllegalArgumentException("Automatic sign-on needs a 1-128 character password.");
            for (char c : password) {
                if (c < 32 || c > 126) throw new IllegalArgumentException("Negotiated plain sign-on requires a printable US-ASCII password.");
            }
            values.put("USER", username);
            values.put("IBMRSEED", "");
            values.put("IBMSUBSPW", new String(password));
        }
        return values;
    }

    public String credentialTarget() {
        return "Pane5250/" + id + "/" + host + ":" + port + "/" + username;
    }
    @Override public String toString() { return name; }
}
