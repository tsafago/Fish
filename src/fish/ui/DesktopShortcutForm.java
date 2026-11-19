package fish.ui;

import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import fish.LayoutShortcuts;

/** An explicit export: editing these fields never saves a recipe. */
final class DesktopShortcutForm extends VBox {
    final TextField name=new TextField();
    final CheckBox autostart=new CheckBox("Open this shortcut when I sign in to Windows");
    final Button create;
    final Label status=new Label();

    DesktopShortcutForm(Workspace workspace) {
        super(8);
        Label label=new Label("Shortcut name");
        label.setLabelFor(name);
        name.setText(workspace.sessionPanes().size()==1 ? workspace.active().profile().name() : "Work panes");
        name.setAccessibleText(label.getText());
        name.textProperty().addListener((o,old,value) -> {
            if (!value.isBlank()) workspace.tutorialAction("shortcut-named");
        });
        create=Ui.button("Create desktop shortcut",() -> {
            setDisable(true);
            message("Creating shortcut…",false);
            workspace.exportDesktopShortcut(name.getText(),autostart.isSelected(),file -> {
                setDisable(false);
                message("Created "+file.getFileName()+" on your Desktop."
                        +(LayoutShortcuts.areDesktopIconsHidden() ? " To see it, turn on Show desktop icons in Windows." : ""),false);
                workspace.tutorialAction("shortcut-created");
            },error -> {
                setDisable(false); message("Shortcut not created: "+error,true);
            });
        });
        status.setWrapText(true);
        status.setVisible(false); status.managedProperty().bind(status.visibleProperty());
        getChildren().addAll(label,name,autostart,create,status);
    }

    private void message(String text,boolean failed) {
        Ui.role(status,failed ? "error" : "muted");
        status.setText(text); status.setVisible(true);
    }
}
