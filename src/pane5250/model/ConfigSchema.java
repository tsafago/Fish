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
        root.put("catalogVersion",1); root.put("workspaceVersion",StateStore.VERSION);
        root.put("storage","All values are XML strings; types below describe their logical values. Use export, edit, validate, apply.");
        root.put("maxBytes",StateStore.MAX_BYTES); root.put("unknownKeys","Rejected without writing. Future workspace versions are rejected, never downgraded.");
        root.put("metadata",Map.of("config.format",enumeration("1",List.of("1")),
                "config.baseRevision",field("sha256-or-absent",null,"Required export revision; never edit it. Apply/validate require a matching, closed workspace.")));
        fields.put("version",enumeration(StateStore.VERSION,List.of(StateStore.VERSION)));
        fields.put("profiles",field("comma-separated-uuid-list",null,"Ordered, nonempty, unique saved profile IDs; no passwords."));
        fields.put("layout",field("layout",null,"1-8 unique pane IDs referencing listed profiles; bounded Fish shape. See layoutGrammar."));
        fields.put("lastProfile",field("uuid",null,"Must be a listed profile ID."));
        fields.put("width",field("integer",1360,"330..10000 logical pixels."));
        fields.put("height",field("integer",850,"260..10000 logical pixels."));
        fields.put("appTheme",enumeration("Charcoal",Arrays.stream(AppTheme.values()).map(Enum::name).toList()));
        fields.put("highlightActive",field("boolean",true,"true or false; independent active-pane tint."));
        fields.put("connectOnStartup",field("boolean",false,"Connect each restored pane once on normal startup. Incomplete logins or missing saved passwords stay offline with a pane notice; no password prompts. Adding a pane never auto-connects."));
        fields.put("appearance.outline",field("boolean",true,"Active-pane outline; shared internal edges have one owner."));
        fields.put("appearance.windowOutline",field("boolean",true,"Whole-window outline; known maximize/restore defect remains."));
        fields.put("appearance.selected",field("text","","Empty for builtin appTheme, otherwise exact custom theme name."));
        fields.put("appearance.themes",field("integer",0,"0..32; all theme indices 0 through count-1 must be complete."));
        fields.put("appearance.retired",field("newline-separated-strokes","ctrl alt K","Former reserved shortcuts; only valid non-typing application strokes."));
        fields.put("font.family",field("text","Automatic","Automatic or an installed fixed-width family. Local file takes precedence."));
        fields.put("font.file",field("local-font-path","","Empty or a local TTF/OTF path readable on this machine; only its path, never font bytes, is exported."));
        fields.put("tutorial.status",enumeration("NEW",Arrays.stream(StateStore.Tutorial.values()).map(Enum::name).toList()));
        String[] flags={"header","footer","profile","connection","host","device","status","dimensions","cursor","insert"};
        Appearance.PaneInfo info=Appearance.PaneInfo.defaults();
        boolean[] defaults={info.header(),info.footer(),info.profile(),info.connection(),info.host(),info.device(),info.status(),info.dimensions(),info.cursor(),info.insert()};
        for (int i=0;i<flags.length;i++) fields.put("info."+flags[i],field("boolean",defaults[i],"true or false; hidden/empty information rows reserve no band."));
        profile.put("name",field("text","Local IBM i","1..80 characters; display name, not identity."));
        profile.put("host",field("hostname-or-ip","10.1.2.3","0..253 characters, no whitespace/control characters. Empty is offline/unconfigured. New-profile default only; existing hosts are unchanged."));
        profile.put("port",field("integer",23,"1..65535; plain TN5250 TCP only."));
        profile.put("device",field("ibmi-name","","Empty or [A-Z#$@][A-Z0-9_#$@]{0,9}; canonicalized uppercase."));
        profile.put("username",field("ibmi-name","","Same naming rule as device. Blank when loginSource is set."));
        profile.put("auto",field("boolean",true,"Recommended new-profile default: RFC 4777 plain automatic sign-on. Username required before connecting, not to save an unfinished profile. False when linked. Existing choices unchanged."));
        profile.put("remember",field("boolean",true,"Recommended new-profile default: allow OS password lookup/storage on Connect. False when linked. Configuration never reads, copies or deletes OS credentials. Existing choices unchanged."));
        profile.put("loginSource",field("uuid-or-empty","","Explicit source for THIS profile's host: independent source, nonempty username, remember=true. No self references, chains or cycles. Inherits login preferences."));
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
        root.put("shortcutPrefix","shortcut.{exact-action-title}");
        root.put("shortcutValue","Swing pressed stroke or empty to unbind. Explicit entries, even equal to current defaults, remain explicit.");
        root.put("actions",Shortcuts.commands().stream().map(c -> {
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("title",c.title()); data.put("default",c.stroke()); data.put("hostAction",c.host()==null ? null : c.host().name()); return data;
        }).toList());
        root.put("hostOverridePrefix","profile.{uuid}.key.{Swing pressed stroke}");
        root.put("hostOverrideValues",HostKeys.hostActions().stream().map(Enum::name).toList());
        root.put("hostOverrideRules","Empty disables one stroke. Ctrl+Alt and OS chords are reserved. Equivalent duplicate strokes are rejected.");
        root.put("hostDefaults",HostKeys.defaultBindings().entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().toString()))
                .map(e -> Map.of("stroke",e.getKey().toString(),"action",e.getValue().name())).toList());
        root.put("applicationAliases",Shortcuts.aliases());
        root.put("aliasRules","Available while the primary action retains its default chord, unless reserved by a host/application/retired binding. Never overrides user bindings.");
        root.put("layoutGrammar",Map.of("leaf","L|{pane-uuid}|{profile-uuid}","split","H|{ratio}|{first}|{second} or V|{ratio}|{first}|{second}",
                "ratio","finite 0.05..0.95","shape","1 full; 2 horizontal; 3 large left with vertically stacked right; 4-8 two rows, ceil(count/2) above. Each row is a right-nested horizontal chain."));
        root.put("semantics","Full snapshots, not partial resets. Apply only while Fish is closed, with matching revision and exclusive workspace.lock. Apply performs no connections/transactions/credential operations. Normal startup stays offline unless connectOnStartup is explicitly enabled.");
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
