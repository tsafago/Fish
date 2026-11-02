package pane5250;

import java.awt.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import pane5250.model.*;
import pane5250.ui.*;

/** Bounded real-monitor regression; requires fresh shared-desktop consent. */
public final class MonitorChrome {
    private static int checks;
    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> task=new FutureTask<>(work); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void check(boolean value,String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static void darkCaption(Workspace window,Robot robot,String name) throws Exception {
        robot.waitForIdle();
        robot.delay(200); // Let the desktop compositor finish the transition being measured.
        Component drag=(Component)field(field(window,"chrome"),"dragArea");
        Rectangle area=edt(() -> {
            Point location=drag.getLocationOnScreen();
            return new Rectangle(location.x+4,location.y+4,Math.max(1,drag.getWidth()-8),Math.max(1,drag.getHeight()-8));
        });
        var image=robot.createScreenCapture(area);
        ImageIO.write(image,"png",Path.of("build","fish-monitor-strip.png").toFile());
        int matching=0;
        for (int y=0;y<image.getHeight();y++) for (int x=0;x<image.getWidth();x++) {
            Color c=new Color(image.getRGB(x,y));
            if (Math.abs(c.getRed()-Ui.PANEL.getRed())+Math.abs(c.getGreen()-Ui.PANEL.getGreen())
                    +Math.abs(c.getBlue()-Ui.PANEL.getBlue())<12) matching++;
        }
        ImageIO.write(robot.createScreenCapture(edt(window::getBounds)),"png",Path.of("build","fish-monitor-"+name+".png").toFile());
        check(matching>=image.getWidth()*image.getHeight()*.9,
                "Custom caption stays intact after "+name+" "+area+"; matching background="+matching+"/"+(image.getWidth()*image.getHeight()));
    }
    public static void main(String[] args) throws Exception {
        Ui.install();
        Robot robot=new Robot(); robot.setAutoDelay(40);
        Path directory=Files.createTempDirectory(Path.of("build"),"monitor-chrome-");
        StateStore store=new StateStore(directory); CountDownLatch closed=new CountDownLatch(1);
        Workspace window=edt(() -> {
            Workspace w=new Workspace(store,Main.demoState(23456),Path.of("scripts","credential-store.ps1"),false);
            w.onClosed(closed::countDown); w.setSize(1000,620); w.setLocationRelativeTo(null);
            w.setAlwaysOnTop(true); w.setVisible(true); return w;
        });
        try {
            robot.waitForIdle();
            darkCaption(window,robot,"initial");
            edt(() -> { window.setIconImages(FishIcon.images()); return null; });
            robot.waitForIdle();
            darkCaption(window,robot,"icon-refresh");
            int index=0;
            for (GraphicsDevice device:GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                GraphicsConfiguration display=device.getDefaultConfiguration(); Rectangle bounds=display.getBounds();
                System.out.println("Monitor "+index+": "+device.getIDstring()+" "+bounds+" scale="+display.getDefaultTransform());
                edt(() -> {
                    window.setBounds(bounds.x+40,bounds.y+60,Math.min(1000,bounds.width-80),Math.min(620,bounds.height-100));
                    return null;
                });
                robot.waitForIdle();
                darkCaption(window,robot,"move-"+index);
                edt(() -> { window.setTitle("Fish - monitor transition"); window.setIconImages(FishIcon.images()); return null; });
                robot.waitForIdle();
                darkCaption(window,robot,"title-icons-"+index);
                edt(() -> { window.setExtendedState(Frame.MAXIMIZED_BOTH); return null; });
                robot.waitForIdle();
                darkCaption(window,robot,"maximized-"+index);
                edt(() -> { window.setExtendedState(Frame.NORMAL); return null; });
                robot.waitForIdle();
                darkCaption(window,robot,"restored-"+index);
                edt(() -> { window.setExtendedState(Frame.ICONIFIED); return null; });
                robot.waitForIdle();
                edt(() -> { window.setExtendedState(Frame.NORMAL); window.toFront(); return null; });
                robot.waitForIdle();
                darkCaption(window,robot,"deiconified-"+index);
                index++;
            }
            Rectangle source=GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()[0].getDefaultConfiguration().getBounds();
            edt(() -> { window.setBounds(source.x+40,source.y+60,1000,620); return null; });
            darkCaption(window,robot,"before-physical-drag");
            Component drag=(Component)field(field(window,"chrome"),"dragArea");
            GraphicsDevice primary=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            Rectangle target=primary.getDefaultConfiguration().getBounds();
            Point from=edt(() -> {
                Point p=drag.getLocationOnScreen(); p.translate(drag.getWidth()/2,drag.getHeight()/2); return p;
            });
            Point to=new Point(target.x+target.width/2,target.y+90);
            robot.mouseMove(from.x,from.y); robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            for (int step=1;step<=20;step++) robot.mouseMove(from.x+(to.x-from.x)*step/20,from.y+(to.y-from.y)*step/20);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.waitForIdle();
            darkCaption(window,robot,"real-caption-drag");
            check(edt(() -> window.getGraphicsConfiguration().getDevice().equals(primary)),"Physical caption drag moves the HWND to the target monitor.");
            JButton logo=(JButton)field(field(window,"chrome"),"caption");
            Point logoPoint=edt(() -> {
                Point p=logo.getLocationOnScreen(); p.translate(logo.getWidth()/2,logo.getHeight()/2); return p;
            });
            edt(() -> { window.putProfile(window.active().profile().withDisplay(window.active().profile().theme(),"None")); return null; });
            robot.mouseMove(logoPoint.x,logoPoint.y); robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(700); robot.waitForIdle();
            check(edt(() -> window.active().profile().effect().equals("Prism")),"Physical logo click applies Prism to the active profile.");
            for (int i=0;i<2;i++) {
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            }
            robot.delay(700); robot.waitForIdle();
            check(edt(() -> window.active().profile().effect().equals("Prism")),"Logo double-click does not toggle effects or move/maximize.");
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseMove(logoPoint.x+14,logoPoint.y);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(700); robot.waitForIdle();
            check(edt(() -> window.active().profile().effect().equals("Prism")),"A drag beginning on the logo does not toggle effects.");
            edt(() -> { logo.requestFocusInWindow(); return null; });
            robot.keyPress(java.awt.event.KeyEvent.VK_SPACE); robot.keyRelease(java.awt.event.KeyEvent.VK_SPACE); robot.waitForIdle();
            check(edt(() -> window.active().profile().effect().equals("None")),"Keyboard Space activates the focused logo and returns None.");
            JDialog dialog=edt(() -> {
                JDialog d=new JDialog(window,"Activation check",false);
                Ui.decorate(d,d::dispose); d.setSize(340,180); d.setLocationRelativeTo(window); d.setVisible(true);
                return d;
            });
            robot.waitForIdle();
            darkCaption(window,robot,"inactive");
            edt(() -> { dialog.dispose(); return null; });
            check(edt(() -> window.sessionPanes().stream().noneMatch(SessionPane::busy)),"No IBM i connections were made.");
            System.out.println("PASS Monitor chrome: "+checks+" checks; no shared clipboard, credentials or host access.");
        } finally {
            edt(() -> { for (Window child:window.getOwnedWindows()) child.dispose(); window.close(false); return null; });
            if (!closed.await(5,TimeUnit.SECONDS)) throw new AssertionError("Workspace did not finish closing.");
            try (var paths=Files.walk(directory)) {
                for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
