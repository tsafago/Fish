package fish.ui;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javafx.animation.Animation;
import javafx.animation.Timeline;
import javafx.scene.control.*;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import org.tn5250j.keyboard.KeyMnemonic;
import fish.Configuration;
import fish.demo.DemoHost;
import fish.model.*;
import fish.update.*;

/** Never-shown real stages/controllers with private configuration and independent loopback sessions. */
public final class WindowRevisionSmoke {
    private static int checks;
    private static Path evidence;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static Object field(Object owner,String name) throws Exception { return FxTestSupport.field(owner,name); }
    private static void set(Object owner,String name,Object value) throws Exception { FxTestSupport.setField(owner,name,value); }
    private static <T> T edt(Callable<T> action) throws Exception { return FxTestSupport.fx(action); }
    private static void await(Callable<Boolean> condition,String message) throws Exception { FxTestSupport.await(condition,10,message); }

    private static final class Fixture implements AutoCloseable {
        final Path directory;
        final StateStore store;
        final ApplicationWindows app;
        final CountDownLatch closed=new CountDownLatch(1);
        Fixture(Path root,String name,int port) throws Exception {
            directory=Files.createDirectory(root.resolve(name)); store=new StateStore(directory);
            Profile profile=new Profile(UUID.randomUUID(),"Synthetic workstation","127.0.0.1",port,"","",false,false,
                    "37","Phosphor","None",1,Map.of(),"");
            StateStore.State state=new StateStore.State(List.of(profile),new Layout.Leaf(UUID.randomUUID(),profile.id()),1060,740,
                    AppTheme.Charcoal,profile.id(),Map.of(),true,Appearance.defaults(),StateStore.Tutorial.SKIPPED,false,
                    new UpdateSettings(false,UpdateSettings.Channel.PREVIEW_PATCHES,""));
            store.save(state);
            app=edt(() -> {
                ApplicationWindows result=new ApplicationWindows(store,directory.resolve("absent-credentials.ps1"),false,false,
                        (s,initial,helper,demo) -> new Workspace(s,initial,helper,demo,
                                new Updates((uri,out,limit,cancel,progress) -> { throw new AssertionError("Unexpected update network request"); },
                                new Updates.Platform() {
                                    @Override public void inspectAndMark(Path file) { throw new AssertionError("No installer"); }
                                    @Override public void launch(Path file,String hash,long pid,Path data) { throw new AssertionError("No installer"); }
                                },directory.resolve("staging")),(owner,message) -> false,false));
                result.onClosed(closed::countDown); result.restore(state); return result;
            });
        }
        Workspace first() { return app.windows().getFirst(); }
        @Override public void close() throws Exception {
            if (closed.getCount()>0) edt(() -> {
                UsabilityIterationSmoke.settle(() -> !app.saving(),"Pending settings did not settle before teardown");
                for (Workspace w:app.windows()) {
                    Preferences p=w.tutorialPreferences();
                    if (p!=null) {
                        ((Button)field(p,"reloadButton")).fire();
                        p.forceDispose();
                    }
                }
                app.quit(); return null;
            });
            check(closed.await(10,TimeUnit.SECONDS),"All-window lifecycle releases the store and worker");
        }
    }

    private static final class SyntheticView extends TerminalView {
        boolean showing,focused=true;
        final List<Rectangle> dirty=new ArrayList<>();
        SyntheticView(SessionPane pane) { super(pane); showing=true; }
        @Override protected boolean isTerminalShowing() { return showing && !isDisabled(); }
        @Override protected boolean hasTerminalFocus() { return focused; }
        @Override protected void recordDirtyRect(Rectangle rect) {
            if (dirty!=null) dirty.add(new Rectangle(rect));
        }
    }

    private static BufferedImage paint(TerminalView target,double scale) {
        return target.paintToImage(scale);
    }

    private static int pixels(BufferedImage image,int rgb) {
        int count=0; for (int y=0;y<image.getHeight();y++) for (int x=0;x<image.getWidth();x++) if ((image.getRGB(x,y)&0xffffff)==rgb) count++;
        return count;
    }

    private static void migration(Path root) throws Exception {
        try (StateStore store=new StateStore(Files.createDirectory(root.resolve("migration")))) {
            Properties legacy=StateStore.properties(StateStore.freshState());
            legacy.remove("appearance.cursorStyle");
            legacy.setProperty("version","13"); legacy.remove("windows"); legacy.remove("info.footerDevice");
            legacy.remove("addProfile"); legacy.remove("savedLayouts"); legacy.remove("selectedLayout");
            for (String flag:List.of("header","footer","profile","connection","host","status","dimensions","cursor","insert")) legacy.setProperty("info."+flag,"true");
            legacy.setProperty("info.device","false");
            check(store.decode(legacy).appearance().info().equals(Appearance.PaneInfo.defaults()),"Exact previous built-in defaults deliberately migrate");
            legacy.setProperty("info.header","false");
            Appearance.PaneInfo hidden=store.decode(legacy).appearance().info();
            check(!hidden.header() && hidden.connection() && hidden.host() && hidden.insert() && !hidden.footerDevice(),"Hidden/custom choices survive without being reset");
            legacy.setProperty("version","14");
            try { store.decode(legacy); throw new AssertionError("Incomplete new format accepted"); } catch (IOException expected) { checks++; }
            StateStore.State base=StateStore.freshState();
            StateStore.State multi=base.withWindows(List.of(new StateStore.WindowState(new Layout.Leaf(UUID.randomUUID(),base.lastProfile()),900,600,base.lastProfile())));
            store.save(multi); check(store.load().equals(multi),"All windows roundtrip with exact shared profile/settings identities");
            Path export=root.resolve("multi.xml");
            try (OutputStream out=Files.newOutputStream(export)) { StateStore.properties(multi).storeToXML(out,"Synthetic multi-window export"); }
            check(store.decode(StateStore.readProperties(export)).windows().equals(multi.windows()),"XML snapshot preserves secondary windows");
            Properties relabeled=StateStore.properties(multi); relabeled.setProperty("version","13");
            try { store.decode(relabeled); throw new AssertionError("Downgrade relabel accepted"); } catch (IOException expected) { checks++; }
        }
    }

    private static void windows(Fixture fixture,DemoHost host) throws Exception {
        Workspace first=fixture.first();
        Workspace second=edt(() -> {
            for (int n=0;n<4;n++) first.split(true);
            first.executeCommand("New window");
            Workspace next=fixture.app.windows().get(1);
            check(next.stage().getOwner()==null && first.stage().getOwner()==null,"Independent normal top-level stages, not owned dialogs");
            check(next.sessionPanes().size()==1 && first.sessionPanes().size()==4,"New window has a fresh session, not copied sockets");
            for (int n=0;n<4;n++) next.split(true);
            check(Collections.disjoint(first.sessionPanes(),next.sessionPanes()),"Two four-pane windows own distinct controllers");
            check(Collections.disjoint(first.currentLayout().leaves(),next.currentLayout().leaves()),"Independent pane IDs");
            check(first.shortcutLabel("New window").equals(KeyChord.parse("ctrl shift N").label()),"Conventional discoverable New window key");
            first.connectAll(); next.connectAll(); return next;
        });
        await(() -> host.activeConnections()==8 && fixture.app.panes().stream().allMatch(SessionPane::connected),"Eight independent loopback sessions did not connect");
        edt(() -> {
            check(fixture.app.panes().stream().map(SessionPane::screen).distinct().count()==8,"Every pane has its own host presentation space");
            check(first.active().screen().getColumns()==80 && second.active().screen().getColumns()==80,"Window creation preserves host dimensions");
            check(((Label)field(first.active(),"title")).getText().equals("Synthetic workstation"),"Default header contains only the profile name");
            String footer=((Label)field(first.active(),"oia")).getText();
            check(footer.contains("Connected") && footer.contains("AUTODEMO") && footer.contains("24") && footer.contains("80")
                    && !footer.contains("verified") && !footer.contains("IBM-3477"),"Default footer is concise and shows actual host-allocated device");
            first.applyShortcuts(Map.of("New window","ctrl shift W"));
            check(second.shortcutOverrides().equals(first.shortcutOverrides()),"App-global exact shortcut changes propagate immediately");
            first.updateSettings(new UpdateSettings(false,UpdateSettings.Channel.STABLE,""));
            check(second.updateSettings().equals(first.updateSettings()),"Updates share one coherent preference set");

            Preferences editor=new Preferences(first,"Appearance");
            set(first,"preferences",editor);
            check(second.preferencesDialog("General")==editor.dialog(),"Other windows reuse the same Settings editor");
            ((CheckBox)field(editor,"startup")).setSelected(!first.connectOnStartup());
            ((TextField)field(editor,"fontPath")).setText("synthetic-missing-font.ttf");
            second.highlightActive(false);
            Method apply=Preferences.class.getDeclaredMethod("apply",boolean.class); apply.setAccessible(true); apply.invoke(editor,false);
            check(((Label)field(editor,"error")).getText().contains("Changed elsewhere"),"Stale app-global draft is rejected rather than overwriting another window");
            ((Button)field(editor,"reloadButton")).fire();
            check(!(boolean)field(editor,"conflict") && field(editor,"validation").equals("") && !editor.profiles.dirty(),
                    "Reload repairs actual pending draft before never-shown disposal");
            editor.forceDispose();
            set(first,"preferences",null);

            Preferences colors=new Preferences(first,"Appearance"); set(first,"preferences",colors);
            @SuppressWarnings("unchecked") ComboBox<String> pal=(ComboBox<String>)field(colors,"palette");
            pal.setValue("Amber");
            Profile concurrent=second.active().profile();
            second.putProfile(concurrent.withDisplay(concurrent.theme(),Profile.FLUX));
            apply.invoke(colors,false);
            check(!((Label)field(colors,"error")).getText().contains("changed"),"Clean applied palette choice rebases a concurrent effect instead of reporting a stale draft");
            check(first.active().profile().theme().equals("Amber") && first.active().profile().effect().equals(Profile.FLUX),
                    "Concurrent effect and immediately applied palette both remain intact");
            ((Button)field(colors,"reloadButton")).fire();
            colors.forceDispose();
            set(first,"preferences",null);
            first.toggleFlux();

            Profile other=Profile.createKerberos("Other saved profile"); first.putProfile(other);
            check(second.profileList().contains(other),"Profiles are shared live without stale copies");
            second.active().disconnect(); second.selectProfile(second.active(),other);
            first.deleteProfile(other.id());
            check(second.active().profile().id().equals(first.profileList().getFirst().id()),"Deletion remaps every window's selected profile");
            Profile p=first.profileList().getFirst();
            Profile renamed=new Profile(p.id(),"Renamed workstation",p.host(),p.port(),p.device(),p.username(),p.automaticSignOn(),
                    p.rememberPassword(),p.codePage(),p.theme(),p.effect(),p.zoom(),p.keymap(),p.savedLayout(),p.loginSource(),p.authentication(),p.kerberosServer());
            first.putProfile(renamed);
            check(second.active().profile().name().equals("Renamed workstation"),"Display-name updates reach connected peer panes");
            check(!second.currentLayout().equals(first.currentLayout()),"Sharing settings never replaces another window's layout");
            StateStore.State snapshot=fixture.app.snapshot(); Configuration.validate(snapshot);
            check(snapshot.windows().size()==1 && snapshot.windows().getFirst().layout().leaves().size()==4,"One coherent snapshot includes both four-pane windows");
            UsabilityIterationSmoke.settle(() -> !fixture.app.saving(),"Immediate settings must finish their separate durable writes before stage-close admission");
            fixture.app.closeWindow(second);
            check(first.sessionPanes().stream().allMatch(SessionPane::connected),"Closing one stage does not disconnect the other's sessions");
            check(fixture.app.windows().size()==1,"Only the selected window closes");
            return null;
        });
        await(() -> host.activeConnections()==4,"Closed window's sockets were not released independently");
    }

    private static boolean running(Timeline t) { return t.getStatus()==Animation.Status.RUNNING; }

    private static void cursor(Fixture fixture) throws Exception {
        SessionPane pane=fixture.first().active();
        SyntheticView view=edt(() -> {
            pane.view().dispose();
            SyntheticView next=new SyntheticView(pane);
            next.resize(1000,620);
            set(pane,"view",next);
            ((ScrollPane)field(pane,"scroll")).setContent(next);
            next.restartCursor();
            return next;
        });
        Thread.sleep(400);
        edt(() -> {
            check((boolean)field(view,"cursorPhase"),"Cursor starts with a full visible interval");
            pane.send(KeyMnemonic.TAB);
            check((boolean)field(view,"cursorPhase"),"Actual field navigation visibly restarts blink");
            view.dirty.clear(); view.restartCursor();
            check(!view.dirty.isEmpty() && view.dirty.stream().allMatch(r -> r.width<100 && r.height<100),"Restart requests only old/new cursor rectangles");
            return null;
        });
        Thread.sleep(350);
        edt(() -> { check((boolean)field(view,"cursorPhase"),"Interaction resets deadline, not the global host blink phase"); return null; });
        await(() -> !(boolean)field(view,"cursorPhase"),"Cursor did not resume blinking after its complete on interval");
        edt(() -> {
            view.screenChanged();
            check(!(boolean)field(view,"cursorPhase"),"Idle host-output invalidation does not reset cursor blink");
            pane.type('X'); check((boolean)field(view,"cursorPhase"),"Actual accepted typing immediately lights cursor");
            pane.type('\u2603'); check((boolean)field(view,"cursorPhase"),"Rejected input lights cursor without dispatching a transaction");
            pane.send(KeyMnemonic.RESET);
            pane.send(KeyMnemonic.INSERT);
            check(pane.screen().getOIA().isInsertMode(),"Insert toggles actual terminal state");
            for (double scale:new double[]{1,1.5}) {
                BufferedImage heavy=paint(view,scale);
                pane.send(KeyMnemonic.INSERT);
                BufferedImage normal=paint(view,scale);
                int ink=Ui.color(ColorRole.CURSOR).getRGB()&0xffffff;
                check(pixels(heavy,ink)>pixels(normal,ink)*1.5,"Insert uses a filled physical-pixel cell at "+scale);
                if (evidence!=null) { ImageIO.write(heavy,"png",evidence.resolve("cursor-insert-"+scale+".png").toFile()); ImageIO.write(normal,"png",evidence.resolve("cursor-normal-"+scale+".png").toFile()); }
                view.focused=false;
                BufferedImage inactive=paint(view,scale);
                check(pixels(inactive,ink)<pixels(normal,ink),"Inactive cursor is not painted");
                view.focused=true; pane.send(KeyMnemonic.INSERT);
            }
            view.showing=false; view.displayChanged();
            check(!running((Timeline)field(view,"cursorBlink")) && !running((Timeline)field(view,"blink")),"Hidden terminal stops both timers");
            view.showing=true; view.restartCursor(); view.setDisable(true); view.updateAnimation();
            check(!running((Timeline)field(view,"cursorBlink")),"Disabled terminal releases its cursor timer");
            view.setDisable(false); view.restartCursor(); view.dispose();
            check(!running((Timeline)field(view,"cursorBlink")),"Disposed view retains no cursor timer");
            return null;
        });
    }

    private static void maintenance(Fixture fixture) throws Exception {
        edt(() -> {
            check(fixture.app.newWindow(),"Additional independent stage for maintenance");
            check(fixture.app.maintenance(fixture.first(),() -> { throw new IOException("Synthetic setup refusal"); },false,
                    message -> check(message.contains("Synthetic"),"Failure remains explicit")),"All-window update handoff admitted");
            return null;
        });
        await(() -> !fixture.app.closing(),"Failed handoff did not restore application");
        edt(() -> {
            check(fixture.app.windows().stream().allMatch(Workspace::isEnabled),"Failed setup re-enables all independent stages");
            check(fixture.first().sessionPanes().stream().allMatch(SessionPane::connected),"Failed setup preserves all original sessions");
            if (evidence!=null) {
                Workspace w=fixture.first(); FxTestSupport.layout(w);
                for (double scale:new double[]{1,1.5})
                    FxTestSupport.writeSnapshot(w.scene().getRoot(),scale,evidence.resolve("window-"+scale+".png"));
            }
            check(Window.getWindows().stream().noneMatch(Window::isShowing),"No test window has been displayed");
            return null;
        });
    }

    public static void main(String[] args) {
        Path root=null;
        try {
            if (args.length>0) { evidence=Path.of(args[0]); Files.createDirectories(evidence); }
            root=Files.createTempDirectory(Path.of("build"),"windows-074-"); Ui.install(); migration(root);
            try (DemoHost host=new DemoHost(); Fixture fixture=new Fixture(root,"windows",host.port())) {
                windows(fixture,host); cursor(fixture); maintenance(fixture);
                check(host.failures().isEmpty(),"Loopback host reports no protocol/session failures");
            }
            try (Fixture reset=new Fixture(root,"reset",9)) {
                edt(() -> { reset.app.newWindow(); check(reset.app.maintenance(reset.first(),() -> {},true,message -> { throw new AssertionError(message); }),"Factory reset applies to all windows"); return null; });
                check(reset.closed.await(10,TimeUnit.SECONDS),"Reset releases all stages and store");
                try (StateStore fresh=new StateStore(reset.directory)) {
                    StateStore.State state=fresh.load();
                    check(state.windows().isEmpty() && state.profiles().size()==1 && state.tutorial()==StateStore.Tutorial.NEW
                            && !state.profiles().getFirst().name().contains("Synthetic"),"Fresh reset state is not resurrected by a stale peer save");
                }
            }
            Path handedOff;
            try (Fixture update=new Fixture(root,"update",9)) {
                handedOff=update.directory;
                edt(() -> {
                    update.app.newWindow();
                    check(update.app.maintenance(update.first(),() -> {
                        check(StateStore.readProperties(update.store.file()).getProperty("windows").equals("1"),"Successful handoff saves all windows before launching setup");
                    },false,message -> { throw new AssertionError(message); }),"All-window update success admitted");
                    return null;
                });
                check(update.closed.await(10,TimeUnit.SECONDS),"Successful setup handoff closes every stage and releases the process owner");
            }
            StateStore restoredStore=new StateStore(handedOff);
            StateStore.State restoredState=restoredStore.load();
            CountDownLatch restoredClosed=new CountDownLatch(1);
            edt(() -> {
                ApplicationWindows app=new ApplicationWindows(restoredStore,handedOff.resolve("absent-credentials.ps1"),false,false,Workspace::new);
                app.onClosed(restoredClosed::countDown); app.restore(restoredState);
                check(app.windows().size()==2,"Normal owner restore recreates the saved window collection");
                check(app.windows().get(0).active()!=app.windows().get(1).active(),"Restored windows create distinct controllers");
                app.quit(); return app;
            });
            check(restoredClosed.await(10,TimeUnit.SECONDS),"App-wide Quit saves and releases a restored collection");
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            PrintStream quiet=new PrintStream(output);
            Path exported=root.resolve("offline.xml");
            check(Configuration.run(new String[]{"export","--data-dir",handedOff.toString(),"--file",exported.toString()},quiet,quiet)==0,"Offline export works after the final window closes");
            check(Configuration.run(new String[]{"validate","--data-dir",handedOff.toString(),"--file",exported.toString()},quiet,quiet)==0,"Offline configuration validates complete multi-window snapshots");
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
            System.out.println("Independent windows/cursor timing+paint/default migration/maintenance checks passed: "+checks);
            Ui.finishTestRun();
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }
}
