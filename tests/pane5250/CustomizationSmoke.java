package pane5250;

import java.awt.*;
import java.awt.event.*;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.tn5250j.Session5250;
import pane5250.demo.*;
import pane5250.model.*;
import pane5250.ui.*;

/** Customization acceptance without foreground input, OS clipboard or credentials. */
public final class CustomizationSmoke {
    private static int checks;
    private static void check(boolean value,String text) { checks++; if (!value) throw new AssertionError(text); }
    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> task=new FutureTask<>(work); SwingUtilities.invokeAndWait(task);
        try { return task.get(); } catch (ExecutionException e) { throw new AssertionError(e.getCause()); }
    }
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void set(Object owner,String name,Object value) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner,value);
    }
    private static void layout(Container parent) {
        parent.doLayout(); for (Component child:parent.getComponents()) if (child instanceof Container container) layout(container);
    }
    private static BufferedImage paint(JComponent component,String file) throws IOException {
        BufferedImage image=new BufferedImage(Math.max(1,component.getWidth()),Math.max(1,component.getHeight()),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); try { component.paint(g); } finally { g.dispose(); }
        if (file!=null) ImageIO.write(image,"png",Path.of("build",file).toFile());
        return image;
    }
    private static boolean different(BufferedImage a,BufferedImage b) {
        for (int y=0;y<a.getHeight();y++) for (int x=0;x<a.getWidth();x++) if (a.getRGB(x,y)!=b.getRGB(x,y)) return true;
        return false;
    }
    private static void migration(Path root) throws Exception {
        Profile p=Profile.create("Migration fixture");
        try (StateStore store=new StateStore(root.resolve("migration"))) {
            StateStore.State saved=new StateStore.State(List.of(p),new Layout.Leaf(UUID.randomUUID(),p.id()),980,650);
            store.save(saved);
            Properties xml=new Properties(); try (var in=Files.newInputStream(store.file())) { xml.loadFromXML(in); }
            xml.setProperty("version","4"); xml.setProperty("profile."+p.id()+".effect","Neon");
            xml.keySet().removeIf(key -> key.toString().startsWith("updates."));
            xml.setProperty("shortcut.Search commands...","ctrl F8");
            try (var out=Files.newOutputStream(store.file())) { xml.storeToXML(out,"legacy fixture"); }
            StateStore.State loaded=store.load();
            check(loaded.profiles().getFirst().effect().equals("None") && loaded.profiles().getFirst().id().equals(p.id()),
                    "Neon migrates only its effect to None; profile identity and other data are retained.");
            check(store.loadNotice().contains("Neon") && store.loadNotice().contains("Commands"),"Both migrations are reported explicitly.");
            check(!loaded.shortcuts().containsKey("Search commands...") && loaded.appearance().retiredShortcuts().contains("ctrl F8"),
                    "Removed palette action leaves no shortcut-editor entry and preserves the former chord as a no-op.");
            store.save(loaded);
            check(store.load().equals(loaded),"Version-5 migration survives restart without another destructive reset.");
        }
    }
    private static byte[] inputScreen() {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        DemoScreens.command(out,4,0x40,4,0x11,0,8);
        DemoScreens.at(out,1,1,0x22,"VISIBLE");
        DemoScreens.at(out,2,1,0x27,"PRIVATE");
        DemoScreens.field(out,4,1,8,0x43,0x24);
        DemoScreens.command(out,4,0x42,0,0);
        return DemoScreens.gds(3,out.toByteArray());
    }
    private static void themeChecks(Workspace w) throws Exception {
        Appearance initial=w.appearanceSettings();
        ThemeDesigner designer=new ThemeDesigner(w); designer.addNotify(); designer.validate();
        DefaultTableModel table=(DefaultTableModel)field(designer,"model");
        JTextField name=(JTextField)field(designer,"name");
        check(table.getRowCount()==ColorRole.values().length,"Designer inventories every semantic, host, logo and gradient color role.");
        name.setText("Fixture custom");
        for (int i=0;i<table.getRowCount();i++) {
            ColorRole role=(ColorRole)table.getValueAt(i,1);
            int rgb=ColorTheme.builtin(AppTheme.Lagoon).rgb(role);
            table.setValueAt(String.format("#%06X",rgb),i,2);
        }
        table.setValueAt("#101820",ColorRole.HOST_0.ordinal(),2);
        table.setValueAt("#6AE5BB",ColorRole.HOST_2.ordinal(),2);
        table.setValueAt("#466080",ColorRole.DIVIDER.ordinal(),2);
        ColorTheme draft=designer.draft();
        designer.preview();
        check(w.appearanceSettings().equals(initial) && Ui.customTheme(),"Real live preview leaves the persistent workspace settings untouched.");
        designer.dismiss();
        check(!Ui.customTheme() && w.appearanceSettings().equals(initial),"Cancel restores the actual applied theme, not a corrupt partial draft.");
        designer=new ThemeDesigner(w); designer.addNotify(); designer.validate();
        table=(DefaultTableModel)field(designer,"model"); name=(JTextField)field(designer,"name"); name.setText(draft.name());
        for (int i=0;i<table.getRowCount();i++) table.setValueAt(String.format("#%06X",draft.rgb((ColorRole)table.getValueAt(i,1))),i,2);
        designer.save(false);
        check(w.appearanceSettings().themes().contains(draft) && !Ui.customTheme(),"Save creates a reusable named theme without applying it.");
        designer.save(true);
        check(w.appearanceSettings().custom().orElseThrow().equals(draft) && Ui.colorTheme().equals(draft),"Save & apply uses the actual persistence and renderer path.");
        for (ColorRole role:ColorRole.values())
            check((Ui.color(role).getRGB()&0xffffff)==draft.rgb(role),"No valid chosen RGB value is silently overridden: "+role);
        check(UIManager.getColor("PopupMenu.background").equals(Ui.color(ColorRole.MENU))
                && UIManager.getColor("ToolTip.foreground").equals(Ui.color(ColorRole.TOOLTIP_TEXT))
                && UIManager.getColor("TextField.selectionForeground").equals(Ui.color(ColorRole.SELECTION_TEXT)),
                "Owned popup, tooltip and field-selection delegates use custom semantic roles.");
        SessionPane pane=w.active();
        check(pane.view().getBackground().equals(Ui.color(ColorRole.ACTIVE_TINT)),
                "A custom active-tint role actually paints the active terminal when tint is enabled.");
        w.highlightActive(false);
        check(pane.view().getBackground().equals(Ui.color(ColorRole.HOST_0)),
                "Disabling custom tint restores the exact chosen host background.");
        w.highlightActive(true);
        check(((JPanel)field(pane,"header")).getBackground().equals(Ui.color(ColorRole.HEADER))
                && ((JPanel)field(pane,"footer")).getBackground().equals(Ui.color(ColorRole.FOOTER)),
                "Pane header/footer use their own live roles.");
        Color[] host=HostPalette.colors("Amber");
        for (int n=0;n<16;n++) check((host[n].getRGB()&0xffffff)==draft.rgb(ColorRole.valueOf("HOST_"+n)),
                "Custom theme owns host attribute slot "+n+" regardless of built-in profile palette.");
        paint(designer.getRootPane(),"fish-050-theme-designer.png");
        designer.dismiss();
        EnumMap<ColorRole,Integer> partial=new EnumMap<>(draft.colors()); partial.remove(ColorRole.GRADIENT_4);
        boolean rejected=false; try { new ColorTheme("Partial",partial); } catch (IllegalArgumentException e) { rejected=true; }
        check(rejected,"Incomplete custom themes are rejected rather than filled with silent defaults.");
        rejected=false; try { ColorTheme.parse("#GG0011"); } catch (IllegalArgumentException e) { rejected=true; }
        check(rejected,"Exact color input rejects malformed hexadecimal values.");
    }
    private static void fontChecks(Workspace w,Path root) throws Exception {
        check(TerminalFonts.installed().size()>=2,"Installed fixed-width families are discovered, not merely advertised.");
        for (String family:TerminalFonts.installed()) check(TerminalFonts.fixed(new Font(family,Font.PLAIN,16)),"Discovered face is monospaced: "+family);
        check(!TerminalFonts.fixed(new Font("Segoe UI",Font.PLAIN,16)),"Proportional UI fonts are not offered as reliable terminal faces.");
        boolean rejected=false;
        try { TerminalFonts.resolve(new Appearance.FontChoice("Missing family","")); } catch (IOException e) { rejected=e.getMessage().contains("missing"); }
        check(rejected,"Missing installed fonts produce explicit diagnostics.");
        rejected=false;
        try { TerminalFonts.resolve(new Appearance.FontChoice("Automatic",root.resolve("absent.ttf").toString())); }
        catch (IOException e) { rejected=e.getMessage().contains("missing"); }
        check(rejected,"Missing custom files are reported, not silently treated as a successful font load.");
        Path system=Path.of(System.getenv("WINDIR"),"Fonts","consola.ttf");
        check(Files.isRegularFile(system),"Local Consolas source exists for a private custom-font fixture.");
        Path custom=root.resolve("fixture-font.ttf"); Files.copy(system,custom);
        Appearance.FontChoice choice=new Appearance.FontChoice("Automatic",custom.toAbsolutePath().toString());
        Font face=TerminalFonts.resolve(choice); TerminalFonts.validate(face,w.profileList());
        w.appearanceSettings(w.appearanceSettings().withFont(choice));
        check(w.terminalFont().getFamily().equals(face.getFamily()),"Local custom TTF is actually selected by the workspace.");
        TerminalView view=w.active().view(); paint(view,null);
        Object frame=field(view,"rendered"); Method runs=frame.getClass().getDeclaredMethod("runs"); runs.setAccessible(true);
        double width=(double)field(view,"baseWidth"); boolean fixed=true;
        for (Object run:(List<?>)runs.invoke(frame)) {
            Method glyph=run.getClass().getDeclaredMethod("glyphs"); glyph.setAccessible(true); GlyphVector vector=(GlyphVector)glyph.invoke(run);
            if (vector!=null) for (int i=0;i<=vector.getNumGlyphs();i++) fixed&=Math.abs(vector.getGlyphPosition(i).getX()-i*width)<.001;
        }
        check(fixed && width==Math.rint(width),"Custom-font glyphs retain exact integral physical-pixel cells, including code-page characters.");
    }
    private static void paneChecks(Workspace w,DemoHost host) throws Exception {
        SessionPane pane=w.active(); Appearance a=w.appearanceSettings();
        Appearance.PaneInfo hidden=new Appearance.PaneInfo(false,false,false,false,false,false,false,false,false,false);
        w.appearanceSettings(a.withLayout(false,hidden)); w.highlightActive(false);
        w.validate(); layout(w.getRootPane());
        check(!((JPanel)field(pane,"header")).isVisible() && !((JPanel)field(pane,"footer")).isVisible(),
                "Header and footer can be fully hidden.");
        JLayeredPane stage=(JLayeredPane)field(pane,"stage"); Rectangle before=stage.getBounds();
        JButton close=(JButton)field(pane,"close");
        check(close.isVisible() && close.getParent()==stage && close.isFocusable()
                        && close.getX()+close.getWidth()==stage.getWidth() && close.getY()==0,
                "Connected pane close is an upper-right keyboard-reachable overlay independent of hidden information.");
        check(pane.getInsets().equals(new Insets(0,0,0,0)) && before.equals(new Rectangle(0,0,pane.getWidth(),pane.getHeight())),
                "Outline off and hidden info consume exactly zero border/row spacing.");
        Session5250 core=(Session5250)field(pane,"core"); core.accept(inputScreen());
        check(!pane.type('X') && pane.recoverableInputError(),"Actual numeric-field rejection enters recoverable local inhibition.");
        JButton reset=(JButton)field(pane,"reset");
        check(reset.isVisible() && reset.getParent()==stage && reset.isFocusable() && stage.getBounds().equals(before),
                "Recoverable Reset is a keyboard-reachable overlay with no reflow or reserved band.");
        check(reset.getY()>stage.getHeight()/2,"Reset stays below the editing cursor when the cursor is in upper rows.");
        check(!reset.getBounds().intersects(close.getBounds()),"Independent Reset and close overlays never overlap.");
        int transactions=host.requestCount();
        paint(w.getRootPane(),"fish-050-hidden-reset.png");
        reset.doClick(0);
        check(!pane.screen().getOIA().isKeyBoardLocked() && !reset.isVisible() && host.requestCount()==transactions,
                "Reset recovers the local error and disappears without submitting a host transaction.");
        core.accept(DemoScreens.gds(3,new byte[]{4,0x11,0x20,0}));
        check(pane.screen().getOIA().isKeyBoardLocked() && !pane.recoverableInputError() && !reset.isVisible(),
                "Host-busy inhibition never presents a false recovery overlay.");
        core.accept(inputScreen());
        TerminalView view=pane.view(); paint(view,null);
        check(!view.visibleText().contains("PRIVATE"),"Custom appearance never exposes non-display host data.");
        w.putProfile(pane.profile().withDisplay(pane.profile().theme(),Profile.FLUX));
        set(view,"fluxPhase",0.0); BufferedImage first=paint(view,null);
        set(view,"fluxPhase",.3); BufferedImage next=paint(view,null);
        check(different(first,next),"Custom gradient roles feed genuinely animated Flux ink.");
        check(!view.visibleText().contains("PRIVATE"),"Custom Flux preserves hidden-field secrecy.");
        w.putProfile(pane.profile().withDisplay(pane.profile().theme(),"None"));
        w.appearanceSettings(w.appearanceSettings().withLayout(false,new Appearance.PaneInfo(true,true,false,false,false,false,false,false,false,false)));
        layout(w.getRootPane());
        check(!((JPanel)field(pane,"header")).isVisible() && !((JPanel)field(pane,"footer")).isVisible(),
                "Enabled rows with every content item disabled also consume no space.");
        check(close.isVisible() && close.getParent()==stage,"Deselecting every header information item cannot hide pane close.");
        check(!pane.screen().getOIA().isInsertMode(),"Optional insert fixture starts empty.");
        w.appearanceSettings(w.appearanceSettings().withLayout(false,new Appearance.PaneInfo(true,true,false,false,false,false,false,false,false,true)));
        layout(w.getRootPane());
        check(!((JPanel)field(pane,"header")).isVisible() && !((JPanel)field(pane,"footer")).isVisible(),
                "Enabled optional items with no actual text never leave empty information bands.");
        w.appearanceSettings(w.appearanceSettings().withLayout(false,Appearance.PaneInfo.defaults()));
        layout(w.getRootPane());
        check(((JPanel)field(pane,"header")).isVisible() && ((JPanel)field(pane,"footer")).isVisible()
                && pane.getInsets().top==0,"Info visibility and active outline remain independent.");
        check(close.isVisible() && close.getParent()==field(pane,"headerControls"),"Close returns to the real header without duplicate controls.");
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory(Path.of("build"),"customization-smoke-");
        List<Throwable> errors=new CopyOnWriteArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((thread,error) -> { errors.add(error); error.printStackTrace(); });
        Ui.install(); migration(root);
        CountDownLatch closed=new CountDownLatch(1);
        try (DemoHost host=new DemoHost()) {
            StateStore store=new StateStore(root.resolve("workspace"));
            Workspace w=edt(() -> {
                Workspace window=new Workspace(store,Main.demoState(host.port()),Path.of("scripts","credential-store.ps1"),false);
                window.onClosed(closed::countDown); window.setFocusableWindowState(false); window.addNotify(); window.validate(); window.connectAll();
                return window;
            });
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while (!edt(() -> w.sessionPanes().stream().allMatch(SessionPane::connected)) && System.nanoTime()<deadline) Thread.sleep(25);
                check(edt(() -> w.sessionPanes().stream().allMatch(SessionPane::connected)),"Real loopback sessions connect.");
                edt(() -> {
                    check(w.commands().stream().noneMatch(c -> c.title.equals("Search commands...")),"Commands window has no action or shortcut entry.");
                    check(!Profile.EFFECTS.contains("Neon"),"Neon is absent from valid effect choices.");
                    Object shortcuts=field(w,"shortcuts");
                    Method dispatch=shortcuts.getClass().getDeclaredMethod("dispatchKeyEvent",KeyEvent.class); dispatch.setAccessible(true);
                    check((boolean)dispatch.invoke(shortcuts,new KeyEvent(w.active().view(),KeyEvent.KEY_PRESSED,1,
                            InputEvent.CTRL_DOWN_MASK|InputEvent.ALT_DOWN_MASK,KeyEvent.VK_K,KeyEvent.CHAR_UNDEFINED)),
                            "Retired default palette shortcut is consumed as a no-op rather than reaching host input.");
                    themeChecks(w); fontChecks(w,root); paneChecks(w,host);
                    Appearance before=w.appearanceSettings();
                    for (AppTheme theme:List.of(AppTheme.Lagoon,AppTheme.Afterglow,AppTheme.Eye_Saver)) {
                        w.applyTheme(theme); layout(w);
                        JComponent chrome=(JComponent)field(w,"chrome");
                        check(chrome.getHeight()==31 && chrome.isVisible() && ((JPanel)field(w,"content")).getY()==31,
                                "Theme changes settle the real chrome/content allocation without overlap: "+theme);
                        paint(w.getRootPane(),"fish-050-"+theme.name()+".png");
                    }
                    w.appearanceSettings(before);
                    var remaining=w.sessionPanes().getFirst();
                    int transactions=host.requestCount();
                    for (SessionPane pane:List.copyOf(w.sessionPanes())) if (pane!=remaining)
                        ((JButton)field(pane,"close")).doClick(0);
                    check(w.sessionPanes().size()==1 && w.sessionPanes().getFirst()==remaining && remaining.connected(),
                            "Each close button removes only its own session, leaving the other socket alive.");
                    JButton close=(JButton)field(remaining,"close");
                    check(close.isVisible(),"Last connected pane retains its disconnect control.");
                    close.doClick(0);
                    check(w.sessionPanes().size()==1 && !remaining.busy() && !close.isVisible()
                                    && host.requestCount()==transactions,
                            "Last pane disconnects without host submission, remains offline, and hides its now-inapplicable close.");
                    check(!w.isVisible(),"All checks remain off the shared desktop.");
                    return null;
                });
            } finally { edt(() -> { w.close(false); return null; }); check(closed.await(5,TimeUnit.SECONDS),"Appearance state is flushed on close."); }
        }
        try (StateStore store=new StateStore(root.resolve("workspace"))) {
            StateStore.State state=store.load();
            check(!state.appearance().outline() && state.appearance().themes().size()==1
                    && state.appearance().custom().orElseThrow().name().equals("Fixture custom"),"Custom theme and outline survive restart.");
            check(state.appearance().font().file().endsWith("fixture-font.ttf"),"Local custom-font reference persists across restart.");
            check(!state.highlightActive() && state.appearance().info().equals(Appearance.PaneInfo.defaults()),
                    "Pane information and tint settings persist independently.");
        }
        check(errors.isEmpty(),"No asynchronous UI/network failures: "+errors);
        try (var paths=Files.walk(root)) { for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        System.out.println("PASS Customization: "+checks+" focused checks; no foreground input or user data.");
    }
}
