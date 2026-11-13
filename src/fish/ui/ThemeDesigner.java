package fish.ui;

import java.util.*;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import fish.model.*;

/** Transactional custom theme editor with visual ColorPicker swatches + exact #RRGGBB hex editing. */
public final class ThemeDesigner {
    public static final class RoleEntry {
        public final ColorRole role;
        public final SimpleStringProperty hex;
        RoleEntry(ColorRole role,String hex) {
            this.role=role;
            this.hex=new SimpleStringProperty(hex);
        }
    }

    private final Workspace workspace;
    private final Stage stage;
    private final ComboBox<Object> saved=new ComboBox<>();
    private final TextField name=new TextField();
    private final Label status=new Label(" ");
    private final ObservableList<RoleEntry> entries=FXCollections.observableArrayList();
    private final TableView<RoleEntry> table=new TableView<>(entries);
    private final Button apply,done;
    private ColorTheme applied;
    private boolean loading;

    public ThemeDesigner(Workspace workspace) {
        this.workspace=workspace;
        this.stage=Ui.dialogStage(workspace.stage(),"Theme designer",true);

        saved.setAccessibleText("Load built-in or saved custom theme");
        saved.setMaxWidth(Double.MAX_VALUE);
        saved.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Object item,boolean empty) {
                super.updateItem(item,empty);
                setText(empty || item==null ? null : item instanceof ColorTheme ct ? "Custom: "+ct.name() : item.toString());
            }
        });
        saved.setButtonCell(saved.getCellFactory().call(null));

        name.setAccessibleText("Custom theme name");
        name.setPromptText("Theme name");

        GridPane top=new GridPane();
        top.setHgap(12); top.setVgap(8);
        ColumnConstraints c0=new ColumnConstraints(140);
        ColumnConstraints c1=new ColumnConstraints(); c1.setHgrow(Priority.ALWAYS);
        top.getColumnConstraints().addAll(c0,c1);
        Label loadLbl=new Label("Load theme"); loadLbl.setLabelFor(saved);
        Label nameLbl=new Label("Custom theme name"); nameLbl.setLabelFor(name);
        top.add(loadLbl,0,0); top.add(saved,1,0);
        top.add(nameLbl,0,1); top.add(name,1,1);

        TableColumn<RoleEntry,String> areaCol=new TableColumn<>("Area");
        areaCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().role.group));
        areaCol.setPrefWidth(130);

        TableColumn<RoleEntry,String> roleCol=new TableColumn<>("Color role");
        roleCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().role.toString()));
        roleCol.setPrefWidth(180);

        TableColumn<RoleEntry,RoleEntry> hexCol=new TableColumn<>("#RRGGBB / Swatch");
        hexCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        hexCol.setPrefWidth(270);
        hexCol.setCellFactory(col -> new TableCell<>() {
            private final ColorPicker picker=new ColorPicker();
            private final TextField field=new TextField();
            private final HBox box=new HBox(8,picker,field);
            private boolean syncing;
            {
                box.setAlignment(Pos.CENTER_LEFT);
                picker.setPrefWidth(46);
                picker.setStyle("-fx-color-label-visible: false;");
                HBox.setHgrow(field,Priority.ALWAYS);
                field.setStyle("-fx-font-family: 'Consolas';");
                picker.setOnAction(e -> {
                    RoleEntry item=getItem();
                    if (syncing || item==null) return;
                    Color c=picker.getValue();
                    int rgb=((int)Math.round(c.getRed()*255)<<16)|((int)Math.round(c.getGreen()*255)<<8)|(int)Math.round(c.getBlue()*255);
                    syncing=true;
                    String formatted=String.format("#%06X",rgb);
                    field.setText(formatted);
                    item.hex.set(formatted);
                    Ui.markInvalid(field,false);
                    syncing=false;
                    refreshActions();
                });
                field.textProperty().addListener((o,a,val) -> {
                    RoleEntry item=getItem();
                    if (syncing || item==null) return;
                    item.hex.set(val);
                    try {
                        int rgb=ColorTheme.parse(val.strip());
                        syncing=true;
                        picker.setValue(Color.rgb((rgb>>16)&255,(rgb>>8)&255,rgb&255));
                        syncing=false;
                        Ui.markInvalid(field,false);
                    } catch (IllegalArgumentException ex) {
                        Ui.markInvalid(field,true);
                    }
                    refreshActions();
                });
            }
            @Override protected void updateItem(RoleEntry item,boolean empty) {
                super.updateItem(item,empty);
                if (empty || item==null) { setGraphic(null); return; }
                syncing=true;
                field.setText(item.hex.get());
                try {
                    int rgb=ColorTheme.parse(item.hex.get().strip());
                    picker.setValue(Color.rgb((rgb>>16)&255,(rgb>>8)&255,rgb&255));
                    Ui.markInvalid(field,false);
                } catch (IllegalArgumentException ex) {
                    Ui.markInvalid(field,true);
                }
                syncing=false;
                setGraphic(box);
            }
        });

        table.getColumns().setAll(List.of(areaCol,roleCol,hexCol));
        table.setFixedCellSize(34);
        table.setAccessibleText("Every semantic color role; edit exact RGB values or pick a swatch");

        Button cancel=Ui.button("Cancel",this::dismiss);
        Button preview=Ui.button("Preview",this::preview);
        Button save=Ui.button("Save",() -> save(false));
        apply=Ui.button("Apply",() -> save(true));
        done=Ui.button("Done",() -> { if (!changed() || save(true)) dismiss(); });
        done.setDefaultButton(true);

        HBox buttons=new HBox(8,cancel,preview,save,apply,done);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox bottom=new VBox(10,status,buttons);
        BorderPane content=new BorderPane();
        content.setPadding(new Insets(14));
        content.setTop(top);
        BorderPane.setMargin(top,new Insets(0,0,12,0));
        content.setCenter(table);
        content.setBottom(bottom);
        BorderPane.setMargin(bottom,new Insets(12,0,0,0));

        saved.setOnAction(e -> {
            if (loading) return;
            Object value=saved.getValue();
            if (value instanceof ColorTheme ct) load(ct);
            else if (value instanceof AppTheme at) load(ColorTheme.builtin(at));
        });

        reload();
        EnumMap<ColorRole,Integer> current=new EnumMap<>(Ui.colorTheme().colors());
        java.awt.Color[] host=HostPalette.colors(workspace.active().profile().theme());
        for (int n=0;n<16;n++) current.put(ColorRole.valueOf("HOST_"+n),host[n].getRGB()&0xffffff);
        load(new ColorTheme(workspace.appearanceSettings().custom().map(ColorTheme::name).orElse("My theme"),current));
        applied=draft();

        name.textProperty().addListener((o,a,b) -> refreshActions());
        refreshActions();

        Ui.decorateDialog(stage,content,this::dismiss,680,640);
        stage.setMinWidth(560);
        stage.setMinHeight(400);
    }

    public Stage stage() { return stage; }
    public ObservableList<RoleEntry> roleEntries() { return entries; }
    public TextField nameField() { return name; }
    public ComboBox<Object> savedCombo() { return saved; }
    public Label statusLabel() { return status; }

    public void setVisible(boolean visible) {
        if (visible) stage.showAndWait();
        else dismiss();
    }

    public void setRoleHex(ColorRole role,String hexValue) {
        for (RoleEntry entry:entries) if (entry.role==role) { entry.hex.set(hexValue); break; }
        table.refresh();
        refreshActions();
    }

    private void reload() {
        loading=true;
        List<Object> items=new ArrayList<>(Arrays.asList(AppTheme.values()));
        items.addAll(workspace.appearanceSettings().themes());
        saved.setItems(FXCollections.observableArrayList(items));
        loading=false;
    }

    private void load(ColorTheme theme) {
        name.setText(theme.name());
        List<RoleEntry> list=new ArrayList<>();
        for (ColorRole role:ColorRole.values())
            list.add(new RoleEntry(role,String.format("#%06X",theme.rgb(role))));
        entries.setAll(list);
        refreshActions();
    }

    private boolean changed() {
        if (applied==null) return true;
        try {
            ColorTheme value=draft();
            return !value.equals(applied) || workspace.appearanceSettings().themes().contains(value)
                    && !workspace.appearanceSettings().selectedTheme().equals(value.name());
        } catch (IllegalArgumentException e) { return true; }
    }

    private void refreshActions() {
        if (apply!=null) apply.setDisable(!changed());
    }

    public ColorTheme draft() {
        EnumMap<ColorRole,Integer> values=new EnumMap<>(ColorRole.class);
        for (RoleEntry entry:entries) {
            try { values.put(entry.role,ColorTheme.parse(Objects.toString(entry.hex.get(),"").strip())); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException(entry.role+": "+e.getMessage(),e); }
        }
        return new ColorTheme(name.getText(),values);
    }

    public void preview() {
        try {
            ColorTheme theme=draft();
            Ui.applyTheme(Ui.theme(),theme);
            Ui.role(status,"muted");
            status.setText(contrastWarning(theme));
            status.setTooltip(null);
        } catch (IllegalArgumentException e) {
            Ui.role(status,"error");
            status.setText(e.getMessage());
            status.setTooltip(new Tooltip(e.getMessage()));
        }
    }

    private static String contrastWarning(ColorTheme theme) {
        java.awt.Color text=new java.awt.Color(theme.rgb(ColorRole.TEXT)),background=new java.awt.Color(theme.rgb(ColorRole.FIELD));
        double diff=Math.abs(text.getRed()*.2126+text.getGreen()*.7152+text.getBlue()*.0722
                -background.getRed()*.2126-background.getGreen()*.7152-background.getBlue()*.0722);
        return diff<80 ? "Warning: text/field contrast may be low; chosen values are retained." : "";
    }

    public boolean save(boolean activate) {
        try {
            ColorTheme value=draft();
            Appearance current=workspace.appearanceSettings();
            List<ColorTheme> themes=new ArrayList<>(current.themes());
            ColorTheme previous=themes.stream().filter(t -> t.name().equalsIgnoreCase(value.name())).findFirst().orElse(null);
            if (previous!=null && !previous.equals(value) && !Ui.confirm(stage,"Replace saved custom theme \""+previous.name()+"\"?","Theme name exists")) return false;
            themes.removeIf(t -> t.name().equalsIgnoreCase(value.name()));
            if (themes.size()>=32) throw new IllegalArgumentException("Up to 32 custom themes are supported. Edit an existing theme or choose another workspace.");
            themes.add(value);
            String selected=activate || previous!=null && current.selectedTheme().equals(previous.name()) ? value.name() : current.selectedTheme();
            workspace.appearanceSettings(current.withTheme(selected,themes));
            reload();
            saved.setValue(value);
            Ui.role(status,"muted");
            status.setText(contrastWarning(value));
            status.setTooltip(null);
            if (selected.equals(value.name())) applied=value;
            refreshActions();
            return true;
        } catch (IllegalArgumentException e) {
            Ui.role(status,"error");
            status.setText(e.getMessage());
            status.setTooltip(new Tooltip(e.getMessage()));
            return false;
        }
    }

    public void dismiss() {
        Ui.applyTheme(Ui.theme(),workspace.appearanceSettings().custom().orElse(null));
        stage.close();
    }
}
