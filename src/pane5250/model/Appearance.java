package pane5250.model;

import java.util.*;

/** Workspace-wide appearance; profiles retain their host palette and effect. */
public record Appearance(boolean outline,boolean windowOutline,PaneInfo info,FontChoice font,String selectedTheme,List<ColorTheme> themes,
                         Set<String> retiredShortcuts,CursorStyle cursorStyle) {
    public enum CursorStyle {
        IBM("Underline / filled Insert"),OUTLINE("Outline / filled Insert"),FILLED("Filled");
        private final String label;
        CursorStyle(String label) { this.label=label; }
        @Override public String toString() { return label; }
    }
    public Appearance(boolean outline,boolean windowOutline,PaneInfo info,FontChoice font,String selectedTheme,List<ColorTheme> themes,
                      Set<String> retiredShortcuts) {
        this(outline,windowOutline,info,font,selectedTheme,themes,retiredShortcuts,CursorStyle.IBM);
    }
    public record PaneInfo(boolean header,boolean footer,boolean profile,boolean connection,
                           boolean host,boolean device,boolean status,boolean dimensions,boolean cursor,boolean insert,boolean footerDevice) {
        public PaneInfo(boolean header,boolean footer,boolean profile,boolean connection,boolean host,boolean device,
                        boolean status,boolean dimensions,boolean cursor,boolean insert) {
            this(header,footer,profile,connection,host,device,status,dimensions,cursor,insert,false);
        }
        public static PaneInfo defaults() { return new PaneInfo(true,true,true,false,false,false,true,true,true,false,true); }
    }
    public record FontChoice(String family,String file) {
        public FontChoice { Objects.requireNonNull(family); Objects.requireNonNull(file); }
        public static FontChoice defaults() { return new FontChoice("Automatic",""); }
    }
    public Appearance {
        Objects.requireNonNull(info); Objects.requireNonNull(font); Objects.requireNonNull(selectedTheme);
        Objects.requireNonNull(cursorStyle);
        themes=List.copyOf(themes);
        if (themes.size()>32) throw new IllegalArgumentException("Up to 32 custom themes are supported.");
        retiredShortcuts=Set.copyOf(retiredShortcuts);
        Set<String> names=new HashSet<>();
        for (ColorTheme theme:themes) if (!names.add(theme.name().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Custom theme names must be unique.");
        if (!selectedTheme.isEmpty() && themes.stream().noneMatch(t -> t.name().equals(selectedTheme)))
            throw new IllegalArgumentException("Selected custom theme is missing.");
    }
    public static Appearance defaults() {
        return new Appearance(true,true,PaneInfo.defaults(),FontChoice.defaults(),"",List.of(),Set.of("ctrl alt K"));
    }
    public Appearance withTheme(String selected,List<ColorTheme> values) { return new Appearance(outline,windowOutline,info,font,selected,values,retiredShortcuts,cursorStyle); }
    public Appearance withLayout(boolean border,PaneInfo values) { return new Appearance(border,windowOutline,values,font,selectedTheme,themes,retiredShortcuts,cursorStyle); }
    public Appearance withWindowOutline(boolean value) { return new Appearance(outline,value,info,font,selectedTheme,themes,retiredShortcuts,cursorStyle); }
    public Appearance withFont(FontChoice value) { return new Appearance(outline,windowOutline,info,value,selectedTheme,themes,retiredShortcuts,cursorStyle); }
    public Appearance withCursorStyle(CursorStyle value) { return new Appearance(outline,windowOutline,info,font,selectedTheme,themes,retiredShortcuts,value); }
    public Optional<ColorTheme> custom() { return themes.stream().filter(t -> t.name().equals(selectedTheme)).findFirst(); }
}
