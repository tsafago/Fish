package pane5250.model;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

public final class StateStore implements AutoCloseable {
    public record State(List<Profile> profiles, Layout layout, int width, int height, AppTheme appTheme) {
        public State(List<Profile> profiles, Layout layout, int width, int height) {
            this(profiles,layout,width,height,AppTheme.Charcoal);
        }
        public State {
            Objects.requireNonNull(appTheme);
            profiles = List.copyOf(profiles);
            if (profiles.isEmpty()) throw new IllegalArgumentException("A workspace needs at least one saved profile.");
            Set<UUID> ids = new HashSet<>();
            for (Profile p : profiles) {
                if (!ids.add(p.id())) throw new IllegalArgumentException("Duplicate profile ID.");
            }
            for (Layout.Leaf leaf : layout.leaves()) {
                if (!ids.contains(leaf.profile())) throw new IllegalArgumentException("Layout references a missing profile.");
            }
        }
    }
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;

    public StateStore(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("workspace.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (OverlappingFileLockException e) { acquired = null; }
        if (acquired == null) {
            lockChannel.close();
            throw new IOException("This workspace is already open. Use a different --data-dir for another instance.");
        }
        lock = acquired;
    }

    public Path file() { return directory.resolve("workspace.xml"); }

    public State load() throws IOException {
        if (!Files.exists(file())) {
            Profile profile = Profile.create("Local IBM i");
            return new State(List.of(profile), new Layout.Leaf(UUID.randomUUID(), profile.id()), 1360, 850);
        }
        if (Files.size(file()) > 2_000_000) throw new IOException("Workspace file is unexpectedly large.");
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file())) { properties.loadFromXML(in); }
        try {
            String version = properties.getProperty("version");
            if (!"1".equals(version) && !"2".equals(version)) throw new IllegalArgumentException("Unknown workspace version.");
            List<Profile> profiles = new ArrayList<>();
            for (String idText : required(properties, "profiles").split(",")) {
                UUID id = UUID.fromString(idText);
                String key = "profile." + id + ".";
                Map<String, String> keys = new LinkedHashMap<>();
                for (String property : properties.stringPropertyNames()) {
                    if (property.startsWith(key + "key."))
                        keys.put(property.substring((key + "key.").length()), properties.getProperty(property));
                }
                profiles.add(new Profile(id, required(properties, key + "name"), required(properties, key + "host"),
                        Integer.parseInt(required(properties, key + "port")), required(properties, key + "device"),
                        required(properties, key + "username"), bool(properties, key + "auto"), bool(properties, key + "remember"),
                        required(properties, key + "codePage"), required(properties, key + "theme"),
                        required(properties, key + "effect"), Double.parseDouble(required(properties, key + "zoom")),
                        keys, required(properties, key + "layout")));
            }
            return new State(profiles, Layout.decode(required(properties, "layout")),
                    Math.max(640, Math.min(10000, Integer.parseInt(required(properties, "width")))),
                    Math.max(400, Math.min(10000, Integer.parseInt(required(properties, "height")))),
                    "1".equals(version) ? AppTheme.Charcoal : AppTheme.valueOf(required(properties,"appTheme")));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid workspace; original file preserved: " + e.getMessage(), e);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing workspace property: " + key);
        return value;
    }
    private static boolean bool(Properties properties, String key) {
        String value = required(properties, key);
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid boolean: " + key);
        return Boolean.parseBoolean(value);
    }

    public void save(State state) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", "2");
        properties.setProperty("appTheme",state.appTheme.name());
        properties.setProperty("profiles", String.join(",", state.profiles.stream().map(p -> p.id().toString()).toList()));
        properties.setProperty("layout", state.layout.encode());
        properties.setProperty("width", Integer.toString(state.width));
        properties.setProperty("height", Integer.toString(state.height));
        for (Profile p : state.profiles) {
            String key = "profile." + p.id() + ".";
            properties.setProperty(key + "name", p.name());
            properties.setProperty(key + "host", p.host());
            properties.setProperty(key + "port", Integer.toString(p.port()));
            properties.setProperty(key + "device", p.device());
            properties.setProperty(key + "username", p.username());
            properties.setProperty(key + "auto", Boolean.toString(p.automaticSignOn()));
            properties.setProperty(key + "remember", Boolean.toString(p.rememberPassword()));
            properties.setProperty(key + "codePage", p.codePage());
            properties.setProperty(key + "theme", p.theme());
            properties.setProperty(key + "effect", p.effect());
            properties.setProperty(key + "zoom", Double.toString(p.zoom()));
            properties.setProperty(key + "layout", p.savedLayout());
            p.keymap().forEach((stroke, mnemonic) -> properties.setProperty(key + "key." + stroke, mnemonic));
        }
        Path temporary = directory.resolve("workspace.xml.tmp");
        try (OutputStream out = Files.newOutputStream(temporary)) {
            properties.storeToXML(out, "Pane5250 workspace - no passwords", "UTF-8");
        }
        try { Files.move(temporary, file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file(), StandardCopyOption.REPLACE_EXISTING); }
    }

    @Override public void close() throws IOException { lock.release(); lockChannel.close(); }
}
