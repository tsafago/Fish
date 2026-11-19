package fish.ui;

import java.awt.Font;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.AccessibleRole;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.tn5250j.Session5250;
import org.tn5250j.event.ScreenListener;
import org.tn5250j.framework.tn5250.Screen5250;
import org.tn5250j.framework.tn5250.ScreenField;
import org.tn5250j.keyboard.KeyMnemonic;
import fish.model.*;
import fish.net.NetworkSession;
import fish.security.Kerberos;
import fish.security.KioskMode;
import static org.tn5250j.TN5250jConstants.*;

public final class SessionPane extends BorderPane {
    private Workspace workspace;
    private final UUID id;
    private Profile profile;
    private Keymap keys;
    private final BorderPane header=new BorderPane();
    private final HBox headerControls=new HBox(4);
    private final BorderPane footer=new BorderPane();
    private final Button reset,close;
    private final Label title=new Label(),connection=new Label(),oia=new Label("Disconnected");
    private TerminalView view;
    private final ScrollPane scroll=new ScrollPane();
    private final StackPane body=new StackPane();
    private final HBox empty=new HBox();
    private final StackPane loading=new StackPane();
    private final Region dragZone=new Region();
    private final Pane stage=new Pane() {
        @Override protected void layoutChildren() {
            double w=getWidth(),h=getHeight();
            body.resizeRelocate(0,0,w,h);
            double closeH=Math.max(24,close.prefHeight(-1));
            double closeW=Math.max(25,close.prefWidth(-1));
            // Only grow into unused terminal margin, never further over host input.
            double dragH=closeH+4;
            if (connected()) {
                Point2D first=view.localToScene(0,view.gridTop());
                if (first!=null) dragH=Math.clamp(sceneToLocal(first).getY(),closeH,dragH);
            }
            dragZone.resizeRelocate(0,0,Math.max(0,w-(close.getParent()==this && close.isVisible() ? closeW : 0)),Math.min(h,dragH));
            if (close.getParent()==this && close.isVisible()) {
                close.resizeRelocate(Math.max(0,w-closeW),0,Math.min(closeW,w),closeH);
            }
            if (noticeOverlay.isVisible()) {
                double reserved=close.getParent()==this && close.isVisible() ? closeW : 0;
                double nw=Math.max(0,Math.min(noticeOverlay.prefWidth(-1),w-16-reserved));
                double nh=noticeOverlay.prefHeight(nw);
                noticeOverlay.resizeRelocate(8,8,nw,nh);
            }
            if (reset.getParent()==this && reset.isVisible()) {
                double rw=reset.prefWidth(-1),rh=reset.prefHeight(-1);
                boolean bottom=screen()==null || screen().getCurrentRow()<screen().getRows()/2;
                double top=close.getParent()==this && close.isVisible() ? closeH+4 : 10;
                if (noticeOverlay.isVisible()) top=Math.max(top,noticeOverlay.getHeight()+14);
                reset.resizeRelocate(Math.max(4,w-rw-10),bottom ? Math.max(4,h-rh-10) : top,Math.min(rw,Math.max(0,w-8)),rh);
            }
            if (resetCallout.isVisible()) {
                double maxCw=Math.max(0,w-16);
                double cw=Math.min(resetCallout.prefWidth(-1),maxCw);
                double ch=resetCallout.prefHeight(cw);
                boolean bottom=screen()==null || screen().getCurrentRow()<screen().getRows()/2;
                double cx=Math.max(8,w-cw-10);
                double cy;
                if (bottom) {
                    double reserveBottom=(reset.getParent()==this && reset.isVisible()) ? reset.prefHeight(-1)+16 : 10;
                    cy=Math.max(8,h-ch-reserveBottom);
                } else {
                    double top=close.getParent()==this && close.isVisible() ? closeH+8 : 10;
                    if (noticeOverlay.isVisible()) top=Math.max(top,noticeOverlay.getHeight()+14);
                    if (reset.getParent()==this && reset.isVisible()) top+=reset.prefHeight(-1)+6;
                    cy=Math.min(Math.max(8,h-ch-8),top);
                }
                resetCallout.resizeRelocate(cx,cy,cw,ch);
            }
            if (zoomHud.isVisible()) {
                double zw=Math.min(zoomHud.prefWidth(-1),Math.max(0,w-16));
                double zh=zoomHud.prefHeight(zw);
                double top=close.getParent()==this && close.isVisible() ? closeH+6 : 12;
                if (noticeOverlay.isVisible()) top=Math.max(top,noticeOverlay.getHeight()+14);
                zoomHud.resizeRelocate(Math.max(8,(w-zw)*0.5),Math.min(Math.max(8,h-zh-8),top),zw,zh);
            }
        }
    };
    private final Region outlineLeft=new Region(),outlineRight=new Region(),outlineBottom=new Region();
    private final Tooltip closeTooltip=new Tooltip("Close and disconnect; keep the last pane");
    private final Tooltip resetTooltip=new Tooltip("Recover this input error without sending Enter (default key: Ctrl+R)");
    private final Tooltip oiaTooltip=new Tooltip("Disconnected");
    private final Tooltip headerTooltip=new Tooltip("Drag to arrange panes, out to detach, or into another Fish window.");
    private final TransientNotice noticeOverlay;
    private final Label zoomHud=new Label();
    private final Timeline zoomHudHide=new Timeline();
    private FadeTransition zoomHudFade;
    private final HBox resetCallout=new HBox(8);
    private final Label resetCalloutLabel=new Label("Are you stuck? Press Enter or press this button!");
    private final Button resetCalloutButton;
    private boolean resetCalloutDismissedForCurrentLock;
    private final ConnectionSpinner spinner=new ConnectionSpinner();
    private final ComboBox<Profile> presets=new ComboBox<>();
    private final Button connectButton;
    private boolean updatingPresets;
    private NetworkSession network;
    private Session5250 core;
    private boolean connecting,disposed,pasting;
    private boolean attempted;
    private int credentialOperations;
    private Kerberos.Provider kerberosProvider=Kerberos::ticket;
    private Kerberos.Resolver kerberosResolver=Kerberos::resolve;
    private Timeline kerberosDeadline;
    private long generation;
    private String notice="Disconnected";
    private String lastProblem="";
    private String connectionDetails="";
    private String shownCard="";
    private java.awt.Color surfaceColor=Ui.BACKGROUND;
    private Charset cachedCharset;
    private CharsetEncoder cachedEncoder;
    private int lastCommandState=-1;

    public SessionPane(Workspace owner,UUID id,Profile profile) {
        this.workspace=owner;
        this.id=id;
        this.profile=profile;
        this.noticeOverlay=new TransientNotice(this::focusTerminal);
        refreshKeys();
        setMinSize(70,70);
        setSnapToPixel(true);
        setAccessibleText(profile.name());

        for (Region edge:List.of(outlineLeft,outlineRight,outlineBottom)) {
            edge.setManaged(false);
            edge.setMouseTransparent(true);
            edge.setSnapToPixel(false);
            edge.setVisible(false);
        }

        updateBarHeights(1.0);
        title.setPadding(new Insets(0,0,0,8));
        connection.setPadding(new Insets(0,4,0,0));
        connection.setStyle("-fx-font-size: 11px;");
        header.setLeft(title);
        BorderPane.setAlignment(title,Pos.CENTER_LEFT);
        Tooltip.install(header,headerTooltip);

        close=Ui.iconButton("Close pane "+profile.name(),"close",ColorRole.HEADER_MUTED,() -> workspace.closePane(this));
        close.setTooltip(closeTooltip);
        headerControls.setAlignment(Pos.CENTER_RIGHT);
        headerControls.getChildren().addAll(connection,close);
        header.setRight(headerControls);
        setTop(header);

        view=new TerminalView(this);
        wireScrollViewport();
        scroll.setContent(view);
        scroll.setPannable(false);

        presets.setMinSize(60,30);
        presets.setMaxWidth(Double.MAX_VALUE);
        presets.setAccessibleText("Reconnect profile");
        presets.setTooltip(new Tooltip("Workstation preset"));
        presets.valueProperty().addListener((obs,oldV,newV) -> {
            if (!updatingPresets && !busy() && newV!=null) {
                activate();
                workspace.selectProfile(this,newV);
            }
        });
        presets.focusedProperty().addListener((o,a,f) -> { if (f) activate(); });
        HBox.setHgrow(presets,Priority.ALWAYS);

        connectButton=Ui.iconButton("Reconnect selected profile","connect",ColorRole.TEXT,() -> {
            activate();
            connect();
            workspace.tutorialAction("Connect / disconnect");
        });
        connectButton.getStyleClass().remove("fish-icon-button");
        connectButton.setMinSize(32,30);
        connectButton.setPrefSize(32,30);

        HBox choices=new HBox(8,presets,connectButton);
        choices.setAlignment(Pos.CENTER);
        choices.setMinWidth(100);
        choices.setPrefWidth(265);
        choices.setMaxWidth(285);
        empty.setAlignment(Pos.CENTER);
        empty.setPadding(new Insets(0,8,0,8));
        empty.getChildren().add(choices);
        empty.addEventHandler(MouseEvent.MOUSE_PRESSED,e -> {
            if (e.getButton()==MouseButton.PRIMARY) activate();
        });

        loading.getChildren().add(spinner);
        body.getChildren().addAll(scroll,empty,loading);

        refreshPresets();

        oia.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        oia.setPadding(new Insets(2,8,2,8));
        oia.setTooltip(oiaTooltip);
        footer.setLeft(oia);
        BorderPane.setAlignment(oia,Pos.CENTER_LEFT);
        footer.addEventHandler(MouseEvent.MOUSE_PRESSED,e -> {
            if (e.getButton()==MouseButton.PRIMARY) {
                activate();
                if (connected()) focusTerminal();
            }
        });

        reset=Ui.button("Reset",() -> {
            dismissResetCalloutImmediately();
            activate();
            send(KeyMnemonic.RESET);
            focusTerminal();
        });
        reset.setTooltip(resetTooltip);
        reset.setAccessibleText("Reset input error");
        reset.setStyle("-fx-padding: 1 8 1 8; -fx-font-size: 11px;");
        reset.setVisible(false);
        reset.setManaged(false);
        footer.setRight(reset);
        BorderPane.setAlignment(reset,Pos.CENTER_RIGHT);
        setBottom(footer);

        resetCalloutButton=Ui.button("Reset",() -> {
            dismissResetCalloutImmediately();
            activate();
            send(KeyMnemonic.RESET);
            focusTerminal();
        });
        resetCalloutButton.setFocusTraversable(false);
        resetCalloutButton.setAccessibleText("Reset stuck input error");
        resetCalloutButton.setStyle("-fx-padding: 2 10 2 10; -fx-font-size: 11.5px; -fx-font-weight: bold;");
        resetCalloutLabel.setWrapText(true);
        resetCallout.setId("pane-reset-callout");
        resetCallout.getStyleClass().add("fish-reset-callout");
        resetCallout.setAlignment(Pos.CENTER_LEFT);
        resetCallout.setFocusTraversable(false);
        resetCallout.setManaged(false);
        resetCallout.setVisible(false);
        resetCallout.getChildren().addAll(resetCalloutLabel,resetCalloutButton);

        zoomHud.setId("pane-zoom-hud");
        zoomHud.getStyleClass().add("fish-zoom-hud");
        zoomHud.setMouseTransparent(true);
        zoomHud.setFocusTraversable(false);
        zoomHud.setManaged(false);
        zoomHud.setVisible(false);
        zoomHudHide.getKeyFrames().setAll(new KeyFrame(Duration.millis(950),e -> hideZoomHud()));

        dragZone.setId("pane-top-drag-zone");
        dragZone.setCursor(Cursor.MOVE);
        Tooltip.install(dragZone,headerTooltip);

        stage.getChildren().addAll(body,dragZone,noticeOverlay,resetCallout,zoomHud);
        stage.widthProperty().addListener((o,a,b) -> onStageGeometryChanged());
        stage.heightProperty().addListener((o,a,b) -> onStageGeometryChanged());
        setCenter(stage);
        getChildren().addAll(outlineLeft,outlineRight,outlineBottom);

        view.setFit(profile.zoom()==1);
        installDrag();
        themeChanged();
    }

    private void updateBarHeights(double scale) {
        double s=scale>0 ? scale : 1.0;
        double hh=Math.round(25.0*s)/s;
        header.setMinHeight(hh);
        header.setPrefHeight(hh);
        header.setMaxHeight(hh);
        double fh=Math.round(22.0*s)/s;
        footer.setMinHeight(fh);
        footer.setPrefHeight(fh);
        footer.setMaxHeight(fh);
    }

    @Override protected void layoutChildren() {
        double s=Ui.scaleFor(this);
        double hh=Math.round(25.0*s)/s;
        if (Math.abs(header.getPrefHeight()-hh)>1e-4) updateBarHeights(s);
        double w=getWidth(),h=getHeight();
        Point2D origin=localToScene(0,0);
        double sceneY0=origin==null ? 0 : origin.getY();
        double topH=0;
        if (header.isVisible() && header.isManaged() && h>0) {
            double devHeaderBot=Math.round(sceneY0*s)+Math.round(25.0*s);
            topH=Math.max(0,Math.min(h,devHeaderBot/s-sceneY0));
            header.resizeRelocate(0,0,w,topH);
        }
        double botY=h;
        if (footer.isVisible() && footer.isManaged() && h>topH) {
            double devPaneBot=Math.round((sceneY0+h)*s);
            double devFooterTop=Math.max(Math.round((sceneY0+topH)*s),devPaneBot-Math.round(22.0*s));
            botY=Math.max(topH,Math.min(h,devFooterTop/s-sceneY0));
            footer.resizeRelocate(0,botY,w,Math.max(0,h-botY));
        }
        stage.resizeRelocate(0,topH,w,Math.max(0,botY-topH));
        disableScrollBitmapCache(scroll);
        view.snapToDeviceGridNow();
        layoutActiveOutline(s);
    }

    private void disableScrollBitmapCache(Node node) {
        if (node==null || node==view) return;
        if (node.isCache()) node.setCache(false);
        if (node instanceof Parent p) {
            for (Node child:p.getChildrenUnmodifiable()) disableScrollBitmapCache(child);
        }
    }

    private void layoutActiveOutline(double s) {
        boolean show=workspace.appearanceSettings().outline() && workspace.active()==this;
        if (!show) {
            outlineLeft.setVisible(false);
            outlineRight.setVisible(false);
            outlineBottom.setVisible(false);
            return;
        }
        boolean frame=workspace.appearanceSettings().windowOutline();
        Region content=workspace.contentPane();
        double w=getWidth(),h=getHeight();
        boolean drawLeft=false,drawRight=false,drawBottom=false;
        if (!frame && content!=null && w>0 && h>0) {
            Point2D topLeft=content.sceneToLocal(localToScene(0,0));
            Point2D bottomRight=content.sceneToLocal(localToScene(w,h));
            if (topLeft!=null && bottomRight!=null) {
                drawLeft=Math.abs(topLeft.getX())<=1.0;
                drawRight=Math.abs(bottomRight.getX()-content.getWidth())<=1.0;
                drawBottom=Math.abs(bottomRight.getY()-content.getHeight())<=1.0;
            }
        }
        double px=Ui.hairline(s);
        String style="-fx-background-color: "+Ui.hex(ColorRole.DIVIDER)+";";
        outlineLeft.setVisible(drawLeft);
        if (drawLeft) {
            Ui.setStyleIfChanged(outlineLeft,style);
            outlineLeft.resizeRelocate(0,0,px,h);
            outlineLeft.toFront();
        }
        outlineRight.setVisible(drawRight);
        if (drawRight) {
            Ui.setStyleIfChanged(outlineRight,style);
            Point2D rightScene=localToScene(w,0);
            double rx=w-px;
            if (rightScene!=null) {
                double devRight=Math.round(rightScene.getX()*s);
                rx=Math.max(0,Math.min(w-px,w-(rightScene.getX()-(devRight-1.0)/s)));
            }
            outlineRight.resizeRelocate(rx,0,px,h);
            outlineRight.toFront();
        }
        outlineBottom.setVisible(drawBottom);
        if (drawBottom) {
            Ui.setStyleIfChanged(outlineBottom,style);
            Point2D botScene=localToScene(0,h);
            double by=h-px;
            if (botScene!=null) {
                double devBot=Math.round(botScene.getY()*s);
                by=Math.max(0,Math.min(h-px,h-(botScene.getY()-(devBot-1.0)/s)));
            }
            outlineBottom.resizeRelocate(0,by,w,px);
            outlineBottom.toFront();
        }
    }

    public void syncScrollViewport() {
        if (view==null) return;
        Bounds vp=scroll.getViewportBounds();
        double vpW=vp!=null && vp.getWidth()>0 ? vp.getWidth() : scroll.getWidth();
        double vpH=vp!=null && vp.getHeight()>0 ? vp.getHeight() : scroll.getHeight();
        if (view.isFit() || view.zoomValue()<=1.0) {
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setFitToWidth(true);
            scroll.setFitToHeight(true);
        } else {
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            double pw=view.prefWidth(-1),ph=view.prefHeight(-1);
            scroll.setFitToWidth(vpW<=0 || pw<=vpW);
            scroll.setFitToHeight(vpH<=0 || ph<=vpH);
        }
    }

    private void onStageGeometryChanged() {
        if (view==null || disposed) return;
        syncScrollViewport();
        view.onStageResized();
    }

    public double referenceStageWidth(double fallback) {
        double sw=stage.getWidth();
        if (sw<=1) return fallback;
        if (view!=null && (view.isFit() || view.zoomValue()<=1.0)) return fallback>1 ? Math.min(fallback,sw) : sw;
        return sw;
    }

    public double referenceStageHeight(double fallback) {
        double sh=stage.getHeight();
        if (sh<=1) return fallback;
        if (view!=null && (view.isFit() || view.zoomValue()<=1.0)) return fallback>1 ? Math.min(fallback,sh) : sh;
        return sh;
    }

    public double[] viewportInView(double viewW,double viewH) {
        Bounds vp=scroll.getViewportBounds();
        if (vp==null || vp.getWidth()<=1 || vp.getHeight()<=1) {
            double sw=stage.getWidth()>1 ? Math.min(viewW,stage.getWidth()) : viewW;
            double sh=stage.getHeight()>1 ? Math.min(viewH,stage.getHeight()) : viewH;
            return new double[]{0,0,Math.max(1,sw),Math.max(1,sh)};
        }
        double visW=Math.min(viewW,vp.getWidth());
        double visH=Math.min(viewH,vp.getHeight());
        double visX=0,visY=0;
        if (viewW>vp.getWidth()) {
            double lx=view.getLayoutX();
            visX=lx<0 ? Math.min(viewW-visW,-lx) : Math.max(0,Math.min(1,scroll.getHvalue()))*(viewW-visW);
        }
        if (viewH>vp.getHeight()) {
            double ly=view.getLayoutY();
            visY=ly<0 ? Math.min(viewH-visH,-ly) : Math.max(0,Math.min(1,scroll.getVvalue()))*(viewH-visH);
        }
        return new double[]{visX,visY,Math.max(1,visW),Math.max(1,visH)};
    }

    private void wireScrollViewport() {
        scroll.setFocusTraversable(false);
        scroll.setCache(false);
        scroll.focusedProperty().addListener((o,a,f) -> {
            if (f && scroll.isVisible() && view!=null) view.requestFocus();
        });
        scroll.skinProperty().addListener((o,a,b) -> disableScrollBitmapCache(scroll));
        scroll.viewportBoundsProperty().addListener((o,a,b) -> { syncScrollViewport(); if (view!=null) view.requestRender(); });
        scroll.widthProperty().addListener((o,a,b) -> syncScrollViewport());
        scroll.heightProperty().addListener((o,a,b) -> syncScrollViewport());
        scroll.hvalueProperty().addListener((o,a,b) -> { if (view!=null) view.requestRender(); });
        scroll.vvalueProperty().addListener((o,a,b) -> { if (view!=null) view.requestRender(); });
        syncScrollViewport();
        view.setScrollRevealCallback(() -> {
            java.awt.Rectangle r=view.currentCursorLogicalBounds();
            Bounds vp=scroll.getViewportBounds();
            if (r==null || vp==null) return;
            double cw=view.getWidth(),ch=view.getHeight();
            if (cw>vp.getWidth()) scroll.setHvalue(Math.max(0,Math.min(1,(r.x-vp.getWidth()/2.0)/(cw-vp.getWidth()))));
            if (ch>vp.getHeight()) scroll.setVvalue(Math.max(0,Math.min(1,(r.y-vp.getHeight()/2.0)/(ch-vp.getHeight()))));
        });
    }

    public void replaceTerminalView(TerminalView replacement) {
        if (view!=null) view.dispose();
        view=replacement;
        wireScrollViewport();
        scroll.setContent(view);
        updateHeader();
    }

    public UUID id() { return id; }
    Workspace workspace() { return workspace; }
    void moveTo(Workspace owner) { workspace=owner; refreshKeys(); }
    public Profile profile() { return profile; }
    public Screen5250 screen() { return core==null ? null : core.getScreen(); }
    public Keymap keys() { return keys; }
    public void refreshKeys() { keys=Keymap.global(workspace.shortcutOverrides()); }
    public TerminalView view() { return view; }
    public Font workspaceFont() { return workspace.terminalFont(); }
    public boolean connected() { return network!=null && network.isConnected(); }
    public boolean busy() { return connecting || connected(); }
    public boolean pristine() { return !attempted && !busy() && core==null; }
    public void activate() { workspace.activate(this); }
    void showClipboardMenu(double screenX,double screenY) {
        if (!workspace.popupAt(view))
            workspace.showPopup(workspace.clipboardMenu(this),view,screenX,screenY);
    }
    public java.awt.Color paneSurfaceColor() { return surfaceColor; }

    public Button closeButton() { return close; }
    public Button connectButton() { return connectButton; }
    public Button resetButton() { return reset; }
    public HBox resetCalloutBox() { return resetCallout; }
    public Button resetCalloutButton() { return resetCalloutButton; }
    public Label resetCalloutLabel() { return resetCalloutLabel; }
    public Label zoomHudLabel() { return zoomHud; }
    public ComboBox<Profile> presetsCombo() { return presets; }
    public Label titleLabel() { return title; }
    public Label oiaLabel() { return oia; }
    public TransientNotice noticeOverlay() { return noticeOverlay; }
    public String shownCard() { return shownCard; }

    public String zoomBadgeText() {
        if (view.isFit() || Math.abs(view.zoomValue()-1.0)<0.005) return "100% (auto)";
        return Math.round(view.zoomValue()*100.0)+"%";
    }

    public void showZoomPopup() {
        if (disposed) return;
        if (zoomHudFade!=null) { zoomHudFade.stop(); zoomHudFade=null; }
        zoomHud.setText(zoomBadgeText());
        zoomHud.setOpacity(1.0);
        zoomHud.setVisible(true);
        zoomHud.toFront();
        stage.requestLayout();
        zoomHudHide.playFromStart();
    }

    private void hideZoomHud() {
        if (!zoomHud.isVisible()) return;
        if (Ui.reducedMotion()) {
            zoomHud.setVisible(false);
            return;
        }
        if (zoomHudFade!=null) zoomHudFade.stop();
        zoomHudFade=new FadeTransition(Duration.millis(150),zoomHud);
        zoomHudFade.setFromValue(zoomHud.getOpacity());
        zoomHudFade.setToValue(0.0);
        zoomHudFade.setOnFinished(e -> {
            zoomHud.setVisible(false);
            zoomHud.setOpacity(1.0);
        });
        zoomHudFade.play();
    }

    public void zoomBy(double delta,boolean showPopup) {
        if (KioskMode.isEnabled()) return;
        double base=view.isFit() ? 1.0 : view.zoomValue();
        double raw=Math.max(0.5,Math.min(3.0,Math.round((base+delta)*100.0)/100.0));
        if (Math.abs(raw-1.0)<=0.02) {
            view.setRelativeZoom(1.0,true);
        } else {
            view.setRelativeZoom(raw,false);
        }
        if (showPopup) showZoomPopup();
    }

    public void zoomReset(boolean showPopup) {
        if (KioskMode.isEnabled()) return;
        view.setRelativeZoom(1.0,true);
        if (showPopup) showZoomPopup();
    }

    public void dismissResetCalloutImmediately() {
        resetCalloutDismissedForCurrentLock=true;
        if (resetCallout.isVisible()) {
            resetCallout.setVisible(false);
            stage.requestLayout();
        }
    }

    public void focusTerminal() {
        view.restartCursor();
        if (connecting && close.isVisible()) close.requestFocus();
        else if (core==null) presets.requestFocus();
        else view.requestFocus();
    }

    List<Node> focusStops() {
        List<Node> stops=new ArrayList<>(4);
        if ("terminal".equals(shownCard)) addFocusStop(stops,view);
        else if ("offline".equals(shownCard)) {
            addFocusStop(stops,presets);
            addFocusStop(stops,connectButton);
        }
        addFocusStop(stops,reset);
        addFocusStop(stops,close);
        return stops;
    }

    private void addFocusStop(List<Node> stops,Node candidate) {
        if (candidate==null || !candidate.isFocusTraversable() || candidate.isDisabled()) return;
        for (Node cur=candidate;cur!=null;cur=cur.getParent()) {
            if (!cur.isVisible()) return;
            if (cur==this) { stops.add(candidate); return; }
        }
    }

    public void refreshPresets() {
        updatingPresets=true;
        try {
            presets.setItems(FXCollections.observableArrayList(workspace.profileList()));
            presets.setValue(profile);
        } finally { updatingPresets=false; }
    }

    public void updateProfile(Profile value) {
        Profile previous=profile;
        profile=value;
        if (!previous.keymap().equals(value.keymap())) refreshKeys();
        refreshPresets();
        updateHeader();
        if (!previous.id().equals(value.id()) || !previous.codePage().equals(value.codePage())) view.screenChanged();
        else if (!previous.theme().equals(value.theme()) || !previous.effect().equals(value.effect())) view.displayChanged();
        if (!view.hasExplicitZoom() && previous.zoom()!=value.zoom()) {
            view.setFit(value.zoom()==1);
            syncScrollViewport();
            view.requestLayout();
            view.requestRender();
        }
    }

    Appearance.CursorStyle cursorStyle() { return workspace.appearanceSettings().cursorStyle(); }

    public void updateHeader() {
        boolean isActive=workspace.active()==this;
        Appearance.PaneInfo info=workspace.appearanceSettings().info();
        Ui.setTextIfChanged(title,info.profile() ? profile.name() : "");
        ColorRole inkRole=isActive ? ColorRole.HEADER_TEXT : ColorRole.HEADER_MUTED;
        String titleColor=Ui.hex(inkRole);
        Ui.setStyleIfChanged(title,"-fx-text-fill: "+titleColor+";");
        Ui.setStyleIfChanged(connection,"-fx-font-size: 11px; -fx-text-fill: "+titleColor+";");
        Ui.updateIcon(close.getGraphic(),"close",inkRole);

        surfaceColor=Ui.paneBackground(isActive && workspace.highlightActive());
        String surfaceHex=Ui.hex(surfaceColor);
        String headerBg=!Ui.customTheme() && isActive && workspace.highlightActive() ? surfaceHex : Ui.hex(ColorRole.HEADER);
        String footerBg=!Ui.customTheme() && isActive && workspace.highlightActive() ? surfaceHex : Ui.hex(ColorRole.FOOTER);

        Ui.setStyleIfChanged(this,"-fx-background-color: "+surfaceHex+"; -fx-border-width: 0; -fx-padding: 0;");
        Ui.setStyleIfChanged(header,"-fx-background-color: "+headerBg+";");
        Ui.setStyleIfChanged(footer,"-fx-background-color: "+footerBg+";");
        Ui.setStyleIfChanged(body,"-fx-background-color: "+surfaceHex+";");
        Ui.setStyleIfChanged(empty,"-fx-background-color: "+surfaceHex+";");
        Ui.setStyleIfChanged(loading,"-fx-background-color: "+surfaceHex+";");
        layoutActiveOutline(Ui.scaleFor(this));

        java.awt.Color terminalSurface=Ui.customTheme() && !(isActive && workspace.highlightActive())
                ? Ui.color(ColorRole.HOST_0) : surfaceColor;
        view.setTerminalBackground(terminalSurface);

        List<String> parts=new ArrayList<>(3);
        if (info.connection()) parts.add(connecting ? "Connecting" : connected() ? "Connected" : "Disconnected");
        if (info.host() && !profile.host().isEmpty()) parts.add(profile.host());
        if (info.device() && !profile.device().isEmpty()) parts.add(profile.device());
        Ui.setTextIfChanged(connection,String.join("  ",parts));

        boolean showHeader=info.header() && (!title.getText().isBlank() || !connection.getText().isBlank());
        if (header.isVisible()!=showHeader) header.setVisible(showHeader);
        if (header.isManaged()!=showHeader) header.setManaged(showHeader);
        boolean kiosk=KioskMode.isEnabled();
        boolean showDragZone=!kiosk && !showHeader && workspace.canDragPane();
        if (dragZone.isVisible()!=showDragZone) dragZone.setVisible(showDragZone);

        Pane desiredCloseParent=showHeader ? headerControls : stage;
        if (close.getParent()!=desiredCloseParent) {
            if (close.getParent() instanceof Pane old) old.getChildren().remove(close);
            desiredCloseParent.getChildren().add(close);
        }
        if (title.isVisible()!=info.profile()) title.setVisible(info.profile());
        if (title.isManaged()!=info.profile()) title.setManaged(info.profile());
        boolean connVis=!connection.getText().isEmpty();
        if (connection.isVisible()!=connVis) connection.setVisible(connVis);
        if (connection.isManaged()!=connVis) connection.setManaged(connVis);

        close.setAccessibleText("Close pane "+profile.name());
        boolean restoreFocus=close.isFocused() && "connecting".equals(shownCard);
        boolean closeVis=!kiosk && (busy() || workspace.hasMultiplePanes());
        if (close.isVisible()!=closeVis) close.setVisible(closeVis);
        boolean closeManaged=showHeader && closeVis;
        if (close.isManaged()!=closeManaged) close.setManaged(closeManaged);

        String closeKey=workspace.shortcutLabel("Close pane");
        Ui.setTooltipText(close,closeTooltip,"Close and disconnect; keep the last pane"+(closeKey.isEmpty() ? "" : " ("+closeKey+")"));
        String resetKey=workspace.shortcutLabel("RESET");
        Ui.setTooltipText(reset,resetTooltip,"Recover this input error without sending Enter"+(resetKey.isEmpty() ? "" : " ("+resetKey+")"));
        header.setCursor(!kiosk && workspace.canDragPane() ? Cursor.MOVE : Cursor.DEFAULT);
        if (kiosk) Tooltip.uninstall(header,headerTooltip);
        else Tooltip.install(header,headerTooltip);

        boolean disablePresets=kiosk || busy() || credentialOperations>0;
        boolean disableConnect=busy() || credentialOperations>0;
        if (presets.isDisabled()!=disablePresets) presets.setDisable(disablePresets);
        if (connectButton.isDisabled()!=disableConnect) connectButton.setDisable(disableConnect);

        String card=connecting ? "connecting" : core!=null && connected() ? "terminal" : "offline";
        if (!card.equals(shownCard)) {
            shownCard=card;
            scroll.setVisible("terminal".equals(card)); scroll.setManaged("terminal".equals(card));
            empty.setVisible("offline".equals(card)); empty.setManaged("offline".equals(card));
            loading.setVisible("connecting".equals(card)); loading.setManaged("connecting".equals(card));
        }
        spinner.updateAnimation();
        if (restoreFocus && !connecting) focusTerminal();
        setAccessibleText(profile.name());
        stage.requestLayout();
        lastCommandState=-1;
        updateOia();
    }

    public void themeChanged() {
        updateHeader();
        view.themeChanged();
        updateOia();
        spinner.repaint();
    }

    public boolean recoverableInputError() {
        return connected() && screen()!=null && screen().getOIA().isKeyBoardLocked()
                && core.recoverableInputError();
    }

    public void connect() { connect(true); }
    void connectOnStartup() { if (!busy()) connect(false); }

    private void connect(boolean interactive) {
        if (busy()) { disconnect(); return; }
        attempted=true;
        if (credentialOperations>0) { problem("The previous Windows sign-in lookup is still finishing. Please wait before reconnecting."); return; }
        Profile connectionProfile=workspace.connectionProfile(profile);
        if (profile.host().isEmpty() || connectionProfile.automaticSignOn() && connectionProfile.username().isEmpty()) {
            if (interactive && !KioskMode.isEnabled()) workspace.profiles();
            else startupGuidance(KioskMode.isEnabled()
                    ? "Profile host or login is incomplete. Contact an administrator."
                    : "Finish this profile in Edit > Settings > Profiles, then Reconnect.");
            return;
        }
        if (!interactive && connectionProfile.automaticSignOn() && !connectionProfile.rememberPassword()) {
            startupGuidance("Reconnect to enter a password, or enable Save password in Edit > Settings > Profiles.");
            return;
        }
        if (interactive) workspace.rememberProfile(profile);
        long token=++generation;
        if (connectionProfile.authentication()==Profile.Authentication.KERBEROS) {
            kerberosConnect(token,connectionProfile,interactive); return;
        }
        if (!connectionProfile.automaticSignOn()) { begin(new char[0],token,connectionProfile,interactive); return; }
        connecting=true;
        updateHeader();
        Profile target=workspace.loginProfile(profile);
        credentialOperations++;
        CompletableFuture.supplyAsync(() -> {
            try { return target.rememberPassword() ? workspace.credentials().read(target) : Optional.<char[]>empty(); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }).whenComplete((saved,err) -> Ui.runLater(() -> {
            try {
                if (err!=null) throw err;
                if (token!=generation || disposed) { saved.ifPresent(p -> Arrays.fill(p,'\0')); return; }
                char[] password;
                if (saved.isPresent()) password=saved.get();
                else {
                    if (!interactive) {
                        startupGuidance("No saved password. Reconnect to sign in.");
                        disconnectNetwork();
                        return;
                    }
                    PasswordField input=new PasswordField();
                    input.setPromptText("Password");
                    VBox panel=new VBox(8,new Label("Plain TCP: the password and session are not encrypted."),Ui.revealablePassword(input));
                    if (!Ui.form(workspace.stage(),"Sign on as "+connectionProfile.username(),panel,"Connect")) { disconnect(); return; }
                    password=input.getText().toCharArray();
                    input.clear();
                }
                if (token!=generation || disposed) { Arrays.fill(password,'\0'); return; }
                if (target.rememberPassword() && saved.isEmpty()) rememberAndBegin(password,token,target,connectionProfile,interactive);
                else begin(password,token,connectionProfile,interactive);
            } catch (Throwable e) {
                if (token==generation && !disposed) { problem("Credential lookup failed: "+message(e)); disconnectNetwork(); }
                else System.err.println("Canceled credential lookup failed: "+message(e));
            } finally {
                credentialOperations--;
                updateHeader();
            }
        }));
    }

    private void kerberosConnect(long token,Profile connectionProfile,boolean interactive) {
        connecting=true;
        credentialOperations++;
        notice="Getting a Windows Kerberos ticket - no password or NTLM fallback";
        updateHeader();
        kerberosDeadline=new Timeline(new KeyFrame(Duration.seconds(20),e -> kerberosTimedOut(token,interactive)));
        kerberosDeadline.play();
        CompletableFuture.supplyAsync(() -> {
            try {
                String principal=Kerberos.principal(connectionProfile.host(),connectionProfile.kerberosServer(),kerberosResolver);
                return kerberosProvider.ticket(principal);
            } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }).whenComplete((ticket,err) -> Ui.runLater(() -> {
            try {
                if (err!=null) throw err;
                if (token!=generation || disposed) return;
                if (ticket==null || ticket.length==0 || ticket.length>Kerberos.MAX_TOKEN)
                    throw new IOException("Windows returned an invalid or oversized Kerberos token (TN5250 limit: 16384 bytes).");
                begin(new char[0],token,connectionProfile,interactive,ticket);
            } catch (Throwable e) {
                if (token==generation && !disposed) {
                    disconnectNetwork();
                    String failure="Kerberos sign-in failed: "+message(e);
                    if (interactive) problem(failure); else startupGuidance(failure);
                } else System.err.println("Canceled Kerberos sign-in failed: "+message(e));
            } finally {
                if (ticket!=null) Arrays.fill(ticket,(byte)0);
                if (kerberosDeadline!=null) { kerberosDeadline.stop(); kerberosDeadline=null; }
                credentialOperations--;
                updateHeader();
            }
        }));
    }

    private void kerberosTimedOut(long token) { kerberosTimedOut(token,true); }
    private void kerberosTimedOut(long token,boolean interactive) {
        if (token!=generation || disposed) return;
        generation++;
        disconnectNetwork();
        String failure="Kerberos timed out. Check domain/VPN connectivity and the server DNS name. Windows may still be finishing its lookup; no connection or fallback will follow.";
        if (interactive) problem(failure); else startupGuidance(failure);
    }

    private void rememberAndBegin(char[] password,long token,Profile target,Profile connectionProfile,boolean interactive) {
        credentialOperations++;
        CompletableFuture.runAsync(() -> {
            try { workspace.credentials().write(target,password); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }).whenComplete((unused,err) -> Ui.runLater(() -> {
            try {
                if (err!=null) throw err;
                if (token==generation && !disposed) begin(password,token,connectionProfile,interactive);
                else Arrays.fill(password,'\0');
            } catch (Throwable e) {
                Arrays.fill(password,'\0');
                if (token==generation && !disposed) { problem("Password was not remembered: "+message(e)); disconnectNetwork(); }
                else System.err.println("Canceled password save failed: "+message(e));
            } finally {
                credentialOperations--;
                updateHeader();
            }
        }));
    }

    private void begin(char[] password,long token,Profile connectionProfile,boolean interactive) {
        begin(password,token,connectionProfile,interactive,null);
    }

    private void begin(char[] password,long token,Profile connectionProfile,boolean interactive,byte[] kerberosTicket) {
        try {
            Map<String,String> environment=connectionProfile.environment(password);
            connecting=true;
            notice="Connecting to "+connectionProfile.host()+":"+connectionProfile.port()+" - plain TCP";
            network=new NetworkSession(record -> {
                if (generation==token && !disposed) {
                    core.accept(record);
                }
            },text -> Ui.runLater(() -> {
                if (generation==token && !disposed) {
                    notice=text;
                    if (text.startsWith("Connected") && network!=null) connectionDetails=network.connectionDetails();
                    if (text.startsWith("Connected") || text.startsWith("Disconnected")) connecting=false;
                    if (text.startsWith("Disconnected")) {
                        generation++; disconnectNetwork(); startupGuidance(text+" Reconnect to retry.");
                    }
                    updateHeader();
                    view.screenChanged();
                    if (text.startsWith("Connected") && workspace.active()==this && workspace.stage().isFocused()) focusTerminal();
                }
            }));
            core=new Session5250(connectionProfile.name(),connectionProfile.codePage(),network.output(),this::problem);
            core.getScreen().addScreenListener(new ScreenListener() {
                @Override public void onScreenChanged(int u,int r,int c,int er,int ec) {
                    if (!pasting) { view.screenChanged(); updateOia(); }
                }
                @Override public void onScreenSizeChanged(int r,int c) {
                    view.resetSelection();
                    if (!pasting) view.screenChanged();
                }
            });
            core.getScreen().getOIA().addOIAListener((o,change) -> {
                if (!pasting) updateOia();
            });
            network.connect(connectionProfile.host(),connectionProfile.port(),environment,kerberosTicket);
            environment.clear();
            updateHeader();
            updateOia();
            if (interactive) focusTerminal();
        } catch (RuntimeException e) {
            disconnectNetwork();
            problem("Connection failed: "+e.getMessage());
        } finally {
            Arrays.fill(password,'\0');
            if (kerberosTicket!=null) Arrays.fill(kerberosTicket,(byte)0);
        }
    }

    public void disconnect() { attempted=true; stopConnection(); }
    void disconnectForProfileChange() { stopConnection(); }

    private void stopConnection() {
        generation++;
        disconnectNetwork();
        notice="Disconnected";
        updateOia();
    }

    private void disconnectNetwork() {
        if (kerberosDeadline!=null) { kerberosDeadline.stop(); kerberosDeadline=null; }
        if (network!=null) network.close();
        network=null;
        connecting=false;
        core=null;
        view.resetSelection();
        updateHeader();
        view.screenChanged();
    }

    public void reconnect() { disconnect(); connect(); }
    public void dispose() {
        disposed=true;
        zoomHudHide.stop();
        if (zoomHudFade!=null) zoomHudFade.stop();
        noticeOverlay.dispose();
        spinner.stop();
        disconnect();
        view.dispose();
    }

    public final class ConnectionSpinner extends StackPane {
        private final Canvas canvas=new Canvas(36,36);
        private double angle;
        private final Timeline clock=new Timeline(new KeyFrame(Duration.millis(40),e -> {
            angle=(System.nanoTime()%1_200_000_000L)*360.0/1_200_000_000L;
            repaint();
        }));

        ConnectionSpinner() {
            setMinSize(36,36); setPrefSize(36,36); setMaxSize(36,36);
            getChildren().add(canvas);
            setAccessibleRole(AccessibleRole.PROGRESS_INDICATOR);
            setAccessibleText("Connecting to workstation");
            clock.setCycleCount(Timeline.INDEFINITE);
            visibleProperty().addListener((o,a,b) -> updateAnimation());
            sceneProperty().addListener((o,a,b) -> updateAnimation());
            repaint();
        }

        public boolean isRunning() { return clock.getStatus()==javafx.animation.Animation.Status.RUNNING; }
        public void setAngle(double value) { this.angle=value; repaint(); }
        void stop() { clock.stop(); }

        void updateAnimation() {
            boolean winShowing=getScene()!=null && getScene().getWindow()!=null && getScene().getWindow().isShowing()
                    && !(getScene().getWindow() instanceof Stage st && st.isIconified());
            if (connecting && !disposed && isVisible() && winShowing) {
                if (!isRunning()) clock.play();
            } else clock.stop();
        }

        void repaint() {
            GraphicsContext gc=canvas.getGraphicsContext2D();
            gc.clearRect(0,0,36,36);
            double size=28,x=4,y=4;
            gc.setLineWidth(2.25);
            gc.setLineCap(StrokeLineCap.ROUND);
            gc.setStroke(Ui.fxColor(ColorRole.DIVIDER));
            gc.strokeOval(x,y,size,size);
            gc.setStroke(Ui.fxColor(ColorRole.FOCUS));
            gc.strokeArc(x,y,size,size,-angle,100,ArcType.OPEN);
        }
    }

    public void problem(String message) {
        if (!javafx.application.Platform.isFxApplicationThread() && Ui.isFxStarted()) {
            Ui.runLater(() -> problem(message));
            return;
        }
        notice=message;
        lastProblem=message;
        updateOia();
        Ui.setTooltipText(oia,oiaTooltip,message);
        Ui.setStyleIfChanged(oia,"-fx-font-family: 'Consolas'; -fx-font-size: 11px; -fx-text-fill: "+Ui.hex(ColorRole.STATUS_ERROR)+";");
        if (!footer.isVisible() || !workspace.appearanceSettings().info().status()) {
            noticeOverlay.setErrorStyle(true);
            noticeOverlay.showMessage(message,true);
            stage.requestLayout();
        }
    }

    private void startupGuidance(String message) {
        notice=message; lastProblem=message; updateOia();
        if (!footer.isVisible() || !workspace.appearanceSettings().info().status()) {
            noticeOverlay.setErrorStyle(false);
            noticeOverlay.showMessage(message,false);
            stage.requestLayout();
        }
    }

    public void showLastNotice() {
        String details=(lastProblem.isEmpty() ? "No input or connection errors in this pane." : lastProblem)
                +(connectionDetails.isEmpty() ? "" : "\n\nConnection diagnostics: "+connectionDetails);
        Ui.message(workspace.stage(),details,"Last pane notice");
    }

    void updateOia() {
        if (!javafx.application.Platform.isFxApplicationThread() && Ui.isFxStarted()) {
            Ui.runLater(this::updateOia);
            return;
        }
        Screen5250 screen=screen();
        Appearance.PaneInfo info=workspace.appearanceSettings().info();
        String dimensions=screen==null ? "" : (info.dimensions() ? "  "+screen.getRows()+"x"+screen.getColumns() : "")
                +(info.cursor() ? "  "+screen.getCurrentRow()+":"+screen.getCurrentCol() : "");
        String state=screen==null || !connected() ? notice : screen.getOIA().isKeyBoardLocked()
                ? recoverableInputError() ? "Input error - Enter or Reset to continue" : "Waiting for host - input inhibited"
                : notice;
        if (screen!=null && screen.getOIA().getInhibitedText()!=null && screen.getOIA().isKeyBoardLocked())
            state+="  "+screen.getOIA().getInhibitedText();
        String text=(info.status() ? state : "")+dimensions
                +(info.footerDevice() && network!=null && !network.allocatedDevice().isEmpty() ? "  "+network.allocatedDevice() : "")
                +(screen!=null && info.insert() && screen.getOIA().isInsertMode() ? "  INS" : "");
        Ui.setTextIfChanged(oia,text.strip());
        boolean showFooter=info.footer() && !text.isBlank();
        boolean layoutDirty=false;
        if (footer.isVisible()!=showFooter) { footer.setVisible(showFooter); layoutDirty=true; }
        if (footer.isManaged()!=showFooter) { footer.setManaged(showFooter); layoutDirty=true; }
        Ui.setTooltipText(oia,oiaTooltip,state+dimensions);
        ColorRole statusRole=recoverableInputError() || notice.startsWith("Disconnected -") ? ColorRole.STATUS_ERROR
                : screen!=null && screen.getOIA().isKeyBoardLocked() ? ColorRole.STATUS_WAIT : ColorRole.FOOTER_TEXT;
        Ui.setStyleIfChanged(oia,"-fx-font-family: 'Consolas'; -fx-font-size: 11px; -fx-text-fill: "+Ui.hex(statusRole)+";");

        if (showFooter) {
            if (reset.getParent()!=footer) {
                if (reset.getParent() instanceof Pane p) p.getChildren().remove(reset);
                footer.setRight(reset);
                layoutDirty=true;
            }
        } else {
            if (reset.getParent()!=stage) {
                if (reset.getParent()==footer) footer.setRight(null);
                else if (reset.getParent() instanceof Pane p) p.getChildren().remove(reset);
                stage.getChildren().add(reset);
                layoutDirty=true;
            }
        }
        boolean rec=recoverableInputError();
        if (!rec) resetCalloutDismissedForCurrentLock=false;
        if (reset.isVisible()!=rec) { reset.setVisible(rec); layoutDirty=true; }
        boolean resetManaged=showFooter && rec;
        if (reset.isManaged()!=resetManaged) { reset.setManaged(resetManaged); layoutDirty=true; }
        boolean showCallout=rec && !resetCalloutDismissedForCurrentLock;
        if (resetCallout.isVisible()!=showCallout) {
            resetCallout.setVisible(showCallout);
            if (showCallout) resetCallout.toFront();
            layoutDirty=true;
        }
        if (layoutDirty) { stage.requestLayout(); requestLayout(); }
        int cmdState=(connected() ? 1 : 0)
                | (busy() ? 2 : 0)
                | (recoverableInputError() ? 4 : 0)
                | (workspace.active()==this ? 8 : 0)
                | (workspace.paneCount()<<4);
        if (cmdState!=lastCommandState) {
            lastCommandState=cmdState;
            workspace.refreshCommands();
        }
    }

    private CharsetEncoder encoder() {
        Charset cs=profile.charset();
        if (cachedEncoder==null || !cs.equals(cachedCharset)) {
            cachedCharset=cs;
            cachedEncoder=cs.newEncoder();
        } else {
            cachedEncoder.reset();
        }
        return cachedEncoder;
    }

    public boolean send(KeyMnemonic key) {
        if (!pasting) view.restartCursor();
        if (!connected()) { problem("Not connected. Ctrl+Alt+R connects this pane."); return false; }
        if (key==KeyMnemonic.ENTER && recoverableInputError()) key=KeyMnemonic.RESET;
        if (key==KeyMnemonic.RESET && recoverableInputError()) dismissResetCalloutImmediately();
        if (key==KeyMnemonic.RESET && screen().getOIA().isKeyBoardLocked() && !recoverableInputError()) {
            problem("Waiting for host. Reset cannot clear host-busy inhibition."); return false;
        }
        if (screen().getOIA().isKeyBoardLocked() && key!=KeyMnemonic.RESET
                && key!=KeyMnemonic.ATTN && key!=KeyMnemonic.SYSREQ) {
            problem(recoverableInputError() ? "Input error. Press Enter or Reset; no keys were queued."
                    : "Waiting for host. Attention / System Request available; no keys were queued.");
            return false;
        }
        try {
            notice="Connected";
            screen().sendKeys(key);
            if (!pasting) {
                updateOia();
                view.restartCursor();
            }
            return true;
        } catch (RuntimeException e) { problem("Host key failed: "+e.getMessage()); return false; }
    }

    public boolean type(char character) {
        if (!pasting) view.restartCursor();
        if (!connected()) { problem("Not connected. Ctrl+Alt+R connects this pane."); return false; }
        if (screen().getOIA().isKeyBoardLocked()) {
            problem("Input inhibited. No characters were queued.");
            return false;
        }
        if (!encoder().canEncode(character)) {
            problem("Character is not representable in code page "+profile.codePage()+".");
            return false;
        }
        String text=character=='[' ? "[[" : character==']' ? "]]" : String.valueOf(character);
        try {
            notice="Connected";
            screen().sendKeys(text);
            if (!pasting) {
                updateOia();
                view.restartCursor();
            }
            return !screen().getOIA().isKeyBoardLocked();
        } catch (RuntimeException e) { problem("Input failed: "+e.getMessage()); return false; }
    }

    public boolean paste(String text) {
        view.restartCursor();
        if (text.length()>32768) { problem("Paste exceeds the 32768-character safety limit; nothing pasted."); return false; }
        text=text.replace("\r\n","\n").replace('\r','\n');
        var enc=encoder();
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (c!='\n' && c!='\t' && (c<32 || c==127 || !enc.canEncode(c))) {
                problem("Paste contains an unsupported character at position "+(i+1)+"; nothing pasted.");
                return false;
            }
        }
        pasting=true;
        try {
            for (int i=0;i<text.length();i++) {
                if (!connected() || screen().getOIA().isKeyBoardLocked()) {
                    pasting=false;
                    view.screenChanged();
                    updateOia();
                    problem("Paste stopped at input inhibition after "+i+" characters.");
                    return false;
                }
                char c=text.charAt(i);
                boolean accepted=c=='\n' ? send(KeyMnemonic.NEW_LINE) : c=='\t' ? send(KeyMnemonic.TAB) : type(c);
                if (!accepted) return false;
            }
            return true;
        } finally {
            if (pasting) {
                pasting=false;
                view.screenChanged();
                updateOia();
                view.restartCursor();
            }
        }
    }

    public static void checkPasswordField(Screen5250 screen,int length) {
        if (screen==null || screen.getOIA().isKeyBoardLocked())
            throw new IllegalArgumentException("Password input is inhibited; nothing inserted.");
        int cursor=screen.getCurrentPos()-1;
        ScreenField field=screen.getScreenFields().getCurrentField();
        if (field==null || !field.withinField(cursor) || field.isBypassField())
            throw new IllegalArgumentException("Place the cursor in an input password field; nothing inserted.");
        if (length>field.endPos()-cursor+1)
            throw new IllegalArgumentException("Password exceeds the remaining field length; nothing inserted.");
        char[] attributes=new char[screen.getScreenLength()+1];
        screen.GetScreen(attributes,attributes.length,PLANE_EXTENDED);
        for (int i=cursor;i<cursor+Math.max(1,length);i++) {
            if ((attributes[i]&EXTENDED_5250_NON_DSP)==0)
                throw new IllegalArgumentException("Password field focus changed or is visible; nothing inserted.");
        }
    }

    public void sendPassword() {
        if (workspace.connectionProfile(profile).authentication()==Profile.Authentication.KERBEROS) {
            problem("Kerberos uses Windows sign-in; there is no remembered password to insert."); return;
        }
        if (!connected()) { problem("Connect before inserting a password."); return; }
        if (!workspace.loginProfile(profile).rememberPassword()) { problem("This login does not use a saved password."); return; }
        char[] extended=new char[screen().getScreenLength()+1];
        screen().GetScreen(extended,extended.length,PLANE_EXTENDED);
        if ((extended[screen().getCurrentPos()-1]&EXTENDED_5250_NON_DSP)==0) {
            problem("Place the cursor in a non-display password field before inserting a remembered password.");
            return;
        }
        long token=generation;
        Profile target=workspace.loginProfile(profile);
        CompletableFuture.supplyAsync(() -> {
            try { return workspace.credentials().read(target); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }).whenComplete((value,err) -> Ui.runLater(() -> {
            try {
                if (err!=null) throw err;
                if (value.isEmpty()) { problem("No remembered password for this profile."); return; }
                char[] password=value.get();
                try {
                    if (token==generation && connected()) {
                        Profile current=workspace.loginProfile(profile);
                        if (!current.rememberPassword() || !target.credentialTarget().equals(current.credentialTarget()))
                            throw new IllegalArgumentException("The selected login changed during lookup; nothing inserted.");
                        checkPasswordField(screen(),password.length);
                        for (char c:password)
                            if (c<32 || c==127) throw new IllegalArgumentException("Password contains control characters; nothing inserted.");
                        paste(new String(password));
                    }
                } finally { Arrays.fill(password,'\0'); }
            } catch (Throwable e) { problem("Password insertion failed: "+message(e)); }
        }));
    }

    Node tutorialConnectionTarget() { return connectButton.isVisible() && !busy() ? connectButton : close.isVisible() ? close : this; }
    Node tutorialCloseTarget() { return close.isVisible() ? close : header.isVisible() ? header : this; }
    boolean connecting() { return connecting || credentialOperations>0; }
    Node tutorialDragTarget() { return header.isVisible() ? header : dragZone.isVisible() ? dragZone : this; }

    public static Layout.Dock direction(java.awt.Point p,java.awt.Dimension d) {
        return direction(p.x,p.y,d.width,d.height);
    }

    public static Layout.Dock direction(double px,double py,double w,double h) {
        if (px<0 || py<0 || px>=w || py>=h) throw new IllegalArgumentException("Outside this pane.");
        double x=px/Math.max(1,w),y=py/Math.max(1,h);
        if (x>=.25 && x<=.75 && y>=.25 && y<=.75) return Layout.Dock.CENTER;
        double nearest=Math.min(Math.min(x,1-x),Math.min(y,1-y));
        if (nearest==y) return Layout.Dock.TOP;
        if (nearest==1-y) return Layout.Dock.BOTTOM;
        return nearest==x ? Layout.Dock.LEFT : Layout.Dock.RIGHT;
    }

    void beginAltDrag(MouseEvent event) {
        activate();
        workspace.beginPaneDrag(this,event);
    }

    void updateAltDrag(MouseEvent event) {
        if (workspace.dragPane(this,event,false)) view.setCursor(Cursor.MOVE);
    }

    void finishAltDrag(MouseEvent event) {
        view.setCursor(Cursor.DEFAULT);
        workspace.dragPane(this,event,true);
    }

    private void installDrag() {
        for (Node source:List.of(header,dragZone)) {
            source.setOnContextMenuRequested(e -> {
                if (!KioskMode.isEnabled()) {
                    showHeaderMenu(source,e.getScreenX(),e.getScreenY());
                    e.consume();
                }
            });
            source.setOnMousePressed(e -> {
                activate();
                if (e.getButton()==MouseButton.PRIMARY) workspace.beginPaneDrag(this,e);
            });
            source.setOnMouseDragged(e -> {
                if (e.isPrimaryButtonDown() && workspace.dragPane(this,e,false)) e.consume();
            });
            source.setOnMouseReleased(e -> {
                if (e.getButton()==MouseButton.SECONDARY && !KioskMode.isEnabled()) {
                    showHeaderMenu(source,e.getScreenX(),e.getScreenY());
                    e.consume();
                    return;
                }
                if (e.getButton()==MouseButton.PRIMARY && workspace.dragPane(this,e,true)) e.consume();
            });
        }
    }

    public void clearPreview() { workspace.clearPreviews(); }

    private void showHeaderMenu(Node owner,double x,double y) {
        if (!workspace.popupAt(owner)) workspace.showPopup(workspace.paneMenu(this),owner,x,y);
    }

    private static String message(Throwable e) {
        Throwable cause=e.getCause()==null ? e : e.getCause();
        if (cause instanceof java.io.UncheckedIOException uio && uio.getCause()!=null) cause=uio.getCause();
        return cause.getMessage()==null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
