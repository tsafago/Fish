package fish.ui;

import javafx.scene.Node;
import javafx.stage.Stage;
import fish.model.Profile;

/** The operational profile workflow uses the same editor as Settings. */
public final class ProfilesDialog {
    public final ProfilesEditor editor;
    private final Workspace workspace;
    private final Stage stage;

    public ProfilesDialog(Workspace workspace,Profile initial) {
        this.workspace=workspace;
        this.stage=Ui.dialogStage(workspace.stage(),"Workstation profiles",true);
        this.editor=new ProfilesEditor(workspace,initial,false,this::dispose);
        Ui.decorateDialog(stage,editor,editor::dismiss,850,640);
        stage.setMinWidth(740);
        stage.setMinHeight(540);
        workspace.profileEditorOpened(editor);
        stage.setOnShown(e -> editor.focusName());
        stage.setOnHidden(e -> {
            editor.discard();
            workspace.profileEditorClosed(editor);
        });
    }

    public Stage stage() { return stage; }
    public void setVisible(boolean visible) {
        if (visible) stage.showAndWait();
        else dispose();
    }
    public Node tutorialTarget(int step) { return editor.tutorialTarget(step); }
    public String tutorialSignInText() { return editor.tutorialSignInText(); }
    public String tutorialConnectionText() { return editor.tutorialConnectionText(); }
    public boolean kerberos() { return editor.kerberos(); }
    public void dispose() {
        if (editor!=null) {
            if (editor.saving()) return;
            editor.discard();
            workspace.profileEditorClosed(editor);
        }
        stage.close();
    }
}
