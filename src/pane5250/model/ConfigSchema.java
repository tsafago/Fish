package pane5250.model;

import java.util.*;

/** Typed catalog for the string entries in the canonical XML properties format. */
public final class ConfigSchema {
    private ConfigSchema() {}
    private static Map<String,Object> field(String type,Object value,String constraints) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("type",type); result.put("default",value); result.put("constraints",constraints);
        return result;
    }
    private static Map<String,Object> enumeration(Object value,List<?> allowed) {
        Map<String,Object> result=field("enum",value,"Exact case-sensitive value.");
        result.put("values",allowed); return result;
    }
    public static Map<String,Object> catalog() {
        Map<String,Object> root=new LinkedHashMap<>(), fields=new LinkedHashMap<>(), profile=new LinkedHashMap<>(), theme=new LinkedHashMap<>();
        root.put("format","Fish XML properties configuration");
        root.put("catalogVersion",8); root.put("workspaceVersion",StateStore.VERSION);
        root.put("freshWorkspaceProfile",Map.of("host","10.1.2.3","port",23,"authentication","KERBEROS",
                "kerberosServer","","auto",false,"remember",false,"loginSource","","connectOnStartup",true));
        root.put("newPasswordProfilePreferences",Map.of("auto",true,"remember",true,"password","Never created, copied or supplied automatically."));
        root.put("storage","All values are XML strings; types below describe their logical values. Use export, edit, validate, apply.");
        root.put("maxBytes",StateStore.MAX_BYTES); root.put("unknownKeys","Rejected without writing. Future workspace versions are rejected, never downgraded.");
        root.put("metadata",Map.of("config.format",enumeration("1",List.of("1")),
                "config.baseRevision",field("sha256-or-absent",null,"Required export revision; never edit it. Apply/validate require a matching, closed workspace.")));
        fields.put("version",enumeration(StateStore.VERSION,List.of(StateStore.VERSION)));
        fields.put("profiles",field("comma-separated-uuid-list",null,"Ordered, nonempty, unique saved profile IDs; no passwords."));
        fields.put("layout",field("layout",null,"1-8 unique pane IDs referencing listed profiles; bounded Fish shape. See layoutGrammar."));
        fields.put("lastProfile",field("uuid",null,"Must be a listed profile ID."));
        fields.put("addProfile",field("uuid",null,"This window's titlebar profile selection; must be a listed ID. Old formats initialize it from lastProfile. Selecting alone does not connect."));
        fields.put("savedLayouts",field("comma-separated-uuid-list","","Ordered, unique stable named-layout IDs, at most 64. Empty means none. Names must be unique ignoring case."));
        fields.put("selectedLayout",field("uuid-or-empty","","Empty or an ID from savedLayouts; management selection only, not a launch instruction."));
        fields.put("width",field("integer",1360,"330..10000 logical pixels."));
        fields.put("height",field("integer",850,"260..10000 logical pixels."));
        fields.put("windows",field("integer",0,"Additional independent windows; 0..20000. Total configuration remains at most 2 MB. Primary window uses layout/width/height/lastProfile."));
        fields.put("appTheme",enumeration("Charcoal",Arrays.stream(AppTheme.values()).map(Enum::name).toList()));
        fields.put("highlightActive",field("boolean",true,"true or false; independent active-pane tint."));
        fields.put("connectOnStartup",field("boolean",true,"Ordinary restored-window startup only. New/factory default on; existing off stays off. Explicit Add, New window, Restore profile layout and --layout connect their intended panes once asynchronously even when off. No automatic retries after failure/disconnect; incomplete login stays offline with Reconnect guidance. Kerberos still requires IBM i/domain setup."));
        fields.put("updates.startup",field("boolean",true,"Bounded nonblocking public GitHub release check. Errors stay quiet on startup. Download/install always requires explicit consent."));
        fields.put("updates.channel",enumeration("PREVIEW_PATCHES",Arrays.stream(UpdateSettings.Channel.values()).map(Enum::name).toList()));
        fields.put("updates.ignoredVersion",field("numeric-version-or-empty","","Exact X.Y.Z hidden only from startup notices; future versions notify again. Manual checks ignore this preference. PREVIEW_PATCHES permits this installed major/minor only; STABLE permits non-prereleases in this installed major. Neither channel installs a new major."));
        fields.put("appearance.outline",field("boolean",true,"Active-pane outline; shared internal edges have one owner."));
        fields.put("appearance.windowOutline",field("boolean",true,"Whole-window outline; known maximize/restore defect remains."));
        fields.put("appearance.cursorStyle",enumeration("IBM",Arrays.stream(Appearance.CursorStyle.values()).map(Enum::name).toList()));
        root.put("cursorStyles",Map.of("IBM","Underline in replace mode; filled block in Insert. IBM-documented mode-aware style, not a verified ACS default.",
                "OUTLINE","Outline in replace mode; filled block in Insert.","FILLED","Filled block in both modes."));
        fields.put("appearance.selected",field("text","","Empty for builtin appTheme, otherwise exact custom theme name."));
        fields.put("appearance.themes",field("integer",0,"0..32; all theme indices 0 through count-1 must be complete."));
        fields.put("appearance.retired",field("newline-separated-strokes","ctrl alt K","Former reserved shortcuts; only valid non-typing application strokes."));
        fields.put("font.family",field("text","Automatic","Automatic or an installed fixed-width family. Local file takes precedence."));
        fields.put("font.file",field("local-font-path","","Empty or a local TTF/OTF path readable on this machine; only its path, never font bytes, is exported."));
        fields.put("tutorial.status",enumeration("NEW",Arrays.stream(StateStore.Tutorial.values()).map(Enum::name).toList()));
        String[] flags={"header","footer","profile","connection","host","device","status","dimensions","cursor","insert","footerDevice"};
        Appearance.PaneInfo info=Appearance.PaneInfo.defaults();
        boolean[] defaults={info.header(),info.footer(),info.profile(),info.connection(),info.host(),info.device(),info.status(),info.dimensions(),info.cursor(),info.insert(),info.footerDevice()};
        for (int i=0;i<flags.length;i++) fields.put("info."+flags[i],field("boolean",defaults[i],"true or false; hidden/empty information rows reserve no band."));
        profile.put("name",field("text","Local IBM i","1..80 characters; display name, not identity."));
        profile.put("host",field("hostname-or-ip","10.1.2.3","0..253 characters, no whitespace/control characters. Empty is offline/unconfigured. New-profile default only; existing hosts are unchanged."));
        profile.put("port",field("integer",23,"1..65535; plain TN5250 TCP only."));
        profile.put("authentication",enumeration("KERBEROS",Arrays.stream(Profile.Authentication.values()).map(Enum::name).toList()));
        profile.put("kerberosServer",field("dns-name-or-empty","","Optional fully qualified DNS service identity, not an IP/realm/SPN; normalized lowercase. Kerberos SPN is krbsvr400/{DNS}. Blank uses a fully qualified host, or reverse DNS with forward confirmation for an IP. Windows chooses realm; no NTLM/password fallback."));
        profile.put("device",field("ibmi-name","","Empty or [A-Z#$@][A-Z0-9_#$@]{0,9}; canonicalized uppercase."));
        profile.put("username",field("ibmi-name","","Same naming rule as device. Blank when loginSource is set. Ignored for KERBEROS (Windows identity/EIM supplies the user)."));
        profile.put("auto",field("boolean",false,"False in the default KERBEROS profile; must also be false for linked profiles. New independent PASSWORD profiles default true; the editor initializes it once on first switch from Kerberos, then preserves choices. Existing PASSWORD off choices are unchanged. Username required for automatic sign-in, not manual sign-in."));
        profile.put("remember",field("boolean",false,"False for KERBEROS and linked profiles. New independent PASSWORD profiles default true; existing explicit off choices remain off. Configuration never touches OS credentials, creates passwords, or acquires tickets."));
        profile.put("loginSource",field("uuid-or-empty","","PASSWORD only: explicit independent PASSWORD source with username and remember=true for THIS target host. No self references, chains or cycles. KERBEROS must be empty; Windows identity is used directly."));
        profile.put("codePage",enumeration("37",Profile.CODE_PAGES));
        profile.put("theme",enumeration("Phosphor",Profile.THEMES));
        profile.put("effect",enumeration("None",Profile.EFFECTS));
        profile.put("zoom",field("number",1,"Finite 0.5..3; existing fit/manual behavior retained."));
        profile.put("layout",field("layout-or-empty","","Empty or saved bounded layout; every profile ID must exist."));
        theme.put("name",field("text",null,"1..64 printable characters; names unique ignoring case."));
        ColorTheme colors=ColorTheme.builtin(AppTheme.Charcoal);
        for (ColorRole role:ColorRole.values())
            theme.put(role.name(),field("rgb",String.format("#%06X",colors.rgb(role)),"Exactly six RGB hex digits, optionally prefixed #. Complete theme required."));
        root.put("properties",fields); root.put("profilePrefix","profile.{uuid}."); root.put("profileFields",profile);
        root.put("themePrefix","appearance.theme.{zero-based-index}."); root.put("themeFields",theme);
        root.put("windowPrefix","window.{zero-based-index}.");
        root.put("windowFields",Map.of("layout",fields.get("layout"),"width",fields.get("width"),"height",fields.get("height"),
                "lastProfile",fields.get("lastProfile"),"addProfile",fields.get("addProfile")));
        root.put("displayPrefix","pane.{pane-uuid}. or window.{index}.pane.{pane-uuid}.");
        root.put("displayFields",Map.of("fit",field("boolean",true,"Optional paired fit/zoom entries for a pane in the corresponding window; both or neither. No screen data."),
                "zoom",field("number",1,"Finite 0.5..3; captured per-pane manual zoom, used when fit=false.")));
        root.put("savedLayoutPrefix","savedLayout.{listed-layout-uuid}.");
        root.put("savedLayoutFields",Map.of("version",enumeration("3",List.of("3")),
                "name",field("text",null,"1..80 printable characters; unique ignoring case. Rename retains UUID/shortcuts."),
                "layout",fields.get("layout"),"width",fields.get("width"),"height",fields.get("height"),
                "lastProfile",fields.get("lastProfile"),"addProfile",fields.get("addProfile")));
        root.put("savedLayoutDisplayPrefix","savedLayout.{listed-layout-uuid}.pane.{pane-uuid}.");
        root.put("savedLayoutDisplayFields",Map.of("fit",field("boolean",true,"Required for every pane in this recipe; no extra pane IDs."),
                "zoom",field("number",1,"Required finite 0.5..3; no terminal contents, credentials or authenticated state.")));
        root.put("savedLayoutLaunch","Fish.exe --layout UUID [--data-dir DIRECTORY]. Exactly one new ordinary window with rekeyed fresh sessions; never filenames, names, hosts or executable configuration. Missing/deleted IDs fail explicitly. Exported .lnk retains the executable path and explicit data directory; moving portable Fish breaks its old target. Cold shortcut launches retain previous window restoration without opening it; saved collection appends the shortcut window for the next ordinary cold launch. Named recipes change only on explicit Update, not moving dividers.");
        root.put("shortcutPrefix","binding.{exact-action-title}");
        root.put("shortcutValue","Newline-separated exact pressed chords, or empty to unbind that action. Modifiers ctrl, shift, alt, altGraph; Java key names. ENTER is standard; ENTER@numpad (alias NUMPAD_ENTER) is keypad. Optional @standard/@numpad/@left/@right/@unknown action-key location; modifier sides are not distinguished. Explicit entries, even equal to defaults, stay explicit.");
        root.put("actions",Shortcuts.commands().stream().filter(c -> !Shortcuts.SETTINGS_ALIASES.contains(c.title())).map(c -> {
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("title",c.title()); data.put("default",KeyBindings.defaultText(c.title())); data.put("hostAction",c.host()==null ? null : c.host().name()); return data;
        }).toList());
        root.put("keyUnbinding",Map.of("property","binding."+KeyBindings.UNBOUND,"value","Newline-separated exact chords disabled only in terminals; empty removes these per-key suppressions."));
        root.put("keyRules","All bindings are application-wide. Custom keys replace their default owners; two explicit owners, duplicate equivalent keys, OS chords and unmodified typing keys for Fish actions are rejected. Host actions only dispatch in terminals; dialog text editing/IME are not intercepted. Unknown-location events use an explicit @unknown binding or standard fallback only.");
        root.put("keyboardRecoveryPrefix","keyboard.legacy.");
        root.put("keyboardRecoveryFields",Map.of("report","Retained actionable conflict report; at most 100000 characters.",
                "binding.{old-settings-action-title}","Original format-12 exact multiline list or empty; recovery only, never dispatched.",
                "shortcut.{old-action-title}","Original single Swing stroke or empty.",
                "profile.{uuid}.name","Original profile display name; independent of current/deleted profiles.",
                "profile.{uuid}.key.{old-Swing-stroke}","Original host mnemonic or empty. Recovery only, never dispatched."));
        root.put("keyboardMigration","Formats 1-11 migrate exact keys as before. Format 12 preserves exact key locations, multiline lists and recovery, then merges old settings action bindings into Settings...; originals remain under keyboard.legacy.binding.*. Explicit unbinding and other custom key owners are preserved. Profile key.*, shortcut.* and live old settings action bindings are rejected in formats 13-17.");
        root.put("windowMigration","Back up workspace.xml with ALL Fish windows closed before Fish 0.7.9. Format 18 / recipe 3 retains simple pane families and adds a typed cursor style. Formats through 17 gain IBM (underline replace / filled Insert). Unsupported format 1-16 geometry or extreme ratios normalize with a notice, retaining every pane/profile ID, saved-layout ID and display setting. Already supported shapes with reasonable intentional ratios remain; format-17/current invalid geometry is rejected. Formats 15/16 and recipes 1/2 migrate deliberately; do not relabel. Downgrade to Fish 0.7.8 or earlier requires restoring the matching backup.");
        root.put("layoutGrammar",Map.of("leaf","L|{pane-uuid}|{profile-uuid}","split","H|{ratio}|{first}|{second} or V|{ratio}|{first}|{second}",
                "ratio","Finite 0.05..0.95 at parse time; current layouts also require each pane's normalized width and height at least 60% of its balanced share. Structural changes reset balanced ratios. Three-strip root ratio measures its first share after both divider widths are excluded.",
                "shape","1 full; 2 equal-default halves, H or V; 3 equal-default rows/columns (first leaf then same-axis pair), or a half beside an opposite-axis pair, mirrored/rotated; 4 quadrants; 5-8 quadrants each splitting at most once into two. A quadrant grid has opposite-axis pairs under its root; each of four quadrant children is a leaf or one leaf pair. No deeper nesting. Centers swap. Whole-window edges arrange 2-3 panes; matching strip direction yields equal thirds, otherwise a half and pair. Quadrant edges fill available half-slots or swap/reorient existing slots without subdivision. UI drops require at least 70x70 logical pixels per pane."));
        root.put("settingsEditing","Valid Settings edits apply immediately after a short coalescing pause and persist through the existing exclusive/revision-checked writer. Saved acknowledges disk success. Invalid partial groups retain their previous usable state; stale edits require reload, failed saves retain Retry. New profiles, password-store operations, destructive actions and file exports require explicit actions. No setting edit connects a session.");
        root.put("semantics","Full snapshots, not partial resets. Apply only while Fish is closed, with matching revision and exclusive workspace.lock. Configuration apply performs no connections/transactions/credential operations. Normal startup follows the saved startup preference; only new/factory workspaces default on.");
        return root;
    }
    public static String document() { return json(catalog(),0,true)+"\n"; }
    public static String json(Object value) { return json(value,0,false); }
    private static String json(Object value,int depth,boolean pretty) {
        if (value==null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?,?> map) {
            List<String> entries=new ArrayList<>();
            map.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().toString())).forEach(e ->
                    entries.add(json(e.getKey().toString())+(pretty ? ": " : ":")+json(e.getValue(),depth+1,pretty)));
            return container("{","}",entries,depth,pretty);
        }
        if (value instanceof Collection<?> list)
            return container("[","]",list.stream().map(v -> json(v,depth+1,pretty)).toList(),depth,pretty);
        StringBuilder text=new StringBuilder("\"");
        for (char c:value.toString().toCharArray()) {
            switch (c) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                default -> { if (c<32) text.append(String.format("\\u%04x",(int)c)); else text.append(c); }
            }
        }
        return text.append('"').toString();
    }
    private static String container(String start,String end,List<String> items,int depth,boolean pretty) {
        if (!pretty || items.isEmpty()) return start+String.join(",",items)+end;
        String indent="  ".repeat(depth+1);
        return start+"\n"+indent+String.join(",\n"+indent,items)+"\n"+"  ".repeat(depth)+end;
    }
}
