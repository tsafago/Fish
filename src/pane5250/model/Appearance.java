package pane5250.model;

import java.util.*;

/** Workspace-wide appearance; profiles retain their host palette and effect. */
public record Appearance(boolean outline,boolean windowOutline,PaneInfo info,FontChoice font,String selectedTheme,List<ColorTheme> themes,
                         Set<String> retiredShortcuts) {
    public record PaneInfo(boolean header,boolean footer,boolean profile,boolean connection,
                           boolean host,boolean device,boolean status,boolean dimensions,boolean cursor,boolean insert) {
        public static PaneInfo defaults() { return new PaneInfo(true,true,true,true,true,false,true,true,true,true); }
    }
    public record FontChoice(String family,String file) {
        public FontChoice { Objects.requireNonNull(family); Objects.requireNonNull(file); }
        public static FontChoice defaults() { return new FontChoice("Automatic",""); }
    }
    public Appearance {
        Objects.requireNonNull(info); Objects.requireNonNull(font); Objects.requireNonNull(selectedTheme);
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
        return new Appearance(true,false,PaneInfo.defaults(),FontChoice.defaults(),"",List.of(),Set.of("ctrl alt K"));
    }
    public Appearance withTheme(String selected,List<ColorTheme> values) { return new Appearance(outline,windowOutline,info,font,selected,values,retiredShortcuts); }
    public Appearance withLayout(boolean border,PaneInfo values) { return new Appearance(border,windowOutline,values,font,selectedTheme,themes,retiredShortcuts); }
    public Appearance withWindowOutline(boolean value) { return new Appearance(outline,value,info,font,selectedTheme,themes,retiredShortcuts); }
    public Appearance withFont(FontChoice value) { return new Appearance(outline,windowOutline,info,value,selectedTheme,themes,retiredShortcuts); }
    public Optional<ColorTheme> custom() { return themes.stream().filter(t -> t.name().equals(selectedTheme)).findFirst(); }
}
