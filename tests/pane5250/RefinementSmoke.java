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
import javax.swing.table.DefaultTableModel;
import pane5250.Main;
import pane5250.demo.DemoHost;
import pane5250.model.*;

/** Production paths, hidden windows and synthetic data; no desktop input or credentials. */
public final class RefinementSmoke {
    private static int checks;
    private static void check(boolean value,String text) { checks++; if (!value) throw new AssertionError(text); }
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void set(Object owner,String name,Object value) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner,value);
    }
    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> task=new FutureTask<>(work); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static void layout(Container c) {
        c.doLayout(); for (Component child:c.getComponents()) if (child instanceof Container p) layout(p);
    }
    private static List<Component> children(Container c) {
        List<Component> result=new ArrayList<>();
        for (Component child:c.getComponents()) { result.add(child); if (child instanceof Container p) result.addAll(children(p)); }
        return result;
    }
    private static JButton button(Container c,String text) {
        return children(c).stream().filter(v -> v instanceof JButton b && text.equals(b.getText())).map(v -> (JButton)v).findFirst().orElseThrow();
    }
    private static List<JCheckBox> boxes(JDialog d) {
        return children(d).stream().filter(c -> c instanceof JCheckBox).map(c -> (JCheckBox)c).toList();
    }
    private static BufferedImage paint(JComponent view,double scale) {
        BufferedImage image=new BufferedImage(Math.max(1,(int)Math.ceil(view.getWidth()*scale)),Math.max(1,(int)Math.ceil(view.getHeight()*scale)),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try { g.setColor(Color.MAGENTA); g.fillRect(0,0,image.getWidth(),image.getHeight()); g.scale(scale,scale); view.paint(g); }
        finally { g.dispose(); }
        return image;
    }
    private static int differences(BufferedImage first,BufferedImage next) {
        int count=0;
        for (int y=0;y<first.getHeight();y++) for (int x=0;x<first.getWidth();x++)
            if (first.getRGB(x,y)!=next.getRGB(x,y)) count++;
        return count;
    }
    private static JDialog ready(JDialog d) {
        d.setFocusableWindowState(false); d.addNotify(); d.validate(); return d;
    }
    private static void actions(JDialog d) {
        List<String> labels=children(d).stream().filter(c -> c instanceof JButton b && b.getText()!=null && !b.getText().isBlank())
                .map(c -> ((JButton)c).getText()).toList();
        check(labels.subList(labels.size()-3,labels.size()).equals(List.of("Cancel","Apply","Done")),"Actual settings use Cancel, Apply, Done in reading/tab order: "+d.getTitle());
        check(d.getRootPane().getDefaultButton()==button(d,"Done"),"Done is the consistent accepting/closing action: "+d.getTitle());
        check(children(d).stream().noneMatch(c -> c instanceof JLabel l && (l.getText().contains("Choose Apply")
                || l.getText().contains("choose Apply") || l.getText().contains("Changes apply immediately"))),"No tautological save helper: "+d.getTitle());
    }
    private static void settings(Workspace w) throws Exception {
        w.appearanceSettings(w.appearanceSettings().withLayout(w.appearanceSettings().outline(),Appearance.PaneInfo.defaults()));
        JDialog focus=ready(AppearanceDialogs.windowFocusDialog(w)); actions(focus);
        MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{new JMenuBar()});
        Object escape=focus.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("ESCAPE"));
        focus.getRootPane().getActionMap().get(escape).actionPerformed(new ActionEvent(focus,0,"escape"));
        check(focus.isDisplayable() && MenuSelectionManager.defaultManager().getSelectedPath().length==0,
                "Shared dialog Escape dismisses an active menu path before discarding a settings draft.");
        check(!button(focus,"Apply").isEnabled(),"Apply starts clean.");
        var choices=boxes(focus);
        choices.get(0).setSelected(!choices.get(0).isSelected()); button(focus,"Apply").doClick(0);
        boolean applied=w.appearanceSettings().windowOutline();
        check(focus.isDisplayable() && !button(focus,"Apply").isEnabled(),"Apply persists and stays open with a new clean baseline.");
        choices.get(1).setSelected(!choices.get(1).isSelected()); button(focus,"Apply").doClick(0);
        Appearance baseline=w.appearanceSettings(); boolean tint=w.highlightActive();
        choices.get(2).setSelected(!choices.get(2).isSelected()); button(focus,"Cancel").doClick(0);
        check(!focus.isDisplayable() && w.appearanceSettings().equals(baseline) && w.highlightActive()==tint
                && baseline.windowOutline()==applied,"Cancel discards only edits after multiple Apply actions.");
        focus=ready(AppearanceDialogs.windowFocusDialog(w));
        boxes(focus).get(2).setSelected(!tint); button(focus,"Done").doClick(0);
        check(!focus.isDisplayable() && w.highlightActive()!=tint,"Done applies a pending change and closes.");
        JDialog info=ready(AppearanceDialogs.paneInfoDialog(w)); actions(info);
        boxes(info).get(0).doClick(0); button(info,"Apply").doClick(0);
        check(info.isDisplayable() && !w.appearanceSettings().info().header(),"Pane information Apply stays open and hides the header.");
        boxes(info).get(5).doClick(0); button(info,"Done").doClick(0);
        check(!info.isDisplayable() && !w.appearanceSettings().info().footer(),"Pane information Done applies and closes.");
        JDialog fonts=ready(AppearanceDialogs.fontsDialog(w)); actions(fonts);
        JComboBox<?> family=children(fonts).stream().filter(c -> c instanceof JComboBox<?>).map(c -> (JComboBox<?>)c).findFirst().orElseThrow();
        String candidate=TerminalFonts.installed().contains("Ubuntu Mono") ? "Ubuntu Mono" : "Automatic";
        String expected=TerminalFonts.resolve(new Appearance.FontChoice(candidate,"")).getFamily();
        family.setSelectedItem(candidate); button(fonts,"Apply").doClick(0);
        check(fonts.isDisplayable() && w.terminalFont().getFamily().equals(expected) && !button(fonts,"Apply").isEnabled(),
                "Actual font Apply installs "+candidate+" and updates its baseline without closing.");
        family.setSelectedItem("Consolas"); button(fonts,"Cancel").doClick(0);
        check(w.terminalFont().getFamily().equals(expected),"Cancel keeps the most recently applied font, not the opening value.");
        layout(w); paint(w.active().view(),1);
        Object glyphs=field(w.active().view(),"rendered");
        w.appearanceSettings(w.appearanceSettings().withWindowOutline(!w.appearanceSettings().windowOutline()));
        check(glyphs==field(w.active().view(),"rendered"),"Non-font settings no longer reload fonts or discard glyph caches.");
        JDialog shortcuts=ready(((AppShortcuts)field(w,"shortcuts")).dialog()); actions(shortcuts);
        JTable table=children(shortcuts).stream().filter(c -> c instanceof JTable).map(c -> (JTable)c).findFirst().orElseThrow();
        for (int row=0;row<table.getRowCount();row++) if (table.getValueAt(row,0).equals("Next pane")) table.setValueAt("ctrl F10",row,1);
        button(shortcuts,"Apply").doClick(0);
        check(shortcuts.isDisplayable() && w.shortcutOverrides().get("Next pane").equals("ctrl pressed F10") && !button(shortcuts,"Apply").isEnabled(),
                "Actual shortcut Apply commits the full mapping and keeps the editor open.");
        for (int row=0;row<table.getRowCount();row++) if (table.getValueAt(row,0).equals("Next pane")) table.setValueAt("ctrl F9",row,1);
        button(shortcuts,"Cancel").doClick(0);
        check(w.shortcutOverrides().get("Next pane").equals("ctrl pressed F10"),"Shortcut cancel retains the last Apply baseline.");
        ThemeDesigner designer=new ThemeDesigner(w); ready(designer);
        ((JTextField)field(designer,"name")).setText("Refinement saved");
        button(designer,"Save").doClick(0);
        check(w.appearanceSettings().themes().stream().anyMatch(t -> t.name().equals("Refinement saved")) && button(designer,"Apply").isEnabled(),
                "Save retains its reusable-library responsibility; Apply can subsequently activate the saved theme.");
        button(designer,"Apply").doClick(0);
        check(designer.isDisplayable() && w.appearanceSettings().selectedTheme().equals("Refinement saved") && !button(designer,"Apply").isEnabled(),
                "Designer Apply activates and resets the draft baseline without closing.");
        ((JTextField)field(designer,"name")).setText("Refinement done"); button(designer,"Done").doClick(0);
        check(!designer.isDisplayable() && w.appearanceSettings().selectedTheme().equals("Refinement done"),"Designer Done saves/applies and closes.");
    }
    private static void dragZone(Workspace w) throws Exception {
        SessionPane pane=w.active();
        Appearance.PaneInfo hidden=new Appearance.PaneInfo(false,false,false,false,false,false,false,false,false,false);
        w.appearanceSettings(w.appearanceSettings().withLayout(false,hidden)); layout(w);
        JLayeredPane stage=(JLayeredPane)field(pane,"stage"); JComponent zone=(JComponent)field(pane,"dragZone");
        JButton close=(JButton)field(pane,"close");
        check(zone.isVisible() && zone.getY()==0 && zone.getHeight()==close.getPreferredSize().height && stage.getHeight()==pane.getHeight(),
                "Hidden-header drag strip is at X height with zero layout space.");
        check(zone.getCursor().getType()==Cursor.MOVE_CURSOR && zone.getTransferHandler()==pane.getTransferHandler(),
                "Hidden strip uses the same production swap handler and useful drag cursor.");
        check(!zone.contains(close.getX()+close.getWidth()/2,close.getY()+close.getHeight()/2),"Close remains independently hittable through the drag strip.");
        check(SwingUtilities.getDeepestComponentAt(stage,20,zone.getHeight()+15)!=zone,"Terminal selection/input below the strip is unaffected.");
        int cursor=pane.screen().getCurrentPos();
        zone.dispatchEvent(new MouseEvent(zone,MouseEvent.MOUSE_PRESSED,1,InputEvent.BUTTON1_DOWN_MASK,25,12,1,false,MouseEvent.BUTTON1));
        zone.dispatchEvent(new MouseEvent(zone,MouseEvent.MOUSE_DRAGGED,2,InputEvent.BUTTON1_DOWN_MASK,26,12,0,false,MouseEvent.NOBUTTON));
        zone.dispatchEvent(new MouseEvent(zone,MouseEvent.MOUSE_RELEASED,3,0,26,12,1,false,MouseEvent.BUTTON1));
        check(pane.screen().getCurrentPos()==cursor,"A below-threshold top-strip gesture neither moves the host cursor nor starts a drag.");
        for (boolean frame:List.of(false,true)) for (double scale:List.of(1.0,1.25,1.5,1.75,2.0)) {
            w.appearanceSettings(w.appearanceSettings().withWindowOutline(frame).withLayout(true,hidden));
            w.validate(); layout(w);
            BufferedImage image=paint(w.getRootPane(),scale);
            for (Component c:children(w.getRootPane())) if (c instanceof JSplitPane split) {
                Component divider=((javax.swing.plaf.basic.BasicSplitPaneUI)split.getUI()).getDivider();
                Rectangle bounds=SwingUtilities.convertRectangle(divider.getParent(),divider.getBounds(),w.getRootPane());
                int lines=0;
                if (split.getOrientation()==JSplitPane.HORIZONTAL_SPLIT) {
                    int y=(int)((bounds.y+40)*scale);
                    for (int x=(int)((bounds.x-3)*scale);x<(int)((bounds.x+bounds.width+3)*scale);x++)
                        if (image.getRGB(x,y)==Ui.LINE.getRGB()) lines++;
                } else {
                    int x=(int)((bounds.x+40)*scale);
                    for (int y=(int)((bounds.y-3)*scale);y<(int)((bounds.y+bounds.height+3)*scale);y++)
                        if (image.getRGB(x,y)==Ui.LINE.getRGB()) lines++;
                }
                check(lines==1,"Active pane outline never adds a second middle divider: "+scale+"/window="+frame);
            }
        }
    }
    private static void zoomAndInk(Workspace w) throws Exception {
        SessionPane pane=w.active(); TerminalView view=pane.view();
        Method position=TerminalView.class.getDeclaredMethod("position",Point.class); position.setAccessible(true);
        List<String> fonts=new ArrayList<>(List.of("Consolas"));
        if (TerminalFonts.installed().contains("Ubuntu Mono")) fonts.addFirst("Ubuntu Mono");
        else System.out.println("Ubuntu Mono is unavailable; its optional coverage check is not demonstrated on this machine.");
        for (String font:fonts) {
            w.appearanceSettings(w.appearanceSettings().withLayout(false,w.appearanceSettings().info()).withFont(new Appearance.FontChoice(font,"")));
            for (double scale:List.of(1.0,1.25,1.5,1.75,2.0)) {
                for (double zoom:List.of(.5,.6,.8)) {
                    w.putProfile(pane.profile().withZoom(zoom)); view.setFit(false); w.setSize(1801,1101); w.validate(); layout(w);
                    BufferedImage normal=paint(view,scale);
                    double x=(double)field(view,"originX"),y=(double)field(view,"originY");
                    double width=(double)field(view,"cellWidth"),height=(double)field(view,"cellHeight");
                    check(Math.abs(x-(view.getWidth()-80*width)/2)<=1/scale && Math.abs(y-(view.getHeight()-24*height)/2)<=1/scale,
                            "Manual zoom stays centered on both axes: "+font+"/"+scale+"/"+zoom);
                    check(Math.abs(x*scale-Math.rint(x*scale))<1e-8 && Math.abs(y*scale-Math.rint(y*scale))<1e-8,
                            "Centered origins are crisp device pixels: "+x+","+y+"/"+scale);
                    Point cell=new Point((int)(x+5.5*width),(int)(y+3.5*height));
                    check((int)position.invoke(view,cell)==3*80+5,"Shared zoom geometry maps a cell-center click back to the correct host position.");
                    check(normal.getRGB(0,0)==view.getBackground().getRGB(),"Resize/zoom clears the previous backing pixels.");
                }
                Object frame=field(view,"rendered"); Map<?,?> masks=(Map<?,?>)field(view,"glyphMasks");
                Map<?,?> cached=new IdentityHashMap<>(masks);
                BufferedImage normal=paint(view,scale);
                long start=System.nanoTime(); w.toggleFlux(); set(view,"fluxPhase",.25); BufferedImage flux=paint(view,scale);
                double elapsed=(System.nanoTime()-start)/1_000_000.0;
                check(differences(normal,flux)>50 && elapsed<150,"Next actual paint changes visible ink promptly: "+font+"/"+scale+" "+elapsed+"ms");
                check(frame==field(view,"rendered") && cached.equals(masks),"None/Flux reuse the exact glyph vectors and per-channel LCD masks.");
                Color[] palette=HostPalette.colors(pane.profile().theme()); Paint[] paints=(Paint[])field(view,"fluxPaints");
                for (int n=0;n<paints.length;n++) paints[n]=palette[n];
                set(view,"sharedPalette",null);
                BufferedImage sameColor=paint(view,scale);
                check(differences(normal,sameColor)==0,"Identical colors give bit-identical normal/Flux glyph coverage, AA and weight: "+font+"/"+scale);
                w.toggleFlux(); check(differences(normal,paint(view,scale))==0,"None returns the exact prior pixels without a missing-text/cache-miss frame.");
                check(view.getScrollableTracksViewportWidth() && view.getScrollableTracksViewportHeight(),"Manual content smaller than the viewport expands for centering.");
                view.setFit(true); paint(view,scale); w.setSize(1701,1001); layout(w); paint(view,scale);
                view.setFit(false); paint(view,scale); view.setFit(true); paint(view,scale);
                check(pane.screen().getRows()==24 && pane.screen().getColumns()==80,"Fit/zoom/resize/zoom/fit never changes the host presentation space.");
            }
        }
        view.setFit(false); w.putProfile(pane.profile().withZoom(3)); layout(w); paint(view,1);
        check(!view.getScrollableTracksViewportWidth(),"Genuinely oversize content retains the existing horizontal scroll path.");
        view.setFit(true); paint(view,1);
        ImageIO.write(paint(w.getRootPane(),1.5),"png",Path.of("build","fish-refinement-offscreen.png").toFile());
    }
    private static void profiles(Workspace w) throws Exception {
        ProfilesDialog dialog=edt(() -> { ProfilesDialog d=new ProfilesDialog(w,w.active().profile()); ready(d); return d; });
        UUID id=edt(() -> w.active().profile().id());
        edt(() -> {
            check(!button(dialog,"Apply").isEnabled() && button(dialog,"Done")!=null && button(dialog,"Save & connect")!=null,
                    "Profile editor retains explicit connection/save responsibility beside Apply and Done.");
            ((JTextField)field(dialog,"name")).setText("Applied workstation"); button(dialog,"Apply").doClick(0); return null;
        });
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (edt(() -> (boolean)field(dialog,"saving"))) { if (System.nanoTime()>deadline) throw new AssertionError("Profile Apply timed out."); Thread.sleep(10); }
        edt(() -> {
            check(dialog.isDisplayable() && w.profileList().stream().anyMatch(p -> p.id().equals(id) && p.name().equals("Applied workstation")),
                    "Actual asynchronous profile Apply saves and stays open, with credentials untouched.");
            ((JTextField)field(dialog,"name")).setText("Done workstation"); button(dialog,"Done").doClick(0); return null;
        });
        deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (edt(dialog::isDisplayable)) { if (System.nanoTime()>deadline) throw new AssertionError("Profile Done timed out."); Thread.sleep(10); }
        check(edt(() -> w.profileList().stream().anyMatch(p -> p.id().equals(id) && p.name().equals("Done workstation")) && w.active().connected()),
                "Profile Done saves/closes without reconnecting an unchanged connection.");
    }
    public static void main(String[] args) throws Exception {
        Ui.install(); Path root=Files.createTempDirectory(Path.of("build"),"refinement-smoke-");
        CountDownLatch closed=new CountDownLatch(1);
        List<Throwable> errors=new CopyOnWriteArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((thread,error) -> { errors.add(error); error.printStackTrace(); });
        try (DemoHost host=new DemoHost()) {
            StateStore store=new StateStore(root);
            Workspace w=edt(() -> {
                Workspace window=new Workspace(store,Main.demoState(host.port()),Path.of("scripts","credential-store.ps1"),false);
                window.setFocusableWindowState(false); window.onClosed(closed::countDown); window.addNotify(); window.validate(); window.connectAll(); return window;
            });
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while (!edt(() -> w.sessionPanes().stream().allMatch(p -> p.connected() && p.screen().getScreenFields().getSize()>0))) {
                    if (System.nanoTime()>deadline) throw new AssertionError("Synthetic host unavailable."); Thread.sleep(20);
                }
                edt(() -> { dragZone(w); zoomAndInk(w); settings(w); return null; });
                profiles(w);
                edt(() -> { w.saveAsync(); return null; });
                ((ExecutorService)field(w,"persistence")).submit(() -> {}).get(5,TimeUnit.SECONDS);
                StateStore.State loaded=store.load();
                check(loaded.appearance().equals(edt(w::appearanceSettings)) && loaded.shortcuts().equals(edt(w::shortcutOverrides))
                        && loaded.profiles().equals(edt(w::profileList)),"All production settings/profile Apply/Done paths round-trip together.");
                check(host.failures().isEmpty() && errors.isEmpty(),"No hidden asynchronous UI/protocol errors: "+errors);
                check(!w.isVisible(),"Checks never expose the shared desktop or use OS input.");
            } finally {
                edt(() -> { for (Window child:w.getOwnedWindows()) child.dispose(); w.close(false); return null; });
                if (!closed.await(5,TimeUnit.SECONDS)) throw new AssertionError("Workspace did not close.");
            }
        } finally {
            try (var paths=Files.walk(root)) { for (Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
        System.out.println("PASS refinement: "+checks+" production geometry/glyph/settings/async-profile checks, no desktop input.");
    }
}
