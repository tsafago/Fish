package pane5250.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;
import pane5250.LayoutShortcuts;
import pane5250.demo.DemoHost;
import pane5250.model.*;
import pane5250.update.*;

/** Isolated settings, real Shell links and constrained docking; no shown windows or real hosts. */
public final class UsabilityIterationSmoke {
    private static int checks;
    private static Path evidence;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> result=new FutureTask<>(action); SwingUtilities.invokeAndWait(result); return result.get();
    }
    private static void await(Callable<Boolean> action,String message) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime()<end) { if (edt(action)) return; Thread.sleep(20); }
        throw new AssertionError(message);
    }
    static void settle(Callable<Boolean> action,String message) throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) { await(action,message); return; }
        SecondaryLoop loop=Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        Exception[] failure={null}; boolean[] ready={false};
        javax.swing.Timer timer=new javax.swing.Timer(15,e -> {
            try { ready[0]=action.call(); } catch (Exception problem) { failure[0]=problem; }
            if (ready[0] || failure[0]!=null || System.nanoTime()>end) { ((javax.swing.Timer)e.getSource()).stop(); loop.exit(); }
        });
        timer.start(); loop.enter(); timer.stop();
        if (failure[0]!=null) throw failure[0];
        if (!ready[0]) throw new AssertionError(message);
    }
    private static JButton button(Container owner,String text) {
        for (Component c:owner.getComponents()) {
            if (c instanceof JButton b && text.equals(b.getText())) return b;
            if (c instanceof Container child) { JButton found=button(child,text); if (found!=null) return found; }
        }
        return null;
    }
    private static void layout(Container owner) {
        owner.doLayout(); for (Component c:owner.getComponents()) if (c instanceof Container child) layout(child);
    }
    private static void snapshot(Component owner,String name) throws Exception {
        if (evidence==null) return;
        for (double scale:new double[]{1,1.5}) {
            BufferedImage image=new BufferedImage((int)(owner.getWidth()*scale),(int)(owner.getHeight()*scale),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics(); g.scale(scale,scale); owner.printAll(g); g.dispose();
            ImageIO.write(image,"png",evidence.resolve(name+"-"+scale+".png").toFile());
        }
    }
    static final class Fixture implements AutoCloseable {
        final StateStore store;
        final ApplicationWindows app;
        final CountDownLatch closed=new CountDownLatch(1);
        Fixture(Path directory) throws Exception {
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
                                }),(owner,message) -> false,false));
                result.onClosed(closed::countDown); result.restore(state); layout(result.windows().getFirst()); return result;
            });
        }
        Workspace first() { return app.windows().getFirst(); }
        @Override public void close() throws Exception {
            await(() -> !app.saving(),"Pending settings did not settle");
            edt(() -> {
                for (Workspace w:app.windows()) {
                    Preferences p=w.tutorialPreferences();
                    if (p!=null) { button(p.dialog(),"Reload saved values").doClick(0); p.profiles.discard(); p.dialog().dispose(); }
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
            ((JCheckBox)field(p,"startup")).doClick(0);
            javax.swing.Timer timer=(javax.swing.Timer)field(p,"edits"); timer.stop();
            check(p.saving(),"Queued edits remain pending when a one-shot Swing timer stops before its callback");
            timer.restart(); return null;
        });
        await(() -> w.connectOnStartup() && !p.saving() && ((JLabel)field(p,"error")).getText().equals("Saved"),"Valid checkbox did not save automatically");
        check(f.store.load().connectOnStartup(),"Saved status acknowledges an actual disk write");
        Workspace other=edt(() -> { f.app.newWindow(w); Workspace next=f.app.windows().getLast(); layout(next); return next; });
        await(() -> !other.active().busy(),"Empty-host explicit window settles without credentials");
        edt(() -> {
            check(other.preferencesDialog("Appearance")==p.dialog(),"One Settings owner across windows");
            ((JCheckBox)field(p,"tint")).doClick(0); return null;
        });
        await(() -> !w.highlightActive() && !other.highlightActive() && !p.saving(),"Immediate changes propagate across windows");
        edt(() -> {
            ((JTextField)field(p,"fontPath")).setText("Z:\\absent-synthetic-font.ttf");
            ((JCheckBox)field(p,"window")).doClick(0); return null;
        });
        await(() -> !w.appearanceSettings().windowOutline() && !p.saving()
                && ((JLabel)field(p,"error")).getText().contains("Not applied"),"Invalid font must stay marked while independent valid controls save");
        check(f.store.load().appearance().font().file().isEmpty(),"Invalid font path never replaces saved usable font");
        edt(() -> { p.select("Appearance"); layout(p.dialog()); snapshot(p.dialog().getContentPane(),"settings-invalid"); return null; });
        edt(() -> { button(p.dialog(),"Reload saved values").doClick(0); p.select("Keyboard Shortcuts"); return null; });
        KeyboardEditor editor=(KeyboardEditor)field(p,"keyboard");
        edt(() -> {
            ((JTextField)field(editor,"search")).setText("Zoom in");
            JTable table=(JTable)field(editor,"table"); table.setRowSelectionInterval(0,0);
            ((JTextArea)field(editor,"keys")).setText("ctrl shift F9\nctrl shift F10"); return null;
        });
        await(() -> !p.saving() && w.shortcutOverrides().containsKey("Zoom in"),"Complete bindings did not autosave");
        Map<String,String> good=w.shortcutOverrides();
        edt(() -> { ((JTextArea)field(editor,"keys")).setText("ctrl NOT_A_KEY"); return null; });
        await(() -> !p.saving() && ((JLabel)field(p,"error")).getText().contains("Not applied"),"Partial key entry must be visibly invalid");
        check(w.shortcutOverrides().equals(good) && f.store.load().shortcuts().equals(good),"Invalid typing does not unbind saved keys or claim success");
        edt(() -> { other.highlightActive(true); return null; });
        check(edt(() -> ((JLabel)field(p,"error")).getText().contains("Changed elsewhere")),"Stale edit buffer cannot overwrite another window");
        edt(() -> { button(p.dialog(),"Reload saved values").doClick(0); return null; });
        check(edt(() -> ((JCheckBox)field(p,"tint")).isSelected()),"Reload recovers the actual shared state without hidden conflict resolution");
        await(() -> !p.saving(),"Reload settles");
        // A valid external rewrite must trip stale-file protection; restore exact bytes before Retry.
        byte[] disk=Files.readAllBytes(f.store.file());
        Files.writeString(f.store.file(),"\n",StandardOpenOption.APPEND);
        edt(() -> { ((JCheckBox)field(p,"checkUpdates")).doClick(0); return null; });
        await(() -> !p.saving() && ((JLabel)field(p,"error")).getText().startsWith("Not saved:"),"Failed persistence must stay visible with recovery");
        check(Files.size(f.store.file())==disk.length+1 && edt(() -> button(p.dialog(),"Retry save").isVisible()),"Autosave never overwrites external state or loses retry");
        edt(() -> { p.select("General"); layout(p.dialog()); snapshot(p.dialog().getContentPane(),"settings-save-failure"); return null; });
        Files.write(f.store.file(),disk);
        edt(() -> { button(p.dialog(),"Retry save").doClick(0); return null; });
        await(() -> !p.saving() && ((JLabel)field(p,"error")).getText().equals("Saved"),"Retry did not persist retained valid settings");
        edt(() -> { p.select("Profiles"); ((JTextField)field(p.profiles,"name")).setText("Renamed synthetic"); return null; });
        await(() -> !p.profiles.saving() && w.profileList().getFirst().name().equals("Renamed synthetic"),"Existing safe profile edits do not autosave");
        check(f.store.load().profiles().getFirst().name().equals("Renamed synthetic"),"Profile Saved status persists the actual edited profile");
        byte[] profileDisk=Files.readAllBytes(f.store.file());
        Files.writeString(f.store.file(),"\n",StandardOpenOption.APPEND);
        edt(() -> { ((JTextField)field(p.profiles,"name")).setText("Retained after failed profile save"); return null; });
        await(() -> !p.profiles.saving() && p.profiles.failedSave(),"Failed profile persistence must remain retryable without credential work");
        check(Files.size(f.store.file())==profileDisk.length+1,"Profile autosave respects external-write protection");
        Files.write(f.store.file(),profileDisk);
        edt(() -> { ((JButton)field(p.profiles,"save")).doClick(0); return null; });
        await(() -> !p.profiles.saving() && !p.profiles.failedSave(),"Profile retry did not persist retained state");
        check(f.store.load().profiles().getFirst().name().equals("Retained after failed profile save"),"Disk-only Retry saves the already applied profile without repeating password operations");
        edt(() -> { ((JTextField)field(p.profiles,"port")).setText("not-a-port"); return null; });
        await(() -> !p.profiles.saving() && p.profiles.error().contains("Port"),"Invalid profile field stays actionable");
        check(w.profileList().getFirst().port()==23,"Invalid profile typing preserves last valid connection state");
        edt(() -> {
            ((JTextField)field(p.profiles,"port")).setText("23");
            ((JPasswordField)field(p.profiles,"password")).setText("synthetic-no-credential-operation");
            return null;
        });
        await(() -> !p.profiles.saving(),"Profile buffer settles without invoking missing credential helper");
        check(edt(() -> p.profiles.dirty()),"Password entry remains an explicit unsaved operation");
        edt(() -> { p.profiles.discard(); ((JTextField)field(p.profiles,"port")).setText("23"); return null; });
        await(() -> !p.profiles.saving(),"Clean profile settles");
        edt(() -> { button(p.dialog(),"New").doClick(0); return null; });
        await(() -> !p.profiles.saving(),"New profile draft settles without creation or connection");
        check(w.profileList().size()==1 && edt(() -> ((JLabel)field(p,"error")).getText().contains("Profile edits")),"New profile is explicit and never reported Saved");
        edt(() -> {
            var reload=ProfilesEditor.class.getDeclaredMethod("refreshList",Profile.class); reload.setAccessible(true);
            reload.invoke(p.profiles,w.profileList().getFirst()); return null;
        });
        edt(() -> {
            layout(p.dialog()); snapshot(p.dialog().getContentPane(),"settings-profiles");
            p.select("Keyboard Shortcuts"); p.dialog().setSize(760,560); layout(p.dialog()); snapshot(p.dialog().getContentPane(),"settings-keyboard-minimum");
            p.dialog().dispose(); return null;
        });
        check(edt(p::ended),"Closing Settings keeps saved changes and releases its timers");
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
            layout(w); snapshot(w.getRootPane(),"stacked-drop-preview");
            w.clearPreviews(); check(w.currentLayout() instanceof Layout.Split split && split.horizontal(),"Cancel clears preview without changing layout");
            w.dock(source,target,Layout.Dock.TOP); layout(w);
            check(w.currentLayout() instanceof Layout.Split split && !split.horizontal(),"Committed top drop stays vertical");
            Map<UUID,Rectangle> actual=Workspace.project(w.currentLayout(),new Rectangle(0,0,
                    ((JPanel)field(w,"content")).getWidth(),((JPanel)field(w,"content")).getHeight()));
            check(actual.equals(plan.bounds()),"Preview geometry equals actual post-drop geometry, not an unrelated half-tile");
            Container content=(Container)field(w,"content");
            for (SessionPane pane:w.sessionPanes())
                check(SwingUtilities.convertRectangle(pane.getParent(),pane.getBounds(),content).equals(plan.bounds().get(pane.id())),
                        "Preview equals actual Swing pane bounds, including divider pixels");
            check(original.getFirst().screen()==firstScreen && original.stream().allMatch(w.sessionPanes()::contains),"Docking retains controllers and presentation-space state");
            check(!w.previewDock(source,source,Layout.Dock.BOTTOM),"Self-drop has no valid-looking preview");
            w.undoArrangement(); layout(w); check(w.currentLayout() instanceof Layout.Split split && split.horizontal(),"Keyboard undo restores preceding geometry");
            w.dock(source,target,Layout.Dock.BOTTOM); layout(w);
            SavedLayout recipe=w.saveCurrentLayout(null,"Stacked loopback");
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
            button(guide,"Skip step").doClick(0); button(guide,"Skip step").doClick(0);
            w.savedLayoutsDialog();
            ((SavedLayoutsDialog)field(w,"layoutsDialog")).dispose();
            Path exe=root.resolve("Fish.exe");
            String image=System.getProperty("fish.test.image","");
            if (image.isBlank()) Files.createFile(exe); else exe=Path.of(image).toAbsolutePath().resolve("Fish.exe");
            Path executable=exe;
            int[] mode={0},writes={0};
            SavedLayoutsDialog manager=new SavedLayoutsDialog(w,new SavedLayoutsDialog.ShortcutExport() {
                @Override public Path desktop() { return root; }
                @Override public int choose(JFileChooser chooser,Component owner) {
                    check(chooser.getCurrentDirectory().toPath().equals(root),"Chooser never writes to the actual Desktop");
                    chooser.setSelectedFile(root.resolve("Synthetic direct shortcut.lnk").toFile());
                    return mode[0]==0 ? JFileChooser.CANCEL_OPTION : JFileChooser.APPROVE_OPTION;
                }
                @Override public void write(UUID id,Path output,Runnable done,Consumer<String> failed) {
                    writes[0]++;
                    if (mode[0]==1) { failed.accept("Synthetic export refusal"); return; }
                    try { LayoutShortcuts.create(output,executable,w.dataDirectory(),id); done.run(); }
                    catch (java.io.IOException e) { throw new IllegalStateException(e); }
                }
            });
            Field field=Workspace.class.getDeclaredField("layoutsDialog"); field.setAccessible(true); field.set(w,manager);
            ((JTextField)field(manager,"name")).setText("Direct synthetic shortcut");
            int before=w.savedLayouts().size();
            button(manager,"Create desktop shortcut...").doClick(0);
            check(w.savedLayouts().size()==before && writes[0]==0,"Cancel direct creation neither saves recipe nor exports a file");
            mode[0]=1; button(manager,"Create desktop shortcut...").doClick(0);
            check(w.savedLayouts().size()==before+1 && ((JLabel)field(manager,"message")).getText().contains("not created"),"Failed file export reports saved recipe separately from missing shortcut");
            check((int)field(guide,"step")==5,"Failed export cannot complete the actual guide");
            ((JTextField)field(manager,"name")).setText("Direct synthetic success"); mode[0]=2;
            button(manager,"Create desktop shortcut...").doClick(0);
            check((int)field(guide,"step")==6 && Files.isRegularFile(root.resolve("Synthetic direct shortcut.lnk")),"Direct creation saves a real native link and advances only on success");
            String[] properties=LayoutShortcuts.inspect(root.resolve("Synthetic direct shortcut.lnk"));
            SavedLayout last=w.savedLayouts().getLast();
            check(properties[1].equals(LayoutShortcuts.arguments(last.id(),w.dataDirectory())),"Direct flow exports exact stable reference without hosts/secrets");
            check(button(manager,"Save layout only")!=null && field(manager,"manage") instanceof JToggleButton,"Existing recipe operations remain discoverable without a button grid up front");
            layout(manager); snapshot(manager.getContentPane(),"shortcut-current");
            ((JToggleButton)field(manager,"manage")).doClick(0); layout(manager); snapshot(manager.getContentPane(),"shortcut-library");
            layout(guide); snapshot(guide.getContentPane(),"shortcut-guide");
            if (evidence!=null) {
                w.applyTheme(AppTheme.Paper); layout(manager); snapshot(manager.getContentPane(),"shortcut-library-paper");
                w.setSize(520,360); layout(w); snapshot(w.getRootPane(),"window-minimum-paper");
                w.applyTheme(AppTheme.Charcoal); layout(w);
            }
            button(guide,"Back").doClick(0); button(guide,"Skip tutorial").doClick(0);
            ((JTextField)field(manager,"name")).setText("Saved only after acknowledged write");
            button(manager,"Save layout only").doClick(0);
            settle(() -> !(boolean)field(manager,"working"),"Save-only layout did not receive disk acknowledgement");
            check(((JLabel)field(manager,"message")).getText().startsWith("Layout saved.")
                    && f.store.load().savedLayouts().stream().anyMatch(s -> s.name().equals("Saved only after acknowledged write")),
                    "Save layout only reports success after real persistence without creating a shortcut");
            manager.dispose(); return null;
        });
        await(() -> !f.app.saving(),"Shortcut state settles");
    }
    public static void main(String[] args) throws Exception {
        Ui.install();
        String path=System.getProperty("fish.test.evidence","");
        if (!path.isBlank()) { evidence=Path.of(path); Files.createDirectories(evidence); }
        Path root=Files.createTempDirectory(Path.of("build"),"usability-local-").toAbsolutePath();
        try {
            model(root);
            try (Fixture fixture=new Fixture(root.resolve("workspace")); DemoHost host=new DemoHost()) {
                autosave(fixture); docking(fixture,host); shortcut(fixture,root);
                edt(() -> { check(Arrays.stream(Window.getWindows()).noneMatch(Window::isShowing),"No production window was ever shown"); return null; });
            }
        } finally {
            edt(() -> { for (Window w:Window.getWindows()) w.dispose(); return null; });
            try (var files=Files.walk(root)) {
                for (Path file:files.sorted(Comparator.reverseOrder()).toList()) {
                    try { Files.deleteIfExists(file); }
                    catch (FileSystemException busy) { Thread.sleep(250); Files.deleteIfExists(file); }
                }
            }
        }
        System.out.println("PASS immediate settings / direct shortcuts / eight-pane directional docking: "+checks);
    }
}
