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
    private final CheckBox currentAutostart=new CheckBox("Start automatically when Windows signs in");
    private final CheckBox existingAutostart=new CheckBox("Autostart when Windows signs in");
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
        heading.getStyleClass().add("fish-section-title");
        Ui.role(context,"muted");

        name.setText(workspace.sessionPanes().size()==1 ? workspace.active().profile().name() : "Work layout");
        name.setAccessibleText("New shortcut layout name");
        name.textProperty().addListener((o,a,val) -> {
            if (!refreshing && val!=null && !val.isBlank()) workspace.tutorialAction("layout-named");
        });
        Label nameLabel=new Label("Name");
        nameLabel.setLabelFor(name);
        currentAutostart.setTooltip(new Tooltip("Launch this saved layout automatically when you sign in to Windows."));
        VBox naming=new VBox(6,nameLabel,name,currentAutostart);

        create=Ui.button("Create desktop shortcut...",() -> chooseShortcut(true));
        create.setDefaultButton(true);

        save=Ui.button("Save layout only",() -> act(() -> {
            workspace.saveCurrentLayout(null,name.getText(),currentAutostart.isSelected());
            refresh();
            showLibrary();
            working=true; refresh(); showMessage("Saving layout...",false);
            workspace.persistSettings(() -> {
                working=false; refresh(); showMessage("Layout saved. Open it or create its shortcut below.",false);
            },error -> {
                working=false; refresh(); showMessage("Not saved: "+error+" Layout kept here for retry.",true);
            });
        }));

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
            fitHeight();
        });

        layouts.setMaxWidth(Double.MAX_VALUE);
        existingAutostart.setTooltip(new Tooltip("Add or remove this layout from your Windows Startup folder. Multiple layouts can autostart independently."));
        existingAutostart.setOnAction(e -> {
            if (refreshing) return;
            act(() -> {
                SavedLayout value=selected();
                boolean enabled=existingAutostart.isSelected();
                workspace.setSavedLayoutAutostart(value.id(),enabled);
                refresh();
                showMessage(enabled
                        ? "Autostart enabled for \""+value.name()+"\"."
                        : "Autostart disabled for \""+value.name()+"\".",false);
            });
        });
        VBox fields=new VBox(8,row("Saved layout",layouts),row("Rename to",existingName),existingAutostart);

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
                refreshing=true;
                try {
                    existingName.setText(newV.name());
                    existingAutostart.setSelected(newV.autostart());
                } finally { refreshing=false; }
            }
        });

        form.getChildren().setAll(current,existing);
        form.setPadding(new Insets(12,16,4,16));
        scroll.setContent(form);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportWidth(460);
        BorderPane body=new BorderPane(scroll);
        footer.setPadding(new Insets(10,16,12,16));
        body.setBottom(footer);
        Ui.decorateDialog(stage,body,this::dispose,500,-1);
        stage.setMinWidth(420);
        stage.setMinHeight(300);
        refresh();
        fitHeight();
    }

    public Stage stage() { return stage; }
    public boolean isShowing() { return stage.isShowing(); }
    public boolean isOpen() { return !disposed; }
    public void show() { stage.show(); stage.toFront(); }
    public void dispose() { disposed=true; workspace.layoutsDialogClosed(this); stage.close(); }
    public boolean isWorking() { return working; }
    public TextField nameField() { return name; }
    public TextField existingNameField() { return existingName; }
    public CheckBox currentAutostartCheck() { return currentAutostart; }
    public CheckBox existingAutostartCheck() { return existingAutostart; }
    public ComboBox<SavedLayout> layoutsCombo() { return layouts; }
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
        fitHeight();
    }

    private final VBox form=new VBox(16);
    private final ScrollPane scroll=new ScrollPane();

    private void fitHeight() {
        if (stage.isMaximized()) return;
        Region root=(Region)stage.getScene().getRoot();
        root.applyCss();
        double width=stage.getWidth()>0 ? stage.getWidth() : 500;
        // Measure the form *after* the disclosure changed its managed children.
        // Listening to selectedProperty instead reads the previous geometry.
        double clientWidth=stage.isShowing() ? stage.getScene().getWidth() : width;
        scroll.setPrefViewportHeight(Math.ceil(form.prefHeight(Math.max(320,clientWidth-18))));
        var owner=stage.isShowing() ? stage : workspace.stage();
        var screens=javafx.stage.Screen.getScreensForRectangle(owner.getX(),owner.getY(),owner.getWidth(),owner.getHeight());
        var bounds=(screens.isEmpty() ? javafx.stage.Screen.getPrimary() : screens.getFirst()).getVisualBounds();
        stage.setWidth(width);
        double frame=stage.isShowing() ? Math.max(0,stage.getHeight()-stage.getScene().getHeight()) : 10;
        stage.setHeight(Math.min(bounds.getHeight()-12,Math.max(stage.getMinHeight(),Math.ceil(root.prefHeight(clientWidth)+frame))));
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
            existingAutostart.setSelected(value!=null && value.autostart());
            for (Button button:new Button[]{replace,rename,delete,open,export}) button.setDisable(value==null || working);
            layouts.setDisable(value==null || working);
            existingName.setDisable(value==null || working);
            existingAutostart.setDisable(value==null || working);
            create.setDisable(working);
            save.setDisable(working);
            name.setDisable(working);
            currentAutostart.setDisable(working);
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
                if (!Boolean.getBoolean("fish.testMode") || Boolean.getBoolean("fish.test.remapDesktop"))
                    output=LayoutShortcuts.remapInactiveDesktop(output);
                if (Files.exists(output))
                    throw new IllegalArgumentException("That file already exists. Choose a new shortcut filename.");
                if (current) value=workspace.saveCurrentLayout(null,title,currentAutostart.isSelected());
                working=true; refresh(); showMessage("Saving layout and creating shortcut...",false);
                shortcutExport.write(Objects.requireNonNull(value).id(),output,() -> {
                    working=false; refresh();
                    String iconHint=LayoutShortcuts.areDesktopIconsHidden()
                            ? " Note: Windows \"Show desktop icons\" is off (right-click Desktop > View > Show desktop icons)."
                            : "";
                    showMessage("Shortcut created. Opens a new Fish window."+iconHint,false);
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
