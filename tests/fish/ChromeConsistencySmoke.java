package fish.ui;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import fish.Main;
import fish.model.*;

/** Synthetic, offscreen JavaFX CSS surface and appearance migration checks. No shared desktop input. */
public final class ChromeConsistencySmoke {
    private static int checks;
    private static void check(boolean value,String text) { checks++; if (!value) throw new AssertionError(text); }
    private static Object field(Object owner,String name) throws Exception { return FxTestSupport.field(owner,name); }
    private static <T> T edt(Callable<T> work) throws Exception { return FxTestSupport.fx(work); }

    private static void cssScaleChecks(double scale) {
        for (Control control:List.of(new TextField("Synthetic"),new Button("Apply"),
                new ToggleButton("Details"),new ScrollPane(new ListView<>()))) {
            StackPane root=new StackPane(control);
            Scene scene=new Scene(root,180,61);
            Ui.styleScene(scene);
            BufferedImage image=FxTestSupport.snapshot(root,scale);
            check(image.getWidth()>=Math.round(180*scale)-1 && image.getHeight()>=Math.round(61*scale)-1,
                    "JavaFX control snapshots cleanly at scale "+scale+": "+control.getClass().getSimpleName());
        }

    }

    private static void fieldFaces(boolean gradient) {
        for (Control control:List.of(new TextField(),new PasswordField(),new TextArea(),new ComboBox<>(),
                new ListView<>(),new MarkdownView("Notes"))) {
            control.setPrefSize(220,58);
            StackPane root=new StackPane(control);
            Ui.styleScene(new Scene(root,220,58));
            BufferedImage image=FxTestSupport.snapshot(root,1.0);
            int y=image.getHeight()/2;
            int a=image.getRGB(16,y-6),b=image.getRGB(image.getWidth()-32,y+6);
            int left=(a>>16&255)+(a>>8&255)+(a&255),right=(b>>16&255)+(b>>8&255)+(b&255);
            check(gradient ? left>right : left==right,control.getClass().getSimpleName()
                    +" field pixels in "+(gradient?"gradient":"flat")+" mode: "+left+"/"+right);
        }
    }

    private static void migration(Path root) throws Exception {
        try (StateStore store=new StateStore(root)) {
            StateStore.State fresh=store.load();
            check(fresh.appearance().windowOutline() && store.loadNotice().isEmpty(),
                    "Fresh default is outline ON, with no error or migration notice.");
            EnumMap<ColorRole,Integer> roles=new EnumMap<>(ColorTheme.builtin(AppTheme.Charcoal).colors());
            roles.put(ColorRole.DIVIDER,0x354549);
            ColorTheme theme=new ColorTheme("Legacy fixture",roles);
            Appearance old=fresh.appearance().withTheme(theme.name(),List.of(theme)).withLayout(false,Appearance.PaneInfo.defaults());
            store.save(new StateStore.State(fresh.profiles(),fresh.layout(),fresh.width(),fresh.height(),fresh.appTheme(),
                    fresh.lastProfile(),fresh.shortcuts(),false,old));
            Properties p=new Properties();
            try (var in=Files.newInputStream(store.file())) { p.loadFromXML(in); }
            p.setProperty("version","5"); p.remove("appearance.windowOutline");
            p.remove("appearance.cursorStyle"); p.remove("addProfile"); p.remove("savedLayouts"); p.remove("selectedLayout");
            p.remove("windows"); p.remove("info.footerDevice");
            p.keySet().removeIf(key -> key.toString().startsWith("updates."));
            p.setProperty("profile."+fresh.profiles().getFirst().id()+".authentication","PASSWORD");
            p.setProperty("appearance.theme.0.LINE","#EEDDCC"); p.setProperty("appearance.theme.0.OUTLINE","#FF0011");
            try (var out=Files.newOutputStream(store.file())) { p.storeToXML(out,"Synthetic legacy appearance"); }
            StateStore.State loaded=store.load();
            check(loaded.appearance().windowOutline() && !loaded.appearance().outline() && !loaded.highlightActive(),
                    "Legacy workspaces get window outline ON without changing chosen active outline/tint.");
            check(loaded.appearance().custom().orElseThrow().rgb(ColorRole.DIVIDER)==0x354549
                    && loaded.appearance().custom().orElseThrow().colors().size()==ColorRole.values().length,
                    "Legacy LINE/OUTLINE colors migrate to the existing shared divider; no dead designer controls.");
            check(store.loadNotice().contains("divider") && store.loadNotice().contains("enabled once"),"Linked-role/default-outline migration is reported explicitly.");
            Appearance chosen=loaded.appearance().withWindowOutline(false);
            StateStore.State state=new StateStore.State(loaded.profiles(),loaded.layout(),loaded.width(),loaded.height(),loaded.appTheme(),
                    loaded.lastProfile(),loaded.shortcuts(),loaded.highlightActive(),chosen);
            store.save(state);
            check(store.load().equals(state) && store.loadNotice().isEmpty(),"Explicit off choice survives restart without another migration.");
            try (var in=Files.newInputStream(store.file())) { p.loadFromXML(in); }
            p.setProperty("appearance.windowOutline","malformed");
            try (var out=Files.newOutputStream(store.file())) { p.storeToXML(out,"Invalid setting"); }
            boolean rejected=false;
            try { store.load(); } catch (java.io.IOException e) { rejected=e.getMessage().contains("Invalid boolean"); }
            check(rejected,"Genuinely malformed appearance still fails explicitly; no invented clean state.");
        }
    }

    private static StateStore.State flush(Workspace w,StateStore store) throws Exception {
        edt(() -> { w.saveAsync(); return null; });
        ((ExecutorService)field(w,"persistence")).submit(() -> {}).get(5,TimeUnit.SECONDS);
        return store.load();
    }

    private static void windowFocusChecks(Workspace w,StateStore store) throws Exception {
        for (int mask=0;mask<8;mask++) {
            final int flags=mask;
            edt(() -> {
                Stage dialog=AppearanceDialogs.windowFocusDialog(w);
                Preferences prefs=w.tutorialPreferences();
                CheckBox win=(CheckBox)field(prefs,"window");
                CheckBox pane=(CheckBox)field(prefs,"pane");
                CheckBox tint=(CheckBox)field(prefs,"tint");
                win.setSelected((flags&1)!=0);
                pane.setSelected((flags&2)!=0);
                tint.setSelected((flags&4)!=0);
                UsabilityIterationSmoke.settle(() -> !prefs.saving(),"Window/focus Preferences autosave did not settle");
                check(w.appearanceSettings().windowOutline()==((flags&1)!=0)
                        && w.appearanceSettings().outline()==((flags&2)!=0)
                        && w.highlightActive()==((flags&4)!=0),
                        "Preferences updates all three choices consistently: "+flags);
                check(!dialog.isShowing(),"Preferences remains off the shared desktop: "+flags);
                prefs.closeSettings();
                UsabilityIterationSmoke.settle(prefs::ended,"Preferences close did not settle");
                return null;
            });
            StateStore.State loaded=flush(w,store);
            check(loaded.appearance().windowOutline()==((flags&1)!=0) && loaded.appearance().outline()==((flags&2)!=0)
                    && loaded.highlightActive()==((flags&4)!=0),"All three applied choices reload together from disk: "+flags);
        }
    }

    private static void workspaceChecks(Workspace w,double scale) throws Exception {
        w.appearanceSettings(w.appearanceSettings().withWindowOutline(true));
        FxTestSupport.layout(w);
        Region shell=(Region)w.scene().getRoot();
        check(w.appearanceSettings().windowOutline() && shell.getStyleClass().contains("fish-window-outlined"),
                "Default workspace has the enabled CSS window outline.");
        check(w.stage().getStyle()==StageStyle.EXTENDED,"Workspace uses JavaFX StageStyle.EXTENDED.");
        WindowChrome chrome=(WindowChrome)field(w,"chrome");
        MenuBar menus=(MenuBar)field(chrome,"menus");
        Menu view=menus.getMenus().stream().filter(c -> c.getText().equals("View")).findFirst().orElseThrow();
        List<String> quick=view.getItems().stream().filter(i -> !(i instanceof SeparatorMenuItem))
                .map(i -> Objects.toString(i.getProperties().get("fish.title"),i.getText())).toList();
        check(quick.equals(List.of("Fit host screen","Zoom in","Zoom out")),"Quick View contains concise view actions.");

        Appearance saved=w.appearanceSettings();
        ColorTheme synthetic=new ColorTheme("Synthetic",ColorTheme.builtin(AppTheme.Charcoal).colors());
        w.appearanceSettings(saved.withTheme(synthetic.name(),List.of(synthetic)));
        w.preferences("Appearance");
        Preferences prefs=w.tutorialPreferences();
        Stage settings=prefs.dialog();
        FxTestSupport.layout(settings);
        Region shellBody=(Region)settings.getScene().lookup(".fish-settings");
        Region rail=(Region)shellBody.lookup(".fish-sidebar");
        Region footer=(Region)shellBody.lookup(".fish-settings-footer");
        double initialWidth=settings.getWidth();
        for (double width:new double[]{settings.getMinWidth(),840,980,1280}) {
            settings.setWidth(width);
            FxTestSupport.layout(settings);
            var first=rail.lookupAll(".list-cell").stream().filter(n -> n instanceof ListCell<?> cell
                    && "Appearance".equals(cell.getItem())).findFirst().orElseThrow();
            var category=FxTestSupport.boundsIn(first,shellBody);
            var caption=FxTestSupport.boundsIn(settings.getScene().lookup(".fish-settings-header"),shellBody);
            var surface=FxTestSupport.boundsIn(settings.getScene().lookup(".fish-settings-rail"),shellBody);
            check(surface.y==caption.y && surface.getMaxX()==rail.getWidth()
                    && surface.getMaxY()==shellBody.getHeight(),"Rail divider extends through the caption to the window top.");
            var heading=FxTestSupport.boundsIn(shellBody.lookupAll(".fish-page-heading").stream()
                    .filter(n -> n.getParent().isVisible()).findFirst().orElseThrow(),shellBody);
            check(caption.getMaxY()<=heading.y && caption.getMaxY()<=category.y
                    && Math.abs(heading.getCenterY()-category.getCenterY())<=2,
                    "Rail and page start together below the caption: "+width+" "+category+" "+heading+" "+caption);
            CheckBox gradients=prefs.gradientCheck();
            var ink=(javafx.scene.text.Text)gradients.lookup(".text");
            var button=FxTestSupport.boundsIn(gradients,shellBody);
            var port=FxTestSupport.boundsIn(shellBody.lookup(".fish-settings-body").getParent(),shellBody);
            check(gradients.getText().equals(ink.getText()) && button.getMaxX()<=port.getMaxX(),
                    "Full gradient label fits on screen at "+width+": "+ink.getText());
        }
        settings.setWidth(initialWidth);
        FxTestSupport.layout(settings);
        var railBounds=FxTestSupport.boundsIn(rail,shellBody);
        var footerBounds=FxTestSupport.boundsIn(footer,shellBody);
        check(railBounds.x==0 && railBounds.y==0 && railBounds.y+railBounds.height==footerBounds.y
                && footerBounds.x==0 && footerBounds.width==railBounds.width,
                "The save status stays at the foot of the navigation rail.");
        var fieldBounds=FxTestSupport.boundsIn(prefs.paletteCombo(),shellBody);
        javafx.scene.Node ancestor=prefs.paletteCombo();
        while (!(ancestor instanceof ScrollPane)) ancestor=ancestor.getParent();
        var viewport=FxTestSupport.boundsIn(ancestor.lookup(".viewport"),shellBody);
        check(viewport.getMaxX()-fieldBounds.getMaxX()>=12,
                "Text palette ends inside the scrollbar gutter: viewport="+viewport+", field="+fieldBounds);
        @SuppressWarnings("unchecked") ComboBox<Object> themeCombo=(ComboBox<Object>)field(prefs,"theme");
        ComboBox<?> paletteCombo=(ComboBox<?>)field(prefs,"palette");
        themeCombo.setValue(AppTheme.Charcoal);
        check(!paletteCombo.isDisabled(),"Switching to a built-in theme immediately enables its dependent host-palette choice.");
        prefs.gradientCheck().fire();
        check(w.appearanceSettings().flatThemes().contains(AppTheme.Charcoal),"Gradient choice applies to this theme.");
        fieldFaces(false);
        themeCombo.setValue(AppTheme.Paper);
        check(prefs.gradientCheck().isSelected(),"Another theme still uses gradients.");
        fieldFaces(true);
        themeCombo.setValue(AppTheme.Charcoal);
        check(!prefs.gradientCheck().isSelected(),"Switching back restores the flat preference.");
        prefs.gradientCheck().fire();
        fieldFaces(true);
        themeCombo.setValue(synthetic);
        check(paletteCombo.isDisabled(),"Switching to a custom theme immediately disables the superseded profile palette.");
        themeCombo.setValue(AppTheme.Charcoal);
        UsabilityIterationSmoke.settle(() -> !prefs.saving(),"Theme toggle autosave did not settle");
        prefs.forceDispose();
        w.appearanceSettings(saved);

        FxTestSupport.writeSnapshot(w.scene().getRoot(),scale,Path.of("build","fish-051-outline-on-"+scale+".png"));
        w.appearanceSettings(saved.withWindowOutline(false));
        FxTestSupport.layout(w);
        check(!shell.getStyleClass().contains("fish-window-outlined"),"Disabling window outline removes CSS border class.");
        FxTestSupport.writeSnapshot(w.scene().getRoot(),scale,Path.of("build","fish-051-outline-off-"+scale+".png"));
        w.appearanceSettings(saved);
        check(!w.isVisible(),"Chrome consistency checks never expose the shared desktop.");
    }

    public static void main(String[] args) throws Exception {
        Ui.install();
        boolean focusOnly=List.of(args).equals(List.of("--focus-only"));
        if (List.of(args).equals(List.of("--geometry-only"))) {
            edt(() -> {
                for (double scale:List.of(1.0,1.25,1.5,1.75,2.0)) cssScaleChecks(scale);
                return null;
            });
            System.out.println("PASS Geometry regression: "+checks+" checks at 100/125/150/175/200%; no desktop input.");
            Ui.finishTestRun();
            return;
        }
        Path root=Files.createTempDirectory(Path.of("build"),"chrome-smoke-");
        if (!focusOnly) migration(root.resolve("migration"));
        CountDownLatch closed=new CountDownLatch(1);
        try {
            StateStore store=new StateStore(root.resolve("workspace"));
            Workspace w=edt(() -> {
                Workspace window=new Workspace(store,Main.demoState(23),Path.of("scripts","credential-store.ps1"),false);
                window.setSize(1000,650);
                window.onClosed(closed::countDown); FxTestSupport.layout(window); return window;
            });
            try {
                if (!focusOnly) edt(() -> {
                    for (double scale:List.of(1.0,1.5)) { cssScaleChecks(scale); workspaceChecks(w,scale); }
                    return null;
                });
                windowFocusChecks(w,store);
            } finally { edt(() -> { w.close(false); return null; }); check(closed.await(5,TimeUnit.SECONDS),"Final local appearance persists and unlocks."); }
        } finally {
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
        }
        System.out.println("PASS Chrome consistency: "+checks+" checks; JavaFX CSS surfaces, no shared-desktop input.");
        Ui.finishTestRun();
    }
}
