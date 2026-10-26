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
import org.tn5250j.keyboard.KeyMnemonic;
import pane5250.demo.DemoHost;
import pane5250.model.*;
import pane5250.ui.*;

/** Foreground interaction coverage. Run only with an idle, consented desktop. */
public final class UiRevisions {
    private static int checks;
    private UiRevisions() {}
    public static void main(String[] args) throws Exception {
        Ui.install();
        Path directory=Files.createTempDirectory(Path.of("build"),"revision-test-");
        DemoHost host=new DemoHost();
        StateStore store=new StateStore(directory);
        List<Throwable> errors=new CopyOnWriteArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((thread,error) -> { errors.add(error); error.printStackTrace(); });
        Workspace window=edt(() -> {
            Workspace w=new Workspace(store,Main.demoState(host.port()),Path.of("scripts","credential-store.ps1"),true);
            Rectangle screen=w.getGraphicsConfiguration().getBounds();
            w.setBounds(screen.x+30,screen.y+30,Math.min(1100,screen.width-60),Math.min(740,screen.height-80));
            w.setAlwaysOnTop(true); w.setVisible(true); w.connectAll(); return w;
        });
        try {
            await(() -> window.sessionPanes().stream().allMatch(SessionPane::connected),"Three named-device sessions connect.");
            Robot robot=new Robot(); robot.setAutoDelay(15);
            List<SessionPane> original=edt(window::sessionPanes);
            SessionPane target=original.getFirst();
            edt(() -> { window.activate(target); target.focusTerminal(); return null; });
            robot.waitForIdle();
            paneSurfaces(window);
            palette(window,robot,target);
            recovery(window,robot,host,target);
            themes(window,robot,target);
            dialogs(window,robot);
            paneActions(window,robot,host,original);
            require(edt(() -> !window.active().recoverableInputError()),"Disconnected/empty workspaces do not expose false recovery.");
            edt(() -> { window.applyTheme(AppTheme.Midnight); window.close(true); return null; });
            await(() -> !window.isDisplayable(),"Revision workspace closes and saves.");
            try (StateStore reopened=new StateStore(directory)) {
                StateStore.State saved=reopened.load();
                require(saved.appTheme()==AppTheme.Midnight && saved.layout().leaves().size()==1,
                        "Application theme and newly added last pane persist on actual close/reopen.");
                require(saved.profiles().stream().anyMatch(p -> p.effect().equals("Neon")),
                        "Opt-in text effect persists with its workstation profile.");
            }
            require(host.failures().isEmpty(),"No loopback negotiation/record failures: "+host.failures());
            require(errors.isEmpty(),"No uncaught UI/background errors: "+errors);
            System.out.println("PASS "+checks+" revised pane, recovery, theme, effect, palette and dialog checks.");
        } finally {
            edt(() -> {
                for (Window child : window.getOwnedWindows()) child.dispose();
                window.close(true); return null;
            });
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (window.isDisplayable() && System.nanoTime()<end) Thread.sleep(25);
            host.close();
            Files.deleteIfExists(directory.resolve("workspace.xml"));
            Files.deleteIfExists(directory.resolve("workspace.xml.tmp"));
            Files.deleteIfExists(directory.resolve("workspace.lock"));
            Files.delete(directory);
        }
    }
    private static void paneSurfaces(Workspace window) throws Exception {
        edt(() -> {
            for (SessionPane pane : window.sessionPanes()) {
                BorderLayout layout=(BorderLayout)pane.getLayout();
                JComponent header=(JComponent)layout.getLayoutComponent(BorderLayout.NORTH);
                JComponent footer=(JComponent)layout.getLayoutComponent(BorderLayout.SOUTH);
                Color surface=Ui.paneBackground(pane==window.active());
                require(header.getBackground().equals(surface) && footer.getBackground().equals(surface)
                                && pane.view().getBackground().equals(surface),"Header, terminal and footer share one coherent pane surface.");
                require(header.getBorder().getBorderInsets(header).bottom==0,"No separate underline beneath the pane header.");
                JButton close=button(pane,"Close pane "+pane.profile().name());
                Point location=SwingUtilities.convertPoint(close,0,0,header);
                require(location.x+close.getWidth()>=header.getWidth()-1,"Close X is at the pane header's far right.");
                require(close.isFocusable() && close.getToolTipText().contains("disconnect"),"Pane close is keyboard-accessible and explained.");
            }
            require(button(window,"Add pane").isShowing() && button(window,"Search commands").isShowing(),
                    "Compact add-pane and Commands controls are immediately visible.");
            return null;
        });
    }
    private static void palette(Workspace window,Robot robot,SessionPane target) throws Exception {
        char[] before=edt(() -> target.screen().getScreenAsChars().clone());
        click(robot,edt(() -> button(window,"Search commands")));
        JDialog dialog=dialog(window,"Commands");
        JTextField search=edt(() -> children(dialog,JTextField.class).getFirst());
        await(search::hasFocus,"Command search receives focus, not the terminal.");
        type(robot,"add window");
        JList<?> list=edt(() -> children(dialog,JList.class).getFirst());
        require(edt(() -> list.getModel().getSize()==2),"Add window alias finds both split orientations.");
        tap(robot,KeyEvent.VK_DOWN);
        require(edt(() -> list.getSelectedIndex()==1),"Down chooses the next command.");
        tap(robot,KeyEvent.VK_UP);
        require(edt(() -> list.getSelectedIndex()==0),"Up chooses the previous command.");
        capture(robot,dialog,"commands");
        edt(() -> { search.setText("no-such-action-123"); return null; });
        require(edt(() -> list.getModel().getSize()==0 && children(dialog,JLabel.class).stream()
                .anyMatch(l -> l.getText().startsWith("No matching commands"))),"Search has a meaningful empty state.");
        tap(robot,KeyEvent.VK_ENTER);
        require(edt(dialog::isShowing),"Enter with no results does not run an unrelated action.");
        tap(robot,KeyEvent.VK_ESCAPE);
        await(target.view()::hasFocus,"Escape restores the intended terminal's focus.");
        require(edt(() -> Arrays.equals(before,target.screen().getScreenAsChars())),"Palette typing/navigation/Enter/Escape never reaches host fields.");

        shortcut(robot,KeyEvent.VK_K);
        JDialog execution=dialog(window,"Commands");
        JTextField query=edt(() -> children(execution,JTextField.class).getFirst());
        edt(() -> { query.setText("add window"); return null; });
        tap(robot,KeyEvent.VK_DOWN); tap(robot,KeyEvent.VK_ENTER);
        await(() -> window.sessionPanes().size()==4,"Palette Enter invokes the shared split action.");
        require(edt(() -> !window.active().busy() && window.active()!=target && window.active().view().hasFocus()),
                "Adding creates and focuses an independent offline pane without an implicit connection.");
        SessionPane added=edt(window::active);
        shortcut(robot,KeyEvent.VK_K);
        JDialog disabled=dialog(window,"Commands");
        edt(() -> { children(disabled,JTextField.class).getFirst().setText("Host F1"); return null; });
        require(edt(() -> !((AppCommand)children(disabled,JList.class).getFirst().getSelectedValue()).isEnabled()),
                "Unavailable host actions are disabled for an offline pane.");
        tap(robot,KeyEvent.VK_ENTER);
        require(edt(disabled::isShowing),"Disabled command cannot execute from the palette.");
        tap(robot,KeyEvent.VK_ESCAPE);
        click(robot,edt(() -> button(added,"Close pane "+added.profile().name())));
        await(() -> window.sessionPanes().size()==3,"Offline close X removes only the newly added pane.");
        click(robot,edt(() -> button(window,"Choose split direction")));
        await(() -> Arrays.stream(MenuSelectionManager.defaultManager().getSelectedPath()).anyMatch(e -> e instanceof JPopupMenu),
                "Immediate split-direction dropdown opens.");
        tap(robot,KeyEvent.VK_DOWN); tap(robot,KeyEvent.VK_DOWN); tap(robot,KeyEvent.VK_ENTER);
        await(() -> window.sessionPanes().size()==4,"Split dropdown runs the shared add-pane action.");
        SessionPane viaDropdown=edt(window::active);
        require(edt(() -> viaDropdown.getParent() instanceof JSplitPane split
                        && split.getOrientation()==JSplitPane.VERTICAL_SPLIT),
                "Top/bottom dropdown selection creates the expected split orientation.");
        click(robot,edt(() -> button(viaDropdown,"Close pane "+viaDropdown.profile().name())));
        await(() -> window.sessionPanes().size()==3,"Dropdown-created offline pane closes without affecting existing sessions.");
    }
    private static void recovery(Workspace window,Robot robot,DemoHost host,SessionPane pane) throws Exception {
        int requests=host.requestCount();
        edt(() -> {
            window.activate(pane); pane.focusTerminal();
            pane.screen().setCursor(1,3);
            require(!pane.type('X') && pane.recoverableInputError(),"Protected-field validation enters the actual recoverable pre-help state.");
            require(button(pane,"Reset input error").isShowing(),"Recoverable input error exposes the contextual Reset button.");
            return null;
        });
        capture(robot,window,"input-recovery");
        click(robot,edt(() -> button(pane,"Reset input error")));
        require(edt(() -> !pane.screen().getOIA().isKeyBoardLocked() && !button(pane,"Reset input error").isVisible()),
                "Contextual Reset clears the local error and disappears.");
        require(host.requestCount()==requests,"Reset never submits Enter or another transaction.");
        edt(() -> {
            pane.screen().moveCursor(pane.screen().getScreenFields().getField(0).startPos());
            pane.send(KeyMnemonic.ENTER);
            require(pane.screen().getOIA().isKeyBoardLocked() && !pane.recoverableInputError()
                            && !button(pane,"Reset input error").isVisible(),"Host-busy inhibition never offers false Reset recovery.");
            pane.send(KeyMnemonic.RESET);
            require(pane.screen().getOIA().isKeyBoardLocked(),"Reset cannot unlock actual host-controlled inhibition.");
            return null;
        });
        await(() -> !pane.screen().getOIA().isKeyBoardLocked(),"Actual host reply, not Reset, releases host busy.");
        require(host.requestCount()==requests+1,"Only the explicitly requested Enter reached the host.");
    }
    private static void themes(Workspace window,Robot robot,SessionPane pane) throws Exception {
        int[] dimensions=edt(() -> window.sessionPanes().stream().mapToInt(p -> p.screen().getScreenLength()).toArray());
        for (AppTheme theme : AppTheme.values()) {
            edt(() -> { window.applyTheme(theme); return null; });
            robot.waitForIdle();
            paneSurfaces(window);
            require(edt(() -> children(window,JSplitPane.class).stream().allMatch(s -> s.getUI() instanceof FlatControls.Split)),
                    "Live "+theme+" refresh preserves flat, draggable split dividers.");
            capture(robot,window,"theme-"+theme.name().toLowerCase(Locale.ROOT));
        }
        edt(() -> { window.applyTheme(AppTheme.Charcoal); return null; });
        Map<String,BufferedImage> images=new LinkedHashMap<>();
        for (String effect : Profile.EFFECTS) {
            BufferedImage image=edt(() -> {
                window.putProfile(pane.profile().withDisplay("Phosphor",effect));
                return render(pane.view());
            });
            images.put(effect,image);
            if (!effect.equals("None"))
                require(difference(images.get("None"),image)>100,"Effect "+effect+" is visibly different at ordinary pane size.");
            edt(() -> {
                pane.view().dispatchEvent(new KeyEvent(pane.view(),KeyEvent.KEY_PRESSED,System.currentTimeMillis(),
                        InputEvent.SHIFT_DOWN_MASK,KeyEvent.VK_RIGHT,KeyEvent.CHAR_UNDEFINED));
                require(difference(image,render(pane.view()))>5,"Selection updates the cached "+effect+" rendering.");
                pane.view().resetSelection();
                require(difference(image,render(pane.view()))<20,"Clearing selection restores "+effect+" ink/background.");
                return null;
            });
        }
        edt(() -> { window.putProfile(pane.profile().withDisplay("Orchid","Neon")); return null; });
        capture(robot,window,"neon-orchid");
        require(edt(() -> Arrays.equals(dimensions,window.sessionPanes().stream().mapToInt(p -> p.screen().getScreenLength()).toArray())
                && window.sessionPanes().stream().allMatch(SessionPane::connected)),
                "Application themes, host palettes and effects preserve connections and host dimensions.");
    }
    private static void dialogs(Workspace window,Robot robot) throws Exception {
        SwingUtilities.invokeLater(window::profiles);
        JDialog profiles=dialog(window,"Workstation profiles");
        require(edt(profiles::isUndecorated),"Profiles use shared owned dark chrome, not a native caption.");
        capture(robot,profiles,"profiles");
        JTabbedPane tabs=edt(() -> children(profiles,JTabbedPane.class).getFirst());
        edt(() -> { tabs.setSelectedIndex(2); return null; });
        capture(robot,profiles,"key-mapping");
        tap(robot,KeyEvent.VK_ESCAPE);
        SwingUtilities.invokeLater(window::appearance);
        JDialog appearance=dialog(window,"Appearance");
        require(edt(appearance::isUndecorated),"Appearance uses the same modern dialog surface.");
        capture(robot,appearance,"appearance");
        JComboBox<?> choices=edt(() -> children(appearance,JComboBox.class).getFirst());
        click(robot,choices);
        capture(robot,appearance,"appearance-choices");
        tap(robot,KeyEvent.VK_ESCAPE);
        edt(() -> { choices.setSelectedItem(AppTheme.Paper); return null; });
        require(edt(() -> Ui.theme()==AppTheme.Paper && children(appearance,JLabel.class).stream()
                .allMatch(label -> label.getForeground().equals(Ui.TEXT))),
                "Changing application theme refreshes the still-open appearance dialog.");
        capture(robot,appearance,"appearance-paper");
        edt(() -> { choices.setSelectedItem(AppTheme.Charcoal); return null; });
        tap(robot,KeyEvent.VK_ESCAPE);
        for (String route : List.of("Dock / rearrange...","Switch profile...","Send host key...","Keyboard reference")) {
            SwingUtilities.invokeLater(() -> window.commands().stream().filter(c -> c.title.equals(route)).findFirst().orElseThrow().actionPerformed(null));
            JDialog form=showingDialog(window);
            require(edt(form::isUndecorated),"Owned "+route+" dialog has consistent custom chrome.");
            capture(robot,form,"dialog-"+route.split(" ")[0].toLowerCase(Locale.ROOT));
            tap(robot,KeyEvent.VK_ESCAPE);
        }
        SwingUtilities.invokeLater(() -> Ui.form(window,"Sign on fixture",new JPasswordField(24),"Connect"));
        JDialog password=dialog(window,"Sign on fixture");
        await(() -> children(password,JPasswordField.class).getFirst().hasFocus(),"Password prompt focuses the secret field.");
        require(edt(password::isUndecorated),"Password prompt uses shared custom chrome.");
        tap(robot,KeyEvent.VK_ESCAPE);
        SwingUtilities.invokeLater(() -> window.active().send(KeyMnemonic.SYSREQ));
        JDialog systemRequest=showingDialog(window);
        require(edt(systemRequest::isUndecorated),"Actual core System Request route uses the same themed prompt.");
        tap(robot,KeyEvent.VK_ESCAPE);
        SwingUtilities.invokeLater(() -> Ui.error(window,"A synthetic connection error with readable details."));
        JDialog error=dialog(window,"Pane5250");
        require(edt(error::isUndecorated),"Connection errors use the same readable, icon-free dialog system.");
        capture(robot,error,"error");
        tap(robot,KeyEvent.VK_ESCAPE);
    }
    private static void paneActions(Workspace window,Robot robot,DemoHost host,List<SessionPane> original) throws Exception {
        SessionPane target=original.get(1);
        click(robot,edt(() -> button(target,"Close pane "+target.profile().name())));
        JDialog confirm=dialog(window,"Close pane");
        require(edt(confirm::isUndecorated),"Transaction-loss confirmation has no native titlebar or legacy icon.");
        capture(robot,confirm,"close-confirmation");
        click(robot,edt(() -> textButton(confirm,"Cancel")));
        require(edt(() -> window.sessionPanes().equals(original) && original.stream().allMatch(SessionPane::connected)),
                "Cancel preserves every pane and independent socket.");
        click(robot,edt(() -> button(target,"Close pane "+target.profile().name())));
        JDialog accepted=dialog(window,"Close pane");
        click(robot,edt(() -> textButton(accepted,"OK")));
        await(() -> window.sessionPanes().size()==2 && !target.busy(),"Close X disconnects only its own pane.");
        require(edt(() -> original.get(0).connected() && original.get(2).connected()),"Other pane sockets remain connected.");
        await(() -> host.activeConnections()==2,"The closed pane's actual TCP socket is released.");
        for (SessionPane pane : edt(window::sessionPanes)) {
            edt(() -> { pane.disconnect(); return null; });
            click(robot,edt(() -> button(pane,"Close pane "+pane.profile().name())));
        }
        require(edt(() -> window.currentLayout() instanceof Layout.Empty && window.active()==null),"The last X leaves a valid empty workspace.");
        click(robot,edt(() -> button(window,"Add pane")));
        await(() -> window.sessionPanes().size()==1 && window.active().view().hasFocus(),"Visible + adds/focuses the first pane from an empty workspace.");
        require(edt(() -> !window.active().busy()),"Add never silently opens a new socket.");
    }
    private static JDialog dialog(Workspace window,String title) throws Exception {
        await(() -> Arrays.stream(window.getOwnedWindows()).anyMatch(w -> w instanceof JDialog d && d.isShowing() && d.getTitle().equals(title)),
                "Dialog opens: "+title);
        return edt(() -> (JDialog)Arrays.stream(window.getOwnedWindows()).filter(w -> w.isShowing() && w instanceof JDialog d && d.getTitle().equals(title)).findFirst().orElseThrow());
    }
    private static JDialog showingDialog(Workspace window) throws Exception {
        await(() -> Arrays.stream(window.getOwnedWindows()).anyMatch(Window::isShowing),"Owned dialog opens.");
        return edt(() -> (JDialog)Arrays.stream(window.getOwnedWindows()).filter(Window::isShowing).findFirst().orElseThrow());
    }
    private static JButton button(Container root,String accessible) {
        return children(root,JButton.class).stream().filter(b -> accessible.equals(b.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
    }
    private static JButton textButton(Container root,String text) {
        return children(root,JButton.class).stream().filter(b -> text.equals(b.getText())).findFirst().orElseThrow();
    }
    private static <T extends Component> List<T> children(Container root,Class<T> type) {
        List<T> found=new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container nested) found.addAll(children(nested,type));
        }
        return found;
    }
    private static void click(Robot robot,Component component) throws Exception {
        await(component::isShowing,"Click target is visible: "+component.getAccessibleContext().getAccessibleName());
        Point point=edt(() -> { Point p=component.getLocationOnScreen(); p.translate(component.getWidth()/2,component.getHeight()/2); return p; });
        robot.mouseMove(point.x,point.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.waitForIdle();
    }
    private static void tap(Robot robot,int key) { robot.keyPress(key); robot.keyRelease(key); robot.waitForIdle(); }
    private static void shortcut(Robot robot,int key) {
        robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_ALT); tap(robot,key);
        robot.keyRelease(KeyEvent.VK_ALT); robot.keyRelease(KeyEvent.VK_CONTROL); robot.waitForIdle();
    }
    private static void type(Robot robot,String text) {
        for (char c : text.toCharArray()) tap(robot,c==' ' ? KeyEvent.VK_SPACE : KeyEvent.VK_A+c-'a');
    }
    private static void capture(Robot robot,Window window,String name) throws Exception {
        robot.waitForIdle();
        BufferedImage image=robot.createScreenCapture(edt(window::getBounds));
        ImageIO.write(image,"png",Path.of("build","revision-"+name+".png").toFile());
    }
    private static BufferedImage render(JComponent component) {
        BufferedImage image=new BufferedImage(component.getWidth(),component.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); component.paint(g); g.dispose(); return image;
    }
    private static int difference(BufferedImage a,BufferedImage b) {
        int count=0;
        for (int y=0;y<a.getHeight();y++) for (int x=0;x<a.getWidth();x++) if (a.getRGB(x,y)!=b.getRGB(x,y)) count++;
        return count;
    }
    private static void await(Callable<Boolean> condition,String message) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (!edt(condition)) {
            if (System.nanoTime()>until) throw new AssertionError(message);
            Thread.sleep(25);
        }
        checks++;
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task=new FutureTask<>(action); SwingUtilities.invokeAndWait(task);
        try { return task.get(); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw (Error)e.getCause();
        }
    }
    private static void require(boolean condition,String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
