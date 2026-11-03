package pane5250.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.Border;
import pane5250.Main;
import pane5250.model.*;

/** Synthetic, offscreen physical-pixel and settings checks. No shared desktop input. */
public final class ChromeConsistencySmoke {
    private static int checks;
    private static void check(boolean value,String text) { checks++; if (!value) throw new AssertionError(text); }
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> task=new FutureTask<>(work); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static void layout(Container c) {
        c.doLayout(); for (Component child:c.getComponents()) if (child instanceof Container parent) layout(parent);
    }
    private static List<Component> children(Container c) {
        List<Component> result=new ArrayList<>();
        for (Component child:c.getComponents()) { result.add(child); if (child instanceof Container p) result.addAll(children(p)); }
        return result;
    }
    private static BufferedImage paint(JComponent c,double scale,String file) throws Exception {
        layout(c);
        BufferedImage image=new BufferedImage((int)Math.ceil(c.getWidth()*scale),(int)Math.ceil(c.getHeight()*scale),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try { g.scale(scale,scale); c.paint(g); } finally { g.dispose(); }
        if (file!=null) ImageIO.write(image,"png",Path.of("build",file).toFile());
        return image;
    }
    private static void perimeter(BufferedImage image,String name) {
        perimeter(image,name,false);
    }
    private static void perimeter(BufferedImage image,String name,boolean junctions) {
        int line=Ui.LINE.getRGB(),w=image.getWidth(),h=image.getHeight();
        boolean edges=true;
        for (int x=0;x<w;x++) edges&=image.getRGB(x,0)==line && image.getRGB(x,h-1)==line;
        for (int y=0;y<h;y++) edges&=image.getRGB(0,y)==line && image.getRGB(w-1,y)==line;
        int horizontal=0,vertical=0;
        for (int x=2;x<w-2;x++) {
            if (image.getRGB(x,1)==line) horizontal++;
            if (image.getRGB(x,h-2)==line) horizontal++;
        }
        for (int y=2;y<h-2;y++) {
            if (image.getRGB(1,y)==line) vertical++;
            if (image.getRGB(w-2,y)==line) vertical++;
        }
        boolean thin=junctions ? horizontal<=16 && vertical<=16 : horizontal==0 && vertical==0;
        check(edges,"Complete four-sided physical-pixel perimeter, including right/bottom and square corners: "+name);
        check(thin,"Perimeter is exactly one physical pixel, never a bevel/shadow: "+name);
    }
    private static void pixelChecks(double scale) throws Exception {
        for (int width:List.of(100,101)) {
            JPanel panel=new JPanel(); panel.setBackground(Ui.FIELD); panel.setSize(width,61); panel.setBorder(Hairline.perimeter());
            perimeter(paint(panel,scale,null),"shared edge "+scale+"/"+width);
            panel.setBorder(new FlatControls.Edge(5,8));
            perimeter(paint(panel,scale,null),"field/button edge "+scale+"/"+width);
        }
        for (JComponent control:List.of(new JTextField("Synthetic"),new JButton("Apply"),
                new JToggleButton("Details"),new JScrollPane(new JList<>(new String[]{"One","Two"})))) {
            control.setSize(180,61);
            perimeter(paint(control,scale,null),control.getClass().getSimpleName()+" "+scale);
        }
        JPopupMenu popup=new JPopupMenu(); popup.add(new JMenuItem("Synthetic item")); popup.setSize(180,65);
        perimeter(paint(popup,scale,null),"popup "+scale);
        JToolTip tooltip=new JToolTip(); tooltip.setTipText("Synthetic tooltip"); tooltip.setSize(180,60);
        perimeter(paint(tooltip,scale,null),"tooltip "+scale);
        JComboBox<String> choices=new JComboBox<>(new String[]{"One","Two","Three"});
        JPopupMenu combo=(JPopupMenu)choices.getUI().getAccessibleChild(choices,0); combo.setSize(180,95);
        check(combo.getBorder() instanceof Hairline,"Actual combo popup replaces Metal's compound bevel.");
        check(children(combo).stream().filter(c -> c instanceof JScrollPane).map(c -> (JScrollPane)c)
                .allMatch(p -> p.getInsets().equals(new Insets(0,0,0,0))),"Combo scroller adds no second perimeter.");
        perimeter(paint(combo,scale,null),"actual combo "+scale);
        JSeparator separator=new JPopupMenu.Separator(); separator.setSize(160,7);
        BufferedImage image=paint(separator,scale,null);
        int rows=0;
        for (int y=0;y<image.getHeight();y++) if (image.getRGB(image.getWidth()/2,y)==Ui.LINE.getRGB()) rows++;
        check(rows==1,"Menu separator paints one device row, not Metal's light/dark pair: "+scale);
    }
    private static void dialogInterior(BufferedImage image,String name) {
        int surface=Ui.color(ColorRole.DIALOG).getRGB(),w=image.getWidth(),h=image.getHeight();
        boolean clean=true;
        for (int x=2;x<w-2;x++) clean&=image.getRGB(x,1)==surface && image.getRGB(x,h-2)==surface;
        for (int y=2;y<h-2;y++) clean&=image.getRGB(1,y)==surface && image.getRGB(w-2,y)==surface;
        check(clean,"All four inner perimeter strips use the dialog surface, never the default LAF background: "+name);
    }
    private static void migration(Path root) throws Exception {
        try (StateStore store=new StateStore(root)) {
            StateStore.State fresh=store.load();
            EnumMap<ColorRole,Integer> roles=new EnumMap<>(ColorTheme.builtin(AppTheme.Charcoal).colors());
            roles.put(ColorRole.DIVIDER,0x354549);
            ColorTheme theme=new ColorTheme("Legacy fixture",roles);
            Appearance old=fresh.appearance().withTheme(theme.name(),List.of(theme)).withLayout(false,Appearance.PaneInfo.defaults());
            store.save(new StateStore.State(fresh.profiles(),fresh.layout(),fresh.width(),fresh.height(),fresh.appTheme(),
                    fresh.lastProfile(),fresh.shortcuts(),false,old));
            Properties p=new Properties();
            try (var in=Files.newInputStream(store.file())) { p.loadFromXML(in); }
            p.setProperty("version","5"); p.remove("appearance.windowOutline");
            p.setProperty("appearance.theme.0.LINE","#EEDDCC"); p.setProperty("appearance.theme.0.OUTLINE","#FF0011");
            try (var out=Files.newOutputStream(store.file())) { p.storeToXML(out,"Synthetic legacy appearance"); }
            StateStore.State loaded=store.load();
            check(!loaded.appearance().windowOutline() && !loaded.appearance().outline() && !loaded.highlightActive(),
                    "Existing version-5 workspaces get window outline OFF without changing chosen active outline/tint.");
            check(loaded.appearance().custom().orElseThrow().rgb(ColorRole.DIVIDER)==0x354549
                    && loaded.appearance().custom().orElseThrow().colors().size()==ColorRole.values().length,
                    "Legacy LINE/OUTLINE colors migrate to the existing shared divider; no dead designer controls.");
            check(store.loadNotice().contains("divider") && store.loadNotice().contains("off by default"),"Linked-role/default-outline migration is reported explicitly.");
            Appearance chosen=loaded.appearance().withWindowOutline(true);
            StateStore.State state=new StateStore.State(loaded.profiles(),loaded.layout(),loaded.width(),loaded.height(),loaded.appTheme(),
                    loaded.lastProfile(),loaded.shortcuts(),loaded.highlightActive(),chosen);
            store.save(state);
            check(store.load().equals(state) && store.loadNotice().isEmpty(),"Explicit window-outline choice survives version-6 restart without another migration.");
        }
    }
    private static JDialog dialog(Workspace w,String title,JComponent body) {
        JDialog dialog=Ui.promptDialog(w,title,body,"Done",false,() -> {});
        dialog.setFocusableWindowState(false); dialog.validate(); return dialog;
    }
    private static JButton button(JDialog dialog,String text) {
        return children(dialog.getRootPane()).stream().filter(c -> c instanceof JButton b && text.equals(b.getText()))
                .map(c -> (JButton)c).findFirst().orElseThrow();
    }
    private static List<JCheckBox> choices(JDialog dialog) {
        return children(dialog.getRootPane()).stream().filter(c -> c instanceof JCheckBox).map(c -> (JCheckBox)c).toList();
    }
    private static StateStore.State flush(Workspace w,StateStore store) throws Exception {
        edt(() -> { w.saveAsync(); return null; });
        ((ExecutorService)field(w,"persistence")).submit(() -> {}).get(5,TimeUnit.SECONDS);
        return store.load();
    }
    private static void windowFocusChecks(Workspace w,StateStore store) throws Exception {
        edt(() -> {
            JDialog dialog=AppearanceDialogs.windowFocusDialog(w);
            dialog.setFocusableWindowState(false); dialog.validate();
            try {
                var actions=children(dialog.getRootPane()).stream().filter(c -> c instanceof JButton b && b.getText()!=null && !b.getText().isEmpty())
                        .map(c -> ((JButton)c).getText()).toList();
                check(actions.equals(List.of("Cancel","Apply")),"Actual Window and focus uses Cancel then Apply, never a surrogate Done action.");
                check(dialog.getRootPane().getDefaultButton()==button(dialog,"Apply"),"Apply is the actual default acceptance action.");
                check(children(dialog.getRootPane()).stream().anyMatch(c -> c instanceof JLabel label
                        && label.getText().contains("saved only when you choose Apply")),"Actual dialog copy names its actual acceptance action.");
                check(choices(dialog).size()==3,"Actual dialog exposes all three independent saved choices.");
                for (double scale:List.of(1.0,1.5)) {
                    BufferedImage image=paint(dialog.getRootPane(),scale,"fish-051-focus-"+scale+".png");
                    perimeter(image,"actual Window and focus "+scale);
                    dialogInterior(image,"actual Window and focus "+scale);
                }
                check(!dialog.isVisible() && !w.isVisible(),"Actual dialog evidence does not expose the shared desktop.");
            } finally { dialog.dispose(); }
            return null;
        });
        for (int mask=0;mask<8;mask++) {
            final int flags=mask;
            edt(() -> {
                Appearance before=w.appearanceSettings(); boolean tint=w.highlightActive();
                JDialog dialog=AppearanceDialogs.windowFocusDialog(w);
                var choices=choices(dialog);
                for (int i=0;i<3;i++) choices.get(i).setSelected((flags&(1<<i))!=0);
                check(w.appearanceSettings().equals(before) && w.highlightActive()==tint,"Editing checks does not apply or save before Apply: "+flags);
                button(dialog,"Apply").doClick(0);
                check(w.appearanceSettings().windowOutline()==((flags&1)!=0)
                        && w.appearanceSettings().outline()==((flags&2)!=0) && w.highlightActive()==((flags&4)!=0),
                        "Actual Apply updates all three choices consistently: "+flags);
                check(!dialog.isDisplayable(),"Actual Apply closes its dialog: "+flags);
                return null;
            });
            StateStore.State loaded=flush(w,store);
            check(loaded.appearance().windowOutline()==((flags&1)!=0) && loaded.appearance().outline()==((flags&2)!=0)
                    && loaded.highlightActive()==((flags&4)!=0),"All three applied choices reload together from disk: "+flags);
        }
        for (String dismiss:List.of("Cancel","Escape","Close")) {
            Appearance before=edt(w::appearanceSettings);
            boolean tint=edt(w::highlightActive);
            edt(() -> {
                JDialog dialog=AppearanceDialogs.windowFocusDialog(w);
                for (JCheckBox box:choices(dialog)) box.setSelected(!box.isSelected());
                if (dismiss.equals("Cancel")) button(dialog,"Cancel").doClick(0);
                else if (dismiss.equals("Escape")) {
                    Object key=dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("ESCAPE"));
                    dialog.getRootPane().getActionMap().get(key).actionPerformed(new java.awt.event.ActionEvent(dialog,0,"escape"));
                } else children(dialog.getRootPane()).stream().filter(c -> c instanceof JButton b
                        && "Close dialog".equals(b.getAccessibleContext().getAccessibleName()))
                        .map(c -> (JButton)c).findFirst().orElseThrow().doClick(0);
                check(!dialog.isDisplayable(),"Actual dismissal closes the dialog: "+dismiss);
                check(w.appearanceSettings().equals(before) && w.highlightActive()==tint,"Actual dismissal discards all three pending edits: "+dismiss);
                return null;
            });
            StateStore.State loaded=flush(w,store);
            check(loaded.appearance().equals(before) && loaded.highlightActive()==tint,"Dismissal preserves all three stored choices: "+dismiss);
        }
    }
    private static void workspaceChecks(Workspace w,double scale) throws Exception {
        check(!w.appearanceSettings().windowOutline() && w.getRootPane().getInsets().equals(new Insets(0,0,0,0)),
                "Fresh workspace has no outer outline or border inset.");
        WindowChrome chrome=(WindowChrome)field(w,"chrome");
        JMenuBar menus=(JMenuBar)field(chrome,"menus");
        JMenu view=Arrays.stream(menus.getComponents()).map(c -> (JMenu)c).filter(c -> c.getText().equals("View")).findFirst().orElseThrow();
        List<String> quick=Arrays.stream(view.getMenuComponents()).filter(c -> c instanceof JMenuItem).map(c -> ((JMenuItem)c).getText()).toList();
        check(quick.equals(List.of("Fit host screen","Zoom in","Zoom out","Appearance...")),"Quick View contains only frequent view actions and a single settings route.");
        check(Arrays.stream(view.getMenuComponents()).noneMatch(c -> c instanceof JCheckBoxMenuItem),"No outline toggle leaks into quick View.");
        Appearance saved=w.appearanceSettings();
        ColorTheme synthetic=new ColorTheme("Synthetic",ColorTheme.builtin(AppTheme.Charcoal).colors());
        w.appearanceSettings(saved.withTheme(synthetic.name(),List.of(synthetic)));
        JPanel appearance=w.appearancePanel();
        var selectors=children(appearance).stream().filter(c -> c instanceof JComboBox<?>)
                .map(c -> (JComboBox<?>)c).toList();
        selectors.get(0).setSelectedItem(AppTheme.Charcoal);
        check(selectors.get(1).isEnabled(),"Switching to a built-in theme immediately enables its dependent host-palette choice.");
        selectors.get(0).setSelectedItem(synthetic);
        check(!selectors.get(1).isEnabled(),"Switching to a custom theme immediately disables the superseded profile palette.");
        selectors.get(0).setSelectedItem(AppTheme.Charcoal);
        w.appearanceSettings(saved);
        List<String> labels=children(appearance).stream().filter(c -> c instanceof JLabel).map(c -> ((JLabel)c).getText()).toList();
        check(labels.subList(0,3).equals(List.of("Application theme","Host palette","Text effect (opt-in)")),
                "Appearance orders application choice before dependent profile palette/effect.");
        check(children(appearance).stream().noneMatch(c -> c instanceof JCheckBox)
                && children(appearance).stream().filter(c -> c instanceof JButton b && "More settings...".equals(b.getText())).count()==1,
                "First-level Appearance has no outline/tint toggles or editor-button wall.");
        JPanel details=AppearanceDialogs.windowFocusPanel(w);
        JPanel choices=(JPanel)details.getComponent(0);
        check(((JCheckBox)choices.getComponent(0)).getText().equals("Show whole-window outline")
                && ((JCheckBox)choices.getComponent(1)).getText().equals("Outline active pane"),
                "Window and pane outline choices are distinct, labeled and ordered in deeper settings.");
        check(choices.getPreferredSize().height<100,"Window/focus choices stay compact; the explanatory note cannot stretch each checkbox row.");
        check(w.commands().stream().map(c -> c.title).distinct().count()==w.commands().size(),"Menu regrouping preserves one shared handler/shortcut identity per action.");
        check(w.commands().stream().anyMatch(c -> c.title.equals("Window and focus...")),"Deeper settings remain shortcut-configurable.");
        JDialog basic=dialog(w,"Appearance",appearance);
        BufferedImage basicImage=paint(basic.getRootPane(),scale,"fish-051-appearance-"+scale+".png");
        perimeter(basicImage,"owned dialog "+scale); dialogInterior(basicImage,"Appearance "+scale);
        JDialog advanced=AppearanceDialogs.windowFocusDialog(w);
        advanced.setFocusableWindowState(false); advanced.validate();
        BufferedImage focusImage=paint(advanced.getRootPane(),scale,"fish-051-focus-"+scale+".png");
        perimeter(focusImage,"Window and focus "+scale); dialogInterior(focusImage,"Window and focus "+scale);
        basic.dispose(); advanced.dispose();
        JFileChooser chooser=new JFileChooser(Path.of("build").toFile());
        JDialog files=dialog(w,"Synthetic local font chooser",chooser);
        perimeter(paint(files.getRootPane(),scale,null),"flat owned font chooser "+scale);
        files.dispose();
        long handle=(long)field(field(chrome,"nativeWindow"),"handle");
        Method geometry=NativeWindow.class.getDeclaredMethod("geometry0",long.class); geometry.setAccessible(true);
        int[] dimensions=(int[])geometry.invoke(null,handle);
        check(dimensions[0]==dimensions[2] && dimensions[1]==dimensions[3],"Actual native frame uses the full rectangular client area, without asymmetric non-client insets.");
        check((dimensions[5]&0x20201)==0 && dimensions[6]==0,
                "No modal/client/static native edge or DWM non-client rendering remains; Win32's derived WINDOWEDGE flag has no painted frame.");
        if (dimensions[4]!=0) check(dimensions[4]==1,
                "DWM uses square corners on Windows 11: "+Arrays.toString(dimensions));
        Method frameBounds=NativeWindow.class.getDeclaredMethod("frameBounds0",long.class,boolean.class); frameBounds.setAccessible(true);
        for (boolean maximized:List.of(false,true)) {
            int[] bounds=(int[])frameBounds.invoke(null,handle,maximized);
            int expected=maximized ? 8 : 0;
            check(Arrays.equals(Arrays.copyOfRange(bounds,4,8),Arrays.copyOfRange(bounds,expected,expected+4)),
                    "Native NCCALCSIZE logic retains all four restored edges / uses the exact maximized work area: "+maximized);
        }
        JPopupMenu heavy=new JPopupMenu(); heavy.add(new JMenuItem("Synthetic popup"));
        Popup pending=PopupFactory.getSharedInstance().getPopup(null,heavy,-20000,-20000);
        Window popup=SwingUtilities.getWindowAncestor(heavy);
        check(popup!=null && !popup.isVisible(),"Actual heavyweight popup is configured before showing, with no desktop exposure.");
        check(((RootPaneContainer)popup).getRootPane().getInsets().equals(new Insets(0,0,0,0)),
                "Heavy popup root contributes no second border.");
        pending.hide();
        Appearance hidden=w.appearanceSettings().withLayout(false,new Appearance.PaneInfo(false,false,false,false,false,false,false,false,false,false));
        w.appearanceSettings(hidden); w.highlightActive(false); w.validate(); layout(w);
        BufferedImage off=paint(w.getRootPane(),scale,"fish-051-outline-off-"+scale+".png");
        int x=off.getWidth()/3,titleBottom=(int)Math.ceil(31*scale)-1;
        check(off.getRGB(x,titleBottom)==Ui.LINE.getRGB() && off.getRGB(x,titleBottom-1)!=Ui.LINE.getRGB()
                && off.getRGB(x,titleBottom+1)!=Ui.LINE.getRGB(),"Title/content separator is exactly one physical row, not a double-painted junction: scale="
                +scale+" chrome="+chrome.getBounds()+" pixels="+Integer.toHexString(off.getRGB(x,titleBottom-1))+"/"
                +Integer.toHexString(off.getRGB(x,titleBottom))+"/"+Integer.toHexString(off.getRGB(x,titleBottom+1)));
        w.appearanceSettings(hidden.withLayout(true,hidden.info())); w.validate(); layout(w);
        BufferedImage active=paint(w.getRootPane(),scale,null);
        check(active.getRGB(x,titleBottom)==Ui.LINE.getRGB() && active.getRGB(x,titleBottom+1)!=Ui.LINE.getRGB(),
                "Active-pane outline does not double the adjacent titlebar separator.");
        w.appearanceSettings(hidden.withWindowOutline(true)); w.validate(); layout(w);
        perimeter(paint(w.getRootPane(),scale,"fish-051-outline-on-"+scale+".png"),"actual workspace optional outline "+scale,true);
        w.setSize(1001,651); w.validate(); layout(w);
        perimeter(paint(w.getRootPane(),scale,null),"odd-sized restored window "+scale,true);
        w.removeNotify(); w.addNotify(); w.validate();
        check(w.appearanceSettings().windowOutline(),"Outline choice survives native peer recreation.");
        w.appearanceSettings(hidden);
        JPopupMenu context=w.clipboardMenu(w.active()); context.setSize(context.getPreferredSize());
        paint(context,scale,"fish-051-context-"+scale+".png");
        check(!w.isVisible(),"Native and paint checks never expose the shared desktop.");
    }
    public static void main(String[] args) throws Exception {
        Ui.install();
        boolean focusOnly=List.of(args).equals(List.of("--focus-only"));
        Path root=Files.createTempDirectory(Path.of("build"),"chrome-smoke-");
        if (!focusOnly) migration(root.resolve("migration"));
        CountDownLatch closed=new CountDownLatch(1);
        try {
            StateStore store=new StateStore(root.resolve("workspace"));
            Workspace w=edt(() -> {
                Workspace window=new Workspace(store,Main.demoState(23),Path.of("scripts","credential-store.ps1"),false);
                window.setFocusableWindowState(false); window.setSize(1000,650);
                window.onClosed(closed::countDown); window.addNotify(); window.validate(); return window;
            });
            try {
                if (!focusOnly) edt(() -> {
                    for (double scale:List.of(1.0,1.5)) { pixelChecks(scale); workspaceChecks(w,scale); }
                    return null;
                });
                windowFocusChecks(w,store);
            }
            finally { edt(() -> { w.close(false); return null; }); check(closed.await(5,TimeUnit.SECONDS),"Final local appearance persists and unlocks."); }
        } finally {
            try (var paths=Files.walk(root)) { for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
        System.out.println("PASS Chrome consistency: "+checks+" checks; synthetic pixels/native messages, no shared-desktop input.");
    }
}
