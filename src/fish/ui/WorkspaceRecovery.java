package fish.ui;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import fish.LayoutShortcuts;
import fish.model.StateStore;
import fish.security.KioskMode;

/** Startup-only recovery, after the launch owner has acquired the OS workspace lock, plus startup-error factory reset and Kiosk guard dialogs. */
public final class WorkspaceRecovery {
    public enum Choice { EXIT, RETRY, FACTORY, DELETE_CONFIG }
    public interface Decisions {
        Choice choose(String error,boolean retry);
        boolean confirm();
        default boolean confirmDeleteConfig() { return confirm(); }
        default boolean authorizeAdmin() {
            try { return KioskMode.authorizeAdminAction(); }
            catch (IOException e) { return false; }
        }
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
            @Override public boolean confirmDeleteConfig() {
                return onFx(() -> Ui.confirm(null,"Factory reset and delete saved Fish configuration in "+store.directory()+"?\n"
                        +"Deletes workspace.xml, recovery backups, Startup layout shortcuts, and stored Fish credentials, then starts clean.",
                        "Confirm factory reset (delete config)"));
            }
            @Override public boolean authorizeAdmin() {
                return onFx(() -> {
                    try { return KioskMode.authorizeAdminAction(); }
                    catch (IOException e) { Ui.error(null,e.getMessage()); return false; }
                });
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
        if (choice==Choice.DELETE_CONFIG) {
            if (KioskMode.isEnabled() && !decisions.authorizeAdmin()) {
                decisions.failed("Administrator permission is required to factory reset Fish while Kiosk mode is enabled.");
                return null;
            }
            if (!decisions.confirmDeleteConfig()) return null;
            try { return deleteConfigAndReset(store); }
            catch (IOException error) {
                decisions.failed("Factory reset (delete config) failed: "+error.getMessage());
                return null;
            }
        }
        if (choice!=Choice.FACTORY) return null;
        if (KioskMode.isEnabled() && !decisions.authorizeAdmin()) {
            decisions.failed("Administrator permission is required to reset Fish while Kiosk mode is enabled.");
            return null;
        }
        if (!decisions.confirm()) return null;
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

    /** Deletes workspace config, recovery backups, Startup layout shortcuts, and stored credentials while holding store lock, then writes fresh state. */
    public static StateStore.State deleteConfigAndReset(StateStore store) throws IOException {
        deleteRecoveryBackups(store.directory());
        LayoutShortcuts.removeAutostartShortcuts(store.directory());
        KioskMode.deleteAllStoredCredentials();
        return store.deleteAndReinitialize();
    }

    /** Wipes Fish configuration in directory (workspace.xml, backups, Startup shortcuts, and stored credentials) after closing locks, then saves factory defaults. */
    public static StateStore.State factoryResetDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        deleteRecoveryBackups(directory);
        Files.deleteIfExists(directory.resolve("workspace.xml.tmp"));
        Files.deleteIfExists(directory.resolve("workspace.xml"));
        LayoutShortcuts.removeAutostartShortcuts(directory);
        KioskMode.deleteAllStoredCredentials();
        try (StateStore clean=new StateStore(directory)) {
            StateStore.State fresh=StateStore.freshState();
            clean.save(fresh);
            return fresh;
        }
    }

    private static void deleteRecoveryBackups(Path directory) throws IOException {
        if (directory==null || !Files.isDirectory(directory)) return;
        try (var stream=Files.list(directory)) {
            for (Path entry:stream.toList()) {
                String name=entry.getFileName().toString();
                if (name.startsWith("workspace-recovery-") && name.endsWith(".xml")) Files.deleteIfExists(entry);
            }
        }
    }

    /** Programmatic testable startup-error recovery handler supporting Factory reset (delete config) with Kiosk admin gate. */
    public static boolean recoverStartupError(Path directory,AutoCloseable openBroker,AutoCloseable openStore,
                                              boolean resetChosen,boolean confirmed,BooleanSupplier adminAuthorizer,
                                              Consumer<String> onError) {
        if (!resetChosen) return false;
        if (KioskMode.isEnabled() && !adminAuthorizer.getAsBoolean()) {
            onError.accept("Administrator permission is required to factory reset Fish while Kiosk mode is enabled.");
            return false;
        }
        if (!confirmed) return false;
        closeQuietly(openBroker);
        closeQuietly(openStore);
        try {
            factoryResetDirectory(directory);
            return true;
        } catch (IOException ex) {
            onError.accept("Factory reset failed: "+ex.getMessage());
            return false;
        }
    }

    /** Interactive modal startup-error dialog offering Factory reset (delete config)... on any Fish start failure. */
    public static boolean offerStartupFactoryReset(Path directory,AutoCloseable openBroker,AutoCloseable openStore,String error) {
        try {
            Ui.install();
            return onFx(() -> {
                AtomicReference<Boolean> resetPicked=new AtomicReference<>(false);
                startupErrorDialog(error,resetPicked::set).showAndWait();
                if (!resetPicked.get()) return false;
                boolean confirmed=Ui.confirm(null,"Delete Fish configuration in "+directory+"?\n"
                        +"Workspace profiles, saved layouts, Startup shortcuts, and stored Fish passwords will be deleted so Fish can start fresh.",
                        "Confirm factory reset (delete config)");
                return recoverStartupError(directory,openBroker,openStore,true,confirmed,() -> {
                    try { return KioskMode.authorizeAdminAction(); }
                    catch (IOException ex) { Ui.error(null,ex.getMessage()); return false; }
                },msg -> Ui.error(null,msg));
            });
        } catch (Exception fallback) {
            System.err.println("Fish could not start: "+error);
            return false;
        }
    }

    public static Stage dialog(String error,boolean retry,Consumer<Choice> selected) {
        Stage stage=Ui.dialogStage(null,"Workspace could not be opened",true);
        stage.initModality(Modality.APPLICATION_MODAL);
        TextArea message=new TextArea("Fish cannot read this workspace:\n"+error
                +"\n\nExit keeps everything unchanged. If the file belongs to a newer release, upgrade Fish or use a matching backup. "
                +"Restore factory settings preserves a backup; Factory reset (delete config) wipes saved configuration cleanly.");
        message.setWrapText(true);
        message.setEditable(false);
        message.setFocusTraversable(false);
        message.setPrefRowCount(6);
        message.setPrefColumnCount(58);

        HBox actions=new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        for (Choice choice:Choice.values()) {
            Button button=Ui.button(switch (choice) {
                case EXIT -> "Exit";
                case RETRY -> "Retry load";
                case FACTORY -> "Restore factory settings...";
                case DELETE_CONFIG -> "Factory reset (delete config)...";
            },() -> { selected.accept(choice); stage.close(); });
            button.setId("recovery-"+choice.name().toLowerCase());
            if (choice==Choice.RETRY) button.setDisable(!retry);
            if (choice==Choice.EXIT) button.setDefaultButton(true);
            actions.getChildren().add(button);
        }
        BorderPane body=new BorderPane();
        body.setPadding(new Insets(18));
        body.setCenter(message);
        body.setBottom(actions);
        BorderPane.setMargin(actions,new Insets(18,0,0,0));
        Ui.decorateDialog(stage,body,() -> { selected.accept(Choice.EXIT); stage.close(); },660,300);
        return stage;
    }

    public static Stage startupErrorDialog(String error,Consumer<Boolean> resetSelected) {
        Stage stage=Ui.dialogStage(null,"Fish startup error",true);
        stage.initModality(Modality.APPLICATION_MODAL);
        TextArea message=new TextArea("Fish encountered an error while starting:\n"+(error==null || error.isBlank() ? "Unexpected startup failure." : error)
                +"\n\nChoose Exit to keep existing files unchanged, or Factory reset (delete config) to delete saved configuration and start clean.");
        message.setWrapText(true);
        message.setEditable(false);
        message.setFocusTraversable(false);
        message.setPrefRowCount(6);
        message.setPrefColumnCount(54);

        HBox actions=new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        Button reset=Ui.button("Factory reset (delete config)...",() -> { resetSelected.accept(true); stage.close(); });
        reset.setId("startup-factory-reset");
        Button exit=Ui.button("Exit",() -> { resetSelected.accept(false); stage.close(); });
        exit.setId("startup-error-exit");
        exit.setDefaultButton(true);
        actions.getChildren().addAll(reset,exit);

        BorderPane body=new BorderPane();
        body.setPadding(new Insets(18));
        body.setCenter(message);
        body.setBottom(actions);
        BorderPane.setMargin(actions,new Insets(18,0,0,0));
        Ui.decorateDialog(stage,body,() -> { resetSelected.accept(false); stage.close(); },580,280);
        return stage;
    }

    /** Modal notice shown when Kiosk mode blocks starting Fish outside a saved layout shortcut; allows admin unlock. */
    public static Stage kioskBlockedDialog(Stage owner,Consumer<Boolean> unlocked) {
        Stage stage=Ui.dialogStage(owner,"Kiosk Mode Active",true);
        stage.initModality(Modality.APPLICATION_MODAL);
        Label heading=new Label("Start Fish from a saved layout shortcut");
        heading.getStyleClass().add("fish-section-title");
        Label note1=new Label("Kiosk mode is enabled on this computer. Fish can only be started from a saved desktop or autostart layout shortcut so floor layouts stay intact.");
        note1.setWrapText(true);
        Label note2=new Label("A supervisor or administrator can disable Kiosk mode below to unlock full workspace and layout controls.");
        note2.setWrapText(true);
        Ui.role(note2,"muted");
        VBox message=new VBox(8,heading,note1,note2);

        HBox actions=new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        Button disable=Ui.button("Disable Kiosk Mode (Administrator)...",() -> {
            try {
                if (KioskMode.setEnabledWithAdmin(false)) {
                    unlocked.accept(true);
                    stage.close();
                } else if (KioskMode.isEnabled()) {
                    Ui.error(stage,"Kiosk mode stays enabled. Administrator authorization was cancelled or declined.");
                }
            } catch (IOException ex) {
                Ui.error(stage,"Could not disable Kiosk mode: "+ex.getMessage());
            }
        });
        disable.setId("kiosk-blocked-disable");
        Button close=Ui.button("Close",() -> { unlocked.accept(false); stage.close(); });
        close.setId("kiosk-blocked-close");
        close.setDefaultButton(true);
        actions.getChildren().addAll(disable,close);

        BorderPane body=new BorderPane();
        body.setPadding(new Insets(18));
        body.setCenter(message);
        body.setBottom(actions);
        BorderPane.setMargin(actions,new Insets(18,0,0,0));
        Ui.decorateDialog(stage,body,() -> { unlocked.accept(false); stage.close(); },520,235);
        return stage;
    }

    /** Blocks non-layout startup while Kiosk mode is active; returns true only if an administrator disabled Kiosk mode. */
    public static boolean showKioskBlockedAndAllowUnlock() {
        try {
            Ui.install();
            return onFx(() -> {
                AtomicReference<Boolean> unlocked=new AtomicReference<>(false);
                kioskBlockedDialog(null,unlocked::set).showAndWait();
                return unlocked.get() && !KioskMode.isEnabled();
            });
        } catch (Exception e) {
            System.err.println("Kiosk mode is enabled: Fish can only be started from a saved layout shortcut.");
            return false;
        }
    }

    private static Choice chooseDialog(String error,boolean retry) {
        Choice[] choice={Choice.EXIT};
        dialog(error,retry,value -> choice[0]=value).showAndWait();
        return choice[0];
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> action) {
        if (Ui.isFxStarted() && Platform.isFxApplicationThread()) {
            try { return action.call(); }
            catch (Exception e) { throw new IllegalStateException("Workspace recovery dialog failed.",e); }
        }
        try { return Ui.callAndWait(action); }
        catch (Exception e) { throw new IllegalStateException("Workspace recovery dialog failed.",e); }
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource==null) return;
        try { resource.close(); } catch (Exception ignored) {}
    }

    public static void deleteDirectoryTreeQuietly(Path root) {
        if (root==null || !Files.exists(root)) return;
        try (var walk=Files.walk(root)) {
            for (Path p:walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    var dos=Files.getFileAttributeView(p,java.nio.file.attribute.DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                    if (dos!=null) dos.setReadOnly(false);
                } catch (IOException | UnsupportedOperationException ignored) {}
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }
}
