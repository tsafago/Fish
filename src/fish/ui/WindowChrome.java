package fish.ui;

import java.util.*;
import javafx.event.EventHandler;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import fish.model.ColorRole;
import fish.model.Profile;
import fish.security.KioskMode;

/** Native JavaFX 25 StageStyle.EXTENDED + HeaderBar unified window chrome. */
@SuppressWarnings({"deprecation", "removal"})
public final class WindowChrome extends HeaderBar {
    public static final int HEIGHT=34;
    private final Workspace workspace;
    private final Stage stage;
    private final LogoButton caption;
    private final Label keyboardHint=new Label();
    private final Button minimizeBtn,maximizeBtn,closeBtn;
    private final MenuBar menus;
    private final HBox tools;
    private final HBox left;
    private final BorderPane dragArea;
    private final HBox controls;
    private final Button menuButton;
    private final Region bottomLine=new Region();
    private final Tooltip maximizeTooltip=new Tooltip("Maximize window");
    private Boolean compact;
    private double cachedMenusWidth=-1;
    private Rectangle2D normalBounds;
    private Rectangle2D keyboardStart;
    private Node keyboardFocus;
    private boolean keyboardSizing;
    private final EventHandler<KeyEvent> windowKeys=this::keyboardEvent;

    WindowChrome(Workspace workspace,Stage stage,MenuBar menus,HBox tools,Runnable toggleFlux) {
        this.workspace=workspace;
        this.stage=stage;
        this.menus=menus;
        this.tools=tools;
        menus.setFocusTraversable(false);
        menus.setMaxHeight(Double.MAX_VALUE);
        menus.skinProperty().addListener((o,a,b) -> workspace.tutorialChanged());
        getStyleClass().addAll("fish-header-bar","fish-workspace-header");
        setSnapToPixel(true);
        updateChromeHeight(1.0);
        HeaderBar.setPrefButtonHeight(stage,0);

        bottomLine.setManaged(false);
        bottomLine.setMouseTransparent(true);
        bottomLine.setSnapToPixel(false);
        bottomLine.setStyle("-fx-background-color: "+Ui.hex(ColorRole.DIVIDER)+";");
        getChildren().add(bottomLine);

        caption=new LogoButton(toggleFlux);
        menuButton=Ui.iconButton("Application menus","down",ColorRole.CHROME_TEXT,this::openCompactMenu);
        menuButton.getStyleClass().add("fish-chrome-button");
        menuButton.setPrefSize(32,HEIGHT-1);
        menuButton.setVisible(false);
        menuButton.setManaged(false);

        left=new HBox(0,caption,menuButton,menus,tools);
        left.setAlignment(Pos.CENTER_LEFT);
        setLeading(left);

        dragArea=new BorderPane();
        dragArea.setId("window-drag-area");
        dragArea.setAccessibleText("Window title; drag to move, double-click to maximize or restore");
        keyboardHint.setStyle("-fx-text-fill: "+Ui.hex(ColorRole.CHROME_INACTIVE)+"; -fx-padding: 0 8 0 8;");
        dragArea.setLeft(keyboardHint);
        BorderPane.setAlignment(keyboardHint,Pos.CENTER_LEFT);
        HeaderBar.setDragType(dragArea,HeaderDragType.DRAGGABLE);
        HeaderBar.setDragType(keyboardHint,HeaderDragType.DRAGGABLE);
        setCenter(dragArea);

        minimizeBtn=windowControlButton("Minimize window","minimize",false,() -> stage.setIconified(true));
        Ui.bindHeaderButton(minimizeBtn,HeaderButtonType.ICONIFY);
        minimizeBtn.setFocusTraversable(true);
        maximizeBtn=windowControlButton("Maximize window","maximize",false,this::toggleMaximize);
        maximizeBtn.setTooltip(maximizeTooltip);
        maximizeBtn.addEventFilter(MouseEvent.MOUSE_PRESSED,e -> {
            if (e.getButton()==MouseButton.PRIMARY) rememberNormal();
        });
        Ui.bindHeaderButton(maximizeBtn,HeaderButtonType.MAXIMIZE);
        maximizeBtn.setFocusTraversable(true);
        closeBtn=windowControlButton("Close window","close",true,this::requestClose);
        Ui.bindHeaderButton(closeBtn,HeaderButtonType.CLOSE);
        closeBtn.setFocusTraversable(true);

        controls=new HBox(0,minimizeBtn,maximizeBtn,closeBtn);
        controls.setAlignment(Pos.CENTER_RIGHT);
        setTrailing(controls);

        stage.maximizedProperty().addListener((obs,was,max) -> {
            String label=max ? "Restore window" : "Maximize window";
            maximizeBtn.setAccessibleText(label);
            Ui.setTooltipText(maximizeBtn,maximizeTooltip,label);
            ColorRole ink=stage.isFocused() ? ColorRole.CHROME_TEXT : ColorRole.CHROME_INACTIVE;
            Ui.updateIcon(maximizeBtn.getGraphic(),max ? "restore" : "maximize",ink);
        });
        stage.outputScaleXProperty().addListener((o,a,scale) -> {
            updateChromeHeight(scale==null ? 1.0 : scale.doubleValue());
            requestLayout();
        });
        stage.renderScaleXProperty().addListener((o,a,scale) -> {
            updateChromeHeight(Ui.scaleFor(this));
            requestLayout();
        });
        stage.xProperty().addListener((o,a,b) -> rememberNormal());
        stage.yProperty().addListener((o,a,b) -> rememberNormal());
        stage.widthProperty().addListener((o,a,b) -> rememberNormal());
        stage.heightProperty().addListener((o,a,b) -> rememberNormal());
        stage.focusedProperty().addListener((o,a,focused) -> {
            if (!focused) finishKeyboard(true);
            themeChanged();
        });
    }

    private void updateChromeHeight(double scale) {
        double s=scale>0 ? scale : 1.0;
        double snapped=Math.round(HEIGHT*s)/s;
        setMinHeight(snapped);
        setPrefHeight(snapped);
        setMaxHeight(snapped);
    }

    public void invalidateMenuWidth() {
        cachedMenusWidth=-1;
        requestLayout();
    }

    private Button windowControlButton(String accessibleName,String iconKind,boolean closeButton,Runnable action) {
        Button btn=new Button();
        btn.setGraphic(Ui.icon(iconKind,ColorRole.CHROME_TEXT));
        btn.getStyleClass().add("fish-chrome-button");
        if (closeButton) btn.getStyleClass().add("fish-window-close");
        btn.setAccessibleText(accessibleName);
        btn.setTooltip(new Tooltip(accessibleName));
        btn.setMinSize(38,HEIGHT-1);
        btn.setPrefSize(38,HEIGHT-1);
        btn.setMaxSize(38,HEIGHT-1);
        btn.setFocusTraversable(true);
        btn.setOnAction(e -> action.run());
        return btn;
    }

    public Button logoButton() { return caption; }
    public Button minimizeButton() { return minimizeBtn; }
    public Button maximizeButton() { return maximizeBtn; }
    public Button closeButton() { return closeBtn; }
    public HBox controlsBox() { return controls; }
    public BorderPane dragArea() { return dragArea; }
    public MenuBar menuBar() { return menus; }
    public HBox toolsBox() { return tools; }

    public double minimumBarWidth() {
        double width=48+32+38*3+32;
        if (tools.isManaged() && tools.isVisible()) {
            for (Node child:tools.getChildren()) width+=Math.max(child.minWidth(-1),16);
        }
        return width;
    }

    public void syncKioskState() {
        compact=null;
        cachedMenusWidth=-1;
        caption.syncKioskState();
        requestLayout();
    }

    @Override protected void layoutChildren() {
        double s=Ui.scaleFor(this);
        double snappedH=Math.round(HEIGHT*s)/s;
        if (Math.abs(getPrefHeight()-snappedH)>1e-4) {
            updateChromeHeight(s);
        }
        double available=getWidth();
        boolean toolsShown=tools.isManaged() && tools.isVisible();
        if (available>0) {
            if (cachedMenusWidth<=0) {
                double measured=menus.prefWidth(-1);
                if (measured>40) cachedMenusWidth=measured;
            }
            double minMenus=toolsShown ? 240 : 80;
            double menusW=Math.max(minMenus,cachedMenusWidth>0 ? cachedMenusWidth : menus.prefWidth(-1));
            double toolsBase=0;
            ComboBox<?> selector=null;
            if (toolsShown) {
                for (Node n:tools.getChildren()) {
                    if (n instanceof ComboBox<?> cb) selector=cb;
                    else toolsBase+=n.prefWidth(-1);
                }
            }
            double desiredCombo=selector==null ? 0 : profileWidth(selector);
            double fullNeeded=48+menusW+toolsBase+desiredCombo+controls.prefWidth(-1)+36;
            boolean narrow=available<fullNeeded;
            if (compact==null || compact!=narrow) {
                compact=narrow;
                menus.setVisible(!narrow);
                menus.setManaged(!narrow);
                menuButton.setVisible(narrow);
                menuButton.setManaged(narrow);
            }
            if (selector!=null) {
                double fixed=48+(narrow ? 32 : menusW)+toolsBase+controls.prefWidth(-1)+36;
                double minCombo=Math.max(72,selector.minWidth(-1));
                double w=Math.max(minCombo,Math.min(desiredCombo,available-fixed));
                if (Math.abs(selector.getPrefWidth()-w)>0.5) selector.setPrefWidth(w);
            }
        }
        if (toolsShown) for (Node n:tools.getChildren()) if (n instanceof Parent p) p.requestLayout();
        super.layoutChildren();
        if (toolsShown) for (Node n:tools.getChildren()) if (n instanceof Parent p) p.layout();
        double h=getHeight();
        double px=Ui.hairline(s);
        Point2D bottomScene=localToScene(0,h);
        double targetY=h-px;
        if (bottomScene!=null) {
            double devBottom=Math.round(bottomScene.getY()*s);
            targetY=h-(bottomScene.getY()-(devBottom-1.0)/s);
        }
        bottomLine.resizeRelocate(0,targetY,Math.max(0,available),px);
        bottomLine.toFront();
    }

    private static double profileWidth(ComboBox<?> combo) {
        String text=Objects.toString(combo.getValue(),"Profile");
        return Math.max(88,Math.min(210,text.length()*7.2+44));
    }

    public void openMenus() {
        workspace.hidePopup();
        if (menus.isVisible() && !menus.getMenus().isEmpty()) {
            menus.getMenus().getFirst().show();
        } else {
            openCompactMenu();
        }
    }

    public ContextMenu openCompactMenu() {
        ContextMenu popup=new ContextMenu();
        for (Menu m:menus.getMenus()) popup.getItems().add(copyMenu(m));
        showAtDivider(popup,menuButton);
        return popup;
    }

    private static Menu copyMenu(Menu original) {
        Menu copy=new Menu(original.getText());
        for (MenuItem item:original.getItems()) {
            if (item instanceof Menu sub) copy.getItems().add(copyMenu(sub));
            else if (item instanceof SeparatorMenuItem) copy.getItems().add(new SeparatorMenuItem());
            else if (item.getProperties().get("fish.command") instanceof AppCommand cmd) {
                String label=item.getText().contains("\t") ? item.getText().substring(0,item.getText().indexOf('\t')) : item.getText();
                copy.getItems().add(item instanceof CheckMenuItem ? cmd.createCheckMenuItem(label) : cmd.createMenuItem(label));
            } else {
                MenuItem clone=new MenuItem(item.getText());
                clone.setDisable(item.isDisable());
                clone.setOnAction(item.getOnAction());
                copy.getItems().add(clone);
            }
        }
        return copy;
    }

    public void showTutorialMenu(String title) {
        if (!stage.isShowing()) return;
        workspace.hidePopup();
        if (menus.isVisible()) {
            for (Menu m:menus.getMenus()) if (m.getText().equals(title)) { m.show(); return; }
        } else {
            openCompactMenu();
        }
    }

    public Node tutorialMenuTarget(String title,String action) {
        if (!menus.isVisible()) return menuButton;
        if (title!=null && !title.isEmpty()) {
            Node found=findMenuButton(menus,title);
            if (found!=null) return found;
        }
        return menus;
    }

    private static Node findMenuButton(Parent root,String title) {
        for (Node child:root.getChildrenUnmodifiable()) {
            if (child instanceof MenuButton mb && title.equals(cleanLabel(mb.getText())) && mb.isVisible()) return mb;
            if (child.getStyleClass().contains("menu-button") && child instanceof Labeled lb
                    && title.equals(cleanLabel(lb.getText())) && child.isVisible()) return child;
            if (child instanceof Parent p) {
                Node nested=findMenuButton(p,title);
                if (nested!=null) return nested;
            }
        }
        return null;
    }

    private static String cleanLabel(String text) {
        return text==null ? "" : text.replace("_","").trim();
    }

    public void showSystemMenu() {
        showAtDivider(systemMenu(),caption);
    }

    private void showAtDivider(ContextMenu popup,Node owner) {
        Point2D line=bottomLine.localToScreen(0,0);
        if (line!=null) workspace.showPopup(popup,owner,owner.localToScreen(0,0).getX(),line.getY());
    }

    public void toggleMaximize() {
        if (stage.isMaximized()) {
            stage.setMaximized(false);
            if (normalBounds!=null) {
                stage.setX(normalBounds.getMinX());
                stage.setY(normalBounds.getMinY());
                stage.setWidth(normalBounds.getWidth());
                stage.setHeight(normalBounds.getHeight());
            }
        } else {
            rememberNormal();
            stage.setMaximized(true);
        }
    }

    private void rememberNormal() {
        if (!stage.isMaximized() && !stage.isIconified() && stage.getWidth()>0 && stage.getHeight()>0) {
            normalBounds=new Rectangle2D(stage.getX(),stage.getY(),stage.getWidth(),stage.getHeight());
        }
    }

    private void requestClose() {
        finishKeyboard(false);
        workspace.close(false);
    }

    private ContextMenu systemMenu() {
        Node focus=workspace.scene()==null ? null : workspace.scene().getFocusOwner();
        ContextMenu menu=new ContextMenu();
        MenuItem restore=new MenuItem("Restore"); restore.setDisable(!stage.isMaximized()); restore.setOnAction(e -> toggleMaximize());
        MenuItem move=new MenuItem("Move"); move.setDisable(stage.isMaximized()); move.setOnAction(e -> startKeyboard(false,focus));
        MenuItem size=new MenuItem("Size"); size.setDisable(stage.isMaximized()); size.setOnAction(e -> startKeyboard(true,focus));
        MenuItem min=new MenuItem("Minimize"); min.setOnAction(e -> stage.setIconified(true));
        MenuItem max=new MenuItem("Maximize"); max.setDisable(stage.isMaximized()); max.setOnAction(e -> toggleMaximize());
        MenuItem close=new MenuItem("Close"); close.setOnAction(e -> requestClose());
        menu.getItems().addAll(restore,move,size,min,max,new SeparatorMenuItem(),close);
        return menu;
    }

    private void startKeyboard(boolean sizing,Node focus) {
        finishKeyboard(false);
        keyboardStart=new Rectangle2D(stage.getX(),stage.getY(),stage.getWidth(),stage.getHeight());
        keyboardSizing=sizing;
        keyboardFocus=focus;
        keyboardHint.setText(sizing ? "Size: arrows / Enter / Esc" : "Move: arrows / Enter / Esc");
        if (workspace.scene()!=null) workspace.scene().addEventFilter(KeyEvent.KEY_PRESSED,windowKeys);
    }

    private void keyboardEvent(KeyEvent event) {
        if (keyboardStart==null) return;
        KeyCode key=event.getCode();
        if (key==KeyCode.ESCAPE || key==KeyCode.ENTER) {
            finishKeyboard(key==KeyCode.ESCAPE);
            event.consume();
        } else if (key==KeyCode.LEFT || key==KeyCode.RIGHT || key==KeyCode.UP || key==KeyCode.DOWN) {
            double step=event.isShiftDown() ? 1 : 10;
            double dx=key==KeyCode.LEFT ? -step : key==KeyCode.RIGHT ? step : 0;
            double dy=key==KeyCode.UP ? -step : key==KeyCode.DOWN ? step : 0;
            if (keyboardSizing) {
                stage.setWidth(Math.max(stage.getMinWidth(),stage.getWidth()+dx));
                stage.setHeight(Math.max(stage.getMinHeight(),stage.getHeight()+dy));
            } else {
                stage.setX(stage.getX()+dx);
                stage.setY(stage.getY()+dy);
            }
            event.consume();
        }
    }

    void finishKeyboard(boolean cancel) {
        if (keyboardStart==null) return;
        if (workspace.scene()!=null) workspace.scene().removeEventFilter(KeyEvent.KEY_PRESSED,windowKeys);
        Rectangle2D saved=keyboardStart;
        keyboardStart=null;
        keyboardHint.setText("");
        if (cancel) {
            stage.setX(saved.getMinX());
            stage.setY(saved.getMinY());
            stage.setWidth(saved.getWidth());
            stage.setHeight(saved.getHeight());
        }
        if (keyboardFocus!=null && stage.isFocused()) keyboardFocus.requestFocus();
        keyboardFocus=null;
    }

    public void themeChanged() {
        cachedMenusWidth=-1;
        caption.repaintMark();
        ColorRole ink=stage.isFocused() ? ColorRole.CHROME_TEXT : ColorRole.CHROME_INACTIVE;
        Ui.updateIcon(minimizeBtn.getGraphic(),"minimize",ink);
        Ui.updateIcon(maximizeBtn.getGraphic(),stage.isMaximized() ? "restore" : "maximize",ink);
        Ui.updateIcon(closeBtn.getGraphic(),"close",ink);
        Ui.setStyleIfChanged(bottomLine,"-fx-background-color: "+Ui.hex(ColorRole.DIVIDER)+";");
        Ui.setStyleIfChanged(keyboardHint,"-fx-text-fill: "+Ui.hex(ColorRole.CHROME_INACTIVE)+"; -fx-padding: 0 8 0 8;");
    }

    private final class LogoButton extends Button {
        private final Canvas mark=FishIcon.canvas(27,20);
        private double pressX,pressY;
        private boolean dragged,suppressNextFire;
        private boolean keyFocused;
        private Profile firstClick;

        LogoButton(Runnable toggleFlux) {
            setGraphic(mark);
            setMinSize(48,HEIGHT-1);
            setPrefSize(48,HEIGHT-1);
            setMaxSize(48,HEIGHT-1);
            setStyle("-fx-background-color: transparent; -fx-padding: 0;");
            setTooltip(new Tooltip("Fish"));
            syncKioskState();
            addEventFilter(MouseEvent.MOUSE_PRESSED,e -> {
                if (KioskMode.isEnabled()) { e.consume(); return; }
                keyFocused=false;
                pressX=e.getX(); pressY=e.getY(); dragged=false; suppressNextFire=false;
                updateFocusBar();
            });
            addEventFilter(MouseEvent.MOUSE_DRAGGED,e -> {
                if (KioskMode.isEnabled()) return;
                if (Math.hypot(e.getX()-pressX,e.getY()-pressY)>=5) dragged=true;
            });
            addEventFilter(MouseEvent.MOUSE_RELEASED,e -> {
                if (KioskMode.isEnabled()) { e.consume(); return; }
                if (e.getButton()!=MouseButton.PRIMARY) return;
                if (dragged) {
                    suppressNextFire=true;
                    e.consume();
                    return;
                }
                if (e.getClickCount()>1 && e.getClickCount()%2==0 && firstClick!=null) {
                    suppressNextFire=true;
                    for (Profile live:workspace.profileList()) if (live.id().equals(firstClick.id()))
                        workspace.putProfile(live.withDisplay(live.theme(),firstClick.effect()));
                    firstClick=null;
                    e.consume();
                } else {
                    firstClick=workspace.active()!=null ? workspace.active().profile() : null;
                }
            });
            addEventFilter(KeyEvent.KEY_PRESSED,e -> {
                if (KioskMode.isEnabled()) return;
                keyFocused=true;
                updateFocusBar();
            });
            focusedProperty().addListener((o,a,f) -> {
                if (!f) keyFocused=false;
                updateFocusBar();
            });
            setOnAction(e -> {
                if (!KioskMode.isEnabled()) toggleFlux.run();
            });
        }

        void syncKioskState() {
            boolean kiosk=KioskMode.isEnabled();
            setFocusTraversable(!kiosk);
            setCursor(javafx.scene.Cursor.DEFAULT);
            setAccessibleText(kiosk ? "Fish" : "Toggle animated Flux text effect in active pane");
            if (kiosk) keyFocused=false;
            updateFocusBar();
        }

        @Override public void fire() {
            if (KioskMode.isEnabled()) return;
            if (suppressNextFire) { suppressNextFire=false; return; }
            super.fire();
        }

        void repaintMark() {
            FishIcon.repaintCanvas(mark);
            updateFocusBar();
        }

        private void updateFocusBar() {
            if (isFocused() && keyFocused) {
                String px=Ui.px(Ui.hairline(Ui.scaleFor(this)));
                Ui.setStyleIfChanged(this,"-fx-background-color: transparent; -fx-padding: 0; -fx-border-color: transparent transparent "+Ui.hex(ColorRole.FOCUS)+" transparent; -fx-border-width: 0 0 "+px+" 0;");
            } else {
                Ui.setStyleIfChanged(this,"-fx-background-color: transparent; -fx-padding: 0;");
            }
        }
    }
}
