package pane5250.ui;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.*;
import javax.swing.*;
import pane5250.model.StateStore;

/** Startup-only recovery, after the launch owner has acquired the OS workspace lock. */
public final class WorkspaceRecovery {
    enum Choice { EXIT, RETRY, FACTORY }
    interface Decisions {
        Choice choose(String error,boolean retry);
        boolean confirm();
        void failed(String error);
    }
    private WorkspaceRecovery() {}
    public static StateStore.State load(StateStore store) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Workspace startup I/O must run off the EDT.");
        return load(store,new Decisions() {
            @Override public Choice choose(String error,boolean retry) { return onEdt(() -> chooseDialog(error,retry)); }
            @Override public boolean confirm() {
                return onEdt(() -> Ui.confirm(null,"Restore factory settings for this workspace?\n"
                        +"The exact original workspace.xml is backed up beside it before replacement. Profiles, layouts and shortcuts reset; "
                        +"saved Windows passwords, font files and other files remain untouched. A newer workspace may need a matching Fish release instead.",
                        "Confirm factory recovery"));
            }
            @Override public void failed(String error) { onEdt(() -> { Ui.error(null,error); return null; }); }
        });
    }
    static StateStore.State load(StateStore store,Decisions decisions) throws IOException {
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
    static JDialog dialog(String error,boolean retry,java.util.function.Consumer<Choice> selected) {
        JDialog dialog=new JDialog((Frame)null,"Workspace could not be opened",true);
        JTextArea message=new JTextArea("Fish cannot read this workspace:\n"+error
                +"\n\nExit keeps everything unchanged. If the file belongs to a newer release, upgrade Fish or use a matching backup. "
                +"Factory recovery requires confirmation and preserves the original file.",6,58);
        message.setLineWrap(true); message.setWrapStyleWord(true); message.setEditable(false); message.setOpaque(false);
        JPanel body=new JPanel(new BorderLayout(0,18)); body.setBorder(Ui.padding(18)); body.add(message,BorderLayout.CENTER);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));
        for (Choice choice:Choice.values()) {
            JButton button=Ui.button(switch(choice) { case EXIT -> "Exit"; case RETRY -> "Retry load"; case FACTORY -> "Restore factory settings..."; },0,
                    () -> { selected.accept(choice); dialog.dispose(); });
            if (choice==Choice.RETRY) button.setEnabled(retry);
            actions.add(button); if (choice==Choice.EXIT) dialog.getRootPane().setDefaultButton(button);
        }
        body.add(actions,BorderLayout.SOUTH); dialog.setContentPane(body);
        Ui.decorate(dialog,() -> { selected.accept(Choice.EXIT); dialog.dispose(); }); dialog.pack(); dialog.setLocationRelativeTo(null);
        return dialog;
    }
    private static Choice chooseDialog(String error,boolean retry) {
        Choice[] choice={Choice.EXIT};
        dialog(error,retry,value -> choice[0]=value).setVisible(true);
        return choice[0];
    }
    private static <T> T onEdt(Callable<T> action) {
        FutureTask<T> result=new FutureTask<>(action);
        try { SwingUtilities.invokeAndWait(result); return result.get(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Workspace recovery interrupted.",e); }
        catch (ExecutionException | java.lang.reflect.InvocationTargetException e) { throw new IllegalStateException("Workspace recovery dialog failed.",e.getCause()); }
    }
}
