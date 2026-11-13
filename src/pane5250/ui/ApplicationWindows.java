package pane5250.ui;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import pane5250.model.*;

/** EDT-owned shared settings and independent frames; only this owner writes the workspace. */
public final class ApplicationWindows {
    @FunctionalInterface interface Factory { Workspace create(StateStore store,StateStore.State state,Path helper,boolean demo); }
    @FunctionalInterface interface Writer { void save(StateStore.State state) throws IOException; }
    private final StateStore store;
    private final Path helper;
    private final boolean demonstration,visible;
    private final Factory factory;
    private final Writer writer;
    private final List<Workspace> windows=new ArrayList<>();
    private final ExecutorService persistence=Executors.newSingleThreadExecutor(r -> new Thread(r,"fish-save"));
    private final javax.swing.Timer saveTimer;
    private Runnable afterClose=() -> {};
    private boolean syncing,closing;
    private StateStore.State shared;
    private List<StateStore.WindowState> dormant=List.of();

    public ApplicationWindows(StateStore store,Path helper,boolean demonstration) {
        this(store,helper,demonstration,true,(s,state,h,d) -> new Workspace(s,state,h,d,new pane5250.update.Updates(),
                (owner,message) -> Ui.confirm(owner,message,"Update Fish"),false));
    }
    ApplicationWindows(StateStore store,Path helper,boolean demo,boolean visible,Factory factory) {
        this(store,helper,demo,visible,factory,store::save);
    }
    ApplicationWindows(StateStore store,Path helper,boolean demo,boolean visible,Factory factory,Writer writer) {
        this.store=store; this.helper=helper; demonstration=demo; this.visible=visible; this.factory=factory;
        this.writer=writer;
        saveTimer=new javax.swing.Timer(500,e -> saveAsync()); saveTimer.setRepeats(false);
    }
    public void onClosed(Runnable callback) { afterClose=callback; }
    public List<Workspace> windows() { return List.copyOf(windows); }
    public void restore(StateStore.State state) {
        restore(state,null);
    }
    public void restore(StateStore.State state,UUID launchLayout) {
        shared=state.withWindows(List.of());
        Ui.applyTheme(state.appTheme(),state.appearance().custom().orElse(null));
        if (launchLayout!=null) {
            SavedLayout recipe=recipe(launchLayout);
            List<StateStore.WindowState> retained=new ArrayList<>();
            retained.add(windowState(state)); retained.addAll(state.windows()); dormant=List.copyOf(retained);
            Workspace window=createRecipe(recipe);
            start(window,true);
            saveTimer.restart();
            return;
        }
        create(shared);
        for (StateStore.WindowState window:state.windows()) create(shared.forWindow(window));
        if (visible) for (Workspace window:windows()) start(window,false);
    }
    private Workspace create(StateStore.State state) {
        Workspace window=factory.create(store,state,helper,demonstration);
        window.application(this); windows.add(window);
        if (visible) {
            if (windows.size()>1) {
                Point location=windows.getFirst().getLocation();
                window.setLocation(location.x+24*(windows.size()-1),location.y+24*(windows.size()-1));
            }
            window.setVisible(true);
        }
        return window;
    }
    private void start(Workspace window,boolean explicit) {
        SwingUtilities.invokeLater(() -> {
            if (window.closing()) return;
            if (demonstration) window.connectAll(); else window.startup(explicit);
        });
    }
    public boolean newWindow() { return newWindow(windows.isEmpty() ? null : windows.getFirst()); }
    public int openRequest(UUID layout) {
        if (closing || saving()) return 0;
        if (layout==null) return newWindow() ? 1 : 0;
        if (shared.savedLayouts().stream().noneMatch(s -> s.id().equals(layout))) return 2;
        openLayout(layout); return 1;
    }
    private SavedLayout recipe(UUID id) {
        return shared.savedLayouts().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Saved layout is unavailable. Open Fish normally and use Panes > Saved layouts / shortcuts to recreate it."));
    }
    boolean openLayout(UUID id) {
        if (closing || saving()) return false;
        Workspace window=createRecipe(recipe(id)); start(window,true); saveTimer.restart(); return true;
    }
    private Workspace createRecipe(SavedLayout recipe) {
        Set<UUID> profiles=new HashSet<>(); shared.profiles().forEach(p -> profiles.add(p.id())); recipe.validateProfiles(profiles);
        Layout layout=recipe.window().layout();
        Map<UUID,UUID> fresh=new HashMap<>();
        for (Layout.Leaf leaf:layout.leaves()) {
            UUID id=UUID.randomUUID(); fresh.put(leaf.pane(),id);
            layout=layout.mapLeaf(leaf.pane(),old -> new Layout.Leaf(id,old.profile()));
        }
        StateStore.WindowState saved=recipe.window();
        Workspace window=create(shared.forWindow(new StateStore.WindowState(layout,saved.width(),saved.height(),saved.lastProfile(),saved.addProfile())));
        recipe.displays().forEach((old,display) -> window.sessionPanes().stream().filter(p -> p.id().equals(fresh.get(old)))
                .findFirst().orElseThrow().view().restoreDisplay(display));
        return window;
    }
    boolean newWindow(Workspace source) {
        if (closing || source==null || saving()) return false;
        StateStore.State current=source.state();
        StateStore.WindowState blank=new StateStore.WindowState(new Layout.Leaf(UUID.randomUUID(),current.addProfile()),
                current.width(),current.height(),current.addProfile(),current.addProfile());
        Workspace window=create(shared.forWindow(blank));
        start(window,true);
        saveTimer.restart();
        return true;
    }
    boolean closing() { return closing; }
    boolean saving() { return windows.stream().anyMatch(Workspace::localSettingsSaving); }
    List<SessionPane> panes() { return windows.stream().flatMap(w -> w.sessionPanes().stream()).toList(); }
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
        saveTimer.restart();
    }
    void refreshActions() { windows.forEach(Workspace::refreshCommands); }
    StateStore.State snapshot() {
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
            try { writer.save(value); action.run(); SwingUtilities.invokeLater(done); }
            catch (IOException | RuntimeException e) { SwingUtilities.invokeLater(() -> failed.accept(e.getMessage())); }
        });
    }
    void saveAsync() {
        if (closing) return;
        StateStore.State snapshot=snapshot();
        persistence.submit(() -> {
            try { writer.save(snapshot); }
            catch (IOException e) { reportSave(e); }
        });
    }
    private void reportSave(IOException error) {
        System.err.println("Fish windows could not be saved: "+error.getMessage());
        SwingUtilities.invokeLater(() -> {
            if (!closing && visible && !windows.isEmpty()) Ui.error(windows.getFirst(),"Windows not saved: "+error.getMessage());
        });
    }
    void closeWindow(Workspace window) {
        if (closing || window.closing()) return;
        if (saving()) {
            if (visible) Ui.error(window,"Wait for the profile save to finish before closing.");
            else System.err.println("Wait for the profile save to finish before closing.");
            return;
        }
        if (!window.closeSettings()) return;
        if (windows.size()==1) { quit(); return; }
        windows.remove(window); window.finishWindow(); changed(windows.getFirst());
        saveAsync();
    }
    void quit() {
        if (closing) return;
        if (saving()) { Ui.error(windows.getFirst(),"Wait for the profile save to finish before quitting Fish."); return; }
        for (Workspace window:windows) if (!window.closeSettings()) return;
        StateStore.State saved=snapshot();
        closing=true; saveTimer.stop();
        windows.forEach(Workspace::finishWindow);
        persistence.submit(() -> {
            try { writer.save(saved); } catch (IOException e) { reportSave(e); }
            release();
        });
    }
    private void release() {
        try { store.close(); }
        catch (IOException e) { System.err.println("Fish workspace lock release failed: "+e.getMessage()); }
        finally { persistence.shutdown(); SwingUtilities.invokeLater(afterClose); }
    }
    boolean maintenance(Workspace owner,Workspace.Maintenance action,boolean reset,java.util.function.Consumer<String> failed) {
        if (closing || saving() || panes().stream().anyMatch(SessionPane::connecting)) return false;
        StateStore.State saved=snapshot();
        closing=true; saveTimer.stop();
        windows.forEach(w -> { w.pauseSaving(); w.setEnabled(false); });
        persistence.submit(() -> {
            try {
                if (reset) store.factoryReset();
                else { writer.save(saved); action.run(); }
            } catch (IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> {
                    closing=false; windows.forEach(w -> w.setEnabled(true)); failed.accept(e.getMessage());
                });
                return;
            }
            SwingUtilities.invokeLater(() -> {
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
