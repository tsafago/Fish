package fish.ui;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import fish.Main;
import fish.model.UpdateSettings;
import fish.update.Updates;

public final class UpdateController implements AutoCloseable {
    private final Workspace workspace;
    private final Updates service;
    private final BiPredicate<Window,String> confirm;
    private final TransientNotice notice;
    private final Timeline startupTimer;
    private Updates.Cancel startupCancel;
    private Updates.Release offered;
    private Stage dialog;
    private Updates.Cancel dialogCancel;
    private Updates.Download download;
    private boolean closed;
    private volatile boolean handedOff;
    private Runnable checkAction=() -> {};
    private boolean checked;
    private boolean busy;
    private Button updateButton;
    private BooleanSupplier dirty=() -> false;
    private Supplier<UpdateSettings.Channel> channel;
    private UpdateSettings.Channel offeredChannel;

    UpdateController(Workspace workspace) { this(workspace,new Updates()); }
    UpdateController(Workspace workspace,Updates service) {
        this(workspace,service,(owner,message) -> Ui.confirm(owner,message,"Update Fish"));
    }
    UpdateController(Workspace workspace,Updates service,BiPredicate<Window,String> confirm) {
        this.workspace=workspace; this.service=service;
        this.confirm=confirm;
        notice=new TransientNotice(() -> { if (workspace.active()!=null) workspace.active().focusTerminal(); });
        notice.action("Update...",this::manual);
        notice.action("Hide version",() -> {
            if (offered!=null) workspace.updateSettings(workspace.updateSettings().ignore(offered.version().toString()));
            notice.dismiss();
        });
        startupTimer=new Timeline(new KeyFrame(Duration.millis(2400),e -> checkStartup()));
    }

    public TransientNotice noticeBanner() { return notice; }
    public Stage attachedDialog() { return dialog; }

    void startup() { if (!closed) startupTimer.playFromStart(); }

    public void checkStartup() {
        if (closed || workspace.closing() || !workspace.updateSettings().startup()) return;
        startupCancel=new Updates.Cancel(); Updates.Cancel cancel=startupCancel;
        UpdateSettings.Channel reqChannel=workspace.updateSettings().channel();
        CompletableFuture.supplyAsync(() -> {
            try { return service.check(Main.VERSION,reqChannel,cancel); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }).whenComplete((result,err) -> Ui.runLater(() -> {
            if (closed || workspace.closing() || dialog!=null || !workspace.updateSettings().startup()
                    || reqChannel!=workspace.updateSettings().channel()) return;
            if (err!=null) {
                System.getLogger(UpdateController.class.getName()).log(System.Logger.Level.DEBUG,"Startup update check: "+err.getMessage());
                return;
            }
            if (result.isPresent() && !result.get().version().toString().equals(workspace.updateSettings().ignoredVersion())) {
                offered=result.get();
                notice.setErrorStyle(false);
                notice.showMessage("Fish "+offered.version()+" is available",false);
                workspace.layoutOverlays();
            }
        }));
    }

    void settings() { workspace.preferences("Updates"); }

    public void manual() {
        if (closed || workspace.closing()) return;
        workspace.preferences("Updates");
        ensureCheck();
    }

    void ensureCheck() {
        if (!checked && dialog!=null) {
            if (startupCancel!=null) startupCancel.cancel();
            notice.dismiss(); checked=true; checkAction.run();
        }
    }

    void refreshAvailability() {
        if (updateButton==null) return;
        boolean current=offered!=null && offeredChannel==channel.get();
        updateButton.setDisable(!current || busy || dirty.getAsBoolean() || workspace.closing());
        updateButton.setTooltip(dirty.getAsBoolean() ? new Tooltip("Wait for settings to save, or repair the marked edits before updating.")
                : !current ? new Tooltip("Check for updates in the selected channel first.") : null);
    }

    void detach(Stage window) {
        if (dialog!=window) return;
        if (dialogCancel!=null) dialogCancel.cancel();
        if (download!=null && !handedOff) { cleanup(download); download=null; }
        dialog=null; updateButton=null; checked=false; busy=false;
    }

    BorderPane panel(Stage window,BooleanSupplier dirty,Supplier<UpdateSettings.Channel> channel) {
        if (dialog!=null) detach(dialog);
        this.dirty=dirty; this.channel=channel; dialog=window; checked=false;
        Label status=new Label("Fish "+Main.VERSION);
        status.setWrapText(true);
        MarkdownView notes=new MarkdownView("Fish release notes");
        notes.setVisible(false);
        notes.managedProperty().bind(notes.visibleProperty());

        ProgressBar progress=new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);

        Button update=Ui.button("Update...",() -> {});
        update.setDisable(true);
        update.setVisible(false); update.managedProperty().bind(update.visibleProperty());
        updateButton=update;

        Button retry=Ui.button("Check for updates",() -> {});
        Button close=Ui.button("Cancel download",() -> {});
        close.setOnAction(e -> {
            if (workspace.closing() && !closed) return;
            if (dialogCancel!=null) dialogCancel.cancel();
            dialogCancel=null; busy=false;
            if (download!=null && !handedOff) { cleanup(download); download=null; }
            progress.setVisible(false); progress.setManaged(false);
            close.setVisible(false);
            status.setText("Download canceled. Check again to retry.");
            retry.setDisable(false); refreshAvailability();
        });
        close.setVisible(false); close.managedProperty().bind(close.visibleProperty());

        HBox operations=new HBox(8,retry,update,close); operations.setAlignment(Pos.CENTER_LEFT);
        VBox top=new VBox(10,status,operations,progress);

        BorderPane body=new BorderPane();
        body.setTop(top);
        BorderPane.setMargin(top,new javafx.geometry.Insets(0,0,16,0));
        body.setCenter(notes);

        Runnable check=() -> {
            if (download!=null && !handedOff) { cleanup(download); download=null; }
            if (dialogCancel!=null) dialogCancel.cancel();
            Updates.Cancel cancel=new Updates.Cancel(); dialogCancel=cancel;
            UpdateSettings.Channel requestedChannel=channel.get(); offeredChannel=null;
            busy=true;
            status.setText("Checking for updates...");
            update.setDisable(true); update.setVisible(false); retry.setDisable(true); notes.setVisible(false);
            progress.setVisible(false); progress.setManaged(false); progress.setProgress(0);
            close.setVisible(false);
            CompletableFuture.supplyAsync(() -> {
                try { return service.check(Main.VERSION,requestedChannel,cancel); }
                catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            }).whenComplete((found,err) -> Ui.runLater(() -> {
                if (closed || dialog!=window || dialogCancel!=cancel) return;
                busy=false;
                retry.setDisable(false);
                if (err!=null) {
                    Throwable cause=err.getCause()!=null ? err.getCause() : err;
                    if (cause instanceof java.io.UncheckedIOException uio && uio.getCause()!=null) cause=uio.getCause();
                    status.setText("Update check failed. Check again to retry.");
                    notes.setVisible(true);
                    notes.markdown(cause.getMessage()==null ? cause.toString() : cause.getMessage());
                    return;
                }
                offered=found.orElse(null);
                offeredChannel=requestedChannel;
                status.setText(found.isEmpty() ? "Fish "+Main.VERSION+" is up to date."
                        : "Fish "+offered.version()+(offered.preview() ? " beta" : "")+" is available.");
                if (found.isPresent()) notes.markdown(offered.notes().isBlank() ? "No patch notes for this version." : offered.notes());
                notes.setVisible(found.isPresent()); update.setVisible(found.isPresent());
                refreshAvailability();
            }));
        };
        checkAction=check;
        retry.setOnAction(e -> check.run());
        update.setOnAction(e -> {
            Updates.Release release=offered;
            if (release==null || busy || dirty.getAsBoolean() || offeredChannel!=channel.get()
                    || !confirm.test(window,"Install Fish "+release.version()+" for this account? All sessions will close. Fish will reopen after the update.")) return;
            Updates.Cancel cancel=new Updates.Cancel(); dialogCancel=cancel;
            busy=true;
            update.setDisable(true); retry.setDisable(true);
            progress.setVisible(true); progress.setManaged(true);
            close.setVisible(true); close.setDisable(false);
            status.setText("Downloading and verifying Fish "+release.version()+"...");
            CompletableFuture.supplyAsync(() -> {
                try {
                    return service.download(release,cancel,count -> Ui.runLater(() -> {
                        if (dialog==window && dialogCancel==cancel)
                            progress.setProgress(Math.min(1.0,(double)count/release.installer().size()));
                    }));
                } catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
            }).whenComplete((result,err) -> Ui.runLater(() -> {
                if (err!=null) {
                    if (dialog==window && dialogCancel==cancel) {
                        Throwable cause=err.getCause()!=null ? err.getCause() : err;
                        if (cause instanceof java.io.UncheckedIOException uio && uio.getCause()!=null) cause=uio.getCause();
                        busy=false;
                        status.setText("Update not installed.");
                        notes.markdown(cause.getMessage()==null ? cause.toString() : cause.getMessage());
                        retry.setDisable(false);
                        progress.setVisible(false); progress.setManaged(false);
                        close.setVisible(false); refreshAvailability();
                    }
                    return;
                }
                if (closed || dialog!=window || dialogCancel!=cancel) { cleanup(result); return; }
                download=result;
                status.setText("Verified. Starting setup; all Fish windows close only after setup starts.");
                close.setDisable(true);
                boolean starting=workspace.updateAndExit(() -> {
                    cancel.check();
                    service.launch(result,ProcessHandle.current().pid(),workspace.dataDirectory());
                    handedOff=true;
                },message -> {
                    if (dialog==window) {
                        busy=false;
                        status.setText("Update not started. Fish is still open.");
                        notes.markdown(message);
                        retry.setDisable(false);
                        progress.setVisible(false); progress.setManaged(false);
                        close.setVisible(false); refreshAvailability();
                    }
                });
                if (!starting) {
                    busy=false;
                    status.setText("Update not started. Finish the pending login, then check again.");
                    retry.setDisable(false); close.setVisible(false); refreshAvailability();
                    progress.setVisible(false); progress.setManaged(false);
                }
            }));
        });
        return body;
    }

    private static void cleanup(Updates.Download value) {
        try { value.close(); }
        catch (IOException e) { System.err.println("Temporary update cleanup failed: "+e.getMessage()); }
    }

    @Override public void close() {
        closed=true; startupTimer.stop(); notice.dispose();
        if (startupCancel!=null) startupCancel.cancel();
        if (dialogCancel!=null) dialogCancel.cancel();
        if (dialog!=null) dialog.close();
        if (download!=null && !handedOff) cleanup(download);
        service.close();
    }
}
