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
import pane5250.model.*;

/** Production chrome/native minimums, referenced form hierarchy, owned guides and semantic themes. */
public final class BetaUiSmoke {
    private static int checks;
    private static Path evidence;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void layout(Container c) { c.doLayout(); for (Component child:c.getComponents()) if (child instanceof Container p) layout(p); }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> run=new FutureTask<>(action); SwingUtilities.invokeAndWait(run); return run.get();
    }
    private static void snapshot(Component c,String name) throws Exception {
        if (evidence==null) return;
        for (double scale:new double[]{1,1.5,2}) {
            BufferedImage image=new BufferedImage((int)Math.ceil(c.getWidth()*scale),(int)Math.ceil(c.getHeight()*scale),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics();
            try { g.scale(scale,scale); c.printAll(g); } finally { g.dispose(); }
            ImageIO.write(image,"png",evidence.resolve(name+"-"+scale+".png").toFile());
        }
    }
    private static double luminance(Color color) {
        double result=0; int[] channels={color.getRed(),color.getGreen(),color.getBlue()}; double[] weights={.2126,.7152,.0722};
        for (int n=0;n<3;n++) { double v=channels[n]/255d; result+=weights[n]*(v<=.04045 ? v/12.92 : Math.pow((v+.055)/1.055,2.4)); }
        return result;
    }
    private static double contrast(Color a,Color b) {
        double x=luminance(a),y=luminance(b); return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);
    }
    private static void themes(Workspace w) {
        for (AppTheme app:List.of(AppTheme.Light,AppTheme.Slate,AppTheme.Charcoal)) {
            w.applyTheme(app);
            for (ColorRole background:List.of(ColorRole.PANEL,ColorRole.FIELD,ColorRole.HOVER,ColorRole.SELECTION))
                check(contrast(Ui.color(ColorRole.TEXT),Ui.color(background))>=4.5,"New app foreground remains readable on every control state: "+app+"/"+background);
            check(contrast(Ui.color(ColorRole.MUTED),Ui.color(ColorRole.PANEL))>=4.5
                    && contrast(Ui.color(ColorRole.ERROR),Ui.color(ColorRole.PANEL))>=4.5,"Secondary/error roles remain readable");
            for (String name:List.of("Azure vivid","Crimson vivid")) {
                Color[] colors=HostPalette.colors(name);
                Set<Integer> normal=new HashSet<>();
                for (int n=1;n<16;n++) {
                    check(contrast(colors[n],Ui.paneBackground(true))>=4.5,"Vivid host role remains readable: "+app+"/"+name+"/"+n);
                    if (n<8) normal.add(colors[n].getRGB());
                }
                check(normal.size()==7,"Host colors retain semantic distinctions rather than becoming one accent");
                check(!colors[2].equals(colors[10]),"Normal/intensified host variants remain distinct");
            }
        }
    }
    private static void chrome(Workspace w) throws Exception {
        WindowChrome chrome=(WindowChrome)field(w,"chrome");
        JPanel controls=(JPanel)field(chrome,"controls"); JPanel drag=(JPanel)field(chrome,"dragArea");
        JComboBox<?> selector=(JComboBox<?>)field(w,"addProfileSelector");
        JButton plus=(JButton)field(w,"addPane");
        Profile p=w.active().profile();
        w.putProfile(new Profile(p.id(),"Long synthetic workstation name "+"W".repeat(45),p.host(),p.port(),p.device(),p.username(),
                p.automaticSignOn(),p.rememberPassword(),p.codePage(),p.theme(),p.effect(),p.zoom(),p.keymap(),p.savedLayout(),
                p.loginSource(),p.authentication(),p.kerberosServer()));
        check(selector.getInsets().equals(new Insets(0,0,0,0)),"Selector has a flat chrome surface, not a boxed field");
        Component renderer=selector.getRenderer().getListCellRendererComponent(new JList<>(),null,-1,false,false);
        if (renderer instanceof JLabel label) check(label.getHorizontalAlignment()==SwingConstants.CENTER,"Selected profile centers in its own area");
        for (AppTheme theme:List.of(AppTheme.Charcoal,AppTheme.Light)) {
            w.applyTheme(theme);
            for (int width:new int[]{w.getMinimumSize().width,50,640,1120}) {
                w.setSize(width,Math.max(420,w.getMinimumSize().height)); layout(w);
                check(w.getWidth()>=w.getMinimumSize().width,"Attempted below-minimum programmatic resize clamps");
                check(controls.getX()+controls.getWidth()<=chrome.getWidth() && drag.getWidth()>=16,"Right caption controls and drag area remain fully allocated");
                for (Component control:controls.getComponents()) check(control.getWidth()>=control.getPreferredSize().width
                        && control.getHeight()>=27,"All caption hit targets retain their actual usable minimums");
                check(selector.getWidth()>=selector.getMinimumSize().width && plus.getWidth()>=30,"Selector/add never collapse into clipped hits");
                if (width==50 || width==1120) snapshot(chrome,"chrome-"+theme+"-"+width);
            }
        }
        w.addNotify(); layout(w);
        Object bridge=field(chrome,"nativeWindow"); long handle=(long)field(bridge,"handle");
        Method nativeMin=NativeWindow.class.getDeclaredMethod("minimum0",long.class); nativeMin.setAccessible(true);
        int[] minimum=(int[])nativeMin.invoke(null,handle);
        Dimension logical=w.getMinimumSize();
        check(handle!=0 && minimum[2]>0 && minimum[0]>=Math.round(logical.width*minimum[2]/96d)
                && minimum[1]>=Math.round(logical.height*minimum[2]/96d),
                "Actual hidden Windows WM_GETMINMAXINFO enforces logical control minimums at current native DPI");
        w.putProfile(p); w.setSize(1120,760); layout(w); snapshot(chrome,"chrome-standard-profile");
    }
    private static void forms(Workspace w) throws Exception {
        for (AppTheme theme:List.of(AppTheme.Charcoal,AppTheme.Light,AppTheme.Slate)) {
            w.applyTheme(theme);
            SavedLayoutsDialog dialog=new SavedLayoutsDialog(w); layout(dialog);
            JTextField name=(JTextField)field(dialog,"name");
            JLabel summary=(JLabel)field(dialog,"context");
            JButton create=(JButton)field(dialog,"create"),save=(JButton)field(dialog,"save");
            JToggleButton manage=(JToggleButton)field(dialog,"manage");
            Rectangle box=SwingUtilities.convertRectangle(name.getParent(),name.getBounds(),dialog);
            Rectangle context=SwingUtilities.convertRectangle(summary.getParent(),summary.getBounds(),dialog);
            Rectangle primary=SwingUtilities.convertRectangle(create.getParent(),create.getBounds(),dialog);
            check(box.x<40 && box.width>=dialog.getWidth()-80 && context.y<box.y,"Referenced shortcut name is full width under profile context");
            check(Math.abs(primary.x-box.x)<=1 && save.getX()>create.getX()+create.getWidth(),"Primary and secondary actions are left aligned in requested order");
            check(manage.getWidth()>=box.width && !((JPanel)field(dialog,"library")).isVisible(),"Saved library is full-width and initially expandable");
            name.setText("Synthetic layout \u5317"); name.selectAll(); name.getCaret().setVisible(true);
            snapshot(dialog.getContentPane(),"shortcuts-"+theme);
            if (w.savedLayouts().isEmpty()) {
                save.doClick(0);
                UsabilityIterationSmoke.settle(() -> !(boolean)field(dialog,"working"),"Synthetic saved layout did not persist");
            } else manage.doClick(0);
            layout(dialog);
            JTextField rename=(JTextField)field(dialog,"existingName");
            check(rename.getFont().canDisplayUpTo(rename.getText())<0,"Saved Unicode names remain readable in the actual rename field");
            snapshot(dialog.getContentPane(),"shortcuts-expanded-"+theme);
            name.getCaret().setVisible(false); name.getCaret().setBlinkRate(0); dialog.dispose();
            Preferences preferences=new Preferences(w,"Appearance"); layout(preferences.dialog());
            snapshot(preferences.dialog().getContentPane(),"settings-"+theme); preferences.dialog().dispose();
            TutorialDialog tutorial=new TutorialDialog(w,TutorialDialog.Guide.APPEARANCE,false);
            w.preferences("Appearance"); tutorial.go(2); layout(tutorial);
            check(tutorial.getOwner()==w && !tutorial.isAlwaysOnTop() && !tutorial.isAutoRequestFocus()
                    && tutorial.getModalExclusionType()==Dialog.ModalExclusionType.APPLICATION_EXCLUDE,
                    "Guide remains application-owned, modeless/excluded and non-focus-stealing, not globally always-on-top");
            Method related=TutorialDialog.class.getDeclaredMethod("related",Window.class); related.setAccessible(true);
            check((boolean)related.invoke(tutorial,w.tutorialPreferences().dialog()),"Settings sibling belongs to tutorial's owned hierarchy");
            JDialog chooser=new JDialog(w.tutorialPreferences().dialog()); JDialog other=new JDialog((Frame)null);
            check((boolean)related.invoke(tutorial,chooser) && !(boolean)related.invoke(tutorial,other),"Nested chooser raises the guide; unrelated windows never do");
            AWTEventListener listener=(AWTEventListener)field(tutorial,"ownedWindows");
            listener.eventDispatched(new WindowEvent(chooser,WindowEvent.WINDOW_ACTIVATED));
            check(field(tutorial,"targetWindow")==w.tutorialPreferences().dialog(),"Real control target and highlight ownership follow Settings");
            snapshot(tutorial.getContentPane(),"tutorial-"+theme);
            tutorial.dispose(); chooser.dispose(); other.dispose(); w.tutorialPreferences().dialog().dispose();
            check(Arrays.stream(Toolkit.getDefaultToolkit().getAWTEventListeners(AWTEvent.WINDOW_EVENT_MASK))
                    .noneMatch(e -> e==listener || e instanceof AWTEventListenerProxy proxy && proxy.getListener()==listener),
                    "Closed tutorial detaches owned-window listener");
            JDialog recovery=WorkspaceRecovery.dialog("Synthetic future workspace version; matching backup required.",true,value -> {});
            layout(recovery); snapshot(recovery.getContentPane(),"startup-recovery-"+theme); recovery.dispose();
        }
    }
    public static void main(String[] args) throws Exception {
        Ui.install();
        if (args.length>0) { evidence=Path.of(args[0]); Files.createDirectories(evidence); }
        Path root=Files.createTempDirectory(Path.of("build"),"beta-ui-");
        try (var f=new UsabilityIterationSmoke.Fixture(root)) {
            edt(() -> {
                Properties state=StateStore.properties(f.app.snapshot()); state.setProperty("width","330"); state.setProperty("height","260");
                Workspace restored=new Workspace(f.store,f.store.decode(state),root.resolve("unused-credentials.ps1"),false);
                check(restored.getWidth()>=restored.getMinimumSize().width && restored.getHeight()>=restored.getMinimumSize().height,
                        "Production restored legacy-small bounds clamp before display");
                restored.finishWindow();
                themes(f.first()); chrome(f.first()); forms(f.first()); return null;
            });
            System.out.println("PASS beta semantic colors / actual caption and native minimums / reference forms / owned tutorial lifecycle: "+checks);
        } finally {
            try (var files=Files.walk(root)) { for (Path p:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
}
