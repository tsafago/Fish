package pane5250.ui;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.dnd.DnDConstants;
import java.awt.event.*;
import java.io.IOException;
import java.util.*;
import javax.swing.*;
import org.tn5250j.Session5250;
import org.tn5250j.event.ScreenListener;
import org.tn5250j.framework.tn5250.Screen5250;
import org.tn5250j.framework.tn5250.ScreenField;
import org.tn5250j.keyboard.KeyMnemonic;
import pane5250.model.*;
import pane5250.net.NetworkSession;
import static org.tn5250j.TN5250jConstants.*;

public final class SessionPane extends JPanel {
    private final Workspace workspace;
    private final UUID id;
    private Profile profile;
    private Keymap keys;
    private final JPanel header = new JPanel(new BorderLayout(8,0));
    private final JPanel headerControls = new JPanel(new BorderLayout(3,0));
    private final JPanel footer = new JPanel(new BorderLayout(6,0));
    private final JButton reset,close;
    private final JLabel title = new JLabel(), connection = new JLabel(), oia = new JLabel("Disconnected");
    private final TerminalView view;
    private final JPanel body=new JPanel(new CardLayout()),empty=new JPanel(new GridBagLayout());
    private final JPanel loading=new JPanel(new GridBagLayout());
    private final JComponent dragZone=new JComponent() {
        @Override public boolean contains(int x,int y) {
            if (!super.contains(x,y)) return false;
            for (JComponent control:java.util.List.of(close,reset,noticeOverlay))
                if (control.isVisible() && control.getParent()==stage && control.getBounds().contains(x,y)) return false;
            Component target=SwingUtilities.getDeepestComponentAt(body,x,y);
            if (target instanceof AbstractButton || target instanceof JComboBox<?> || target instanceof javax.swing.text.JTextComponent) return false;
            return true;
        }
    };
    private final JLayeredPane stage=new JLayeredPane() {
        @Override public void doLayout() {
            body.setBounds(0,0,getWidth(),getHeight());
            dragZone.setBounds(0,0,getWidth(),Math.min(getHeight(),close.getPreferredSize().height));
            if (close.getParent()==this) {
                Dimension size=close.getPreferredSize();
                close.setBounds(Math.max(0,getWidth()-size.width),0,Math.min(size.width,getWidth()),size.height);
            }
            if (reset.getParent()==this) {
                Dimension size=reset.getPreferredSize();
                boolean bottom=screen()==null || screen().getCurrentRow()<screen().getRows()/2;
                int top=close.getParent()==this && close.isVisible() ? close.getHeight()+4 : 10;
                reset.setBounds(Math.max(4,getWidth()-size.width-10),bottom ? Math.max(4,getHeight()-size.height-10) : top,
                        Math.min(size.width,Math.max(0,getWidth()-8)),size.height);
            }
            Dimension size=noticeOverlay.getPreferredSize();
            int reserved=close.getParent()==this && close.isVisible() ? close.getWidth() : 0;
            noticeOverlay.setBounds(8,8,Math.max(0,Math.min(size.width,getWidth()-16-reserved)),size.height);
        }
    };
    private final JLabel noticeOverlay=new JLabel();
    private final javax.swing.Timer noticeClock=new javax.swing.Timer(6500,e -> noticeOverlay.setVisible(false));
    private final ConnectionSpinner spinner=new ConnectionSpinner();
    private final JComboBox<Profile> presets=new JComboBox<>();
    private final JButton connectButton;
    private boolean updatingPresets;
    private NetworkSession network;
    private Session5250 core;
    private boolean connecting, disposed;
    private int credentialOperations;
    private long generation;
    private Layout.Dock preview;
    private String notice = "Disconnected";
    private String shownCard="";

    public SessionPane(Workspace workspace, UUID id, Profile profile) {
        super(new BorderLayout());
        this.workspace = workspace;
        this.id = id;
        this.profile = profile;
        refreshKeys();
        setMinimumSize(new Dimension(70,70));
        setBorder(new javax.swing.border.AbstractBorder() {
            @Override public Insets getBorderInsets(Component c) {
                int size=workspace.appearanceSettings().outline() ? 1 : 0; return new Insets(size,size,size,size);
            }
            @Override public Insets getBorderInsets(Component c,Insets insets) {
                int size=workspace.appearanceSettings().outline() ? 1 : 0; insets.set(size,size,size,size); return insets;
            }
            @Override public void paintBorder(Component c,Graphics graphics,int x,int y,int width,int height) {
                if (!workspace.appearanceSettings().outline() || workspace.active()!=SessionPane.this) return;
                Container shell=workspace.getContentPane();
                Rectangle bounds=SwingUtilities.convertRectangle(getParent(),getBounds(),shell);
                boolean frame=workspace.appearanceSettings().windowOutline();
                Hairline.rectangle(graphics,x,y,width,height,false,
                        !frame && bounds.x==0,!frame && bounds.y+height==shell.getHeight(),
                        !frame && bounds.x+width==shell.getWidth());
            }
        });
        setBackground(Ui.BACKGROUND);
        header.setBackground(Ui.BACKGROUND);
        header.setBorder(BorderFactory.createEmptyBorder());
        header.setPreferredSize(new Dimension(100,25));
        title.setBorder(BorderFactory.createEmptyBorder(0,8,0,0));
        title.setForeground(Ui.MUTED);
        connection.setForeground(Ui.MUTED);
        connection.setBorder(BorderFactory.createEmptyBorder(0,0,0,8));
        connection.setFont(new Font("Segoe UI",Font.PLAIN,11));
        header.add(title, BorderLayout.CENTER);
        headerControls.setOpaque(false);
        headerControls.add(connection,BorderLayout.CENTER);
        close=Ui.iconButton("Close pane "+profile.name(),"close",() -> workspace.closePane(this));
        close.setToolTipText("Close and disconnect; keep the last pane");
        headerControls.add(close,BorderLayout.EAST);
        header.add(headerControls, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        view = new TerminalView(this);
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Ui.BACKGROUND);
        body.add(scroll,"terminal");
        JPanel choices=new JPanel(new BorderLayout(8,0)) {
            @Override public Dimension getPreferredSize() {
                Dimension size=super.getPreferredSize(); size.width=Math.max(265,size.width); return size;
            }
            @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        };
        choices.setOpaque(false);
        choices.setMinimumSize(new Dimension(40,30));
        presets.setMinimumSize(new Dimension(25,30));
        presets.getAccessibleContext().setAccessibleName("Preset");
        presets.setToolTipText("Workstation preset");
        presets.addActionListener(e -> {
            if (!updatingPresets && !busy() && presets.getSelectedItem() instanceof Profile selected) {
                activate(); workspace.selectProfile(this,selected);
            }
        });
        presets.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { activate(); }
        });
        connectButton=Ui.iconButton("Connect to selected preset","connect",() -> { activate(); connect(); });
        connectButton.setPreferredSize(new Dimension(32,30)); connectButton.setContentAreaFilled(true);
        choices.add(presets,BorderLayout.CENTER); choices.add(connectButton,BorderLayout.EAST);
        empty.setLayout(new BoxLayout(empty,BoxLayout.X_AXIS));
        empty.setBorder(BorderFactory.createEmptyBorder(0,8,0,8));
        empty.add(Box.createHorizontalGlue()); empty.add(choices); empty.add(Box.createHorizontalGlue());
        body.add(empty,"offline");
        loading.add(spinner);
        body.add(loading,"connecting");
        refreshPresets();
        oia.setFont(new Font("Consolas",Font.PLAIN,11));
        oia.setForeground(Ui.MUTED);
        oia.setBorder(BorderFactory.createEmptyBorder(2,8,2,8));
        footer.add(oia,BorderLayout.CENTER);
        reset=Ui.button("Reset",KeyEvent.VK_UNDEFINED,() -> { activate(); send(KeyMnemonic.RESET); focusTerminal(); });
        reset.setToolTipText("Recover this input error without sending Enter (default key: Esc)");
        reset.getAccessibleContext().setAccessibleName("Reset input error");
        reset.setBorder(BorderFactory.createEmptyBorder(2,8,2,8));
        reset.setVisible(false); footer.add(reset,BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        stage.add(body,JLayeredPane.DEFAULT_LAYER);
        dragZone.setName("pane-top-drag-zone");
        dragZone.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        dragZone.setToolTipText("Drag onto another pane to swap");
        stage.add(dragZone,JLayeredPane.DRAG_LAYER);
        noticeOverlay.setOpaque(true); noticeOverlay.setBorder(Ui.padding(6)); noticeOverlay.setVisible(false);
        stage.add(noticeOverlay,JLayeredPane.PALETTE_LAYER); add(stage,BorderLayout.CENTER);
        noticeClock.setRepeats(false);
        view.setFit(profile.zoom() == 1);
        installDrag();
        themeChanged();
    }

    public UUID id() { return id; }
    public Profile profile() { return profile; }
    public Screen5250 screen() { return core == null ? null : core.getScreen(); }
    public Keymap keys() { return keys; }
    public void refreshKeys() { keys=new Keymap(profile.keymap(),workspace.reassignedHostActions()); }
    public TerminalView view() { return view; }
    public Font workspaceFont() { return workspace.terminalFont(); }
    public boolean connected() { return network != null && network.isConnected(); }
    public boolean busy() { return connecting || connected(); }
    public void activate() { workspace.activate(this); }
    public JPopupMenu clipboardMenu() { return workspace.clipboardMenu(this); }
    public void focusTerminal() {
        if (connecting && close.isShowing()) close.requestFocusInWindow();
        else if (core==null) presets.requestFocusInWindow();
        else view.requestFocusInWindow();
    }
    public void refreshPresets() {
        updatingPresets=true;
        try {
            presets.setModel(new DefaultComboBoxModel<>(workspace.profileList().toArray(Profile[]::new)));
            presets.setSelectedItem(profile);
        } finally { updatingPresets=false; }
    }
    public void updateProfile(Profile value) {
        Profile previous=profile;
        profile = value;
        if (!previous.keymap().equals(value.keymap())) refreshKeys();
        refreshPresets();
        updateHeader();
        if (!previous.id().equals(value.id()) || !previous.codePage().equals(value.codePage())) view.screenChanged();
        else if (!previous.theme().equals(value.theme()) || !previous.effect().equals(value.effect())) view.displayChanged();
        if (previous.zoom()!=value.zoom()) { view.revalidate(); view.repaint(); }
    }
    public void updateHeader() {
        boolean active=workspace.active()==this;
        Appearance.PaneInfo info=workspace.appearanceSettings().info();
        title.setText(info.profile() ? profile.name() : "");
        title.setForeground(Ui.color(active ? ColorRole.HEADER_TEXT : ColorRole.HEADER_MUTED));
        Color surface=Ui.paneBackground(active && workspace.highlightActive());
        setBackground(surface);
        header.setBackground(!Ui.customTheme() && active && workspace.highlightActive() ? surface : Ui.color(ColorRole.HEADER));
        footer.setBackground(!Ui.customTheme() && active && workspace.highlightActive() ? surface : Ui.color(ColorRole.FOOTER));
        body.setBackground(surface); empty.setBackground(surface); loading.setBackground(surface);
        connection.setForeground(Ui.color(active ? ColorRole.HEADER_TEXT : ColorRole.HEADER_MUTED));
        Color terminalSurface=Ui.customTheme() && !(active && workspace.highlightActive()) ? Ui.color(ColorRole.HOST_0) : surface;
        view.setBackground(terminalSurface);
        if (view.getParent() instanceof JViewport viewport) viewport.setBackground(terminalSurface);
        java.util.List<String> parts=new ArrayList<>();
        if (info.connection()) parts.add(connecting ? "Connecting" : connected() ? "TCP" : "Offline");
        if (info.host()) parts.add(profile.host());
        if (info.device() && !profile.device().isEmpty()) parts.add(profile.device());
        connection.setText(String.join("  ",parts));
        header.setVisible(info.header() && (!title.getText().isBlank() || !connection.getText().isBlank()));
        dragZone.setVisible(!header.isVisible() && workspace.hasMultiplePanes());
        Container closeParent=header.isVisible() ? headerControls : stage;
        if (close.getParent()!=closeParent) {
            close.getParent().remove(close);
            if (header.isVisible()) headerControls.add(close,BorderLayout.EAST);
            else stage.add(close,JLayeredPane.PALETTE_LAYER);
        }
        title.setVisible(info.profile()); connection.setVisible(!connection.getText().isEmpty());
        noticeOverlay.setBackground(Ui.color(ColorRole.FOOTER)); noticeOverlay.setForeground(Ui.color(ColorRole.STATUS_ERROR));
        close.getAccessibleContext().setAccessibleName("Close pane "+profile.name());
        boolean restoreFocus=close.isFocusOwner() && shownCard.equals("connecting");
        close.setVisible(busy() || workspace.hasMultiplePanes());
        String closeKey=workspace.shortcutLabel("Close pane");
        close.setToolTipText("Close and disconnect; keep the last pane"+(closeKey.isEmpty() ? "" : " ("+closeKey+")"));
        String resetKey=workspace.shortcutLabel("RESET");
        reset.setToolTipText("Recover this input error without sending Enter"+(resetKey.isEmpty() ? "" : " ("+resetKey+")"));
        header.setToolTipText("Drag onto another pane to swap. Ctrl+Alt+D swaps with the keyboard.");
        header.setCursor(workspace.hasMultiplePanes() ? Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) : Cursor.getDefaultCursor());
        presets.setEnabled(!busy()); connectButton.setEnabled(!busy());
        String card=connecting ? "connecting" : core!=null && connected() ? "terminal" : "offline";
        if (!card.equals(shownCard)) { ((CardLayout)body.getLayout()).show(body,card); shownCard=card; }
        spinner.updateAnimation();
        if (restoreFocus && !connecting) focusTerminal();
        getAccessibleContext().setAccessibleName(profile.name());
        revalidate();
        repaint();
        updateOia();
    }
    public void themeChanged() {
        setBackground(Ui.BACKGROUND); header.setBackground(Ui.BACKGROUND); footer.setBackground(Ui.BACKGROUND);
        connection.setForeground(Ui.MUTED); oia.setForeground(Ui.MUTED);
        if (view.getParent() instanceof JViewport viewport) viewport.setBackground(Ui.BACKGROUND);
        updateHeader(); view.themeChanged(); updateOia();
    }
    public boolean recoverableInputError() {
        return connected() && screen()!=null && screen().getOIA().isKeyBoardLocked()
                && core.recoverableInputError();
    }

    public void connect() {
        if (busy()) { disconnect(); return; }
        if (profile.host().isEmpty()) { workspace.profiles(); return; }
        workspace.rememberProfile(profile);
        long token = ++generation;
        Profile connectionProfile=workspace.connectionProfile(profile);
        if (!connectionProfile.automaticSignOn()) { begin(new char[0], token,connectionProfile); return; }
        connecting = true;
        updateHeader();
        Profile target = workspace.loginProfile(profile);
        credentialOperations++;
        new SwingWorker<Optional<char[]>, Void>() {
            @Override protected Optional<char[]> doInBackground() throws IOException {
                return target.rememberPassword() ? workspace.credentials().read(target) : Optional.empty();
            }
            @Override protected void done() {
                try {
                    Optional<char[]> saved = get();
                    if (token != generation || disposed) { saved.ifPresent(p -> Arrays.fill(p, '\0')); return; }
                    char[] password;
                    if (saved.isPresent()) password = saved.get();
                    else {
                        JPasswordField input = new JPasswordField(24);
                        JPanel panel = new JPanel(new BorderLayout(0,8));
                        panel.add(new JLabel("Plain TCP: the password and session are not encrypted."), BorderLayout.NORTH);
                        panel.add(input, BorderLayout.CENTER);
                        if (!Ui.form(SessionPane.this,"Sign on as "+connectionProfile.username(),panel,"Connect")) { disconnect(); return; }
                        password = input.getPassword();
                        input.setText("");
                    }
                    if (token!=generation || disposed) { Arrays.fill(password,'\0'); return; }
                    if (target.rememberPassword() && saved.isEmpty()) rememberAndBegin(password,token,target,connectionProfile);
                    else begin(password,token,connectionProfile);
                } catch (Exception e) {
                    if (token==generation && !disposed) { problem("Credential lookup failed: " + message(e)); disconnectNetwork(); }
                    else System.err.println("Canceled credential lookup failed: "+message(e));
                } finally { credentialOperations--; }
            }
        }.execute();
    }

    private void rememberAndBegin(char[] password,long token,Profile target,Profile connectionProfile) {
        credentialOperations++;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws IOException { workspace.credentials().write(target,password); return null; }
            @Override protected void done() {
                try {
                    get();
                    if (token == generation && !disposed) begin(password,token,connectionProfile);
                    else Arrays.fill(password, '\0');
                } catch (Exception e) {
                    Arrays.fill(password, '\0');
                    if (token==generation && !disposed) { problem("Password was not remembered: " + message(e)); disconnectNetwork(); }
                    else System.err.println("Canceled password save failed: "+message(e));
                } finally { credentialOperations--; }
            }
        }.execute();
    }

    private void begin(char[] password,long token,Profile connectionProfile) {
        try {
            Map<String,String> environment = connectionProfile.environment(password);
            connecting = true;
            notice = "Connecting to " + connectionProfile.host() + ":" + connectionProfile.port() + " - plain TCP";
            network = new NetworkSession(record -> {
                if (generation == token && !disposed) {
                    core.accept(record);
                    view.revalidate();
                    view.screenChanged();
                    updateOia();
                }
            }, text -> SwingUtilities.invokeLater(() -> {
                if (generation == token && !disposed) {
                    notice = text;
                    if (text.startsWith("Connected") || text.startsWith("Disconnected")) connecting = false;
                    if (text.startsWith("Disconnected")) { generation++; disconnectNetwork(); }
                    updateHeader();
                    updateOia();
                    view.screenChanged();
                }
            }));
            core = new Session5250(connectionProfile.name(),connectionProfile.codePage(),network.output(),this::problem);
            core.getScreen().addScreenListener(new ScreenListener() {
                @Override public void onScreenChanged(int u,int r,int c,int er,int ec) { view.screenChanged(); updateOia(); }
                @Override public void onScreenSizeChanged(int r,int c) { view.resetSelection(); view.screenChanged(); view.revalidate(); }
            });
            core.getScreen().getOIA().addOIAListener((o,change) -> updateOia());
            network.connect(connectionProfile.host(),connectionProfile.port(),environment);
            environment.clear();
            updateHeader();
            updateOia();
            focusTerminal();
        } catch (RuntimeException e) {
            disconnectNetwork();
            problem("Connection failed: " + e.getMessage());
        } finally { Arrays.fill(password, '\0'); }
    }

    public void disconnect() {
        generation++;
        disconnectNetwork();
        notice = "Disconnected";
        updateOia();
    }
    private void disconnectNetwork() {
        if (network != null) network.close();
        network = null;
        connecting = false;
        core=null;
        view.resetSelection();
        updateHeader();
        view.screenChanged();
    }
    public void reconnect() { disconnect(); connect(); }
    public void dispose() { disposed = true; noticeClock.stop(); spinner.updateAnimation(); disconnect(); view.dispose(); }

    private final class ConnectionSpinner extends JComponent {
        private double angle;
        private final javax.swing.Timer clock=new javax.swing.Timer(40,e -> {
            angle=(System.nanoTime()%1_200_000_000L)*360.0/1_200_000_000L;
            repaint();
        });
        private Frame frame;
        private final WindowStateListener state=e -> updateAnimation();
        ConnectionSpinner() {
            setPreferredSize(new Dimension(36,36));
            setOpaque(false);
            getAccessibleContext().setAccessibleName("Connecting to workstation");
            addHierarchyListener(e -> {
                if ((e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0) updateAnimation();
            });
        }
        void updateAnimation() {
            if (connecting && !disposed && isShowing() && (frame==null || (frame.getExtendedState()&Frame.ICONIFIED)==0)) {
                if (!clock.isRunning()) clock.start();
            } else clock.stop();
        }
        @Override public void addNotify() {
            super.addNotify();
            if (SwingUtilities.getWindowAncestor(this) instanceof Frame parent) {
                frame=parent; frame.addWindowStateListener(state);
            }
            updateAnimation();
        }
        @Override public void removeNotify() {
            clock.stop();
            if (frame!=null) frame.removeWindowStateListener(state);
            frame=null;
            super.removeNotify();
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g=(Graphics2D)graphics.create();
            try {
                Ui.quality(g);
                double size=Math.min(getWidth(),getHeight())-8,x=(getWidth()-size)/2,y=(getHeight()-size)/2;
                g.setStroke(new BasicStroke(2.25f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                g.setColor(Ui.LINE); g.draw(new java.awt.geom.Ellipse2D.Double(x,y,size,size));
                g.setColor(Ui.FOCUS); g.draw(new java.awt.geom.Arc2D.Double(x,y,size,size,-angle,100,java.awt.geom.Arc2D.OPEN));
            } finally { g.dispose(); }
        }
        @Override public javax.accessibility.AccessibleContext getAccessibleContext() {
            if (accessibleContext==null) accessibleContext=new AccessibleJComponent() {
                @Override public javax.accessibility.AccessibleRole getAccessibleRole() {
                    return javax.accessibility.AccessibleRole.PROGRESS_BAR;
                }
                @Override public javax.accessibility.AccessibleStateSet getAccessibleStateSet() {
                    var states=super.getAccessibleStateSet();
                    if (connecting) states.add(javax.accessibility.AccessibleState.BUSY);
                    return states;
                }
            };
            return accessibleContext;
        }
    }

    public void problem(String message) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> problem(message)); return; }
        notice = message;
        updateOia();
        oia.setToolTipText(message);
        oia.setForeground(Ui.color(ColorRole.STATUS_ERROR));
        if (!footer.isVisible() || !workspace.appearanceSettings().info().status()) {
            noticeOverlay.setText(message); noticeOverlay.setToolTipText(message);
            noticeOverlay.setVisible(true); stage.doLayout(); noticeClock.restart();
        }
    }
    private void updateOia() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::updateOia); return; }
        Screen5250 screen = screen();
        Appearance.PaneInfo info=workspace.appearanceSettings().info();
        String dimensions = screen == null ? "" : (info.dimensions() ? "  " + screen.getRows() + "x" + screen.getColumns() : "")
                + (info.cursor() ? "  " + screen.getCurrentRow() + ":" + screen.getCurrentCol() : "");
        String state = screen == null || !connected() ? notice : screen.getOIA().isKeyBoardLocked()
                ? recoverableInputError() ? "Input error - Enter or Reset to continue" : "Waiting for host - input inhibited"
                : notice;
        if (screen != null && screen.getOIA().getInhibitedText() != null && screen.getOIA().isKeyBoardLocked())
            state += "  " + screen.getOIA().getInhibitedText();
        String text=(info.status() ? state : "")+dimensions
                +(screen!=null && info.insert() && screen.getOIA().isInsertMode() ? "  INS" : "");
        oia.setText(text.strip());
        boolean wasVisible=footer.isVisible();
        footer.setVisible(info.footer() && !text.isBlank());
        if (wasVisible!=footer.isVisible()) revalidate();
        oia.setToolTipText(state + dimensions);
        oia.setForeground(Ui.color(recoverableInputError() || notice.startsWith("Disconnected -") ? ColorRole.STATUS_ERROR
                : screen!=null && screen.getOIA().isKeyBoardLocked() ? ColorRole.STATUS_WAIT : ColorRole.FOOTER_TEXT));
        boolean inline=footer.isVisible();
        Container target=inline ? footer : stage;
        if (reset.getParent()!=target) {
            reset.getParent().remove(reset);
            if (inline) footer.add(reset,BorderLayout.EAST); else stage.add(reset,JLayeredPane.MODAL_LAYER);
            footer.revalidate();
        }
        reset.setVisible(recoverableInputError());
        stage.doLayout();
        workspace.refreshCommands();
    }

    public boolean send(KeyMnemonic key) {
        if (!connected()) { problem("Not connected. Ctrl+Alt+R connects this pane."); return false; }
        if (key==KeyMnemonic.ENTER && recoverableInputError()) key=KeyMnemonic.RESET;
        if (key==KeyMnemonic.RESET && screen().getOIA().isKeyBoardLocked() && !recoverableInputError()) {
            problem("Waiting for host. Reset cannot clear host-busy inhibition."); return false;
        }
        if (screen().getOIA().isKeyBoardLocked() && key != KeyMnemonic.RESET
                && key != KeyMnemonic.ATTN && key != KeyMnemonic.SYSREQ) {
            problem(recoverableInputError() ? "Input error. Press Enter or Reset; no keys were queued."
                    : "Waiting for host. Attention / System Request available; no keys were queued.");
            return false;
        }
        try {
            screen().sendKeys(key);
            notice = "Connected - IBM-3477-FC";
            updateOia();
            view.screenChanged();
            return true;
        } catch (RuntimeException e) { problem("Host key failed: " + e.getMessage()); return false; }
    }
    public boolean type(char character) {
        if (!connected()) { problem("Not connected. Ctrl+Alt+R connects this pane."); return false; }
        if (screen().getOIA().isKeyBoardLocked()) {
            problem("Input inhibited. No characters were queued.");
            return false;
        }
        if (!profile.charset().newEncoder().canEncode(character)) {
            problem("Character is not representable in code page " + profile.codePage() + ".");
            return false;
        }
        String text = character == '[' ? "[[" : character == ']' ? "]]" : String.valueOf(character);
        try {
            screen().sendKeys(text);
            notice = "Connected - IBM-3477-FC";
            updateOia(); view.screenChanged();
            return !screen().getOIA().isKeyBoardLocked();
        } catch (RuntimeException e) { problem("Input failed: " + e.getMessage()); return false; }
    }
    public boolean paste(String text) {
        if (text.length() > 32768) { problem("Paste exceeds the 32768-character safety limit; nothing pasted."); return false; }
        text = text.replace("\r\n","\n").replace('\r','\n');
        var encoder = profile.charset().newEncoder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && c != '\t' && (c < 32 || c == 127 || !encoder.canEncode(c))) {
                problem("Paste contains an unsupported character at position " + (i + 1) + "; nothing pasted.");
                return false;
            }
        }
        for (int i = 0; i < text.length(); i++) {
            if (!connected() || screen().getOIA().isKeyBoardLocked()) {
                problem("Paste stopped at input inhibition after " + i + " characters.");
                return false;
            }
            char c = text.charAt(i);
            boolean accepted = c == '\n' ? send(KeyMnemonic.NEW_LINE) : c == '\t' ? send(KeyMnemonic.TAB) : type(c);
            if (!accepted) return false;
        }
        return true;
    }
    public static void checkPasswordField(Screen5250 screen, int length) {
        if (screen == null || screen.getOIA().isKeyBoardLocked())
            throw new IllegalArgumentException("Password input is inhibited; nothing inserted.");
        int cursor = screen.getCurrentPos() - 1;
        ScreenField field = screen.getScreenFields().getCurrentField();
        if (field == null || !field.withinField(cursor) || field.isBypassField())
            throw new IllegalArgumentException("Place the cursor in an input password field; nothing inserted.");
        if (length > field.endPos() - cursor + 1)
            throw new IllegalArgumentException("Password exceeds the remaining field length; nothing inserted.");
        char[] attributes = new char[screen.getScreenLength() + 1];
        screen.GetScreen(attributes,attributes.length,PLANE_EXTENDED);
        for (int i = cursor; i < cursor + Math.max(1,length); i++) {
            if ((attributes[i] & EXTENDED_5250_NON_DSP) == 0)
                throw new IllegalArgumentException("Password field focus changed or is visible; nothing inserted.");
        }
    }
    public void sendPassword() {
        if (!connected()) { problem("Connect before inserting a password."); return; }
        if (!workspace.loginProfile(profile).rememberPassword()) { problem("This login does not use a saved password."); return; }
        char[] extended = new char[screen().getScreenLength() + 1];
        screen().GetScreen(extended, extended.length, PLANE_EXTENDED);
        if ((extended[screen().getCurrentPos() - 1] & EXTENDED_5250_NON_DSP) == 0) {
            problem("Place the cursor in a non-display password field before inserting a remembered password.");
            return;
        }
        new SwingWorker<Optional<char[]>, Void>() {
            final long token = generation;
            final Profile target = workspace.loginProfile(profile);
            @Override protected Optional<char[]> doInBackground() throws IOException { return workspace.credentials().read(target); }
            @Override protected void done() {
                try {
                    Optional<char[]> value = get();
                    if (value.isEmpty()) { problem("No remembered password for this profile."); return; }
                    char[] password = value.get();
                    try {
                        if (token == generation && connected()) {
                            Profile current=workspace.loginProfile(profile);
                            if (!current.rememberPassword() || !target.credentialTarget().equals(current.credentialTarget()))
                                throw new IllegalArgumentException("The selected login changed during lookup; nothing inserted.");
                            checkPasswordField(screen(),password.length);
                            for (char c : password)
                                if (c < 32 || c == 127) throw new IllegalArgumentException("Password contains control characters; nothing inserted.");
                            paste(new String(password));
                        }
                    } finally { Arrays.fill(password,'\0'); }
                } catch (Exception e) { problem("Password insertion failed: " + message(e)); }
            }
        }.execute();
    }

    Component tutorialConnectionTarget() { return presets.isVisible() && !busy() ? presets : this; }
    boolean connecting() { return connecting || credentialOperations>0; }
    Component tutorialDragTarget() { return header.isVisible() ? header : dragZone.isVisible() ? dragZone : this; }
    private void installDrag() {
        TransferHandler transfer = new TransferHandler() {
            @Override public int getSourceActions(JComponent c) { return MOVE; }
            @Override protected Transferable createTransferable(JComponent c) { return new StringSelection(id.toString()); }
            @Override public boolean canImport(TransferSupport support) {
                if (!support.isDrop() || !support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false;
                try {
                    UUID source = UUID.fromString((String) support.getTransferable().getTransferData(DataFlavor.stringFlavor));
                    if (!workspace.hasPane(source) || source.equals(id)) return false;
                    preview = Layout.Dock.CENTER;
                    support.setDropAction(DnDConstants.ACTION_MOVE);
                    repaint();
                    return true;
                } catch (UnsupportedFlavorException | IOException | IllegalArgumentException e) { return false; }
            }
            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    UUID source = UUID.fromString((String) support.getTransferable().getTransferData(DataFlavor.stringFlavor));
                    workspace.dock(source,id,preview);
                    workspace.clearPreviews();
                    return true;
                } catch (UnsupportedFlavorException | IOException | IllegalArgumentException e) {
                    problem("Could not swap panes: " + e.getMessage());
                    return false;
                }
            }
            @Override protected void exportDone(JComponent source, Transferable data, int action) { workspace.clearPreviews(); }
        };
        header.setTransferHandler(transfer);
        dragZone.setTransferHandler(transfer);
        view.setTransferHandler(transfer);
        setTransferHandler(transfer);
        MouseAdapter drag = new MouseAdapter() {
            Point pressed;
            @Override public void mousePressed(MouseEvent e) {
                activate();
                pressed=null;
                if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e))
                    workspace.paneMenu(SessionPane.this).show(e.getComponent(),e.getX(),e.getY());
                else if (SwingUtilities.isLeftMouseButton(e) && workspace.hasMultiplePanes()) pressed = e.getPoint();
            }
            @Override public void mouseReleased(MouseEvent e) { pressed = null; }
            @Override public void mouseDragged(MouseEvent e) {
                if (pressed != null && pressed.distance(e.getPoint()) >= java.awt.dnd.DragSource.getDragThreshold()) {
                    JComponent source=(JComponent)e.getComponent();
                    source.getTransferHandler().exportAsDrag(source,e,TransferHandler.MOVE);
                    pressed = null;
                }
            }
        };
        for (JComponent source:java.util.List.of(header,dragZone)) {
            source.addMouseListener(drag); source.addMouseMotionListener(drag);
        }
        MouseAdapter altDrag=new MouseAdapter() {
            Point pressed;
            @Override public void mousePressed(MouseEvent e) {
                pressed=SwingUtilities.isLeftMouseButton(e) && e.isAltDown() ? e.getPoint() : null;
            }
            @Override public void mouseReleased(MouseEvent e) { pressed=null; }
            @Override public void mouseExited(MouseEvent e) { if ((e.getModifiersEx()&InputEvent.BUTTON1_DOWN_MASK)==0) pressed=null; }
            @Override public void mouseDragged(MouseEvent e) {
                if (pressed!=null && pressed.distance(e.getPoint())>=java.awt.dnd.DragSource.getDragThreshold()) {
                    view.getTransferHandler().exportAsDrag(view,e,TransferHandler.MOVE); pressed=null;
                }
            }
        };
        view.addMouseListener(altDrag); view.addMouseMotionListener(altDrag);
        MouseAdapter forward=new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { header.dispatchEvent(SwingUtilities.convertMouseEvent(e.getComponent(),e,header)); }
            @Override public void mouseReleased(MouseEvent e) { header.dispatchEvent(SwingUtilities.convertMouseEvent(e.getComponent(),e,header)); }
            @Override public void mouseDragged(MouseEvent e) { header.dispatchEvent(SwingUtilities.convertMouseEvent(e.getComponent(),e,header)); }
        };
        for (JComponent label:java.util.List.of(title,connection)) {
            label.addMouseListener(forward); label.addMouseMotionListener(forward);
        }
    }
    public void clearPreview() { preview = null; repaint(); }
    @Override protected void paintChildren(Graphics graphics) {
        super.paintChildren(graphics);
        if (preview == null) return;
        Rectangle area = new Rectangle(0,0,getWidth(),getHeight());
        Graphics2D g = (Graphics2D) graphics.create();
        g.setColor(new Color(Ui.FOCUS.getRed(),Ui.FOCUS.getGreen(),Ui.FOCUS.getBlue(),45)); g.fill(area);
        Hairline.rectangle(g,area.x+1,area.y+1,area.width-2,area.height-2);
        g.setColor(Ui.TEXT);
        g.drawString("Swap panes",area.x + 12, area.y + 44);
        g.dispose();
    }
    private static String message(Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
