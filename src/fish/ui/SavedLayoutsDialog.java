package fish.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import fish.LayoutShortcuts;
import fish.model.SavedLayout;

/** Current-window shortcut creation first; existing recipes remain explicit, stable references. */
public final class SavedLayoutsDialog {
    public interface ShortcutExport {
        Path desktop() throws IOException;
        Path choose(Path initialDir,String initialFileName,Window owner);
        void write(UUID id,Path output,Runnable done,Consumer<String> failed);
    }

    private final Workspace workspace;
    private final Stage stage;
    private final ShortcutExport shortcutExport;
    private final TextField name=new TextField(),existingName=new TextField();
    private final ComboBox<SavedLayout> layouts=new ComboBox<>();
    private final Label context=new Label(),message=new Label(" ");
    private final Button create,save,replace,rename,delete,open,export;
    private final ToggleButton manage=new ToggleButton("Saved layouts");
    private final VBox library=new VBox(12);
    private boolean refreshing,working,disposed;

    public SavedLayoutsDialog(Workspace workspace) {
        this(workspace,new ShortcutExport() {
            @Override public Path desktop() throws IOException { return LayoutShortcuts.desktop(); }
            @Override public Path choose(Path initialDir,String initialFileName,Window owner) {
                FileChooser chooser=new FileChooser();
                chooser.setTitle("Create layout shortcut");
                if (initialDir!=null && Files.isDirectory(initialDir)) chooser.setInitialDirectory(initialDir.toFile());
                chooser.setInitialFileName(initialFileName);
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Windows shortcut (*.lnk)","*.lnk"));
                File selected=chooser.showSaveDialog(owner);
                return selected==null ? null : selected.toPath();
            }
            @Override public void write(UUID id,Path output,Runnable done,Consumer<String> failed) {
                workspace.exportSavedLayout(id,output,done,failed);
            }
        });
    }

    public SavedLayoutsDialog(Workspace workspace,ShortcutExport shortcutExport) {
        this.workspace=workspace;
        this.shortcutExport=shortcutExport;
        this.stage=Ui.dialogStage(workspace.stage(),"Saved layouts / shortcuts",false);

        Label heading=new Label("Shortcut for this window");
        heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        context.setStyle("-fx-font-size: 13px;");
        Ui.role(context,"muted");

        name.setText(workspace.sessionPanes().size()==1 ? workspace.active().profile().name() : "Work layout");
        name.setAccessibleText("New shortcut layout name");
        name.setStyle("-fx-font-size: 13.5px;");
        name.textProperty().addListener((o,a,val) -> {
            if (!refreshing && val!=null && !val.isBlank()) workspace.tutorialAction("layout-named");
        });
        Label nameLabel=new Label("Name");
        nameLabel.setLabelFor(name);
        nameLabel.setStyle("-fx-font-size: 13px;");
        VBox naming=new VBox(4,nameLabel,name);

        create=Ui.button("Create desktop shortcut...",() -> chooseShortcut(true));
        create.setDefaultButton(true);
        create.setStyle("-fx-font-size: 13px; -fx-padding: 6 14 6 14;");

        save=Ui.button("Save layout only",() -> act(() -> {
            workspace.saveCurrentLayout(null,name.getText());
            refresh();
            showLibrary();
            working=true; refresh(); showMessage("Saving layout...",false);
            workspace.persistSettings(() -> {
                working=false; refresh(); showMessage("Layout saved. Open it or create its shortcut below.",false);
            },error -> {
                working=false; refresh(); showMessage("Not saved: "+error+" Layout kept here for retry.",true);
            });
        }));
        save.setStyle("-fx-font-size: 13px; -fx-padding: 6 14 6 14;");

        HBox primary=new HBox(8,create,save);
        primary.setAlignment(Pos.CENTER_LEFT);

        VBox current=new VBox(12,new VBox(4,heading,context),naming,primary);

        manage.setMaxWidth(Double.MAX_VALUE);
        manage.setAlignment(Pos.CENTER_LEFT);
        manage.setGraphic(Ui.icon("right"));
        manage.setGraphicTextGap(8);
        manage.setOnAction(e -> {
            boolean show=manage.isSelected();
            library.setVisible(show);
            library.setManaged(show);
            manage.setGraphic(Ui.icon(show ? "down" : "right"));
            stage.sizeToScene();
        });

        layouts.setMaxWidth(Double.MAX_VALUE);
        VBox fields=new VBox(8,row("Saved layout",layouts),row("Rename to",existingName));

        open=Ui.button("Open new window",() -> act(() -> {
            if (!workspace.openSavedLayout(selected().id()))
                throw new IllegalArgumentException("Fish is saving or closing; try again shortly.");
            showMessage("Opened an independent window.",false);
        }));
        export=Ui.button("Create shortcut...",() -> chooseShortcut(false));
        replace=Ui.button("Update from this window",() -> act(() -> {
            SavedLayout value=selected();
            if (Ui.confirm(stage,"Replace \""+value.name()+"\" with this window's current panes?\nExisting shortcuts will use the new arrangement.","Update saved layout")) {
                workspace.saveCurrentLayout(value.id(),value.name());
                refresh();
                showMessage("Updated; existing shortcuts keep working.",false);
            }
        }));
        rename=Ui.button("Rename",() -> act(() -> {
            workspace.renameSavedLayout(selected().id(),existingName.getText());
            refresh();
            showMessage("Renamed; existing shortcuts keep working.",false);
        }));
        delete=Ui.button("Delete",() -> act(() -> {
            SavedLayout value=selected();
            if (Ui.confirm(stage,"Delete \""+value.name()+"\"?\nExported shortcuts are kept, but will report this layout as unavailable.","Delete saved layout")) {
                workspace.deleteSavedLayout(value.id());
                refresh();
                showMessage("Deleted; no exported shortcut files were removed.",false);
            }
        }));

        FlowPane actions=new FlowPane(8,8,open,export,replace,rename,delete);
        library.getChildren().addAll(fields,actions);
        library.setVisible(false);
        library.setManaged(false);

        VBox existing=new VBox(10,manage,library);

        Ui.role(message,"muted");
        message.setAccessibleText("Shortcut creation status");
        Button closeBtn=Ui.button("Close",this::dispose);
        BorderPane footer=new BorderPane();
        footer.setPadding(new Insets(10,0,0,0));
        footer.setCenter(message);
        BorderPane.setAlignment(message,Pos.CENTER_LEFT);
        footer.setRight(closeBtn);

        layouts.valueProperty().addListener((obs,oldV,newV) -> {
            if (!refreshing && newV!=null) {
                workspace.selectSavedLayout(newV.id());
                existingName.setText(newV.name());
            }
        });

        VBox body=new VBox(18,current,existing,footer);
        body.setPadding(new Insets(20));

        Ui.decorateDialog(stage,body,this::dispose,760,-1);
        stage.setMinWidth(680);
        refresh();
        stage.sizeToScene();
    }

    public Stage stage() { return stage; }
    public boolean isShowing() { return stage.isShowing(); }
    public boolean isOpen() { return !disposed; }
    public void show() { stage.show(); stage.toFront(); }
    public void dispose() { disposed=true; workspace.layoutsDialogClosed(this); stage.close(); }
    public boolean isWorking() { return working; }
    public TextField nameField() { return name; }
    public TextField existingNameField() { return existingName; }
    public Label contextLabel() { return context; }
    public Label messageLabel() { return message; }
    public Button createButton() { return create; }
    public Button saveButton() { return save; }
    public ToggleButton manageButton() { return manage; }
    public VBox libraryBox() { return library; }

    private static HBox row(String text,Control control) {
        Label label=new Label(text);
        label.setLabelFor(control);
        label.setMinWidth(95);
        label.setPrefWidth(95);
        HBox.setHgrow(control,Priority.ALWAYS);
        HBox box=new HBox(12,label,control);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void showLibrary() {
        manage.setSelected(true);
        library.setVisible(true);
        library.setManaged(true);
        manage.setGraphic(Ui.icon("down"));
        stage.sizeToScene();
    }

    private SavedLayout selected() {
        if (layouts.getValue()==null) throw new IllegalArgumentException("Save or select a layout first.");
        return layouts.getValue();
    }

    public void refresh() {
        SavedLayout old=layouts.getValue();
        boolean edited=old!=null && !existingName.getText().equals(old.name());
        refreshing=true;
        try {
            int panes=workspace.sessionPanes().size();
            context.setText(panes==1 ? "One profile: "+workspace.active().profile().name() : panes+" panes with their current profiles and arrangement");
            context.setTooltip(new Tooltip("Captures profiles, arrangement, proportions and fit/zoom; never terminal contents or passwords."));
            layouts.setItems(FXCollections.observableArrayList(workspace.savedLayouts()));
            SavedLayout value=workspace.savedLayouts().stream().filter(s -> Objects.equals(s.id(),workspace.selectedLayout()))
                    .findFirst().orElse(workspace.savedLayouts().isEmpty() ? null : workspace.savedLayouts().getFirst());
            layouts.setValue(value);
            if (!edited) existingName.setText(value==null ? "" : value.name());
            for (Button button:new Button[]{replace,rename,delete,open,export}) button.setDisable(value==null || working);
            layouts.setDisable(value==null || working);
            existingName.setDisable(value==null || working);
            create.setDisable(working);
            save.setDisable(working);
            name.setDisable(working);
            manage.setText("Saved layouts ("+workspace.savedLayouts().size()+")");
        } finally { refreshing=false; }
    }

    private void showMessage(String value,boolean err) {
        message.setText(value);
        message.setTooltip(new Tooltip(value));
        Ui.role(message,err ? "error" : "muted");
    }

    private void act(Runnable action) {
        if (working) return;
        try { action.run(); }
        catch (IllegalArgumentException e) { showMessage(e.getMessage(),true); }
    }

    private void chooseShortcut(boolean current) {
        act(() -> {
            SavedLayout value=current ? null : selected();
            String title=current ? name.getText().strip() : value.name();
            if (current && (title.isEmpty() || title.length()>80 || title.chars().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("Name must be 1-80 printable characters.");
            if (current && workspace.savedLayouts().stream().anyMatch(s -> s.name().equalsIgnoreCase(title)))
                throw new IllegalArgumentException("That name is already saved. Use Saved layouts below, or enter a new name.");
            if (current && workspace.savedLayouts().size()>=SavedLayout.MAX_SAVED)
                throw new IllegalArgumentException("Keep at most 64 saved layouts. Update or remove one in Saved layouts below.");
            try {
                String safe=title.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]","_").replaceAll("[. ]+$","");
                if (safe.isBlank()) safe="Fish layout";
                workspace.tutorialAction("layout-destination-open");
                Path output=shortcutExport.choose(shortcutExport.desktop(),"Fish - "+safe+".lnk",stage);
                if (output==null) return;
                if (!output.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".lnk"))
                    output=output.resolveSibling(output.getFileName()+".lnk");
                if (Files.exists(output))
                    throw new IllegalArgumentException("That file already exists. Choose a new shortcut filename.");
                if (current) value=workspace.saveCurrentLayout(null,title);
                working=true; refresh(); showMessage("Saving layout and creating shortcut...",false);
                shortcutExport.write(Objects.requireNonNull(value).id(),output,() -> {
                    working=false; refresh(); showMessage("Shortcut created. Opens a new Fish window.",false);
                    workspace.tutorialAction("layout-shortcut");
                },error -> {
                    working=false; refresh(); showMessage("Layout kept; shortcut not created: "+error,true);
                });
            } catch (IOException e) {
                showMessage(e.getMessage(),true);
            } finally {
                workspace.tutorialChanged();
            }
        });
    }

    public Node tutorialTarget(String control) {
        return switch (control) {
            case "name" -> name;
            case "save" -> save;
            case "export","destination" -> create;
            case "message" -> message;
            default -> manage;
        };
    }
}
