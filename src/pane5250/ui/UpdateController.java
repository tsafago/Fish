package pane5250.ui;

import java.awt.*;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import pane5250.Main;
import pane5250.model.UpdateSettings;
import pane5250.update.Updates;

final class UpdateController implements AutoCloseable {
    private final Workspace workspace;
    private final Updates service;
    private final java.util.function.BiPredicate<Component,String> confirm;
    private final TransientNotice notice;
    private final Timer startupTimer;
    private Updates.Cancel startupCancel;
    private Updates.Release offered;
    private JDialog dialog;
    private Updates.Cancel dialogCancel;
    private Updates.Download download;
    private boolean closed;
    private volatile boolean handedOff;
    private Runnable checkAction=() -> {};
    private boolean checked;
    private boolean busy;
    private JButton updateButton;
    private java.util.function.BooleanSupplier dirty=() -> false;
    private java.util.function.Supplier<UpdateSettings.Channel> channel;
    private UpdateSettings.Channel offeredChannel;
    UpdateController(Workspace workspace) { this(workspace,new Updates()); }
    UpdateController(Workspace workspace,Updates service) {
        this(workspace,service,(owner,message) -> Ui.confirm(owner,message,"Update Fish"));
    }
    UpdateController(Workspace workspace,Updates service,java.util.function.BiPredicate<Component,String> confirm) {
        this.workspace=workspace; this.service=service;
        this.confirm=confirm;
        notice=new TransientNotice(() -> workspace.active().focusTerminal());
        notice.action("Update...",this::manual);
        notice.action("Hide version",() -> {
            if (offered!=null) workspace.updateSettings(workspace.updateSettings().ignore(offered.version().toString()));
            notice.dismiss();
        });
        JLayeredPane layer=workspace.getLayeredPane();
        layer.add(notice,JLayeredPane.POPUP_LAYER);
        layer.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { position(); }
        });
        startupTimer=new Timer(2400,e -> checkStartup()); startupTimer.setRepeats(false);
    }
    void startup() { if (!closed) startupTimer.restart(); }
    private void position() {
        Dimension size=notice.getPreferredSize();
        JLayeredPane layer=workspace.getLayeredPane();
        int width=Math.min(size.width,Math.max(0,layer.getWidth()-16));
        notice.setBounds(Math.max(8,layer.getWidth()-width-8),42,width,size.height);
    }
    private void checkStartup() {
        if (closed || workspace.closing() || !workspace.updateSettings().startup()) return;
        startupCancel=new Updates.Cancel(); Updates.Cancel cancel=startupCancel;
        UpdateSettings.Channel channel=workspace.updateSettings().channel();
        new SwingWorker<Optional<Updates.Release>,Void>() {
            @Override protected Optional<Updates.Release> doInBackground() throws IOException { return service.check(Main.VERSION,channel,cancel); }
            @Override protected void done() {
                if (closed || workspace.closing() || dialog!=null || !workspace.updateSettings().startup()
                        || channel!=workspace.updateSettings().channel()) return;
                try {
                    Optional<Updates.Release> result=get();
                    if (result.isPresent() && !result.get().version().toString().equals(workspace.updateSettings().ignoredVersion())) {
                        offered=result.get(); notice.showMessage("Fish "+offered.version()+" is available",false); position();
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (ExecutionException e) {
                    System.getLogger(UpdateController.class.getName()).log(System.Logger.Level.DEBUG,"Startup update check: "+e.getCause().getMessage());
                }
            }
        }.execute();
    }
    void settings() {
        workspace.preferences("Updates");
    }
    void manual() {
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
        updateButton.setEnabled(current && !busy && !dirty.getAsBoolean() && !workspace.closing());
        updateButton.setToolTipText(dirty.getAsBoolean() ? "Apply or cancel your settings edits before updating."
                : !current ? "Check for updates in the selected channel first." : null);
    }
    void detach(JDialog window) {
        if (dialog!=window) return;
        if (dialogCancel!=null) dialogCancel.cancel();
        if (download!=null && !handedOff) { cleanup(download); download=null; }
        dialog=null; updateButton=null; checked=false; busy=false;
    }
    JPanel panel(JDialog window,java.util.function.BooleanSupplier dirty,
                 java.util.function.Supplier<UpdateSettings.Channel> channel) {
        if (dialog!=null) detach(dialog);
        this.dirty=dirty; this.channel=channel; dialog=window; checked=false;
        JLabel status=new JLabel("Check public tsafago/Fish releases");
        MarkdownView notes=new MarkdownView("Fish release notes");
        notes.markdown("Only public release metadata is checked. No credentials or workspace data are sent.");
        JProgressBar progress=new JProgressBar(0,100); progress.setVisible(false);
        progress.setForeground(Ui.FOCUS); progress.setBackground(Ui.FIELD);
        JButton update=Ui.button("Update...",0,() -> {}); update.setEnabled(false);
        updateButton=update;
        JButton retry=Ui.button("Check for updates",0,() -> {});
        JButton link=Ui.button("Release notes online",0,() -> {
            if (offered==null) return;
            try { Desktop.getDesktop().browse(offered.url()); }
            catch (IOException | UnsupportedOperationException e) { Ui.error(window,"Could not open release notes: "+e.getMessage()); }
        }); link.setEnabled(false);
        Runnable dismiss=() -> {
            if (workspace.closing() && !closed) return;
            if (dialogCancel!=null) dialogCancel.cancel();
            dialogCancel=null; busy=false;
            if (download!=null && !handedOff) { cleanup(download); download=null; }
            progress.setVisible(false); status.setText("Download canceled. Check again to retry.");
            retry.setEnabled(true); refreshAvailability();
        };
        JPanel body=new JPanel(new BorderLayout(0,12));
        body.add(status,BorderLayout.NORTH); body.add(new JScrollPane(notes),BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout(0,8)); bottom.add(progress,BorderLayout.NORTH);
        JPanel buttons=new JPanel(new GridLayout(2,1,0,6));
        JPanel links=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0)),operations=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        JButton close=Ui.button("Cancel download",0,dismiss); close.setVisible(false);
        links.add(link); links.add(retry); operations.add(close); operations.add(update);
        buttons.add(links); buttons.add(operations);
        bottom.add(buttons,BorderLayout.SOUTH); body.add(bottom,BorderLayout.SOUTH);
        Runnable check=() -> {
            if (download!=null && !handedOff) { cleanup(download); download=null; }
            if (dialogCancel!=null) dialogCancel.cancel();
            Updates.Cancel cancel=new Updates.Cancel(); dialogCancel=cancel;
            UpdateSettings.Channel requestedChannel=channel.get(); offeredChannel=null;
            busy=true;
            status.setText("Checking for updates..."); update.setEnabled(false); retry.setEnabled(false); link.setEnabled(false);
            progress.setVisible(false); progress.setValue(0); close.setVisible(false);
            new SwingWorker<Optional<Updates.Release>,Void>() {
                @Override protected Optional<Updates.Release> doInBackground() throws IOException {
                    return service.check(Main.VERSION,requestedChannel,cancel);
                }
                @Override protected void done() {
                    if (closed || dialog!=window || dialogCancel!=cancel) return;
                    busy=false;
                    retry.setEnabled(true);
                    try {
                        Optional<Updates.Release> found=get(); offered=found.orElse(null);
                        offeredChannel=requestedChannel;
                        status.setText(found.isEmpty() ? "Fish "+Main.VERSION+" is up to date in this channel."
                                : "Fish "+offered.version()+(offered.preview() ? " - experimental preview" : "")+" is available");
                        notes.markdown(found.isEmpty() ? "No newer eligible Windows x64 release. The selected channel controls eligibility.\n"
                                +"Preview patches stay in this minor version; stable releases stay in this major version."
                                : "**Unsigned installer:** checksums verify download integrity, not a trusted publisher. Windows/IT policy still applies.\n\n"
                                +"Updates install Fish for this Windows user. Portable folders are not replaced; the installed copy opens afterward with this workspace.\n\n"
                                +offered.notes());
                        refreshAvailability(); link.setEnabled(found.isPresent());
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    catch (ExecutionException e) { status.setText("Update check failed. Check again to retry."); notes.markdown(e.getCause().getMessage()); }
                }
            }.execute();
        };
        checkAction=check;
        retry.addActionListener(e -> check.run());
        update.addActionListener(e -> {
            Updates.Release release=offered;
            if (release==null || busy || dirty.getAsBoolean() || offeredChannel!=channel.get()
                    || !confirm.test(window,"Download Fish "+release.version()+" and install it for this Windows user?\n"
                    +"After verification, your workspace will be saved and all sessions in this window will end.\n"
                    +"The installer waits for Fish to exit, reports the outcome and opens the installed copy on success.\n"
                    +"Portable files are kept. The installer is unsigned; Windows and your IT policy may block it.")) return;
            Updates.Cancel cancel=new Updates.Cancel(); dialogCancel=cancel;
            busy=true;
            update.setEnabled(false); retry.setEnabled(false); progress.setVisible(true); close.setVisible(true);
            status.setText("Downloading and verifying Fish "+release.version()+"...");
            new SwingWorker<Updates.Download,Integer>() {
                @Override protected Updates.Download doInBackground() throws IOException {
                    return service.download(release,cancel,count -> publish((int)Math.min(100,count*100/release.installer().size())));
                }
                @Override protected void process(java.util.List<Integer> values) {
                    if (dialog==window && dialogCancel==cancel) progress.setValue(values.getLast());
                }
                @Override protected void done() {
                    Updates.Download result;
                    try { result=get(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    catch (ExecutionException e) {
                        if (dialog==window && dialogCancel==cancel) {
                            busy=false;
                            status.setText("Update not installed."); notes.markdown(e.getCause().getMessage());
                            retry.setEnabled(true); progress.setVisible(false);
                        }
                        return;
                    }
                    if (closed || dialog!=window || dialogCancel!=cancel) { cleanup(result); return; }
                    download=result; status.setText("Verified. Starting setup; this window closes only after setup starts.");
                    close.setEnabled(false);
                    boolean starting=workspace.updateAndExit(() -> {
                        cancel.check();
                        service.launch(result,ProcessHandle.current().pid(),workspace.dataDirectory());
                        handedOff=true;
                    },message -> {
                        if (dialog==window) {
                            busy=false;
                            status.setText("Update not started. Fish is still open.");
                            notes.markdown(message); retry.setEnabled(true); progress.setVisible(false); close.setEnabled(true);
                        }
                    });
                    if (!starting) {
                        busy=false;
                        status.setText("Update not started. Finish the pending login, then check again.");
                        retry.setEnabled(true); close.setEnabled(true); progress.setVisible(false);
                    }
                }
            }.execute();
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
        if (dialog!=null) dialog.dispose();
        if (download!=null && !handedOff) cleanup(download);
        service.close();
    }
}
