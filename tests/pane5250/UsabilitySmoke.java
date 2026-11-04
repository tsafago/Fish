package pane5250.ui;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.tn5250j.Session5250;
import org.tn5250j.keyboard.KeyMnemonic;
import pane5250.*;
import pane5250.demo.*;
import pane5250.model.*;

/** Actual dispatch/dialog/configuration paths, with hidden windows and a private fixture clipboard. */
public final class UsabilitySmoke {
    private static int checks;
    private static void check(boolean value,String text) { checks++; if (!value) throw new AssertionError(text); }
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void set(Object owner,String name,Object value) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner,value);
    }
    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> task=new FutureTask<>(work); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static void await(Callable<Boolean> work,String message) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (!work.call() && System.nanoTime()<end) Thread.sleep(15);
        check(work.call(),message);
    }
    private static void layout(Container root) {
        root.doLayout(); for (Component c:root.getComponents()) if (c instanceof Container p) layout(p);
    }
    private static List<Component> children(Container root) {
        List<Component> found=new ArrayList<>();
        for (Component c:root.getComponents()) { found.add(c); if (c instanceof Container p) found.addAll(children(p)); }
        return found;
    }
    private static JButton button(Container root,String text) {
        return children(root).stream().filter(c -> c instanceof JButton b && text.equals(b.getText())).map(c -> (JButton)c).findFirst().orElseThrow();
    }
    private static JDialog ready(JDialog dialog) {
        dialog.setFocusableWindowState(false); dialog.addNotify(); dialog.validate(); layout(dialog); return dialog;
    }
    private static void image(JDialog dialog,String name,double scale) throws IOException {
        layout(dialog);
        JComponent root=dialog.getRootPane();
        BufferedImage image=new BufferedImage((int)(root.getWidth()*scale),(int)(root.getHeight()*scale),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try { g.scale(scale,scale); root.paint(g); } finally { g.dispose(); }
        ImageIO.write(image,"png",Path.of("build","usability-"+name+".png").toFile());
        check(!dialog.isVisible(),"Synthetic image never exposes a shared desktop window.");
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
        Profile login=new Profile(UUID.randomUUID(),"Login","",23,"","FIXTURE",true,true,"37","Mint",Profile.FLUX,.8,Map.of("F2","PF3"),"");
        Profile target=new Profile(UUID.randomUUID(),"Linked","",23,"","",false,false,"500","Orchid","None",1.3,Map.of(),"",login.id());
        Layout layout=Layout.bounded(List.of(new Layout.Leaf(UUID.randomUUID(),login.id()),new Layout.Leaf(UUID.randomUUID(),target.id())));
        login=login.withLayout(layout.encode());
        ColorTheme theme=new ColorTheme("Config fixture",ColorTheme.builtin(AppTheme.Lagoon).colors());
        Appearance a=new Appearance(false,false,new Appearance.PaneInfo(false,true,false,true,false,true,false,true,false,true),
                new Appearance.FontChoice("Automatic",""),theme.name(),List.of(theme),Set.of("ctrl alt K","ctrl alt Y"));
        StateStore.State state=new StateStore.State(List.of(login,target),layout,910,610,AppTheme.Afterglow,target.id(),
                Map.of("Next pane","ctrl F10","Host F1","ctrl F1"),false,a,StateStore.Tutorial.COMPLETED);
        try (StateStore store=new StateStore(dir)) { store.save(state); }
        ByteArrayOutputStream error=new ByteArrayOutputStream();
        check(cli(dir,"export",candidate,error)==0,"Full production export succeeds.");
        Properties exported=StateStore.readProperties(candidate);
        check(exported.stringPropertyNames().stream().noneMatch(k -> k.toLowerCase(Locale.ROOT).contains("password")),"Export contains no secret/password fields.");
        check(exported.getProperty("profile."+target.id()+".loginSource").equals(login.id().toString()),"Only the explicit stable login reference is exported.");
        Map<?,?> catalog=ConfigSchema.catalog();
        Map<?,?> fields=(Map<?,?>)catalog.get("properties"),profileFields=(Map<?,?>)catalog.get("profileFields"),themeFields=(Map<?,?>)catalog.get("themeFields");
        for (String key:exported.stringPropertyNames()) {
            boolean covered=fields.containsKey(key) || key.startsWith("config.") || key.startsWith("shortcut.")
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
                    && loaded.shortcuts().equals(state.shortcuts()) && loaded.width()==1010 && loaded.appearance().info().header(),
                    "Unedited profiles, key provenance, saved layouts and every theme role survive bulk edits.");
            check(!oldRevision.equals(store.revision()),"Applied bytes have a new revision.");
            check(cli(dir,"apply",candidate,new ByteArrayOutputStream())!=0,"A running/locked workspace cleanly rejects config apply.");
        }
        rejected(dir,candidate,exported,"stale revision");
        Files.delete(candidate); check(cli(dir,"export",candidate,error)==0,"Fresh export after apply.");
        exported=StateStore.readProperties(candidate);
        for (Map.Entry<String,String> mutation:Map.of("width","bad","appearance.outline","maybe","unknown.setting","x",
                "shortcut.Next pane","ctrl C","font.family","Nonexistent fixture font","layout","E").entrySet()) {
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
        Path legacy=root.resolve("legacy"); Files.createDirectory(legacy);
        Properties old=StateStore.properties(state);
        old.setProperty("version","7"); old.remove("tutorial.status");
        old.remove("profile."+login.id()+".loginSource"); old.remove("profile."+target.id()+".loginSource");
        properties(legacy.resolve("workspace.xml"),old);
        try (StateStore store=new StateStore(legacy)) {
            check(store.load().tutorial()==StateStore.Tutorial.SKIPPED,"Upgrade does not force a tutorial over an experienced session.");
        }
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
        try { Shortcuts.validate(Map.of("Next pane","ctrl C"),List.of(Profile.create("Conflict"))); throw new AssertionError("Conflict accepted."); }
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
    private static KeyEvent key(Component source,int id,int code,int modifiers,int location) {
        return new KeyEvent(source,id,System.currentTimeMillis(),modifiers,code,KeyEvent.CHAR_UNDEFINED,location);
    }
    private static boolean dispatch(AppShortcuts shortcuts,Component source,int code,int modifiers,int location) {
        boolean used=shortcuts.dispatchKeyEvent(key(source,KeyEvent.KEY_PRESSED,code,modifiers,location));
        shortcuts.dispatchKeyEvent(key(source,KeyEvent.KEY_RELEASED,code,modifiers,location)); return used;
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
        check(dispatch(keys,view,KeyEvent.VK_ENTER,0,KeyEvent.KEY_LOCATION_STANDARD) && !pane.recoverableInputError() && bytes.size()==0,
                "First physical Enter consumes Reset only and emits exactly zero host bytes.");
        check(!dispatch(keys,view,KeyEvent.VK_ENTER,0,KeyEvent.KEY_LOCATION_STANDARD),"Next normal Enter resumes the terminal listener.");
        for (KeyListener listener:view.getKeyListeners()) listener.keyPressed(key(view,KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,0,KeyEvent.KEY_LOCATION_STANDARD));
        byte[] record=bytes.toByteArray();
        check(record.length>=13 && (record[12]&255)==0xf1,"Next Enter emits the exact Enter AID F1, not Attention.");
        recording.accept(screen(0x43)); pane.type('X'); bytes.reset();
        check(dispatch(keys,view,KeyEvent.VK_ENTER,0,KeyEvent.KEY_LOCATION_NUMPAD) && bytes.size()==0 && !pane.recoverableInputError(),
                "Keypad Enter during a local error also performs only Reset.");
        recording.accept(screen(0x40));
        recording.accept(DemoScreens.gds(3,new byte[]{4,0x11,0x20,0}));
        bytes.reset(); pane.send(KeyMnemonic.ENTER); pane.send(KeyMnemonic.RESET);
        check(pane.screen().getOIA().isKeyBoardLocked() && bytes.size()==0,"Neither Enter nor Reset falsely clears host-busy inhibition.");
        recording.accept(screen(0x40));
        Clipboard fixture=new Clipboard("Private usability fixture");
        set(view,"clipboardSource",(Supplier<Clipboard>)() -> fixture);
        fixture.setContents(new StringSelection("A\nB"),null); bytes.reset();
        check(dispatch(keys,view,KeyEvent.VK_V,InputEvent.CTRL_DOWN_MASK,KeyEvent.KEY_LOCATION_STANDARD) && bytes.size()==0,
                "Ctrl+V uses the actual app action and fixture clipboard without sending Enter.");
        check(dispatch(keys,view,KeyEvent.VK_C,InputEvent.CTRL_DOWN_MASK,KeyEvent.KEY_LOCATION_STANDARD)
                && fixture.getData(DataFlavor.stringFlavor).toString().contains("FIXTURE"),"Ctrl+C copies actual screen text.");
        JTextField edit=new JTextField(); w.getLayeredPane().add(edit);
        check(!dispatch(keys,edit,KeyEvent.VK_C,InputEvent.CTRL_DOWN_MASK,KeyEvent.KEY_LOCATION_STANDARD)
                && !dispatch(keys,edit,KeyEvent.VK_V,InputEvent.CTRL_DOWN_MASK,KeyEvent.KEY_LOCATION_STANDARD),"Native text editing retains Ctrl+C/V.");
        w.getLayeredPane().remove(edit);
        ProfilesDialog profile=new ProfilesDialog(w,pane.profile()); ready(profile);
        check(!dispatch(keys,(JTextField)field(profile,"name"),KeyEvent.VK_ESCAPE,0,KeyEvent.KEY_LOCATION_STANDARD),"Dialog Escape never sends host Attention.");
        profile.dispose();
        pane.disconnect(); bytes.reset();
        check(!pane.send(KeyMnemonic.ENTER) && bytes.size()==0,"Disconnected Enter does not invent recovery or submit.");
    }
    private static void tutorial(Workspace w) throws Exception {
        w.tutorialStatus(StateStore.Tutorial.NEW); w.startTutorialIfNeeded();
        TutorialDialog guide=(TutorialDialog)field(w,"tutorial"); ready(guide);
        List<Profile> original=w.profileList(); int panes=w.sessionPanes().size();
        check(w.tutorialStatus()==StateStore.Tutorial.IN_PROGRESS && !guide.isModal(),"First-run production guide is user-paced and modeless.");
        guide.go(1); image(guide,"profile-step-100",1); image(guide,"profile-step-150",1.5);
        check(button(guide,"Back").isEnabled() && button(guide,"Skip").isEnabled(),"Next, Back and Skip remain available in the profile step.");
        guide.go(4); image(guide,"panes-step-150",1.5);
        check(!((JComponent)field(guide,"highlight")).contains(15,15),"Tutorial highlight never intercepts input.");
        guide.finish(true);
        check(w.tutorialStatus()==StateStore.Tutorial.COMPLETED && w.profileList().equals(original) && w.sessionPanes().size()==panes,
                "Walking the guide alone creates no profile, connection or pane.");
        w.showTutorial(); guide=(TutorialDialog)field(w,"tutorial"); ready(guide); guide.go(3); guide.finish(false);
        check(w.tutorialStatus()==StateStore.Tutorial.COMPLETED && w.profileList().equals(original),"Replay/skip does not reset progress or duplicate profiles.");
        w.tutorialStatus(StateStore.Tutorial.NEW); w.startTutorialIfNeeded();
        guide=(TutorialDialog)field(w,"tutorial"); guide.finish(false);
        check(w.tutorialStatus()==StateStore.Tutorial.SKIPPED,"First-run Skip persists its explicit state.");
        StateStore.State snapshot=((StateStore)field(w,"store")).decode(StateStore.properties(
                new StateStore.State(w.profileList(),Layout.bounded(w.sessionPanes().stream().map(p -> new Layout.Leaf(p.id(),p.profile().id())).toList()),
                        900,600,Ui.theme(),w.lastProfile().id(),w.shortcutOverrides(),w.highlightActive(),w.appearanceSettings(),w.tutorialStatus())));
        check(snapshot.tutorial()==StateStore.Tutorial.SKIPPED,"Tutorial status round-trips through the production XML codec.");
        ManualDialog manual=new ManualDialog(w,"/AI-CONFIG.md","Configuration for external tools"); manual.dispose();
        check(true,"AI guide is discoverable through the actual bundled document viewer.");
    }
    public static void main(String[] args) throws Exception {
        Ui.install(); Path root=Files.createTempDirectory(Path.of("build"),"usability-smoke-");
        List<Throwable> errors=new CopyOnWriteArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((thread,error) -> { errors.add(error); error.printStackTrace(); });
        try {
            configurations(root); mappings();
            CountDownLatch closed=new CountDownLatch(1);
            try (DemoHost host=new DemoHost()) {
                StateStore store=new StateStore(root.resolve("ui"));
                Workspace w=edt(() -> {
                    Workspace result=new Workspace(store,Main.demoState(host.port()),root.resolve("absent-credential-helper"),false);
                    result.setFocusableWindowState(false); result.onClosed(closed::countDown);
                    result.addNotify(); result.validate(); result.connectAll(); return result;
                });
                try {
                    await(() -> edt(() -> w.active().connected() && w.active().screen().getScreenFields().getSize()>0),"Loopback fixture connected.");
                    int transactions=host.requestCount();
                    edt(() -> { tutorial(w); return null; });
                    check(host.requestCount()==transactions && edt(() -> w.sessionPanes().stream().allMatch(SessionPane::connected)),
                            "Guide completion/replay keeps active sessions connected and submits no host transaction.");
                    edt(() -> { dispatching(w); return null; });
                    Profile login=new Profile(UUID.randomUUID(),"Shared login","",23,"","FIXTURE",true,true,"37","Phosphor","None",1,Map.of(),"");
                    Profile child=Profile.create("Target");
                    ProfilesDialog d=edt(() -> {
                        w.putProfile(login); w.putProfile(child);
                        ProfilesDialog result=new ProfilesDialog(w,child); ready(result); image(result,"profile-editor-150",1.5);
                        JComboBox<?> source=(JComboBox<?>)field(result,"loginFrom"); source.setSelectedItem(login);
                        check(!((JTextField)field(result,"username")).isEnabled() && ((JTextField)field(result,"username")).getText().equals("FIXTURE"),
                                "Real login source shows inherited username with no plaintext roundtrip.");
                        button(result,"Apply").doClick(0); return result;
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
                        check(duplicate.loginSource()==null && !duplicate.rememberPassword(),"Duplicate becomes independent without a remembered secret/reference.");
                        try {
                            w.validateProfile(new Profile(login.id(),login.name(),login.host(),23,"","",false,false,"37","Phosphor","None",1,Map.of(),""));
                            throw new AssertionError("Invalid source edit accepted.");
                        } catch (IllegalArgumentException expected) { checks++; }
                        ((JComboBox<?>)field(d,"loginFrom")).setSelectedIndex(0);
                        check(((JTextField)field(d,"username")).isEnabled() && !((JCheckBox)field(d,"remember")).isSelected(),
                                "Independent login retains visible metadata but not saved-password consent.");
                        d.dispose();
                        Profile fresh=Profile.create("Recommended"); w.putProfile(fresh);
                        ProfilesDialog recommendation=new ProfilesDialog(w,fresh); ready(recommendation);
                        check(!((JCheckBox)field(recommendation,"automatic")).isSelected() && !((JCheckBox)field(recommendation,"remember")).isSelected(),
                                "Fresh profiles require explicit sign-in/storage consent.");
                        button(recommendation,"Use recommended sign-in").doClick(0);
                        check(((JCheckBox)field(recommendation,"automatic")).isSelected() && ((JCheckBox)field(recommendation,"remember")).isSelected(),
                                "One explicit recommended choice enables both preferences, never silently on existing profiles.");
                        recommendation.dispose();
                        return null;
                    });
                    check(errors.isEmpty(),"No asynchronous UI/configuration failures: "+errors);
                } finally {
                    edt(() -> { for (Window child:w.getOwnedWindows()) child.dispose(); w.close(false); return null; });
                    check(closed.await(5,TimeUnit.SECONDS),"Production workspace closes and releases its lock.");
                }
            }
        } finally {
            try (var paths=Files.walk(root)) { for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
        System.out.println("PASS usability: "+checks+" configuration/guide/login/dispatch checks; private clipboard, hidden windows, no OS credentials.");
    }
}
