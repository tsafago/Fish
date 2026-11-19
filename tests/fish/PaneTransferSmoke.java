package fish.ui;

import fish.LaunchBroker;
import fish.Main;
import fish.demo.DemoHost;
import fish.model.*;
import fish.security.KioskMode;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.control.ContextMenu;
import javafx.stage.Screen;
import javafx.stage.Window;
import static java.lang.foreign.ValueLayout.*;
import static fish.ui.FxTestSupport.*;

/** Win64: captured mouse messages posted ONLY to this fixture's own source HWND. Never moves the system pointer. */
public final class PaneTransferSmoke {
    private static int checks;
    private static void check(boolean ok,String message) { if (!ok) throw new AssertionError(message); checks++; }
    private static MethodHandle win(String name,MemoryLayout... args) {
        return Linker.nativeLinker().downcallHandle(SymbolLookup.libraryLookup("user32",Arena.global()).find(name).orElseThrow(),
                FunctionDescriptor.of(JAVA_INT,args));
    }
    private static final MethodHandle POST=win("PostMessageW",ADDRESS,JAVA_INT,JAVA_LONG,JAVA_LONG),
            MAP=win("MapWindowPoints",ADDRESS,ADDRESS,ADDRESS,JAVA_INT),
            PID=win("GetWindowThreadProcessId",ADDRESS,ADDRESS),
            GET_KEYS=win("GetKeyboardState",ADDRESS), SET_KEYS=win("SetKeyboardState",ADDRESS);
    private static final MethodHandle SEND=Linker.nativeLinker().downcallHandle(
            SymbolLookup.libraryLookup("user32",Arena.global()).find("SendMessageW").orElseThrow(),
            FunctionDescriptor.of(JAVA_LONG,ADDRESS,JAVA_INT,JAVA_LONG,JAVA_LONG));
    private static long hwnd(Workspace w) throws Exception { return fx(() -> PaneWindowHit.handle(w.stage())); }
    private static long xy(int x,int y) { return x&65535L | (y&65535L)<<16; }
    private static long client(Workspace w,Point2D p) throws Exception {
        return fx(() -> xy((int)Math.round(p.getX()*w.stage().getOutputScaleX()),(int)Math.round(p.getY()*w.stage().getOutputScaleY())));
    }
    private static long map(long from,long to,long point) {
        try (var a=Arena.ofConfined()) {
            var p=a.allocate(8,4); p.set(JAVA_INT,0,(short)point); p.set(JAVA_INT,4,(short)(point>>16));
            int unused=(int)MAP.invokeExact(MemorySegment.ofAddress(from),MemorySegment.ofAddress(to),p,1);
            return xy(p.get(JAVA_INT,0),p.get(JAVA_INT,4));
        } catch (Throwable err) { throw new AssertionError(err); }
    }
    private static void post(Workspace w,int msg,long flags,long point) throws Exception {
        long id=hwnd(w);
        try (var a=Arena.ofConfined()) {
            var h=MemorySegment.ofAddress(id); var pid=a.allocate(JAVA_INT);
            if (id==0 || (int)PID.invokeExact(h,pid)==0 || pid.get(JAVA_INT,0)!=ProcessHandle.current().pid())
                throw new AssertionError("Refusing to send to a foreign HWND");
            if ((int)POST.invokeExact(h,msg,flags,point)==0) throw new AssertionError("PostMessage failed");
        } catch (Throwable err) { throw new AssertionError(err); }
    }
    private static void drag(SessionPane p,long end,boolean cancel) throws Exception {
        Workspace w=fx(p::workspace);
        long start=client(w,fx(() -> p.tutorialDragTarget().localToScene(28,6)));
        long id=hwnd(w);
        fxRun(() -> {
            try (var a=Arena.ofConfined()) {
                var h=MemorySegment.ofAddress(id); var pid=a.allocate(JAVA_INT);
                if (id==0 || (int)PID.invokeExact(h,pid)==0 || pid.get(JAVA_INT,0)!=ProcessHandle.current().pid())
                    throw new AssertionError("Invalid fixture HWND");
                // Glass reads GetKeyState, not just MK_LBUTTON in wParam. Change only this fixture
                // UI thread's queue-local state, never the physical mouse or system keyboard state.
                var saved=a.allocate(256); var keys=a.allocate(256);
                if ((int)GET_KEYS.invokeExact(saved)==0) throw new AssertionError("GetKeyboardState");
                keys.copyFrom(saved); keys.set(JAVA_BYTE,1,(byte)0x80);
                try {
                    long unused=(long)SEND.invokeExact(h,0x200,0L,start);
                    if ((int)SET_KEYS.invokeExact(keys)==0) throw new AssertionError("SetKeyboardState");
                    unused=(long)SEND.invokeExact(h,0x201,1L,start);
                    unused=(long)SEND.invokeExact(h,0x200,1L,xy((short)start+10,(short)(start>>16)));
                    unused=(long)SEND.invokeExact(h,0x200,1L,end);
                    if (cancel) {
                        unused=(long)SEND.invokeExact(h,0x100,27L,1L);
                        unused=(long)SEND.invokeExact(h,0x101,27L,0xc0000001L);
                    }
                } finally {
                    int restored=(int)SET_KEYS.invokeExact(saved);
                    long unused=(long)SEND.invokeExact(h,0x202,0L,end);
                    if (restored==0) throw new AssertionError("Restore fixture thread keys");
                }
            } catch (Throwable err) { throw new AssertionError(err); }
        });
        await(() -> field(w,"dragPane")==null,"Mouse release did not clear capture");
    }
    private static long over(SessionPane p,Workspace destination) throws Exception {
        return map(hwnd(destination),hwnd(fx(p::workspace)),client(destination,fx(() -> {
            SessionPane at=destination.sessionPanes().getFirst();
            return at.localToScene(at.getWidth()*.5,at.getHeight()*.5);
        })));
    }
    private static void position(Workspace w,boolean right) {
        position(w,Screen.getPrimary(),right);
    }
    private static void position(Workspace w,Screen display,boolean right) {
        var b=display.getVisualBounds();
        w.stage().setAlwaysOnTop(true);
        w.setSize((int)Math.min(615,b.getWidth()*.42),380);
        w.stage().setX(right ? b.getMaxX()-w.getWidth()-18 : b.getMinX()+18);
        w.stage().setY(b.getMinY()+45); w.layoutNow();
    }
    private static void transact(SessionPane p,int sequence) throws Exception {
        Workspace w=fx(p::workspace);
        fxRun(() -> { w.activate(p); p.focusTerminal(); });
        await(() -> w.scene().getFocusOwner()==p.view() && p.connected() && !p.screen().getOIA().isKeyBoardLocked(),"Terminal not ready");
        int pos=fx(() -> p.screen().getCurrentPos()-1);
        post(w,0x100,'7',1); post(w,0x101,'7',0xc0000001L);
        await(() -> p.screen().getScreenAsChars()[pos]=='7',"Native typed digit did not reach host field");
        post(w,0x100,13,1); post(w,0x101,13,0xc0000001L);
        try { await(() -> p.view().visibleText().contains("independent session request "+sequence),"Missing reply "+sequence); }
        catch (AssertionError missing) {
            throw new AssertionError(missing.getMessage()+": "+fx(() -> "connected="+p.connected()+" focus="+w.scene().getFocusOwner()
                    +" locked="+p.screen().getOIA().isKeyBoardLocked()+" demo="+p.view().visibleText()),missing);
        }
        check(fx(() -> p.view().visibleText().contains("Host received Enter")),"Unexpected AID");
    }
    private static Process child(String mode,Path root) throws Exception {
        List<String> cmd=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString()));
        String modules=System.getProperty("jdk.module.path");
        if (modules!=null) cmd.addAll(List.of("--module-path",modules));
        cmd.addAll(List.of("--add-modules=javafx.controls,javafx.swing","--enable-native-access=javafx.graphics,ALL-UNNAMED",
                "-Djavafx.enablePreview=true","-Djavafx.suppressPreviewWarning=true","-Dfish.testMode=true",
                "-Djava.library.path="+System.getProperty("java.library.path"),"-cp",System.getProperty("java.class.path"),
                PaneTransferSmoke.class.getName(),mode,root.toString()));
        return new ProcessBuilder(cmd).inheritIO().start();
    }
    private static final String MODE="fixture-pane-transfer";
    private static void foreign(Path root) throws Exception {
        Ui.install();
        var claim=LaunchBroker.claim(root,MODE,() -> false);
        var store=claim.store();
        try (var host=new DemoHost(); var broker=claim.broker()) {
            var app=fx(() -> new ApplicationWindows(store,root.resolve("absent"),true));
            fxRun(() -> { app.restore(Main.demoState(host.port())); position(app.windows().getFirst(),true); });
            Files.writeString(root.resolve("ready"),Long.toString(hwnd(app.windows().getFirst())));
            for (int i=0;i<800 && !Files.exists(root.resolve("stop"));i++) Thread.sleep(50);
            CountDownLatch closed=new CountDownLatch(1);
            fxRun(() -> { app.onClosed(closed::countDown); app.quit(); });
            check(closed.await(5,TimeUnit.SECONDS),"Foreign fixture failed to close");
        } finally { Platform.exit(); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length==2) {
            Path root=Path.of(args[1]);
            if (args[0].equals("hot")) check(LaunchBroker.claim(root,MODE,() -> false).forwarded(),"Did not forward same-directory launch");
            else foreign(root);
            return;
        }
        Ui.install();
        Path root=Files.createTempDirectory(Path.of(".").toAbsolutePath(),"pane-transfer-");
        ApplicationWindows[] owner={null};
        var claim=LaunchBroker.claim(root,MODE,null,id -> {
            try { return fx(() -> owner[0].openRequest(id)); }
            catch (Exception err) { throw new AssertionError(err); }
        });
        CountDownLatch closed=new CountDownLatch(1);
        var store=claim.store();
        try (var host=new DemoHost(); var broker=claim.broker()) {
            var app=fx(() -> {
                var windows=new ApplicationWindows(store,root.resolve("no-credentials"),true);
                owner[0]=windows; windows.onClosed(closed::countDown); windows.restore(Main.demoState(host.port()));
                position(windows.windows().getFirst(),false); return windows;
            });
            try {
                var a=fx(() -> app.windows().getFirst());
                await(() -> a.sessionPanes().stream().allMatch(SessionPane::connected),"Initial demo sessions");
                var p=fx(() -> a.sessionPanes().getFirst()); var screen=fx(p::screen); var view=fx(p::view);
                var display=new SavedLayout.Display(false,1.2);
                fxRun(() -> view.restoreDisplay(display));
                System.out.println("Fixture display: "+fx(() -> Screen.getScreens().size()+" monitor(s), output scale "+a.stage().getOutputScaleX()));
                transact(p,1);
                // Real right-click menu on the pane; invoke its actual bound command.
                fxRun(() -> a.activate(a.sessionPanes().getLast()));
                long head=client(a,fx(() -> p.tutorialDragTarget().localToScene(28,6)));
                post(a,0x200,0,head); post(a,0x204,2,head); post(a,0x205,0,head);
                await(() -> Window.getWindows().stream().anyMatch(w -> w instanceof ContextMenu m && m.getOwnerWindow()==a.stage()),"Pane menu");
                fxRun(() -> {
                    var menu=(ContextMenu)Window.getWindows().stream().filter(w -> w instanceof ContextMenu m && m.getOwnerWindow()==a.stage()).findFirst().orElseThrow();
                    var item=menu.getItems().stream().filter(i -> i.getText().equals("Detach pane")).findFirst().orElseThrow();
                    menu.hide(); item.fire();
                });
                var detached=fx(p::workspace);
                check(detached!=a && fx(() -> detached.active()==p && detached.paneCount()==1 && !a.hasPane(p.id())),"Menu failed to move the pane");
                fxRun(() -> position(detached,true));
                transact(p,2);
                check(!fx(() -> detached.command("Detach pane").isEnabled()),"Lone pane must not create another stage");
                long back=over(p,a);
                drag(p,back,true);
                check(fx(() -> p.workspace()==detached && app.windows().size()==2),"Escape committed the drop");
                check(host.requestCount()==2,"Cancel sent a host key");
                fxRun(() -> detached.preferencesDialog("General"));
                drag(p,back,false);
                check(fx(() -> p.workspace()==detached && detached.tutorialPreferences()!=null),"Last-pane drag discarded a Settings editor");
                fxRun(() -> detached.tutorialPreferences().forceDispose());
                drag(p,back,false);
                await(() -> p.workspace()==a && app.windows().size()==1,"Re-dock last pane did not retire detached stage");
                transact(p,3);
                long outside=client(a,new Point2D(a.getWidth()+32,160));
                fxRun(() -> {
                    int[] xy={(short)outside,(short)(outside>>16)};
                    long handle=a.paneWindowHandle(),hit=PaneWindowHit.at0(handle,xy);
                    check(hit==PaneWindowHit.OUTSIDE,"Fixture tear-off point isn't open desktop space: "+Arrays.toString(xy));
                });
                drag(p,outside,false);
                await(() -> app.windows().size()==2 && p.workspace()!=a,"Drag-out did not detach");
                var out=fx(p::workspace);
                transact(p,4);
                var hot=child("hot",root);
                try { check(hot.waitFor(12,TimeUnit.SECONDS) && hot.exitValue()==0,"Hot launcher did not forward and exit"); }
                finally { if (hot.isAlive()) hot.destroyForcibly(); }
                var b=fx(() -> app.windows().getLast());
                check(b!=out && b!=a && b.paneCount()==1,"Broker did not open a distinct workspace");
                Screen second=fx(() -> Screen.getScreens().stream().filter(s -> s.getOutputScaleX()!=Screen.getPrimary().getOutputScaleX())
                        .findFirst().orElseGet(() -> Screen.getScreens().getLast()));
                fxRun(() -> { position(out,false); position(b,second,true); });
                await(() -> b.active().connected(),"Broker-created window not connected");
                await(() -> b.stage().getOutputScaleX()==second.getOutputScaleX(),"Broker window did not adopt destination monitor scale");
                System.out.println("Broker destination output scale "+fx(() -> b.stage().getOutputScaleX()));
                var before=fx(b::currentLayout);
                fxRun(() -> {
                    var leaves=new ArrayList<>(before.leaves());
                    while (leaves.size()<8) leaves.add(new Layout.Leaf(UUID.randomUUID(),b.active().profile().id()));
                    b.setLayoutForTest(Layout.bounded(leaves));
                });
                drag(p,over(p,b),false);
                check(fx(() -> b.paneCount()==8 && p.workspace()==out && app.windows().size()==3),"Full target must not fall through to detach");
                fxRun(() -> { b.setLayoutForTest(before); KioskMode.setTestOverride(true); });
                try {
                    drag(p,over(p,b),false);
                    check(fx(() -> p.workspace()==out && !app.detach(a.active(),null)),"Kiosk moved a pane");
                } finally { fxRun(() -> KioskMode.setTestOverride(false)); }
                drag(p,over(p,b),false);
                await(() -> p.workspace()==b && app.windows().size()==2,"Dock into broker-created independent window");
                transact(p,5);
                check(fx(() -> p.screen()==screen && p.view()==view && app.panes().stream().filter(q -> q.id().equals(p.id())).count()==1)
                        && host.connectionCount()==4 && host.activeConnections()==4 && host.requestCount()==5,"Session identity or socket changed: accepted="
                        +host.connectionCount()+" active="+host.activeConnections()+" requests="+host.requestCount()+" replies="+fx(() -> app.panes().stream()
                                .map(q -> q.view().visibleText().lines().filter(line -> line.contains("Host received")).toList()).toList()));
                fxRun(() -> position(b,false));
                Path other=Files.createDirectory(root.resolve("independent"));
                Process foreign=child("foreign",other);
                try {
                    await(() -> Files.exists(other.resolve("ready")) && Files.size(other.resolve("ready"))>0,"Independent owner failed to start");
                    long remote=Long.parseLong(Files.readString(other.resolve("ready")));
                    drag(p,map(remote,hwnd(b),xy(120,130)),false);
                    check(fx(() -> p.workspace()==b && app.windows().size()==2 && b.paneCount()==2),"Foreign JVM treated as a transfer or tear-off");
                    transact(p,6);
                } finally {
                    Files.createFile(other.resolve("stop"));
                    if (!foreign.waitFor(8,TimeUnit.SECONDS)) foreign.destroyForcibly();
                }
                var snapshot=fx(app::snapshot);
                check(display.equals(snapshot.windows().getFirst().displays().get(p.id())),"Transferred pane's display settings were lost");
                check(snapshot.windows().size()==1 && snapshot.windows().getFirst().layout().leaves().stream().anyMatch(l -> l.pane().equals(p.id()))
                        && !snapshot.layout().leaves().stream().anyMatch(l -> l.pane().equals(p.id())),"Persisted pane in wrong workspace");
                check(host.failures().isEmpty() && host.connectionCount()==4 && host.requestCount()==6,"Unexpected loopback activity");
                fxRun(app::quit); check(closed.await(10,TimeUnit.SECONDS),"Final persistence timed out");
                try (StateStore reopened=new StateStore(root)) { check(snapshot.equals(reopened.load()),"Reopened state differs from transfer snapshot"); }
                System.out.println("PASS "+checks+" checks; 6 native typed-digit/Enter round trips, same view/screen, 4 sockets, broker forward + separate JVM.");
            } finally { if (!app.closing()) { fxRun(app::quit); closed.await(10,TimeUnit.SECONDS); } }
        } finally {
            Platform.exit();
            try (var paths=Files.walk(root)) {
                for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
