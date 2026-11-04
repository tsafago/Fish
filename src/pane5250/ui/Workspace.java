package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import org.tn5250j.keyboard.KeyMnemonic;
import pane5250.model.*;
import pane5250.security.CredentialStore;

/**
 * THESIS: the host presentation space owns the window, not application decoration.
 * OWN-WORLD: near-black flat panes, hairline steel dividers, compact headers, true monospace cells.
 * STORY: configure a workstation, connect, work in fields; app actions never consume host F-keys.
 * FIRST VIEWPORT: terminal fills the window; menu, 25px pane headers, 18px status; no sidebars.
 * FORM: user-pinned split workspace, one large left pane and two stacked right panes in demo.
 */
public final class Workspace extends JFrame {
    private final StateStore store;
    private final CredentialStore credentials;
    private final LinkedHashMap<UUID,Profile> profiles = new LinkedHashMap<>();
    private final Map<UUID,SessionPane> panes = new LinkedHashMap<>();
    private final List<AppCommand> commands = new ArrayList<>();
    private final JPanel content = new JPanel(new BorderLayout());
    private final ExecutorService persistence = Executors.newSingleThreadExecutor(r -> {
        return new Thread(r,"workspace-save");
    });
    private final javax.swing.Timer saveTimer;
    private Layout layout;
    private SessionPane active;
    private UUID lastProfile;
    private Component tree;
    private final boolean demonstration;
    private boolean closing;
    private boolean highlightActive;
    private Appearance appearance;
    private Font terminalFont;
    private String fontNotice="";
    private final AppShortcuts shortcuts;
    private final WindowChrome chrome;
    private JButton addPane,settingsButton;
    private Preferences preferences;
    private ProfilesEditor profileEditor;
    private JMenuBar menuBar;
    private StateStore.Tutorial tutorialStatus;
    private TutorialDialog tutorial;
    private boolean connectOnStartup, startupHandled;
    private UpdateSettings updateSettings;
    private Map<String,String> keyboardRecovery;
    private final UpdateController updates;
    private Runnable afterClose = () -> {};

    public Workspace(StateStore store, StateStore.State state, Path credentialHelper, boolean demonstration) {
        this(store,state,credentialHelper,demonstration,new pane5250.update.Updates());
    }
    Workspace(StateStore store,StateStore.State state,Path credentialHelper,boolean demonstration,pane5250.update.Updates updateService) {
        this(store,state,credentialHelper,demonstration,updateService,(owner,message) -> Ui.confirm(owner,message,"Update Fish"));
    }
    Workspace(StateStore store,StateStore.State state,Path credentialHelper,boolean demonstration,pane5250.update.Updates updateService,
              java.util.function.BiPredicate<Component,String> confirmUpdate) {
        super("Fish" + (demonstration ? " - local synthetic host" : ""));
        setUndecorated(true);
        setIconImages(FishIcon.images());
        getAccessibleContext().setAccessibleName("Fish");
        appearance=state.appearance();
        tutorialStatus=state.tutorial();
        connectOnStartup=state.connectOnStartup();
        updateSettings=state.updates();
        keyboardRecovery=state.keyboardRecovery();
        Ui.applyTheme(state.appTheme(),appearance.custom().orElse(null));
        this.store = store;
        this.demonstration = demonstration;
        credentials = new CredentialStore(credentialHelper);
        state.profiles().forEach(p -> profiles.put(p.id(),p));
        resolveFont();
        layout = Layout.normalize(state.layout());
        lastProfile=state.lastProfile();
        highlightActive=state.highlightActive();
        saveTimer = new javax.swing.Timer(500,e -> saveAsync());
        saveTimer.setRepeats(false);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(330,260));
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(state.width(),screen.width),Math.min(state.height(),screen.height));
        setLocationRelativeTo(null);
        setBackground(Ui.BACKGROUND);
        content.setBackground(Ui.BACKGROUND);
        JPanel shell = new JPanel(new BorderLayout());
        Ui.background(shell,ColorRole.BACKGROUND);
        setContentPane(shell);
        updateWindowOutline();
        JMenuBar menus=menus(); menuBar=menus;
        JPanel tools=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0)); tools.setOpaque(false);
        addPane=new JButton(command("Add pane"));
        addPane.setText("Pane"); addPane.setIcon(Ui.symbol("plus"));
        addPane.setToolTipText("Add the next pane (maximum 8)");
        addPane.getAccessibleContext().setAccessibleName("Add pane");
        settingsButton=new JButton(command(Shortcuts.SETTINGS)); settingsButton.setText("Settings");
        settingsButton.getAccessibleContext().setAccessibleName("Settings");
        for (JButton b : List.of(addPane,settingsButton)) {
            b.setBorder(BorderFactory.createEmptyBorder(6,9,6,9));
            b.setContentAreaFilled(false);
            b.setIconTextGap(5);
            Ui.foreground(b,ColorRole.CHROME_TEXT);
            tools.add(b);
        }
        chrome=new WindowChrome(this,menus,tools,this::toggleFlux);
        shell.add(chrome,BorderLayout.NORTH);
        shell.add(content,BorderLayout.CENTER);
        shortcuts=new AppShortcuts(this,commands,state.shortcuts());
        updates=new UpdateController(this,updateService,confirmUpdate);
        commands.forEach(command -> command.performed(this::tutorialAction));
        buildTree();
        if (!fontNotice.isEmpty()) SwingUtilities.invokeLater(() -> {
            if (!closing) { active.problem(fontNotice); Ui.error(this,fontNotice); }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { close(false); }
        });
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { changed(); }
        });
    }
    public SessionPane active() { return active; }
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
    public void startup() {
        if (startupHandled || demonstration || closing) return;
        startupHandled=true;
        if (connectOnStartup) sessionPanes().forEach(SessionPane::connectOnStartup);
        startTutorialIfNeeded();
        if (updateSettings.startup()) updates.startup();
    }
    boolean closing() { return closing; }
    public UpdateSettings updateSettings() { return updateSettings; }
    public void updateSettings(UpdateSettings value) { updateSettings=Objects.requireNonNull(value); changed(); }
    Path dataDirectory() { return store.file().getParent(); }
    public void showTutorial() { openTutorial(false); }
    private void openTutorial(boolean firstRun) {
        openTutorial(TutorialDialog.Guide.GETTING_STARTED,firstRun);
    }
    void openTutorial(TutorialDialog.Guide guide,boolean firstRun) {
        if (tutorial!=null && !tutorial.ended()) tutorial.finish(false);
        tutorial=new TutorialDialog(this,guide,firstRun);
        if (isShowing()) { tutorial.setVisible(true); tutorial.focusGuide(); }
    }
    void tutorialChanged() { if (tutorial!=null) tutorial.refreshTarget(); }
    void tutorialAction(String action) { if (tutorial!=null && !tutorial.ended()) tutorial.action(action); }
    void profileEditorOpened(ProfilesEditor editor) { profileEditor=editor; tutorialChanged(); }
    void profileEditorClosed(ProfilesEditor editor) { if (profileEditor==editor) profileEditor=null; tutorialChanged(); }
    ProfilesEditor tutorialProfileEditor() {
        return profileEditor!=null ? profileEditor : preferences!=null && !preferences.ended() && preferences.category().equals("Profiles")
                ? preferences.profiles : null;
    }
    Preferences tutorialPreferences() { return preferences!=null && !preferences.ended() ? preferences : null; }
    Component tutorialAddTarget() { return addPane; }
    Component tutorialSettingsTarget() { return settingsButton; }
    Component tutorialDivider(boolean horizontal) { return dividerTarget(tree,horizontal); }
    private Component dividerTarget(Component node,boolean horizontal) {
        if (!(node instanceof JSplitPane split)) return null;
        if ((split.getOrientation()==JSplitPane.HORIZONTAL_SPLIT)==horizontal
                && split.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI ui) return ui.getDivider();
        Component left=dividerTarget(split.getLeftComponent(),horizontal);
        return left==null ? dividerTarget(split.getRightComponent(),horizontal) : left;
    }
    Component tutorialMenuTarget(String menu,String action) { return chrome.tutorialMenuTarget(menu,action); }
    void showTutorialMenu(String menu) { chrome.showTutorialMenu(menu); }
    Component tutorialTarget(int step) {
        if (step<=3) return tutorialMenuTarget("Session","Profiles...");
        if (step==4) return active==null ? content : active.tutorialConnectionTarget();
        if (step==5) return tutorialMenuTarget("Panes","Add pane");
        if (tree instanceof JSplitPane split && split.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI ui)
            return ui.getDivider();
        return active==null ? content : active.tutorialDragTarget();
    }
    public void configurationGuide() {
        try { new ManualDialog(this,"/AI-CONFIG.md","Configuration for external tools").setVisible(true); }
        catch (java.io.IOException e) { Ui.error(this,"Configuration guide could not be opened: "+e.getMessage()); }
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
        tutorialChanged();
        changed();
        if (!fontNotice.isEmpty()) active.problem(fontNotice);
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
        JDialog dialog=preferencesDialog(category);
        if (isShowing()) { dialog.setVisible(true); dialog.toFront(); preferences.focus(); }
        tutorialAction("settings-"+preferences.category());
        tutorialChanged();
    }
    JDialog preferencesDialog(String category) {
        if (preferences==null || preferences.ended()) preferences=new Preferences(this,category);
        else preferences.select(category);
        return preferences.dialog();
    }
    void preferencesClosed(Preferences value) { if (preferences==value) preferences=null; tutorialChanged(); }
    UpdateController updateController() { return updates; }
    boolean settingsSaving() { return preferences!=null && preferences.saving() || profileEditor!=null && profileEditor.saving(); }
    void refreshActions() { refreshCommands(); }
    Map<String,String> keyboardRecovery() { return keyboardRecovery; }
    void keyboardRecovery(Map<String,String> value) { KeyboardMigration.validateRecovery(value); keyboardRecovery=Map.copyOf(value); changed(); }
    boolean connectOnStartup() { return connectOnStartup; }
    void connectOnStartup(boolean value) {
        connectOnStartup=value; command("Connect panes on startup").putValue(Action.SELECTED_KEY,value); changed();
    }
    void executeCommand(String title) { command(title).actionPerformed(new ActionEvent(this,ActionEvent.ACTION_PERFORMED,"preferences")); }
    @Override public void addNotify() {
        super.addNotify();
        if (chrome!=null) chrome.installNative();
    }
    @Override public void removeNotify() {
        if (chrome!=null) chrome.nativeRemoved();
        super.removeNotify();
    }
    public Set<KeyMnemonic> reassignedHostActions() { return shortcuts.reassignedHostActions(); }
    public Map<String,String> shortcutOverrides() { return shortcuts.overrides(); }
    public String shortcutLabel(String title) { return Objects.toString(command(title).getValue("Fish.shortcut"),""); }
    public void applyShortcuts(Map<String,String> values) {
        shortcuts.apply(values,profileList());
        panes.values().forEach(p -> { p.refreshKeys(); p.updateHeader(); });
        refreshShortcutLabels();
        changed();
    }
    public void validateProfile(Profile profile) {
        if (!profile.keymap().isEmpty()) throw new IllegalArgumentException("Keyboard mappings are application-wide. Use Settings > Keyboard Shortcuts, not per-profile keys.");
        List<Profile> candidates=new ArrayList<>(profileList());
        candidates.removeIf(p -> p.id().equals(profile.id())); candidates.add(profile);
        Profile existing=profiles.get(profile.id());
        if (existing!=null && (connectionChanged(existing,profile) || existing.rememberPassword()!=profile.rememberPassword())
                && panes.values().stream().anyMatch(p -> p.connecting()
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
    private AppCommand command(String name) { return commands.stream().filter(c -> c.title.equals(name)).findFirst().orElseThrow(); }
    public void refreshCommands() { commands.forEach(AppCommand::refresh); }
    private void refreshShortcutLabels() {
        if (shortcuts!=null) shortcuts.refreshLabels();
    }
    public void themeChanged() {
        setIconImages(FishIcon.images());
        content.setBackground(Ui.BACKGROUND);
        if (getContentPane()!=null) getContentPane().setBackground(Ui.BACKGROUND);
        updateWindowOutline();
    }
    private void updateWindowOutline() {
        Ui.background(getRootPane(),ColorRole.BACKGROUND);
        getRootPane().setBorder(appearance!=null && appearance.windowOutline() ? Hairline.perimeter() : BorderFactory.createEmptyBorder());
    }
    public void applyTheme(AppTheme theme) {
        appearance=appearance.withTheme("",appearance.themes());
        Ui.applyTheme(theme); changed();
    }
    public String paneName(UUID id) {
        List<SessionPane> order=sessionPanes();
        int index=order.indexOf(panes.get(id)),count=order.size();
        if (count==1) return "Pane";
        if (count==2) return index==0 ? "Left" : "Right";
        if (count==3) return index==0 ? "Left" : index==1 ? "Upper right" : "Lower right";
        int top=(count+1)/2;
        return index<top ? "Top row, column "+(index+1) : "Bottom row, column "+(index-top+1);
    }
    public void activate(SessionPane pane) {
        if (active==pane) { refreshCommands(); return; }
        active = pane;
        tutorialChanged();
        refreshShortcutLabels();
        panes.values().forEach(SessionPane::updateHeader);
        refreshCommands();
    }
    public void onClosed(Runnable action) { afterClose = action; }
    public void connectAll() { sessionPanes().forEach(SessionPane::connect); }
    public void changed() { if (!closing) saveTimer.restart(); }

    private void buildTree() {
        Set<UUID> live = new HashSet<>();
        layout.leaves().forEach(l -> live.add(l.pane()));
        Iterator<Map.Entry<UUID,SessionPane>> iterator = panes.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!live.contains(entry.getKey())) { entry.getValue().dispose(); iterator.remove(); }
        }
        content.removeAll();
        tree = component(layout);
        content.add(tree,BorderLayout.CENTER);
        if (active == null || !live.contains(active.id())) active = live.isEmpty() ? null : panes.get(layout.leaves().getFirst().pane());
        panes.values().forEach(SessionPane::updateHeader);
        refreshShortcutLabels();
        content.revalidate();
        content.repaint();
        refreshCommands();
        tutorialChanged();
        SwingUtilities.invokeLater(() -> { if (active != null) active.focusTerminal(); });
    }
    private Component component(Layout node) {
        if (node instanceof Layout.Leaf leaf)
            return panes.computeIfAbsent(leaf.pane(),id -> new SessionPane(this,id,Objects.requireNonNull(profiles.get(leaf.profile()))));
        Layout.Split split = (Layout.Split) node;
        JSplitPane divider = new JSplitPane(split.horizontal() ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT,
                component(split.first()),component(split.second()));
        divider.putClientProperty("initialRatio",split.ratio());
        divider.setBorder(BorderFactory.createEmptyBorder());
        divider.setDividerSize(4);
        divider.setContinuousLayout(true);
        divider.setResizeWeight(split.ratio());
        divider.setOneTouchExpandable(false);
        divider.setBackground(Ui.LINE);
        divider.setUI(new FlatControls.Split());
        divider.addComponentListener(new ComponentAdapter() {
            private boolean first = true;
            @Override public void componentResized(ComponentEvent e) {
                if (first && divider.getWidth() > 0 && divider.getHeight() > 0) {
                    divider.setDividerLocation(split.ratio());
                    first = false;
                }
            }
        });
        divider.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,e -> changed());
        if (divider.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI ui) ui.getDivider().addMouseListener(new MouseAdapter() {
            private int start;
            @Override public void mousePressed(MouseEvent e) { start=divider.getDividerLocation(); }
            @Override public void mouseReleased(MouseEvent e) {
                if (start!=divider.getDividerLocation()) tutorialAction(split.horizontal() ? "divider-horizontal" : "divider-vertical");
            }
        });
        return divider;
    }
    private Layout capture(Component component) {
        if (component==tree && layout instanceof Layout.Empty) return layout;
        if (component instanceof SessionPane pane) return new Layout.Leaf(pane.id(),pane.profile().id());
        JSplitPane split = (JSplitPane) component;
        int total = (split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? split.getWidth() : split.getHeight()) - split.getDividerSize();
        double ratio = total > 0 && split.getDividerLocation() >= 0 ? split.getDividerLocation() / (double) total
                : (double) split.getClientProperty("initialRatio");
        return new Layout.Split(split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT,
                Math.max(.05,Math.min(.95,ratio)),capture(split.getLeftComponent()),capture(split.getRightComponent()));
    }
    public Layout currentLayout() { layout = capture(tree); return layout; }
    private StateStore.State state() { return new StateStore.State(profileList(),currentLayout(),getWidth(),getHeight(),Ui.theme(),lastProfile,
            shortcuts.overrides(),highlightActive,appearance,tutorialStatus,connectOnStartup,updateSettings,keyboardRecovery); }
    public void saveAsync() {
        if (closing) return;
        StateStore.State snapshot = state();
        persistence.submit(() -> {
            try { store.save(snapshot); }
            catch (java.io.IOException e) { SwingUtilities.invokeLater(() -> {
                if (closing) System.err.println("Fish workspace not saved: "+e.getMessage());
                else Ui.error(this,"Workspace not saved: "+e.getMessage());
            }); }
        });
    }
    public void split(boolean horizontal) {
        if (panes.size()>=Layout.MAX_PANES) return;
        UUID id=UUID.randomUUID();
        List<Layout.Leaf> slots=new ArrayList<>(currentLayout().leaves());
        slots.add(new Layout.Leaf(id,lastProfile));
        layout=Layout.bounded(slots);
        buildTree();
        activate(panes.get(id)); active.focusTerminal();
        changed();
        tutorialAction("Add pane");
    }
    public void dock(UUID source, UUID target, Layout.Dock dock) {
        layout = currentLayout().move(source,target,Layout.Dock.CENTER);
        buildTree();
        active = panes.get(source);
        activate(active);
        SwingUtilities.invokeLater(active::focusTerminal);
        changed();
        tutorialAction("panes-swapped");
    }
    public void clearPreviews() { panes.values().forEach(SessionPane::clearPreview); }
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
            layout=Layout.bounded(slots);
            buildTree();
        }
        active.focusTerminal();
        changed();
        tutorialAction("Close pane");
    }
    public void cycle(int delta) {
        List<SessionPane> ordered = sessionPanes();
        if (ordered.isEmpty()) return;
        int index = Math.floorMod(ordered.indexOf(active) + delta,ordered.size());
        activate(ordered.get(index));
        active.focusTerminal();
    }
    private void adjustDivider(double delta) {
        Component parent = active.getParent();
        if (parent instanceof JSplitPane split) {
            int before=split.getDividerLocation();
            int length = (split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? split.getWidth() : split.getHeight()) - split.getDividerSize();
            split.setDividerLocation(Math.max(.05,Math.min(.95,split.getDividerLocation() / (double) Math.max(1,length) + delta)));
            if (before!=split.getDividerLocation()) tutorialAction(split.getOrientation()==JSplitPane.HORIZONTAL_SPLIT ? "divider-horizontal" : "divider-vertical");
        } else active.problem("Add another pane first; there is no divider to adjust.");
    }
    private void dockingDialog() {
        if (panes.size() == 1) return;
        SessionPane source = active;
        List<SessionPane> targets = sessionPanes().stream().filter(p -> p != source).toList();
        JComboBox<String> target = new JComboBox<>(targets.stream().map(p -> paneName(p.id())+" - "+p.profile().name()).toArray(String[]::new));
        JPanel form = new JPanel(new GridLayout(0,2,10,10));
        JLabel targetLabel = new JLabel("Target pane"); targetLabel.setLabelFor(target);
        form.add(targetLabel); form.add(target);
        if (Ui.form(this,"Swap panes",form,"Swap"))
            dock(source.id(),targets.get(target.getSelectedIndex()).id(),Layout.Dock.CENTER);
    }

    public void profiles() { new ProfilesDialog(this,active==null ? profileList().getFirst() : active.profile()).setVisible(true); }
    public void moveProfile(UUID id,int direction) {
        List<Profile> order=ProfileManagement.move(profileList(),id,direction);
        profiles.clear(); order.forEach(p -> profiles.put(p.id(),p));
        panes.values().forEach(SessionPane::refreshPresets); changed();
        if (preferences!=null) preferences.syncProfiles();
    }
    public void validateDeleteProfile(UUID id) {
        ProfileManagement.delete(state(),id);
        if (panes.values().stream().anyMatch(p -> p.profile().id().equals(id) && p.connecting()))
            throw new IllegalArgumentException("This profile has a pending login operation. Cancel it or wait for it to finish before deleting.");
    }
    public void deleteProfile(UUID id) {
        validateDeleteProfile(id);
        StateStore.State next=ProfileManagement.delete(state(),id);
        for (SessionPane pane:panes.values()) if (pane.profile().id().equals(id)) pane.disconnect();
        profiles.clear(); next.profiles().forEach(p -> profiles.put(p.id(),p));
        lastProfile=next.lastProfile(); layout=next.layout();
        Map<UUID,UUID> references=new HashMap<>();
        layout.leaves().forEach(leaf -> references.put(leaf.pane(),leaf.profile()));
        for (SessionPane pane:panes.values()) pane.updateProfile(profiles.get(references.get(pane.id())));
        refreshShortcutLabels(); changed();
        if (preferences!=null) preferences.syncProfiles();
    }
    private static boolean connectionChanged(Profile a, Profile b) {
        return !a.host().equals(b.host()) || a.port() != b.port() || !a.device().equals(b.device())
                || !a.username().equals(b.username()) || a.automaticSignOn() != b.automaticSignOn() || !a.codePage().equals(b.codePage())
                || !Objects.equals(a.loginSource(),b.loginSource()) || a.authentication()!=b.authentication()
                || !a.kerberosServer().equals(b.kerberosServer());
    }
    public void putProfile(Profile value) {
        Profile existing=profiles.get(value.id());
        if (value.equals(existing)) return;
        if (existing==null || !existing.keymap().equals(value.keymap()) || !existing.codePage().equals(value.codePage())
                || connectionChanged(existing,value) || existing.rememberPassword()!=value.rememberPassword())
            validateProfile(value);
        Profile old = profiles.put(value.id(),value);
        if (existing==null || !existing.keymap().equals(value.keymap()))
            shortcuts.apply(shortcuts.overrides(),profileList());
        for (SessionPane pane : panes.values()) {
            if (pane.profile().id().equals(value.id())) {
                if (old != null && connectionChanged(old,value)) pane.disconnect();
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
            layout = currentLayout();
            changed();
        }
        if (!active.busy()) active.connect();
        refreshShortcutLabels();
        rememberProfile(value);
        active.focusTerminal();
    }
    private void quickSwitch() {
        JComboBox<Profile> choices = new JComboBox<>(profileList().toArray(Profile[]::new));
        choices.setSelectedItem(active.profile());
        if (Ui.form(this,"Switch workstation",choices,"Switch")) useProfile((Profile) choices.getSelectedItem());
    }
    private void saveProfileLayout() {
        Profile updated = active.profile().withLayout(currentLayout().encode());
        putProfile(updated);
        active.problem("Layout saved to " + updated.name() + ".");
    }
    private void restoreProfileLayout() {
        String saved = active.profile().savedLayout();
        if (saved.isEmpty()) { active.problem("This profile has no saved layout. Ctrl+Alt+S saves the current layout."); return; }
        try {
            Layout restored = Layout.normalize(Layout.decode(saved));
            for (Layout.Leaf leaf : restored.leaves()) {
                if (!profiles.containsKey(leaf.profile())) throw new IllegalArgumentException("Saved profile no longer exists.");
            }
            layout = restored;
            buildTree();
            changed();
        } catch (IllegalArgumentException e) { Ui.error(this,"Could not restore layout: " + e.getMessage()); }
    }
    private void zoom(double delta) {
        double value = Math.max(.5,Math.min(3,active.profile().zoom() + delta));
        Profile profile = active.profile().withZoom(value);
        putProfile(profile);
        active.view().setFit(false);
    }
    private void fit() {
        putProfile(active.profile().withZoom(1));
        active.view().setFit(true);
    }
    public void toggleFlux() {
        if (closing || settingsSaving() || active==null) return;
        Profile profile=active.profile();
        putProfile(profile.withDisplay(profile.theme(),profile.effect().equals(Profile.FLUX) ? "None" : Profile.FLUX));
    }
    private void hostKeys() {
        JComboBox<KeyMnemonic> keys = new JComboBox<>(Keymap.hostActions().toArray(KeyMnemonic[]::new));
        keys.setSelectedItem(KeyMnemonic.ENTER);
        if (Ui.form(this,"Send host key",keys,"Send")) active.send((KeyMnemonic) keys.getSelectedItem());
        active.focusTerminal();
    }
    public void appearance() {
        preferences("Appearance");
    }
    JPanel appearancePanel() {
        return (JPanel)preferencesDialog("Appearance").getContentPane();
    }

    private JMenuBar menus() {
        JMenuBar bar = new JMenuBar();
        bar.setBorder(BorderFactory.createEmptyBorder());
        JMenu session = menu(bar,"Session",KeyEvent.VK_S);
        action(session,"Connect / disconnect","ctrl alt R",() -> active.connect());
        action(session,"Reconnect","ctrl alt shift R",() -> active.reconnect());
        AppCommand startup=new AppCommand("Connect panes on startup","Reconnect restored panes using saved logins; missing passwords wait for Connect",null,
                () -> {
                    connectOnStartup=!connectOnStartup;
                    command("Connect panes on startup").putValue(Action.SELECTED_KEY,connectOnStartup);
                    changed();
                },() -> !closing,"automatic reconnect launch");
        startup.putValue(Action.SELECTED_KEY,connectOnStartup);
        commands.add(startup);
        session.addSeparator();
        action(session,"Profiles...","ctrl alt P",this::profiles);
        action(session,"Switch profile...","ctrl alt O",this::quickSwitch);
        session.addSeparator();
        action(session,"Quit","ctrl alt Q",() -> close(false));
        JMenu edit=menu(bar,"Edit",KeyEvent.VK_E);
        action(edit,"Copy selection / screen","ctrl alt shift C",() -> active.view().copy());
        action(edit,"Paste into fields","ctrl alt shift V",() -> active.view().paste());
        action(null,Shortcuts.SETTINGS,"ctrl COMMA",() -> preferences("Appearance"));
        for (String alias:Shortcuts.SETTINGS_ALIASES.stream().sorted().toList())
            action(null,alias,null,() -> preferences("Appearance"));
        JMenu inserts=menu(bar,"Inserts",KeyEvent.VK_I);
        action(inserts,"Insert profile username","ctrl alt U",() -> active.paste(connectionProfile(active.profile()).username()));
        action(inserts,"Insert remembered password","ctrl alt shift P",() -> active.sendPassword());
        inserts.addSeparator();
        for (KeyMnemonic key:List.of(KeyMnemonic.ENTER,KeyMnemonic.FIELD_EXIT,KeyMnemonic.FIELD_PLUS,KeyMnemonic.FIELD_MINUS,
                KeyMnemonic.CLEAR,KeyMnemonic.HELP,KeyMnemonic.PAGE_UP,KeyMnemonic.PAGE_DOWN))
            action(inserts,key.name().replace('_',' '),null,() -> active.send(key)).hostKey=key;
        inserts.addSeparator(); action(inserts,"Send host key...","ctrl alt F",this::hostKeys);
        JMenu layoutMenu = menu(bar,"Panes",KeyEvent.VK_P);
        action(layoutMenu,"Add pane","ctrl alt H",() -> split(true));
        action(layoutMenu,"Swap panes...","ctrl alt D",this::dockingDialog);
        action(layoutMenu,"Close pane","ctrl alt W",this::closePane);
        layoutMenu.addSeparator();
        action(layoutMenu,"Next pane","ctrl alt RIGHT",() -> cycle(1));
        action(layoutMenu,"Previous pane","ctrl alt LEFT",() -> cycle(-1));
        layoutMenu.addSeparator();
        action(layoutMenu,"Decrease divider","ctrl alt OPEN_BRACKET",() -> adjustDivider(-.05));
        action(layoutMenu,"Increase divider","ctrl alt CLOSE_BRACKET",() -> adjustDivider(.05));
        layoutMenu.addSeparator();
        action(layoutMenu,"Save layout to profile","ctrl alt S",this::saveProfileLayout);
        action(layoutMenu,"Restore profile layout","ctrl alt L",this::restoreProfileLayout);
        JMenu view = menu(bar,"View",KeyEvent.VK_V);
        action(view,"Fit host screen","ctrl alt 0",this::fit);
        action(view,"Zoom in","ctrl alt EQUALS",() -> zoom(.1));
        action(view,"Zoom out","ctrl alt MINUS",() -> zoom(-.1));
        action(null,"Theme designer...",null,() -> new ThemeDesigner(this).setVisible(true));
        for (AppTheme theme : AppTheme.values()) {
            AppCommand change=new AppCommand("Theme: "+theme,"Application theme; does not reconnect sessions",null,
                    () -> applyTheme(theme),() -> !closing && !settingsSaving(),"appearance color scheme "+theme.name());
            commands.add(change);
        }
        AppCommand flux=new AppCommand("Flux animated text","Flowing multicolor text; opt-in, host attributes preserved",null,
                () -> putProfile(active.profile().withDisplay(active.profile().theme(),Profile.FLUX)),
                () -> active!=null && !closing,"theme gradient rainbow animated flowing effect");
        commands.add(flux);
        JMenu host = menu(bar,"Host",KeyEvent.VK_H);
        for (KeyMnemonic key:List.of(KeyMnemonic.RESET,KeyMnemonic.ATTN,KeyMnemonic.SYSREQ)) {
            action(host,key.name(),null,() -> active.send(key)).hostKey=key;
        }
        host.addSeparator();
        action(host,"Last pane notice...",null,() -> active.showLastNotice());
        JMenu help = menu(bar,"Help",KeyEvent.VK_L);
        JMenu tutorials=new JMenu("Tutorials"); help.add(tutorials);
        String[] tutorialActions={"Getting started tutorial","Tutorial: Appearance","Tutorial: Non-Kerberos profile","Tutorial: Keyboard shortcuts","Tutorial: Pane tips"};
        TutorialDialog.Guide[] guides=TutorialDialog.Guide.values();
        for (int n=0;n<guides.length;n++) {
            TutorialDialog.Guide guide=guides[n];
            action(tutorials,tutorialActions[n],null,() -> openTutorial(guide,false));
            ((JMenuItem)tutorials.getMenuComponent(n)).setText(guide.title);
        }
        action(null,"Check for updates...",null,() -> updates.manual());
        action(help,"Full manual / Getting Started",null,() -> {
            try { new ManualDialog(this).setVisible(true); }
            catch (java.io.IOException e) { Ui.error(this,"The manual could not be opened: "+e.getMessage()); }
        });
        action(help,"Configuration for external tools",null,this::configurationGuide);
        action(help,"Keyboard reference",null,() -> Ui.message(this,
                Keymap.describeDefaults() + "\n\nCtrl+Alt+M: Menu     Ctrl+Alt+P: Profiles\nCtrl+N: Add pane (max 8)     Ctrl+Alt+D: Swap\n"
                + "Ctrl+Tab / Ctrl+Shift+Tab: Focus pane     Ctrl+Alt+1..8: Focus pane number\n"
                + "Ctrl+Alt+[/]: Resize divider     Ctrl+0: Fit\nCtrl+C/V: Copy/paste\n"
                + "Shift+Arrows: Rectangular selection     Drag: Mouse selection\n"
                + "Ctrl+Alt+Space: Window menu     Alt+Space: Windows menu     Alt+F4: Close\n"
                + "Window Move/Size: Arrows (Shift for 1px), Enter commits, Esc cancels\n"
                + "Ctrl+comma: Settings     Appearance, Profiles, Keyboard Shortcuts, General, Updates\n"
                + "Your saved custom bindings take precedence; Settings > Keyboard Shortcuts shows every exact assignment.",
                "Keyboard reference"));
        action(help,"Compatibility and connection safety",null,() -> Ui.message(this,
                "TN5250J-based 5250 engine; only IBM-3477-FC.\nPlain TCP is not encrypted. Use only on a trusted network.\n"
                + "No real IBM i / ACS acceptance has been performed.\nEnhanced display structured fields and WEA stop the session explicitly.\n"
                + "See COMPATIBILITY.md for the demonstrated scope and acceptance checklist.\n"
                + "GPL-2.0-or-later. No warranty.", "Compatibility"));
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
        commands.add(new AppCommand("Minimize window","Window size",null,() -> setExtendedState(getExtendedState()|Frame.ICONIFIED),
                () -> !closing,"minimize"));
        for (int i = 1; i <= 8; i++) {
            final int index = i - 1;
            commands.add(new AppCommand("Focus pane "+i,"Focus pane by its layout position","ctrl alt "+i,() -> {
                if (index < sessionPanes().size()) { activate(sessionPanes().get(index)); active.focusTerminal(); }
            },() -> !closing && index<panes.size(),"select focus pane window tab "+i));
        }
        return bar;
    }
    private JMenu menu(JMenuBar bar, String name, int mnemonic) {
        JMenu menu = new JMenu(name); menu.setMnemonic(mnemonic);
        Ui.background(menu,ColorRole.CHROME); bar.add(menu); return menu;
    }
    private AppCommand action(JMenu menu,String text,String stroke,Runnable command) {
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
            if (List.of("Profiles...","Quit","Appearance...","Theme designer...","Fonts...","Pane information...","Window and focus...","Keyboard reference",
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
            if (menu!=null && (menu.getText().equals("Host") || menu.getText().equals("Inserts")) || text.equals("RESET")
                    || text.startsWith("Insert ") || text.startsWith("Paste")) {
                return active.connected() && (!active.screen().getOIA().isKeyBoardLocked()
                        || List.of("RESET","ATTN","SYSREQ").contains(text) || text.equals("ENTER") && active.recoverableInputError());
            }
            return true;
        },aliases);
        commands.add(action);
        if (menu!=null) {
            JMenuItem item=new JMenuItem(action);
            item.setText(switch(text) {
                case "Copy selection / screen" -> "Copy"; case "Paste into fields" -> "Paste";
                case "Insert profile username" -> "Profile username"; case "Insert remembered password" -> "Saved password";
                case "RESET" -> "Reset"; case "ATTN" -> "Attention"; case "SYSREQ" -> "System Request";
                case "ENTER" -> "Enter"; case "FIELD EXIT" -> "Field Exit"; case "FIELD PLUS" -> "Field +"; case "FIELD MINUS" -> "Field -";
                case "CLEAR" -> "Clear"; case "HELP" -> "Help"; case "PAGE UP" -> "Roll up"; case "PAGE DOWN" -> "Roll down";
                default -> text;
            });
            menu.add(item);
        }
        return action;
    }
    public JPopupMenu paneMenu(SessionPane pane) {
        activate(pane);
        JPopupMenu menu = new JPopupMenu();
        for (String name : List.of("Connect / disconnect","Reconnect","Add pane",
                "Swap panes...","Close pane","Profiles...")) menu.add(new JMenuItem(command(name)));
        return menu;
    }
    public JPopupMenu clipboardMenu(SessionPane pane) {
        activate(pane);
        pane.focusTerminal();
        JPopupMenu menu=new JPopupMenu();
        menu.add(new JMenuItem(command("Copy selection / screen")));
        menu.add(new JMenuItem(command("Paste into fields")));
        menu.addSeparator();
        menu.add(new JMenuItem(command("RESET")));
        for (String action:List.of("Connect / disconnect","Add pane","Swap panes...","Close pane"))
            menu.add(new JMenuItem(command(action)));
        return menu;
    }
    public void close(boolean force) {
        if (closing) return;
        if (settingsSaving()) { Ui.error(this,"Wait for the profile save to finish before closing Fish."); return; }
        saveTimer.stop();
        StateStore.State snapshot = state();
        closing = true;
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
                SwingUtilities.invokeLater(afterClose);
            }
        });
    }
    private void disposeWorkspace() {
        updates.close();
        if (tutorial!=null) tutorial.dispose();
        shortcuts.dispose();
        panes.values().forEach(SessionPane::dispose);
        dispose();
    }
    @FunctionalInterface interface Maintenance { void run() throws java.io.IOException; }
    boolean updateAndExit(Maintenance launch,java.util.function.Consumer<String> failed) {
        StateStore.State snapshot=state();
        return maintenance(() -> { store.save(snapshot); launch.run(); },failed);
    }
    void factoryDefaults() {
        if (!Ui.confirm(this,"Remove all profiles, layouts, themes, font choices and preferences, then close Fish?\n"
                +"Active sessions will end. Next launch starts the getting-started guide with the default Kerberos profile.\n"
                +"Windows saved passwords, font files and other workspaces are kept. No backup is created.","Factory defaults")) return;
        maintenance(store::factoryReset,message -> Ui.error(this,"Factory defaults could not be saved: "+message));
    }
    private boolean maintenance(Maintenance action,java.util.function.Consumer<String> failed) {
        if (closing) return false;
        if (panes.values().stream().anyMatch(SessionPane::connecting)) {
            Ui.error(this,"A login/password operation is still pending. Cancel it or wait for it to finish, then try again."); return false;
        }
        saveTimer.stop(); closing=true; setEnabled(false);
        persistence.submit(() -> {
            try { action.run(); }
            catch (java.io.IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> {
                    closing=false; setEnabled(true); failed.accept(e.getMessage());
                });
                return;
            }
            SwingUtilities.invokeLater(() -> {
                disposeWorkspace();
                persistence.submit(() -> {
                    try { store.close(); }
                    catch (java.io.IOException e) { System.err.println("Workspace lock release failed: "+e.getMessage()); }
                    finally { persistence.shutdown(); SwingUtilities.invokeLater(afterClose); }
                });
            });
        });
        return true;
    }
}
