package fish.ui;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import fish.model.*;
import fish.update.*;

/** Shipped JavaFX constructors/actions, isolated configuration, injected metadata, and no desktop/auth/install access. */
public final class SettingsTutorialSmoke {
    private static int checks;
    private static Path evidence;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static Object field(Object owner,String name) throws Exception { return FxTestSupport.field(owner,name); }
    private static <T> T edt(Callable<T> action) throws Exception { return FxTestSupport.fx(action); }
    private static void await(Callable<Boolean> action,String message) throws Exception { FxTestSupport.await(action,10,message); }
    private static ButtonBase button(Stage stage,String text) { return FxTestSupport.button(stage,text); }
    private static List<Node> children(Stage stage) { return FxTestSupport.children(stage); }
    private static void layout(Workspace w) { FxTestSupport.layout(w); }
    private static void layout(Stage s) { FxTestSupport.layout(s); }
    private static void snapshot(Stage window,String name) throws Exception {
        if (evidence==null) return;
        FxTestSupport.writeSnapshot(window.getScene().getRoot(),1.0,evidence.resolve(name+".png"));
    }
    private static TutorialDialog guide(Workspace workspace) throws Exception { return (TutorialDialog)field(workspace,"tutorial"); }
    private static int step(Workspace workspace) throws Exception { return (int)field(guide(workspace),"step"); }
    private static void rejects(StateStore store,Properties properties,String message) throws Exception {
        try { store.decode(properties); } catch (IOException expected) { checks++; return; } throw new AssertionError(message);
    }

    private static final class Http implements Updates.Transport {
        final AtomicInteger checks=new AtomicInteger(),downloads=new AtomicInteger();
        final byte[] metadata;
        Http() {
            Map<String,Object> release=MaintenanceSmoke.release(MaintenanceSmoke.futureVersion(),true);
            release.put("body","## Readable release notes\n\n**Bold**, *emphasis*, and `code`.\n\n- First change\n- Second change\n\n"
                    +"```text\nnot an executable command\n```\n\n[Project](https://github.com/tsafago/Fish)\n\n"
                    +"<script>alert('untrusted')</script>\n\n![No remote image](https://example.invalid/private.png)");
            metadata=ConfigSchema.json(List.of(release)).getBytes(StandardCharsets.UTF_8);
        }
        @Override public void fetch(URI uri,OutputStream out,long limit,Updates.Cancel cancel,java.util.function.LongConsumer progress) throws IOException {
            cancel.check(); if (!uri.equals(Updates.API)) { downloads.incrementAndGet(); throw new AssertionError("Unexpected download"); }
            checks.incrementAndGet(); out.write(metadata); progress.accept(metadata.length);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final StateStore store;
        final Workspace workspace;
        final Http http=new Http();
        final CountDownLatch closed=new CountDownLatch(1);
        Fixture(Path root,String name,boolean incompletePassword) throws Exception {
            Path directory=Files.createDirectory(root.resolve(name)); store=new StateStore(directory);
            Profile first=incompletePassword ? new Profile(UUID.randomUUID(),"Incomplete","127.0.0.1",9,"","",true,true,
                    "37","Phosphor","None",1,Map.of(),"") : new Profile(UUID.randomUUID(),"Synthetic workstation","",23,"","",false,false,
                    "37","Phosphor","None",1,Map.of(),"",null,Profile.Authentication.KERBEROS,"");
            Profile second=Profile.createKerberos("Other workstation");
            StateStore.State state=new StateStore.State(List.of(first,second),new Layout.Leaf(UUID.randomUUID(),first.id()),1060,740,
                    AppTheme.Charcoal,first.id(),Map.of(),true,Appearance.defaults(),StateStore.Tutorial.SKIPPED,incompletePassword,
                    new UpdateSettings(false,UpdateSettings.Channel.PREVIEW_PATCHES,""));
            store.save(state);
            workspace=edt(() -> {
                Updates.Platform platform=new Updates.Platform() {
                    @Override public void inspectAndMark(Path file) { throw new AssertionError("No installer inspection expected"); }
                    @Override public void launch(Path file,String hash,long pid,Path data) { throw new AssertionError("No installer execution"); }
                };
                Workspace w=new Workspace(store,state,directory.resolve("absent-credential-helper.ps1"),false,
                        new Updates(http,platform,Files.createDirectory(directory.resolve("staging"))),(owner,message) -> false);
                w.onClosed(closed::countDown); layout(w); return w;
            });
        }
        @Override public void close() throws Exception {
            edt(() -> {
                Preferences p=workspace.tutorialPreferences();
                if (p!=null) {
                    ((Button)field(p,"reloadButton")).fire();
                    UsabilityIterationSmoke.settle(() -> !p.saving(),"Settings teardown did not settle");
                    p.forceDispose();
                }
                workspace.close(false); return null;
            });
            check(closed.await(10,TimeUnit.SECONDS),"Workspace closed and persisted");
        }
    }

    private static void migration(Path root) throws Exception {
        StateStore.State base=StateStore.freshState();
        try (StateStore store=new StateStore(Files.createDirectory(root.resolve("format")))) {
            Properties old=StateStore.properties(base); old.remove("appearance.cursorStyle"); old.setProperty("version","12"); old.setProperty("connectOnStartup","false");
            old.remove("windows"); old.remove("info.footerDevice");
            old.remove("addProfile"); old.remove("savedLayouts"); old.remove("selectedLayout");
            old.setProperty("binding.Preferences...","ctrl COMMA\nctrl F1@numpad");
            old.setProperty("binding.Fonts...","ctrl shift F2@right"); old.setProperty("binding.Shortcuts...","ctrl shift F3");
            old.setProperty("binding.Host F13","ENTER@numpad\nctrl F4");
            StateStore.State migrated=store.decode(old);
            check(!migrated.connectOnStartup(),"Explicit old startup off remains off");
            check(migrated.profiles().equals(base.profiles()) && migrated.layout().equals(base.layout()),"Profile identities and layout retained");
            Map<KeyChord,String> map=KeyBindings.effective(migrated.shortcuts());
            for (String chord:List.of("ctrl COMMA","ctrl F1@numpad","ctrl shift F2@right","ctrl shift F3"))
                check(map.get(KeyChord.parse(chord)).equals(Shortcuts.SETTINGS),"Exact old settings alias preserved: "+chord);
            check(migrated.shortcuts().keySet().stream().noneMatch(Shortcuts.SETTINGS_ALIASES::contains),"One live settings action");
            check(migrated.keyboardRecovery().get("binding.Preferences...").equals(old.getProperty("binding.Preferences...")),"Original multiline binding retained exactly");
            check(KeyboardMigration.report(migrated.keyboardRecovery()).contains("ctrl F1@numpad"),"Recovery appears in actual editor report");
            check(migrated.shortcuts().get("Host F13").equals(old.getProperty("binding.Host F13")),"Host multiline identities untouched");
            store.save(migrated); check(store.load().equals(migrated),"Format 13 full roundtrip");
            Properties off=StateStore.properties(base); off.remove("appearance.cursorStyle"); off.setProperty("version","12"); old.setProperty("binding.Preferences...","");
            off.setProperty("binding.Preferences...","");
            off.remove("windows"); off.remove("info.footerDevice");
            off.remove("addProfile"); off.remove("savedLayouts"); off.remove("selectedLayout");
            check(!KeyBindings.effective(store.decode(off).shortcuts()).containsKey(KeyChord.parse("ctrl COMMA")),"Explicit settings unbinding does not resurrect");
            Properties claimed=StateStore.properties(base); claimed.remove("appearance.cursorStyle"); claimed.setProperty("version","12"); claimed.setProperty("binding.Copy selection / screen","ctrl COMMA");
            claimed.remove("windows"); claimed.remove("info.footerDevice");
            claimed.remove("addProfile"); claimed.remove("savedLayouts"); claimed.remove("selectedLayout");
            check(KeyBindings.effective(store.decode(claimed).shortcuts()).get(KeyChord.parse("ctrl COMMA")).equals("Copy selection / screen"),"Other custom owner remains intact");
            Properties relabeled=StateStore.properties(migrated); relabeled.setProperty("version","12");
            rejects(store,relabeled,"Format 13 cannot be relabeled as 12");
            Properties invalid=StateStore.properties(base); invalid.setProperty("binding.Fonts...","ctrl F1");
            rejects(store,invalid,"Old titles are recovery only in format 13");
            check(StateStore.freshState().connectOnStartup(),"Actual new-workspace initializer enables startup");
            check(Profile.create("Password").automaticSignOn() && Profile.create("Password").rememberPassword(),"Actual new-password factory enables recommended options");
        }
    }

    private static void topology(Fixture fixture) throws Exception {
        Workspace w=fixture.workspace;
        MenuBar menus=(MenuBar)field(w,"menuBar");
        check(menus.getMenus().stream().map(Menu::getText).toList()
                .equals(List.of("Session","Edit","Inserts","Panes","View","Host","Help")),"Task-based top-level menus");
        for (Menu menu:menus.getMenus()) {
            for (MenuItem entry:menu.getItems()) {
                if (entry instanceof Menu nested)
                    check(menu.getText().equals("Help") && nested.getText().equals("Tutorials") && nested.getItems().size()==6,
                            "Only meaningful Tutorials submenu remains");
                if (entry.getProperties().get("fish.command") instanceof AppCommand action)
                    check(!Shortcuts.SETTINGS_ALIASES.contains(action.title)
                            && (!action.title.equals(Shortcuts.SETTINGS) || menu.getText().equals("Edit")),"Settings is only under Edit");
            }
        }
        for (MenuItem entry:w.paneMenu(w.active()).getItems())
            check(!(entry.getProperties().get("fish.command") instanceof AppCommand command)
                    || !Shortcuts.SETTINGS_ALIASES.contains(command.title),"No context appearance launcher");
        long visible=FxTestSupport.children(w).stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> b.getProperties().get("fish.command") instanceof AppCommand command && command.title.equals(Shortcuts.SETTINGS)).count();
        check(visible==0,"No top-level Settings button");
        WindowChrome chrome=(WindowChrome)field(w,"chrome");
        HBox tools=(HBox)field(chrome,"tools");
        check(tools.getChildren().size()==3
                && tools.getChildren().get(0) instanceof ComboBox<?> && tools.getChildren().get(1) instanceof Separator
                && tools.getChildren().get(2) instanceof Button addBtn
                && addBtn.getProperties().get("fish.command") instanceof AppCommand cmd && cmd.title.equals("Add pane"),
                "Only profile/+ split-pill follows all menus without a leading separator before reserved drag space and window controls");
        check(menus.getMenus().get(1).getItems().stream()
                .filter(item -> item.getProperties().get("fish.command") instanceof AppCommand action && action.title.equals(Shortcuts.SETTINGS)).count()==1,
                "Exactly one Edit Settings entry");
        AppCommand settings=w.commands().stream().filter(c -> c.title.equals(Shortcuts.SETTINGS)).findFirst().orElseThrow();
        settings.execute();
        Preferences p=w.tutorialPreferences(); check(p!=null && p.category().equals("Appearance"),"Actual Settings action opens Appearance controls");
        check(fixture.http.checks.get()==0,"Constructing Appearance sends no metadata request");
        check(w.preferencesDialog("Appearance")==p.dialog(),"Same Settings dialog reused");
        @SuppressWarnings("unchecked") ListView<String> nav=(ListView<String>)field(p,"navigation");
        check(List.copyOf(nav.getItems()).equals(List.of("Appearance","Profiles","Keyboard Shortcuts","General","Updates")),
                "Direct understandable category index");
        for (String control:List.of("theme","palette","effect","font","header","footer","outline"))
            check(p.tutorialTarget(control)!=null,"Real inline Appearance control: "+control);
        for (String title:List.of("Copy selection / screen","Paste into fields","Add pane","Zoom in")) {
            AppCommand command=w.commands().stream().filter(c -> c.title.equals(title)).findFirst().orElseThrow();
            String primary=command.primaryShortcut();
            check(!primary.isEmpty() && !primary.contains(" / "),"One primary accelerator: "+title);
        }
        check(w.shortcutLabel("Copy selection / screen").equals(KeyChord.parse("ctrl C").label()),"Common Copy chord is primary");
        p.select("Profiles"); check(p.profiles.getParent()!=null && button(p.dialog(),"New")!=null,"Actual profile editor inline, not a launcher");
        @SuppressWarnings("unchecked") ComboBox<Profile.Authentication> auth=(ComboBox<Profile.Authentication>)field(p.profiles,"authentication");
        auth.setValue(Profile.Authentication.PASSWORD);
        check(((CheckBox)field(p.profiles,"automatic")).isSelected() && ((CheckBox)field(p.profiles,"remember")).isSelected(),"First password-mode switch enables creation defaults");
        ((CheckBox)field(p.profiles,"remember")).setSelected(false); auth.setValue(Profile.Authentication.KERBEROS); auth.setValue(Profile.Authentication.PASSWORD);
        check(!((CheckBox)field(p.profiles,"remember")).isSelected(),"Mode toggling retains the user's later off choice");
        p.closeSettings();
        UsabilityIterationSmoke.settle(p::ended,"Profile mode edits did not finish saving");
    }

    private static void tutorials(Fixture fixture) throws Exception {
        Workspace w=fixture.workspace; int profilesBefore=w.profileList().size();
        w.openTutorial(TutorialDialog.Guide.GETTING_STARTED,true);
        check(guide(w).stage().getModality()==Modality.NONE,"Tutorial is actionable/modeless");
        button(guide(w).stage(),"Skip step").fire(); check(step(w)==1,"Skip profile selection without host/auth activity");
        w.executeCommand("Add pane"); check(step(w)==2 && w.sessionPanes().size()==1,"Actual Add action progresses intro and reuses a pristine tile");
        w.executeCommand("Add pane");
        ((Button)w.active().tutorialCloseTarget()).fire(); check(step(w)==3 && w.sessionPanes().size()==1,"Actual X progresses intro");
        w.executeCommand(Shortcuts.SETTINGS); check(step(w)==4,"Actual Settings route progresses intro");
        check(w.profileList().size()==profilesBefore && !w.active().busy(),"Intro never creates a profile or connects secretly");
        button(guide(w).stage(),"Skip tutorial").fire(); check(w.tutorialStatus()==StateStore.Tutorial.SKIPPED && guide(w).ended(),"Skip whole guide at any step");

        w.openTutorial(TutorialDialog.Guide.APPEARANCE,false); button(guide(w).stage(),"Show control").fire();
        Preferences p=w.tutorialPreferences(); check(step(w)==1 && p.category().equals("Appearance"),"Guide opens actual Appearance category");
        ComboBox<?> app=(ComboBox<?>)field(p,"theme"); app.getSelectionModel().select((app.getSelectionModel().getSelectedIndex()+1)%AppTheme.values().length);
        check(step(w)==2,"Actual theme selection progresses");
        ComboBox<?> palette=(ComboBox<?>)field(p,"palette"); palette.getSelectionModel().select((palette.getSelectionModel().getSelectedIndex()+1)%palette.getItems().size());
        check(step(w)==3,"Actual palette selection progresses");
        ((ComboBox<?>)field(p,"family")).getSelectionModel().select(1); check(step(w)==4,"Actual font selection progresses");
        ((CheckBox)p.tutorialTarget("header")).fire(); check(step(w)==5,"Actual header control progresses");
        ((CheckBox)p.tutorialTarget("footer")).fire(); check(step(w)==6,"Actual footer control progresses");
        p.tutorialTarget("theme"); layout(p.dialog()); snapshot(p.dialog(),"appearance-top");
        snapshot(p.dialog(),"appearance-info");
        UsabilityIterationSmoke.settle(() -> !p.saving(),"Appearance did not autosave");
        check(guide(w).ended() && !p.ended(),"Acknowledged autosave finishes guide and retains Settings");
        check(w.profileList().get(1).theme().equals("Phosphor"),"Appearance does not silently recolor every profile");

        w.openTutorial(TutorialDialog.Guide.PASSWORD_PROFILE,false); button(guide(w).stage(),"Show control").fire();
        check(step(w)==1,"Profile guide selects actual inline editor");
        button(p.dialog(),"New").fire(); check(step(w)==2 && w.profileList().size()==profilesBefore,"New is a draft, not a duplicate save");
        @SuppressWarnings("unchecked") ComboBox<Profile.Authentication> auth=(ComboBox<Profile.Authentication>)field(p.profiles,"authentication");
        auth.setValue(Profile.Authentication.PASSWORD); check(step(w)==3,"Actual mode change progresses");
        ((TextField)field(p.profiles,"host")).setText("127.0.0.1"); check(step(w)==4,"Actual host edit progresses");
        ((TextField)field(p.profiles,"username")).setText("FIXTURE"); check(step(w)==5,"Actual username edit progresses without reading password");
        ((CheckBox)field(p.profiles,"remember")).fire(); check(step(w)==6,"Actual login preference progresses");
        ((ToggleButton)field(p.profiles,"disclosure")).fire(); check(step(w)==7,"Actual Advanced disclosure progresses");
        snapshot(p.dialog(),"profiles-inline");
        button(p.dialog(),"Create profile").fire();
    }

    private static void keyboardAndPanes(Fixture fixture) throws Exception {
        Workspace w=fixture.workspace; Preferences p=w.tutorialPreferences();
        check(guide(w).ended() && w.profileList().size()==3,"Explicit Create saves exactly once, with no password/ticket operation");
        check(((ComboBox<?>)field(p,"scope")).getItems().size()==3,"New profile immediately available for appearance in same Settings");

        w.openTutorial(TutorialDialog.Guide.KEYBOARD,false); button(guide(w).stage(),"Show control").fire();
        check(step(w)==1,"Guide opens actual Keyboard Shortcuts");
        KeyboardEditor keyboard=(KeyboardEditor)field(p,"keyboard");
        ((TextField)field(keyboard,"search")).setText("Zoom in"); check(step(w)==2,"Actual search progresses");
        @SuppressWarnings("unchecked") TableView<KeyboardEditor.ActionBindingRow> table=(TableView<KeyboardEditor.ActionBindingRow>)field(keyboard,"table");
        table.getSelectionModel().clearSelection(); table.getSelectionModel().select(0);
        check(step(w)==3,"Actual row selection progresses");
        ((TextArea)field(keyboard,"keys")).setText("ctrl shift F9\nctrl shift F10"); check(step(w)==4,"Actual valid multiline edit progresses");
        check(table.getItems().getFirst().effective.get().startsWith(KeyChord.parse("ctrl shift F9").label()),"Effective table retains the primary/chord-list order");
        p.dialog().setWidth(760); p.dialog().setHeight(560); layout(p.dialog()); snapshot(p.dialog(),"keyboard");
        Preferences keysEditor=p;
        UsabilityIterationSmoke.settle(() -> !keysEditor.saving(),"Keyboard edits did not autosave");
        check(guide(w).ended(),"Actual validated autosave completes keyboard guide");
        check(w.shortcutLabel("Zoom in").equals(KeyChord.parse("ctrl shift F9").label()),"Custom first chord is primary, not lexical sorting");
        check(KeyChord.list(w.shortcutOverrides().get("Zoom in")).contains(KeyChord.parse("ctrl shift F10")),"Alternative remains fully editable");
        p.closeSettings();

        w.openTutorial(TutorialDialog.Guide.PANES,false); w.executeCommand("Add pane"); layout(w); check(step(w)==1,"Pane guide progresses actual add");
        w.executeCommand("Decrease divider"); check(step(w)==2,"Horizontal keyboard resize progresses");
        List<SessionPane> pair=w.sessionPanes(); w.dock(pair.getFirst().id(),pair.getLast().id(),Layout.Dock.TOP);
        layout(w); check(step(w)==3,"Actual top-edge placement progresses");
        w.executeCommand("Increase divider"); check(step(w)==4,"Vertical keyboard resize progresses");
        List<SessionPane> order=w.sessionPanes(); w.dock(order.get(0).id(),order.get(1).id(),Layout.Dock.CENTER);
        check(step(w)==5,"Only an actual swap progresses");
        button(guide(w).stage(),"Show control").fire(); p=w.tutorialPreferences();
        ((CheckBox)p.tutorialTarget("startup")).fire(); check(step(w)==6,"Actual startup control progresses");
        Preferences startupEditor=p; UsabilityIterationSmoke.settle(() -> !startupEditor.saving(),"Startup setting did not autosave");
        snapshot(guide(w).stage(),"tutorial");
        w.executeCommand("Fit host screen"); check(step(w)==7,"Actual Fit advances to reusable shortcuts");
        SavedLayout saved=w.saveCurrentLayout(null,"Tutorial synthetic layout");
        check(guide(w).ended(),"Actual named layout save finishes pane guide"); w.deleteSavedLayout(saved.id());

        for (TutorialDialog.Guide type:TutorialDialog.Guide.values()) {
            int count=w.profileList().size(); w.openTutorial(type,false); check(step(w)==0,"Replay restarts "+type);
            button(guide(w).stage(),"Skip step").fire(); button(guide(w).stage(),"Back").fire();
            check(step(w)==0 && !button(guide(w).stage(),"Skip tutorial").isDisabled(),"Back and both skips available in "+type);
            button(guide(w).stage(),"Skip tutorial").fire(); check(w.profileList().size()==count,"Replay/skip causes no profile changes");
        }
        p.closeSettings();
        Preferences last=p; UsabilityIterationSmoke.settle(last::ended,"Startup option did not finish saving");
    }

    public static void main(String[] args) throws Exception {
        Ui.install(); String path=System.getProperty("fish.test.evidence","");
        if (!path.isBlank()) { evidence=Path.of(path); Files.createDirectories(evidence); }
        edt(() -> {
            CheckBox disabled=new CheckBox("Inactive setting choices"); disabled.setDisable(true);
            javafx.scene.layout.StackPane holder=new javafx.scene.layout.StackPane(disabled);
            javafx.scene.Scene sc=new javafx.scene.Scene(holder,250,36);
            sc.getStylesheets().setAll(Ui.activeStylesheetUrl());
            holder.applyCss(); holder.layout();
            var image=FxTestSupport.snapshot(holder,1.0);
            check(image.getWidth()==250,"Disabled JavaFX checkbox renders cleanly with theme CSS");
            return null;
        });
        Path root=Files.createTempDirectory("Fish-settings-tutorial-fixtures-");
        try {
            migration(root);
            try (Fixture fixture=new Fixture(root,"flows",false)) {
                edt(() -> { topology(fixture); tutorials(fixture); return null; });
                await(() -> !fixture.workspace.tutorialPreferences().saving(),"Profile Apply did not settle");
                edt(() -> { keyboardAndPanes(fixture); fixture.workspace.preferences("Updates"); return null; });
                await(() -> !button(fixture.workspace.tutorialPreferences().dialog(),"Update...").isDisabled(),"Injected release notes did not arrive");
                edt(() -> {
                    Preferences p=fixture.workspace.tutorialPreferences();
                    MarkdownView markdown=children(p.dialog()).stream().filter(MarkdownView.class::isInstance).map(MarkdownView.class::cast).findFirst().orElseThrow();
                    String html=markdown.htmlSnapshot();
                    check(html.contains("<h3") && html.contains("<li") && html.contains("<pre")
                            && html.contains("<strong>") && html.contains("<em>") && html.contains("<a href="),
                            "Actual update pane renders headings, lists, code, emphasis and links: "+html);
                    check(!html.contains("<script>") && !html.contains("<img"),"Untrusted HTML/images are not executable or loaded");
                    check(!MarkdownView.safeLink(URI.create("file:///C:/private")) && !MarkdownView.safeLink(URI.create("https://user:password@example.invalid")),"Unsafe explicit links rejected");
                    check(fixture.http.downloads.get()==0,"Rendering and guides perform no downloads");
                    snapshot(p.dialog(),"updates"); p.forceDispose(); return null;
                });
            }
            try (Fixture fixture=new Fixture(root,"startup",true)) {
                edt(() -> {
                    fixture.workspace.startup(); SessionPane pane=fixture.workspace.active();
                    check(!pane.busy() && !pane.connected(),"Incomplete startup login remains offline without credential lookup");
                    check(((Label)field(pane,"oia")).getText().contains("Settings > Profiles"),"Startup guidance is actionable");
                    check(!((TransientNotice)field(pane,"noticeOverlay")).isVisible(),"Expected first-start guidance does not flash an alarm");
                    Profile before=pane.profile();
                    Profile off=new Profile(before.id(),before.name(),before.host(),before.port(),"","",false,false,"37","Phosphor","None",1,Map.of(),"");
                    fixture.workspace.putProfile(off);
                    check(!off.duplicate().automaticSignOn() && !off.duplicate().rememberPassword(),"Duplicate retains existing explicit password OFF preferences");
                    ProfilesDialog editor=new ProfilesDialog(fixture.workspace,off);
                    @SuppressWarnings("unchecked") ComboBox<Profile.Authentication> auth=(ComboBox<Profile.Authentication>)field(editor.editor,"authentication");
                    auth.setValue(Profile.Authentication.KERBEROS);
                    auth.setValue(Profile.Authentication.PASSWORD);
                    check(!((CheckBox)field(editor.editor,"automatic")).isSelected() && !((CheckBox)field(editor.editor,"remember")).isSelected(),
                            "Existing explicit PASSWORD off choices survive mode toggles");
                    snapshot(editor.stage(),"profiles-dialog"); editor.dispose();
                    return null;
                });
            }
            edt(() -> { for (Window window:Window.getWindows()) check(!window.isShowing(),"No desktop window was shown"); return null; });
        } finally {
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
        }
        System.out.println("Single Settings / inline controls / Markdown / six interactive tutorials / defaults checks passed: "+checks);
        Ui.finishTestRun();
    }
}
