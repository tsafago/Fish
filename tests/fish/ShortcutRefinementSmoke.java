package fish.ui;

import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import fish.LayoutShortcuts;
import fish.model.*;
import fish.update.*;

/** Never-shown JavaFX forms, empty hosts and native links in a temporary directory only. */
public final class ShortcutRefinementSmoke {
    private static int checks;
    private static Path evidence;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static Object field(Object owner,String name) throws Exception { return FxTestSupport.field(owner,name); }
    private static void set(Object owner,String name,Object value) throws Exception { FxTestSupport.setField(owner,name,value); }
    private static <T> T edt(Callable<T> action) throws Exception { return FxTestSupport.fx(action); }
    private static ButtonBase button(Stage stage,String text) { return FxTestSupport.button(stage,text); }
    private static void layout(Workspace w) { FxTestSupport.layout(w); }
    private static void layout(Stage stage) { FxTestSupport.layout(stage); }
    private static void image(Node node,String name) throws Exception {
        if (evidence==null) return;
        for (double scale:new double[]{1,1.5})
            FxTestSupport.writeSnapshot(node,scale,evidence.resolve(name+"-"+scale+".png"));
    }

    private static final class Fixture implements AutoCloseable {
        final StateStore store;
        final Workspace workspace;
        final CountDownLatch closed=new CountDownLatch(1);
        Fixture(Path root) throws Exception {
            store=new StateStore(root);
            Profile p=new Profile(UUID.randomUUID(),"Synthetic local profile","",23,"","",false,false,"37","Phosphor","None",1,Map.of(),"");
            StateStore.State state=new StateStore.State(List.of(p),new Layout.Leaf(UUID.randomUUID(),p.id()),1120,760,
                    AppTheme.Charcoal,p.id(),Map.of(),true,Appearance.defaults(),StateStore.Tutorial.SKIPPED,false,
                    new UpdateSettings(false,UpdateSettings.Channel.PREVIEW_PATCHES,""));
            store.save(state);
            workspace=edt(() -> {
                Workspace w=new Workspace(store,state,root.resolve("absent-credentials.ps1"),false,
                        new Updates((uri,out,limit,cancel,progress) -> { throw new AssertionError("No HTTP"); },new Updates.Platform() {
                            @Override public void inspectAndMark(Path file) { throw new AssertionError("No installer"); }
                            @Override public void launch(Path file,String hash,long pid,Path data) { throw new AssertionError("No installer"); }
                        }),(owner,message) -> false);
                w.onClosed(closed::countDown); layout(w); return w;
            });
        }
        @Override public void close() throws Exception {
            edt(() -> { workspace.close(false); return null; });
            check(closed.await(10,TimeUnit.SECONDS),"Window workers close and persist");
        }
    }

    private static void syntax() {
        List<String> names=KeyChord.supportedKeys();
        check(names.containsAll(List.of("F5","F24","ENTER","NUMPAD_ENTER","NUMPAD0","ADD","EQUALS","LEFT","PAGE_DOWN")),"Reference includes ordinary, function, punctuation and keypad keys");
        check(names.stream().noneMatch(n -> Set.of("WINDOWS","META","CONTROL","SHIFT","ALT","ALT_GRAPH","UNDEFINED").contains(n)),"Modifiers and reserved Windows key are not action keys");
        for (String name:names) check(KeyChord.parse(KeyChord.parse(name).toString()).equals(KeyChord.parse(name)),"Allowed token roundtrips: "+name);
        for (String example:List.of("F5","ENTER","ctrl F5","ctrl shift F5","ENTER@numpad","altGraph Q"))
            check(KeyChord.list(example).size()==1,"Every shipped example is parser-valid: "+example);
        check(KeyChord.list("ctrl F5\nctrl shift F5").size()==2,"Alternatives are newline-separated, not plus/comma-separated");
        for (String suffix:KeyChord.locations()) check(KeyChord.parse("F5"+suffix).toString()!=null,"Published suffix accepted: "+suffix);
    }

    private static void keyboard(Workspace w) throws Exception {
        w.preferences("Keyboard Shortcuts"); Preferences p=w.tutorialPreferences();
        KeyboardEditor editor=(KeyboardEditor)field(p,"keyboard"); Map<String,String> before=editor.draft();
        button(p.dialog(),"Allowed keys and actions...").fire();
        KeyboardReference reference=(KeyboardReference)field(editor,"reference");
        check(reference!=null && reference.stage().getModality()==Modality.NONE,"Reference is discoverable in the real editor, not another settings route");
        check(editor.draft().equals(before),"Browsing reference never edits draft bindings");
        TableView<?> keys=(TableView<?>)field(reference,"keysTable"),actions=(TableView<?>)field(reference,"actionsTable");
        check(keys.getItems().size()==KeyChord.supportedKeys().size(),"Allowed keys come from parser definitions");
        check(actions.getItems().size()==KeyBindings.defaultsByAction().size()-Shortcuts.SETTINGS_ALIASES.size(),"All assignable canonical actions and Unbound keys are listed");
        check(keys.getAccessibleText().contains("Allowed") && actions.getAccessibleText().contains("Assignable"),"Reference tables have accessible names");
        TextField search=(TextField)field(reference,"search");
        search.setText("ENTER"); check(keys.getItems().size()>1 && keys.getItems().size()<KeyChord.supportedKeys().size(),"Keys search filters names and syntax");
        search.setText(""); keys.getSelectionModel().select(0);
        check(!((TextArea)field(reference,"syntax")).getText().isBlank(),"Selected key exposes selectable entry syntax");
        search.setText("Zoom in"); ((TabPane)field(reference,"tabs")).getSelectionModel().select(1);
        check(actions.getItems().size()==1,"Action list is independently browsable/searchable");
        actions.getSelectionModel().select(0);
        check(((TextArea)field(reference,"syntax")).getText().equals(KeyBindings.defaultText("Zoom in")),"Action defaults retain exact alternative/location syntax");
        reference.stage().setWidth(760); reference.stage().setHeight(580); layout(reference.stage()); image(reference.stage().getScene().getRoot(),"keys-actions");
        button(reference.stage(),"Edit selected action").fire();
        @SuppressWarnings("unchecked") TableView<KeyboardEditor.ActionBindingRow> table=(TableView<KeyboardEditor.ActionBindingRow>)field(editor,"table");
        check(table.getSelectionModel().getSelectedItem().action.equals("Zoom in"),"Reference action navigation selects its real draft editor row");
        check(editor.draft().equals(before),"Choosing an action still never changes its binding");
        TextArea input=(TextArea)field(editor,"keys"); input.setText("ctrl shift F9\nctrl shift F10");
        check(editor.draft().get("Zoom in").equals(KeyChord.text(KeyChord.list(input.getText()))),"Illustrated multiline format remains editable and normalized");
        p.dialog().setWidth(1060); p.dialog().setHeight(740); layout(p.dialog()); image(p.dialog().getScene().getRoot(),"keyboard-settings");
        p.dialog().setWidth(760); p.dialog().setHeight(560); layout(p.dialog());
        check(table.getHeight()>=90 && input.getHeight()>=32,"Visible keyboard viewport and multiline editor remain usable at minimum Settings size");
        image(p.dialog().getScene().getRoot(),"keyboard-settings-minimum");
        p.closeSettings();
        UsabilityIterationSmoke.settle(p::ended,"Reference-driven key edit did not finish saving");
        check(w.shortcutOverrides().containsKey("Zoom in"),"Closing Settings flushes the reference-driven valid edit");
    }

    private static TutorialDialog guide(Workspace w) throws Exception { return (TutorialDialog)field(w,"tutorial"); }
    private static int step(Workspace w) throws Exception { return (int)field(guide(w),"step"); }

    private static void tutorial(Fixture f,Path root) throws Exception {
        Workspace w=f.workspace; int profiles=w.profileList().size();
        w.executeCommand("Tutorial: Desktop shortcuts");
        check(step(w)==0 && w.savedLayouts().isEmpty() && !w.active().busy(),"Opening guide has no session, profile or recipe side effects");
        button(guide(w).stage(),"Skip step").fire(); button(guide(w).stage(),"Skip step").fire();
        check(step(w)==2,"Existing-layout path can skip profile/add without connecting");
        w.executeCommand("Saved layouts / shortcuts...");
        check(step(w)==3,"Actual menu action opens manager and advances");
        SavedLayoutsDialog previous=(SavedLayoutsDialog)field(w,"layoutsDialog"); previous.dispose();
        String image=System.getProperty("fish.test.image","");
        Path target=image.isBlank() ? Files.createFile(root.resolve("Fish.exe")) : Path.of(image).toAbsolutePath().resolve("Fish.exe");
        check(Files.isRegularFile(target),"Explicit packaged target or synthetic fixture file exists; it is never executed");
        int[] mode={0},writes={0},choices={0};
        SavedLayoutsDialog manager=new SavedLayoutsDialog(w,new SavedLayoutsDialog.ShortcutExport() {
            @Override public Path desktop() { return root; }
            @Override public Path choose(Path initialDir,String initialFileName,Window owner) {
                choices[0]++;
                check(initialDir.equals(root),"Production chooser starts in the injected destination, never actual Desktop");
                check(initialFileName.endsWith(".lnk"),"Production filename is a Windows link");
                return mode[0]==0 ? null : root.resolve("Synthetic tutorial shortcut.lnk");
            }
            @Override public void write(UUID id,Path output,Runnable done,Consumer<String> failed) {
                writes[0]++;
                if (mode[0]==1) { failed.accept("Synthetic export refused; choose another folder."); return; }
                try {
                    f.store.save(w.state()); LayoutShortcuts.create(output,target,w.dataDirectory(),id); done.run();
                } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            }
        });
        set(w,"layoutsDialog",manager);
        TextField name=(TextField)field(manager,"name"); name.setText("Synthetic tutorial layout");
        check(step(w)==4 && w.savedLayouts().isEmpty(),"Real name entry advances without saving");
        check(w.tutorialLayoutTarget("export")==field(manager,"create"),"Guide anchor is the direct current-window export button");
        button(manager.stage(),"Create desktop shortcut...").fire();
        check(step(w)==5 && w.savedLayouts().isEmpty() && writes[0]==0 && !Files.exists(root.resolve("Synthetic tutorial shortcut.lnk")),
                "Cancel creates no recipe or file and never claims success");
        mode[0]=1; button(manager.stage(),"Create desktop shortcut...").fire();
        check(step(w)==5 && w.savedLayouts().size()==1 && ((Label)field(manager,"message")).getText().contains("refused"),
                "Failed export keeps the saved recipe and stays actionable");
        ((ToggleButton)field(manager,"manage")).fire();
        mode[0]=2; button(manager.stage(),"Create shortcut...").fire();
        check(step(w)==6 && writes[0]==2 && choices[0]==3,"Only actual successful native export advances to the launch instructions");
        String[] link=LayoutShortcuts.inspect(root.resolve("Synthetic tutorial shortcut.lnk"));
        check(link[0].equals(target.toString()) && link[1].equals(LayoutShortcuts.arguments(w.savedLayouts().getFirst().id(),w.dataDirectory())),
                "Guided production chooser/export path produces a real native link with exact stable recipe reference");
        layout(manager.stage()); layout(guide(w).stage());
        image(manager.stage().getScene().getRoot(),"layout-manager");
        image(guide(w).stage().getScene().getRoot(),"desktop-guide");
        button(guide(w).stage(),"Back").fire(); check(step(w)==5,"Back remains available after actual export");
        button(guide(w).stage(),"Skip tutorial").fire(); check(guide(w).ended(),"Guide is skippable at export stage");
        int saved=w.savedLayouts().size();
        w.executeCommand("Tutorial: Desktop shortcuts");
        check(step(w)==0 && w.profileList().size()==profiles && w.savedLayouts().size()==saved && !w.active().busy(),
                "Replay resets progress without new sessions, recipes or profile changes");
        button(guide(w).stage(),"Skip tutorial").fire(); manager.dispose();
    }

    private static void chrome(Workspace w) throws Exception {
        WindowChrome chrome=(WindowChrome)field(w,"chrome");
        ComboBox<?> combo=(ComboBox<?>)field(w,"addProfileSelector"); Button add=(Button)field(w,"addPane");
        for (AppTheme theme:List.of(AppTheme.Charcoal,AppTheme.Midnight,AppTheme.Paper)) {
            Ui.applyTheme(theme); w.setSize(1120,760); layout(w);
            check(combo.getStyleClass().contains("fish-chrome-combo"),"Chrome selector stays borderless across theme refresh: "+theme);
            HBox tools=(HBox)combo.getParent();
            check(tools.getChildren().size()==3 && tools.getChildren().get(1) instanceof Separator && tools.getChildren().get(2)==add,
                    "Profile and plus remain aligned with only the inner pill join separator between them: "+theme);
            image(chrome,"chrome-"+theme);
            for (int width:new int[]{330,500,680,1120}) {
                w.setSize(width,760); layout(w);
                Region controls=(Region)field(chrome,"controls"),drag=(Region)field(chrome,"dragArea");
                check(controls.getLayoutX()+controls.getWidth()<=chrome.getWidth()+1 && drag.getWidth()>=16 && combo.getWidth()>=45,
                        "Narrow chrome retains selector, drag space and native controls at "+width+" / "+theme);
                if (width==330 || width==680) image(chrome,"chrome-"+theme+"-"+width);
            }
        }
        Ui.applyTheme(AppTheme.Charcoal);
        check("Profile for new pane".equals(combo.getAccessibleText())
                && add.getAccessibleText().contains("Add and connect"),"Original accessible intent and keyboard controls preserved");
    }

    public static void main(String[] args) throws Exception {
        Ui.install(); syntax(); String path=System.getProperty("fish.test.evidence","");
        if (!path.isBlank()) { evidence=Path.of(path); Files.createDirectories(evidence); }
        Path root=Files.createTempDirectory(Path.of("build"),"shortcut-refinement-").toAbsolutePath();
        try (Fixture fixture=new Fixture(root.resolve("workspace"))) {
            edt(() -> { chrome(fixture.workspace); keyboard(fixture.workspace); tutorial(fixture,root); return null; });
            check(Window.getWindows().stream().noneMatch(Window::isShowing),"No test window was ever shown");
        } finally {
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
        }
        System.out.println("PASS unified chrome / parser-derived reference / real shortcut tutorial: "+checks);
        Ui.finishTestRun();
    }
}
