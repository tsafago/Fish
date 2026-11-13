package pane5250.model;

import java.util.*;
import java.nio.charset.Charset;
import org.tn5250j.framework.tn5250.KbdTypesCodePages;

public record Profile(UUID id, String name, String host, int port, String device, String username,
                      boolean automaticSignOn, boolean rememberPassword, String codePage,
                      String theme, String effect, double zoom, Map<String, String> keymap,
                      String savedLayout,UUID loginSource,Authentication authentication,String kerberosServer) {
    public enum Authentication {
        PASSWORD("Password / manual"), KERBEROS("Windows (Kerberos)");
        private final String label;
        Authentication(String label) { this.label=label; }
        @Override public String toString() { return label; }
    }
    public Profile(UUID id,String name,String host,int port,String device,String username,boolean automaticSignOn,
                   boolean rememberPassword,String codePage,String theme,String effect,double zoom,
                   Map<String,String> keymap,String savedLayout,UUID loginSource) {
        this(id,name,host,port,device,username,automaticSignOn,rememberPassword,codePage,theme,effect,zoom,keymap,savedLayout,
                loginSource,Authentication.PASSWORD,"");
    }
    public Profile(UUID id,String name,String host,int port,String device,String username,boolean automaticSignOn,
                   boolean rememberPassword,String codePage,String theme,String effect,double zoom,
                   Map<String,String> keymap,String savedLayout) {
        this(id,name,host,port,device,username,automaticSignOn,rememberPassword,codePage,theme,effect,zoom,keymap,savedLayout,null);
    }
    public static final List<String> CODE_PAGES = List.of("37", "273", "277", "278", "280",
            "284", "285", "297", "500", "870", "871", "1140", "1141", "1147", "1148");
    public static final List<String> THEMES = List.of("Phosphor", "Cool", "Warm", "Amber", "Ice", "Orchid", "High contrast",
            "IBM vivid", "Mint", "Rose", "OLED green", "OLED amber", "Azure vivid", "Crimson vivid");
    public static final String FLUX="Flux (animated)";
    public static final List<String> EFFECTS = List.of("None", "Soft glow", "Prism",FLUX);

    public Profile {
        Objects.requireNonNull(id);
        Objects.requireNonNull(authentication);
        kerberosServer=serverName(kerberosServer);
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
        if (loginSource!=null && (!username.isEmpty() || automaticSignOn || rememberPassword))
            throw new IllegalArgumentException("A linked login inherits username and sign-in preferences; its own login fields must be empty/false.");
        if (loginSource!=null && loginSource.equals(id)) throw new IllegalArgumentException("A profile cannot use its own login.");
        if (authentication==Authentication.KERBEROS && (automaticSignOn || rememberPassword || loginSource!=null))
            throw new IllegalArgumentException("Kerberos uses Windows sign-in, not saved passwords or another profile's login. Set auto/remember false and loginSource empty.");
        if (!CODE_PAGES.contains(codePage)) throw new IllegalArgumentException("Unsupported SBCS code page: " + codePage);
        if (!THEMES.contains(theme) || !EFFECTS.contains(effect)) throw new IllegalArgumentException("Unknown palette or effect.");
        if (!Double.isFinite(zoom) || zoom < 0.5 || zoom > 3) throw new IllegalArgumentException("Zoom must be 50-300%.");
    }

    public static Profile create(String name) {
        return new Profile(UUID.randomUUID(), name, "10.1.2.3", 23, "", "", true, true,
                "37", "Phosphor", "None", 1, Map.of(), "");
    }
    public static Profile kerberosDefault() {
        return createKerberos("Local IBM i");
    }
    public static Profile createKerberos(String name) {
        return new Profile(UUID.randomUUID(),name,"10.1.2.3",23,"","",false,false,
                "37","Phosphor","None",1,Map.of(),"",null,Authentication.KERBEROS,"");
    }
    public static String serverName(String value) {
        String name=Objects.requireNonNull(value).strip().toLowerCase(Locale.ROOT);
        if (name.length()>1 && name.endsWith(".")) name=name.substring(0,name.length()-1);
        if (!name.isEmpty() && (name.length()>253 || !name.contains(".") || name.matches("[0-9.]+")
                || !Arrays.stream(name.split("\\.",-1)).allMatch(s -> s.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))))
            throw new IllegalArgumentException("Kerberos server must be a fully qualified DNS name, not an IP, realm or SPN. Leave blank for DNS discovery.");
        return name;
    }

    public Profile duplicate() {
        return new Profile(UUID.randomUUID(), name.substring(0, Math.min(75, name.length())) + " copy", host, port, "", username,
                automaticSignOn, rememberPassword, codePage, theme, effect, zoom, keymap, "",null,authentication,kerberosServer);
    }

    public Profile withLayout(String layout) {
        return new Profile(id, name, host, port, device, username, automaticSignOn, rememberPassword,
                codePage, theme, effect, zoom, keymap, layout,loginSource,authentication,kerberosServer);
    }

    public Profile withZoom(double value) {
        return new Profile(id, name, host, port, device, username, automaticSignOn, rememberPassword,
                codePage, theme, effect, value, keymap, savedLayout,loginSource,authentication,kerberosServer);
    }
    public Profile withDisplay(String palette,String textEffect) {
        return new Profile(id,name,host,port,device,username,automaticSignOn,rememberPassword,
                codePage,palette,textEffect,zoom,keymap,savedLayout,loginSource,authentication,kerberosServer);
    }
    public Profile withKeys(Map<String,String> keys) {
        return new Profile(id,name,host,port,device,username,automaticSignOn,rememberPassword,codePage,theme,effect,zoom,keys,savedLayout,loginSource,authentication,kerberosServer);
    }
    public static Profile login(Profile target,Collection<Profile> profiles) {
        if (target.loginSource==null) return target;
        Profile source=profiles.stream().filter(p -> p.id.equals(target.loginSource)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Login source is missing for "+target.name+". Choose an independent login or an existing source."));
        if (source.loginSource!=null) throw new IllegalArgumentException("Login references must point directly to an independent profile; chains/cycles are not allowed.");
        if (source.authentication!=Authentication.PASSWORD || source.username.isEmpty() || !source.rememberPassword)
            throw new IllegalArgumentException("Login source "+source.name+" must have a username and Save password enabled.");
        return source;
    }
    public static Profile effective(Profile target,Collection<Profile> profiles) {
        Profile login=login(target,profiles);
        if (login==target) return target;
        return new Profile(target.id,target.name,target.host,target.port,target.device,login.username,login.automaticSignOn,
                login.rememberPassword,target.codePage,target.theme,target.effect,target.zoom,target.keymap,target.savedLayout,
                null,target.authentication,target.kerberosServer);
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
        if (authentication==Authentication.KERBEROS && password.length!=0)
            throw new IllegalArgumentException("Kerberos never accepts a password.");
        if (automaticSignOn) {
            if (username.isEmpty()) throw new IllegalArgumentException("Enter a username for automatic sign-in, or turn it off.");
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
        if (authentication==Authentication.KERBEROS) throw new IllegalStateException("Kerberos does not use Windows Credential Manager passwords.");
        // Keep existing OS credentials reachable after the Fish rename.
        return "Pane5250/" + id + "/" + host + ":" + port + "/" + username;
    }
    @Override public String toString() { return name; }
}
