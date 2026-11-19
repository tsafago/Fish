package fish.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.stage.Stage;
import javafx.util.Duration;
import fish.LayoutShortcuts;
import fish.model.*;
import fish.security.KioskMode;
import fish.update.Updates;

/** JavaFX UI-thread-owned shared settings and independent stages; only this owner writes the workspace. */
public final class ApplicationWindows {
    @FunctionalInterface public interface Factory { Workspace create(StateStore store,StateStore.State state,Path helper,boolean demo); }
    @FunctionalInterface public interface Writer { void save(StateStore.State state) throws IOException; }

    private final StateStore store;
    private final Path helper;
    private final boolean demonstration,visible;
    private final Factory factory;
    private final Writer writer;
    private final List<Workspace> windows=new ArrayList<>();
    private final ExecutorService persistence=Executors.newSingleThreadExecutor(r -> new Thread(r,"fish-save"));
    private final Timeline saveTimer;
    private Runnable afterClose=() -> {};
    private boolean syncing,closing;
    private StateStore.State shared;
    private List<StateStore.WindowState> dormant=List.of();

    public ApplicationWindows(StateStore store,Path helper,boolean demonstration) {
        this(store,helper,demonstration,true,(s,state,h,d) -> new Workspace(s,state,h,d,new Updates(),
                (owner,message) -> Ui.confirm(owner,message,"Update Fish"),false));
    }
    public ApplicationWindows(StateStore store,Path helper,boolean demo,boolean visible,Factory factory) {
        this(store,helper,demo,visible,factory,store::save);
    }
    public ApplicationWindows(StateStore store,Path helper,boolean demo,boolean visible,Factory factory,Writer writer) {
        this.store=store; this.helper=helper; demonstration=demo; this.visible=visible; this.factory=factory;
        this.writer=writer;
        saveTimer=new Timeline(new KeyFrame(Duration.millis(500),e -> saveAsync()));
    }

    public void onClosed(Runnable callback) { afterClose=callback; }
    public List<Workspace> windows() { return List.copyOf(windows); }

    public void restore(StateStore.State state) { restore(state,null); }

    public void restore(StateStore.State state,UUID launchLayout) {
        shared=state.withWindows(List.of());
        Ui.surfacePreferences(state.appearance().flatThemes());
        Ui.applyTheme(state.appTheme(),state.appearance().custom().orElse(null));
        if (!demonstration && !KioskMode.isEnabled()) {
            try { LayoutShortcuts.syncAutostart(store.directory(),LayoutShortcuts.executable(),state.savedLayouts()); }
            catch (Exception ignored) {}
        }
        if (launchLayout!=null) {
            SavedLayout recipe=recipe(launchLayout);
            List<StateStore.WindowState> retained=new ArrayList<>();
            retained.add(windowState(state)); retained.addAll(state.windows()); dormant=List.copyOf(retained);
            Workspace window=createRecipe(recipe);
            start(window,true);
            if (!KioskMode.isEnabled()) saveTimer.playFromStart();
            return;
        }
        create(shared);
        for (StateStore.WindowState window:state.windows()) create(shared.forWindow(window));
        if (visible) for (Workspace window:windows()) start(window,false);
    }

    private Workspace create(StateStore.State state) {
        Workspace window=factory.create(store,state,helper,demonstration);
        window.application(this);
        windows.add(window);
        if (visible) {
            window.setVisible(true);
            if (windows.size()>1) {
                double baseX=windows.getFirst().stage().getX();
                double baseY=windows.getFirst().stage().getY();
                window.stage().setX(baseX+24*(windows.size()-1));
                window.stage().setY(baseY+24*(windows.size()-1));
            }
        }
        return window;
    }

    private void start(Workspace window,boolean explicit) {
        Ui.runLater(() -> {
            if (window.closing()) return;
            if (demonstration) window.connectAll(); else window.startup(explicit);
        });
    }

    public boolean newWindow() { return newWindow(windows.isEmpty() ? null : windows.getFirst()); }

    public int openRequest(UUID layout) {
        if (closing || saving()) return 0;
        if (layout==null) {
            if (KioskMode.isEnabled()) {
                Stage owner=windows.isEmpty() ? null : windows.getFirst().stage();
                Stage dialog=WorkspaceRecovery.kioskBlockedDialog(owner,unlocked -> {});
                dialog.show();
                dialog.toFront();
                return 1;
            }
            return newWindow() ? 1 : 0;
        }
        if (shared.savedLayouts().stream().noneMatch(s -> s.id().equals(layout))) return 2;
        openLayout(layout);
        return 1;
    }

    private SavedLayout recipe(UUID id) {
        return shared.savedLayouts().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Shortcut is unavailable. Open Fish normally and create a new shortcut in Settings > Shortcuts."));
    }

    public boolean openLayout(UUID id) {
        if (closing || saving()) return false;
        Workspace window=createRecipe(recipe(id));
        start(window,true);
        if (!KioskMode.isEnabled()) saveTimer.playFromStart();
        return true;
    }

    private Workspace createRecipe(SavedLayout recipe) {
        Set<UUID> profiles=new HashSet<>(); shared.profiles().forEach(p -> profiles.add(p.id())); recipe.validateProfiles(profiles);
        Layout layout=recipe.window().layout();
        Map<UUID,UUID> fresh=new HashMap<>();
        for (Layout.Leaf leaf:layout.leaves()) {
            UUID id=UUID.randomUUID(); fresh.put(leaf.pane(),id);
            layout=layout.mapLeaf(leaf.pane(),old -> new Layout.Leaf(id,old.profile()));
        }
        Map<UUID,SavedLayout.Display> remappedDisplays=new LinkedHashMap<>();
        recipe.displays().forEach((old,display) -> {
            UUID mapped=fresh.get(old);
            if (mapped!=null) remappedDisplays.put(mapped,display);
        });
        StateStore.WindowState saved=recipe.window();
        Workspace window=create(shared.forWindow(new StateStore.WindowState(
                layout,saved.width(),saved.height(),saved.lastProfile(),saved.addProfile(),remappedDisplays)));
        recipe.displays().forEach((old,display) -> window.sessionPanes().stream().filter(p -> p.id().equals(fresh.get(old)))
                .findFirst().orElseThrow().view().restoreDisplay(display));
        return window;
    }

    public boolean newWindow(Workspace source) {
        if (closing || source==null || saving()) return false;
        StateStore.State current=source.state();
        StateStore.WindowState blank=new StateStore.WindowState(new Layout.Leaf(UUID.randomUUID(),current.addProfile()),
                current.width(),current.height(),current.addProfile(),current.addProfile());
        Workspace window=create(shared.forWindow(blank));
        start(window,true);
        saveTimer.playFromStart();
        return true;
    }

    Workspace windowAt(long handle) {
        if (handle<=0) return null;
        return windows.stream().filter(w -> w.paneWindowHandle()==handle).findFirst().orElse(null);
    }

    private boolean movable(SessionPane pane) {
        return pane!=null && !closing && !saving() && !KioskMode.isEnabled() && !pane.connecting()
                && windows.contains(pane.workspace()) && !pane.workspace().closing() && pane.workspace().hasPane(pane.id());
    }

    boolean canTransfer(SessionPane pane,Workspace target) {
        return movable(pane) && windows.contains(target) && target!=pane.workspace() && !target.closing()
                && !target.hasPane(pane.id()) && target.paneCount()<Layout.MAX_PANES;
    }

    boolean transfer(SessionPane pane,Workspace target,Layout next) {
        if (!canTransfer(pane,target)) return false;
        Workspace source=pane.workspace();
        Layout remaining=source.currentLayout().remove(pane.id());
        // A modeless editor belongs to its stage. Don't close it or discard an edit as a side effect of a drag.
        if (remaining==null && source.hasOpenEditor()) {
            pane.problem("Close Settings before moving the last pane."); return false;
        }
        source.releasePane(pane,remaining);
        target.receivePane(pane,next);
        if (remaining==null) {
            windows.remove(source);
            source.finishWindow(); // the pane is no longer in its disposal set
        }
        scheduleSave();
        return true;
    }

    boolean detach(SessionPane pane,int[] desktopPoint) {
        if (!movable(pane) || !pane.workspace().hasMultiplePanes()) return false;
        Workspace source=pane.workspace();
        Layout.Leaf leaf=new Layout.Leaf(pane.id(),pane.profile().id());
        // Stage construction/show must succeed before removing anything from the live tree.
        var blank=new Layout.Leaf(UUID.randomUUID(),leaf.profile());
        Workspace target=factory.create(store,shared.forWindow(new StateStore.WindowState(blank,
                source.getWidth(),source.getHeight(),leaf.profile(),leaf.profile())),helper,demonstration);
        target.application(this); windows.add(target);
        boolean moved=false;
        try {
            if (visible) {
                var s=source.stage(); var t=target.stage();
                var screen=javafx.stage.Screen.getScreensForRectangle(s.getX(),s.getY(),s.getWidth(),s.getHeight())
                        .stream().findFirst().orElse(javafx.stage.Screen.getPrimary()).getVisualBounds();
                t.setX(Math.max(screen.getMinX(),Math.min(s.getX()+24,screen.getMaxX()-t.getWidth())));
                t.setY(Math.max(screen.getMinY(),Math.min(s.getY()+24,screen.getMaxY()-t.getHeight())));
                target.setVisible(true);
                if (desktopPoint!=null && target.paneWindowHandle()!=0)
                    PaneWindowHit.place0(target.paneWindowHandle(),desktopPoint[0],desktopPoint[1]);
            }
            moved=transfer(pane,target,leaf);
            return moved;
        } finally {
            if (!moved && pane.workspace()!=target) { windows.remove(target); target.finishWindow(); }
        }
    }

    public boolean closing() { return closing; }
    public boolean saving() { return windows.stream().anyMatch(Workspace::localSettingsSaving); }
    Workspace shortcutWriter() { return windows.stream().filter(Workspace::exportingShortcut).findFirst().orElse(null); }
    public List<SessionPane> panes() { return windows.stream().flatMap(w -> w.sessionPanes().stream()).toList(); }
    boolean checkStartupUpdates(Workspace window) { return !windows.isEmpty() && windows.getFirst()==window; }

    void changed(Workspace source) {
        if (closing || syncing || !windows.contains(source)) return;
        StateStore.State next=source.state();
        if (!dormant.isEmpty()) {
            Set<UUID> ids=new HashSet<>(); next.profiles().forEach(p -> ids.add(p.id()));
            UUID replacement=next.profiles().getFirst().id();
            dormant=dormant.stream().map(w -> {
                Layout layout=w.layout();
                for (Layout.Leaf leaf:layout.leaves()) if (!ids.contains(leaf.profile())) layout=layout.replaceProfile(leaf.profile(),replacement);
                return new StateStore.WindowState(layout,w.width(),w.height(),ids.contains(w.lastProfile()) ? w.lastProfile() : replacement,
                        ids.contains(w.addProfile()) ? w.addProfile() : replacement,w.displays());
            }).toList();
        }
        shared=next.withWindows(List.of());
        syncing=true;
        try { for (Workspace window:windows) if (window!=source) window.receiveShared(next); }
        finally { syncing=false; }
        if (!KioskMode.isEnabled()) saveTimer.playFromStart();
    }

    void scheduleSave() {
        if (closing || syncing || KioskMode.isEnabled()) return;
        saveTimer.playFromStart();
    }

    void refreshActions() { windows.forEach(Workspace::refreshCommands); }

    public StateStore.State snapshot() {
        if (windows.isEmpty()) throw new IllegalStateException("No Fish windows remain.");
        StateStore.State primary=windows.getFirst().state();
        List<StateStore.WindowState> retained=new ArrayList<>(dormant);
        retained.addAll(windows.stream().map(Workspace::state).map(ApplicationWindows::windowState).toList());
        if (!dormant.isEmpty()) primary=primary.forWindow(retained.getFirst());
        return primary.withWindows(retained.subList(1,retained.size()));
    }

    private static StateStore.WindowState windowState(StateStore.State value) {
        return new StateStore.WindowState(value.layout(),value.width(),value.height(),value.lastProfile(),value.addProfile(),value.displays());
    }

    void persistTask(Workspace.Maintenance action,Runnable done,java.util.function.Consumer<String> failed) {
        saveTimer.stop();
        StateStore.State value=snapshot();
        persistence.submit(() -> {
            try { writer.save(value); action.run(); Ui.runLater(done); }
            catch (IOException | RuntimeException e) { Ui.runLater(() -> failed.accept(e.getMessage())); }
        });
    }

    public void saveAsync() {
        if (closing || KioskMode.isEnabled()) return;
        if (shortcutWriter()!=null) { saveTimer.playFromStart(); return; }
        StateStore.State snap=snapshot();
        persistence.submit(() -> {
            try { writer.save(snap); }
            catch (IOException e) { reportSave(e); }
        });
    }

    void submitWrite(java.util.function.Consumer<Writer> work) {
        persistence.submit(() -> work.accept(writer));
    }

    private void reportSave(IOException error) {
        System.err.println("Fish windows could not be saved: "+error.getMessage());
        Ui.runLater(() -> {
            if (!closing && visible && !windows.isEmpty())
                Ui.error(windows.getFirst().stage(),"Windows not saved: "+error.getMessage());
        });
    }

    public void closeWindow(Workspace window) {
        if (closing || window.closing()) return;
        if (saving()) {
            if (visible) Ui.error(window.stage(),"Wait for the profile save to finish before closing.");
            else System.err.println("Wait for the profile save to finish before closing.");
            return;
        }
        if (!window.closeSettings()) return;
        if (windows.size()==1) { quit(); return; }
        windows.remove(window); window.finishWindow(); changed(windows.getFirst());
        saveAsync();
    }

    public void quit() {
        if (closing) return;
        if (saving()) { Ui.error(windows.getFirst().stage(),"Wait for the profile save to finish before quitting Fish."); return; }
        for (Workspace window:windows) if (!window.closeSettings()) return;
        boolean kiosk=KioskMode.isEnabled();
        StateStore.State saved=kiosk ? null : snapshot();
        closing=true; saveTimer.stop();
        windows.forEach(Workspace::finishWindow);
        persistence.submit(() -> {
            if (!kiosk && saved!=null) {
                try { writer.save(saved); } catch (IOException e) { reportSave(e); }
            }
            release();
        });
    }

    private void release() {
        try { store.close(); }
        catch (IOException e) { System.err.println("Fish workspace lock release failed: "+e.getMessage()); }
        finally { persistence.shutdown(); Ui.runLater(afterClose); }
    }

    public boolean maintenance(Workspace owner,Workspace.Maintenance action,boolean reset,java.util.function.Consumer<String> failed) {
        if (closing || saving() || panes().stream().anyMatch(SessionPane::connecting)) return false;
        StateStore.State saved=snapshot();
        closing=true; saveTimer.stop();
        windows.forEach(w -> { w.pauseSaving(); w.setEnabled(false); });
        persistence.submit(() -> {
            try {
                if (reset) store.factoryReset();
                else { writer.save(saved); action.run(); }
            } catch (IOException | RuntimeException e) {
                Ui.runLater(() -> {
                    closing=false; windows.forEach(w -> w.setEnabled(true)); failed.accept(e.getMessage());
                });
                return;
            }
            Ui.runLater(() -> {
                windows.forEach(Workspace::finishWindow);
                persistence.submit(this::release);
            });
        });
        return true;
    }

    Workspace settingsOwner(Workspace requested) {
        return windows.stream().filter(w -> w.tutorialPreferences()!=null).findFirst().orElse(requested);
    }
}
