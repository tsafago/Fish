package fish.ui;

import java.util.*;
import javafx.event.EventHandler;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import fish.model.ColorRole;
import fish.model.Profile;

/** Native JavaFX 25 StageStyle.EXTENDED + HeaderBar unified window chrome. */
@SuppressWarnings({"deprecation", "removal"})
public final class WindowChrome extends HeaderBar {
    public static final int HEIGHT=31;
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
    private Boolean compact;
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
        getStyleClass().add("fish-header-bar");
        setMinHeight(HEIGHT);
        setPrefHeight(HEIGHT);
        setMaxHeight(HEIGHT);
        HeaderBar.setPrefButtonHeight(stage,0);

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
        HeaderBar.setButtonType(minimizeBtn,HeaderButtonType.ICONIFY);
        minimizeBtn.setFocusTraversable(true);
        maximizeBtn=windowControlButton("Maximize window","maximize",false,this::toggleMaximize);
        HeaderBar.setButtonType(maximizeBtn,HeaderButtonType.MAXIMIZE);
        maximizeBtn.setFocusTraversable(true);
        closeBtn=windowControlButton("Close window","close",true,this::requestClose);
        HeaderBar.setButtonType(closeBtn,HeaderButtonType.CLOSE);
        closeBtn.setFocusTraversable(true);

        controls=new HBox(0,minimizeBtn,maximizeBtn,closeBtn);
        controls.setAlignment(Pos.CENTER_RIGHT);
        setTrailing(controls);

        stage.maximizedProperty().addListener((obs,was,max) -> {
            String label=max ? "Restore window" : "Maximize window";
            maximizeBtn.setAccessibleText(label);
            maximizeBtn.setTooltip(new Tooltip(label));
            maximizeBtn.setGraphic(Ui.icon(max ? "restore" : "maximize",ColorRole.CHROME_TEXT));
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
    public HBox controlsBox() { return controls; }
    public BorderPane dragArea() { return dragArea; }
    public MenuBar menuBar() { return menus; }
    public HBox toolsBox() { return tools; }

    public double minimumBarWidth() {
        double width=48+32+38*3+32;
        for (Node child:tools.getChildren()) width+=Math.max(child.minWidth(-1),16);
        return width;
    }

    @Override protected void layoutChildren() {
        double available=getWidth();
        if (available>0) {
            menus.applyCss();
            double menusW=Math.max(240,menus.prefWidth(-1));
            double toolsBase=0;
            ComboBox<?> selector=null;
            for (Node n:tools.getChildren()) {
                if (n instanceof ComboBox<?> cb) selector=cb;
                else toolsBase+=n.prefWidth(-1);
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
                double w=Math.max(68,Math.min(desiredCombo,available-fixed));
                selector.setPrefWidth(w);
            }
        }
        super.layoutChildren();
    }

    private static double profileWidth(ComboBox<?> combo) {
        String text=Objects.toString(combo.getValue(),"Profile");
        return Math.max(88,Math.min(210,text.length()*7.2+44));
    }

    public void openMenus() {
        if (menus.isVisible() && !menus.getMenus().isEmpty()) {
            menus.getMenus().getFirst().show();
        } else {
            openCompactMenu();
        }
    }

    public ContextMenu openCompactMenu() {
        ContextMenu popup=new ContextMenu();
        for (Menu m:menus.getMenus()) popup.getItems().add(copyMenu(m));
        popup.show(menuButton,Side.BOTTOM,0,0);
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
        if (menus.isVisible()) {
            for (Menu m:menus.getMenus()) if (m.getText().equals(title)) { m.show(); return; }
        } else {
            openCompactMenu();
        }
    }

    public Node tutorialMenuTarget(String title,String action) {
        if (!menus.isVisible()) return menuButton;
        return menus;
    }

    public void showSystemMenu() {
        ContextMenu menu=systemMenu();
        menu.show(this,Side.BOTTOM,0,0);
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
        caption.repaintMark();
        ColorRole ink=stage.isFocused() ? ColorRole.CHROME_TEXT : ColorRole.CHROME_INACTIVE;
        minimizeBtn.setGraphic(Ui.icon("minimize",ink));
        maximizeBtn.setGraphic(Ui.icon(stage.isMaximized() ? "restore" : "maximize",ink));
        closeBtn.setGraphic(Ui.icon("close",ink));
        keyboardHint.setStyle("-fx-text-fill: "+Ui.hex(ColorRole.CHROME_INACTIVE)+"; -fx-padding: 0 8 0 8;");
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
            setAccessibleText("Toggle animated Flux text effect in active pane");
            addEventFilter(MouseEvent.MOUSE_PRESSED,e -> {
                keyFocused=false;
                pressX=e.getX(); pressY=e.getY(); dragged=false; suppressNextFire=false;
                updateFocusBar();
            });
            addEventFilter(MouseEvent.MOUSE_DRAGGED,e -> {
                if (Math.hypot(e.getX()-pressX,e.getY()-pressY)>=5) dragged=true;
            });
            addEventFilter(MouseEvent.MOUSE_RELEASED,e -> {
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
                keyFocused=true;
                updateFocusBar();
            });
            focusedProperty().addListener((o,a,f) -> {
                if (!f) keyFocused=false;
                updateFocusBar();
            });
            setOnAction(e -> toggleFlux.run());
        }

        @Override public void fire() {
            if (suppressNextFire) { suppressNextFire=false; return; }
            super.fire();
        }

        void repaintMark() {
            FishIcon.repaintCanvas(mark);
            updateFocusBar();
        }

        private void updateFocusBar() {
            if (isFocused() && keyFocused) {
                setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-border-color: transparent transparent "+Ui.hex(ColorRole.FOCUS)+" transparent; -fx-border-width: 0 0 1 0;");
            } else {
                setStyle("-fx-background-color: transparent; -fx-padding: 0;");
            }
        }
    }
}
