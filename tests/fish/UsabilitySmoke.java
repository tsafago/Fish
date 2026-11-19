package fish.ui;

import java.awt.datatransfer.*;
import java.awt.event.KeyEvent;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.swing.KeyStroke;
import org.tn5250j.Session5250;
import org.tn5250j.keyboard.KeyMnemonic;
import fish.*;
import fish.demo.*;
import fish.model.*;

/** Actual JavaFX dispatch/dialog/configuration paths, with hidden windows and a private fixture clipboard. */
public final class UsabilitySmoke {
    private static int checks;
    private static void check(boolean value,String text) { checks++; if (!value) throw new AssertionError(text); }
    private static Object field(Object owner,String name) throws Exception { return FxTestSupport.field(owner,name); }
    private static void set(Object owner,String name,Object value) throws Exception { FxTestSupport.setField(owner,name,value); }
    private static <T> T edt(Callable<T> work) throws Exception { return FxTestSupport.fx(work); }
    private static void await(Callable<Boolean> work,String message) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (!work.call() && System.nanoTime()<end) Thread.sleep(15);
        check(work.call(),message);
    }
    private static ButtonBase button(Stage stage,String text) { return FxTestSupport.button(stage,text); }
    private static void image(Stage dialog,String name,double scale) throws IOException {
        FxTestSupport.writeSnapshot(dialog.getScene().getRoot(),scale,Path.of("build","usability-"+name+".png"));
        check(!dialog.isShowing(),"Synthetic image never exposes a shared desktop window.");
    }
    private static int cli(Path directory,String command,Path file,ByteArrayOutputStream error) {
        List<String> args=new ArrayList<>(List.of(command,"--data-dir",directory.toString()));
        if (file!=null) args.addAll(List.of("--file",file.toString()));
        return Configuration.run(args.toArray(String[]::new),new PrintStream(OutputStream.nullOutputStream()),new PrintStream(error));
    }
    private static void properties(Path file,Properties values) throws IOException {
        try (OutputStream out=Files.newOutputStream(file)) { values.storeToXML(out,"Synthetic configuration fixture","UTF-8"); }
    }
    private static void rejected(Path directory,Path candidate,Properties values,String reason) throws IOException {
        properties(candidate,values); byte[] original=Files.readAllBytes(directory.resolve("workspace.xml"));
        ByteArrayOutputStream error=new ByteArrayOutputStream();
        check(cli(directory,"apply",candidate,error)!=0 && error.size()>0,"Explicit rejection: "+reason);
        check(Arrays.equals(original,Files.readAllBytes(directory.resolve("workspace.xml"))),"Rejected "+reason+" preserves every original workspace byte.");
    }
    private static void configurations(Path root) throws Exception {
        Path dir=Files.createDirectory(root.resolve("config")),candidate=root.resolve("candidate.xml");
        Profile login=new Profile(UUID.randomUUID(),"Login","",23,"","FIXTURE",true,true,"37","Mint",Profile.FLUX,.8,Map.of(),"");
        Profile target=new Profile(UUID.randomUUID(),"Linked","",23,"","",false,false,"500","Orchid","None",1.3,Map.of(),"",login.id());
        Layout layout=Layout.bounded(List.of(new Layout.Leaf(UUID.randomUUID(),login.id()),new Layout.Leaf(UUID.randomUUID(),target.id())));
        login=login.withLayout(layout.encode());
        ColorTheme theme=new ColorTheme("Config fixture",ColorTheme.builtin(AppTheme.Lagoon).colors());
        Appearance a=new Appearance(false,false,new Appearance.PaneInfo(false,true,false,true,false,true,false,true,false,true),
                new Appearance.FontChoice("Automatic",""),theme.name(),List.of(theme),Set.of("ctrl alt K","ctrl alt Y"));
        StateStore.State state=new StateStore.State(List.of(login,target),layout,910,610,AppTheme.Afterglow,target.id(),
                Map.of("Next pane","ctrl F10","Host F1","ctrl F1"),false,a,StateStore.Tutorial.COMPLETED,true);
        try (StateStore store=new StateStore(dir)) { store.save(state); }
        ByteArrayOutputStream error=new ByteArrayOutputStream();
        check(cli(dir,"export",candidate,error)==0,"Full production export succeeds.");
        Properties exported=StateStore.readProperties(candidate);
        check(exported.stringPropertyNames().stream().noneMatch(k -> k.toLowerCase(Locale.ROOT).contains("password")),"Export contains no secret/password fields.");
        check(exported.getProperty("profile."+target.id()+".loginSource").equals(login.id().toString()),"Only the explicit stable login reference is exported.");
        Map<?,?> catalog=ConfigSchema.catalog();
        Map<?,?> fields=(Map<?,?>)catalog.get("properties"),profileFields=(Map<?,?>)catalog.get("profileFields"),themeFields=(Map<?,?>)catalog.get("themeFields");
        for (String key:exported.stringPropertyNames()) {
            boolean covered=fields.containsKey(key) || key.startsWith("config.") || key.startsWith("binding.") || key.startsWith("shortcut.")
                    || key.startsWith("window.") || key.startsWith("savedLayout.") || key.startsWith("paneDisplay.")
                    || key.startsWith("profile.") && (profileFields.containsKey(key.substring(key.indexOf('.',8)+1)) || key.contains(".key."))
                    || key.startsWith("appearance.theme.0.") && themeFields.containsKey(key.substring("appearance.theme.0.".length()));
            check(covered,"Catalog covers actual exported field "+key);
        }
        check(cli(dir,"validate",candidate,error)==0,"Complete snapshot validates without any credential helper.");
        String oldRevision=exported.getProperty("config.baseRevision");
        exported.setProperty("width","1010"); exported.setProperty("info.header","true");
        properties(candidate,exported);
        check(cli(dir,"apply",candidate,error)==0,"Bulk apply succeeds atomically.");
        try (StateStore store=new StateStore(dir)) {
            StateStore.State loaded=store.load();
            check(loaded.profiles().equals(state.profiles()) && loaded.appearance().themes().equals(a.themes())
                    && loaded.shortcuts().equals(state.shortcuts()) && loaded.width()==1010 && loaded.appearance().info().header() && loaded.connectOnStartup(),
                    "Unedited profiles, key provenance, saved layouts and every theme role survive bulk edits.");
            check(!oldRevision.equals(store.revision()),"Applied bytes have a new revision.");
            check(cli(dir,"apply",candidate,new ByteArrayOutputStream())!=0,"A running/locked workspace cleanly rejects config apply.");
        }
        rejected(dir,candidate,exported,"stale revision");
        Files.delete(candidate); check(cli(dir,"export",candidate,error)==0,"Fresh export after apply.");
        exported=StateStore.readProperties(candidate);
        for (Map.Entry<String,String> mutation:Map.of("width","bad","appearance.outline","maybe","unknown.setting","x",
                "binding.Next pane","ctrl F1","font.family","Nonexistent fixture font","layout","E").entrySet()) {
            Properties bad=new Properties(); bad.putAll(exported); bad.setProperty(mutation.getKey(),mutation.getValue());
            rejected(dir,candidate,bad,mutation.getKey());
        }
        Properties bad=new Properties(); bad.putAll(exported); bad.setProperty("profile."+login.id()+".remember","false");
        rejected(dir,candidate,bad,"orphaned shared login");
        bad=new Properties(); bad.putAll(exported); bad.setProperty("profile."+login.id()+".layout","L|"+UUID.randomUUID()+"|"+UUID.randomUUID());
        rejected(dir,candidate,bad,"missing saved-layout profile");
        bad=new Properties(); bad.putAll(exported); bad.setProperty("profile."+target.id()+".loginSource",UUID.randomUUID().toString());
        rejected(dir,candidate,bad,"missing login source");
        bad=new Properties(); bad.putAll(exported);
        bad.setProperty("profile."+login.id()+".username",""); bad.setProperty("profile."+login.id()+".auto","false");
        bad.setProperty("profile."+login.id()+".remember","false"); bad.setProperty("profile."+login.id()+".loginSource",target.id().toString());
        rejected(dir,candidate,bad,"cyclic/chained logins");
        bad=new Properties(); bad.putAll(exported); bad.setProperty("font.file","\\\\synthetic-network\\font.ttf");
        rejected(dir,candidate,bad,"network font without attempting network access");
        bad=new Properties(); bad.putAll(exported); bad.setProperty("profile."+target.id()+".password","synthetic-forbidden-value");
        rejected(dir,candidate,bad,"forbidden plaintext password field");
        Path hostile=root.resolve("hostile.xml");
        Files.writeString(hostile,"<?xml version=\"1.0\"?><!DOCTYPE properties SYSTEM \"file:///not-a-properties-dtd\"><properties><entry key=\"x\">y</entry></properties>");
        try { StateStore.readProperties(hostile); throw new AssertionError("Unexpected external DTD accepted."); }
        catch (IOException expected) { checks++; }
        Files.writeString(hostile,"<?xml version=\"1.0\"?><!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\" [<!ENTITY leak SYSTEM \"file:///not-a-secret\">]><properties><entry key=\"x\">&leak;</entry></properties>");
        try { StateStore.readProperties(hostile); throw new AssertionError("Unexpected external entity accepted."); }
        catch (IOException expected) { checks++; }
        Files.writeString(hostile,"<?xml version=\"1.0\"?><!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\"><properties><entry key=\"x\">1</entry><entry key=\"x\">2</entry></properties>");
        try { StateStore.readProperties(hostile); throw new AssertionError("Duplicate key accepted."); }
        catch (IOException expected) { checks++; }
        try (StateStore store=new StateStore(dir)) {
            store.load(); Files.writeString(store.file(),"\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);
            try { store.save(state); throw new AssertionError("Stale save accepted."); }
            catch (IOException expected) { checks++; }
        }
    }
    private static void mappings() {
        HostKeys keys=new HostKeys(Map.of());
        check(keys.bindings().get(KeyStroke.getKeyStroke("ESCAPE"))==KeyMnemonic.ATTN,"Escape is Attention.");
        check(keys.bindings().get(KeyStroke.getKeyStroke("shift ESCAPE"))==KeyMnemonic.SYSREQ,"Shift+Escape remains System Request.");
        check(keys.bindings().get(KeyStroke.getKeyStroke("ctrl R"))==KeyMnemonic.RESET,"Explicit accessible Reset chord.");
        check(keys.bindings().get(KeyStroke.getKeyStroke("PAUSE"))==KeyMnemonic.CLEAR
                && keys.bindings().get(KeyStroke.getKeyStroke("alt F1"))==KeyMnemonic.HELP,"IBM documented Clear and Help parity.");
        Profile p=Profile.create("Migration").withKeys(Map.of("ctrl C","PF1","ESCAPE","RESET"));
        Shortcuts.Migration migrated=Shortcuts.migrate(List.of(p),Map.of(),Set.of());
        check(migrated.profiles().getFirst().keymap().equals(p.keymap())
                && migrated.overrides().get("Copy selection / screen").equals("ctrl alt shift C")
                && !migrated.notices().isEmpty(),"Explicit host overrides survive new defaults with an explanatory migration notice.");
        migrated=Shortcuts.migrate(List.of(Profile.create("Old")),Map.of("Next pane","ctrl R"),Set.of());
        check(migrated.profiles().getFirst().keymap().get("ctrl R").isEmpty(),"New Reset default does not steal an explicit global binding.");
        try { Shortcuts.validate(Map.of("Next pane","ctrl C","Copy selection / screen","ctrl C"),List.of(Profile.create("Conflict"))); throw new AssertionError("Conflict accepted."); }
        catch (IllegalArgumentException expected) { checks++; }
        check(Shortcuts.aliasBindings(Map.of(),List.of(Profile.create("Aliases")),Set.of()).get(KeyStroke.getKeyStroke("ctrl shift EQUALS")).equals("Zoom in"),
                "Conventional Ctrl+plus alias is available.");
        check(!Shortcuts.aliasBindings(Map.of(),List.of(Profile.create("Custom").withKeys(Map.of("ctrl INSERT","FIELD_EXIT"))),Set.of()).containsKey(KeyStroke.getKeyStroke("ctrl INSERT")),
                "Clipboard alias never steals a custom host binding.");
    }
    private static byte[] screen(int ffw) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        DemoScreens.command(out,4,0x40,4,0x11,0,8);
        DemoScreens.at(out,1,1,0x22,"FIXTURE");
        DemoScreens.field(out,2,1,8,ffw,0x24);
        DemoScreens.command(out,4,0x42,0,0);
        return DemoScreens.gds(3,out.toByteArray());
    }
    private static boolean dispatch(AppShortcuts shortcuts,Node source,KeyCode code,boolean ctrl,int location) {
        boolean used=shortcuts.dispatchKey(source,new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED,"","",code,false,ctrl,false,false),location);
        shortcuts.dispatchKey(source,new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_RELEASED,"","",code,false,ctrl,false,false),location);
        return used;
    }
    private static void dispatching(Workspace w) throws Exception {
        SessionPane pane=w.active(); TerminalView view=pane.view(); AppShortcuts keys=(AppShortcuts)field(w,"shortcuts");
        check(w.commands().stream().map(c -> c.title).collect(java.util.stream.Collectors.toSet())
                .equals(Shortcuts.commands().stream().map(Shortcuts.Command::title).collect(java.util.stream.Collectors.toSet())),
                "Machine action catalog exactly matches the actual production menus/shortcut editor.");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Session5250 recording=new Session5250("Usability fixture","37",bytes,text -> {});
        set(pane,"core",recording);
        recording.accept(screen(0x43)); pane.type('X'); bytes.reset();
        check(pane.recoverableInputError(),"Actual numeric misentry is recoverable.");
        check(dispatch(keys,view,KeyCode.ENTER,false,KeyEvent.KEY_LOCATION_STANDARD) && !pane.recoverableInputError() && bytes.size()==0,
                "First physical Enter consumes Reset only and emits exactly zero host bytes.");
        check(dispatch(keys,view,KeyCode.ENTER,false,KeyEvent.KEY_LOCATION_STANDARD),"Next normal Enter dispatches host Enter.");
        byte[] record=bytes.toByteArray();
        check(record.length>=13 && (record[12]&255)==0xf1,"Next Enter emits the exact Enter AID F1, not Attention.");
        recording.accept(screen(0x43)); pane.type('X'); bytes.reset();
        check(dispatch(keys,view,KeyCode.ENTER,false,KeyEvent.KEY_LOCATION_NUMPAD) && bytes.size()==0 && !pane.recoverableInputError(),
                "Keypad Enter during a local error also performs only Reset.");
        recording.accept(screen(0x40));
        recording.accept(DemoScreens.gds(3,new byte[]{4,0x11,0x20,0}));
        bytes.reset(); pane.send(KeyMnemonic.ENTER); pane.send(KeyMnemonic.RESET);
        check(pane.screen().getOIA().isKeyBoardLocked() && bytes.size()==0,"Neither Enter nor Reset falsely clears host-busy inhibition.");
        recording.accept(screen(0x40));
        Clipboard fixture=new Clipboard("Private usability fixture");
        set(view,"customClipboard",(Supplier<Clipboard>)() -> fixture);
        fixture.setContents(new StringSelection("A\nB"),null); bytes.reset();
        check(dispatch(keys,view,KeyCode.V,true,KeyEvent.KEY_LOCATION_STANDARD) && bytes.size()==0,
                "Ctrl+V uses the actual app action and fixture clipboard without sending Enter.");
        check(dispatch(keys,view,KeyCode.C,true,KeyEvent.KEY_LOCATION_STANDARD)
                && fixture.getData(DataFlavor.stringFlavor).toString().contains("FIXTURE"),"Ctrl+C copies actual screen text.");
        TextField edit=new TextField();
        check(!dispatch(keys,edit,KeyCode.C,true,KeyEvent.KEY_LOCATION_STANDARD)
                && !dispatch(keys,edit,KeyCode.V,true,KeyEvent.KEY_LOCATION_STANDARD),"Native text editing retains Ctrl+C/V.");
        ProfilesDialog profile=new ProfilesDialog(w,pane.profile());
        check(!dispatch(keys,(TextField)field(profile,"name"),KeyCode.ESCAPE,false,KeyEvent.KEY_LOCATION_STANDARD),"Dialog Escape never sends host Attention.");
        profile.dispose();
        pane.disconnect(); bytes.reset();
        check(!pane.send(KeyMnemonic.ENTER) && bytes.size()==0,"Disconnected Enter does not invent recovery or submit.");
    }
    private static void tutorialAndManual(Workspace w) throws Exception {
        w.tutorialStatus(StateStore.Tutorial.NEW); w.startTutorialIfNeeded();
        TutorialDialog guide=(TutorialDialog)field(w,"tutorial");
        List<Profile> original=w.profileList(); int panes=w.sessionPanes().size();
        check(w.tutorialStatus()==StateStore.Tutorial.IN_PROGRESS && guide.stage().getModality()==javafx.stage.Modality.NONE,
                "First-run production guide is user-paced and modeless.");
        guide.finish(true);
        check(w.tutorialStatus()==StateStore.Tutorial.COMPLETED && w.profileList().equals(original) && w.sessionPanes().size()==panes,
                "Walking the guide alone creates no profile, connection or pane.");
        w.showTutorial(); guide=(TutorialDialog)field(w,"tutorial"); guide.go(2); guide.finish(false);
        check(w.tutorialStatus()==StateStore.Tutorial.COMPLETED && w.profileList().equals(original),"Replay/skip does not reset progress or duplicate profiles.");
        w.tutorialStatus(StateStore.Tutorial.NEW); w.startTutorialIfNeeded();
        guide=(TutorialDialog)field(w,"tutorial"); guide.finish(false);
        check(w.tutorialStatus()==StateStore.Tutorial.SKIPPED,"First-run Skip persists its explicit state.");

        ManualDialog manual=new ManualDialog(w);
        MarkdownView page=(MarkdownView)field(manual,"page");
        String html=page.htmlSnapshot();
        String rendered=page.plainText();
        check(html.contains("<strong") && html.contains("<ol"),"Quick guide formats its numbered sign-in instructions.");
        check(rendered.contains("host address") && !rendered.contains("**"),"Sign-in instructions have no raw markup.");
        ((TextField)field(manual,"find")).setText("desktop shortcut");
        check(((List<?>)field(manual,"index")).size()>0,"Quick guide finds desktop shortcut instructions.");
        ((TextField)field(manual,"find")).setText(""); image(manual.stage(),"quick-guide-100",1);
        manual.themeChanged();
        manual.dispose();
        String safe=Markdown.render("**Bold** `*literal*`\n\n<script>x</script>\n\n![remote](https://example.invalid/a.png)\n\n[bad](file:///bad)\n\n| A | B |\n|---|---|\n| `a|b` | x |");
        check(safe.contains("<strong>Bold</strong>") && safe.contains("<code>*literal*</code>")
                && safe.contains("&lt;script&gt;") && !safe.contains("<img") && !safe.contains("href=\"file:")
                && safe.contains("<td><code>a|b</code></td>"),"Renderer escapes raw HTML, loads no images, blocks file links and keeps literal pipes/code.");
    }
    private static void startup(Path root) throws Exception {
        Profile fresh=Profile.create("Defaults");
        check(fresh.host().equals("10.1.2.3") && fresh.automaticSignOn() && fresh.rememberPassword(),"New profile defaults match the explicit user choices.");
        try { fresh.environment("fixture".toCharArray()); throw new AssertionError("Blank automatic username sent."); }
        catch (IllegalArgumentException expected) { checks++; }
        Path helper=root.resolve("fixture-missing.ps1");
        Files.writeString(helper,"$null = [Console]::In.ReadToEnd()\n[Console]::Out.WriteLine('MISSING')\n");
        try (DemoHost host=new DemoHost()) {
            StateStore.State demo=Main.demoState(host.port());
            Profile missing=new Profile(UUID.randomUUID(),"Missing fixture login","127.0.0.1",host.port(),"","FIXTURE",true,true,"37","Mint","None",1,Map.of(),"");
            Profile unremembered=new Profile(UUID.randomUUID(),"No saved consent","127.0.0.1",host.port(),"","FIXTURE",true,false,"37","Mint","None",1,Map.of(),"");
            List<Profile> profiles=new ArrayList<>(demo.profiles()); profiles.addAll(List.of(fresh,missing,unremembered));
            Layout panes=Layout.bounded(profiles.stream().map(p -> new Layout.Leaf(UUID.randomUUID(),p.id())).toList());
            StateStore.State state=new StateStore.State(profiles,panes,1100,700,demo.appTheme(),demo.lastProfile(),Map.of(),true,Appearance.defaults(),StateStore.Tutorial.SKIPPED,true);
            CountDownLatch closed=new CountDownLatch(1);
            StateStore store=new StateStore(root.resolve("startup"));
            Workspace w=edt(() -> {
                Workspace window=new Workspace(store,state,helper,false);
                window.onClosed(closed::countDown); FxTestSupport.layout(window);
                check(window.sessionPanes().stream().noneMatch(SessionPane::busy),"Restoring state alone does not connect.");
                window.startup(); return window;
            });
            try {
                await(() -> edt(() -> w.sessionPanes().stream().filter(SessionPane::connected).count()==3
                        && w.sessionPanes().stream().noneMatch(p -> {
                            try { return (int)field(p,"credentialOperations")>0; } catch (Exception e) { throw new AssertionError(e); }
                        })),"Only complete loopback/manual profiles connect at startup; fixture missing-password lookup completes.");
                edt(() -> {
                    check(host.connectionCount()==3 && host.requestCount()==0,"Startup opens exactly the intended independent loopback sockets without AID transactions.");
                    check(w.lastProfile().id().equals(state.lastProfile()),"Background startup attempts retain the saved last-selected profile.");
                    check(Window.getWindows().stream().noneMatch(Window::isShowing),"Startup creates no password/profile prompt storm.");
                    w.startup();
                    check(w.sessionPanes().stream().filter(SessionPane::connected).count()==3,"Repeated startup callback never toggles connected sockets off.");
                    return null;
                });
            } finally { edt(() -> { w.close(false); return null; }); check(closed.await(5,TimeUnit.SECONDS),"Startup fixture closes cleanly."); }
        }
    }
    public static void main(String[] args) throws Exception {
        Ui.install(); Path root=Files.createTempDirectory(Path.of("build"),"usability-smoke-");
        List<Throwable> errors=new CopyOnWriteArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((thread,error) -> { errors.add(error); error.printStackTrace(); });
        try {
            configurations(root); mappings(); startup(root);
            CountDownLatch closed=new CountDownLatch(1);
            try (DemoHost host=new DemoHost()) {
                StateStore store=new StateStore(root.resolve("ui"));
                Workspace w=edt(() -> {
                    Workspace result=new Workspace(store,Main.demoState(host.port()),root.resolve("absent-credential-helper"),false);
                    result.onClosed(closed::countDown);
                    FxTestSupport.layout(result); result.connectAll(); return result;
                });
                try {
                    await(() -> edt(() -> w.active().connected() && w.active().screen().getScreenFields().getSize()>0),"Loopback fixture connected.");
                    int transactions=host.requestCount();
                    edt(() -> { tutorialAndManual(w); return null; });
                    check(host.requestCount()==transactions && edt(() -> w.sessionPanes().stream().allMatch(SessionPane::connected)),
                            "Guide completion/replay keeps active sessions connected and submits no host transaction.");
                    edt(() -> { dispatching(w); return null; });
                    Profile login=new Profile(UUID.randomUUID(),"Shared login","",23,"","FIXTURE",true,true,"37","Phosphor","None",1,Map.of(),"");
                    Profile child=Profile.create("Target");
                    ProfilesDialog d=edt(() -> {
                        w.putProfile(login); w.putProfile(child);
                        ProfilesDialog result=new ProfilesDialog(w,child); image(result.stage(),"profile-editor-150",1.5);
                        @SuppressWarnings("unchecked") ComboBox<Object> source=(ComboBox<Object>)field(result,"loginFrom");
                        source.setValue(login);
                        check(((TextField)field(result,"username")).isDisabled() && ((TextField)field(result,"username")).getText().equals("FIXTURE"),
                                "Real login source shows inherited username with no plaintext roundtrip.");
                        return result;
                    });
                    await(() -> edt(() -> !((boolean)field(d,"saving"))),"Linked Apply completes without touching a missing credential helper.");
                    edt(() -> {
                        Profile linked=w.profileList().stream().filter(p -> p.id().equals(child.id())).findFirst().orElseThrow();
                        check(linked.loginSource().equals(login.id()) && linked.username().isEmpty() && !linked.rememberPassword(),
                                "Apply saves stable indirection, never own password/auth copies.");
                        check(w.loginProfile(linked).credentialTarget().equals(login.credentialTarget()),"Linked lookup uses exactly the source OS target.");
                        SessionPane pane=w.active(); w.selectProfile(pane,linked); set(pane,"credentialOperations",1);
                        try {
                            w.validateProfile(new Profile(login.id(),login.name(),login.host(),23,"","OTHER",true,true,"37","Phosphor","None",1,Map.of(),""));
                            throw new AssertionError("Pending login edit accepted.");
                        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("pending"),"Pending auth identity cannot race an asynchronous password lookup/save."); }
                        finally { set(pane,"credentialOperations",0); }
                        Profile renamed=new Profile(login.id(),"Renamed login",login.host(),23,"","FIXTURE",true,true,"37","Phosphor","None",1,Map.of(),"");
                        w.putProfile(renamed);
                        check(w.loginProfile(linked).id().equals(login.id()) && w.loginProfile(linked).name().equals("Renamed login"),
                                "Source rename follows stable identity without retargeting its secret.");
                        Profile duplicate=w.connectionProfile(linked).duplicate();
                        check(duplicate.loginSource()==null && !duplicate.id().equals(login.id()),
                                "Duplicate becomes independent with its own credential identity and no shared reference.");
                        try {
                            w.validateProfile(new Profile(login.id(),login.name(),login.host(),23,"","",false,false,"37","Phosphor","None",1,Map.of(),""));
                            throw new AssertionError("Invalid source edit accepted.");
                        } catch (IllegalArgumentException expected) { checks++; }
                        @SuppressWarnings("unchecked") ComboBox<Object> srcCombo=(ComboBox<Object>)field(d,"loginFrom");
                        srcCombo.getSelectionModel().select(0);
                        check(!((TextField)field(d,"username")).isDisabled(),
                                "Independent login re-enables username field.");
                        d.dispose();
                        Profile fresh=Profile.create("Recommended"); w.putProfile(fresh);
                        ProfilesDialog recommendation=new ProfilesDialog(w,fresh);
                        check(((CheckBox)field(recommendation,"automatic")).isSelected() && ((CheckBox)field(recommendation,"remember")).isSelected(),
                                "New-profile recommendation checkboxes start enabled, as explicitly requested.");
                        check(((TextField)field(recommendation,"host")).getText().equals("10.1.2.3"),"Real profile form starts at the user-directed IP.");
                        recommendation.dispose();
                        Profile manual=new Profile(UUID.randomUUID(),"Existing manual","",23,"","",false,false,"37","Phosphor","None",1,Map.of(),"");
                        w.putProfile(manual); ProfilesDialog existing=new ProfilesDialog(w,manual);
                        check(!((CheckBox)field(existing,"automatic")).isSelected() && !((CheckBox)field(existing,"remember")).isSelected()
                                && ((TextField)field(existing,"host")).getText().isEmpty(),"Existing explicit off/blank choices are never overwritten by new defaults.");
                        existing.dispose();
                        return null;
                    });
                    ProfilesDialog unfinished=edt(() -> {
                        Profile fresh=Profile.create("Unfinished"); w.putProfile(fresh);
                        ProfilesDialog form=new ProfilesDialog(w,fresh);
                        ((TextField)field(form,"name")).setText("Unfinished saved");
                        return form;
                    });
                    await(() -> edt(() -> !unfinished.editor.saving()),"Recommended blank-username Apply finishes without credentials.");
                    edt(() -> {
                        String status=((Label)field(unfinished,"error")).getText();
                        check(!unfinished.editor.saveButton().isVisible()
                                && (status.isBlank() || status.equals("Saved"))
                                && ((Profile)field(unfinished,"selected")).automaticSignOn(),
                                "Autosave retains an unfinished recommended login without touching credentials.");
                        button(unfinished.stage(),"Connect").fire();
                        check(((Label)field(unfinished,"error")).getText().contains("Enter a username")
                                && !w.active().busy(),"Connect requires username before any credential/network attempt.");
                        unfinished.dispose(); return null;
                    });
                    check(errors.isEmpty(),"No asynchronous UI/configuration failures: "+errors);
                } finally {
                    edt(() -> { w.close(false); return null; });
                    check(closed.await(5,TimeUnit.SECONDS),"Production workspace closes and releases its lock.");
                }
            }
        } finally {
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
        }
        System.out.println("PASS usability: "+checks+" configuration/guide/login/dispatch checks; private clipboard, hidden windows, no OS credentials.");
        Ui.finishTestRun();
    }
}
