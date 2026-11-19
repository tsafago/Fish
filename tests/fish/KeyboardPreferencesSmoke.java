package fish.ui;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.tn5250j.Session5250;
import org.tn5250j.keyboard.KeyMnemonic;
import fish.demo.*;
import fish.model.*;
import fish.net.TelnetCodec;

/** Synthetic keys, isolated workspaces and never-shown real JavaFX controls. */
public final class KeyboardPreferencesSmoke {
    private static int checks;
    private static Path evidence;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void rejects(Work action,String message) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IOException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static Object field(Object owner,String name) throws Exception { return FxTestSupport.field(owner,name); }
    private static <T> T edt(Callable<T> work) throws Exception { return FxTestSupport.fx(work); }
    private static ButtonBase button(Stage stage,String text) { return FxTestSupport.button(stage,text); }
    private static ButtonBase button(javafx.scene.Parent root,String text) { return FxTestSupport.button(root,text); }

    private static KeyEvent awtKey(java.awt.Component source,int id,int code,int location,int mods) {
        return new KeyEvent(source,id,System.currentTimeMillis(),mods,code,KeyEvent.CHAR_UNDEFINED,location);
    }
    private static javafx.scene.input.KeyEvent fxKey(javafx.event.EventType<javafx.scene.input.KeyEvent> type,KeyCode code,boolean shift,boolean ctrl,boolean alt) {
        return new javafx.scene.input.KeyEvent(type,"","",code,shift,ctrl,alt,false);
    }

    private static void model(Path root) throws Exception {
        javax.swing.JPanel source=new javax.swing.JPanel();
        for (String value:List.of("ENTER","ENTER@numpad","ctrl ENTER@numpad","shift F12","ctrl alt U","altGraph Q",
                "UP@numpad","NUMPAD0@numpad","ctrl ADD@numpad","F1@unknown","F2@left","F2@right")) {
            KeyChord parsed=KeyChord.parse(value);
            check(parsed.equals(KeyChord.parse(parsed.toString())),"Typed exact roundtrip: "+value);
            check(parsed.equals(KeyChord.from(awtKey(source,KeyEvent.KEY_PRESSED,parsed.code(),parsed.location(),parsed.modifiers()))),"Capture exact roundtrip: "+value);
        }
        check(KeyChord.parse("NUMPAD_ENTER").equals(KeyChord.parse("ENTER@numpad")),"Readable keypad Enter alias");
        check(KeyChord.from(awtKey(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_KP_UP,4,0)).equals(KeyChord.parse("UP@numpad")),"Normalize Java numpad arrow variant");
        check(KeyChord.from(awtKey(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_Q,1,
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
        check(custom.lookup(awtKey(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,4,0))==KeyMnemonic.PF13,"Custom keypad Enter wins over default");
        check(custom.lookup(awtKey(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,1,0))==KeyMnemonic.ENTER,"Main Enter stays independent");
        check(custom.lookup(awtKey(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_F3,1,InputEvent.CTRL_DOWN_MASK))==KeyMnemonic.PF13,"Two distinct bindings invoke same action");
        check(custom.lookup(awtKey(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,0,0))==KeyMnemonic.ENTER,"Unlocated Java event has standard fallback, not invented keypad identity");
        check(!KeyBindings.effective(Map.of(KeyBindings.UNBOUND,"ENTER@numpad")).containsKey(KeyChord.parse("ENTER@numpad")),"Per-key unbinding");
        check(!KeyBindings.effective(Map.of("ENTER","")).containsValue("ENTER"),"Per-action unbinding");
        check(Keymap.global(Map.of(KeyBindings.UNBOUND,"ENTER@unknown")).lookup(
                awtKey(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,0,0))==null,"Explicit unlocated suppression beats standard fallback");
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
            Properties retired=StateStore.properties(state);
            retired.setProperty("binding.Increase divider","ctrl F7");
            retired.setProperty("binding.Compatibility and connection safety","ctrl F8");
            check(store.decode(retired).shortcuts().equals(state.shortcuts()),"Removing obsolete menu actions preserves other bindings on upgrade");
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
        check(dispatch.dispatchKey(terminal,fxKey(javafx.scene.input.KeyEvent.KEY_PRESSED,KeyCode.ENTER,false,false,false),KeyEvent.KEY_LOCATION_NUMPAD),
                "Actual global dispatcher claims exact custom numpad key");
        check(dispatch.dispatchKey(terminal,fxKey(javafx.scene.input.KeyEvent.KEY_RELEASED,KeyCode.ENTER,false,false,false),KeyEvent.KEY_LOCATION_NUMPAD),
                "Claimed release suppressed");
        dispatch.dispatchKey(terminal,fxKey(javafx.scene.input.KeyEvent.KEY_PRESSED,KeyCode.LEFT,false,false,false),KeyEvent.KEY_LOCATION_STANDARD);
        check(!dispatch.dispatchKey(terminal,fxKey(javafx.scene.input.KeyEvent.KEY_PRESSED,KeyCode.A,false,false,false),KeyEvent.KEY_LOCATION_STANDARD),
                "Unbound printable press remains input");
        javafx.scene.input.KeyEvent typed=new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_TYPED,"a","a",KeyCode.UNDEFINED,false,false,false,false);
        check(!dispatch.dispatchKey(terminal,typed,KeyEvent.KEY_LOCATION_STANDARD),"Held navigation key cannot swallow later printable input");
        dispatch.dispatchKey(terminal,fxKey(javafx.scene.input.KeyEvent.KEY_RELEASED,KeyCode.LEFT,false,false,false),KeyEvent.KEY_LOCATION_STANDARD);
        TextField text=new TextField();
        check(!dispatch.dispatchKey(text,fxKey(javafx.scene.input.KeyEvent.KEY_PRESSED,KeyCode.C,false,true,false),KeyEvent.KEY_LOCATION_STANDARD),
                "Dialog/field copy is never terminal clipboard access");
        check(!dispatch.dispatchKey(text,fxKey(javafx.scene.input.KeyEvent.KEY_PRESSED,KeyCode.ENTER,false,false,false),KeyEvent.KEY_LOCATION_NUMPAD),
                "Text-field keypad Enter never invokes a host binding");
        check(!dispatch.dispatchKey(terminal,fxKey(javafx.scene.input.KeyEvent.KEY_PRESSED,KeyCode.F4,false,false,true),KeyEvent.KEY_LOCATION_STANDARD),
                "Reserved Windows control passed through");

        Preferences preferences=new Preferences(workspace,"Keyboard");
        Stage dialog=preferences.dialog();
        check(!dialog.isShowing(),"Preferences production window never shown");
        @SuppressWarnings("unchecked") ListView<String> navigation=(ListView<String>)field(preferences,"navigation");
        check(navigation.getItems().contains("Shortcuts") && "Keyboard Shortcuts".equals(navigation.getSelectionModel().getSelectedItem()),
                "Keyboard bindings open separately from desktop shortcuts");
        KeyboardEditor editor=(KeyboardEditor)field(preferences,"keyboard");
        @SuppressWarnings("unchecked") TableView<KeyboardEditor.ActionBindingRow> actions=(TableView<KeyboardEditor.ActionBindingRow>)field(editor,"table");
        TextArea keys=(TextArea)field(editor,"keys");
        int selected=-1;
        for (int n=0;n<actions.getItems().size();n++) if (actions.getItems().get(n).action.equals("Host F13")) selected=n;
        actions.getSelectionModel().select(selected);
        check(keys.getText().contains("ENTER@numpad"),"Actual editor displays exact typed syntax");
        keys.setText("ENTER@numpad\nctrl F4");
        CheckBox window=(CheckBox)field(preferences,"window"); boolean before=workspace.appearanceSettings().windowOutline();
        window.setSelected(!before);
        navigation.getSelectionModel().select("Appearance");
        check(workspace.appearanceSettings().windowOutline()!=before && preferences.saving(),"Discrete UI edits apply immediately; durable writes remain pending");
        UsabilityIterationSmoke.settle(() -> !preferences.saving(),"Autosave did not settle");
        check(workspace.appearanceSettings().windowOutline()!=before && workspace.shortcutOverrides().get("Host F13").contains("ctrl F4"),
                "Actual valid panes and keyboard controls apply without a button");
        window.setSelected(before); ((Button)dialog.getScene().lookup(".fish-window-close")).fire();
        UsabilityIterationSmoke.settle(preferences::ended,"Close did not flush valid changes");
        check(workspace.appearanceSettings().windowOutline()==before,"Close keeps automatically persisted edits");

        Preferences invalid=new Preferences(workspace,"Terminal");
        TextField path=(TextField)field(invalid,"fontPath"); path.setText(root.resolve("missing.ttf").toString());
        boolean startupBefore=workspace.connectOnStartup();
        ((CheckBox)field(invalid,"startup")).setSelected(!startupBefore);
        UsabilityIterationSmoke.settle(() -> !invalid.saving(),"Invalid input did not settle");
        check(workspace.connectOnStartup()!=startupBefore && ((Label)field(invalid,"error")).getText().toLowerCase(Locale.ROOT).contains("font"),
                "Invalid font remains marked without blocking unrelated valid settings");
        button(invalid.dialog(),"Reload saved values").fire();
        invalid.forceDispose();

        workspace.updateSettings(workspace.updateSettings().ignore("0.7.3"));
        Preferences updates=new Preferences(workspace,"Updates");
        CheckBox discover=(CheckBox)field(updates,"checkUpdates");
        boolean wasEnabled=discover.isSelected(); discover.fire(); updates.closeSettings();
        UsabilityIterationSmoke.settle(updates::ended,"Discovery preference did not persist on close");
        check(workspace.updateSettings().startup()!=wasEnabled && workspace.updateSettings().ignoredVersion().equals("0.7.3"),
                "Changing discovery does not discard an existing update policy.");

        ProfilesDialog profiles=new ProfilesDialog(workspace,workspace.profileList().getFirst());
        check(profiles.editor.deviceField().isVisible(),"Optional device is beside the connection settings");
        check(((ComboBox<?>)field(profiles,"authentication")).isVisible() && !((TextField)field(profiles,"username")).isVisible(),
                "Kerberos essentials, not password clutter");
        if (evidence!=null) snapshot(profiles.stage(),evidence.resolve("profiles.png"));
        Profile originalProfile=workspace.profileList().getFirst();
        @SuppressWarnings("unchecked") ComboBox<Profile.Authentication> authentication=(ComboBox<Profile.Authentication>)field(profiles,"authentication");
        authentication.setValue(Profile.Authentication.PASSWORD);
        check(((TextField)field(profiles,"username")).isVisible() && ((Node)field(profiles,"passwordWrapper")).isVisible(),
                "Password mode surfaces its required controls");
        authentication.setValue(Profile.Authentication.KERBEROS);
        profiles.editor.deviceField().setText("not a device");
        check(profiles.editor.error().startsWith("Device") && workspace.profileList().getFirst().device().equals(originalProfile.device()),
                "Invalid device remains editable without changing the saved value");
        profiles.editor.deviceField().setText(originalProfile.device());
        profiles.dispose();

        KeyRecorder recorder=new KeyRecorder();
        check(recorder.captureFxEvent(fxKey(javafx.scene.input.KeyEvent.KEY_PRESSED,KeyCode.ENTER,false,true,false),KeyEvent.KEY_LOCATION_NUMPAD)
                && recorder.chosen().equals(KeyChord.parse("ctrl ENTER@numpad")),"Production recorder captures exact chord without host dispatch");
        recorder.captureFxEvent(fxKey(javafx.scene.input.KeyEvent.KEY_PRESSED,KeyCode.ENTER,false,false,false),KeyEvent.KEY_LOCATION_NUMPAD);
        check(recorder.chosen().equals(KeyChord.parse("ctrl ENTER@numpad")),"Repeat cannot replace captured chord after modifiers change");
        button(recorder,"Clear / record again").fire(); check(recorder.chosen()==null,"Recorder can clear");
        check(!recorder.captureFxEvent(fxKey(javafx.scene.input.KeyEvent.KEY_PRESSED,KeyCode.F4,false,false,true),KeyEvent.KEY_LOCATION_STANDARD)
                && recorder.chosen()==null,"Recorder passes reserved Windows controls through");
        rejects(() -> KeyChord.parse("WINDOWS"),"Standalone Windows key cannot be bound");

        java.util.concurrent.atomic.AtomicReference<KeyChord> accepted=new java.util.concurrent.atomic.AtomicReference<>();
        Stage recordingDialog=KeyRecorder.dialog(workspace.stage(),accepted::set);
        KeyRecorder live=(KeyRecorder)recordingDialog.getProperties().get("fish.recorder");
        Button acceptBtn=(Button)recordingDialog.getProperties().get("fish.accept");
        live.captureFxEvent(fxKey(javafx.scene.input.KeyEvent.KEY_PRESSED,KeyCode.ENTER,false,false,false),KeyEvent.KEY_LOCATION_NUMPAD);
        check(acceptBtn.isDisabled(),"Held recorded Enter cannot auto-accept itself");
        live.captureFxEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_TYPED,"\n","\n",KeyCode.UNDEFINED,false,false,false,false),KeyEvent.KEY_LOCATION_NUMPAD);
        live.captureFxEvent(fxKey(javafx.scene.input.KeyEvent.KEY_RELEASED,KeyCode.ENTER,false,false,false),KeyEvent.KEY_LOCATION_NUMPAD);
        check(!acceptBtn.isDisabled(),"Releasing action key enables explicit acceptance");
        acceptBtn.fire();
        check(KeyChord.parse("ENTER@numpad").equals(accepted.get()) && !recordingDialog.isShowing(),"Recorder accepts exact chord and closes");

        Stage cancelRecording=KeyRecorder.dialog(workspace.stage(),chord -> { throw new AssertionError("Canceled recording accepted"); });
        KeyRecorder cancelCapture=(KeyRecorder)cancelRecording.getProperties().get("fish.recorder");
        cancelCapture.captureFxEvent(fxKey(javafx.scene.input.KeyEvent.KEY_PRESSED,KeyCode.C,false,false,true),KeyEvent.KEY_LOCATION_STANDARD);
        check(!cancelRecording.isShowing(),"Recorder cancellation disposes without dispatch");
        check(!workspace.isShowing() && Window.getWindows().stream().noneMatch(Window::isShowing),"No fixture takes over desktop");

        Preferences render=new Preferences(workspace,"Keyboard");
        FxTestSupport.layout(render.dialog());
        for (double scale:new double[]{1,1.25,1.5,1.75,2}) {
            var img=FxTestSupport.snapshot(render.dialog(),scale);
            check(img.getWidth()>0,"Actual Preferences render at "+scale);
            if (scale==1 && evidence!=null) javax.imageio.ImageIO.write(img,"png",evidence.resolve("preferences-keyboard.png").toFile());
        }
        if (evidence!=null) {
            for (String category:List.of("General","Appearance","Profiles")) {
                render.select(category);
                snapshot(render.dialog(),evidence.resolve("preferences-"+category.toLowerCase(Locale.ROOT)+".png"));
            }
        }
        render.forceDispose();
    }

    private static void snapshot(Stage dialog,Path path) throws IOException {
        FxTestSupport.layout(dialog);
        FxTestSupport.writeSnapshot(dialog.getScene().getRoot(),1.0,path);
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
                KeyCode code=n==2 ? KeyCode.F4 : KeyCode.ENTER;
                int location=n==0 ? KeyEvent.KEY_LOCATION_NUMPAD : KeyEvent.KEY_LOCATION_STANDARD;
                boolean ctrl=n==2;
                check(dispatch.dispatchKey(pane.view(),fxKey(javafx.scene.input.KeyEvent.KEY_PRESSED,code,false,ctrl,false),location),
                        "Production transaction key claimed");
                dispatch.dispatchKey(pane.view(),new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_TYPED,"\n","\n",KeyCode.UNDEFINED,false,ctrl,false,false),location);
                dispatch.dispatchKey(pane.view(),fxKey(javafx.scene.input.KeyEvent.KEY_RELEASED,code,false,ctrl,false),location);
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
            dispatch.dispatchKey(pane.view(),fxKey(javafx.scene.input.KeyEvent.KEY_PRESSED,KeyCode.ENTER,false,false,false),KeyEvent.KEY_LOCATION_NUMPAD);
            dispatch.dispatchKey(pane.view(),fxKey(javafx.scene.input.KeyEvent.KEY_RELEASED,KeyCode.ENTER,false,false,false),KeyEvent.KEY_LOCATION_NUMPAD);
            check(!pane.recoverableInputError() && bytes.size()==0,"First custom keypad Enter still performs Reset only with zero host bytes");
            dispatch.dispatchKey(pane.view(),fxKey(javafx.scene.input.KeyEvent.KEY_PRESSED,KeyCode.ENTER,false,false,false),KeyEvent.KEY_LOCATION_NUMPAD);
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
            } finally {
                edt(() -> { workspace.close(false); return null; });
                check(closed.await(10,TimeUnit.SECONDS),"Production workspace closed with persistence");
            }
        } finally {
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
        }
        System.out.println("Exact keys/migration/recorder/inline Settings/profile checks passed: "+checks);
        Ui.finishTestRun();
    }
}
