package fish;

import java.nio.file.*;
import java.util.*;
import fish.demo.DemoHost;
import fish.model.*;
import fish.ui.*;

public final class Main {
    public static final String VERSION = "0.8.0";
    private Main() {}
    public static void main(String[] args) {
        System.setProperty("javafx.enablePreview", "true");
        System.setProperty("javafx.suppressPreviewWarning", "true");
        if (args.length==1 && args[0].equals("--aot-warmup")) {
            runAotWarmup();
            return;
        }
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
                    Ui.runAndWait(() -> {
                        ApplicationWindows app=running.get();
                        if (app!=null) accepted.set(app.openRequest(id));
                    });
                } catch (RuntimeException e) {
                    System.getLogger(Main.class.getName()).log(System.Logger.Level.ERROR,"New Fish window failed",e);
                }
                return accepted.get();
            });
            if (claim.forwarded()) return;
            Ui.FxBoot.startAsync();
            java.util.concurrent.CompletableFuture<Void> awtWarmup=java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    java.awt.Color unused=Ui.BACKGROUND;
                    if (unused!=null) TerminalFonts.resolve(Appearance.defaults().font());
                } catch (Throwable ignored) {}
            });
            StateStore store=claim.store();
            DemoHost host = demonstration ? new DemoHost() : null;
            StateStore.State state;
            try { state=demonstration ? demoState(Objects.requireNonNull(host).port()) : WorkspaceRecovery.load(store); }
            catch (Exception e) { claim.broker().close(); store.close(); if (host!=null) host.close(); throw e; }
            if (state==null) { claim.broker().close(); store.close(); if (host!=null) host.close(); System.exit(0); return; }
            java.util.concurrent.CompletableFuture<Void> fontWarmup=awtWarmup.thenRunAsync(() -> {
                try {
                    java.awt.Font resolved=TerminalFonts.resolve(state.appearance().font());
                    TerminalFonts.validate(resolved,state.profiles());
                } catch (Exception ignored) {}
            });
            Ui.install(state.appTheme(),state.appearance().custom().orElse(null));
            if (demonstration || state.connectOnStartup() || requestedLayout!=null) {
                try { fontWarmup.get(2,java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception ignored) {}
            }
            Path location = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path helper = (Files.isDirectory(location) ? Path.of("scripts") : location.getParent()).resolve("credential-store.ps1").toAbsolutePath();
            boolean demo = demonstration;
            boolean temporary = supplied == null && demonstration;
            Ui.runLater(() -> {
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
                        javafx.application.Platform.exit();
                    });
                    Workspace.withDeferredFontWarmup(fontWarmup,() -> application.restore(state,launchLayout));
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
            try { Ui.install(); Ui.error(null,"Fish could not start: " + e.getMessage()); }
            catch (Exception ignored) { System.err.println("Fish could not start: " + e.getMessage()); }
            System.exit(1);
        }
    }

    private static void runAotWarmup() {
        Path tempDir=null;
        try {
            Ui.FxBoot.startAsync();
            tempDir=Files.createTempDirectory("Fish-aot-warmup-");
            try (DemoHost host=new DemoHost()) {
                StateStore store=new StateStore(tempDir);
                StateStore.State state=demoState(host.port());
                store.save(state);
                StateStore.State loaded=store.load();
                java.awt.Font font=TerminalFonts.resolve(loaded.appearance().font());
                TerminalFonts.validate(font,loaded.profiles());
                Ui.install(loaded.appTheme(),loaded.appearance().custom().orElse(null));
                Path helper=Path.of("credential-store.ps1").toAbsolutePath();
                java.util.concurrent.CountDownLatch closed=new java.util.concurrent.CountDownLatch(1);
                Ui.runAndWait(() -> {
                    Workspace ws=new Workspace(store,loaded,helper,true,new fish.update.Updates(),(o,m) -> false,false);
                    ws.onClosed(closed::countDown);
                    try {
                        ws.scene().getRoot().applyCss();
                        ws.scene().getRoot().layout();
                        for (SessionPane pane:ws.sessionPanes()) {
                            pane.view().resize(640,380);
                            pane.view().paintToImage(1.0);
                            pane.view().paintToImage(1.5);
                        }
                        TutorialDialog tut=new TutorialDialog(ws,TutorialDialog.Guide.GETTING_STARTED,false);
                        tut.stage().getScene().getRoot().applyCss();
                        tut.stage().getScene().getRoot().layout();
                        tut.dispose();
                    } finally {
                        ws.close(true);
                    }
                });
                closed.await(5,java.util.concurrent.TimeUnit.SECONDS);
            }
            Ui.shutdown();
        } catch (Throwable t) {
            System.err.println("AOT warmup note: "+t.getMessage());
            try { Ui.shutdown(); } catch (Throwable ignored) {}
        } finally {
            if (tempDir!=null) {
                try (var walk=Files.walk(tempDir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (java.io.IOException ignored) {}
                    });
                } catch (java.io.IOException ignored) {}
            }
        }
    }

    public static Path defaultDirectory(Path appData) {
        return appData.resolve("Fish");
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
