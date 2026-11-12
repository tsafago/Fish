package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import org.tn5250j.keyboard.KeyMnemonic;
import pane5250.model.*;
import pane5250.demo.*;
import pane5250.net.TelnetCodec;
import org.tn5250j.Session5250;

/** Synthetic keys, isolated workspaces and never-shown real production controls. */
public final class KeyboardPreferencesSmoke {
    private static int checks;
    private static Path evidence;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void rejects(Work action,String message) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IOException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static Object field(Object owner,String name) throws Exception {
        if (owner instanceof ProfilesDialog profiles) owner=profiles.editor;
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> result=new FutureTask<>(work); SwingUtilities.invokeAndWait(result); return result.get();
    }
    private static JButton button(Container owner,String text) {
        for (Component child:owner.getComponents()) {
            if (child instanceof JButton b && text.equals(b.getText())) return b;
            if (child instanceof Container parent) { JButton found=button(parent,text); if (found!=null) return found; }
        }
        return null;
    }
    private static <T extends Component> T component(Container owner,Class<T> type) {
        for (Component child:owner.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container parent) { T found=component(parent,type); if (found!=null) return found; }
        }
        return null;
    }
    private static KeyEvent key(Component source,int id,int code,int location,int mods) {
        return new KeyEvent(source,id,System.currentTimeMillis(),mods,code,KeyEvent.CHAR_UNDEFINED,location);
    }
    private static void model(Path root) throws Exception {
        JPanel source=new JPanel();
        for (String value:List.of("ENTER","ENTER@numpad","ctrl ENTER@numpad","shift F12","ctrl alt U","altGraph Q",
                "UP@numpad","NUMPAD0@numpad","ctrl ADD@numpad","F1@unknown","F2@left","F2@right")) {
            KeyChord parsed=KeyChord.parse(value);
            check(parsed.equals(KeyChord.parse(parsed.toString())),"Typed exact roundtrip: "+value);
            check(parsed.equals(KeyChord.from(key(source,KeyEvent.KEY_PRESSED,parsed.code(),parsed.location(),parsed.modifiers()))),"Capture exact roundtrip: "+value);
        }
        check(KeyChord.parse("NUMPAD_ENTER").equals(KeyChord.parse("ENTER@numpad")),"Readable keypad Enter alias");
        check(KeyChord.from(key(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_KP_UP,4,0)).equals(KeyChord.parse("UP@numpad")),"Normalize Java numpad arrow variant");
        check(KeyChord.from(key(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_Q,1,
                InputEvent.CTRL_DOWN_MASK|InputEvent.ALT_DOWN_MASK|InputEvent.ALT_GRAPH_DOWN_MASK)).equals(KeyChord.parse("altGraph Q")),"AltGraph is not silently conflated with Ctrl+Alt");
        for (String value:List.of("typed a","released ENTER","ctrl","alt F4","alt SPACE","ctrl ESCAPE","meta A","ENTER@wrong"))
            rejects(() -> KeyChord.parse(value),"Reject invalid/OS chord "+value);
        rejects(() -> KeyBindings.effective(Map.of("Host F1","F2","Host F2","F2")),"Two explicit owners conflict");
        rejects(() -> KeyBindings.effective(Map.of("Host F1","ENTER\nENTER@standard")),"Equivalent duplicates conflict");
        rejects(() -> KeyBindings.effective(Map.of("Quit","Q")),"Application typing key rejected");
        rejects(() -> KeyBindings.effective(Map.of("misspelled action","F1")),"Unknown action rejected");
        Map<KeyChord,String> defaults=KeyBindings.effective(Map.of());
        check(defaults.get(KeyChord.parse("ENTER")).equals("ENTER") && defaults.get(KeyChord.parse("ENTER@numpad")).equals("ENTER"),"Both physical Enter locations default to host Enter");
        check(defaults.get(KeyChord.parse("ctrl ENTER")).equals("FIELD EXIT"),"Explicit accessible Field Exit remains");
        check(defaults.get(KeyChord.parse("ctrl COMMA")).equals(Shortcuts.SETTINGS),"Conventional canonical Settings shortcut");
        check(defaults.get(KeyChord.parse("ESCAPE")).equals("ATTN") && defaults.get(KeyChord.parse("shift ESCAPE")).equals("SYSREQ"),"Attention/System Request defaults");
        Keymap custom=Keymap.global(Map.of("Host F13","ENTER@numpad\nctrl F3"));
        check(custom.lookup(key(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,4,0))==KeyMnemonic.PF13,"Custom keypad Enter wins over default");
        check(custom.lookup(key(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,1,0))==KeyMnemonic.ENTER,"Main Enter stays independent");
        check(custom.lookup(key(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_F3,1,InputEvent.CTRL_DOWN_MASK))==KeyMnemonic.PF13,"Two distinct bindings invoke same action");
        check(custom.lookup(key(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,0,0))==KeyMnemonic.ENTER,"Unlocated Java event has standard fallback, not invented keypad identity");
        check(!KeyBindings.effective(Map.of(KeyBindings.UNBOUND,"ENTER@numpad")).containsKey(KeyChord.parse("ENTER@numpad")),"Per-key unbinding");
        check(!KeyBindings.effective(Map.of("ENTER","")).containsValue("ENTER"),"Per-action unbinding");
        check(Keymap.global(Map.of(KeyBindings.UNBOUND,"ENTER@unknown")).lookup(
                key(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,0,0))==null,"Explicit unlocated suppression beats standard fallback");
        check(KeyBindings.defaultClaims(Map.of("Host F13","ENTER@numpad")).stream().anyMatch(s -> s.contains("ENTER -> Host F13")),"Default takeover explicitly reportable");
        Profile first=Profile.createKerberos("First"),second=Profile.createKerberos("Second");
        StateStore.State base=new StateStore.State(List.of(first,second),new Layout.Leaf(UUID.randomUUID(),first.id()),1000,700);
        Properties legacy=StateStore.properties(base); legacy.remove("appearance.cursorStyle"); legacy.setProperty("version","11");
        legacy.remove("windows"); legacy.remove("info.footerDevice");
        legacy.remove("addProfile"); legacy.remove("savedLayouts"); legacy.remove("selectedLayout");
        legacy.setProperty("profile."+first.id()+".key.F2","PF13");
        legacy.setProperty("profile."+second.id()+".key.F2","PF14");
        legacy.setProperty("profile."+first.id()+".key.ctrl F1","PF20");
        legacy.setProperty("profile."+second.id()+".key.ctrl F1","PF20");
        legacy.setProperty("profile."+first.id()+".key.ENTER","PF21");
        legacy.setProperty("shortcut.Copy selection / screen","ctrl K");
        legacy.setProperty("shortcut.Appearance...","ctrl COMMA");
        Path directory=Files.createDirectory(root.resolve("migration"));
        try (StateStore store=new StateStore(directory)) {
            StateStore.State state=store.decode(legacy);
            check(state.profiles().stream().allMatch(p -> p.keymap().isEmpty()),"Profile maps removed only after migration");
            Map<KeyChord,String> effective=KeyBindings.effective(state.shortcuts());
            check(!effective.containsKey(KeyChord.parse("F2")),"Conflicting profiles never choose a winner");
            check(effective.get(KeyChord.parse("ctrl F1")).equals("Host F20"),"Identical definitions deduplicate");
            check(effective.get(KeyChord.parse("ENTER")).equals("Host F21") && effective.get(KeyChord.parse("ENTER@numpad")).equals("ENTER"),"Legacy profile plain Enter was main-only");
            check(effective.get(KeyChord.parse("ctrl K")).equals("Copy selection / screen"),"Application override preserved");
            check(effective.get(KeyChord.parse("ctrl COMMA")).equals(Shortcuts.SETTINGS),"Explicit old settings binding migrates to the canonical action");
            check(state.keyboardRecovery().get("report").contains("PF13") && state.keyboardRecovery().get("report").contains("PF14"),"Exact conflicting definitions retained in one report");
            check(KeyboardMigration.report(state.keyboardRecovery()).contains(first.id().toString()),"Original stable IDs recoverable");
            store.save(state); check(store.load().equals(state),"Format-12 exact lists and complete migration archive roundtrip");
            check(ProfileManagement.delete(state,second.id()).keyboardRecovery().equals(state.keyboardRecovery()),"Profile deletion cannot lose recovery archive");
            Properties bad=StateStore.properties(state); bad.setProperty("profile."+first.id()+".key.F1","PF1");
            rejects(() -> store.decode(bad),"New-format profile map rejected, never ignored");
            Properties relabeled=StateStore.properties(state); relabeled.setProperty("version","11");
            rejects(() -> store.decode(relabeled),"Relabeling exact keys rejected");
            store.factoryReset();
        }
        try (StateStore store=new StateStore(directory)) {
            check(store.load().keyboardRecovery().isEmpty() && store.load().shortcuts().isEmpty(),"Factory reset clears global keys and recovery archive");
        }
        Map<String,String> broad=KeyboardMigration.migrate(List.of(first),Map.of("Host F13","ENTER")).bindings();
        check(KeyBindings.host(broad).get(KeyChord.parse("ENTER@numpad"))==KeyMnemonic.PF13,"Legacy application broad Enter retained for both locations");
        check(ConfigSchema.document().contains("binding.{exact-action-title}") && !ConfigSchema.document().contains("hostOverridePrefix"),"Export schema uses global exact lists only");
        Map<KeyChord,String> aliases=KeyBindings.effective(KeyboardMigration.migrate(List.of(first),Map.of("Copy selection / screen","ctrl C")).bindings());
        check(aliases.get(KeyChord.parse("ctrl INSERT")).equals("Copy selection / screen"),"Legacy explicit default retains its old aliases");
    }
    private static void ui(Workspace workspace,Path root) throws Exception {
        AppShortcuts dispatch=(AppShortcuts)field(workspace,"shortcuts");
        workspace.applyShortcuts(Map.of("Host F13","ENTER@numpad\nctrl F3"));
        Keymap one=workspace.active().keys();
        workspace.executeCommand("Add pane");
        check(workspace.active().keys().exactBindings().equals(one.exactBindings()),"Every pane uses same application map");
        TerminalView terminal=workspace.active().view();
        KeyEvent custom=key(terminal,KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,4,0);
        check(dispatch.dispatchKeyEvent(custom) && custom.isConsumed(),"Actual global dispatcher claims exact custom numpad key");
        check(dispatch.dispatchKeyEvent(key(terminal,KeyEvent.KEY_RELEASED,KeyEvent.VK_ENTER,4,0)),"Claimed release suppressed");
        dispatch.dispatchKeyEvent(key(terminal,KeyEvent.KEY_PRESSED,KeyEvent.VK_LEFT,1,0));
        check(!dispatch.dispatchKeyEvent(key(terminal,KeyEvent.KEY_PRESSED,KeyEvent.VK_A,1,0)),"Unbound printable press remains input");
        KeyEvent typed=new KeyEvent(terminal,KeyEvent.KEY_TYPED,0,0,KeyEvent.VK_UNDEFINED,'a');
        check(!dispatch.dispatchKeyEvent(typed),"Held navigation key cannot swallow later printable input");
        dispatch.dispatchKeyEvent(key(terminal,KeyEvent.KEY_RELEASED,KeyEvent.VK_LEFT,1,0));
        JTextField text=new JTextField(); workspace.getContentPane().add(text,BorderLayout.SOUTH);
        check(!dispatch.dispatchKeyEvent(key(text,KeyEvent.KEY_PRESSED,KeyEvent.VK_C,1,InputEvent.CTRL_DOWN_MASK)),"Dialog/field copy is never terminal clipboard access");
        check(!dispatch.dispatchKeyEvent(key(text,KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,4,0)),"Text-field keypad Enter never invokes a host binding");
        check(!dispatch.dispatchKeyEvent(key(terminal,KeyEvent.KEY_PRESSED,KeyEvent.VK_F4,1,InputEvent.ALT_DOWN_MASK)),"Reserved Windows control passed through");
        Preferences preferences=new Preferences(workspace,"Keyboard");
        JDialog dialog=preferences.dialog();
        check(!dialog.isShowing(),"Preferences production window never shown");
        @SuppressWarnings("unchecked") JList<String> navigation=(JList<String>)field(preferences,"navigation");
        check(navigation.getModel().getSize()==5 && navigation.getSelectedValue().equals("Keyboard Shortcuts"),"Flat indexed categories and inline keyboard editor");
        KeyboardEditor editor=(KeyboardEditor)field(preferences,"keyboard");
        JTable actions=(JTable)field(editor,"table"); JTextArea keys=(JTextArea)field(editor,"keys");
        int selected=-1;
        for (int n=0;n<actions.getRowCount();n++) if (actions.getValueAt(n,1).equals("Host F13")) selected=n;
        actions.setRowSelectionInterval(selected,selected);
        check(keys.getText().contains("ENTER@numpad"),"Actual editor displays exact typed syntax");
        keys.setText("ENTER@numpad\nctrl F4");
        JCheckBox window=(JCheckBox)field(preferences,"window"); boolean before=workspace.appearanceSettings().windowOutline();
        window.setSelected(!before);
        navigation.setSelectedValue("Appearance",true);
        check(workspace.appearanceSettings().windowOutline()==before,"Rapid edits coalesce rather than writing each event");
        UsabilityIterationSmoke.settle(() -> !preferences.saving(),"Autosave did not settle");
        check(workspace.appearanceSettings().windowOutline()!=before && workspace.shortcutOverrides().get("Host F13").contains("ctrl F4"),"Actual valid panes and keyboard controls apply without a button");
        window.setSelected(before); button(dialog,"Close").doClick(0);
        UsabilityIterationSmoke.settle(preferences::ended,"Close did not flush valid changes");
        check(workspace.appearanceSettings().windowOutline()==before,"Close keeps automatically persisted edits");
        Preferences invalid=new Preferences(workspace,"Terminal");
        JTextField path=(JTextField)field(invalid,"fontPath"); path.setText(root.resolve("missing.ttf").toString());
        boolean startupBefore=workspace.connectOnStartup();
        ((JCheckBox)field(invalid,"startup")).setSelected(!startupBefore);
        UsabilityIterationSmoke.settle(() -> !invalid.saving(),"Invalid input did not settle");
        check(workspace.connectOnStartup()!=startupBefore && ((JLabel)field(invalid,"error")).getText().toLowerCase(Locale.ROOT).contains("font"),"Invalid font remains marked without blocking unrelated valid settings");
        button(invalid.dialog(),"Reload saved values").doClick(0);
        invalid.dialog().dispose();
        Preferences hidden=new Preferences(workspace,"Updates");
        check(button(hidden.dialog(),"Show hidden updates")!=null,"Update preferences indexed as real controls");
        button(hidden.dialog(),"Show hidden updates").doClick(0); hidden.dialog().dispose();
        UsabilityIterationSmoke.settle(hidden::ended,"Hidden-update setting close did not settle");
        workspace.updateSettings(workspace.updateSettings().ignore("0.7.3"));
        Preferences ignored=new Preferences(workspace,"Updates");
        button(ignored.dialog(),"Show hidden updates").doClick(0);
        check(workspace.updateSettings().ignoredVersion().equals("0.7.3"),"Clearing hidden version coalesces with other edits");
        UsabilityIterationSmoke.settle(() -> !ignored.saving(),"Hidden update autosave did not settle");
        check(workspace.updateSettings().ignoredVersion().isEmpty(),"Hidden-version edit automatically persists: "+((JLabel)field(ignored,"error")).getText()+"; ignored="+field(ignored,"ignored")); ignored.dialog().dispose();
        ProfilesDialog profiles=new ProfilesDialog(workspace,workspace.profileList().getFirst());
        JToggleButton disclosure=(JToggleButton)field(profiles,"disclosure");
        JPanel advanced=(JPanel)field(profiles,"advanced");
        check(!advanced.isVisible() && !disclosure.isSelected(),"Profile advanced options start collapsed");
        check(((JComboBox<?>)field(profiles,"authentication")).isVisible() && !((JTextField)field(profiles,"username")).isVisible(),"Kerberos essentials, not password clutter");
        check(((JPanel)field(profiles,"advancedIdentity")).getParent()==advanced,"Kerberos service DNS stays under Advanced, including for IP transport");
        if (evidence!=null) snapshot(profiles,evidence.resolve("profiles.png"));
        JTextField port=(JTextField)field(profiles,"port"); String portBefore=port.getText();
        disclosure.doClick(0); port.setText("2323"); disclosure.doClick(0); disclosure.doClick(0);
        check(port.getText().equals("2323"),"Collapse/expand retains advanced draft values");
        @SuppressWarnings("unchecked") JComboBox<Profile.Authentication> authentication=(JComboBox<Profile.Authentication>)field(profiles,"authentication");
        authentication.setSelectedItem(Profile.Authentication.PASSWORD);
        check(((JTextField)field(profiles,"username")).isVisible() && ((JPasswordField)field(profiles,"password")).isVisible(),"Password mode surfaces its required controls");
        authentication.setSelectedItem(Profile.Authentication.KERBEROS);
        check(port.getText().equals("2323"),"Auth switching retains unrelated advanced values");
        port.setText("not-a-port"); disclosure.doClick(0); button(profiles,"Apply").doClick(0);
        check(advanced.isVisible() && ((JLabel)field(profiles,"error")).getText().contains("Port"),"Validation reveals hidden corrective advanced field");
        check(workspace.profileList().getFirst().port()==Integer.parseInt(portBefore),"Invalid profile draft never persisted");
        port.setText(portBefore); ((JTextField)field(profiles,"kerberosServer")).setText("not a DNS identity");
        disclosure.doClick(0); button(profiles,"Apply").doClick(0);
        check(advanced.isVisible() && ((JLabel)field(profiles,"error")).getText().startsWith("Kerberos server"),"Invalid collapsed service DNS reveals its corrective field");
        profiles.dispose();
        KeyRecorder recorder=new KeyRecorder();
        KeyEvent recorded=key(recorder.capture,KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,4,InputEvent.CTRL_DOWN_MASK);
        check(recorder.captureEvent(recorded) && recorded.isConsumed() && recorder.chosen().equals(KeyChord.parse("ctrl ENTER@numpad")),"Production recorder captures exact chord without host dispatch");
        recorder.captureEvent(key(recorder.capture,KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,4,0));
        check(recorder.chosen().equals(KeyChord.parse("ctrl ENTER@numpad")),"Repeat cannot replace captured chord after modifiers change");
        KeyEvent other=key(text,KeyEvent.KEY_PRESSED,KeyEvent.VK_A,1,0);
        check(!recorder.dispatchKeyEvent(other) && !other.isConsumed(),"Recorder never observes text/password fields elsewhere");
        button(recorder,"Clear / record again").doClick(0); check(recorder.chosen()==null,"Recorder can clear");
        KeyEvent reserved=key(recorder.capture,KeyEvent.KEY_PRESSED,KeyEvent.VK_F4,1,InputEvent.ALT_DOWN_MASK);
        check(!recorder.captureEvent(reserved) && !reserved.isConsumed() && recorder.chosen()==null,"Recorder passes reserved Windows controls through");
        rejects(() -> KeyChord.parse("WINDOWS"),"Standalone Windows key cannot be bound");
        java.util.concurrent.atomic.AtomicReference<KeyChord> accepted=new java.util.concurrent.atomic.AtomicReference<>();
        JDialog recordingDialog=KeyRecorder.dialog(workspace,accepted::set);
        KeyRecorder live=component(recordingDialog,KeyRecorder.class);
        live.captureEvent(key(live.capture,KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,4,0));
        check(!recordingDialog.getRootPane().getDefaultButton().isEnabled(),"Held recorded Enter cannot auto-accept itself");
        live.captureEvent(new KeyEvent(live.capture,KeyEvent.KEY_TYPED,0,0,KeyEvent.VK_UNDEFINED,'\n'));
        live.captureEvent(key(live.capture,KeyEvent.KEY_RELEASED,KeyEvent.VK_ENTER,4,0));
        check(recordingDialog.getRootPane().getDefaultButton().isEnabled(),"Releasing action key enables explicit acceptance");
        recordingDialog.getRootPane().getDefaultButton().doClick(0);
        check(KeyChord.parse("ENTER@numpad").equals(accepted.get()) && !recordingDialog.isDisplayable(),"Recorder accepts exact chord and disposes its scoped dispatcher");
        JDialog cancelRecording=KeyRecorder.dialog(workspace,chord -> { throw new AssertionError("Canceled recording accepted"); });
        KeyRecorder cancelCapture=component(cancelRecording,KeyRecorder.class);
        cancelCapture.captureEvent(key(cancelCapture.capture,KeyEvent.KEY_PRESSED,KeyEvent.VK_C,1,InputEvent.ALT_DOWN_MASK));
        check(!cancelRecording.isDisplayable(),"Recorder cancellation disposes without dispatch");
        check(!workspace.isShowing() && Arrays.stream(Window.getWindows()).noneMatch(Window::isShowing),"No fixture takes over desktop");
        Preferences render=new Preferences(workspace,"Keyboard");
        render.dialog().validate();
        for (double scale:new double[]{1,1.25,1.5,1.75,2}) {
            JPanel panel=(JPanel)render.dialog().getContentPane();
            BufferedImage image=new BufferedImage((int)(1040*scale),(int)(720*scale),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics(); g.scale(scale,scale); panel.printAll(g); g.dispose();
            check(image.getWidth()==(int)(1040*scale),"Actual Preferences render at "+scale);
            if (scale==1 && evidence!=null) javax.imageio.ImageIO.write(image,"png",evidence.resolve("preferences-keyboard.png").toFile());
        }
        if (evidence!=null) {
            @SuppressWarnings("unchecked") JList<String> categories=(JList<String>)field(render,"navigation");
            for (String category:List.of("General","Terminal","Panes")) {
                categories.setSelectedValue(category,true);
                snapshot(render.dialog(),evidence.resolve("preferences-"+category.toLowerCase(Locale.ROOT)+".png"));
            }
        }
        render.dialog().dispose();
    }
    private static void snapshot(JDialog dialog,Path path) throws IOException {
        if (!dialog.isDisplayable()) dialog.addNotify();
        dialog.validate(); JComponent content=(JComponent)dialog.getContentPane();
        BufferedImage image=new BufferedImage(content.getWidth(),content.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); content.printAll(g); g.dispose(); javax.imageio.ImageIO.write(image,"png",path.toFile());
    }
    private static void transactions(Workspace workspace,DemoHost host) throws Exception {
        SessionPane pane=edt(() -> {
            Profile profile=new Profile(UUID.randomUUID(),"Synthetic keyboard transaction","127.0.0.1",host.port(),"","",false,false,
                    "37","Phosphor","None",1,Map.of(),"");
            workspace.putProfile(profile); workspace.selectProfile(workspace.active(),profile); workspace.active().connect();
            return workspace.active();
        });
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (!edt(() -> pane.connected() && pane.screen()!=null && pane.screen().getScreenFields().getSize()>0)) {
            if (System.nanoTime()>end) throw new AssertionError("Synthetic host did not finish setup.");
            Thread.sleep(15);
        }
        edt(() -> {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            Session5250 recording=new Session5250("Synthetic keys","37",bytes,pane::problem);
            Field core=SessionPane.class.getDeclaredField("core"); core.setAccessible(true); core.set(pane,recording);
            AppShortcuts dispatch=(AppShortcuts)field(workspace,"shortcuts");
            for (int n=0;n<3;n++) {
                recording.accept(DemoScreens.screen(1,false,"SYNTHETIC")); bytes.reset();
                int code=n==2 ? KeyEvent.VK_F4 : KeyEvent.VK_ENTER;
                int location=n==0 ? KeyEvent.KEY_LOCATION_NUMPAD : KeyEvent.KEY_LOCATION_STANDARD;
                int mods=n==2 ? InputEvent.CTRL_DOWN_MASK : 0;
                check(dispatch.dispatchKeyEvent(key(pane.view(),KeyEvent.KEY_PRESSED,code,location,mods)),"Production transaction key claimed");
                dispatch.dispatchKeyEvent(new KeyEvent(pane.view(),KeyEvent.KEY_TYPED,0,mods,KeyEvent.VK_UNDEFINED,'\n'));
                dispatch.dispatchKeyEvent(key(pane.view(),KeyEvent.KEY_RELEASED,code,location,mods));
                byte[] packet=bytes.toByteArray();
                ByteArrayInputStream wire=new ByteArrayInputStream(packet);
                try (TelnetCodec codec=new TelnetCodec(wire,OutputStream.nullOutputStream(),Map.of(),message -> { throw new AssertionError(message); })) {
                    byte[] record=codec.readRecord();
                    check(record.length>=13 && (record[12]&255)==(n==1 ? 0xf1 : 0xb1),"Actual main Enter AID F1 / custom keypad and Ctrl+F4 PF13 AID B1");
                    check(wire.available()==0,"One pressed/typed/released key generates exactly one complete host transaction");
                }
            }
            ByteArrayOutputStream screen=new ByteArrayOutputStream();
            DemoScreens.command(screen,4,0x40,4,0x11,0,8);
            DemoScreens.at(screen,1,1,0x22,"FIXTURE"); DemoScreens.field(screen,2,1,8,0x43,0x24);
            DemoScreens.command(screen,4,0x42,0,0);
            recording.accept(DemoScreens.gds(3,screen.toByteArray()));
            pane.type('X'); bytes.reset();
            check(pane.recoverableInputError(),"Actual local numeric error available");
            dispatch.dispatchKeyEvent(key(pane.view(),KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,4,0));
            dispatch.dispatchKeyEvent(key(pane.view(),KeyEvent.KEY_RELEASED,KeyEvent.VK_ENTER,4,0));
            check(!pane.recoverableInputError() && bytes.size()==0,"First custom keypad Enter still performs Reset only with zero host bytes");
            dispatch.dispatchKeyEvent(key(pane.view(),KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,4,0));
            check(bytes.size()>=13 && (bytes.toByteArray()[12]&255)==0xb1,"Next keypad Enter resumes its explicit PF13 mapping");
            return null;
        });
    }
    public static void main(String[] args) throws Exception {
        Ui.install();
        String evidencePath=System.getProperty("fish.test.evidence","");
        if (!evidencePath.isBlank()) { evidence=Path.of(evidencePath); Files.createDirectories(evidence); }
        Path root=Files.createTempDirectory("Fish-keyboard-preferences-");
        try {
            model(root);
            Path directory=Files.createDirectory(root.resolve("ui"));
            StateStore store=new StateStore(directory);
            Profile offline=new Profile(UUID.randomUUID(),"Synthetic offline","",23,"","",false,false,
                    "37","Phosphor","None",1,Map.of(),"",null,Profile.Authentication.KERBEROS,"");
            StateStore.State state=new StateStore.State(List.of(offline),new Layout.Leaf(UUID.randomUUID(),offline.id()),1060,740,
                    AppTheme.Charcoal,offline.id(),Map.of(),true,Appearance.defaults(),StateStore.Tutorial.SKIPPED,false,
                    new UpdateSettings(false,UpdateSettings.Channel.PREVIEW_PATCHES,""));
            Workspace workspace=edt(() -> new Workspace(store,state,directory.resolve("absent-helper.ps1"),false));
            CountDownLatch closed=new CountDownLatch(1);
            try (DemoHost host=new DemoHost()) {
                edt(() -> { workspace.onClosed(closed::countDown); ui(workspace,root); return null; });
                transactions(workspace,host);
            }
            finally {
                edt(() -> { for (Window child:workspace.getOwnedWindows()) child.dispose(); workspace.close(false); return null; });
                check(closed.await(10,TimeUnit.SECONDS),"Production workspace closed with persistence");
            }
        } finally {
            try (var paths=Files.walk(root)) {
                for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Exact keys/migration/recorder/inline Settings/profile checks passed: "+checks);
    }
}
