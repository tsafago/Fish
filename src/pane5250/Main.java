package pane5250;

import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import pane5250.demo.DemoHost;
import pane5250.model.*;
import pane5250.ui.*;

public final class Main {
    public static final String VERSION = "0.8.0";
    private Main() {}
    public static void main(String[] args) {
        if (args.length>0 && args[0].equals("--config")) {
            System.exit(Configuration.run(Arrays.copyOfRange(args,1,args.length),System.out,System.err));
            return;
        }
        boolean demonstration = false;
        Path supplied = null;
        UUID requestedLayout=null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--demo")) demonstration = true;
            else if (args[i].equals("--data-dir") && i + 1 < args.length) supplied = Path.of(args[++i]);
            else if (args[i].equals("--layout") && i+1<args.length) {
                if (requestedLayout!=null) { System.err.println("Supply --layout only once."); System.exit(2); return; }
                try { requestedLayout=SavedLayout.reference(args[++i]); }
                catch (IllegalArgumentException e) { System.err.println(e.getMessage()); System.exit(2); return; }
            }
            else if (args[i].equals("--help")) {
                System.out.println("Fish [--demo] [--data-dir DIRECTORY] [--layout UUID]\n"
                        + "--layout opens exactly one new auto-connecting window from a named saved layout.\n"
                        + "--demo starts a loopback-only synthetic TN5250 host in a separate temporary workspace.\n"
                        + "Fish --config <command> ... provides offline configuration.\n"+Configuration.HELP);
                return;
            } else {
                System.err.println("Unknown or incomplete argument: " + args[i]);
                System.exit(2);
            }
        }
        try {
            if (demonstration && requestedLayout!=null) throw new IllegalArgumentException("--layout cannot be combined with --demo.");
            UUID launchLayout=requestedLayout;
            Path directory = supplied != null ? supplied : demonstration ? Files.createTempDirectory("Fish-demo-")
                    : defaultDirectory(Path.of(Optional.ofNullable(System.getenv("APPDATA")).orElse(System.getProperty("user.home"))));
            java.util.concurrent.atomic.AtomicReference<ApplicationWindows> running=new java.util.concurrent.atomic.AtomicReference<>();
            LaunchBroker.Claim claim=LaunchBroker.claim(directory,VERSION+(demonstration ? ":demo" : ":normal"),launchLayout,id -> {
                var accepted=new java.util.concurrent.atomic.AtomicInteger();
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        ApplicationWindows app=running.get();
                        if (app!=null) accepted.set(app.openRequest(id));
                    });
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); return 0; }
                catch (java.lang.reflect.InvocationTargetException e) {
                    System.getLogger(Main.class.getName()).log(System.Logger.Level.ERROR,"New Fish window failed",e.getCause());
                }
                return accepted.get();
            });
            if (claim.forwarded()) return;
            StateStore store=claim.store();
            Ui.install();
            DemoHost host = demonstration ? new DemoHost() : null;
            StateStore.State state;
            try { state=demonstration ? demoState(Objects.requireNonNull(host).port()) : WorkspaceRecovery.load(store); }
            catch (Exception e) { claim.broker().close(); store.close(); if (host!=null) host.close(); throw e; }
            if (state==null) { claim.broker().close(); store.close(); if (host!=null) host.close(); return; }
            Path location = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path helper = (Files.isDirectory(location) ? Path.of("scripts") : location.getParent()).resolve("credential-store.ps1").toAbsolutePath();
            boolean demo = demonstration;
            boolean temporary = supplied == null && demonstration;
            SwingUtilities.invokeLater(() -> {
                try {
                    ApplicationWindows application=new ApplicationWindows(store,helper,demo);
                    application.onClosed(() -> {
                        running.set(null);
                        try { claim.broker().close(); }
                        catch (java.io.IOException e) { System.err.println("Could not remove Fish launch metadata: "+e.getMessage()); }
                        if (host != null) host.close();
                        if (temporary) {
                            try {
                                Files.deleteIfExists(directory.resolve("workspace.xml"));
                                Files.deleteIfExists(directory.resolve("workspace.xml.tmp"));
                                Files.deleteIfExists(directory.resolve("workspace.lock"));
                                Files.delete(directory);
                            } catch (java.io.IOException e) {
                                System.err.println("Could not remove temporary demo workspace: " + e.getMessage());
                            }
                        }
                    });
                    application.restore(state,launchLayout);
                    running.set(application);
                    if (!store.loadNotice().isEmpty())
                        System.getLogger(Main.class.getName()).log(System.Logger.Level.INFO,"Workspace updated: "+store.loadNotice());
                } catch (RuntimeException e) {
                    try { claim.broker().close(); }
                    catch (java.io.IOException closeError) { e.addSuppressed(closeError); }
                    if (host != null) host.close();
                    try { store.close(); }
                    catch (java.io.IOException closeError) { e.addSuppressed(closeError); }
                    Ui.error(null,"Fish could not open its workspace: " + e.getMessage());
                    System.exit(1);
                }
            });
        } catch (Exception e) {
            Ui.error(null,"Fish could not start: " + e.getMessage());
            System.exit(1);
        }
    }

    public static Path defaultDirectory(Path appData) {
        Path legacy=appData.resolve("Pane5250");
        // Reuse the same locked workspace and Credential Manager targets for existing users.
        return Files.exists(legacy.resolve("workspace.xml")) ? legacy : appData.resolve("Fish");
    }

    public static StateStore.State demoState(int port) {
        List<Profile> profiles = new ArrayList<>();
        List<Layout.Leaf> leaves = new ArrayList<>();
        String[] names = {"Main","Customers","Orders"};
        for (int i = 1; i <= 3; i++) {
            UUID id = new UUID(0,i);
            Profile profile = new Profile(id,names[i - 1],"127.0.0.1",port,"DEMOA" + i,"",false,false,
                    "37","Phosphor","None",1,Map.of(),"");
            profiles.add(profile);
            leaves.add(new Layout.Leaf(new UUID(1,i),id));
        }
        Layout layout = new Layout.Split(true,.5,leaves.get(0),new Layout.Split(false,.5,leaves.get(1),leaves.get(2)));
        return new StateStore.State(profiles,layout,1500,900);
    }
}
