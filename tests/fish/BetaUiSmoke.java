package fish.ui;

import java.awt.Color;
import java.awt.Rectangle;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderButtonType;
import javafx.scene.layout.HeaderDragType;
import javafx.scene.layout.Region;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import fish.model.*;

/** Production JavaFX HeaderBar chrome, referenced form hierarchy, owned guides and semantic themes. */
@SuppressWarnings({"deprecation", "removal"})
public final class BetaUiSmoke {
    private static int checks;
    private static Path evidence;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static Object field(Object owner,String name) throws Exception { return FxTestSupport.field(owner,name); }
    private static void layout(Workspace w) { FxTestSupport.layout(w); }
    private static void layout(Stage s) { FxTestSupport.layout(s); }
    private static <T> T edt(Callable<T> action) throws Exception { return FxTestSupport.fx(action); }
    private static void snapshot(Node c,String name) throws Exception {
        if (evidence==null) return;
        for (double scale:new double[]{1,1.5,2})
            FxTestSupport.writeSnapshot(c,scale,evidence.resolve(name+"-"+scale+".png"));
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
        HBox controls=(HBox)field(chrome,"controls");
        Region drag=(Region)field(chrome,"dragArea");
        ComboBox<?> selector=(ComboBox<?>)field(w,"addProfileSelector");
        Button plus=(Button)field(w,"addPane");

        check(w.stage().getStyle()==StageStyle.EXTENDED,"Workspace uses native JavaFX 25 StageStyle.EXTENDED without custom JNI WNDPROC subclassing");
        check(HeaderBar.getDragType(drag)==HeaderDragType.DRAGGABLE,"HeaderBar drag area uses native Glass HTCAPTION drag handling");
        check(HeaderBar.getButtonType(controls.getChildren().get(0))==HeaderButtonType.ICONIFY
                && HeaderBar.getButtonType(controls.getChildren().get(1))==HeaderButtonType.MAXIMIZE
                && HeaderBar.getButtonType(controls.getChildren().get(2))==HeaderButtonType.CLOSE,
                "HeaderBar window controls expose native ICONIFY/MAXIMIZE (Snap Layouts)/CLOSE button types");

        Profile p=w.active().profile();
        w.putProfile(new Profile(p.id(),"Long synthetic workstation name "+"W".repeat(45),p.host(),p.port(),p.device(),p.username(),
                p.automaticSignOn(),p.rememberPassword(),p.codePage(),p.theme(),p.effect(),p.zoom(),p.keymap(),p.savedLayout(),
                p.loginSource(),p.authentication(),p.kerberosServer()));
        check(selector.getStyleClass().contains("fish-chrome-combo"),"Selector has a flat chrome surface, not a boxed field");
        for (AppTheme theme:List.of(AppTheme.Charcoal,AppTheme.Light)) {
            w.applyTheme(theme);
            for (int width:new int[]{(int)Math.round(w.stage().getMinWidth()),50,640,1120}) {
                w.setSize(width,(int)Math.max(420,w.stage().getMinHeight())); layout(w);
                check(w.getWidth()>=w.stage().getMinWidth(),"Attempted below-minimum programmatic resize clamps");
                check(controls.getLayoutX()+controls.getWidth()<=chrome.getWidth()+1 && drag.getWidth()>=16,"Right caption controls and drag area remain fully allocated");
                for (Node control:controls.getChildren()) if (control instanceof Region r)
                    check(r.getWidth()>=r.prefWidth(-1)-1 && r.getHeight()>=27,"All caption hit targets retain their actual usable minimums");
                check(selector.getWidth()>=selector.minWidth(-1) && plus.getWidth()>=30,"Selector/add never collapse into clipped hits");
                if (width==50 || width==1120) snapshot(chrome,"chrome-"+theme+"-"+width);
            }
        }
        w.putProfile(p); w.setSize(1120,760); layout(w); snapshot(chrome,"chrome-standard-profile");
    }
    private static void forms(Workspace w) throws Exception {
        for (AppTheme theme:List.of(AppTheme.Charcoal,AppTheme.Light,AppTheme.Slate)) {
            w.applyTheme(theme);
            SavedLayoutsDialog dialog=new SavedLayoutsDialog(w); layout(dialog.stage());
            TextField name=(TextField)field(dialog,"name");
            Label summary=(Label)field(dialog,"context");
            Button create=(Button)field(dialog,"create"),save=(Button)field(dialog,"save");
            ToggleButton manage=(ToggleButton)field(dialog,"manage");
            Node root=dialog.stage().getScene().getRoot();
            Rectangle box=FxTestSupport.boundsIn(name,root);
            Rectangle context=FxTestSupport.boundsIn(summary,root);
            Rectangle primary=FxTestSupport.boundsIn(create,root);
            Rectangle secondary=FxTestSupport.boundsIn(save,root);
            check(box.x<40 && box.width>=dialog.stage().getScene().getWidth()-80 && context.y<box.y,
                    "Referenced shortcut name is full width under profile context");
            check(Math.abs(primary.x-box.x)<=2 && secondary.x>=primary.x+primary.width,
                    "Primary and secondary actions are left aligned in requested order");
            check(manage.getWidth()>=box.width-2 && !((Region)field(dialog,"library")).isVisible(),
                    "Saved library is full-width and initially expandable");
            name.setText("Synthetic layout \u5317");
            snapshot(root,"shortcuts-"+theme);
            if (w.savedLayouts().isEmpty()) {
                save.fire();
                UsabilityIterationSmoke.settle(() -> !(boolean)field(dialog,"working"),"Synthetic saved layout did not persist");
            } else manage.fire();
            layout(dialog.stage());
            TextField rename=(TextField)field(dialog,"existingName");
            check(!rename.getText().isBlank(),"Saved Unicode names remain readable in the actual rename field");
            snapshot(root,"shortcuts-expanded-"+theme);
            dialog.dispose();

            Preferences preferences=new Preferences(w,"Appearance"); layout(preferences.dialog());
            snapshot(preferences.dialog().getScene().getRoot(),"settings-"+theme); preferences.forceDispose();

            TutorialDialog tutorial=new TutorialDialog(w,TutorialDialog.Guide.APPEARANCE,false);
            w.preferences("Appearance"); tutorial.go(2); layout(tutorial.stage());
            check(tutorial.stage().getOwner()==w.stage() && !tutorial.stage().isAlwaysOnTop()
                    && tutorial.stage().getModality()==Modality.NONE,
                    "Guide remains application-owned, modeless and non-always-on-top");
            Method related=TutorialDialog.class.getDeclaredMethod("related",Window.class); related.setAccessible(true);
            check((boolean)related.invoke(tutorial,w.tutorialPreferences().dialog()),"Settings sibling belongs to tutorial's owned hierarchy");
            Stage chooser=new Stage(); chooser.initOwner(w.tutorialPreferences().dialog());
            Stage other=new Stage();
            check((boolean)related.invoke(tutorial,chooser) && !(boolean)related.invoke(tutorial,other),"Nested chooser raises the guide; unrelated windows never do");
            check(field(tutorial,"targetWindow")==w.tutorialPreferences().dialog(),"Real control target and highlight ownership follow Settings");
            snapshot(tutorial.stage().getScene().getRoot(),"tutorial-"+theme);
            tutorial.dispose(); chooser.close(); other.close(); w.tutorialPreferences().forceDispose();

            Stage recovery=WorkspaceRecovery.dialog("Synthetic future workspace version; matching backup required.",true,value -> {});
            layout(recovery); snapshot(recovery.getScene().getRoot(),"startup-recovery-"+theme); recovery.close();
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
                check(restored.getWidth()>=restored.stage().getMinWidth() && restored.getHeight()>=restored.stage().getMinHeight(),
                        "Production restored legacy-small bounds clamp before display");
                restored.finishWindow();
                themes(f.first()); chrome(f.first()); forms(f.first()); return null;
            });
            System.out.println("PASS beta semantic colors / JavaFX HeaderBar caption and minimums / reference forms / owned tutorial lifecycle: "+checks);
        } finally {
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
        }
        Ui.finishTestRun();
    }
}
