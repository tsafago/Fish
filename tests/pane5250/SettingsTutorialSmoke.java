package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.*;
import pane5250.model.*;
import pane5250.update.*;

/** Shipped constructors/actions, isolated configuration, injected metadata, and no desktop/auth/install access. */
public final class SettingsTutorialSmoke {
    private static int checks;
    private static Path evidence;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static Object field(Object owner,String name) throws Exception {
        Field value=owner.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(owner);
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> work=new FutureTask<>(action); SwingUtilities.invokeAndWait(work); return work.get();
    }
    private static void await(Callable<Boolean> action,String message) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime()<end) { if (edt(action)) return; Thread.sleep(15); }
        throw new AssertionError(message);
    }
    private static JButton button(Container parent,String text) {
        for (Component child:parent.getComponents()) {
            if (child instanceof JButton b && text.equals(b.getText())) return b;
            if (child instanceof Container container) { JButton found=button(container,text); if (found!=null) return found; }
        }
        return null;
    }
    private static List<Component> children(Container parent) {
        List<Component> all=new ArrayList<>();
        for (Component child:parent.getComponents()) { all.add(child); if (child instanceof Container container) all.addAll(children(container)); }
        return all;
    }
    private static void layout(Container parent) {
        parent.doLayout(); for (Component child:parent.getComponents()) if (child instanceof Container container) layout(container);
    }
    private static void snapshot(JDialog window,String name) throws Exception {
        if (evidence==null) return;
        if (!window.isDisplayable()) window.addNotify();
        layout(window); Container body=window.getContentPane();
        double scale=window.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        BufferedImage image=new BufferedImage((int)Math.ceil(body.getWidth()*scale),(int)Math.ceil(body.getHeight()*scale),BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=image.createGraphics(); graphics.scale(scale,scale); body.printAll(graphics); graphics.dispose();
        ImageIO.write(image,"png",evidence.resolve(name+".png").toFile());
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
            Map<String,Object> release=MaintenanceSmoke.release("0.7.5",true);
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
                    "37","Phosphor","None",1,Map.of(),"") : Profile.createKerberos("Synthetic workstation");
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
            edt(() -> { for (Window window:workspace.getOwnedWindows()) window.dispose(); workspace.close(false); return null; });
            check(closed.await(10,TimeUnit.SECONDS),"Workspace closed and persisted");
        }
    }
    private static void migration(Path root) throws Exception {
        StateStore.State base=StateStore.freshState();
        try (StateStore store=new StateStore(Files.createDirectory(root.resolve("format")))) {
            Properties old=StateStore.properties(base); old.setProperty("version","12"); old.setProperty("connectOnStartup","false");
            old.remove("windows"); old.remove("info.footerDevice");
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
            Properties off=StateStore.properties(base); off.setProperty("version","12"); off.setProperty("binding.Preferences...","");
            off.remove("windows"); off.remove("info.footerDevice");
            check(!KeyBindings.effective(store.decode(off).shortcuts()).containsKey(KeyChord.parse("ctrl COMMA")),"Explicit settings unbinding does not resurrect");
            Properties claimed=StateStore.properties(base); claimed.setProperty("version","12"); claimed.setProperty("binding.Copy selection / screen","ctrl COMMA");
            claimed.remove("windows"); claimed.remove("info.footerDevice");
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
        JMenuBar menus=(JMenuBar)field(w,"menuBar");
        check(Arrays.stream(menus.getComponents()).filter(JMenu.class::isInstance).map(JMenu.class::cast).map(JMenu::getText).toList()
                .equals(List.of("Session","Edit","Inserts","Panes","View","Host","Help")),"Task-based top-level menus");
        for (Component top:menus.getComponents()) if (top instanceof JMenu menu) {
            for (Component entry:menu.getMenuComponents()) if (entry instanceof JMenu nested)
                check(menu.getText().equals("Help") && nested.getText().equals("Tutorials") && nested.getItemCount()==5,"Only meaningful Tutorials submenu remains");
            for (Component entry:menu.getMenuComponents()) if (entry instanceof JMenuItem item && item.getAction() instanceof AppCommand action)
                check(!Shortcuts.SETTINGS_ALIASES.contains(action.title)
                        && (!action.title.equals(Shortcuts.SETTINGS) || menu.getText().equals("Edit")),"Settings is only under Edit");
        }
        for (Component entry:w.paneMenu(w.active()).getComponents()) if (entry instanceof JMenuItem item)
            check(!(item.getAction() instanceof AppCommand command) || !Shortcuts.SETTINGS_ALIASES.contains(command.title),"No context appearance launcher");
        long visible=children(w.getContentPane()).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(b -> b.getAction() instanceof AppCommand command && command.title.equals(Shortcuts.SETTINGS)).count();
        check(visible==0,"No top-level Settings button");
        WindowChrome chrome=(WindowChrome)field(w,"chrome");
        JPanel actions=(JPanel)field(chrome,"actions");
        Component tools=actions.getComponent(actions.getComponentCount()-1);
        check(tools instanceof JPanel panel && panel.getComponentCount()==1
                && ((JButton)panel.getComponent(0)).getAction() instanceof AppCommand command && command.title.equals("Add pane"),
                "Only + Pane follows all menus before reserved drag space and window controls");
        check(Arrays.stream(menus.getMenu(1).getMenuComponents()).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast)
                .filter(item -> item.getAction() instanceof AppCommand action && action.title.equals(Shortcuts.SETTINGS)).count()==1,"Exactly one Edit Settings entry");
        AppCommand settings=w.commands().stream().filter(c -> c.title.equals(Shortcuts.SETTINGS)).findFirst().orElseThrow();
        settings.actionPerformed(new ActionEvent(w,0,"fixture"));
        Preferences p=w.tutorialPreferences(); check(p!=null && p.category().equals("Appearance"),"Actual Settings action opens Appearance controls");
        check(fixture.http.checks.get()==0,"Constructing Appearance sends no metadata request");
        check(w.preferencesDialog("Appearance")==p.dialog(),"Same Settings dialog reused");
        @SuppressWarnings("unchecked") JList<String> nav=(JList<String>)field(p,"navigation");
        check(java.util.stream.IntStream.range(0,nav.getModel().getSize()).mapToObj(nav.getModel()::getElementAt).toList()
                .equals(List.of("Appearance","Profiles","Keyboard Shortcuts","General","Updates")),"Direct understandable category index");
        for (String control:List.of("theme","palette","effect","font","header","footer","outline"))
            check(SwingUtilities.isDescendingFrom(p.tutorialTarget(control),p.dialog()),"Real inline Appearance control: "+control);
        for (String title:List.of("Copy selection / screen","Paste into fields","Add pane","Zoom in")) {
            AppCommand command=w.commands().stream().filter(c -> c.title.equals(title)).findFirst().orElseThrow();
            String primary=Objects.toString(command.getValue("Fish.shortcut"));
            check(!primary.isEmpty() && !primary.contains(" / "),"One primary accelerator: "+title);
        }
        check(w.shortcutLabel("Copy selection / screen").equals(KeyChord.parse("ctrl C").label()),"Common Copy chord is primary");
        p.select("Profiles"); check(p.profiles.getParent()!=null && button(p.dialog(),"New")!=null,"Actual profile editor inline, not a launcher");
        JComboBox<?> auth=(JComboBox<?>)field(p.profiles,"authentication"); auth.setSelectedItem(Profile.Authentication.PASSWORD);
        check(((JCheckBox)field(p.profiles,"automatic")).isSelected() && ((JCheckBox)field(p.profiles,"remember")).isSelected(),"First password-mode switch enables creation defaults");
        ((JCheckBox)field(p.profiles,"remember")).setSelected(false); auth.setSelectedItem(Profile.Authentication.KERBEROS); auth.setSelectedItem(Profile.Authentication.PASSWORD);
        check(!((JCheckBox)field(p.profiles,"remember")).isSelected(),"Mode toggling retains the user's later off choice");
        p.dialog().dispose();
    }
    private static void tutorials(Fixture fixture) throws Exception {
        Workspace w=fixture.workspace; int profilesBefore=w.profileList().size();
        w.openTutorial(TutorialDialog.Guide.GETTING_STARTED,true);
        check(guide(w).getModalityType()==Dialog.ModalityType.MODELESS,"Tutorial is actionable/modeless");
        check(guide(w).getModalExclusionType()==Dialog.ModalExclusionType.APPLICATION_EXCLUDE,"Guide remains accessible with a real modal editor");
        button(guide(w),"Skip step").doClick(0); check(step(w)==1,"Skip connection without host/auth activity");
        w.executeCommand("Add pane"); check(step(w)==2 && w.sessionPanes().size()==2,"Actual Add action progresses intro");
        ((JButton)w.active().tutorialCloseTarget()).doClick(0); check(step(w)==3 && w.sessionPanes().size()==1,"Actual X progresses intro");
        w.executeCommand(Shortcuts.SETTINGS); check(step(w)==4,"Actual Settings route progresses intro");
        check(w.profileList().size()==profilesBefore && !w.active().busy(),"Intro never creates a profile or connects secretly");
        button(guide(w),"Skip tutorial").doClick(0); check(w.tutorialStatus()==StateStore.Tutorial.SKIPPED && guide(w).ended(),"Skip whole guide at any step");
        w.openTutorial(TutorialDialog.Guide.APPEARANCE,false); button(guide(w),"Show control").doClick(0);
        Preferences p=w.tutorialPreferences(); check(step(w)==1 && p.category().equals("Appearance"),"Guide opens actual Appearance category");
        JComboBox<?> app=(JComboBox<?>)field(p,"theme"); app.setSelectedIndex((app.getSelectedIndex()+1)%AppTheme.values().length);
        check(step(w)==2,"Actual theme selection progresses");
        JComboBox<?> palette=(JComboBox<?>)field(p,"palette"); palette.setSelectedIndex((palette.getSelectedIndex()+1)%palette.getItemCount());
        check(step(w)==3,"Actual palette selection progresses");
        ((JComboBox<?>)field(p,"family")).setSelectedIndex(1); check(step(w)==4,"Actual font selection progresses");
        ((JCheckBox)p.tutorialTarget("header")).doClick(0); check(step(w)==5,"Actual header control progresses");
        ((JCheckBox)p.tutorialTarget("footer")).doClick(0); check(step(w)==6,"Actual footer control progresses");
        p.tutorialTarget("theme"); layout(p.dialog()); snapshot(p.dialog(),"appearance-top");
        JCheckBox tint=(JCheckBox)field(p,"tint"); tint.scrollRectToVisible(new Rectangle(0,0,tint.getWidth(),tint.getHeight()));
        layout(p.dialog()); snapshot(p.dialog(),"appearance-info");
        button(p.dialog(),"Apply").doClick(0); check(guide(w).ended() && !p.ended(),"Actual Apply finishes guide and retains Settings");
        check(w.profileList().get(1).theme().equals("Phosphor"),"Appearance does not silently recolor every profile");
        w.openTutorial(TutorialDialog.Guide.PASSWORD_PROFILE,false); button(guide(w),"Show control").doClick(0);
        check(step(w)==1,"Profile guide selects actual inline editor");
        button(p.dialog(),"New").doClick(0); check(step(w)==2 && w.profileList().size()==profilesBefore,"New is a draft, not a duplicate save");
        ((JComboBox<?>)field(p.profiles,"authentication")).setSelectedItem(Profile.Authentication.PASSWORD); check(step(w)==3,"Actual mode change progresses");
        ((JTextField)field(p.profiles,"host")).setText("127.0.0.1"); check(step(w)==4,"Actual host edit progresses");
        ((JTextField)field(p.profiles,"username")).setText("FIXTURE"); check(step(w)==5,"Actual username edit progresses without reading password");
        ((JCheckBox)field(p.profiles,"remember")).doClick(0); check(step(w)==6,"Actual login preference progresses");
        ((JToggleButton)field(p.profiles,"disclosure")).doClick(0); check(step(w)==7,"Actual Advanced disclosure progresses");
        snapshot(p.dialog(),"profiles-inline");
        button(p.dialog(),"Apply").doClick(0);
    }
    private static void keyboardAndPanes(Fixture fixture) throws Exception {
        Workspace w=fixture.workspace; Preferences p=w.tutorialPreferences();
        check(guide(w).ended() && w.profileList().size()==3,"Explicit Apply saves exactly once, with no password/ticket operation");
        check(((JComboBox<?>)field(p,"scope")).getItemCount()==3,"New profile immediately available for appearance in same Settings");
        w.openTutorial(TutorialDialog.Guide.KEYBOARD,false); button(guide(w),"Show control").doClick(0);
        check(step(w)==1,"Guide opens actual Keyboard Shortcuts");
        KeyboardEditor keyboard=(KeyboardEditor)field(p,"keyboard");
        ((JTextField)field(keyboard,"search")).setText("Zoom in"); check(step(w)==2,"Actual search progresses");
        JTable table=(JTable)field(keyboard,"table"); table.clearSelection(); table.setRowSelectionInterval(0,0);
        check(step(w)==3,"Actual row selection progresses");
        ((JTextArea)field(keyboard,"keys")).setText("ctrl shift F9\nctrl shift F10"); check(step(w)==4,"Actual valid multiline edit progresses");
        check(table.getValueAt(0,2).toString().startsWith(KeyChord.parse("ctrl shift F9").label()),"Effective table retains the primary/chord-list order");
        p.dialog().setSize(760,560); layout(p.dialog()); snapshot(p.dialog(),"keyboard");
        button(p.dialog(),"Apply").doClick(0); check(guide(w).ended(),"Actual validated Apply completes keyboard guide");
        check(w.shortcutLabel("Zoom in").equals(KeyChord.parse("ctrl shift F9").label()),"Custom first chord is primary, not lexical sorting");
        check(KeyChord.list(w.shortcutOverrides().get("Zoom in")).contains(KeyChord.parse("ctrl shift F10")),"Alternative remains fully editable");
        p.dialog().dispose();
        w.openTutorial(TutorialDialog.Guide.PANES,false); w.executeCommand("Add pane"); layout(w); check(step(w)==1,"Pane guide progresses actual add");
        w.executeCommand("Decrease divider"); check(step(w)==2,"Horizontal keyboard resize progresses");
        w.executeCommand("Add pane"); layout(w); check(step(w)==3,"Three-pane layout progresses");
        w.executeCommand("Increase divider"); check(step(w)==4,"Vertical keyboard resize progresses");
        List<SessionPane> order=w.sessionPanes(); w.dock(order.get(0).id(),order.get(1).id(),Layout.Dock.CENTER);
        check(step(w)==5,"Only an actual swap progresses");
        button(guide(w),"Show control").doClick(0); p=w.tutorialPreferences();
        ((JCheckBox)p.tutorialTarget("startup")).doClick(0); check(step(w)==6,"Actual startup control progresses");
        snapshot(guide(w),"tutorial");
        w.executeCommand("Fit host screen"); check(guide(w).ended(),"Actual Fit finishes pane guide");
        for (TutorialDialog.Guide type:TutorialDialog.Guide.values()) {
            int count=w.profileList().size(); w.openTutorial(type,false); check(step(w)==0,"Replay restarts "+type);
            button(guide(w),"Skip step").doClick(0); button(guide(w),"Back").doClick(0);
            check(step(w)==0 && button(guide(w),"Skip tutorial").isEnabled(),"Back and both skips available in "+type);
            button(guide(w),"Skip tutorial").doClick(0); check(w.profileList().size()==count,"Replay/skip causes no profile changes");
        }
        p.dialog().dispose();
    }
    public static void main(String[] args) throws Exception {
        Ui.install(); String path=System.getProperty("fish.test.evidence","");
        if (!path.isBlank()) { evidence=Path.of(path); Files.createDirectories(evidence); }
        edt(() -> {
            JCheckBox disabled=new JCheckBox("Inactive setting choices"); disabled.setEnabled(false); disabled.setSize(250,36);
            BufferedImage image=new BufferedImage(250,36,BufferedImage.TYPE_INT_RGB); Graphics2D graphics=image.createGraphics();
            graphics.setColor(Ui.PANEL); graphics.fillRect(0,0,250,36); disabled.paint(graphics); graphics.dispose();
            int matches=0,expected=Ui.color(ColorRole.DISABLED).getRGB()&0xffffff;
            for (int y=0;y<36;y++) for (int x=24;x<250;x++) if ((image.getRGB(x,y)&0xffffff)==expected) matches++;
            check(matches>=20,"Disabled checkbox text uses readable theme ink, not Basic's background emboss");
            return null;
        });
        Path root=Files.createTempDirectory("Fish-settings-tutorial-fixtures-");
        try {
            migration(root);
            try (Fixture fixture=new Fixture(root,"flows",false)) {
                edt(() -> { topology(fixture); tutorials(fixture); return null; });
                await(() -> !fixture.workspace.tutorialPreferences().saving(),"Profile Apply did not settle");
                edt(() -> { keyboardAndPanes(fixture); fixture.workspace.preferences("Updates"); return null; });
                await(() -> button(fixture.workspace.tutorialPreferences().dialog(),"Update...").isEnabled(),"Injected release notes did not arrive");
                edt(() -> {
                    Preferences p=fixture.workspace.tutorialPreferences();
                    MarkdownView markdown=children(p.dialog()).stream().filter(MarkdownView.class::isInstance).map(MarkdownView.class::cast).findFirst().orElseThrow();
                    String html=markdown.getText(); check(html.contains("<h3") && html.contains("<li") && html.contains("<pre")
                            && html.contains("<strong>") && html.contains("<em>") && html.contains("<a href="),
                            "Actual update pane renders headings, lists, code, emphasis and links: "+html);
                    check(!html.contains("<script>") && !html.contains("<img"),"Untrusted HTML/images are not executable or loaded");
                    check(!MarkdownView.safeLink(URI.create("file:///C:/private")) && !MarkdownView.safeLink(URI.create("https://user:password@example.invalid")),"Unsafe explicit links rejected");
                    check(fixture.http.downloads.get()==0,"Rendering and guides perform no downloads");
                    snapshot(p.dialog(),"updates"); p.dialog().dispose(); return null;
                });
            }
            try (Fixture fixture=new Fixture(root,"startup",true)) {
                edt(() -> {
                    fixture.workspace.startup(); SessionPane pane=fixture.workspace.active();
                    check(!pane.busy() && !pane.connected(),"Incomplete startup login remains offline without credential lookup");
                    check(((JLabel)field(pane,"oia")).getText().contains("Settings > Profiles"),"Startup guidance is actionable");
                    check(!((TransientNotice)field(pane,"noticeOverlay")).isVisible(),"Expected first-start guidance does not flash an alarm");
                    Profile before=pane.profile();
                    Profile off=new Profile(before.id(),before.name(),before.host(),before.port(),"","",false,false,"37","Phosphor","None",1,Map.of(),"");
                    fixture.workspace.putProfile(off);
                    check(!off.duplicate().automaticSignOn() && !off.duplicate().rememberPassword(),"Duplicate retains existing explicit password OFF preferences");
                    ProfilesDialog editor=new ProfilesDialog(fixture.workspace,off);
                    ((JComboBox<?>)field(editor.editor,"authentication")).setSelectedItem(Profile.Authentication.KERBEROS);
                    ((JComboBox<?>)field(editor.editor,"authentication")).setSelectedItem(Profile.Authentication.PASSWORD);
                    check(!((JCheckBox)field(editor.editor,"automatic")).isSelected() && !((JCheckBox)field(editor.editor,"remember")).isSelected(),
                            "Existing explicit PASSWORD off choices survive mode toggles");
                    snapshot(editor,"profiles-dialog"); editor.dispose();
                    return null;
                });
            }
            edt(() -> { for (Window window:Window.getWindows()) check(!window.isShowing(),"No desktop window was shown"); return null; });
        } finally {
            try (var paths=Files.walk(root)) { for (Path file:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
        System.out.println("Single Settings / inline controls / Markdown / five interactive tutorials / defaults checks passed: "+checks);
    }
}
