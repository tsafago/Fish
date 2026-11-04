package pane5250.ui;

import javax.swing.*;

/** Existing quick actions share Preferences, rather than maintaining separate drafts. */
final class AppearanceDialogs {
    private AppearanceDialogs() {}
    static void fonts(Workspace workspace) { workspace.preferences("Terminal"); }
    static JDialog fontsDialog(Workspace workspace) { return workspace.preferencesDialog("Terminal"); }
    static void paneInfo(Workspace workspace) { workspace.preferences("Panes"); }
    static JDialog paneInfoDialog(Workspace workspace) { return workspace.preferencesDialog("Panes"); }
    static void windowFocus(Workspace workspace) { workspace.preferences("Panes"); }
    static JDialog windowFocusDialog(Workspace workspace) { return workspace.preferencesDialog("Panes"); }
    static JPanel windowFocusPanel(Workspace workspace) {
        JPanel panel=new JPanel();
        panel.add(new JCheckBox("Show whole-window outline",workspace.appearanceSettings().windowOutline()));
        panel.add(new JCheckBox("Outline active pane",workspace.appearanceSettings().outline()));
        panel.add(new JCheckBox("Tint active pane background",workspace.highlightActive()));
        return panel;
    }
}
