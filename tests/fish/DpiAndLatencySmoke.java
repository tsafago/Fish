package fish.ui;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import org.tn5250j.TN5250jConstants;
import org.tn5250j.framework.tn5250.Screen5250;
import org.tn5250j.keyboard.KeyMnemonic;
import fish.demo.DemoHost;
import fish.Main;
import fish.model.AppTheme;
import fish.model.Appearance;
import fish.model.ColorRole;
import fish.model.Layout;
import fish.model.StateStore;

/**
 * Automated 100% and 150% DPI 1-physical-pixel border/underline inspection,
 * 0.0px active-pane switch stability, and terminal/layout latency regression checks.
 */
public final class DpiAndLatencySmoke {
    private static int checks;

    private DpiAndLatencySmoke() {}

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        return FxTestSupport.fx(work);
    }

    public static void main(String[] args) throws Exception {
        Ui.install();
        Path root = Files.createTempDirectory(Path.of("build"), "dpi-latency-smoke-");
        DemoHost host = new DemoHost();
        CountDownLatch closed = new CountDownLatch(1);
        try {
            StateStore store = new StateStore(root.resolve("workspace"));
            Workspace workspace = fx(() -> {
                Workspace w = new Workspace(store, Main.demoState(host.port()), Path.of("scripts", "credential-store.ps1"), true);
                w.onClosed(closed::countDown);
                // 968x662 logical at 1.5x = exact 1452x993 physical screenshot dimensions.
                w.setSize(968, 662);
                w.highlightActive(true);
                Appearance app = w.appearanceSettings()
                        .withWindowOutline(true)
                        .withLayout(true, Appearance.PaneInfo.defaults());
                w.appearanceSettings(app);
                FxTestSupport.layout(w);
                w.connectAll();
                return w;
            });
            try {
                FxTestSupport.await(
                        () -> workspace.sessionPanes().size() == 3
                                && workspace.sessionPanes().stream().allMatch(p -> p.connected() && p.screen() != null
                                        && p.screen().getScreenFields().getSize() > 0),
                        12,
                        "All 3 demo 5250 sessions connected with active fields.");

                for (AppTheme theme : List.of(AppTheme.Charcoal, AppTheme.Paper, AppTheme.OLED)) {
                    fx(() -> {
                        Ui.applyTheme(theme);
                        workspace.appearanceSettings(workspace.appearanceSettings().withTheme("", workspace.appearanceSettings().themes()));
                        return null;
                    });
                    verifyStrictOnePixelBordersAndUnderlines(workspace, 1.0, 968, 662, theme.name());
                    verifyStrictOnePixelBordersAndUnderlines(workspace, 1.5, 968, 662, theme.name());
                    // Odd logical dimensions (969x663 -> .5 physical split offsets at 1.5x)
                    verifyStrictOnePixelBordersAndUnderlines(workspace, 1.5, 969, 663, theme.name() + "-odd");
                }

                fx(() -> {
                    Ui.applyTheme(AppTheme.Charcoal);
                    workspace.setSize(968, 662);
                    return null;
                });

                verifyZeroActivePaneShift(workspace, 1.0);
                verifyZeroActivePaneShift(workspace, 1.5);
                verifyKeystrokePasteSelectionAndDoubleBuffer(workspace);
                verifyResizeDividerAndMouseDockLatency(workspace);
            } finally {
                fx(() -> {
                    Ui.setScaleOverride(0);
                    workspace.close(true);
                    return null;
                });
                check(closed.await(5, TimeUnit.SECONDS), "Workspace closes and persists cleanly.");
            }
        } finally {
            host.close();
            Ui.setScaleOverride(0);
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
        }
        System.out.println("PASS DPI & latency: " + checks + " 1px-border, 150%-scanline, 0px-shift, and latency checks.");
        Ui.finishTestRun();
    }

    private static void verifyStrictOnePixelBordersAndUnderlines(
            Workspace w, double scale, int width, int height, String label) throws Exception {
        fx(() -> {
            Ui.setScaleOverride(scale);
            try {
                w.setSize(width, height);
                w.appearanceSettings(w.appearanceSettings().withWindowOutline(true).withLayout(true, Appearance.PaneInfo.defaults()));
                w.highlightActive(true);
                w.activate(w.sessionPanes().getFirst());
                Ui.syncSceneStylesheet(w.scene());
                FxTestSupport.layout(w);
                FxTestSupport.layout(w);

                BufferedImage snap = FxTestSupport.snapshot(w, scale);
                if ("Charcoal".equals(label) && scale == 1.5) {
                    FxTestSupport.writeSnapshot(w.scene().getRoot(), scale, Path.of("build", "dpi-150-three-panes.png"));
                }
                int expectedW = (int) Math.round(width * scale);
                int expectedH = (int) Math.round(height * scale);
                check(Math.abs(snap.getWidth() - expectedW) <= 1 && Math.abs(snap.getHeight() - expectedH) <= 1,
                        "Snapshot physical dimensions match " + expectedW + "x" + expectedH + " at " + scale + "x (" + label + "): got "
                                + snap.getWidth() + "x" + snap.getHeight());

                // 1. WindowChrome bottom border: strictly 1 physical pixel.
                WindowChrome chrome = w.chrome();
                Bounds chromeScene = chrome.localToScene(chrome.getBoundsInLocal());
                int chromeBotDev = (int) Math.round(chromeScene.getMaxY() * scale) - 1;
                int probeX = snap.getWidth() - (int) Math.round(165 * scale);
                assertSinglePixelHorizontalTransition(snap, probeX, chromeBotDev,
                        "WindowChrome bottom border (" + label + "@" + scale + "x)");

                // 2. WindowChrome vertical toolbar separators: strictly 1 physical pixel each.
                @SuppressWarnings("unchecked")
                List<Region> sepBars = (List<Region>) FxTestSupport.field(w, "chromeSeparatorBars");
                check(sepBars.size() == 2, "Workspace has two chrome separator hairlines.");
                for (int i = 0; i < sepBars.size(); i++) {
                    Region bar = sepBars.get(i);
                    Bounds bs = bar.localToScene(bar.getBoundsInLocal());
                    int sepX = (int) Math.round(bs.getMinX() * scale);
                    int sepY = (int) Math.round(bs.getCenterY() * scale);
                    assertSinglePixelVerticalTransition(snap, sepX, sepY,
                            "WindowChrome separator #" + i + " (" + label + "@" + scale + "x)");
                }

                // 3. WorkspaceSplit horizontal and vertical dividers: strictly 1 physical pixel, zero 6px trench, zero parallel double lines.
                List<Workspace.WorkspaceSplit> splits = FxTestSupport.children(w).stream()
                        .filter(Workspace.WorkspaceSplit.class::isInstance)
                        .map(Workspace.WorkspaceSplit.class::cast)
                        .toList();
                check(splits.size() == 2, "3-pane workspace has 1 horizontal and 1 vertical WorkspaceSplit.");
                for (Workspace.WorkspaceSplit split : splits) {
                    Region seam = (Region) FxTestSupport.field(split, "seam");
                    Bounds seamScene = seam.localToScene(seam.getBoundsInLocal());
                    if (split.isHorizontal()) {
                        int seamX = (int) Math.round(seamScene.getMinX() * scale);
                        // Probe across pane 1 / pane 2 header interior, terminal margin, and footer interior.
                        SessionPane topRight = w.sessionPanes().get(1);
                        Region trHeader = (Region) FxTestSupport.field(topRight, "header");
                        Bounds hScene = trHeader.localToScene(trHeader.getBoundsInLocal());
                        int headerMidY = (int) Math.round((hScene.getMinY() + hScene.getHeight() * 0.45) * scale);
                        assertSinglePixelVerticalTransition(snap, seamX, headerMidY,
                                "Horizontal split seam through header (" + label + "@" + scale + "x)");

                        int termMarginY = (int) Math.round((hScene.getMaxY() + 6) * scale);
                        assertSinglePixelVerticalTransition(snap, seamX, termMarginY,
                                "Horizontal split seam through terminal margin (" + label + "@" + scale + "x)");
                    } else {
                        int seamY = (int) Math.round(seamScene.getMinY() * scale);
                        int rightMidX = (int) Math.round((seamScene.getMinX() + seamScene.getWidth() * 0.72) * scale);
                        assertSinglePixelHorizontalTransition(snap, rightMidX, seamY,
                                "Vertical split seam between stacked panes (" + label + "@" + scale + "x)");
                    }
                }

                // 4. Every SessionPane header bottom line and footer top line: strictly 1 physical pixel.
                for (int idx = 0; idx < w.sessionPanes().size(); idx++) {
                    SessionPane pane = w.sessionPanes().get(idx);
                    Region hLine = (Region) FxTestSupport.field(pane, "headerLine");
                    Region fLine = (Region) FxTestSupport.field(pane, "footerLine");
                    Bounds hb = hLine.localToScene(hLine.getBoundsInLocal());
                    Bounds fb = fLine.localToScene(fLine.getBoundsInLocal());
                    int clearX = (int) Math.round((hb.getMinX() + hb.getWidth() * 0.62) * scale);
                    int hy = (int) Math.round(hb.getMinY() * scale);
                    int fy = (int) Math.round(fb.getMinY() * scale);
                    assertSinglePixelHorizontalTransition(snap, clearX, hy,
                            "Pane #" + idx + " header bottom hairline (" + label + "@" + scale + "x)");
                    assertSinglePixelHorizontalTransition(snap, clearX, fy,
                            "Pane #" + idx + " footer top hairline (" + label + "@" + scale + "x)");
                }

                // 5. Outer window outline when enabled vs disabled with active pane outline: max 1 physical pixel, never doubled.
                int midLeftY = (int) Math.round(snap.getHeight() * 0.5);
                assertOuterEdgeSinglePixel(snap, 0, midLeftY, 1, 0,
                        "Outer left window outline (" + label + "@" + scale + "x)");
                assertOuterEdgeSinglePixel(snap, snap.getWidth() - 1, midLeftY, -1, 0,
                        "Outer right window outline (" + label + "@" + scale + "x)");
                int bottomClearX = (int) Math.round(snap.getWidth() * 0.72);
                assertOuterEdgeSinglePixel(snap, bottomClearX, snap.getHeight() - 1, 0, -1,
                        "Outer bottom window outline (" + label + "@" + scale + "x)");

                // 6. 5250 EXTENDED_5250_UNDERLINE scanlines across all 3 panes: strictly 1 physical pixel (never doubled at 1.5x!).
                int underlinedChecked = 0;
                for (int idx = 0; idx < w.sessionPanes().size(); idx++) {
                    SessionPane pane = w.sessionPanes().get(idx);
                    TerminalView view = pane.view();
                    Screen5250 screen = pane.screen();
                    int cols = screen.getColumns(), rows = screen.getRows(), len = screen.getScreenLength();
                    char[] text = new char[len + 1];
                    char[] ext = new char[len + 1];
                    char[] attr = new char[len + 1];
                    screen.GetScreen(text, text.length, TN5250jConstants.PLANE_TEXT);
                    screen.GetScreen(ext, ext.length, TN5250jConstants.PLANE_EXTENDED);
                    screen.GetScreen(attr, attr.length, TN5250jConstants.PLANE_IS_ATTR_PLACE);

                    double originX = (double) FxTestSupport.field(view, "originX");
                    double originY = (double) FxTestSupport.field(view, "originY");
                    double cellW = (double) FxTestSupport.field(view, "cellWidth");
                    double cellH = (double) FxTestSupport.field(view, "cellHeight");
                    int bgRgb = view.getTerminalBackground().getRGB() & 0xffffff;
                    int cursorPos = screen.getCurrentPos() - 1;

                    for (int row = 0; row < rows; row++) {
                        for (int col = 0; col < cols; col++) {
                            int pos = row * cols + col;
                            if (pos == cursorPos || attr[pos] != 0 || text[pos] > ' ') continue;
                            if ((ext[pos] & TN5250jConstants.EXTENDED_5250_UNDERLINE) == 0) continue;
                            if ((ext[pos] & TN5250jConstants.EXTENDED_5250_NON_DSP) != 0) continue;
                            if ((ext[pos] & TN5250jConstants.EXTENDED_5250_COL_SEP) != 0) continue;

                            Point2D cellTop = view.localToScene(originX + (col + 0.5) * cellW, originY + row * cellH);
                            Point2D cellBot = view.localToScene(originX + (col + 0.5) * cellW, originY + (row + 1) * cellH);
                            int px = (int) Math.round(cellTop.getX() * scale);
                            int y0 = Math.max(0, (int) Math.floor(cellTop.getY() * scale) + 1);
                            int y1 = Math.min(snap.getHeight() - 1, (int) Math.ceil(cellBot.getY() * scale));
                            int nonBgRows = 0;
                            int firstHit = -1, lastHit = -1;
                            for (int y = y0; y <= y1; y++) {
                                int rgb = snap.getRGB(px, y) & 0xffffff;
                                if (!closeRgb(rgb, bgRgb, 6)) {
                                    nonBgRows++;
                                    if (firstHit < 0) firstHit = y;
                                    lastHit = y;
                                }
                            }
                            check(nonBgRows == 1 && firstHit == lastHit,
                                    "5250 underline must be strictly 1 physical pixel in pane #" + idx
                                            + " row " + row + " col " + col + " (" + label + "@" + scale + "x): got "
                                            + nonBgRows + "px [" + firstHit + ".." + lastHit + "]");
                            underlinedChecked++;
                            break; // Checked this row's underlined input field
                        }
                    }
                }
                check(underlinedChecked >= 3, "Inspected underlined 5250 fields across all 3 panes (" + underlinedChecked + ").");
            } finally {
                Ui.setScaleOverride(0);
                Ui.syncSceneStylesheet(w.scene());
                FxTestSupport.layout(w);
            }
            return null;
        });
    }

    private static void assertSinglePixelHorizontalTransition(BufferedImage img, int x, int expectedY, String context) {
        int topRef = img.getRGB(x, Math.max(0, expectedY - 4)) & 0xffffff;
        int botRef = img.getRGB(x, Math.min(img.getHeight() - 1, expectedY + 4)) & 0xffffff;
        List<Integer> lineRows = new ArrayList<>();
        for (int y = Math.max(0, expectedY - 3); y <= Math.min(img.getHeight() - 1, expectedY + 3); y++) {
            int rgb = img.getRGB(x, y) & 0xffffff;
            if (!closeRgb(rgb, topRef, 4) && !closeRgb(rgb, botRef, 4)) {
                lineRows.add(y);
            }
        }
        check(lineRows.size() == 1,
                context + " expected 1 physical pixel line near y=" + expectedY + " (x=" + x + "), got " + lineRows.size() + " rows: " + lineRows);
    }

    private static void assertSinglePixelVerticalTransition(BufferedImage img, int expectedX, int y, String context) {
        int leftRef = img.getRGB(Math.max(0, expectedX - 4), y) & 0xffffff;
        int rightRef = img.getRGB(Math.min(img.getWidth() - 1, expectedX + 4), y) & 0xffffff;
        List<Integer> lineCols = new ArrayList<>();
        for (int x = Math.max(0, expectedX - 3); x <= Math.min(img.getWidth() - 1, expectedX + 3); x++) {
            int rgb = img.getRGB(x, y) & 0xffffff;
            if (!closeRgb(rgb, leftRef, 4) && !closeRgb(rgb, rightRef, 4)) {
                lineCols.add(x);
            }
        }
        check(lineCols.size() == 1,
                context + " expected 1 physical pixel line near x=" + expectedX + " (y=" + y + "), got " + lineCols.size() + " cols: " + lineCols);
    }

    private static void assertOuterEdgeSinglePixel(BufferedImage img, int edgeX, int edgeY, int dx, int dy, String context) {
        int interior = img.getRGB(edgeX + dx * 4, edgeY + dy * 4) & 0xffffff;
        int borderPixels = 0;
        for (int step = 0; step <= 3; step++) {
            int rgb = img.getRGB(edgeX + dx * step, edgeY + dy * step) & 0xffffff;
            if (!closeRgb(rgb, interior, 4)) borderPixels++;
        }
        check(borderPixels == 1, context + " expected strictly 1 physical outer border pixel, got " + borderPixels);
    }

    private static boolean closeRgb(int a, int b, int tolerance) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) <= tolerance
                && Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) <= tolerance
                && Math.abs((a & 255) - (b & 255)) <= tolerance;
    }

    private static void verifyZeroActivePaneShift(Workspace w, double scale) throws Exception {
        fx(() -> {
            Ui.setScaleOverride(scale);
            try {
                Ui.syncSceneStylesheet(w.scene());
                for (boolean winOutline : List.of(true, false)) {
                    w.appearanceSettings(w.appearanceSettings().withWindowOutline(winOutline).withLayout(true, Appearance.PaneInfo.defaults()));
                    w.highlightActive(true);
                    List<SessionPane> panes = w.sessionPanes();
                    w.activate(panes.get(0));
                    FxTestSupport.layout(w);
                    FxTestSupport.layout(w);
                    double[] baseline = capturePaneSceneBounds(panes);

                    for (int round = 0; round < 2; round++) {
                        for (SessionPane target : panes) {
                            w.activate(target);
                            FxTestSupport.layout(w);
                            FxTestSupport.layout(w);
                            double[] after = capturePaneSceneBounds(panes);
                            double maxDelta = 0;
                            for (int i = 0; i < baseline.length; i++) {
                                maxDelta = Math.max(maxDelta, Math.abs(baseline[i] - after[i]));
                            }
                            check(maxDelta < 1e-6,
                                    "Switching active pane at " + scale + "x (windowOutline=" + winOutline
                                            + ") must produce 0.0px shift; maxDelta=" + maxDelta);
                        }
                    }
                }
            } finally {
                Ui.setScaleOverride(0);
                Ui.syncSceneStylesheet(w.scene());
                FxTestSupport.layout(w);
            }
            return null;
        });
    }

    private static double[] capturePaneSceneBounds(List<SessionPane> panes) throws Exception {
        double[] coords = new double[panes.size() * 16];
        int k = 0;
        for (SessionPane pane : panes) {
            Region header = (Region) FxTestSupport.field(pane, "header");
            Region footer = (Region) FxTestSupport.field(pane, "footer");
            for (Node n : List.of(pane, header, pane.view(), footer)) {
                Bounds b = n.localToScene(n.getBoundsInLocal());
                coords[k++] = b.getMinX();
                coords[k++] = b.getMinY();
                coords[k++] = b.getWidth();
                coords[k++] = b.getHeight();
            }
        }
        return coords;
    }

    private static void verifyKeystrokePasteSelectionAndDoubleBuffer(Workspace w) throws Exception {
        fx(() -> {
            Ui.setScaleOverride(1.5);
            try {
                Ui.syncSceneStylesheet(w.scene());
                FxTestSupport.layout(w);
                SessionPane pane = w.sessionPanes().getFirst();
                w.activate(pane);
                TerminalView view = pane.view();
                view.forceSurfaceRender();

                // Double-buffered fxPixels must be fully populated and opaque.
                int[] pixels = view.surfacePixelsForTest();
                check(pixels != null && pixels.length > 10_000, "TerminalView PixelBuffer allocated at 1.5x.");
                boolean anyTransparent = false;
                for (int px : pixels) {
                    if ((px >>> 24) != 255) { anyTransparent = true; break; }
                }
                check(!anyTransparent, "Double-buffered fxPixels never contains transparent/erased texels.");

                // 100 rapid keystrokes + 200-char paste latency check.
                long t0 = System.nanoTime();
                for (int i = 0; i < 50; i++) {
                    check(pane.type((char) ('A' + (i % 26))), "Rapid character input #" + i);
                    check(pane.send(KeyMnemonic.BACK_SPACE), "Rapid backspace #" + i);
                }
                pane.paste("FISH5250".repeat(25)); // 200 chars (clamped cleanly to field capacity without synchronous per-char render)
                pane.send(KeyMnemonic.ERASE_FIELD);
                long inputElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                check(inputElapsedMs < 400, "100 keystrokes + 200-char paste completed in " + inputElapsedMs + "ms (< 400ms budget).");

                // Warm frame, then verify glyphMasks cache hit rate across selection + cursor renders.
                view.forceSurfaceRender();
                view.resetGlyphStats();
                for (int s = 0; s < 10; s++) {
                    view.setSelectionRange(s, s + 12);
                    view.forceSurfaceRender();
                }
                view.resetSelection();
                view.forceSurfaceRender();
                long hits = view.glyphMaskHits();
                long misses = view.glyphMaskMisses();
                check(hits > 0 && misses == 0,
                        "Selection/cursor repaints achieve 100% glyphMasks hit rate (hits=" + hits + ", misses=" + misses + ").");

                // Click without drag must NEVER flash a 1-cell selection box; sub-cell jitter must not select; cross-cell drag selects.
                double originX = (double) FxTestSupport.field(view, "originX");
                double originY = (double) FxTestSupport.field(view, "originY");
                double cellW = (double) FxTestSupport.field(view, "cellWidth");
                double cellH = (double) FxTestSupport.field(view, "cellHeight");
                double x0 = originX + 5.5 * cellW;
                double y0 = originY + 3.5 * cellH;

                fireMouse(view, MouseEvent.MOUSE_PRESSED, x0, y0, true);
                check((int) FxTestSupport.field(view, "anchor") == -1 && (int) FxTestSupport.field(view, "end") == -1,
                        "MOUSE_PRESSED alone never flashes a 1-cell selection.");
                fireMouse(view, MouseEvent.MOUSE_DRAGGED, x0 + 1.0, y0 + 1.0, true);
                check((int) FxTestSupport.field(view, "anchor") == -1,
                        "Sub-threshold mouse jitter within the same cell does not trigger selection flicker.");
                fireMouse(view, MouseEvent.MOUSE_DRAGGED, x0 + cellW * 6, y0, true);
                check((int) FxTestSupport.field(view, "anchor") >= 0 && (int) FxTestSupport.field(view, "end") > (int) FxTestSupport.field(view, "anchor"),
                        "Dragging across cells establishes selection cleanly.");
                fireMouse(view, MouseEvent.MOUSE_RELEASED, x0 + cellW * 6, y0, false);

                // Clicking clears active selection cleanly without starting a new selection.
                fireMouse(view, MouseEvent.MOUSE_PRESSED, x0, y0, true);
                check((int) FxTestSupport.field(view, "anchor") == -1 && (int) FxTestSupport.field(view, "end") == -1,
                        "Clicking clears prior selection immediately.");
                fireMouse(view, MouseEvent.MOUSE_RELEASED, x0, y0, false);
            } finally {
                Ui.setScaleOverride(0);
                Ui.syncSceneStylesheet(w.scene());
                FxTestSupport.layout(w);
            }
            return null;
        });
    }

    private static void fireMouse(Node target, javafx.event.EventType<MouseEvent> type, double x, double y, boolean primaryDown) {
        Point2D scene = target.localToScene(x, y);
        MouseEvent event = new MouseEvent(
                type,
                x, y,
                scene.getX(), scene.getY(),
                MouseButton.PRIMARY,
                1,
                false, false, false, false,
                primaryDown, false, false,
                true, false, true, null);
        Event.fireEvent(target, event);
    }

    private static void verifyResizeDividerAndMouseDockLatency(Workspace w) throws Exception {
        fx(() -> {
            Ui.setScaleOverride(1.5);
            try {
                Ui.syncSceneStylesheet(w.scene());
                w.setSize(968, 662);
                FxTestSupport.layout(w);

                long statesBefore = w.stateBuildCountForTest();
                long resizeStart = System.nanoTime();
                for (int step = 1; step <= 40; step++) {
                    w.setSize(968 - step * 3, 662 - step * 2);
                }
                w.setSize(968, 662);
                long resizeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - resizeStart);
                check(w.stateBuildCountForTest() == statesBefore,
                        "40-step live window resize performs 0 intermediate StateStore.State builds.");
                check(resizeMs < 800, "40-step window resize completed in " + resizeMs + "ms (< 20ms/step).");

                Workspace.WorkspaceSplit rootSplit = FxTestSupport.children(w).stream()
                        .filter(Workspace.WorkspaceSplit.class::isInstance)
                        .map(Workspace.WorkspaceSplit.class::cast)
                        .filter(Workspace.WorkspaceSplit::isHorizontal)
                        .findFirst().orElseThrow();
                int origLoc = rootSplit.getDividerLocation();
                long divStart = System.nanoTime();
                for (int step = -15; step <= 15; step++) {
                    w.resizeDividerLive(rootSplit, origLoc + step * 4);
                }
                w.resizeDividerLive(rootSplit, origLoc);
                long divMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - divStart);
                check(w.stateBuildCountForTest() == statesBefore,
                        "30-step live divider drag performs 0 intermediate StateStore.State builds.");
                check(divMs < 400, "30-step live divider drag completed in " + divMs + "ms.");

                // 60-step in-scene mouse-drag dock preview + commit + undo.
                Layout initialLayout = w.currentLayout();
                UUID sourceId = w.sessionPanes().get(0).id();
                SessionPane targetPane = w.sessionPanes().get(1);
                Bounds tb = targetPane.localToScene(targetPane.getBoundsInLocal());
                long dockStart = System.nanoTime();
                for (int i = 0; i < 60; i++) {
                    double frac = (i % 20) / 20.0;
                    double sx = tb.getMinX() + tb.getWidth() * (0.15 + 0.7 * frac);
                    double sy = tb.getMinY() + tb.getHeight() * 0.5;
                    check(w.previewDockAtScene(sourceId, sx, sy), "Mouse-drag dock preview valid at step #" + i);
                }
                long dockPreviewMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dockStart);
                check(dockPreviewMs < 400, "60-step mouse-drag dock preview completed in " + dockPreviewMs + "ms.");

                check(w.commitDockAtScene(sourceId, tb.getCenterX(), tb.getCenterY()),
                        "Mouse-drag dock commit swaps panes cleanly.");
                w.clearPreviews();
                FxTestSupport.layout(w);
                check(!w.currentLayout().equals(initialLayout), "Committed mouse-drag dock updated workspace layout.");
                w.undoArrangement();
                FxTestSupport.layout(w);
                check(w.currentLayout().equals(initialLayout), "Undo restores exact layout after mouse-drag dock.");
            } finally {
                Ui.setScaleOverride(0);
                Ui.syncSceneStylesheet(w.scene());
                FxTestSupport.layout(w);
            }
            return null;
        });
    }
}
