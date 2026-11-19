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
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderButtonType;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
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
                verifyHeaderButtonSingleFireAndDialogClose(workspace);
                verifyClickInPaneFocusCursorAndImmediateInput(workspace);
                verifyTutorialsHighlightAndPositioning(workspace);
                verifySmoothCrispContinuousResize(workspace);
                verifyPolish090Features(workspace, root);
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
            Ui.finishTestRun();
        }
        System.out.println("PASS DPI & latency: " + checks + " 1px-border, 150%-scanline, 0px-shift, 0.9.0 polish, and latency checks.");
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

                // 2. WindowChrome split-pill join separator: strictly 1 physical pixel, with no separator before Profile selector.
                @SuppressWarnings("unchecked")
                List<Region> sepBars = (List<Region>) FxTestSupport.field(w, "chromeSeparatorBars");
                check(sepBars.size() == 1, "Workspace has only the single split-pill join separator hairline.");
                for (int i = 0; i < sepBars.size(); i++) {
                    Region bar = sepBars.get(i);
                    Bounds bs = bar.localToScene(bar.getBoundsInLocal());
                    int sepX = (int) Math.round(bs.getMinX() * scale);
                    int sepY = (int) Math.round(bs.getCenterY() * scale);
                    assertSinglePixelVerticalTransition(snap, sepX, sepY,
                            "WindowChrome separator #" + i + " (" + label + "@" + scale + "x)");
                    Button add=(Button)w.chrome().lookup(".fish-chrome-pill-add");
                    add.arm();
                    try {
                        BufferedImage armed=FxTestSupport.snapshot(w,scale);
                        int focus=Ui.color(ColorRole.FOCUS).getRGB();
                        check(closeRgb(armed.getRGB(sepX,sepY),focus,4)
                                        && !closeRgb(armed.getRGB(sepX-1,sepY),focus,4)
                                        && !closeRgb(armed.getRGB(sepX+1,sepY),focus,4)
                                        && bs.equals(bar.localToScene(bar.getBoundsInLocal())),
                                "Split pill highlights exactly its existing join pixel without moving controls: "+label+"@"+scale);
                    } finally { add.disarm(); }
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

                // Pane information flows into the terminal without a divider stripe.
                for (int idx = 0; idx < w.sessionPanes().size(); idx++) {
                    SessionPane pane = w.sessionPanes().get(idx);
                    Region hLine = (Region) FxTestSupport.field(pane, "header");
                    Region fLine = (Region) FxTestSupport.field(pane, "footer");
                    Bounds hb = hLine.localToScene(hLine.getBoundsInLocal());
                    Bounds fb = fLine.localToScene(fLine.getBoundsInLocal());
                    int clearX = (int) Math.round((hb.getMinX() + hb.getWidth() * 0.62) * scale);
                    int hy = (int) Math.round(hb.getMaxY() * scale);
                    int fy = (int) Math.round(fb.getMinY() * scale);
                    for (int y:new int[]{hy-1,fy}) check((snap.getRGB(clearX,y)&0xffffff)!=(Ui.LINE.getRGB()&0xffffff),
                            "No header/footer divider on pane "+idx+" at "+scale);
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
                scene.getX(), scene.getY(),
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

    private static void verifyHeaderButtonSingleFireAndDialogClose(Workspace w) throws Exception {
        fx(() -> {
            WindowChrome chrome = w.chrome();
            Button maxBtn = chrome.maximizeButton();
            Stage stage = w.stage();
            check(HeaderBar.getButtonType(maxBtn) == HeaderButtonType.MAXIMIZE,
                    "Maximize button retains HeaderButtonType.MAXIMIZE for native Snap Layouts.");
            stage.setMaximized(false);
            FxTestSupport.layout(w);

            // Real primary mouse click (MOUSE_PRESSED + MOUSE_RELEASED) must NOT double-toggle back to false.
            double cx = maxBtn.getWidth() * 0.5;
            double cy = maxBtn.getHeight() * 0.5;
            fireMouse(maxBtn, MouseEvent.MOUSE_PRESSED, cx, cy, true);
            fireMouse(maxBtn, MouseEvent.MOUSE_RELEASED, cx, cy, false);
            check(stage.isMaximized(),
                    "Primary mouse click on maximize button maximizes stage without shrinking back.");

            // Second primary mouse click restores stage cleanly.
            fireMouse(maxBtn, MouseEvent.MOUSE_PRESSED, cx, cy, true);
            fireMouse(maxBtn, MouseEvent.MOUSE_RELEASED, cx, cy, false);
            check(!stage.isMaximized(),
                    "Second primary mouse click on maximize button restores stage.");

            // Programmatic/keyboard fire() still works cleanly without mouse events.
            maxBtn.fire();
            check(stage.isMaximized(), "Keyboard/programmatic maximizeBtn.fire() maximizes stage.");
            maxBtn.fire();
            check(!stage.isMaximized(), "Second maximizeBtn.fire() restores stage.");

            // Verify Ui.decorateDialog close button also fires exactly once on primary mouse click AND .fire().
            int[] closeCalls = {0};
            Stage dlg = new Stage();
            BorderPane content = new BorderPane();
            Scene dlgScene = Ui.decorateDialog(dlg, content, () -> closeCalls[0]++, 320, 180);
            Region wrapped = (Region) dlgScene.getRoot();
            wrapped.applyCss();
            wrapped.layout();
            Button dlgClose = FxTestSupport.children(wrapped).stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(b -> HeaderBar.getButtonType(b) == HeaderButtonType.CLOSE)
                    .findFirst()
                    .orElseThrow();
            fireMouse(dlgClose, MouseEvent.MOUSE_PRESSED, dlgClose.getWidth() * 0.5, dlgClose.getHeight() * 0.5, true);
            fireMouse(dlgClose, MouseEvent.MOUSE_RELEASED, dlgClose.getWidth() * 0.5, dlgClose.getHeight() * 0.5, false);
            check(closeCalls[0] == 1, "Dialog HeaderBar close button invokes onClose once on primary mouse click, got " + closeCalls[0]);
            dlgClose.fire();
            check(closeCalls[0] == 2, "Dialog HeaderBar close button invokes onClose once on .fire(), got " + closeCalls[0]);
            return null;
        });
    }

    private static void verifyClickInPaneFocusCursorAndImmediateInput(Workspace w) throws Exception {
        fx(() -> {
            Ui.setScaleOverride(1.5);
            try {
                Ui.syncSceneStylesheet(w.scene());
                w.setSize(968, 662);
                FxTestSupport.layout(w);

                SessionPane pane0 = w.sessionPanes().get(0);
                SessionPane pane1 = w.sessionPanes().get(1);
                ScrollPane scroll0 = (ScrollPane) FxTestSupport.field(pane0, "scroll");
                check(!scroll0.isFocusTraversable(),
                        "SessionPane ScrollPane is non-focus-traversable so clicks never steal focus from TerminalView.");

                // Start with pane1 active and focused so clicking pane0 must switch active pane AND focus pane0.view().
                w.activate(pane1);
                pane1.view().requestFocus();
                check(w.active() == pane1, "Precondition: pane1 is active before clicking pane0.");

                Screen5250 screen0 = pane0.screen();
                var firstField = screen0.getScreenFields().getFields()[0];
                int fieldStart = firstField.startPos();
                int clickOffset = Math.min(3, Math.max(1, firstField.getLength() - 2));
                int targetPos = fieldStart + clickOffset;
                int targetRow = targetPos / screen0.getColumns();
                int targetCol = targetPos % screen0.getColumns();

                TerminalView view0 = pane0.view();
                view0.forceSurfaceRender();
                double originX = (double) FxTestSupport.field(view0, "originX");
                double originY = (double) FxTestSupport.field(view0, "originY");
                double cellW = (double) FxTestSupport.field(view0, "cellWidth");
                double cellH = (double) FxTestSupport.field(view0, "cellHeight");
                double clickX = originX + (targetCol + 0.5) * cellW;
                double clickY = originY + (targetRow + 0.5) * cellH;

                // Dispatch primary click through scene -> ScrollPane -> TerminalView.
                fireMouse(view0, MouseEvent.MOUSE_PRESSED, clickX, clickY, true);
                check(w.active() == pane0, "Clicking inside inactive pane activates it immediately on MOUSE_PRESSED.");
                check(w.scene().getFocusOwner() == view0 && view0.hasTerminalFocus(),
                        "TerminalView keeps scene focus on MOUSE_PRESSED (ScrollPane never steals focus).");
                check(screen0.isCursorActive(), "5250 cursor is active immediately on MOUSE_PRESSED.");
                check(screen0.getCurrentRow() - 1 == targetRow && screen0.getCurrentCol() - 1 == targetCol,
                        "5250 cursor moves immediately on MOUSE_PRESSED to clicked (" + targetRow + "," + targetCol
                                + "), got (" + (screen0.getCurrentRow() - 1) + "," + (screen0.getCurrentCol() - 1) + ").");

                fireMouse(view0, MouseEvent.MOUSE_RELEASED, clickX, clickY, false);
                check(w.scene().getFocusOwner() == view0 && view0.hasTerminalFocus(),
                        "TerminalView retains scene focus after MOUSE_RELEASED without pressing Tab.");

                // Verify the cursor is visibly painted inside the clicked cell immediately.
                pane0.send(KeyMnemonic.ERASE_FIELD);
                fireMouse(view0, MouseEvent.MOUSE_PRESSED, clickX, clickY, true);
                fireMouse(view0, MouseEvent.MOUSE_RELEASED, clickX, clickY, false);
                view0.forceSurfaceRender();
                int[] pixels = view0.surfacePixelsForTest();
                int surfW = (int) FxTestSupport.field(view0, "surfaceW");
                double scaleX = (double) FxTestSupport.field(view0, "geometryDpiX");
                double scaleY = (double) FxTestSupport.field(view0, "geometryDpiY");
                int bgRgb = view0.getTerminalBackground().getRGB() & 0xffffff;
                int cX0 = (int) Math.rint(originX * scaleX) + (int) Math.rint(targetCol * cellW * scaleX);
                int cX1 = (int) Math.rint(originX * scaleX) + (int) Math.rint((targetCol + 1) * cellW * scaleX);
                int cY0 = (int) Math.rint(originY * scaleY) + (int) Math.rint(targetRow * cellH * scaleY);
                int cY1 = (int) Math.rint(originY * scaleY) + (int) Math.rint((targetRow + 1) * cellH * scaleY);
                int litCursorPixels = 0;
                for (int py = cY0; py < cY1; py++) {
                    for (int px = cX0; px < cX1; px++) {
                        if ((pixels[py * surfW + px] & 0xffffff) != bgRgb) litCursorPixels++;
                    }
                }
                check(litCursorPixels > 0, "Visible cursor ink is rendered immediately inside clicked cell without pressing Tab.");

                // Immediately send KeyEvents to scene.getFocusOwner() with ZERO Tab key press.
                Node focusOwner = w.scene().getFocusOwner();
                Event.fireEvent(focusOwner, new KeyEvent(KeyEvent.KEY_TYPED, "Z", "", KeyCode.UNDEFINED, false, false, false, false));
                Event.fireEvent(focusOwner, new KeyEvent(KeyEvent.KEY_TYPED, "9", "", KeyCode.UNDEFINED, false, false, false, false));
                char[] textPlane = new char[screen0.getScreenLength()];
                screen0.GetScreen(textPlane, textPlane.length, TN5250jConstants.PLANE_TEXT);
                check(textPlane[targetPos] == 'Z' && textPlane[targetPos + 1] == '9',
                        "Immediate typing after click writes directly at clicked cell without Tab: got '"
                                + textPlane[targetPos] + textPlane[targetPos + 1] + "'.");
                Event.fireEvent(focusOwner, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.BACK_SPACE, false, false, false, false));
                screen0.GetScreen(textPlane, textPlane.length, TN5250jConstants.PLANE_TEXT);
                check(textPlane[targetPos] == 'Z' && (textPlane[targetPos + 1] == 0 || textPlane[targetPos + 1] == ' '),
                        "Immediate Backspace host key works after click without Tab.");
                pane0.send(KeyMnemonic.ERASE_FIELD);
            } finally {
                Ui.setScaleOverride(0);
                Ui.syncSceneStylesheet(w.scene());
                FxTestSupport.layout(w);
            }
            return null;
        });
    }

    private static void verifyTutorialsHighlightAndPositioning(Workspace w) throws Exception {
        fx(() -> {
            w.setSize(1120, 720);
            FxTestSupport.layout(w);

            // Top-bar menu targets resolve to individual .menu-button nodes rather than the full MenuBar.
            Node editMenuNode = w.chrome().tutorialMenuTarget("Edit", "Settings");
            Node helpMenuNode = w.chrome().tutorialMenuTarget("Help", "Fish manual");
            check(editMenuNode != null && editMenuNode != w.chrome().menuBar(),
                    "tutorialMenuTarget('Edit') resolves to specific Edit .menu-button rather than full MenuBar.");
            check(helpMenuNode != null && helpMenuNode != w.chrome().menuBar(),
                    "tutorialMenuTarget('Help') resolves to specific Help .menu-button rather than full MenuBar.");
            Bounds editBounds = editMenuNode.localToScene(editMenuNode.getBoundsInLocal());
            Bounds helpBounds = helpMenuNode.localToScene(helpMenuNode.getBoundsInLocal());
            check(editBounds.getWidth() > 20 && editBounds.getWidth() < 90 && helpBounds.getMinX() > editBounds.getMaxX(),
                    "Individual top-bar menu buttons have distinct compact bounds.");

            // Concise copy and highlight verification across all 6 guides.
            for (TutorialDialog.Guide guide : TutorialDialog.Guide.values()) {
                TutorialDialog td = new TutorialDialog(w, guide, false);
                try {
                    List<?> steps = (List<?>) FxTestSupport.field(td, "steps");
                    Label copy = (Label) FxTestSupport.field(td, "text");
                    for (int i = 0; i < steps.size(); i++) {
                        td.go(i);
                        String body = copy.getText();
                        check(body.length() <= 220 && !body.contains("\n\n"),
                                "Tutorial copy is concise for " + guide.name() + " step " + i + ": len=" + body.length());
                    }
                    if (guide == TutorialDialog.Guide.GETTING_STARTED) {
                        td.go(0);
                        Region highlightLayer = td.highlightLayer();
                        Rectangle highlightRect = td.highlightRect();
                        check(highlightLayer.getParent() == w.scene().getRoot(),
                                "Tutorial highlightLayer attaches to workspace scene root.");
                        check(highlightLayer.getViewOrder() < w.chrome().getViewOrder(),
                                "Tutorial highlightLayer renders above WindowChrome (" + highlightLayer.getViewOrder()
                                        + " < " + w.chrome().getViewOrder() + ").");
                        check(highlightRect.isVisible() && highlightRect.getWidth() >= 60 && highlightRect.getHeight() >= 20,
                                "Starter tutorial step 0 visibly highlights top-bar profile selector.");
                        double initialHighlightX = highlightRect.getX();
                        Bounds anchorScene = td.currentAnchor().localToScene(td.currentAnchor().getBoundsInLocal());
                        check(td.card().getLayoutY() >= anchorScene.getMaxY() + 6,
                                "Starter tutorial step 0 positions cleanly below the top-bar profile selector without covering it.");
                        w.setSize(520, 720);
                        FxTestSupport.layout(w);
                        check(Math.abs(highlightRect.getX() - initialHighlightX) > 80,
                                "Tutorial highlightRect tracks top-bar control movement live on window resize.");
                        w.setSize(1120, 720);
                        FxTestSupport.layout(w);
                    }
                } finally {
                    td.dispose();
                }
            }

            // Preferences ScrollPane auto-scrolls offscreen controls into view when targeted by tutorials.
            Stage pStage = AppearanceDialogs.windowFocusDialog(w);
            Preferences prefs = w.tutorialPreferences();
            try {
                Region pRoot = (Region) pStage.getScene().getRoot();
                pRoot.resize(860, 360);
                pRoot.applyCss();
                pRoot.layout();
                Node footerNode = prefs.tutorialTarget("footer");
                pRoot.layout();
                check(footerNode != null, "Preferences resolves 'footer' tutorial target.");
                ScrollPane sp = null;
                for (Node p = footerNode.getParent(); p != null; p = p.getParent()) {
                    if (p instanceof ScrollPane found) { sp = found; break; }
                }
                check(sp != null, "Appearance 'footer' control lives inside a ScrollPane.");
                Bounds spScene = sp.localToScene(sp.getBoundsInLocal());
                Bounds targetScene = footerNode.localToScene(footerNode.getBoundsInLocal());
                check(targetScene.getMaxY() <= spScene.getMaxY() + 2 && targetScene.getMinY() >= spScene.getMinY() - 2,
                        "Preferences.tutorialTarget('footer') scrolls ScrollPane so target is visible in viewport.");
            } finally {
                prefs.closeSettings();
                UsabilityIterationSmoke.settle(prefs::ended, "Preferences close settled.");
            }
            return null;
        });
    }

    private static void verifySmoothCrispContinuousResize(Workspace w) throws Exception {
        fx(() -> {
            SessionPane pane = w.sessionPanes().getFirst();
            TerminalView view = pane.view();
            view.setFit(true);
            ImageView surface = (ImageView) FxTestSupport.field(view, "surface");

            for (double scale : List.of(1.0, 1.5)) {
                Ui.setScaleOverride(scale);
                try {
                    Ui.syncSceneStylesheet(w.scene());
                    int prevGridW = -1;
                    int maxStepJump = 0;
                    int distinctGridWidths = 0;

                    // Continuous upward sweep across 680..1120 by 2px (tall height so width strictly governs fit).
                    for (int width = 680; width <= 1120; width += 2) {
                        w.setSize(width, 920);
                        FxTestSupport.layout(w);
                        view.forceSurfaceRender();
                        check(!surface.isSmooth(), "TerminalView ImageView keeps smooth=false for crisp 1:1 ClearType pixels.");
                        double cellW = (double) FxTestSupport.field(view, "cellWidth");
                        double scaleX = (double) FxTestSupport.field(view, "geometryDpiX");
                        double originX = (double) FxTestSupport.field(view, "originX");
                        check(Math.abs(originX * scaleX - Math.rint(originX * scaleX)) < 1e-6,
                                "OriginX snaps to exact physical pixel during continuous resize.");
                        int gridW = (int) Math.rint(80.0 * cellW * scaleX);
                        if (prevGridW >= 0) {
                            check(gridW >= prevGridW,
                                    "Continuous upward resize is strictly monotonic at scale=" + scale + ", width=" + width
                                            + ": prev=" + prevGridW + ", cur=" + gridW);
                            int jump = gridW - prevGridW;
                            if (jump > maxStepJump) maxStepJump = jump;
                            if (jump > 0) distinctGridWidths++;
                        } else {
                            distinctGridWidths = 1;
                        }
                        prevGridW = gridW;
                    }
                    check(maxStepJump <= 16,
                            "Continuous resize avoids 80px font-jump snapping at scale=" + scale + " (maxJump=" + maxStepJump + "px).");
                    check(distinctGridWidths >= 20,
                            "Sub-pixel font steps produce smooth progressive grid growth at scale=" + scale
                                    + " (distinctGridWidths=" + distinctGridWidths + ").");

                    // Continuous downward sweep 1120..680 by 2px must also be monotonic non-increasing.
                    int prevDownGridW = Integer.MAX_VALUE;
                    for (int width = 1120; width >= 680; width -= 2) {
                        w.setSize(width, 920);
                        FxTestSupport.layout(w);
                        view.forceSurfaceRender();
                        double cellW = (double) FxTestSupport.field(view, "cellWidth");
                        double scaleX = (double) FxTestSupport.field(view, "geometryDpiX");
                        int gridW = (int) Math.rint(80.0 * cellW * scaleX);
                        check(gridW <= prevDownGridW,
                                "Continuous downward resize is strictly monotonic at scale=" + scale + ", width=" + width
                                        + ": prev=" + prevDownGridW + ", cur=" + gridW);
                        prevDownGridW = gridW;
                    }

                    // 1px back-and-forth mouse jitter deadband check: zero oscillation.
                    w.setSize(900, 920);
                    FxTestSupport.layout(w);
                    view.forceSurfaceRender();
                    int baseStep = (int) FxTestSupport.field(view, "fontPixels");
                    for (int i = 0; i < 6; i++) {
                        w.setSize((i % 2 == 0) ? 901 : 900, 920);
                        FxTestSupport.layout(w);
                        view.forceSurfaceRender();
                        check((int) FxTestSupport.field(view, "fontPixels") == baseStep,
                                "1px resize jitter stays steady without fontStep snap-back oscillation.");
                    }
                } finally {
                    Ui.setScaleOverride(0);
                }
            }
            Ui.syncSceneStylesheet(w.scene());
            w.setSize(968, 662);
            FxTestSupport.layout(w);
            return null;
        });
    }

    private static void verifyPolish090Features(Workspace w, Path root) throws Exception {
        // 5. Default CursorStyle is OUTLINE ("Outline / filled Insert") and reducedMotion defaults to false.
        check(Appearance.defaults().cursorStyle() == Appearance.CursorStyle.OUTLINE,
                "Fresh Appearance.defaults().cursorStyle() is CursorStyle.OUTLINE.");
        check(!Appearance.defaults().reducedMotion(),
                "Fresh Appearance.defaults().reducedMotion() is false.");

        // 3a. Updates.select allows cross-minor and cross-major updates cleanly.
        String sampleReleasesJson = fish.model.ConfigSchema.json(List.of(
                fish.update.MaintenanceSmoke.release("0.9.0", true),
                fish.update.MaintenanceSmoke.release("0.10.2", false),
                fish.update.MaintenanceSmoke.release("1.3.0", false),
                fish.update.MaintenanceSmoke.release("2.0.0", true)));
        byte[] sampleBytes = sampleReleasesJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var currentVer = fish.update.Updates.Version.parse("0.9.0");
        var allSelected = fish.update.Updates.select(sampleBytes, currentVer, fish.model.UpdateSettings.Channel.PREVIEW_PATCHES);
        check(allSelected.isPresent() && allSelected.get().version().equals(fish.update.Updates.Version.parse("2.0.0")),
                "PREVIEW_PATCHES ('All releases (including betas)') selects 2.0.0 across major versions.");
        var stableSelected = fish.update.Updates.select(sampleBytes, currentVer, fish.model.UpdateSettings.Channel.STABLE);
        check(stableSelected.isPresent() && stableSelected.get().version().equals(fish.update.Updates.Version.parse("1.3.0")),
                "STABLE ('Stable releases') selects 1.3.0 across major versions while skipping 2.0.0 preview.");

        // 6a. StateStore format 20 round-trips reducedMotion=true and migrates format 19 cleanly.
        Path rmDir = Files.createDirectory(root.resolve("rm-store"));
        try (StateStore rmStore = new StateStore(rmDir)) {
            StateStore.State s0 = rmStore.load();
            check(s0.appearance().cursorStyle() == Appearance.CursorStyle.OUTLINE && !s0.appearance().reducedMotion(),
                    "Fresh StateStore.load() uses OUTLINE cursor and reducedMotion=false.");
            rmStore.save(new StateStore.State(
                    s0.profiles(), s0.layout(), s0.width(), s0.height(), s0.appTheme(), s0.lastProfile(),
                    s0.shortcuts(), s0.highlightActive(), s0.appearance().withReducedMotion(true),
                    s0.tutorial(), s0.connectOnStartup(), s0.updates(), s0.keyboardRecovery(),
                    s0.windows(), s0.addProfile(), s0.savedLayouts(), s0.selectedLayout(), s0.displays()));
            StateStore.State loaded = rmStore.load();
            check(loaded.appearance().reducedMotion(), "StateStore round-trips appearance.reducedMotion=true in format 20.");

            java.util.Properties v19 = StateStore.properties(s0);
            v19.setProperty("version", "19");
            v19.remove("appearance.reducedMotion");
            StateStore.State migrated19 = rmStore.decode(v19);
            check(!migrated19.appearance().reducedMotion(), "Format 19 without appearance.reducedMotion migrates cleanly to false.");
        }

        fx(() -> {
            // 1. No vertical separator line between Help menu and Profile selector; split-pill join separator intact.
            javafx.scene.layout.HBox tools = (javafx.scene.layout.HBox) FxTestSupport.field(w.chrome(), "tools");
            check(tools.getChildren().size() == 3
                    && tools.getChildren().get(0) instanceof javafx.scene.control.ComboBox<?>
                    && tools.getChildren().get(1) instanceof javafx.scene.control.Separator
                    && tools.getChildren().get(2) instanceof Button,
                    "Top-bar tools has 3 children [ComboBox, join Separator, + Button] without leading vertical line.");

            // 7. Menu bar shortcut hints use DisplayAccelerator (match()==false), 11px muted .accelerator-text, right-justified.
            javafx.scene.control.Menu editMenu = w.chrome().menuBar().getMenus().get(1);
            javafx.scene.control.MenuItem settingsItem = editMenu.getItems().stream()
                    .filter(it -> it.getProperties().get("fish.command") instanceof AppCommand cmd && cmd.title.equals("Settings..."))
                    .findFirst().orElseThrow();
            check("Settings...".equals(settingsItem.getText()),
                    "MenuItem text is pure action label ('Settings...') without inline spaces+shortcut: " + settingsItem.getText());
            AppCommand settingsCmd = (AppCommand) settingsItem.getProperties().get("fish.command");
            check(settingsItem.getAccelerator() != null
                    && !settingsCmd.primaryShortcut().isBlank()
                    && settingsCmd.primaryShortcut().equals(settingsItem.getAccelerator().getDisplayText()),
                    "MenuItem DisplayAccelerator exposes primaryShortcut ('" + settingsCmd.primaryShortcut() + "') display text.");
            KeyEvent ctrlComma = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.COMMA, false, true, false, false);
            check(!settingsItem.getAccelerator().match(ctrlComma),
                    "DisplayAccelerator.match() returns false so Scene.getAccelerators() never double-fires.");

            javafx.scene.control.ContextMenu popup = w.paneMenu(w.active());
            popup.show(w.stage(), w.stage().getX() + 80, w.stage().getY() + 80);
            try {
                Scene popScene = popup.getScene();
                check(popScene.getRoot().getOpacity()==1 && popScene.getRoot().getTranslateY()==0
                        && popScene.getStylesheets().contains(Ui.stylesheetForScale(Ui.scaleFor(popScene))),
                        "The first menu frame is themed and fully visible, without a post-show fade.");
                popScene.getRoot().applyCss();
                popScene.getRoot().layout();
                List<Node> accelNodes = new ArrayList<>(popScene.getRoot().lookupAll(".accelerator-text"));
                check(!accelNodes.isEmpty(), "ContextMenu renders dedicated .accelerator-text nodes.");
                Double commonRightEdge = null;
                for (Node acc : accelNodes) {
                    if (!acc.isVisible()) continue;
                    if (acc instanceof javafx.scene.control.Labeled lbl) {
                        if (lbl.getText() == null || lbl.getText().isBlank()) continue;
                        check(Math.abs(lbl.getFont().getSize() - 11.0) < 0.25,
                                "Menu shortcut hint font size is 11px, got " + lbl.getFont().getSize());
                    } else if (acc instanceof javafx.scene.text.Text txt) {
                        if (txt.getText() == null || txt.getText().isBlank()) continue;
                        check(Math.abs(txt.getFont().getSize() - 11.0) < 0.25,
                                "Menu shortcut hint Text font size is 11px, got " + txt.getFont().getSize());
                    }
                    Bounds b = acc.localToScene(acc.getBoundsInLocal());
                    if (b.getWidth() <= 1) continue;
                    ParentRowBounds row = findMenuItemLabelBounds(acc);
                    if (row != null) {
                        check(b.getMinX() >= row.labelMaxX + 14.0,
                                "Accelerator column is separated to the right of action label (labelMaxX="
                                        + row.labelMaxX + ", accelMinX=" + b.getMinX() + ").");
                    }
                    if (commonRightEdge == null) commonRightEdge = b.getMaxX();
                    else check(Math.abs(b.getMaxX() - commonRightEdge) <= 1.5,
                            "Menu shortcut hints right-align to a shared column (expected right=" + commonRightEdge + ", got=" + b.getMaxX() + ").");
                }
                check(commonRightEdge != null, "Verified at least one visible right-aligned .accelerator-text node.");
            } finally {
                popup.hide();
            }

            // 2 & 3b & 6b. Preferences sidebar unclipped/0px-shift, Updates MarkdownView live-theming, Reduce motion + Flux pause.
            Stage pStage = w.preferencesDialog("Appearance");
            Preferences prefs = w.tutorialPreferences();
            try {
                for (double scale : List.of(1.0, 1.5)) {
                    Ui.setScaleOverride(scale);
                    Ui.syncSceneStylesheet(pStage.getScene());
                    Region pRoot = (Region) pStage.getScene().getRoot();
                    pRoot.resize(860, 620);
                    pRoot.applyCss();
                    pRoot.layout();

                    @SuppressWarnings("unchecked")
                    javafx.scene.control.ListView<String> nav = (javafx.scene.control.ListView<String>) FxTestSupport.field(prefs, "navigation");
                    check(nav.getWidth() >= 184.0, "Settings navigation width >= 184px: " + nav.getWidth());

                    java.util.Map<String, Double> unselectedTextX = new java.util.HashMap<>();
                    nav.getSelectionModel().select("Appearance");
                    pRoot.applyCss();
                    pRoot.layout();
                    for (Node n : nav.lookupAll(".list-cell")) {
                        if (n instanceof javafx.scene.control.ListCell<?> cell && !cell.isEmpty() && cell.getItem() instanceof String item) {
                            Node txt = cell.lookup(".text");
                            check(txt != null, "Sidebar cell has .text node for " + item);
                            String rendered = (txt instanceof javafx.scene.text.Text t) ? t.getText() : cell.getText();
                            check(!rendered.contains("\u2026") && !rendered.contains("..."),
                                    "Sidebar category text is unclipped/untruncated for '" + item + "': got '" + rendered + "'.");
                            Bounds tb = txt.localToParent(txt.getBoundsInLocal());
                            if (!cell.isSelected()) unselectedTextX.put(item, tb.getMinX());
                            check(tb.getMinY() >= 3.5 && tb.getMaxY() <= cell.getHeight() - 3.5,
                                    "Sidebar cell text vertically centered without clipping at scale=" + scale + " for '" + item
                                            + "': minY=" + tb.getMinY() + ", maxY=" + tb.getMaxY() + ", cellH=" + cell.getHeight());
                        }
                    }

                    // Select every category and verify 0px horizontal shift and zero vertical clipping when selected.
                    for (String cat : List.of("Profiles", "Keyboard Shortcuts", "General", "Updates")) {
                        nav.getSelectionModel().select(cat);
                        pRoot.applyCss();
                        pRoot.layout();
                        for (Node n : nav.lookupAll(".list-cell")) {
                            if (n instanceof javafx.scene.control.ListCell<?> cell && !cell.isEmpty() && cat.equals(cell.getItem())) {
                                check(cell.isSelected(), "Cell '" + cat + "' is marked selected.");
                                Node txt = cell.lookup(".text");
                                Bounds tb = txt.localToParent(txt.getBoundsInLocal());
                                Double beforeX = unselectedTextX.get(cat);
                                if (beforeX != null) {
                                    check(Math.abs(tb.getMinX() - beforeX) < 0.1,
                                            "Selected sidebar cell '" + cat + "' has 0px horizontal offset shift (before="
                                                    + beforeX + ", selected=" + tb.getMinX() + ").");
                                }
                                check(tb.getMinY() >= 3.5 && tb.getMaxY() <= cell.getHeight() - 3.5,
                                        "Selected sidebar cell '" + cat + "' is unclipped vertically (minY="
                                                + tb.getMinY() + ", maxY=" + tb.getMaxY() + ", h=" + cell.getHeight() + ").");
                            }
                        }
                    }
                }
                Ui.setScaleOverride(0);
                Ui.syncSceneStylesheet(pStage.getScene());

                // 3b. Updates release notes MarkdownView live-theming across Charcoal -> Paper -> Charcoal.
                @SuppressWarnings("unchecked")
                javafx.scene.control.ListView<String> navAll = (javafx.scene.control.ListView<String>) FxTestSupport.field(prefs, "navigation");
                navAll.getSelectionModel().select("Updates");
                Region pRoot = (Region) pStage.getScene().getRoot();
                pRoot.applyCss();
                pRoot.layout();
                MarkdownView notes = (MarkdownView) pRoot.lookup(".fish-markdown-view");
                check(notes != null, "Updates pane contains a .fish-markdown-view release notes component.");
                notes.markdown("## Fish 0.9.0 Notes\n\n- Cross-major updates enabled\n- `Fish.aot` cache");
                pRoot.applyCss();
                pRoot.layout();
                var charcoalFace = notes.getBackground().getFills().getLast().getFill();
                check(charcoalFace instanceof javafx.scene.paint.LinearGradient && notes.contentBox().getBackground()==null,
                        "Notes share one uninterrupted Charcoal field gradient.");
                Ui.applyTheme(AppTheme.Paper);
                pRoot.applyCss();
                pRoot.layout();
                var paperFace = notes.getBackground().getFills().getLast().getFill();
                check(!paperFace.equals(charcoalFace) && paperFace instanceof javafx.scene.paint.LinearGradient,
                        "Switching to Paper live-themes the release notes surface.");
                Ui.applyTheme(AppTheme.Charcoal);
                pRoot.applyCss();
                pRoot.layout();
                check(notes.getBackground().getFills().getLast().getFill().equals(charcoalFace),
                        "Switching back to Charcoal restores Updates MarkdownView background immediately.");

                // 6b. Reduce motion checkbox toggles Ui.reducedMotion() and Appearance immediately.
                navAll.getSelectionModel().select("Appearance");
                pRoot.applyCss();
                pRoot.layout();
                javafx.scene.control.CheckBox rmCheck = prefs.reducedMotionCheck();
                check(!rmCheck.isSelected() && !Ui.reducedMotion(), "Reduce motion starts unchecked/false.");

                rmCheck.fire();
                pRoot.layout();
                check(rmCheck.isSelected() && Ui.reducedMotion() && w.appearanceSettings().reducedMotion(),
                        "Checking Reduce motion updates Appearance and Ui.reducedMotion() immediately.");

                Node testBanner = w.chrome();
                Ui.animateSubtle(testBanner, 0.0, -2.5, 85);
                check(testBanner.getOpacity() == 1.0 && testBanner.getTranslateY() == 0.0,
                        "Ui.animateSubtle skips transitions immediately when Reduce motion is enabled.");

                rmCheck.fire();
                pRoot.layout();
                check(!rmCheck.isSelected() && !Ui.reducedMotion() && !w.appearanceSettings().reducedMotion(),
                        "Unchecking Reduce motion restores subtle motion.");
            } finally {
                Ui.setScaleOverride(0);
                Ui.setReducedMotion(false);
                prefs.forceDispose();
            }

            // Verify TerminalView Flux animation clock pauses immediately when Reduce motion is enabled and resumes when disabled.
            SessionPane pane0 = w.sessionPanes().getFirst();
            w.putProfile(pane0.profile().withDisplay(pane0.profile().theme(), fish.model.Profile.FLUX));
            TerminalView offscreenFluxView = new TerminalView(pane0) {
                @Override protected boolean isTerminalShowing() { return true; }
            };
            Stage fluxHolder = new Stage();
            fluxHolder.setScene(new Scene(offscreenFluxView, 320, 200));
            try {
                offscreenFluxView.updateAnimation();
                check(TerminalView.fluxAnimationClock().getStatus() == javafx.animation.Animation.Status.RUNNING,
                        "Flux animation clock runs when Flux effect is active and Reduce motion is off.");
                Ui.setReducedMotion(true);
                check(TerminalView.fluxAnimationClock().getStatus() != javafx.animation.Animation.Status.RUNNING,
                        "Enabling Reduce motion immediately pauses TerminalView Flux animation clock.");
                Ui.setReducedMotion(false);
                check(TerminalView.fluxAnimationClock().getStatus() == javafx.animation.Animation.Status.RUNNING,
                        "Disabling Reduce motion resumes TerminalView Flux animation clock.");
            } finally {
                Ui.setReducedMotion(false);
                offscreenFluxView.dispose();
                w.putProfile(pane0.profile().withDisplay(pane0.profile().theme(), "None"));
            }

            // 4. AI-CONFIG.md table column 1 renders cleanly without character-per-newline vertical crushing.
            String aiConfigMd = Files.readString(Path.of("AI-CONFIG.md"));
            MarkdownView docView = new MarkdownView("Configuration guide");
            docView.markdown(aiConfigMd);
            Stage docStage = new Stage();
            Ui.decorateDialog(docStage, docView, docStage::close, 680, 520);
            docView.resize(680, 520);
            docView.applyCss();
            docView.layout();
            javafx.scene.layout.GridPane areaTable = FxTestSupport.children(docView).stream()
                    .filter(javafx.scene.layout.GridPane.class::isInstance)
                    .map(javafx.scene.layout.GridPane.class::cast)
                    .filter(gp -> gp.getColumnConstraints().size() == 2)
                    .findFirst().orElse(null);
            check(areaTable != null, "AI-CONFIG.md renders 2-column GridPane table.");
            check(areaTable.getColumnConstraints().get(0).getMinWidth() >= 110.0,
                    "AI-CONFIG.md table column 0 has minWidth >= 110px: " + areaTable.getColumnConstraints().get(0).getMinWidth());
            int checkedCol0Cells = 0;
            for (Node cellNode : areaTable.getChildren()) {
                Integer colIdx = javafx.scene.layout.GridPane.getColumnIndex(cellNode);
                if (colIdx != null && colIdx == 0 && cellNode instanceof Region cellReg) {
                    checkedCol0Cells++;
                    check(cellReg.getWidth() >= 110.0 && cellReg.getHeight() <= 54.0,
                            "AI-CONFIG.md column 0 cell renders horizontally without per-char wrapping (w="
                                    + cellReg.getWidth() + ", h=" + cellReg.getHeight() + ").");
                }
            }
            check(checkedCol0Cells >= 12, "Verified all " + checkedCol0Cells + " column-0 cells in AI-CONFIG.md table.");
            return null;
        });
    }

    private record ParentRowBounds(double labelMaxX) {}

    private static String hexOf(java.awt.Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static ParentRowBounds findMenuItemLabelBounds(Node accelNode) {
        Node parent = accelNode.getParent();
        if (parent == null) return null;
        for (Node sibling : parent.lookupAll(".label")) {
            if (sibling == accelNode || accelNode.equals(sibling)) continue;
            Bounds sb = sibling.localToScene(sibling.getBoundsInLocal());
            if (sb.getWidth() > 1) return new ParentRowBounds(sb.getMaxX());
        }
        return null;
    }

    private static String sampleReleaseJson(String version, boolean prerelease) {
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        return "{\"draft\":false,\"prerelease\":" + prerelease
                + ",\"tag_name\":\"v" + version + "\",\"name\":\"Fish " + version + "\""
                + ",\"html_url\":\"https://github.com/tsafago/Fish/releases/tag/v" + version + "\""
                + ",\"body\":\"Release notes " + version + "\""
                + ",\"assets\":["
                + "{\"name\":\"Fish-" + version + "-setup.exe\",\"size\":4096,\"digest\":\"sha256:" + sha + "\","
                + "\"browser_download_url\":\"https://github.com/tsafago/Fish/releases/download/v" + version + "/Fish-" + version + "-setup.exe\"},"
                + "{\"name\":\"SHA256SUMS.txt\",\"size\":256,\"digest\":\"sha256:" + sha + "\","
                + "\"browser_download_url\":\"https://github.com/tsafago/Fish/releases/download/v" + version + "/SHA256SUMS.txt\"}"
                + "]}";
    }
}
