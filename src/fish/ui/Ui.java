package fish.ui;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.stage.*;
import fish.model.*;

/** JavaFX platform lifecycle, live CSS theme stylesheet engine, vector icons, and dialog shells. */
@SuppressWarnings({"deprecation", "removal"})
public final class Ui {
    public static java.awt.Color BACKGROUND, PANEL, FIELD, HOVER, LINE, TEXT, MUTED, FOCUS, ERROR, SELECTION;
    private record StylesheetKey(AppTheme theme, ColorTheme colors, int scaleMillis) {}

    private static AppTheme theme = AppTheme.Charcoal;
    private static ColorTheme colors = ColorTheme.builtin(theme);
    private static boolean custom;
    private static double scaleOverride;
    private static String currentStylesheet = "";
    private static final Map<StylesheetKey, String> STYLESHEET_CACHE = new ConcurrentHashMap<>();
    private static final Set<Scene> SCENES = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Scene, Integer> SCENE_SCALE_MILLIS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Stage> STAGES = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Window> SCALED_WINDOWS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final List<Runnable> THEME_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Runnable> MOTION_LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean reducedMotion;
    private static boolean popupAnimationsInstalled;

    private Ui() {}

    static {
        updatePaletteFields(theme);
        currentStylesheet = stylesheetForScale(1.0);
    }

    private static java.awt.Color awtColor(int rgb) { return new java.awt.Color(rgb); }

    private static void updatePaletteFields(AppTheme value) {
        theme = value;
        BACKGROUND = awtColor(colors.rgb(ColorRole.BACKGROUND));
        PANEL = awtColor(colors.rgb(ColorRole.PANEL));
        FIELD = awtColor(colors.rgb(ColorRole.FIELD));
        HOVER = awtColor(colors.rgb(ColorRole.HOVER));
        LINE = awtColor(colors.rgb(ColorRole.DIVIDER));
        TEXT = awtColor(colors.rgb(ColorRole.TEXT));
        MUTED = awtColor(colors.rgb(ColorRole.MUTED));
        FOCUS = awtColor(colors.rgb(ColorRole.FOCUS));
        ERROR = awtColor(colors.rgb(ColorRole.ERROR));
        SELECTION = awtColor(colors.rgb(ColorRole.SELECTION));
    }

    public static AppTheme theme() { return theme; }
    public static java.awt.Color color(ColorRole role) { return awtColor(colors.rgb(role)); }
    public static javafx.scene.paint.Color fxColor(ColorRole role) {
        int rgb = colors.rgb(role);
        return javafx.scene.paint.Color.rgb((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
    }
    public static String hex(ColorRole role) { return String.format("#%06X", colors.rgb(role) & 0xffffff); }
    public static String hex(java.awt.Color c) { return String.format("#%06X", c.getRGB() & 0xffffff); }
    public static ColorTheme colorTheme() { return colors; }
    public static boolean customTheme() { return custom; }
    public static boolean dark() {
        return (BACKGROUND.getRed() * .2126 + BACKGROUND.getGreen() * .7152 + BACKGROUND.getBlue() * .0722) < 128;
    }
    public static java.awt.Color paneBackground(boolean active) {
        return color(active ? ColorRole.ACTIVE_TINT : ColorRole.BACKGROUND);
    }

    /** Pure-JavaFX bootstrap nested class so Main can start Prism/Glass in parallel before AWT initializes Ui. */
    public static final class FxBoot {
        static volatile boolean fxStarted;
        private static CompletableFuture<Void> fxStartupFuture;
        private FxBoot() {}

        public static synchronized CompletableFuture<Void> startAsync() {
            System.setProperty("javafx.enablePreview", "true");
            System.setProperty("javafx.suppressPreviewWarning", "true");
            if (fxStarted) return CompletableFuture.completedFuture(null);
            if (fxStartupFuture != null) return fxStartupFuture;
            CompletableFuture<Void> future = new CompletableFuture<>();
            fxStartupFuture = future;
            if (Platform.isFxApplicationThread()) {
                Platform.setImplicitExit(false);
                fxStarted = true;
                future.complete(null);
                return future;
            }
            Thread boot = new Thread(() -> {
                try {
                    Platform.startup(() -> {
                        Platform.setImplicitExit(false);
                        fxStarted = true;
                        future.complete(null);
                    });
                } catch (IllegalStateException alreadyRunning) {
                    CountDownLatch latch = new CountDownLatch(1);
                    Platform.runLater(() -> {
                        Platform.setImplicitExit(false);
                        latch.countDown();
                    });
                    try { latch.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    fxStarted = true;
                    future.complete(null);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }, "fish-fx-bootstrap");
            boot.setDaemon(false);
            boot.start();
            return future;
        }

        static synchronized void shutdown() {
            if (fxStarted) {
                fxStarted = false;
                fxStartupFuture = null;
                Platform.exit();
            }
        }
    }

    public static CompletableFuture<Void> startToolkitAsync() {
        return FxBoot.startAsync();
    }

    public static void install() {
        install(AppTheme.Charcoal, null);
    }

    public static synchronized void install(AppTheme initialTheme, ColorTheme customTheme) {
        try {
            FxBoot.startAsync().get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("JavaFX startup interrupted.", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("JavaFX toolkit startup failed.", e);
        }
        if (Boolean.getBoolean("fish.testMode") && !Boolean.getBoolean("fish.embeddedJvm")
                && "main".equals(Thread.currentThread().getName())
                && Thread.currentThread().getUncaughtExceptionHandler() == Thread.currentThread().getThreadGroup()) {
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> {
                error.printStackTrace();
                System.exit(1);
            });
        }
        AppTheme target = initialTheme == null ? AppTheme.Charcoal : initialTheme;
        runOnFx(() -> applyTheme(target, customTheme));
    }

    public static boolean isFxStarted() { return FxBoot.fxStarted; }

    public static synchronized void shutdown() {
        FxBoot.shutdown();
    }

    public static void finishTestRun() {
        if (!Boolean.getBoolean("fish.embeddedJvm")) shutdown();
    }

    public static void runLater(Runnable work) {
        if (FxBoot.fxStarted) Platform.runLater(work);
        else work.run();
    }

    public static void runOnFx(Runnable work) {
        if (!FxBoot.fxStarted || Platform.isFxApplicationThread()) work.run();
        else runAndWait(work);
    }

    public static void runAndWait(Runnable work) {
        if (!FxBoot.fxStarted || Platform.isFxApplicationThread()) {
            work.run();
            return;
        }
        runAndWaitDirect(work);
    }

    private static void runAndWaitDirect(Runnable work) {
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = {null};
        Platform.runLater(() -> {
            try { work.run(); }
            catch (Throwable t) { failure[0] = t; }
            finally { done.countDown(); }
        });
        try {
            if (!done.await(120, TimeUnit.SECONDS))
                throw new IllegalStateException("Timed out waiting for JavaFX Application Thread.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for JavaFX Application Thread.", e);
        }
        if (failure[0] instanceof RuntimeException re) throw re;
        if (failure[0] instanceof Error err) throw err;
        if (failure[0] != null) throw new IllegalStateException(failure[0]);
    }

    public static <T> T callAndWait(Callable<T> work) throws Exception {
        if (!FxBoot.fxStarted || Platform.isFxApplicationThread()) return work.call();
        CountDownLatch done = new CountDownLatch(1);
        Object[] box = {null};
        Throwable[] failure = {null};
        Platform.runLater(() -> {
            try { box[0] = work.call(); }
            catch (Throwable t) { failure[0] = t; }
            finally { done.countDown(); }
        });
        if (!done.await(120, TimeUnit.SECONDS))
            throw new TimeoutException("Timed out waiting for JavaFX Application Thread.");
        if (failure[0] instanceof Exception ex) throw ex;
        if (failure[0] instanceof Error err) throw err;
        @SuppressWarnings("unchecked")
        T result = (T) box[0];
        return result;
    }

    public static void dispatchSessionRecord(BooleanSupplier alive, Runnable work) throws InterruptedException {
        if (!FxBoot.fxStarted || Platform.isFxApplicationThread()) {
            if (alive.getAsBoolean()) work.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = {null};
        Platform.runLater(() -> {
            try { if (alive.getAsBoolean()) work.run(); }
            catch (Throwable t) { failure[0] = t; }
            finally { done.countDown(); }
        });
        while (alive.getAsBoolean() && !done.await(40, TimeUnit.MILLISECONDS)) {
            // Poll so closing a session never blocks indefinitely if the UI is shutting down.
        }
        if (failure[0] instanceof RuntimeException re) throw re;
        if (failure[0] instanceof Error err) throw err;
    }

    public static void onThemeChanged(Runnable listener) { THEME_LISTENERS.add(listener); }
    public static void removeThemeListener(Runnable listener) { THEME_LISTENERS.remove(listener); }

    public static boolean reducedMotion() { return reducedMotion; }
    public static void onMotionChanged(Runnable listener) { MOTION_LISTENERS.add(listener); }
    public static void setReducedMotion(boolean value) {
        boolean changed = reducedMotion != value;
        reducedMotion = value;
        if (value && FxBoot.fxStarted && Platform.isFxApplicationThread()) {
            for (Window w : Window.getWindows()) {
                if (w.getScene() != null) resetMotion(w.getScene().getRoot());
            }
        }
        if (changed) {
            for (Runnable listener : MOTION_LISTENERS) listener.run();
        }
    }

    public static boolean canAnimate(Node node) {
        return !reducedMotion && node != null
                && node.getScene() != null
                && node.getScene().getWindow() != null
                && node.getScene().getWindow().isShowing();
    }

    public static void resetMotion(Node node) {
        if (node == null) return;
        if (node.getProperties().remove("fish.motionAnim") instanceof javafx.animation.Animation running) {
            running.stop();
        }
        node.setOpacity(1.0);
        node.setTranslateY(0.0);
    }

    public static void animateSubtle(Node node, double fromOpacity, double fromTranslateY, double durationMs) {
        if (node == null) return;
        if (node.getProperties().remove("fish.motionAnim") instanceof javafx.animation.Animation running) {
            running.stop();
        }
        if (!canAnimate(node)) {
            node.setOpacity(1.0);
            node.setTranslateY(0.0);
            return;
        }
        node.setOpacity(fromOpacity);
        node.setTranslateY(fromTranslateY);
        javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(durationMs),
                        new javafx.animation.KeyValue(node.opacityProperty(), 1.0, javafx.animation.Interpolator.EASE_OUT),
                        new javafx.animation.KeyValue(node.translateYProperty(), 0.0, javafx.animation.Interpolator.EASE_OUT)));
        timeline.setOnFinished(e -> {
            node.setOpacity(1.0);
            node.setTranslateY(0.0);
            node.getProperties().remove("fish.motionAnim", timeline);
        });
        node.getProperties().put("fish.motionAnim", timeline);
        timeline.play();
    }

    private static void installPopupAnimations() {
        if (popupAnimationsInstalled || !Platform.isFxApplicationThread()) return;
        popupAnimationsInstalled = true;
        java.util.function.Consumer<Window> attach = w -> {
            if (w instanceof PopupControl popup && !(popup instanceof Tooltip)
                    && popup.getProperties().putIfAbsent("fish.popupMotion", Boolean.TRUE) == null) {
                popup.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> {
                    if (popup.getScene() != null) {
                        syncSceneStylesheet(popup.getScene());
                        animateSubtle(popup.getScene().getRoot(), 0.0, -2.5, 80);
                    }
                });
                popup.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> {
                    if (popup.getScene() != null) resetMotion(popup.getScene().getRoot());
                });
                if (popup.isShowing() && popup.getScene() != null) {
                    syncSceneStylesheet(popup.getScene());
                    animateSubtle(popup.getScene().getRoot(), 0.0, -2.5, 80);
                }
            }
        };
        for (Window w : Window.getWindows()) attach.accept(w);
        Window.getWindows().addListener((javafx.collections.ListChangeListener<Window>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (Window w : change.getAddedSubList()) attach.accept(w);
                }
            }
        });
    }

    public static void registerStage(Stage stage) {
        installPopupAnimations();
        STAGES.add(stage);
        stage.getIcons().setAll(FishIcon.fxImages());
    }

    public static List<Stage> stages() {
        return List.copyOf(STAGES);
    }

    public static double hairline(double scale) {
        if (scale <= 1.0) return 1.0;
        return Math.floor(99_999.0 / scale) / 100_000.0;
    }

    public static String px(double value) {
        return String.format(Locale.ROOT, "%.5fpx", value);
    }

    public static void setScaleOverride(double scale) {
        scaleOverride = scale > 0 ? scale : 0;
    }

    public static double scaleOverride() {
        return scaleOverride;
    }

    public static double scaleFor(Node node) {
        return scaleFor(node == null ? null : node.getScene());
    }

    public static double scaleFor(Scene scene) {
        if (scaleOverride > 0) return scaleOverride;
        if (scene != null && scene.getWindow() != null && scene.getWindow().isShowing()) {
            Window w = scene.getWindow();
            double rs = w.getRenderScaleX();
            double os = w.getOutputScaleX();
            if (rs > 0 && rs != 1.0) return rs;
            if (os > 0) return os;
            if (rs > 0) return rs;
        }
        return 1.0;
    }

    public static String stylesheetForScale(double scale) {
        int scaleMillis = Math.max(1000, (int) Math.round(scale * 1000.0));
        return STYLESHEET_CACHE.computeIfAbsent(
                new StylesheetKey(theme, colors, scaleMillis),
                k -> buildStylesheetUri(k.scaleMillis() / 1000.0));
    }

    public static void requestScaleLayout(Parent root) {
        if (root == null) return;
        root.requestLayout();
        for (Node child : root.getChildrenUnmodifiable()) {
            if (child instanceof Parent p) requestScaleLayout(p);
        }
    }

    public static void syncSceneStylesheet(Scene scene) {
        if (scene == null) return;
        double scale = scaleFor(scene);
        int scaleMillis = Math.max(1000, (int) Math.round(scale * 1000.0));
        String target = stylesheetForScale(scale);
        if (scene.getStylesheets().size() != 1 || !target.equals(scene.getStylesheets().getFirst())) {
            scene.getStylesheets().setAll(target);
            Integer prevScale = SCENE_SCALE_MILLIS.put(scene, scaleMillis);
            if (prevScale == null || prevScale != scaleMillis) {
                requestScaleLayout(scene.getRoot());
            }
        }
    }

    private static void attachWindowScale(Scene scene, Window window) {
        if (window == null) return;
        syncSceneStylesheet(scene);
        if (SCALED_WINDOWS.add(window)) {
            window.showingProperty().addListener((o, a, b) -> {
                if (window.getScene() != null) syncSceneStylesheet(window.getScene());
            });
            window.outputScaleXProperty().addListener((o, a, b) -> {
                if (window.getScene() != null) syncSceneStylesheet(window.getScene());
            });
            window.renderScaleXProperty().addListener((o, a, b) -> {
                if (window.getScene() != null) syncSceneStylesheet(window.getScene());
            });
        }
    }

    public static Scene styleScene(Scene scene) {
        installPopupAnimations();
        SCENES.add(scene);
        scene.setFill(fxColor(ColorRole.BACKGROUND));
        syncSceneStylesheet(scene);
        scene.windowProperty().addListener((obs, oldW, newW) -> attachWindowScale(scene, newW));
        if (scene.getWindow() != null) attachWindowScale(scene, scene.getWindow());
        return scene;
    }

    public static void setStyleIfChanged(Node node, String style) {
        if (!Objects.equals(node.getStyle(), style)) node.setStyle(style);
    }

    public static void setTextIfChanged(Labeled node, String text) {
        if (!Objects.equals(node.getText(), text)) node.setText(text);
    }

    public static void setTooltipText(Control control, Tooltip tooltip, String text) {
        if (!Objects.equals(tooltip.getText(), text)) tooltip.setText(text);
        if (control.getTooltip() != tooltip) control.setTooltip(tooltip);
    }

    public static String stylesheetUri() { return currentStylesheet; }
    public static String activeStylesheetUrl() { return currentStylesheet; }

    public static void applyTheme(AppTheme value) { applyTheme(value, null); }

    public static void applyTheme(AppTheme value, ColorTheme override) {
        ColorTheme nextColors = override == null ? ColorTheme.builtin(value) : override;
        boolean nextCustom = override != null;
        if (value == theme && nextCustom == custom && Objects.equals(nextColors, colors)
                && SCENES.isEmpty() && STAGES.isEmpty() && THEME_LISTENERS.isEmpty()) {
            currentStylesheet = stylesheetForScale(scaleFor((Scene) null));
            return;
        }
        colors = nextColors;
        custom = nextCustom;
        updatePaletteFields(value);
        HostPalette.clear();
        currentStylesheet = stylesheetForScale(scaleFor((Scene) null));
        Runnable apply = () -> {
            javafx.scene.paint.Color fill = fxColor(ColorRole.BACKGROUND);
            List<javafx.scene.image.Image> icons = FishIcon.fxImages();
            for (Scene scene : new ArrayList<>(SCENES)) {
                if (scene != null) {
                    scene.setFill(fill);
                    syncSceneStylesheet(scene);
                }
            }
            for (Stage stage : new ArrayList<>(STAGES)) {
                if (stage != null && !stage.getIcons().equals(icons)) {
                    stage.getIcons().setAll(icons);
                }
            }
            WindowBorder.refresh();
            for (Runnable listener : THEME_LISTENERS) listener.run();
            for (Scene scene : new ArrayList<>(SCENES)) {
                if (scene != null && scene.getRoot() != null && scene.getWindow() != null && scene.getWindow().isShowing()) {
                    scene.getRoot().applyCss();
                }
            }
        };
        if (FxBoot.fxStarted && !Platform.isFxApplicationThread()) runAndWaitDirect(apply);
        else apply.run();
    }

    private static String buildStylesheetUri(double scale) {
        String px = String.format(Locale.ROOT, "%.5f", hairline(scale));
        String splitInset = String.format(Locale.ROOT, "%.5f", Math.max(0.5, 1.5 - hairline(scale) * 0.5));
        String cssTemplate = """
            .root {
                -fish-bg: %s;
                -fish-panel: %s;
                -fish-field: %s;
                -fish-dialog: %s;
                -fish-text: %s;
                -fish-muted: %s;
                -fish-disabled: %s;
                -fish-hover: %s;
                -fish-pressed: %s;
                -fish-focus: %s;
                -fish-error: %s;
                -fish-selection: %s;
                -fish-selection-text: %s;
                -fish-chrome: %s;
                -fish-chrome-text: %s;
                -fish-chrome-inactive: %s;
                -fish-menu: %s;
                -fish-menu-text: %s;
                -fish-menu-hover: %s;
                -fish-menu-selected-text: %s;
                -fish-tooltip: %s;
                -fish-tooltip-text: %s;
                -fish-header: %s;
                -fish-header-text: %s;
                -fish-header-muted: %s;
                -fish-footer: %s;
                -fish-footer-text: %s;
                -fish-status-error: %s;
                -fish-status-wait: %s;
                -fish-active-tint: %s;
                -fish-divider: %s;
                -fish-divider-bg: %s;
                -fx-base: -fish-panel;
                -fx-background: -fish-dialog;
                -fx-control-inner-background: -fish-field;
                -fx-control-inner-background-alt: -fish-panel;
                -fx-accent: -fish-selection;
                -fx-focus-color: -fish-focus;
                -fx-faint-focus-color: transparent;
                -fx-text-base-color: -fish-text;
                -fx-text-background-color: -fish-text;
                -fx-text-inner-color: -fish-text;
                -fx-selection-bar: -fish-selection;
                -fx-selection-bar-non-focused: -fish-selection;
                -fx-selection-bar-text: -fish-selection-text;
                -fx-font-family: "Segoe UI";
                -fx-font-size: 12px;
            }
            .fish-dialog-shell {
                -fx-background-color: -fish-dialog;
                -fx-border-color: -fish-divider;
                -fx-border-width: __PX__;
            }
            .fish-dialog-shell.fish-dwm-border {
                -fx-border-width: 0;
            }
            .fish-window-shell {
                -fx-background-color: -fish-bg;
            }
            .fish-window-outlined {
                -fx-border-color: -fish-divider;
                -fx-border-width: 0;
            }
            .fish-window-outlined.fish-dwm-border {
                -fx-border-width: 0;
            }
            .fish-header-bar {
                -fx-background-color: -fish-chrome;
                -fx-border-color: transparent transparent -fish-divider transparent;
                -fx-border-width: 0 0 __PX__ 0;
            }
            .fish-workspace-header {
                -fx-border-width: 0;
            }
            .label, .text {
                -fx-text-fill: -fish-text;
            }
            .fish-muted {
                -fx-text-fill: -fish-muted !important;
            }
            .fish-error {
                -fx-text-fill: -fish-error !important;
            }
            .fish-section-title {
                -fx-font-weight: bold;
                -fx-font-size: 12.5px;
                -fx-text-fill: -fish-text;
            }
            .fish-page-heading {
                -fx-font-weight: bold;
                -fx-font-size: 16px;
                -fx-text-fill: -fish-text;
            }
            .button, .toggle-button {
                -fx-background-color: -fish-divider, -fish-field;
                -fx-background-insets: 0, __PX__;
                -fx-background-radius: 4, 3;
                -fx-text-fill: -fish-text;
                -fx-padding: 5 12 5 12;
                -fx-cursor: hand;
            }
            .button:hover, .toggle-button:hover {
                -fx-background-color: -fish-divider, -fish-hover;
            }
            .button:armed, .toggle-button:armed, .toggle-button:selected {
                -fx-background-color: -fish-focus, -fish-pressed;
            }
            .button:focused, .toggle-button:focused {
                -fx-background-color: -fish-focus, -fish-field;
            }
            .button:default {
                -fx-background-color: -fish-focus, -fish-selection;
                -fx-text-fill: -fish-selection-text;
            }
            .button:default:hover {
                -fx-background-color: -fish-focus, -fish-hover;
            }
            .button:disabled, .toggle-button:disabled {
                -fx-opacity: 1.0;
                -fx-text-fill: -fish-disabled;
                -fx-background-color: -fish-divider, -fish-panel;
            }
            .fish-icon-button {
                -fx-background-color: transparent;
                -fx-background-insets: 0;
                -fx-background-radius: 4;
                -fx-padding: 4 6 4 6;
            }
            .fish-icon-button:hover {
                -fx-background-color: -fish-hover;
            }
            .fish-icon-button:armed {
                -fx-background-color: -fish-pressed;
            }
            .fish-icon-button:focused {
                -fx-border-color: transparent transparent -fish-focus transparent;
                -fx-border-width: 0 0 __PX__ 0;
            }
            .fish-chrome-button {
                -fx-background-color: transparent;
                -fx-background-insets: 0;
                -fx-background-radius: 0;
                -fx-text-fill: -fish-chrome-text;
                -fx-padding: 4 9 4 9;
                -fx-focus-traversable: true;
            }
            .fish-chrome-button:hover {
                -fx-background-color: -fish-menu-hover;
            }
            .fish-chrome-button:armed {
                -fx-background-color: -fish-pressed;
            }
            .fish-chrome-button:focused {
                -fx-border-color: transparent transparent -fish-focus transparent;
                -fx-border-width: 0 0 __PX__ 0;
            }
            .fish-window-close:hover, .fish-window-close:armed {
                -fx-background-color: -fish-error;
            }
            .text-field, .password-field, .text-area {
                -fx-background-color: -fish-divider, -fish-field;
                -fx-background-insets: 0, __PX__;
                -fx-background-radius: 4, 3;
                -fx-text-fill: -fish-text;
                -fx-prompt-text-fill: -fish-muted;
                -fx-highlight-fill: -fish-selection;
                -fx-highlight-text-fill: -fish-selection-text;
            }
            .text-field:focused, .password-field:focused, .text-area:focused {
                -fx-background-color: -fish-focus, -fish-field;
            }
            .text-area .content {
                -fx-background-color: -fish-field;
                -fx-background-radius: 3;
            }
            .fish-tutorial-card.text-area {
                -fx-background-color: transparent;
                -fx-background-insets: 0;
                -fx-background-radius: 0;
                -fx-padding: 0;
            }
            .fish-tutorial-card.text-area .content {
                -fx-background-color: transparent;
                -fx-background-radius: 0;
                -fx-padding: 2 0 2 0;
            }
            .fish-tutorial-card.text-area:focused {
                -fx-background-color: transparent;
            }
            .invalid-field {
                -fx-background-color: -fish-error, -fish-field !important;
            }
            .combo-box-base {
                -fx-background-color: -fish-divider, -fish-field;
                -fx-background-insets: 0, __PX__;
                -fx-background-radius: 4, 3;
            }
            .combo-box-base:hover {
                -fx-background-color: -fish-divider, -fish-hover;
            }
            .combo-box-base:focused {
                -fx-background-color: -fish-focus, -fish-field;
            }
            .combo-box .list-cell {
                -fx-text-fill: -fish-text;
                -fx-background-color: transparent;
                -fx-padding: 4 8 4 8;
            }
            .combo-box-popup .list-view {
                -fx-background-color: -fish-divider, -fish-menu;
                -fx-background-insets: 0, __PX__;
                -fx-background-radius: 5, 4;
                -fx-padding: 3;
                -fx-effect: null;
            }
            .combo-box-popup .list-view .list-cell {
                -fx-background-color: transparent;
                -fx-background-radius: 3;
                -fx-text-fill: -fish-menu-text;
                -fx-padding: 5 10 5 10;
            }
            .combo-box-popup .list-view .list-cell:hover,
            .combo-box-popup .list-view .list-cell:filled:selected {
                -fx-background-color: -fish-menu-hover;
                -fx-text-fill: -fish-menu-selected-text;
            }
            .fish-chrome-combo {
                -fx-background-color: transparent;
                -fx-background-insets: 0;
                -fx-background-radius: 0;
            }
            .fish-chrome-combo:hover {
                -fx-background-color: -fish-menu-hover;
            }
            .fish-chrome-combo:showing {
                -fx-background-color: -fish-pressed;
            }
            .fish-chrome-combo:focused {
                -fx-border-color: transparent transparent -fish-focus transparent;
                -fx-border-width: 0 0 __PX__ 0;
            }
            .fish-chrome-combo > .list-cell {
                -fx-text-fill: -fish-chrome-text;
                -fx-alignment: center-left;
                -fx-padding: 2 6 2 8;
            }
            .fish-chrome-pill-combo {
                -fx-background-color: -fish-divider, -fish-field;
                -fx-background-insets: 0, __PX__ 0 __PX__ __PX__;
                -fx-background-radius: 4 0 0 4, 3 0 0 3;
                -fx-border-width: 0;
            }
            .fish-chrome-pill-combo:hover {
                -fx-background-color: -fish-divider, -fish-hover;
            }
            .fish-chrome-pill-combo:showing {
                -fx-background-color: -fish-focus, -fish-pressed;
            }
            .fish-chrome-pill-combo:focused {
                -fx-background-color: -fish-focus, -fish-field;
                -fx-border-width: 0;
            }
            .fish-chrome-pill-combo > .list-cell {
                -fx-text-fill: -fish-chrome-text;
                -fx-alignment: center-left;
                -fx-padding: 2 4 2 9;
            }
            .fish-chrome-pill-combo > .arrow-button {
                -fx-padding: 0 7 0 4;
            }
            .fish-chrome-pill-join {
                -fx-background-color: -fish-divider, -fish-field;
                -fx-background-insets: 0, __PX__ 0 __PX__ 0;
                -fx-background-radius: 0;
                -fx-padding: 0;
            }
            .fish-chrome-pill-add {
                -fx-background-color: -fish-divider, -fish-field;
                -fx-background-insets: 0, __PX__ __PX__ __PX__ 0;
                -fx-background-radius: 0 4 4 0, 0 3 3 0;
                -fx-padding: 0 8 0 8;
                -fx-border-width: 0;
            }
            .fish-chrome-pill-add:hover {
                -fx-background-color: -fish-divider, -fish-hover;
            }
            .fish-chrome-pill-add:armed {
                -fx-background-color: -fish-focus, -fish-pressed;
            }
            .fish-chrome-pill-add:focused {
                -fx-background-color: -fish-focus, -fish-field;
                -fx-border-width: 0;
            }
            .fish-chrome-pill-add:disabled {
                -fx-background-color: -fish-divider, -fish-panel;
            }
            .check-box {
                -fx-text-fill: -fish-text;
            }
            .check-box:disabled {
                -fx-opacity: 1.0;
                -fx-text-fill: -fish-disabled;
            }
            .check-box > .box {
                -fx-background-color: -fish-divider, -fish-field;
                -fx-background-insets: 0, __PX__;
                -fx-background-radius: 3, 2;
                -fx-padding: 3;
            }
            .check-box:focused > .box {
                -fx-background-color: -fish-focus, -fish-field;
            }
            .check-box:selected > .box > .mark {
                -fx-background-color: -fish-focus;
            }
            .check-box:disabled > .box > .mark {
                -fx-background-color: -fish-disabled;
            }
            .menu-bar {
                -fx-background-color: transparent;
                -fx-padding: 0 2 0 2;
                -fx-border-width: 0;
            }
            .menu-bar > .container > .menu-button {
                -fx-background-color: transparent;
                -fx-background-insets: 4 1 4 1;
                -fx-background-radius: 4;
                -fx-padding: 6 10 6 10;
            }
            .menu-bar > .container > .menu-button > .label {
                -fx-text-fill: -fish-chrome-text;
            }
            .menu-bar > .container > .menu-button:hover,
            .menu-bar > .container > .menu-button:focused,
            .menu-bar > .container > .menu-button:showing {
                -fx-background-color: -fish-menu-hover;
            }
            .context-menu {
                -fx-background-color: -fish-divider, -fish-menu;
                -fx-background-insets: 0, __PX__;
                -fx-background-radius: 5, 4;
                -fx-padding: 4 3 4 3;
                -fx-effect: null;
            }
            .menu-item {
                -fx-background-color: transparent;
                -fx-background-radius: 3;
                -fx-padding: 5 12 5 12;
            }
            .menu-item > .label {
                -fx-text-fill: -fish-menu-text;
            }
            .menu-item:focused, .menu-item:hover {
                -fx-background-color: -fish-menu-hover;
            }
            .menu-item:focused > .label, .menu-item:hover > .label {
                -fx-text-fill: -fish-menu-selected-text;
            }
            .menu-item > .accelerator-text,
            .menu-item:focused > .accelerator-text,
            .menu-item:hover > .accelerator-text {
                -fx-text-fill: -fish-muted;
                -fx-font-size: 11px;
                -fx-padding: 0 0 0 22;
            }
            .menu-item:disabled {
                -fx-opacity: 1.0;
            }
            .menu-item:disabled > .label {
                -fx-text-fill: -fish-disabled;
            }
            .menu-item:disabled > .accelerator-text {
                -fx-text-fill: -fish-disabled;
                -fx-font-size: 11px;
                -fx-padding: 0 0 0 22;
            }
            .separator > .line {
                -fx-border-color: -fish-divider transparent transparent transparent;
                -fx-border-width: __PX__ 0 0 0;
                -fx-border-insets: 0;
            }
            .separator:vertical > .line {
                -fx-border-color: transparent transparent transparent -fish-divider;
                -fx-border-width: 0 0 0 __PX__;
            }
            .list-view, .table-view {
                -fx-background-color: -fish-divider, -fish-field;
                -fx-background-insets: 0, __PX__;
                -fx-background-radius: 4, 3;
                -fx-table-cell-border-color: -fish-divider;
            }
            .list-view:focused, .table-view:focused {
                -fx-background-color: -fish-focus, -fish-field;
            }
            .list-cell {
                -fx-background-color: -fish-field;
                -fx-text-fill: -fish-text;
                -fx-padding: 5 10 5 10;
            }
            .list-cell:filled:hover {
                -fx-background-color: -fish-hover;
            }
            .list-cell:filled:selected {
                -fx-background-color: -fish-selection;
                -fx-text-fill: -fish-selection-text;
            }
            .fish-sidebar.list-view {
                -fx-background-color: -fish-panel;
                -fx-background-insets: 0;
                -fx-background-radius: 0;
                -fx-padding: 6 6 6 6;
                -fx-border-color: transparent -fish-divider transparent transparent;
                -fx-border-width: 0 __PX__ 0 0;
            }
            .fish-sidebar.list-view:focused {
                -fx-background-color: -fish-panel;
            }
            .fish-sidebar .list-cell {
                -fx-background-color: transparent;
                -fx-background-radius: 5;
                -fx-min-height: 32;
                -fx-pref-height: 32;
                -fx-padding: 0 12 0 12;
                -fx-alignment: center-left;
            }
            .fish-sidebar .list-cell:filled:hover {
                -fx-background-color: -fish-hover;
            }
            .fish-sidebar .list-cell:filled:selected {
                -fx-background-color: -fish-selection;
                -fx-background-radius: 5;
                -fx-text-fill: -fish-selection-text;
            }
            .fish-settings-footer {
                -fx-border-color: -fish-divider transparent transparent transparent;
                -fx-border-width: __PX__ 0 0 0;
                -fx-padding: 10 0 0 0;
            }
            .table-view .column-header-background,
            .table-view .column-header,
            .table-view .filler {
                -fx-background-color: -fish-panel;
                -fx-border-color: transparent transparent -fish-divider transparent;
                -fx-border-width: 0 0 __PX__ 0;
            }
            .table-view .column-header .label {
                -fx-text-fill: -fish-muted;
                -fx-alignment: center-left;
                -fx-padding: 5 8 5 8;
            }
            .table-row-cell {
                -fx-background-color: -fish-field;
                -fx-border-color: transparent transparent -fish-divider transparent;
                -fx-border-width: 0 0 __PX__ 0;
            }
            .table-row-cell:filled:selected {
                -fx-background-color: -fish-selection;
            }
            .table-row-cell:filled:selected .table-cell {
                -fx-text-fill: -fish-selection-text;
            }
            .tab-pane > .tab-header-area > .tab-header-background {
                -fx-background-color: -fish-panel;
                -fx-border-color: transparent transparent -fish-divider transparent;
                -fx-border-width: 0 0 __PX__ 0;
            }
            .tab-pane > .tab-header-area .tab {
                -fx-background-color: -fish-panel;
                -fx-background-insets: 0;
                -fx-background-radius: 0;
                -fx-padding: 6 14 6 14;
            }
            .tab-pane > .tab-header-area .tab:selected {
                -fx-background-color: -fish-hover;
                -fx-border-color: transparent transparent -fish-focus transparent;
                -fx-border-width: 0 0 __PX__ 0;
            }
            .tab-pane > .tab-header-area .tab .tab-label {
                -fx-text-fill: -fish-text;
            }
            .scroll-pane {
                -fx-background-color: transparent;
                -fx-padding: 0;
            }
            .scroll-pane > .viewport {
                -fx-background-color: transparent;
            }
            .fish-markdown-view.scroll-pane {
                -fx-background-color: -fish-divider, -fish-field;
                -fx-background-insets: 0, __PX__;
                -fx-background-radius: 4, 3;
                -fx-padding: __PX__;
            }
            .fish-markdown-view.scroll-pane > .viewport {
                -fx-background-color: -fish-field;
                -fx-background-radius: 3;
            }
            .scroll-bar {
                -fx-background-color: -fish-panel;
                -fx-pref-width: 10;
                -fx-pref-height: 10;
            }
            .scroll-bar > .increment-button, .scroll-bar > .decrement-button {
                -fx-padding: 0;
                -fx-pref-width: 0;
                -fx-pref-height: 0;
                -fx-opacity: 0;
            }
            .scroll-bar > .thumb {
                -fx-background-color: -fish-divider;
                -fx-background-insets: 2;
                -fx-background-radius: 4;
            }
            .scroll-bar > .thumb:hover, .scroll-bar > .thumb:pressed {
                -fx-background-color: -fish-muted;
            }
            .split-pane {
                -fx-background-color: transparent;
                -fx-padding: 0;
            }
            .split-pane > .split-pane-divider {
                -fx-padding: 0 1.5 0 1.5;
                -fx-background-color: transparent, -fish-divider;
                -fx-background-insets: 0, 0 __SPLIT_INSET__ 0 __SPLIT_INSET__;
            }
            .split-pane:vertical > .split-pane-divider {
                -fx-padding: 1.5 0 1.5 0;
                -fx-background-color: transparent, -fish-divider;
                -fx-background-insets: 0, __SPLIT_INSET__ 0 __SPLIT_INSET__ 0;
            }
            .split-pane > .split-pane-divider:hover {
                -fx-background-color: transparent, -fish-focus;
            }
            .progress-bar > .track {
                -fx-background-color: -fish-divider, -fish-field;
                -fx-background-insets: 0, __PX__;
                -fx-background-radius: 3, 2;
            }
            .progress-bar > .bar {
                -fx-background-color: -fish-focus;
                -fx-background-insets: __PX__;
                -fx-background-radius: 2;
            }
            .tooltip {
                -fx-background-color: -fish-divider, -fish-tooltip;
                -fx-background-insets: 0, __PX__;
                -fx-background-radius: 4, 3;
                -fx-text-fill: -fish-tooltip-text;
                -fx-padding: 5 9 5 9;
                -fx-effect: null;
            }
            .hyperlink {
                -fx-text-fill: -fish-focus;
                -fx-border-color: transparent;
                -fx-padding: 0;
            }
            .fish-notice {
                -fx-background-color: -fish-footer;
                -fx-border-color: -fish-divider;
                -fx-border-width: __PX__;
                -fx-padding: 4 8 4 10;
            }
            .fish-zoom-hud {
                -fx-background-color: -fish-divider, -fish-tooltip;
                -fx-background-insets: 0, __PX__;
                -fx-background-radius: 6, 5;
                -fx-text-fill: -fish-tooltip-text;
                -fx-font-size: 12.5px;
                -fx-font-weight: bold;
                -fx-padding: 5 11 5 11;
                -fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.28), 8, 0.1, 0, 2);
            }
            .fish-reset-callout {
                -fx-background-color: -fish-status-error, -fish-panel;
                -fx-background-insets: 0, __PX__;
                -fx-background-radius: 6, 5;
                -fx-padding: 6 8 6 11;
                -fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.30), 10, 0.1, 0, 3);
            }
            .fish-reset-callout > .label {
                -fx-text-fill: -fish-text;
                -fx-font-size: 12px;
            }
            """;
        String css = cssTemplate
                .replace("__PX__", px)
                .replace("__SPLIT_INSET__", splitInset)
                .formatted(
                    hex(ColorRole.BACKGROUND), hex(ColorRole.PANEL), hex(ColorRole.FIELD), hex(ColorRole.DIALOG),
                    hex(ColorRole.TEXT), hex(ColorRole.MUTED), hex(ColorRole.DISABLED),
                    hex(ColorRole.HOVER), hex(ColorRole.PRESSED), hex(ColorRole.FOCUS), hex(ColorRole.ERROR),
                    hex(ColorRole.SELECTION), hex(ColorRole.SELECTION_TEXT),
                    hex(ColorRole.CHROME), hex(ColorRole.CHROME_TEXT), hex(ColorRole.CHROME_INACTIVE),
                    hex(ColorRole.MENU), hex(ColorRole.MENU_TEXT), hex(ColorRole.MENU_HOVER), hex(ColorRole.MENU_SELECTED_TEXT),
                    hex(ColorRole.TOOLTIP), hex(ColorRole.TOOLTIP_TEXT),
                    hex(ColorRole.HEADER), hex(ColorRole.HEADER_TEXT), hex(ColorRole.HEADER_MUTED),
                    hex(ColorRole.FOOTER), hex(ColorRole.FOOTER_TEXT), hex(ColorRole.STATUS_ERROR), hex(ColorRole.STATUS_WAIT),
                    hex(ColorRole.ACTIVE_TINT), hex(ColorRole.DIVIDER), hex(ColorRole.DIVIDER_BACKGROUND));
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
    }

    public static Node icon(String kind) {
        return icon(kind, ColorRole.TEXT);
    }

    public static SVGPath icon(String kind, ColorRole colorRole) {
        SVGPath svg = new SVGPath();
        svg.setContent(switch (kind) {
            case "close" -> "M 2 2 L 10 10 M 10 2 L 2 10";
            case "plus" -> "M 1 6 L 11 6 M 6 1 L 6 11";
            case "search" -> "M 7.5 7.5 L 11 11 M 8 4.5 A 3.5 3.5 0 1 1 1 4.5 A 3.5 3.5 0 1 1 8 4.5";
            case "connect" -> "M 1 6 L 10 6 M 6 2 L 10 6 L 6 10";
            case "right" -> "M 4 2 L 8 6 L 4 10";
            case "down" -> "M 2 4 L 6 8 L 10 4";
            case "minimize" -> "M 1 6 L 10 6";
            case "maximize" -> "M 1.5 1.5 H 10.5 V 10.5 H 1.5 Z";
            case "restore" -> "M 3.5 1.5 H 10.5 V 8.5 H 8.5 M 1.5 3.5 H 8.5 V 10.5 H 1.5 Z";
            case "eye" -> "M 1 6 C 2.8 2.8 9.2 2.8 11 6 C 9.2 9.2 2.8 9.2 1 6 Z M 7.5 6 A 1.5 1.5 0 1 1 4.5 6 A 1.5 1.5 0 1 1 7.5 6";
            case "eye-off" -> "M 1.5 1.5 L 10.5 10.5 M 1 6 C 2.8 2.8 9.2 2.8 11 6 C 9.8 8.1 7.8 9 6 9";
            case "install" -> "M 6 1 V 8 M 3 5.5 L 6 8.5 L 9 5.5 M 1.5 10.5 H 10.5";
            default -> "M 2 4 L 6 8 L 10 4";
        });
        svg.setFill(javafx.scene.paint.Color.TRANSPARENT);
        svg.setStrokeWidth(1.35);
        svg.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        svg.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        svg.getProperties().put("fish.iconRole", colorRole);
        svg.setStroke(fxColor(colorRole));
        svg.disabledProperty().addListener((o, a, b) -> applyIconStroke(svg));
        WeakReference<SVGPath> ref = new WeakReference<>(svg);
        Runnable listener = new Runnable() {
            @Override public void run() {
                SVGPath target = ref.get();
                if (target == null) THEME_LISTENERS.remove(this);
                else applyIconStroke(target);
            }
        };
        onThemeChanged(listener);
        return svg;
    }

    private static void applyIconStroke(SVGPath svg) {
        ColorRole role = (ColorRole) svg.getProperties().getOrDefault("fish.iconRole", ColorRole.TEXT);
        svg.setStroke(svg.isDisabled() ? fxColor(ColorRole.DISABLED) : fxColor(role));
    }

    public static void updateIcon(Node graphic, String kind, ColorRole role) {
        if (graphic instanceof SVGPath svg) {
            String path = switch (kind) {
                case "close" -> "M 2 2 L 10 10 M 10 2 L 2 10";
                case "plus" -> "M 1 6 L 11 6 M 6 1 L 6 11";
                case "minimize" -> "M 1 6 L 10 6";
                case "maximize" -> "M 1.5 1.5 H 10.5 V 10.5 H 1.5 Z";
                case "restore" -> "M 3.5 1.5 H 10.5 V 8.5 H 8.5 M 1.5 3.5 H 8.5 V 10.5 H 1.5 Z";
                default -> svg.getContent();
            };
            if (!Objects.equals(svg.getContent(), path)) svg.setContent(path);
            svg.getProperties().put("fish.iconRole", role);
            applyIconStroke(svg);
        }
    }

    public static void updateIcon(ButtonBase button, String kind, ColorRole role) {
        if (button.getGraphic() instanceof SVGPath) {
            updateIcon(button.getGraphic(), kind, role);
        } else {
            button.setGraphic(icon(kind, role));
        }
    }

    public static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.setMnemonicParsing(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    public static Button iconButton(String accessibleName, String kind, Runnable action) {
        return iconButton(accessibleName, kind, ColorRole.TEXT, action);
    }

    public static Button iconButton(String accessibleName, String kind, ColorRole role, Runnable action) {
        Button b = new Button();
        b.setGraphic(icon(kind, role));
        b.getStyleClass().add("fish-icon-button");
        b.setTooltip(new Tooltip(accessibleName));
        b.setAccessibleText(accessibleName);
        b.setMinSize(25, 24);
        b.setPrefSize(25, 24);
        b.setOnAction(e -> action.run());
        return b;
    }

    public static HBox searchBox(TextField field, String placeholder) {
        field.setPromptText(placeholder);
        SVGPath glass = icon("search", ColorRole.MUTED);
        Button clear = iconButton("Clear search", "close", ColorRole.MUTED, () -> {
            field.clear();
            field.requestFocus();
        });
        clear.setFocusTraversable(false);
        clear.visibleProperty().bind(field.textProperty().isNotEmpty());
        clear.managedProperty().bind(clear.visibleProperty());
        HBox wrapper = new HBox(6, glass, field, clear);
        wrapper.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        return wrapper;
    }

    public static HBox revealablePassword(PasswordField passwordField) {
        TextField plain = new TextField();
        plain.textProperty().bindBidirectional(passwordField.textProperty());
        plain.promptTextProperty().bind(passwordField.promptTextProperty());
        plain.tooltipProperty().bind(passwordField.tooltipProperty());
        plain.disableProperty().bind(passwordField.disableProperty());
        plain.setManaged(false);
        plain.setVisible(false);
        StackPane stack = new StackPane(passwordField, plain);
        HBox.setHgrow(stack, Priority.ALWAYS);
        ToggleButton toggle = new ToggleButton();
        toggle.getStyleClass().add("fish-icon-button");
        toggle.setGraphic(icon("eye", ColorRole.MUTED));
        toggle.setTooltip(new Tooltip("Show or hide typed password"));
        toggle.setAccessibleText("Show typed password");
        toggle.disableProperty().bind(passwordField.disableProperty());
        toggle.selectedProperty().addListener((obs, was, show) -> {
            plain.setManaged(show);
            plain.setVisible(show);
            passwordField.setManaged(!show);
            passwordField.setVisible(!show);
            toggle.setGraphic(icon(show ? "eye-off" : "eye", ColorRole.MUTED));
            if (show) { plain.requestFocus(); plain.positionCaret(plain.getText().length()); }
            else { passwordField.requestFocus(); passwordField.positionCaret(passwordField.getText().length()); }
        });
        HBox box = new HBox(4, stack, toggle);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static void role(Node node, String roleName) {
        node.getStyleClass().removeAll("fish-muted", "fish-error");
        if ("error".equals(roleName)) node.getStyleClass().add("fish-error");
        else if ("muted".equals(roleName)) node.getStyleClass().add("fish-muted");
    }

    public static void markInvalid(Node control, boolean invalid) {
        if (invalid) {
            if (!control.getStyleClass().contains("invalid-field")) control.getStyleClass().add("invalid-field");
        } else {
            control.getStyleClass().remove("invalid-field");
        }
    }

    /**
     * Attaches OpenJFX {@link HeaderBar#setButtonType} while preventing {@code ButtonBehavior}
     * from double-firing {@code onAction} on primary mouse clicks already handled by
     * {@code HeaderButtonBehavior}. Programmatic {@code button.fire()} and keyboard activation
     * continue to invoke {@code onAction} normally.
     */
    public static void bindHeaderButton(Button button, HeaderButtonType type) {
        HeaderBar.setButtonType(button, type);
        button.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY
                    && button.getLayoutBounds().contains(e.getX(), e.getY())
                    && button.getScene() != null
                    && button.getScene().getWindow() instanceof Stage) {
                button.disarm();
            }
        });
    }

    public static Stage dialogStage(Window owner, String title, boolean modal) {
        Stage stage = new Stage(StageStyle.EXTENDED);
        if (owner != null) stage.initOwner(owner);
        stage.initModality(modal ? Modality.WINDOW_MODAL : Modality.NONE);
        stage.setTitle(title);
        HeaderBar.setPrefButtonHeight(stage, 0);
        registerStage(stage);
        return stage;
    }

    public static Scene decorateDialog(Stage dialog, Region content, Runnable dismiss, double prefW, double prefH) {
        HeaderBar.setPrefButtonHeight(dialog, 0);
        Label titleLabel = new Label(dialog.getTitle());
        dialog.titleProperty().addListener((o, a, b) -> titleLabel.setText(b));
        titleLabel.setPadding(new Insets(0, 8, 0, 12));
        BorderPane dragArea = new BorderPane();
        dragArea.setLeft(titleLabel);
        BorderPane.setAlignment(titleLabel, Pos.CENTER_LEFT);
        HeaderBar.setDragType(dragArea, HeaderDragType.DRAGGABLE);
        HeaderBar.setDragType(titleLabel, HeaderDragType.DRAGGABLE);

        Button close = iconButton("Close dialog", "close", ColorRole.CHROME_TEXT, dismiss);
        close.getStyleClass().remove("fish-icon-button");
        close.getStyleClass().addAll("fish-chrome-button", "fish-window-close");
        close.setPrefSize(38, 31);
        bindHeaderButton(close, HeaderButtonType.CLOSE);

        HeaderBar header = new HeaderBar(null, dragArea, close);
        header.getStyleClass().add("fish-header-bar");
        header.setMinHeight(32);
        header.setPrefHeight(32);

        BorderPane shell = new BorderPane();
        shell.getStyleClass().add("fish-dialog-shell");
        shell.setTop(header);
        shell.setCenter(content);

        Scene scene = prefW > 0 && prefH > 0 ? new Scene(shell, prefW, prefH) : new Scene(shell);
        styleScene(scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                dismiss.run();
            }
        });
        dialog.setOnCloseRequest(e -> {
            e.consume();
            dismiss.run();
        });
        dialog.setScene(scene);
        dialog.showingProperty().addListener((o, a, showing) -> {
            if (showing && WindowBorder.refresh()) {
                if (!shell.getStyleClass().contains("fish-dwm-border")) shell.getStyleClass().add("fish-dwm-border");
            } else {
                shell.getStyleClass().remove("fish-dwm-border");
            }
        });
        dialog.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> animateSubtle(shell, 0.0, 3.0, 90));
        dialog.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> resetMotion(shell));
        shell.applyCss();
        return scene;
    }

    public static boolean form(Window owner, String title, Node body, String acceptLabel) {
        return prompt(owner, title, body, acceptLabel, true);
    }

    public static boolean confirm(Window owner, String message, String title) {
        return prompt(owner, title, messageNode(message), "OK", true);
    }

    public static void message(Window owner, String message, String title) {
        prompt(owner, title, messageNode(message), "Close", false);
    }

    public static void error(Window owner, String message) {
        runOnFx(() -> message(owner, message, "Fish"));
    }

    public static String input(Window owner, String title, String message) {
        String[] result = {null};
        runOnFx(() -> {
            TextField field = new TextField();
            field.setPrefColumnCount(28);
            Label label = new Label(message);
            label.setLabelFor(field);
            VBox box = new VBox(10, label, field);
            if (prompt(owner, title, box, "Send", true)) result[0] = field.getText();
        });
        return result[0];
    }

    private static Node messageNode(String text) {
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(true);
        area.setFocusTraversable(false);
        int lines = Math.min(18, Math.max(3, (int) text.lines().count() + 1));
        area.setPrefRowCount(lines);
        area.setPrefColumnCount(48);
        return area;
    }

    private static boolean prompt(Window owner, String title, Node body, String acceptLabel, boolean showCancel) {
        boolean[] accepted = {false};
        Stage dialog = dialogStage(owner, title, true);
        if (owner == null) dialog.initModality(Modality.APPLICATION_MODAL);
        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        if (showCancel) {
            Button cancel = button("Cancel", dialog::close);
            cancel.setCancelButton(true);
            actions.getChildren().add(cancel);
        }
        Button ok = button(acceptLabel, () -> {
            accepted[0] = true;
            dialog.close();
        });
        ok.setDefaultButton(true);
        actions.getChildren().add(ok);

        BorderPane wrapper = new BorderPane();
        wrapper.setPadding(new Insets(16));
        wrapper.setCenter(body);
        wrapper.setBottom(actions);
        BorderPane.setMargin(actions, new Insets(16, 0, 0, 0));

        decorateDialog(dialog, wrapper, dialog::close, -1, -1);
        dialog.sizeToScene();
        dialog.setMinWidth(380);
        dialog.showAndWait();
        return accepted[0];
    }
}
