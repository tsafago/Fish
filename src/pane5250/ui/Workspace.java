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
    private Runnable afterClose = () -> {};

    public Workspace(StateStore store, StateStore.State state, Path credentialHelper, boolean demonstration) {
        super("Fish" + (demonstration ? " - local synthetic host" : ""));
        setUndecorated(true);
        setIconImages(FishIcon.images());
        getAccessibleContext().setAccessibleName("Fish");
        if (Ui.theme()!=state.appTheme()) Ui.applyTheme(state.appTheme());
        this.store = store;
        this.demonstration = demonstration;
        credentials = new CredentialStore(credentialHelper);
        state.profiles().forEach(p -> profiles.put(p.id(),p));
        layout = Layout.normalize(state.layout());
        lastProfile=state.lastProfile();
        saveTimer = new javax.swing.Timer(500,e -> saveAsync());
        saveTimer.setRepeats(false);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(680,440));
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(state.width(),screen.width),Math.min(state.height(),screen.height));
        setLocationRelativeTo(null);
        setBackground(Ui.BACKGROUND);
        content.setBackground(Ui.BACKGROUND);
        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(Ui.BACKGROUND);
        setContentPane(shell);
        getRootPane().setBorder(BorderFactory.createMatteBorder(1,1,1,1,Ui.LINE));
        JMenuBar menus=menus();
        JPanel tools=new JPanel(new FlowLayout(FlowLayout.LEFT,3,0)); tools.setOpaque(false);
        JButton addPane=new JButton(command("Add pane")); addPane.setText("+ Pane");
        addPane.setToolTipText("Add the next pane (maximum 8; Ctrl+Alt+H)");
        addPane.getAccessibleContext().setAccessibleName("Add pane");
        JButton find=new JButton(command("Search commands...")); find.setText("Commands");
        find.setToolTipText("Search commands (Ctrl+Alt+K)");
        find.getAccessibleContext().setAccessibleName("Search commands");
        for (JButton b : List.of(addPane,find)) { b.setBorder(BorderFactory.createEmptyBorder(4,7,4,7)); tools.add(b); }
        shell.add(new WindowChrome(this,menus,tools),BorderLayout.NORTH);
        shell.add(content,BorderLayout.CENTER);
        buildTree();
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
    public void selectProfile(SessionPane pane,Profile profile) {
        if (pane.busy()) throw new IllegalStateException("Disconnect before selecting a preset.");
        lastProfile=profile.id(); pane.updateProfile(profile); changed();
    }
    public void rememberProfile(Profile profile) { lastProfile=profile.id(); changed(); }
    public boolean hasPane(UUID id) { return panes.containsKey(id); }
    public List<SessionPane> sessionPanes() { return layout.leaves().stream().map(l -> panes.get(l.pane())).toList(); }
    public List<AppCommand> commands() { return List.copyOf(commands); }
    private AppCommand command(String name) { return commands.stream().filter(c -> c.title.equals(name)).findFirst().orElseThrow(); }
    public void refreshCommands() { commands.forEach(AppCommand::refresh); }
    public void themeChanged() {
        content.setBackground(Ui.BACKGROUND);
        if (getContentPane()!=null) getContentPane().setBackground(Ui.BACKGROUND);
        getRootPane().setBorder(BorderFactory.createLineBorder(Ui.LINE));
    }
    public void applyTheme(AppTheme theme) { Ui.applyTheme(theme); changed(); }
    public void commandPalette() {
        SessionPane target=active;
        CommandPalette palette=new CommandPalette(this); palette.setVisible(true);
        if (target!=null && panes.containsKey(target.id())) { activate(target); target.focusTerminal(); }
        if (palette.chosen()!=null) palette.chosen().actionPerformed(new ActionEvent(this,ActionEvent.ACTION_PERFORMED,"palette"));
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
        active = pane;
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
        content.revalidate();
        content.repaint();
        refreshCommands();
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
    private StateStore.State state() { return new StateStore.State(profileList(),currentLayout(),getWidth(),getHeight(),Ui.theme(),lastProfile); }
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
    }
    public void dock(UUID source, UUID target, Layout.Dock dock) {
        layout = currentLayout().move(source,target,Layout.Dock.CENTER);
        buildTree();
        active = panes.get(source);
        activate(active);
        SwingUtilities.invokeLater(active::focusTerminal);
        changed();
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
            int length = (split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? split.getWidth() : split.getHeight()) - split.getDividerSize();
            split.setDividerLocation(Math.max(.05,Math.min(.95,split.getDividerLocation() / (double) Math.max(1,length) + delta)));
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
    private static boolean connectionChanged(Profile a, Profile b) {
        return !a.host().equals(b.host()) || a.port() != b.port() || !a.device().equals(b.device())
                || !a.username().equals(b.username()) || a.automaticSignOn() != b.automaticSignOn() || !a.codePage().equals(b.codePage());
    }
    public void putProfile(Profile value) {
        Profile old = profiles.put(value.id(),value);
        for (SessionPane pane : panes.values()) {
            if (pane.profile().id().equals(value.id())) {
                if (old != null && connectionChanged(old,value)) pane.disconnect();
                pane.updateProfile(value);
            }
            pane.refreshPresets();
        }
        changed();
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
    private void hostKeys() {
        JComboBox<KeyMnemonic> keys = new JComboBox<>(Keymap.hostActions().toArray(KeyMnemonic[]::new));
        keys.setSelectedItem(KeyMnemonic.ENTER);
        if (Ui.form(this,"Send host key",keys,"Send")) active.send((KeyMnemonic) keys.getSelectedItem());
        active.focusTerminal();
    }
    public void appearance() {
        JPanel form=new JPanel(new GridLayout(0,2,12,12));
        JComboBox<AppTheme> themes=new JComboBox<>(AppTheme.values()); themes.setSelectedItem(Ui.theme());
        JLabel application=new JLabel("Application theme"); application.setLabelFor(themes);
        form.add(application); form.add(themes);
        themes.addActionListener(e -> applyTheme((AppTheme)themes.getSelectedItem()));
        if (active!=null) {
            SessionPane pane=active;
            JComboBox<String> palettes=new JComboBox<>(Profile.THEMES.toArray(String[]::new));
            JComboBox<String> effects=new JComboBox<>(Profile.EFFECTS.toArray(String[]::new));
            palettes.setSelectedItem(pane.profile().theme()); effects.setSelectedItem(pane.profile().effect());
            JLabel palette=new JLabel("Host palette"), effect=new JLabel("Text effect (opt-in)");
            palette.setLabelFor(palettes); effect.setLabelFor(effects);
            form.add(palette); form.add(palettes); form.add(effect); form.add(effects);
            ActionListener display=e -> putProfile(pane.profile().withDisplay((String)palettes.getSelectedItem(),(String)effects.getSelectedItem()));
            palettes.addActionListener(display); effects.addActionListener(display);
        }
        JPanel body=new JPanel(new BorderLayout(0,12));
        body.add(form,BorderLayout.CENTER); body.add(new JLabel("Changes apply immediately and are saved."),BorderLayout.SOUTH);
        Ui.present(this,"Appearance",body,"Done");
    }

    private JMenuBar menus() {
        JMenuBar bar = new JMenuBar();
        bar.setBorder(BorderFactory.createMatteBorder(0,0,1,0,Ui.LINE));
        JMenu session = menu(bar,"Session",KeyEvent.VK_S);
        action(session,"Connect / disconnect","ctrl alt R",() -> active.connect());
        action(session,"Reconnect","ctrl alt shift R",() -> active.reconnect());
        session.addSeparator();
        action(session,"Profiles...","ctrl alt P",this::profiles);
        action(session,"Switch profile...","ctrl alt O",this::quickSwitch);
        session.addSeparator();
        action(session,"Insert profile username","ctrl alt U",() -> active.paste(active.profile().username()));
        action(session,"Insert remembered password","ctrl alt shift P",() -> active.sendPassword());
        session.addSeparator();
        action(session,"Quit","ctrl alt Q",() -> close(false));
        JMenu edit=menu(bar,"Edit",KeyEvent.VK_E);
        action(edit,"Copy selection / screen","ctrl alt shift C",() -> active.view().copy());
        action(edit,"Paste into fields","ctrl alt shift V",() -> active.view().paste());
        JMenu layoutMenu = menu(bar,"Panes",KeyEvent.VK_P);
        action(layoutMenu,"Add pane","ctrl alt H",() -> split(true));
        bind("ctrl alt V",() -> command("Add pane").actionPerformed(new ActionEvent(this,ActionEvent.ACTION_PERFORMED,"add")));
        action(layoutMenu,"Swap panes...","ctrl alt D",this::dockingDialog);
        action(layoutMenu,"Close pane","ctrl alt W",this::closePane);
        layoutMenu.addSeparator();
        action(layoutMenu,"Next pane","ctrl alt RIGHT",() -> cycle(1));
        action(layoutMenu,"Previous pane","ctrl alt LEFT",() -> cycle(-1));
        action(layoutMenu,"Decrease divider","ctrl alt OPEN_BRACKET",() -> adjustDivider(-.05));
        action(layoutMenu,"Increase divider","ctrl alt CLOSE_BRACKET",() -> adjustDivider(.05));
        layoutMenu.addSeparator();
        action(layoutMenu,"Save layout to profile","ctrl alt S",this::saveProfileLayout);
        action(layoutMenu,"Restore profile layout","ctrl alt L",this::restoreProfileLayout);
        JMenu view = menu(bar,"View",KeyEvent.VK_V);
        action(view,"Search commands...","ctrl alt K",this::commandPalette);
        action(view,"Appearance...","ctrl alt T",this::appearance);
        for (AppTheme theme : AppTheme.values()) {
            AppCommand change=new AppCommand("Theme: "+theme,"Application theme; does not reconnect sessions",null,
                    () -> applyTheme(theme),() -> !closing,"appearance color scheme "+theme.name());
            commands.add(change);
        }
        commands.add(new AppCommand("Flux animated text","Flowing multicolor text; opt-in, host attributes preserved",null,
                () -> putProfile(active.profile().withDisplay(active.profile().theme(),Profile.FLUX)),
                () -> active!=null && !closing,"theme gradient rainbow animated flowing effect"));
        view.addSeparator();
        action(view,"Fit host screen","ctrl alt 0",this::fit);
        action(view,"Zoom in","ctrl alt EQUALS",() -> zoom(.1));
        action(view,"Zoom out","ctrl alt MINUS",() -> zoom(-.1));
        view.addSeparator();
        JMenu host = menu(bar,"Host",KeyEvent.VK_H);
        action(host,"Send host key...","ctrl alt F",this::hostKeys);
        for (KeyMnemonic key : List.of(KeyMnemonic.ENTER,KeyMnemonic.FIELD_EXIT,KeyMnemonic.FIELD_PLUS,KeyMnemonic.FIELD_MINUS,
                KeyMnemonic.RESET,KeyMnemonic.ATTN,KeyMnemonic.SYSREQ,KeyMnemonic.CLEAR,KeyMnemonic.HELP,KeyMnemonic.PAGE_UP,KeyMnemonic.PAGE_DOWN))
            action(host,key.name().replace('_',' '),null,() -> active.send(key));
        JMenu help = menu(bar,"Help",KeyEvent.VK_L);
        action(help,"Keyboard reference",null,() -> Ui.message(this,
                Keymap.describeDefaults() + "\n\nCtrl+Alt+M: Menu     Ctrl+Alt+P: Profiles\nCtrl+Alt+H/V: Add pane (max 8)     Ctrl+Alt+D: Swap\n"
                + "Ctrl+Alt+Left/Right: Focus pane     Ctrl+Alt+1..8: Focus pane number\n"
                + "Ctrl+Alt+[/]: Resize divider     Ctrl+Alt+0: Fit\nCtrl+Alt+Shift+C/V: Copy/paste\n"
                + "Shift+Arrows: Rectangular selection     Drag: Mouse selection\n"
                + "Ctrl+Alt+Space: Window menu     Alt+Space: Windows menu     Alt+F4: Close\n"
                + "Window Move/Size: Arrows (Shift for 1px), Enter commits, Esc cancels\n"
                + "Ctrl+Alt+K: Search commands     Ctrl+Alt+T: Appearance",
                "Keyboard reference"));
        action(help,"Compatibility and connection safety",null,() -> Ui.message(this,
                "TN5250J-based 5250 engine; only IBM-3477-FC.\nPlain TCP is not encrypted. Use only on a trusted network.\n"
                + "No real IBM i / ACS acceptance has been performed.\nEnhanced display structured fields and WEA stop the session explicitly.\n"
                + "See COMPATIBILITY.md for the demonstrated scope and acceptance checklist.\n"
                + "GPL-2.0-or-later. No warranty.", "Compatibility"));
        for (int i=1;i<=24;i++) {
            KeyMnemonic key=KeyMnemonic.valueOf("PF"+i);
            commands.add(new AppCommand("Host F"+i,"Send function key to the focused session",i<=12 ? "F"+i : "shift F"+(i-12),
                    () -> active.send(key),() -> active!=null && active.connected() && !active.screen().getOIA().isKeyBoardLocked(),
                    "PF"+i+" function key"));
        }
        bind("ctrl alt M",() -> { bar.getMenu(0).doClick(); });
        for (int i = 1; i <= 8; i++) {
            final int index = i - 1;
            bind("ctrl alt " + i,() -> {
                if (index < sessionPanes().size()) { activate(sessionPanes().get(index)); active.focusTerminal(); }
            });
        }
        return bar;
    }
    private JMenu menu(JMenuBar bar, String name, int mnemonic) {
        JMenu menu = new JMenu(name); menu.setMnemonic(mnemonic); bar.add(menu); return menu;
    }
    private void action(JMenu menu,String text,String stroke,Runnable command) {
        String aliases=switch (text) {
            case "Add pane" -> "add window new session split side by side";
            case "Swap panes..." -> "move rearrange dock";
            case "Close pane" -> "disconnect remove pane reset offline";
            case "Profiles..." -> "settings workstation host device credentials";
            case "Search commands..." -> "command palette find action search";
            case "Appearance..." -> "settings theme palette color effects glow prism flux animated gradient";
            case "Copy selection / screen" -> "clipboard copy export selected text";
            case "Paste into fields" -> "clipboard paste insert text";
            case "RESET" -> "unlock recover input error hung stuck";
            case "Reconnect" -> "retry connect restart session";
            default -> "";
        };
        AppCommand action=new AppCommand(text,menu.getText()+" / "+text,stroke,command,() -> {
            if (closing) return false;
            if (text.equals("Add pane")) return panes.size()<Layout.MAX_PANES;
            if (List.of("Profiles...","Quit","Search commands...","Appearance...","Keyboard reference",
                    "Compatibility and connection safety").contains(text)) return true;
            if (active==null) return false;
            if (text.equals("Copy selection / screen")) return active.screen()!=null;
            if (List.of("Swap panes...","Next pane","Previous pane").contains(text)) return panes.size()>1;
            if (text.equals("Restore profile layout")) return !active.profile().savedLayout().isEmpty();
            if (menu.getText().equals("Host") || text.startsWith("Insert ") || text.startsWith("Paste")) {
                return active.connected() && (!active.screen().getOIA().isKeyBoardLocked()
                        || List.of("RESET","ATTN","SYSREQ").contains(text));
            }
            return true;
        },aliases);
        commands.add(action);
        if (stroke!=null) bind(stroke,() -> action.actionPerformed(new ActionEvent(this,ActionEvent.ACTION_PERFORMED,text)));
        menu.add(new JMenuItem(action));
    }
    private void bind(String stroke,Runnable command) {
        KeyStroke key = KeyStroke.getKeyStroke(stroke);
        if (key == null) throw new IllegalArgumentException("Invalid application shortcut: " + stroke);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key,stroke);
        getRootPane().getActionMap().put(stroke,new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { command.run(); }
        });
    }
    public JPopupMenu paneMenu(SessionPane pane) {
        activate(pane);
        JPopupMenu menu = new JPopupMenu();
        for (String name : List.of("Connect / disconnect","Reconnect","Add pane",
                "Swap panes...","Close pane","Profiles...","Appearance...")) menu.add(new JMenuItem(command(name)));
        return menu;
    }
    public JPopupMenu clipboardMenu(SessionPane pane) {
        activate(pane);
        pane.focusTerminal();
        JPopupMenu menu=new JPopupMenu();
        menu.add(new JMenuItem(command("Copy selection / screen")));
        menu.add(new JMenuItem(command("Paste into fields")));
        return menu;
    }
    public void close(boolean force) {
        if (closing) return;
        saveTimer.stop();
        StateStore.State snapshot = state();
        closing = true;
        panes.values().forEach(SessionPane::dispose);
        dispose();
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
}
