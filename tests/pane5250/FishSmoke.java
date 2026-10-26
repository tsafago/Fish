package pane5250;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
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

/** Bounded, noninteractive Fish checks: no Robot, credentials or visible windows. */
public final class FishSmoke {
    private static int checks;
    private static void check(boolean value,String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> task=new FutureTask<>(work);
        SwingUtilities.invokeAndWait(task);
        try { return task.get(); }
        catch (ExecutionException e) { throw new AssertionError("EDT smoke failure",e.getCause()); }
    }
    private static void await(Callable<Boolean> condition,String message) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (!condition.call() && System.nanoTime()<deadline) Thread.sleep(25);
        check(condition.call(),message);
    }
    private static List<Component> children(Container root) {
        List<Component> result=new ArrayList<>();
        for (Component child:root.getComponents()) {
            result.add(child);
            if (child instanceof Container nested) result.addAll(children(nested));
        }
        return result;
    }
    private static BufferedImage paint(JComponent view,double dpi) {
        BufferedImage image=new BufferedImage((int)Math.ceil(view.getWidth()*dpi),
                (int)Math.ceil(view.getHeight()*dpi),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try { g.scale(dpi,dpi); view.paint(g); }
        finally { g.dispose(); }
        return image;
    }
    private static Object field(Object object,String name) throws Exception {
        Field field=object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static String shape(Layout node) {
        if (node instanceof Layout.Leaf) return "L";
        Layout.Split split=(Layout.Split)node;
        return (split.horizontal() ? "H(" : "V(")+shape(split.first())+","+shape(split.second())+")";
    }
    private static void layouts() {
        String[] expected={"L","H(L,L)","H(L,V(L,L))","V(H(L,L),H(L,L))",
                "V(H(L,H(L,L)),H(L,L))","V(H(L,H(L,L)),H(L,H(L,L)))",
                "V(H(L,H(L,H(L,L))),H(L,H(L,L)))","V(H(L,H(L,H(L,L))),H(L,H(L,H(L,L))))"};
        List<Layout.Leaf> slots=new ArrayList<>();
        UUID profile=UUID.randomUUID();
        for (int i=0;i<8;i++) {
            slots.add(new Layout.Leaf(UUID.randomUUID(),profile));
            Layout tree=Layout.bounded(slots);
            check(shape(tree).equals(expected[i]),"Exact "+(i+1)+"-slot row/column topology.");
            check(tree.leaves().equals(slots),"Bounded slots preserve every pane/preset identity.");
        }
        slots.add(new Layout.Leaf(UUID.randomUUID(),profile));
        boolean rejected=false;
        try { Layout.bounded(slots); } catch (IllegalArgumentException expectedError) { rejected=true; }
        check(rejected,"A ninth slot is explicitly rejected.");
        rejected=false;
        try { Layout.bounded(List.of()); } catch (IllegalArgumentException expectedError) { rejected=true; }
        check(rejected,"Zero bounded slots are explicitly rejected.");
    }
    private static void solePane(Path directory) throws Exception {
        StateStore store=new StateStore(directory.resolve("single"));
        StateStore.State state=store.load();
        CountDownLatch closed=new CountDownLatch(1);
        Workspace single=edt(() -> {
            Workspace w=new Workspace(store,state,Path.of("scripts","credential-store.ps1"),false);
            w.onClosed(closed::countDown);
            return w;
        });
        try {
            edt(() -> {
                SessionPane only=single.active();
                single.closePane();
                check(single.sessionPanes().size()==1 && single.active()==only,"Disconnecting the sole tile cannot remove it.");
                check(only.screen()==null && !only.busy(),"The sole tile remains empty and offline.");
                return null;
            });
        } finally {
            edt(() -> { single.close(false); return null; });
            check(closed.await(5,TimeUnit.SECONDS),"A never-shown one-pane workspace closes without a modal.");
        }
    }
    private static void migration(Path directory) throws Exception {
        Path migration=directory.resolve("migration");
        Files.createDirectories(migration);
        check(Main.defaultDirectory(migration).equals(migration.resolve("Fish")),"New users use Fish storage.");
        Files.createDirectories(migration.resolve("Pane5250"));
        Files.writeString(migration.resolve("Pane5250").resolve("workspace.xml"),"legacy");
        check(Main.defaultDirectory(migration).equals(migration.resolve("Pane5250")),"Existing storage and locking are reused.");
        Profile one=Profile.create("One"),two=Profile.create("Two");
        check(two.credentialTarget().startsWith("Pane5250/"),"Existing Credential Manager namespace is retained.");
        try (StateStore store=new StateStore(migration.resolve("state"))) {
            StateStore.State state=new StateStore.State(List.of(one,two),new Layout.Leaf(UUID.randomUUID(),two.id()),
                    1100,700,AppTheme.Midnight,two.id());
            store.save(state);
            check(store.load().equals(state),"Last preset, profiles, theme and layout persist.");
            Properties xml=new Properties();
            try (var in=Files.newInputStream(store.file())) { xml.loadFromXML(in); }
            xml.setProperty("version","2"); xml.remove("lastProfile"); xml.setProperty("layout","E");
            try (var out=Files.newOutputStream(store.file())) { xml.storeToXML(out,"legacy fixture"); }
            StateStore.State restored=store.load();
            check(restored.layout().leaves().size()==1 && !store.loadNotice().isBlank(),"Legacy empty state migrates explicitly to one pane.");
            check(restored.profiles().equals(state.profiles()),"Migration retains all presets.");
            xml.remove("version");
            try (var out=Files.newOutputStream(store.file())) { xml.storeToXML(out,"invalid fixture"); }
            byte[] original=Files.readAllBytes(store.file());
            boolean rejected=false;
            try { store.load(); } catch (java.io.IOException expected) { rejected=true; }
            check(rejected && Arrays.equals(original,Files.readAllBytes(store.file())),"Corrupt state is rejected and preserved.");
            xml.setProperty("version","2");
            List<Layout.Leaf> excess=new ArrayList<>();
            for (int i=0;i<9;i++) excess.add(new Layout.Leaf(UUID.randomUUID(),one.id()));
            Layout large=excess.getFirst();
            for (int i=1;i<9;i++) large=new Layout.Split(true,.5,large,excess.get(i));
            xml.setProperty("layout",large.encode());
            try (var out=Files.newOutputStream(store.file())) { xml.storeToXML(out,"overcapacity legacy fixture"); }
            original=Files.readAllBytes(store.file()); rejected=false;
            try { store.load(); }
            catch (java.io.IOException expected) { rejected=expected.getMessage().contains("1-8"); }
            check(rejected && Arrays.equals(original,Files.readAllBytes(store.file())),"Overcapacity legacy state is explicitly rejected, never truncated.");
        }
    }
    public static void main(String[] args) throws Exception {
        Path directory=Files.createTempDirectory(Path.of("build"),"fish-smoke-");
        CountDownLatch closed=new CountDownLatch(1);
        List<Throwable> errors=new CopyOnWriteArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((thread,error) -> { errors.add(error); error.printStackTrace(); });
        DemoHost host=new DemoHost();
        StateStore store=new StateStore(directory);
        Workspace window=null;
        try {
            migration(directory);
            Ui.install();
            layouts();
            solePane(directory);
            Workspace workspace=edt(() -> {
                Workspace w=new Workspace(store,Main.demoState(host.port()),Path.of("scripts","credential-store.ps1"),true);
                w.onClosed(closed::countDown);
                w.setFocusableWindowState(false); w.addNotify(); w.validate(); w.connectAll();
                return w;
            });
            window=workspace;
            await(() -> edt(() -> workspace.sessionPanes().stream().allMatch(SessionPane::connected)),"Three independent loopback sessions connect.");
            List<SessionPane> original=edt(workspace::sessionPanes);
            edt(() -> {
                check(workspace.getTitle().startsWith("Fish") && workspace.isUndecorated(),"Fish retains custom window chrome.");
                check(workspace.getIconImages().size()>=6,"Runtime fish icons include small and large resolutions.");
                TerminalView view=original.getFirst().view();
                BufferedImage terminal=paint(view,1.5);
                ImageIO.write(terminal,"png",Path.of("build","fish-terminal-150.png").toFile());
                check(view.visibleText().contains("Main Menu"),"The screenshot contains real host data.");
                for (String metric:List.of("baseWidth","baseHeight","ascent")) {
                    double value=(double)field(view,metric);
                    check(value==Math.rint(value),"Physical "+metric+" is an integer pixel metric.");
                }
                Object cached=field(view,"rendered");
                paint(view,1.5);
                check(cached==field(view,"rendered"),"Same-size repaint reuses final-resolution glyphs.");
                check(view.getWidth()>=80*(double)field(view,"cellWidth"),"Auto-fit remains within the actual host width.");
                for (String effect:Profile.EFFECTS) {
                    workspace.putProfile(original.getFirst().profile().withDisplay("Phosphor",effect));
                    paint(view,1.5);
                }
                workspace.putProfile(original.getFirst().profile().withDisplay("Phosphor","None"));
                workspace.dock(original.getFirst().id(),original.get(1).id(),Layout.Dock.LEFT);
                check(workspace.sessionPanes().get(1)==original.getFirst(),"Even an edge drop swaps, never subdivides.");
                workspace.setSize(1200,740); workspace.validate();
                check(original.stream().allMatch(p -> p.connected() && p.screen().getColumns()==80),"Swap/resize preserve sessions and host dimensions.");
                return null;
            });
            edt(() -> { original.getFirst().send(KeyMnemonic.PF5); return null; });
            await(() -> edt(() -> original.getFirst().screen().getColumns()==132),"Host switching to 27x132 still works.");
            edt(() -> {
                original.getFirst().send(KeyMnemonic.PF6);
                return null;
            });
            await(() -> edt(() -> original.getFirst().screen().getColumns()==80),"Host switching back to 24x80 still works.");
            edt(() -> {
                UUID preset=original.get(2).profile().id();
                workspace.rememberProfile(original.get(2).profile());
                for (int n=4;n<=8;n++) {
                    workspace.split(n%2==0);
                    check(workspace.sessionPanes().size()==n,"Addition creates exactly "+n+" bounded slots.");
                    check(workspace.active().profile().id().equals(preset),"New offline pane selects the last used preset.");
                    check(original.stream().allMatch(p -> workspace.sessionPanes().contains(p) && p.connected()),"Reflow retains existing session objects/sockets.");
                    Layout tree=workspace.currentLayout();
                    check(Layout.normalize(tree).equals(tree),"Restoring "+n+" slots preserves bounded topology and dividers.");
                }
                workspace.split(true);
                check(workspace.sessionPanes().size()==8,"Direct addition is capped at eight.");
                AppCommand add=workspace.commands().stream().filter(c -> c.title.equals("Add pane")).findFirst().orElseThrow();
                check(!add.isEnabled(),"All shared Add pane controls are disabled at eight.");
                check(add.matches("add window") && add.matches("new session") && add.matches("split"),"Command aliases share bounded addition.");
                for (SessionPane pane:workspace.sessionPanes()) {
                    List<Component> controls=children(pane);
                    JButton close=controls.stream().filter(c -> c instanceof JButton b
                            && ("Disconnect pane "+pane.profile().name()).equals(b.getAccessibleContext().getAccessibleName()))
                            .map(c -> (JButton)c).findFirst().orElseThrow();
                    close.doClick(0);
                    check(!pane.busy() && pane.screen()==null && workspace.sessionPanes().size()==8,"X disconnects/clears but retains its slot.");
                    Container body=(Container)((BorderLayout)pane.getLayout()).getLayoutComponent(BorderLayout.CENTER);
                    Container offline=Arrays.stream(body.getComponents()).filter(Component::isVisible).map(c -> (Container)c).findFirst().orElseThrow();
                    List<Component> offlineControls=children(offline);
                    check(offlineControls.stream().filter(c -> c instanceof JComboBox<?>).count()==1
                            && offlineControls.stream().filter(c -> c instanceof JButton b && "Connect".equals(b.getText())).count()==1
                            && offlineControls.stream().noneMatch(c -> c instanceof JLabel),"Offline content has only the preset selector and Connect.");
                }
                check(Arrays.stream(workspace.getOwnedWindows()).noneMatch(Window::isVisible),"Disconnect never opens a confirmation.");
                check(!workspace.isVisible(),"No smoke check shows or focuses the desktop.");
                workspace.validate();
                BufferedImage offline=paint(workspace.getRootPane(),1);
                ImageIO.write(offline,"png",Path.of("build","fish-offline-eight.png").toFile());
                workspace.active().connect();
                return null;
            });
            await(() -> edt(() -> workspace.active().connected()),"A retained empty tile reconnects normally.");
            check(host.connectionCount()==4,"No resize, reflow or swap reconnects a socket.");
            edt(() -> {
                workspace.dispatchEvent(new WindowEvent(workspace,WindowEvent.WINDOW_CLOSING));
                check(!workspace.isDisplayable(),"Window close disposes immediately without confirmation.");
                return null;
            });
            check(closed.await(5,TimeUnit.SECONDS),"State save/lock release finish after immediate close.");
            try (StateStore reopened=new StateStore(directory)) {
                check(reopened.load().layout().leaves().size()==8,"Eight-slot layout persists.");
                check(reopened.load().lastProfile().equals(original.get(2).profile().id()),"Last used preset survives restart.");
            }
            check(errors.isEmpty(),"No asynchronous failures: "+errors);
            check(host.failures().isEmpty(),"No synthetic host failures: "+host.failures());
            System.out.println("PASS Fish: "+checks+" focused checks; no foreground input or credential access.");
        } finally {
            if (window!=null) {
                Workspace w=window;
                edt(() -> { w.close(true); return null; });
                closed.await(5,TimeUnit.SECONDS);
            } else store.close();
            host.close();
            try (var paths=Files.walk(directory)) {
                for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
