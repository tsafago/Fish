package pane5250;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import pane5250.model.*;
import pane5250.ui.*;

/** Run only with fresh permission: one bounded desktop pass, no shared clipboard. */
public final class StreamliningDesktop {
    private static int checks;
    private static Robot robot;
    private static void check(boolean value,String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> task=new FutureTask<>(work); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static List<Component> children(Container parent) {
        List<Component> result=new ArrayList<>();
        for (Component child:parent.getComponents()) {
            result.add(child); if (child instanceof Container container) result.addAll(children(container));
        }
        return result;
    }
    private static void await(Callable<Boolean> condition,String message) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (!condition.call() && System.nanoTime()<end) Thread.sleep(50);
        check(condition.call(),message);
    }
    private static JDialog dialog(String title) throws Exception {
        final JDialog[] result={null};
        await(() -> edt(() -> {
            for (Window w:Window.getWindows()) if (w instanceof JDialog d && d.isShowing() && d.getTitle().equals(title)) {
                result[0]=d; return true;
            }
            return false;
        }),title+" opens");
        return result[0];
    }
    private static JButton button(Container owner,String text) throws Exception {
        return edt(() -> children(owner).stream().filter(c -> c instanceof JButton b && text.equals(b.getText()))
                .map(c -> (JButton)c).findFirst().orElseThrow());
    }
    private static void click(Component component) throws Exception {
        Point point=edt(() -> {
            Point p=component.getLocationOnScreen(); p.translate(component.getWidth()/2,component.getHeight()/2); return p;
        });
        robot.mouseMove(point.x,point.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }
    private static void keys(int... keys) {
        for (int key:keys) robot.keyPress(key);
        for (int i=keys.length-1;i>=0;i--) robot.keyRelease(keys[i]);
        robot.waitForIdle();
    }
    private static void screenshot(Workspace workspace,String file) throws Exception {
        Rectangle bounds=edt(workspace::getBounds);
        ImageIO.write(robot.createScreenCapture(bounds),"png",Path.of("build",file).toFile());
    }
    private static void darkCaption(Workspace workspace,String state) throws Exception {
        Rectangle bounds=edt(workspace::getBounds);
        Rectangle strip=new Rectangle(bounds.x+120,bounds.y+5,bounds.width-280,18);
        var image=robot.createScreenCapture(strip);
        int bright=0;
        for (int y=0;y<image.getHeight();y++) for (int x=0;x<image.getWidth();x++) {
            Color color=new Color(image.getRGB(x,y));
            if (color.getRed()+color.getGreen()+color.getBlue()>510) bright++;
        }
        check(bright<image.getWidth()*image.getHeight()/4,"Custom dark caption remains intact when "+state);
    }
    public static void main(String[] args) throws Exception {
        Ui.install(); robot=new Robot(); robot.setAutoDelay(75);
        Path directory=Files.createTempDirectory(Path.of("build"),"streamlining-desktop-");
        StateStore store=new StateStore(directory); CountDownLatch closed=new CountDownLatch(1);
        Workspace workspace=edt(() -> {
            Workspace w=new Workspace(store,Main.demoState(23456),Path.of("scripts","credential-store.ps1"),false);
            w.onClosed(closed::countDown); w.setSize(1000,680); w.setLocationRelativeTo(null);
            w.setAlwaysOnTop(true); w.setVisible(true); w.active().focusTerminal(); return w;
        });
        try {
            await(() -> edt(workspace::isFocused),"Fish receives test focus");
            Thread.sleep(250); darkCaption(workspace,"active");
            SwingUtilities.invokeLater(workspace::showShortcuts);
            JDialog shortcuts=dialog("Shortcuts - all actions");
            Thread.sleep(250); darkCaption(workspace,"inactive behind its dialog");
            JTextField find=edt(() -> children(shortcuts).stream().filter(c -> c instanceof JTextField f
                    && "Find shortcut action".equals(f.getAccessibleContext().getAccessibleName())).map(c -> (JTextField)c).findFirst().orElseThrow());
            edt(() -> { find.setText("Next pane"); return null; });
            JTable table=edt(() -> children(shortcuts).stream().filter(c -> c instanceof JTable).map(c -> (JTable)c).findFirst().orElseThrow());
            edt(() -> { table.setRowSelectionInterval(0,0); return null; });
            click(button(shortcuts,"Record shortcut"));
            JDialog record=dialog("Record shortcut");
            await(() -> edt(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextField),"Recorder receives keyboard focus");
            keys(KeyEvent.VK_CONTROL,KeyEvent.VK_TAB);
            click(button(record,"Use shortcut"));
            screenshot(workspace,"fish-shortcuts-desktop.png");
            click(button(shortcuts,"Save shortcuts"));
            await(() -> edt(() -> !shortcuts.isShowing()),"Shortcut editor saves and closes");
            check(edt(() -> workspace.shortcutOverrides().get("Next pane").contains("TAB")),"Real recorder stores Ctrl+Tab");
            SessionPane initial=edt(workspace::active);
            edt(() -> { workspace.active().focusTerminal(); return null; });
            keys(KeyEvent.VK_CONTROL,KeyEvent.VK_TAB);
            await(() -> edt(() -> workspace.active()!=initial),"Physical Ctrl+Tab changes panes without host input");
            edt(() -> { workspace.commands().stream().filter(c -> c.title.equals("Full manual / Getting Started")).findFirst().orElseThrow()
                    .actionPerformed(new ActionEvent(workspace,0,"desktop")); return null; });
            JDialog manual=dialog("Fish manual");
            keys(KeyEvent.VK_CONTROL,KeyEvent.VK_F);
            check(edt(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextField),"Manual Ctrl+F focuses full-text search");
            edt(() -> { manual.dispose(); workspace.toFront(); workspace.active().focusTerminal(); return null; });
            GraphicsConfiguration display=edt(workspace::getGraphicsConfiguration);
            Rectangle work=new Rectangle(display.getBounds()); Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(display);
            work.x+=insets.left; work.y+=insets.top; work.width-=insets.left+insets.right; work.height-=insets.top+insets.bottom;
            keys(KeyEvent.VK_WINDOWS,KeyEvent.VK_LEFT);
            Thread.sleep(650); keys(KeyEvent.VK_ESCAPE);
            Rectangle side=edt(workspace::getBounds);
            check(side.width<=work.width*.6 && side.height>=work.height*.8,"Win+Left performs native half-screen snap: "+side);
            screenshot(workspace,"fish-native-side-snap.png");
            keys(KeyEvent.VK_WINDOWS,KeyEvent.VK_UP);
            Thread.sleep(650); keys(KeyEvent.VK_ESCAPE);
            Rectangle quarter=edt(workspace::getBounds);
            check(quarter.width<=work.width*.6 && quarter.height<=work.height*.65,"Win+Up after side snap performs native quarter-screen snap: "+quarter);
            screenshot(workspace,"fish-native-quarter-snap.png");
            edt(() -> { workspace.commands().stream().filter(c -> c.title.equals("Maximize / restore window")).findFirst().orElseThrow()
                    .actionPerformed(new ActionEvent(workspace,0,"desktop")); return null; });
            await(() -> edt(() -> (workspace.getExtendedState()&Frame.MAXIMIZED_BOTH)!=0),"Native maximize command works");
            screenshot(workspace,"fish-native-maximized.png");
            edt(() -> { workspace.commands().stream().filter(c -> c.title.equals("Maximize / restore window")).findFirst().orElseThrow()
                    .actionPerformed(new ActionEvent(workspace,0,"desktop")); return null; });
            await(() -> edt(() -> (workspace.getExtendedState()&Frame.MAXIMIZED_BOTH)==0),"Native restore command works");
            edt(() -> { workspace.setBounds(work.x+100,work.y+60,1000,680); workspace.toFront(); return null; });
            Component drag=edt(() -> children(workspace).stream().filter(c -> "window-drag-area".equals(c.getName())).findFirst().orElseThrow());
            Point start=edt(() -> { Point p=drag.getLocationOnScreen(); p.translate(drag.getWidth()/2,drag.getHeight()/2); return p; });
            robot.mouseMove(start.x,start.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            for (int i=1;i<=16;i++) robot.mouseMove(start.x+(work.x+work.width/2-start.x)*i/16,start.y+(work.y+1-start.y)*i/16);
            Thread.sleep(450); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            await(() -> edt(() -> (workspace.getExtendedState()&Frame.MAXIMIZED_BOTH)!=0),"Dragging native caption to the top maximizes");
            edt(() -> { workspace.setAlwaysOnTop(false); workspace.toFront(); workspace.requestFocus(); return null; });
            keys(KeyEvent.VK_WINDOWS,KeyEvent.VK_Z); Thread.sleep(900);
            screenshot(workspace,"fish-snap-layouts-desktop.png"); keys(KeyEvent.VK_ESCAPE);
            check(edt(() -> workspace.getOwnedWindows().length>=2 && workspace.sessionPanes().stream().noneMatch(SessionPane::busy)),
                    "Desktop UI checks neither connect nor send data to a host");
            System.out.println("PASS Streamlining desktop: "+checks+" bounded checks; no shared clipboard or credentials.");
        } finally {
            edt(() -> {
                for (Window child:workspace.getOwnedWindows()) child.dispose();
                workspace.close(false); return null;
            });
            closed.await(5,TimeUnit.SECONDS);
            try (var paths=Files.walk(directory)) {
                for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
