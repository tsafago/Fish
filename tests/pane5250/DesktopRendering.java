package pane5250;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import pane5250.demo.DemoHost;
import pane5250.model.StateStore;
import pane5250.ui.*;

/** Real desktop interaction and poisoned-buffer regressions, not only fresh offscreen screenshots. */
public final class DesktopRendering {
    private static final boolean BASELINE = Boolean.getBoolean("pane5250.renderBaseline");
    private static int checks;
    private DesktopRendering() {}

    public static void main(String[] args) throws Exception {
        Ui.install();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((thread,error) -> { errors.add(error); error.printStackTrace(); });
        Path directory = Files.createTempDirectory(Path.of("build"),"render-test-");
        DemoHost host = new DemoHost();
        StateStore store = new StateStore(directory);
        Workspace window = edt(() -> {
            Workspace frame = new Workspace(store,Main.demoState(host.port()),Path.of("scripts","credential-store.ps1"),true);
            String effect=System.getProperty("pane5250.effect","None");
            if (!effect.equals("None"))
                for (var profile : frame.profileList()) frame.putProfile(profile.withDisplay(profile.theme(),effect));
            frame.setAlwaysOnTop(true);
            frame.setVisible(true); frame.connectAll(); return frame;
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (!edt(() -> window.sessionPanes().stream().allMatch(p -> p.connected() && p.screen().getScreenFields().getSize() > 0))) {
                if (System.nanoTime() > deadline) throw new AssertionError("Demo sessions did not become ready.");
                Thread.sleep(25);
            }
            run(window,new Robot());
            require(host.connectionCount() == 3,"Resizing, exposing and opening menus never reconnects a session.");
            require(host.failures().isEmpty(),"No synthetic protocol failures.");
            require(errors.isEmpty(),"No uncaught EDT or background failures: " + errors);
        } finally {
            edt(() -> { window.close(true); return null; });
            while (window.isDisplayable()) Thread.sleep(25);
            host.close();
            Files.deleteIfExists(directory.resolve("workspace.xml"));
            Files.deleteIfExists(directory.resolve("workspace.lock"));
            Files.delete(directory);
        }
    }

    private static void run(Workspace window, Robot robot) throws Exception {
        robot.setAutoDelay(0);
        edt(() -> {
            Rectangle screen = window.getGraphicsConfiguration().getBounds();
            window.setBounds(screen.x + 35,screen.y + 35,Math.min(1100,screen.width - 90),Math.min(760,screen.height - 90));
            window.toFront();
            return null;
        });
        robot.waitForIdle();
        int[] dimensions = edt(() -> window.sessionPanes().stream().mapToInt(p -> p.screen().getScreenLength()).toArray());
        boolean clean = edt(() -> {
            for (SessionPane pane : window.sessionPanes()) {
                TerminalView view = pane.view();
                BufferedImage poison = render(view,Color.MAGENTA), fresh = render(view,Ui.BACKGROUND);
                for (int y = 0; y < fresh.getHeight(); y++)
                    for (int x = 0; x < fresh.getWidth(); x++)
                        if (poison.getRGB(x,y) != fresh.getRGB(x,y)) return false;
            }
            return true;
        });
        require(clean,"Opaque terminal redraw is independent of every previous pixel in the shared buffer.");
        edt(() -> {
            SessionPane pane = window.sessionPanes().getFirst();
            BufferedImage before = render(pane.view(),Ui.BACKGROUND);
            require(pane.type('7'),"Field-aware input remains available with the cached renderer.");
            BufferedImage after = render(pane.view(),Ui.BACKGROUND);
            require(!Arrays.equals(before.getRGB(0,0,before.getWidth(),before.getHeight(),null,0,before.getWidth()),
                    after.getRGB(0,0,after.getWidth(),after.getHeight(),null,0,after.getWidth())),
                    "An edited field invalidates the cached glyphs immediately.");
            require(pane.send(org.tn5250j.keyboard.KeyMnemonic.LEFT)
                            && pane.send(org.tn5250j.keyboard.KeyMnemonic.ERASE_EOF),
                    "Host field-editing keys remain usable.");
            BufferedImage erased = render(pane.view(),Ui.BACKGROUND);
            require(Arrays.equals(before.getRGB(0,0,before.getWidth(),before.getHeight(),null,0,before.getWidth()),
                    erased.getRGB(0,0,erased.getWidth(),erased.getHeight(),null,0,erased.getWidth())),
                    "Field-erasing keys invalidate glyphs and restore the exact original display.");
            return null;
        });
        Meter meter = new Meter();
        RepaintManager previous = RepaintManager.currentManager(window);
        RepaintManager.setCurrentManager(meter);
        List<Long> lags = new CopyOnWriteArrayList<>();
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        try {
            sampler.scheduleAtFixedRate(() -> {
                long sent = System.nanoTime();
                SwingUtilities.invokeLater(() -> lags.add(System.nanoTime() - sent));
            },0,10,TimeUnit.MILLISECONDS);
            Rectangle bounds = edt(window::getBounds);
            Point corner = new Point(bounds.x + bounds.width - 2,bounds.y + bounds.height - 2);
            List<String> hits = new CopyOnWriteArrayList<>();
            AWTEventListener trace = event -> {
                if (event instanceof MouseEvent mouse && mouse.getID() == MouseEvent.MOUSE_PRESSED)
                    hits.add(mouse.getComponent().getClass().getName() + " " + mouse.getLocationOnScreen());
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(trace,AWTEvent.MOUSE_EVENT_MASK);
            drag(robot,corner,-120,-80);
            Toolkit.getDefaultToolkit().removeAWTEventListener(trace);
            robot.waitForIdle();
            Rectangle resized = edt(window::getBounds);
            require(resized.width < bounds.width - 60 && resized.height < bounds.height - 40,
                    "Real window-edge dragging changes both dimensions: " + bounds + " -> " + resized
                            + "; target " + corner + ", pointer " + MouseInfo.getPointerInfo().getLocation() + ", hits " + hits);
            List<JSplitPane> splits = edt(() -> descendants(window,JSplitPane.class));
            for (JSplitPane split : splits) {
                int originalDivider = edt(split::getDividerLocation);
                Component divider = edt(() -> ((BasicSplitPaneUI) split.getUI()).getDivider());
                Point start = edt(() -> {
                    Point point = divider.getLocationOnScreen();
                    point.translate(divider.getWidth() / 2,divider.getHeight() / 2);
                    return point;
                });
                boolean horizontal = split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT;
                drag(robot,start,horizontal ? 60 : 0,horizontal ? 0 : 45);
                robot.waitForIdle();
                require(Math.abs(edt(split::getDividerLocation) - originalDivider - (horizontal ? 60 : 45)) <= 2,
                        "Actual split-divider drag changes its location continuously.");
                drag(robot,new Point(start.x + (horizontal ? 60 : 0),start.y + (horizontal ? 0 : 45)),
                        horizontal ? -60 : 0,horizontal ? 0 : -45);
                robot.waitForIdle();
                require(Math.abs(edt(split::getDividerLocation) - originalDivider) <= 2,"Divider can be dragged back without drift.");
            }
            robot.waitForIdle();
        } finally {
            sampler.shutdownNow();
            robot.waitForIdle();
            RepaintManager.setCurrentManager(previous);
        }
        double paint95 = percentile(meter.paints,95), lag95 = percentile(lags,95);
        System.out.printf(Locale.ROOT,
                "%s resize (%s): DPI=%s, paints=%d, paint median=%.2fms p95=%.2fms max=%.2fms, EDT lag p95=%.2fms max=%.2fms%n",
                BASELINE ? "BASELINE" : "MEASURED",System.getProperty("pane5250.effect","None"),edt(() -> window.getGraphicsConfiguration().getDefaultTransform()),
                meter.paints.size(),percentile(meter.paints,50),paint95,percentile(meter.paints,100),lag95,percentile(lags,100));
        require(meter.paints.size() >= 8,"Resize measurement includes real dirty-region paints.");
        require(paint95 < 30 && lag95 < 75,"Resize p95 paint is below 30ms and EDT response below 75ms.");
        for (JMenuBar bar : edt(() -> descendants(window,JMenuBar.class))) {
            for (int index = 0; index < bar.getMenuCount(); index++) {
                JMenu menu = bar.getMenu(index);
                click(robot,menu);
                robot.waitForIdle();
                require(edt(menu::isPopupMenuVisible),"Actual title-row menu opens.");
                robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE);
                robot.waitForIdle();
            }
        }
        windowControls(window,robot);
        List<SessionPane> order = edt(window::sessionPanes);
        dragPane(robot,order.get(0),order.get(1));
        await(() -> window.sessionPanes().get(1) == order.get(0),"Real pane-header drop swaps the live sessions.");
        dragPane(robot,order.get(0),order.get(1));
        await(() -> window.sessionPanes().equals(order),"Dragging back restores the pinned composition.");
        require(edt(() -> Arrays.equals(dimensions,window.sessionPanes().stream().mapToInt(p -> p.screen().getScreenLength()).toArray())),
                "Continuous window/divider resize and maximize/restore preserve every host presentation space.");
        for (SessionPane pane : edt(window::sessionPanes)) {
            Point origin = edt(() -> pane.view().getLocationOnScreen());
            BufferedImage margin = robot.createScreenCapture(new Rectangle(origin.x + 4,origin.y + 4,3,3));
            require(margin.getRGB(1,1) == pane.view().getBackground().getRGB(),"Actual exposed terminal margin is clean, not old menu/pane pixels.");
        }
        BufferedImage capture = robot.createScreenCapture(edt(window::getBounds));
        String scale = System.getProperty("sun.java2d.uiScale","native").replace('.','-');
        ImageIO.write(capture,"png",Path.of("build","desktop-render-" + (BASELINE ? "baseline-" : "")
                +System.getProperty("pane5250.effect","None").toLowerCase(Locale.ROOT)+"-"+scale+".png").toFile());
        System.out.println(BASELINE ? "Baseline diagnostics recorded." : "PASS " + checks + " desktop rendering, resize and custom-window checks.");
    }

    private static void windowControls(Workspace window,Robot robot) throws Exception {
        require(edt(window::isUndecorated),"Workspace has no native titlebar.");
        Rectangle normal = edt(window::getBounds);
        Component title = edt(() -> descendants(window,JPanel.class).stream()
                .filter(p -> "window-drag-area".equals(p.getName())).findFirst().orElseThrow());
        Point titlePoint = center(title);
        drag(robot,titlePoint,32,20);
        robot.waitForIdle();
        require(edt(() -> window.getX() == normal.x + 32 && window.getY() == normal.y + 20),
                "Actual title dragging moves the window by the pointer delta.");
        Rectangle moved = edt(window::getBounds);
        click(robot,title); Thread.sleep(70); click(robot,title);
        await(() -> (window.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0,"Title double-click maximizes.");
        click(robot,button(window,"Restore window"));
        await(() -> window.getExtendedState() == Frame.NORMAL,"Restore button restores normal state.");
        require(edt(() -> window.getBounds().equals(moved)),"Restore preserves pre-maximize bounds.");
        click(robot,button(window,"Maximize window"));
        await(() -> (window.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0,"Maximize button works.");
        Rectangle maximized = edt(window::getBounds);
        Rectangle workArea = edt(() -> {
            Rectangle bounds = new Rectangle(window.getGraphicsConfiguration().getBounds());
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(window.getGraphicsConfiguration());
            bounds.x += insets.left; bounds.y += insets.top;
            bounds.width -= insets.left + insets.right; bounds.height -= insets.top + insets.bottom;
            return bounds;
        });
        require(Math.abs(maximized.width - workArea.width) <= 2 && Math.abs(maximized.height - workArea.height) <= 2,
                "Maximize uses the monitor work area without covering the taskbar: " + maximized + " / " + workArea);
        click(robot,button(window,"Restore window"));
        await(() -> window.getExtendedState() == Frame.NORMAL,"Second restore works.");
        for (int edge = 0; edge < 8; edge++) {
            edt(() -> { window.setBounds(normal); return null; });
            robot.waitForIdle();
            int horizontal = switch (edge) { case 0,4,6 -> -1; case 1,5,7 -> 1; default -> 0; };
            int vertical = switch (edge) { case 2,4,5 -> -1; case 3,6,7 -> 1; default -> 0; };
            Point start = new Point(normal.x + (horizontal < 0 ? 2 : horizontal > 0 ? normal.width - 2 : normal.width / 2),
                    normal.y + (vertical < 0 ? 2 : vertical > 0 ? normal.height - 2 : normal.height / 2));
            drag(robot,start,-horizontal * 24,-vertical * 24);
            robot.waitForIdle();
            Rectangle resized = edt(window::getBounds);
            require(resized.width == normal.width - Math.abs(horizontal) * 24
                            && resized.height == normal.height - Math.abs(vertical) * 24
                            && resized.x == normal.x + (horizontal < 0 ? 24 : 0)
                            && resized.y == normal.y + (vertical < 0 ? 24 : 0),
                    "Window edge/corner " + edge + " resizes only the intended sides: " + resized);
        }
        edt(() -> { window.setBounds(normal.x,normal.y,700,460); return null; });
        robot.waitForIdle();
        drag(robot,new Point(normal.x + 698,normal.y + 458),-160,-160);
        robot.waitForIdle();
        require(edt(() -> window.getSize().equals(window.getMinimumSize())),"Mouse resizing respects minimum usable dimensions.");
        edt(() -> { window.setBounds(normal); window.active().focusTerminal(); return null; });
        robot.waitForIdle();
        await(() -> window.active().view().hasFocus(),"Terminal focus before window keyboard operations.");
        robot.keyPress(KeyEvent.VK_ALT); tap(robot,KeyEvent.VK_SPACE); robot.keyRelease(KeyEvent.VK_ALT);
        Thread.sleep(150);
        tap(robot,KeyEvent.VK_DOWN); tap(robot,KeyEvent.VK_ENTER);
        tap(robot,KeyEvent.VK_RIGHT); tap(robot,KeyEvent.VK_DOWN); tap(robot,KeyEvent.VK_ENTER);
        require(edt(() -> window.getX() == normal.x + 10 && window.getY() == normal.y + 10),
                "Native Alt+Space / Move remains functional without a duplicate Swing popup.");
        require(edt(() -> MenuSelectionManager.defaultManager().getSelectedPath().length == 0),
                "Native window commands do not strand an application popup.");
        edt(() -> { window.setBounds(normal); window.active().focusTerminal(); return null; });
        robot.waitForIdle();
        systemCommand(robot,1);
        tap(robot,KeyEvent.VK_RIGHT); tap(robot,KeyEvent.VK_DOWN); tap(robot,KeyEvent.VK_ENTER);
        require(edt(() -> window.getX() == normal.x + 10 && window.getY() == normal.y + 10),
                "Custom window menu / Move / arrows / Enter moves without sending host keys.");
        require(edt(() -> window.active().view().hasFocus()),"Keyboard Move restores terminal focus; actual "
                + edt(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
        Rectangle keyboardMoved = edt(window::getBounds);
        systemCommand(robot,2);
        tap(robot,KeyEvent.VK_RIGHT); tap(robot,KeyEvent.VK_DOWN);
        require(edt(() -> window.getWidth() == normal.width + 10 && window.getHeight() == normal.height + 10),
                "Keyboard Size changes dimensions before cancellation.");
        tap(robot,KeyEvent.VK_ESCAPE);
        require(edt(() -> window.getBounds().equals(keyboardMoved)),"Escape cancels keyboard sizing exactly.");
        systemCommand(robot,2);
        tap(robot,KeyEvent.VK_RIGHT); tap(robot,KeyEvent.VK_ENTER);
        require(edt(() -> window.getWidth() == normal.width + 10),"Enter commits keyboard sizing.");
        require(edt(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == window.active().view()),
                "Window keyboard operations restore the terminal's focus.");
        click(robot,button(window,"Minimize window"));
        await(() -> (window.getExtendedState() & Frame.ICONIFIED) != 0,"Minimize button iconifies.");
        edt(() -> { window.setExtendedState(Frame.NORMAL); window.setBounds(normal); window.toFront(); return null; });
        await(() -> window.getExtendedState() == Frame.NORMAL && window.isShowing(),"Window returns after minimizing.");
        click(robot,button(window,"Close window"));
        cancelClose(window,robot);
        edt(() -> { window.active().focusTerminal(); return null; });
        robot.keyPress(KeyEvent.VK_ALT); tap(robot,KeyEvent.VK_F4); robot.keyRelease(KeyEvent.VK_ALT);
        cancelClose(window,robot);
        require(edt(() -> window.sessionPanes().stream().allMatch(SessionPane::connected)),
                "Canceling close through either window route preserves every live session.");
        JButton close = button(window,"Close window");
        edt(() -> { close.requestFocusInWindow(); return null; });
        await(close::hasFocus,"Custom window buttons participate in keyboard focus.");
        require(edt(() -> {
            BufferedImage image = new BufferedImage(close.getWidth(),close.getHeight(),BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); close.paint(graphics); graphics.dispose();
            return image.getRGB(3,3) == Ui.FOCUS.getRGB();
        }),"Focused custom window buttons paint a visible focus outline.");
        edt(() -> { window.setBounds(normal); window.active().focusTerminal(); return null; });
        robot.waitForIdle();
    }
    private static void dragPane(Robot robot,SessionPane source,SessionPane target) throws Exception {
        Component header = edt(() -> ((BorderLayout)source.getLayout()).getLayoutComponent(BorderLayout.NORTH));
        Point start = center(header), destination = center(target.view());
        drag(robot,start,destination.x - start.x,destination.y - start.y);
        robot.waitForIdle();
    }
    private static void cancelClose(Workspace window,Robot robot) throws Exception {
        await(() -> Arrays.stream(window.getOwnedWindows()).anyMatch(w -> w instanceof JDialog && w.isShowing()),
                "Close requests the existing connected-session confirmation.");
        JButton cancel = edt(() -> Arrays.stream(window.getOwnedWindows()).filter(Window::isShowing)
                .flatMap(w -> descendants(w,JButton.class).stream()).filter(b -> "Cancel".equals(b.getText()))
                .findFirst().orElseThrow());
        click(robot,cancel);
        await(() -> Arrays.stream(window.getOwnedWindows()).noneMatch(Window::isShowing),"Close can be canceled.");
    }
    private static JButton button(Workspace window,String name) throws Exception {
        return edt(() -> descendants(window,JButton.class).stream()
                .filter(b -> name.equals(b.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow());
    }
    private static void systemCommand(Robot robot,int downs) throws Exception {
        robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_ALT); tap(robot,KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_ALT); robot.keyRelease(KeyEvent.VK_CONTROL);
        await(() -> Arrays.stream(MenuSelectionManager.defaultManager().getSelectedPath())
                .anyMatch(element -> element instanceof JPopupMenu),"Ctrl+Alt+Space opens the custom window menu.");
        for (int i = 0; i < downs; i++) tap(robot,KeyEvent.VK_DOWN);
        tap(robot,KeyEvent.VK_ENTER);
    }
    private static void tap(Robot robot,int key) { robot.keyPress(key); robot.keyRelease(key); robot.waitForIdle(); }
    private static Point center(Component component) throws Exception {
        return edt(() -> { Point point = component.getLocationOnScreen(); point.translate(component.getWidth() / 2,component.getHeight() / 2); return point; });
    }
    private static void click(Robot robot,Component component) throws Exception {
        Point point = center(component);
        robot.mouseMove(point.x,point.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.waitForIdle();
    }
    private static void await(Callable<Boolean> action,String text) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!edt(action)) {
            if (System.nanoTime() > deadline) { require(false,text); return; }
            Thread.sleep(25);
        }
        require(true,text);
        Thread.sleep(100);
    }
    private static BufferedImage render(TerminalView view,Color background) {
        BufferedImage image = new BufferedImage(view.getWidth(),view.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(background); g.fillRect(0,0,image.getWidth(),image.getHeight()); view.paint(g); g.dispose();
        return image;
    }
    private static void drag(Robot robot,Point start,int dx,int dy) throws InterruptedException {
        robot.mouseMove(start.x,start.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        for (int i = 1; i <= 36; i++) {
            robot.mouseMove(start.x + dx * i / 36,start.y + dy * i / 36);
            Thread.sleep(16);
        }
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }
    private static double percentile(List<Long> values,int percentile) {
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        return sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1,(int)Math.ceil(sorted.length * percentile / 100.0) - 1)] / 1_000_000.0;
    }
    private static void require(boolean condition,String text) {
        checks++;
        if (BASELINE) System.out.println((condition ? "OK " : "REPRODUCED ") + text);
        else if (!condition) throw new AssertionError(text);
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
    private static <T extends Component> List<T> descendants(Container root,Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) result.add(type.cast(child));
            if (child instanceof Container nested) result.addAll(descendants(nested,type));
        }
        return result;
    }
    private static final class Meter extends RepaintManager {
        final List<Long> paints = new CopyOnWriteArrayList<>();
        @Override public void paintDirtyRegions() {
            long start = System.nanoTime();
            super.paintDirtyRegions();
            long duration = System.nanoTime() - start;
            if (duration > 100_000) paints.add(duration);
        }
    }
}
