package pane5250.ui;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.*;
import java.io.IOException;
import javax.accessibility.*;
import javax.swing.*;
import org.tn5250j.framework.tn5250.Screen5250;
import org.tn5250j.keyboard.KeyMnemonic;
import pane5250.model.Profile;
import static org.tn5250j.TN5250jConstants.*;

public final class TerminalView extends JComponent implements Scrollable {
    private final SessionPane pane;
    private final Font face;
    private double cellWidth = 9, cellHeight = 18, originX = 12, originY = 10;
    private int anchor = -1, end = -1;
    private boolean dragging;
    private boolean fit = true;
    private final Timer blink;
    private boolean phase = true;

    public TerminalView(SessionPane pane) {
        this.pane = pane;
        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        String family = java.util.Arrays.asList(fonts).contains("Cascadia Mono") ? "Cascadia Mono"
                : java.util.Arrays.asList(fonts).contains("Consolas") ? "Consolas" : Font.MONOSPACED;
        face = new Font(family, Font.PLAIN, 16);
        setBackground(Ui.BACKGROUND);
        setForeground(Ui.TEXT);
        setOpaque(true);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        getAccessibleContext().setAccessibleName("5250 terminal");
        getAccessibleContext().setAccessibleDescription("Field-aware host terminal. Ctrl Alt M opens application menus.");
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { pane.activate(); repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
        addKeyListener(new KeyAdapter() {
            private boolean suppressTyped;
            @Override public void keyPressed(KeyEvent event) {
                suppressTyped = false;
                if (event.isControlDown() && event.isAltDown() && !event.isAltGraphDown()) return;
                if (event.isShiftDown() && event.getKeyCode() >= KeyEvent.VK_LEFT
                        && event.getKeyCode() <= KeyEvent.VK_DOWN) {
                    selectByKey(event.getKeyCode());
                    event.consume();
                    return;
                }
                KeyMnemonic mnemonic = pane.keys().lookup(event);
                if (mnemonic != null) {
                    suppressTyped = true;
                    pane.send(mnemonic);
                    event.consume();
                }
            }
            @Override public void keyTyped(KeyEvent event) {
                if (suppressTyped) { event.consume(); return; }
                if (event.isControlDown() && !event.isAltGraphDown() || event.isAltDown() && !event.isAltGraphDown()) return;
                char c = event.getKeyChar();
                if (c >= 32 && c != 127 && c != KeyEvent.CHAR_UNDEFINED) {
                    pane.type(c);
                    event.consume();
                }
            }
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                requestFocusInWindow();
                anchor = end = position(event.getPoint());
                dragging = false;
                repaint();
            }
            @Override public void mouseDragged(MouseEvent event) {
                end = position(event.getPoint());
                dragging = true;
                repaint();
            }
            @Override public void mouseReleased(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                if (!dragging && pane.screen() != null && pane.connected()) {
                    pane.screen().moveCursor(end);
                    anchor = end = -1;
                }
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        blink = new Timer(550, e -> { phase = !phase; if (isShowing()) repaint(); });
    }

    @Override public void addNotify() { super.addNotify(); blink.start(); }
    @Override public void removeNotify() { blink.stop(); super.removeNotify(); }
    public void dispose() { blink.stop(); }
    public void setFit(boolean value) { fit = value; revalidate(); repaint(); }
    public boolean isFit() { return fit; }
    public void resetSelection() { anchor = end = -1; repaint(); }

    public String visibleText() {
        return visibleText(pane.screen(), anchor, end);
    }

    public static String visibleText(Screen5250 screen, int anchor, int end) {
        if (screen == null) return "";
        char[] chars = plane(screen, PLANE_TEXT);
        char[] hidden = plane(screen, PLANE_EXTENDED);
        char[] attributes = plane(screen, PLANE_IS_ATTR_PLACE);
        int cols = screen.getColumns();
        int r1 = 0, r2 = screen.getRows() - 1, c1 = 0, c2 = cols - 1;
        if (anchor >= 0 && end >= 0) {
            r1 = Math.min(anchor / cols, end / cols); r2 = Math.max(anchor / cols, end / cols);
            c1 = Math.min(anchor % cols, end % cols); c2 = Math.max(anchor % cols, end % cols);
        }
        StringBuilder result = new StringBuilder();
        for (int row = r1; row <= r2; row++) {
            StringBuilder line = new StringBuilder();
            for (int col = c1; col <= c2; col++) {
                int pos = row * cols + col;
                line.append(chars[pos] >= 32 && attributes[pos] == 0
                        && (hidden[pos] & EXTENDED_5250_NON_DSP) == 0 ? chars[pos] : ' ');
            }
            result.append(line.toString().stripTrailing());
            if (row < r2) result.append('\n');
        }
        return result.toString();
    }

    public void copy() {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(visibleText()), null);
    }
    public void paste() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (data instanceof String text) pane.paste(text);
        } catch (UnsupportedFlavorException | IOException | IllegalStateException e) {
            pane.problem("Clipboard unavailable: " + e.getMessage());
        }
    }
    private void selectByKey(int key) {
        Screen5250 screen = pane.screen();
        if (screen == null) return;
        if (anchor < 0) anchor = end = screen.getCurrentPos() - 1;
        int delta = switch (key) {
            case KeyEvent.VK_LEFT -> -1; case KeyEvent.VK_RIGHT -> 1;
            case KeyEvent.VK_UP -> -screen.getColumns(); default -> screen.getColumns();
        };
        end = Math.max(0, Math.min(screen.getScreenLength() - 1, end + delta));
        repaint();
    }

    private static char[] plane(Screen5250 screen, int plane) {
        char[] data = new char[screen.getScreenLength() + 1];
        screen.GetScreen(data, data.length, plane);
        return data;
    }

    private int position(Point point) {
        int cols = pane.screen() == null ? 80 : pane.screen().getColumns();
        int rows = pane.screen() == null ? 24 : pane.screen().getRows();
        int col = Math.max(0, Math.min(cols - 1, (int) ((point.x - originX) / cellWidth)));
        int row = Math.max(0, Math.min(rows - 1, (int) ((point.y - originY) / cellHeight)));
        return row * cols + col;
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            Screen5250 screen = pane.screen();
            int rows = screen == null ? 24 : screen.getRows(), cols = screen == null ? 80 : screen.getColumns();
            FontRenderContext frc = g.getFontRenderContext();
            double baseWidth = face.getStringBounds("M", frc).getWidth() / 16;
            double baseHeight = face.getLineMetrics("M", frc).getHeight() / 16;
            double size = fit ? Math.max(3, Math.min(28, Math.min((getWidth() - 24) / cols / baseWidth,
                    (getHeight() - 20) / rows / baseHeight))) : 16 * pane.profile().zoom();
            Font font = face.deriveFont((float) size);
            g.setFont(font);
            cellWidth = font.getStringBounds("M", frc).getWidth();
            cellHeight = font.getLineMetrics("M", frc).getHeight();
            double ascent = font.getLineMetrics("M", frc).getAscent();
            originX = Math.max(12, (getWidth() - cols * cellWidth) / 2);
            originY = Math.max(10, (getHeight() - rows * cellHeight) / 2);
            if (screen == null) {
                g.setFont(new Font("Segoe UI", Font.PLAIN, 14));
                g.setColor(Ui.TEXT);
                g.drawString(pane.profile().host().isBlank() ? "No host configured" : "Session disconnected", 24, 40);
                g.setColor(Ui.MUTED);
                g.drawString("Ctrl+Alt+P  Profiles     Ctrl+Alt+R  Connect", 24, 65);
                return;
            }
            char[] text = plane(screen, PLANE_TEXT), colors = plane(screen, PLANE_COLOR);
            char[] extended = plane(screen, PLANE_EXTENDED), attributes = plane(screen, PLANE_IS_ATTR_PLACE);
            Color[] palette = palette(pane.profile().theme());
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    int pos = row * cols + col;
                    double x = originX + col * cellWidth, y = originY + row * cellHeight;
                    int fg = colors[pos] & 255, bg = colors[pos] >> 8 & 255;
                    Color foreground = palette[Math.min(15, fg)], background = palette[Math.min(15, bg)];
                    if (bg != 0) { g.setColor(background); g.fill(new Rectangle2D.Double(x,y,cellWidth,cellHeight)); }
                    if (selected(pos, cols)) {
                        g.setColor(new Color(0x4b6574)); g.fill(new Rectangle2D.Double(x,y,cellWidth,cellHeight));
                    }
                    if (attributes[pos] != 0 || (extended[pos] & EXTENDED_5250_NON_DSP) != 0) continue;
                    boolean blinking = (extended[pos] & EXTENDED_5250_BLINK) != 0;
                    if (!blinking || phase) {
                        char character = text[pos] >= 32 ? text[pos] : ' ';
                        if (character != ' ') {
                            if (pane.profile().effect().equals("Soft glow")) {
                                g.setColor(new Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue(), 45));
                                g.drawString(String.valueOf(character), (float) x + 0.7f, (float) (y + ascent) + 0.7f);
                            }
                            if (pane.profile().effect().equals("Prism") && bg == 0) {
                                float mix = (float) row / Math.max(1, rows - 1) * .12f;
                                foreground = new Color(Math.min(255, foreground.getRed() + (int) (mix * 80)),
                                        foreground.getGreen(), Math.min(255, foreground.getBlue() + (int) (mix * 80)));
                            }
                            g.setColor(foreground);
                            g.drawString(String.valueOf(character), (float) x, (float) (y + ascent));
                        }
                        g.setColor(foreground);
                        if ((extended[pos] & EXTENDED_5250_UNDERLINE) != 0)
                            g.draw(new Line2D.Double(x,y + ascent + 1,x + cellWidth,y + ascent + 1));
                        if ((extended[pos] & EXTENDED_5250_COL_SEP) != 0) {
                            g.draw(new Line2D.Double(x,y + cellHeight - 3,x,y + cellHeight));
                            g.draw(new Line2D.Double(x + cellWidth,y + cellHeight - 3,x + cellWidth,y + cellHeight));
                        }
                    }
                }
            }
            if (hasFocus() && pane.connected() && screen.isCursorActive() && phase) {
                int pos = Math.min(screen.getCurrentPos() - 1, rows * cols - 1);
                g.setColor(new Color(0xb0f0ac));
                g.draw(new Rectangle2D.Double(originX + pos % cols * cellWidth,
                        originY + pos / cols * cellHeight, Math.max(1, cellWidth - 1), Math.max(1, cellHeight - 1)));
            }
            if (hasFocus()) {
                g.setColor(Ui.FOCUS);
                g.drawRect(0,0,getWidth() - 1,getHeight() - 1);
            }
        } finally { g.dispose(); }
    }

    private boolean selected(int pos, int cols) {
        return anchor >= 0 && end >= 0 && pos / cols >= Math.min(anchor / cols, end / cols)
                && pos / cols <= Math.max(anchor / cols, end / cols)
                && pos % cols >= Math.min(anchor % cols, end % cols)
                && pos % cols <= Math.max(anchor % cols, end % cols);
    }
    private static Color[] palette(String theme) {
        int[] values = {0x101213,0x8daefa,0x9bdb98,0x84d9df,0xf69b9b,0xd8a0db,0xe3d78c,0xe4e5df,
                0xb5b9bc,0xacc7ff,0xb1edab,0xa0f0ee,0xffb4b4,0xf4b8f2,0xffeca3,0xffffff};
        if (theme.equals("Cool")) { values[2] = 0x99d4ba; values[7] = 0xddeaf2; }
        if (theme.equals("Warm")) { values[2] = 0xbcd993; values[7] = 0xf0e4cc; }
        Color[] result = new Color[values.length];
        for (int i = 0; i < values.length; i++) result[i] = new Color(values[i]);
        return result;
    }
    @Override public Dimension getPreferredSize() {
        Screen5250 screen = pane.screen();
        int cols = screen == null ? 80 : screen.getColumns(), rows = screen == null ? 24 : screen.getRows();
        return fit ? new Dimension(240,140) : new Dimension((int) (cols * 9.7 * pane.profile().zoom()) + 24,
                (int) (rows * 19 * pane.profile().zoom()) + 20);
    }
    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 20; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return 100; }
    @Override public boolean getScrollableTracksViewportWidth() { return fit; }
    @Override public boolean getScrollableTracksViewportHeight() { return fit; }
    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleTerminal();
        return accessibleContext;
    }
    protected class AccessibleTerminal extends AccessibleJComponent {
        @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.TEXT; }
    }
}
