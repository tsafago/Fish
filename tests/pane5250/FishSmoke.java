package pane5250;

import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.tn5250j.keyboard.KeyMnemonic;
import org.tn5250j.Session5250;
import pane5250.demo.DemoHost;
import pane5250.demo.DemoScreens;
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
    private static void setField(Object object,String name,Object value) throws Exception {
        Field field=object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object,value);
    }
    private static void clipboard(TerminalView view,boolean copy,Clipboard clipboard) throws Exception {
        var transfer=TerminalView.class.getDeclaredMethod("clipboard",boolean.class,java.util.function.Supplier.class);
        transfer.setAccessible(true);
        transfer.invoke(view,copy,(java.util.function.Supplier<Clipboard>)() -> clipboard);
    }
    private static byte[] inputScreen(int ffw) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        DemoScreens.command(out,4,0x40,4,0x11,0,8);
        DemoScreens.at(out,1,1,0x22,"HELLO");
        for (int row=2;row<=4;row++) DemoScreens.field(out,row,1,8,row==2 ? ffw : 0x40,0x24);
        DemoScreens.command(out,4,0x42,0,0);
        return DemoScreens.gds(3,out.toByteArray());
    }
    private static byte[] attributesScreen() {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        DemoScreens.command(out,4,0x40,4,0x11,0,8);
        DemoScreens.at(out,1,1,0x27,"TOPSECRET");
        DemoScreens.at(out,2,1,0x21,"REVERSE");
        DemoScreens.at(out,3,1,0x2a,"BLINK");
        DemoScreens.field(out,4,1,8,0x40,0x27);
        DemoScreens.command(out,4,0x42,0,0);
        return DemoScreens.gds(3,out.toByteArray());
    }
    private static void clipboardChecks(Workspace workspace,SessionPane pane,DemoHost host) throws Exception {
        TerminalView view=pane.view();
        Session5250 core=(Session5250)field(pane,"core");
        Clipboard clipboard=new Clipboard("Synthetic Fish fixture");
        int transactions=host.requestCount();
        core.accept(inputScreen(0x40));
        clipboard.setContents(new StringSelection("AB[]"),null);
        clipboard(view,false,clipboard);
        check(new String(pane.screen().getScreenAsChars(),81,4).equals("AB[]"),"Clipboard paste edits the actual host input field, including literal brackets.");
        setField(view,"anchor",81); setField(view,"end",84);
        clipboard(view,true,clipboard);
        check(clipboard.getData(DataFlavor.stringFlavor).equals("AB[]"),"Clipboard copy exports precisely the rectangular selection.");
        view.resetSelection();
        core.accept(inputScreen(0x40));
        clipboard.setContents(new StringSelection("A\tB\nC"),null);
        clipboard(view,false,clipboard);
        check(host.requestCount()==transactions,"Clipboard newlines/tabs never submit Enter or a host transaction.");
        core.accept(inputScreen(0x43));
        clipboard.setContents(new StringSelection("4X5"),null);
        clipboard(view,false,clipboard);
        check(pane.screen().getScreenAsChars()[81]=='4' && pane.screen().getScreenAsChars()[82]!='X'
                && pane.screen().getOIA().isKeyBoardLocked(),"Numeric rejection stops clipboard input without bypassing inhibition.");
        JPopupMenu inhibited=workspace.clipboardMenu(pane);
        check(inhibited.getComponentCount()>=2 && !((JMenuItem)inhibited.getComponent(1)).isEnabled(),"Context menu disables Paste when input is inhibited.");
        core.accept(inputScreen(0x40));
        pane.screen().moveCursor(2);
        clipboard.setContents(new StringSelection("Z"),null);
        clipboard(view,false,clipboard);
        check(new String(pane.screen().getScreenAsChars(),1,5).equals("HELLO"),"Clipboard paste cannot overwrite protected host text.");
        core.accept(inputScreen(0x40));
        clipboard.setContents(new StringSelection("A\u6f22B"),null);
        char[] before=pane.screen().getScreenAsChars().clone();
        clipboard(view,false,clipboard);
        check(Arrays.equals(before,pane.screen().getScreenAsChars()),"Unrepresentable clipboard text is rejected before editing a prefix.");
        core.accept(DemoScreens.gds(3,new byte[]{4,0x11,0x20,0}));
        clipboard.setContents(new StringSelection("NO"),null);
        clipboard(view,false,clipboard);
        check(pane.screen().getOIA().isKeyBoardLocked() && Arrays.equals(before,pane.screen().getScreenAsChars()),
                "Clipboard paste cannot unlock or edit a host-inhibited screen.");
        core.accept(attributesScreen());
        clipboard.setContents(new StringSelection("PWD"),null);
        clipboard(view,false,clipboard);
        clipboard(view,true,clipboard);
        String safe=(String)clipboard.getData(DataFlavor.stringFlavor);
        check(!safe.contains("TOPSECRET") && !safe.contains("PWD") && safe.contains("REVERSE"),"Clipboard export omits both host-hidden text and typed password fields.");
        clipboard(view,false,new Clipboard("Empty fixture"));
        check(((String)field(pane,"notice")).contains("does not contain text"),"A clipboard without text produces an explicit message.");
        clipboard(view,true,new Clipboard("Busy fixture") {
            @Override public synchronized void setContents(Transferable contents,ClipboardOwner owner) {
                throw new IllegalStateException("Synthetic clipboard busy");
            }
        });
        check(((String)field(pane,"notice")).contains("Synthetic clipboard busy"),"Clipboard copy failures are reported, not thrown on the EDT.");
        core.accept(DemoScreens.screen(1,false,""));
        JPopupMenu context=workspace.clipboardMenu(pane);
        check(((JMenuItem)context.getComponent(0)).getAction()==workspace.commands().stream()
                .filter(c -> c.matches("copy clipboard")).findFirst().orElseThrow(),"Context menu reuses the shared clipboard command.");
        check(workspace.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("ctrl C"))==null
                && workspace.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("ctrl V"))==null,
                "Clipboard additions do not steal Ctrl+C/V from host mappings.");
    }
    private static int differences(BufferedImage a,BufferedImage b,int top,int bottom) {
        int count=0;
        for (int y=Math.max(0,top);y<Math.min(bottom,a.getHeight());y++)
            for (int x=0;x<a.getWidth();x++) if (a.getRGB(x,y)!=b.getRGB(x,y)) count++;
        return count;
    }
    private static double luminance(Color color) {
        int[] channels={color.getRed(),color.getGreen(),color.getBlue()};
        double[] weights={.2126,.7152,.0722};
        double result=0;
        for (int i=0;i<3;i++) {
            double value=channels[i]/255.0;
            result+=weights[i]*(value<=.04045 ? value/12.92 : Math.pow((value+.055)/1.055,2.4));
        }
        return result;
    }
    private static double contrast(Color a,Color b) {
        double x=luminance(a),y=luminance(b);
        return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);
    }
    private static void fluxChecks(Workspace workspace,SessionPane pane) throws Exception {
        TerminalView view=pane.view();
        workspace.commands().stream().filter(c -> c.title.equals("Flux animated text")).findFirst().orElseThrow()
                .actionPerformed(new ActionEvent(workspace,ActionEvent.ACTION_PERFORMED,"fixture"));
        check(pane.profile().effect().equals(Profile.FLUX),"Flux is immediately available through the View menu action and persistent effect list.");
        setField(view,"fluxPhase",0.0);
        BufferedImage first=paint(view,1.5);
        Object glyphs=field(view,"rendered");
        setField(view,"fluxPhase",.35);
        BufferedImage second=paint(view,1.5);
        check(differences(first,second,0,first.getHeight())>100,"Two deterministic Flux phases visibly change terminal text.");
        check(glyphs==field(view,"rendered"),"Animation reuses the physical-size glyph vectors instead of allocating per-cell geometry.");
        ImageIO.write(first,"png",Path.of("build","fish-flux-a.png").toFile());
        ImageIO.write(second,"png",Path.of("build","fish-flux-b.png").toFile());
        Field clock=TerminalView.class.getDeclaredField("ANIMATION"); clock.setAccessible(true);
        javax.swing.Timer timer=(javax.swing.Timer)clock.get(null);
        check(timer.getDelay()==50 && timer.isCoalesce() && !timer.isRunning(),"One coalescing 20fps clock is suspended for hidden terminal surfaces.");
        Session5250 core=(Session5250)field(pane,"core");
        core.accept(attributesScreen());
        setField(view,"phase",true); setField(view,"fluxPhase",0.0);
        BufferedImage attributesA=paint(view,1);
        setField(view,"fluxPhase",.35);
        BufferedImage attributesB=paint(view,1);
        int y=(int)Math.round((double)field(view,"originY")),height=(int)(double)field(view,"cellHeight");
        check(differences(attributesA,attributesB,y+height,y+height*2)==0,"Flux preserves reverse-video foreground/background colors exactly.");
        boolean hidden=true;
        for (int row=y;row<y+height;row++) for (int x=0;x<attributesB.getWidth();x++)
            hidden&=attributesB.getRGB(x,row)==view.getBackground().getRGB();
        check(hidden,"Flux never draws non-display host data.");
        setField(view,"phase",false);
        BufferedImage blinkOff=paint(view,1);
        check(differences(attributesB,blinkOff,y+height*2,y+height*3)>5,"Host blink still switches glyph visibility under Flux.");
        core.accept(DemoScreens.screen(1,false,""));
        AppTheme originalTheme=Ui.theme();
        for (AppTheme theme:AppTheme.values()) {
            workspace.applyTheme(theme); paint(view,1);
            boolean readable=true;
            for (Paint paint:(Paint[])field(view,"fluxPaints")) if (paint instanceof LinearGradientPaint gradient) {
                for (Color color:gradient.getColors())
                    readable&=contrast(color,view.getBackground())>=4.5 && contrast(color,Ui.SELECTION)>=4.5;
            }
            check(readable,"Flux gradient stops preserve text/selection contrast in "+theme+".");
        }
        workspace.applyTheme(originalTheme);
        workspace.putProfile(pane.profile().withDisplay("Phosphor","None"));
        setField(view,"fluxPhase",0.0); BufferedImage still=paint(view,1);
        setField(view,"fluxPhase",.7);
        check(differences(still,paint(view,1),0,still.getHeight())==0,"Default static rendering does not animate or change users' defaults.");
        workspace.activate(pane);
        Object cached=field(view,"rendered");
        workspace.toggleFlux();
        check(pane.profile().effect().equals(Profile.FLUX) && field(view,"rendered")==cached,
                "Logo effect path updates coloration while retaining the same glyph cache.");
        setField(view,"fluxPhase",0.0);
        BufferedImage animated=paint(view,1);
        check(differences(still,animated,0,still.getHeight())>100,"Logo animated Flux toggle visibly changes terminal ink, not just icon styling.");
        setField(view,"fluxPhase",.35);
        check(differences(animated,paint(view,1),0,still.getHeight())>100,"Logo selects the genuinely animated effect, not static Prism.");
        workspace.toggleFlux();
        check(pane.profile().effect().equals("None") && differences(still,paint(view,1),0,still.getHeight())==0,
                "Second toggle restores the exact crisp no-effect rendering.");
    }
    private static void iconChecks() throws Exception {
        Field outline=FishIcon.class.getDeclaredField("OUTLINE"); outline.setAccessible(true);
        Shape shape=(Shape)outline.get(null);
        List<Point2D> points=new ArrayList<>();
        List<Line2D> lines=new ArrayList<>();
        Point2D start=null,previous=null;
        int paths=0,closed=0;
        double[] xy=new double[6];
        for (PathIterator path=shape.getPathIterator(null);!path.isDone();path.next()) {
            int kind=path.currentSegment(xy);
            Point2D point=new Point2D.Double(xy[0],xy[1]);
            if (kind==PathIterator.SEG_MOVETO) { start=previous=point; paths++; }
            else if (kind==PathIterator.SEG_LINETO) { lines.add(new Line2D.Double(previous,point)); previous=point; }
            else if (kind==PathIterator.SEG_CLOSE) { lines.add(new Line2D.Double(previous,start)); closed++; }
            if (kind!=PathIterator.SEG_CLOSE) points.add(point);
        }
        int crossings=0;
        for (int a=0;a<lines.size();a++) for (int b=a+1;b<lines.size();b++) {
            Line2D first=lines.get(a),second=lines.get(b);
            if (first.getP1().equals(second.getP1()) || first.getP1().equals(second.getP2())
                    || first.getP2().equals(second.getP1()) || first.getP2().equals(second.getP2())) continue;
            if (first.intersectsLine(second)) crossings++;
        }
        check(paths==1 && closed==0 && lines.size()==4 && crossings==1,
                "Fish has one open path and one crossing, with no line joining the tail tips.");
        check(shape.getBounds2D().equals(new Rectangle2D.Double(4,6,54,36))
                && points.contains(new Point2D.Double(58,24)) && points.contains(new Point2D.Double(4,6))
                && points.contains(new Point2D.Double(4,42)) && !points.contains(new Point2D.Double(40,24)),
                "Horizontal fish tail tips end at the top and bottom of the square, with no center-tip seam.");
        check(lines.stream().anyMatch(l -> l.getP1().equals(new Point2D.Double(4,6))
                        && l.getP2().equals(new Point2D.Double(40,42)))
                && lines.stream().anyMatch(l -> l.getP1().equals(new Point2D.Double(40,6))
                        && l.getP2().equals(new Point2D.Double(4,42))),
                "The square's left sides extend as uninterrupted 45-degree lines crossing at its left corner.");
        Path2D square=new Path2D.Double();
        square.moveTo(22,24); square.lineTo(40,6); square.lineTo(58,24); square.lineTo(40,42); square.closePath();
        Path2D triangle=new Path2D.Double();
        triangle.moveTo(4,6); triangle.lineTo(22,24); triangle.lineTo(4,42); triangle.closePath();
        Area expected=new Area(square); expected.add(new Area(triangle)); expected.exclusiveOr(new Area(shape));
        check(expected.isEmpty(),"Crossed outline encloses a true 45-degree square and the triangle formed by extending its left sides.");
        check(shape.getBounds2D().getWidth()/shape.getBounds2D().getHeight()==1.5,
                "Longer silhouette has a measurable 3:2 exterior aspect ratio without stretching the square body.");
        ImageIO.write(FishIcon.image(256),"png",Path.of("build","fish-horizontal-icon.png").toFile());
        BufferedImage squareMark=new BufferedImage(60,30,BufferedImage.TYPE_INT_ARGB);
        BufferedImage wideMark=new BufferedImage(60,30,BufferedImage.TYPE_INT_ARGB);
        Graphics2D squareGraphics=squareMark.createGraphics(),wideGraphics=wideMark.createGraphics();
        try {
            new FishIcon(40,30).paintIcon(null,squareGraphics,10,0);
            new FishIcon(60,30).paintIcon(null,wideGraphics,0,0);
        } finally { squareGraphics.dispose(); wideGraphics.dispose(); }
        check(differences(squareMark,wideMark,0,30)==0,"Titlebar icon bounds center a uniform scale; the body remains an actual square.");
        BufferedImage sizes=new BufferedImage(420,156,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=sizes.createGraphics();
        try {
            g.setColor(Ui.PANEL); g.fillRect(0,0,sizes.getWidth(),sizes.getHeight());
            int x=8;
            for (int size:new int[]{16,24,32,48,64,128}) {
                g.drawImage(FishIcon.image(size),x,(136-size)/2,null);
                g.setColor(Ui.TEXT); g.drawString(Integer.toString(size),x,150);
                x+=size+16;
            }
        } finally { g.dispose(); }
        ImageIO.write(sizes,"png",Path.of("build","fish-icon-sizes.png").toFile());
        check(Profile.create("Default").effect().equals("None"),"New profiles still default to no effects.");
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
            w.setFocusableWindowState(false); w.addNotify(); w.validate();
            return w;
        });
        try {
            edt(() -> {
                SessionPane only=single.active();
                single.closePane();
                check(single.sessionPanes().size()==1 && single.active()==only,"Disconnecting the sole tile cannot remove it.");
                check(only.screen()==null && !only.busy(),"The sole tile remains empty and offline.");
                check(!((JButton)field(only,"close")).isVisible(),"The disconnected sole tile hides its redundant X.");
                single.split(true);
                check(single.sessionPanes().stream().allMatch(p -> {
                    try { return ((JButton)field(p,"close")).isVisible(); }
                    catch (Exception e) { throw new AssertionError(e); }
                }),"Both offline tiles show their X after adding a pane.");
                single.closePane(single.active());
                check(single.sessionPanes().size()==1 && !((JButton)field(single.active(),"close")).isVisible(),
                        "Removing back to one offline tile hides its X again.");
                ImageIO.write(paint(single.getRootPane(),1.5),"png",directory.resolveSibling("fish-single-selection.png").toFile());
                return null;
            });
            try (DemoHost host=new DemoHost()) {
                edt(() -> {
                    Profile p=single.active().profile();
                    single.putProfile(new Profile(p.id(),p.name(),"127.0.0.1",host.port(),"","",false,false,
                            p.codePage(),p.theme(),p.effect(),p.zoom(),p.keymap(),p.savedLayout()));
                    single.active().connect();
                    check(field(single.active(),"shownCard").equals("connecting"),"Real connection startup displays the loading card, not a blank terminal.");
                    check(((JButton)field(single.active(),"close")).isVisible(),"The lone connecting pane retains X for cancellation.");
                    return null;
                });
                await(() -> edt(() -> single.active().connected() && field(single.active(),"shownCard").equals("terminal")),
                        "Sole loopback session finishes negotiation.");
                edt(() -> {
                    SessionPane only=single.active();
                    check(field(only,"shownCard").equals("terminal") && ((JButton)field(only,"close")).isVisible(),
                            "A connected sole pane shows the real terminal and its disconnect X.");
                    check(!((javax.swing.Timer)field(field(only,"spinner"),"clock")).isRunning(),"Connection spinner stops after success.");
                    only.disconnect();
                    check(field(only,"shownCard").equals("offline") && !((JButton)field(only,"close")).isVisible(),
                            "Disconnect restores selection mode and hides the sole X.");
                    return null;
                });
            }
            try (var server=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {
                server.setSoTimeout(5000);
                edt(() -> {
                    Profile p=single.active().profile();
                    single.putProfile(new Profile(p.id(),p.name(),"127.0.0.1",server.getLocalPort(),"","",false,false,
                            p.codePage(),p.theme(),p.effect(),p.zoom(),p.keymap(),p.savedLayout()));
                    single.active().connect(); return null;
                });
                try (var peer=server.accept()) {
                    edt(() -> {
                        SessionPane only=single.active();
                        JComponent spinner=(JComponent)field(only,"spinner");
                        check(only.busy() && !only.connected() && field(only,"shownCard").equals("connecting"),
                                "A held real handshake stays in connecting mode rather than inventing success.");
                        check(!((JComboBox<?>)field(only,"presets")).isEnabled() && !((JButton)field(only,"connectButton")).isEnabled(),
                                "Preset/Connect stay disabled until connection finishes.");
                        check(spinner.getAccessibleContext().getAccessibleRole()==javax.accessibility.AccessibleRole.PROGRESS_BAR
                                && spinner.getAccessibleContext().getAccessibleStateSet().contains(javax.accessibility.AccessibleState.BUSY),
                                "Spinner exposes meaningful accessible progress/busy state.");
                        setField(spinner,"angle",0.0); BufferedImage first=paint(spinner,1.5);
                        setField(spinner,"angle",90.0);
                        check(differences(first,paint(spinner,1.5),0,first.getHeight())>50,"Rotating indicator visibly differs at deterministic phases.");
                        check(!((javax.swing.Timer)field(spinner,"clock")).isRunning(),"Hidden connecting panes consume no animation clock.");
                        single.validate();
                        ImageIO.write(paint(single.getRootPane(),1.5),"png",directory.resolveSibling("fish-connecting.png").toFile());
                        return null;
                    });
                }
                await(() -> edt(() -> !single.active().busy()),"A failed held handshake leaves connecting mode.");
                edt(() -> {
                    SessionPane only=single.active();
                    check(field(only,"shownCard").equals("offline") && ((String)field(only,"notice")).startsWith("Disconnected -"),
                            "Connection failure restores selection and reports the real diagnostic.");
                    check(!((JButton)field(only,"close")).isVisible()
                            && !((javax.swing.Timer)field(field(only,"spinner"),"clock")).isRunning(),
                            "Failed sole connection hides X and leaves no running spinner.");
                    only.connect(); return null;
                });
                try (var peer=server.accept()) {
                    edt(() -> {
                        SessionPane only=single.active();
                        ((JButton)field(only,"close")).doClick(0);
                        check(single.active()==only && !only.busy() && field(only,"shownCard").equals("offline"),
                                "X cancels a real in-progress connection without removing the last tile.");
                        return null;
                    });
                }
            }
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
        Profile one=Profile.create("One"),two=Profile.create("Two").withDisplay("Ice",Profile.FLUX);
        check(two.credentialTarget().startsWith("Pane5250/"),"Existing Credential Manager namespace is retained.");
        try (StateStore store=new StateStore(migration.resolve("state"))) {
            StateStore.State state=new StateStore.State(List.of(one,two),new Layout.Leaf(UUID.randomUUID(),two.id()),
                    1100,700,AppTheme.Midnight,two.id());
            store.save(state);
            check(store.load().equals(state),"Last preset, profiles, theme and layout persist.");
            Properties xml=new Properties();
            try (var in=Files.newInputStream(store.file())) { xml.loadFromXML(in); }
            xml.setProperty("version","2"); xml.remove("lastProfile"); xml.setProperty("layout","E");
            xml.remove("windows"); xml.remove("info.footerDevice");
            xml.keySet().removeIf(key -> key.toString().startsWith("updates."));
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
            xml.remove("windows"); xml.remove("info.footerDevice");
            xml.keySet().removeIf(key -> key.toString().startsWith("updates."));
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
            iconChecks();
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
                clipboardChecks(workspace,original.getFirst(),host);
                fluxChecks(workspace,original.getFirst());
                return null;
            });
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
                int closedPanes=0;
                for (SessionPane pane:workspace.sessionPanes()) {
                    List<Component> controls=children(pane);
                    JButton close=controls.stream().filter(c -> c instanceof JButton b
                            && ("Close pane "+pane.profile().name()).equals(b.getAccessibleContext().getAccessibleName()))
                            .map(c -> (JButton)c).findFirst().orElseThrow();
                    close.doClick(0);
                    check(!pane.busy() && pane.screen()==null && workspace.sessionPanes().size()==Math.max(1,8-++closedPanes),
                            "X disconnects and removes its tile, except the final tile.");
                    check(Layout.normalize(workspace.currentLayout()).equals(workspace.currentLayout()),"Closing reflows into bounded slots.");
                    if (!workspace.hasPane(pane.id())) continue;
                    Container body=(Container)((BorderLayout)pane.getLayout()).getLayoutComponent(BorderLayout.CENTER);
                    Container offline=Arrays.stream(body.getComponents()).filter(Component::isVisible).map(c -> (Container)c).findFirst().orElseThrow();
                    List<Component> offlineControls=children(offline);
                    check(offlineControls.stream().filter(c -> c instanceof JComboBox<?>).count()==1
                            && offlineControls.stream().filter(c -> c instanceof JButton b
                                && "Connect to selected preset".equals(b.getAccessibleContext().getAccessibleName()) && b.getIcon()!=null).count()==1
                            && offlineControls.stream().noneMatch(c -> c instanceof JLabel),"Offline content has only the preset selector and Connect.");
                }
                check(Arrays.stream(workspace.getOwnedWindows()).noneMatch(Window::isVisible),"Disconnect never opens a confirmation.");
                check(!workspace.isVisible(),"No smoke check shows or focuses the desktop.");
                workspace.validate();
                BufferedImage offline=paint(workspace.getRootPane(),1);
                ImageIO.write(offline,"png",Path.of("build","fish-empty-one.png").toFile());
                check(add.isEnabled(),"Closing tiles enables Add pane again.");
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
                check(reopened.load().layout().leaves().size()==1,"The remaining one-pane layout persists.");
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
