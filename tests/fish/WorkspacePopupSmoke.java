package fish.ui;

import fish.Main;
import fish.demo.DemoHost;
import fish.model.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.stage.*;
import static java.lang.foreign.ValueLayout.*;

/** Win64: native mouse/key messages addressed only to this loopback demo. No Robot or desktop capture. */
public final class WorkspacePopupSmoke {
    private static <T> T fx(Callable<T> work) throws Exception { return FxTestSupport.fx(work); }
    private static void run(Runnable work) { FxTestSupport.fxRun(work); }
    private static void await(Callable<Boolean> ok,String reason) throws Exception { FxTestSupport.await(ok,4,reason); }
    private static void check(boolean ok,String reason) { if (!ok) throw new AssertionError(reason); }

    private static MethodHandle win(String name,MemoryLayout result,MemoryLayout... args) {
        return Linker.nativeLinker().downcallHandle(SymbolLookup.libraryLookup("user32",Arena.global()).find(name).orElseThrow(),
                FunctionDescriptor.of(result,args));
    }
    private static final MethodHandle post=win("PostMessageW",JAVA_INT,ADDRESS,JAVA_INT,JAVA_LONG,JAVA_LONG),
            pid=win("GetWindowThreadProcessId",JAVA_INT,ADDRESS,ADDRESS),
            rect=win("GetWindowRect",JAVA_INT,ADDRESS,ADDRESS),screen=win("ClientToScreen",JAVA_INT,ADDRESS,ADDRESS);
    private static MemorySegment hwnd(Window w) throws Exception {
        // Resolve the exact shown Window, not a title or a global foreground HWND.
        long raw=fx(() -> {
            Object peer=Class.forName("com.sun.javafx.stage.WindowHelper").getMethod("getPeer",Window.class).invoke(null,w);
            return (long)Class.forName("com.sun.javafx.tk.TKStage").getMethod("getRawHandle").invoke(peer);
        });
        var h=MemorySegment.ofAddress(raw);
        try (var a=Arena.ofConfined()) {
            var p=a.allocate(JAVA_INT);
            check(raw!=0 && (int)pid.invokeExact(h,p)!=0 && p.get(JAVA_INT,0)==ProcessHandle.current().pid(),"Foreign HWND");
        } catch (Throwable err) { throw new AssertionError(err); }
        return h;
    }
    private static void message(Window w,int type,long flags,long point) throws Exception {
        var h=hwnd(w);
        try { check((int)post.invokeExact(h,type,flags,point)!=0,"PostMessage failed"); }
        catch (Throwable err) { throw new AssertionError(err); }
    }
    private static long coords(Node n,double x,double y) throws Exception {
        return fx(() -> {
            var p=n.localToScene(x,y);
            Window w=n.getScene().getWindow();
            return Math.round(p.getX()*w.getOutputScaleX()) | Math.round(p.getY()*w.getOutputScaleY())<<16;
        });
    }
    private static void click(Workspace w,Node n,double x,double y,boolean right) throws Exception {
        long pos=coords(n,x,y);
        message(w.stage(),0x200,0,pos);
        message(w.stage(),right ? 0x204 : 0x201,right ? 2 : 1,pos);
        message(w.stage(),right ? 0x205 : 0x202,0,pos);
        if (right) {
            // Posted buttons don't move the shared desktop cursor. Supply Windows' WM_CONTEXTMENU
            // with the same screen point that a physical release would have supplied.
            var h=hwnd(w.stage());
            try (var a=Arena.ofConfined()) {
                var p=a.allocate(8,4);
                p.set(JAVA_INT,0,(short)pos); p.set(JAVA_INT,4,(short)(pos>>16));
                check((int)screen.invokeExact(h,p)!=0,"ClientToScreen");
                message(w.stage(),0x7b,h.address(),(p.get(JAVA_INT,0)&0xffffL) | (long)p.get(JAVA_INT,4)<<16);
            } catch (Throwable err) { throw new AssertionError(err); }
        }
    }
    private static void key(Workspace w,int vk) throws Exception {
        message(w.stage(),0x100,vk,1);
        message(w.stage(),0x101,vk,0xc0000001L);
    }
    private static List<ContextMenu> menus(Workspace w) {
        return Window.getWindows().stream().filter(ContextMenu.class::isInstance).map(ContextMenu.class::cast)
                .filter(p -> p.isShowing() && p.getOwnerWindow()==w.stage()).toList();
    }
    private static ContextMenu menu(Workspace w,ContextMenu previous) throws Exception {
        await(() -> menus(w).size()==1 && menus(w).getFirst()!=previous,"Expected one new root menu: "+previous);
        return fx(() -> menus(w).getFirst());
    }
    private static void none(Workspace w,String how) throws Exception { await(() -> menus(w).isEmpty(),how); }
    private static void atDivider(Workspace w,ContextMenu menu,String name) throws Exception {
        var owner=hwnd(w.stage());
        var popup=hwnd(menu);
        Region line=(Region)FxTestSupport.field(w.chrome(),"bottomLine");
        int y=fx(() -> (int)Math.round(line.localToScene(0,0).getY()*w.stage().getOutputScaleY()));
        try (var a=Arena.ofConfined()) {
            var r=a.allocate(16,4);
            r.set(JAVA_INT,4,y);
            check((int)screen.invokeExact(owner,r)!=0,"ClientToScreen");
            int divider=r.get(JAVA_INT,4);
            check((int)rect.invokeExact(popup,r)!=0,"GetWindowRect");
            int top=r.get(JAVA_INT,4);
            System.out.printf("%s: native top=%d, divider=%d px%n",name,top,divider);
            check(top==divider,name+" does not meet the chrome divider");
        } catch (Throwable err) { throw new AssertionError(err); }
    }
    private static void interact(Workspace w) throws Exception {
        var panes=fx(w::sessionPanes);
        SessionPane a=panes.get(0),b=panes.get(1);
        int[] shows={0},peak={0};
        ListChangeListener<Window> opens=change -> {
            while (change.next()) for (Window added:change.getAddedSubList()) {
                if (added instanceof ContextMenu p && p.getOwnerWindow()==w.stage()) {
                    shows[0]++; peak[0]=Math.max(peak[0],menus(w).size());
                }
            }
        };
        run(() -> Window.getWindows().addListener(opens));
        ContextMenu last=null;
        int gesture=0;
        for (var p:List.of(a,a,a,b,a,b)) {
            // Click the exposed terminal, not the previous popup.
            click(w,p.view(),new int[]{200,110,40,65,30,65}[gesture++],30,true);
            last=menu(w,last);
            check(fx(() -> w.active()==p && menus(w).getFirst().getOwnerNode()==p.view()),"Wrong clicked pane");
        }
        run(() -> {
            Window.getWindows().removeListener(opens);
            check(shows[0]==6 && peak[0]==1,"Six gestures opened "+shows[0]+" menus; peak="+peak[0]);
        });
        click(w,b.view(),15,90,false); none(w,"Left-click in the same anchor");
        click(w,b.view(),35,60,true); menu(w,null);
        click(w,a.view(),80,100,false); none(w,"Left-click across panes");
        check(fx(() -> w.active()==a && w.scene().getFocusOwner()==a.view()),"Outside click was swallowed");
        click(w,b.getTop(),70,10,true);
        var header=menu(w,null);
        check(fx(() -> !header.getItems().getFirst().getText().equals("Copy")),"Header showed clipboard menu");
        key(w,27); none(w,"Escape after header popup");
        click(w,a.view(),60,120,false);
        key(w,0x5d); var keyboard=menu(w,null);
        key(w,40);
        await(() -> keyboard.getScene().getFocusOwner()!=null &&
                keyboard.getScene().getFocusOwner().getStyleClass().contains("menu-item"),"Down did not focus a menu item");
        key(w,27); none(w,"Keyboard Escape");
        click(w,a.view(),70,70,true); menu(w,null);
        run(() -> w.addProfileCombo().requestFocus()); none(w,"Focus transfer within workspace");
        click(w,a.view(),70,70,true); menu(w,null);
        Stage other=fx(() -> {
            Stage stage=new Stage(); stage.setTitle("Popup focus fixture"); stage.setScene(new Scene(new Label("Offline fixture"),160,85));
            stage.show(); stage.requestFocus(); return stage;
        });
        try { none(w,"Focus transfer to another stage"); }
        finally { run(() -> { other.close(); w.stage().requestFocus(); }); }
        System.out.println("PASS repeated same/cross-pane clicks, header, outside left click, focus, menu key, Down and Escape");

        for (Menu m:fx(() -> List.copyOf(w.menuBar().getMenus()))) {
            Node button=fx(() -> w.chrome().tutorialMenuTarget(m.getText().replace("_",""),""));
            click(w,button,8,12,false);
            atDivider(w,menu(w,null),m.getText()); key(w,27); none(w,"Escape from "+m.getText());
        }
        run(w.chrome()::showSystemMenu); atDivider(w,menu(w,null),"system"); key(w,27); none(w,"Escape from system");
        run(() -> w.stage().setWidth(465));
        await(() -> !w.menuBar().isVisible(),"Compact mode");
        run(w.chrome()::openCompactMenu); atDivider(w,menu(w,null),"compact"); key(w,27); none(w,"Escape from compact");
    }

    private static int pixel(Region n,int x,int y) { return n.snapshot(null,null).getPixelReader().getArgb(x,y)&0xffffff; }
    private static double brightness(int rgb) { return HostPalette.contrastRatio(rgb,0); }
    private static void themes(Workspace w) {
        double minPill=100,minText=100;
        for (AppTheme t:AppTheme.values()) for (boolean flat:new boolean[]{false,true}) {
            Ui.surfacePreferences(flat ? Set.of(t) : Set.of()); Ui.applyTheme(t);
            var chrome=w.chrome();
            Region pill=w.addProfileCombo();
            // Sample the real selector face clear of label, arrow and edges; never read the desktop.
            int face=pixel(pill,8,5);
            int left=pixel(chrome,2,2),right=pixel(chrome,(int)chrome.getWidth()-3,(int)chrome.getHeight()-4);
            // Also sample a shown production menu, inside its padding and clear of labels.
            chrome.showSystemMenu();
            ContextMenu menu=menus(w).getFirst();
            try {
                Region body=(Region)menu.getSkin().getNode();
                int start=pixel(body,7,3),end=pixel(body,(int)body.getWidth()-8,(int)body.getHeight()-4);
                check(flat ? start==end && left==right : brightness(start)>brightness(end) && brightness(left)>brightness(right),
                        t+" flat="+flat+" gradients: "+List.of(start,end,left,right));
                for (int bg:new int[]{left,right}) {
                    check(t.dark ? brightness(face)>brightness(bg) : brightness(face)<brightness(bg),t+" pill polarity");
                    minPill=Math.min(minPill,HostPalette.contrastRatio(face,bg));
                }
                minText=Math.min(minText,HostPalette.contrastRatio(face,Ui.colorTheme().rgb(ColorRole.CHROME_TEXT)));
                for (int bg:new int[]{start,end})
                    minText=Math.min(minText,HostPalette.contrastRatio(bg,Ui.colorTheme().rgb(ColorRole.MENU_TEXT)));
            } finally { menu.hide(); }
        }
        System.out.printf(Locale.ROOT,"Rendered %d theme/mode pairs: min pill %.3f:1, text %.3f:1%n",
                AppTheme.values().length*2,minPill,minText);
        check(minPill>=1.15 && minText>=4.5,"Rendered contrast fell below floor");
    }
    public static void main(String[] args) throws Exception {
        Ui.install();
        int result=1;
        try (DemoHost host=new DemoHost(); StateStore store=new StateStore(Path.of("workspace"))) {
            var w=fx(() -> {
                var workspace=new Workspace(store,Main.demoState(host.port()),Path.of("no-credentials"),true);
                workspace.setSize(950,510); workspace.setVisible(true); workspace.connectAll(); return workspace;
            });
            try {
                await(() -> w.sessionPanes().stream().allMatch(SessionPane::connected),"Loopback terminals did not connect");
                check(fx(() -> w.stage().getOutputScaleY()==Double.parseDouble(args[0])),"Incorrect output scale");
                interact(w); run(() -> themes(w)); result=0;
            } finally { run(w::finishWindow); }
        } catch (Throwable e) { e.printStackTrace(); }
        finally { Platform.exit(); System.exit(result); }
    }
}
