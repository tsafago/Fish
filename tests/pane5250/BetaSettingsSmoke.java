package pane5250.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.swing.*;
import pane5250.model.*;
import pane5250.demo.DemoHost;

/** Measured actual Settings controls and production paint; isolated state, never shown. */
public final class BetaSettingsSmoke {
    private static int checks;
    private static boolean baseline;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> run=new FutureTask<>(action); SwingUtilities.invokeAndWait(run); return run.get();
    }
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void paint(Workspace w) {
        BufferedImage image=new BufferedImage(w.getWidth(),w.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); try { w.getContentPane().printAll(g); } finally { g.dispose(); }
    }
    private static double change(UsabilityIterationSmoke.Fixture f,Preferences p,String name,Runnable edit,Callable<Boolean> visible) throws Exception {
        UsabilityIterationSmoke.settle(() -> !p.saving(),"Previous write remains pending");
        long start=System.nanoTime(); edit.run();
        if (!baseline) check(visible.call(),"Discrete setting must be visible before the event handler returns: "+name);
        if (!visible.call()) UsabilityIterationSmoke.settle(visible,"Control did not take effect: "+name);
        paint(f.first());
        double elapsed=(System.nanoTime()-start)/1_000_000d;
        System.out.printf(java.util.Locale.ROOT,"Settings %s interaction->production paint: %.1f ms%n",name,elapsed);
        if (!baseline) check(elapsed<200,"Synthetic control-to-paint latency exceeds 200 ms: "+name);
        return elapsed;
    }
    private static void slow(Path root) throws Exception {
        CountDownLatch writing=new CountDownLatch(1),release=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean block=new java.util.concurrent.atomic.AtomicBoolean(true);
        var f=new UsabilityIterationSmoke.Fixture(root,s -> state -> {
            check(!SwingUtilities.isEventDispatchThread(),"Disk writer must never execute on EDT");
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
            edt(() -> { ((JComboBox<?>)field(p,"cursorStyle")).setSelectedItem(Appearance.CursorStyle.FILLED); return null; });
            check(writing.await(10,TimeUnit.SECONDS),"Injected writer entered");
            edt(() -> {
                check(!((JLabel)field(p,"error")).getText().equals("Saved"),"Blocked disk acknowledgement cannot claim Saved");
                ((JComboBox<?>)field(p,"palette")).setSelectedItem("Azure vivid");
                ((JCheckBox)field(p,"startup")).setSelected(true);
                ((JComboBox<?>)field(p,"cursorStyle")).setSelectedItem(Appearance.CursorStyle.OUTLINE);
                ProfilesEditor profiles=(ProfilesEditor)field(p,"profiles");
                ((JComboBox<?>)field(profiles,"codePage")).setSelectedItem("1140");
                check(w.profileList().getFirst().theme().equals("Azure vivid") && peer.profileList().getFirst().theme().equals("Azure vivid")
                        && peer.connectOnStartup() && peer.appearanceSettings().cursorStyle()==Appearance.CursorStyle.OUTLINE
                        && peer.profileList().getFirst().codePage().equals("1140"),
                        "Rendering/shared state must advance while an older generation's disk write is blocked");
                peer.highlightActive(false);
                check(!((JCheckBox)field(p,"tint")).isSelected(),"Clean open editor rebases external changes even during persistence");
                paint(w);
                return null;
            });
            release.countDown();
            UsabilityIterationSmoke.settle(() -> !p.saving(),"Latest applied generation did not persist");
            StateStore.State state=f.store.load();
            check(state.appearance().cursorStyle()==Appearance.CursorStyle.OUTLINE && state.connectOnStartup()
                    && !state.highlightActive() && state.profiles().getFirst().theme().equals("Azure vivid")
                    && state.profiles().getFirst().codePage().equals("1140"),
                    "Older acknowledgement never loses newer edits/rebased shared changes");
            check(edt(() -> ((JLabel)field(p,"error")).getText().equals("Saved")),"Only latest durable generation reports Saved");
        } finally { release.countDown(); f.close(); }
    }
    public static void main(String[] args) throws Exception {
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
            FutureTask<Void> run=new FutureTask<>(() -> {
                Workspace w=f.first(); paint(w); w.preferences("Appearance"); Preferences p=w.tutorialPreferences();
                JComboBox<?> theme=(JComboBox<?>)field(p,"theme");
                Object delegate=theme.getUI(); Font font=theme.getFont();
                change(f,p,"theme",() -> theme.setSelectedItem(AppTheme.Paper),() -> Ui.theme()==AppTheme.Paper);
                check(theme.getUI()==delegate && theme.getFont().equals(font),"Color-only theme choice preserves delegates, font metrics and control layout");
                JComboBox<?> palette=(JComboBox<?>)field(p,"palette");
                change(f,p,"palette",() -> palette.setSelectedItem("Amber"),() -> w.profileList().getFirst().theme().equals("Amber"));
                JComboBox<?> family=(JComboBox<?>)field(p,"family");
                change(f,p,"font",() -> family.setSelectedItem("Consolas"),() -> w.appearanceSettings().font().family().equals("Consolas"));
                JCheckBox chrome=(JCheckBox)field(p,"window");
                change(f,p,"outline",() -> chrome.doClick(0),() -> !w.appearanceSettings().windowOutline());
                JComboBox<?> cursor=(JComboBox<?>)field(p,"cursorStyle");
                change(f,p,"cursor",() -> cursor.setSelectedItem(Appearance.CursorStyle.FILLED),() -> w.appearanceSettings().cursorStyle()==Appearance.CursorStyle.FILLED);
                JCheckBox startup=(JCheckBox)field(p,"startup");
                change(f,p,"general",() -> startup.doClick(0),w::connectOnStartup);
                return null;
            });
            SwingUtilities.invokeAndWait(run); run.get();
        }
        if (!baseline) slow(root.resolve("slow"));
        System.out.println("PASS immediate Settings production latency / blocked writer / generation and peer rebase: "+checks);
        } finally {
            try (var files=Files.walk(root)) { for (Path path:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
