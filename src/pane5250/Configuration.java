package pane5250;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import pane5250.model.*;
import pane5250.ui.TerminalFonts;

/** Offline-only configuration commands: no workspace window, sockets or credential store. */
public final class Configuration {
    private Configuration() {}
    public static final String HELP="Fish-config.cmd <paths|schema|export|validate|apply> [--data-dir DIRECTORY] [--file FILE]\n"
            +"Export a full snapshot, edit it, validate (dry run), then apply while Fish is closed.\n"
            +"Export/validate/apply require --file. Schema prints to stdout or writes a new --file.\n"
            +"No password arguments or secret export. See AI-CONFIG.md and CONFIG-SCHEMA.json.\n";
    public static int run(String[] args,PrintStream out,PrintStream error) {
        try {
            if (args.length==0 || args[0].equals("--help") || args[0].equals("help")) { out.print(HELP); return 0; }
            String command=args[0];
            if (!Set.of("paths","schema","export","validate","apply").contains(command))
                throw new IllegalArgumentException("Unknown configuration command. "+HELP);
            Path directory=Main.defaultDirectory(Path.of(Optional.ofNullable(System.getenv("APPDATA")).orElse(System.getProperty("user.home"))));
            Path file=null;
            Set<String> seen=new HashSet<>();
            for (int i=1;i<args.length;i++) {
                String flag=args[i];
                if (!Set.of("--data-dir","--file").contains(flag) || !seen.add(flag) || i+1==args.length)
                    throw new IllegalArgumentException("Unknown, duplicate or incomplete option. Use --data-dir and --file only; never pass passwords.");
                Path value=Path.of(args[++i]).toAbsolutePath().normalize();
                if (flag.equals("--data-dir")) directory=value; else file=value;
            }
            directory=directory.toAbsolutePath().normalize();
            if (command.equals("paths")) {
                out.println(ConfigSchema.json(Map.of("version",Main.VERSION,"workspace",directory.resolve("workspace.xml").toString(),
                        "lock",directory.resolve("workspace.lock").toString(),"guide",document("AI-CONFIG.md"),"schema",document("CONFIG-SCHEMA.json"))));
                return 0;
            }
            if (command.equals("schema")) {
                if (file==null) out.print(ConfigSchema.document());
                else writeNew(file,ConfigSchema.document().getBytes(StandardCharsets.UTF_8));
                return 0;
            }
            if (file==null) throw new IllegalArgumentException(command+" requires --file.");
            Path canonical=directory.resolve("workspace.xml");
            if (file.equals(canonical) || file.equals(directory.resolve("workspace.lock")) || file.equals(directory.resolve("workspace.xml.tmp"))
                    || Files.exists(file) && Files.exists(canonical) && Files.isSameFile(file,canonical))
                throw new IllegalArgumentException("Use a separate exported candidate file, not the live workspace/lock.");
            try (StateStore store=new StateStore(directory)) {
                StateStore.State current=store.load();
                if (command.equals("export")) {
                    store.requireRevision(store.revision());
                    Properties values=StateStore.properties(current);
                    values.setProperty("config.format","1"); values.setProperty("config.baseRevision",store.revision());
                    ByteArrayOutputStream data=new ByteArrayOutputStream();
                    values.storeToXML(data,"Fish configuration export - no passwords; keep config.baseRevision unchanged","UTF-8");
                    writeNew(file,data.toByteArray());
                } else {
                    Properties values=StateStore.readProperties(file);
                    if (!"1".equals(values.remove("config.format"))) throw new IllegalArgumentException("Not a Fish configuration export. Export first.");
                    Object base=values.remove("config.baseRevision");
                    if (!(base instanceof String expected) || !expected.matches("absent|[0-9a-f]{64}"))
                        throw new IllegalArgumentException("Missing/invalid config.baseRevision. Export first; do not edit the revision.");
                    store.requireRevision(expected);
                    if (!StateStore.VERSION.equals(values.getProperty("version")))
                        throw new IllegalArgumentException("Candidate must use current workspace version "+StateStore.VERSION+". Export the old workspace to migrate it.");
                    StateStore.State candidate=store.decode(values);
                    validate(candidate);
                    store.requireRevision(expected);
                    if (command.equals("apply")) store.save(candidate);
                }
                out.println(ConfigSchema.json(Map.of("ok",true,"command",command,"revision",store.revision(),
                        "workspace",store.file().toString(),"runtime","Next launch remains offline; no sessions or credentials changed.")));
                if (!store.loadNotice().isEmpty()) error.println("Migration information: "+store.loadNotice());
            }
            return 0;
        } catch (IllegalArgumentException e) {
            error.println("Configuration rejected: "+e.getMessage()); return 2;
        } catch (IOException e) {
            error.println("Configuration not changed: "+e.getMessage()); return 3;
        }
    }
    public static void validate(StateStore.State state) throws IOException {
        Shortcuts.validate(state.shortcuts(),state.profiles());
        for (String value:state.appearance().retiredShortcuts()) Shortcuts.stroke(value);
        Set<UUID> profiles=new HashSet<>();
        state.profiles().forEach(p -> profiles.add(p.id()));
        for (Profile profile:state.profiles()) {
            Profile.login(profile,state.profiles());
            if (!profile.savedLayout().isEmpty()) {
                Layout saved=Layout.decode(profile.savedLayout());
                if (!Layout.normalize(saved).equals(saved)) throw new IllegalArgumentException("Saved layout is not a bounded Fish layout: "+profile.name());
                for (Layout.Leaf leaf:saved.leaves()) if (!profiles.contains(leaf.profile()))
                    throw new IllegalArgumentException("Saved layout references a missing profile: "+profile.name());
            }
        }
        String fontFile=state.appearance().font().file();
        if (fontFile.startsWith("\\\\") || fontFile.startsWith("//"))
            throw new IllegalArgumentException("Configuration font files must be local, not network/UNC paths.");
        TerminalFonts.validate(TerminalFonts.resolve(state.appearance().font()),state.profiles());
    }
    private static String document(String name) throws IOException {
        try {
            Path location=Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path folder=Files.isDirectory(location) ? location : location.getParent();
            Path file=folder.resolve(name);
            if (!Files.exists(file) && folder.getParent()!=null && Files.exists(folder.getParent().resolve(name)))
                file=folder.getParent().resolve(name);
            return file.toAbsolutePath().normalize().toString();
        } catch (java.net.URISyntaxException e) { throw new IOException("Cannot locate bundled configuration documentation.",e); }
    }
    private static void writeNew(Path target,byte[] content) throws IOException {
        if (Files.exists(target)) throw new IOException("Output exists; choose a new file to preserve your existing edits.");
        Path parent=target.toAbsolutePath().getParent();
        if (!Files.isDirectory(parent)) throw new IOException("Output directory does not exist.");
        Files.write(target,content,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
    }
}
