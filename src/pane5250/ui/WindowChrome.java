package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import pane5250.model.ColorRole;

/** Native Swing window mechanics without a second, system-colored title/menu row. */
final class WindowChrome extends JPanel {
    static final int HEIGHT=31;
    private final JFrame window;
    private final LogoButton caption;
    private final JLabel keyboardHint=new JLabel();
    private final WindowButton maximize;
    private final JMenuBar menus;
    private final JComponent tools;
    private final JPanel left,actions,controls,dragArea;
    private final JButton menuButton;
    private NativeWindow nativeWindow;
    private Boolean compact;
    private Rectangle normalBounds;
    private Rectangle keyboardStart;
    private Component keyboardFocus;
    private boolean keyboardSizing;
    private final KeyEventDispatcher windowKeys = this::keyboardEvent;

    WindowChrome(JFrame window,JMenuBar menus,JComponent tools,Runnable toggleFlux) {
        super(new BorderLayout(8,0));
        this.window = window;
        this.menus=menus; this.tools=tools;
        caption=new LogoButton(toggleFlux);
        normalBounds = window.getBounds();
        setBackground(Ui.color(ColorRole.CHROME));
        setPreferredSize(new Dimension(300,HEIGHT));
        setBorder(Hairline.bottom());
        menus.setBorder(BorderFactory.createEmptyBorder());
        left = new JPanel(new BorderLayout(0,0));
        left.setOpaque(false);
        left.add(caption,BorderLayout.WEST);
        actions=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0)); actions.setOpaque(false);
        menuButton=Ui.iconButton("Application menus","down",this::openCompactMenu);
        menuButton.setVisible(false); actions.add(menuButton);
        actions.add(menus); actions.add(tools);
        left.add(actions,BorderLayout.CENTER);
        add(left,BorderLayout.WEST);
        dragArea = new JPanel(new BorderLayout());
        dragArea.setOpaque(false);
        dragArea.setName("window-drag-area");
        dragArea.getAccessibleContext().setAccessibleName("Window title; drag to move, double-click to maximize or restore");
        Ui.foreground(keyboardHint,ColorRole.CHROME_INACTIVE);
        dragArea.add(keyboardHint);
        installMove(dragArea);
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
            @Override public void windowClosed(WindowEvent e) { finishKeyboard(false); }
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
    void nativeRemoved() { nativeWindow=null; caption.cancelClick(); }
    @Override public void doLayout() {
        int fullTools=tools.getPreferredSize().width;
        for (Component component:tools.getComponents()) if (component instanceof JButton b) {
            String text=b.getText()==null ? "" : b.getText();
            if (b.getClientProperty("fullLabel")==null) b.putClientProperty("fullLabel",text);
            String full=(String)b.getClientProperty("fullLabel");
            FontMetrics metrics=b.getFontMetrics(b.getFont());
            fullTools+=metrics.stringWidth(full)-metrics.stringWidth(text);
            if (text.isEmpty() && !full.isEmpty() && b.getIcon()!=null) fullTools+=b.getIconTextGap();
        }
        Insets insets=getInsets();
        int fullWidth=caption.getPreferredSize().width+menus.getPreferredSize().width+fullTools
                +controls.getPreferredSize().width+2*((BorderLayout)getLayout()).getHgap()
                +insets.left+insets.right+32;
        boolean narrow=getWidth()<fullWidth;
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
            popup.add(copyMenu(menus.getMenu(i)));
        }
        popup.show(menuButton,0,menuButton.getHeight());
    }
    static JMenu copyMenu(JMenu original) {
        return copyMenu(original,null);
    }
    static JMenu copyMenu(JMenu original,Runnable after) {
        for (var listener:original.getMenuListeners()) listener.menuSelected(new javax.swing.event.MenuEvent(original));
        JMenu copy=new JMenu(original.getText());
        for (Component item:original.getMenuComponents()) {
            if (item instanceof JMenu menu) copy.add(copyMenu(menu,after));
            else if (item instanceof JMenuItem command) copy.add(copyItem(command,after));
            else if (item instanceof JSeparator) copy.addSeparator();
        }
        return copy;
    }
    private static JMenuItem copyItem(JMenuItem original) {
        return copyItem(original,null);
    }
    static JMenuItem copyItem(JMenuItem original,Runnable after) {
        if (after!=null) {
            JMenuItem copy=new JMenuItem(original.getText());
            copy.setEnabled(original.isEnabled()); copy.setAccelerator(original.getAccelerator());
            copy.addActionListener(e -> { original.doClick(0); after.run(); }); return copy;
        }
        if (original.getAction()!=null) return new JMenuItem(original.getAction());
        JMenuItem copy=original instanceof JCheckBoxMenuItem ? new JCheckBoxMenuItem(original.getText(),original.isSelected())
                : new JMenuItem(original.getText());
        copy.addActionListener(e -> original.doClick(0)); return copy;
    }
    void themeChanged() {
        setBackground(Ui.color(ColorRole.CHROME));
        setBorder(Hairline.bottom());
        keyboardHint.setForeground(Ui.color(ColorRole.CHROME_INACTIVE));
    }

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
        keyboardHint.setText(sizing ? "Size: arrows / Enter / Esc" : "Move: arrows / Enter / Esc");
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
        keyboardHint.setText("");
        if (cancel) window.setBounds(saved);
        if (keyboardFocus != null && window.isFocused()) keyboardFocus.requestFocusInWindow();
        keyboardFocus = null;
    }
    private void installMove(JComponent area) {
        MouseAdapter move = new MouseAdapter() {
            Point pressed;
            Rectangle original;
            double anchor;
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) { systemMenu().show(area,e.getX(),e.getY()); return; }
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                pressed = e.getLocationOnScreen();
                original = window.getBounds();
                anchor = (pressed.x - original.x) / (double)original.width;
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (pressed == null) return;
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
            }
        };
        area.addMouseListener(move); area.addMouseMotionListener(move);
    }

    private final class LogoButton extends JButton {
        private final javax.swing.Timer click;
        private Point pressed;
        private boolean dragged;
        private boolean keyboardFocus;
        LogoButton(Runnable toggleFlux) {
            super(new FishIcon(27,20));
            Object interval=Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");
            click=new javax.swing.Timer(interval instanceof Integer ms ? ms : 500,e -> toggleFlux.run());
            click.setRepeats(false);
            setPreferredSize(new Dimension(48,HEIGHT-1));
            setBorder(BorderFactory.createEmptyBorder());
            setContentAreaFilled(false); setOpaque(false); setFocusPainted(false);
            setToolTipText("Fish");
            getAccessibleContext().setAccessibleName("Toggle animated Flux text effect in active pane");
            addActionListener(e -> toggleFlux.run());
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) {
                    keyboardFocus=e.getCause()==FocusEvent.Cause.TRAVERSAL_FORWARD || e.getCause()==FocusEvent.Cause.TRAVERSAL_BACKWARD
                            || e.getCause()==FocusEvent.Cause.TRAVERSAL_UP || e.getCause()==FocusEvent.Cause.TRAVERSAL_DOWN;
                    repaint();
                }
                @Override public void focusLost(FocusEvent e) { keyboardFocus=false; repaint(); }
            });
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) { keyboardFocus=true; repaint(); }
            });
        }
        void cancelClick() { click.stop(); pressed=null; }
        @Override protected void processMouseEvent(MouseEvent event) {
            if (event.getID()==MouseEvent.MOUSE_PRESSED) {
                keyboardFocus=false;
                pressed=event.getPoint(); dragged=false;
                if (event.getClickCount()>1) click.stop();
            }
            super.processMouseEvent(event);
            if (event.getID()==MouseEvent.MOUSE_RELEASED) pressed=null;
        }
        @Override protected void processMouseMotionEvent(MouseEvent event) {
            if (event.getID()==MouseEvent.MOUSE_DRAGGED && pressed!=null
                    && pressed.distance(event.getPoint())>=java.awt.dnd.DragSource.getDragThreshold()) {
                dragged=true; click.stop(); getModel().setArmed(false);
            }
            super.processMouseMotionEvent(event);
        }
        @Override protected void fireActionPerformed(ActionEvent event) {
            if (EventQueue.getCurrentEvent() instanceof MouseEvent mouse) {
                if (!dragged && mouse.getClickCount()==1) click.restart();
                else click.stop();
            } else super.fireActionPerformed(event);
        }
        @Override public void removeNotify() { cancelClick(); super.removeNotify(); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g=(Graphics2D)graphics.create();
            try {
                getIcon().paintIcon(this,g,(getWidth()-getIcon().getIconWidth())/2,(getHeight()-getIcon().getIconHeight())/2);
                if (hasFocus() && keyboardFocus) {
                    g.setColor(Ui.FOCUS); g.fillRect(getWidth()/2-7,getHeight()-4,14,1);
                }
            } finally { g.dispose(); }
        }
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
            setPreferredSize(new Dimension(36,HEIGHT-1));
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
                        ? kind.equals("close") ? Ui.ERROR : Ui.HOVER : Ui.color(ColorRole.CHROME));
                g.fillRect(0,0,getWidth(),getHeight());
                g.setColor(kind.equals("close") && (getModel().isRollover() || getModel().isPressed()) ? Ui.BACKGROUND
                        : Ui.color(window.isActive() ? ColorRole.CHROME_TEXT : ColorRole.CHROME_INACTIVE));
                int x = (getWidth() - 10) / 2, y = (getHeight() - 10) / 2;
                if (kind.equals("minimize")) g.drawLine(x,y + 7,x + 9,y + 7);
                else if (kind.equals("close")) { g.drawLine(x,y,x + 9,y + 9); g.drawLine(x + 9,y,x,y + 9); }
                else if (isMaximized()) {
                    g.drawRect(x + 2,y,7,7); g.setColor(Ui.color(ColorRole.CHROME)); g.fillRect(x,y + 2,8,8);
                    g.setColor(Ui.color(ColorRole.CHROME_TEXT)); g.drawRect(x,y + 2,7,7);
                } else g.drawRect(x,y,9,9);
                if (hasFocus()) Hairline.horizontal(g,getWidth()/2.0-6,getHeight()-4,12);
            } finally { g.dispose(); }
        }
    }
}
