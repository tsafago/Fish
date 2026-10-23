package pane5250.ui;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.*;
import java.io.IOException;
import javax.accessibility.*;
import javax.swing.*;
import org.tn5250j.framework.tn5250.Screen5250;
import org.tn5250j.keyboard.KeyMnemonic;
import static org.tn5250j.TN5250jConstants.*;

public final class TerminalView extends JComponent implements Scrollable {
    private final SessionPane pane;
    private final Font face;
    private static final FontRenderContext TEXT_CONTEXT = new FontRenderContext(null,true,true);
    private static final Color SELECTION = new Color(0x4b6574);
    private static final java.util.Map<String,Color[]> PALETTES = java.util.Map.of(
            "Phosphor",palette("Phosphor"),"Cool",palette("Cool"),"Warm",palette("Warm"));
    private final double baseWidth, baseHeight, ascent;
    private RenderFrame rendered;
    private Screen5250 renderedScreen;
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
        baseWidth = face.getStringBounds("M",TEXT_CONTEXT).getWidth();
        var metrics = face.getLineMetrics("M",TEXT_CONTEXT);
        baseHeight = metrics.getHeight();
        ascent = metrics.getAscent();
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
        blink = new Timer(550, e -> {
            phase = !phase;
            if (!isShowing()) return;
            if (rendered != null && rendered.blinking) repaint();
            else if (hasFocus() && pane.screen() != null) {
                int pos = pane.screen().getCurrentPos() - 1;
                repaint((int)(originX + pos % pane.screen().getColumns() * cellWidth) - 2,
                        (int)(originY + pos / pane.screen().getColumns() * cellHeight) - 2,
                        (int)Math.ceil(cellWidth) + 5,(int)Math.ceil(cellHeight) + 5);
            }
        });
    }

    @Override public void addNotify() { super.addNotify(); blink.start(); }
    @Override public void removeNotify() { blink.stop(); super.removeNotify(); }
    public void dispose() { blink.stop(); }
    public void setFit(boolean value) { fit = value; revalidate(); repaint(); }
    public boolean isFit() { return fit; }
    public void screenChanged() { rendered = null; repaint(); }
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
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            // A bare JComponent has no UI delegate to honor its opaque-background contract.
            g.setColor(getBackground());
            g.fillRect(0,0,getWidth(),getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            Screen5250 screen = pane.screen();
            int rows = screen == null ? 24 : screen.getRows(), cols = screen == null ? 80 : screen.getColumns();
            double scale = fit ? Math.max(3.0 / 16,Math.min(28.0 / 16,
                    Math.min((getWidth() - 24) / (cols * baseWidth),(getHeight() - 20) / (rows * baseHeight))))
                    : pane.profile().zoom();
            cellWidth = baseWidth * scale;
            cellHeight = baseHeight * scale;
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
            if (rendered == null || renderedScreen != screen) {
                rendered = snapshot(screen);
                renderedScreen = screen;
            }
            Graphics2D cells = (Graphics2D)g.create();
            try {
                cells.translate(originX,originY);
                cells.scale(scale,scale);
                cells.setStroke(new BasicStroke((float)(1 / scale)));
                Color[] palette = PALETTES.get(pane.profile().theme());
                Rectangle clip = cells.getClipBounds();
                for (RenderRun run : rendered.runs) {
                    if (run.background != 0 && intersects(run,clip)) {
                        cells.setColor(palette[run.background]);
                        cells.fill(new Rectangle2D.Double(run.start * baseWidth,run.row * baseHeight,
                                (run.end - run.start) * baseWidth,baseHeight));
                    }
                }
                if (anchor >= 0 && end >= 0) {
                    int left = Math.min(anchor % cols,end % cols), top = Math.min(anchor / cols,end / cols);
                    cells.setColor(SELECTION);
                    cells.fill(new Rectangle2D.Double(left * baseWidth,top * baseHeight,
                            (Math.abs(anchor % cols - end % cols) + 1) * baseWidth,
                            (Math.abs(anchor / cols - end / cols) + 1) * baseHeight));
                }
                String effect = pane.profile().effect();
                for (RenderRun run : rendered.runs) {
                    if (!intersects(run,clip) || run.hidden
                            || ((run.flags & EXTENDED_5250_BLINK) != 0 && !phase)) continue;
                    Color foreground = palette[run.foreground];
                    if (effect.equals("Prism") && run.background == 0) {
                        float mix = (float)run.row / Math.max(1,rows - 1) * .12f;
                        foreground = new Color(Math.min(255,foreground.getRed() + (int)(mix * 80)),
                                foreground.getGreen(),Math.min(255,foreground.getBlue() + (int)(mix * 80)));
                    }
                    if (run.glyphs != null) {
                        float x = (float)(run.inkStart * baseWidth), y = (float)(run.row * baseHeight + ascent);
                        if (effect.equals("Soft glow")) {
                            cells.setColor(new Color(foreground.getRed(),foreground.getGreen(),foreground.getBlue(),45));
                            cells.drawGlyphVector(run.glyphs,x + .7f,y + .7f);
                        }
                        cells.setColor(foreground);
                        cells.drawGlyphVector(run.glyphs,x,y);
                    }
                    cells.setColor(foreground);
                    if ((run.flags & EXTENDED_5250_UNDERLINE) != 0)
                        cells.draw(new Line2D.Double(run.start * baseWidth,run.row * baseHeight + ascent + 1,
                                run.end * baseWidth,run.row * baseHeight + ascent + 1));
                    if (run.separators != null) cells.draw(run.separators);
                }
            } finally { cells.dispose(); }
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

    private boolean intersects(RenderRun run,Rectangle clip) {
        return clip == null || clip.intersects(run.start * baseWidth - 2,run.row * baseHeight - 2,
                (run.end - run.start) * baseWidth + 4,baseHeight + 4);
    }
    private RenderFrame snapshot(Screen5250 screen) {
        char[] text = plane(screen,PLANE_TEXT), colors = plane(screen,PLANE_COLOR);
        char[] flags = plane(screen,PLANE_EXTENDED), attributes = plane(screen,PLANE_IS_ATTR_PLACE);
        java.util.List<RenderRun> runs = new java.util.ArrayList<>();
        boolean blinking = false;
        int cols = screen.getColumns();
        for (int row = 0; row < screen.getRows(); row++) {
            int col = 0;
            while (col < cols) {
                int start = col, pos = row * cols + col;
                boolean hidden = attributes[pos] != 0 || (flags[pos] & EXTENDED_5250_NON_DSP) != 0;
                while (++col < cols) {
                    int next = row * cols + col;
                    if (colors[next] != colors[pos] || flags[next] != flags[pos]
                            || (attributes[next] != 0) != (attributes[pos] != 0)) break;
                }
                GlyphVector glyphs = null;
                int first = pos, last = row * cols + col;
                if (!hidden) {
                    while (first < last && text[first] <= ' ') first++;
                    while (last > first && text[last - 1] <= ' ') last--;
                    if (first < last) {
                        char[] ink = java.util.Arrays.copyOfRange(text,first,last);
                        for (int i = 0; i < ink.length; i++) if (ink[i] < 32) ink[i] = ' ';
                        glyphs = face.createGlyphVector(TEXT_CONTEXT,ink);
                        for (int i = 0; i <= glyphs.getNumGlyphs(); i++)
                            glyphs.setGlyphPosition(i,new Point2D.Double(i * baseWidth,0));
                    }
                }
                Path2D separators = null;
                if (!hidden && (flags[pos] & EXTENDED_5250_COL_SEP) != 0) {
                    separators = new Path2D.Double();
                    for (int x = start; x <= col; x++) {
                        separators.moveTo(x * baseWidth,(row + 1) * baseHeight - 3);
                        separators.lineTo(x * baseWidth,(row + 1) * baseHeight);
                    }
                }
                blinking |= !hidden && (flags[pos] & EXTENDED_5250_BLINK) != 0;
                runs.add(new RenderRun(row,start,col,Math.min(15,colors[pos] & 255),
                        Math.min(15,colors[pos] >> 8 & 255),flags[pos],hidden,glyphs,first % cols,separators));
            }
        }
        return new RenderFrame(runs,blinking);
    }
    private record RenderFrame(java.util.List<RenderRun> runs,boolean blinking) {}
    private record RenderRun(int row,int start,int end,int foreground,int background,int flags,
                             boolean hidden,GlyphVector glyphs,int inkStart,Path2D separators) {}
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
        return fit ? new Dimension(240,140) : new Dimension((int)Math.ceil(cols * baseWidth * pane.profile().zoom()) + 24,
                (int)Math.ceil(rows * baseHeight * pane.profile().zoom()) + 20);
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
