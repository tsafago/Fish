package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import pane5250.Main;
import pane5250.demo.DemoHost;
import pane5250.model.*;

/** One baseline/confirmation batch on real monitors, only after fresh desktop consent. */
public final class RefinementDesktop {
    private static native void observe0(long handle);
    private static native int[] counts0();
    private static native void stop0();
    private static boolean baseline;
    private static int checks;
    private static final List<String> failures=new ArrayList<>();
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
        for (Component c:parent.getComponents()) { result.add(c); if (c instanceof Container p) result.addAll(children(p)); }
        return result;
    }
    private static Point center(Component c) throws Exception {
        return edt(() -> { Point p=c.getLocationOnScreen(); p.translate(c.getWidth()/2,c.getHeight()/2); return p; });
    }
    private static void click(Robot robot,Component c) throws Exception {
        Point p=center(c); robot.mouseMove(p.x,p.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.waitForIdle();
    }
    private static BufferedImage capture(Robot robot,Component c) throws Exception {
        Method m=DpiDesktop.class.getDeclaredMethod("capture",Robot.class,Component.class,String.class); m.setAccessible(true);
        return (BufferedImage)m.invoke(null,robot,c,null);
    }
    private static int differences(BufferedImage first,BufferedImage next) {
        return differences(first,next,null);
    }
    private static int differences(BufferedImage first,BufferedImage next,Rectangle excluded) {
        if (first.getWidth()!=next.getWidth() || first.getHeight()!=next.getHeight()) return Integer.MAX_VALUE;
        int count=0;
        for (int y=0;y<first.getHeight();y++) for (int x=0;x<first.getWidth();x++)
            if ((excluded==null || !excluded.contains(x,y)) && first.getRGB(x,y)!=next.getRGB(x,y)) count++;
        return count;
    }
    private static double toggle(Workspace w,Robot robot) throws Exception {
        WindowChrome chrome=(WindowChrome)field(w,"chrome");
        Component logo=(Component)field(chrome,"caption");
        click(robot,(Component)field(chrome,"dragArea"));
        Point p=center(logo); robot.mouseMove(p.x,p.y); robot.waitForIdle();
        BufferedImage before=capture(robot,w.active().view());
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); long sent=System.nanoTime();
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        while (differences(before,capture(robot,w.active().view()))<100) {
            if (System.nanoTime()-sent>TimeUnit.SECONDS.toNanos(2)) break;
            Thread.sleep(5);
        }
        double elapsed=(System.nanoTime()-sent)/1_000_000.0;
        System.out.println((baseline ? "BASELINE" : "CURRENT")+" visible logo switch="+elapsed+"ms");
        check(elapsed<180,"Physical logo click changes visible terminal ink within 180ms: "+elapsed+"ms; effect="+edt(() -> w.active().profile().effect()));
        return elapsed;
    }
    private static JDialog visible(String title) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (true) {
            JDialog dialog=edt(() -> Arrays.stream(Window.getWindows()).filter(v -> v instanceof JDialog d
                    && d.isShowing() && d.getTitle().equals(title)).map(v -> (JDialog)v).findFirst().orElse(null));
            if (dialog!=null) return dialog;
            if (System.nanoTime()>deadline) throw new AssertionError("Dialog did not open: "+title);
            Thread.sleep(10);
        }
    }
    private static int[] client(Window w) throws Exception {
        Method m=NativeWindow.class.getDeclaredMethod("clientBounds0",Window.class); m.setAccessible(true);
        return edt(() -> (int[])m.invoke(null,w));
    }
    private static void dialogs(Workspace w,Robot robot,String scale) throws Exception {
        SwingUtilities.invokeLater(() -> AppearanceDialogs.fonts(w));
        JDialog fonts=visible("Terminal fonts");
        JComboBox<?> family=children(fonts).stream().filter(c -> c instanceof JComboBox<?>).map(c -> (JComboBox<?>)c).findFirst().orElseThrow();
        String candidate=TerminalFonts.installed().contains("Ubuntu Mono") ? "Ubuntu Mono" : "Consolas";
        String first=edt(() -> w.appearanceSettings().font().family().equals(candidate) ? "Consolas" : candidate);
        String expected=TerminalFonts.resolve(new Appearance.FontChoice(first,"")).getFamily();
        edt(() -> { family.setSelectedItem(first); return null; });
        check(edt(() -> button(fonts,"Apply").isEnabled()),"Production font editor marks its draft dirty/"+scale);
        click(robot,button(fonts,"Apply"));
        check(edt(() -> fonts.isShowing() && w.terminalFont().getFamily().equals(expected) && !button(fonts,"Apply").isEnabled()),
                "Physical Apply installs the font, stays open and resets its baseline/"+scale);
        edt(() -> { family.setSelectedItem(candidate); return null; });
        click(robot,button(fonts,"Done"));
        check(!edt(fonts::isDisplayable) && edt(() -> w.terminalFont().getFamily().equals(candidate)),
                "Physical Done commits pending font edits and closes the real editor/"+scale);
        SwingUtilities.invokeLater(w::appearance); JDialog appearance=visible("Appearance");
        JComboBox<?> effects=children(appearance).stream().filter(c -> c instanceof JComboBox<?>).map(c -> (JComboBox<?>)c).toList().get(2);
        TerminalView view=w.active().view();
        int[] window=client(w),popup=client(appearance);
        Rectangle relative=edt(() -> SwingUtilities.convertRectangle(view.getParent(),view.getBounds(),w));
        double dpi=w.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        Rectangle excluded=new Rectangle(popup[0]-window[0]-(int)Math.ceil(relative.x*dpi-.5),
                popup[1]-window[1]-(int)Math.ceil(relative.y*dpi-.5),popup[2],popup[3]);
        BufferedImage before=capture(robot,view);
        long sent=System.nanoTime(); edt(() -> { effects.setSelectedItem(Profile.FLUX); return null; });
        while (differences(before,capture(robot,view),excluded)<80) {
            if (System.nanoTime()-sent>TimeUnit.SECONDS.toNanos(2)) break;
            Thread.sleep(5);
        }
        double elapsed=(System.nanoTime()-sent)/1_000_000.0;
        check(elapsed<180,"Production effect dropdown changes actual terminal ink outside its dialog within 180ms/"+scale+": "+elapsed+"ms");
        click(robot,button(appearance,"More settings..."));
        JPopupMenu details=edt(() -> Arrays.stream(MenuSelectionManager.defaultManager().getSelectedPath())
                .filter(p -> p instanceof JPopupMenu).map(p -> (JPopupMenu)p).findFirst().orElseThrow());
        check(Arrays.stream(details.getComponents()).noneMatch(c -> c instanceof JMenuItem i && "Flux animated text".equals(i.getText())),
                "Actual More settings popup has no duplicate Flux item/"+scale);
        robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE);
        click(robot,button(appearance,"Done"));
    }
    private static JButton button(Container parent,String text) {
        return children(parent).stream().filter(c -> c instanceof JButton b && text.equals(b.getText())).map(c -> (JButton)c).findFirst().orElseThrow();
    }
    private static void resize(Workspace w,Robot robot,String scale) throws Exception {
        Rectangle bounds=edt(w::getBounds);
        Object bridge=field(field(w,"chrome"),"nativeWindow");
        observe0((long)field(bridge,"handle"));
        Point start=new Point(bounds.x+bounds.width-2,bounds.y+bounds.height-2);
        List<Long> lags=new CopyOnWriteArrayList<>();
        List<Integer> visibleInk=new CopyOnWriteArrayList<>();
        Robot screenSampler=new Robot();
        ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor();
        try {
            sampler.scheduleAtFixedRate(() -> {
                long sent=System.nanoTime(); SwingUtilities.invokeLater(() -> lags.add(System.nanoTime()-sent));
                Rectangle area=new Rectangle(bounds.x+12,bounds.y+90,bounds.width-155,bounds.height-205);
                BufferedImage image=screenSampler.createScreenCapture(area);
                int ink=0;
                Set<Integer> backgrounds=new HashSet<>(List.of(Ui.BACKGROUND.getRGB(),Ui.paneBackground(true).getRGB(),
                        Ui.color(ColorRole.CHROME).getRGB(),Ui.color(ColorRole.DIVIDER_BACKGROUND).getRGB()));
                for (int y=0;y<image.getHeight();y+=2) for (int x=0;x<image.getWidth();x+=2)
                    if (!backgrounds.contains(image.getRGB(x,y))) ink++;
                visibleInk.add(ink);
            },0,10,TimeUnit.MILLISECONDS);
            robot.mouseMove(start.x,start.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            for (int i=1;i<=20;i++) { robot.mouseMove(start.x-i*6,start.y-i*4); robot.delay(12); }
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.waitForIdle();
        } finally { sampler.shutdownNow(); stop0(); }
        int[] messages=counts0();
        Collections.sort(lags);
        double lag=lags.isEmpty() ? Double.POSITIVE_INFINITY : lags.get((lags.size()-1)*95/100)/1_000_000.0;
        System.out.println((baseline ? "BASELINE" : "CURRENT")+" resize "+scale+" erase/paint/size="+Arrays.toString(messages)+" EDT p95="+lag
                +"ms frames="+visibleInk.size()+" min sampled ink="+visibleInk.stream().mapToInt(Integer::intValue).min().orElse(-1));
        check(messages[2]>=5,"Resize exercised repeated actual native size transitions: "+scale);
        check(edt(() -> w.getWidth()<bounds.width-70 && w.getHeight()<bounds.height-40),"Native resize remains responsive: "+scale);
        check(lag<75,"Resize EDT response p95 below 75ms: "+scale);
        if (!baseline) check(messages[0]==0,"Resizing does not force native background erasure: "+scale);
        check(!visibleInk.isEmpty() && visibleInk.stream().allMatch(n -> n>100),"Sampled actual resize frames never lose all terminal ink: "+scale);
    }
    private static void hiddenDrag(Workspace w,Robot robot) throws Exception {
        List<SessionPane> order=edt(w::sessionPanes);
        Rectangle window=edt(w::getBounds);
        JComponent zone=(JComponent)field(order.getFirst(),"dragZone");
        Point from=center(zone),to=center(order.get(1).view());
        robot.mouseMove(from.x,from.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        for (int i=1;i<=18;i++) {
            robot.mouseMove(from.x+(to.x-from.x)*i/18,from.y+(to.y-from.y)*i/18); robot.delay(15);
        }
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.delay(150); robot.waitForIdle();
        check(edt(() -> w.sessionPanes().get(1)==order.getFirst()),"Real hidden-header pane-top drop swaps the correct live sessions.");
        check(edt(() -> w.getBounds().equals(window)),"Pane-top drag never moves the application window.");
    }
    public static void main(String[] args) throws Exception {
        baseline=List.of(args).contains("--baseline");
        boolean remainder=List.of(args).contains("--remainder");
        System.load(Path.of("build","refinement-probe.dll").toAbsolutePath().toString());
        Ui.install();
        System.out.println("Available Ubuntu fixed-width families: "+TerminalFonts.installed().stream().filter(f -> f.toLowerCase(Locale.ROOT).contains("ubuntu")).toList());
        Path root=Files.createTempDirectory(Path.of("build"),"refinement-desktop-");
        Point pointer=MouseInfo.getPointerInfo().getLocation();
        CountDownLatch closed=new CountDownLatch(1);
        try (DemoHost host=new DemoHost()) {
            StateStore store=new StateStore(root);
            Workspace w=edt(() -> {
                Workspace window=new Workspace(store,Main.demoState(host.port()),Path.of("scripts","credential-store.ps1"),true);
                window.onClosed(closed::countDown); window.setAlwaysOnTop(true); window.setVisible(true); window.connectAll(); return window;
            });
            Robot robot=new Robot(); robot.setAutoDelay(5);
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while (!edt(() -> w.sessionPanes().stream().allMatch(p -> p.connected() && p.screen().getScreenFields().getSize()>0))) {
                    if (System.nanoTime()>deadline) throw new AssertionError("Synthetic host unavailable.");
                    Thread.sleep(20);
                }
                Set<Double> scales=new HashSet<>();
                for (GraphicsDevice device:GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                    GraphicsConfiguration config=device.getDefaultConfiguration();
                    if (!scales.add(config.getDefaultTransform().getScaleX())) continue;
                    Rectangle screen=config.getBounds();
                    edt(() -> { w.setBounds(screen.x+41,screen.y+63,1101,751); w.toFront(); return null; });
                    robot.delay(200); robot.waitForIdle();
                    String scale=Double.toString(config.getDefaultTransform().getScaleX());
                    System.out.println(device.getIDstring()+" "+config.getBounds()+" "+config.getDefaultTransform());
                    edt(() -> { w.activate(w.sessionPanes().getFirst()); w.putProfile(w.active().profile().withDisplay("Phosphor","None")); return null; });
                    if (!remainder || config.getDefaultTransform().getScaleX()!=1) {
                        toggle(w,robot); resize(w,robot,scale+" Flux");
                    }
                    edt(() -> { w.putProfile(w.active().profile().withDisplay("Phosphor","None")); w.setSize(1101,751); return null; });
                    robot.delay(80);
                    if (!remainder || config.getDefaultTransform().getScaleX()!=1) resize(w,robot,scale+" None");
                    if (!baseline) {
                        dialogs(w,robot,scale);
                        edt(() -> {
                            Appearance.PaneInfo hidden=new Appearance.PaneInfo(false,false,false,false,false,false,false,false,false,false);
                            w.appearanceSettings(w.appearanceSettings().withLayout(false,hidden)); return null;
                        });
                        robot.waitForIdle(); hiddenDrag(w,robot);
                    }
                    ImageIO.write(capture(robot,w.getRootPane()),"png",Path.of("build","fish-refinement-"+(baseline ? "before-" : "after-")+scale+".png").toFile());
                }
                check(host.failures().isEmpty() && edt(() -> w.sessionPanes().stream().allMatch(SessionPane::connected)),
                        "Resize/effect/swap keeps independent sockets and synthetic protocol healthy.");
            } finally {
                stop0(); edt(() -> { for (Window child:w.getOwnedWindows()) child.dispose(); w.close(false); return null; });
                if (!closed.await(5,TimeUnit.SECONDS)) throw new AssertionError("Workspace did not close.");
                robot.mouseMove(pointer.x,pointer.y);
            }
        } finally {
            try (var paths=Files.walk(root)) { for (Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
        System.out.println((baseline ? "BASELINE" : failures.isEmpty() ? "PASS" : "FAIL")+" refinement desktop "+checks+" checks; "+failures.size()+" failures.");
        if (!baseline && !failures.isEmpty()) throw new AssertionError(String.join("\n",failures));
    }
}
