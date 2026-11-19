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
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
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
import fish.security.KioskMode;
import static org.tn5250j.TN5250jConstants.*;

/**
 * Hardware-backed JavaFX terminal surface using PixelBuffer<IntBuffer> + ClearType LCD GlyphVector masks.
 * Preserves snapped physical-pixel cell metrics, non-display secrecy, Soft glow, Prism, Flux, and mode-aware cursors.
 */
public class TerminalView extends Region {
    private final SessionPane pane;
    private Window observedWindow;
    private final ChangeListener<Window> windowChanged=(o,old,w) -> attachWindow(w);
    private final InvalidationListener windowVisibility=o -> { updateAnimation(); requestRender(); };
    private final InvalidationListener windowScale=o -> requestRender();
    private final ImageView surface=new ImageView();
    private PixelBuffer<IntBuffer> pixelBuffer;
    private WritableImage fxImage;
    private int[] fxPixels;
    private int surfaceW,surfaceH;
    private BufferedImage workImage;
    private int[] workPixels;
    private final ScratchMask scratchMask=new ScratchMask();
    private boolean renderScheduled;
    private boolean fullRenderPending;
    private Rectangle dirtyRenderPending;
    private long glyphHits,glyphMisses;
    private final AnimationTimer renderPulse=new AnimationTimer() {
        @Override public void handle(long now) {
            stop();
            flushPendingRender();
        }
    };

    private Font face;
    private static final int FONT_SUBSTEPS=16;
    private static final FontRenderContext TEXT_CONTEXT=new FontRenderContext(null,
            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    private static final FontRenderContext METRICS_CONTEXT=new FontRenderContext(null,
            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    private Font inkFace;
    private int fontPixels;
    private int lastPrewarmHalfPx=-100;
    private final Map<Integer,InkMetrics> fontMetrics=new HashMap<>();
    private final Map<Integer,Font> strikeFonts=new HashMap<>();
    private final Map<Font,Float> strikeAscents=new HashMap<>();
    private double nominalWidth,nominalHeight;
    private double baseWidth,baseHeight,ascent;
    private RenderFrame rendered;
    private Screen5250 renderedScreen;
    private static final int MAX_CHAR_MASKS=8192;
    private record StrikeKey(Font base,int halfPx) {}
    private static final Map<StrikeKey,Font> GLOBAL_STRIKE_FONTS=new java.util.concurrent.ConcurrentHashMap<>(128);
    private static final Map<CharGlyphKey,GlyphMask> GLOBAL_GLYPH_MASKS=new java.util.concurrent.ConcurrentHashMap<>(2048);
    private static final ThreadLocal<ScratchMask> WARMER_SCRATCH=ThreadLocal.withInitial(ScratchMask::new);
    private static volatile long activeResizeUntilNanos;
    private static final java.util.concurrent.ExecutorService GLYPH_WARMER=java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"fish-glyph-warmer");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY-1);
        return t;
    });
    private final Map<CharGlyphKey,GlyphMask> glyphMasks=new LinkedHashMap<>(256,0.75f,false) {
        @Override protected boolean removeEldestEntry(Map.Entry<CharGlyphKey,GlyphMask> eldest) {
            return size()>MAX_CHAR_MASKS;
        }
    };
    private BufferedImage sharedInk;
    private RenderFrame sharedFrame;
    private Rectangle sharedBounds;
    private int sharedFontPixels;
    private String sharedEffect;
    private int sharedCustomHash;
    private Color[] sharedPalette;
    private Color sharedBackground,sharedSelection;
    private int sharedAnchor,sharedEnd;
    private boolean sharedPhase;
    private double sharedFlux=Double.NaN;
    private double cellWidth=9,cellHeight=18,originX=12,originY=10;
    double gridTop() { return originY; }
    private double geometryWidth=-1,geometryHeight=-1,geometryRefW=-1,geometryRefH=-1;
    private double geometryDpiX=1,geometryDpiY=1,geometryZoom;
    private boolean geometryFit,revealQueued;
    private int surfaceDevX,surfaceDevY;
    private int anchor=-1,end=-1,pressCell=-1;
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
    private static final Set<TerminalView> ALL_VIEWS=Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<TerminalView> ANIMATED=new HashSet<>();
    private static final Timeline ANIMATION=createFluxTimeline();
    static {
        Runnable syncMotion=() -> {
            for (TerminalView view:new ArrayList<>(ALL_VIEWS)) view.updateAnimation();
        };
        Ui.onMotionChanged(() -> {
            if (javafx.application.Platform.isFxApplicationThread()) syncMotion.run();
            else Ui.runLater(syncMotion);
        });
    }

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
        ALL_VIEWS.add(this);
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
            if (oldScene!=null) oldScene.windowProperty().removeListener(windowChanged);
            if (disposed) return;
            if (newScene!=null) newScene.windowProperty().addListener(windowChanged);
            attachWindow(newScene==null ? null : newScene.getWindow());
            updateAnimation();
        });
        widthProperty().addListener((o,a,b) -> { requestRender(); revealCursor(); });
        heightProperty().addListener((o,a,b) -> { requestRender(); revealCursor(); });
        layoutXProperty().addListener((o,a,b) -> requestRender());
        layoutYProperty().addListener((o,a,b) -> requestRender());

        boolean[] suppressTyped={false};
        setOnKeyPressed(event -> {
            suppressTyped[0]=false;
            if (event.isControlDown() && event.isAltDown()) return;
            if (event.getCode()==KeyCode.ENTER && !event.isControlDown() && !event.isShiftDown() && !event.isAltDown()
                    && pane.recoverableInputError()) {
                suppressTyped[0]=true;
                pane.dismissResetCalloutImmediately();
                pane.send(KeyMnemonic.RESET);
                event.consume();
                return;
            }
            if (event.getCode()==KeyCode.CONTEXT_MENU && pane.keys().lookup(event)==null) {
                suppressTyped[0]=true;
                event.consume();
                Point2D screenPt=localToScreen(originX,Math.min(Math.max(1,getHeight()-1),originY+cellHeight));
                if (screenPt!=null) pane.showClipboardMenu(screenPt.getX(),screenPt.getY());
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
        double[] pressPt={0,0};
        setOnMousePressed(event -> {
            if (event.getButton()!=MouseButton.PRIMARY) return;
            pane.activate();
            requestFocus();
            paneDrag[0]=event.isAltDown() && !KioskMode.isEnabled();
            if (paneDrag[0]) {
                restartCursor();
                pane.beginAltDrag(event);
                event.consume();
                return;
            }
            pressPt[0]=event.getX();
            pressPt[1]=event.getY();
            pressCell=position(event.getX(),event.getY());
            dragging=false;
            if (anchor>=0 || end>=0) {
                anchor=end=-1;
                requestRender();
            }
            if (pane.connected() && pane.screen()!=null) {
                Screen5250 screen=pane.screen();
                char[] guiPlane=plane(screen,PLANE_EXTENDED_GRAPHIC);
                int gui=pressCell>=0 && pressCell<guiPlane.length ? guiPlane[pressCell] : 0;
                boolean hotspot=gui>=BUTTON_LEFT && gui<=BUTTON_LAST;
                if (!hotspot) {
                    repaintCursor();
                    screen.moveCursor(pressCell);
                    if (!screen.isCursorActive()) screen.setCursorActive(true);
                    pane.updateOia();
                }
            }
            restartCursor();
            event.consume();
        });
        setOnMouseDragged(event -> {
            if (!event.isPrimaryButtonDown()) return;
            event.consume();
            if (paneDrag[0]) {
                pane.updateAltDrag(event);
                return;
            }
            int pos=position(event.getX(),event.getY());
            if (!dragging) {
                if (pos==pressCell && Math.hypot(event.getX()-pressPt[0],event.getY()-pressPt[1])<4.0) return;
                dragging=true;
                anchor=pressCell>=0 ? pressCell : pos;
                end=pos;
                requestRender();
                return;
            }
            if (pos==end) return;
            end=pos;
            requestRender();
        });
        setOnMouseReleased(event -> {
            if (event.getButton()==MouseButton.SECONDARY) {
                pane.showClipboardMenu(event.getScreenX(),event.getScreenY());
                event.consume();
                return;
            }
            if (event.getButton()!=MouseButton.PRIMARY) return;
            event.consume();
            if (paneDrag[0]) {
                paneDrag[0]=false;
                pane.finishAltDrag(event);
                return;
            }
            if (!dragging && pane.screen()!=null && pane.connected()) {
                Screen5250 screen=pane.screen();
                int target=pressCell>=0 ? pressCell : position(event.getX(),event.getY());
                char[] guiPlane=plane(screen,PLANE_EXTENDED_GRAPHIC);
                int gui=target>=0 && target<guiPlane.length ? guiPlane[target] : 0;
                boolean hotspot=gui>=BUTTON_LEFT && gui<=BUTTON_LAST;
                repaintCursor();
                if (hotspot || screen.getCurrentPos()-1!=target) {
                    screen.moveCursor(target);
                }
                if (!screen.isCursorActive()) screen.setCursorActive(true);
                anchor=end=-1;
                restartCursor();
                pane.updateOia();
            }
            pressCell=-1;
            dragging=false;
        });
        setOnContextMenuRequested(event -> {
            pane.showClipboardMenu(event.getScreenX(),event.getScreenY());
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
        if (window==observedWindow) return;
        if (observedWindow!=null) {
            observedWindow.showingProperty().removeListener(windowVisibility);
            observedWindow.outputScaleXProperty().removeListener(windowScale);
            observedWindow.outputScaleYProperty().removeListener(windowScale);
            if (observedWindow instanceof Stage s) s.iconifiedProperty().removeListener(windowVisibility);
        }
        observedWindow=window;
        if (window==null) return;
        window.showingProperty().addListener(windowVisibility);
        window.outputScaleXProperty().addListener(windowScale);
        window.outputScaleYProperty().addListener(windowScale);
        if (window instanceof Stage s) s.iconifiedProperty().addListener(windowVisibility);
        requestRender();
    }

    public void setScrollRevealCallback(Runnable callback) { this.scrollRevealCallback=callback; }

    public Color getTerminalBackground() { return backgroundColor; }
    public void setTerminalBackground(Color color) {
        Objects.requireNonNull(color);
        String css="-fx-background-color: "+Ui.hex(color)+";";
        boolean colorChanged=!Objects.equals(this.backgroundColor,color);
        this.backgroundColor=color;
        Ui.setStyleIfChanged(this,css);
        if (colorChanged) requestRender();
    }

    public long glyphMaskHits() { return glyphHits; }
    public long glyphMaskMisses() { return glyphMisses; }
    public void resetGlyphStats() { glyphHits=0; glyphMisses=0; }
    public int[] surfacePixelsForTest() {
        if (fxPixels==null || pixelBuffer==null || surfaceW<=0 || surfaceH<=0) return fxPixels;
        int stride=pixelBuffer.getWidth();
        if (stride==surfaceW && fxPixels.length==surfaceW*surfaceH) return fxPixels;
        int[] out=new int[surfaceW*surfaceH];
        for (int r=0;r<surfaceH;r++) System.arraycopy(fxPixels,r*stride,out,r*surfaceW,surfaceW);
        return out;
    }

    public void dispose() {
        disposed=true;
        if (getScene()!=null) getScene().windowProperty().removeListener(windowChanged);
        attachWindow(null);
        renderPulse.stop();
        renderScheduled=false;
        blink.stop();
        cursorBlink.stop();
        stopAnimation();
        glyphMasks.clear();
        sharedInk=null;
        sharedFrame=null;
        workImage=null;
        workPixels=null;
        scratchMask.dispose();
        fxPixels=null;
    }

    public void restartCursor() {
        if (disposed) return;
        cursorPhase=true;
        repaintCursor();
        if (cursorReady()) cursorBlink.playFromStart();
        else cursorBlink.stop();
    }

    protected boolean hasTerminalFocus() {
        if (isFocused()) return true;
        javafx.scene.Scene s=getScene();
        if (s==null || s.getFocusOwner()!=this) return false;
        javafx.stage.Window w=s.getWindow();
        return w==null || !w.isShowing();
    }
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

    private static int colX(int col,double bw) { return (int)Math.rint(col*bw); }
    private static int rowY(int row,double bh) { return (int)Math.rint(row*bh); }
    private int colX(int col) { return (int)Math.rint(col*baseWidth); }
    private int rowY(int row) { return (int)Math.rint(row*baseHeight); }
    private int baselineY(int row) { return rowY(row)+(int)ascent; }

    private Rectangle cursorBounds() {
        Screen5250 screen=pane.screen();
        if (screen==null) return null;
        int cols=screen.getColumns();
        int pos=Math.max(0,Math.min(screen.getCurrentPos()-1,screen.getScreenLength()-1));
        int col=pos%cols,row=pos/cols;
        double dpiX=geometryDpiX>0 ? geometryDpiX : 1.0,dpiY=geometryDpiY>0 ? geometryDpiY : 1.0;
        int x0=colX(col),x1=colX(col+1),y0=rowY(row),y1=rowY(row+1);
        return new Rectangle((int)Math.floor(originX+x0/dpiX)-2,
                (int)Math.floor(originY+y0/dpiY)-2,
                (int)Math.ceil((x1-x0)/dpiX)+5,(int)Math.ceil((y1-y0)/dpiY)+5);
    }

    protected void recordDirtyRect(Rectangle rect) {}

    private void repaintCursor() {
        Rectangle next=cursorBounds();
        if (lastCursor!=null) { recordDirtyRect(lastCursor); requestDirtyRender(lastCursor); }
        if (next!=null) { recordDirtyRect(next); requestDirtyRender(next); }
        lastCursor=next;
    }

    private boolean canAnimate() {
        return !Ui.reducedMotion() && isTerminalShowing() && pane.connected() && pane.profile().effect().equals(Profile.FLUX);
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

    public void setFit(boolean value) { fit=value; geometryWidth=-1; pane.syncScrollViewport(); requestLayout(); requestRender(); revealCursor(); }
    public boolean isFit() { return fit; }
    public boolean hasExplicitZoom() { return savedZoom!=null; }
    public double zoomValue() { return savedZoom==null ? pane.profile().zoom() : savedZoom; }
    public void setRelativeZoom(double zoomFactor,boolean fitMode) {
        savedZoom=fitMode ? 1.0 : Math.max(0.5,Math.min(3.0,zoomFactor));
        fit=fitMode;
        geometryWidth=-1;
        pane.syncScrollViewport();
        requestLayout();
        requestRender();
        revealCursor();
    }
    public void onStageResized() {
        geometryWidth=-1;
        requestLayout();
        requestRender();
    }
    public void restoreDisplay(SavedLayout.Display display) {
        savedZoom=display.fit() ? 1.0 : display.zoom();
        geometryWidth=-1;
        setFit(display.fit());
    }
    public void clearSavedZoom() { savedZoom=null; geometryWidth=-1; }
    public void screenChanged() {
        rendered=null; sharedFrame=null; geometryWidth=-1;
        fluxPaints=null; updateAnimation(); requestLayout(); requestRender();
        revealCursor();
    }
    public void displayChanged() { sharedFrame=null; sharedPalette=null; fluxPaints=null; updateAnimation(); requestRender(); }
    public void themeChanged() {
        setTerminalBackground(Ui.customTheme() ? Ui.color(ColorRole.HOST_0) : pane.paneSurfaceColor());
        displayChanged();
    }
    public void fontChanged() {
        face=pane.workspaceFont();
        Font ref=face.deriveFont(16f);
        nominalWidth=ref.getStringBounds("M",METRICS_CONTEXT).getWidth();
        nominalHeight=ref.getLineMetrics("M",METRICS_CONTEXT).getHeight();
        fontMetrics.clear(); strikeFonts.clear(); strikeAscents.clear(); glyphMasks.clear(); fontPixels=0; lastPrewarmHalfPx=-100; geometryWidth=-1;
        configureInk(metrics(16*FONT_SUBSTEPS));
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
        double dpiX=geometryDpiX>0 ? geometryDpiX : 1.0,dpiY=geometryDpiY>0 ? geometryDpiY : 1.0;
        double dx=(px-originX)*dpiX,dy=(py-originY)*dpiY;
        int col=Math.max(0,Math.min(cols-1,(int)Math.floor(dx/Math.max(1e-6,baseWidth))));
        while (col+1<cols && dx>=colX(col+1)) col++;
        while (col>0 && dx<colX(col)) col--;
        int row=Math.max(0,Math.min(rows-1,(int)Math.floor(dy/Math.max(1e-6,baseHeight))));
        while (row+1<rows && dy>=rowY(row+1)) row++;
        while (row>0 && dy<rowY(row)) row--;
        return row*cols+col;
    }

    private void ensureGeometry() {
        double lw=Math.max(1.0,getWidth()),lh=Math.max(1.0,getHeight());
        double refW=pane.referenceStageWidth(lw),refH=pane.referenceStageHeight(lh);
        if (Math.abs(geometryWidth-lw)>1e-4 || Math.abs(geometryHeight-lh)>1e-4
                || Math.abs(geometryRefW-refW)>1e-4 || Math.abs(geometryRefH-refH)>1e-4
                || geometryFit!=fit || geometryZoom!=zoomValue())
            geometry(geometryDpiX,geometryDpiY);
    }

    public Rectangle currentCursorLogicalBounds() {
        Screen5250 screen=pane.screen();
        if (screen==null) return null;
        ensureGeometry();
        int cols=screen.getColumns();
        int pos=Math.max(0,Math.min(screen.getCurrentPos()-1,screen.getScreenLength()-1));
        int col=pos%cols,row=pos/cols;
        double dpiX=geometryDpiX>0 ? geometryDpiX : 1.0,dpiY=geometryDpiY>0 ? geometryDpiY : 1.0;
        int x0=colX(col),x1=colX(col+1),y0=rowY(row),y1=rowY(row+1);
        return new Rectangle((int)Math.floor(originX+x0/dpiX),
                (int)Math.floor(originY+y0/dpiY),
                (int)Math.ceil((x1-x0)/dpiX)+1,(int)Math.ceil((y1-y0)/dpiY)+1);
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

    private int computeTargetStep(double refW,double refH,int rows,int cols,double dpiX,double dpiY) {
        double availW=Math.max(cols,(refW-24.0)*dpiX);
        double availH=Math.max(rows*2.0,(refH-20.0)*dpiY);
        double fitScale=Math.max(3.0/16.0,Math.min(28.0/16.0,
                Math.min((refW-24.0)/(cols*nominalWidth),(refH-20.0)/(rows*nominalHeight))));
        double exactFitStep=Math.max(FONT_SUBSTEPS,16.0*fitScale*dpiY*FONT_SUBSTEPS);
        int fitStep=Math.max(FONT_SUBSTEPS,(int)Math.floor(exactFitStep));
        if (fit && fontPixels>=FONT_SUBSTEPS && geometryFit && geometryDpiX==dpiX && geometryDpiY==dpiY) {
            if (fitStep==fontPixels+1 && exactFitStep<fontPixels+1.35) {
                fitStep=fontPixels;
            } else if (fitStep==fontPixels-1 && exactFitStep>fontPixels-0.35) {
                InkMetrics keep=metrics(fontPixels);
                if (colX(cols,keep.width)<=Math.ceil(availW) && rowY(rows,keep.height)<=Math.ceil(availH)) {
                    fitStep=fontPixels;
                }
            }
        }
        InkMetrics fitTarget=metrics(fitStep);
        while (fitStep>FONT_SUBSTEPS
                && (colX(cols,fitTarget.width)>Math.ceil(availW) || rowY(rows,fitTarget.height)>Math.ceil(availH))) {
            fitTarget=metrics(--fitStep);
        }
        if (fit) return fitStep;
        int scaled=(int)Math.round(fitStep*zoomValue());
        return Math.max(FONT_SUBSTEPS*3,Math.min(FONT_SUBSTEPS*240,scaled));
    }

    private void geometry(double dpiX,double dpiY) {
        double lw=Math.max(1.0,getWidth()),lh=Math.max(1.0,getHeight());
        double refW=pane.referenceStageWidth(lw),refH=pane.referenceStageHeight(lh);
        Screen5250 screen=pane.screen();
        int rows=screen==null ? 24 : screen.getRows(),cols=screen==null ? 80 : screen.getColumns();
        int step=computeTargetStep(refW,refH,rows,cols,dpiX,dpiY);
        InkMetrics target=metrics(step);
        configureInk(target);
        cellWidth=baseWidth/dpiX; cellHeight=baseHeight/dpiY;
        double gridLogicalW=colX(cols)/dpiX,gridLogicalH=rowY(rows)/dpiY;
        originX=Math.rint(Math.max(12.0,(lw-gridLogicalW)*0.5)*dpiX)/dpiX;
        originY=Math.rint(Math.max(10.0,(lh-gridLogicalH)*0.5)*dpiY)/dpiY;
        geometryWidth=lw; geometryHeight=lh; geometryRefW=refW; geometryRefH=refH;
        geometryFit=fit; geometryZoom=zoomValue();
        geometryDpiX=dpiX; geometryDpiY=dpiY;
    }

    private double outputScaleX() {
        if (Ui.scaleOverride()>0) return Ui.scaleOverride();
        if (getScene()!=null && getScene().getWindow()!=null) {
            Window w=getScene().getWindow();
            double rs=w.getRenderScaleX();
            double os=w.getOutputScaleX();
            if (rs>0 && rs!=1.0) return rs;
            if (os>0) return os;
            if (rs>0) return rs;
        }
        return 1.0;
    }

    private double outputScaleY() {
        if (Ui.scaleOverride()>0) return Ui.scaleOverride();
        if (getScene()!=null && getScene().getWindow()!=null) {
            Window w=getScene().getWindow();
            double rs=w.getRenderScaleY();
            double os=w.getOutputScaleY();
            if (rs>0 && rs!=1.0) return rs;
            if (os>0) return os;
            if (rs>0) return rs;
        }
        return 1.0;
    }

    private void snapSurfaceToDeviceGrid(double sx,double sy) {
        Point2D scenePt=localToScene(0,0);
        double snapX=0,snapY=0;
        if (scenePt!=null) {
            snapX=(Math.round(scenePt.getX()*sx)-scenePt.getX()*sx)/sx;
            snapY=(Math.round(scenePt.getY()*sy)-scenePt.getY()*sy)/sy;
        }
        surface.relocate(surfaceDevX/sx+snapX,surfaceDevY/sy+snapY);
    }

    void snapToDeviceGridNow() {
        snapSurfaceToDeviceGrid(outputScaleX(),outputScaleY());
    }

    @Override protected void layoutChildren() {
        snapSurfaceToDeviceGrid(outputScaleX(),outputScaleY());
        requestRender();
    }

    private boolean windowShowing() {
        return getScene()!=null && getScene().getWindow()!=null && getScene().getWindow().isShowing();
    }

    public void requestRender() {
        if (disposed || !windowShowing()) return;
        fullRenderPending=true;
        dirtyRenderPending=null;
        scheduleRenderPulse();
    }

    private void requestDirtyRender(Rectangle logicalDirty) {
        if (disposed || !windowShowing() || logicalDirty==null) return;
        if (!fullRenderPending) {
            if (dirtyRenderPending==null) dirtyRenderPending=new Rectangle(logicalDirty);
            else dirtyRenderPending.add(logicalDirty);
        }
        scheduleRenderPulse();
    }

    private void scheduleRenderPulse() {
        if (!renderScheduled) {
            renderScheduled=true;
            renderPulse.start();
        }
    }

    private void flushPendingRender() {
        renderScheduled=false;
        if (disposed || !windowShowing()) {
            fullRenderPending=false;
            dirtyRenderPending=null;
            return;
        }
        boolean full=fullRenderPending;
        Rectangle dirty=dirtyRenderPending;
        fullRenderPending=false;
        dirtyRenderPending=null;
        renderBuffer(full ? null : dirty);
    }

    public void forceSurfaceRender() {
        renderPulse.stop();
        renderScheduled=false;
        fullRenderPending=false;
        dirtyRenderPending=null;
        renderBuffer(null);
    }

    private void renderBuffer(Rectangle logicalDirty) {
        if (disposed) return;
        double lw=getWidth(),lh=getHeight();
        if (lw<=1 || lh<=1) return;
        double sx=outputScaleX(),sy=outputScaleY();
        double[] vp=pane.viewportInView(lw,lh);
        double visX=vp[0],visY=vp[1],visW=vp[2],visH=vp[3];
        int totalDevW=Math.max(1,(int)Math.ceil(lw*sx));
        int totalDevH=Math.max(1,(int)Math.ceil(lh*sy));
        int devVisX=Math.max(0,Math.min(totalDevW-1,(int)Math.floor(visX*sx)));
        int devVisY=Math.max(0,Math.min(totalDevH-1,(int)Math.floor(visY*sy)));
        int devVisRight=Math.max(devVisX+1,Math.min(totalDevW,(int)Math.ceil((visX+visW)*sx)));
        int devVisBottom=Math.max(devVisY+1,Math.min(totalDevH,(int)Math.ceil((visY+visH)*sy)));
        int pw=Math.max(1,devVisRight-devVisX);
        int ph=Math.max(1,devVisBottom-devVisY);
        int allocW=(pw+255)&~255;
        int allocH=(ph+255)&~255;
        boolean recreated=false;
        if (fxPixels==null || pixelBuffer==null || pixelBuffer.getWidth()<allocW || pixelBuffer.getHeight()<allocH
                || pixelBuffer.getWidth()>allocW+512 || pixelBuffer.getHeight()>allocH+512) {
            fxPixels=new int[allocW*allocH];
            IntBuffer buf=IntBuffer.wrap(fxPixels);
            pixelBuffer=new PixelBuffer<>(allocW,allocH,buf,PixelFormat.getIntArgbPreInstance());
            fxImage=new WritableImage(pixelBuffer);
            surface.setImage(fxImage);
            recreated=true;
        }
        if (surfaceW!=pw || surfaceH!=ph) {
            if (surfaceW>0) activeResizeUntilNanos=System.nanoTime()+250_000_000L;
            surfaceW=pw;
            surfaceH=ph;
            surface.setViewport(new javafx.geometry.Rectangle2D(0,0,pw,ph));
            recreated=true;
        }
        if (surfaceDevX!=devVisX || surfaceDevY!=devVisY) {
            surfaceDevX=devVisX;
            surfaceDevY=devVisY;
            recreated=true;
        }
        if (workImage==null || workImage.getWidth()<allocW || workImage.getHeight()<allocH
                || workImage.getWidth()>allocW+512 || workImage.getHeight()>allocH+512) {
            workImage=new BufferedImage(allocW,allocH,BufferedImage.TYPE_INT_ARGB_PRE);
            workPixels=((DataBufferInt)workImage.getRaster().getDataBuffer()).getData();
        }
        surface.getTransforms().setAll(javafx.scene.transform.Transform.scale(1.0/sx,1.0/sy));
        snapSurfaceToDeviceGrid(sx,sy);

        int dx,dy,dw,dh;
        if (recreated || logicalDirty==null) {
            dx=0; dy=0; dw=pw; dh=ph;
        } else {
            int rx0=(int)Math.floor(logicalDirty.x*sx)-devVisX-1;
            int ry0=(int)Math.floor(logicalDirty.y*sy)-devVisY-1;
            int rx1=(int)Math.ceil((logicalDirty.x+logicalDirty.width)*sx)-devVisX+2;
            int ry1=(int)Math.ceil((logicalDirty.y+logicalDirty.height)*sy)-devVisY+2;
            dx=Math.max(0,rx0);
            dy=Math.max(0,ry0);
            dw=Math.min(pw,rx1)-dx;
            dh=Math.min(ph,ry1)-dy;
            if (dw<=0 || dh<=0) return;
        }

        Graphics2D g=workImage.createGraphics();
        try {
            g.setClip(dx,dy,dw,dh);
            g.translate(-devVisX,-devVisY);
            g.scale(sx,sy);
            if (!recreated && logicalDirty!=null) {
                g.clip(logicalDirty);
            }
            paintSurface(g,devVisX/sx,devVisY/sy,pw/sx,ph/sy);
        } finally {
            g.dispose();
        }
        final int workStride=workImage.getWidth();
        final int fxStride=pixelBuffer.getWidth();
        final int copyX=dx,copyY=dy,copyW=dw,copyH=dh;
        final int[] src=workPixels,dst=fxPixels;
        pixelBuffer.updateBuffer(pb -> {
            for (int row=0;row<copyH;row++) {
                System.arraycopy(src,(copyY+row)*workStride+copyX,dst,(copyY+row)*fxStride+copyX,copyW);
            }
            return new javafx.geometry.Rectangle2D(copyX,copyY,copyW,copyH);
        });
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
        paintSurface((Graphics2D)graphics,0,0,lw,lh);
    }

    private void paintSurface(Graphics2D graphics,double viewX,double viewY,double viewW,double viewH) {
        Graphics2D g=(Graphics2D)graphics.create();
        try {
            g.setColor(backgroundColor);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
            g.fillRect((int)Math.floor(viewX)-1,(int)Math.floor(viewY)-1,(int)Math.ceil(viewW)+3,(int)Math.ceil(viewH)+3);
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
                sharedFrame=null;
                fluxPaints=null;
                lastPrewarmHalfPx=-100;
            }
            int curHalfPx=Math.max(6,(int)Math.floor((fontPixels/(float)FONT_SUBSTEPS)*2.0f+0.001f));
            if (System.nanoTime()>=activeResizeUntilNanos && Math.abs(curHalfPx-lastPrewarmHalfPx)>=3) {
                lastPrewarmHalfPx=curHalfPx;
                scheduleGlyphPrewarm(face,fontPixels,rendered);
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
                String effect=pane.profile().effect();
                if (effect.equals(Profile.FLUX)) prepareFlux(palette,cols);
                paintSharedInk(cells,palette,effect,rows,cols,phase,dpiX,dpiY,viewX,viewY,viewW,viewH);
                if (hasTerminalFocus() && pane.connected() && screen.isCursorActive() && cursorPhase)
                    paintCursor(cells,screen,rows,cols);
            } finally { cells.dispose(); }
        } finally { g.dispose(); }
    }

    private void paintCursor(Graphics2D cells,Screen5250 screen,int rows,int cols) {
        int pos=Math.max(0,Math.min(screen.getCurrentPos()-1,rows*cols-1));
        int col=pos%cols,row=pos/cols;
        int x=colX(col),y=rowY(row);
        int width=Math.max(1,colX(col+1)-x),height=Math.max(1,rowY(row+1)-y);
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
            if (run.text!=null && col>=run.inkStart && col<run.inkStart+run.text.length()) {
                char ch=run.text.charAt(col-run.inkStart);
                if (ch>' ') {
                    GlyphMask mask=charMask(fontPixels,inkFace,ch);
                    int left=mask.left,top=(int)ascent+mask.top;
                    for (int n=0;n<mask.positions.length;n++) {
                        int pixel=mask.positions[n],px=left+pixel%mask.width,py=top+pixel/mask.width;
                        if (px>=0 && px<width && py>=0 && py<height)
                            output[py*width+px]=blendInk(fill.getRGB(),ink.getRGB(),mask.coverage[n]);
                    }
                }
            }
            Graphics2D details=image.createGraphics();
            try {
                details.setColor(ink); details.translate(-x,-y);
                paintRunDecorations(details,run);
            } finally { details.dispose(); }
        }
        cells.drawImage(image,x,y,null);
    }

    private void paintRunDecorations(Graphics2D g,RenderRun run) {
        if ((run.flags&EXTENDED_5250_UNDERLINE)!=0) {
            int ux=colX(run.start);
            int uw=Math.max(1,colX(run.end)-ux);
            int uy=Math.min(baselineY(run.row)+1,rowY(run.row+1)-1);
            g.fillRect(ux,uy,uw,1);
        }
        if (!run.hidden && (run.flags&EXTENDED_5250_COL_SEP)!=0) {
            int sy=rowY(run.row+1)-3;
            for (int c=run.start;c<=run.end;c++) {
                g.fillRect(colX(c),sy,1,3);
            }
        }
    }

    private record InkMetrics(int step,Font font,double width,double height,double ascent) {}
    private InkMetrics metrics(int step) {
        return fontMetrics.computeIfAbsent(step,s -> {
            float size=s/(float)FONT_SUBSTEPS;
            int halfPx=Math.max(6,(int)Math.floor(size*2.0f+0.001f));
            Font font=strikeFonts.computeIfAbsent(halfPx,hp ->
                    GLOBAL_STRIKE_FONTS.computeIfAbsent(new StrikeKey(face,hp),k -> k.base.deriveFont(k.halfPx*0.5f)));
            float lineAscent=strikeAscents.computeIfAbsent(font,f -> f.getLineMetrics("M",METRICS_CONTEXT).getAscent());
            double w=Math.max(1.0,nominalWidth*(size/16.0));
            double h=Math.max(2.0,nominalHeight*(size/16.0));
            double asc=Math.min(Math.max(1.0,Math.floor(h)-1.0),Math.max(1.0,Math.rint(lineAscent)));
            return new InkMetrics(s,font,w,h,asc);
        });
    }
    private void configureInk(InkMetrics metrics) {
        if (fontPixels==metrics.step) return;
        fontPixels=metrics.step; inkFace=metrics.font;
        baseWidth=metrics.width; baseHeight=metrics.height; ascent=metrics.ascent;
        sharedFrame=null; fluxPaints=null;
    }
    private static final class ScratchMask {
        BufferedImage image;
        Graphics2D graphics;
        int[] pixels;
        void ensure(int mw,int mh) {
            if (image==null || image.getWidth()<mw || image.getHeight()<mh) {
                int aw=Math.max(mw,image==null ? 64 : image.getWidth());
                int ah=Math.max(mh,image==null ? 64 : image.getHeight());
                if (graphics!=null) graphics.dispose();
                image=new BufferedImage(aw,ah,BufferedImage.TYPE_INT_RGB);
                pixels=((DataBufferInt)image.getRaster().getDataBuffer()).getData();
                graphics=image.createGraphics();
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
                graphics.setColor(Color.WHITE);
            }
        }
        void dispose() {
            if (graphics!=null) { graphics.dispose(); graphics=null; }
            image=null; pixels=null;
        }
    }
    private record CharGlyphKey(Font font,char ch) {}
    private record GlyphMask(int left,int top,int width,int[] positions,int[] coverage) {}
    private static GlyphMask rasterizeGlyphMask(Font font,char ch,ScratchMask scratch) {
        GlyphVector vector=font.createGlyphVector(TEXT_CONTEXT,new char[]{ch});
        Rectangle bounds=vector.getPixelBounds(TEXT_CONTEXT,0,0);
        bounds.grow(2,2);
        int mw=Math.max(1,bounds.width),mh=Math.max(1,bounds.height);
        scratch.ensure(mw,mh);
        int stride=scratch.image.getWidth();
        for (int r=0;r<mh;r++) Arrays.fill(scratch.pixels,r*stride,r*stride+mw,0);
        scratch.graphics.setClip(0,0,mw,mh);
        scratch.graphics.drawGlyphVector(vector,-bounds.x,-bounds.y);
        int count=0;
        for (int r=0;r<mh;r++) {
            int rowBase=r*stride;
            for (int c=0;c<mw;c++) if ((scratch.pixels[rowBase+c]&0xffffff)!=0) count++;
        }
        int[] positions=new int[count],coverage=new int[count];
        for (int r=0,n=0;r<mh;r++) {
            int rowBase=r*stride;
            for (int c=0;c<mw;c++) {
                int cov=scratch.pixels[rowBase+c]&0xffffff;
                if (cov!=0) {
                    positions[n]=r*mw+c;
                    coverage[n++]=cov;
                }
            }
        }
        return new GlyphMask(bounds.x,bounds.y,mw,positions,coverage);
    }
    private static void scheduleGlyphPrewarm(Font baseFace,int currentStep,RenderFrame frame) {
        if (baseFace==null || frame==null) return;
        java.util.BitSet seen=new java.util.BitSet(256);
        StringBuilder unique=new StringBuilder(96);
        for (RenderRun run:frame.runs) {
            if (run.hidden || run.text==null) continue;
            for (int i=0,len=run.text.length();i<len;i++) {
                char ch=run.text.charAt(i);
                if (ch>' ' && !seen.get(ch)) { seen.set(ch); unique.append(ch); }
            }
        }
        if (unique.isEmpty()) return;
        String chars=unique.toString();
        int center=Math.max(6,(int)Math.floor((currentStep/(float)FONT_SUBSTEPS)*2.0f+0.001f));
        GLYPH_WARMER.execute(() -> {
            int[] deltas={-1,1,-2,2,-3,3,-4,4,-5,5,-6,6,-7,7,-8,8};
            ScratchMask scratch=WARMER_SCRATCH.get();
            for (int delta:deltas) {
                int hp=center+delta;
                if (hp<10 || hp>64 || GLOBAL_GLYPH_MASKS.size()>=32768) continue;
                Font strike=GLOBAL_STRIKE_FONTS.computeIfAbsent(new StrikeKey(baseFace,hp),k -> k.base.deriveFont(k.halfPx*0.5f));
                for (int i=0,len=chars.length();i<len;i++) {
                    while (System.nanoTime()<activeResizeUntilNanos) {
                        try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    }
                    char ch=chars.charAt(i);
                    CharGlyphKey key=new CharGlyphKey(strike,ch);
                    if (GLOBAL_GLYPH_MASKS.containsKey(key)) continue;
                    GlyphMask mask=rasterizeGlyphMask(strike,ch,scratch);
                    if (GLOBAL_GLYPH_MASKS.size()<32768) GLOBAL_GLYPH_MASKS.putIfAbsent(key,mask);
                }
            }
        });
    }
    private GlyphMask charMask(int step,Font font,char ch) {
        CharGlyphKey key=new CharGlyphKey(font,ch);
        GlyphMask existing=glyphMasks.get(key);
        if (existing!=null) {
            glyphHits++;
            return existing;
        }
        GlyphMask shared=GLOBAL_GLYPH_MASKS.get(key);
        if (shared!=null) {
            glyphHits++;
            glyphMasks.put(key,shared);
            return shared;
        }
        glyphMisses++;
        GlyphMask created=rasterizeGlyphMask(font,ch,scratchMask);
        glyphMasks.put(key,created);
        if (GLOBAL_GLYPH_MASKS.size()<32768) GLOBAL_GLYPH_MASKS.put(key,created);
        return created;
    }

    private static int customEffectHash() {
        if (!Ui.customTheme()) return 0;
        int h=Ui.color(ColorRole.GLOW).getRGB();
        for (int i=1;i<=6;i++) h=h*31+Ui.color(ColorRole.valueOf("GRADIENT_"+i)).getRGB();
        return h;
    }

    private void paintSharedInk(Graphics2D cells,Color[] palette,String effect,int rows,int cols,boolean visiblePhase,
                                double dpiX,double dpiY,double viewX,double viewY,double viewW,double viewH) {
        Rectangle bounds=new Rectangle((int)Math.floor((viewX-originX)*dpiX)-2,(int)Math.floor((viewY-originY)*dpiY)-2,
                (int)Math.ceil(viewW*dpiX)+6,(int)Math.ceil(viewH*dpiY)+6);
        bounds=bounds.intersection(new Rectangle(-4,-4,colX(cols)+8,rowY(rows)+8));
        if (bounds.isEmpty()) return;
        boolean lit=visiblePhase || !rendered.blinking;
        double time=effect.equals(Profile.FLUX) ? fluxPhase : -1;
        int customHash=customEffectHash();
        boolean changed=sharedInk==null || sharedFrame!=rendered || sharedFontPixels!=fontPixels
                || !bounds.equals(sharedBounds) || sharedPalette!=palette || !Objects.equals(effect,sharedEffect)
                || customHash!=sharedCustomHash || !backgroundColor.equals(sharedBackground)
                || !Ui.color(ColorRole.TERMINAL_SELECTION).equals(sharedSelection)
                || anchor!=sharedAnchor || end!=sharedEnd || sharedPhase!=lit || Double.compare(sharedFlux,time)!=0;
        if (changed) {
            int allocInkW=(bounds.width+127)&~127;
            int allocInkH=(bounds.height+127)&~127;
            if (sharedInk==null || sharedInk.getWidth()<allocInkW || sharedInk.getHeight()<allocInkH
                    || sharedInk.getWidth()>allocInkW+512 || sharedInk.getHeight()>allocInkH+512) {
                sharedInk=new BufferedImage(allocInkW,allocInkH,BufferedImage.TYPE_INT_ARGB_PRE);
            }
            int sharedStride=sharedInk.getWidth();
            int bw=bounds.width,bh=bounds.height;
            Graphics2D ink=sharedInk.createGraphics();
            PaintContext[] gradients=new PaintContext[palette.length];
            try {
                ink.setClip(0,0,bw,bh);
                ink.setColor(backgroundColor); ink.fillRect(0,0,bw,bh);
                ink.translate(-bounds.x,-bounds.y); paintBackgrounds(ink,palette,cols,bounds);
                int[] output=((DataBufferInt)sharedInk.getRaster().getDataBuffer()).getData();
                double prismSpan=Math.max(1,colX(cols));
                for (RenderRun run:rendered.runs) {
                    if (run.hidden || !intersects(run,bounds) || ((run.flags&EXTENDED_5250_BLINK)!=0 && !visiblePhase)) continue;
                    if (run.text!=null) {
                        int baseRowY=baselineY(run.row);
                        Color fgColor=palette[run.foreground];
                        int foreground=fgColor.getRGB();
                        boolean softGlow=effect.equals("Soft glow") && run.background==0;
                        boolean prism=effect.equals("Prism") && run.background==0;
                        PaintContext gradient=null;
                        if (effect.equals(Profile.FLUX) && run.background==0) {
                            gradient=gradients[run.foreground];
                            if (gradient==null) {
                                gradient=fluxPaints[run.foreground].createContext(ColorModel.getRGBdefault(),bounds,bounds,
                                        new AffineTransform(),cells.getRenderingHints());
                                gradients[run.foreground]=gradient;
                            }
                        }
                        if (softGlow) {
                            int glowRgb=(Ui.customTheme() ? Ui.color(ColorRole.GLOW) : fgColor).getRGB();
                            for (int i=0,len=run.text.length();i<len;i++) {
                                char ch=run.text.charAt(i);
                                if (ch<=' ') continue;
                                int col=run.inkStart+i;
                                if (colX(col+1)<bounds.x-16 || colX(col)>bounds.x+bw+16) continue;
                                GlyphMask mask=charMask(fontPixels,inkFace,ch);
                                int baseX=colX(col)+mask.left-bounds.x,baseY=baseRowY+mask.top-bounds.y;
                                for (int n=0;n<mask.positions.length;n++) {
                                    int pixel=mask.positions[n],cov=mask.coverage[n];
                                    int px=baseX+pixel%mask.width,py=baseY+pixel/mask.width;
                                    if (py>=0 && py<bh) {
                                        int rowOff=py*sharedStride;
                                        if (px-1>=0 && px-1<bw) output[rowOff+px-1]=blendGlow(output[rowOff+px-1],glowRgb,cov);
                                        if (px+1>=0 && px+1<bw) output[rowOff+px+1]=blendGlow(output[rowOff+px+1],glowRgb,cov);
                                    }
                                    if (px>=0 && px<bw) {
                                        if (py-1>=0 && py-1<bh) output[(py-1)*sharedStride+px]=blendGlow(output[(py-1)*sharedStride+px],glowRgb,cov);
                                        if (py+1>=0 && py+1<bh) output[(py+1)*sharedStride+px]=blendGlow(output[(py+1)*sharedStride+px],glowRgb,cov);
                                    }
                                }
                            }
                        }
                        int prismStartRgb=foreground,prismEndRgb=foreground;
                        if (prism) {
                            float[] hsb=Color.RGBtoHSB(fgColor.getRed(),fgColor.getGreen(),fgColor.getBlue(),null);
                            Color prismStart=Ui.customTheme() ? effectColor(fgColor,0) : fgColor;
                            Color prismEnd=Ui.customTheme() ? effectColor(fgColor,.5)
                                    : Color.getHSBColor((hsb[0]+.26f)%1,Math.max(.35f,hsb[1]),hsb[2]);
                            prismStartRgb=prismStart.getRGB(); prismEndRgb=prismEnd.getRGB();
                        }
                        for (int i=0,len=run.text.length();i<len;i++) {
                            char ch=run.text.charAt(i);
                            if (ch<=' ') continue;
                            int col=run.inkStart+i;
                            if (colX(col+1)<bounds.x-16 || colX(col)>bounds.x+bw+16) continue;
                            GlyphMask mask=charMask(fontPixels,inkFace,ch);
                            int left=colX(col)+mask.left,top=baseRowY+mask.top;
                            int charFg=prism
                                    ? ColorTheme.mix(prismStartRgb,prismEndRgb,Math.max(0.0,Math.min(1.0,(colX(col)+colX(col+1))*0.5/prismSpan)))
                                    : foreground;
                            int[] colors=null;
                            if (gradient!=null && mask.positions.length>0) {
                                int height=mask.positions[mask.positions.length-1]/mask.width+1;
                                Raster raster=gradient.getRaster(left,top,mask.width,height);
                                colors=(int[])raster.getDataElements(raster.getMinX(),raster.getMinY(),mask.width,height,null);
                            }
                            for (int n=0;n<mask.positions.length;n++) {
                                int pixel=mask.positions[n],x=left+pixel%mask.width-bounds.x,y=top+pixel/mask.width-bounds.y;
                                if (x<0 || y<0 || x>=bw || y>=bh) continue;
                                int index=y*sharedStride+x;
                                output[index]=blendInk(output[index],colors==null ? charFg : colors[pixel],mask.coverage[n]);
                            }
                        }
                    }
                    ink.setColor(palette[run.foreground]);
                    paintRunDecorations(ink,run);
                }
            } finally {
                for (PaintContext context:gradients) if (context!=null) context.dispose();
                ink.dispose();
            }
            sharedFrame=rendered; sharedFontPixels=fontPixels;
            sharedBounds=bounds; sharedPalette=palette; sharedEffect=effect; sharedCustomHash=customHash;
            sharedBackground=backgroundColor; sharedSelection=Ui.color(ColorRole.TERMINAL_SELECTION);
            sharedAnchor=anchor; sharedEnd=end; sharedPhase=lit; sharedFlux=time;
        }
        java.awt.Composite prevComp=cells.getComposite();
        cells.setComposite(java.awt.AlphaComposite.Src);
        cells.drawImage(sharedInk,bounds.x,bounds.y,bounds.x+bounds.width,bounds.y+bounds.height,0,0,bounds.width,bounds.height,null);
        cells.setComposite(prevComp);
    }

    private static int blendGlow(int background,int glowRgb,int coverage) {
        int result=0xff000000;
        for (int shift=0;shift<=16;shift+=8) {
            int alpha=((coverage>>shift&255)*80+127)/255;
            int back=background>>shift&255,front=glowRgb>>shift&255;
            result|=((back*(255-alpha)+front*alpha+127)/255)<<shift;
        }
        return result;
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
                int x0=colX(run.start),y0=rowY(run.row);
                cells.setColor(palette[run.background]);
                cells.fillRect(x0,y0,colX(run.end)-x0,rowY(run.row+1)-y0);
            }
        }
        if (anchor>=0 && end>=0) {
            int c0=Math.min(anchor%cols,end%cols),c1=Math.max(anchor%cols,end%cols)+1;
            int r0=Math.min(anchor/cols,end/cols),r1=Math.max(anchor/cols,end/cols)+1;
            int x0=colX(c0),y0=rowY(r0);
            cells.setColor(Ui.color(ColorRole.TERMINAL_SELECTION));
            cells.fillRect(x0,y0,colX(c1)-x0,rowY(r1)-y0);
        }
    }

    private void prepareFlux(Color[] palette,int cols) {
        int width=Math.max(1,colX(cols));
        if (fluxPaints!=null && paintedFluxPhase==fluxPhase && fluxPalette==palette && fluxWidth==width
                && backgroundColor.equals(fluxBackground) && Ui.color(ColorRole.TERMINAL_SELECTION).equals(fluxSelection)) return;
        fluxPaints=new Paint[palette.length];
        for (RenderRun run:rendered.runs) {
            int color=run.foreground;
            if (run.hidden || run.text==null || run.background!=0 || fluxPaints[color]!=null) continue;
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
        int x0=colX(run.start),y0=rowY(run.row);
        return clip==null || clip.intersects(x0-2,y0-2,colX(run.end)-x0+4,rowY(run.row+1)-y0+4);
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
                String runText=null;
                int first=pos,last=row*cols+col;
                if (!hidden) {
                    while (first<last && text[first]<=' ') first++;
                    while (last>first && text[last-1]<=' ') last--;
                    if (first<last) {
                        char[] ink=Arrays.copyOfRange(text,first,last);
                        for (int i=0;i<ink.length;i++) if (ink[i]<32) ink[i]=' ';
                        runText=new String(ink);
                    }
                }
                blinking|=!hidden && (flags[pos]&EXTENDED_5250_BLINK)!=0;
                runs.add(new RenderRun(row,start,col,Math.min(15,colors[pos]&255),
                        Math.min(15,colors[pos]>>8&255),flags[pos],hidden,runText,first%cols));
            }
        }
        return new RenderFrame(runs,blinking);
    }

    private record RenderFrame(List<RenderRun> runs,boolean blinking) {}
    private final class RenderRun {
        final int row,start,end,foreground,background,flags,inkStart;
        final boolean hidden;
        final String text;

        RenderRun(int row,int start,int end,int foreground,int background,int flags,
                  boolean hidden,String text,int inkStart) {
            this.row=row; this.start=start; this.end=end;
            this.foreground=foreground; this.background=background; this.flags=flags;
            this.hidden=hidden; this.text=text; this.inkStart=inkStart;
        }

        GlyphVector glyphs() {
            if (text==null) return null;
            GlyphVector gv=inkFace.createGlyphVector(TEXT_CONTEXT,text);
            int origin=colX(inkStart,baseWidth);
            for (int i=0;i<=gv.getNumGlyphs();i++) {
                gv.setGlyphPosition(i,new java.awt.geom.Point2D.Double(colX(inkStart+i,baseWidth)-origin,0));
            }
            return gv;
        }
    }

    @Override protected double computePrefWidth(double height) {
        if (fit || zoomValue()<=1.0) return 240;
        Screen5250 screen=pane.screen();
        int rows=screen==null ? 24 : screen.getRows(),cols=screen==null ? 80 : screen.getColumns();
        double sx=outputScaleX(),sy=outputScaleY();
        int step=computeTargetStep(pane.referenceStageWidth(640),pane.referenceStageHeight(400),rows,cols,sx,sy);
        InkMetrics ink=metrics(step);
        return Math.ceil(colX(cols,ink.width)/sx)+24;
    }

    @Override protected double computePrefHeight(double width) {
        if (fit || zoomValue()<=1.0) return 140;
        Screen5250 screen=pane.screen();
        int rows=screen==null ? 24 : screen.getRows(),cols=screen==null ? 80 : screen.getColumns();
        double sx=outputScaleX(),sy=outputScaleY();
        int step=computeTargetStep(pane.referenceStageWidth(640),pane.referenceStageHeight(400),rows,cols,sx,sy);
        InkMetrics ink=metrics(step);
        return Math.ceil(rowY(rows,ink.height)/sy)+20;
    }

    public int allocatedSurfacePixelsForTest() {
        return fxPixels==null ? 0 : fxPixels.length;
    }
    public int allocatedSharedInkPixelsForTest() {
        return sharedInk==null ? 0 : sharedInk.getWidth()*sharedInk.getHeight();
    }
    public int positionForTest(double x,double y) {
        return position(x,y);
    }
}
