package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;
import pane5250.LayoutShortcuts;
import pane5250.model.*;
import pane5250.update.*;

/** Never-shown production forms/delegates, empty hosts and native links in a temporary directory only. */
public final class ShortcutRefinementSmoke {
    private static int checks;
    private static Path evidence;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void set(Object owner,String name,Object value) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner,value);
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> result=new FutureTask<>(action); SwingUtilities.invokeAndWait(result); return result.get();
    }
    private static JButton button(Container parent,String text) {
        for (Component c:parent.getComponents()) {
            if (c instanceof JButton b && text.equals(b.getText())) return b;
            if (c instanceof Container child) { JButton found=button(child,text); if (found!=null) return found; }
        }
        return null;
    }
    private static void layout(Container parent) {
        parent.doLayout(); for (Component c:parent.getComponents()) if (c instanceof Container child) layout(child);
    }
    private static BufferedImage paint(Component c,double scale) {
        BufferedImage image=new BufferedImage((int)Math.ceil(c.getWidth()*scale),(int)Math.ceil(c.getHeight()*scale),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); g.scale(scale,scale); c.printAll(g); g.dispose(); return image;
    }
    private static void image(Component c,String name) throws Exception {
        if (evidence==null) return;
        for (double scale:new double[]{1,1.5}) ImageIO.write(paint(c,scale),"png",evidence.resolve(name+"-"+scale+".png").toFile());
    }
    private static int pixels(BufferedImage image,Color color) {
        int count=0,rgb=color.getRGB()&0xffffff;
        for (int y=0;y<image.getHeight();y++) for (int x=0;x<image.getWidth();x++) if ((image.getRGB(x,y)&0xffffff)==rgb) count++;
        return count;
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
        button(p.dialog(),"Allowed keys and actions...").doClick(0);
        KeyboardReference reference=(KeyboardReference)field(editor,"reference");
        check(reference!=null && reference.getModalityType()==Dialog.ModalityType.MODELESS,"Reference is discoverable in the real editor, not another settings route");
        check(editor.draft().equals(before),"Browsing reference never edits draft bindings");
        JTable keys=(JTable)field(reference,"keys"),actions=(JTable)field(reference,"actions");
        check(keys.getRowCount()==KeyChord.supportedKeys().size(),"Allowed keys come from parser definitions");
        check(actions.getRowCount()==KeyBindings.defaultsByAction().size()-Shortcuts.SETTINGS_ALIASES.size(),"All assignable canonical actions and Unbound keys are listed");
        check(keys.getAccessibleContext().getAccessibleName().contains("Allowed") && actions.getAccessibleContext().getAccessibleName().contains("Assignable"),"Reference tables have accessible names");
        JTextField search=(JTextField)field(reference,"search");
        search.setText("ENTER"); check(keys.getRowCount()>1 && keys.getRowCount()<KeyChord.supportedKeys().size(),"Keys search filters names and syntax");
        search.setText(""); keys.setRowSelectionInterval(0,0);
        check(!((JTextArea)field(reference,"syntax")).getText().isBlank(),"Selected key exposes selectable entry syntax");
        search.setText("Zoom in"); ((JTabbedPane)field(reference,"tabs")).setSelectedIndex(1);
        check(actions.getRowCount()==1,"Action list is independently browsable/searchable");
        actions.setRowSelectionInterval(0,0);
        check(((JTextArea)field(reference,"syntax")).getText().equals(KeyBindings.defaultText("Zoom in")),"Action defaults retain exact alternative/location syntax");
        reference.setSize(760,580); layout(reference); image(reference.getContentPane(),"keys-actions");
        button(reference,"Edit selected action").doClick(0);
        JTable table=(JTable)field(editor,"table");
        check(table.getValueAt(table.getSelectedRow(),1).equals("Zoom in"),"Reference action navigation selects its real draft editor row");
        check(editor.draft().equals(before),"Choosing an action still never changes its binding");
        JTextArea input=(JTextArea)field(editor,"keys"); input.setText("ctrl shift F9\nctrl shift F10");
        check(editor.draft().get("Zoom in").equals(KeyChord.text(KeyChord.list(input.getText()))),"Illustrated multiline format remains editable and normalized");
        p.dialog().setSize(1060,740); layout(p.dialog()); image(p.dialog().getContentPane(),"keyboard-settings");
        p.dialog().setSize(760,560); layout(p.dialog());
        check(table.getParent().getHeight()>=90 && input.getHeight()>=32,"Visible keyboard viewport and multiline editor remain usable at minimum Settings size");
        image(p.dialog().getContentPane(),"keyboard-settings-minimum");
        p.dialog().dispose();
        UsabilityIterationSmoke.settle(p::ended,"Reference-driven key edit did not finish saving");
        check(w.shortcutOverrides().containsKey("Zoom in"),"Closing Settings flushes the reference-driven valid edit");
    }
    private static TutorialDialog guide(Workspace w) throws Exception { return (TutorialDialog)field(w,"tutorial"); }
    private static int step(Workspace w) throws Exception { return (int)field(guide(w),"step"); }
    private static void tutorial(Fixture f,Path root) throws Exception {
        Workspace w=f.workspace; int profiles=w.profileList().size();
        w.executeCommand("Tutorial: Desktop shortcuts");
        check(step(w)==0 && w.savedLayouts().isEmpty() && !w.active().busy(),"Opening guide has no session, profile or recipe side effects");
        button(guide(w),"Skip step").doClick(0); button(guide(w),"Skip step").doClick(0);
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
            @Override public int choose(JFileChooser chooser,Component owner) {
                try {
                    choices[0]++;
                    check(chooser.getCurrentDirectory().toPath().equals(root),"Production chooser starts in the injected destination, never actual Desktop");
                    check(step(w)==5 && w.tutorialLayoutTarget("destination")==chooser,"Guide follows the real file chooser before approval");
                    check(chooser.getSelectedFile().getName().endsWith(".lnk"),"Production filename is a Windows link");
                    chooser.setSelectedFile(root.resolve("Synthetic tutorial shortcut.lnk").toFile());
                    return mode[0]==0 ? JFileChooser.CANCEL_OPTION : JFileChooser.APPROVE_OPTION;
                } catch (Exception e) { throw new IllegalStateException(e); }
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
        JTextField name=(JTextField)field(manager,"name"); name.setText("Synthetic tutorial layout");
        check(step(w)==4 && w.savedLayouts().isEmpty(),"Real name entry advances without saving");
        check(w.tutorialLayoutTarget("export")==field(manager,"create"),"Guide anchor is the direct current-window export button");
        button(manager,"Create desktop shortcut...").doClick(0);
        check(step(w)==5 && w.savedLayouts().isEmpty() && writes[0]==0 && !Files.exists(root.resolve("Synthetic tutorial shortcut.lnk")),"Cancel creates no recipe or file and never claims success");
        mode[0]=1; button(manager,"Create desktop shortcut...").doClick(0);
        check(step(w)==5 && w.savedLayouts().size()==1 && ((JLabel)field(manager,"message")).getText().contains("refused"),"Failed export keeps the saved recipe and stays actionable");
        ((JToggleButton)field(manager,"manage")).doClick(0);
        mode[0]=2; button(manager,"Create shortcut...").doClick(0);
        check(step(w)==6 && writes[0]==2 && choices[0]==3,"Only actual successful native export advances to the launch instructions");
        String[] link=LayoutShortcuts.inspect(root.resolve("Synthetic tutorial shortcut.lnk"));
        check(link[0].equals(target.toString()) && link[1].equals(LayoutShortcuts.arguments(w.savedLayouts().getFirst().id(),w.dataDirectory())),
                "Guided production chooser/export path produces a real native link with exact stable recipe reference");
        layout(manager); layout(guide(w)); image(manager.getContentPane(),"layout-manager"); image(guide(w).getContentPane(),"desktop-guide");
        button(guide(w),"Back").doClick(0); check(step(w)==5,"Back remains available after actual export");
        button(guide(w),"Skip tutorial").doClick(0); check(guide(w).ended(),"Guide is skippable at export stage");
        int saved=w.savedLayouts().size();
        w.executeCommand("Tutorial: Desktop shortcuts");
        check(step(w)==0 && w.profileList().size()==profiles && w.savedLayouts().size()==saved && !w.active().busy(),"Replay resets progress without new sessions, recipes or profile changes");
        button(guide(w),"Skip tutorial").doClick(0); manager.dispose();
    }
    private static void chrome(Workspace w) throws Exception {
        WindowChrome chrome=(WindowChrome)field(w,"chrome");
        JComboBox<?> combo=(JComboBox<?>)field(w,"addProfileSelector"); JButton add=(JButton)field(w,"addPane");
        for (AppTheme theme:List.of(AppTheme.Charcoal,AppTheme.Midnight,AppTheme.Paper)) {
            Ui.applyTheme(theme); w.setSize(1120,760); layout(w);
            check(combo.getInsets().equals(new Insets(0,0,0,0)) && !combo.isOpaque(),"Chrome selector stays borderless across theme refresh: "+theme);
            check(combo.getHeight()==add.getHeight() && combo.getY()==add.getY(),"Profile and plus share menu-row height and alignment: "+theme);
            Component separator=combo.getParent().getComponent(2);
            check(separator instanceof JSeparator && combo.getX()+combo.getWidth()==separator.getX()
                    && separator.getX()+separator.getWidth()==add.getX(),"Profile and plus remain aligned with the requested thin separator between them");
            image(chrome,"chrome-"+theme);
            for (int width:new int[]{330,500,680,1120}) {
                w.setSize(width,760); layout(w);
                Component controls=(Component)field(chrome,"controls"),drag=(Component)field(chrome,"dragArea");
                check(controls.getX()+controls.getWidth()<=chrome.getWidth() && drag.getWidth()>=16 && combo.getWidth()>=45,
                        "Narrow chrome retains selector, drag space and native controls at "+width+" / "+theme);
                if (width==330 || width==680) image(chrome,"chrome-"+theme+"-"+width);
            }
        }
        Ui.applyTheme(AppTheme.Charcoal);
        final boolean[] focus={false};
        JComboBox<String> sample=new JComboBox<>(new String[]{"Synthetic profile"}) { @Override public boolean hasFocus() { return focus[0]; } };
        sample.putClientProperty("chrome.control",true); sample.updateUI(); sample.setSize(160,27); layout(sample);
        BufferedImage rest=paint(sample,1.5);
        check(pixels(rest,Ui.color(ColorRole.CHROME))>rest.getWidth()*rest.getHeight()/2,"Resting selector uses the titlebar surface, not a boxed form fill");
        image(sample,"selector-rest");
        sample.dispatchEvent(new MouseEvent(sample,MouseEvent.MOUSE_ENTERED,0,0,5,5,0,false));
        check(pixels(paint(sample,1.5),Ui.color(ColorRole.MENU_HOVER))>100,"Hover uses the same semantic menu surface");
        image(sample,"selector-hover");
        sample.dispatchEvent(new MouseEvent(sample,MouseEvent.MOUSE_EXITED,0,0,5,5,0,false));
        focus[0]=true;
        check(pixels(paint(sample,1.5),Ui.FOCUS)>80,"Keyboard focus paints a measurable physical-pixel underline without a form-field box");
        image(sample,"selector-focus");
        sample.setEnabled(false);
        check(pixels(paint(sample,1.5),Ui.color(ColorRole.DISABLED))>0,"Disabled selector retains readable semantic text");
        image(sample,"selector-disabled");
        check(combo.getAccessibleContext().getAccessibleName().equals("Profile for new pane")
                && add.getAccessibleContext().getAccessibleName().contains("Add and connect"),"Original accessible intent and keyboard controls preserved");
    }
    public static void main(String[] args) throws Exception {
        Ui.install(); syntax(); String path=System.getProperty("fish.test.evidence","");
        if (!path.isBlank()) { evidence=Path.of(path); Files.createDirectories(evidence); }
        Path root=Files.createTempDirectory(Path.of("build"),"shortcut-refinement-").toAbsolutePath();
        try (Fixture fixture=new Fixture(root.resolve("workspace"))) {
            edt(() -> { chrome(fixture.workspace); keyboard(fixture.workspace); tutorial(fixture,root); return null; });
            check(Arrays.stream(Window.getWindows()).noneMatch(Window::isShowing),"No test window was ever shown");
        } finally {
            edt(() -> { for (Window w:Window.getWindows()) w.dispose(); return null; });
            try (var files=Files.walk(root)) {
                for (Path file:files.sorted(Comparator.reverseOrder()).toList()) {
                    for (int attempt=0;;attempt++) {
                        try { Files.deleteIfExists(file); break; }
                        catch (java.nio.file.FileSystemException locked) {
                            if (attempt==9) throw locked;
                            Thread.sleep(100);
                        }
                    }
                }
            }
        }
        System.out.println("PASS unified chrome / parser-derived reference / real shortcut tutorial: "+checks);
    }
}
