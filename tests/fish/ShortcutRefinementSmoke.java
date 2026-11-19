package fish.ui;

import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
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

    private static void tutorial(Fixture f) throws Exception {
        Workspace w=f.workspace; int profiles=w.profileList().size();
        w.executeCommand("Tutorial: Desktop shortcuts");
        check(step(w)==0 && w.savedLayouts().isEmpty() && !w.active().busy(),"Opening guide has no session, profile or recipe side effects");
        guide(w).skipStepButton().fire(); guide(w).skipStepButton().fire();
        check(step(w)==2,"Shortcut guide can skip profile/add without connecting");
        w.executeCommand("Saved layouts / shortcuts...");
        Preferences p=w.tutorialPreferences();
        check(step(w)==3 && p.category().equals("Shortcuts"),"Legacy action routes to Shortcuts and advances");
        TextField name=(TextField)p.tutorialTarget("shortcut-name"); name.setText("Synthetic tutorial shortcut");
        check(step(w)==4 && w.savedLayouts().isEmpty(),"Real name entry advances without saving");
        Button create=(Button)p.tutorialTarget("shortcut-create");
        check(guide(w).currentAnchor()==create,"Guide highlights the actual Settings button");
        Path desktop=LayoutShortcuts.desktop(),target=LayoutShortcuts.executable();
        System.setProperty("fish.test.desktopDir",target.toString()); // a file, not a folder
        try {
            create.fire();
            FxTestSupport.await(() -> !w.exportingShortcut(),"Failed export did not finish");
            check(step(w)==4 && w.savedLayouts().isEmpty() && f.store.load().savedLayouts().isEmpty()
                    && ((Label)p.tutorialTarget("shortcut-status")).getText().contains("not created"),
                    "Failure creates no phantom recipe and does not advance the guide");
        } finally { System.setProperty("fish.test.desktopDir",desktop.toString()); }
        Path occupied=desktop.resolve(SavedLayout.shortcutFileName(name.getText()));
        Files.writeString(occupied,"unrelated link");
        create.fire();
        FxTestSupport.await(() -> !w.exportingShortcut(),"Shortcut export did not finish");
        check(step(w)==5 && Files.readString(occupied).equals("unrelated link"),"Successful export advances without overwriting existing files");
        String[] link=LayoutShortcuts.inspect(desktop.resolve("Fish - Synthetic tutorial shortcut (2).lnk"));
        check(link[0].equals(target.toString()) && link[1].equals(LayoutShortcuts.arguments(f.store.load().savedLayouts().getFirst().id(),w.dataDirectory())),
                "Guided export produces a native link to the persisted UUID");
        layout(p.dialog());
        image(p.dialog().getScene().getRoot(),"shortcut-settings");
        image(guide(w).card(),"desktop-guide");
        guide(w).backButton().fire(); check(step(w)==4,"Back remains available after actual export");
        guide(w).skipTutorialButton().fire(); check(guide(w).ended(),"Guide is skippable at export stage");
        int saved=w.savedLayouts().size();
        w.executeCommand("Tutorial: Desktop shortcuts");
        check(step(w)==0 && w.profileList().size()==profiles && w.savedLayouts().size()==saved && !w.active().busy(),
                "Replay resets progress without new sessions, recipes or profile changes");
        guide(w).skipTutorialButton().fire(); p.closeSettings();
    }

    private static void transactions(Path root,ShortcutFixture shell) throws Exception {
        try (var f=new UsabilityIterationSmoke.Fixture(root.resolve("transaction workspace"),s -> state -> {
            if (state.savedLayouts().stream().anyMatch(r -> r.name().equals("Refused"))) throw new java.io.IOException("fixture save failure");
            s.save(state);
        })) {
            Workspace w=f.first(); Path data=w.dataDirectory();
            Path collision=shell.startup.resolve("Fish - Shift_A.lnk");
            // Genuine Fish link, even the same filename, but belonging to a different workspace.
            LayoutShortcuts.create(collision,shell.exe,root.resolve("other workspace"),UUID.randomUUID());
            byte[] original=Files.readAllBytes(collision);
            SavedLayout a=ShortcutFixture.export(w,"Shift/A",true);
            SavedLayout b=ShortcutFixture.export(w,"Shift:A",true);
            LayoutShortcuts.syncAutostart(data,shell.exe,f.store.load().savedLayouts());
            check(Arrays.equals(original,Files.readAllBytes(collision)),"Startup reconciliation preserves other workspaces' links");
            for (var entry:Map.of(a,2,b,3).entrySet()) {
                Path link=shell.startup.resolve("Fish - Shift_A ("+entry.getValue()+").lnk");
                check(LayoutShortcuts.inspect(link)[1].equals(LayoutShortcuts.arguments(entry.getKey().id(),data)),
                        "Sanitized names can collide without losing either sign-in shortcut");
            }
            AtomicReference<String> error=new AtomicReference<>();
            edt(() -> { w.exportDesktopShortcut("Refused",true,path -> { throw new AssertionError(path); },error::set); return null; });
            FxTestSupport.await(() -> error.get()!=null,"Expected injected persistence failure");
            check(error.get().contains("fixture save failure") && edt(() -> w.savedLayouts().equals(List.of(a,b)))
                    && f.store.load().savedLayouts().equals(List.of(a,b)),"A refused commit changes neither live nor persisted recipes");
            check(!Files.exists(shell.desktop.resolve("Fish - Refused.lnk")) && !Files.exists(shell.startup.resolve("Fish - Refused.lnk")),
                    "A refused commit rolls back both of its links");
            String old=System.getProperty("fish.test.kiosk"); System.setProperty("fish.test.kiosk","true");
            try {
                error.set(null);
                edt(() -> { w.exportDesktopShortcut("Blocked",false,path -> { throw new AssertionError(path); },error::set); return null; });
                check(error.get()!=null && !Files.exists(shell.desktop.resolve("Fish - Blocked.lnk")),"Kiosk rejects even a direct export call");
                check(edt(() -> f.app.openRequest(a.id())==1),"A deployed UUID can still launch in kiosk");
            } finally { if (old==null) System.clearProperty("fish.test.kiosk"); else System.setProperty("fish.test.kiosk",old); }
        }
    }

    private static String edit(Workspace w,SavedLayout before,java.util.function.UnaryOperator<SavedLayout> mutation) throws Exception {
        AtomicReference<String> result=new AtomicReference<>();
        edt(() -> { w.editShortcut(before,mutation,change -> result.set(change.warning()),result::set); return null; });
        FxTestSupport.await(() -> result.get()!=null,"Shortcut edit did not finish");
        return result.get();
    }

    // Generate historically deployed arguments (and lookalikes) without ever resolving a real shell folder.
    private static void rawLink(ShortcutFixture shell,Path link,String args) throws Exception {
        if (!Set.of(shell.desktop,shell.startup).contains(link.getParent())) throw new AssertionError("Not a fixture shell folder");
        var method=LayoutShortcuts.class.getDeclaredMethod("create0",String.class,String.class,String.class,String.class);
        method.setAccessible(true);
        method.invoke(null,link.toString(),shell.exe.toString(),args,shell.exe.getParent().toString());
    }

    private static void management(Path root,ShortcutFixture shell) throws Exception {
        var reject=new java.util.concurrent.atomic.AtomicBoolean();
        try (var f=new UsabilityIterationSmoke.Fixture(root.resolve("managed workspace"),s -> state -> {
            if (reject.get()) throw new java.io.IOException("recipe write refused"); s.save(state);
        })) {
            Workspace first=f.first();
            Preferences p=edt(() -> { first.preferences("General"); return first.tutorialPreferences(); });
            Workspace second=edt(() -> {
                check(f.app.newWindow(first),"Second broker window opened");
                Workspace w=f.app.windows().getLast();
                w.preferences("Shortcuts");
                check(first.tutorialPreferences()==p && p.category().equals("Shortcuts"),"Existing Settings routes to Shortcuts");
                ((TextField)p.tutorialTarget("shortcut-name")).setText("Work shift");
                ((Button)p.tutorialTarget("shortcut-create")).fire(); return w;
            });
            FxTestSupport.await(() -> !f.app.saving(),"Export did not finish");
            SavedLayout original=f.store.load().savedLayouts().getFirst();
            check(edt(() -> original.window().layout().equals(second.currentLayout()) && !second.currentLayout().equals(first.currentLayout())),
                    "Shared Settings exports the requesting window, not the Settings owner");
            Path desk=shell.desktop.resolve(SavedLayout.shortcutFileName(original.name()));
            Path customDesk=Files.move(desk,shell.desktop.resolve("Arbitrary local name.LNK"));
            edt(() -> { ((CheckBox)p.tutorialTarget("shortcut-sign-in")).fire(); return null; });
            FxTestSupport.await(() -> !f.app.saving(),"Sign-in change did not finish");
            SavedLayout enabled=original.withAutostart(true);
            Path customStart=Files.move(shell.startup.resolve(SavedLayout.shortcutFileName(original.name())),shell.startup.resolve("Custom startup.lnk"));
            check(f.store.load().savedLayouts().equals(List.of(enabled)) && Files.exists(customDesk),"Sign-in adds a real link without renaming Desktop");
            byte[] deskBytes=Files.readAllBytes(customDesk),startBytes=Files.readAllBytes(customStart);

            Path collision=shell.desktop.resolve("Fish - New shift.lnk");
            LayoutShortcuts.create(collision,shell.exe,root.resolve("other data"),original.id());
            byte[] foreign=Files.readAllBytes(collision);
            Path appLink=shell.startup.resolve(collision.getFileName());
            LayoutShortcuts.createApplicationLink(appLink,shell.exe); byte[] appBytes=Files.readAllBytes(appLink);
            Path lookalike=shell.desktop.resolve("Almost.lnk");
            rawLink(shell,lookalike,LayoutShortcuts.arguments(original.id(),first.dataDirectory())+" --demo");
            Path unknown=shell.startup.resolve("Unlisted.lnk");
            LayoutShortcuts.create(unknown,shell.exe,first.dataDirectory(),UUID.randomUUID());
            LayoutShortcuts.syncAutostart(first.dataDirectory(),shell.exe,List.of(enabled));
            check(Files.exists(unknown) && Files.exists(customStart),"Startup reconciliation touches only listed UUIDs; keeps a manually renamed matching link");
            Path outside=root.resolve("Outside Desktop.lnk"); Files.copy(customDesk,outside);
            Path renamedDesk=shell.desktop.resolve("Fish - New shift (2).lnk"),renamedStart=shell.startup.resolve(renamedDesk.getFileName());
            reject.set(true);
            String error=edit(second,enabled,s -> s.renamed("New shift"));
            reject.set(false);
            check(error.contains("recipe write refused") && Arrays.equals(deskBytes,Files.readAllBytes(customDesk))
                    && Arrays.equals(startBytes,Files.readAllBytes(customStart)) && !Files.exists(renamedDesk) && !Files.exists(renamedStart)
                    && f.store.load().savedLayouts().equals(List.of(enabled)),"Failed rename restores both old Shell files byte-for-byte, neither new name nor recipe published");
            check(edit(second,enabled,s -> s.renamed("New shift")).isEmpty(),"Rename commits");
            SavedLayout renamed=enabled.renamed("New shift");
            for (Path link:List.of(renamedDesk,renamedStart))
                check(LayoutShortcuts.inspect(link)[1].equals(LayoutShortcuts.arguments(original.id(),first.dataDirectory())),"Renamed link uses original UUID and data directory");
            check(!Files.exists(customDesk) && !Files.exists(customStart) && edt(() -> first.savedLayouts().equals(second.savedLayouts())
                    && p.tutorialTarget("shortcut-list") instanceof ListView<?> list && list.getSelectionModel().getSelectedItem().equals(renamed)),
                    "Arbitrarily named old links gone; shared Settings selection refreshed by UUID");
            check(edit(first,renamed,s -> first.captureShortcut(s.id(),s.name(),s.autostart())).isEmpty(),"Save over commits");
            SavedLayout replaced=f.store.load().savedLayouts().getFirst();
            check(edt(() -> replaced.id().equals(original.id()) && replaced.window().layout().equals(first.currentLayout())
                    && replaced.autostart() && second.savedLayouts().equals(List.of(replaced))),"Save over replaces recipe for all windows, preserving UUID and sign-in");
            check(edit(second,renamed,s -> null).contains("changed"),"Stale recipe cannot delete a newer edit");
            Files.copy(renamedStart,customStart);
            check(edit(second,replaced,s -> s.withAutostart(false)).isEmpty() && !Files.exists(renamedStart) && !Files.exists(customStart)
                    && Files.exists(renamedDesk),"Disabling sign-in removes every matching Startup link but not Desktop");
            SavedLayout disabled=replaced.withAutostart(false);
            String previous=System.getProperty("fish.test.kiosk"); System.setProperty("fish.test.kiosk","true");
            try { check(edit(first,disabled,s -> null).contains("Kiosk") && Files.exists(renamedDesk),"Kiosk blocks management before link changes"); }
            finally { if (previous==null) System.clearProperty("fish.test.kiosk"); else System.setProperty("fish.test.kiosk",previous); }
            reject.set(true); byte[] renamedBytes=Files.readAllBytes(renamedDesk);
            error=edit(first,disabled,s -> null); reject.set(false);
            check(error.contains("refused") && Arrays.equals(renamedBytes,Files.readAllBytes(renamedDesk)) && f.store.load().savedLayouts().equals(List.of(disabled)),
                    "Refused delete restores exact Desktop link and backing recipe");
            check(edit(first,disabled,s -> null).isEmpty() && !Files.exists(renamedDesk) && f.store.load().savedLayouts().isEmpty()
                    && edt(() -> second.savedLayouts().isEmpty() && f.app.openRequest(disabled.id())==2),"Committed delete removes Desktop, broker entry and persistent recipe");
            check(Arrays.equals(foreign,Files.readAllBytes(collision)) && Arrays.equals(appBytes,Files.readAllBytes(appLink))
                    && Files.exists(lookalike) && Arrays.equals(deskBytes,Files.readAllBytes(outside)),"Unrelated workspace, application, extra-argument and out-of-scope links survive");

            // UUID-only links denote the default data directory. Pass that path for identity comparison only;
            // there is no real StateStore here and the commit does no I/O.
            rawLink(shell,customDesk,"--layout "+original.id()); rawLink(shell,customStart,"--layout "+original.id());
            LayoutShortcuts.change(first.dataDirectory(),original,null,() -> {});
            check(Files.exists(customDesk) && Files.exists(customStart),"A custom data directory cannot claim legacy default-workspace links");
            Path defaultData=fish.Main.defaultDirectory(Path.of(Optional.ofNullable(System.getenv("APPDATA")).orElse(System.getProperty("user.home"))));
            LayoutShortcuts.change(defaultData,original,null,() -> {});
            check(!Files.exists(customDesk) && !Files.exists(customStart),"Legacy UUID-only Desktop and Startup links managed by the default workspace");

            LayoutShortcuts.create(customDesk,shell.exe,first.dataDirectory(),original.id());
            byte[] originalBytes=Files.readAllBytes(customDesk);
            try {
                LayoutShortcuts.change(first.dataDirectory(),original,null,() -> {
                    Files.writeString(customDesk,"occupied during save",StandardOpenOption.CREATE_NEW);
                    throw new java.io.IOException("injected partial failure");
                });
                throw new AssertionError("Expected rollback conflict");
            } catch (java.io.IOException partial) {
                try (var backups=Files.newDirectoryStream(shell.desktop,".fish-*.bak")) {
                    Path backup=backups.iterator().next();
                    check(partial.getMessage().contains(backup.toString()) && Files.readString(customDesk).equals("occupied during save")
                            && Arrays.equals(Files.readAllBytes(backup),originalBytes),"Rollback conflict preserves the new occupant and reports the recoverable original");
                }
            }
        }
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
        try (ShortcutFixture shell=new ShortcutFixture(root); Fixture fixture=new Fixture(root.resolve("workspace"))) {
            if (!Arrays.asList(args).contains("--manager-only"))
                edt(() -> { chrome(fixture.workspace); keyboard(fixture.workspace); tutorial(fixture); return null; });
            transactions(root,shell);
            management(root,shell);
            check(Window.getWindows().stream().noneMatch(Window::isShowing),"No test window was ever shown");
        } finally {
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
        }
        System.out.println("PASS shortcut management, broker, native Shell links and rollback: "+checks+" total checks");
        Ui.finishTestRun();
    }
}
