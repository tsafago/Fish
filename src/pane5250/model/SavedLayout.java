package pane5250.model;

import java.util.*;

/** Reusable window recipe, never a presentation-space capture or a live connection. */
public record SavedLayout(UUID id,String name,StateStore.WindowState window,Map<UUID,Display> displays) {
    public static final int MAX_SAVED=64;
    public record Display(boolean fit,double zoom) {
        public Display {
            if (!Double.isFinite(zoom) || zoom<.5 || zoom>3) throw new IllegalArgumentException("Saved pane zoom must be 0.5-3.");
        }
    }
    public SavedLayout {
        Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(window);
        name=name.strip();
        if (name.isEmpty() || name.length()>80 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Layout name must be 1-80 printable characters.");
        List<Layout.Leaf> leaves=window.layout().leaves();
        if (leaves.isEmpty() || leaves.size()>Layout.MAX_PANES || !Layout.normalize(window.layout()).equals(window.layout()))
            throw new IllegalArgumentException("A saved layout needs 1-8 bounded panes.");
        Set<UUID> panes=new HashSet<>();
        for (Layout.Leaf leaf:leaves) if (!panes.add(leaf.pane())) throw new IllegalArgumentException("Duplicate saved pane ID.");
        displays=Map.copyOf(displays);
        if (!displays.keySet().equals(panes)) throw new IllegalArgumentException("Every saved pane needs exactly one fit/zoom setting.");
    }
    public void validateProfiles(Set<UUID> ids) {
        if (!ids.contains(window.lastProfile()) || !ids.contains(window.addProfile())
                || window.layout().leaves().stream().anyMatch(p -> !ids.contains(p.profile())))
            throw new IllegalArgumentException("Saved layout \""+name+"\" references a missing profile. Update or delete the layout first.");
    }
    public static UUID reference(String value) {
        if (value==null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Layout reference must be the saved layout's UUID, not a file or name.");
        return UUID.fromString(value);
    }
    public SavedLayout renamed(String value) { return new SavedLayout(id,value,window,displays); }
    @Override public String toString() { return name; }
}
