package fish.ui;

import java.awt.Font;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiPredicate;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import org.tn5250j.keyboard.KeyMnemonic;
import fish.model.*;
import fish.security.CredentialStore;
import fish.security.KioskMode;
import fish.update.Updates;

/**
 * Primary Fish JavaFX window: StageStyle.EXTENDED + HeaderBar, 1-8 constrained split panes,
 * pre-drop arrangement preview, undo stack, unified shortcut/menu catalog, and async persistence.
 */
@SuppressWarnings({"deprecation", "removal"})
public final class Workspace {
    public record DropPlan(UUID source,UUID target,Layout.Dock dock,Layout.Scope scope,Layout layout,Map<UUID,Rectangle> bounds) {}
    public record DockTarget(UUID pane,Layout.Dock dock,Layout.Scope scope) {}
    @FunctionalInterface public interface Maintenance { void run() throws java.io.IOException; }

    public static final class WorkspaceSplit extends Pane {
        private final boolean horizontal;
        private final boolean threeStrips;
        private double userRatio;
        private final Layout.Split modelSplit;
        private final Node first,second;
        private final Region divider=new Region();
        private final Region seam=new Region();
        private boolean hovered,draggingDivider;

        WorkspaceSplit(Workspace owner,Layout.Split split,Node first,Node second) {
            this.modelSplit=split;
            this.horizontal=split.horizontal();
            this.threeStrips=Layout.isThreeStrips(split);
            this.userRatio=split.ratio();
            this.first=first;
            this.second=second;
            setSnapToPixel(false);
            divider.setPickOnBounds(true);
            divider.setSnapToPixel(false);
            divider.setStyle("-fx-background-color: transparent;");
            divider.setCursor(KioskMode.isEnabled() ? Cursor.DEFAULT : (horizontal ? Cursor.H_RESIZE : Cursor.V_RESIZE));
            seam.setManaged(false);
            seam.setMouseTransparent(true);
            seam.setSnapToPixel(false);
            divider.setOnMouseEntered(e -> { hovered=!KioskMode.isEnabled(); styleDivider(); });
            divider.setOnMouseExited(e -> { hovered=false; styleDivider(); });
            int[] startLoc={0};
            divider.setOnMousePressed(e -> {
                if (KioskMode.isEnabled()) return;
                startLoc[0]=getDividerLocation();
                draggingDivider=true;
                styleDivider();
            });
            divider.setOnMouseDragged(e -> {
                if (KioskMode.isEnabled() || !draggingDivider) return;
                Point2D local=sceneToLocal(e.getSceneX(),e.getSceneY());
                if (local!=null) {
                    int target=(int)Math.round(horizontal ? local.getX() : local.getY());
                    owner.resizeDividerLive(this,target);
                }
                e.consume();
            });
            divider.setOnMouseReleased(e -> {
                if (!draggingDivider) return;
                draggingDivider=false;
                styleDivider();
                if (!KioskMode.isEnabled() && startLoc[0]!=getDividerLocation()) {
                    owner.changed();
                    owner.tutorialAction(horizontal ? "divider-horizontal" : "divider-vertical");
                }
            });
            styleDivider();
            getChildren().addAll(first,second,seam,divider);
        }

        public boolean isHorizontal() { return horizontal; }
        public boolean isThreeStrips() { return threeStrips; }
        public double userRatio() { return userRatio; }
        public void setUserRatio(double r) { this.userRatio=r; requestLayout(); }
        public Node firstChild() { return first; }
        public Node secondChild() { return second; }
        public Region dividerNode() { return divider; }

        public int getDividerLocation() {
            int w=(int)Math.round(getWidth()),h=(int)Math.round(getHeight());
            Rectangle[] areas=partition(modelSplit,new Rectangle(0,0,w,h),userRatio);
            return horizontal ? areas[0].width : areas[0].height;
        }

        void styleDivider() {
            boolean kiosk=KioskMode.isEnabled();
            divider.setCursor(kiosk ? Cursor.DEFAULT : (horizontal ? Cursor.H_RESIZE : Cursor.V_RESIZE));
            String line=Ui.hex(!kiosk && (hovered || draggingDivider) ? ColorRole.FOCUS : ColorRole.DIVIDER);
            Ui.setStyleIfChanged(seam,"-fx-background-color: "+line+";");
        }

        @Override protected double computeMinWidth(double height) {
            double a=first.minWidth(-1),b=second.minWidth(-1);
            return horizontal ? a+b : Math.max(a,b);
        }

        @Override protected double computeMinHeight(double width) {
            double a=first.minHeight(-1),b=second.minHeight(-1);
            return horizontal ? Math.max(a,b) : a+b;
        }

        @Override protected void layoutChildren() {
            int w=Math.max(0,(int)Math.round(getWidth()));
            int h=Math.max(0,(int)Math.round(getHeight()));
            Rectangle[] areas=partition(modelSplit,new Rectangle(0,0,w,h),userRatio);
            first.resizeRelocate(areas[0].x,areas[0].y,areas[0].width,areas[0].height);
            second.resizeRelocate(areas[1].x,areas[1].y,areas[1].width,areas[1].height);
            if (first instanceof Parent p) p.requestLayout();
            if (second instanceof Parent p) p.requestLayout();
            int loc=horizontal ? areas[0].width : areas[0].height;
            double s=Ui.scaleFor(this);
            double px=Ui.hairline(s);
            if (horizontal) {
                Point2D sp=localToScene(loc,0);
                double seamX=loc;
                if (sp!=null) {
                    double dev=Math.min(Math.max(0,Math.round(sp.getX()*s)),Math.max(0,Math.round((sp.getX()-loc+w)*s)-1));
                    seamX=loc+(dev/s-sp.getX());
                }
                seam.resizeRelocate(seamX,0,px,h);
                divider.resizeRelocate(loc-3,0,6,h);
            } else {
                Point2D sp=localToScene(0,loc);
                double seamY=loc;
                if (sp!=null) {
                    double dev=Math.min(Math.max(0,Math.round(sp.getY()*s)),Math.max(0,Math.round((sp.getY()-loc+h)*s)-1));
                    seamY=loc+(dev/s-sp.getY());
                }
                seam.resizeRelocate(0,seamY,w,px);
                divider.resizeRelocate(0,loc-3,w,6);
            }
            seam.toFront();
            divider.toFront();
        }
    }

    private final Stage stage;
    private final Scene scene;
    private final Region windowTop=windowOutlineBar(),windowBottom=windowOutlineBar(),
            windowLeft=windowOutlineBar(),windowRight=windowOutlineBar();
    private boolean showFallbackOutline;
    private final BorderPane shell=new BorderPane() {
        @Override protected void layoutChildren() {
            double s=Ui.scaleFor(this);
            double w=getWidth(),h=getHeight();
            double topH=0;
            if (chrome!=null && chrome.isManaged() && chrome.isVisible()) {
                topH=Math.min(h,Math.round(WindowChrome.HEIGHT*s)/s);
                chrome.resizeRelocate(0,0,w,topH);
            }
            if (centerHost!=null) {
                centerHost.resizeRelocate(0,topH,w,Math.max(0,h-topH));
            }
            layoutWindowOutline();
        }
    };
    private final StateStore store;
    private final CredentialStore credentials;
    private final LinkedHashMap<UUID,Profile> profiles=new LinkedHashMap<>();
    private final Map<UUID,SessionPane> panes=new LinkedHashMap<>();
    private final List<AppCommand> commands=new ArrayList<>();
    private final StackPane content=new StackPane();
    private final Canvas dropOverlay=new Canvas();
    private final Pane centerHost;
    private final ExecutorService persistence=Executors.newSingleThreadExecutor(r -> new Thread(r,"workspace-save"));
    private final Timeline saveTimer;
    private Layout layout;
    private SessionPane active;
    private UUID lastProfile;
    private UUID addProfile;
    private List<SavedLayout> savedLayouts;
    private UUID selectedLayout;
    private Node tree;
    private boolean adjustingDividers,rebuildingLayout;
    private final Deque<Layout> arrangementHistory=new ArrayDeque<>();
    private DropPlan dropPlan;
    private final boolean demonstration;
    private boolean closing;
    private boolean highlightActive;
    private static final ThreadLocal<java.util.concurrent.CompletableFuture<Void>> DEFERRED_FONT_WARMUP=new ThreadLocal<>();
    private Appearance appearance;
    private Font terminalFont;
    private String fontNotice="";
    private record PreviewKey(UUID source,UUID target,Layout.Dock dock,Layout.Scope scope) {}
    private final AppShortcuts shortcuts;
    private final WindowChrome chrome;
    private final Button addPane;
    private final Tooltip addPaneTooltip=new Tooltip("Add and connect selected profile (maximum 8)");
    private final List<Region> chromeSeparatorBars=new ArrayList<>(2);
    private final ComboBox<Profile> addProfileSelector=new ComboBox<>();
    private PreviewKey lastPreviewKey;
    private boolean lastPreviewValid;
    private long stateBuildCount;
    private boolean refreshingAdd;
    private CompletableFuture<Void> shortcutWrite;
    private ApplicationWindows application;
    private Preferences preferences;
    private ProfilesEditor profileEditor;
    private final MenuBar menuBar;
    private ContextMenu openPopup;
    private final HBox toolsBox;
    private Menu sessionMenu,editMenu,insertsMenu,panesMenu,viewMenu,hostMenu,helpMenu;
    private List<MenuItem> normalEditItems=List.of(),kioskEditItems=List.of();
    private StateStore.Tutorial tutorialStatus;
    private TutorialDialog tutorial;
    private boolean connectOnStartup,startupHandled;
    private UpdateSettings updateSettings;
    private Map<String,String> keyboardRecovery;
    private final UpdateController updates;
    private Runnable afterClose=() -> {};
    private final Runnable themeListener=this::themeChanged;
    private final Runnable kioskListener=() -> {
        if (javafx.application.Platform.isFxApplicationThread()) syncKioskMode();
        else Ui.runLater(this::syncKioskMode);
    };
    private double logicalWidth,logicalHeight;
    private int settingsBatch;
    private Layout cachedSpatialLayout;
    private List<SessionPane> cachedSessionPanes=List.of();

    public Workspace(StateStore store,StateStore.State state,Path credentialHelper,boolean demonstration) {
        this(store,state,credentialHelper,demonstration,new Updates());
    }
    public Workspace(StateStore store,StateStore.State state,Path credentialHelper,boolean demonstration,Updates updateService) {
        this(store,state,credentialHelper,demonstration,updateService,(owner,message) -> Ui.confirm(owner,message,"Update Fish"));
    }
    public Workspace(StateStore store,StateStore.State state,Path credentialHelper,boolean demonstration,Updates updateService,
                     BiPredicate<Window,String> confirmUpdate) {
        this(store,state,credentialHelper,demonstration,updateService,confirmUpdate,true);
    }
    public Workspace(StateStore store,StateStore.State state,Path credentialHelper,boolean demonstration,Updates updateService,
                     BiPredicate<Window,String> confirmUpdate,boolean initializeTheme) {
        this.stage=new Stage(StageStyle.EXTENDED);
        stage.setTitle("Fish"+(demonstration ? " - local synthetic host" : ""));
        Ui.registerStage(stage);

        appearance=state.appearance();
        tutorialStatus=state.tutorial();
        connectOnStartup=state.connectOnStartup();
        updateSettings=state.updates();
        keyboardRecovery=state.keyboardRecovery();
        addProfile=state.addProfile();
        savedLayouts=state.savedLayouts();
        selectedLayout=state.selectedLayout();
        if (initializeTheme) {
            Ui.surfacePreferences(appearance.flatThemes());
            Ui.setReducedMotion(appearance.reducedMotion());
            Ui.applyTheme(state.appTheme(),appearance.custom().orElse(null));
        }

        this.store=store;
        this.demonstration=demonstration;
        this.credentials=new CredentialStore(credentialHelper);
        state.profiles().forEach(p -> profiles.put(p.id(),p));
        java.util.concurrent.CompletableFuture<Void> deferredWarmup=DEFERRED_FONT_WARMUP.get();
        boolean deferInitialFont=deferredWarmup!=null && !demonstration && !connectOnStartup && state.displays().isEmpty();
        if (!deferInitialFont) resolveFont();
        else deferredWarmup.whenComplete((ignored,err) -> Ui.runLater(this::finishDeferredFontResolution));
        layout=Layout.normalize(state.layout());
        lastProfile=state.lastProfile();
        highlightActive=state.highlightActive();

        saveTimer=new Timeline(new KeyFrame(Duration.millis(500),e -> saveAsync()));

        Rectangle2D screenBounds=Screen.getPrimary().getVisualBounds();
        logicalWidth=Math.max(330,Math.min(state.width(),screenBounds.getWidth()));
        logicalHeight=Math.max(260,Math.min(state.height(),screenBounds.getHeight()));

        shell.getStyleClass().add("fish-window-shell");
        shell.getChildren().addAll(windowTop,windowBottom,windowLeft,windowRight);
        updateWindowOutline();

        menuBar=buildMenus();

        addProfileSelector.getStyleClass().addAll("fish-chrome-combo","fish-chrome-pill-combo");
        addProfileSelector.setMinSize(72,24);
        addProfileSelector.setPrefSize(160,24);
        addProfileSelector.setMaxHeight(24);
        addProfileSelector.setAccessibleText("Profile for new pane");
        addProfileSelector.setTooltip(new Tooltip("Choose a profile, then + to add and connect; selection alone changes no session"));
        refreshAddProfiles();
        addProfileSelector.valueProperty().addListener((obs,oldV,newV) -> {
            if (!refreshingAdd && newV!=null) {
                addProfile=newV.id();
                changed();
                tutorialAction("add-profile-selected");
            }
        });

        AppCommand addCmd=command("Add pane");
        addPane=new Button();
        addPane.getProperties().put("fish.command",addCmd);
        addPane.setGraphic(Ui.icon("plus",ColorRole.CHROME_TEXT));
        addPane.getStyleClass().addAll("fish-chrome-button","fish-chrome-pill-add");
        addPane.setMinSize(30,24);
        addPane.setPrefSize(30,24);
        addPane.setMaxHeight(24);
        addPane.disableProperty().bind(addCmd.enabledProperty().not());
        addPane.setAccessibleText("Add and connect selected profile");
        addPane.setTooltip(addPaneTooltip);
        addPane.setOnAction(e -> addCmd.execute());

        Separator pillJoin=chromeSeparator(true);
        var outlined=javafx.css.PseudoClass.getPseudoClass("outlined");
        javafx.beans.InvalidationListener outline=obs -> pillJoin.pseudoClassStateChanged(outlined,
                addProfileSelector.isFocused() || addProfileSelector.isShowing() || addPane.isFocused() || addPane.isArmed());
        for (var property:List.of(addProfileSelector.focusedProperty(),addProfileSelector.showingProperty(),
                addPane.focusedProperty(),addPane.armedProperty())) property.addListener(outline);
        HBox tools=new HBox(0,addProfileSelector,pillJoin,addPane) {
            @Override protected void layoutChildren() {
                double s=Ui.scaleFor(this);
                double px=Ui.hairline(s);
                Point2D origin=localToScene(0,0);
                double sceneX=origin==null ? 0.0 : origin.getX();
                double sceneY=origin==null ? 0.0 : origin.getY();
                double pillH=Math.round(24.0*s)/s;
                double y0=Math.round((sceneY+(getHeight()-pillH)*0.5)*s)/s-sceneY;
                double x0=Math.round((sceneX+6.0)*s)/s-sceneX;
                double avail=Math.max(0.0,getWidth()-x0-4.0);
                double addW=Math.min(Math.round(30.0*s)/s,avail);
                double comboTarget=Math.max(addProfileSelector.minWidth(-1),
                        Math.min(addProfileSelector.prefWidth(-1),Math.max(0.0,avail-addW-px)));
                double comboW=Math.round(comboTarget*s)/s;
                addProfileSelector.resizeRelocate(x0,y0,comboW,pillH);
                pillJoin.resizeRelocate(x0+comboW,y0,px,pillH);
                addPane.resizeRelocate(x0+comboW+px,y0,addW,pillH);
            }
        };
        tools.setSnapToPixel(false);
        tools.setPadding(new Insets(0,4,0,6));
        tools.setAlignment(Pos.CENTER_LEFT);
        this.toolsBox=tools;

        chrome=new WindowChrome(this,stage,menuBar,tools,this::toggleFlux);
        chrome.setViewOrder(-1.0);
        shell.setTop(chrome);

        dropOverlay.setMouseTransparent(true);
        dropOverlay.setVisible(false);
        dropOverlay.setManaged(false);

        updates=new UpdateController(this,updateService,confirmUpdate);
        TransientNotice updateBanner=updates.noticeBanner();

        centerHost=new Pane() {
            @Override protected void layoutChildren() {
                double w=getWidth(),h=getHeight();
                if (lastPreviewKey!=null && (content.getWidth()!=w || content.getHeight()!=h)) clearPreviews();
                content.resizeRelocate(0,0,w,h);
                if (dropOverlay.isVisible()) {
                    dropOverlay.setWidth(w);
                    dropOverlay.setHeight(h);
                    dropOverlay.relocate(0,0);
                    paintDropOverlay();
                }
                if (updateBanner.isVisible()) {
                    double bw=Math.min(updateBanner.prefWidth(-1),Math.max(0,w-16));
                    double bh=updateBanner.prefHeight(bw);
                    updateBanner.resizeRelocate(Math.max(8,w-bw-8),10,bw,bh);
                }
            }
        };
        centerHost.getChildren().addAll(content,dropOverlay,updateBanner);
        shell.setCenter(centerHost);

        scene=new Scene(shell,logicalWidth,logicalHeight);
        Ui.styleScene(scene);
        // JavaFX auto-hide exempts presses on the anchor (which can be an entire terminal).
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED,e -> hidePopup());
        scene.focusOwnerProperty().addListener((o,old,node) -> hidePopup());

        shortcuts=new AppShortcuts(this,commands,state.shortcuts());
        shortcuts.attach(scene);
        commands.forEach(cmd -> cmd.performed(this::tutorialAction));

        stage.setScene(scene);
        stage.setMinWidth(330);
        stage.setMinHeight(260);
        stage.setWidth(logicalWidth);
        stage.setHeight(logicalHeight);

        buildTree();
        state.displays().forEach((id,display) -> {
            SessionPane p=panes.get(id);
            if (p!=null) p.view().restoreDisplay(display);
        });

        if (!fontNotice.isEmpty()) Ui.runLater(() -> {
            if (!closing && active!=null) {
                active.problem(fontNotice);
                if (stage.isShowing()) Ui.error(stage,fontNotice);
            }
        });

        stage.setOnCloseRequest(e -> {
            e.consume();
            chrome.finishKeyboard(false);
            close(false);
        });
        stage.focusedProperty().addListener((o,a,focused) -> {
            if (!focused) hidePopup();
            else if (active!=null) active.view().restartCursor();
        });
        stage.showingProperty().addListener((o,a,showing) -> {
            Ui.syncSceneStylesheet(scene);
            updateWindowOutline();
            if (showing) {
                shell.requestLayout();
                Ui.runLater(() -> {
                    Ui.syncSceneStylesheet(scene);
                    updateWindowOutline();
                });
            }
        });
        stage.maximizedProperty().addListener((o,a,max) -> updateWindowOutline());
        stage.widthProperty().addListener((o,a,w) -> {
            if (w.doubleValue()>0) {
                if (!stage.isMaximized() && !stage.isIconified()) logicalWidth=w.doubleValue();
                windowGeometryChanged();
            }
        });
        stage.heightProperty().addListener((o,a,h) -> {
            if (h.doubleValue()>0) {
                if (!stage.isMaximized() && !stage.isIconified()) logicalHeight=h.doubleValue();
                windowGeometryChanged();
            }
        });
        Ui.onThemeChanged(themeListener);
        KioskMode.addListener(kioskListener);
        syncKioskMode();
        constrainWindow();
        layoutNow();
    }

    private Separator chromeSeparator(boolean pillJoin) {
        Region bar=new Region();
        bar.setManaged(false);
        bar.setMouseTransparent(true);
        bar.setSnapToPixel(false);
        bar.getStyleClass().add("fish-chrome-separator-bar");
        chromeSeparatorBars.add(bar);
        Separator sep=new Separator(Orientation.VERTICAL) {
            private boolean added;
            @Override protected void layoutChildren() {
                super.layoutChildren();
                for (Node child:getChildren()) {
                    if (child!=bar) { child.setVisible(false); child.setManaged(false); }
                }
                if (!added) { added=true; getChildren().add(bar); }
                double s=Ui.scaleFor(this);
                double px=Ui.hairline(s);
                double w=getWidth(),h=getHeight();
                if (pillJoin) {
                    bar.resizeRelocate(0,0,px,h);
                    return;
                }
                Point2D sp=localToScene(w*0.5,0);
                double bx=(w-px)*0.5;
                if (sp!=null) {
                    double dev=Math.round(sp.getX()*s);
                    bx=w*0.5+(dev/s-sp.getX());
                }
                double bh=pillJoin ? h : Math.min(h,Math.round(16.0*s)/s);
                double by=(h-bh)*0.5;
                bar.resizeRelocate(bx,by,px,bh);
            }
        };
        sep.getStyleClass().add("fish-chrome-separator");
        if (pillJoin) {
            sep.getStyleClass().add("fish-chrome-pill-join");
            sep.setViewOrder(-1.0);
        }
        sep.setFocusTraversable(false);
        sep.setMouseTransparent(true);
        sep.setSnapToPixel(false);
        double sw=pillJoin ? 1 : 13;
        double sh=pillJoin ? 24 : 20;
        sep.setMinSize(sw,sh);
        sep.setPrefSize(sw,sh);
        sep.setMaxSize(sw,sh);
        return sep;
    }

    public Stage stage() { return stage; }
    public Scene scene() { return scene; }
    public BorderPane shell() { return shell; }
    public StackPane contentPane() { return content; }
    public Node treeNode() { return tree; }
    public WindowChrome chrome() { return chrome; }
    public MenuBar menuBar() { return menuBar; }
    public AppShortcuts shortcuts() { return shortcuts; }
    public ComboBox<Profile> addProfileCombo() { return addProfileSelector; }
    public Button addPaneButton() { return addPane; }

    public boolean isShowing() { return stage.isShowing(); }
    public boolean isVisible() { return stage.isShowing(); }
    public void setVisible(boolean visible) {
        if (visible) { stage.show(); layoutNow(); }
        else stage.hide();
    }
    public void toFront() { stage.toFront(); }
    public String getTitle() { return stage.getTitle(); }
    public void setTitle(String t) { stage.setTitle(t); }
    public boolean isEnabled() { return !shell.isDisabled(); }
    public void setEnabled(boolean enabled) { shell.setDisable(!enabled); }

    public int getWidth() {
        int minW=minimumWidth();
        int w=(int)Math.round(stage.isShowing() && stage.getWidth()>0 ? stage.getWidth() : logicalWidth);
        return Math.max(minW,w);
    }
    public int getHeight() {
        int minH=minimumHeight();
        int h=(int)Math.round(stage.isShowing() && stage.getHeight()>0 ? stage.getHeight() : logicalHeight);
        return Math.max(minH,h);
    }
    public int minimumWidth() { return Math.max(330,(int)Math.ceil(stage.getMinWidth())); }
    public int minimumHeight() { return Math.max(260,(int)Math.ceil(stage.getMinHeight())); }

    public void setSize(int width,int height) {
        constrainWindow();
        int w=Math.max(minimumWidth(),width);
        int h=Math.max(minimumHeight(),height);
        logicalWidth=w;
        logicalHeight=h;
        stage.setWidth(w);
        stage.setHeight(h);
        shell.resize(w,h);
        layoutNow();
        windowGeometryChanged();
    }

    public void layoutNow() {
        double w=logicalWidth>0 ? logicalWidth : 1060;
        double h=logicalHeight>0 ? logicalHeight : 740;
        if (!stage.isShowing()) {
            shell.resize(Math.max(minimumWidth(),w),Math.max(minimumHeight(),h));
        }
        shell.applyCss();
        shell.layout();
    }

    void layoutOverlays() { centerHost.requestLayout(); }

    void constrainWindow() {
        if (chrome==null || tree==null) return;
        double paneMinW=tree.minWidth(-1),paneMinH=tree.minHeight(-1);
        double chromeMinW=chrome.minimumBarWidth();
        int border=appearance!=null && appearance.windowOutline() ? 2 : 0;
        double minW=Math.max(330,Math.max(chromeMinW,paneMinW)+border);
        double minH=Math.max(260,paneMinH+WindowChrome.HEIGHT+border);
        stage.setMinWidth(minW);
        stage.setMinHeight(minH);
        if (!stage.isMaximized() && (logicalWidth<minW || logicalHeight<minH)) {
            logicalWidth=Math.max(logicalWidth,minW);
            logicalHeight=Math.max(logicalHeight,minH);
            stage.setWidth(logicalWidth);
            stage.setHeight(logicalHeight);
        }
    }

    public SessionPane active() { return active; }
    void application(ApplicationWindows value) { application=value; saveTimer.stop(); }
    public CredentialStore credentials() { return credentials; }
    public List<Profile> profileList() { return List.copyOf(profiles.values()); }
    public Profile lastProfile() { return profiles.get(lastProfile); }
    public Profile loginProfile(Profile profile) { return Profile.login(profile,profileList()); }
    public Profile connectionProfile(Profile profile) { return Profile.effective(profile,profileList()); }
    public StateStore.Tutorial tutorialStatus() { return tutorialStatus; }
    void tutorialStatus(StateStore.Tutorial status) { tutorialStatus=status; changed(); }

    public void startTutorialIfNeeded() {
        if (!demonstration && !KioskMode.isEnabled() && tutorialStatus==StateStore.Tutorial.NEW) openTutorial(true);
    }

    public void startup() { startup(false); }
    public void startup(boolean explicit) {
        if (startupHandled || demonstration || closing) return;
        startupHandled=true;
        if (connectOnStartup || explicit) sessionPanes().forEach(SessionPane::connectOnStartup);
        startTutorialIfNeeded();
        if (!KioskMode.isEnabled() && updateSettings.startup() && (application==null || application.checkStartupUpdates(this))) updates.startup();
    }

    public boolean closing() { return closing || application!=null && application.closing(); }
    public UpdateSettings updateSettings() { return updateSettings; }
    public void updateSettings(UpdateSettings value) { updateSettings=Objects.requireNonNull(value); changed(); }
    public Path dataDirectory() { return store.file().getParent(); }

    public void showTutorial() { openTutorial(false); }
    private void openTutorial(boolean firstRun) { openTutorial(TutorialDialog.Guide.GETTING_STARTED,firstRun); }
    public void openTutorial(TutorialDialog.Guide guide,boolean firstRun) {
        if (KioskMode.isEnabled()) return;
        if (tutorial!=null && !tutorial.ended()) tutorial.finish(false);
        tutorial=new TutorialDialog(this,guide,firstRun);
        if (isShowing()) { tutorial.setVisible(true); tutorial.focusGuide(); }
    }
    public TutorialDialog activeTutorial() { return tutorial; }
    void tutorialChanged() { if (tutorial!=null) tutorial.refreshTarget(); }
    public void tutorialAction(String action) { if (tutorial!=null && !tutorial.ended()) tutorial.action(action); }

    void profileEditorOpened(ProfilesEditor editor) { profileEditor=editor; tutorialChanged(); }
    void profileEditorClosed(ProfilesEditor editor) { if (profileEditor==editor) profileEditor=null; tutorialChanged(); }
    public ProfilesEditor tutorialProfileEditor() {
        return profileEditor!=null ? profileEditor : preferences!=null && !preferences.ended() && "Profiles".equals(preferences.category())
                ? preferences.profiles : null;
    }
    public Preferences tutorialPreferences() { return preferences!=null && !preferences.ended() ? preferences : null; }
    Node tutorialAddTarget() { return addPane; }
    Node tutorialProfileTarget() { return addProfileSelector; }
    Node tutorialSettingsTarget() { return tutorialMenuTarget("Edit","Settings..."); }
    void showTutorialSettings() { showTutorialMenu("Edit"); }
    public Node tutorialDivider(boolean horizontal) { return dividerTarget(tree,horizontal); }
    private Node dividerTarget(Node node,boolean horizontal) {
        if (!(node instanceof WorkspaceSplit split)) return null;
        if (split.isHorizontal()==horizontal) return split.dividerNode();
        Node left=dividerTarget(split.firstChild(),horizontal);
        return left!=null ? left : dividerTarget(split.secondChild(),horizontal);
    }
    Node tutorialMenuTarget(String menu,String action) { return chrome.tutorialMenuTarget(menu,action); }
    void showTutorialMenu(String menu) { chrome.showTutorialMenu(menu); }

    public void selectProfile(SessionPane pane,Profile profile) {
        if (KioskMode.isEnabled()) return;
        if (pane.busy()) throw new IllegalStateException("Disconnect before selecting a preset.");
        lastProfile=profile.id(); pane.updateProfile(profile); refreshShortcutLabels(); changed();
    }
    public void rememberProfile(Profile profile) { lastProfile=profile.id(); changed(); }
    public boolean hasPane(UUID id) { return panes.containsKey(id); }
    public boolean hasMultiplePanes() { return layout instanceof Layout.Split; }
    public boolean highlightActive() { return highlightActive; }
    public Appearance appearanceSettings() { return appearance; }
    public static void withDeferredFontWarmup(java.util.concurrent.CompletableFuture<Void> warmup,Runnable action) {
        DEFERRED_FONT_WARMUP.set(warmup);
        try { action.run(); }
        finally { DEFERRED_FONT_WARMUP.remove(); }
    }

    private void finishDeferredFontResolution() {
        if (closing || terminalFont!=null) return;
        resolveFont();
        if (!fontNotice.isEmpty() && active!=null) {
            active.problem(fontNotice);
            if (stage.isShowing()) Ui.error(stage,fontNotice);
        }
    }

    public Font terminalFont() {
        if (terminalFont==null) resolveFont();
        return terminalFont;
    }

    public void appearanceSettings(Appearance value) {
        Appearance previous=appearance;
        appearance=value;
        Ui.setReducedMotion(value.reducedMotion());
        Ui.surfacePreferences(value.flatThemes());
        boolean font=!previous.font().equals(value.font());
        if (font) resolveFont();
        if (!previous.flatThemes().equals(value.flatThemes()) || !previous.custom().equals(value.custom())
                || !Objects.equals(Ui.customTheme() ? Ui.colorTheme() : null,value.custom().orElse(null)))
            Ui.applyTheme(Ui.theme(),appearance.custom().orElse(null));
        else updateWindowOutline();
        panes.values().forEach(p -> { if (font) p.view().fontChanged(); p.updateHeader(); });
        constrainWindow();
        tutorialChanged();
        changed();
        if (!fontNotice.isEmpty() && active!=null) active.problem(fontNotice);
    }

    private void resolveFont() {
        try {
            terminalFont=TerminalFonts.resolve(appearance.font());
            TerminalFonts.validate(terminalFont,profileList());
            fontNotice="";
        } catch (java.io.IOException | IllegalArgumentException e) {
            fontNotice="Terminal font unavailable: "+e.getMessage()+"\nUsing Java Monospaced temporarily; saved choice retained. Settings > Appearance can repair it.";
            System.err.println(fontNotice);
            terminalFont=new Font(Font.MONOSPACED,Font.PLAIN,16);
        }
    }

    public void showShortcuts() { preferences("Keyboard Shortcuts"); }
    public void preferences(String category) {
        if (KioskMode.isEnabled()) return;
        if (application!=null) {
            Workspace owner=application.settingsOwner(this);
            if (owner!=this) { owner.preferences(category); return; }
        }
        Stage dialog=preferencesDialog(category);
        if (isShowing()) { dialog.show(); dialog.toFront(); preferences.focus(); }
        tutorialAction("settings-"+preferences.category());
        tutorialChanged();
    }

    public Stage preferencesDialog(String category) {
        if (application!=null) {
            Workspace owner=application.settingsOwner(this);
            if (owner!=this) return owner.preferencesDialog(category);
        }
        if (preferences==null || preferences.ended()) preferences=new Preferences(this,category);
        else preferences.select(category);
        return preferences.dialog();
    }

    void preferencesClosed(Preferences value) { if (preferences==value) preferences=null; tutorialChanged(); }
    public UpdateController updateController() { return updates; }
    boolean localSettingsSaving() { return exportingShortcut() || preferences!=null && preferences.saving() || profileEditor!=null && profileEditor.saving(); }
    boolean settingsSaving() { return localSettingsSaving() || application!=null && application.saving(); }
    void refreshActions() { if (application==null) refreshCommands(); else application.refreshActions(); }
    public Map<String,String> keyboardRecovery() { return keyboardRecovery; }
    void keyboardRecovery(Map<String,String> value) { KeyboardMigration.validateRecovery(value); keyboardRecovery=Map.copyOf(value); changed(); }
    public boolean connectOnStartup() { return connectOnStartup; }
    public void connectOnStartup(boolean value) {
        connectOnStartup=value; command("Connect panes on startup").setSelected(value); changed();
    }
    public void executeCommand(String title) { command(title).execute(); }

    public Set<KeyMnemonic> reassignedHostActions() { return shortcuts.reassignedHostActions(); }
    public Map<String,String> shortcutOverrides() { return shortcuts.overrides(); }
    public String shortcutLabel(String title) { return command(title).primaryShortcut(); }

    public void applyShortcuts(Map<String,String> values) {
        shortcuts.apply(values,profileList());
        panes.values().forEach(p -> { p.refreshKeys(); p.updateHeader(); });
        refreshShortcutLabels();
        changed();
    }

    public void validateProfile(Profile profile) {
        if (!profile.keymap().isEmpty())
            throw new IllegalArgumentException("Keyboard mappings are application-wide. Use Settings > Keyboard Shortcuts, not per-profile keys.");
        List<Profile> candidates=new ArrayList<>(profileList());
        candidates.removeIf(p -> p.id().equals(profile.id())); candidates.add(profile);
        Profile existing=profiles.get(profile.id());
        if (existing!=null && (connectionChanged(existing,profile) || existing.rememberPassword()!=profile.rememberPassword())
                && allPanes().stream().anyMatch(p -> p.connecting()
                        && (p.profile().id().equals(profile.id()) || profile.id().equals(p.profile().loginSource()))))
            throw new IllegalArgumentException("This login has a pending connection/password operation. Wait for it to finish before changing its login.");
        for (Profile candidate:candidates) Profile.login(candidate,candidates);
        shortcuts.validate(shortcuts.overrides(),candidates);
        try { TerminalFonts.validate(terminalFont(),candidates); }
        catch (java.io.IOException e) { throw new IllegalArgumentException(e.getMessage(),e); }
    }

    public void highlightActive(boolean enabled) {
        highlightActive=enabled; panes.values().forEach(SessionPane::updateHeader); changed();
    }

    public static List<UUID> spatialPaneOrder(Layout layout) {
        if (layout==null || layout instanceof Layout.Empty) return List.of();
        List<Layout.Leaf> leaves=layout.leaves();
        if (leaves.size()<=1) return leaves.stream().map(Layout.Leaf::pane).toList();
        Map<UUID,Rectangle> bounds=project(layout,new Rectangle(0,0,10_000,10_000));
        record Entry(UUID id,Rectangle rect) {}
        List<Entry> byTop=new ArrayList<>(leaves.size());
        for (Layout.Leaf leaf:leaves) {
            byTop.add(new Entry(leaf.pane(),bounds.getOrDefault(leaf.pane(),new Rectangle(0,0,0,0))));
        }
        byTop.sort(Comparator.comparingInt((Entry e) -> e.rect.y).thenComparingInt(e -> e.rect.x));
        List<List<Entry>> bands=new ArrayList<>();
        for (Entry candidate:byTop) {
            if (!bands.isEmpty()) {
                List<Entry> current=bands.getLast();
                boolean sameBand=true;
                for (Entry existing:current) {
                    int overlap=Math.min(candidate.rect.y+candidate.rect.height,existing.rect.y+existing.rect.height)
                            -Math.max(candidate.rect.y,existing.rect.y);
                    double minHeight=Math.min(candidate.rect.height,existing.rect.height);
                    if (overlap<=0.5*minHeight) {
                        sameBand=false;
                        break;
                    }
                }
                if (sameBand) {
                    current.add(candidate);
                    continue;
                }
            }
            List<Entry> band=new ArrayList<>();
            band.add(candidate);
            bands.add(band);
        }
        Comparator<Entry> inBand=Comparator.comparingInt((Entry e) -> e.rect.x).thenComparingInt(e -> e.rect.y);
        List<UUID> ordered=new ArrayList<>(leaves.size());
        for (List<Entry> band:bands) {
            band.sort(inBand);
            for (Entry e:band) ordered.add(e.id);
        }
        return List.copyOf(ordered);
    }

    public List<SessionPane> sessionPanes() {
        Layout effective=tree!=null && !rebuildingLayout && !(layout instanceof Layout.Empty) ? capture(tree) : layout;
        if (Objects.equals(effective,cachedSpatialLayout) && cachedSessionPanes.size()==panes.size()) {
            boolean intact=true;
            for (SessionPane p:cachedSessionPanes) {
                if (panes.get(p.id())!=p) { intact=false; break; }
            }
            if (intact) return cachedSessionPanes;
        }
        List<SessionPane> ordered=spatialPaneOrder(effective).stream().map(panes::get).filter(Objects::nonNull).toList();
        cachedSpatialLayout=effective;
        cachedSessionPanes=ordered;
        return ordered;
    }
    public List<AppCommand> commands() { return List.copyOf(commands); }
    public AppCommand command(String name) { return commands.stream().filter(c -> c.title.equals(name)).findFirst().orElseThrow(); }

    public void refreshCommands() {
        commands.forEach(AppCommand::refresh);
        if (addPane!=null) {
            Ui.setTooltipText(addPane,addPaneTooltip,panes.size()>=Layout.MAX_PANES
                    ? "Eight panes open; close one before adding"
                    : "Add and connect "+Objects.toString(profiles.get(addProfile),"selected profile"));
        }
    }

    private void refreshAddProfiles() {
        refreshingAdd=true;
        try {
            if (!profiles.containsKey(addProfile)) addProfile=profiles.keySet().iterator().next();
            addProfileSelector.setItems(FXCollections.observableArrayList(profileList()));
            addProfileSelector.setValue(profiles.get(addProfile));
        } finally { refreshingAdd=false; }
        if (chrome!=null) { constrainWindow(); chrome.requestLayout(); }
    }

    private void refreshShortcutLabels() {
        if (shortcuts!=null) shortcuts.refreshLabels();
    }

    public void themeChanged() {
        chrome.themeChanged();
        Ui.updateIcon(addPane.getGraphic(),"plus",ColorRole.CHROME_TEXT);
        updateWindowOutline();
        refreshSplitStyles(tree);
        panes.values().forEach(SessionPane::themeChanged);
        if (dropOverlay.isVisible()) paintDropOverlay();
        constrainWindow();
    }

    private void refreshSplitStyles(Node node) {
        if (node instanceof WorkspaceSplit split) {
            split.styleDivider();
            refreshSplitStyles(split.firstChild());
            refreshSplitStyles(split.secondChild());
        }
    }

    public int paneCount() { return panes.size(); }

    private static Region windowOutlineBar() {
        Region r=new Region();
        r.setManaged(false);
        r.setMouseTransparent(true);
        r.setSnapToPixel(false);
        r.setViewOrder(-2.0);
        r.setVisible(false);
        return r;
    }

    private void layoutWindowOutline() {
        boolean vis=showFallbackOutline && shell.getWidth()>0 && shell.getHeight()>0;
        windowTop.setVisible(vis);
        windowBottom.setVisible(vis);
        windowLeft.setVisible(vis);
        windowRight.setVisible(vis);
        if (!vis) return;
        double s=Ui.scaleFor(shell);
        double px=Ui.hairline(s);
        double w=shell.getWidth(),h=shell.getHeight();
        double rightX=Math.max(0,(Math.ceil(w*s-1e-6)-1.0)/s);
        double bottomY=Math.max(0,(Math.ceil(h*s-1e-6)-1.0)/s);
        String style="-fx-background-color: "+Ui.hex(ColorRole.DIVIDER)+";";
        for (Region r:List.of(windowTop,windowBottom,windowLeft,windowRight)) Ui.setStyleIfChanged(r,style);
        windowTop.resizeRelocate(0,0,rightX+px,px);
        windowBottom.resizeRelocate(0,bottomY,rightX+px,px);
        windowLeft.resizeRelocate(0,0,px,bottomY+px);
        windowRight.resizeRelocate(rightX,0,px,bottomY+px);
    }

    private void updateWindowOutline() {
        boolean wantOutline=appearance!=null && appearance.windowOutline();
        boolean hasClass=shell.getStyleClass().contains("fish-window-outlined");
        if (wantOutline && !hasClass) shell.getStyleClass().add("fish-window-outlined");
        else if (!wantOutline && hasClass) shell.getStyleClass().remove("fish-window-outlined");

        boolean appliedDwm=stage.isShowing() && WindowBorder.isWindows11()
                && WindowBorder.sync(wantOutline && !stage.isMaximized());
        boolean suppressCssBorder=stage.isMaximized() || appliedDwm;
        boolean hasDwmClass=shell.getStyleClass().contains("fish-dwm-border");
        if (suppressCssBorder && !hasDwmClass) shell.getStyleClass().add("fish-dwm-border");
        else if (!suppressCssBorder && hasDwmClass) shell.getStyleClass().remove("fish-dwm-border");

        showFallbackOutline=wantOutline && !suppressCssBorder;
        layoutWindowOutline();
        for (SessionPane p:panes.values()) p.requestLayout();
    }

    public void applyTheme(AppTheme theme) {
        appearance=appearance.withTheme("",appearance.themes());
        Ui.applyTheme(theme);
        changed();
    }

    public String paneName(UUID id) {
        return "Pane "+(sessionPanes().indexOf(panes.get(id))+1);
    }

    public void activate(SessionPane pane) {
        pane.view().restartCursor();
        if (active==pane) { refreshCommands(); return; }
        active=pane;
        tutorialChanged();
        refreshShortcutLabels();
        panes.values().forEach(SessionPane::updateHeader);
        refreshCommands();
    }

    public void onClosed(Runnable action) { afterClose=action; }
    public void connectAll() { sessionPanes().forEach(SessionPane::connectOnStartup); }

    public void windowGeometryChanged() {
        if (closing || settingsBatch>0) return;
        if (application!=null) application.scheduleSave();
        else saveTimer.playFromStart();
    }

    public long stateBuildCountForTest() { return stateBuildCount; }

    public void changed() {
        if (closing) return;
        if (settingsBatch>0) return;
        if (application!=null) application.changed(this); else saveTimer.playFromStart();
        if (preferences!=null) preferences.sharedChanged();
    }

    void beginSettings() { settingsBatch++; }
    void endSettings() { if (--settingsBatch==0) changed(); }
    private List<SessionPane> allPanes() { return application==null ? sessionPanes() : application.panes(); }

    public void setLayoutForTest(Layout nextLayout) {
        this.layout=nextLayout;
        buildTree();
        layoutNow();
    }

    private void buildTree() {
        Set<UUID> live=new HashSet<>();
        layout.leaves().forEach(l -> live.add(l.pane()));
        if (!arrangementHistory.isEmpty() && !arrangementHistory.peek().leaves().stream().map(Layout.Leaf::pane).collect(java.util.stream.Collectors.toSet()).equals(live))
            arrangementHistory.clear();
        clearPreviews();
        Iterator<Map.Entry<UUID,SessionPane>> iterator=panes.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry=iterator.next();
            if (!live.contains(entry.getKey())) { entry.getValue().dispose(); iterator.remove(); }
        }
        content.getChildren().clear();
        rebuildingLayout=true;
        try { tree=buildComponent(layout); } finally { rebuildingLayout=false; }
        content.getChildren().add(tree);
        constrainWindow();
        if (active==null || !live.contains(active.id())) active=live.isEmpty() ? null : panes.get(layout.leaves().getFirst().pane());
        panes.values().forEach(SessionPane::updateHeader);
        refreshShortcutLabels();
        refreshCommands();
        tutorialChanged();
        Ui.runLater(() -> { if (active!=null) active.focusTerminal(); });
    }

    private Node buildComponent(Layout node) {
        if (node instanceof Layout.Leaf leaf)
            return panes.computeIfAbsent(leaf.pane(),id -> new SessionPane(this,id,Objects.requireNonNull(profiles.get(leaf.profile()))));
        Layout.Split split=(Layout.Split)node;
        return new WorkspaceSplit(this,split,buildComponent(split.first()),buildComponent(split.second()));
    }

    void resizeDividerLive(WorkspaceSplit split,int targetLocation) {
        if (!applyDividerLocation(split,targetLocation)) return;
        windowGeometryChanged();
    }

    public void resizeDivider(WorkspaceSplit split,int targetLocation) {
        if (!applyDividerLocation(split,targetLocation)) return;
        changed();
    }

    private boolean applyDividerLocation(WorkspaceSplit split,int targetLocation) {
        if (KioskMode.isEnabled() || adjustingDividers || rebuildingLayout || tree==null) return false;
        adjustingDividers=true;
        double previousRatio=split.userRatio();
        try {
            int extent=(int)Math.round(split.isHorizontal() ? split.getWidth() : split.getHeight());
            if (extent>4) {
                double candidateRatio=Math.max(0.05,Math.min(0.95,Layout.dividerRatio(split.isThreeStrips(),targetLocation,extent)));
                split.setUserRatio(candidateRatio);
            }
            Layout candidate=capture(tree);
            int cw=(int)Math.round(content.getWidth()),ch=(int)Math.round(content.getHeight());
            if (!Layout.constrained(candidate)
                    || (cw>0 && ch>0 && project(candidate,new Rectangle(0,0,cw,ch)).values().stream().anyMatch(r -> r.width<70 || r.height<70))) {
                split.setUserRatio(previousRatio);
            }
            split.layout();
            if (lastPreviewKey!=null && split.userRatio()!=previousRatio) clearPreviews();
        } finally {
            adjustingDividers=false;
        }
        return true;
    }

    private Layout capture(Node node) {
        if (node==tree && layout instanceof Layout.Empty) return layout;
        if (node instanceof SessionPane pane) return new Layout.Leaf(pane.id(),pane.profile().id());
        WorkspaceSplit split=(WorkspaceSplit)node;
        return new Layout.Split(split.isHorizontal(),
                Math.max(.05,Math.min(.95,split.userRatio())),capture(split.firstChild()),capture(split.secondChild()));
    }

    public Layout currentLayout() { layout=capture(tree); return layout; }

    public StateStore.State state() {
        stateBuildCount++;
        return new StateStore.State(profileList(),currentLayout(),getWidth(),getHeight(),Ui.theme(),lastProfile,
                shortcuts.overrides(),highlightActive,appearance,tutorialStatus,connectOnStartup,updateSettings,keyboardRecovery,
                List.of(),addProfile,savedLayouts,selectedLayout,displayState());
    }

    private Map<UUID,SavedLayout.Display> displayState() {
        Map<UUID,SavedLayout.Display> result=new LinkedHashMap<>();
        sessionPanes().forEach(p -> result.put(p.id(),new SavedLayout.Display(p.view().isFit(),p.view().zoomValue())));
        return result;
    }

    public void saveAsync() {
        if (KioskMode.isEnabled()) return;
        if (application!=null) { application.saveAsync(); return; }
        if (closing) return;
        if (exportingShortcut()) { saveTimer.playFromStart(); return; }
        StateStore.State snapshot=state();
        persistence.submit(() -> {
            try { store.save(snapshot); }
            catch (java.io.IOException e) {
                Ui.runLater(() -> {
                    if (closing) System.err.println("Fish workspace not saved: "+e.getMessage());
                    else Ui.error(stage,"Workspace not saved: "+e.getMessage());
                });
            }
        });
    }

    void persistSettings(Runnable done,java.util.function.Consumer<String> failed) {
        if (KioskMode.isEnabled()) { failed.accept("Settings and layouts cannot be modified while Kiosk mode is enabled."); return; }
        // Take this settings snapshot after a pending export publishes its UUID, not before.
        Workspace exporter=application==null ? this : application.shortcutWriter();
        if (exporter!=null && exporter.exportingShortcut()) {
            exporter.shortcutWrite.thenRun(() -> persistSettings(done,failed)); return;
        }
        saveTimer.stop();
        if (application!=null) { application.persistTask(() -> {},done,failed); return; }
        StateStore.State snapshot=state();
        persistence.submit(() -> {
            try { store.save(snapshot); Ui.runLater(done); }
            catch (java.io.IOException | RuntimeException e) { Ui.runLater(() -> failed.accept(e.getMessage())); }
        });
    }

    public void split(boolean horizontal) {
        if (KioskMode.isEnabled() || closing() || settingsSaving() || panes.size()>=Layout.MAX_PANES) return;
        Profile selected=profiles.get(addProfile);
        if (selected==null) { if (active!=null) active.problem("Select a saved profile before adding."); return; }
        SessionPane next;
        if (panes.size()==1 && active.pristine()) {
            next=active; next.updateProfile(selected);
        } else {
            UUID id=UUID.randomUUID();
            int cw=(int)Math.round(content.getWidth()),ch=(int)Math.round(content.getHeight());
            try {
                layout=currentLayout().add(new Layout.Leaf(id,selected.id()),active.id(),candidate ->
                        cw<70 || ch<70 || project(candidate,new Rectangle(0,0,cw,ch))
                                .values().stream().allMatch(r -> r.width>=70 && r.height>=70));
            } catch (IllegalArgumentException e) { active.problem(e.getMessage()); return; }
            arrangementHistory.clear(); buildTree(); next=panes.get(id);
        }
        lastProfile=selected.id();
        activate(next);
        next.connectOnStartup();
        next.focusTerminal();
        changed();
        tutorialAction("Add pane");
    }

    public void dock(UUID source,UUID target,Layout.Dock dock) {
        dock(source,target,dock,Layout.Scope.PANE);
    }

    public void dock(UUID source,UUID target,Layout.Dock dock,Layout.Scope scope) {
        DropPlan plan=planDock(source,target,dock,scope);
        clearPreviews();
        if (plan!=null) applyDock(plan);
    }

    private void applyDock(DropPlan plan) {
        arrangementHistory.push(currentLayout());
        while (arrangementHistory.size()>20) arrangementHistory.removeLast();
        layout=plan.layout();
        buildTree();
        active=panes.get(plan.source());
        activate(active);
        Ui.runLater(active::focusTerminal);
        changed();
        tutorialAction(plan.dock()==Layout.Dock.CENTER ? "panes-swapped" : "panes-arranged");
    }

    public static Map<UUID,Rectangle> project(Layout layout,Rectangle area) {
        Map<UUID,Rectangle> result=new LinkedHashMap<>();
        project(layout,area,result);
        return result;
    }

    private static void project(Layout layout,Rectangle r,Map<UUID,Rectangle> result) {
        if (layout instanceof Layout.Leaf leaf) { result.put(leaf.pane(),new Rectangle(r)); return; }
        Layout.Split split=(Layout.Split)layout;
        Rectangle[] areas=partition(split,r,split.ratio());
        project(split.first(),areas[0],result);
        project(split.second(),areas[1],result);
    }

    public static Rectangle[] partition(Layout.Split split,Rectangle r,double ratio) {
        int extent=Math.max(0,split.horizontal() ? r.width : r.height);
        int first=Layout.firstPixels(split,extent,ratio);
        Rectangle a=new Rectangle(r),b=new Rectangle(r);
        if (split.horizontal()) { a.width=first; b.x+=first; b.width=extent-first; }
        else { a.height=first; b.y+=first; b.height=extent-first; }
        return new Rectangle[]{a,b};
    }

    public DropPlan planDock(UUID source,UUID target,Layout.Dock dock) {
        return planDock(source,target,dock,Layout.Scope.PANE);
    }

    public DropPlan planDock(UUID source,UUID target,Layout.Dock dock,Layout.Scope scope) {
        if (KioskMode.isEnabled() || closing() || settingsSaving() || scope==Layout.Scope.PANE && source.equals(target) || !hasPane(source) || !hasPane(target)) return null;
        try {
            Layout current=currentLayout(),next=current.move(source,target,dock,scope);
            if (next.equals(current)) return null;
            int cw=Math.max(1,(int)Math.round(content.getWidth()>0 ? content.getWidth() : getWidth()));
            int ch=Math.max(1,(int)Math.round(content.getHeight()>0 ? content.getHeight() : getHeight()-WindowChrome.HEIGHT));
            Map<UUID,Rectangle> bounds=project(next,new Rectangle(0,0,cw,ch));
            if (dock!=Layout.Dock.CENTER && bounds.values().stream().anyMatch(r -> r.width<70 || r.height<70)) return null;
            return new DropPlan(source,target,dock,scope,next,bounds);
        } catch (IllegalArgumentException e) { return null; }
    }

    public boolean previewDock(UUID source,UUID target,Layout.Dock dock) {
        return previewDock(source,target,dock,Layout.Scope.PANE);
    }

    public boolean previewDock(UUID source,UUID target,Layout.Dock dock,Layout.Scope scope) {
        PreviewKey key=new PreviewKey(source,target,dock,scope);
        if (key.equals(lastPreviewKey)) return lastPreviewValid;
        dropPlan=planDock(source,target,dock,scope);
        lastPreviewKey=key;
        lastPreviewValid=dropPlan!=null;
        if (dropPlan==null) {
            dropOverlay.setVisible(false);
            return false;
        }
        dropOverlay.setVisible(true);
        centerHost.requestLayout();
        paintDropOverlay();
        return true;
    }

    public DockTarget dropTargetAtScene(double sceneX,double sceneY) {
        Point2D cp=content.sceneToLocal(sceneX,sceneY);
        if (cp==null) return null;
        double px=cp.getX(),py=cp.getY();
        if (px<0 || py<0 || px>=content.getWidth() || py>=content.getHeight()) return null;
        for (SessionPane candidate:panes.values()) {
            Point2D lp=candidate.sceneToLocal(sceneX,sceneY);
            if (lp!=null && lp.getX()>=0 && lp.getY()>=0 && lp.getX()<candidate.getWidth() && lp.getY()<candidate.getHeight()) {
                return classifyDrop(candidate,px,py,lp);
            }
        }
        return null;
    }

    public boolean previewDockAtScene(UUID source,double sceneX,double sceneY) {
        DockTarget placement=dropTargetAtScene(sceneX,sceneY);
        if (placement!=null && previewDock(source,placement.pane(),placement.dock(),placement.scope())) {
            return true;
        }
        clearPreviews();
        return false;
    }

    public boolean commitDockAtScene(UUID source,double sceneX,double sceneY) {
        DockTarget placement=dropTargetAtScene(sceneX,sceneY);
        DropPlan plan=placement==null ? null : planDock(source,placement.pane(),placement.dock(),placement.scope());
        clearPreviews();
        if (plan==null) return false;
        applyDock(plan);
        return true;
    }

    private void paintDropOverlay() {
        GraphicsContext gc=dropOverlay.getGraphicsContext2D();
        gc.clearRect(0,0,dropOverlay.getWidth(),dropOverlay.getHeight());
        if (dropPlan==null) return;
        javafx.scene.paint.Color focusColor=Ui.fxColor(ColorRole.FOCUS);
        javafx.scene.paint.Color fillTint=javafx.scene.paint.Color.color(focusColor.getRed(),focusColor.getGreen(),focusColor.getBlue(),0.25);
        for (var entry:dropPlan.bounds().entrySet()) {
            Rectangle r=entry.getValue();
            if (entry.getKey().equals(dropPlan.source())) {
                gc.setFill(fillTint);
                gc.fillRect(r.x,r.y,r.width,r.height);
                gc.setStroke(focusColor);
                gc.setLineWidth(1.5);
                gc.strokeRect(r.x+1,r.y+1,Math.max(1,r.width-2),Math.max(1,r.height-2));
                String label=dropPlan.dock()==Layout.Dock.CENTER ? "Swap panes" : (dropPlan.scope()==Layout.Scope.WINDOW ? "Window " : "Pane ")
                        +dropPlan.dock().name().toLowerCase(Locale.ROOT)+" - "+dropPlan.layout().arrangement();
                double badgeW=Math.min(r.width-12,Math.max(70,label.length()*7.0+18)),badgeH=26;
                double bx=r.x+(r.width-badgeW)/2.0,by=r.y+(r.height-badgeH)/2.0;
                gc.setFill(Ui.fxColor(ColorRole.PANEL));
                gc.fillRect(bx,by,badgeW,badgeH);
                gc.setStroke(Ui.fxColor(ColorRole.DIVIDER));
                gc.setLineWidth(1);
                gc.strokeRect(bx+.5,by+.5,badgeW-1,badgeH-1);
                gc.setFill(Ui.fxColor(ColorRole.TEXT));
                gc.setFont(javafx.scene.text.Font.font("Segoe UI",12));
                gc.fillText(label,bx+8,by+17,badgeW-16);
            } else {
                gc.setStroke(focusColor);
                gc.setLineWidth(1);
                gc.strokeRect(r.x+.5,r.y+.5,Math.max(1,r.width-1),Math.max(1,r.height-1));
            }
        }
    }

    public DockTarget dropTarget(UUID paneId,Node origin,double localX,double localY) {
        Point2D scenePt=origin.localToScene(localX,localY);
        Point2D contentPt=content.sceneToLocal(scenePt);
        return contentPt==null ? null : dropTarget(paneId,(int)Math.round(contentPt.getX()),(int)Math.round(contentPt.getY()));
    }

    public DockTarget dropTarget(UUID paneId,java.awt.Point position) {
        return dropTarget(paneId,position.x,position.y);
    }

    public DockTarget dropTarget(UUID paneId,int px,int py) {
        int cw=(int)Math.round(content.getWidth()),ch=(int)Math.round(content.getHeight());
        if (px<0 || py<0 || px>=cw || py>=ch || !hasPane(paneId)) return null;
        SessionPane targetPane=panes.get(paneId);
        Point2D paneLocal=targetPane.sceneToLocal(content.localToScene(px,py));
        if (paneLocal==null || paneLocal.getX()<0 || paneLocal.getX()>=targetPane.getWidth()
                || paneLocal.getY()<0 || paneLocal.getY()>=targetPane.getHeight()) return null;
        return classifyDrop(targetPane,px,py,paneLocal);
    }

    private DockTarget classifyDrop(SessionPane target,double x,double y,Point2D local) {
        double w=content.getWidth(),h=content.getHeight();
        if (panes.size()>=2 && panes.size()<=4) {
            // Reserve the inner part of each pane's 25% edge zone for local drops.
            double bandX=Math.min(72,Math.min(w*.10,target.getWidth()*.16));
            double bandY=Math.min(72,Math.min(h*.10,target.getHeight()*.16));
            double dx=Math.min(x,w-x)/bandX,dy=Math.min(y,h-y)/bandY;
            if (Math.min(dx,dy)<1) {
                Layout.Dock edge=dx<dy ? (x<w/2 ? Layout.Dock.LEFT : Layout.Dock.RIGHT)
                        : (y<h/2 ? Layout.Dock.TOP : Layout.Dock.BOTTOM);
                return new DockTarget(target.id(),edge,Layout.Scope.WINDOW);
            }
        }
        return new DockTarget(target.id(),SessionPane.direction(local.getX(),local.getY(),target.getWidth(),target.getHeight()),Layout.Scope.PANE);
    }

    public void undoArrangement() {
        if (KioskMode.isEnabled() || arrangementHistory.isEmpty()) return;
        Layout previous=arrangementHistory.pop();
        Map<UUID,UUID> currentProfiles=new HashMap<>();
        currentLayout().leaves().forEach(leaf -> currentProfiles.put(leaf.pane(),leaf.profile()));
        for (Layout.Leaf leaf:previous.leaves())
            previous=previous.mapLeaf(leaf.pane(),old -> new Layout.Leaf(old.pane(),currentProfiles.get(old.pane())));
        layout=previous; clearPreviews(); buildTree(); changed();
    }

    public void clearPreviews() {
        dropPlan=null;
        lastPreviewKey=null;
        lastPreviewValid=false;
        dropOverlay.setVisible(false);
    }

    public void closePane() {
        if (active!=null) closePane(active);
    }

    public void closePane(SessionPane pane) {
        if (KioskMode.isEnabled()) return;
        if (panes.size()==1) {
            pane.disconnect();
            activate(pane);
        } else {
            List<SessionPane> ordered=new ArrayList<>(sessionPanes());
            int index=ordered.indexOf(pane);
            ordered.remove(pane);
            if (active==pane) active=ordered.get(Math.min(Math.max(0,index),ordered.size()-1));
            layout=Objects.requireNonNull(currentLayout().remove(pane.id()));
            arrangementHistory.clear(); clearPreviews();
            buildTree();
        }
        active.focusTerminal();
        changed();
        tutorialAction("Close pane");
    }

    public void cycle(int delta) {
        List<SessionPane> ordered=sessionPanes();
        if (ordered.isEmpty()) return;
        int index=Math.floorMod(ordered.indexOf(active)+delta,ordered.size());
        activate(ordered.get(index));
        active.focusTerminal();
    }

    private record FocusStop(SessionPane pane,Node node) {}

    public boolean traversePaneFocus(Node source,int delta) {
        List<FocusStop> stops=new ArrayList<>();
        for (SessionPane pane:sessionPanes()) {
            for (Node node:pane.focusStops()) stops.add(new FocusStop(pane,node));
        }
        if (stops.isEmpty()) return false;
        Node current=source!=null ? source : (scene!=null ? scene.getFocusOwner() : null);
        int index=findFocusStopIndex(stops,current);
        if (index<0 && scene!=null && scene.getFocusOwner()!=current) {
            index=findFocusStopIndex(stops,scene.getFocusOwner());
        }
        if (index<0) {
            SessionPane enclosing=null;
            for (Node cur=current;cur!=null;cur=cur.getParent()) {
                if (cur instanceof SessionPane sp) { enclosing=sp; break; }
            }
            SessionPane anchor=enclosing!=null ? enclosing : active;
            if (anchor!=null) {
                for (int i=0;i<stops.size();i++) {
                    if (stops.get(i).pane()==anchor) { index=i; break; }
                }
            }
        }
        int nextIndex=index<0 ? (delta>=0 ? 0 : stops.size()-1) : Math.floorMod(index+delta,stops.size());
        FocusStop target=stops.get(nextIndex);
        activate(target.pane());
        target.node().requestFocus();
        return true;
    }

    private static int findFocusStopIndex(List<FocusStop> stops,Node candidate) {
        if (candidate==null) return -1;
        for (int i=0;i<stops.size();i++) {
            Node stopNode=stops.get(i).node();
            for (Node cur=candidate;cur!=null;cur=cur.getParent()) {
                if (cur==stopNode) return i;
            }
        }
        return -1;
    }

    private void dockingDialog() {
        if (panes.size()==1) return;
        SessionPane source=active;
        List<SessionPane> targets=sessionPanes().stream().filter(p -> p!=source).toList();
        ComboBox<String> target=new ComboBox<>(FXCollections.observableArrayList(
                targets.stream().map(p -> paneName(p.id())+" - "+p.profile().name()).toList()));
        target.getSelectionModel().selectFirst();
        target.setMaxWidth(Double.MAX_VALUE);
        HBox form=new HBox(12,new Label("Target pane"),target);
        form.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(target,Priority.ALWAYS);
        if (Ui.form(stage,"Swap panes",form,"Swap"))
            dock(source.id(),targets.get(target.getSelectionModel().getSelectedIndex()).id(),Layout.Dock.CENTER);
    }

    private void arrangementDialog() {
        if (panes.size()<2) return;
        SessionPane source=active;
        List<SessionPane> others=sessionPanes().stream().filter(p -> p!=source).toList();
        ComboBox<String> target=new ComboBox<>(FXCollections.observableArrayList(
                others.stream().map(p -> paneName(p.id())+" - "+p.profile().name()).toList()));
        target.getSelectionModel().selectFirst();
        ComboBox<Layout.Dock> direction=new ComboBox<>(FXCollections.observableArrayList(Layout.Dock.values()));
        direction.setValue(Layout.Dock.RIGHT);
        ComboBox<String> scope=new ComboBox<>(FXCollections.observableArrayList("Pane / quadrant"));
        if (panes.size()<=4) scope.getItems().add("Whole window");
        scope.getSelectionModel().selectFirst();
        for (ComboBox<?> cb:List.of(target,direction,scope)) cb.setMaxWidth(Double.MAX_VALUE);

        Label hint=new Label("Centers swap. Edges keep simple balanced layouts.");
        Ui.role(hint,"muted");
        GridPane form=new GridPane();
        form.setHgap(10); form.setVgap(10);
        ColumnConstraints c0=new ColumnConstraints(100);
        ColumnConstraints c1=new ColumnConstraints(); c1.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(c0,c1);
        form.add(new Label("Target area"),0,0); form.add(scope,1,0);
        form.add(new Label("Beside pane"),0,1); form.add(target,1,1);
        form.add(new Label("Placement"),0,2); form.add(direction,1,2);
        form.add(hint,0,3,2,1);

        Runnable preview=() -> {
            Layout.Scope area=scope.getSelectionModel().getSelectedIndex()==0 ? Layout.Scope.PANE : Layout.Scope.WINDOW;
            boolean valid=previewDock(source.id(),others.get(target.getSelectionModel().getSelectedIndex()).id(),direction.getValue(),area);
            hint.setText(valid ? "Highlighted result; Arrange confirms." : "No simple placement here, or not enough room. No panes will move.");
        };
        target.setOnAction(e -> preview.run());
        direction.setOnAction(e -> preview.run());
        scope.setOnAction(e -> {
            target.setDisable(scope.getSelectionModel().getSelectedIndex()!=0);
            if (scope.getSelectionModel().getSelectedIndex()==1 && direction.getValue()==Layout.Dock.CENTER)
                direction.setValue(Layout.Dock.BOTTOM);
            preview.run();
        });
        preview.run();
        boolean accepted=Ui.form(stage,"Arrange pane",form,"Arrange");
        Layout.Dock edge=direction.getValue();
        Layout.Scope area=scope.getSelectionModel().getSelectedIndex()==0 ? Layout.Scope.PANE : Layout.Scope.WINDOW;
        boolean valid=planDock(source.id(),others.get(target.getSelectionModel().getSelectedIndex()).id(),edge,area)!=null;
        clearPreviews();
        if (accepted && valid) dock(source.id(),others.get(target.getSelectionModel().getSelectedIndex()).id(),edge,area);
        else if (accepted) source.problem("No simple placement here, or not enough room. No panes moved.");
    }

    public void profiles() {
        if (KioskMode.isEnabled()) return;
        new ProfilesDialog(this,active==null ? profileList().getFirst() : active.profile()).setVisible(true);
    }

    public void moveProfile(UUID id,int direction) {
        List<Profile> order=ProfileManagement.move(profileList(),id,direction);
        profiles.clear(); order.forEach(p -> profiles.put(p.id(),p));
        refreshAddProfiles(); panes.values().forEach(SessionPane::refreshPresets); changed();
        if (preferences!=null) { preferences.syncProfiles(); preferences.sharedChanged(); }
    }

    public void validateDeleteProfile(UUID id) {
        if (exportingShortcut() || application!=null && application.shortcutWriter()!=null)
            throw new IllegalArgumentException("Wait for the desktop shortcut to finish before deleting a profile.");
        ProfileManagement.delete(state(),id);
        if (allPanes().stream().anyMatch(p -> p.profile().id().equals(id) && p.connecting()))
            throw new IllegalArgumentException("This profile has a pending login operation. Cancel it or wait for it to finish before deleting.");
    }

    public void deleteProfile(UUID id) {
        validateDeleteProfile(id);
        StateStore.State next=ProfileManagement.delete(state(),id);
        for (SessionPane pane:panes.values()) if (pane.profile().id().equals(id)) pane.disconnectForProfileChange();
        profiles.clear(); next.profiles().forEach(p -> profiles.put(p.id(),p));
        lastProfile=next.lastProfile(); layout=next.layout();
        addProfile=next.addProfile(); refreshAddProfiles();
        Map<UUID,UUID> references=new HashMap<>();
        layout.leaves().forEach(leaf -> references.put(leaf.pane(),leaf.profile()));
        for (SessionPane pane:panes.values()) pane.updateProfile(profiles.get(references.get(pane.id())));
        refreshShortcutLabels(); changed();
        if (preferences!=null) preferences.syncProfiles();
    }

    private static boolean connectionChanged(Profile a,Profile b) {
        return !a.host().equals(b.host()) || a.port()!=b.port() || !a.device().equals(b.device())
                || !a.username().equals(b.username()) || a.automaticSignOn()!=b.automaticSignOn() || !a.codePage().equals(b.codePage())
                || !Objects.equals(a.loginSource(),b.loginSource()) || a.authentication()!=b.authentication()
                || !a.kerberosServer().equals(b.kerberosServer());
    }

    public void putProfile(Profile value) {
        Profile existing=profiles.get(value.id());
        if (value.equals(existing)) return;
        if (existing==null || !existing.keymap().equals(value.keymap()) || !existing.codePage().equals(value.codePage())
                || connectionChanged(existing,value) || existing.rememberPassword()!=value.rememberPassword())
            validateProfile(value);
        Profile old=profiles.put(value.id(),value);
        refreshAddProfiles();
        if (existing==null || !existing.keymap().equals(value.keymap()))
            shortcuts.apply(shortcuts.overrides(),profileList());
        for (SessionPane pane:panes.values()) {
            if (pane.profile().id().equals(value.id())) {
                if (old!=null && connectionChanged(old,value)) pane.disconnectForProfileChange();
                pane.updateProfile(value);
            }
            pane.refreshPresets();
        }
        refreshShortcutLabels();
        changed();
        if (preferences!=null) preferences.syncProfiles();
    }

    public void useProfile(Profile value) {
        if (active==null) {
            layout=new Layout.Leaf(UUID.randomUUID(),value.id()); buildTree();
        }
        if (!active.profile().id().equals(value.id())) {
            active.disconnect();
            active.updateProfile(value);
            layout=currentLayout();
            changed();
        }
        if (!active.busy()) active.connect();
        refreshShortcutLabels();
        rememberProfile(value);
        active.focusTerminal();
    }

    private void quickSwitch() {
        ComboBox<Profile> choices=new ComboBox<>(FXCollections.observableArrayList(profileList()));
        choices.setValue(active.profile());
        choices.setMaxWidth(Double.MAX_VALUE);
        if (Ui.form(stage,"Switch workstation",choices,"Switch") && choices.getValue()!=null)
            useProfile(choices.getValue());
    }

    private void restoreProfileLayout() {
        String saved=active.profile().savedLayout();
        if (saved.isEmpty()) { active.problem("This profile has no legacy layout."); return; }
        try {
            Layout restored=Layout.normalize(Layout.decode(saved));
            for (Layout.Leaf leaf:restored.leaves()) {
                if (!profiles.containsKey(leaf.profile())) throw new IllegalArgumentException("Saved profile no longer exists.");
            }
            for (Layout.Leaf leaf:restored.leaves()) {
                SessionPane existing=panes.get(leaf.pane());
                if (existing!=null && !existing.profile().id().equals(leaf.profile())) {
                    existing.disconnectForProfileChange(); existing.updateProfile(profiles.get(leaf.profile()));
                }
            }
            layout=restored;
            buildTree();
            sessionPanes().forEach(SessionPane::connectOnStartup);
            changed();
        } catch (IllegalArgumentException e) { Ui.error(stage,"Could not restore layout: "+e.getMessage()); }
    }

    public void zoom(double delta) {
        zoom(active,delta);
    }

    public void zoom(SessionPane target,double delta) {
        if (KioskMode.isEnabled() || target==null) return;
        target.zoomBy(delta,true);
        changed();
    }

    public void fit() {
        fit(active);
    }

    public void fit(SessionPane target) {
        if (KioskMode.isEnabled() || target==null) return;
        target.zoomReset(true);
        changed();
    }

    public void toggleFlux() {
        if (KioskMode.isEnabled() || closing || settingsSaving() || active==null) return;
        Profile profile=active.profile();
        putProfile(profile.withDisplay(profile.theme(),profile.effect().equals(Profile.FLUX) ? "None" : Profile.FLUX));
    }

    public static boolean isAllowedInKiosk(String title,KeyMnemonic hostKey) {
        if (hostKey!=null) return true;
        if (title==null) return false;
        if (title.startsWith("Focus pane ")) return true;
        return List.of(
                "Copy selection / screen",
                "Paste into fields",
                "Disable Kiosk Mode...",
                "Insert profile username",
                "Insert remembered password",
                "Send host key...",
                "Next pane",
                "Previous pane",
                "Open menus",
                "Window menu",
                "Maximize / restore window",
                "Minimize window"
        ).contains(title);
    }

    public void disableKioskModePrompt() {
        if (!KioskMode.isEnabled()) return;
        if (Boolean.getBoolean("fish.testMode")) {
            try {
                KioskMode.setEnabledWithAdmin(false);
            } catch (java.io.IOException e) {
                Ui.error(stage,"Could not disable Kiosk mode: "+e.getMessage());
            }
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                KioskMode.setEnabledWithAdmin(false);
                return "";
            } catch (java.io.IOException e) {
                return e.getMessage()==null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }).thenAccept(err -> Ui.runLater(() -> {
            if (!err.isEmpty()) Ui.error(stage,"Could not disable Kiosk mode: "+err);
        }));
    }

    private void syncKioskMode() {
        if (closing) return;
        boolean kiosk=KioskMode.isEnabled();
        if (kiosk) {
            clearPreviews();
            saveTimer.stop();
            if (tutorial!=null) tutorial.dispose();
            if (preferences!=null && !preferences.ended()) preferences.forceDispose();
            if (profileEditor!=null && profileEditor.getScene()!=null
                    && profileEditor.getScene().getWindow() instanceof Stage editorStage) editorStage.close();
            editMenu.getItems().setAll(kioskEditItems);
            menuBar.getMenus().setAll(editMenu,insertsMenu);
            if (toolsBox!=null) {
                toolsBox.setVisible(false);
                toolsBox.setManaged(false);
            }
        } else {
            editMenu.getItems().setAll(normalEditItems);
            menuBar.getMenus().setAll(sessionMenu,editMenu,insertsMenu,panesMenu,viewMenu,hostMenu,helpMenu);
            if (toolsBox!=null) {
                toolsBox.setVisible(true);
                toolsBox.setManaged(true);
            }
        }
        if (chrome!=null) chrome.syncKioskState();
        refreshSplitStyles(tree);
        panes.values().forEach(SessionPane::updateHeader);
        refreshCommands();
        constrainWindow();
        shell.requestLayout();
    }

    private void hostKeys() {
        ComboBox<KeyMnemonic> keys=new ComboBox<>(FXCollections.observableArrayList(Keymap.hostActions()));
        keys.setValue(KeyMnemonic.ENTER);
        keys.setMaxWidth(Double.MAX_VALUE);
        if (Ui.form(stage,"Send host key",keys,"Send") && keys.getValue()!=null)
            active.send(keys.getValue());
        active.focusTerminal();
    }

    public void appearance() { preferences("Appearance"); }

    private MenuBar buildMenus() {
        MenuBar bar=new MenuBar();
        Menu session=menu(bar,"Session");
        this.sessionMenu=session;
        action(session,"Connect / disconnect",() -> active.connect());
        action(session,"Reconnect",() -> active.reconnect());
        action(session,"New window",() -> {
            if (application==null) active.problem("New window is available when Fish is launched normally.");
            else if (!application.newWindow(this)) active.problem("Finish the pending save before opening a window.");
        });
        AppCommand startup=new AppCommand("Connect panes on startup","Reconnect restored panes using saved logins; missing passwords wait for Connect",null,
                () -> {
                    connectOnStartup=!connectOnStartup;
                    command("Connect panes on startup").setSelected(connectOnStartup);
                    changed();
                },() -> !closing && !KioskMode.isEnabled(),"automatic reconnect launch");
        startup.setSelected(connectOnStartup);
        commands.add(startup);
        session.getItems().add(new SeparatorMenuItem());
        action(session,"Profiles...",this::profiles);
        action(session,"Switch profile...",this::quickSwitch);
        session.getItems().add(new SeparatorMenuItem());
        action(session,"Quit",() -> { if (application==null) close(false); else application.quit(); });

        Menu edit=menu(bar,"Edit");
        this.editMenu=edit;
        AppCommand copyCmd=action(edit,"Copy selection / screen",() -> active.view().copy());
        AppCommand pasteCmd=action(edit,"Paste into fields",() -> active.view().paste());
        edit.getItems().add(new SeparatorMenuItem());
        action(edit,Shortcuts.SETTINGS,() -> preferences("Appearance"));
        normalEditItems=List.copyOf(edit.getItems());
        AppCommand disableKiosk=new AppCommand("Disable Kiosk Mode...","Edit / Disable Kiosk Mode... (requires Administrator)",null,
                this::disableKioskModePrompt,() -> !closing && KioskMode.isEnabled(),"kiosk unlock supervisor administrator");
        commands.add(disableKiosk);
        kioskEditItems=List.of(
                copyCmd.createMenuItem("Copy"),
                pasteCmd.createMenuItem("Paste"),
                new SeparatorMenuItem(),
                disableKiosk.createMenuItem("Disable Kiosk Mode..."));
        for (String alias:Shortcuts.SETTINGS_ALIASES.stream().sorted().toList()) {
            Runnable handler=switch (alias) {
                case "Check for updates..." -> () -> updates.manual();
                default -> () -> preferences("Appearance");
            };
            action(null,alias,handler);
        }

        Menu inserts=menu(bar,"Inserts");
        this.insertsMenu=inserts;
        action(inserts,"Insert profile username",() -> active.paste(connectionProfile(active.profile()).username()));
        action(inserts,"Insert remembered password",() -> active.sendPassword());
        inserts.getItems().add(new SeparatorMenuItem());
        for (KeyMnemonic key:List.of(KeyMnemonic.ENTER,KeyMnemonic.FIELD_EXIT,KeyMnemonic.FIELD_PLUS,KeyMnemonic.FIELD_MINUS,
                KeyMnemonic.CLEAR,KeyMnemonic.HELP,KeyMnemonic.PAGE_UP,KeyMnemonic.PAGE_DOWN))
            action(inserts,key.name().replace('_',' '),() -> active.send(key)).hostKey=key;
        inserts.getItems().add(new SeparatorMenuItem());
        action(inserts,"Send host key...",this::hostKeys);

        Menu layoutMenu=menu(bar,"Panes");
        this.panesMenu=layoutMenu;
        action(layoutMenu,"Add pane",() -> split(true));
        action(layoutMenu,"Swap panes...",this::dockingDialog);
        action(layoutMenu,"Arrange pane...",this::arrangementDialog);
        action(layoutMenu,"Undo pane arrangement",this::undoArrangement);
        action(layoutMenu,"Close pane",this::closePane);
        layoutMenu.getItems().add(new SeparatorMenuItem());
        action(layoutMenu,"Next pane",() -> cycle(1));
        action(layoutMenu,"Previous pane",() -> cycle(-1));
        // Keep persisted command IDs and custom chords without exposing a layout library.
        action(null,"Save layout to profile",() -> preferences("General"));
        action(null,"Restore profile layout",this::restoreProfileLayout);
        action(null,"Saved layouts / shortcuts...",() -> preferences("General"));

        Menu view=menu(bar,"View");
        this.viewMenu=view;
        action(view,"Fit host screen",this::fit);
        action(view,"Zoom in",() -> zoom(.1));
        action(view,"Zoom out",() -> zoom(-.1));
        for (AppTheme t:AppTheme.values()) {
            AppCommand change=new AppCommand("Theme: "+t,"Application theme; does not reconnect sessions",null,
                    () -> applyTheme(t),() -> !closing && !settingsSaving() && !KioskMode.isEnabled(),"appearance color scheme "+t.name());
            commands.add(change);
        }
        AppCommand flux=new AppCommand("Flux animated text","Flowing multicolor text; opt-in, host attributes preserved",null,
                () -> putProfile(active.profile().withDisplay(active.profile().theme(),Profile.FLUX)),
                () -> active!=null && !closing && !KioskMode.isEnabled(),"theme gradient rainbow animated flowing effect");
        commands.add(flux);

        Menu host=menu(bar,"Host");
        this.hostMenu=host;
        for (KeyMnemonic key:List.of(KeyMnemonic.RESET,KeyMnemonic.ATTN,KeyMnemonic.SYSREQ)) {
            action(host,key.name(),() -> active.send(key)).hostKey=key;
        }
        host.getItems().add(new SeparatorMenuItem());
        action(host,"Last pane notice...",() -> active.showLastNotice());

        Menu help=menu(bar,"Help");
        this.helpMenu=help;
        Menu tutorials=new Menu("Tutorials");
        help.getItems().add(tutorials);
        String[] tutorialActions={"Getting started tutorial","Tutorial: Appearance","Tutorial: Non-Kerberos profile","Tutorial: Keyboard shortcuts",
                "Tutorial: Pane tips","Tutorial: Desktop shortcuts"};
        TutorialDialog.Guide[] guides=TutorialDialog.Guide.values();
        for (int n=0;n<guides.length;n++) {
            TutorialDialog.Guide guide=guides[n];
            AppCommand cmd=action(null,tutorialActions[n],() -> openTutorial(guide,false));
            tutorials.getItems().add(cmd.createMenuItem(guide.title));
        }
        AppCommand guideCommand=action(null,"Full manual / Getting Started",() -> {
            try { new ManualDialog(this).setVisible(true); }
            catch (java.io.IOException e) { Ui.error(stage,"The manual could not be opened: "+e.getMessage()); }
        });
        help.getItems().addFirst(guideCommand.createMenuItem("Quick guide"));
        action(help,"Keyboard reference",() -> preferences("Keyboard Shortcuts"));

        for (int i=1;i<=24;i++) {
            KeyMnemonic key=KeyMnemonic.valueOf("PF"+i);
            AppCommand function=new AppCommand("Host F"+i,"Send function key to the focused session",i<=12 ? "F"+i : "shift F"+(i-12),
                    () -> active.send(key),() -> active!=null && active.connected() && !active.screen().getOIA().isKeyBoardLocked(),
                    "PF"+i+" function key");
            function.hostKey=key; commands.add(function);
        }
        for (KeyMnemonic key:Keymap.hostActions()) {
            if (commands.stream().anyMatch(c -> c.hostKey==key)) continue;
            AppCommand hostAction=new AppCommand("Host "+key.name().replace('_',' '),"Host keyboard / "+key,null,
                    () -> active.send(key),() -> active!=null && active.connected() && !active.screen().getOIA().isKeyBoardLocked(),
                    "host keyboard "+key.name());
            hostAction.hostKey=key; commands.add(hostAction);
        }
        commands.add(new AppCommand("Open menus","Application menus","ctrl alt M",() -> chrome.openMenus(),() -> !closing,"menu toolbar"));
        commands.add(new AppCommand("Window menu","Window move, size and close","ctrl alt SPACE",() -> chrome.showSystemMenu(),
                () -> !closing,"window move resize"));
        commands.add(new AppCommand("Maximize / restore window","Window size",null,() -> chrome.toggleMaximize(),() -> !closing,"maximize restore"));
        commands.add(new AppCommand("Minimize window","Window size",null,() -> stage.setIconified(true),
                () -> !closing,"minimize"));
        for (int i=1;i<=8;i++) {
            final int index=i-1;
            commands.add(new AppCommand("Focus pane "+i,"Focus pane by its layout position","ctrl alt "+i,() -> {
                if (index<sessionPanes().size()) { activate(sessionPanes().get(index)); active.focusTerminal(); }
            },() -> !closing && index<panes.size(),"select focus pane window tab "+i));
        }
        return bar;
    }

    private Menu menu(MenuBar bar,String name) {
        Menu m=new Menu(name);
        m.setMnemonicParsing(false);
        bar.getMenus().add(m);
        return m;
    }

    private AppCommand action(Menu menu,String text,Runnable command) {
        String aliases=switch (text) {
            case "Add pane" -> "add window new session split side by side";
            case "Swap panes..." -> "move rearrange dock";
            case "Close pane" -> "disconnect remove pane reset offline";
            case "Profiles..." -> "settings workstation host device credentials";
            case "Appearance..." -> "settings theme palette color effects glow prism flux animated gradient";
            case "Copy selection / screen" -> "clipboard copy export selected text";
            case "Paste into fields" -> "clipboard paste insert text";
            case "RESET" -> "unlock recover input error hung stuck";
            case "Reconnect" -> "retry connect restart session";
            default -> "";
        };
        AppCommand action=new AppCommand(text,(menu==null ? "Fish" : menu.getText())+" / "+text,Shortcuts.defaultStroke(text),command,() -> {
            if (closing || settingsSaving()) return false;
            if (KioskMode.isEnabled() && !isAllowedInKiosk(text,null)
                    && !(menu!=null && (menu.getText().equals("Host") || menu.getText().equals("Inserts")) && !text.equals("Last pane notice...")))
                return false;
            if (text.equals("Add pane")) return panes.size()<Layout.MAX_PANES;
            if (List.of("Profiles...","New window","Saved layouts / shortcuts...","Quit","Appearance...","Theme designer...","Fonts...","Pane information...","Window and focus...","Keyboard reference",
                    "Shortcuts...","Full manual / Getting Started",
                    "Getting started tutorial","Application settings...","Check for updates...","Last pane notice...",Shortcuts.SETTINGS).contains(text)
                    || text.startsWith("Tutorial:")) return true;
            if (active==null) return false;
            if (text.startsWith("Insert ") && connectionProfile(active.profile()).authentication()==Profile.Authentication.KERBEROS)
                return false;
            if (text.equals("Insert profile username") && connectionProfile(active.profile()).username().isEmpty()) return false;
            if (text.equals("Insert remembered password") && (!connectionProfile(active.profile()).rememberPassword()
                    || connectionProfile(active.profile()).username().isEmpty())) return false;
            if (text.equals("Copy selection / screen")) return active.screen()!=null;
            if (List.of("Swap panes...","Next pane","Previous pane").contains(text)) return panes.size()>1;
            if (text.equals("Restore profile layout")) return !active.profile().savedLayout().isEmpty();
            if (text.equals("Arrange pane...") || text.equals("Swap panes...")) return panes.size()>1;
            if (text.equals("Undo pane arrangement")) return !arrangementHistory.isEmpty();
            if (menu!=null && (menu.getText().equals("Host") || menu.getText().equals("Inserts")) || text.equals("RESET")
                    || text.startsWith("Insert ") || text.startsWith("Paste")) {
                return active.connected() && (!active.screen().getOIA().isKeyBoardLocked()
                        || List.of("RESET","ATTN","SYSREQ").contains(text) || text.equals("ENTER") && active.recoverableInputError());
            }
            return true;
        },aliases);
        commands.add(action);
        if (menu!=null) {
            String displayText=switch (text) {
                case "Copy selection / screen" -> "Copy";
                case "Paste into fields" -> "Paste";
                case "Insert profile username" -> "Profile username";
                case "Insert remembered password" -> "Saved password";
                case "RESET" -> "Reset";
                case "ATTN" -> "Attention";
                case "SYSREQ" -> "System Request";
                case "ENTER" -> "Enter";
                case "FIELD EXIT" -> "Field Exit";
                case "FIELD PLUS" -> "Field +";
                case "FIELD MINUS" -> "Field -";
                case "CLEAR" -> "Clear";
                case "HELP" -> "Help";
                case "PAGE UP" -> "Roll up";
                case "PAGE DOWN" -> "Roll down";
                default -> text;
            };
            menu.getItems().add(action.createMenuItem(displayText));
        }
        return action;
    }

    void hidePopup() {
        if (openPopup!=null) openPopup.hide();
    }

    boolean popupAt(Node owner) {
        // A single native gesture can deliver both secondary release and a context-menu request.
        // A fresh press already retired the old popup, including presses on the same anchor.
        return openPopup!=null && openPopup.isShowing() && openPopup.getOwnerNode()==owner;
    }

    void showPopup(ContextMenu popup,Node owner,double screenX,double screenY) {
        hidePopup();
        menuBar.getMenus().forEach(Menu::hide);
        // Dismiss AND deliver the original click to the next pane/control.
        popup.setConsumeAutoHidingEvents(false);
        popup.setOnHidden(e -> { if (openPopup==popup) openPopup=null; });
        openPopup=popup;
        popup.show(owner,screenX,screenY);
    }

    public ContextMenu paneMenu(SessionPane pane) {
        activate(pane);
        ContextMenu menu=new ContextMenu();
        for (String name:List.of("Connect / disconnect","Reconnect","Add pane",
                "Arrange pane...","Swap panes...","Undo pane arrangement","Close pane","Profiles...")) {
            menu.getItems().add(command(name).createMenuItem(name));
        }
        return menu;
    }

    public ContextMenu clipboardMenu(SessionPane pane) {
        activate(pane);
        pane.focusTerminal();
        ContextMenu menu=new ContextMenu();
        menu.getItems().addAll(
                command("Copy selection / screen").createMenuItem("Copy"),
                command("Paste into fields").createMenuItem("Paste"),
                new SeparatorMenuItem(),
                command("RESET").createMenuItem("Reset"));
        if (!KioskMode.isEnabled()) {
            for (String act:List.of("Connect / disconnect","Add pane","Arrange pane...","Swap panes...","Close pane"))
                menu.getItems().add(command(act).createMenuItem(act));
        }
        return menu;
    }

    public void close(boolean force) {
        if (application!=null) { application.closeWindow(this); return; }
        if (closing) return;
        if (!force && settingsSaving()) { Ui.error(stage,"Wait for the profile save to finish before closing Fish."); return; }
        if (!force && !closeSettings()) return;
        saveTimer.stop();
        boolean skipSave=KioskMode.isEnabled();
        StateStore.State snapshot=skipSave ? null : state();
        closing=true;
        disposeWorkspace();
        persistence.submit(() -> {
            try {
                if (!skipSave && snapshot!=null) store.save(snapshot);
            } catch (java.io.IOException e) {
                System.err.println("Fish closed; workspace could not be saved: "+e.getMessage());
            } finally {
                try { store.close(); }
                catch (java.io.IOException e) { System.err.println("Fish workspace lock could not be released: "+e.getMessage()); }
                persistence.shutdown();
                Ui.runLater(afterClose);
            }
        });
    }

    private void disposeWorkspace() {
        hidePopup();
        Ui.removeThemeListener(themeListener);
        KioskMode.removeListener(kioskListener);
        updates.close();
        if (tutorial!=null) tutorial.dispose();
        if (preferences!=null && !preferences.ended()) preferences.forceDispose();
        shortcuts.dispose();
        panes.values().forEach(SessionPane::dispose);
        stage.close();
    }

    void pauseSaving() { saveTimer.stop(); }
    boolean closeSettings() {
        if (profileEditor!=null) {
            profileEditor.dismiss();
            if (profileEditor!=null) return false;
        }
        if (preferences==null) return true;
        Preferences current=preferences;
        current.closeSettings();
        return current.ended();
    }
    public void finishWindow() { closing=true; saveTimer.stop(); disposeWorkspace(); persistence.shutdown(); afterClose.run(); }

    void receiveShared(StateStore.State value) {
        List<Profile> incoming=value.profiles();
        UUID replacement=incoming.getFirst().id();
        Set<UUID> ids=new HashSet<>(); incoming.forEach(p -> ids.add(p.id()));
        for (SessionPane pane:panes.values()) {
            Profile next=incoming.stream().filter(p -> p.id().equals(pane.profile().id())).findFirst().orElse(incoming.getFirst());
            if (!next.id().equals(pane.profile().id()) || connectionChanged(pane.profile(),next)) pane.disconnectForProfileChange();
        }
        profiles.clear(); incoming.forEach(p -> profiles.put(p.id(),p));
        savedLayouts=value.savedLayouts(); selectedLayout=value.selectedLayout(); refreshAddProfiles();
        for (Layout.Leaf leaf:layout.leaves()) if (!ids.contains(leaf.profile())) layout=layout.replaceProfile(leaf.profile(),replacement);
        if (!ids.contains(lastProfile)) lastProfile=replacement;
        Appearance previous=appearance;
        appearance=value.appearance(); highlightActive=value.highlightActive();
        connectOnStartup=value.connectOnStartup(); updateSettings=value.updates(); tutorialStatus=value.tutorial(); keyboardRecovery=value.keyboardRecovery();
        shortcuts.apply(value.shortcuts(),profileList());
        if (!previous.font().equals(appearance.font())) resolveFont();
        for (SessionPane pane:panes.values()) {
            Profile next=profiles.getOrDefault(pane.profile().id(),incoming.getFirst());
            pane.updateProfile(next); pane.refreshKeys();
            if (!previous.font().equals(appearance.font())) pane.view().fontChanged();
        }
        updateWindowOutline(); refreshShortcutLabels(); refreshCommands();
        if (preferences!=null) { preferences.syncProfiles(); preferences.sharedChanged(); }
    }

    public List<SavedLayout> savedLayouts() { return savedLayouts; }
    SavedLayout captureShortcut(String name,boolean autostart) {
        StateStore.State current=state();
        return new SavedLayout(UUID.randomUUID(),name,
                new StateStore.WindowState(current.layout(),current.width(),current.height(),current.lastProfile(),current.addProfile()),
                current.displays(),autostart);
    }

    boolean exportingShortcut() { return shortcutWrite!=null; }

    private void finishShortcutWrite() {
        var finished=shortcutWrite;
        shortcutWrite=null; refreshActions(); finished.complete(null);
    }

    void exportDesktopShortcut(String name,boolean autostart,java.util.function.Consumer<Path> done,java.util.function.Consumer<String> failed) {
        if (KioskMode.isEnabled()) { failed.accept("Shortcuts cannot be changed in Kiosk mode."); return; }
        if (demonstration) { failed.accept("Shortcuts require a normal saved workspace, not a temporary Demo host."); return; }
        if (closing() || settingsSaving()) { failed.accept("Wait for the pending save to finish."); return; }
        final SavedLayout value;
        final StateStore.State snapshot;
        try {
            value=captureShortcut(name,autostart);
            if (savedLayouts.stream().anyMatch(s -> s.name().equalsIgnoreCase(value.name())))
                throw new IllegalArgumentException("A shortcut already uses that name. Enter another name.");
            List<SavedLayout> next=new ArrayList<>(savedLayouts); next.add(value);
            snapshot=(application==null ? state() : application.snapshot()).withSavedLayouts(next,value.id());
        } catch (IllegalArgumentException e) { failed.accept(e.getMessage()); return; }
        shortcutWrite=new CompletableFuture<>(); refreshActions();
        java.util.function.Consumer<ApplicationWindows.Writer> export=writer -> {
            try {
                Path file=fish.LayoutShortcuts.export(dataDirectory(),value,() -> {
                    if (KioskMode.isEnabled()) throw new java.io.IOException("Shortcuts cannot be changed in Kiosk mode.");
                    writer.save(snapshot);
                });
                Ui.runLater(() -> {
                    savedLayouts=snapshot.savedLayouts(); selectedLayout=value.id();
                    changed(); finishShortcutWrite(); done.accept(file);
                });
            } catch (java.io.IOException | RuntimeException e) {
                Ui.runLater(() -> { finishShortcutWrite(); failed.accept(e.getMessage()); });
            }
        };
        if (application!=null) application.submitWrite(export);
        else persistence.submit(() -> export.accept(store::save));
    }

    public boolean updateAndExit(Maintenance launch,java.util.function.Consumer<String> failed) {
        if (application!=null) return application.maintenance(this,launch,false,failed);
        StateStore.State snapshot=state();
        return maintenance(() -> { store.save(snapshot); launch.run(); },failed);
    }

    public void factoryDefaults() {
        if (!Ui.confirm(stage,"Close all sessions and erase this workspace's profiles, shortcuts and settings? This can't be undone. Saved Windows passwords are kept.","Factory defaults")) return;
        if (application!=null) {
            if (!application.maintenance(this,() -> {},true,message -> Ui.error(stage,"Factory defaults could not be saved: "+message)))
                Ui.error(stage,"Finish pending profile saves/login operations in all Fish windows before resetting.");
        } else maintenance(store::factoryReset,message -> Ui.error(stage,"Factory defaults could not be saved: "+message));
    }

    public boolean maintenance(Maintenance action,java.util.function.Consumer<String> failed) {
        if (closing) return false;
        if (panes.values().stream().anyMatch(SessionPane::connecting)) {
            Ui.error(stage,"A login/password operation is still pending. Cancel it or wait for it to finish, then try again.");
            return false;
        }
        saveTimer.stop(); closing=true; setEnabled(false);
        persistence.submit(() -> {
            try { action.run(); }
            catch (java.io.IOException | RuntimeException e) {
                Ui.runLater(() -> {
                    closing=false; setEnabled(true); failed.accept(e.getMessage());
                });
                return;
            }
            Ui.runLater(() -> {
                disposeWorkspace();
                persistence.submit(() -> {
                    try { store.close(); }
                    catch (java.io.IOException e) { System.err.println("Workspace lock release failed: "+e.getMessage()); }
                    finally { persistence.shutdown(); Ui.runLater(afterClose); }
                });
            });
        });
        return true;
    }
}
