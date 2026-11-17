package fish.ui;

import java.awt.Color;
import java.awt.Paint;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javax.imageio.ImageIO;
import fish.Main;
import fish.demo.DemoHost;
import fish.model.*;

/** Production JavaFX paths, hidden stages and synthetic data; no desktop input or credentials. */
public final class RefinementSmoke {
    private static int checks;
    private static void check(boolean value,String text) { checks++; if (!value) throw new AssertionError(text); }
    private static Object field(Object owner,String name) throws Exception { return FxTestSupport.field(owner,name); }
    private static void set(Object owner,String name,Object value) throws Exception { FxTestSupport.setField(owner,name,value); }
    private static <T> T edt(Callable<T> work) throws Exception { return FxTestSupport.fx(work); }
    private static ButtonBase button(javafx.stage.Stage s,String text) { return FxTestSupport.button(s,text); }
    private static BufferedImage paint(TerminalView view,double scale) { return view.paintToImage(scale); }
    private static int differences(BufferedImage first,BufferedImage next) {
        int count=0;
        for (int y=0;y<first.getHeight();y++) for (int x=0;x<first.getWidth();x++)
            if (first.getRGB(x,y)!=next.getRGB(x,y)) count++;
        return count;
    }

    private static void settings(Workspace w) throws Exception {
        w.appearanceSettings(w.appearanceSettings().withLayout(w.appearanceSettings().outline(),Appearance.PaneInfo.defaults()));
        w.preferences("Appearance");
        Preferences prefs=w.tutorialPreferences();
        String candidate=TerminalFonts.installed().contains("Ubuntu Mono") ? "Ubuntu Mono" : "Automatic";
        String expected=TerminalFonts.resolve(new Appearance.FontChoice(candidate,"")).getFamily();
        @SuppressWarnings("unchecked") ComboBox<String> family=(ComboBox<String>)field(prefs,"family");
        family.setValue(candidate);
        UsabilityIterationSmoke.settle(() -> !prefs.saving(),"Font choice did not autosave");
        check(w.terminalFont().getFamily().equals(expected),"Actual font preference installs "+candidate);
        prefs.forceDispose();

        FxTestSupport.layout(w);
        paint(w.active().view(),1);
        Object glyphs=field(w.active().view(),"rendered");
        w.appearanceSettings(w.appearanceSettings().withWindowOutline(!w.appearanceSettings().windowOutline()));
        check(glyphs==field(w.active().view(),"rendered"),"Non-font settings no longer reload fonts or discard glyph caches.");

        w.preferences("Appearance");
        Preferences colorPrefs=w.tutorialPreferences();
        FxTestSupport.layout(colorPrefs.dialog());
        javafx.scene.layout.StackPane hueBar=colorPrefs.hueBar();
        double barW=Math.max(120.0,hueBar.getWidth());
        double clickX=8.0+0.60*Math.max(1.0,barW-16.0);
        hueBar.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED,clickX,12,clickX,12,MouseButton.PRIMARY,1,false,false,false,false,true,false,false,true,false,false,null));
        hueBar.fireEvent(new MouseEvent(MouseEvent.MOUSE_RELEASED,clickX,12,clickX,12,MouseButton.PRIMARY,1,false,false,false,false,false,false,false,true,false,false,null));
        check(Profile.isCustomColor(w.active().profile().theme()) && Preferences.CUSTOM_COLOR.equals(colorPrefs.paletteCombo().getValue()),
                "Clicking the rainbow hue bar selects a custom #RRGGBB primary color immediately: "+w.active().profile().theme());
        UsabilityIterationSmoke.settle(() -> !colorPrefs.saving(),"Custom primary color autosave did not settle");
        colorPrefs.forceDispose();
    }

    private static void dragZone(Workspace w) throws Exception {
        SessionPane pane=w.active();
        Appearance.PaneInfo hidden=new Appearance.PaneInfo(false,false,false,false,false,false,false,false,false,false);
        w.appearanceSettings(w.appearanceSettings().withLayout(false,hidden));
        FxTestSupport.layout(w);
        Region stage=(Region)field(pane,"stage");
        Region zone=(Region)field(pane,"dragZone");
        Button close=(Button)field(pane,"close");
        check(zone.isVisible() && zone.getLayoutY()==0 && zone.getHeight()>=24 && Math.abs(stage.getHeight()-pane.getHeight())<=2,
                "Hidden-header drag strip is at X height with zero layout space.");
        check(zone.getCursor()==Cursor.MOVE,"Hidden strip uses a move drag cursor.");
        check(zone.getWidth()<=stage.getWidth()-close.getWidth()+1,"Close remains independently hittable beside the drag strip.");
        int cursor=pane.screen().getCurrentPos();
        zone.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED,25,12,25,12,MouseButton.PRIMARY,1,false,false,false,false,true,false,false,true,false,false,null));
        zone.fireEvent(new MouseEvent(MouseEvent.MOUSE_RELEASED,26,12,26,12,MouseButton.PRIMARY,1,false,false,false,false,false,false,false,true,false,false,null));
        check(pane.screen().getCurrentPos()==cursor,"A click on the top strip neither moves the host cursor nor starts a drag.");
    }

    private static void zoomAndInk(Workspace w) throws Exception {
        SessionPane pane=w.active(); TerminalView view=pane.view();
        Method position=TerminalView.class.getDeclaredMethod("position",double.class,double.class); position.setAccessible(true);
        List<String> fonts=new ArrayList<>(List.of("Consolas"));
        if (TerminalFonts.installed().contains("Ubuntu Mono")) fonts.addFirst("Ubuntu Mono");
        for (String font:fonts) {
            w.appearanceSettings(w.appearanceSettings().withLayout(false,w.appearanceSettings().info()).withFont(new Appearance.FontChoice(font,"")));
            for (double scale:List.of(1.0,1.25,1.5,1.75,2.0)) {
                for (double zoom:List.of(.5,.6,.8)) {
                    w.putProfile(pane.profile().withZoom(zoom)); view.setFit(false); w.setSize(1801,1101); FxTestSupport.layout(w);
                    BufferedImage normal=paint(view,scale);
                    double x=(double)field(view,"originX"),y=(double)field(view,"originY");
                    double width=(double)field(view,"cellWidth"),height=(double)field(view,"cellHeight");
                    check(Math.abs(x-(view.getWidth()-80*width)/2)<=1/scale && Math.abs(y-(view.getHeight()-24*height)/2)<=1/scale,
                            "Manual zoom stays centered on both axes: "+font+"/"+scale+"/"+zoom);
                    check(Math.abs(x*scale-Math.rint(x*scale))<1e-8 && Math.abs(y*scale-Math.rint(y*scale))<1e-8,
                            "Centered origins are crisp device pixels: "+x+","+y+"/"+scale);
                    check((int)position.invoke(view,x+5.5*width,y+3.5*height)==3*80+5,"Shared zoom geometry maps a cell-center click back to the correct host position.");
                    check(normal.getRGB(0,0)==view.getTerminalBackground().getRGB(),"Resize/zoom clears the previous backing pixels.");
                }
                Object frame=field(view,"rendered"); Map<?,?> masks=(Map<?,?>)field(view,"glyphMasks");
                Map<?,?> cached=new IdentityHashMap<>(masks);
                BufferedImage normal=paint(view,scale);
                long start=System.nanoTime(); w.toggleFlux(); set(view,"fluxPhase",.25); BufferedImage flux=paint(view,scale);
                double elapsed=(System.nanoTime()-start)/1_000_000.0;
                check(differences(normal,flux)>50 && elapsed<250,"Next actual paint changes visible ink promptly: "+font+"/"+scale+" "+elapsed+"ms");
                check(frame==field(view,"rendered") && cached.equals(masks),"None/Flux reuse the exact glyph vectors and per-channel LCD masks.");
                Color[] palette=HostPalette.colors(pane.profile().theme()); Paint[] paints=(Paint[])field(view,"fluxPaints");
                for (int n=0;n<paints.length;n++) paints[n]=palette[n];
                set(view,"sharedPalette",null);
                BufferedImage sameColor=paint(view,scale);
                check(differences(normal,sameColor)==0,"Identical colors give bit-identical normal/Flux glyph coverage, AA and weight: "+font+"/"+scale);
                w.toggleFlux(); check(differences(normal,paint(view,scale))==0,"None returns the exact prior pixels without a missing-text/cache-miss frame.");
                view.setFit(true); paint(view,scale); w.setSize(1701,1001); FxTestSupport.layout(w); paint(view,scale);
                view.setFit(false); paint(view,scale); view.setFit(true); paint(view,scale);
                check(pane.screen().getRows()==24 && pane.screen().getColumns()==80,"Fit/zoom/resize/zoom/fit never changes the host presentation space.");
            }
        }
        ScrollPane sp=(ScrollPane)field(pane,"scroll");
        check(sp.isFitToWidth() && sp.isFitToHeight(),"Manual content smaller than the viewport expands for centering.");
        view.setFit(false); w.putProfile(pane.profile().withZoom(3)); FxTestSupport.layout(w); paint(view,1);
        check(!sp.isFitToWidth(),"Genuinely oversize content retains the horizontal scroll path.");
        view.setFit(true); paint(view,1);
        ImageIO.write(FxTestSupport.snapshot(w,1.5),"png",Path.of("build","fish-refinement-offscreen.png").toFile());
    }

    private static void profiles(Workspace w) throws Exception {
        ProfilesDialog dialog=edt(() -> new ProfilesDialog(w,w.active().profile()));
        UUID id=edt(() -> w.active().profile().id());
        edt(() -> {
            check(button(dialog.stage(),"Apply").isDisabled() && button(dialog.stage(),"Done")!=null && button(dialog.stage(),"Save & connect")!=null,
                    "Profile editor retains explicit connection/save responsibility beside Apply and Done.");
            ((TextField)field(dialog,"name")).setText("Applied workstation"); button(dialog.stage(),"Apply").fire(); return null;
        });
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (edt(() -> dialog.editor.saving())) { if (System.nanoTime()>deadline) throw new AssertionError("Profile Apply timed out."); Thread.sleep(10); }
        edt(() -> {
            check(w.profileList().stream().anyMatch(p -> p.id().equals(id) && p.name().equals("Applied workstation")),
                    "Actual asynchronous profile Apply saves and stays open, with credentials untouched.");
            ((TextField)field(dialog,"name")).setText("Done workstation"); button(dialog.stage(),"Done").fire(); return null;
        });
        deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (edt(() -> dialog.editor.saving())) { if (System.nanoTime()>deadline) throw new AssertionError("Profile Done timed out."); Thread.sleep(10); }
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
                window.onClosed(closed::countDown); FxTestSupport.layout(window); window.connectAll(); return window;
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
                edt(() -> { w.close(false); return null; });
                if (!closed.await(5,TimeUnit.SECONDS)) throw new AssertionError("Workspace did not close.");
            }
        } finally {
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
        }
        System.out.println("PASS refinement: "+checks+" production geometry/glyph/settings/async-profile checks, no desktop input.");
        Ui.finishTestRun();
    }
}
