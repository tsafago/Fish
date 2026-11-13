package fish.ui;

import java.io.IOException;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import fish.model.StateStore;

/** Startup-only recovery, after the launch owner has acquired the OS workspace lock. */
public final class WorkspaceRecovery {
    public enum Choice { EXIT, RETRY, FACTORY }
    public interface Decisions {
        Choice choose(String error,boolean retry);
        boolean confirm();
        void failed(String error);
    }
    private WorkspaceRecovery() {}

    public static StateStore.State load(StateStore store) throws Exception {
        if (Ui.isFxStarted() && Platform.isFxApplicationThread())
            throw new IllegalStateException("Workspace startup I/O must run off the UI thread.");
        return load(store,new Decisions() {
            @Override public Choice choose(String error,boolean retry) { return onFx(() -> chooseDialog(error,retry)); }
            @Override public boolean confirm() {
                return onFx(() -> Ui.confirm(null,"Restore factory settings for this workspace?\n"
                        +"The exact original workspace.xml is backed up beside it before replacement. Profiles, layouts and shortcuts reset; "
                        +"saved Windows passwords, font files and other files remain untouched. A newer workspace may need a matching Fish release instead.",
                        "Confirm factory recovery"));
            }
            @Override public void failed(String error) { onFx(() -> { Ui.error(null,error); return null; }); }
        });
    }

    public static StateStore.State load(StateStore store,Decisions decisions) throws IOException {
        IOException problem;
        try { return store.load(); } catch (IOException error) { problem=error; }
        Choice choice=decisions.choose(problem.getMessage(),true);
        if (choice==Choice.RETRY) {
            try { return store.load(); } catch (IOException error) { problem=error; }
            choice=decisions.choose(problem.getMessage(),false);
        }
        if (choice!=Choice.FACTORY || !decisions.confirm()) return null;
        try {
            Path backup=store.recoverFactory();
            System.getLogger(WorkspaceRecovery.class.getName()).log(System.Logger.Level.INFO,"Original workspace preserved at "+backup);
            return store.load();
        } catch (IOException error) {
            decisions.failed("Factory recovery failed: "+error.getMessage()
                    +"\nNothing is reported restored. Keep the original workspace and any recovery backup; exit and fix permissions or use a matching Fish version.");
            return null;
        }
    }

    public static Stage dialog(String error,boolean retry,java.util.function.Consumer<Choice> selected) {
        Stage stage=Ui.dialogStage(null,"Workspace could not be opened",true);
        stage.initModality(Modality.APPLICATION_MODAL);
        TextArea message=new TextArea("Fish cannot read this workspace:\n"+error
                +"\n\nExit keeps everything unchanged. If the file belongs to a newer release, upgrade Fish or use a matching backup. "
                +"Factory recovery requires confirmation and preserves the original file.");
        message.setWrapText(true);
        message.setEditable(false);
        message.setFocusTraversable(false);
        message.setPrefRowCount(6);
        message.setPrefColumnCount(54);

        HBox actions=new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        for (Choice choice:Choice.values()) {
            Button button=Ui.button(switch (choice) {
                case EXIT -> "Exit";
                case RETRY -> "Retry load";
                case FACTORY -> "Restore factory settings...";
            },() -> { selected.accept(choice); stage.close(); });
            if (choice==Choice.RETRY) button.setDisable(!retry);
            if (choice==Choice.EXIT) button.setDefaultButton(true);
            actions.getChildren().add(button);
        }
        BorderPane body=new BorderPane();
        body.setPadding(new Insets(18));
        body.setCenter(message);
        body.setBottom(actions);
        BorderPane.setMargin(actions,new Insets(18,0,0,0));
        Ui.decorateDialog(stage,body,() -> { selected.accept(Choice.EXIT); stage.close(); },580,290);
        return stage;
    }

    private static Choice chooseDialog(String error,boolean retry) {
        Choice[] choice={Choice.EXIT};
        dialog(error,retry,value -> choice[0]=value).showAndWait();
        return choice[0];
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> action) {
        try { return Ui.callAndWait(action); }
        catch (Exception e) { throw new IllegalStateException("Workspace recovery dialog failed.",e); }
    }
}
