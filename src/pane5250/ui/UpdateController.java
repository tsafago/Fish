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
        if (dialog!=null) { dialog.toFront(); return; }
        if (startupCancel!=null) startupCancel.cancel();
        notice.dismiss();
        JDialog window=new JDialog(workspace,"Fish updates",true); dialog=window;
        JLabel status=new JLabel("Checking tsafago/Fish...");
        JTextArea notes=new JTextArea(14,60);
        notes.setEditable(false); notes.setLineWrap(true); notes.setWrapStyleWord(true); notes.setCaretPosition(0);
        notes.setText("Only public release metadata is checked. No credentials or workspace data are sent.");
        JProgressBar progress=new JProgressBar(0,100); progress.setVisible(false);
        progress.setForeground(Ui.FOCUS); progress.setBackground(Ui.FIELD);
        JButton update=Ui.button("Update...",0,() -> {}); update.setEnabled(false);
        JButton retry=Ui.button("Check again",0,() -> {}); retry.setEnabled(false);
        JButton link=Ui.button("Release notes online",0,() -> {
            if (offered==null) return;
            try { Desktop.getDesktop().browse(offered.url()); }
            catch (IOException | UnsupportedOperationException e) { Ui.error(window,"Could not open release notes: "+e.getMessage()); }
        }); link.setEnabled(false);
        Runnable dismiss=() -> {
            if (workspace.closing() && !closed) return;
            if (dialogCancel!=null) dialogCancel.cancel();
            if (download!=null && !handedOff) { cleanup(download); download=null; }
            window.dispose(); if (dialog==window) dialog=null;
        };
        JPanel body=new JPanel(new BorderLayout(0,12)); body.setBorder(Ui.padding(16));
        body.add(status,BorderLayout.NORTH); body.add(new JScrollPane(notes),BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout(0,8)); bottom.add(progress,BorderLayout.NORTH);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        JButton close=Ui.button("Close / cancel",0,dismiss);
        buttons.add(link); buttons.add(retry); buttons.add(close); buttons.add(update);
        bottom.add(buttons,BorderLayout.SOUTH); body.add(bottom,BorderLayout.SOUTH); window.setContentPane(body);
        Runnable check=() -> {
            if (download!=null && !handedOff) { cleanup(download); download=null; }
            if (dialogCancel!=null) dialogCancel.cancel();
            Updates.Cancel cancel=new Updates.Cancel(); dialogCancel=cancel;
            status.setText("Checking for updates..."); update.setEnabled(false); retry.setEnabled(false); link.setEnabled(false);
            progress.setVisible(false); progress.setValue(0);
            new SwingWorker<Optional<Updates.Release>,Void>() {
                @Override protected Optional<Updates.Release> doInBackground() throws IOException {
                    return service.check(Main.VERSION,workspace.updateSettings().channel(),cancel);
                }
                @Override protected void done() {
                    if (closed || dialog!=window || dialogCancel!=cancel) return;
                    retry.setEnabled(true);
                    try {
                        Optional<Updates.Release> found=get(); offered=found.orElse(null);
                        status.setText(found.isEmpty() ? "Fish "+Main.VERSION+" is up to date in this channel."
                                : "Fish "+offered.version()+(offered.preview() ? " - experimental preview" : "")+" is available");
                        notes.setText(found.isEmpty() ? "No newer eligible Windows x64 release. Application settings controls the channel.\n"
                                +"Preview patches stay in this minor version; stable releases stay in this major version."
                                : "Unsigned installer: checksums verify download integrity, not a trusted publisher. Windows/IT policy still applies.\n\n"
                                +"Updates install Fish for this Windows user. Portable folders are not replaced; the installed copy opens afterward with this workspace.\n\n"
                                +offered.notes());
                        notes.setCaretPosition(0); update.setEnabled(found.isPresent()); link.setEnabled(found.isPresent());
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    catch (ExecutionException e) { status.setText("Update check failed. Check again to retry."); notes.setText(e.getCause().getMessage()); }
                }
            }.execute();
        };
        retry.addActionListener(e -> check.run());
        update.addActionListener(e -> {
            Updates.Release release=offered;
            if (release==null || !confirm.test(window,"Download Fish "+release.version()+" and install it for this Windows user?\n"
                    +"After verification, your workspace will be saved and all sessions in this window will end.\n"
                    +"The installer waits for Fish to exit, reports the outcome and opens the installed copy on success.\n"
                    +"Portable files are kept. The installer is unsigned; Windows and your IT policy may block it.")) return;
            Updates.Cancel cancel=new Updates.Cancel(); dialogCancel=cancel;
            update.setEnabled(false); retry.setEnabled(false); progress.setVisible(true);
            status.setText("Downloading and verifying Fish "+release.version()+"...");
            new SwingWorker<Updates.Download,Integer>() {
                @Override protected Updates.Download doInBackground() throws IOException {
                    return service.download(release,cancel,count -> publish((int)Math.min(100,count*100/release.installer().size())));
                }
                @Override protected void process(java.util.List<Integer> values) { progress.setValue(values.getLast()); }
                @Override protected void done() {
                    Updates.Download result;
                    try { result=get(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    catch (ExecutionException e) {
                        if (dialog==window) {
                            status.setText("Update not installed."); notes.setText(e.getCause().getMessage());
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
                            status.setText("Update not started. Fish is still open.");
                            notes.setText(message); retry.setEnabled(true); progress.setVisible(false); close.setEnabled(true);
                        }
                    });
                    if (!starting) {
                        status.setText("Update not started. Finish the pending login, then check again.");
                        retry.setEnabled(true); close.setEnabled(true); progress.setVisible(false);
                    }
                }
            }.execute();
        });
        Ui.decorate(window,dismiss);
        window.getRootPane().registerKeyboardAction(e -> dismiss.run(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        window.setSize(650,470); window.setMinimumSize(new Dimension(460,340)); window.setLocationRelativeTo(workspace);
        check.run();
        if (workspace.isShowing()) window.setVisible(true);
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
