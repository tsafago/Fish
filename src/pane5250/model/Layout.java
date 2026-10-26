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

    default Layout remove(UUID pane) {
        if (this instanceof Empty) return this;
        if (this instanceof Leaf leaf) return leaf.pane.equals(pane) ? null : leaf;
        Split split = (Split) this;
        Layout first = split.first.remove(pane), second = split.second.remove(pane);
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
        if (source.equals(target)) return this;
        Leaf from = leaves().stream().filter(l -> l.pane.equals(source)).findFirst().orElseThrow();
        Leaf to = leaves().stream().filter(l -> l.pane.equals(target)).findFirst().orElseThrow();
        if (dock == Dock.CENTER) return swap(source, target, from, to);
        return Objects.requireNonNull(remove(source)).insert(target, from, dock);
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
