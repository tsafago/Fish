package fish.ui;

import java.io.*;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.tn5250j.Session5250;
import org.tn5250j.keyboard.KeyMnemonic;
import fish.demo.DemoHost;
import fish.demo.DemoScreens;
import fish.model.*;
import fish.update.*;

/** Real JavaFX controls in never-shown stages, private state and injected HTTP/install consent/launcher. */
public final class MaintenanceUiSmoke {
    private static int checks;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static Object field(Object owner,String name) throws Exception { return FxTestSupport.field(owner,name); }
    private static void set(Object owner,String name,Object value) throws Exception { FxTestSupport.setField(owner,name,value); }
    private static void invoke(Object owner,String name) throws Exception { FxTestSupport.invoke(owner,name); }
    private static <T> T edt(Callable<T> work) throws Exception { return FxTestSupport.fx(work); }
    private static void await(Callable<Boolean> condition,String message) throws Exception { FxTestSupport.await(condition,10,message); }
    private static ButtonBase button(Stage stage,String text) { return FxTestSupport.button(stage,text); }
    private static ButtonBase button(Parent parent,String text) { return FxTestSupport.button(parent,text); }

    private static final class Http implements Updates.Transport {
        static final String VERSION=MaintenanceSmoke.futureVersion();
        final byte[] exe="MZ injected test bytes, never executed".getBytes(StandardCharsets.UTF_8);
        final byte[] manifest=(Updates.hash(exe)+"  Fish-"+VERSION+"-setup.exe\r\n").getBytes(StandardCharsets.UTF_8);
        final byte[] json;
        volatile boolean fail,block;
        final AtomicInteger downloads=new AtomicInteger();
        Http() {
            var release=MaintenanceSmoke.release(VERSION,true);
            List<Map<String,Object>> assets=new ArrayList<>();
            for (String name:List.of("Fish-"+VERSION+"-setup.exe","SHA256SUMS.txt","Fish-"+VERSION+"-windows-x64.zip")) {
                byte[] content=name.endsWith(".exe") ? exe : name.endsWith(".txt") ? manifest : new byte[]{1};
                assets.add(Map.of("name",name,"state","uploaded","size",content.length,"digest","sha256:"+Updates.hash(content),
                        "browser_download_url","https://github.com/tsafago/Fish/releases/download/v"+VERSION+"/"+name));
            }
            release.put("assets",assets); json=ConfigSchema.json(List.of(release)).getBytes(StandardCharsets.UTF_8);
        }
        @Override public void fetch(URI url,OutputStream out,long limit,Updates.Cancel cancel,LongConsumer progress) throws IOException {
            cancel.check();
            if (fail) throw new IOException("Synthetic network refusal; no real connection was attempted.");
            if (url.toString().endsWith(".exe")) {
                downloads.incrementAndGet();
                for (int count=0;block && count<1000;count++) {
                    cancel.check();
                    try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); cancel.check(); }
                }
            }
            cancel.check();
            byte[] data=url.equals(Updates.API) ? json : url.toString().endsWith(".txt") ? manifest : exe;
            if (data.length>limit) throw new IOException("Synthetic size bound.");
            out.write(data); progress.accept(data.length);
        }
    }
    private static final class PlatformImpl implements Updates.Platform {
        volatile boolean refuse;
        final AtomicInteger launches=new AtomicInteger();
        @Override public void inspectAndMark(Path file) {}
        @Override public void launch(Path file,String hash,long pid,Path data) throws IOException {
            if (refuse) throw new IOException("Synthetic Windows setup launch failure.");
            launches.incrementAndGet();
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Path directory,staging;
        final StateStore store;
        final Http http=new Http();
        final PlatformImpl platform=new PlatformImpl();
        final CountDownLatch closed=new CountDownLatch(1);
        final AtomicBoolean consent=new AtomicBoolean(true);
        final Workspace workspace;
        Fixture(Path root,String name) throws Exception {
            directory=Files.createDirectory(root.resolve(name)); staging=Files.createDirectory(directory.resolve("staging"));
            store=new StateStore(directory);
            Profile offline=new Profile(UUID.randomUUID(),"Synthetic offline","",23,"","",false,false,
                    "37","Phosphor","None",1,Map.of(),"",null,Profile.Authentication.KERBEROS,"");
            StateStore.State fresh=new StateStore.State(List.of(offline),new Layout.Leaf(UUID.randomUUID(),offline.id()),1000,700);
            var state=new StateStore.State(fresh.profiles(),fresh.layout(),1000,700,AppTheme.Charcoal,fresh.lastProfile(),
                    Map.of(),false,Appearance.defaults(),StateStore.Tutorial.SKIPPED,false,UpdateSettings.defaults());
            store.save(state);
            workspace=edt(() -> {
                Workspace w=new Workspace(store,state,directory.resolve("absent-credential-helper.ps1"),false,
                        new Updates(http,platform,staging),(owner,message) -> consent.get());
                w.onClosed(closed::countDown);
                check(!w.isShowing(),"Fixture window is never shown");
                check(w.commands().stream().map(c -> c.title).collect(java.util.stream.Collectors.toSet())
                        .equals(Shortcuts.commands().stream().map(Shortcuts.Command::title).collect(java.util.stream.Collectors.toSet())),
                        "Actual menu actions and exported shortcut catalog match");
                return w;
            });
        }
        UpdateController controller() throws Exception { return (UpdateController)field(workspace,"updates"); }
        Stage dialog() throws Exception { return (Stage)field(controller(),"dialog"); }
        void manualReady() throws Exception {
            edt(() -> { controller().manual(); return null; });
            await(() -> dialog()!=null && !button(dialog(),"Update...").isDisabled(),"Manual lookup did not finish");
            edt(() -> { check(!dialog().isShowing(),"Updater remains invisible"); return null; });
        }
        void closeDialog() throws Exception {
            edt(() -> {
                Stage d=dialog();
                if (d!=null) {
                    if (d.getOnCloseRequest()!=null) d.getOnCloseRequest().handle(new javafx.stage.WindowEvent(d,javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
                    d.close();
                }
                return null;
            });
        }
        @Override public void close() throws Exception {
            if (closed.getCount()!=0) edt(() -> { workspace.close(false); return null; });
            if (!closed.await(10,TimeUnit.SECONDS)) throw new AssertionError("Workspace persistence did not close");
        }
    }
    private static void profilesAndNotices(Fixture fixture) throws Exception {
        try (DemoHost host=new DemoHost()) {
            edt(() -> {
                Workspace w=fixture.workspace; Profile first=w.profileList().getFirst(),second=first.duplicate(),third=first.duplicate();
                w.putProfile(second); w.putProfile(third);
                ProfilesDialog editor=new ProfilesDialog(w,second);
                ((TextField)field(editor,"name")).setText("Unapplied name fixture");
                button(editor.stage(),"Up").fire();
                check(w.profileList().getFirst().id().equals(second.id()),"Real Up control persists stable-ID ordering");
                check(((TextField)field(editor,"name")).getText().equals("Unapplied name fixture")
                        && w.profileList().getFirst().name().equals(second.name()),"Reordering preserves draft fields without applying them");
                ListView<?> list=(ListView<?>)field(editor,"list");
                list.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.DOWN,false,true,false,false));
                check(w.profileList().get(1).id().equals(second.id()),"Scoped Ctrl+Down handler moves selected profile");
                check(!button(editor.stage(),"Delete").isDisabled(),"Delete is wired to saved profiles");
                editor.dispose();
                w.commands().stream().filter(c -> c.title.equals("Add pane")).findFirst().orElseThrow().execute();
                w.executeCommand("Add pane");
                w.deleteProfile(first.id());
                check(w.sessionPanes().size()==2,"Deleting current profile keeps both panes");
                for (SessionPane pane:w.sessionPanes()) {
                    check(pane.profile().id().equals(second.id()),"Live/offline profile reference remapped");
                    ComboBox<?> presets=(ComboBox<?>)field(pane,"presets");
                    check(((Profile)presets.getItems().getFirst()).id().equals(second.id()) && presets.getItems().size()==2,
                            "Selectors have persistent order and no deleted profile");
                }
                for (Profile profile:w.profileList()) w.putProfile(new Profile(profile.id(),profile.name(),"127.0.0.1",host.port(),"","",false,false,
                        "37","Phosphor","None",1,Map.of(),""));
                w.selectProfile(w.sessionPanes().get(0),w.profileList().get(0));
                w.selectProfile(w.sessionPanes().get(1),w.profileList().get(1));
                w.activate(w.sessionPanes().getFirst()); w.connectAll(); return null;
            });
            await(() -> fixture.workspace.sessionPanes().stream().allMatch(p -> p.connected() && p.screen().getScreenFields().getSize()>0),
                    "Synthetic loopback sessions did not connect");
            edt(() -> {
                Workspace w=fixture.workspace;
                SessionPane pane=w.active();
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                Session5250 recording=new Session5250("Notice fixture","37",bytes,pane::problem);
                set(pane,"core",recording);
                ByteArrayOutputStream screen=new ByteArrayOutputStream();
                DemoScreens.command(screen,4,0x40,4,0x11,0,8);
                DemoScreens.at(screen,1,1,0x22,"FIXTURE"); DemoScreens.field(screen,2,1,8,0x43,0x24);
                DemoScreens.command(screen,4,0x42,0,0);
                recording.accept(DemoScreens.gds(3,screen.toByteArray()));
                Appearance appearance=w.appearanceSettings();
                w.appearanceSettings(appearance.withLayout(appearance.outline(),
                        new Appearance.PaneInfo(true,false,true,true,true,false,true,true,true,true)));
                pane.type('X'); bytes.reset(); pane.problem("Numeric input needs Reset.");
                TransientNotice notice=(TransientNotice)field(pane,"noticeOverlay");
                check(pane.recoverableInputError() && notice.isVisible(),"Actual numeric error has bordered notice");
                button(notice,"Dismiss notice").fire();
                check(!notice.isVisible() && pane.recoverableInputError() && bytes.size()==0,"Notice X never resets, unlocks or submits host input");
                check(((Button)field(pane,"reset")).isVisible() && !((String)field(pane,"lastProblem")).isEmpty(),"Recovery and last-notice inspection remain available");
                pane.send(KeyMnemonic.RESET);
                check(!pane.recoverableInputError() && bytes.size()==0,"Explicit Reset alone recovers the actual input error");
                SessionPane other=w.sessionPanes().get(1); UUID removed=pane.profile().id();
                w.deleteProfile(removed);
                check(!pane.connected() && other.connected(),"Deleting a connected profile ends only its own socket");
                check(pane.profile().id().equals(other.profile().id()),"Deleted connected profile falls back without dropping panes");
                return null;
            });
        }
    }
    private static void updateUi(Fixture fixture) throws Exception {
        edt(() -> { invoke(fixture.controller(),"checkStartup"); return null; });
        await(() -> ((TransientNotice)field(fixture.controller(),"notice")).isVisible(),"Startup notice missing");
        edt(() -> {
            button((Parent)field(fixture.controller(),"notice"),"Hide version").fire();
            check(fixture.workspace.updateSettings().ignoredVersion().equals(Http.VERSION),"Hide records the exact offered version");
            invoke(fixture.controller(),"checkStartup"); return null;
        });
        Thread.sleep(100);
        edt(() -> { check(!((TransientNotice)field(fixture.controller(),"notice")).isVisible(),"Exact ignored startup version stays hidden"); return null; });
        fixture.manualReady();
        fixture.consent.set(false);
        edt(() -> { button(fixture.dialog(),"Update...").fire(); return null; });
        check(fixture.http.downloads.get()==0 && fixture.platform.launches.get()==0,"Declined consent downloads and launches nothing");
        fixture.http.fail=true;
        edt(() -> { button(fixture.dialog(),"Check for updates").fire(); return null; });
        await(() -> !button(fixture.dialog(),"Check for updates").isDisabled() && button(fixture.dialog(),"Update...").isDisabled(),"Manual error/retry did not settle");
        fixture.http.fail=false;
        edt(() -> { button(fixture.dialog(),"Check for updates").fire(); return null; });
        await(() -> !button(fixture.dialog(),"Update...").isDisabled(),"Manual retry failed");
        fixture.consent.set(true); fixture.http.block=true;
        edt(() -> { button(fixture.dialog(),"Update...").fire(); return null; });
        await(() -> fixture.http.downloads.get()>0,"Injected download did not start");
        fixture.closeDialog();
        await(() -> { try (var files=Files.list(fixture.staging)) { return files.findAny().isEmpty(); } },"Cancel leaked staging");
        check(fixture.platform.launches.get()==0 && !fixture.workspace.closing(),"Cancel preserves the running app and executes nothing");
        fixture.http.block=false; fixture.platform.refuse=true;
        fixture.manualReady();
        edt(() -> { button(fixture.dialog(),"Update...").fire(); return null; });
        await(() -> !button(fixture.dialog(),"Check for updates").isDisabled() && !fixture.workspace.closing(),"Launch failure did not restore retry");
        check(fixture.platform.launches.get()==0,"Launch failure is not reported successful");
        fixture.closeDialog();
        fixture.platform.refuse=false;
        fixture.manualReady();
        edt(() -> { button(fixture.dialog(),"Update...").fire(); return null; });
        check(fixture.closed.await(10,TimeUnit.SECONDS),"Verified handoff did not save/close cleanly");
        check(fixture.platform.launches.get()==1,"Exactly one injected installer handoff, never a real process");
        try (StateStore reopened=new StateStore(fixture.directory)) {
            check(reopened.load().updates().ignoredVersion().equals(Http.VERSION),"Handoff saves update choices before closing");
        }
    }
    private static void reset(Fixture fixture) throws Exception {
        edt(() -> {
            Method method=Workspace.class.getDeclaredMethod("maintenance",Workspace.Maintenance.class,Consumer.class);
            method.setAccessible(true);
            boolean started=(boolean)method.invoke(fixture.workspace,(Workspace.Maintenance)fixture.store::factoryReset,
                    (Consumer<String>)message -> { throw new AssertionError(message); });
            check(started,"Factory reset uses production maintenance lifecycle"); return null;
        });
        check(fixture.closed.await(10,TimeUnit.SECONDS),"Factory reset did not close");
        try (StateStore reopened=new StateStore(fixture.directory)) {
            var state=reopened.load();
            check(state.tutorial()==StateStore.Tutorial.NEW && state.connectOnStartup()
                    && state.profiles().getFirst().authentication()==Profile.Authentication.KERBEROS,"Factory startup follows the new explicit connection default");
        }
    }
    public static void main(String[] args) throws Exception {
        Ui.install();
        Path root=Files.createTempDirectory("Fish-maintenance-ui-fixtures-");
        try {
            try (Fixture fixture=new Fixture(root,"profiles")) { profilesAndNotices(fixture); }
            try (Fixture fixture=new Fixture(root,"updates")) { updateUi(fixture); }
            try (Fixture fixture=new Fixture(root,"reset")) { reset(fixture); }
            edt(() -> { for (Window window:Window.getWindows()) check(!window.isShowing(),"No fixture ever opens a desktop window"); return null; });
        } catch (Throwable t) {
            t.printStackTrace();
            if (!Boolean.getBoolean("fish.embeddedJvm")) System.exit(1);
            throw t;
        } finally {
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
        }
        System.out.println("Never-shown production profile/update/reset/notice UI checks passed: "+checks);
        Ui.finishTestRun();
    }
}
