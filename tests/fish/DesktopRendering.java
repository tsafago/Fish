package fish;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javax.imageio.ImageIO;
import fish.demo.DemoHost;
import fish.model.Layout;
import fish.model.StateStore;
import fish.ui.*;

/** Live JavaFX desktop rendering, resize, maximize/restore, split divider, and poisoned-buffer regressions. */
public final class DesktopRendering {
    private static final boolean BASELINE = Boolean.getBoolean("fish.renderBaseline");
    private static int checks;
    private DesktopRendering() {}

    public static void main(String[] args) throws Exception {
        Ui.install();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((thread,error) -> { errors.add(error); error.printStackTrace(); });
        Path directory = Files.createTempDirectory(Path.of("build"),"render-test-");
        DemoHost host = new DemoHost();
        StateStore store = new StateStore(directory);
        CountDownLatch closed=new CountDownLatch(1);
        Workspace window = edt(() -> {
            Workspace frame = new Workspace(store,Main.demoState(host.port()),Path.of("scripts","credential-store.ps1"),true);
            frame.onClosed(closed::countDown);
            String effect=System.getProperty("fish.effect","None");
            if (!effect.equals("None"))
                for (var profile : frame.profileList()) frame.putProfile(profile.withDisplay(profile.theme(),effect));
            frame.stage().setAlwaysOnTop(true);
            frame.setVisible(true);
            frame.connectAll();
            return frame;
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (!edt(() -> window.sessionPanes().stream().allMatch(p -> p.connected() && p.screen().getScreenFields().getSize() > 0))) {
                if (System.nanoTime() > deadline) throw new AssertionError("Demo sessions did not become ready.");
                Thread.sleep(25);
            }
            run(window);
            require(host.connectionCount() == 3,"Resizing, exposing and opening menus never reconnects a session.");
            require(host.failures().isEmpty(),"No synthetic protocol failures.");
            require(errors.isEmpty(),"No uncaught FX or background failures: " + errors);
        } finally {
            edt(() -> { window.close(true); return null; });
            require(closed.await(5,TimeUnit.SECONDS),"Workspace save and lock release finish.");
            host.close();
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(directory);
        }
        Ui.finishTestRun();
    }

    private static void run(Workspace window) throws Exception {
        edt(() -> {
            Rectangle2D screen = Screen.getPrimary().getVisualBounds();
            Stage st = window.stage();
            st.setX(screen.getMinX() + 35);
            st.setY(screen.getMinY() + 35);
            window.setSize((int)Math.min(1100,screen.getWidth() - 90),(int)Math.min(760,screen.getHeight() - 90));
            window.toFront();
            return null;
        });
        int[] dimensions = edt(() -> window.sessionPanes().stream().mapToInt(p -> p.screen().getScreenLength()).toArray());
        boolean clean = edt(() -> {
            for (SessionPane pane : window.sessionPanes()) {
                TerminalView view = pane.view();
                BufferedImage poison = render(view,Color.MAGENTA), fresh = render(view,Ui.BACKGROUND);
                for (int y = 0; y < fresh.getHeight(); y++)
                    for (int x = 0; x < fresh.getWidth(); x++)
                        if (poison.getRGB(x,y) != fresh.getRGB(x,y)) return false;
            }
            return true;
        });
        require(clean,"Opaque terminal redraw is independent of every previous pixel in the shared buffer.");
        edt(() -> {
            SessionPane pane = window.sessionPanes().getFirst();
            BufferedImage before = render(pane.view(),Ui.BACKGROUND);
            require(pane.type('7'),"Field-aware input remains available with the cached renderer.");
            BufferedImage after = render(pane.view(),Ui.BACKGROUND);
            require(!Arrays.equals(before.getRGB(0,0,before.getWidth(),before.getHeight(),null,0,before.getWidth()),
                    after.getRGB(0,0,after.getWidth(),after.getHeight(),null,0,after.getWidth())),
                    "An edited field invalidates the cached glyphs immediately.");
            require(pane.send(org.tn5250j.keyboard.KeyMnemonic.LEFT)
                            && pane.send(org.tn5250j.keyboard.KeyMnemonic.ERASE_EOF),
                    "Host field-editing keys remain usable.");
            BufferedImage erased = render(pane.view(),Ui.BACKGROUND);
            require(Arrays.equals(before.getRGB(0,0,before.getWidth(),before.getHeight(),null,0,before.getWidth()),
                    erased.getRGB(0,0,erased.getWidth(),erased.getHeight(),null,0,erased.getWidth())),
                    "Field-erasing keys invalidate glyphs and restore the exact original display.");
            return null;
        });

        List<Long> paints = new CopyOnWriteArrayList<>();
        List<Long> lags = new CopyOnWriteArrayList<>();
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        try {
            sampler.scheduleAtFixedRate(() -> {
                long sent = System.nanoTime();
                Ui.runLater(() -> lags.add(System.nanoTime() - sent));
            },0,10,TimeUnit.MILLISECONDS);
            int origW = edt(window::getWidth), origH = edt(window::getHeight);
            for (int step = 1; step <= 16; step++) {
                final int nw = origW - step * 8;
                final int nh = origH - step * 5;
                edt(() -> {
                    long start = System.nanoTime();
                    window.setSize(nw, nh);
                    FxTestSupport.layout(window);
                    for (SessionPane p : window.sessionPanes()) p.view().forceSurfaceRender();
                    paints.add(System.nanoTime() - start);
                    return null;
                });
                Thread.sleep(10);
            }
            require(edt(window::getWidth) < origW - 60 && edt(window::getHeight) < origH - 40,
                    "Window resize changes both dimensions cleanly.");
            List<Workspace.WorkspaceSplit> splits = edt(() -> FxTestSupport.children(window).stream()
                    .filter(Workspace.WorkspaceSplit.class::isInstance).map(Workspace.WorkspaceSplit.class::cast).toList());
            for (Workspace.WorkspaceSplit split : splits) {
                int originalDivider = edt(split::getDividerLocation);
                boolean horizontal = split.isHorizontal();
                int delta = horizontal ? 60 : 45;
                edt(() -> { window.resizeDivider(split, originalDivider + delta); FxTestSupport.layout(window); return null; });
                require(Math.abs(edt(split::getDividerLocation) - (originalDivider + delta)) <= 3,
                        "Split-divider resize changes its location continuously.");
                edt(() -> { window.resizeDivider(split, originalDivider); FxTestSupport.layout(window); return null; });
                require(Math.abs(edt(split::getDividerLocation) - originalDivider) <= 3,
                        "Divider can be resized back without drift.");
            }
        } finally {
            sampler.shutdownNow();
        }
        double paint95 = percentile(paints,95), lag95 = percentile(lags,95);
        System.out.printf(Locale.ROOT,
                "%s resize (%s): scale=%.2f, paints=%d, paint median=%.2fms p95=%.2fms max=%.2fms, FX lag p95=%.2fms max=%.2fms%n",
                BASELINE ? "BASELINE" : "MEASURED",System.getProperty("fish.effect","None"),
                edt(() -> window.stage().getOutputScaleX()),
                paints.size(),percentile(paints,50),paint95,percentile(paints,100),lag95,percentile(lags,100));
        require(paints.size() >= 8,"Resize measurement includes real layout/surface paints.");
        require(paint95 < 60 && lag95 < 100,"Resize p95 paint and FX response remain smooth.");

        windowControls(window);
        List<SessionPane> order = edt(window::sessionPanes);
        edt(() -> { window.dock(order.get(0).id(),order.get(1).id(),Layout.Dock.CENTER); return null; });
        await(() -> window.sessionPanes().get(1) == order.get(0),"Pane swap preserves live sessions.");
        edt(() -> { window.dock(order.get(0).id(),order.get(1).id(),Layout.Dock.CENTER); return null; });
        await(() -> window.sessionPanes().equals(order),"Swapping back restores the pinned composition.");
        require(edt(() -> Arrays.equals(dimensions,window.sessionPanes().stream().mapToInt(p -> p.screen().getScreenLength()).toArray())),
                "Continuous window/divider resize and maximize/restore preserve every host presentation space.");
        BufferedImage capture = edt(() -> FxTestSupport.snapshot(window,1.0));
        ImageIO.write(capture,"png",Path.of("build","desktop-render-" + (BASELINE ? "baseline-" : "")
                +System.getProperty("fish.effect","None").toLowerCase(Locale.ROOT).replace(' ','-')+".png").toFile());
        System.out.println(BASELINE ? "Baseline diagnostics recorded." : "PASS " + checks + " JavaFX desktop rendering, resize and StageStyle.EXTENDED checks.");
    }

    private static void windowControls(Workspace window) throws Exception {
        require(edt(() -> window.stage().getStyle() == StageStyle.EXTENDED),"Workspace uses native JavaFX StageStyle.EXTENDED.");
        Button maxBtn = button(window,"Maximize window");
        edt(() -> { maxBtn.fire(); return null; });
        await(() -> window.stage().isMaximized(),"Maximize button maximizes stage.");
        Button restoreBtn = button(window,"Restore window");
        edt(() -> { restoreBtn.fire(); return null; });
        await(() -> !window.stage().isMaximized(),"Restore button restores normal state.");
        Button minBtn = button(window,"Minimize window");
        edt(() -> { minBtn.fire(); return null; });
        await(() -> window.stage().isIconified(),"Minimize button iconifies stage.");
        edt(() -> { window.stage().setIconified(false); window.toFront(); return null; });
        await(() -> !window.stage().isIconified() && window.isShowing(),"Stage returns after minimizing.");
        Button close = button(window,"Close window");
        require(edt(close::isFocusTraversable),"Custom window buttons participate in keyboard focus.");
    }

    private static Button button(Workspace window,String name) throws Exception {
        return edt(() -> FxTestSupport.children(window).stream()
                .filter(Node.class::isInstance)
                .filter(b -> b instanceof Button btn && name.equals(btn.getAccessibleText()))
                .map(b -> (Button)b).findFirst().orElseThrow());
    }

    private static void await(Callable<Boolean> action,String text) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!edt(action)) {
            if (System.nanoTime() > deadline) { require(false,text); return; }
            Thread.sleep(25);
        }
        require(true,text);
    }

    private static BufferedImage render(TerminalView view,Color background) {
        int w = Math.max(1,(int)Math.round(view.getWidth()));
        int h = Math.max(1,(int)Math.round(view.getHeight()));
        BufferedImage image = new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(background); g.fillRect(0,0,w,h);
        view.paintComponent(g);
        g.dispose();
        return image;
    }

    private static double percentile(List<Long> values,int percentile) {
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        return sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1,(int)Math.ceil(sorted.length * percentile / 100.0) - 1)] / 1_000_000.0;
    }

    private static void require(boolean condition,String text) {
        checks++;
        if (BASELINE) System.out.println((condition ? "OK " : "REPRODUCED ") + text);
        else if (!condition) throw new AssertionError(text);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        return FxTestSupport.fx(action);
    }
}
