package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
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
    private final JPanel content = new JPanel(new BorderLayout());
    private final ExecutorService persistence = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r,"workspace-save"); thread.setDaemon(true); return thread;
    });
    private final javax.swing.Timer saveTimer;
    private Layout layout;
    private SessionPane active;
    private Component tree;
    private final boolean demonstration;
    private boolean closing;
    private Runnable afterClose = () -> {};

    public Workspace(StateStore store, StateStore.State state, Path credentialHelper, boolean demonstration) {
        super("Pane5250" + (demonstration ? " - local synthetic host" : ""));
        setUndecorated(true);
        this.store = store;
        this.demonstration = demonstration;
        credentials = new CredentialStore(credentialHelper);
        state.profiles().forEach(p -> profiles.put(p.id(),p));
        layout = state.layout();
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
        shell.add(new WindowChrome(this,menus()),BorderLayout.NORTH);
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
    public boolean hasPane(UUID id) { return panes.containsKey(id); }
    public List<SessionPane> sessionPanes() { return layout.leaves().stream().map(l -> panes.get(l.pane())).toList(); }
    public String paneName(UUID id) {
        int index = 1;
        for (Layout.Leaf leaf : layout.leaves()) {
            if (leaf.pane().equals(id)) return "A" + index;
            index++;
        }
        return "A";
    }
    public void activate(SessionPane pane) {
        active = pane;
        panes.values().forEach(SessionPane::updateHeader);
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
        if (active == null || !live.contains(active.id())) active = panes.get(layout.leaves().getFirst().pane());
        panes.values().forEach(SessionPane::updateHeader);
        content.revalidate();
        content.repaint();
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
        divider.setUI(new BasicSplitPaneUI() {
            @Override public BasicSplitPaneDivider createDefaultDivider() {
                BasicSplitPaneDivider handle = new BasicSplitPaneDivider(this) {
                    @Override public void paint(Graphics g) {
                        g.setColor(Ui.BACKGROUND);
                        g.fillRect(0,0,getWidth(),getHeight());
                        g.setColor(Ui.LINE);
                        if (getOrientation() == JSplitPane.HORIZONTAL_SPLIT)
                            g.drawLine(getWidth() / 2,0,getWidth() / 2,getHeight());
                        else g.drawLine(0,getHeight() / 2,getWidth(),getHeight() / 2);
                    }
                };
                handle.setBorder(BorderFactory.createEmptyBorder());
                return handle;
            }
        });
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
        if (component instanceof SessionPane pane) return new Layout.Leaf(pane.id(),pane.profile().id());
        JSplitPane split = (JSplitPane) component;
        int total = (split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? split.getWidth() : split.getHeight()) - split.getDividerSize();
        double ratio = total > 0 && split.getDividerLocation() >= 0 ? split.getDividerLocation() / (double) total
                : (double) split.getClientProperty("initialRatio");
        return new Layout.Split(split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT,
                Math.max(.05,Math.min(.95,ratio)),capture(split.getLeftComponent()),capture(split.getRightComponent()));
    }
    public Layout currentLayout() { layout = capture(tree); return layout; }
    private StateStore.State state() { return new StateStore.State(profileList(),currentLayout(),getWidth(),getHeight()); }
    public void saveAsync() {
        StateStore.State snapshot = state();
        persistence.submit(() -> {
            try { store.save(snapshot); }
            catch (java.io.IOException e) { SwingUtilities.invokeLater(() -> Ui.error(this,"Workspace not saved: " + e.getMessage())); }
        });
    }
    public void split(boolean horizontal) {
        if (active == null) return;
        layout = currentLayout().insert(active.id(), new Layout.Leaf(UUID.randomUUID(),active.profile().id()),
                horizontal ? Layout.Dock.RIGHT : Layout.Dock.BOTTOM);
        buildTree();
        changed();
    }
    public void dock(UUID source, UUID target, Layout.Dock dock) {
        layout = currentLayout().move(source,target,dock);
        buildTree();
        active = panes.get(source);
        activate(active);
        SwingUtilities.invokeLater(active::focusTerminal);
        changed();
    }
    public void clearPreviews() { panes.values().forEach(SessionPane::clearPreview); }
    public void closePane() {
        if (layout.leaves().size() == 1) { active.problem("The last pane stays open. Disconnect it or close the application."); return; }
        if (active.busy() && JOptionPane.showConfirmDialog(this,"Disconnect and close this pane?","Close pane",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        layout = Objects.requireNonNull(currentLayout().remove(active.id()));
        buildTree();
        changed();
    }
    public void cycle(int delta) {
        List<SessionPane> ordered = sessionPanes();
        int index = Math.floorMod(ordered.indexOf(active) + delta,ordered.size());
        activate(ordered.get(index));
        active.focusTerminal();
    }
    private void adjustDivider(double delta) {
        Component parent = active.getParent();
        if (parent instanceof JSplitPane split) {
            int length = (split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? split.getWidth() : split.getHeight()) - split.getDividerSize();
            split.setDividerLocation(Math.max(.05,Math.min(.95,split.getDividerLocation() / (double) Math.max(1,length) + delta)));
        } else active.problem("Split this pane first; there is no divider to adjust.");
    }
    private void dockingDialog() {
        if (panes.size() == 1) { active.problem("Add a split before docking. Ctrl+Alt+H or Ctrl+Alt+V."); return; }
        SessionPane source = active;
        List<SessionPane> targets = sessionPanes().stream().filter(p -> p != source).toList();
        JComboBox<String> target = new JComboBox<>(targets.stream().map(p -> paneName(p.id()) + " - " + p.profile().name()).toArray(String[]::new));
        JComboBox<Layout.Dock> direction = new JComboBox<>(Layout.Dock.values());
        JPanel form = new JPanel(new GridLayout(0,2,10,10));
        JLabel targetLabel = new JLabel("Target pane"); targetLabel.setLabelFor(target);
        JLabel directionLabel = new JLabel("Position (center swaps)"); directionLabel.setLabelFor(direction);
        form.add(targetLabel); form.add(target); form.add(directionLabel); form.add(direction);
        if (JOptionPane.showConfirmDialog(this,form,"Dock " + paneName(source.id()),JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION)
            dock(source.id(),targets.get(target.getSelectedIndex()).id(),(Layout.Dock) direction.getSelectedItem());
    }

    public void profiles() { new ProfilesDialog(this,active.profile()).setVisible(true); }
    public boolean confirmProfileChange(Profile value) {
        Profile old = profiles.get(value.id());
        if (old == null || !connectionChanged(old,value)) return true;
        boolean live = panes.values().stream().anyMatch(p -> p.profile().id().equals(value.id()) && p.busy());
        return !live || JOptionPane.showConfirmDialog(this,"Connection settings changed. Disconnect panes using this profile?",
                "Apply profile",JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }
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
        }
        changed();
    }
    public void useProfile(Profile value) {
        if (!active.profile().id().equals(value.id())) {
            if (active.busy() && JOptionPane.showConfirmDialog(this,"Disconnect this pane and switch to " + value.name() + "?",
                    "Switch profile",JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            active.disconnect();
            active.updateProfile(value);
            layout = currentLayout();
            changed();
        }
        if (!active.busy()) active.connect();
        active.focusTerminal();
    }
    private void quickSwitch() {
        JComboBox<Profile> choices = new JComboBox<>(profileList().toArray(Profile[]::new));
        choices.setSelectedItem(active.profile());
        if (JOptionPane.showConfirmDialog(this,choices,"Switch workstation",JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) useProfile((Profile) choices.getSelectedItem());
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
            Layout restored = Layout.decode(saved);
            for (Layout.Leaf leaf : restored.leaves()) {
                if (!profiles.containsKey(leaf.profile())) throw new IllegalArgumentException("Saved profile no longer exists.");
            }
            if (panes.values().stream().anyMatch(SessionPane::busy) && JOptionPane.showConfirmDialog(this,
                    "Restore the saved layout? Sessions in removed panes will disconnect.","Restore layout",
                    JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
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
        if (JOptionPane.showConfirmDialog(this,keys,"Send host key",JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) active.send((KeyMnemonic) keys.getSelectedItem());
        active.focusTerminal();
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
        JMenu layoutMenu = menu(bar,"Panes",KeyEvent.VK_P);
        action(layoutMenu,"Split left / right","ctrl alt H",() -> split(true));
        action(layoutMenu,"Split top / bottom","ctrl alt V",() -> split(false));
        action(layoutMenu,"Dock / rearrange...","ctrl alt D",this::dockingDialog);
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
        action(view,"Fit host screen","ctrl alt 0",this::fit);
        action(view,"Zoom in","ctrl alt EQUALS",() -> zoom(.1));
        action(view,"Zoom out","ctrl alt MINUS",() -> zoom(-.1));
        view.addSeparator();
        action(view,"Copy selection / screen","ctrl alt shift C",() -> active.view().copy());
        action(view,"Paste into fields","ctrl alt shift V",() -> active.view().paste());
        JMenu host = menu(bar,"Host",KeyEvent.VK_H);
        action(host,"Send host key...","ctrl alt F",this::hostKeys);
        for (KeyMnemonic key : List.of(KeyMnemonic.ENTER,KeyMnemonic.FIELD_EXIT,KeyMnemonic.FIELD_PLUS,KeyMnemonic.FIELD_MINUS,
                KeyMnemonic.RESET,KeyMnemonic.ATTN,KeyMnemonic.SYSREQ,KeyMnemonic.CLEAR,KeyMnemonic.HELP,KeyMnemonic.PAGE_UP,KeyMnemonic.PAGE_DOWN))
            action(host,key.name().replace('_',' '),null,() -> active.send(key));
        JMenu help = menu(bar,"Help",KeyEvent.VK_E);
        action(help,"Keyboard reference",null,() -> JOptionPane.showMessageDialog(this,
                Keymap.describeDefaults() + "\n\nCtrl+Alt+M: Menu     Ctrl+Alt+P: Profiles\nCtrl+Alt+H/V: Split     Ctrl+Alt+D: Dock\n"
                + "Ctrl+Alt+Left/Right: Focus pane     Ctrl+Alt+1..9: Focus pane number\n"
                + "Ctrl+Alt+[/]: Resize divider     Ctrl+Alt+0: Fit\nCtrl+Alt+Shift+C/V: Copy/paste\n"
                + "Shift+Arrows: Rectangular selection     Drag: Mouse selection\n"
                + "Ctrl+Alt+Space: Window menu     Alt+Space: Windows menu     Alt+F4: Close\n"
                + "Window Move/Size: Arrows (Shift for 1px), Enter commits, Esc cancels",
                "Keyboard reference",JOptionPane.PLAIN_MESSAGE));
        action(help,"Compatibility and connection safety",null,() -> JOptionPane.showMessageDialog(this,
                "TN5250J-based 5250 engine; only IBM-3477-FC.\nPlain TCP is not encrypted. Use only on a trusted network.\n"
                + "No real IBM i / ACS acceptance has been performed.\nEnhanced display structured fields and WEA stop the session explicitly.\n"
                + "See COMPATIBILITY.md for the demonstrated scope and acceptance checklist.\n"
                + "GPL-2.0-or-later. No warranty.", "Compatibility",JOptionPane.INFORMATION_MESSAGE));
        bind("ctrl alt M",() -> { bar.getMenu(0).doClick(); });
        for (int i = 1; i <= 9; i++) {
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
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> command.run());
        if (stroke != null) { item.setAccelerator(KeyStroke.getKeyStroke(stroke)); bind(stroke,command); }
        menu.add(item);
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
        JPopupMenu menu = new JPopupMenu();
        String[] names = {"Connect / disconnect","Reconnect","Split left / right","Split top / bottom","Dock...","Profiles..."};
        Runnable[] commands = {pane::connect,pane::reconnect,() -> split(true),() -> split(false),this::dockingDialog,this::profiles};
        for (int i = 0; i < names.length; i++) {
            JMenuItem item = new JMenuItem(names[i]); Runnable command = commands[i];
            item.addActionListener(e -> { activate(pane); command.run(); }); menu.add(item);
        }
        return menu;
    }
    public void close(boolean force) {
        if (closing) return;
        if (!force && panes.values().stream().anyMatch(SessionPane::busy) && JOptionPane.showConfirmDialog(this,
                "Disconnect sessions and close Pane5250?","Close workspace",JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        saveTimer.stop();
        StateStore.State snapshot = state();
        closing = true;
        panes.values().forEach(SessionPane::dispose);
        persistence.submit(() -> {
            try {
                store.save(snapshot);
                store.close();
                persistence.shutdown();
                SwingUtilities.invokeLater(() -> { dispose(); afterClose.run(); });
            } catch (java.io.IOException e) {
                SwingUtilities.invokeLater(() -> {
                    closing = false;
                    Ui.error(this,"Workspace could not be saved. Window kept open: " + e.getMessage());
                });
            }
        });
    }
}
