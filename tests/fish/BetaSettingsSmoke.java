package fish.ui;

import java.nio.file.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import fish.demo.DemoHost;
import fish.model.*;

/** Measured actual JavaFX Settings controls and production paint; isolated state, never shown. */
public final class BetaSettingsSmoke {
    private static int checks;
    private static boolean baseline;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static <T> T edt(Callable<T> action) throws Exception { return FxTestSupport.fx(action); }
    private static Object field(Object owner,String name) throws Exception { return FxTestSupport.field(owner,name); }
    private static void paint(Workspace w) { FxTestSupport.snapshot(w,1.0); }

    private static double change(UsabilityIterationSmoke.Fixture f,Preferences p,String name,Runnable edit,Callable<Boolean> visible) throws Exception {
        UsabilityIterationSmoke.settle(() -> !p.saving(),"Previous write remains pending");
        long start=System.nanoTime(); edit.run();
        if (!baseline) check(visible.call(),"Discrete setting must be visible before the event handler returns: "+name);
        if (!visible.call()) UsabilityIterationSmoke.settle(visible,"Control did not take effect: "+name);
        paint(f.first());
        double elapsed=(System.nanoTime()-start)/1_000_000d;
        System.out.printf(java.util.Locale.ROOT,"Settings %s interaction->production paint: %.1f ms%n",name,elapsed);
        if (!baseline) check(elapsed<250,"Synthetic control-to-paint latency exceeds 250 ms: "+name);
        return elapsed;
    }

    private static void slow(Path root) throws Exception {
        CountDownLatch writing=new CountDownLatch(1),release=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean block=new java.util.concurrent.atomic.AtomicBoolean(true);
        var f=new UsabilityIterationSmoke.Fixture(root,s -> state -> {
            check(!Platform.isFxApplicationThread(),"Disk writer must never execute on JavaFX Application Thread");
            if (block.getAndSet(false)) {
                writing.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new java.io.IOException("Injected slow save timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
            }
            s.save(state);
        });
        try {
            Workspace w=f.first();
            Workspace peer=edt(() -> { check(f.app.newWindow(w),"Independent window admitted"); return f.app.windows().getLast(); });
            Preferences p=edt(() -> { w.preferences("Appearance"); return w.tutorialPreferences(); });
            edt(() -> {
                @SuppressWarnings("unchecked") ComboBox<Appearance.CursorStyle> cs=(ComboBox<Appearance.CursorStyle>)field(p,"cursorStyle");
                cs.setValue(Appearance.CursorStyle.FILLED);
                return null;
            });
            check(writing.await(10,TimeUnit.SECONDS),"Injected writer entered");
            edt(() -> {
                check(!((Label)field(p,"error")).getText().equals("Saved"),"Blocked disk acknowledgement cannot claim Saved");
                @SuppressWarnings("unchecked") ComboBox<String> pal=(ComboBox<String>)field(p,"palette");
                pal.setValue("Azure vivid");
                ((CheckBox)field(p,"startup")).setSelected(true);
                @SuppressWarnings("unchecked") ComboBox<Appearance.CursorStyle> cs=(ComboBox<Appearance.CursorStyle>)field(p,"cursorStyle");
                cs.setValue(Appearance.CursorStyle.OUTLINE);
                ProfilesEditor profiles=(ProfilesEditor)field(p,"profiles");
                profiles.deviceField().setText("DSP1140");
                check(w.profileList().getFirst().theme().equals("Azure vivid") && peer.profileList().getFirst().theme().equals("Azure vivid")
                        && peer.connectOnStartup() && peer.appearanceSettings().cursorStyle()==Appearance.CursorStyle.OUTLINE
                        && peer.profileList().getFirst().device().equals("DSP1140"),
                        "Rendering/shared state must advance while an older generation's disk write is blocked");
                peer.highlightActive(false);
                check(!((CheckBox)field(p,"tint")).isSelected(),"Clean open editor rebases external changes even during persistence");
                paint(w);
                return null;
            });
            release.countDown();
            UsabilityIterationSmoke.settle(() -> !p.saving(),"Latest applied generation did not persist");
            StateStore.State state=f.store.load();
            check(state.appearance().cursorStyle()==Appearance.CursorStyle.OUTLINE && state.connectOnStartup()
                    && !state.highlightActive() && state.profiles().getFirst().theme().equals("Azure vivid")
                    && state.profiles().getFirst().device().equals("DSP1140"),
                    "Older acknowledgement never loses newer edits/rebased shared changes");
            check(edt(() -> ((Label)field(p,"error")).getText().equals("Saved")),"Only latest durable generation reports Saved");
        } finally { release.countDown(); f.close(); }
    }

    public static void main(String[] args) throws Exception {
        Ui.install();
        baseline=args.length>0 && args[0].equals("baseline");
        Path root=Files.createTempDirectory(Path.of("build"),"beta-settings-");
        try {
            try (DemoHost host=new DemoHost(); var f=new UsabilityIterationSmoke.Fixture(root)) {
                if (!baseline) {
                    edt(() -> {
                        Workspace w=f.first(); Profile p=w.active().profile();
                        w.putProfile(new Profile(p.id(),p.name(),"127.0.0.1",host.port(),"","",false,false,"37","Phosphor","None",1,java.util.Map.of(),""));
                        w.active().connectOnStartup(); return null;
                    });
                    UsabilityIterationSmoke.settle(() -> f.first().active().connected() && f.first().active().screen().getScreenFields().getSize()>0,
                            "Synthetic production terminal did not connect for latency measurement");
                    System.out.println("Latency sample uses production connected loopback TN5250/TerminalView paint; not IBM i/hardware acceptance.");
                }
                edt(() -> {
                    Workspace w=f.first(); paint(w); w.preferences("Appearance"); Preferences p=w.tutorialPreferences();
                    @SuppressWarnings("unchecked") ComboBox<Object> theme=(ComboBox<Object>)field(p,"theme");
                    FxTestSupport.layout(p.dialog());
                    double wBefore=theme.getWidth(),hBefore=theme.getHeight();
                    change(f,p,"theme",() -> theme.setValue(AppTheme.Paper),() -> Ui.theme()==AppTheme.Paper);
                    FxTestSupport.layout(p.dialog());
                    check(Math.abs(theme.getWidth()-wBefore)<=1 && Math.abs(theme.getHeight()-hBefore)<=1,
                            "Color-only theme choice preserves control layout");
                    @SuppressWarnings("unchecked") ComboBox<String> palette=(ComboBox<String>)field(p,"palette");
                    change(f,p,"palette",() -> palette.setValue("Amber"),() -> w.profileList().getFirst().theme().equals("Amber"));
                    @SuppressWarnings("unchecked") ComboBox<String> family=(ComboBox<String>)field(p,"family");
                    change(f,p,"font",() -> family.setValue("Consolas"),() -> w.appearanceSettings().font().family().equals("Consolas"));
                    CheckBox chrome=(CheckBox)field(p,"window");
                    change(f,p,"outline",chrome::fire,() -> !w.appearanceSettings().windowOutline());
                    @SuppressWarnings("unchecked") ComboBox<Appearance.CursorStyle> cursor=(ComboBox<Appearance.CursorStyle>)field(p,"cursorStyle");
                    change(f,p,"cursor",() -> cursor.setValue(Appearance.CursorStyle.FILLED),() -> w.appearanceSettings().cursorStyle()==Appearance.CursorStyle.FILLED);
                    CheckBox startup=(CheckBox)field(p,"startup");
                    change(f,p,"general",startup::fire,w::connectOnStartup);
                    return null;
                });
            }
            if (!baseline) slow(root.resolve("slow"));
            System.out.println("PASS immediate Settings production latency / blocked writer / generation and peer rebase: "+checks);
        } finally {
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
        }
        Ui.finishTestRun();
    }
}
