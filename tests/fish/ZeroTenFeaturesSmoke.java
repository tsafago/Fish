package fish.ui;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.tn5250j.Session5250;
import org.tn5250j.framework.tn5250.ScreenOIA;
import org.tn5250j.keyboard.KeyMnemonic;
import fish.Configuration;
import fish.LayoutShortcuts;
import fish.Main;
import fish.demo.DemoHost;
import fish.demo.DemoScreens;
import fish.model.*;
import fish.security.KioskMode;

/**
 * Hermetic verification suite for all Fish 0.10.0 fixes and features:
 *  1. 8-pane 300% zoom viewport-clipped buffer sizing, zero crash/slowdown, per-pane relative 100% (auto) zoom, HUD popup & layout persistence
 *  2. scroll + / scroll - KeyChord parsing, KeyRecorder wheel capture, and hovered-pane Ctrl+Scroll dispatch
 *  3. Startup-error Factory reset (delete config) with Kiosk admin gate + Uninstall config/Startup deletion
 *  4. Per-layout Windows Autostart (.lnk creation/sync/rename/removal across multiple layouts + SavedLayoutsDialog UI)
 *  5. Admin-controlled Kiosk mode full UI/menu/logo/splitter/zoom/persistence/launch lockdown + live admin unlock
 *  6. Stuck / Reset callout ("Are you stuck? Press Enter or press this button!") with 0ms dismissal on Enter & button
 *  7. System-wide installation (-AllUsers) scripts & Preferences > General controls
 */
public final class ZeroTenFeaturesSmoke {
    private static int checks;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static <T> T fx(Callable<T> action) throws Exception { return FxTestSupport.fx(action); }
    private static Object field(Object owner, String name) throws Exception { return FxTestSupport.field(owner, name); }
    private static void layout(Workspace w) { FxTestSupport.layout(w); }

    private static ScrollEvent wheelEvent(Object source, javafx.event.EventTarget target, double deltaY, double deltaX,
                                          boolean shift, boolean ctrl, boolean alt, boolean meta) {
        return new ScrollEvent(source, target, ScrollEvent.SCROLL, 120, 120, 120, 120,
                shift, ctrl, alt, meta, false, false,
                deltaX, deltaY, deltaX, deltaY,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null);
    }

    private static void verifyScrollBindingsAndRecorder(Path root) throws Exception {
        Map<KeyChord, String> defaults = KeyBindings.effective(Map.of());
        check(defaults.keySet().stream().noneMatch(k -> KeyChord.isScrollCode(k.code())),
                "SCROLL+ and SCROLL- are not bound to any action by default");
        check(KeyChord.supportedKeys().contains("SCROLL+") && KeyChord.supportedKeys().contains("SCROLL-"),
                "Supported keys list includes SCROLL+ and SCROLL-");
        String schema = Files.readString(Path.of("CONFIG-SCHEMA.json"));
        check(schema.contains("\"SCROLL+\"") && schema.contains("\"SCROLL-\"") && schema.contains("\"catalogVersion\": 11"),
                "CONFIG-SCHEMA.json exports catalogVersion 11 and SCROLL+/SCROLL-");

        for (String up : List.of("scroll +", "SCROLL+", "wheel +")) {
            KeyChord parsed = KeyChord.parse(up);
            check(parsed.code() == KeyChord.VK_SCROLL_UP && parsed.modifiers() == 0, "Bare up wheel parses: " + up);
            check(KeyChord.parse(parsed.toString()).equals(parsed), "Canonical round-trip for " + up);
            check(parsed.label().equals("Scroll +"), "Human label for bare scroll +: " + parsed.label());
        }
        for (String down : List.of("scroll -", "SCROLL-", "wheel -")) {
            KeyChord parsed = KeyChord.parse(down);
            check(parsed.code() == KeyChord.VK_SCROLL_DOWN && parsed.modifiers() == 0, "Bare down wheel parses: " + down);
            check(KeyChord.parse(parsed.toString()).equals(parsed), "Canonical round-trip for " + down);
            check(parsed.label().equals("Scroll -"), "Human label for bare scroll -: " + parsed.label());
        }
        KeyChord ctrlUp = KeyChord.parse("ctrl scroll +");
        KeyChord ctrlDown = KeyChord.parse("ctrl scroll -");
        check(ctrlUp.equals(KeyChord.parse("ctrl SCROLL+")) && ctrlUp.label().equals("Ctrl+Scroll +"),
                "Spaced and compact ctrl scroll + match: " + ctrlUp + " / " + ctrlUp.label());
        check(ctrlDown.equals(KeyChord.parse("ctrl SCROLL-")) && ctrlDown.label().equals("Ctrl+Scroll -"),
                "Spaced and compact ctrl scroll - match: " + ctrlDown + " / " + ctrlDown.label());

        fx(() -> {
            KeyRecorder recUp = new KeyRecorder();
            boolean consumedUp = recUp.captureScrollEvent(wheelEvent(recUp.capture, recUp.capture, 40.0, 0.0, false, true, false, false));
            check(consumedUp && recUp.chosen() != null && "ctrl SCROLL+".equals(recUp.chosen().toString()),
                    "KeyRecorder captures Ctrl+wheel up as ctrl SCROLL+: " + recUp.chosen());

            KeyRecorder recDown = new KeyRecorder();
            boolean consumedDown = recDown.captureScrollEvent(wheelEvent(recDown.capture, recDown.capture, -40.0, 0.0, false, true, false, false));
            check(consumedDown && recDown.chosen() != null && "ctrl SCROLL-".equals(recDown.chosen().toString()),
                    "KeyRecorder captures Ctrl+wheel down as ctrl SCROLL-: " + recDown.chosen());

            KeyRecorder recShiftHoriz = new KeyRecorder();
            boolean consumedShift = recShiftHoriz.captureScrollEvent(wheelEvent(recShiftHoriz.capture, recShiftHoriz.capture, 0.0, -40.0, true, false, false, false));
            check(consumedShift && recShiftHoriz.chosen() != null && "shift SCROLL-".equals(recShiftHoriz.chosen().toString()),
                    "KeyRecorder falls back to deltaX when Windows Shift+wheel reports deltaY==0: " + recShiftHoriz.chosen());
            return null;
        });
    }

    private static void verifyEightPaneZoomAndHoveredWheel(Path root, int port) throws Exception {
        Path dir = Files.createDirectory(root.resolve("zoom-eight"));
        UUID p1Id = UUID.randomUUID(), p2Id = UUID.randomUUID();
        Profile profA = new Profile(p1Id, "Shared IBM A", "127.0.0.1", port, "", "", false, false, "37", "Phosphor", "None", 1.0, Map.of(), "");
        Profile profB = new Profile(p2Id, "Distinct IBM B", "127.0.0.1", port, "", "", false, false, "37", "Amber", "None", 1.0, Map.of(), "");

        StateStore store = new StateStore(dir);
        UUID lastPane = UUID.randomUUID();
        Layout eight = new Layout.Leaf(lastPane, profA.id());
        for (int i = 1; i < 8; i++) {
            UUID nextPane = UUID.randomUUID();
            eight = eight.add(new Layout.Leaf(nextPane, i % 2 == 1 ? profB.id() : profA.id()), lastPane, c -> true);
            lastPane = nextPane;
        }
        StateStore.State seed = new StateStore.State(List.of(profA, profB), eight, 1400, 900, AppTheme.Charcoal,
                profA.id(), Map.of(), true, Appearance.defaults().withReducedMotion(true), StateStore.Tutorial.SKIPPED, false, UpdateSettings.defaults());
        store.save(seed);
        StateStore.State initial = store.load();
        CountDownLatch closed = new CountDownLatch(1);

        ApplicationWindows app = fx(() -> {
            ApplicationWindows aw = new ApplicationWindows(store, dir.resolve("absent-cred.ps1"), false, false,
                    (s, st, h, d) -> new Workspace(s, st, h, d, new fish.update.Updates(), (owner, msg) -> false, false));
            aw.onClosed(closed::countDown);
            aw.restore(initial, null);
            return aw;
        });
        try {
            Workspace w = fx(() -> app.windows().getFirst());
            fx(() -> {
                w.stage().setWidth(1400);
                w.stage().setHeight(900);
                layout(w);
                w.connectAll();
                return null;
            });
            UsabilityIterationSmoke.settle(() -> w.sessionPanes().size() == 8
                    && w.sessionPanes().stream().allMatch(p -> p.connected() && p.screen() != null && p.screen().getScreenFields().getSize() > 0),
                    "All 8 synthetic panes connected");

                // 1. Verify per-pane zoom independence across panes sharing Profile A + HUD popup text.
                fx(() -> {
                    List<SessionPane> panes = w.sessionPanes();
                    List<SessionPane> sharedAPanes = panes.stream().filter(p -> p.profile().id().equals(profA.id())).toList();
                    check(sharedAPanes.size() >= 2, "Multiple panes share Profile A");
                    SessionPane firstA = sharedAPanes.get(0);
                    SessionPane secondA = sharedAPanes.get(1);
                    w.activate(firstA);
                    w.zoom(0.1);
                    w.zoom(0.1);
                    w.zoom(0.1);
                    check(!firstA.view().isFit() && Math.abs(firstA.view().zoomValue() - 1.3) < 0.005,
                            "Zoomed pane reaches 130%: " + firstA.view().zoomValue());
                    check(firstA.zoomHudLabel().isVisible() && "130%".equals(firstA.zoomHudLabel().getText()),
                            "Zoom HUD shows 130%: " + firstA.zoomHudLabel().getText());
                    check(secondA.view().isFit() && "100% (auto)".equals(secondA.zoomBadgeText()),
                            "Sibling pane sharing same Profile A remains at 100% (auto)");
                    check(Math.abs(firstA.profile().zoom() - 1.0) < 0.001,
                            "Workspace.zoom never mutates shared Profile.zoom");

                    // Switching profile on firstA preserves its explicit per-pane 130% zoom!
                    firstA.updateProfile(profB);
                    check(!firstA.view().isFit() && Math.abs(firstA.view().zoomValue() - 1.3) < 0.005,
                            "Changing a pane's profile preserves its explicit per-pane zoom");
                    firstA.updateProfile(profA);

                    // Snap back to 100% (auto) via fit and via stepping down to 1.0
                    w.zoom(firstA, -0.3);
                    check(firstA.view().isFit() && "100% (auto)".equals(firstA.zoomHudLabel().getText()),
                            "Stepping back to 100% snaps to fit mode and shows '100% (auto)'");

                    // Below 100% (80%): stays centered without scrollbars!
                    w.zoom(firstA, -0.2);
                    layout(w);
                    ScrollPane sp = (ScrollPane) field(firstA, "scroll");
                    check(!firstA.view().isFit() && "80%".equals(firstA.zoomHudLabel().getText()),
                            "Sub-100% zoom shows 80% HUD");
                    check(sp.getHbarPolicy() == ScrollPane.ScrollBarPolicy.NEVER
                            && sp.getVbarPolicy() == ScrollPane.ScrollBarPolicy.NEVER,
                            "Sub-100% zoom centers inside pane with ScrollBarPolicy.NEVER");

                    // Hovered-pane Ctrl+Scroll binding test:
                    // Unbound wheel does NOT consume or zoom
                    AppShortcuts shortcuts = (AppShortcuts) field(w, "shortcuts");
                    boolean unboundHandled = shortcuts.dispatchScroll(secondA.view(),
                            wheelEvent(secondA.view(), secondA.view(), 40.0, 0.0, false, true, false, false));
                    check(!unboundHandled && secondA.view().isFit(), "Unbound Ctrl+Scroll is not consumed");

                    // Bind ctrl scroll + -> Zoom in, ctrl scroll - -> Zoom out
                    w.applyShortcuts(Map.of("Zoom in", "ctrl EQUALS\nctrl scroll +", "Zoom out", "ctrl MINUS\nctrl scroll -"));
                    w.activate(firstA); // firstA is active (at 80%), hover over secondA (at 100% auto)!
                    boolean boundHandled = shortcuts.dispatchScroll(secondA.view(),
                            wheelEvent(secondA.view(), secondA.view(), 40.0, 0.0, false, true, false, false));
                    check(boundHandled, "Bound Ctrl+Scroll + is consumed");
                    check(!secondA.view().isFit() && Math.abs(secondA.view().zoomValue() - 1.1) < 0.005
                            && "110%".equals(secondA.zoomHudLabel().getText()),
                            "Ctrl+Scroll + zooms hovered pane (secondA -> 110%) even while firstA is active");
                    check(Math.abs(firstA.view().zoomValue() - 0.8) < 0.005,
                            "Active non-hovered pane (firstA) stays at 80%");
                    return null;
                });

                // 2. Pane-size-relative zoom scaling check: resize window from 1400x900 to 900x600.
                fx(() -> {
                    SessionPane p0 = w.sessionPanes().getFirst();
                    p0.view().setRelativeZoom(1.5, false);
                    layout(w);
                    p0.view().forceSurfaceRender();
                    int stepLarge = (int) field(p0.view(), "fontPixels");

                    w.stage().setWidth(900);
                    w.stage().setHeight(600);
                    layout(w);
                    p0.view().forceSurfaceRender();
                    int stepSmall = (int) field(p0.view(), "fontPixels");
                    check(stepLarge > stepSmall,
                            "150% zoom scales proportionally with pane size (large step=" + stepLarge + " > small step=" + stepSmall + ")");

                    w.stage().setWidth(1400);
                    w.stage().setHeight(900);
                    layout(w);
                    return null;
                });

                // 3. CRITICAL 8-PANE 300% ZOOM STRESS TEST: viewport-clipped buffers, scroll hit-test, fast render, zero OOM!
                fx(() -> {
                    long startNs = System.nanoTime();
                    long totalAllocatedSurfacePixels = 0;
                    long totalVirtualPixels = 0;
                    for (SessionPane p : w.sessionPanes()) {
                        p.view().setRelativeZoom(3.0, false);
                    }
                    layout(w);
                    for (SessionPane p : w.sessionPanes()) {
                        ScrollPane sp = (ScrollPane) field(p, "scroll");
                        sp.setHvalue(0.5);
                        sp.setVvalue(0.5);
                        layout(w);
                        p.view().forceSurfaceRender();
                        int surfPx = p.view().allocatedSurfacePixelsForTest();
                        int inkPx = p.view().allocatedSharedInkPixelsForTest();
                        double vw = p.view().getWidth(), vh = p.view().getHeight();
                        double[] vp = p.viewportInView(vw, vh);
                        int maxExpectedVpAlloc = ((int) Math.ceil(vp[2]) + 512) * ((int) Math.ceil(vp[3]) + 512);
                        check(surfPx > 0 && surfPx <= maxExpectedVpAlloc,
                                "300% zoomed surface buffer is bounded to visible viewport (surfPx=" + surfPx + " <= " + maxExpectedVpAlloc + ")");
                        check(inkPx > 0 && inkPx <= maxExpectedVpAlloc,
                                "300% zoomed sharedInk buffer is bounded to visible viewport (inkPx=" + inkPx + " <= " + maxExpectedVpAlloc + ")");
                        totalAllocatedSurfacePixels += surfPx;
                        totalVirtualPixels += (long) (vw * vh);

                        // Scrolled mouse hit-test accuracy in TerminalView-local coordinates:
                        double ox = (double) field(p.view(), "originX");
                        double oy = (double) field(p.view(), "originY");
                        double cw = (double) field(p.view(), "cellWidth");
                        double ch = (double) field(p.view(), "cellHeight");
                        int cols = p.screen().getColumns();
                        int hitPos = p.view().positionForTest(ox + cw * 15.5, oy + ch * 10.5);
                        check(hitPos == 10 * cols + 15,
                                "Scrolled 300% position hit-testing remains exact at row 10, col 15: got " + hitPos);
                    }
                    double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
                    check(totalAllocatedSurfacePixels < totalVirtualPixels && totalAllocatedSurfacePixels <= 8_000_000L,
                            "Total 8-pane surface pixels (" + totalAllocatedSurfacePixels + ") stays strictly viewport-bounded below 300% virtual canvas (" + totalVirtualPixels + ")");
                    check(elapsedMs < 4000.0, "8-pane 300% zoom layout + full render completed rapidly in " + String.format(Locale.ROOT, "%.1f", elapsedMs) + " ms");
                    return null;
                });

                // 4. Persist per-pane zoom in StateStore format 21 AND in SavedLayout (--layout reopen)!
                AtomicBoolean savedDone = new AtomicBoolean(false);
                UUID layoutId = fx(() -> {
                    List<SessionPane> panes = w.sessionPanes();
                    panes.get(0).view().setRelativeZoom(1.0, true);
                    panes.get(1).view().setRelativeZoom(1.4, false);
                    panes.get(2).view().setRelativeZoom(0.8, false);
                    SavedLayout saved = w.saveCurrentLayout(null, "Floor Zoom Recipe", false);
                    w.persistSettings(() -> savedDone.set(true), err -> { throw new AssertionError(err); });
                    return saved.id();
                });
                UsabilityIterationSmoke.settle(savedDone::get, "SavedLayout zoom recipe persisted");

                StateStore.State reloaded = store.load();
                check("21".equals(StateStore.VERSION), "StateStore.VERSION is 21");
                SavedLayout persistedRecipe = reloaded.savedLayouts().stream()
                        .filter(l -> l.id().equals(layoutId)).findFirst().orElseThrow();
                long customZoomCount = persistedRecipe.displays().values().stream()
                        .filter(d -> !d.fit() && (Math.abs(d.zoom() - 1.4) < 0.005 || Math.abs(d.zoom() - 0.8) < 0.005)).count();
                check(customZoomCount == 2, "SavedLayout persists distinct per-pane zoom factors (1.4 and 0.8)");

                fx(() -> {
                    int rc = app.openRequest(layoutId);
                    check(rc == 1 && app.windows().size() == 2, "openRequest(layoutId) opens saved layout window: rc=" + rc);
                    Workspace w2 = app.windows().get(1);
                    List<SessionPane> rPanes = w2.sessionPanes();
                    check(rPanes.get(0).view().isFit() && "100% (auto)".equals(rPanes.get(0).zoomBadgeText()),
                            "Restored recipe pane 0 is 100% (auto)");
                    check(!rPanes.get(1).view().isFit() && Math.abs(rPanes.get(1).view().zoomValue() - 1.4) < 0.005,
                            "Restored recipe pane 1 preserves 140% zoom");
                    check(!rPanes.get(2).view().isFit() && Math.abs(rPanes.get(2).view().zoomValue() - 0.8) < 0.005,
                            "Restored recipe pane 2 preserves 80% zoom");
                    return null;
                });
        } finally {
            fx(() -> { app.quit(); return null; });
            check(closed.await(10, TimeUnit.SECONDS), "8-pane ApplicationWindows closed and released StateStore cleanly");
        }
    }

    private static void verifyAutostartAndKioskAndResetCallout(Path root, int port) throws Exception {
        Path startupDir = Files.createDirectory(root.resolve("startup-folder"));
        Path fakeExe = Files.writeString(root.resolve("Fish.exe"), "MZ-fake");
        String prevStartup = System.getProperty("fish.test.startupDir");
        String prevExe = System.getProperty("fish.test.executable");
        System.setProperty("fish.test.startupDir", startupDir.toString());
        System.setProperty("fish.test.executable", fakeExe.toString());

        Path dir = Files.createDirectory(root.resolve("autostart-kiosk"));
        StateStore store = new StateStore(dir);
        Profile p = new Profile(UUID.randomUUID(), "Warehouse Floor", "127.0.0.1", port, "", "", false, false, "37", "Phosphor", "None", 1.0, Map.of(), "");
        Layout twoPane = new Layout.Split(true, 0.5, new Layout.Leaf(UUID.randomUUID(), p.id()), new Layout.Leaf(UUID.randomUUID(), p.id()));
        StateStore.State seed = new StateStore.State(List.of(p), twoPane, 1100, 750,
                AppTheme.Charcoal, p.id(), Map.of(), true, Appearance.defaults().withReducedMotion(true), StateStore.Tutorial.SKIPPED, false, UpdateSettings.defaults());
        store.save(seed);
        StateStore.State init = store.load();
        CountDownLatch closed = new CountDownLatch(1);

        ApplicationWindows app = fx(() -> {
            ApplicationWindows aw = new ApplicationWindows(store, dir.resolve("absent-cred.ps1"), false, false,
                    (s, st, h, d) -> new Workspace(s, st, h, d, new fish.update.Updates(), (owner, msg) -> false, false));
            aw.onClosed(closed::countDown);
            aw.restore(init, null);
            return aw;
        });
        try {
            Workspace w = fx(() -> app.windows().getFirst());
            fx(() -> {
                layout(w);
                w.connectAll();
                return null;
            });
            UsabilityIterationSmoke.settle(() -> w.sessionPanes().size() == 2
                    && w.sessionPanes().stream().allMatch(sp -> sp.connected() && sp.screen() != null),
                    "Connected 2 panes for autostart/kiosk/reset tests");

                // --- Per-Layout Multi-Autostart Verification ---
                UUID layoutAId = fx(() -> w.saveCurrentLayout(null, "Morning Receiving", true).id());
                UUID layoutBId = fx(() -> w.saveCurrentLayout(null, "Afternoon Shipping", true).id());
                List<Path> lnksAfterBoth = Files.list(startupDir).filter(f -> f.toString().endsWith(".lnk")).toList();
                check(lnksAfterBoth.size() == 2, "Enabling autostart on 2 layouts creates 2 Startup .lnk files: " + lnksAfterBoth);
                for (Path lnk : lnksAfterBoth) {
                    String[] inspected = LayoutShortcuts.inspect(lnk);
                    check(inspected[1].contains("--layout ") && inspected[1].contains("--data-dir"),
                            "Autostart .lnk points to --layout with --data-dir: " + inspected[1]);
                }

                // SavedLayoutsDialog UI check (both checkboxes exist & reflect state)
                fx(() -> {
                    SavedLayoutsDialog dlg = new SavedLayoutsDialog(w);
                    Stage dlgStage = (Stage) field(dlg, "stage");
                    CheckBox createCb = (CheckBox) field(dlg, "currentAutostart");
                    CheckBox manageCb = (CheckBox) field(dlg, "existingAutostart");
                    check(createCb != null && "Start automatically when Windows signs in".equals(createCb.getText()),
                            "SavedLayoutsDialog has creation autostart checkbox");
                    check(manageCb != null && "Autostart when Windows signs in".equals(manageCb.getText()) && manageCb.isSelected(),
                            "SavedLayoutsDialog management autostart checkbox reflects selected autostart layout");
                    dlg.dispose();
                    check(!dlgStage.isShowing(), "SavedLayoutsDialog stayed hidden and disposed cleanly");
                    return null;
                });

                // Rename layoutA updates its Startup .lnk; disable autostart on layoutA removes only layoutA's .lnk!
                fx(() -> { w.renameSavedLayout(layoutAId, "Renamed Receiving"); return null; });
                List<Path> lnksAfterRename = Files.list(startupDir).filter(f -> f.toString().endsWith(".lnk")).toList();
                check(lnksAfterRename.size() == 2 && lnksAfterRename.stream().anyMatch(f -> f.getFileName().toString().contains("Renamed Receiving")),
                        "Renaming autostart layout updates its Startup .lnk filename");

                fx(() -> { w.setSavedLayoutAutostart(layoutAId, false); return null; });
                List<Path> lnksAfterDisableOne = Files.list(startupDir).filter(f -> f.toString().endsWith(".lnk")).toList();
                check(lnksAfterDisableOne.size() == 1 && LayoutShortcuts.inspect(lnksAfterDisableOne.getFirst())[1].contains(layoutBId.toString()),
                        "Disabling autostart on Layout A removes only Layout A's .lnk while keeping Layout B's .lnk");

                // Verify StateStore format 21 persists autostart flags cleanly
                AtomicBoolean autostartPersisted = new AtomicBoolean(false);
                fx(() -> {
                    w.persistSettings(() -> autostartPersisted.set(true), err -> { throw new AssertionError(err); });
                    return null;
                });
                UsabilityIterationSmoke.settle(autostartPersisted::get, "Settings persisted");
                StateStore.State afterSave = store.load();
                check(afterSave.savedLayouts().stream().filter(l -> l.id().equals(layoutBId)).findFirst().orElseThrow().autostart(),
                        "StateStore v21 persists autostart=true on Layout B");
                check(!afterSave.savedLayouts().stream().filter(l -> l.id().equals(layoutAId)).findFirst().orElseThrow().autostart(),
                        "StateStore v21 persists autostart=false on Layout A");

                // --- Stuck / Reset Callout Verification (0ms synchronous dismissal on Enter AND button) ---
                fx(() -> {
                    SessionPane active = w.active();
                    ByteArrayOutputStream gdsOut = new ByteArrayOutputStream();
                    DemoScreens.command(gdsOut, 4, 0x40, 4, 0x11, 0, 8);
                    DemoScreens.at(gdsOut, 1, 1, 0x22, "NUMERIC CHECK");
                    DemoScreens.field(gdsOut, 4, 1, 8, 0x43, 0x24);
                    DemoScreens.command(gdsOut, 4, 0x42, 0, 0);
                    byte[] numericPacket = DemoScreens.gds(3, gdsOut.toByteArray());
                    Session5250 core = (Session5250) field(active, "core");
                    core.accept(numericPacket);
                    check(!active.type('X') && active.recoverableInputError(),
                            "Typing alpha into signed-numeric field enters recoverable input-inhibited state");
                    FxTestSupport.layout(w);
                    check(active.resetCalloutBox().isVisible(), "Reset callout pops up immediately when Reset appears");
                    check("Are you stuck? Press Enter or press this button!".equals(active.resetCalloutLabel().getText()),
                            "Reset callout displays exact helpful message");
                    check(!active.resetCalloutButton().isFocusTraversable(),
                            "Reset callout button is non-focus-traversable so TerminalView keeps focus");

                    // 1) Press Enter -> must dismiss synchronously in 0 ms!
                    KeyEvent enterPress = new KeyEvent(active.view(), active.view(), KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false);
                    Event.fireEvent(active.view(), enterPress);
                    check(!active.recoverableInputError() && !active.resetCalloutBox().isVisible(),
                            "Reset callout disappears immediately (0 ms) upon pressing Enter");

                    // 2) Re-trigger input error -> callout reappears -> click [Reset] button -> dismisses in 0 ms!
                    check(!active.type('X') && active.recoverableInputError(),
                            "Second numeric rejection re-enters stuck state");
                    FxTestSupport.layout(w);
                    check(active.resetCalloutBox().isVisible(), "Reset callout reappears on next stuck state");
                    active.resetCalloutButton().fire();
                    check(!active.recoverableInputError() && !active.resetCalloutBox().isVisible(),
                            "Reset callout disappears immediately (0 ms) upon clicking inline [Reset] button");
                    return null;
                });

                // --- Preferences > General System-Wide Install & Kiosk Controls Verification ---
                fx(() -> {
                    Stage prefsDialog = w.preferencesDialog("General");
                    FxTestSupport.layout(prefsDialog);
                    Preferences prefs = (Preferences) field(w, "preferences");
                    Node sysBtn = prefsDialog.getScene().getRoot().lookup("#prefs-install-system");
                    Node kioskBtn = prefsDialog.getScene().getRoot().lookup("#prefs-kiosk-toggle");
                    check(sysBtn instanceof Button b && b.getText().contains("Install system-wide"),
                            "Preferences > General offers Install system-wide (Administrator)...");
                    check(kioskBtn instanceof Button b && b.getText().contains("Enable Kiosk Mode"),
                            "Preferences > General offers Enable Kiosk Mode (Administrator)...");
                    prefs.forceDispose();
                    return null;
                });

                // --- Admin-Controlled Kiosk Mode Full Lockdown & Live Unlock Verification ---
                byte[] diskBytesBeforeKiosk = Files.readAllBytes(store.file());
                try {
                    fx(() -> {
                        KioskMode.setTestOverride(true);
                        return null;
                    });

                    // Launch & CLI guards
                    check(Main.isKioskLaunchBlocked(false, null), "Bare Fish launch is blocked in Kiosk mode");
                    check(Main.isKioskLaunchBlocked(true, null), "--demo launch is blocked in Kiosk mode");
                    check(!Main.isKioskLaunchBlocked(false, layoutBId), "--layout <uuid> launch is allowed in Kiosk mode");

                    // Offline --config apply blocked in Kiosk mode
                    Path exportedXml = root.resolve("kiosk-candidate.xml");
                    Files.write(exportedXml, diskBytesBeforeKiosk);
                    ByteArrayOutputStream cfgOut = new ByteArrayOutputStream(), cfgErr = new ByteArrayOutputStream();
                    int cfgRc = Configuration.run(new String[]{"apply", "--data-dir", dir.toString(), "--file", exportedXml.toString()},
                            new PrintStream(cfgOut), new PrintStream(cfgErr));
                    check(cfgRc != 0 && cfgErr.toString(StandardCharsets.UTF_8).contains("Kiosk mode"),
                            "--config apply is rejected when Kiosk mode is enabled");

                    fx(() -> {
                        // Menus & top controls locked down
                        MenuBar bar = w.menuBar();
                        List<String> menuTitles = bar.getMenus().stream().map(Menu::getText).toList();
                        check(menuTitles.equals(List.of("Edit", "Inserts")),
                                "Kiosk mode hides Session, Panes, View, Host, Help, leaving only [Edit, Inserts]: got " + menuTitles);
                        Menu editMenu = bar.getMenus().get(0);
                        List<String> editItemTexts = editMenu.getItems().stream()
                                .filter(i -> !(i instanceof SeparatorMenuItem))
                                .map(MenuItem::getText).toList();
                        check(editItemTexts.equals(List.of("Copy", "Paste", "Disable Kiosk Mode...")),
                                "Kiosk Edit menu shows only Copy, Paste, Disable Kiosk Mode...: got " + editItemTexts);

                        Node toolsBox = (Node) field(w.chrome(), "tools");
                        check(!toolsBox.isVisible() && !toolsBox.isManaged(),
                                "Kiosk mode hides [Profile | +] selector/add toolbar");

                        // Fish logo becomes a static dumb icon (clicking does NOT toggle Flux)
                        ButtonBase logo = (ButtonBase) field(w.chrome(), "caption");
                        String effectBefore = w.active().profile().effect();
                        logo.fire();
                        check(effectBefore.equals(w.active().profile().effect()),
                                "Fish logo is a static dumb icon in Kiosk mode and does not enable Flux");

                        // Pane controls, splitters, zoom, add/close panes locked
                        int paneCountBefore = w.sessionPanes().size();
                        w.split(true);
                        w.closePane();
                        w.zoom(0.3);
                        check(w.sessionPanes().size() == paneCountBefore && w.active().view().isFit(),
                                "Add pane, close pane, and zoom are locked in Kiosk mode");
                        for (SessionPane sp : w.sessionPanes()) {
                            Button closeBtn = (Button) field(sp, "close");
                            ComboBox<?> presets = (ComboBox<?>) field(sp, "presets");
                            check(!closeBtn.isVisible(), "Pane close button is hidden in Kiosk mode");
                            check(presets.isDisabled(), "Offline profile selector is disabled in Kiosk mode");
                            List<String> ctxItems = sp.clipboardMenu().getItems().stream()
                                    .filter(i -> !(i instanceof SeparatorMenuItem)).map(MenuItem::getText).toList();
                            check(ctxItems.stream().allMatch(t -> t.startsWith("Copy") || t.startsWith("Paste") || t.startsWith("Reset")),
                                    "Kiosk terminal context menu only exposes Copy, Paste, Reset: " + ctxItems);
                        }

                        // Window resizing is allowed in-memory, while lasting disk saves are suppressed!
                        w.stage().setWidth(1240);
                        w.stage().setHeight(810);
                        app.scheduleSave();
                        return null;
                    });
                    Thread.sleep(320);
                    byte[] diskBytesDuringKiosk = Files.readAllBytes(store.file());
                    check(Arrays.equals(diskBytesBeforeKiosk, diskBytesDuringKiosk),
                            "Kiosk mode suppresses lasting workspace saves to disk while allowing window move/resize");

                    // Live Admin Unlock -> immediately restores full menus, [Profile | +], pane controls & zoom!
                    fx(() -> {
                        KioskMode.setTestToggleHandler(enable -> {
                            KioskMode.setTestOverride(enable);
                            return true;
                        });
                        boolean changed = KioskMode.setEnabledWithAdmin(false);
                        check(changed && !KioskMode.isEnabled(), "Admin unlock disables Kiosk mode");
                        MenuBar bar = w.menuBar();
                        List<String> unlockedMenus = bar.getMenus().stream().map(Menu::getText).toList();
                        check(unlockedMenus.equals(List.of("Session", "Edit", "Inserts", "Panes", "View", "Host", "Help")),
                                "Live unlock restores all 7 menus without restart: " + unlockedMenus);
                        Node toolsBox = (Node) field(w.chrome(), "tools");
                        check(toolsBox.isVisible() && toolsBox.isManaged(), "Live unlock restores [Profile | +]");
                        return null;
                    });
                } finally {
                    KioskMode.setTestToggleHandler(null);
                    KioskMode.setTestAdminAuthorizer(null);
                    KioskMode.setTestOverride(null);
                }
        } finally {
            fx(() -> { app.quit(); return null; });
            check(closed.await(10, TimeUnit.SECONDS), "Autostart/Kiosk ApplicationWindows closed and released StateStore cleanly");
            if (prevStartup == null) System.clearProperty("fish.test.startupDir"); else System.setProperty("fish.test.startupDir", prevStartup);
            if (prevExe == null) System.clearProperty("fish.test.executable"); else System.setProperty("fish.test.executable", prevExe);
        }
    }

    private static void verifyStartupFactoryResetAndInstallScripts(Path root) throws Exception {
        Path startupDir = Files.createDirectory(root.resolve("reset-startup"));
        Path fakeExe = Files.writeString(Files.createDirectory(root.resolve("reset-bin")).resolve("Fish.exe"), "MZ-fake");
        String prevStartup = System.getProperty("fish.test.startupDir");
        String prevExe = System.getProperty("fish.test.executable");
        System.setProperty("fish.test.startupDir", startupDir.toString());
        System.setProperty("fish.test.executable", fakeExe.toString());
        try {
            // 1. Corrupt workspace.xml -> Choice.DELETE_CONFIG wipes config & Startup .lnk and boots clean default state!
            Path corruptDir = Files.createDirectory(root.resolve("corrupt-ws"));
            Files.writeString(corruptDir.resolve("workspace.xml"), "<<<CORRUPT-XML");
            Files.writeString(corruptDir.resolve("workspace-recovery-old.xml"), "old-backup");
            UUID staleLayoutId = UUID.randomUUID();
            LayoutShortcuts.create(startupDir.resolve("Stale Layout.lnk"), fakeExe, corruptDir, staleLayoutId);
            check(Files.exists(startupDir.resolve("Stale Layout.lnk")), "Seeded workspace Startup .lnk before factory reset");

            try (StateStore store = new StateStore(corruptDir)) {
                StateStore.State recovered = WorkspaceRecovery.load(store, new WorkspaceRecovery.Decisions() {
                    @Override public WorkspaceRecovery.Choice choose(String error, boolean retry) { return WorkspaceRecovery.Choice.DELETE_CONFIG; }
                    @Override public boolean confirm() { return true; }
                    @Override public void failed(String error) { throw new AssertionError(error); }
                });
                check(recovered != null && recovered.profiles().size() == 1,
                        "Choice.DELETE_CONFIG recovers corrupt workspace to clean default state");
                check(!Files.exists(corruptDir.resolve("workspace-recovery-old.xml")),
                        "Choice.DELETE_CONFIG deletes old workspace recovery artifacts");
                check(!Files.exists(startupDir.resolve("Stale Layout.lnk")),
                        "Choice.DELETE_CONFIG removes Startup layout shortcuts for that workspace");
            }

            // 2. Kiosk admin gate on startup-error factory reset: denied without admin, allowed with admin!
            Path kioskResetDir = Files.createDirectory(root.resolve("kiosk-reset-ws"));
            Files.writeString(kioskResetDir.resolve("workspace.xml"), "important-kiosk-config");
            try {
                KioskMode.setTestOverride(true);
                AtomicReference<String> deniedMsg = new AtomicReference<>("");
                boolean denied = WorkspaceRecovery.recoverStartupError(kioskResetDir, null, null, true, true, () -> false, deniedMsg::set);
                check(!denied && Files.exists(kioskResetDir.resolve("workspace.xml"))
                        && deniedMsg.get().contains("Administrator"),
                        "Kiosk mode blocks startup factory reset when Administrator permission is declined");

                AtomicBoolean anyErr = new AtomicBoolean(false);
                boolean allowed = WorkspaceRecovery.recoverStartupError(kioskResetDir, null, null, true, true, () -> true, m -> anyErr.set(true));
                check(allowed && !anyErr.get()
                        && !Files.readString(kioskResetDir.resolve("workspace.xml")).contains("important-kiosk-config"),
                        "Kiosk mode permits startup factory reset once Administrator authorizes it");
            } finally {
                KioskMode.setTestOverride(null);
            }

            // 3. Startup error dialog exposes #startup-factory-reset button without showing window
            fx(() -> {
                AtomicReference<Boolean> picked = new AtomicReference<>(false);
                Stage dlg = WorkspaceRecovery.startupErrorDialog("Synthetic startup failure", picked::set);
                Node resetBtn = dlg.getScene().getRoot().lookup("#startup-factory-reset");
                check(resetBtn instanceof Button b && "Factory reset (delete config)...".equals(b.getText()),
                        "Startup error dialog exposes Factory reset (delete config)... button");
                dlg.close();
                return null;
            });

            // 4. Hermetic PowerShell Install-Fish.ps1 (-AllUsers & per-user) + Uninstall-Fish.ps1 config deletion test!
            Path psSandbox = Files.createDirectory(root.resolve("ps-install-sandbox"));
            Path fakeSource = Files.createDirectory(psSandbox.resolve("src-image"));
            Files.createDirectories(fakeSource.resolve("app"));
            Files.createDirectories(fakeSource.resolve("runtime"));
            Files.writeString(fakeSource.resolve("Fish.exe"), "MZ-fake-app");
            Files.copy(Path.of("Install-Fish.ps1"), fakeSource.resolve("Install-Fish.ps1"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(Path.of("Uninstall-Fish.ps1"), fakeSource.resolve("Uninstall-Fish.ps1"), StandardCopyOption.REPLACE_EXISTING);

            Path userDest = psSandbox.resolve("UserInstall");
            Path sysDest = psSandbox.resolve("ProgramFilesFish");
            Path testAppData = Files.createDirectories(psSandbox.resolve("AppData"));
            Path testProgData = Files.createDirectories(psSandbox.resolve("ProgramData"));
            Path testStartup = Files.createDirectories(psSandbox.resolve("Startup"));
            Path testUserProgs = Files.createDirectories(psSandbox.resolve("UserPrograms"));
            Path testUserDesk = Files.createDirectories(psSandbox.resolve("UserDesktop"));
            Path testCommonProgs = Files.createDirectories(psSandbox.resolve("CommonPrograms"));
            Path testCommonDesk = Files.createDirectories(psSandbox.resolve("CommonDesktop"));

            Map<String, String> env = new LinkedHashMap<>();
            env.put("FISH_TEST_NO_ELEVATE", "1");
            env.put("FISH_TEST_SKIP_REGISTRY", "1");
            env.put("FISH_TEST_SKIP_CREDENTIALS", "1");
            env.put("FISH_TEST_APPDATA", testAppData.toString());
            env.put("FISH_TEST_PROGRAMDATA", testProgData.toString());
            env.put("FISH_TEST_STARTUP", testStartup.toString());
            env.put("FISH_TEST_USER_PROGRAMS", testUserProgs.toString());
            env.put("FISH_TEST_USER_DESKTOP", testUserDesk.toString());
            env.put("FISH_TEST_COMMON_PROGRAMS", testCommonProgs.toString());
            env.put("FISH_TEST_COMMON_DESKTOP", testCommonDesk.toString());

            // Per-user install + seed %APPDATA%\Fish\workspace.xml + Startup .lnk -> Uninstall deletes config & shortcuts!
            runPowerShell(env, "-File", Path.of("Install-Fish.ps1").toAbsolutePath().toString(),
                    "-Source", fakeSource.toString(), "-Destination", userDest.toString(), "-DesktopShortcut", "-Quiet");
            check(Files.exists(userDest.resolve("Fish.exe"))
                    && Files.exists(testUserProgs.resolve("Fish.lnk"))
                    && Files.exists(testUserDesk.resolve("Fish.lnk")),
                    "Per-user Install-Fish.ps1 installs Fish.exe and Start Menu/Desktop shortcuts");

            Files.createDirectories(testAppData.resolve("Fish"));
            Files.writeString(testAppData.resolve("Fish").resolve("workspace.xml"), "<properties/>");
            LayoutShortcuts.create(testStartup.resolve("Floor Auto.lnk"), userDest.resolve("Fish.exe"),
                    testAppData.resolve("Fish"), UUID.randomUUID());

            runPowerShell(env, "-File", Path.of("Uninstall-Fish.ps1").toAbsolutePath().toString(),
                    "-Destination", userDest.toString(), "-Quiet");
            check(!Files.exists(userDest.resolve("Fish.exe"))
                    && !Files.exists(testAppData.resolve("Fish"))
                    && !Files.exists(testStartup.resolve("Floor Auto.lnk"))
                    && !Files.exists(testUserProgs.resolve("Fish.lnk"))
                    && !Files.exists(testUserDesk.resolve("Fish.lnk")),
                    "Uninstall-Fish.ps1 deletes Fish installation, %APPDATA%\\Fish config, Startup layout .lnk, and shortcuts");

            // System-wide -AllUsers install + Uninstall -AllUsers!
            Files.createDirectories(testAppData.resolve("Fish"));
            Files.writeString(testAppData.resolve("Fish").resolve("workspace.xml"), "<properties/>");
            Files.createDirectories(testProgData.resolve("Fish"));
            Files.writeString(testProgData.resolve("Fish").resolve("machine.xml"), "<properties/>");

            runPowerShell(env, "-File", Path.of("Install-Fish.ps1").toAbsolutePath().toString(),
                    "-Source", fakeSource.toString(), "-Destination", sysDest.toString(), "-AllUsers", "-DesktopShortcut", "-Quiet");
            check(Files.exists(sysDest.resolve("Fish.exe"))
                    && Files.exists(testCommonProgs.resolve("Fish.lnk"))
                    && Files.exists(testCommonDesk.resolve("Fish.lnk")),
                    "Install-Fish.ps1 -AllUsers installs system-wide Fish.exe and Common Start Menu/Desktop shortcuts");

            runPowerShell(env, "-File", Path.of("Uninstall-Fish.ps1").toAbsolutePath().toString(),
                    "-Destination", sysDest.toString(), "-AllUsers", "-Quiet");
            check(!Files.exists(sysDest.resolve("Fish.exe"))
                    && !Files.exists(testAppData.resolve("Fish"))
                    && !Files.exists(testProgData.resolve("Fish"))
                    && !Files.exists(testCommonProgs.resolve("Fish.lnk"))
                    && !Files.exists(testCommonDesk.resolve("Fish.lnk")),
                    "Uninstall-Fish.ps1 -AllUsers deletes system-wide install, %APPDATA%\\Fish, %ProgramData%\\Fish, and Common shortcuts");
        } finally {
            if (prevStartup == null) System.clearProperty("fish.test.startupDir"); else System.setProperty("fish.test.startupDir", prevStartup);
            if (prevExe == null) System.clearProperty("fish.test.executable"); else System.setProperty("fish.test.executable", prevExe);
        }
    }

    private static void runPowerShell(Map<String, String> extraEnv, String... psArgs) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("powershell.exe");
        cmd.add("-NoProfile");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        Collections.addAll(cmd, psArgs);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(extraEnv);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = proc.waitFor();
        check(code == 0, "PowerShell command failed (" + code + "): " + String.join(" ", psArgs) + "\n" + output);
    }

    public static void main(String[] args) throws Exception {
        Ui.install();
        check("0.10.0".equals(Main.VERSION), "Main.VERSION is 0.10.0: " + Main.VERSION);
        Path root = Files.createTempDirectory(Path.of("build"), "zero-ten-smoke-");
        try (DemoHost host = new DemoHost()) {
            verifyScrollBindingsAndRecorder(root);
            verifyEightPaneZoomAndHoveredWheel(root, host.port());
            verifyAutostartAndKioskAndResetCallout(root, host.port());
            verifyStartupFactoryResetAndInstallScripts(root);
            check(host.failures().isEmpty(), "Zero synthetic TN5250 host failures: " + host.failures());
            check(Window.getWindows().stream().noneMatch(Window::isShowing), "Zero visible desktop windows during hermetic suite");
        } finally {
            KioskMode.setTestToggleHandler(null);
            KioskMode.setTestAdminAuthorizer(null);
            KioskMode.setTestOverride(null);
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
        }
        System.out.println("PASS Fish 0.10.0 zoom/wheel/autostart/kiosk/reset/recovery/system-install checks: " + checks);
        Ui.finishTestRun();
    }
}
