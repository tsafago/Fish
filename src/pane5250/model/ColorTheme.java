package pane5250.model;

import java.util.*;

/** Complete, named RGB theme; partial themes cannot be saved or applied. */
public record ColorTheme(String name,Map<ColorRole,Integer> colors) {
    public ColorTheme {
        name=name.strip();
        if (name.isEmpty() || name.length()>64 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Theme name must be 1-64 printable characters.");
        if (colors.size()!=ColorRole.values().length || !colors.keySet().containsAll(EnumSet.allOf(ColorRole.class)))
            throw new IllegalArgumentException("Theme must define every color role.");
        if (colors.values().stream().anyMatch(v -> v==null || v<0 || v>0xffffff))
            throw new IllegalArgumentException("Colors must be six-digit RGB values.");
        colors=Map.copyOf(colors);
    }
    public int rgb(ColorRole role) { return colors.get(role); }
    public static int parse(String value) {
        if (!value.matches("#?[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Use #RRGGBB, for example #A4C8D0.");
        return Integer.parseInt(value.replace("#",""),16);
    }
    public static ColorTheme builtin(AppTheme theme) {
        EnumMap<ColorRole,Integer> c=new EnumMap<>(ColorRole.class);
        for (ColorRole role:ColorRole.values()) c.put(role,theme.text);
        c.put(ColorRole.BACKGROUND,theme.background); c.put(ColorRole.PANEL,theme.panel);
        c.put(ColorRole.FIELD,theme.field); c.put(ColorRole.DIALOG,theme.panel);
        c.put(ColorRole.TEXT,theme.text); c.put(ColorRole.MUTED,theme.muted); c.put(ColorRole.DISABLED,theme.muted);
        c.put(ColorRole.LINE,theme.line); c.put(ColorRole.HOVER,theme.hover); c.put(ColorRole.PRESSED,theme.selection);
        c.put(ColorRole.FOCUS,theme.focus); c.put(ColorRole.ERROR,theme.error);
        c.put(ColorRole.SELECTION,theme.selection); c.put(ColorRole.SELECTION_TEXT,theme.text);
        c.put(ColorRole.CHROME,theme.panel); c.put(ColorRole.CHROME_TEXT,theme.text); c.put(ColorRole.CHROME_INACTIVE,theme.muted);
        c.put(ColorRole.MENU,theme.panel); c.put(ColorRole.MENU_TEXT,theme.text);
        c.put(ColorRole.MENU_HOVER,theme.hover); c.put(ColorRole.MENU_SELECTED_TEXT,theme.text);
        c.put(ColorRole.TOOLTIP,theme.panel); c.put(ColorRole.TOOLTIP_TEXT,theme.text);
        c.put(ColorRole.HEADER,theme.background); c.put(ColorRole.HEADER_TEXT,theme.text); c.put(ColorRole.HEADER_MUTED,theme.muted);
        c.put(ColorRole.FOOTER,theme.background); c.put(ColorRole.FOOTER_TEXT,theme.text);
        c.put(ColorRole.STATUS_ERROR,theme.error); c.put(ColorRole.STATUS_WAIT,theme.muted);
        c.put(ColorRole.OUTLINE,theme.focus);
        c.put(ColorRole.ACTIVE_TINT,mix(theme.background,theme.focus,1/25.0));
        if (theme.background==0) c.put(ColorRole.ACTIVE_TINT,0);
        c.put(ColorRole.DIVIDER,theme.line); c.put(ColorRole.DIVIDER_BACKGROUND,theme.background);
        c.put(ColorRole.CURSOR,theme.focus); c.put(ColorRole.TERMINAL_SELECTION,theme.selection);
        int[] host=theme.dark ? new int[]{theme.background,0x8daefa,0x9bdb98,0x84d9df,0xf69b9b,0xd8a0db,0xe3d78c,0xe4e5df,
                0xb5b9bc,0xacc7ff,0xb1edab,0xa0f0ee,0xffb4b4,0xf4b8f2,0xffeca3,0xffffff}
                : new int[]{theme.background,0x244aab,0x286033,0x00646e,0x9e2535,0x753583,0x705600,0x27323a,
                0x4d5662,0x234c91,0x245b2a,0x00575a,0x9f2332,0x74288a,0x775200,0x141c24};
        for (int i=0;i<16;i++) c.put(ColorRole.valueOf("HOST_"+i),host[i]);
        int[] gradient={0x63dfc5,0x79a9ff,0xc795ff,0xf394bf,0xffc477,0x82e0ad};
        for (int i=0;i<6;i++) c.put(ColorRole.valueOf("GRADIENT_"+(i+1)),gradient[i]);
        c.put(ColorRole.GLOW,theme.focus);
        c.put(ColorRole.LOGO_1,0x69cbbe); c.put(ColorRole.LOGO_2,0x83a7e8); c.put(ColorRole.LOGO_3,0xcf92b9);
        return new ColorTheme(theme.toString(),c);
    }
    public static int mix(int first,int second,double amount) {
        int r=(int)(((first>>16)&255)*(1-amount)+((second>>16)&255)*amount);
        int g=(int)(((first>>8)&255)*(1-amount)+((second>>8)&255)*amount);
        int b=(int)((first&255)*(1-amount)+(second&255)*amount);
        return r<<16|g<<8|b;
    }
    @Override public String toString() { return name; }
}
