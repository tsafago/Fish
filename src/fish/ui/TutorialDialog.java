package fish.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Popup;
import javafx.stage.PopupWindow;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import fish.model.ColorRole;
import fish.model.StateStore;

/** Pass-through highlights and explicit production-action events; never performs a host or credential operation. */
public final class TutorialDialog {
    public enum Guide {
        GETTING_STARTED("Getting started"),APPEARANCE("Appearance, colors and pane info"),
        PASSWORD_PROFILE("Non-Kerberos profile"),KEYBOARD("Keyboard shortcuts"),PANES("Pane tips and tricks"),
        DESKTOP_SHORTCUTS("Desktop shortcuts");
        public final String title;
        Guide(String title) { this.title=title; }
    }

    private record Step(String title,String text,String surface,String control,String event) {}

    private final Workspace workspace;
    private final boolean firstRun;
    private final Guide guide;
    private final List<Step> steps;
    private final Label title=new Label(),progress=new Label();
    private final Label text=new Label();
    private final VBox card=new VBox(8);
    // Used only when a small host cannot fit the card beside the target.
    private final Popup overflow=new Popup();
    private final Button back,next,tryIt,skipTutorial,skipStep;
    private final Rectangle highlightRect=new Rectangle();
    private final Pane highlightLayer=new Pane(highlightRect);
    private final ChangeListener<Number> geometryListener=(obs,oldV,newV) -> syncHighlightAndPosition();
    private final ChangeListener<Object> anchorBoundsListener=(obs,oldV,newV) -> syncHighlightAndPosition();
    private ProfilesEditor editor;
    private int step;
    private boolean ended,refreshing;
    private Node anchor,observedAnchor;
    private Pane observedRoot;
    private Window targetWindow;
    private Pane cardRoot;
    private boolean queued;
    private final ChangeListener<Boolean> focusChanged=(o,old,focused) -> queueRefresh();
    private final ListChangeListener<Window> windowsChanged=change -> {
        while (change.next()) {
            for (Window w:change.getAddedSubList()) if (w!=overflow && related(w)) {
                if (w instanceof Stage) w.focusedProperty().addListener(focusChanged);
                queueRefresh();
            }
            for (Window w:change.getRemoved()) if (w!=overflow && (w==targetWindow || related(w))) {
                if (w instanceof Stage) w.focusedProperty().removeListener(focusChanged);
                queueRefresh();
            }
        }
    };

    public TutorialDialog(Workspace workspace,boolean firstRun) { this(workspace,Guide.GETTING_STARTED,firstRun); }

    public TutorialDialog(Workspace workspace,Guide guide,boolean firstRun) {
        this.workspace=workspace;
        this.firstRun=firstRun;
        this.guide=guide;
        this.steps=steps(guide);
        if (firstRun) workspace.tutorialStatus(StateStore.Tutorial.IN_PROGRESS);

        highlightLayer.setMouseTransparent(true);
        highlightLayer.setManaged(false);
        highlightLayer.setViewOrder(-100.0);
        highlightRect.setMouseTransparent(true);
        highlightRect.setStrokeWidth(2.0);
        highlightRect.setArcWidth(8);
        highlightRect.setArcHeight(8);

        title.getStyleClass().add("fish-section-title");
        Ui.role(progress,"muted");
        BorderPane heading=new BorderPane();
        heading.setLeft(title);

        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);

        tryIt=Ui.button("Show control",this::showControl);
        skipTutorial=Ui.button("Skip tutorial",() -> finish(false));
        BorderPane contextual=new BorderPane();
        contextual.setLeft(tryIt);
        contextual.setRight(skipTutorial);

        back=Ui.button("Back",() -> go(step-1));
        skipStep=Ui.button("Skip step",this::advance);
        next=Ui.button("Next",this::advance);
        HBox navigation=new HBox(6,back,skipStep,next);
        navigation.setAlignment(Pos.CENTER_RIGHT);

        Button close=Ui.iconButton("Close tutorial","close",ColorRole.TEXT,() -> finish(false));
        HBox meta=new HBox(8,progress,close);
        meta.setAlignment(Pos.CENTER_RIGHT);
        heading.setRight(meta);
        card.setAccessibleText(guide.title+" tutorial");
        card.setPadding(new Insets(10,12,12,12));
        card.getStyleClass().add("fish-tutorial-guide");
        card.getChildren().setAll(heading,text,contextual,navigation);
        card.setManaged(false);
        card.setViewOrder(-101);
        card.addEventFilter(KeyEvent.KEY_PRESSED,e -> {
            if (e.getCode()==KeyCode.ESCAPE) { e.consume(); finish(false); }
        });
        overflow.setAutoHide(false);
        overflow.setHideOnEscape(false);
        overflow.setAutoFix(false);
        // PopupWindow normally forwards its owner's keystrokes to the popup
        // first. The overflow view is mouse-operated; keys still belong to
        // the open menu, combo or form, including Escape and IME composition.
        var dispatch=overflow.getScene().getEventDispatcher();
        overflow.getScene().setEventDispatcher((e,tail) -> e instanceof KeyEvent || e instanceof InputMethodEvent
                ? e : dispatch.dispatchEvent(e,tail));
        Window.getWindows().addListener(windowsChanged);
        for (Window w:Window.getWindows()) if (w instanceof Stage && related(w)) w.focusedProperty().addListener(focusChanged);
        go(0);
    }

    public Stage hostStage() { return cardRoot!=null && cardRoot.getScene().getWindow() instanceof Stage s ? s : workspace.stage(); }
    public Pane card() { return card; }
    static boolean insideCard(Object target) {
        for (Node n=target instanceof Node node ? node : null;n!=null;n=n.getParent())
            if (n.getStyleClass().contains("fish-tutorial-guide")) return true;
        return false;
    }
    public int currentStep() { return step; }
    public Guide guide() { return guide; }
    public Button tryItButton() { return tryIt; }
    public Button skipTutorialButton() { return skipTutorial; }
    public Button skipStepButton() { return skipStep; }
    public Button backButton() { return back; }
    public Button nextButton() { return next; }
    public Window targetWindow() { return targetWindow; }
    public Node currentAnchor() { return anchor; }
    public Pane highlightLayer() { return highlightLayer; }
    public Rectangle highlightRect() { return highlightRect; }

    private void queueRefresh() {
        if (!queued && !ended) {
            queued=true;
            Platform.runLater(() -> { queued=false; refreshTarget(); });
        }
    }

    public boolean related(Window window) {
        for (Window cur=window;cur!=null;cur=cur instanceof Stage s ? s.getOwner() :
                cur instanceof PopupWindow p ? p.getOwnerWindow() : null) {
            if (cur==workspace.stage()) return true;
        }
        return false;
    }

    public void go(int value) {
        step=Math.max(0,Math.min(steps.size()-1,value));
        refreshTarget();
    }

    private void advance() {
        if (step==steps.size()-1) finish(true);
        else go(step+1);
    }

    public void action(String event) {
        if (!ended) {
            if (steps.get(step).event().equals(event)) advance();
            else refreshTarget();
        }
    }

    private void showControl() {
        Step current=steps.get(step);
        switch (current.surface()) {
            case "Appearance","Profiles","Keyboard Shortcuts","General","Shortcuts" -> workspace.preferences(current.surface());
            case "settings" -> workspace.showTutorialSettings();
            case "Help","Panes","View","Session" -> workspace.showTutorialMenu(current.surface());
            default -> { if (anchor!=null) anchor.requestFocus(); }
        }
        refreshTarget();
    }

    private static Step s(String title,String text,String surface,String control,String event) {
        return new Step(title,text,surface,control,event);
    }

    private static List<Step> steps(Guide guide) {
        return switch (guide) {
            case GETTING_STARTED -> List.of(
                    s("Choose a profile","Pick a workstation profile in the top-bar selector beside +. Configure host or password profiles anytime in Edit > Settings > Profiles.","add-profile","","add-profile-selected"),
                    s("Open a session pane","Click + to connect the selected profile in a new pane (reusing an unused starter pane first).","add","","Add pane"),
                    s("Close a pane","Click X on a pane header to close that pane while keeping the last pane open offline.","close","","Close pane"),
                    s("Customize Fish","Open Edit > Settings to adjust themes, terminal colors, fonts, profiles, and shortcuts.","settings","","settings-Appearance"),
                    s("More guides","Open Help > Tutorials anytime for focused guides on profiles, shortcuts, pane layouts, and .lnk launchers.","Help","Tutorials",""));
            case APPEARANCE -> List.of(
                    s("Open Appearance","Open Edit > Settings and select Appearance on the left.","Appearance","theme","settings-Appearance"),
                    s("Application theme","Pick an Application theme—dark and light themes apply immediately across every window.","Appearance","theme","appearance-theme"),
                    s("Palette or primary color","Select a Terminal profile, then choose a Text palette. Choose Custom color to pick your own.","Appearance","palette","appearance-palette"),
                    s("Terminal font","Choose a monospaced font family or browse to a local .ttf/.otf file and click Check font.","Appearance","font","appearance-font"),
                    s("Pane header","Toggle Show header and choose which details appear above each pane.","Appearance","header","appearance-header"),
                    s("Pane footer","Toggle Show footer to choose status, screen size, cursor row:col, and insert indicators.","Appearance","footer","appearance-footer"),
                    s("Auto-save status","Valid changes apply right away and persist automatically when the footer shows Saved.","Appearance","apply","settings-applied"));
            case PASSWORD_PROFILE -> List.of(
                    s("Open Profiles","Open Edit > Settings > Profiles to create or edit workstation profiles.","Profiles","list","settings-Profiles"),
                    s("Choose a profile","Click New to create a profile, or select an existing one and click Next. Profile changes save automatically.","Profiles","new","profile-new"),
                    s("Password sign-in","Set Sign in with to Password / manual to reveal username and password fields.","Profiles","authentication","profile-password-mode"),
                    s("Host & name","Enter your IBM i hostname or IP address and a clear profile name.","Profiles","host","profile-address"),
                    s("Username","Enter your IBM i user profile (or pick an existing saved login).","Profiles","username","profile-username"),
                    s("Sign-in options","Choose whether Fish signs in automatically and stores your password in Windows Credential Manager.","Profiles","remember","profile-login-options"),
                    s("Device (optional)","Enter a device name if your workstation requires one. Otherwise, click Next.","Profiles","device","profile-device"),
                    s("Save a password","To store a password, enter it and choose Save login changes. Otherwise you're done. Pick the profile beside + when you're ready to connect.","Profiles","password",""));
            case KEYBOARD -> List.of(
                    s("Open Keyboard Shortcuts","Open Edit > Settings > Keyboard Shortcuts to customize app and 5250 host keys.","Keyboard Shortcuts","keyboard-search","settings-Keyboard Shortcuts"),
                    s("Filter actions","Type an action name or key in Find action to narrow the list.","Keyboard Shortcuts","keyboard-search","keyboard-search"),
                    s("Select an action","Click a row in the table to edit all bindings for that action.","Keyboard Shortcuts","keyboard-table","keyboard-action"),
                    s("Edit or record keys","Enter one chord per line (e.g. ctrl F5 or ENTER@numpad), or click Record and add.","Keyboard Shortcuts","keyboard-keys","keyboard-binding"),
                    s("Auto-save check","Conflict-free bindings apply automatically and confirm Saved in the footer.","Keyboard Shortcuts","apply","settings-applied"));
            case PANES -> List.of(
                    s("Add a pane","Pick a profile beside + and click + to open up to eight independent panes.","add","","Add pane"),
                    s("Resize side-by-side","Drag the divider left or right.","divider-horizontal","","divider-horizontal"),
                    s("Split or arrange","Drag a pane header toward a window or pane edge—or use Panes > Arrange pane—to split cleanly.","Panes","Arrange pane...","panes-arranged"),
                    s("Resize stacked rows","Drag the horizontal divider vertically to adjust stacked pane heights.","divider-vertical","","divider-vertical"),
                    s("Swap or undo","Drop onto another pane's center (or use Panes > Swap panes) to swap; Undo restores the prior layout.","Panes","Swap panes...","panes-swapped"),
                    s("Startup behavior","In Edit > Settings > General, choose whether restored panes reconnect automatically on launch.","General","startup","startup-changed"),
                    s("Fit & navigate","Use View > Fit host screen to scale text to the pane, and Ctrl+Tab to cycle panes.","View","Fit host screen","Fit host screen"),
                    s("Create a desktop shortcut","In Settings > Shortcuts, name your shortcut and choose Export current window to reopen these panes.","Shortcuts","shortcut-create","shortcut-created"));
            case DESKTOP_SHORTCUTS -> List.of(
                    s("Pick a profile (optional)","For a single-profile launcher, select that profile beside +; for your current layout, click Skip step.","add-profile","","add-profile-selected"),
                    s("Set up panes","Click + to open a pane, or skip this step if your panes are ready.","add","","Add pane"),
                    s("Open Settings","Open Edit > Settings, then select Shortcuts.","Shortcuts","shortcut-name","settings-Shortcuts"),
                    s("Name your shortcut","Enter a name you will recognize on your Desktop, such as Morning work.","Shortcuts","shortcut-name","shortcut-named"),
                    s("Create your shortcut","To open these panes at Windows sign-in, select the checkbox. Then choose Export current window.","Shortcuts","shortcut-create","shortcut-created"),
                    s("Open from your Desktop","Double-click the shortcut on your Desktop to open these panes in a new Fish window.","Shortcuts","shortcut-status",""));
        };
    }

    private Node target(Step current) {
        Preferences settings=workspace.tutorialPreferences();
        return switch (current.surface()) {
            case "connect" -> workspace.active().tutorialConnectionTarget();
            case "add" -> workspace.chrome().openTutorialMenuRow("Add pane",workspace.tutorialAddTarget());
            case "add-profile" -> workspace.tutorialProfileTarget();
            case "close" -> workspace.chrome().openTutorialMenuRow("Close pane",workspace.active().tutorialCloseTarget());
            case "settings" -> workspace.tutorialSettingsTarget();
            case "divider-horizontal","divider-vertical" -> {
                Node divider=workspace.tutorialDivider(current.surface().equals("divider-horizontal"));
                yield divider==null ? workspace.tutorialAddTarget() : divider;
            }
            case "Profiles","Appearance","Keyboard Shortcuts","General","Shortcuts" -> {
                if (current.surface().equals("Profiles") && editor!=null) yield editor.tutorialTarget(current.control());
                if (settings==null) yield workspace.tutorialSettingsTarget();
                yield current.surface().equals(settings.category()) ? settings.tutorialTarget(current.control())
                        : settings.tutorialCategory(current.surface());
            }
            default -> workspace.tutorialMenuTarget(current.surface(),current.control());
        };
    }

    public void refreshTarget() {
        if (ended || refreshing) return;
        refreshing=true;
        try {
            editor=workspace.tutorialProfileEditor();
            Step current=steps.get(step);
            Node target=target(current);
            String message=current.text();
            if (target instanceof ListCell<?> cell && current.surface().equals(cell.getItem()))
                message="Select "+current.surface()+" in the Settings sidebar.";
            if (guide==Guide.APPEARANCE && current.control().equals("palette") && workspace.tutorialPreferences()!=null
                    && workspace.tutorialPreferences().category().equals("Appearance") && workspace.tutorialPreferences().customPalette()) {
                message+=" Custom theme owns terminal colors—pick a built-in theme to change profile palettes, or skip.";
                target=workspace.tutorialPreferences().tutorialTarget("theme");
            }
            if (current.surface().startsWith("divider-") && workspace.tutorialDivider(current.surface().equals("divider-horizontal"))==null)
                message+=" Click + first to add a second pane and create a divider, or skip.";
            if (current.surface().equals("add") && workspace.sessionPanes().size()==8)
                message+=" All eight pane slots are full—close one first or skip.";
            if (current.surface().equals("close") && workspace.sessionPanes().size()==1 && !workspace.active().busy())
                message="Only one offline pane is open, so X is hidden until another pane or connection is active. Click Next or Skip step.";

            // If this step leads back to the workspace, don't strand the
            // instructions behind Settings or the pane-placement form.
            for (Window w:Window.getWindows()) {
                if (!(w instanceof Stage s) || !s.isFocused() || !related(s)
                        || target!=null && target.getScene()==s.getScene()) continue;
                if (s==workspace.stage()) {
                    if (target!=null && target.getScene()!=null && target.getScene().getWindow() instanceof Stage) {
                        target=workspace.tutorialSettingsTarget();
                        message="Open Edit > Settings, then select "+current.surface()+".";
                    }
                } else if (current.surface().equals("Panes") && current.control().equals(s.getTitle()+"...")) {
                    target=s.getScene().lookup(".combo-box");
                    message=s.getTitle().equals("Swap panes") ? "Choose the other pane, then select Swap."
                            : "Choose the target area, pane and placement, then select Arrange.";
                } else {
                    target=s.getScene().lookup(".fish-window-close");
                    message="Return to the workspace to continue. "+current.text();
                }
                break;
            }
            if (target instanceof ComboBox<?> combo && combo.isShowing() && combo.getSkin() instanceof ComboBoxListViewSkin<?> skin) {
                Node list=skin.getPopupContent();
                if (list.getScene()!=null && list.getScene().getWindow().isShowing()) target=list;
            }
            title.setText(current.title());
            progress.setText((step+1)+" of "+steps.size());
            if (!text.getText().equals(message)) text.setText(message);
            back.setDisable(step==0);
            next.setText(step==steps.size()-1 ? "Done" : "Next");

            bindAnchorObserver(target);
            anchor=target;
            Scene targetScene=anchor==null ? workspace.scene() : anchor.getScene();
            Window win=targetScene==null ? workspace.stage() : targetScene.getWindow();
            if (win==null) win=workspace.stage();

            if (targetWindow!=win) {
                detachWindowListeners(targetWindow);
                detachHighlight();
                targetWindow=win;
                attachWindowListeners(targetWindow);
            }
            if (targetScene!=null && targetScene.getRoot() instanceof Pane rootPane) {
                attachHighlightRoot(rootPane);
            }
            Window host=targetWindow;
            while (host instanceof PopupWindow popup) host=popup.getOwnerWindow();
            Pane root=host!=null && host.getScene()!=null && host.getScene().getRoot() instanceof Pane p ? p : null;
            if (root!=cardRoot) {
                detachCard();
                cardRoot=root;
                if (root!=null) {
                    root.widthProperty().addListener(geometryListener);
                    root.heightProperty().addListener(geometryListener);
                    root.getChildren().add(card);
                }
            }
            syncHighlightAndPosition();
        } finally {
            refreshing=false;
        }
    }

    private void detachCard() {
        overflow.hide();
        overflow.getContent().clear();
        if (cardRoot==null) return;
        cardRoot.widthProperty().removeListener(geometryListener);
        cardRoot.heightProperty().removeListener(geometryListener);
        cardRoot.getChildren().remove(card);
        cardRoot=null;
    }

    private void bindAnchorObserver(Node nextAnchor) {
        if (observedAnchor==nextAnchor) return;
        if (observedAnchor!=null) {
            observedAnchor.layoutBoundsProperty().removeListener(anchorBoundsListener);
            observedAnchor.localToSceneTransformProperty().removeListener(anchorBoundsListener);
        }
        observedAnchor=nextAnchor;
        if (observedAnchor!=null) {
            observedAnchor.layoutBoundsProperty().addListener(anchorBoundsListener);
            observedAnchor.localToSceneTransformProperty().addListener(anchorBoundsListener);
        }
    }

    private void attachWindowListeners(Window win) {
        if (win==null) return;
        win.xProperty().addListener(geometryListener);
        win.yProperty().addListener(geometryListener);
        win.widthProperty().addListener(geometryListener);
        win.heightProperty().addListener(geometryListener);
    }

    private void detachWindowListeners(Window win) {
        if (win==null) return;
        win.xProperty().removeListener(geometryListener);
        win.yProperty().removeListener(geometryListener);
        win.widthProperty().removeListener(geometryListener);
        win.heightProperty().removeListener(geometryListener);
    }

    private void attachHighlightRoot(Pane rootPane) {
        if (observedRoot!=rootPane) {
            if (observedRoot!=null) {
                observedRoot.widthProperty().removeListener(geometryListener);
                observedRoot.heightProperty().removeListener(geometryListener);
                observedRoot.getChildren().remove(highlightLayer);
            }
            observedRoot=rootPane;
            observedRoot.widthProperty().addListener(geometryListener);
            observedRoot.heightProperty().addListener(geometryListener);
        }
        if (!rootPane.getChildren().contains(highlightLayer)) {
            rootPane.getChildren().add(highlightLayer);
        }
        highlightLayer.setViewOrder(-100.0);
    }

    private void detachHighlight() {
        if (observedRoot!=null) {
            observedRoot.widthProperty().removeListener(geometryListener);
            observedRoot.heightProperty().removeListener(geometryListener);
            observedRoot.getChildren().remove(highlightLayer);
            observedRoot=null;
        } else if (highlightLayer.getParent() instanceof Pane p) {
            p.getChildren().remove(highlightLayer);
        }
    }

    private void syncHighlightAndPosition() {
        if (ended) return;
        Scene targetScene=anchor==null ? null : anchor.getScene();
        if (anchor!=null && targetScene!=null && highlightLayer.getParent() instanceof Pane rootPane) {
            double rw=Math.max(1.0,rootPane.getWidth()),rh=Math.max(1.0,rootPane.getHeight());
            highlightLayer.resizeRelocate(0,0,rw,rh);
            Bounds sceneBounds=anchor.localToScene(anchor.getLayoutBounds());
            if (anchor.isVisible() && sceneBounds!=null && sceneBounds.getWidth()>0 && sceneBounds.getHeight()>0) {
                Color focus=Ui.fxColor(ColorRole.FOCUS);
                highlightRect.setStroke(focus);
                highlightRect.setFill(focus.deriveColor(0,1.0,1.0,0.09));
                double pad=2.0;
                double x0=Math.max(1.5,sceneBounds.getMinX()-pad);
                double y0=Math.max(1.5,sceneBounds.getMinY()-pad);
                double x1=Math.min(Math.max(x0+2.0,rw-1.5),sceneBounds.getMaxX()+pad);
                double y1=Math.min(Math.max(y0+2.0,rh-1.5),sceneBounds.getMaxY()+pad);
                highlightRect.setX(x0);
                highlightRect.setY(y0);
                highlightRect.setWidth(Math.max(2.0,x1-x0));
                highlightRect.setHeight(Math.max(2.0,y1-y0));
                highlightRect.setVisible(true);
            } else {
                highlightRect.setVisible(false);
            }
        } else {
            highlightRect.setVisible(false);
        }
        position();
    }

    // Work in one coordinate system (screen logical pixels for shown windows).
    // Consider both sides of *every* obstruction, not only the active menu row:
    // a compact menu and its submenu can occupy different sides on a small monitor.
    private static Point2D place(Rectangle2D area,List<Rectangle2D> avoid,double w,double h) {
        if (area.getWidth()<w+8 || area.getHeight()<h+8) return null;
        Rectangle2D a=avoid.getFirst();
        List<Double> xs=new ArrayList<>(List.of(a.getMinX(),a.getMaxX()-w));
        List<Double> ys=new ArrayList<>(List.of(a.getMaxY()+9,a.getMinY()));
        for (Rectangle2D b:avoid) {
            xs.add(b.getMaxX()+9); xs.add(b.getMinX()-w-9);
            ys.add(b.getMaxY()+9); ys.add(b.getMinY()-h-9);
        }
        double best=Double.POSITIVE_INFINITY;
        Point2D pos=null;
        for (double cy:ys) for (double cx:xs) {
            double x=Math.clamp(cx,area.getMinX()+4,area.getMaxX()-w-4);
            double y=Math.clamp(cy,area.getMinY()+4,area.getMaxY()-h-4);
            if (avoid.stream().anyMatch(b -> b.intersects(x-3,y-3,w+6,h+6))) continue;
            double score=Math.hypot(x-a.getMinX(),y-a.getMaxY()-9);
            if (score<best) { best=score; pos=new Point2D(x,y); }
        }
        return pos;
    }

    private boolean positioning;
    private void position() {
        if (positioning || cardRoot==null || targetWindow==null) return;
        positioning=true;
        try {
            Stage host=hostStage();
            Point2D origin=cardRoot.localToScreen(0,0);
            boolean live=origin!=null && host.isShowing();
            if (!live) origin=Point2D.ZERO;
            Bounds b=anchor==null ? null : live ? anchor.localToScreen(anchor.getLayoutBounds())
                    : anchor.localToScene(anchor.getLayoutBounds());
            List<Rectangle2D> avoid=new ArrayList<>();
            avoid.add(b==null ? new Rectangle2D(origin.getX(),origin.getY(),1,1)
                    : new Rectangle2D(b.getMinX(),b.getMinY(),b.getWidth(),b.getHeight()));
            if (live) for (Window w:Window.getWindows()) {
                if (w!=overflow && w instanceof PopupWindow && related(w) && w.getWidth()>0 && w.getHeight()>0)
                    avoid.add(new Rectangle2D(w.getX(),w.getY(),w.getWidth(),w.getHeight()));
            }
            double width=Math.clamp(cardRoot.getWidth()-8,280,368);
            card.applyCss();
            double height=Math.ceil(card.prefHeight(width));
            Rectangle2D area=new Rectangle2D(origin.getX(),origin.getY(),cardRoot.getWidth(),cardRoot.getHeight());
            Point2D pt=place(area,avoid,width,height);
            if (pt==null && live && host.isFocused()) {
                List<Screen> screens=Screen.getScreensForRectangle(avoid.getFirst());
                if (!screens.isEmpty()) pt=place(screens.getFirst().getVisualBounds(),avoid,width,height);
                if (pt!=null) {
                    card.setVisible(true);
                    if (!overflow.isShowing()) {
                        cardRoot.getChildren().remove(card);
                        overflow.getContent().setAll(card);
                        card.resizeRelocate(0,0,width,height);
                        overflow.show(host,pt.getX(),pt.getY());
                    } else {
                        card.resizeRelocate(0,0,width,height);
                        overflow.setX(pt.getX()); overflow.setY(pt.getY());
                    }
                    card.layout();
                    return;
                }
            }
            if (overflow.getContent().contains(card)) {
                overflow.hide(); overflow.getContent().clear();
                cardRoot.getChildren().add(card);
            }
            card.setVisible(pt!=null || !live);
            card.resizeRelocate(pt==null ? 4 : pt.getX()-origin.getX(),
                    pt==null ? 4 : pt.getY()-origin.getY(),width,height);
            // This unmanaged overlay is its own layout root. Lay out its
            // buttons now, including immediately after Back changes the copy.
            card.layout();
        } finally { positioning=false; }
    }

    public void finish(boolean completed) {
        if (ended) return;
        if (firstRun) workspace.tutorialStatus(completed ? StateStore.Tutorial.COMPLETED : StateStore.Tutorial.SKIPPED);
        dispose();
    }

    public boolean ended() { return ended; }

    public void dispose() {
        if (!ended) {
            ended=true;
            Window.getWindows().removeListener(windowsChanged);
            for (Window w:Window.getWindows()) if (w instanceof Stage && related(w)) w.focusedProperty().removeListener(focusChanged);
            bindAnchorObserver(null);
            detachWindowListeners(targetWindow);
            detachHighlight();
            detachCard();
            targetWindow=null;
            anchor=null;
        }
    }
}
