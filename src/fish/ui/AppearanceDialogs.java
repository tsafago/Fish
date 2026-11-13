package fish.ui;

import javafx.scene.control.CheckBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Existing quick actions share Preferences, rather than maintaining separate drafts. */
public final class AppearanceDialogs {
    private AppearanceDialogs() {}
    public static void fonts(Workspace workspace) { workspace.preferences("Terminal"); }
    public static Stage fontsDialog(Workspace workspace) { return workspace.preferencesDialog("Terminal"); }
    public static void paneInfo(Workspace workspace) { workspace.preferences("Panes"); }
    public static Stage paneInfoDialog(Workspace workspace) { return workspace.preferencesDialog("Panes"); }
    public static void windowFocus(Workspace workspace) { workspace.preferences("Panes"); }
    public static Stage windowFocusDialog(Workspace workspace) { return workspace.preferencesDialog("Panes"); }
    public static VBox windowFocusPanel(Workspace workspace) {
        VBox panel=new VBox(6);
        panel.getChildren().addAll(
                new CheckBox("Show whole-window outline"),
                new CheckBox("Outline active pane"),
                new CheckBox("Tint active pane background"));
        ((CheckBox)panel.getChildren().get(0)).setSelected(workspace.appearanceSettings().windowOutline());
        ((CheckBox)panel.getChildren().get(1)).setSelected(workspace.appearanceSettings().outline());
        ((CheckBox)panel.getChildren().get(2)).setSelected(workspace.highlightActive());
        return panel;
    }
}
