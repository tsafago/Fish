package fish.ui;

import java.util.List;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
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
        DESKTOP_SHORTCUTS("Desktop profile / layout shortcuts");
        public final String title;
        Guide(String title) { this.title=title; }
    }

    private record Step(String title,String text,String surface,String control,String event) {}

    private final Workspace workspace;
    private final Stage stage;
    private final boolean firstRun;
    private final Guide guide;
    private final List<Step> steps;
    private final Label title=new Label(),progress=new Label();
    private final TextArea text=new TextArea();
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

    public TutorialDialog(Workspace workspace,boolean firstRun) { this(workspace,Guide.GETTING_STARTED,firstRun); }

    public TutorialDialog(Workspace workspace,Guide guide,boolean firstRun) {
        this.workspace=workspace;
        this.firstRun=firstRun;
        this.guide=guide;
        this.steps=steps(guide);
        this.stage=Ui.dialogStage(workspace.stage(),guide.title,false);
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
        heading.setRight(progress);

        text.getStyleClass().add("fish-tutorial-card");
        text.setEditable(false);
        text.setWrapText(true);
        text.setFocusTraversable(false);
        text.setAccessibleText("Tutorial step instructions");
        text.setPrefRowCount(3);

        tryIt=Ui.button("Show control",this::showControl);
        skipTutorial=Ui.button("Skip tutorial",() -> finish(false));
        BorderPane contextual=new BorderPane();
        contextual.setLeft(tryIt);
        contextual.setRight(skipTutorial);

        back=Ui.button("Back",() -> go(step-1));
        skipStep=Ui.button("Skip step",this::advance);
        next=Ui.button("Next",this::advance);
        next.setDefaultButton(true);
        HBox navigation=new HBox(6,back,skipStep,next);
        navigation.setAlignment(Pos.CENTER_RIGHT);

        VBox actions=new VBox(8,contextual,navigation);
        BorderPane body=new BorderPane();
        body.setPadding(new Insets(12,14,12,14));
        body.setTop(heading);
        BorderPane.setMargin(heading,new Insets(0,0,6,0));
        body.setCenter(text);
        body.setBottom(actions);
        BorderPane.setMargin(actions,new Insets(8,0,0,0));

        Ui.decorateDialog(stage,body,() -> finish(false),380,232);
        stage.setMinWidth(332);
        stage.setMinHeight(208);
        stage.setOnShown(e -> {
            refreshTarget();
            Platform.runLater(() -> {
                if (!ended) refreshTarget();
            });
        });
        go(0);
    }

    public Stage stage() { return stage; }
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

    public void setVisible(boolean visible) {
        if (visible) {
            stage.show();
            refreshTarget();
            Platform.runLater(() -> {
                if (!ended) refreshTarget();
            });
        } else {
            dispose();
        }
    }

    public void focusGuide() {
        if (stage.isShowing()) {
            stage.requestFocus();
            next.requestFocus();
        }
    }

    public boolean related(Window window) {
        if (window==null || window==stage) return false;
        for (Window cur=window;cur!=null;cur=(cur instanceof Stage s ? s.getOwner() : null)) {
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
            case "Appearance","Profiles","Keyboard Shortcuts","General" -> workspace.preferences(current.surface());
            case "settings" -> workspace.showTutorialSettings();
            case "layouts","layout-destination" -> workspace.savedLayoutsDialog();
            case "Help","Panes","View","Session" -> workspace.showTutorialMenu(current.surface());
            default -> { if (workspace.isShowing()) workspace.toFront(); }
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
                    s("Start a draft","Click New to start a profile draft, or select an existing profile and click Next.","Profiles","new","profile-new"),
                    s("Password sign-in","Set Sign in with to Password / manual to reveal username and password fields.","Profiles","authentication","profile-password-mode"),
                    s("Host & name","Enter your IBM i hostname or IP address and a clear profile name.","Profiles","host","profile-address"),
                    s("Username","Enter your IBM i user profile (or pick an existing saved login).","Profiles","username","profile-username"),
                    s("Sign-in options","Choose whether Fish signs in automatically and stores your password in Windows Credential Manager.","Profiles","remember","profile-login-options"),
                    s("Advanced options","Expand Advanced only if you need a custom port, device name, or EBCDIC code page.","Profiles","advanced","profile-advanced"),
                    s("Create profile","Click Create profile to save—then pick it beside + whenever you're ready to connect.","Profiles","apply","profile-saved"));
            case KEYBOARD -> List.of(
                    s("Open Keyboard Shortcuts","Open Edit > Settings > Keyboard Shortcuts to customize app and 5250 host keys.","Keyboard Shortcuts","keyboard-search","settings-Keyboard Shortcuts"),
                    s("Filter actions","Type an action name or key in Find action to narrow the list.","Keyboard Shortcuts","keyboard-search","keyboard-search"),
                    s("Select an action","Click a row in the table to edit all bindings for that action.","Keyboard Shortcuts","keyboard-table","keyboard-action"),
                    s("Edit or record keys","Enter one chord per line (e.g. ctrl F5 or ENTER@numpad), or click Record and add.","Keyboard Shortcuts","keyboard-keys","keyboard-binding"),
                    s("Auto-save check","Conflict-free bindings apply automatically and confirm Saved in the footer.","Keyboard Shortcuts","apply","settings-applied"));
            case PANES -> List.of(
                    s("Add a pane","Pick a profile beside + and click + to open up to eight independent panes.","add","","Add pane"),
                    s("Resize side-by-side","Drag the vertical divider (or use Panes > Decrease / Increase divider) to resize columns.","divider-horizontal","","divider-horizontal"),
                    s("Split or arrange","Drag a pane header toward a window or pane edge—or use Panes > Arrange pane—to split cleanly.","Panes","Arrange pane...","panes-arranged"),
                    s("Resize stacked rows","Drag the horizontal divider vertically to adjust stacked pane heights.","divider-vertical","","divider-vertical"),
                    s("Swap or undo","Drop onto another pane's center (or use Panes > Swap panes) to swap; Undo restores the prior layout.","Panes","Swap panes...","panes-swapped"),
                    s("Startup behavior","In Edit > Settings > General, choose whether restored panes reconnect automatically on launch.","General","startup","startup-changed"),
                    s("Fit & navigate","Use View > Fit host screen to scale text to the pane, and Ctrl+Tab to cycle panes.","View","Fit host screen","Fit host screen"),
                    s("Save a layout","Open Panes > Saved layouts / shortcuts to save this arrangement or export a Desktop .lnk.","Panes","Saved layouts / shortcuts...","layout-saved"));
            case DESKTOP_SHORTCUTS -> List.of(
                    s("Pick a profile (optional)","For a single-profile launcher, select that profile beside +; for your current layout, click Skip step.","add-profile","","add-profile-selected"),
                    s("Set up panes","Click + to open the pane you want in the recipe, or Skip step if your layout is ready.","add","","Add pane"),
                    s("Open Saved layouts","Open Panes > Saved layouts / shortcuts to name and export this window's layout.","Panes","Saved layouts / shortcuts...","layouts-opened"),
                    s("Name the layout","Enter a descriptive name (such as Morning work) under Shortcut for this window.","layouts","name","layout-named"),
                    s("Export shortcut","Click Create desktop shortcut to save the layout and choose where to write the .lnk file.","layouts","export","layout-destination-open"),
                    s("Confirm destination","Pick Desktop or another folder and confirm—Cancel leaves disk and saved layouts untouched.","layout-destination","destination","layout-shortcut"),
                    s("Launch anytime","Double-click the .lnk anytime to open a fresh Fish window with that saved layout.","layouts","message",""));
        };
    }

    private Node target(Step current) {
        Preferences settings=workspace.tutorialPreferences();
        return switch (current.surface()) {
            case "connect" -> workspace.active().tutorialConnectionTarget();
            case "add" -> workspace.tutorialAddTarget();
            case "add-profile" -> workspace.tutorialProfileTarget();
            case "layouts","layout-destination" -> workspace.tutorialLayoutTarget(current.control());
            case "close" -> workspace.active().tutorialCloseTarget();
            case "settings" -> workspace.tutorialSettingsTarget();
            case "divider-horizontal","divider-vertical" -> {
                Node divider=workspace.tutorialDivider(current.surface().equals("divider-horizontal"));
                yield divider==null ? workspace.tutorialAddTarget() : divider;
            }
            case "Appearance","Keyboard Shortcuts","General" -> settings!=null && settings.category().equals(current.surface())
                    ? settings.tutorialTarget(current.control()) : workspace.tutorialSettingsTarget();
            case "Profiles" -> editor!=null ? editor.tutorialTarget(current.control()) : workspace.tutorialSettingsTarget();
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
            syncHighlightAndPosition();
        } finally {
            refreshing=false;
        }
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
            Bounds sceneBounds=anchor.localToScene(anchor.getBoundsInLocal());
            if (sceneBounds!=null && sceneBounds.getWidth()>0 && sceneBounds.getHeight()>0) {
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

    Point2D computeDialogPosition(Rectangle2D screen,double winSceneX,double winSceneY,double sw,double sh) {
        if (anchor==null) return null;
        Bounds b=anchor.localToScene(anchor.getBoundsInLocal());
        if (b==null || b.getWidth()<=0 || b.getHeight()<=0) return null;
        double bx=winSceneX+b.getMinX(),by=winSceneY+b.getMinY();
        double bw=b.getWidth(),bh=b.getHeight();
        boolean topBar=b.getMinY()<64;
        double[][] candidates=topBar
                ? new double[][]{
                        {bx,by+bh+10},
                        {bx+bw-sw,by+bh+10},
                        {bx-sw-12,by+bh+6},
                        {bx+bw+12,by}}
                : new double[][]{
                        {bx+bw+12,by},
                        {bx-sw-12,by},
                        {bx,by+bh+12},
                        {bx,by-sh-12}};
        double bestX=candidates[0][0],bestY=candidates[0][1];
        double bestScore=Double.MAX_VALUE;
        for (int i=0;i<candidates.length;i++) {
            double[] c=candidates[i];
            double x=Math.max(screen.getMinX()+8,Math.min(c[0],screen.getMaxX()-sw-8));
            double y=Math.max(screen.getMinY()+8,Math.min(c[1],screen.getMaxY()-sh-8));
            double ox=Math.max(0,Math.min(bx+bw+6,x+sw)-Math.max(bx-6,x));
            double oy=Math.max(0,Math.min(by+bh+6,y+sh)-Math.max(by-6,y));
            double cost=ox*oy*2000+Math.abs(x-c[0])+Math.abs(y-c[1])+i*18.0;
            if (cost<bestScore) { bestScore=cost; bestX=x; bestY=y; }
        }
        return new Point2D(Math.round(bestX),Math.round(bestY));
    }

    private void position() {
        if (anchor==null || anchor.getScene()==null || targetWindow==null || !stage.isShowing()) return;
        Bounds b=anchor.localToScene(anchor.getBoundsInLocal());
        if (b==null || b.getWidth()<=0 || b.getHeight()<=0) return;
        Scene scene=anchor.getScene();
        double sx=targetWindow.getX()+scene.getX(),sy=targetWindow.getY()+scene.getY();
        double bx=sx+b.getMinX(),by=sy+b.getMinY();
        List<Screen> screens=Screen.getScreensForRectangle(bx,by,Math.max(1.0,b.getWidth()),Math.max(1.0,b.getHeight()));
        if (screens.isEmpty() && targetWindow.getWidth()>0) {
            screens=Screen.getScreensForRectangle(targetWindow.getX(),targetWindow.getY(),
                    Math.max(1.0,targetWindow.getWidth()),Math.max(1.0,targetWindow.getHeight()));
        }
        Rectangle2D screen=(screens.isEmpty() ? Screen.getPrimary() : screens.getFirst()).getVisualBounds();
        double sw=stage.getWidth()>40 ? stage.getWidth() : 380;
        double sh=stage.getHeight()>40 ? stage.getHeight() : 232;
        Point2D pt=computeDialogPosition(screen,sx,sy,sw,sh);
        if (pt==null) return;
        if (Math.abs(stage.getX()-pt.getX())>0.5) stage.setX(pt.getX());
        if (Math.abs(stage.getY()-pt.getY())>0.5) stage.setY(pt.getY());
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
            bindAnchorObserver(null);
            detachWindowListeners(targetWindow);
            detachHighlight();
            targetWindow=null;
            anchor=null;
        }
        stage.close();
    }
}
