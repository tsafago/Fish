package pane5250.ui;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.io.IOException;
import javax.accessibility.*;
import javax.swing.*;
import org.tn5250j.framework.tn5250.Screen5250;
import org.tn5250j.keyboard.KeyMnemonic;
import pane5250.model.*;
import static org.tn5250j.TN5250jConstants.*;

public final class TerminalView extends JComponent implements Scrollable {
    private final SessionPane pane;
    private Font face;
    private static final FontRenderContext TEXT_CONTEXT = new FontRenderContext(null,
            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    private Font inkFace;
    private int fontPixels;
    private final java.util.Map<Integer,InkMetrics> fontMetrics=new java.util.HashMap<>();
    private EffectLayer litEffect,dimEffect;
    private int effectResolution;
    private Color effectBackground;
    private int effectAnchor,effectEnd;
    private double nominalWidth,nominalHeight;
    private double baseWidth,baseHeight,ascent;
    private RenderFrame rendered;
    private Screen5250 renderedScreen;
    private final java.util.Map<GlyphVector,GlyphMask> glyphMasks=new java.util.IdentityHashMap<>();
    private BufferedImage sharedInk;
    private Rectangle sharedBounds;
    private Color[] sharedPalette;
    private Color sharedBackground,sharedSelection;
    private int sharedAnchor,sharedEnd;
    private boolean sharedPhase;
    private double sharedFlux=Double.NaN;
    private double cellWidth = 9, cellHeight = 18, originX = 12, originY = 10;
    private int geometryWidth=-1,geometryHeight;
    private double geometryDpiX=1,geometryDpiY=1,geometryZoom;
    private boolean geometryFit,revealQueued;
    private int anchor = -1, end = -1;
    private boolean dragging;
    private boolean fit = true;
    private final Timer blink;
    private boolean phase = true;
    private boolean disposed;
    private java.util.function.Supplier<Clipboard> clipboardSource=() -> Toolkit.getDefaultToolkit().getSystemClipboard();
    private double fluxPhase,paintedFluxPhase=Double.NaN;
    private Paint[] fluxPaints;
    private Color[] fluxPalette;
    private Color fluxBackground,fluxSelection;
    private int fluxWidth;
    private Frame animationWindow;
    private final WindowStateListener animationState=e -> updateAnimation();
    private static final float[] FLUX_STOPS={0,.2f,.4f,.6f,.8f,1};
    private static final java.util.Set<TerminalView> ANIMATED=new java.util.HashSet<>();
    private static final Timer ANIMATION=new Timer(50,event -> {
        double time=fluxTime();
        for (var iterator=ANIMATED.iterator();iterator.hasNext();) {
            TerminalView view=iterator.next();
            if (!view.canAnimate()) { iterator.remove(); continue; }
            view.fluxPhase=time; view.repaint();
        }
        if (ANIMATED.isEmpty()) ((Timer)event.getSource()).stop();
    });

    public TerminalView(SessionPane pane) {
        this.pane = pane;
        fontChanged();
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
                if (event.getKeyCode()==KeyEvent.VK_ENTER && event.getModifiersEx()==0 && pane.recoverableInputError()) {
                    suppressTyped=true; pane.send(KeyMnemonic.RESET); event.consume(); return;
                }
                if (event.getKeyCode()==KeyEvent.VK_CONTEXT_MENU && pane.keys().lookup(event)==null) {
                    suppressTyped=true; event.consume();
                    pane.clipboardMenu().show(TerminalView.this,
                            (int)originX,(int)Math.min(getHeight()-1,originY+cellHeight));
                    return;
                }
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
            private boolean paneDrag;
            @Override public void mousePressed(MouseEvent event) {
                if (popup(event)) return;
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                requestFocusInWindow();
                paneDrag=event.isAltDown();
                if (paneDrag) return;
                anchor = end = position(event.getPoint());
                dragging = false;
                repaint();
            }
            @Override public void mouseDragged(MouseEvent event) {
                if (paneDrag) return;
                end = position(event.getPoint());
                dragging = true;
                repaint();
            }
            @Override public void mouseReleased(MouseEvent event) {
                if (popup(event)) return;
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                if (paneDrag) { paneDrag=false; return; }
                if (!dragging && pane.screen() != null && pane.connected()) {
                    pane.screen().moveCursor(end);
                    anchor = end = -1;
                }
                repaint();
            }
            private boolean popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return false;
                pane.clipboardMenu().show(TerminalView.this,event.getX(),event.getY());
                event.consume(); return true;
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
        addHierarchyListener(event -> {
            if ((event.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0) updateAnimation();
        });
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { revealCursor(); }
        });
    }

    @Override public void addNotify() {
        super.addNotify(); blink.start();
        if (SwingUtilities.getWindowAncestor(this) instanceof Frame frame) {
            animationWindow=frame; frame.addWindowStateListener(animationState);
        }
        updateAnimation();
    }
    @Override public void removeNotify() {
        stopAnimation(); blink.stop();
        if (animationWindow!=null) animationWindow.removeWindowStateListener(animationState);
        animationWindow=null;
        super.removeNotify();
    }
    public void dispose() { disposed=true; blink.stop(); stopAnimation(); glyphMasks.clear(); sharedInk=null; }
    private boolean canAnimate() {
        return !disposed && isShowing() && pane.connected() && pane.profile().effect().equals(Profile.FLUX)
                && (animationWindow==null || (animationWindow.getExtendedState()&Frame.ICONIFIED)==0);
    }
    private static double fluxTime() {
        return Math.floorMod(System.nanoTime(),8_000_000_000L)/8_000_000_000.0;
    }
    private void updateAnimation() {
        if (canAnimate()) {
            if (ANIMATED.add(this)) fluxPhase=fluxTime();
            if (!ANIMATION.isRunning()) ANIMATION.start();
        } else stopAnimation();
    }
    private void stopAnimation() {
        ANIMATED.remove(this);
        if (ANIMATED.isEmpty()) ANIMATION.stop();
    }
    public void setFit(boolean value) { fit = value; revalidate(); repaint(); revealCursor(); }
    public boolean isFit() { return fit; }
    public void screenChanged() {
        rendered=null; glyphMasks.clear(); sharedInk=null; geometryWidth=-1;
        litEffect=dimEffect=null; fluxPaints=null; updateAnimation(); repaint();
        revealCursor();
    }
    public void displayChanged() { sharedPalette=null; litEffect=dimEffect=null; fluxPaints=null; updateAnimation(); repaint(); }
    public void themeChanged() {
        setBackground(Ui.customTheme() ? Ui.color(ColorRole.HOST_0) : pane.getBackground()); screenChanged();
    }
    public void fontChanged() {
        face=pane.workspaceFont();
        nominalWidth=face.getStringBounds("M",TEXT_CONTEXT).getWidth();
        nominalHeight=face.getLineMetrics("M",TEXT_CONTEXT).getHeight();
        fontMetrics.clear(); fontPixels=0; geometryWidth=-1; configureInk(metrics(16)); revalidate();
        if (blink!=null) screenChanged();
    }
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

    public void copy() { clipboard(true,clipboardSource); }
    public void paste() { clipboard(false,clipboardSource); }
    private void clipboard(boolean copy,java.util.function.Supplier<Clipboard> source) {
        try {
            if (pane.screen()==null) { pane.problem("No host screen to "+(copy ? "copy." : "paste into.")); return; }
            if (copy) source.get().setContents(new StringSelection(visibleText()),null);
            else {
                if (!pane.connected() || pane.screen().getOIA().isKeyBoardLocked()) {
                    pane.problem("Paste unavailable: disconnected or host input inhibited."); return;
                }
                Object data=source.get().getData(DataFlavor.stringFlavor);
                if (!(data instanceof String text)) { pane.problem("Clipboard does not contain text."); return; }
                pane.paste(text);
            }
        } catch (UnsupportedFlavorException e) {
            pane.problem("Clipboard does not contain text.");
        } catch (IOException | IllegalStateException | SecurityException | HeadlessException e) {
            pane.problem("Clipboard unavailable: "+e.getMessage());
        } finally { pane.focusTerminal(); }
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
        ensureGeometry();
        int cols = pane.screen() == null ? 80 : pane.screen().getColumns();
        int rows = pane.screen() == null ? 24 : pane.screen().getRows();
        int col = Math.max(0, Math.min(cols - 1, (int) ((point.x - originX) / cellWidth)));
        int row = Math.max(0, Math.min(rows - 1, (int) ((point.y - originY) / cellHeight)));
        return row * cols + col;
    }
    private void ensureGeometry() {
        if (geometryWidth!=getWidth() || geometryHeight!=getHeight() || geometryFit!=fit || geometryZoom!=pane.profile().zoom())
            geometry(geometryDpiX,geometryDpiY);
    }
    private void revealCursor() {
        if (fit || revealQueued || !hasFocus() || pane.screen()==null) return;
        revealQueued=true;
        SwingUtilities.invokeLater(() -> {
            revealQueued=false;
            Screen5250 screen=pane.screen();
            if (disposed || fit || !hasFocus() || screen==null || !screen.isCursorActive()) return;
            ensureGeometry();
            int pos=screen.getCurrentPos()-1;
            scrollRectToVisible(new Rectangle((int)Math.floor(originX+pos%screen.getColumns()*cellWidth),
                    (int)Math.floor(originY+pos/screen.getColumns()*cellHeight),
                    (int)Math.ceil(cellWidth)+1,(int)Math.ceil(cellHeight)+1));
        });
    }
    private void geometry(double dpiX,double dpiY) {
        Screen5250 screen=pane.screen();
        int rows=screen==null ? 24 : screen.getRows(),cols=screen==null ? 80 : screen.getColumns();
        double scale=fit ? Math.max(3.0/16,Math.min(28.0/16,
                Math.min((getWidth()-24)/(cols*nominalWidth),(getHeight()-20)/(rows*nominalHeight)))) : pane.profile().zoom();
        int pixels=Math.max(1,(int)Math.floor(16*scale*dpiY));
        InkMetrics target=metrics(pixels);
        while (fit && pixels>1 && (cols*target.width>(getWidth()-24)*dpiX || rows*target.height>(getHeight()-20)*dpiY))
            target=metrics(--pixels);
        configureInk(target);
        cellWidth=baseWidth/dpiX; cellHeight=baseHeight/dpiY;
        originX=Math.rint(Math.max(12,(getWidth()-cols*cellWidth)/2)*dpiX)/dpiX;
        originY=Math.rint(Math.max(10,(getHeight()-rows*cellHeight)/2)*dpiY)/dpiY;
        geometryWidth=getWidth(); geometryHeight=getHeight(); geometryFit=fit; geometryZoom=pane.profile().zoom();
        geometryDpiX=dpiX; geometryDpiY=dpiY;
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            // A bare JComponent has no UI delegate to honor its opaque-background contract.
            g.setColor(getBackground());
            g.fillRect(0,0,getWidth(),getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            Screen5250 screen = pane.screen();
            int rows = screen == null ? 24 : screen.getRows(), cols = screen == null ? 80 : screen.getColumns();
            if (screen==null) return;
            double dpiX=Math.abs(g.getTransform().getScaleX()),dpiY=Math.abs(g.getTransform().getScaleY());
            geometry(dpiX,dpiY);
            if (rendered == null || renderedScreen != screen) {
                rendered = snapshot(screen);
                renderedScreen = screen;
                glyphMasks.clear(); sharedPalette=null;
                fluxPaints=null;
            }
            Graphics2D cells = (Graphics2D)g.create();
            try {
                cells.translate(originX,originY);
                cells.scale(1/dpiX,1/dpiY);
                AffineTransform target=cells.getTransform();
                target.setTransform(1,0,0,1,Math.rint(target.getTranslateX()),Math.rint(target.getTranslateY()));
                cells.setTransform(target);
                cells.setStroke(new BasicStroke(1));
                Color[] palette = HostPalette.colors(pane.profile().theme());
                Rectangle clip = cells.getClipBounds();
                paintBackgrounds(cells,palette,cols,clip);
                String effect = pane.profile().effect();
                if (effect.equals("None") || effect.equals(Profile.FLUX)) {
                    if (effect.equals(Profile.FLUX)) prepareFlux(palette,cols);
                    paintSharedInk(cells,palette,effect,rows,cols,phase,dpiX,dpiY);
                }
                else {
                    int resolution=1;
                    if (effectResolution!=resolution || !getBackground().equals(effectBackground)
                            || anchor!=effectAnchor || end!=effectEnd) {
                        litEffect=dimEffect=null;
                        effectResolution=resolution;
                        effectBackground=getBackground(); effectAnchor=anchor; effectEnd=end;
                    }
                    boolean lit=phase || !rendered.blinking;
                    EffectLayer layer=lit ? litEffect : dimEffect;
                    if (layer==null) {
                        layer=effectLayer(palette,effect,rows,cols,lit,resolution);
                        if (lit) litEffect=layer; else dimEffect=layer;
                    }
                    for (EffectTile tile : layer.tiles)
                        if (clip==null || clip.intersects(tile.bounds)) cells.drawImage(tile.image,tile.placement,null);
                }
            } finally { cells.dispose(); }
            if (hasFocus() && pane.connected() && screen.isCursorActive() && phase) {
                int pos = Math.min(screen.getCurrentPos() - 1, rows * cols - 1);
                g.setColor(Ui.color(ColorRole.CURSOR));
                g.draw(new Rectangle2D.Double(originX + pos % cols * cellWidth,
                        originY + pos / cols * cellHeight, Math.max(1, cellWidth - 1), Math.max(1, cellHeight - 1)));
            }
        } finally { g.dispose(); }
    }
    private record InkMetrics(Font font,double width,double height,double ascent) {}
    private InkMetrics metrics(int pixels) {
        return fontMetrics.computeIfAbsent(pixels,size -> {
            Font font=face.deriveFont((float)size);
            var lines=font.getLineMetrics("M",TEXT_CONTEXT);
            return new InkMetrics(font,Math.ceil(font.getStringBounds("M",TEXT_CONTEXT).getWidth()),
                    Math.ceil(lines.getHeight()),Math.ceil(lines.getAscent()));
        });
    }
    private void configureInk(InkMetrics metrics) {
        int pixels=metrics.font.getSize();
        if (fontPixels==pixels) return;
        fontPixels=pixels; inkFace=metrics.font;
        baseWidth=metrics.width; baseHeight=metrics.height; ascent=metrics.ascent;
        rendered=null; glyphMasks.clear(); sharedInk=null; litEffect=dimEffect=null;
    }
    private record GlyphMask(int left,int top,int width,int[] positions,int[] coverage) {}
    private GlyphMask glyphMask(GlyphVector glyphs) {
        return glyphMasks.computeIfAbsent(glyphs,vector -> {
            Rectangle bounds=vector.getPixelBounds(TEXT_CONTEXT,0,0);
            bounds.grow(2,2);
            BufferedImage image=new BufferedImage(Math.max(1,bounds.width),Math.max(1,bounds.height),BufferedImage.TYPE_INT_RGB);
            Graphics2D ink=image.createGraphics();
            try {
                ink.setColor(Color.BLACK); ink.fillRect(0,0,image.getWidth(),image.getHeight());
                ink.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                ink.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
                ink.setColor(Color.WHITE); ink.drawGlyphVector(vector,-bounds.x,-bounds.y);
            } finally { ink.dispose(); }
            int[] pixels=((DataBufferInt)image.getRaster().getDataBuffer()).getData();
            int count=0;
            for (int pixel:pixels) if ((pixel&0xffffff)!=0) count++;
            int[] positions=new int[count],coverage=new int[count];
            for (int i=0,n=0;i<pixels.length;i++) if ((pixels[i]&0xffffff)!=0) {
                positions[n]=i; coverage[n++]=pixels[i]&0xffffff;
            }
            return new GlyphMask(bounds.x,bounds.y,image.getWidth(),positions,coverage);
        });
    }
    private void paintSharedInk(Graphics2D cells,Color[] palette,String effect,int rows,int cols,boolean visiblePhase,
                                double dpiX,double dpiY) {
        Rectangle visible=getVisibleRect();
        Rectangle bounds=new Rectangle((int)Math.floor((visible.x-originX)*dpiX),(int)Math.floor((visible.y-originY)*dpiY),
                (int)Math.ceil(visible.width*dpiX)+2,(int)Math.ceil(visible.height*dpiY)+2);
        bounds=bounds.intersection(new Rectangle(-4,-4,(int)(cols*baseWidth)+8,(int)(rows*baseHeight)+8));
        if (bounds.isEmpty()) return;
        boolean lit=visiblePhase || !rendered.blinking;
        double time=effect.equals(Profile.FLUX) ? fluxPhase : -1;
        boolean changed=sharedInk==null || !bounds.equals(sharedBounds) || sharedPalette!=palette
                || !getBackground().equals(sharedBackground) || !Ui.color(ColorRole.TERMINAL_SELECTION).equals(sharedSelection)
                || anchor!=sharedAnchor || end!=sharedEnd || sharedPhase!=lit || sharedFlux!=time;
        if (changed) {
            if (sharedInk==null || sharedInk.getWidth()!=bounds.width || sharedInk.getHeight()!=bounds.height)
                sharedInk=new BufferedImage(bounds.width,bounds.height,BufferedImage.TYPE_INT_RGB);
            Graphics2D ink=sharedInk.createGraphics();
            PaintContext[] gradients=new PaintContext[palette.length];
            try {
                ink.setColor(getBackground()); ink.fillRect(0,0,bounds.width,bounds.height);
                ink.translate(-bounds.x,-bounds.y); paintBackgrounds(ink,palette,cols,bounds);
                int[] output=((DataBufferInt)sharedInk.getRaster().getDataBuffer()).getData();
                for (RenderRun run:rendered.runs) {
                    if (run.hidden || !intersects(run,bounds) || ((run.flags&EXTENDED_5250_BLINK)!=0 && !visiblePhase)) continue;
                    if (run.glyphs!=null) {
                        GlyphMask mask=glyphMask(run.glyphs);
                        int left=(int)(run.inkStart*baseWidth)+mask.left,top=(int)(run.row*baseHeight+ascent)+mask.top;
                        int[] colors=null;
                        if (effect.equals(Profile.FLUX) && run.background==0) {
                            PaintContext gradient=gradients[run.foreground];
                            if (gradient==null) {
                                gradient=fluxPaints[run.foreground].createContext(ColorModel.getRGBdefault(),bounds,bounds,
                                        new AffineTransform(),cells.getRenderingHints());
                                gradients[run.foreground]=gradient;
                            }
                            int height=mask.positions.length==0 ? 1 : mask.positions[mask.positions.length-1]/mask.width+1;
                            Raster raster=gradient.getRaster(left,top,mask.width,height);
                            colors=(int[])raster.getDataElements(raster.getMinX(),raster.getMinY(),mask.width,height,null);
                        }
                        int foreground=palette[run.foreground].getRGB();
                        for (int n=0;n<mask.positions.length;n++) {
                            int pixel=mask.positions[n],x=left+pixel%mask.width-bounds.x,y=top+pixel/mask.width-bounds.y;
                            if (x<0 || y<0 || x>=bounds.width || y>=bounds.height) continue;
                            int index=y*bounds.width+x;
                            output[index]=blendInk(output[index],colors==null ? foreground : colors[pixel],mask.coverage[n]);
                        }
                    }
                    ink.setColor(palette[run.foreground]);
                    if ((run.flags&EXTENDED_5250_UNDERLINE)!=0)
                        ink.draw(new Line2D.Double(run.start*baseWidth,run.row*baseHeight+ascent+1,run.end*baseWidth,run.row*baseHeight+ascent+1));
                    if (run.separators!=null) ink.draw(run.separators);
                }
            } finally {
                for (PaintContext context:gradients) if (context!=null) context.dispose();
                ink.dispose();
            }
            sharedBounds=bounds; sharedPalette=palette; sharedBackground=getBackground();
            sharedSelection=Ui.color(ColorRole.TERMINAL_SELECTION);
            sharedAnchor=anchor; sharedEnd=end; sharedPhase=lit; sharedFlux=time;
        }
        cells.drawImage(sharedInk,bounds.x,bounds.y,null);
    }
    private static int blendInk(int background,int foreground,int coverage) {
        int result=0xff000000;
        for (int shift=0;shift<=16;shift+=8) {
            int alpha=coverage>>shift&255,back=background>>shift&255,front=foreground>>shift&255;
            result|=((back*(255-alpha)+front*alpha+127)/255)<<shift;
        }
        return result;
    }
    private void paintBackgrounds(Graphics2D cells,Color[] palette,int cols,Rectangle clip) {
        for (RenderRun run : rendered.runs) {
            if (run.background!=0 && intersects(run,clip)) {
                cells.setColor(palette[run.background]);
                cells.fill(new Rectangle2D.Double(run.start*baseWidth,run.row*baseHeight,
                        (run.end-run.start)*baseWidth,baseHeight));
            }
        }
        if (anchor>=0 && end>=0) {
            int left=Math.min(anchor%cols,end%cols),top=Math.min(anchor/cols,end/cols);
            cells.setColor(Ui.color(ColorRole.TERMINAL_SELECTION));
            cells.fill(new Rectangle2D.Double(left*baseWidth,top*baseHeight,
                    (Math.abs(anchor%cols-end%cols)+1)*baseWidth,(Math.abs(anchor/cols-end/cols)+1)*baseHeight));
        }
    }
    private EffectLayer effectLayer(Color[] palette,String effect,int rows,int cols,boolean visiblePhase,int resolution) {
        java.util.List<EffectTile> tiles=new java.util.ArrayList<>();
        for (int row=0;row<rows;row++) {
            double left=Double.POSITIVE_INFINITY,right=0;
            for (RenderRun run : rendered.runs) {
                if (run.row!=row || run.hidden || ((run.flags&EXTENDED_5250_BLINK)!=0 && !visiblePhase)) continue;
                if (run.glyphs!=null) {
                    Rectangle2D ink=run.glyphs.getVisualBounds();
                    left=Math.min(left,run.inkStart*baseWidth+ink.getMinX());
                    right=Math.max(right,run.inkStart*baseWidth+ink.getMaxX());
                }
                if ((run.flags&EXTENDED_5250_UNDERLINE)!=0 || run.separators!=null) {
                    left=Math.min(left,run.start*baseWidth); right=Math.max(right,run.end*baseWidth);
                }
            }
            if (!Double.isFinite(left)) continue;
            Rectangle bounds=new Rectangle((int)Math.floor(left)-4,(int)Math.floor(row*baseHeight)-4,
                    (int)Math.ceil(right)-(int)Math.floor(left)+8,(int)Math.ceil((row+1)*baseHeight)-(int)Math.floor(row*baseHeight)+8);
            BufferedImage image=new BufferedImage(bounds.width*resolution,bounds.height*resolution,BufferedImage.TYPE_INT_RGB);
            Graphics2D ink=image.createGraphics();
            try {
                ink.setColor(getBackground()); ink.fillRect(0,0,image.getWidth(),image.getHeight());
                ink.scale(resolution,resolution); ink.translate(-bounds.x,-bounds.y);
                ink.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                ink.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
                paintBackgrounds(ink,palette,cols,bounds);
                // Neighboring ink must survive the overlap between opaque row tiles.
                paintInk(ink,palette,effect,cols,visiblePhase,bounds);
            } finally { ink.dispose(); }
            AffineTransform placement=new AffineTransform();
            placement.translate(bounds.x,bounds.y); placement.scale(1.0/resolution,1.0/resolution);
            tiles.add(new EffectTile(image,placement,bounds));
        }
        return new EffectLayer(tiles);
    }
    private record EffectLayer(java.util.List<EffectTile> tiles) {}
    private record EffectTile(BufferedImage image,AffineTransform placement,Rectangle bounds) {}
    private void paintInk(Graphics2D cells,Color[] palette,String effect,int cols,boolean visiblePhase,Rectangle clip) {
        for (RenderRun run : rendered.runs) {
            if (!intersects(run,clip) || run.hidden
                    || ((run.flags & EXTENDED_5250_BLINK)!=0 && !visiblePhase)) continue;
            Color foreground=palette[run.foreground];
            if (run.glyphs!=null) {
                float x=(float)(run.inkStart*baseWidth), y=(float)(run.row*baseHeight+ascent);
                if (effect.equals("Soft glow") && run.background==0) {
                    float radius=1;
                    Color glow=Ui.customTheme() ? Ui.color(ColorRole.GLOW) : foreground;
                    cells.setColor(new Color(glow.getRed(),glow.getGreen(),glow.getBlue(),80));
                    cells.drawGlyphVector(run.glyphs,x-radius,y); cells.drawGlyphVector(run.glyphs,x+radius,y);
                    cells.drawGlyphVector(run.glyphs,x,y-radius); cells.drawGlyphVector(run.glyphs,x,y+radius);
                }
                cells.setColor(foreground);
                if (effect.equals("Prism") && run.background==0) {
                    float[] hsb=Color.RGBtoHSB(foreground.getRed(),foreground.getGreen(),foreground.getBlue(),null);
                    Color shifted=Ui.customTheme() ? effectColor(foreground,.5)
                            : Color.getHSBColor((hsb[0]+.26f)%1,Math.max(.35f,hsb[1]),hsb[2]);
                    cells.setPaint(new GradientPaint(0,0,Ui.customTheme() ? effectColor(foreground,0) : foreground,
                            (float)(cols*baseWidth),0,shifted));
                }
                if (effect.equals(Profile.FLUX) && run.background==0) cells.setPaint(fluxPaints[run.foreground]);
                cells.drawGlyphVector(run.glyphs,x,y);
            }
            cells.setColor(foreground);
            if ((run.flags & EXTENDED_5250_UNDERLINE)!=0)
                cells.draw(new Line2D.Double(run.start*baseWidth,run.row*baseHeight+ascent+1,run.end*baseWidth,run.row*baseHeight+ascent+1));
            if (run.separators!=null) cells.draw(run.separators);
        }
    }

    private void prepareFlux(Color[] palette,int cols) {
        int width=Math.max(1,(int)(cols*baseWidth));
        if (fluxPaints!=null && paintedFluxPhase==fluxPhase && fluxPalette==palette && fluxWidth==width
                && getBackground().equals(fluxBackground) && Ui.color(ColorRole.TERMINAL_SELECTION).equals(fluxSelection)) return;
        fluxPaints=new Paint[palette.length];
        for (RenderRun run:rendered.runs) {
            int color=run.foreground;
            if (run.hidden || run.glyphs==null || run.background!=0 || fluxPaints[color]!=null) continue;
            if (color==0) { fluxPaints[color]=palette[color]; continue; }
            float[] hsb=Color.RGBtoHSB(palette[color].getRed(),palette[color].getGreen(),palette[color].getBlue(),null);
            Color[] stops=new Color[FLUX_STOPS.length];
            for (int step=0;step<stops.length;step++) {
                double wave=Math.sin(2*Math.PI*(fluxPhase+FLUX_STOPS[step]));
                float hue=hsb[1]<.22f ? (float)(FLUX_STOPS[step]+.12*wave) : hsb[0]+(float)(.065*wave);
                hue=(float)(hue-Math.floor(hue));
                Color ink=Color.getHSBColor(hue,hsb[1]<.22f ? .72f : hsb[1],hsb[2]*(float)(.9+.1*wave));
                stops[step]=Ui.customTheme() ? effectColor(palette[color],fluxPhase+FLUX_STOPS[step]) : readableFlux(ink);
            }
            float startX=(float)(width*.65*(2*fluxPhase-1)),startY=(float)(width*.12*(2*fluxPhase-1));
            fluxPaints[color]=new LinearGradientPaint(new Point2D.Float(startX,startY),
                    new Point2D.Float(startX+width*.65f,startY+width*.12f),FLUX_STOPS,stops,
                    MultipleGradientPaint.CycleMethod.REFLECT,MultipleGradientPaint.ColorSpaceType.LINEAR_RGB,
                    new AffineTransform());
        }
        fluxPalette=palette; fluxWidth=width; paintedFluxPhase=fluxPhase;
        fluxBackground=getBackground(); fluxSelection=Ui.color(ColorRole.TERMINAL_SELECTION);
    }
    private Color effectColor(Color host,double position) {
        double wrapped=(position-Math.floor(position))*6;
        int index=(int)wrapped;
        Color first=Ui.color(ColorRole.valueOf("GRADIENT_"+(index+1)));
        Color next=Ui.color(ColorRole.valueOf("GRADIENT_"+((index+1)%6+1)));
        int gradient=ColorTheme.mix(first.getRGB(),next.getRGB(),wrapped-index);
        return new Color(ColorTheme.mix(host.getRGB(),gradient,.5));
    }
    private Color readableFlux(Color ink) {
        int target=Ui.dark() ? 255 : 0;
        while (contrast(ink,getBackground())<4.6 || contrast(ink,Ui.color(ColorRole.TERMINAL_SELECTION))<4.6) {
            Color adjusted=new Color((ink.getRed()*4+target)/5,(ink.getGreen()*4+target)/5,(ink.getBlue()*4+target)/5);
            if (adjusted.equals(ink)) return Ui.TEXT;
            ink=adjusted;
        }
        return ink;
    }
    private static double contrast(Color first,Color second) {
        double a=luminance(first),b=luminance(second);
        return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);
    }
    private static double luminance(Color color) {
        return .2126*linear(color.getRed())+.7152*linear(color.getGreen())+.0722*linear(color.getBlue());
    }
    private static double linear(int channel) {
        double value=channel/255.0;
        return value<=.04045 ? value/12.92 : Math.pow((value+.055)/1.055,2.4);
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
                        glyphs = inkFace.createGlyphVector(TEXT_CONTEXT,ink);
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
    @Override public Dimension getPreferredSize() {
        Screen5250 screen = pane.screen();
        int cols = screen == null ? 80 : screen.getColumns(), rows = screen == null ? 24 : screen.getRows();
        if (fit) return new Dimension(240,140);
        AffineTransform display=getGraphicsConfiguration()==null ? new AffineTransform() : getGraphicsConfiguration().getDefaultTransform();
        InkMetrics ink=metrics(Math.max(1,(int)Math.floor(16*pane.profile().zoom()*display.getScaleY())));
        return new Dimension((int)Math.ceil(cols*ink.width/display.getScaleX())+24,
                (int)Math.ceil(rows*ink.height/display.getScaleY())+20);
    }
    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 20; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return 100; }
    @Override public boolean getScrollableTracksViewportWidth() {
        return fit || getParent() instanceof JViewport viewport && getPreferredSize().width<=viewport.getExtentSize().width;
    }
    @Override public boolean getScrollableTracksViewportHeight() {
        return fit || getParent() instanceof JViewport viewport && getPreferredSize().height<=viewport.getExtentSize().height;
    }
    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleTerminal();
        return accessibleContext;
    }
    protected class AccessibleTerminal extends AccessibleJComponent {
        @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.TEXT; }
    }
}
