package fish.ui;

import java.util.*;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import fish.model.*;

/** Validated edit buffer; Settings applies complete, conflict-free bindings automatically. */
public final class KeyboardEditor extends BorderPane {
    public static final class ActionBindingRow {
        public final String scope;
        public final String action;
        public final SimpleStringProperty effective;
        ActionBindingRow(String scope,String action,String effective) {
            this.scope=scope;
            this.action=action;
            this.effective=new SimpleStringProperty(effective);
        }
    }

    private final Map<String,String> values=new LinkedHashMap<>();
    private final List<String> actions=new ArrayList<>(KeyBindings.defaultsByAction().keySet().stream()
            .filter(action -> !Shortcuts.SETTINGS_ALIASES.contains(action)).toList());
    private final ObservableList<ActionBindingRow> rowItems=FXCollections.observableArrayList();
    private final FilteredList<ActionBindingRow> filtered=new FilteredList<>(rowItems,r -> true);
    private final TableView<ActionBindingRow> table=new TableView<>(filtered);
    private final TextArea keys=new TextArea();
    private final Label status=new Label(" ");
    private final TextField search=new TextField();
    private final Button record;
    private KeyboardReference reference;
    private boolean loading;
    private Runnable onDraftChanged=() -> {};

    public KeyboardEditor(Workspace workspace) {
        values.putAll(workspace.shortcutOverrides());
        for (String action:actions) {
            String scope=action.equals(KeyBindings.UNBOUND) ? "Terminal"
                    : KeyBindings.command(action).host()==null ? "Fish" : "Terminal";
            rowItems.add(new ActionBindingRow(scope,action,summary(text(action))));
        }

        TableColumn<ActionBindingRow,String> cScope=new TableColumn<>("Scope");
        cScope.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().scope));
        cScope.setPrefWidth(75);

        TableColumn<ActionBindingRow,String> cAction=new TableColumn<>("Action");
        cAction.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().action));
        cAction.setPrefWidth(220);

        TableColumn<ActionBindingRow,String> cKeys=new TableColumn<>("Effective keys");
        cKeys.setCellValueFactory(d -> d.getValue().effective);
        cKeys.setPrefWidth(280);

        table.getColumns().setAll(List.of(cScope,cAction,cKeys));
        table.setFixedCellSize(27);
        table.setAccessibleText("Application-wide Fish and host key actions");

        search.setAccessibleText("Find keyboard action");
        search.textProperty().addListener((o,a,val) -> {
            String query=val==null ? "" : val.strip().toLowerCase(Locale.ROOT);
            filtered.setPredicate(r -> query.isEmpty()
                    || (r.scope+" "+r.action+" "+r.effective.get()).toLowerCase(Locale.ROOT).contains(query));
            if (!query.isEmpty()) workspace.tutorialAction("keyboard-search");
        });

        HBox searchInput=Ui.searchBox(search,"Find action or shortcut...");
        HBox.setHgrow(searchInput,Priority.ALWAYS);
        Label findLbl=new Label("Find action");
        findLbl.setLabelFor(search);
        Button refBtn=Ui.button("Allowed keys and actions...",() -> {
            if (reference==null) reference=new KeyboardReference(getScene()==null ? workspace.stage() : getScene().getWindow(),this::selectAction);
            if (workspace.isShowing()) reference.show();
            workspace.tutorialAction("keyboard-reference");
        });
        HBox topRow=new HBox(8,findLbl,searchInput,refBtn);
        topRow.setAlignment(Pos.CENTER_LEFT);

        VBox topBox=new VBox(6,topRow);
        if (workspace.keyboardRecovery().containsKey("report")) {
            Label conflict=new Label("Migration retained conflicting old definitions. Review them below.");
            Ui.role(conflict,"muted");
            topBox.getChildren().add(conflict);
        }
        setTop(topBox);
        BorderPane.setMargin(topBox,new Insets(0,0,10,0));
        setCenter(table);

        keys.setPrefRowCount(3);
        keys.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px;");
        keys.setAccessibleText("Exact key chords, one per line");
        keys.textProperty().addListener((o,a,val) -> {
            if (loading || selected()==null) return;
            values.put(selected(),val.strip());
            refreshRows();
            describe();
            onDraftChanged.run();
            try {
                KeyBindings.effective(values);
                if (!val.isBlank()) workspace.tutorialAction("keyboard-binding");
            } catch (IllegalArgumentException ignored) {}
        });

        table.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> {
            load();
            if (selected()!=null) workspace.tutorialAction("keyboard-action");
        });

        Label keysLbl=new Label("Bindings (one chord per line; your list replaces the defaults)");
        keysLbl.setLabelFor(keys);

        record=Ui.button("Record and add...",() -> {
            if (selected()!=null) {
                Stage rec=KeyRecorder.dialog(getScene()==null ? workspace.stage() : getScene().getWindow(),chord -> {
                    String existing=keys.getText().strip();
                    keys.setText(existing.isEmpty() ? chord.toString() : existing+"\n"+chord);
                });
                rec.showAndWait();
            }
        });
        Button removeLine=Ui.button("Remove line",() -> {
            if (selected()==null || keys.getText().isEmpty()) return;
            int caret=keys.getCaretPosition();
            String current=keys.getText();
            int lineIdx=(int)current.substring(0,Math.min(caret,current.length())).chars().filter(c -> c=='\n').count();
            List<String> lines=new ArrayList<>(current.lines().toList());
            if (!lines.isEmpty()) {
                lines.remove(Math.min(lineIdx,lines.size()-1));
                keys.setText(String.join("\n",lines));
            }
        });
        Button unbind=Ui.button("Unbind action",() -> { if (selected()!=null) keys.setText(""); });
        Button restore=Ui.button("Restore default",() -> {
            if (selected()==null) return;
            values.remove(selected());
            load();
            refreshRows();
            onDraftChanged.run();
        });
        Button migrated=Ui.button("Migrated definitions...",() ->
                Ui.message(getScene()==null ? workspace.stage() : getScene().getWindow(),
                        KeyboardMigration.report(workspace.keyboardRecovery()),"Keyboard migration"));

        HBox primary=new HBox(6,record,removeLine,unbind);
        HBox secondary=new HBox(6,restore,migrated);

        Label examples=new Label(KeyboardReference.EXAMPLES+"\n"+KeyboardReference.ALTERNATIVES);
        examples.setWrapText(true);
        Ui.role(examples,"muted");
        Label note=new Label("Custom keys replace defaults; duplicate custom owners conflict.");
        status.setAccessibleText("Keyboard validation and default replacements");

        VBox editor=new VBox(6,keysLbl,keys,primary,secondary,examples,note,status);
        setBottom(editor);
        BorderPane.setMargin(editor,new Insets(10,0,0,0));

        refreshRows();
        if (!filtered.isEmpty()) table.getSelectionModel().select(0);
    }

    private static javafx.geometry.Insets newInsets(double t,double r,double b,double l) {
        return new javafx.geometry.Insets(t,r,b,l);
    }

    public void setOnDraftChanged(Runnable listener) { this.onDraftChanged=Objects.requireNonNull(listener); }
    public TableView<ActionBindingRow> actionTable() { return table; }
    public TextArea keysArea() { return keys; }
    public TextField searchField() { return search; }
    public Label statusLabel() { return status; }
    public KeyboardReference openReferenceForTest(Workspace workspace) {
        if (reference==null) reference=new KeyboardReference(workspace.stage(),this::selectAction);
        return reference;
    }

    private void refreshRows() {
        try {
            Map<KeyChord,String> effective=KeyBindings.effective(values);
            for (int n=0;n<actions.size();n++) {
                String action=actions.get(n);
                String actual=action.equals(KeyBindings.UNBOUND) ? values.getOrDefault(action,"")
                        : KeyChord.text(KeyChord.list(text(action)).stream()
                                .filter(key -> action.equals(effective.get(key))).toList());
                rowItems.get(n).effective.set(summary(actual));
            }
        } catch (IllegalArgumentException e) {
            if (selected()!=null) {
                int idx=actions.indexOf(selected());
                if (idx>=0) rowItems.get(idx).effective.set("Invalid draft");
            }
        }
    }

    private String selected() {
        ActionBindingRow sel=table.getSelectionModel().getSelectedItem();
        return sel==null ? null : sel.action;
    }

    public void selectAction(String action) {
        search.setText("");
        for (int i=0;i<filtered.size();i++) {
            if (filtered.get(i).action.equals(action)) {
                table.getSelectionModel().select(i);
                table.scrollTo(i);
                keys.requestFocus();
                return;
            }
        }
        throw new IllegalArgumentException("Unknown keyboard action: "+action);
    }

    private String text(String action) { return values.getOrDefault(action,KeyBindings.defaultText(action)); }

    private static String summary(String text) {
        try { return text.isBlank() ? "Unbound" : AppCommand.describeStroke(text); }
        catch (IllegalArgumentException e) { return text.replace('\n',';'); }
    }

    private void load() {
        loading=true;
        try {
            keys.setText(selected()==null ? "" : text(selected()));
            keys.setDisable(selected()==null);
        } finally { loading=false; }
        describe();
    }

    private void describe() {
        try {
            KeyBindings.effective(values);
            List<String> claims=KeyBindings.defaultClaims(values);
            status.setText(claims.isEmpty() ? " " : "Default replacements: "+claims.size()+" (hover for details)");
            status.setTooltip(claims.isEmpty() ? null : new Tooltip(String.join("; ",claims)));
            Ui.role(status,"muted");
            Ui.markInvalid(keys,false);
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
            status.setTooltip(new Tooltip(e.getMessage()));
            Ui.role(status,"error");
            Ui.markInvalid(keys,true);
        }
    }

    public Map<String,String> draft() { KeyBindings.effective(values); return KeyBindings.normalize(values); }

    public void reload(Map<String,String> saved) {
        values.clear(); values.putAll(saved); load(); refreshRows(); describe();
    }

    public Node tutorialTarget(String control) {
        return switch (control) {
            case "search" -> search;
            case "keys" -> keys;
            case "record" -> record;
            default -> table;
        };
    }

    public boolean dirty(Map<String,String> saved) {
        try { return !draft().equals(saved); }
        catch (IllegalArgumentException e) { return true; }
    }
}
