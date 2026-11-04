package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import pane5250.model.Profile;

/** The operational profile workflow uses the same editor as Settings. */
public final class ProfilesDialog extends JDialog {
    final ProfilesEditor editor;
    private final Workspace workspace;
    public ProfilesDialog(Workspace workspace,Profile initial) {
        super(workspace,"Workstation profiles",true);
        this.workspace=workspace;
        editor=new ProfilesEditor(workspace,initial,false,this::dispose);
        setContentPane(editor);
        Ui.decorate(this,editor::dismiss);
        getRootPane().setDefaultButton(editor.doneButton());
        getRootPane().registerKeyboardAction(e -> editor.dismiss(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setSize(850,650); setMinimumSize(new Dimension(760,550)); setLocationRelativeTo(workspace);
        workspace.profileEditorOpened(editor);
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { editor.focusName(); }
            @Override public void windowClosed(WindowEvent e) { editor.discard(); workspace.profileEditorClosed(editor); }
        });
    }
    Component tutorialTarget(int step) { return editor.tutorialTarget(step); }
    String tutorialSignInText() { return editor.tutorialSignInText(); }
    String tutorialConnectionText() { return editor.tutorialConnectionText(); }
    boolean kerberos() { return editor.kerberos(); }
    @Override public void dispose() {
        if (editor!=null) {
            if (editor.saving()) return;
            editor.discard(); workspace.profileEditorClosed(editor);
        }
        super.dispose();
    }
}
