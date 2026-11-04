package pane5250.model;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class StateStore implements AutoCloseable {
    public static final String VERSION="10";
    public static final int MAX_BYTES=2_000_000;
    public enum Tutorial { NEW, IN_PROGRESS, COMPLETED, SKIPPED }
    public record State(List<Profile> profiles, Layout layout, int width, int height, AppTheme appTheme, UUID lastProfile,
                        Map<String,String> shortcuts, boolean highlightActive,Appearance appearance,Tutorial tutorial,boolean connectOnStartup) {
        public State(List<Profile> profiles,Layout layout,int width,int height,AppTheme appTheme,UUID lastProfile,
                     Map<String,String> shortcuts,boolean highlightActive,Appearance appearance,Tutorial tutorial) {
            this(profiles,layout,width,height,appTheme,lastProfile,shortcuts,highlightActive,appearance,tutorial,false);
        }
        public State(List<Profile> profiles,Layout layout,int width,int height,AppTheme appTheme,UUID lastProfile,
                     Map<String,String> shortcuts,boolean highlightActive,Appearance appearance) {
            this(profiles,layout,width,height,appTheme,lastProfile,shortcuts,highlightActive,appearance,Tutorial.SKIPPED);
        }
        public State(List<Profile> profiles, Layout layout, int width, int height, AppTheme appTheme, UUID lastProfile,
                     Map<String,String> shortcuts,boolean highlightActive) {
            this(profiles,layout,width,height,appTheme,lastProfile,shortcuts,highlightActive,Appearance.defaults());
        }
        public State(List<Profile> profiles, Layout layout, int width, int height, AppTheme appTheme, UUID lastProfile) {
            this(profiles,layout,width,height,appTheme,lastProfile,Map.of(),true);
        }
        public State(List<Profile> profiles, Layout layout, int width, int height) {
            this(profiles,layout,width,height,AppTheme.Charcoal);
        }
        public State(List<Profile> profiles, Layout layout, int width, int height, AppTheme appTheme) {
            this(profiles,layout,width,height,appTheme,profiles.isEmpty() ? null : profiles.getFirst().id());
        }
        public State {
            Objects.requireNonNull(appTheme);
            Objects.requireNonNull(appearance);
            Objects.requireNonNull(tutorial);
            Objects.requireNonNull(layout);
            if (width<330 || width>10000 || height<260 || height>10000)
                throw new IllegalArgumentException("Window width must be 330-10000 and height 260-10000.");
            shortcuts=Map.copyOf(shortcuts);
            profiles = List.copyOf(profiles);
            if (profiles.isEmpty()) throw new IllegalArgumentException("A workspace needs at least one saved profile.");
            Set<UUID> ids = new HashSet<>();
            for (Profile p : profiles) {
                if (!ids.add(p.id())) throw new IllegalArgumentException("Duplicate profile ID.");
                Profile.login(p,profiles);
                new HostKeys(p.keymap());
            }
            if (layout.leaves().isEmpty() || layout.leaves().size()>Layout.MAX_PANES)
                throw new IllegalArgumentException("A workspace needs 1-8 panes.");
            Set<UUID> paneIds=new HashSet<>();
            for (Layout.Leaf leaf : layout.leaves()) {
                if (!ids.contains(leaf.profile())) throw new IllegalArgumentException("Layout references a missing profile.");
                if (!paneIds.add(leaf.pane())) throw new IllegalArgumentException("Duplicate pane ID.");
            }
            if (!ids.contains(lastProfile)) throw new IllegalArgumentException("Last used preset is missing.");
        }
        public State withTutorial(Tutorial value) {
            return new State(profiles,layout,width,height,appTheme,lastProfile,shortcuts,highlightActive,appearance,value,connectOnStartup);
        }
    }
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private String loadNotice="";
    private String revision;
    public String loadNotice() { return loadNotice; }

    public StateStore(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("workspace.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (OverlappingFileLockException e) { acquired = null; }
        catch (IOException e) { lockChannel.close(); throw e; }
        if (acquired == null) {
            lockChannel.close();
            throw new IOException("This workspace is already open. Use a different --data-dir for another instance.");
        }
        lock = acquired;
        try { revision=diskRevision(); }
        catch (IOException e) { close(); throw e; }
    }

    public Path file() { return directory.resolve("workspace.xml"); }
    public synchronized String revision() { return revision; }
    private String diskRevision() throws IOException {
        if (Files.notExists(file())) return "absent";
        return digest(readBytes(file()));
    }
    private static String digest(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is required by Java.",e); }
    }
    public synchronized void requireRevision(String expected) throws IOException {
        if (!revision.equals(expected) || !revision.equals(diskRevision()))
            throw new IOException("Workspace changed since export/load. Export again and reapply your edits; nothing was overwritten.");
    }

    public synchronized State load() throws IOException {
        loadNotice="";
        if (Files.notExists(file())) {
            if (!revision.equals("absent")) throw new IOException("Workspace was removed while open; no default state was substituted.");
            Profile profile = Profile.kerberosDefault();
            return new State(List.of(profile), new Layout.Leaf(UUID.randomUUID(), profile.id()), 1360, 850).withTutorial(Tutorial.NEW);
        }
        byte[] data=readBytes(file());
        State result=decode(readProperties(data));
        revision=digest(data);
        return result;
    }
    private static byte[] readBytes(Path file) throws IOException {
        try (InputStream in=Files.newInputStream(file)) {
            byte[] data=in.readNBytes(MAX_BYTES+1);
            if (data.length>MAX_BYTES) throw new IOException("Configuration exceeds the 2000000-byte safety limit.");
            return data;
        }
    }
    public static Properties readProperties(Path file) throws IOException {
        return readProperties(readBytes(file));
    }
    private static Properties readProperties(byte[] data) throws IOException {
        Properties result=new Properties() {
            @Override public synchronized Object put(Object key,Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate configuration property: "+key);
                return super.put(key,value);
            }
        };
        try (InputStream in=new ByteArrayInputStream(data)) { result.loadFromXML(in); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid configuration: "+e.getMessage(),e); }
        Properties parsed=new Properties(); parsed.putAll(result); return parsed;
    }
    public State decode(Properties properties) throws IOException {
        try {
            String version = properties.getProperty("version");
            if (version==null || !Set.of("1","2","3","4","5","6","7","8","9",VERSION).contains(version)) throw new IllegalArgumentException("Unknown workspace version.");
            boolean usability=Integer.parseInt(version)>=8;
            List<Profile> profiles = new ArrayList<>();
            for (String idText : required(properties, "profiles").split(",",-1)) {
                UUID id = UUID.fromString(idText);
                String key = "profile." + id + ".";
                if (!VERSION.equals(version) && (!properties.getProperty(key+"authentication","PASSWORD").equals("PASSWORD")
                        || !properties.getProperty(key+"kerberosServer","").isEmpty()))
                    throw new IllegalArgumentException("Kerberos metadata requires workspace version "+VERSION+"; do not relabel or downgrade it.");
                Map<String, String> keys = new LinkedHashMap<>();
                for (String property : properties.stringPropertyNames()) {
                    if (property.startsWith(key + "key."))
                        keys.put(property.substring((key + "key.").length()), properties.getProperty(property));
                }
                String effect=required(properties,key+"effect");
                if (!usability && effect.equals("Neon")) {
                    effect="None"; notice("Removed Neon effect changed to None for "+required(properties,key+"name")+"; other profile settings retained.");
                }
                profiles.add(new Profile(id, required(properties, key + "name"), required(properties, key + "host"),
                        Integer.parseInt(required(properties, key + "port")), required(properties, key + "device"),
                        required(properties, key + "username"), bool(properties, key + "auto"), bool(properties, key + "remember"),
                        required(properties, key + "codePage"), required(properties, key + "theme"),
                        effect, Double.parseDouble(required(properties, key + "zoom")),
                        keys, required(properties, key + "layout"),
                        usability && !required(properties,key+"loginSource").isEmpty()
                                ? UUID.fromString(required(properties,key+"loginSource")) : null,
                        VERSION.equals(version) ? Profile.Authentication.valueOf(required(properties,key+"authentication")) : Profile.Authentication.PASSWORD,
                        VERSION.equals(version) ? required(properties,key+"kerberosServer") : ""));
            }
            UUID last=Set.of("3","4","5","6","7","8","9",VERSION).contains(version) ? UUID.fromString(required(properties,"lastProfile")) : profiles.getFirst().id();
            Layout layout=Layout.decode(required(properties,"layout"));
            if (Set.of("1","2").contains(version) && !layout.leaves().isEmpty()) last=layout.leaves().getFirst().profile();
            if (layout instanceof Layout.Empty) {
                if (usability) throw new IllegalArgumentException("A workspace needs at least one pane.");
                layout=new Layout.Leaf(UUID.randomUUID(),last);
                notice("Restored one offline pane from the previous empty workspace; all presets retained.");
            }
            Layout bounded=Layout.normalize(layout);
            if (usability && !bounded.equals(layout))
                throw new IllegalArgumentException("Layout must use Fish's bounded 1-8 pane slots; see AI-CONFIG.md.");
            if (!bounded.equals(layout)) notice("Previous pane layout rearranged into Fish's bounded slots; all presets retained.");
            Map<String,String> shortcuts=new LinkedHashMap<>();
            for (String property:properties.stringPropertyNames())
                if (property.startsWith("shortcut.")) shortcuts.put(property.substring(9),properties.getProperty(property));
            Appearance appearance=Set.of("5","6","7","8","9",VERSION).contains(version) ? readAppearance(properties,version) : Appearance.defaults();
            if ("5".equals(version)) notice("Structural borders use the saved divider color; other appearance choices retained.");
            if (Set.of("5","6").contains(version))
                notice("Window outline enabled once on upgrade. Earlier state did not distinguish an explicit off choice from the old default; Window and focus can turn it off permanently.");
            if (shortcuts.containsKey("Search commands...")) {
                Set<String> retired=new HashSet<>(appearance.retiredShortcuts());
                String old=shortcuts.remove("Search commands...");
                if (!old.isBlank()) retired.add(old);
                appearance=new Appearance(appearance.outline(),appearance.windowOutline(),appearance.info(),appearance.font(),appearance.selectedTheme(),
                        appearance.themes(),retired);
                notice("Commands window removed; its former shortcut is reserved until explicitly reassigned. All actions remain in menus/shortcuts.");
            }
            if (!usability) {
                Shortcuts.Migration migration=Shortcuts.migrate(profiles,shortcuts,appearance.retiredShortcuts());
                profiles=new ArrayList<>(migration.profiles()); shortcuts=new LinkedHashMap<>(migration.overrides());
                migration.notices().forEach(this::notice);
            }
            State result=new State(profiles, bounded,
                    Math.max(330, Math.min(10000, Integer.parseInt(required(properties, "width")))),
                    Math.max(260, Math.min(10000, Integer.parseInt(required(properties, "height")))),
                    "1".equals(version) ? AppTheme.Charcoal : AppTheme.valueOf(required(properties,"appTheme")),last,
                    shortcuts,!Set.of("4","5","6","7","8","9",VERSION).contains(version) || bool(properties,"highlightActive"),appearance,
                    usability ? Tutorial.valueOf(required(properties,"tutorial.status")) : Tutorial.SKIPPED,
                    Integer.parseInt(version)>=9 && bool(properties,"connectOnStartup"));
            if (usability && (result.width()!=Integer.parseInt(required(properties,"width"))
                    || result.height()!=Integer.parseInt(required(properties,"height"))))
                throw new IllegalArgumentException("Window dimensions exceed the supported bounds.");
            Properties known=properties(result);
            if (Set.of("5","6").contains(version)) for (int i=0;i<appearance.themes().size();i++) {
                for (String role:List.of("LINE","OUTLINE")) {
                    String old="appearance.theme."+i+"."+role;
                    if (properties.containsKey(old)) ColorTheme.parse(required(properties,old));
                    known.setProperty(old,"");
                }
            }
            for (String key:properties.stringPropertyNames())
                if (!known.containsKey(key) && !(key.equals("shortcut.Search commands...") && !VERSION.equals(version)))
                    throw new IllegalArgumentException("Unknown configuration property: "+key);
            return result;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid workspace; original file preserved: " + e.getMessage(), e);
        }
    }
    private void notice(String text) { loadNotice+=(loadNotice.isEmpty() ? "" : "\n")+text; }
    private static Appearance readAppearance(Properties p,String version) {
            List<ColorTheme> themes=new ArrayList<>();
            int count=Integer.parseInt(required(p,"appearance.themes"));
            if (count<0 || count>32) throw new IllegalArgumentException("Custom theme count must be 0-32.");
            for (int i=0;i<count;i++) {
                String prefix="appearance.theme."+i+".";
                EnumMap<ColorRole,Integer> colors=new EnumMap<>(ColorRole.class);
                for (ColorRole role:ColorRole.values()) colors.put(role,ColorTheme.parse(required(p,prefix+role.name())));
                themes.add(new ColorTheme(required(p,prefix+"name"),colors));
            }
            Appearance.PaneInfo info=new Appearance.PaneInfo(bool(p,"info.header"),bool(p,"info.footer"),
                    bool(p,"info.profile"),bool(p,"info.connection"),bool(p,"info.host"),bool(p,"info.device"),
                    bool(p,"info.status"),bool(p,"info.dimensions"),bool(p,"info.cursor"),bool(p,"info.insert"));
            Set<String> retired=new HashSet<>();
            for (String value:required(p,"appearance.retired").split("\n")) if (!value.isBlank()) retired.add(value);
            boolean windowOutline=!"5".equals(version) && bool(p,"appearance.windowOutline");
            return new Appearance(bool(p,"appearance.outline"),!Set.of("7","8","9",VERSION).contains(version) || windowOutline,info,
                    new Appearance.FontChoice(required(p,"font.family"),required(p,"font.file")),
                    required(p,"appearance.selected"),themes,retired);
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

    public static Properties properties(State state) {
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        properties.setProperty("tutorial.status",state.tutorial.name());
        properties.setProperty("connectOnStartup",Boolean.toString(state.connectOnStartup));
        properties.setProperty("highlightActive",Boolean.toString(state.highlightActive));
        state.shortcuts.forEach((action,stroke) -> properties.setProperty("shortcut."+action,stroke));
        properties.setProperty("lastProfile",state.lastProfile.toString());
        properties.setProperty("appTheme",state.appTheme.name());
        properties.setProperty("profiles", String.join(",", state.profiles.stream().map(p -> p.id().toString()).toList()));
        properties.setProperty("layout", state.layout.encode());
        properties.setProperty("width", Integer.toString(state.width));
        properties.setProperty("height", Integer.toString(state.height));
        Appearance a=state.appearance;
        properties.setProperty("appearance.outline",Boolean.toString(a.outline()));
        properties.setProperty("appearance.windowOutline",Boolean.toString(a.windowOutline()));
        properties.setProperty("font.family",a.font().family()); properties.setProperty("font.file",a.font().file());
        properties.setProperty("appearance.selected",a.selectedTheme());
        properties.setProperty("appearance.retired",String.join("\n",a.retiredShortcuts()));
        properties.setProperty("appearance.themes",Integer.toString(a.themes().size()));
        for (int i=0;i<a.themes().size();i++) {
            ColorTheme theme=a.themes().get(i); String prefix="appearance.theme."+i+".";
            properties.setProperty(prefix+"name",theme.name());
            theme.colors().forEach((role,rgb) -> properties.setProperty(prefix+role.name(),String.format("#%06X",rgb)));
        }
        Appearance.PaneInfo info=a.info();
        boolean[] flags={info.header(),info.footer(),info.profile(),info.connection(),info.host(),info.device(),
                info.status(),info.dimensions(),info.cursor(),info.insert()};
        String[] names={"header","footer","profile","connection","host","device","status","dimensions","cursor","insert"};
        for (int i=0;i<names.length;i++) properties.setProperty("info."+names[i],Boolean.toString(flags[i]));
        for (Profile p : state.profiles) {
            String key = "profile." + p.id() + ".";
            properties.setProperty(key + "name", p.name());
            properties.setProperty(key + "host", p.host());
            properties.setProperty(key + "port", Integer.toString(p.port()));
            properties.setProperty(key + "device", p.device());
            properties.setProperty(key + "username", p.username());
            properties.setProperty(key + "auto", Boolean.toString(p.automaticSignOn()));
            properties.setProperty(key + "remember", Boolean.toString(p.rememberPassword()));
            properties.setProperty(key + "authentication",p.authentication().name());
            properties.setProperty(key + "kerberosServer",p.kerberosServer());
            properties.setProperty(key + "codePage", p.codePage());
            properties.setProperty(key + "theme", p.theme());
            properties.setProperty(key + "effect", p.effect());
            properties.setProperty(key + "zoom", Double.toString(p.zoom()));
            properties.setProperty(key + "layout", p.savedLayout());
            properties.setProperty(key + "loginSource",p.loginSource()==null ? "" : p.loginSource().toString());
            p.keymap().forEach((stroke, mnemonic) -> properties.setProperty(key + "key." + stroke, mnemonic));
        }
        return properties;
    }
    public synchronized void save(State state) throws IOException {
        requireRevision(revision);
        Properties values=properties(state);
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        values.storeToXML(data,"Fish workspace - no passwords","UTF-8");
        if (data.size()>MAX_BYTES) throw new IOException("Configuration exceeds the 2000000-byte safety limit.");
        byte[] serialized=data.toByteArray();
        Path temporary=Files.createTempFile(directory,"workspace-",".tmp");
        try {
            try (FileChannel out=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer bytes=java.nio.ByteBuffer.wrap(serialized);
                while (bytes.hasRemaining()) out.write(bytes);
                out.force(true);
            }
            requireRevision(revision);
            Files.move(temporary,file(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            revision=digest(serialized);
        } finally { Files.deleteIfExists(temporary); }
    }

    @Override public void close() throws IOException { try { lock.release(); } finally { lockChannel.close(); } }
}
