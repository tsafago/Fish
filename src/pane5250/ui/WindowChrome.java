package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/** Native Swing window mechanics without a second, system-colored title/menu row. */
final class WindowChrome extends JPanel {
    private final JFrame window;
    private final JLabel caption = new JLabel(new FishIcon(30,26));
    private final WindowButton maximize;
    private final JMenuBar menus;
    private final JComponent tools;
    private final JPanel left,actions,controls,dragArea;
    private final JButton menuButton;
    private NativeWindow nativeWindow;
    private Boolean compact;
    private final javax.swing.Timer pulse;
    private long pulseStart;
    private Rectangle normalBounds;
    private Rectangle keyboardStart;
    private Component keyboardFocus;
    private boolean keyboardSizing;
    private final KeyEventDispatcher windowKeys = this::keyboardEvent;

    WindowChrome(JFrame window,JMenuBar menus,JComponent tools) {
        super(new BorderLayout(8,0));
        this.window = window;
        this.menus=menus; this.tools=tools;
        pulse=new javax.swing.Timer(50,e -> {
            double phase=(System.nanoTime()-pulseStart)/1_400_000_000.0;
            ((FishIcon)caption.getIcon()).pulse(Math.min(1,phase)); caption.repaint();
            if (phase>=1) { ((javax.swing.Timer)e.getSource()).stop(); ((FishIcon)caption.getIcon()).pulse(0); caption.repaint(); }
        });
        normalBounds = window.getBounds();
        setBackground(Ui.PANEL);
        setPreferredSize(new Dimension(300,28));
        setBorder(BorderFactory.createMatteBorder(0,0,1,0,Ui.LINE));
        menus.setBorder(BorderFactory.createEmptyBorder());
        left = new JPanel(new BorderLayout(0,0));
        left.setOpaque(false);
        caption.setBorder(BorderFactory.createEmptyBorder(0,8,0,8));
        caption.setForeground(Ui.MUTED);
        caption.setFont(new Font("Segoe UI",Font.PLAIN,11));
        caption.setToolTipText(window.getTitle());
        caption.getAccessibleContext().setAccessibleName("Fish");
        left.add(caption,BorderLayout.WEST);
        actions=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0)); actions.setOpaque(false);
        menuButton=Ui.iconButton("Application menus","down",this::openCompactMenu);
        menuButton.setVisible(false); actions.add(menuButton);
        actions.add(menus); actions.add(tools);
        left.add(actions,BorderLayout.CENTER);
        add(left,BorderLayout.WEST);
        dragArea = new JPanel();
        dragArea.setOpaque(false);
        dragArea.setName("window-drag-area");
        dragArea.getAccessibleContext().setAccessibleName("Window title; drag to move, double-click to maximize or restore");
        installMove(dragArea);
        installMove(caption);
        add(dragArea,BorderLayout.CENTER);
        controls = new JPanel(new GridLayout(1,3,0,0));
        controls.setOpaque(false);
        controls.add(new WindowButton("Minimize window","minimize",() -> window.setExtendedState(window.getExtendedState() | Frame.ICONIFIED)));
        maximize = new WindowButton("Maximize window","maximize",this::toggleMaximize);
        controls.add(maximize);
        controls.add(new WindowButton("Close window","close",this::requestClose));
        add(controls,BorderLayout.EAST);
        window.setGlassPane(new ResizeEdges());
        window.getGlassPane().setVisible(true);
        window.getRootPane().registerKeyboardAction(e -> requestClose(),
                KeyStroke.getKeyStroke("alt F4"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        window.addWindowStateListener(e -> {
            maximize.getAccessibleContext().setAccessibleName(isMaximized() ? "Restore window" : "Maximize window");
            maximize.setToolTipText(isMaximized() ? "Restore window" : "Maximize window");
            maximize.repaint();
        });
        window.addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent e) { rememberNormal(); }
            @Override public void componentResized(ComponentEvent e) { rememberNormal(); }
        });
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { finishKeyboard(false); pulse.stop(); }
        });
        window.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) { finishKeyboard(true); }
        });
    }
    void installNative() {
        if (nativeWindow!=null) return;
        nativeWindow=new NativeWindow(window);
        window.getGlassPane().setVisible(false);
        revalidate();
    }
    @Override public void doLayout() {
        boolean narrow=getWidth()<700;
        if (compact==null || compact!=narrow) {
            compact=narrow; menus.setVisible(!narrow); menuButton.setVisible(narrow);
            for (Component component:tools.getComponents()) if (component instanceof JButton b) {
                if (b.getClientProperty("fullLabel")==null) b.putClientProperty("fullLabel",b.getText());
                b.setText(narrow ? "" : (String)b.getClientProperty("fullLabel"));
            }
        }
        super.doLayout(); left.doLayout(); actions.doLayout(); controls.doLayout();
        if (nativeWindow!=null) nativeWindow.regions(SwingUtilities.convertRectangle(dragArea.getParent(),dragArea.getBounds(),window),
                SwingUtilities.convertRectangle(maximize.getParent(),maximize.getBounds(),window));
    }
    void openMenus() { if (menus.isVisible()) menus.getMenu(0).doClick(); else openCompactMenu(); }
    void showSystemMenu() { systemMenu().show(this,0,getHeight()); }
    private void openCompactMenu() {
        JPopupMenu popup=new JPopupMenu();
        for (int i=0;i<menus.getMenuCount();i++) {
            JMenu original=menus.getMenu(i), copy=new JMenu(original.getText());
            for (Component item:original.getMenuComponents()) {
                if (item instanceof JMenuItem command) copy.add(new JMenuItem(command.getAction()));
                else if (item instanceof JSeparator) copy.addSeparator();
            }
            popup.add(copy);
        }
        popup.show(menuButton,0,menuButton.getHeight());
    }
    void themeChanged() { setBackground(Ui.PANEL); caption.setForeground(Ui.MUTED); setBorder(BorderFactory.createMatteBorder(0,0,1,0,Ui.LINE)); }

    private boolean isMaximized() { return (window.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0; }
    private void rememberNormal() {
        if (window.getExtendedState() == Frame.NORMAL) normalBounds = window.getBounds();
    }
    private void requestClose() {
        finishKeyboard(false);
        window.dispatchEvent(new WindowEvent(window,WindowEvent.WINDOW_CLOSING));
    }
    void toggleMaximize() {
        if (nativeWindow!=null) { nativeWindow.toggleMaximize(); return; }
        if (isMaximized()) {
            Rectangle restored = new Rectangle(normalBounds);
            window.setExtendedState(Frame.NORMAL);
            window.setBounds(restored);
        } else {
            rememberNormal();
            GraphicsConfiguration display = window.getGraphicsConfiguration();
            Rectangle bounds = new Rectangle(display.getBounds());
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(display);
            bounds.x += insets.left; bounds.y += insets.top;
            bounds.width -= insets.left + insets.right; bounds.height -= insets.top + insets.bottom;
            window.setMaximizedBounds(bounds);
            window.setExtendedState(Frame.MAXIMIZED_BOTH);
        }
    }
    private JPopupMenu systemMenu() {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        JPopupMenu menu = new JPopupMenu();
        item(menu,"Restore",KeyEvent.VK_R,this::toggleMaximize).setEnabled(isMaximized());
        item(menu,"Move",KeyEvent.VK_M,() -> startKeyboard(false,focus)).setEnabled(!isMaximized());
        item(menu,"Size",KeyEvent.VK_S,() -> startKeyboard(true,focus)).setEnabled(!isMaximized());
        item(menu,"Minimize",KeyEvent.VK_N,() -> window.setExtendedState(window.getExtendedState() | Frame.ICONIFIED));
        item(menu,"Maximize",KeyEvent.VK_X,this::toggleMaximize).setEnabled(!isMaximized());
        menu.addSeparator();
        item(menu,"Close",KeyEvent.VK_C,this::requestClose);
        return menu;
    }
    private static JMenuItem item(JPopupMenu menu,String title,int mnemonic,Runnable action) {
        JMenuItem item = new JMenuItem(title,mnemonic);
        item.addActionListener(e -> action.run());
        menu.add(item);
        return item;
    }
    private void startKeyboard(boolean sizing,Component focus) {
        finishKeyboard(false);
        keyboardStart = window.getBounds();
        keyboardSizing = sizing;
        keyboardFocus = focus;
        caption.setText(sizing ? "Size: arrows / Enter / Esc" : "Move: arrows / Enter / Esc");
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(windowKeys);
    }
    private boolean keyboardEvent(KeyEvent event) {
        if (keyboardStart == null || SwingUtilities.getWindowAncestor(event.getComponent()) != window) return false;
        if (event.getID() == KeyEvent.KEY_PRESSED) {
            int key = event.getKeyCode();
            if (key == KeyEvent.VK_ESCAPE || key == KeyEvent.VK_ENTER) finishKeyboard(key == KeyEvent.VK_ESCAPE);
            else if (key >= KeyEvent.VK_LEFT && key <= KeyEvent.VK_DOWN) {
                int step = event.isShiftDown() ? 1 : 10;
                int dx = key == KeyEvent.VK_LEFT ? -step : key == KeyEvent.VK_RIGHT ? step : 0;
                int dy = key == KeyEvent.VK_UP ? -step : key == KeyEvent.VK_DOWN ? step : 0;
                Rectangle bounds = window.getBounds();
                if (keyboardSizing) {
                    bounds.width = Math.max(window.getMinimumSize().width,bounds.width + dx);
                    bounds.height = Math.max(window.getMinimumSize().height,bounds.height + dy);
                } else { bounds.x += dx; bounds.y += dy; }
                window.setBounds(bounds);
            }
        }
        event.consume();
        return true;
    }
    private void finishKeyboard(boolean cancel) {
        if (keyboardStart == null) return;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(windowKeys);
        Rectangle saved = keyboardStart;
        keyboardStart = null;
        caption.setText("");
        if (cancel) window.setBounds(saved);
        if (keyboardFocus != null && window.isFocused()) keyboardFocus.requestFocusInWindow();
        keyboardFocus = null;
    }
    private void installMove(JComponent area) {
        MouseAdapter move = new MouseAdapter() {
            Point pressed;
            Rectangle original;
            double anchor;
            boolean dragged;
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) { systemMenu().show(area,e.getX(),e.getY()); return; }
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                pressed = e.getLocationOnScreen();
                dragged=false;
                original = window.getBounds();
                anchor = (pressed.x - original.x) / (double)original.width;
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (pressed == null) return;
                dragged=true;
                if (nativeWindow!=null) { pressed=null; nativeWindow.beginMove(); return; }
                Point point = e.getLocationOnScreen();
                if (isMaximized()) {
                    toggleMaximize();
                    window.setLocation(point.x - (int)(window.getWidth() * anchor),point.y - 12);
                    pressed = point; original = window.getBounds();
                } else window.setLocation(original.x + point.x - pressed.x,original.y + point.y - pressed.y);
            }
            @Override public void mouseReleased(MouseEvent e) { pressed = null; }
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) toggleMaximize();
                else if (area==caption && !dragged && SwingUtilities.isLeftMouseButton(e)) {
                    pulseStart=System.nanoTime(); pulse.restart();
                }
            }
        };
        area.addMouseListener(move); area.addMouseMotionListener(move);
    }

    private final class ResizeEdges extends JComponent {
        private static final int WEST = 1, EAST = 2, NORTH = 4, SOUTH = 8;
        private int edge;
        private Point pressed;
        private Rectangle original;
        ResizeEdges() {
            setOpaque(false);
            setName("window-resize-edges");
            MouseAdapter resize = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) { showCursor(edges(e.getX(),e.getY())); }
                @Override public void mousePressed(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return;
                    edge = edges(e.getX(),e.getY());
                    if (edge == 0) return;
                    pressed = e.getLocationOnScreen(); original = window.getBounds();
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (pressed == null) return;
                    Point point = e.getLocationOnScreen();
                    int dx = point.x - pressed.x, dy = point.y - pressed.y;
                    Rectangle bounds = new Rectangle(original);
                    if ((edge & WEST) != 0) {
                        bounds.width = Math.max(window.getMinimumSize().width,original.width - dx);
                        bounds.x = original.x + original.width - bounds.width;
                    } else if ((edge & EAST) != 0) bounds.width = Math.max(window.getMinimumSize().width,original.width + dx);
                    if ((edge & NORTH) != 0) {
                        bounds.height = Math.max(window.getMinimumSize().height,original.height - dy);
                        bounds.y = original.y + original.height - bounds.height;
                    } else if ((edge & SOUTH) != 0) bounds.height = Math.max(window.getMinimumSize().height,original.height + dy);
                    window.setBounds(bounds);
                }
                @Override public void mouseReleased(MouseEvent e) { pressed = null; edge = 0; showCursor(edges(e.getX(),e.getY())); }
                @Override public void mouseExited(MouseEvent e) { if (pressed == null) setCursor(Cursor.getDefaultCursor()); }
            };
            addMouseListener(resize); addMouseMotionListener(resize);
        }
        private int edges(int x,int y) {
            if (isMaximized() || !window.isResizable()) return 0;
            return (x < 5 ? WEST : x >= getWidth() - 5 ? EAST : 0)
                    | (y < 5 ? NORTH : y >= getHeight() - 5 ? SOUTH : 0);
        }
        @Override public boolean contains(int x,int y) { return pressed != null || edges(x,y) != 0; }
        private void showCursor(int direction) {
            int cursor = switch (direction) {
                case WEST -> Cursor.W_RESIZE_CURSOR; case EAST -> Cursor.E_RESIZE_CURSOR;
                case NORTH -> Cursor.N_RESIZE_CURSOR; case SOUTH -> Cursor.S_RESIZE_CURSOR;
                case WEST | NORTH -> Cursor.NW_RESIZE_CURSOR; case EAST | NORTH -> Cursor.NE_RESIZE_CURSOR;
                case WEST | SOUTH -> Cursor.SW_RESIZE_CURSOR; case EAST | SOUTH -> Cursor.SE_RESIZE_CURSOR;
                default -> Cursor.DEFAULT_CURSOR;
            };
            setCursor(Cursor.getPredefinedCursor(cursor));
        }
    }

    private final class WindowButton extends JButton {
        private final String kind;
        WindowButton(String accessibleName,String kind,Runnable action) {
            this.kind = kind;
            getAccessibleContext().setAccessibleName(accessibleName);
            setToolTipText(accessibleName);
            setPreferredSize(new Dimension(36,27));
            setBorder(BorderFactory.createEmptyBorder());
            setContentAreaFilled(false);
            setRolloverEnabled(true);
            setFocusable(true);
            setFocusPainted(false);
            addActionListener(e -> action.run());
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create();
            try {
                Ui.quality(g);
                g.setColor(getModel().isRollover() || getModel().isPressed()
                        ? kind.equals("close") ? Ui.ERROR : Ui.HOVER : Ui.PANEL);
                g.fillRect(0,0,getWidth(),getHeight());
                g.setColor(kind.equals("close") && (getModel().isRollover() || getModel().isPressed()) ? Ui.BACKGROUND : Ui.TEXT);
                int x = (getWidth() - 10) / 2, y = (getHeight() - 10) / 2;
                if (kind.equals("minimize")) g.drawLine(x,y + 7,x + 9,y + 7);
                else if (kind.equals("close")) { g.drawLine(x,y,x + 9,y + 9); g.drawLine(x + 9,y,x,y + 9); }
                else if (isMaximized()) {
                    g.drawRect(x + 2,y,7,7); g.setColor(Ui.PANEL); g.fillRect(x,y + 2,8,8);
                    g.setColor(Ui.TEXT); g.drawRect(x,y + 2,7,7);
                } else g.drawRect(x,y,9,9);
                if (hasFocus()) { g.setColor(Ui.FOCUS); g.drawRect(3,3,getWidth() - 7,getHeight() - 7); }
            } finally { g.dispose(); }
        }
    }
}
