package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import pane5250.Main;
import pane5250.demo.DemoHost;
import pane5250.model.*;

/** Bounded production-popup/mixed-monitor regression. Requires fresh desktop consent. */
public final class DpiDesktop {
    private static final List<String> failures=new ArrayList<>();
    private static int checks;
    private static String prefix;
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> task=new FutureTask<>(work); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static void check(boolean value,String text) {
        checks++; if (!value) { failures.add(text); System.out.println("FAIL "+text); }
    }
    private static List<Component> children(Container parent) {
        List<Component> result=new ArrayList<>();
        for (Component child:parent.getComponents()) {
            result.add(child); if (child instanceof Container container) result.addAll(children(container));
        }
        return result;
    }
    private static Point center(Component component) throws Exception {
        return edt(() -> {
            Point p=component.getLocationOnScreen(); p.translate(component.getWidth()/2,component.getHeight()/2); return p;
        });
    }
    private static void hover(Robot robot,Component component) throws Exception {
        Point p=center(component); robot.mouseMove(p.x,p.y); robot.delay(130); robot.waitForIdle();
    }
    private static void click(Robot robot,Component component) throws Exception {
        hover(robot,component); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.delay(180); robot.waitForIdle();
    }
    private static BufferedImage capture(Robot robot,Component component,String file) throws Exception {
        Window owner=component instanceof Window w ? w : SwingUtilities.getWindowAncestor(component);
        GraphicsConfiguration config=owner.getGraphicsConfiguration();
        Rectangle monitor=config.getBounds();
        double scale=config.getDefaultTransform().getScaleX();
        Method clientBounds=NativeWindow.class.getDeclaredMethod("clientBounds0",Window.class); clientBounds.setAccessible(true);
        int[] client=edt(() -> (int[])clientBounds.invoke(null,owner));
        Rectangle relative=edt(() -> SwingUtilities.convertRectangle(component.getParent(),component.getBounds(),owner));
        Rectangle target=new Rectangle(client[0]+pixel(relative.x*scale),client[1]+pixel(relative.y*scale),
                pixel((relative.x+relative.width)*scale)-pixel(relative.x*scale),
                pixel((relative.y+relative.height)*scale)-pixel(relative.y*scale));
        if (component==((RootPaneContainer)owner).getRootPane())
            target=new Rectangle(client[0],client[1],client[2],client[3]);
        int x=monitor.x+(int)Math.floor((target.x-monitor.x)/scale);
        int y=monitor.y+(int)Math.floor((target.y-monitor.y)/scale);
        Rectangle request=new Rectangle(x,y,(int)Math.ceil((target.x+target.width-monitor.x)/scale)-(x-monitor.x)+1,
                (int)Math.ceil((target.y+target.height-monitor.y)/scale)-(y-monitor.y)+1);
        MultiResolutionImage image=robot.createMultiResolutionScreenCapture(request);
        BufferedImage full=(BufferedImage)image.getResolutionVariants().stream()
                .max(Comparator.comparingInt(i -> i.getWidth(null))).orElseThrow();
        int offsetX=target.x-monitor.x-pixel((request.x-monitor.x)*scale);
        int offsetY=target.y-monitor.y-pixel((request.y-monitor.y)*scale);
        if (offsetX+target.width>full.getWidth() || offsetY+target.height>full.getHeight())
            throw new AssertionError("Native capture mapping: component="+component.getClass()+" native="+Arrays.toString(client)
                    +" relative="+relative+" target="+target+" request="+request+" offset="+offsetX+","+offsetY+" raster="+full.getWidth()+"x"+full.getHeight());
        BufferedImage physical=full.getSubimage(offsetX,offsetY,target.width,target.height);
        if (file!=null) ImageIO.write(physical,"png",Path.of("build",prefix+"-"+file+".png").toFile());
        return physical;
    }
    private static int pixel(double value) { return (int)Math.ceil(value-.5); }
    private static void perimeter(BufferedImage image,String name) {
        int w=image.getWidth(),h=image.getHeight(),line=Ui.LINE.getRGB();
        int top=0,bottom=0,left=0,right=0;
        for (int x=0;x<w;x++) { if (image.getRGB(x,0)!=line) top++; if (image.getRGB(x,h-1)!=line) bottom++; }
        for (int y=0;y<h;y++) { if (image.getRGB(0,y)!=line) left++; if (image.getRGB(w-1,y)!=line) right++; }
        check(top+bottom+left+right==0,name+" "+w+"x"+h+" missing T/B/L/R="+top+"/"+bottom+"/"+left+"/"+right);
    }
    private static JDialog visibleDialog(String title) throws Exception {
        return edt(() -> Arrays.stream(Window.getWindows()).filter(w -> w instanceof JDialog d
                && d.isShowing() && title.equals(d.getTitle())).map(w -> (JDialog)w).findFirst().orElseThrow());
    }
    private static JButton button(Container parent,String text) {
        return children(parent).stream().filter(c -> c instanceof JButton b && text.equals(b.getText()))
                .map(c -> (JButton)c).findFirst().orElseThrow();
    }
    private static void menus(Workspace w,Robot robot,int monitor) throws Exception {
        WindowChrome chrome=(WindowChrome)field(w,"chrome");
        JMenuBar bar=(JMenuBar)field(chrome,"menus");
        hover(robot,(Component)field(chrome,"dragArea"));
        BufferedImage before=capture(robot,w.getRootPane(),null);
        click(robot,bar.getMenu(0));
        for (int i=0;i<bar.getMenuCount();i++) {
            JMenu menu=bar.getMenu(i);
            hover(robot,menu);
            robot.delay(160); robot.waitForIdle();
            check(edt(() -> menu.getPopupMenu().isShowing()),"Actual quick menu opens via pointer transition: "+menu.getText()+"/"+monitor);
            if (edt(() -> menu.getPopupMenu().isShowing()))
                perimeter(capture(robot,menu.getPopupMenu(),i==0 ? "menu-"+monitor : null),"Actual "+menu.getText()+" popup/"+monitor);
            edt(() -> { w.active().view().repaint(3,5,71,43); return null; });
        }
        robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE);
        hover(robot,(Component)field(chrome,"dragArea")); robot.delay(180); robot.waitForIdle();
        BufferedImage after=capture(robot,w.getRootPane(),"dismissed-"+monitor);
        int newLines=0,line=Ui.LINE.getRGB();
        if (before.getWidth()==after.getWidth() && before.getHeight()==after.getHeight()) {
            int scale=(int)Math.ceil(w.getGraphicsConfiguration().getDefaultTransform().getScaleY());
            for (int y=40*scale;y<after.getHeight()-4;y++) for (int x=4;x<after.getWidth()-4;x++)
                if (after.getRGB(x,y)==line && before.getRGB(x,y)!=line) newLines++;
        } else newLines=-1;
        check(newLines==0,"Quick-menu switch/dismiss restores content without orphan divider pixels: "+monitor+" new="+newLines);
        SwingUtilities.invokeLater(w::appearance);
        robot.delay(250); robot.waitForIdle();
        JDialog appearance=visibleDialog("Appearance");
        perimeter(capture(robot,appearance.getRootPane(),"appearance-"+monitor),"Actual Appearance dialog/"+monitor);
        JComboBox<?> combo=children(appearance.getRootPane()).stream().filter(c -> c instanceof JComboBox<?>)
                .map(c -> (JComboBox<?>)c).findFirst().orElseThrow();
        click(robot,combo);
        JPopupMenu comboPopup=edt(() -> (JPopupMenu)combo.getUI().getAccessibleChild(combo,0));
        check(edt(comboPopup::isShowing),"Production combo opens/"+monitor);
        if (edt(comboPopup::isShowing))
            perimeter(capture(robot,comboPopup,"combo-"+monitor),"Actual combo dropdown/"+monitor);
        robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE); robot.waitForIdle();
        click(robot,button(appearance,"More settings..."));
        JPopupMenu details=edt(() -> Arrays.stream(MenuSelectionManager.defaultManager().getSelectedPath())
                .filter(p -> p instanceof JPopupMenu).map(p -> (JPopupMenu)p).findFirst().orElseThrow());
        perimeter(capture(robot,details,null),"Actual More settings popup/"+monitor);
        JMenu submenu=Arrays.stream(details.getComponents()).filter(c -> c instanceof JMenu)
                .map(c -> (JMenu)c).findFirst().orElseThrow();
        hover(robot,submenu); robot.delay(300); robot.waitForIdle();
        check(edt(() -> submenu.getPopupMenu().isShowing()),"Production appearance submenu opens/"+monitor);
        if (edt(() -> submenu.getPopupMenu().isShowing()))
            perimeter(capture(robot,submenu.getPopupMenu(),null),"Actual appearance submenu/"+monitor);
        hover(robot,details.getComponent(0)); robot.delay(150);
        JMenuItem focus=Arrays.stream(details.getComponents()).filter(c -> c instanceof JMenuItem item
                && "Window and focus...".equals(item.getText())).map(c -> (JMenuItem)c).findFirst().orElseThrow();
        click(robot,focus);
        JDialog settings=visibleDialog("Window and focus");
        perimeter(capture(robot,settings.getRootPane(),"focus-"+monitor),"Actual Window and focus dialog/"+monitor);
        click(robot,button(settings,"Cancel"));
        click(robot,button(appearance,"Done"));
        check(edt(() -> w.getRootPane().getBorder() instanceof Hairline),"Popup/tooltip ownership cannot remove the workspace perimeter/"+monitor);
    }
    private static void controls(Workspace w,Robot robot,int monitor) throws Exception {
        WindowChrome chrome=(WindowChrome)field(w,"chrome");
        JPanel controls=(JPanel)field(chrome,"controls");
        for (int i:new int[]{0,1,2,1,0}) {
            JButton target=(JButton)controls.getComponent(i);
            hover(robot,target);
            check(edt(() -> {
                for (int index=0;index<3;index++)
                    if (((JButton)controls.getComponent(index)).getModel().isRollover()!=(index==controls.getComponentZOrder(target))) return false;
                return true;
            }),"Exactly the hovered caption control is highlighted: "+monitor+"/"+i);
        }
        JButton maximize=(JButton)controls.getComponent(1);
        hover(robot,maximize);
        BufferedImage image=capture(robot,maximize,"maximize-hover-"+monitor);
        check(image.getRGB(4,4)==Ui.HOVER.getRGB(),"Maximize paints its themed rectangular hover background/"+monitor);
        hover(robot,(Component)field(chrome,"dragArea"));
        check(edt(() -> Arrays.stream(controls.getComponents()).noneMatch(c -> ((JButton)c).getModel().isRollover())),
                "Caption exit clears all three hover states/"+monitor);
    }
    public static void main(String[] args) throws Exception {
        boolean baseline=List.of(args).equals(List.of("--baseline"));
        boolean windowOnly=List.of(args).equals(List.of("--window-only"));
        prefix=baseline ? "fish-dpi-before" : "fish-dpi-after";
        Ui.install();
        List<Throwable> errors=new CopyOnWriteArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((thread,error) -> { errors.add(error); error.printStackTrace(); });
        Path root=Files.createTempDirectory(Path.of("build"),"dpi-desktop-");
        CountDownLatch closed=new CountDownLatch(1);
        Point pointer=MouseInfo.getPointerInfo().getLocation();
        try (DemoHost host=new DemoHost()) {
            StateStore store=new StateStore(root);
            Workspace w=edt(() -> {
                Workspace window=new Workspace(store,Main.demoState(host.port()),Path.of("scripts","credential-store.ps1"),true);
                window.appearanceSettings(window.appearanceSettings().withWindowOutline(true).withLayout(false,Appearance.PaneInfo.defaults()));
                window.highlightActive(false);
                window.onClosed(closed::countDown); window.setAlwaysOnTop(true); window.setVisible(true); window.connectAll(); return window;
            });
            Robot robot=new Robot(); robot.setAutoDelay(15);
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while (!edt(() -> w.sessionPanes().stream().allMatch(p -> p.connected() && p.screen().getScreenFields().getSize()>0))) {
                    if (System.nanoTime()>deadline) throw new AssertionError("Synthetic host did not become ready.");
                    Thread.sleep(25);
                }
                int[] dimensions=edt(() -> w.sessionPanes().stream().mapToInt(p -> p.screen().getScreenLength()).toArray());
                int monitor=0;
                for (GraphicsDevice device:GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                    GraphicsConfiguration config=device.getDefaultConfiguration(); Rectangle bounds=config.getBounds();
                    if (baseline && config.getDefaultTransform().getScaleX()==1) continue;
                    System.out.println(device.getIDstring()+" "+bounds+" "+config.getDefaultTransform()+" physical="+device.getDisplayMode().getWidth()+"x"+device.getDisplayMode().getHeight());
                    edt(() -> { w.setBounds(bounds.x+41,bounds.y+63,1001,651); w.toFront(); return null; });
                    robot.delay(320); robot.waitForIdle();
                    hover(robot,(Component)field(field(w,"chrome"),"dragArea"));
                    perimeter(capture(robot,w.getRootPane(),"window-"+monitor),"Actual restored window/"+monitor);
                    if (!windowOnly) { menus(w,robot,monitor); controls(w,robot,monitor); }
                    System.out.println("Before maximize: outline="+w.appearanceSettings().windowOutline()+" border="+w.getRootPane().getBorder()
                            +" root="+w.getRootPane().getBounds()+" client="+Arrays.toString((int[])nativeClient(w)));
                    edt(() -> { w.setExtendedState(Frame.MAXIMIZED_BOTH); return null; });
                    robot.delay(250); robot.waitForIdle();
                    System.out.println("After maximize: outline="+w.appearanceSettings().windowOutline()+" border="+w.getRootPane().getBorder()
                            +" root="+w.getRootPane().getBounds()+" client="+Arrays.toString((int[])nativeClient(w)));
                    perimeter(capture(robot,w.getRootPane(),"maximized-"+monitor),"Actual maximized window/"+monitor);
                    if (!windowOnly) controls(w,robot,monitor);
                    edt(() -> { w.setExtendedState(Frame.NORMAL); w.setIconImages(FishIcon.images()); return null; });
                    robot.delay(200); robot.waitForIdle();
                    perimeter(capture(robot,w.getRootPane(),null),"Actual restored-after-maximize window/"+monitor);
                    if (baseline) break;
                    monitor++;
                }
                check(edt(() -> Arrays.equals(dimensions,w.sessionPanes().stream().mapToInt(p -> p.screen().getScreenLength()).toArray()))
                        && host.connectionCount()==3,"Monitor/popup/hover transitions retain all three sockets and host dimensions.");
                check(errors.isEmpty() && host.failures().isEmpty(),"No asynchronous UI/protocol errors: "+errors+" / "+host.failures());
            } finally {
                edt(() -> { for (Window child:w.getOwnedWindows()) child.dispose(); w.close(false); return null; });
                if (!closed.await(5,TimeUnit.SECONDS)) throw new AssertionError("Synthetic workspace did not close.");
                robot.mouseMove(pointer.x,pointer.y);
            }
        } finally {
            try (var paths=Files.walk(root)) { for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
        System.out.println((failures.isEmpty() ? "PASS" : "FAIL")+" DPI desktop: "+checks+" checks; "+failures.size()+" failures.");
        if (!baseline && !failures.isEmpty()) throw new AssertionError(String.join("\n",failures));
    }
    private static Object nativeClient(Window window) throws Exception {
        Method method=NativeWindow.class.getDeclaredMethod("clientBounds0",Window.class); method.setAccessible(true);
        return edt(() -> method.invoke(null,window));
    }
}
