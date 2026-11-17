package fish.ui;

import java.util.*;
import java.util.function.Consumer;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import fish.model.*;

/** Searchable syntax and action reference derived from the live parser and command catalog. */
public final class KeyboardReference {
    public static final String EXAMPLES="Single: F5 or ENTER.  Ctrl+F5: ctrl F5.  Wheel: ctrl scroll + or ctrl SCROLL-.";
    public static final String ALTERNATIVES="Use spaces, not + signs. One alternative per line. Main: ENTER. Keypad: ENTER@numpad.";

    public record KeyRow(String label,String token) {}
    public record ActionRow(String scope,String action,String defaults) {}

    private final Stage stage;
    private final TextField search=new TextField();
    private final TabPane tabs=new TabPane();
    private final TableView<KeyRow> keysTable;
    private final TableView<ActionRow> actionsTable;
    private final TextArea syntax=new TextArea();
    private final Button use;

    public KeyboardReference(Window owner,Consumer<String> chooseAction) {
        stage=Ui.dialogStage(owner,"Allowed keys and actions",false);
        search.setAccessibleText("Find allowed key or assignable action");
        HBox searchInput=Ui.searchBox(search,"Filter keys or actions...");
        HBox.setHgrow(searchInput,Priority.ALWAYS);
        Label findLbl=new Label("Find key or action");
        findLbl.setLabelFor(search);
        HBox searchRow=new HBox(8,findLbl,searchInput);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        Label help=new Label(EXAMPLES+"\n"+ALTERNATIVES+"\n"
                +"Modifiers: ctrl, shift, alt, altGraph. Locations: "+String.join(", ",KeyChord.locations())+".\n"
                +"Windows keys, Ctrl+Escape, Alt+F4 and Alt+Space are reserved. Unmodified typing keys cannot run Fish actions.");
        help.setWrapText(true);
        Ui.role(help,"muted");
        VBox top=new VBox(8,searchRow,help);

        FilteredList<KeyRow> filteredKeys=new FilteredList<>(FXCollections.observableArrayList(
                KeyChord.supportedKeys().stream().map(s -> new KeyRow(KeyChord.parse(s).label(),s)).toList()),r -> true);
        keysTable=new TableView<>(filteredKeys);
        keysTable.setAccessibleText("Allowed action keys");
        keysTable.setFixedCellSize(27);
        TableColumn<KeyRow,String> kCol1=new TableColumn<>("Key");
        kCol1.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().label()));
        kCol1.setPrefWidth(260);
        TableColumn<KeyRow,String> kCol2=new TableColumn<>("Type this token");
        kCol2.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().token()));
        kCol2.setPrefWidth(320);
        keysTable.getColumns().setAll(List.of(kCol1,kCol2));

        FilteredList<ActionRow> filteredActions=new FilteredList<>(FXCollections.observableArrayList(
                KeyBindings.defaultsByAction().entrySet().stream().filter(e -> !Shortcuts.SETTINGS_ALIASES.contains(e.getKey()))
                        .map(e -> new ActionRow(e.getKey().equals(KeyBindings.UNBOUND) ? "Terminal"
                                : KeyBindings.command(e.getKey()).host()==null ? "Fish" : "Terminal",
                                e.getKey(),KeyChord.text(e.getValue()).replace("\n"," ; "))).toList()),r -> true);
        actionsTable=new TableView<>(filteredActions);
        actionsTable.setAccessibleText("Assignable actions");
        actionsTable.setFixedCellSize(27);
        TableColumn<ActionRow,String> aCol1=new TableColumn<>("Scope");
        aCol1.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().scope()));
        aCol1.setPrefWidth(75);
        TableColumn<ActionRow,String> aCol2=new TableColumn<>("Action");
        aCol2.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().action()));
        aCol2.setPrefWidth(240);
        TableColumn<ActionRow,String> aCol3=new TableColumn<>("Default entry");
        aCol3.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().defaults()));
        aCol3.setPrefWidth(340);
        actionsTable.getColumns().setAll(List.of(aCol1,aCol2,aCol3));

        Tab keysTab=new Tab("Keys",keysTable); keysTab.setClosable(false);
        Tab actionsTab=new Tab("Actions",actionsTable); actionsTab.setClosable(false);
        tabs.getTabs().addAll(keysTab,actionsTab);
        tabs.setAccessibleText("Allowed keys and assignable actions");

        syntax.setEditable(false);
        syntax.setPrefRowCount(3);
        syntax.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px;");
        syntax.setAccessibleText("Selected key token or action default bindings, one per line");

        use=Ui.button("Edit selected action",() -> {
            ActionRow sel=actionsTable.getSelectionModel().getSelectedItem();
            if (sel!=null) { chooseAction.accept(sel.action()); dispose(); }
        });
        use.setDisable(true);
        Button done=Ui.button("Done",this::dispose);
        done.setDefaultButton(true);
        HBox buttons=new HBox(8,use,done);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox bottom=new VBox(8,syntax,buttons);
        BorderPane body=new BorderPane();
        body.setPadding(new Insets(16));
        body.setTop(top);
        BorderPane.setMargin(top,new Insets(0,0,12,0));
        body.setCenter(tabs);
        body.setBottom(bottom);
        BorderPane.setMargin(bottom,new Insets(12,0,0,0));

        search.textProperty().addListener((o,a,val) -> {
            String q=val==null ? "" : val.strip().toLowerCase(Locale.ROOT);
            filteredKeys.setPredicate(r -> q.isEmpty() || (r.label()+" "+r.token()).toLowerCase(Locale.ROOT).contains(q));
            filteredActions.setPredicate(r -> q.isEmpty() || (r.scope()+" "+r.action()+" "+r.defaults()).toLowerCase(Locale.ROOT).contains(q));
            describe();
        });
        keysTable.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> describe());
        actionsTable.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> describe());
        tabs.getSelectionModel().selectedIndexProperty().addListener((o,a,b) -> describe());
        describe();

        Ui.decorateDialog(stage,body,this::dispose,760,580);
        stage.setMinWidth(620);
        stage.setMinHeight(480);
    }

    private void describe() {
        boolean actionTab=tabs.getSelectionModel().getSelectedIndex()==1;
        ActionRow selAction=actionsTable.getSelectionModel().getSelectedItem();
        KeyRow selKey=keysTable.getSelectionModel().getSelectedItem();
        use.setDisable(!actionTab || selAction==null);
        if (actionTab) {
            syntax.setText(selAction==null ? "Select a row to see its exact entry syntax." : KeyBindings.defaultText(selAction.action()));
        } else {
            syntax.setText(selKey==null ? "Select a row to see its exact entry syntax." : selKey.token());
        }
    }

    public Stage stage() { return stage; }
    public boolean isShowing() { return stage.isShowing(); }
    public void show() { stage.show(); stage.toFront(); focusSearch(); }
    public void dispose() { stage.close(); }
    public void focusSearch() { search.requestFocus(); }
    public TabPane tabPane() { return tabs; }
    public TableView<ActionRow> actionsTable() { return actionsTable; }
    public TableView<KeyRow> keysTable() { return keysTable; }
    public Button editActionButton() { return use; }
    public TextField searchField() { return search; }
}
