package fish.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.*;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Point2D;
import javafx.scene.AccessibleRole;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.tn5250j.framework.tn5250.Screen5250;
import org.tn5250j.keyboard.KeyMnemonic;
import fish.model.*;
import static org.tn5250j.TN5250jConstants.*;

/**
 * Hardware-backed JavaFX terminal surface using PixelBuffer<IntBuffer> + ClearType LCD GlyphVector masks.
 * Preserves snapped physical-pixel cell metrics, non-display secrecy, Soft glow, Prism, Flux, and mode-aware cursors.
 */
public class TerminalView extends Region {
    private final SessionPane pane;
    private final ImageView surface=new ImageView();
    private PixelBuffer<IntBuffer> pixelBuffer;
    private WritableImage fxImage;
    private int[] fxPixels;
    private BufferedImage backingImage;

    private Font face;
    private static final FontRenderContext TEXT_CONTEXT=new FontRenderContext(null,
            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    private Font inkFace;
    private int fontPixels;
    private final Map<Integer,InkMetrics> fontMetrics=new HashMap<>();
    private EffectLayer litEffect,dimEffect;
    private int effectResolution;
    private Color effectBackground;
    private int effectAnchor,effectEnd;
    private double nominalWidth,nominalHeight;
    private double baseWidth,baseHeight,ascent;
    private RenderFrame rendered;
    private Screen5250 renderedScreen;
    private final Map<GlyphVector,GlyphMask> glyphMasks=new IdentityHashMap<>();
    private BufferedImage sharedInk;
    private Rectangle sharedBounds;
    private Color[] sharedPalette;
    private Color sharedBackground,sharedSelection;
    private int sharedAnchor,sharedEnd;
    private boolean sharedPhase;
    private double sharedFlux=Double.NaN;
    private double cellWidth=9,cellHeight=18,originX=12,originY=10;
    private int geometryWidth=-1,geometryHeight;
    private double geometryDpiX=1,geometryDpiY=1,geometryZoom;
    private boolean geometryFit,revealQueued;
    private int anchor=-1,end=-1;
    private boolean dragging;
    private boolean fit=true;
    private Double savedZoom;
    private Color backgroundColor=Ui.BACKGROUND;
    private final Timeline blink;
    private boolean phase=true;
    private final Timeline cursorBlink;
    private boolean cursorPhase=true;
    private Rectangle lastCursor;
    private boolean disposed;
    private Supplier<java.awt.datatransfer.Clipboard> customClipboard;
    private double fluxPhase,paintedFluxPhase=Double.NaN;
    private Paint[] fluxPaints;
    private Color[] fluxPalette;
    private Color fluxBackground,fluxSelection;
    private int fluxWidth;
    private Runnable scrollRevealCallback;

    private static final float[] FLUX_STOPS={0,.2f,.4f,.6f,.8f,1};
    private static final Set<TerminalView> ANIMATED=new HashSet<>();
    private static final Timeline ANIMATION=createFluxTimeline();

    private static Timeline createFluxTimeline() {
        Timeline t=new Timeline(new KeyFrame(Duration.millis(50),event -> {
            double time=fluxTime();
            for (var iterator=ANIMATED.iterator();iterator.hasNext();) {
                TerminalView view=iterator.next();
                if (!view.canAnimate()) { iterator.remove(); continue; }
                view.fluxPhase=time;
                view.requestRender();
            }
            if (ANIMATED.isEmpty()) stopFluxClock();
        }));
        t.setCycleCount(Timeline.INDEFINITE);
        return t;
    }
    private static void stopFluxClock() { ANIMATION.stop(); }

    public TerminalView(SessionPane pane) {
        this.pane=pane;
        fontChanged();
        surface.setSmooth(false);
        surface.setManaged(false);
        getChildren().add(surface);
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.TEXT);
        setAccessibleText("5250 terminal");
        setAccessibleHelp("Field-aware host terminal. Ctrl Alt M opens application menus.");

        focusedProperty().addListener((obs,was,focused) -> {
            if (focused) { pane.activate(); restartCursor(); }
            else { repaintCursor(); updateAnimation(); }
        });
        visibleProperty().addListener((o,a,b) -> updateAnimation());
        disabledProperty().addListener((o,a,b) -> updateAnimation());
        sceneProperty().addListener((obs,oldScene,newScene) -> {
            if (newScene!=null) {
                newScene.windowProperty().addListener((wo,oldW,newW) -> attachWindow(newW));
                if (newScene.getWindow()!=null) attachWindow(newScene.getWindow());
            }
            updateAnimation();
        });
        widthProperty().addListener((o,a,b) -> { requestRender(); revealCursor(); });
        heightProperty().addListener((o,a,b) -> { requestRender(); revealCursor(); });

        boolean[] suppressTyped={false};
        setOnKeyPressed(event -> {
            suppressTyped[0]=false;
            if (event.isControlDown() && event.isAltDown()) return;
            if (event.getCode()==KeyCode.ENTER && !event.isControlDown() && !event.isShiftDown() && !event.isAltDown()
                    && pane.recoverableInputError()) {
                suppressTyped[0]=true;
                pane.send(KeyMnemonic.RESET);
                event.consume();
                return;
            }
            if (event.getCode()==KeyCode.CONTEXT_MENU && pane.keys().lookup(event)==null) {
                suppressTyped[0]=true;
                event.consume();
                Point2D screenPt=localToScreen(originX,Math.min(Math.max(1,getHeight()-1),originY+cellHeight));
                if (screenPt!=null) pane.clipboardMenu().show(this,screenPt.getX(),screenPt.getY());
                return;
            }
            if (event.isShiftDown() && (event.getCode()==KeyCode.LEFT || event.getCode()==KeyCode.RIGHT
                    || event.getCode()==KeyCode.UP || event.getCode()==KeyCode.DOWN)) {
                restartCursor();
                selectByKey(event.getCode());
                event.consume();
                return;
            }
            KeyMnemonic mnemonic=pane.keys().lookup(event);
            if (mnemonic!=null) {
                suppressTyped[0]=true;
                pane.send(mnemonic);
                event.consume();
            }
        });
        setOnKeyTyped(event -> {
            if (suppressTyped[0]) { event.consume(); return; }
            if (event.isControlDown() || event.isAltDown() || event.isMetaDown()) return;
            String ch=event.getCharacter();
            if (ch!=null && !ch.isEmpty()) {
                char c=ch.charAt(0);
                if (c>=32 && c!=127 && c!=65535) {
                    pane.type(c);
                    event.consume();
                }
            }
        });

        boolean[] paneDrag={false};
        setOnMousePressed(event -> {
            if (event.getButton()==MouseButton.SECONDARY) {
                pane.clipboardMenu().show(this,event.getScreenX(),event.getScreenY());
                event.consume();
                return;
            }
            if (event.getButton()!=MouseButton.PRIMARY) return;
            requestFocus();
            restartCursor();
            paneDrag[0]=event.isAltDown();
            if (paneDrag[0]) return;
            anchor=end=position(event.getX(),event.getY());
            dragging=false;
            requestRender();
        });
        setOnMouseDragged(event -> {
            if (!event.isPrimaryButtonDown() || paneDrag[0]) return;
            end=position(event.getX(),event.getY());
            dragging=true;
            requestRender();
        });
        setOnMouseReleased(event -> {
            if (event.getButton()==MouseButton.SECONDARY) return;
            if (event.getButton()!=MouseButton.PRIMARY) return;
            if (paneDrag[0]) { paneDrag[0]=false; return; }
            if (!dragging && pane.screen()!=null && pane.connected()) {
                repaintCursor();
                pane.screen().moveCursor(end);
                anchor=end=-1;
                restartCursor();
            }
            requestRender();
        });
        setOnContextMenuRequested(event -> {
            pane.clipboardMenu().show(this,event.getScreenX(),event.getScreenY());
            event.consume();
        });

        blink=new Timeline(new KeyFrame(Duration.millis(550),e -> {
            phase=!phase;
            if (!isVisible()) return;
            if (rendered!=null && rendered.blinking) requestRender();
        }));
        blink.setCycleCount(Timeline.INDEFINITE);

        cursorBlink=new Timeline(new KeyFrame(Duration.millis(550),e -> {
            cursorPhase=!cursorPhase;
            repaintCursor();
        }));
        cursorBlink.setCycleCount(Timeline.INDEFINITE);
    }

    private void attachWindow(Window window) {
        if (window==null) return;
        window.showingProperty().addListener((o,a,showing) -> { updateAnimation(); if (showing) requestRender(); });
        window.outputScaleXProperty().addListener((o,a,b) -> requestRender());
        window.outputScaleYProperty().addListener((o,a,b) -> requestRender());
        if (window instanceof Stage st) {
            st.iconifiedProperty().addListener((o,a,b) -> updateAnimation());
        }
    }

    public void setScrollRevealCallback(Runnable callback) { this.scrollRevealCallback=callback; }

    public Color getTerminalBackground() { return backgroundColor; }
    public void setTerminalBackground(Color color) {
        this.backgroundColor=Objects.requireNonNull(color);
        setStyle("-fx-background-color: "+Ui.hex(color)+";");
        requestRender();
    }

    public void dispose() {
        disposed=true;
        blink.stop();
        cursorBlink.stop();
        stopAnimation();
        glyphMasks.clear();
        sharedInk=null;
        backingImage=null;
        fxPixels=null;
    }

    public void restartCursor() {
        if (disposed) return;
        cursorPhase=true;
        repaintCursor();
        if (cursorReady()) cursorBlink.playFromStart();
        else cursorBlink.stop();
    }

    protected boolean hasTerminalFocus() { return isFocused(); }
    protected boolean isTerminalShowing() {
        if (disposed || isDisabled() || !isVisible()) return false;
        if (getScene()==null || getScene().getWindow()==null) return false;
        Window w=getScene().getWindow();
        if (!w.isShowing()) return false;
        return !(w instanceof Stage st && st.isIconified());
    }

    private boolean cursorReady() {
        return isTerminalShowing() && hasTerminalFocus() && pane.connected() && pane.screen()!=null && pane.screen().isCursorActive();
    }

    private Rectangle cursorBounds() {
        Screen5250 screen=pane.screen();
        if (screen==null) return null;
        int pos=Math.max(0,Math.min(screen.getCurrentPos()-1,screen.getScreenLength()-1));
        return new Rectangle((int)Math.floor(originX+pos%screen.getColumns()*cellWidth)-2,
                (int)Math.floor(originY+pos/screen.getColumns()*cellHeight)-2,
                (int)Math.ceil(cellWidth)+5,(int)Math.ceil(cellHeight)+5);
    }

    protected void recordDirtyRect(Rectangle rect) {}

    private void repaintCursor() {
        Rectangle next=cursorBounds();
        if (lastCursor!=null) { recordDirtyRect(lastCursor); requestDirtyRender(lastCursor); }
        if (next!=null) { recordDirtyRect(next); requestDirtyRender(next); }
        lastCursor=next;
    }

    private boolean canAnimate() {
        return isTerminalShowing() && pane.connected() && pane.profile().effect().equals(Profile.FLUX);
    }

    private static double fluxTime() {
        return Math.floorMod(System.nanoTime(),8_000_000_000L)/8_000_000_000.0;
    }

    public static Timeline fluxAnimationClock() { return ANIMATION; }

    public void updateAnimation() {
        boolean showing=isTerminalShowing();
        if (showing && pane.connected()) {
            if (blink.getStatus()!=javafx.animation.Animation.Status.RUNNING) blink.play();
        } else blink.stop();
        if (cursorReady()) {
            if (cursorBlink.getStatus()!=javafx.animation.Animation.Status.RUNNING) restartCursor();
        } else cursorBlink.stop();
        if (canAnimate()) {
            if (ANIMATED.add(this)) fluxPhase=fluxTime();
            if (ANIMATION.getStatus()!=javafx.animation.Animation.Status.RUNNING) ANIMATION.play();
        } else stopAnimation();
    }

    private void stopAnimation() {
        ANIMATED.remove(this);
        if (ANIMATED.isEmpty()) ANIMATION.stop();
    }

    public void setFit(boolean value) { fit=value; pane.syncScrollViewport(); requestLayout(); requestRender(); revealCursor(); }
    public boolean isFit() { return fit; }
    public double zoomValue() { return savedZoom==null ? pane.profile().zoom() : savedZoom; }
    public void restoreDisplay(SavedLayout.Display display) {
        savedZoom=display.zoom(); geometryWidth=-1; setFit(display.fit());
    }
    public void clearSavedZoom() { savedZoom=null; geometryWidth=-1; }
    public void screenChanged() {
        rendered=null; glyphMasks.clear(); sharedInk=null; geometryWidth=-1;
        litEffect=dimEffect=null; fluxPaints=null; updateAnimation(); requestLayout(); requestRender();
        revealCursor();
    }
    public void displayChanged() { sharedPalette=null; litEffect=dimEffect=null; fluxPaints=null; updateAnimation(); requestRender(); }
    public void themeChanged() {
        setTerminalBackground(Ui.customTheme() ? Ui.color(ColorRole.HOST_0) : pane.paneSurfaceColor());
        displayChanged();
    }
    public void fontChanged() {
        face=pane.workspaceFont();
        nominalWidth=face.getStringBounds("M",TEXT_CONTEXT).getWidth();
        nominalHeight=face.getLineMetrics("M",TEXT_CONTEXT).getHeight();
        fontMetrics.clear(); fontPixels=0; geometryWidth=-1; configureInk(metrics(16));
        pane.syncScrollViewport();
        requestLayout();
        if (blink!=null) screenChanged();
    }
    public void resetSelection() { anchor=end=-1; requestRender(); }
    public void setSelectionRange(int selectionAnchor,int selectionEnd) {
        this.anchor=selectionAnchor; this.end=selectionEnd; requestRender();
    }

    public String visibleText() {
        return visibleText(pane.screen(),anchor,end);
    }

    public static String visibleText(Screen5250 screen,int anchor,int end) {
        if (screen==null) return "";
        char[] chars=plane(screen,PLANE_TEXT);
        char[] hidden=plane(screen,PLANE_EXTENDED);
        char[] attributes=plane(screen,PLANE_IS_ATTR_PLACE);
        int cols=screen.getColumns();
        int r1=0,r2=screen.getRows()-1,c1=0,c2=cols-1;
        if (anchor>=0 && end>=0) {
            r1=Math.min(anchor/cols,end/cols); r2=Math.max(anchor/cols,end/cols);
            c1=Math.min(anchor%cols,end%cols); c2=Math.max(anchor%cols,end%cols);
        }
        StringBuilder result=new StringBuilder();
        for (int row=r1;row<=r2;row++) {
            StringBuilder line=new StringBuilder();
            for (int col=c1;col<=c2;col++) {
                int pos=row*cols+col;
                line.append(chars[pos]>=32 && attributes[pos]==0
                        && (hidden[pos]&EXTENDED_5250_NON_DSP)==0 ? chars[pos] : ' ');
            }
            result.append(line.toString().stripTrailing());
            if (row<r2) result.append('\n');
        }
        return result.toString();
    }

    public void copy() {
        if (customClipboard!=null) { clipboardAwt(true,customClipboard); return; }
        try {
            if (pane.screen()==null) { pane.problem("No host screen to copy."); return; }
            Clipboard cb=Clipboard.getSystemClipboard();
            ClipboardContent content=new ClipboardContent();
            content.putString(visibleText());
            if (!cb.setContent(content)) pane.problem("Clipboard unavailable: could not write text.");
        } catch (RuntimeException e) {
            pane.problem("Clipboard unavailable: "+e.getMessage());
        } finally { pane.focusTerminal(); }
    }

    public void paste() {
        if (customClipboard!=null) { clipboardAwt(false,customClipboard); return; }
        try {
            if (pane.screen()==null) { pane.problem("No host screen to paste into."); return; }
            if (!pane.connected() || pane.screen().getOIA().isKeyBoardLocked()) {
                pane.problem("Paste unavailable: disconnected or host input inhibited."); return;
            }
            Clipboard cb=Clipboard.getSystemClipboard();
            if (!cb.hasString() || cb.getString()==null) {
                pane.problem("Clipboard does not contain text."); return;
            }
            pane.paste(cb.getString());
        } catch (RuntimeException e) {
            pane.problem("Clipboard unavailable: "+e.getMessage());
        } finally { pane.focusTerminal(); }
    }

    public void clipboard(boolean copy,Supplier<java.awt.datatransfer.Clipboard> source) {
        clipboardAwt(copy,source);
    }

    private void clipboardAwt(boolean copy,Supplier<java.awt.datatransfer.Clipboard> source) {
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
        } catch (java.awt.datatransfer.UnsupportedFlavorException e) {
            pane.problem("Clipboard does not contain text.");
        } catch (IOException | IllegalStateException | SecurityException | java.awt.HeadlessException e) {
            pane.problem("Clipboard unavailable: "+e.getMessage());
        } finally { pane.focusTerminal(); }
    }

    public void selectByKey(KeyCode key) {
        Screen5250 screen=pane.screen();
        if (screen==null) return;
        if (anchor<0) anchor=end=screen.getCurrentPos()-1;
        int delta=switch (key) {
            case LEFT -> -1;
            case RIGHT -> 1;
            case UP -> -screen.getColumns();
            default -> screen.getColumns();
        };
        end=Math.max(0,Math.min(screen.getScreenLength()-1,end+delta));
        requestRender();
    }

    private static char[] plane(Screen5250 screen,int plane) {
        char[] data=new char[screen.getScreenLength()+1];
        screen.GetScreen(data,data.length,plane);
        return data;
    }

    private int position(double px,double py) {
        ensureGeometry();
        int cols=pane.screen()==null ? 80 : pane.screen().getColumns();
        int rows=pane.screen()==null ? 24 : pane.screen().getRows();
        int col=Math.max(0,Math.min(cols-1,(int)((px-originX)/cellWidth)));
        int row=Math.max(0,Math.min(rows-1,(int)((py-originY)/cellHeight)));
        return row*cols+col;
    }

    private void ensureGeometry() {
        int w=Math.max(1,(int)Math.round(getWidth())),h=Math.max(1,(int)Math.round(getHeight()));
        if (geometryWidth!=w || geometryHeight!=h || geometryFit!=fit || geometryZoom!=zoomValue())
            geometry(geometryDpiX,geometryDpiY);
    }

    public Rectangle currentCursorLogicalBounds() {
        Screen5250 screen=pane.screen();
        if (screen==null) return null;
        ensureGeometry();
        int pos=Math.max(0,Math.min(screen.getCurrentPos()-1,screen.getScreenLength()-1));
        return new Rectangle((int)Math.floor(originX+pos%screen.getColumns()*cellWidth),
                (int)Math.floor(originY+pos/screen.getColumns()*cellHeight),
                (int)Math.ceil(cellWidth)+1,(int)Math.ceil(cellHeight)+1);
    }

    private void revealCursor() {
        if (fit || revealQueued || !hasTerminalFocus() || pane.screen()==null) return;
        revealQueued=true;
        Ui.runLater(() -> {
            revealQueued=false;
            if (disposed || fit || !hasTerminalFocus() || pane.screen()==null || !pane.screen().isCursorActive()) return;
            if (scrollRevealCallback!=null) scrollRevealCallback.run();
        });
    }

    private void geometry(double dpiX,double dpiY) {
        int w=Math.max(1,(int)Math.round(getWidth())),h=Math.max(1,(int)Math.round(getHeight()));
        Screen5250 screen=pane.screen();
        int rows=screen==null ? 24 : screen.getRows(),cols=screen==null ? 80 : screen.getColumns();
        double scale=fit ? Math.max(3.0/16,Math.min(28.0/16,
                Math.min((w-24)/(cols*nominalWidth),(h-20)/(rows*nominalHeight)))) : zoomValue();
        int pixels=Math.max(1,(int)Math.floor(16*scale*dpiY));
        InkMetrics target=metrics(pixels);
        while (fit && pixels>1 && (cols*target.width>(w-24)*dpiX || rows*target.height>(h-20)*dpiY))
            target=metrics(--pixels);
        configureInk(target);
        cellWidth=baseWidth/dpiX; cellHeight=baseHeight/dpiY;
        originX=Math.rint(Math.max(12,(w-cols*cellWidth)/2)*dpiX)/dpiX;
        originY=Math.rint(Math.max(10,(h-rows*cellHeight)/2)*dpiY)/dpiY;
        geometryWidth=w; geometryHeight=h; geometryFit=fit; geometryZoom=zoomValue();
        geometryDpiX=dpiX; geometryDpiY=dpiY;
    }

    private double outputScaleX() {
        if (getScene()!=null && getScene().getWindow()!=null) {
            double s=getScene().getWindow().getOutputScaleX();
            if (s>0) return s;
        }
        return 1.0;
    }

    private double outputScaleY() {
        if (getScene()!=null && getScene().getWindow()!=null) {
            double s=getScene().getWindow().getOutputScaleY();
            if (s>0) return s;
        }
        return 1.0;
    }

    @Override protected void layoutChildren() {
        requestRender();
    }

    private boolean windowShowing() {
        return getScene()!=null && getScene().getWindow()!=null && getScene().getWindow().isShowing();
    }

    public void requestRender() {
        if (windowShowing()) renderBuffer(null);
    }

    private void requestDirtyRender(Rectangle logicalDirty) {
        if (windowShowing()) renderBuffer(logicalDirty);
    }

    public void forceSurfaceRender() {
        renderBuffer(null);
    }

    private void renderBuffer(Rectangle logicalDirty) {
        if (disposed) return;
        double lw=getWidth(),lh=getHeight();
        if (lw<=1 || lh<=1) return;
        double sx=outputScaleX(),sy=outputScaleY();
        int pw=Math.max(1,(int)Math.ceil(lw*sx));
        int ph=Math.max(1,(int)Math.ceil(lh*sy));
        boolean recreated=false;
        if (backingImage==null || backingImage.getWidth()!=pw || backingImage.getHeight()!=ph) {
            backingImage=new BufferedImage(pw,ph,BufferedImage.TYPE_INT_ARGB_PRE);
            fxPixels=((DataBufferInt)backingImage.getRaster().getDataBuffer()).getData();
            IntBuffer buf=IntBuffer.wrap(fxPixels);
            pixelBuffer=new PixelBuffer<>(pw,ph,buf,PixelFormat.getIntArgbPreInstance());
            fxImage=new WritableImage(pixelBuffer);
            surface.setImage(fxImage);
            recreated=true;
        }
        surface.getTransforms().setAll(javafx.scene.transform.Transform.scale(1.0/sx,1.0/sy));
        surface.relocate(0,0);

        Graphics2D g=backingImage.createGraphics();
        try {
            g.scale(sx,sy);
            if (!recreated && logicalDirty!=null) {
                g.clip(logicalDirty);
            }
            paintSurface(g,lw,lh);
        } finally {
            g.dispose();
        }
        if (recreated || logicalDirty==null) {
            int fpw=pw,fph=ph;
            pixelBuffer.updateBuffer(pb -> new javafx.geometry.Rectangle2D(0,0,fpw,fph));
        } else {
            int dx=Math.max(0,(int)Math.floor(logicalDirty.x*sx)-1);
            int dy=Math.max(0,(int)Math.floor(logicalDirty.y*sy)-1);
            int dw=Math.min(pw-dx,(int)Math.ceil(logicalDirty.width*sx)+3);
            int dh=Math.min(ph-dy,(int)Math.ceil(logicalDirty.height*sy)+3);
            if (dw>0 && dh>0) {
                pixelBuffer.updateBuffer(pb -> new javafx.geometry.Rectangle2D(dx,dy,dw,dh));
            }
        }
    }

    public BufferedImage paintToImage(double scale) {
        int lw=Math.max(1,(int)Math.round(getWidth()<=0 ? 640 : getWidth()));
        int lh=Math.max(1,(int)Math.round(getHeight()<=0 ? 400 : getHeight()));
        if (getWidth()<=0 || getHeight()<=0) resize(lw,lh);
        BufferedImage image=new BufferedImage(Math.max(1,(int)Math.ceil(lw*scale)),Math.max(1,(int)Math.ceil(lh*scale)),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try {
            g.scale(scale,scale);
            paintComponent(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    public void paintComponent(java.awt.Graphics graphics) {
        double lw=getWidth()<=0 ? 640 : getWidth();
        double lh=getHeight()<=0 ? 400 : getHeight();
        paintSurface((Graphics2D)graphics,lw,lh);
    }

    private void paintSurface(Graphics2D graphics,double logicalW,double logicalH) {
        Graphics2D g=(Graphics2D)graphics.create();
        try {
            g.setColor(backgroundColor);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
            g.fillRect(0,0,(int)Math.ceil(logicalW),(int)Math.ceil(logicalH));
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            Screen5250 screen=pane.screen();
            int rows=screen==null ? 24 : screen.getRows(),cols=screen==null ? 80 : screen.getColumns();
            if (screen==null) return;
            double dpiX=Math.abs(g.getTransform().getScaleX()),dpiY=Math.abs(g.getTransform().getScaleY());
            geometry(dpiX,dpiY);
            if (rendered==null || renderedScreen!=screen) {
                rendered=snapshot(screen);
                renderedScreen=screen;
                glyphMasks.clear(); sharedPalette=null;
                fluxPaints=null;
            }
            Graphics2D cells=(Graphics2D)g.create();
            try {
                cells.translate(originX,originY);
                cells.scale(1/dpiX,1/dpiY);
                AffineTransform target=cells.getTransform();
                target.setTransform(1,0,0,1,Math.rint(target.getTranslateX()),Math.rint(target.getTranslateY()));
                cells.setTransform(target);
                cells.setStroke(new java.awt.BasicStroke(1));
                Color[] palette=HostPalette.colors(pane.profile().theme());
                Rectangle clip=cells.getClipBounds();
                paintBackgrounds(cells,palette,cols,clip);
                String effect=pane.profile().effect();
                if (effect.equals("None") || effect.equals(Profile.FLUX)) {
                    if (effect.equals(Profile.FLUX)) prepareFlux(palette,cols);
                    paintSharedInk(cells,palette,effect,rows,cols,phase,dpiX,dpiY,logicalW,logicalH);
                } else {
                    int resolution=1;
                    if (effectResolution!=resolution || !backgroundColor.equals(effectBackground)
                            || anchor!=effectAnchor || end!=effectEnd) {
                        litEffect=dimEffect=null;
                        effectResolution=resolution;
                        effectBackground=backgroundColor; effectAnchor=anchor; effectEnd=end;
                    }
                    boolean lit=phase || !rendered.blinking;
                    EffectLayer layer=lit ? litEffect : dimEffect;
                    if (layer==null) {
                        layer=effectLayer(palette,effect,rows,cols,lit,resolution);
                        if (lit) litEffect=layer; else dimEffect=layer;
                    }
                    for (EffectTile tile:layer.tiles)
                        if (clip==null || clip.intersects(tile.bounds)) cells.drawImage(tile.image,tile.placement,null);
                }
                if (hasTerminalFocus() && pane.connected() && screen.isCursorActive() && cursorPhase)
                    paintCursor(cells,screen,rows,cols);
            } finally { cells.dispose(); }
        } finally { g.dispose(); }
    }

    private void paintCursor(Graphics2D cells,Screen5250 screen,int rows,int cols) {
        int pos=Math.max(0,Math.min(screen.getCurrentPos()-1,rows*cols-1));
        int col=pos%cols,row=pos/cols,width=(int)baseWidth,height=(int)baseHeight;
        int x=col*width,y=row*height;
        Appearance.CursorStyle style=pane.cursorStyle();
        Color fill=Ui.color(ColorRole.CURSOR);
        if (!screen.getOIA().isInsertMode() && style!=Appearance.CursorStyle.FILLED) {
            cells.setColor(fill);
            cells.fillRect(x,y+height-1,width,1);
            if (style==Appearance.CursorStyle.OUTLINE) {
                cells.fillRect(x,y,width,1); cells.fillRect(x,y,1,height); cells.fillRect(x+width-1,y,1,height);
            }
            return;
        }
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        int[] output=((DataBufferInt)image.getRaster().getDataBuffer()).getData();
        Arrays.fill(output,fill.getRGB());
        Color ink=contrast(Color.BLACK,fill)>=contrast(Color.WHITE,fill) ? Color.BLACK : Color.WHITE;
        for (RenderRun run:rendered.runs) {
            if (run.row!=row || col<run.start || col>=run.end || run.hidden
                    || (run.flags&EXTENDED_5250_BLINK)!=0 && !phase) continue;
            if (run.glyphs!=null) {
                GlyphMask mask=glyphMask(run.glyphs);
                int left=(int)(run.inkStart*baseWidth)+mask.left-x,top=(int)ascent+mask.top;
                for (int n=0;n<mask.positions.length;n++) {
                    int pixel=mask.positions[n],px=left+pixel%mask.width,py=top+pixel/mask.width;
                    if (px>=0 && px<width && py>=0 && py<height)
                        output[py*width+px]=blendInk(fill.getRGB(),ink.getRGB(),mask.coverage[n]);
                }
            }
            Graphics2D details=image.createGraphics();
            try {
                details.setColor(ink); details.translate(-x,-y);
                if ((run.flags&EXTENDED_5250_UNDERLINE)!=0)
                    details.draw(new Line2D.Double(run.start*baseWidth,row*baseHeight+ascent+1,run.end*baseWidth,row*baseHeight+ascent+1));
                if (run.separators!=null) details.draw(run.separators);
            } finally { details.dispose(); }
        }
        cells.drawImage(image,x,y,null);
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
                                double dpiX,double dpiY,double logicalW,double logicalH) {
        Rectangle bounds=new Rectangle((int)Math.floor((0-originX)*dpiX),(int)Math.floor((0-originY)*dpiY),
                (int)Math.ceil(logicalW*dpiX)+2,(int)Math.ceil(logicalH*dpiY)+2);
        bounds=bounds.intersection(new Rectangle(-4,-4,(int)(cols*baseWidth)+8,(int)(rows*baseHeight)+8));
        if (bounds.isEmpty()) return;
        boolean lit=visiblePhase || !rendered.blinking;
        double time=effect.equals(Profile.FLUX) ? fluxPhase : -1;
        boolean changed=sharedInk==null || !bounds.equals(sharedBounds) || sharedPalette!=palette
                || !backgroundColor.equals(sharedBackground) || !Ui.color(ColorRole.TERMINAL_SELECTION).equals(sharedSelection)
                || anchor!=sharedAnchor || end!=sharedEnd || sharedPhase!=lit || sharedFlux!=time;
        if (changed) {
            if (sharedInk==null || sharedInk.getWidth()!=bounds.width || sharedInk.getHeight()!=bounds.height)
                sharedInk=new BufferedImage(bounds.width,bounds.height,BufferedImage.TYPE_INT_RGB);
            Graphics2D ink=sharedInk.createGraphics();
            PaintContext[] gradients=new PaintContext[palette.length];
            try {
                ink.setColor(backgroundColor); ink.fillRect(0,0,bounds.width,bounds.height);
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
            sharedBounds=bounds; sharedPalette=palette; sharedBackground=backgroundColor;
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
        for (RenderRun run:rendered.runs) {
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
        List<EffectTile> tiles=new ArrayList<>();
        for (int row=0;row<rows;row++) {
            double left=Double.POSITIVE_INFINITY,right=0;
            for (RenderRun run:rendered.runs) {
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
                ink.setColor(backgroundColor); ink.fillRect(0,0,image.getWidth(),image.getHeight());
                ink.scale(resolution,resolution); ink.translate(-bounds.x,-bounds.y);
                ink.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                ink.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
                paintBackgrounds(ink,palette,cols,bounds);
                paintInk(ink,palette,effect,cols,visiblePhase,bounds);
            } finally { ink.dispose(); }
            AffineTransform placement=new AffineTransform();
            placement.translate(bounds.x,bounds.y); placement.scale(1.0/resolution,1.0/resolution);
            tiles.add(new EffectTile(image,placement,bounds));
        }
        return new EffectLayer(tiles);
    }

    private record EffectLayer(List<EffectTile> tiles) {}
    private record EffectTile(BufferedImage image,AffineTransform placement,Rectangle bounds) {}

    private void paintInk(Graphics2D cells,Color[] palette,String effect,int cols,boolean visiblePhase,Rectangle clip) {
        for (RenderRun run:rendered.runs) {
            if (!intersects(run,clip) || run.hidden
                    || ((run.flags&EXTENDED_5250_BLINK)!=0 && !visiblePhase)) continue;
            Color foreground=palette[run.foreground];
            if (run.glyphs!=null) {
                float x=(float)(run.inkStart*baseWidth),y=(float)(run.row*baseHeight+ascent);
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
                    cells.setPaint(new java.awt.GradientPaint(0,0,Ui.customTheme() ? effectColor(foreground,0) : foreground,
                            (float)(cols*baseWidth),0,shifted));
                }
                if (effect.equals(Profile.FLUX) && run.background==0) cells.setPaint(fluxPaints[run.foreground]);
                cells.drawGlyphVector(run.glyphs,x,y);
            }
            cells.setColor(foreground);
            if ((run.flags&EXTENDED_5250_UNDERLINE)!=0)
                cells.draw(new Line2D.Double(run.start*baseWidth,run.row*baseHeight+ascent+1,run.end*baseWidth,run.row*baseHeight+ascent+1));
            if (run.separators!=null) cells.draw(run.separators);
        }
    }

    private void prepareFlux(Color[] palette,int cols) {
        int width=Math.max(1,(int)(cols*baseWidth));
        if (fluxPaints!=null && paintedFluxPhase==fluxPhase && fluxPalette==palette && fluxWidth==width
                && backgroundColor.equals(fluxBackground) && Ui.color(ColorRole.TERMINAL_SELECTION).equals(fluxSelection)) return;
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
            fluxPaints[color]=new LinearGradientPaint(new java.awt.geom.Point2D.Float(startX,startY),
                    new java.awt.geom.Point2D.Float(startX+width*.65f,startY+width*.12f),FLUX_STOPS,stops,
                    MultipleGradientPaint.CycleMethod.REFLECT,MultipleGradientPaint.ColorSpaceType.LINEAR_RGB,
                    new AffineTransform());
        }
        fluxPalette=palette; fluxWidth=width; paintedFluxPhase=fluxPhase;
        fluxBackground=backgroundColor; fluxSelection=Ui.color(ColorRole.TERMINAL_SELECTION);
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
        while (contrast(ink,backgroundColor)<4.6 || contrast(ink,Ui.color(ColorRole.TERMINAL_SELECTION))<4.6) {
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
        return clip==null || clip.intersects(run.start*baseWidth-2,run.row*baseHeight-2,
                (run.end-run.start)*baseWidth+4,baseHeight+4);
    }

    private RenderFrame snapshot(Screen5250 screen) {
        char[] text=plane(screen,PLANE_TEXT),colors=plane(screen,PLANE_COLOR);
        char[] flags=plane(screen,PLANE_EXTENDED),attributes=plane(screen,PLANE_IS_ATTR_PLACE);
        List<RenderRun> runs=new ArrayList<>();
        boolean blinking=false;
        int cols=screen.getColumns();
        for (int row=0;row<screen.getRows();row++) {
            int col=0;
            while (col<cols) {
                int start=col,pos=row*cols+col;
                boolean hidden=attributes[pos]!=0 || (flags[pos]&EXTENDED_5250_NON_DSP)!=0;
                while (++col<cols) {
                    int next=row*cols+col;
                    if (colors[next]!=colors[pos] || flags[next]!=flags[pos]
                            || (attributes[next]!=0)!=(attributes[pos]!=0)) break;
                }
                GlyphVector glyphs=null;
                int first=pos,last=row*cols+col;
                if (!hidden) {
                    while (first<last && text[first]<=' ') first++;
                    while (last>first && text[last-1]<=' ') last--;
                    if (first<last) {
                        char[] ink=Arrays.copyOfRange(text,first,last);
                        for (int i=0;i<ink.length;i++) if (ink[i]<32) ink[i]=' ';
                        glyphs=inkFace.createGlyphVector(TEXT_CONTEXT,ink);
                        for (int i=0;i<=glyphs.getNumGlyphs();i++)
                            glyphs.setGlyphPosition(i,new java.awt.geom.Point2D.Double(i*baseWidth,0));
                    }
                }
                Path2D separators=null;
                if (!hidden && (flags[pos]&EXTENDED_5250_COL_SEP)!=0) {
                    separators=new Path2D.Double();
                    for (int x=start;x<=col;x++) {
                        separators.moveTo(x*baseWidth,(row+1)*baseHeight-3);
                        separators.lineTo(x*baseWidth,(row+1)*baseHeight);
                    }
                }
                blinking|=!hidden && (flags[pos]&EXTENDED_5250_BLINK)!=0;
                runs.add(new RenderRun(row,start,col,Math.min(15,colors[pos]&255),
                        Math.min(15,colors[pos]>>8&255),flags[pos],hidden,glyphs,first%cols,separators));
            }
        }
        return new RenderFrame(runs,blinking);
    }

    private record RenderFrame(List<RenderRun> runs,boolean blinking) {}
    private record RenderRun(int row,int start,int end,int foreground,int background,int flags,
                             boolean hidden,GlyphVector glyphs,int inkStart,Path2D separators) {}

    @Override protected double computePrefWidth(double height) {
        Screen5250 screen=pane.screen();
        int cols=screen==null ? 80 : screen.getColumns();
        if (fit) return 240;
        double sx=outputScaleX(),sy=outputScaleY();
        InkMetrics ink=metrics(Math.max(1,(int)Math.floor(16*zoomValue()*sy)));
        return Math.ceil(cols*ink.width/sx)+24;
    }

    @Override protected double computePrefHeight(double width) {
        Screen5250 screen=pane.screen();
        int rows=screen==null ? 24 : screen.getRows();
        if (fit) return 140;
        double sy=outputScaleY();
        InkMetrics ink=metrics(Math.max(1,(int)Math.floor(16*zoomValue()*sy)));
        return Math.ceil(rows*ink.height/sy)+20;
    }
}
