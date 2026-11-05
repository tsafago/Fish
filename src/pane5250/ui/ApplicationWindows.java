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
    private final StateStore store;
    private final Path helper;
    private final boolean demonstration,visible;
    private final Factory factory;
    private final List<Workspace> windows=new ArrayList<>();
    private final ExecutorService persistence=Executors.newSingleThreadExecutor(r -> new Thread(r,"fish-save"));
    private final javax.swing.Timer saveTimer;
    private Runnable afterClose=() -> {};
    private boolean syncing,closing;
    private StateStore.State shared;

    public ApplicationWindows(StateStore store,Path helper,boolean demonstration) {
        this(store,helper,demonstration,true,(s,state,h,d) -> new Workspace(s,state,h,d,new pane5250.update.Updates(),
                (owner,message) -> Ui.confirm(owner,message,"Update Fish"),false));
    }
    ApplicationWindows(StateStore store,Path helper,boolean demo,boolean visible,Factory factory) {
        this.store=store; this.helper=helper; demonstration=demo; this.visible=visible; this.factory=factory;
        saveTimer=new javax.swing.Timer(500,e -> saveAsync()); saveTimer.setRepeats(false);
    }
    public void onClosed(Runnable callback) { afterClose=callback; }
    public List<Workspace> windows() { return List.copyOf(windows); }
    public void restore(StateStore.State state) {
        shared=state.withWindows(List.of());
        Ui.applyTheme(state.appTheme(),state.appearance().custom().orElse(null));
        create(shared);
        for (StateStore.WindowState window:state.windows()) create(shared.forWindow(window));
        if (visible) for (Workspace window:windows()) start(window);
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
    private void start(Workspace window) {
        SwingUtilities.invokeLater(() -> {
            if (window.closing()) return;
            if (demonstration) window.connectAll(); else window.startup();
        });
    }
    public boolean newWindow() { return newWindow(windows.isEmpty() ? null : windows.getFirst()); }
    boolean newWindow(Workspace source) {
        if (closing || source==null || saving()) return false;
        StateStore.State current=source.state();
        StateStore.WindowState blank=new StateStore.WindowState(new Layout.Leaf(UUID.randomUUID(),current.lastProfile()),
                current.width(),current.height(),current.lastProfile());
        Workspace window=create(shared.forWindow(blank));
        if (visible) start(window);
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
        return primary.withWindows(windows.stream().skip(1).map(Workspace::state)
                .map(s -> new StateStore.WindowState(s.layout(),s.width(),s.height(),s.lastProfile())).toList());
    }
    void saveAsync() {
        if (closing) return;
        StateStore.State snapshot=snapshot();
        persistence.submit(() -> {
            try { store.save(snapshot); }
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
        if (saving()) { Ui.error(window,"Wait for the profile save to finish before closing."); return; }
        if (windows.size()==1) { quit(); return; }
        windows.remove(window); window.finishWindow(); changed(windows.getFirst());
        saveAsync();
    }
    void quit() {
        if (closing) return;
        if (saving()) { Ui.error(windows.getFirst(),"Wait for the profile save to finish before quitting Fish."); return; }
        StateStore.State saved=snapshot();
        closing=true; saveTimer.stop();
        windows.forEach(Workspace::finishWindow);
        persistence.submit(() -> {
            try { store.save(saved); } catch (IOException e) { reportSave(e); }
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
                else { store.save(saved); action.run(); }
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
