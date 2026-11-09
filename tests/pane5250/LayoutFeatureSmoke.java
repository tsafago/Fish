package pane5250.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import pane5250.Configuration;
import pane5250.LayoutShortcuts;
import pane5250.demo.DemoHost;
import pane5250.model.*;
import pane5250.update.*;

/** Bounded production actions with never-shown frames and synthetic/loopback state only. */
public final class LayoutFeatureSmoke {
    private static int checks;
    private static void check(boolean value,String text) { checks++; if (!value) throw new AssertionError(text); }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> result=new FutureTask<>(action); SwingUtilities.invokeAndWait(result); return result.get();
    }
    private static Object field(Object owner,String name) throws Exception {
        Field value=owner.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(owner);
    }
    private static void set(Object owner,String name,Object value) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner,value);
    }
    private static void await(Callable<Boolean> action,String text) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime()<end) { if (edt(action)) return; Thread.sleep(15); }
        throw new AssertionError(text);
    }
    private static void rejects(Callable<?> action,String text) throws Exception {
        try { action.call(); } catch (IllegalArgumentException | IOException expected) { checks++; return; }
        throw new AssertionError(text);
    }
    private static void layout(Container target) {
        target.doLayout(); for (Component child:target.getComponents()) if (child instanceof Container c) layout(c);
    }
    private static Profile profile(String name,int port) {
        return new Profile(UUID.randomUUID(),name,"127.0.0.1",port,"","",false,false,"37","Phosphor","None",1,Map.of(),"");
    }
    private static final class Fixture implements AutoCloseable {
        final StateStore store;
        final ApplicationWindows app;
        final CountDownLatch closed=new CountDownLatch(1);
        Fixture(Path directory,StateStore.State state,UUID recipe) throws Exception {
            Files.createDirectories(directory); store=new StateStore(directory); store.save(state);
            app=edt(() -> {
                ApplicationWindows result=new ApplicationWindows(store,directory.resolve("no-credentials.ps1"),false,false,
                        (s,initial,helper,demo) -> new Workspace(s,initial,helper,demo,
                                new Updates((uri,out,limit,cancel,progress) -> { throw new AssertionError("No external HTTP"); },
                                new Updates.Platform() {
                                    @Override public void inspectAndMark(Path file) { throw new AssertionError("No installer"); }
                                    @Override public void launch(Path file,String hash,long pid,Path data) { throw new AssertionError("No installer"); }
                                },directory.resolve("staging")),(owner,message) -> false,false));
                result.onClosed(closed::countDown); result.restore(state,recipe); return result;
            });
        }
        Workspace first() { return app.windows().getFirst(); }
        @Override public void close() throws Exception {
            if (closed.getCount()>0) edt(() -> { app.quit(); return null; });
            check(closed.await(10,TimeUnit.SECONDS),"Owner releases its store and worker");
        }
    }
    private static StateStore.State base(List<Profile> profiles) {
        Profile first=profiles.getFirst();
        return new StateStore.State(profiles,new Layout.Leaf(UUID.randomUUID(),first.id()),1120,760,AppTheme.Charcoal,
                first.id(),Map.of(),true,Appearance.defaults(),StateStore.Tutorial.SKIPPED,false,
                new UpdateSettings(false,UpdateSettings.Channel.PREVIEW_PATCHES,""));
    }
    @SuppressWarnings("unchecked")
    private static JComboBox<Profile> selector(Workspace w) throws Exception { return (JComboBox<Profile>)field(w,"addProfileSelector"); }
    private static void model(Path root) throws Exception {
        Profile first=profile("Synthetic A",23),second=profile("Synthetic B",24);
        StateStore.State state=base(List.of(first,second));
        Layout.Leaf a=new Layout.Leaf(UUID.randomUUID(),first.id()),b=new Layout.Leaf(UUID.randomUUID(),second.id());
        Layout split=new Layout.Split(true,.37,a,b);
        SavedLayout one=new SavedLayout(UUID.randomUUID(),"Work / \u5317",new StateStore.WindowState(split,1050,720,first.id(),second.id()),
                Map.of(a.pane(),new SavedLayout.Display(true,1),b.pane(),new SavedLayout.Display(false,1.65)));
        SavedLayout two=new SavedLayout(UUID.randomUUID(),"Single",new StateStore.WindowState(a,900,600,first.id()),Map.of(a.pane(),new SavedLayout.Display(true,1)));
        StateStore.State named=state.withSavedLayouts(List.of(one,two),one.id());
        try (StateStore store=new StateStore(root.resolve("model"))) {
            store.save(named); check(store.load().equals(named),"Two independent Unicode named snapshots roundtrip exactly");
            Configuration.validate(named);
            Properties values=StateStore.properties(named);
            check(store.decode(values).savedLayouts().getFirst().window().layout().equals(split),"Divider proportion survives strict XML decode");
            String xml=values.toString();
            for (String secret:List.of("IBMSUBSPW","IBMTICKET","password=","ticket=","screenContents","authenticated"))
                check(!xml.contains(secret),"No live/secret field "+secret);
            Properties invalid=new Properties(); invalid.putAll(values);
            invalid.setProperty("savedLayout."+one.id()+".pane."+b.pane()+".zoom","NaN");
            rejects(() -> store.decode(invalid),"Nonfinite saved zoom rejected");
            invalid.clear(); invalid.putAll(values); invalid.setProperty("savedLayout."+one.id()+".version","2");
            rejects(() -> store.decode(invalid),"Future recipe format rejected");
            invalid.clear(); invalid.putAll(values); invalid.setProperty("savedLayout."+one.id()+".layout","L|"+a.pane()+"|"+UUID.randomUUID());
            rejects(() -> store.decode(invalid),"Missing profile is not substituted");
            invalid.clear(); invalid.putAll(values); invalid.setProperty("savedLayout."+one.id()+".name","Single");
            rejects(() -> store.decode(invalid),"Duplicate named recipes rejected");
            invalid.clear(); invalid.putAll(values); invalid.setProperty("savedLayout."+UUID.randomUUID()+".name","Unlisted");
            rejects(() -> store.decode(invalid),"Unlisted/orphan recipe fields rejected");
            invalid.clear(); invalid.putAll(values); invalid.setProperty("version","14");
            rejects(() -> store.decode(invalid),"Relabeled recipe data rejected without downgrade");
            rejects(() -> ProfileManagement.delete(named,second.id()),"Named references prevent dangling profile deletion");
            check(ProfileManagement.delete(state,second.id()).profiles().size()==1,"Unreferenced profile deletion still works");
            Properties legacy=StateStore.properties(state); legacy.setProperty("version","14");
            legacy.remove("addProfile"); legacy.remove("savedLayouts"); legacy.remove("selectedLayout");
            StateStore.State upgraded=store.decode(legacy);
            check(upgraded.addProfile().equals(state.lastProfile()) && upgraded.savedLayouts().isEmpty() && !upgraded.connectOnStartup(),
                    "Format 14 migrates selection/library without enabling startup");
            check(upgraded.layout().equals(state.layout()) && upgraded.profiles().equals(state.profiles()),"Migration retains window and profile identities");
            rejects(() -> SavedLayout.reference("..\\workspace.xml"),"No file/path layout references");
            rejects(() -> SavedLayout.reference("1-1-1-1-1"),"No abbreviated Java UUID acceptance");
            rejects(() -> new SavedLayout.Display(false,3.01),"Bounded zoom");
            rejects(() -> new SavedLayout(one.id()," ",one.window(),one.displays()),"Empty names rejected");
            rejects(() -> new SavedLayout(one.id(),"X",one.window(),Map.of()),"Missing display entries rejected");
            List<SavedLayout> tooMany=new ArrayList<>();
            for (int n=0;n<65;n++) tooMany.add(new SavedLayout(UUID.randomUUID(),"Recipe "+n,two.window(),two.displays()));
            rejects(() -> state.withSavedLayouts(tooMany,null),"Recipe library bounded at 64");
            Path scope=root.resolve("Unicode \u5317 space");
            String arguments=LayoutShortcuts.arguments(one.id(),scope);
            check(arguments.contains("--layout "+one.id()) && arguments.contains("--data-dir \"") && !arguments.contains("Synthetic"),
                    "Shortcut arguments contain only stable ID and quoted workspace scope");
            check(LayoutShortcuts.arguments(one.id(),Path.of("C:\\")).endsWith("C:\\\\\""),"Windows root trailing slash quotes correctly");
            store.factoryReset();
            check(store.load().savedLayouts().isEmpty() && store.load().selectedLayout()==null,"Factory reset clears owned recipes and selection only");
            rejects(() -> { store.save(named); return null; },"Pre-reset stale recipe state cannot return");
        }
    }
    private static StateStore.State live(Path root,DemoHost a,DemoHost b) throws Exception {
        Profile pa=profile("Synthetic A",a.port()),pb=profile("Synthetic B",b.port());
        StateStore.State result;
        try (Fixture fixture=new Fixture(root.resolve("live"),base(List.of(pa,pb)),null)) {
            Workspace w=fixture.first();
            edt(() -> {
                w.startup(); selector(w).setSelectedItem(pb);
                check(w.sessionPanes().size()==1 && !w.active().busy() && a.connectionCount()==0 && b.connectionCount()==0,
                        "Selection alone and saved startup-off do not connect or mutate the pane");
                check(w.active().profile().id().equals(pa.id()),"Changing titlebar selection leaves current profile alone");
                w.executeCommand("Add pane");
                check(w.sessionPanes().size()==1 && w.active().profile().id().equals(pb.id()),"First explicit add reuses only a pristine tile with the selected profile");
                return null;
            });
            await(() -> b.activeConnections()==1 && w.active().connected(),"Selected B did not auto-connect");
            edt(() -> {
                w.startup(); w.repaint(); w.active().view().screenChanged();
                check(b.connectionCount()==1,"Repeated startup/paint never toggles or retries the connection");
                selector(w).setSelectedItem(pa); w.executeCommand("Add pane"); return null;
            });
            await(() -> a.activeConnections()==1 && b.activeConnections()==1,"New pane did not create its own selected A socket");
            SavedLayout pair=edt(() -> {
                check(w.sessionPanes().size()==2,"Later Add creates a separate pane, no blank third tile");
                w.active().view().restoreDisplay(new SavedLayout.Display(false,1.65));
                return w.saveCurrentLayout(null,"Pair / \u5317");
            });
            Workspace other=edt(() -> {
                check(fixture.app.newWindow(w),"New window action succeeds");
                return fixture.app.windows().getLast();
            });
            await(() -> a.activeConnections()==2 && other.active().connected(),"Explicit New window ignored startup-off");
            SavedLayout single=edt(() -> {
                check(other.getOwner()==null && other.sessionPanes().size()==1,"New window is ordinary and independent");
                SavedLayout value=other.saveCurrentLayout(null,"Single");
                check(w.savedLayouts().size()==2,"Named library refreshes across windows");
                selector(other).setSelectedItem(pb);
                w.moveProfile(pb.id(),-1);
                check(selector(other).getItemAt(0).id().equals(pb.id()) && ((Profile)selector(other).getSelectedItem()).id().equals(pb.id()),
                        "Reordered profiles refresh peers while retaining their own selected IDs");
                check(((Profile)selector(w).getSelectedItem()).id().equals(pa.id()),"Per-window add choice stays independent");
                w.renameSavedLayout(pair.id(),"Renamed \u5317");
                check(other.savedLayouts().stream().anyMatch(s -> s.id().equals(pair.id()) && s.name().equals("Renamed \u5317")),
                        "Layout rename retains ID and refreshes peers");
                rejects(() -> { w.validateDeleteProfile(pb.id()); return null; },"Live named library blocks referenced deletion");
                check(fixture.app.openRequest(UUID.randomUUID())==2,"Stale shortcut ID is explicitly unavailable");
                check(fixture.app.openRequest(pair.id())==1,"Saved recipe requests a new window");
                return value;
            });
            await(() -> fixture.app.windows().size()==3 && a.activeConnections()==3 && b.activeConnections()==2,"Saved layout did not create exactly two independent sockets");
            edt(() -> {
                Workspace opened=fixture.app.windows().getLast();
                check(opened.sessionPanes().size()==2 && Collections.disjoint(opened.currentLayout().leaves(),pair.window().layout().leaves()),
                        "Saved launch rekeys pane identities, not live controllers");
                check(opened.sessionPanes().stream().anyMatch(p -> !p.view().isFit() && p.view().zoomValue()==1.65),
                        "Saved per-pane manual zoom actually reaches the renderer");
                check(w.sessionPanes().size()==2 && other.sessionPanes().size()==1,"Opening a recipe never replaces existing windows");
                fixture.app.closeWindow(opened);
                SessionPane pane=w.sessionPanes().getFirst(); pane.disconnect();
                return null;
            });
            await(() -> a.activeConnections()==2 && b.activeConnections()==0,"Closing one recipe window and explicit disconnect release only those sockets");
            int before=b.connectionCount();
            edt(() -> { w.startup(); w.repaint(); w.active().view().screenChanged(); return null; });
            Thread.sleep(200);
            check(b.connectionCount()==before,"Explicit disconnect stays disconnected without automatic retry");
            edt(() -> { w.sessionPanes().getFirst().reconnect(); return null; });
            await(() -> b.activeConnections()==1,"Explicit Reconnect failed");
            check(b.connectionCount()==before+1,"Reconnect makes exactly one new socket");
            edt(() -> {
                for (int n=0;n<6;n++) w.executeCommand("Add pane");
                check(w.sessionPanes().size()==8,"Eight pane limit includes the reused initial tile");
                w.executeCommand("Add pane");
                check(w.sessionPanes().size()==8 && !((JButton)field(w,"addPane")).isEnabled(),"No ninth session/controller; plus is disabled");
                check(((JButton)field(w,"addPane")).getToolTipText().contains("Eight"),"Disabled add explains the limit");
                w.deleteSavedLayout(single.id());
                check(other.savedLayouts().size()==1,"Delete refreshes shared library without deleting a window");
                return null;
            });
            await(() -> a.activeConnections()==8,"All eight intended A sockets across independent windows did not connect");
            result=edt(fixture.app::snapshot);
            check(result.savedLayouts().getFirst().id().equals(pair.id()),"Recipe identity stable after adds/deletion");
        }
        await(() -> a.activeConnections()==0 && b.activeConnections()==0,"Owner quit did not release all sockets");
        return result;
    }
    private static void cold(Path root,StateStore.State state,DemoHost a,DemoHost b) throws Exception {
        SavedLayout pair=state.savedLayouts().getFirst();
        try (Fixture fixture=new Fixture(root.resolve("cold \u5317 spaces"),state,pair.id())) {
            await(() -> fixture.app.windows().size()==1 && a.activeConnections()==1 && b.activeConnections()==1,"Cold shortcut did not create exactly one recipe window");
            edt(() -> {
                Workspace w=fixture.first();
                check(w.sessionPanes().size()==2 && w.getOwner()==null,"Cold recipe opens no default extra window");
                StateStore.State saved=fixture.app.snapshot();
                check(saved.layout().equals(state.layout()) && saved.windows().size()==state.windows().size()+1,
                        "Prior ordinary window restoration is retained, not reset by cold shortcut");
                check(saved.windows().getLast().displays().values().stream().anyMatch(d -> !d.fit() && d.zoom()==1.65),
                        "Launched fit/zoom survives ordinary window persistence");
                check(!saved.connectOnStartup(),"Explicit recipe launch does not overwrite ordinary startup-off");
                visual(w);
                return null;
            });
        }
    }
    private static void failure(Path root) throws Exception {
        int port;
        try (ServerSocket free=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) { port=free.getLocalPort(); }
        Profile bad=profile("Synthetic refused loopback",port);
        try (Fixture fixture=new Fixture(root.resolve("failure"),base(List.of(bad)),null)) {
            Workspace w=fixture.first();
            edt(() -> { w.executeCommand("Add pane"); return null; });
            await(() -> !w.active().busy() && ((String)field(w.active(),"lastProblem")).contains("Reconnect"),"Connection failure does not expose a concise retry path");
            check(edt(() -> ((JButton)field(w.active(),"connectButton")).getAccessibleContext().getAccessibleName().contains("Reconnect")),
                    "Failed pane exposes an accessible Reconnect control");
            try (DemoHost recovered=new DemoHost(port)) {
                edt(() -> { w.active().reconnect(); return null; });
                await(() -> w.active().connected(),"Retry did not connect to recovered loopback endpoint");
                edt(() -> { w.active().disconnect(); w.active().updateHeader(); return null; });
                Thread.sleep(200);
                check(recovered.connectionCount()==1,"Explicit disconnect did not retry by itself");
            }
            edt(() -> {
                Profile kerberos=new Profile(UUID.randomUUID(),"Synthetic cancellation","127.0.0.1",port,"","",false,false,
                        "37","Phosphor","None",1,Map.of(),"",null,Profile.Authentication.KERBEROS,"fake.example.test");
                w.putProfile(kerberos); w.selectProfile(w.active(),kerberos);
                CountDownLatch release=new CountDownLatch(1);
                set(w.active(),"kerberosProvider",(pane5250.security.Kerberos.Provider)principal -> {
                    try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                    return new byte[]{1,2,3};
                });
                w.active().connectOnStartup(); w.active().disconnect(); release.countDown();
                return null;
            });
            await(() -> !w.active().connecting(),"Injected canceled auth lookup did not finish");
            check(edt(() -> !w.active().busy()),"Canceled injected ticket never creates a socket; no real tickets acquired");
        }
    }
    private static void profileRestore(Path root) throws Exception {
        try (DemoHost a=new DemoHost(); DemoHost b=new DemoHost()) {
            Profile pa=profile("Restore A",a.port()),pb=profile("Restore B",b.port());
            try (Fixture fixture=new Fixture(root.resolve("profile-restore"),base(List.of(pa,pb)),null)) {
                Workspace w=fixture.first();
                UUID pane=edt(() -> {
                    UUID id=w.active().id();
                    String saved=new Layout.Leaf(id,pb.id()).encode();
                    w.putProfile(pa.withLayout(saved)); w.putProfile(pb.withLayout(saved));
                    w.active().connectOnStartup(); return id;
                });
                await(() -> a.activeConnections()==1 && w.active().connected(),"Initial restore fixture did not connect A");
                edt(() -> { w.executeCommand("Restore profile layout"); return null; });
                await(() -> a.activeConnections()==0 && b.activeConnections()==1 && w.active().connected(),
                        "Restore did not replace the old A socket with the saved B assignment");
                edt(() -> {
                    check(w.active().id().equals(pane) && w.active().profile().id().equals(pb.id()),
                            "Restoring reused pane IDs applies the saved profile rather than its stale host");
                    w.executeCommand("Restore profile layout"); return null;
                });
                check(b.connectionCount()==1,"Repeated restore of an already connected pane never duplicates its connection");
            }
            try (Fixture fixture=new Fixture(root.resolve("pristine-disconnect"),base(List.of(pa,pb)),null)) {
                Workspace w=fixture.first();
                edt(() -> {
                    SessionPane pane=w.active();
                    pane.disconnectForProfileChange();
                    check(pane.pristine(),"Implicit profile maintenance preserves an unused tile");
                    pane.disconnect();
                    check(!pane.pristine(),"An explicitly disconnected tile cannot be silently reused");
                    selector(w).setSelectedItem(pb); w.executeCommand("Add pane");
                    check(w.sessionPanes().size()==2,"Add preserves an explicitly disconnected tile");
                    return null;
                });
                await(() -> b.activeConnections()==1 && w.active().connected(),"Explicit add after disconnect did not connect B");
                check(a.activeConnections()==0,"Preserved disconnected A never reconnects automatically");
            }
        }
    }
    private static void visual(Workspace w) throws Exception {
        String destination=System.getProperty("fish.test.evidence","");
        if (destination.isBlank()) return;
        Path output=Path.of(destination); Files.createDirectories(output);
        w.setSize(1120,760); layout(w);
        w.savedLayoutsDialog();
        SavedLayoutsDialog dialog=(SavedLayoutsDialog)field(w,"layoutsDialog"); layout(dialog);
        JTextField name=(JTextField)field(dialog,"name");
        check(name.getFont().canDisplayUpTo(name.getText())<0,"Unicode layout name has real UI glyph coverage");
        for (double scale:new double[]{1,1.5}) {
            for (Window frame:new Window[]{w,dialog}) {
                Container body=((RootPaneContainer)frame).getContentPane();
                BufferedImage image=new BufferedImage((int)Math.ceil(body.getWidth()*scale),(int)Math.ceil(body.getHeight()*scale),BufferedImage.TYPE_INT_RGB);
                Graphics2D g=image.createGraphics(); g.scale(scale,scale); body.printAll(g); g.dispose();
                ImageIO.write(image,"png",output.resolve((frame==w ? "window-" : "saved-layouts-")+scale+".png").toFile());
            }
        }
        for (int width:new int[]{330,680,1120}) {
            w.setSize(width,760); layout(w);
            WindowChrome chrome=(WindowChrome)field(w,"chrome");
            Component controls=(Component)field(chrome,"controls");
            check(controls.getX()+controls.getWidth()<=chrome.getWidth(),"Native controls stay inside titlebar at width "+width);
            check(selector(w).getWidth()>=45,"Profile selector remains usable at width "+width);
        }
        dialog.dispose();
    }
    public static void main(String[] args) throws Exception {
        Ui.install();
        Path root=Files.createTempDirectory(Path.of("build"),"layouts-075-");
        try {
            model(root);
            try (DemoHost a=new DemoHost(); DemoHost b=new DemoHost()) {
                StateStore.State state=live(root,a,b); cold(root,state,a,b);
            }
            failure(root);
            profileRestore(root);
            check(Arrays.stream(Window.getWindows()).noneMatch(Window::isShowing),"No test window is shown");
            System.out.println("PASS named layouts / profile-plus / explicit connect / retry / cold persistence: "+checks);
        } finally {
            edt(() -> { for (Window window:Window.getWindows()) window.dispose(); return null; });
            try (var files=Files.walk(root)) {
                for (Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
