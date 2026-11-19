package fish.ui;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;
import fish.LayoutShortcuts;
import fish.demo.DemoHost;
import fish.model.*;
import fish.update.*;

/** Isolated settings, real Shell links and constrained docking; no shown windows or real hosts. */
public final class UsabilityIterationSmoke {
    private static int checks;
    private static Path evidence;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static Object field(Object owner,String name) throws Exception { return FxTestSupport.field(owner,name); }
    private static <T> T edt(Callable<T> action) throws Exception { return FxTestSupport.fx(action); }
    private static void await(Callable<Boolean> action,String message) throws Exception { FxTestSupport.await(action,12,message); }
    public static void settle(Callable<Boolean> action,String message) throws Exception { FxTestSupport.settle(action,12,message); }
    private static ButtonBase button(Stage stage,String text) { return FxTestSupport.button(stage,text); }
    private static void layout(Workspace w) { FxTestSupport.layout(w); }
    private static void layout(Stage stage) { FxTestSupport.layout(stage); }
    private static void snapshot(Stage stage,String name) throws Exception {
        if (evidence==null) return;
        for (double scale:new double[]{1,1.5})
            FxTestSupport.writeSnapshot(stage.getScene().getRoot(),scale,evidence.resolve(name+"-"+scale+".png"));
    }

    public static final class Fixture implements AutoCloseable {
        public final StateStore store;
        public final ApplicationWindows app;
        public final CountDownLatch closed=new CountDownLatch(1);
        public Fixture(Path directory) throws Exception {
            this(directory,s -> s::save);
        }
        public Fixture(Path directory,java.util.function.Function<StateStore,ApplicationWindows.Writer> writes) throws Exception {
            store=new StateStore(directory);
            Profile profile=new Profile(UUID.randomUUID(),"Synthetic workstation","",23,"","",false,false,"37","Phosphor","None",1,Map.of(),"");
            StateStore.State state=new StateStore.State(List.of(profile),new Layout.Leaf(UUID.randomUUID(),profile.id()),1120,760,
                    AppTheme.Charcoal,profile.id(),Map.of(),true,Appearance.defaults(),StateStore.Tutorial.SKIPPED,false,
                    new UpdateSettings(false,UpdateSettings.Channel.PREVIEW_PATCHES,""));
            store.save(state);
            app=edt(() -> {
                ApplicationWindows result=new ApplicationWindows(store,directory.resolve("absent-credentials.ps1"),false,false,
                        (s,initial,helper,demo) -> new Workspace(s,initial,helper,demo,new Updates(
                                (uri,out,limit,cancel,progress) -> { throw new AssertionError("No external HTTP"); },
                                new Updates.Platform() {
                                    @Override public void inspectAndMark(Path path) { throw new AssertionError("No installer"); }
                                    @Override public void launch(Path path,String hash,long pid,Path data) { throw new AssertionError("No installer"); }
                                }),(owner,message) -> false,false),writes.apply(store));
                result.onClosed(closed::countDown); result.restore(state); layout(result.windows().getFirst()); return result;
            });
        }
        public Workspace first() { return app.windows().getFirst(); }
        @Override public void close() throws Exception {
            await(() -> !app.saving(),"Pending settings did not settle");
            edt(() -> {
                for (Workspace w:app.windows()) {
                    Preferences p=w.tutorialPreferences();
                    if (p!=null) { button(p.dialog(),"Reload saved values").fire(); p.profiles.discard(); p.forceDispose(); }
                }
                app.quit(); return null;
            });
            check(closed.await(12,TimeUnit.SECONDS),"Owner closes and releases configuration lock");
        }
    }

    private static void model(Path root) throws Exception {
        UUID profile=UUID.randomUUID();
        List<Layout.Leaf> slots=new ArrayList<>();
        for (int n=0;n<8;n++) slots.add(new Layout.Leaf(UUID.randomUUID(),profile));
        int refused=0,moved=0;
        for (int n=2;n<=8;n++) {
            Layout old=Layout.bounded(slots.subList(0,n));
            check(Layout.normalize(old).equals(old),"Balanced canonical geometry is preserved at "+n);
            for (Layout.Leaf from:old.leaves()) for (Layout.Leaf to:old.leaves()) for (Layout.Dock edge:Layout.Dock.values()) {
                if (from.equals(to)) { check(old.move(from.pane(),to.pane(),edge).equals(old),"Self drop is a no-op"); continue; }
                try {
                    Layout next=old.move(from.pane(),to.pane(),edge);
                    check(Layout.constrained(next) && new HashSet<>(next.leaves()).equals(new HashSet<>(old.leaves())),"Accepted placement preserves identities and simple shapes");
                    check(Layout.decode(next.encode()).equals(next),"Directional ratios/order/orientation roundtrip"); moved++;
                } catch (IllegalArgumentException expected) { refused++; }
            }
            if (n>3) for (Layout.Leaf from:old.leaves()) for (Layout.Dock edge:Layout.Dock.values()) {
                try { old.move(from.pane(),old.leaves().getFirst().pane(),edge,Layout.Scope.WINDOW); }
                catch (IllegalArgumentException expected) { refused++; }
            }
        }
        check(moved>100 && refused>20,"Real valid and invalid directional placements are exercised");
        Layout vertical=new Layout.Split(false,.37,slots.get(0),slots.get(1));
        check(Layout.normalize(vertical).equals(vertical),"Two stacked panes never normalize back to horizontal");
        try (StateStore store=new StateStore(root.resolve("model"))) {
            Profile p=new Profile(profile,"Synthetic","",23,"","",false,false,"37","Phosphor","None",1,Map.of(),"");
            SavedLayout recipe=new SavedLayout(UUID.randomUUID(),"Stacked",new StateStore.WindowState(vertical,1000,700,profile),
                    Map.of(slots.get(0).pane(),new SavedLayout.Display(true,1),slots.get(1).pane(),new SavedLayout.Display(false,1.4)));
            StateStore.State state=new StateStore.State(List.of(p),vertical,1000,700).withSavedLayouts(List.of(recipe),recipe.id());
            store.save(state); check(store.load().equals(state),"Current format persists oriented windows and recipe fit/zoom exactly");
            Properties legacy=StateStore.properties(new StateStore.State(List.of(p),Layout.bounded(slots.subList(0,2)),1000,700));
            legacy.remove("appearance.cursorStyle");
            legacy.setProperty("version","15");
            check(store.decode(legacy).layout().equals(Layout.bounded(slots.subList(0,2))),"Actual format15 migration keeps geometry and profile IDs");
            Properties oldRecipe=StateStore.properties(state);
            oldRecipe.remove("appearance.cursorStyle");
            oldRecipe.setProperty("layout",Layout.bounded(slots.subList(0,2)).encode());
            oldRecipe.setProperty("savedLayout."+recipe.id()+".layout",Layout.bounded(slots.subList(0,2)).encode());
            oldRecipe.setProperty("savedLayout."+recipe.id()+".version","1"); oldRecipe.setProperty("version","15");
            check(store.decode(oldRecipe).savedLayouts().getFirst().id().equals(recipe.id()),"Legacy recipe1 imports without replacing stable shortcut references");
            Properties relabeled=StateStore.properties(state); relabeled.setProperty("version","15");
            try { store.decode(relabeled); throw new AssertionError("Relabeled directional layout accepted"); } catch (java.io.IOException expected) { checks++; }
        }
        check(SessionPane.direction(new Point(100,5),new Dimension(200,100))==Layout.Dock.TOP
                && SessionPane.direction(new Point(100,95),new Dimension(200,100))==Layout.Dock.BOTTOM
                && SessionPane.direction(new Point(5,50),new Dimension(200,100))==Layout.Dock.LEFT
                && SessionPane.direction(new Point(195,50),new Dimension(200,100))==Layout.Dock.RIGHT
                && SessionPane.direction(new Point(100,50),new Dimension(200,100))==Layout.Dock.CENTER,"Familiar edge targets and center swap");
    }

    private static Preferences settings(Workspace w,String category) {
        w.preferences(category); return w.tutorialPreferences();
    }

    private static void autosave(Fixture f) throws Exception {
        Workspace w=f.first();
        Preferences p=edt(() -> settings(w,"General"));
        edt(() -> {
            check(button(p.dialog(),"Apply")==null && button(p.dialog(),"Done")==null,"Settings has no transaction buttons");
            ((CheckBox)field(p,"startup")).fire();
            Timeline timer=(Timeline)field(p,"writes"); timer.stop();
            check(p.saving() && w.connectOnStartup(),"Discrete edits apply immediately but remain pending until the coalesced write callback");
            timer.playFromStart(); return null;
        });
        await(() -> w.connectOnStartup() && !p.saving() && ((Label)field(p,"error")).getText().equals("Saved"),"Valid checkbox did not save automatically");
        check(f.store.load().connectOnStartup(),"Saved status acknowledges an actual disk write");
        Workspace other=edt(() -> { f.app.newWindow(w); Workspace next=f.app.windows().getLast(); layout(next); return next; });
        await(() -> !other.active().busy(),"Empty-host explicit window settles without credentials");
        edt(() -> {
            check(other.preferencesDialog("Appearance")==p.dialog(),"One Settings owner across windows");
            ((CheckBox)field(p,"tint")).fire(); return null;
        });
        await(() -> !w.highlightActive() && !other.highlightActive() && !p.saving(),"Immediate changes propagate across windows");
        edt(() -> {
            ((TextField)field(p,"fontPath")).setText("Z:\\absent-synthetic-font.ttf");
            ((CheckBox)field(p,"window")).fire(); return null;
        });
        await(() -> !w.appearanceSettings().windowOutline() && !p.saving()
                && ((Label)field(p,"error")).getText().contains("Not applied"),"Invalid font must stay marked while independent valid controls save");
        check(f.store.load().appearance().font().file().isEmpty(),"Invalid font path never replaces saved usable font");
        edt(() -> { p.select("Appearance"); layout(p.dialog()); snapshot(p.dialog(),"settings-invalid"); return null; });
        edt(() -> { button(p.dialog(),"Reload saved values").fire(); p.select("Keyboard Shortcuts"); return null; });
        KeyboardEditor editor=(KeyboardEditor)field(p,"keyboard");
        edt(() -> {
            ((TextField)field(editor,"search")).setText("Zoom in");
            TableView<?> table=(TableView<?>)field(editor,"table"); table.getSelectionModel().select(0);
            ((TextArea)field(editor,"keys")).setText("ctrl shift F9\nctrl shift F10"); return null;
        });
        await(() -> !p.saving() && w.shortcutOverrides().containsKey("Zoom in"),"Complete bindings did not autosave");
        Map<String,String> good=w.shortcutOverrides();
        edt(() -> { ((TextArea)field(editor,"keys")).setText("ctrl NOT_A_KEY"); return null; });
        await(() -> !p.saving() && ((Label)field(p,"error")).getText().contains("Not applied"),"Partial key entry must be visibly invalid");
        check(w.shortcutOverrides().equals(good) && f.store.load().shortcuts().equals(good),"Invalid typing does not unbind saved keys or claim success");
        edt(() -> { other.highlightActive(true); return null; });
        check(edt(() -> ((Label)field(p,"error")).getText().contains("Changed elsewhere")),"Stale edit buffer cannot overwrite another window");
        edt(() -> { button(p.dialog(),"Reload saved values").fire(); return null; });
        check(edt(() -> ((CheckBox)field(p,"tint")).isSelected()),"Reload recovers the actual shared state without hidden conflict resolution");
        await(() -> !p.saving(),"Reload settles");
        byte[] disk=Files.readAllBytes(f.store.file());
        Files.writeString(f.store.file(),"\n",StandardOpenOption.APPEND);
        edt(() -> { ((CheckBox)field(p,"checkUpdates")).fire(); return null; });
        await(() -> !p.saving() && ((Label)field(p,"error")).getText().startsWith("Not saved:"),"Failed persistence must stay visible with recovery");
        check(Files.size(f.store.file())==disk.length+1 && edt(() -> button(p.dialog(),"Retry save").isVisible()),"Autosave never overwrites external state or loses retry");
        edt(() -> { p.select("General"); layout(p.dialog()); snapshot(p.dialog(),"settings-save-failure"); return null; });
        Files.write(f.store.file(),disk);
        edt(() -> { button(p.dialog(),"Retry save").fire(); return null; });
        await(() -> !p.saving() && ((Label)field(p,"error")).getText().equals("Saved"),"Retry did not persist retained valid settings");
        edt(() -> { p.select("Profiles"); ((TextField)field(p.profiles,"name")).setText("Renamed synthetic"); return null; });
        await(() -> !p.profiles.saving() && w.profileList().getFirst().name().equals("Renamed synthetic"),"Existing safe profile edits do not autosave");
        check(f.store.load().profiles().getFirst().name().equals("Renamed synthetic"),"Profile Saved status persists the actual edited profile");
        byte[] profileDisk=Files.readAllBytes(f.store.file());
        Files.writeString(f.store.file(),"\n",StandardOpenOption.APPEND);
        edt(() -> { ((TextField)field(p.profiles,"name")).setText("Retained after failed profile save"); return null; });
        await(() -> !p.profiles.saving() && p.profiles.failedSave(),"Failed profile persistence must remain retryable without credential work");
        check(Files.size(f.store.file())==profileDisk.length+1,"Profile autosave respects external-write protection");
        Files.write(f.store.file(),profileDisk);
        edt(() -> { ((Button)field(p.profiles,"save")).fire(); return null; });
        await(() -> !p.profiles.saving() && !p.profiles.failedSave(),"Profile retry did not persist retained state");
        check(f.store.load().profiles().getFirst().name().equals("Retained after failed profile save"),"Disk-only Retry saves the already applied profile without repeating password operations");
        edt(() -> { p.profiles.deviceField().setText("not a device"); return null; });
        await(() -> !p.profiles.saving() && p.profiles.error().contains("Device"),"Invalid profile field stays actionable");
        check(w.profileList().getFirst().device().isEmpty(),"Invalid profile typing preserves last valid connection state");
        edt(() -> {
            p.profiles.deviceField().clear();
            ((PasswordField)field(p.profiles,"password")).setText("synthetic-no-credential-operation");
            return null;
        });
        await(() -> !p.profiles.saving(),"Profile buffer settles without invoking missing credential helper");
        check(edt(() -> p.profiles.dirty()),"Password entry remains an explicit unsaved operation");
        edt(() -> { p.profiles.passwordField().clear(); return null; });
        await(() -> !p.profiles.saving(),"Clean profile settles");
        edt(() -> { button(p.dialog(),"New").fire(); return null; });
        await(() -> !p.profiles.saving(),"New profile autosaves without connecting");
        check(f.store.load().profiles().size()==2 && !p.profiles.dirty(),"New creates one durable profile");
        edt(() -> {
            var reload=ProfilesEditor.class.getDeclaredMethod("refreshList",Profile.class); reload.setAccessible(true);
            reload.invoke(p.profiles,w.profileList().getFirst()); return null;
        });
        edt(() -> {
            layout(p.dialog()); snapshot(p.dialog(),"settings-profiles");
            p.select("Keyboard Shortcuts"); p.dialog().setWidth(760); p.dialog().setHeight(560); layout(p.dialog()); snapshot(p.dialog(),"settings-keyboard-minimum");
            p.closeSettings(); return null;
        });
        await(p::ended,"Closing Settings keeps saved changes and releases its timers");
    }

    private static void docking(Fixture f,DemoHost host) throws Exception {
        Workspace w=f.first();
        edt(() -> {
            Profile old=w.profileList().getFirst();
            Profile loopback=new Profile(old.id(),old.name(),"127.0.0.1",host.port(),"","",false,false,"37","Phosphor","None",1,Map.of(),"");
            w.putProfile(loopback); w.selectProfile(w.active(),loopback); w.connectAll();
            w.split(true); layout(w); return null;
        });
        await(() -> w.sessionPanes().size()==2 && w.sessionPanes().stream().allMatch(p -> p.connected() && p.screen().getScreenFields().getSize()>0),"Two loopback sockets did not connect");
        List<SessionPane> original=edt(w::sessionPanes);
        Object firstScreen=edt(() -> original.getFirst().screen());
        int connections=host.activeConnections();
        edt(() -> {
            UUID source=original.getFirst().id(),target=original.getLast().id();
            Workspace.DropPlan plan=w.planDock(source,target,Layout.Dock.TOP);
            check(plan!=null && plan.layout() instanceof Layout.Split split && !split.horizontal(),"Top drop produces stacked panes");
            check(w.previewDock(source,target,Layout.Dock.TOP),"Live preview accepts the exact candidate");
            layout(w); snapshot(w.stage(),"stacked-drop-preview");
            w.clearPreviews(); check(w.currentLayout() instanceof Layout.Split split && split.horizontal(),"Cancel clears preview without changing layout");
            w.dock(source,target,Layout.Dock.TOP); layout(w);
            check(w.currentLayout() instanceof Layout.Split split && !split.horizontal(),"Committed top drop stays vertical");
            Region content=(Region)field(w,"content");
            Map<UUID,Rectangle> actual=Workspace.project(w.currentLayout(),new Rectangle(0,0,(int)Math.round(content.getWidth()),(int)Math.round(content.getHeight())));
            check(actual.equals(plan.bounds()),"Preview geometry equals actual post-drop geometry, not an unrelated half-tile");
            for (SessionPane pane:w.sessionPanes())
                check(FxTestSupport.boundsIn(pane,content).equals(plan.bounds().get(pane.id())),
                        "Preview equals actual JavaFX pane bounds, including divider pixels");
            check(original.getFirst().screen()==firstScreen && original.stream().allMatch(w.sessionPanes()::contains),"Docking retains controllers and presentation-space state");
            check(!w.previewDock(source,source,Layout.Dock.BOTTOM),"Self-drop has no valid-looking preview");
            w.undoArrangement(); layout(w); check(w.currentLayout() instanceof Layout.Split split && split.horizontal(),"Keyboard undo restores preceding geometry");
            w.dock(source,target,Layout.Dock.BOTTOM); layout(w);
            SavedLayout recipe=w.captureShortcut("Stacked loopback",false);
            check(recipe.window().layout().equals(w.currentLayout()),"Named recipe captures directional orientation and ratios");
            for (int n=2;n<8;n++) { w.split(true); layout(w); }
            check(w.sessionPanes().size()==8,"Constrained add reaches eight without an impossible grid");
            w.split(true); check(w.sessionPanes().size()==8,"Maximum never creates a ninth controller");
            Layout before=w.currentLayout();
            w.dock(source,UUID.randomUUID(),Layout.Dock.TOP);
            check(w.currentLayout().equals(before),"Foreign/outside reference cannot move a pane");
            for (SessionPane pane:new ArrayList<>(w.sessionPanes())) if (w.sessionPanes().size()>1) { w.closePane(pane); layout(w); check(Layout.constrained(w.currentLayout()),"Remove preserves a valid existing orientation"); }
            check(w.sessionPanes().size()==1,"Removal keeps the last pane");
            w.clearPreviews(); return null;
        });
        check(connections==2,"Initial independent loopback connection count");
    }

    private static void shortcut(Fixture f,Path root) throws Exception {
        Workspace w=f.first();
        edt(() -> {
            w.executeCommand("Tutorial: Desktop shortcuts");
            TutorialDialog guide=(TutorialDialog)field(w,"tutorial");
            button(guide.stage(),"Skip step").fire(); button(guide.stage(),"Skip step").fire();
            w.preferences("General");
            var manager=w.tutorialPreferences();
            ((TextField)manager.tutorialTarget("shortcut-name")).setText("Direct synthetic shortcut");
            int before=w.savedLayouts().size();
            Button create=(Button)manager.tutorialTarget("shortcut-create");
            create.fire(); settle(() -> !w.exportingShortcut(),"Shortcut export did not finish");
            Path file=LayoutShortcuts.desktop().resolve("Fish - Direct synthetic shortcut.lnk");
            check(w.savedLayouts().size()==before+1 && (int)field(guide,"step")==5 && Files.isRegularFile(file),"Direct creation advances only after native export");
            String[] properties=LayoutShortcuts.inspect(file);
            SavedLayout last=w.savedLayouts().getLast();
            check(properties[1].equals(LayoutShortcuts.arguments(last.id(),w.dataDirectory())),"Direct flow exports exact stable reference without hosts/secrets");
            layout(manager.dialog()); snapshot(manager.dialog(),"shortcut-current");
            layout(guide.stage()); snapshot(guide.stage(),"shortcut-guide");
            if (evidence!=null) {
                w.applyTheme(AppTheme.Paper); layout(manager.dialog()); snapshot(manager.dialog(),"shortcut-settings-paper");
                w.setSize(520,360); layout(w); snapshot(w.stage(),"window-minimum-paper");
                w.applyTheme(AppTheme.Charcoal); layout(w);
            }
            button(guide.stage(),"Back").fire(); button(guide.stage(),"Skip tutorial").fire();
            manager.closeSettings(); return null;
        });
        await(() -> !f.app.saving(),"Shortcut state settles");
    }

    public static void main(String[] args) throws Exception {
        Ui.install();
        String path=System.getProperty("fish.test.evidence","");
        if (!path.isBlank()) { evidence=Path.of(path); Files.createDirectories(evidence); }
        Path root=Files.createTempDirectory(Path.of("build"),"usability-local-").toAbsolutePath();
        try (ShortcutFixture ignored=new ShortcutFixture(root)) {
            model(root);
            try (Fixture fixture=new Fixture(root.resolve("workspace")); DemoHost host=new DemoHost()) {
                autosave(fixture); docking(fixture,host); shortcut(fixture,root);
                edt(() -> { check(Window.getWindows().stream().noneMatch(Window::isShowing),"No production window was ever shown"); return null; });
            }
        } finally {
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
        }
        System.out.println("PASS immediate settings / direct shortcuts / eight-pane directional docking: "+checks);
        Ui.finishTestRun();
    }
}
