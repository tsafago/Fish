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
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import org.tn5250j.keyboard.KeyMnemonic;
import fish.model.*;
import fish.security.CredentialStore;
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
        private boolean hovered;

        WorkspaceSplit(Workspace owner,Layout.Split split,Node first,Node second) {
            this.modelSplit=split;
            this.horizontal=split.horizontal();
            this.threeStrips=Layout.isThreeStrips(split);
            this.userRatio=split.ratio();
            this.first=first;
            this.second=second;
            divider.setCursor(horizontal ? Cursor.H_RESIZE : Cursor.V_RESIZE);
            divider.setOnMouseEntered(e -> { hovered=true; styleDivider(); });
            divider.setOnMouseExited(e -> { hovered=false; styleDivider(); });
            int[] startLoc={0};
            divider.setOnMousePressed(e -> {
                startLoc[0]=getDividerLocation();
            });
            divider.setOnMouseDragged(e -> {
                Point2D local=sceneToLocal(e.getSceneX(),e.getSceneY());
                if (local!=null) {
                    int target=(int)Math.round(horizontal ? local.getX()-2 : local.getY()-2);
                    owner.resizeDivider(this,target);
                }
                e.consume();
            });
            divider.setOnMouseReleased(e -> {
                if (startLoc[0]!=getDividerLocation()) {
                    owner.tutorialAction(horizontal ? "divider-horizontal" : "divider-vertical");
                }
            });
            styleDivider();
            getChildren().addAll(first,second,divider);
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
            String bg=Ui.hex(ColorRole.DIVIDER_BACKGROUND);
            String line=Ui.hex(hovered ? ColorRole.FOCUS : ColorRole.DIVIDER);
            if (horizontal) {
                divider.setStyle("-fx-background-color: "+bg+", "+line+"; -fx-background-insets: 0, 0 1 0 2;");
            } else {
                divider.setStyle("-fx-background-color: "+bg+", "+line+"; -fx-background-insets: 0, 2 0 1 0;");
            }
        }

        @Override protected double computeMinWidth(double height) {
            double a=first.minWidth(-1),b=second.minWidth(-1);
            return horizontal ? a+b+4 : Math.max(a,b);
        }

        @Override protected double computeMinHeight(double width) {
            double a=first.minHeight(-1),b=second.minHeight(-1);
            return horizontal ? Math.max(a,b) : a+b+4;
        }

        @Override protected void layoutChildren() {
            int w=Math.max(0,(int)Math.round(getWidth()));
            int h=Math.max(0,(int)Math.round(getHeight()));
            Rectangle[] areas=partition(modelSplit,new Rectangle(0,0,w,h),userRatio);
            first.resizeRelocate(areas[0].x,areas[0].y,areas[0].width,areas[0].height);
            second.resizeRelocate(areas[1].x,areas[1].y,areas[1].width,areas[1].height);
            int loc=horizontal ? areas[0].width : areas[0].height;
            if (horizontal) divider.resizeRelocate(loc,0,4,h);
            else divider.resizeRelocate(0,loc,w,4);
        }
    }

    private final Stage stage;
    private final Scene scene;
    private final BorderPane shell=new BorderPane();
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
    private Appearance appearance;
    private Font terminalFont;
    private String fontNotice="";
    private final AppShortcuts shortcuts;
    private final WindowChrome chrome;
    private final Button addPane;
    private final ComboBox<Profile> addProfileSelector=new ComboBox<>();
    private boolean refreshingAdd,exportingLayout;
    private SavedLayoutsDialog layoutsDialog;
    private ApplicationWindows application;
    private Preferences preferences;
    private ProfilesEditor profileEditor;
    private final MenuBar menuBar;
    private StateStore.Tutorial tutorialStatus;
    private TutorialDialog tutorial;
    private boolean connectOnStartup,startupHandled;
    private UpdateSettings updateSettings;
    private Map<String,String> keyboardRecovery;
    private final UpdateController updates;
    private Runnable afterClose=() -> {};
    private final Runnable themeListener=this::themeChanged;
    private double logicalWidth,logicalHeight;
    private int settingsBatch;

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
        if (initializeTheme) Ui.applyTheme(state.appTheme(),appearance.custom().orElse(null));

        this.store=store;
        this.demonstration=demonstration;
        this.credentials=new CredentialStore(credentialHelper);
        state.profiles().forEach(p -> profiles.put(p.id(),p));
        resolveFont();
        layout=Layout.normalize(state.layout());
        lastProfile=state.lastProfile();
        highlightActive=state.highlightActive();

        saveTimer=new Timeline(new KeyFrame(Duration.millis(500),e -> saveAsync()));

        Rectangle2D screenBounds=Screen.getPrimary().getVisualBounds();
        logicalWidth=Math.max(330,Math.min(state.width(),screenBounds.getWidth()));
        logicalHeight=Math.max(260,Math.min(state.height(),screenBounds.getHeight()));

        shell.getStyleClass().add("fish-window-shell");
        updateWindowOutline();

        menuBar=buildMenus();

        addProfileSelector.getStyleClass().add("fish-chrome-combo");
        addProfileSelector.setMinSize(68,27);
        addProfileSelector.setPrefSize(160,27);
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
        addPane.getStyleClass().add("fish-chrome-button");
        addPane.setMinSize(30,27);
        addPane.setPrefSize(30,27);
        addPane.disableProperty().bind(addCmd.enabledProperty().not());
        addPane.setAccessibleText("Add and connect selected profile");
        addPane.setTooltip(new Tooltip("Add and connect selected profile (maximum 8)"));
        addPane.setOnAction(e -> addCmd.execute());

        HBox tools=new HBox(2,chromeSeparator(),addProfileSelector,chromeSeparator(),addPane);
        tools.setAlignment(Pos.CENTER_LEFT);

        chrome=new WindowChrome(this,stage,menuBar,tools,this::toggleFlux);
        shell.setTop(chrome);

        dropOverlay.setMouseTransparent(true);
        dropOverlay.setVisible(false);
        dropOverlay.setManaged(false);

        updates=new UpdateController(this,updateService,confirmUpdate);
        TransientNotice updateBanner=updates.noticeBanner();

        centerHost=new Pane() {
            @Override protected void layoutChildren() {
                double w=getWidth(),h=getHeight();
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
            close(false);
        });
        stage.focusedProperty().addListener((o,a,focused) -> {
            if (focused && active!=null) active.view().restartCursor();
        });
        stage.widthProperty().addListener((o,a,w) -> {
            if (w.doubleValue()>0) { logicalWidth=w.doubleValue(); changed(); }
        });
        stage.heightProperty().addListener((o,a,h) -> {
            if (h.doubleValue()>0) { logicalHeight=h.doubleValue(); changed(); }
        });
        Ui.onThemeChanged(themeListener);
        constrainWindow();
        layoutNow();
    }

    private static Separator chromeSeparator() {
        Separator sep=new Separator(Orientation.VERTICAL);
        sep.setPrefSize(10,16);
        sep.setMaxHeight(16);
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
        changed();
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
        if (!demonstration && tutorialStatus==StateStore.Tutorial.NEW) openTutorial(true);
    }

    public void startup() { startup(false); }
    public void startup(boolean explicit) {
        if (startupHandled || demonstration || closing) return;
        startupHandled=true;
        if (connectOnStartup || explicit) sessionPanes().forEach(SessionPane::connectOnStartup);
        startTutorialIfNeeded();
        if (updateSettings.startup() && (application==null || application.checkStartupUpdates(this))) updates.startup();
    }

    public boolean closing() { return closing || application!=null && application.closing(); }
    public UpdateSettings updateSettings() { return updateSettings; }
    public void updateSettings(UpdateSettings value) { updateSettings=Objects.requireNonNull(value); changed(); }
    public Path dataDirectory() { return store.file().getParent(); }

    public void showTutorial() { openTutorial(false); }
    private void openTutorial(boolean firstRun) { openTutorial(TutorialDialog.Guide.GETTING_STARTED,firstRun); }
    public void openTutorial(TutorialDialog.Guide guide,boolean firstRun) {
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
    public SavedLayoutsDialog openLayoutsDialogInstance() { return layoutsDialog; }

    void layoutsDialogClosed(SavedLayoutsDialog dialog) { if (layoutsDialog==dialog) layoutsDialog=null; tutorialChanged(); }

    Node tutorialAddTarget() { return addPane; }
    Node tutorialProfileTarget() { return addProfileSelector; }
    Node tutorialLayoutTarget(String control) {
        return layoutsDialog!=null && layoutsDialog.isOpen() ? layoutsDialog.tutorialTarget(control)
                : tutorialMenuTarget("Panes","Saved layouts / shortcuts...");
    }
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

    public void configurationGuide() {
        try { new ManualDialog(this,"/AI-CONFIG.md","Configuration for external tools").setVisible(true); }
        catch (java.io.IOException e) { Ui.error(stage,"Configuration guide could not be opened: "+e.getMessage()); }
    }

    public void selectProfile(SessionPane pane,Profile profile) {
        if (pane.busy()) throw new IllegalStateException("Disconnect before selecting a preset.");
        lastProfile=profile.id(); pane.updateProfile(profile); refreshShortcutLabels(); changed();
    }
    public void rememberProfile(Profile profile) { lastProfile=profile.id(); changed(); }
    public boolean hasPane(UUID id) { return panes.containsKey(id); }
    public boolean hasMultiplePanes() { return layout instanceof Layout.Split; }
    public boolean highlightActive() { return highlightActive; }
    public Appearance appearanceSettings() { return appearance; }
    public Font terminalFont() { return terminalFont; }

    public void appearanceSettings(Appearance value) {
        Appearance previous=appearance;
        appearance=value;
        boolean font=!previous.font().equals(value.font());
        if (font) resolveFont();
        if (!previous.custom().equals(value.custom())
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
    boolean localSettingsSaving() { return exportingLayout || preferences!=null && preferences.saving() || profileEditor!=null && profileEditor.saving(); }
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
        try { TerminalFonts.validate(terminalFont,candidates); }
        catch (java.io.IOException e) { throw new IllegalArgumentException(e.getMessage(),e); }
    }

    public void highlightActive(boolean enabled) {
        highlightActive=enabled; panes.values().forEach(SessionPane::updateHeader); changed();
    }
    public List<SessionPane> sessionPanes() { return layout.leaves().stream().map(l -> panes.get(l.pane())).toList(); }
    public List<AppCommand> commands() { return List.copyOf(commands); }
    public AppCommand command(String name) { return commands.stream().filter(c -> c.title.equals(name)).findFirst().orElseThrow(); }

    public void refreshCommands() {
        commands.forEach(AppCommand::refresh);
        if (addPane!=null) addPane.setTooltip(new Tooltip(panes.size()>=Layout.MAX_PANES ? "Eight panes open; close one before adding"
                : "Add and connect "+Objects.toString(profiles.get(addProfile),"selected profile")));
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

    private void updateWindowOutline() {
        shell.getStyleClass().remove("fish-window-outlined");
        if (appearance!=null && appearance.windowOutline()) {
            shell.getStyleClass().add("fish-window-outlined");
        }
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

    public void changed() {
        if (closing) return;
        if (settingsBatch>0) return;
        if (application!=null) application.changed(this); else saveTimer.playFromStart();
        if (preferences!=null) preferences.sharedChanged();
        if (layoutsDialog!=null) layoutsDialog.refresh();
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

    public void resizeDivider(WorkspaceSplit split,int targetLocation) {
        if (adjustingDividers || rebuildingLayout || tree==null) return;
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
        } finally {
            adjustingDividers=false;
        }
        changed();
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
        if (application!=null) { application.saveAsync(); return; }
        if (closing) return;
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
        saveTimer.stop();
        if (application!=null) { application.persistTask(() -> {},done,failed); return; }
        StateStore.State snapshot=state();
        persistence.submit(() -> {
            try { store.save(snapshot); Ui.runLater(done); }
            catch (java.io.IOException | RuntimeException e) { Ui.runLater(() -> failed.accept(e.getMessage())); }
        });
    }

    public void split(boolean horizontal) {
        if (closing() || settingsSaving() || panes.size()>=Layout.MAX_PANES) return;
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
        if (plan==null) return;
        arrangementHistory.push(currentLayout());
        while (arrangementHistory.size()>20) arrangementHistory.removeLast();
        layout=plan.layout();
        buildTree();
        active=panes.get(source);
        activate(active);
        Ui.runLater(active::focusTerminal);
        changed();
        tutorialAction(dock==Layout.Dock.CENTER ? "panes-swapped" : "panes-arranged");
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
        int length=Math.max(0,(split.horizontal() ? r.width : r.height)-4);
        int first=Layout.firstPixels(split,(split.horizontal() ? r.width : r.height),ratio);
        Rectangle a=new Rectangle(r),b=new Rectangle(r);
        if (split.horizontal()) { a.width=first; b.x+=first+4; b.width=length-first; }
        else { a.height=first; b.y+=first+4; b.height=length-first; }
        return new Rectangle[]{a,b};
    }

    public DropPlan planDock(UUID source,UUID target,Layout.Dock dock) {
        return planDock(source,target,dock,Layout.Scope.PANE);
    }

    public DropPlan planDock(UUID source,UUID target,Layout.Dock dock,Layout.Scope scope) {
        if (closing() || settingsSaving() || scope==Layout.Scope.PANE && source.equals(target) || !hasPane(source) || !hasPane(target)) return null;
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
        clearPreviews();
        dropPlan=planDock(source,target,dock,scope);
        if (dropPlan==null) return false;
        dropOverlay.setVisible(true);
        centerHost.requestLayout();
        paintDropOverlay();
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
        int left=px,right=cw-1-px,top=py,bottom=ch-1-py;
        int nearest=Math.min(Math.min(left,right),Math.min(top,bottom));
        int band=Math.min(28,Math.max(12,Math.min(cw,ch)/20));
        if (panes.size()<=3 && nearest<band) {
            return new DockTarget(paneId,nearest==top ? Layout.Dock.TOP : nearest==bottom ? Layout.Dock.BOTTOM
                    : nearest==left ? Layout.Dock.LEFT : Layout.Dock.RIGHT,Layout.Scope.WINDOW);
        }
        SessionPane targetPane=panes.get(paneId);
        Point2D paneLocal=targetPane.sceneToLocal(content.localToScene(px,py));
        if (paneLocal==null) return null;
        return new DockTarget(paneId,SessionPane.direction(paneLocal.getX(),paneLocal.getY(),targetPane.getWidth(),targetPane.getHeight()),Layout.Scope.PANE);
    }

    public void undoArrangement() {
        if (arrangementHistory.isEmpty()) return;
        Layout previous=arrangementHistory.pop();
        Map<UUID,UUID> currentProfiles=new HashMap<>();
        currentLayout().leaves().forEach(leaf -> currentProfiles.put(leaf.pane(),leaf.profile()));
        for (Layout.Leaf leaf:previous.leaves())
            previous=previous.mapLeaf(leaf.pane(),old -> new Layout.Leaf(old.pane(),currentProfiles.get(old.pane())));
        layout=previous; clearPreviews(); buildTree(); changed();
    }

    public void clearPreviews() {
        dropPlan=null;
        dropOverlay.setVisible(false);
    }

    public void closePane() {
        if (active!=null) closePane(active);
    }

    public void closePane(SessionPane pane) {
        if (panes.size()==1) {
            pane.disconnect();
            activate(pane);
        } else {
            List<Layout.Leaf> slots=new ArrayList<>(currentLayout().leaves());
            int index=sessionPanes().indexOf(pane);
            slots.removeIf(leaf -> leaf.pane().equals(pane.id()));
            if (active==pane) active=panes.get(slots.get(Math.min(index,slots.size()-1)).pane());
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

    private void adjustDivider(double delta) {
        Node parent=active==null ? null : active.getParent();
        if (parent instanceof WorkspaceSplit split) {
            int before=split.getDividerLocation();
            int length=(int)Math.round(split.isHorizontal() ? split.getWidth() : split.getHeight())-4;
            int next=(int)Math.round(length*Math.max(.05,Math.min(.95,before/(double)Math.max(1,length)+delta)));
            resizeDivider(split,next);
            if (before!=split.getDividerLocation()) tutorialAction(split.isHorizontal() ? "divider-horizontal" : "divider-vertical");
        } else if (active!=null) active.problem("Add another pane first; there is no divider to adjust.");
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
        ComboBox<String> scope=new ComboBox<>(FXCollections.observableArrayList("Pane / quadrant","Whole window (2-3 panes)"));
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

    public void profiles() { new ProfilesDialog(this,active==null ? profileList().getFirst() : active.profile()).setVisible(true); }

    public void moveProfile(UUID id,int direction) {
        List<Profile> order=ProfileManagement.move(profileList(),id,direction);
        profiles.clear(); order.forEach(p -> profiles.put(p.id(),p));
        refreshAddProfiles(); panes.values().forEach(SessionPane::refreshPresets); changed();
        if (preferences!=null) { preferences.syncProfiles(); preferences.sharedChanged(); }
    }

    public void validateDeleteProfile(UUID id) {
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

    private void saveProfileLayout() {
        Profile updated=active.profile().withLayout(currentLayout().encode());
        putProfile(updated);
        active.problem("Layout saved to "+updated.name()+".");
    }

    private void restoreProfileLayout() {
        String saved=active.profile().savedLayout();
        if (saved.isEmpty()) { active.problem("This profile has no saved layout. Ctrl+Alt+S saves the current layout."); return; }
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

    private void zoom(double delta) {
        double value=Math.max(.5,Math.min(3,active.view().zoomValue()+delta));
        Profile profile=active.profile().withZoom(value);
        putProfile(profile);
        active.view().clearSavedZoom();
        active.view().setFit(false);
        changed();
    }

    private void fit() {
        putProfile(active.profile().withZoom(1));
        active.view().setFit(true);
        changed();
    }

    public void toggleFlux() {
        if (closing || settingsSaving() || active==null) return;
        Profile profile=active.profile();
        putProfile(profile.withDisplay(profile.theme(),profile.effect().equals(Profile.FLUX) ? "None" : Profile.FLUX));
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
                },() -> !closing,"automatic reconnect launch");
        startup.setSelected(connectOnStartup);
        commands.add(startup);
        session.getItems().add(new SeparatorMenuItem());
        action(session,"Profiles...",this::profiles);
        action(session,"Switch profile...",this::quickSwitch);
        session.getItems().add(new SeparatorMenuItem());
        action(session,"Quit",() -> { if (application==null) close(false); else application.quit(); });

        Menu edit=menu(bar,"Edit");
        action(edit,"Copy selection / screen",() -> active.view().copy());
        action(edit,"Paste into fields",() -> active.view().paste());
        edit.getItems().add(new SeparatorMenuItem());
        action(edit,Shortcuts.SETTINGS,() -> preferences("Appearance"));
        for (String alias:Shortcuts.SETTINGS_ALIASES.stream().sorted().toList()) {
            Runnable handler=switch (alias) {
                case "Theme designer..." -> () -> new ThemeDesigner(this).setVisible(true);
                case "Check for updates..." -> () -> updates.manual();
                default -> () -> preferences("Appearance");
            };
            action(null,alias,handler);
        }

        Menu inserts=menu(bar,"Inserts");
        action(inserts,"Insert profile username",() -> active.paste(connectionProfile(active.profile()).username()));
        action(inserts,"Insert remembered password",() -> active.sendPassword());
        inserts.getItems().add(new SeparatorMenuItem());
        for (KeyMnemonic key:List.of(KeyMnemonic.ENTER,KeyMnemonic.FIELD_EXIT,KeyMnemonic.FIELD_PLUS,KeyMnemonic.FIELD_MINUS,
                KeyMnemonic.CLEAR,KeyMnemonic.HELP,KeyMnemonic.PAGE_UP,KeyMnemonic.PAGE_DOWN))
            action(inserts,key.name().replace('_',' '),() -> active.send(key)).hostKey=key;
        inserts.getItems().add(new SeparatorMenuItem());
        action(inserts,"Send host key...",this::hostKeys);

        Menu layoutMenu=menu(bar,"Panes");
        action(layoutMenu,"Add pane",() -> split(true));
        action(layoutMenu,"Swap panes...",this::dockingDialog);
        action(layoutMenu,"Arrange pane...",this::arrangementDialog);
        action(layoutMenu,"Undo pane arrangement",this::undoArrangement);
        action(layoutMenu,"Close pane",this::closePane);
        layoutMenu.getItems().add(new SeparatorMenuItem());
        action(layoutMenu,"Next pane",() -> cycle(1));
        action(layoutMenu,"Previous pane",() -> cycle(-1));
        layoutMenu.getItems().add(new SeparatorMenuItem());
        action(layoutMenu,"Decrease divider",() -> adjustDivider(-.05));
        action(layoutMenu,"Increase divider",() -> adjustDivider(.05));
        layoutMenu.getItems().add(new SeparatorMenuItem());
        action(layoutMenu,"Save layout to profile",this::saveProfileLayout);
        action(layoutMenu,"Restore profile layout",this::restoreProfileLayout);
        action(layoutMenu,"Saved layouts / shortcuts...",this::savedLayoutsDialog);

        Menu view=menu(bar,"View");
        action(view,"Fit host screen",this::fit);
        action(view,"Zoom in",() -> zoom(.1));
        action(view,"Zoom out",() -> zoom(-.1));
        for (AppTheme t:AppTheme.values()) {
            AppCommand change=new AppCommand("Theme: "+t,"Application theme; does not reconnect sessions",null,
                    () -> applyTheme(t),() -> !closing && !settingsSaving(),"appearance color scheme "+t.name());
            commands.add(change);
        }
        AppCommand flux=new AppCommand("Flux animated text","Flowing multicolor text; opt-in, host attributes preserved",null,
                () -> putProfile(active.profile().withDisplay(active.profile().theme(),Profile.FLUX)),
                () -> active!=null && !closing,"theme gradient rainbow animated flowing effect");
        commands.add(flux);

        Menu host=menu(bar,"Host");
        for (KeyMnemonic key:List.of(KeyMnemonic.RESET,KeyMnemonic.ATTN,KeyMnemonic.SYSREQ)) {
            action(host,key.name(),() -> active.send(key)).hostKey=key;
        }
        host.getItems().add(new SeparatorMenuItem());
        action(host,"Last pane notice...",() -> active.showLastNotice());

        Menu help=menu(bar,"Help");
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
        action(help,"Full manual / Getting Started",() -> {
            try { new ManualDialog(this).setVisible(true); }
            catch (java.io.IOException e) { Ui.error(stage,"The manual could not be opened: "+e.getMessage()); }
        });
        action(help,"Configuration for external tools",this::configurationGuide);
        action(help,"Keyboard reference",() -> Ui.message(stage,
                Keymap.describeDefaults()+"\n\nCtrl+Alt+M: Menu     Ctrl+Alt+P: Profiles\nCtrl+N: Add pane (max 8)     Ctrl+Alt+D: Swap\n"
                +"Ctrl+Tab / Ctrl+Shift+Tab: Focus pane     Ctrl+Alt+1..8: Focus pane number\n"
                +"Ctrl+Alt+[/]: Resize divider     Ctrl+0: Fit\nCtrl+C/V: Copy/paste\n"
                +"Shift+Arrows: Rectangular selection     Drag: Mouse selection\n"
                +"Ctrl+Alt+Space: Window menu     Alt+Space: Windows menu     Alt+F4: Close\n"
                +"Window Move/Size: Arrows (Shift for 1px), Enter commits, Esc cancels\n"
                +"Ctrl+comma: Settings     Appearance, Profiles, Keyboard Shortcuts, General, Updates\n"
                +"Your saved custom bindings take precedence; Settings > Keyboard Shortcuts shows every exact assignment.",
                "Keyboard reference"));
        action(help,"Compatibility and connection safety",() -> Ui.message(stage,
                "TN5250J-based 5250 engine; only IBM-3477-FC.\nPlain TCP is not encrypted. Use only on a trusted network.\n"
                +"No real IBM i / ACS acceptance has been performed.\nEnhanced display structured fields and WEA stop the session explicitly.\n"
                +"See COMPATIBILITY.md for the demonstrated scope and acceptance checklist.\n"
                +"GPL-2.0-or-later. No warranty.","Compatibility"));

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
            if (text.equals("Add pane")) return panes.size()<Layout.MAX_PANES;
            if (List.of("Profiles...","New window","Saved layouts / shortcuts...","Quit","Appearance...","Theme designer...","Fonts...","Pane information...","Window and focus...","Keyboard reference",
                    "Compatibility and connection safety","Shortcuts...","Full manual / Getting Started",
                    "Getting started tutorial","Configuration for external tools","Application settings...","Check for updates...","Last pane notice...",Shortcuts.SETTINGS).contains(text)
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
        for (String act:List.of("Connect / disconnect","Add pane","Arrange pane...","Swap panes...","Close pane"))
            menu.getItems().add(command(act).createMenuItem(act));
        return menu;
    }

    public void close(boolean force) {
        if (application!=null) { application.closeWindow(this); return; }
        if (closing) return;
        if (!force && settingsSaving()) { Ui.error(stage,"Wait for the profile save to finish before closing Fish."); return; }
        if (!force && !closeSettings()) return;
        saveTimer.stop();
        StateStore.State snapshot=state();
        closing=true;
        disposeWorkspace();
        persistence.submit(() -> {
            try {
                store.save(snapshot);
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
        Ui.removeThemeListener(themeListener);
        updates.close();
        if (tutorial!=null) tutorial.dispose();
        if (preferences!=null && !preferences.ended()) preferences.forceDispose();
        if (layoutsDialog!=null) layoutsDialog.dispose();
        shortcuts.dispose();
        panes.values().forEach(SessionPane::dispose);
        stage.close();
    }

    void pauseSaving() { saveTimer.stop(); }
    boolean closeSettings() {
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
        if (layoutsDialog!=null && layoutsDialog.isOpen()) layoutsDialog.refresh();
    }

    public List<SavedLayout> savedLayouts() { return savedLayouts; }
    public UUID selectedLayout() { return selectedLayout; }
    public void selectSavedLayout(UUID id) { if (!Objects.equals(selectedLayout,id)) { selectedLayout=id; changed(); } }

    public SavedLayout saveCurrentLayout(UUID id,String name) {
        if (settingsSaving()) throw new IllegalArgumentException("Wait for the pending save to finish.");
        StateStore.State current=state();
        Map<UUID,SavedLayout.Display> displays=new LinkedHashMap<>();
        sessionPanes().forEach(p -> displays.put(p.id(),new SavedLayout.Display(p.view().isFit(),p.view().zoomValue())));
        SavedLayout value=new SavedLayout(id==null ? UUID.randomUUID() : id,name,
                new StateStore.WindowState(current.layout(),current.width(),current.height(),current.lastProfile(),current.addProfile()),displays);
        List<SavedLayout> next=new ArrayList<>(savedLayouts);
        if (id!=null && next.stream().noneMatch(s -> s.id().equals(id))) throw new IllegalArgumentException("Saved layout was deleted. Save as new instead.");
        next.removeIf(s -> s.id().equals(value.id())); next.add(value);
        updateSavedLayouts(next,value.id());
        tutorialAction("layout-saved");
        return value;
    }

    public void renameSavedLayout(UUID id,String name) {
        List<SavedLayout> next=savedLayouts.stream().map(s -> s.id().equals(id) ? s.renamed(name) : s).toList();
        updateSavedLayouts(next,id);
    }

    public void deleteSavedLayout(UUID id) {
        List<SavedLayout> next=savedLayouts.stream().filter(s -> !s.id().equals(id)).toList();
        updateSavedLayouts(next,next.isEmpty() ? null : next.getFirst().id());
    }

    private void updateSavedLayouts(List<SavedLayout> next,UUID selection) {
        if (settingsSaving()) throw new IllegalArgumentException("Wait for the pending save to finish.");
        state().withSavedLayouts(next,selection);
        savedLayouts=List.copyOf(next); selectedLayout=selection; changed();
    }

    public boolean openSavedLayout(UUID id) {
        if (application==null) throw new IllegalArgumentException("Launch Fish normally to open saved layouts in independent windows.");
        return application.openLayout(id);
    }

    public void exportSavedLayout(UUID id,Path destination,Runnable done,java.util.function.Consumer<String> failed) {
        if (demonstration) { failed.accept("Shortcuts require a normal saved workspace, not a temporary Demo host."); return; }
        if (settingsSaving()) { failed.accept("Wait for the pending save to finish."); return; }
        if (savedLayouts.stream().noneMatch(s -> s.id().equals(id))) { failed.accept("Saved layout is unavailable."); return; }
        final Path executable;
        try { executable=fish.LayoutShortcuts.executable(); }
        catch (java.io.IOException e) { failed.accept(e.getMessage()); return; }
        exportingLayout=true; refreshActions();
        Runnable success=() -> { exportingLayout=false; refreshActions(); done.run(); };
        java.util.function.Consumer<String> failure=message -> { exportingLayout=false; refreshActions(); failed.accept(message); };
        Maintenance task=() -> fish.LayoutShortcuts.create(destination,executable,dataDirectory(),id);
        if (application!=null) application.persistTask(task,success,failure);
        else {
            StateStore.State snapshot=state();
            persistence.submit(() -> {
                try { store.save(snapshot); task.run(); Ui.runLater(success); }
                catch (java.io.IOException | RuntimeException e) { Ui.runLater(() -> failure.accept(e.getMessage())); }
            });
        }
    }

    public void savedLayoutsDialog() {
        if (layoutsDialog==null || !layoutsDialog.isOpen()) layoutsDialog=new SavedLayoutsDialog(this);
        else layoutsDialog.refresh();
        if (isShowing()) layoutsDialog.show();
        tutorialAction("layouts-opened");
    }

    public boolean updateAndExit(Maintenance launch,java.util.function.Consumer<String> failed) {
        if (application!=null) return application.maintenance(this,launch,false,failed);
        StateStore.State snapshot=state();
        return maintenance(() -> { store.save(snapshot); launch.run(); },failed);
    }

    public void factoryDefaults() {
        if (!Ui.confirm(stage,"Remove all profiles, layouts, themes, font choices and preferences, then close ALL Fish windows?\n"
                +"Active sessions in every Fish window will end. Next launch starts the getting-started guide with the default Kerberos profile.\n"
                +"Windows saved passwords, font files and other workspaces are kept. No backup is created.","Factory defaults")) return;
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
