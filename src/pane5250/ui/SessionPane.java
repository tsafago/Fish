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
    private final JPanel footer = new JPanel(new BorderLayout(6,0));
    private final JButton reset,close;
    private final JLabel title = new JLabel(), connection = new JLabel(), oia = new JLabel("Disconnected");
    private final TerminalView view;
    private NetworkSession network;
    private Session5250 core;
    private boolean connecting, disposed;
    private long generation;
    private Layout.Dock preview;
    private String notice = "Disconnected";

    public SessionPane(Workspace workspace, UUID id, Profile profile) {
        super(new BorderLayout());
        this.workspace = workspace;
        this.id = id;
        this.profile = profile;
        keys = new Keymap(profile.keymap());
        setMinimumSize(new Dimension(180,110));
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
        JPanel controls=new JPanel(new BorderLayout(3,0)); controls.setOpaque(false);
        controls.add(connection,BorderLayout.CENTER);
        close=Ui.iconButton("Close pane "+profile.name(),"close",() -> workspace.closePane(this));
        close.setToolTipText("Close pane / disconnect "+profile.name()+" (Ctrl+Alt+W for focused pane)");
        controls.add(close,BorderLayout.EAST);
        header.add(controls, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        view = new TerminalView(this);
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Ui.BACKGROUND);
        add(scroll, BorderLayout.CENTER);
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
        view.setFit(profile.zoom() == 1);
        installDrag();
        themeChanged();
    }

    public UUID id() { return id; }
    public Profile profile() { return profile; }
    public Screen5250 screen() { return core == null ? null : core.getScreen(); }
    public Keymap keys() { return keys; }
    public TerminalView view() { return view; }
    public boolean connected() { return network != null && network.isConnected(); }
    public boolean busy() { return connecting || connected(); }
    public void activate() { workspace.activate(this); }
    public void focusTerminal() { view.requestFocusInWindow(); }
    public void updateProfile(Profile value) {
        profile = value;
        keys = new Keymap(value.keymap());
        updateHeader();
        view.revalidate();
        view.screenChanged();
    }
    public void updateHeader() {
        boolean active=workspace.active()==this;
        title.setText(workspace.paneName(id) + "   " + profile.name());
        title.setForeground(active ? Ui.TEXT : Ui.MUTED);
        Color surface=Ui.paneBackground(active);
        setBackground(surface); header.setBackground(surface); footer.setBackground(surface);
        connection.setForeground(active ? Ui.TEXT : Ui.MUTED);
        view.setBackground(surface);
        if (view.getParent() instanceof JViewport viewport) viewport.setBackground(surface);
        connection.setText(connecting ? "Connecting" : connected() ? "TCP " + profile.host() : "Offline");
        close.getAccessibleContext().setAccessibleName("Close pane "+profile.name());
        close.setToolTipText("Close / disconnect "+profile.name()+" (Ctrl+Alt+W for focused pane)");
        header.setToolTipText("Drag to dock. Right-click for actions. Ctrl+Alt+D docks with the keyboard.");
        getAccessibleContext().setAccessibleName(title.getText());
        setBorder(BorderFactory.createLineBorder(active ? Ui.FOCUS : Ui.BACKGROUND));
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
        long token = ++generation;
        if (!profile.automaticSignOn()) { begin(new char[0], token); return; }
        connecting = true;
        updateHeader();
        Profile target = profile;
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
                        if (!Ui.form(SessionPane.this,"Sign on as "+profile.username(),panel,"Connect")) { disconnect(); return; }
                        password = input.getPassword();
                        input.setText("");
                    }
                    if (profile.rememberPassword() && saved.isEmpty()) rememberAndBegin(password, token);
                    else begin(password, token);
                } catch (Exception e) { problem("Credential lookup failed: " + message(e)); disconnectNetwork(); }
            }
        }.execute();
    }

    private void rememberAndBegin(char[] password, long token) {
        Profile target = profile;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws IOException { workspace.credentials().write(target,password); return null; }
            @Override protected void done() {
                try {
                    get();
                    if (token == generation && !disposed) begin(password, token);
                    else Arrays.fill(password, '\0');
                } catch (Exception e) {
                    Arrays.fill(password, '\0');
                    problem("Password was not remembered: " + message(e));
                    disconnectNetwork();
                }
            }
        }.execute();
    }

    private void begin(char[] password, long token) {
        try {
            Map<String,String> environment = profile.environment(password);
            connecting = true;
            notice = "Connecting to " + profile.host() + ":" + profile.port() + " - plain TCP";
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
                    updateHeader();
                    updateOia();
                    view.screenChanged();
                }
            }));
            core = new Session5250(profile.name(), profile.codePage(), network.output(), this::problem);
            core.getScreen().addScreenListener(new ScreenListener() {
                @Override public void onScreenChanged(int u,int r,int c,int er,int ec) { view.screenChanged(); updateOia(); }
                @Override public void onScreenSizeChanged(int r,int c) { view.resetSelection(); view.screenChanged(); view.revalidate(); }
            });
            core.getScreen().getOIA().addOIAListener((o,change) -> updateOia());
            network.connect(profile.host(), profile.port(), environment);
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
        updateHeader();
        view.screenChanged();
    }
    public void reconnect() { disconnect(); connect(); }
    public void dispose() { disposed = true; disconnect(); view.dispose(); }

    public void problem(String message) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> problem(message)); return; }
        notice = message;
        oia.setText(message);
        oia.setToolTipText(message);
        oia.setForeground(Ui.ERROR);
    }
    private void updateOia() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::updateOia); return; }
        Screen5250 screen = screen();
        String dimensions = screen == null ? "" : "  " + screen.getRows() + "x" + screen.getColumns()
                + "  " + screen.getCurrentRow() + ":" + screen.getCurrentCol();
        String state = screen == null || !connected() ? notice : screen.getOIA().isKeyBoardLocked()
                ? recoverableInputError() ? "Input error - Reset to continue" : "Waiting for host - input inhibited"
                : notice + (screen.getOIA().isInsertMode() ? "  INS" : "");
        if (screen != null && screen.getOIA().getInhibitedText() != null && screen.getOIA().isKeyBoardLocked())
            state += "  " + screen.getOIA().getInhibitedText();
        oia.setText(state + dimensions);
        oia.setToolTipText(state + dimensions);
        oia.setForeground(recoverableInputError() || notice.startsWith("Disconnected -") ? Ui.ERROR
                : workspace.active()==this ? Ui.TEXT : Ui.MUTED);
        reset.setVisible(recoverableInputError());
        workspace.refreshCommands();
    }

    public boolean send(KeyMnemonic key) {
        if (!connected()) { problem("Not connected. Ctrl+Alt+R connects this pane."); return false; }
        if (screen().getOIA().isKeyBoardLocked() && key != KeyMnemonic.RESET
                && key != KeyMnemonic.ATTN && key != KeyMnemonic.SYSREQ) {
            problem(recoverableInputError() ? "Input error. Use Reset or Esc; no keys were queued."
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
        char[] extended = new char[screen().getScreenLength() + 1];
        screen().GetScreen(extended, extended.length, PLANE_EXTENDED);
        if ((extended[screen().getCurrentPos() - 1] & EXTENDED_5250_NON_DSP) == 0) {
            problem("Place the cursor in a non-display password field before inserting a remembered password.");
            return;
        }
        new SwingWorker<Optional<char[]>, Void>() {
            final long token = generation;
            final Profile target = profile;
            @Override protected Optional<char[]> doInBackground() throws IOException { return workspace.credentials().read(target); }
            @Override protected void done() {
                try {
                    Optional<char[]> value = get();
                    if (value.isEmpty()) { problem("No remembered password for this profile."); return; }
                    char[] password = value.get();
                    try {
                        if (token == generation && connected()) {
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

    private void installDrag() {
        TransferHandler transfer = new TransferHandler() {
            @Override public int getSourceActions(JComponent c) { return MOVE; }
            @Override protected Transferable createTransferable(JComponent c) { return new StringSelection(id.toString()); }
            @Override public boolean canImport(TransferSupport support) {
                if (!support.isDrop() || !support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false;
                try {
                    UUID source = UUID.fromString((String) support.getTransferable().getTransferData(DataFlavor.stringFlavor));
                    if (!workspace.hasPane(source) || source.equals(id)) return false;
                    Point point = SwingUtilities.convertPoint(support.getComponent(), support.getDropLocation().getDropPoint(), SessionPane.this);
                    preview = dockAt(point);
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
                    problem("Could not dock pane: " + e.getMessage());
                    return false;
                }
            }
            @Override protected void exportDone(JComponent source, Transferable data, int action) { workspace.clearPreviews(); }
        };
        header.setTransferHandler(transfer);
        view.setTransferHandler(transfer);
        setTransferHandler(transfer);
        MouseAdapter drag = new MouseAdapter() {
            Point pressed;
            @Override public void mousePressed(MouseEvent e) {
                activate();
                if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) workspace.paneMenu(SessionPane.this).show(header,e.getX(),e.getY());
                else pressed = e.getPoint();
            }
            @Override public void mouseReleased(MouseEvent e) { pressed = null; }
            @Override public void mouseDragged(MouseEvent e) {
                if (pressed != null && pressed.distance(e.getPoint()) > 5) {
                    header.getTransferHandler().exportAsDrag(header,e,TransferHandler.MOVE);
                    pressed = null;
                }
            }
        };
        header.addMouseListener(drag);
        header.addMouseMotionListener(drag);
        title.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { header.dispatchEvent(SwingUtilities.convertMouseEvent(title,e,header)); }
            @Override public void mouseReleased(MouseEvent e) { header.dispatchEvent(SwingUtilities.convertMouseEvent(title,e,header)); }
        });
        title.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) { header.dispatchEvent(SwingUtilities.convertMouseEvent(title,e,header)); }
        });
    }
    private Layout.Dock dockAt(Point point) {
        double x = point.x / (double) Math.max(1,getWidth()), y = point.y / (double) Math.max(1,getHeight());
        double nearest = Math.min(Math.min(x,1-x),Math.min(y,1-y));
        if (nearest > .25) return Layout.Dock.CENTER;
        if (nearest == x) return Layout.Dock.LEFT;
        if (nearest == 1-x) return Layout.Dock.RIGHT;
        return nearest == y ? Layout.Dock.TOP : Layout.Dock.BOTTOM;
    }
    public void clearPreview() { preview = null; repaint(); }
    @Override protected void paintChildren(Graphics graphics) {
        super.paintChildren(graphics);
        if (preview == null) return;
        Rectangle area = new Rectangle(0,0,getWidth(),getHeight());
        switch (preview) {
            case LEFT -> area.width /= 2;
            case RIGHT -> { area.x = area.width / 2; area.width /= 2; }
            case TOP -> area.height /= 2;
            case BOTTOM -> { area.y = area.height / 2; area.height /= 2; }
            case CENTER -> {}
        }
        Graphics2D g = (Graphics2D) graphics.create();
        g.setColor(new Color(Ui.FOCUS.getRed(),Ui.FOCUS.getGreen(),Ui.FOCUS.getBlue(),45)); g.fill(area);
        g.setColor(Ui.FOCUS); g.drawRect(area.x + 1,area.y + 1,area.width - 3,area.height - 3);
        g.setColor(Ui.TEXT);
        g.drawString(preview == Layout.Dock.CENTER ? "Swap panes" : "Dock " + preview.name().toLowerCase(Locale.ROOT),
                area.x + 12, area.y + 44);
        g.dispose();
    }
    private static String message(Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
