package pane5250.model;

import java.util.*;
import java.util.function.UnaryOperator;

public sealed interface Layout permits Layout.Empty, Layout.Leaf, Layout.Split {
    record Empty() implements Layout {}
    record Leaf(UUID pane, UUID profile) implements Layout {
        public Leaf { Objects.requireNonNull(pane); Objects.requireNonNull(profile); }
    }
    record Split(boolean horizontal, double ratio, Layout first, Layout second) implements Layout {
        public Split {
            if (!Double.isFinite(ratio) || ratio < 0.05 || ratio > 0.95)
                throw new IllegalArgumentException("Invalid divider ratio.");
            Objects.requireNonNull(first);
            Objects.requireNonNull(second);
            if (first instanceof Empty || second instanceof Empty) throw new IllegalArgumentException("Empty split child.");
        }
    }
    enum Dock { CENTER, LEFT, RIGHT, TOP, BOTTOM }
    enum Scope { PANE, WINDOW }
    int MAX_PANES = 8;

    static Layout bounded(List<Leaf> slots) {
        if (slots.isEmpty() || slots.size()>MAX_PANES)
            throw new IllegalArgumentException("Fish supports 1-8 panes. Original layout and presets have not been discarded.");
        if (slots.size()==1) return slots.getFirst();
        if (slots.size()==2) return pair(true,slots.get(0),slots.get(1));
        if (slots.size()==3) return new Split(true,.5,slots.getFirst(),
                new Split(false,.5,slots.get(1),slots.get(2)));
        List<Layout> quarters=new ArrayList<>();
        int extra=slots.size()-4,index=0;
        for (int q=0;q<4;q++) quarters.add(q<extra
                ? pair(true,slots.get(index++),slots.get(index++)) : slots.get(index++));
        return grid(quarters,false);
    }
    private static Layout legacyRow(List<Leaf> slots) {
        if (slots.size()==1) return slots.getFirst();
        return new Split(true,1.0/slots.size(),slots.getFirst(),legacyRow(slots.subList(1,slots.size())));
    }
    private static Layout pair(boolean horizontal,Layout first,Layout second) {
        return new Split(horizontal,.5,first,second);
    }
    private static Layout strips(List<Leaf> leaves,boolean horizontal) {
        return new Split(horizontal,1.0/3,leaves.get(0),pair(horizontal,leaves.get(1),leaves.get(2)));
    }
    private static Layout grid(List<Layout> quarters,boolean horizontal) {
        return horizontal
                ? pair(true,pair(false,quarters.get(0),quarters.get(2)),pair(false,quarters.get(1),quarters.get(3)))
                : pair(false,pair(true,quarters.get(0),quarters.get(1)),pair(true,quarters.get(2),quarters.get(3)));
    }
    private static List<Layout> quarters(Layout layout) {
        Split root=(Split)layout,a=(Split)root.first,b=(Split)root.second;
        return new ArrayList<>(root.horizontal ? List.of(a.first,b.first,a.second,b.second)
                : List.of(a.first,a.second,b.first,b.second));
    }
    private static boolean two(Layout node) {
        return node instanceof Split s && s.first instanceof Leaf && s.second instanceof Leaf;
    }
    private static boolean threeStrips(Layout node) {
        return node instanceof Split s && s.first instanceof Leaf && two(s.second)
                && ((Split)s.second).horizontal==s.horizontal;
    }
    private static boolean simpleShape(Layout layout) {
        int count=layout.leaves().size();
        if (count==1) return layout instanceof Leaf;
        if (!(layout instanceof Split s)) return false;
        if (count==2) return two(s);
        if (count==3) return threeStrips(s)
                || s.first instanceof Leaf && two(s.second) && ((Split)s.second).horizontal!=s.horizontal
                || s.second instanceof Leaf && two(s.first) && ((Split)s.first).horizontal!=s.horizontal;
        if (count<4 || count>MAX_PANES || !(s.first instanceof Split a) || !(s.second instanceof Split b)
                || a.horizontal==s.horizontal || b.horizontal==s.horizontal) return false;
        return quarters(s).stream().allMatch(q -> q instanceof Leaf || two(q));
    }
    static Layout equalize(Layout layout) {
        if (!(layout instanceof Split s)) return layout;
        return new Split(s.horizontal,threeStrips(s) ? 1.0/3 : .5,equalize(s.first),equalize(s.second));
    }
    static Layout normalize(Layout layout) {
        if (constrained(layout)) return layout;
        if (simpleShape(layout)) return equalize(layout);
        List<Leaf> leaves=layout.leaves();
        if (leaves.size()==3 && layout instanceof Split s
                && (s.first instanceof Split a && a.horizontal==s.horizontal
                    || s.second instanceof Split b && b.horizontal==s.horizontal)) return strips(leaves,s.horizontal);
        return bounded(leaves);
    }
    static boolean constrained(Layout layout) {
        if (!simpleShape(layout)) return false;
        Map<UUID,double[]> actual=new HashMap<>(),balanced=new HashMap<>();
        fractions(layout,1,1,actual); fractions(equalize(layout),1,1,balanced);
        return actual.entrySet().stream().allMatch(entry -> {
            double[] expected=balanced.get(entry.getKey()),value=entry.getValue();
            return value[0]+1e-9>=expected[0]*.6 && value[1]+1e-9>=expected[1]*.6;
        });
    }
    private static void fractions(Layout layout,double width,double height,Map<UUID,double[]> result) {
        if (layout instanceof Leaf leaf) { result.put(leaf.pane,new double[]{width,height}); return; }
        Split s=(Split)layout;
        fractions(s.first,width*(s.horizontal ? s.ratio : 1),height*(s.horizontal ? 1 : s.ratio),result);
        fractions(s.second,width*(s.horizontal ? 1-s.ratio : 1),height*(s.horizontal ? 1 : 1-s.ratio),result);
    }
    static int firstPixels(Split split,int extent) {
        return firstPixels(split,extent,split.ratio);
    }
    static int firstPixels(Split split,int extent,double ratio) {
        // A three-strip root must reserve both divider gaps before dividing its first share.
        return (int)Math.round(Math.max(0,extent-4-(threeStrips(split) ? 4 : 0))*ratio);
    }
    static double dividerRatio(boolean threeStrips,int location,int extent) {
        return location/(double)Math.max(1,extent-4-(threeStrips ? 4 : 0));
    }
    static boolean isThreeStrips(Layout layout) { return threeStrips(layout); }
    default String arrangement() {
        if (leaves().size()==1) return "Full window";
        if (leaves().size()==2) return "Equal halves";
        if (threeStrips(this)) return ((Split)this).horizontal ? "Three equal columns" : "Three equal rows";
        return leaves().size()==3 ? "Half and pair" : leaves().size()==4 ? "Quadrants" : "Split quadrants";
    }
    static boolean legacyShape(Layout layout) {
        List<Leaf> leaves=layout.leaves(); int size=leaves.size();
        if (size<1 || size>MAX_PANES) return false;
        Layout expected=size<4 ? bounded(leaves) : pair(false,legacyRow(leaves.subList(0,(size+1)/2)),
                legacyRow(leaves.subList((size+1)/2,size)));
        return sameShape(layout,expected);
    }
    default Layout add(Leaf added,UUID active) {
        return add(added,active,next -> true);
    }
    default Layout add(Leaf added,UUID active,java.util.function.Predicate<Layout> fits) {
        if (leaves().size()>=MAX_PANES) throw new IllegalArgumentException("Eight panes open; close one before adding.");
        if (leaves().stream().anyMatch(leaf -> leaf.pane.equals(added.pane))) throw new IllegalArgumentException("Duplicate pane ID.");
        if (leaves().stream().noneMatch(leaf -> leaf.pane.equals(active))) throw new IllegalArgumentException("Active pane is missing.");
        Layout current=equalize(normalize(this));
        if (current.leaves().size()<3) {
            Layout next=current instanceof Leaf ? pair(true,current,added)
                    : current.insert(active,added,((Split)current).horizontal ? Dock.BOTTOM : Dock.RIGHT);
            if (constrained(next) && fits.test(next)) return next;
        } else if (current.leaves().size()==3) {
            Split s=(Split)current;
            List<Leaf> order=new ArrayList<>(current.leaves()); order.add(added);
            Layout next=threeStrips(s) ? bounded(order)
                    : new Split(s.horizontal,.5,s.first instanceof Leaf ? pair(!s.horizontal,s.first,added) : s.first,
                            s.second instanceof Leaf ? pair(!s.horizontal,s.second,added) : s.second);
            if (fits.test(next)) return next;
        } else {
            List<Layout> quarters=quarters(current);
            List<Integer> order=new ArrayList<>(List.of(0,1,2,3));
            order.sort(Comparator.comparing(q -> quarters.get(q).leaves().stream().noneMatch(leaf -> leaf.pane.equals(active))));
            for (int q:order) if (quarters.get(q) instanceof Leaf leaf)
                for (boolean horizontal:List.of(true,false)) {
                    List<Layout> next=new ArrayList<>(quarters); next.set(q,pair(horizontal,leaf,added));
                    Layout candidate=grid(next,((Split)current).horizontal);
                    if (fits.test(candidate)) return candidate;
                }
        }
        throw new IllegalArgumentException("No room for another simple pane. Enlarge the window; each quadrant splits only once.");
    }
    private static boolean sameShape(Layout a,Layout b) {
        if (a instanceof Leaf) return b instanceof Leaf;
        return a instanceof Split x && b instanceof Split y && x.horizontal==y.horizontal
                && sameShape(x.first,y.first) && sameShape(x.second,y.second);
    }

    default List<Leaf> leaves() {
        if (this instanceof Empty) return List.of();
        if (this instanceof Leaf leaf) return List.of(leaf);
        Split split = (Split) this;
        List<Leaf> result = new ArrayList<>(split.first.leaves());
        result.addAll(split.second.leaves());
        return List.copyOf(result);
    }

    default Layout mapLeaf(UUID pane, UnaryOperator<Leaf> update) {
        if (this instanceof Empty) return this;
        if (this instanceof Leaf leaf) return leaf.pane.equals(pane) ? update.apply(leaf) : leaf;
        Split split = (Split) this;
        return new Split(split.horizontal, split.ratio, split.first.mapLeaf(pane, update), split.second.mapLeaf(pane, update));
    }

    default Layout replaceProfile(UUID removed,UUID replacement) {
        if (this instanceof Empty) return this;
        if (this instanceof Leaf leaf) return leaf.profile.equals(removed) ? new Leaf(leaf.pane,replacement) : leaf;
        Split split=(Split)this;
        return new Split(split.horizontal,split.ratio,split.first.replaceProfile(removed,replacement),
                split.second.replaceProfile(removed,replacement));
    }

    default Layout remove(UUID pane) {
        if (leaves().stream().noneMatch(leaf -> leaf.pane.equals(pane))) return this;
        if (leaves().size()<=4) {
            Layout result=removeRaw(pane);
            return result==null ? null : equalize(normalize(result));
        }
        List<Layout> quarters=quarters(this);
        int removed=quarter(quarters,pane);
        Layout remaining=quarters.get(removed).removeRaw(pane); quarters.set(removed,remaining);
        if (remaining==null) {
            int donor=-1,distance=Integer.MAX_VALUE;
            for (int q=0;q<4;q++) if (two(quarters.get(q))) {
                int next=Math.abs(q%2-removed%2)+Math.abs(q/2-removed/2);
                if (next<distance) { donor=q; distance=next; }
            }
            Split split=(Split)quarters.get(donor);
            boolean before=removed<donor;
            quarters.set(removed,before ? split.first : split.second);
            quarters.set(donor,before ? split.second : split.first);
        }
        return grid(quarters,((Split)this).horizontal);
    }
    private static int quarter(List<Layout> quarters,UUID pane) {
        for (int q=0;q<4;q++) if (quarters.get(q).leaves().stream().anyMatch(leaf -> leaf.pane.equals(pane))) return q;
        throw new IllegalArgumentException("Pane is missing.");
    }
    private Layout removeRaw(UUID pane) {
        if (this instanceof Empty) return this;
        if (this instanceof Leaf leaf) return leaf.pane.equals(pane) ? null : leaf;
        Split split = (Split) this;
        Layout first = split.first.removeRaw(pane), second = split.second.removeRaw(pane);
        if (first == null) return second;
        if (second == null) return first;
        return new Split(split.horizontal, split.ratio, first, second);
    }

    default Layout insert(UUID target, Leaf added, Dock dock) {
        if (this instanceof Empty) return added;
        if (this instanceof Leaf leaf) {
            if (!leaf.pane.equals(target)) return leaf;
            boolean before = dock == Dock.LEFT || dock == Dock.TOP;
            return new Split(dock == Dock.LEFT || dock == Dock.RIGHT, .5,
                    before ? added : leaf, before ? leaf : added);
        }
        Split split = (Split) this;
        return new Split(split.horizontal, split.ratio, split.first.insert(target, added, dock),
                split.second.insert(target, added, dock));
    }

    default Layout move(UUID source, UUID target, Dock dock) {
        return move(source,target,dock,Scope.PANE);
    }
    default Layout move(UUID source,UUID target,Dock dock,Scope scope) {
        Leaf from=leaves().stream().filter(l -> l.pane.equals(source)).findFirst().orElseThrow(() -> new IllegalArgumentException("Source pane is missing."));
        if (scope==Scope.WINDOW) {
            if (dock==Dock.CENTER || leaves().size()>3 || leaves().size()<2)
                throw new IllegalArgumentException("Whole-window edges arrange two or three panes. Use quadrant edges or centers for four to eight.");
            boolean horizontal=dock==Dock.LEFT || dock==Dock.RIGHT,before=dock==Dock.LEFT || dock==Dock.TOP;
            Layout rest=Objects.requireNonNull(removeRaw(source));
            if (rest instanceof Leaf) return pair(horizontal,before ? from : rest,before ? rest : from);
            if (((Split)rest).horizontal==horizontal) {
                List<Leaf> order=new ArrayList<>(rest.leaves()); order.add(before ? 0 : order.size(),from);
                return strips(order,horizontal);
            }
            return pair(horizontal,before ? from : equalize(rest),before ? equalize(rest) : from);
        }
        if (source.equals(target)) return this;
        Leaf to = leaves().stream().filter(l -> l.pane.equals(target)).findFirst().orElseThrow(() -> new IllegalArgumentException("Target pane is missing."));
        if (dock == Dock.CENTER) return swap(source, target, from, to);
        if (leaves().size()<4) return equalize(normalize(Objects.requireNonNull(removeRaw(source)).insert(target,from,dock)));
        List<Layout> quarters=quarters(this);
        int a=quarter(quarters,source),b=quarter(quarters,target);
        if (a!=b && two(quarters.get(a)) && quarters.get(b) instanceof Leaf) {
            quarters.set(a,Objects.requireNonNull(quarters.get(a).removeRaw(source)));
            quarters.set(b,to.insert(target,from,dock));
        } else {
            Layout swapped=swap(source,target,from,to);
            quarters=quarters(swapped);
            if (two(quarters.get(b))) {
                Leaf other=quarters.get(b).leaves().stream().filter(l -> !l.pane.equals(source)).findFirst().orElseThrow();
                quarters.set(b,other.insert(other.pane,from,dock));
            }
        }
        return grid(quarters,((Split)this).horizontal);
    }

    private Layout swap(UUID source, UUID target, Leaf from, Leaf to) {
        if (this instanceof Leaf leaf) {
            if (leaf.pane.equals(source)) return to;
            return leaf.pane.equals(target) ? from : leaf;
        }
        Split split = (Split) this;
        return new Split(split.horizontal, split.ratio, split.first.swap(source, target, from, to),
                split.second.swap(source, target, from, to));
    }

    default String encode() {
        if (this instanceof Empty) return "E";
        if (this instanceof Leaf leaf) return "L|" + leaf.pane + "|" + leaf.profile;
        Split split = (Split) this;
        return (split.horizontal ? "H|" : "V|") + split.ratio + "|" + split.first.encode() + "|" + split.second.encode();
    }

    static Layout decode(String value) {
        var parser = new Parser(value.split("\\|", -1));
        Layout layout = parser.read(0);
        if (parser.index != parser.tokens.length) throw new IllegalArgumentException("Extra layout data.");
        Set<UUID> ids = new HashSet<>();
        for (Leaf leaf : layout.leaves()) {
            if (!ids.add(leaf.pane)) throw new IllegalArgumentException("Duplicate pane ID in layout.");
        }
        return layout;
    }

    final class Parser {
        private final String[] tokens;
        private int index;
        Parser(String[] tokens) { this.tokens = tokens; }
        String next() {
            if (index >= tokens.length) throw new IllegalArgumentException("Truncated layout.");
            return tokens[index++];
        }
        Layout read(int depth) {
            if (depth > 64) throw new IllegalArgumentException("Layout exceeds safe nesting depth.");
            String type = next();
            if (type.equals("E")) return new Empty();
            if (type.equals("L")) return new Leaf(UUID.fromString(next()), UUID.fromString(next()));
            if (!type.equals("H") && !type.equals("V")) throw new IllegalArgumentException("Unknown layout node.");
            double ratio = Double.parseDouble(next());
            return new Split(type.equals("H"), ratio, read(depth + 1), read(depth + 1));
        }
    }
}
