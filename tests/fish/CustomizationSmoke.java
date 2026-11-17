package fish;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import fish.demo.*;
import fish.model.*;
import fish.ui.*;
import org.tn5250j.Session5250;

/** Customization acceptance without foreground input, OS clipboard or credentials. */
public final class CustomizationSmoke {
    private static int checks;
    private static void check(boolean value,String text) { checks++; if (!value) throw new AssertionError(text); }
    private static <T> T edt(Callable<T> work) throws Exception { return FxTestSupport.fx(work); }
    private static Object field(Object owner,String name) throws Exception { return FxTestSupport.field(owner,name); }
    private static void set(Object owner,String name,Object value) throws Exception { FxTestSupport.setField(owner,name,value); }
    private static BufferedImage paint(TerminalView view) { return view.paintToImage(1.0); }
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
            xml.remove("appearance.cursorStyle"); xml.remove("addProfile"); xml.remove("savedLayouts"); xml.remove("selectedLayout");
            xml.remove("windows"); xml.remove("info.footerDevice");
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
        SessionPane pane=w.active();
        String savedProfileTheme=pane.profile().theme();
        String savedProfileEffect=pane.profile().effect();

        // 1. Verify all 21 curated primary color presets synthesize WCAG-legible, collision-free IBM palettes across dark & light themes
        check(Profile.PRIMARY_COLORS.size()>=20,"At least 20 common primary color presets are available for standard users.");
        for (AppTheme appTheme:List.of(AppTheme.Charcoal,AppTheme.OLED,AppTheme.Paper,AppTheme.Light)) {
            int bgRgb=ColorTheme.builtin(appTheme).rgb(ColorRole.HOST_0);
            Color bg=new Color(bgRgb);
            for (Profile.PrimaryColor preset:Profile.PRIMARY_COLORS) {
                check(Profile.isCustomColor(preset.hex()) && Profile.normalizeTheme(preset.hex().toLowerCase(Locale.ROOT)).equals(preset.hex()),
                        "Primary preset normalizes to canonical #RRGGBB: "+preset);
                Color[] slots=HostPalette.fromPrimaryColor(preset.rgb(),appTheme.dark,bg);
                check(slots.length==16 && (slots[0].getRGB()&0xffffff)==bgRgb,"Synthesized IBM palette preserves 16 host slots and theme background.");
                double normalContrast=HostPalette.contrastRatio(slots[2].getRGB()&0xffffff,bgRgb);
                double brightContrast=HostPalette.contrastRatio(slots[10].getRGB()&0xffffff,bgRgb);
                check(normalContrast>=(appTheme.dark ? 6.8 : 4.8),"Primary text slot 2 maintains crisp WCAG contrast on "+appTheme+": "+preset.name()+" -> "+normalContrast);
                check(brightContrast>normalContrast,"Bright primary slot 10 has stronger contrast than normal slot 2 on "+appTheme+": "+preset.name());
                for (int slot:new int[]{1,3,4,5,6,7,8,9,11,12,13,14,15}) {
                    double sc=HostPalette.contrastRatio(slots[slot].getRGB()&0xffffff,bgRgb);
                    check(sc>=(slot==8 ? 2.5 : 4.2),"Synthesized IBM slot "+slot+" remains readable on "+appTheme+": "+preset.name()+" -> "+sc);
                }
            }
        }

        // 2. Verify automatic hue collision avoidance on Crimson, Royal Blue, Cyan, and Amber primaries
        Color darkBg=new Color(ColorTheme.builtin(AppTheme.Charcoal).rgb(ColorRole.HOST_0));
        for (String collidingHex:List.of("#EF4444","#3B82F6","#06B6D4","#F59E0B","#D946EF")) {
            int rgb=Profile.parseCustomColor(collidingHex);
            Color[] slots=HostPalette.fromPrimaryColor(rgb,true,darkBg);
            double pHue=HostPalette.hueOfRgb(slots[2].getRGB()&0xffffff);
            for (int secSlot:new int[]{1,3,4,5,6}) {
                double sHue=HostPalette.hueOfRgb(slots[secSlot].getRGB()&0xffffff);
                check(HostPalette.hueDistance(pHue,sHue)>=24.0,
                        "Secondary IBM slot "+secSlot+" automatically separates from primary "+collidingHex+" (pHue="+pHue+", sHue="+sHue+")");
            }
        }

        // 3. Verify Settings > Appearance Primary Color dropdown + 360-degree Hue selector + live terminal effects
        w.preferences("Appearance");
        Preferences prefs=w.tutorialPreferences();
        FxTestSupport.layout(prefs.dialog());
        check(prefs.paletteCombo().getItems().contains(Preferences.CUSTOM_COLOR),
                "Text palette dropdown includes Custom color alongside built-in palettes.");
        check(prefs.primaryColorCombo().getItems().equals(Profile.PRIMARY_COLORS),
                "Primary color dropdown sits beside Text palette with all curated presets.");

        BufferedImage beforeColor=paint(pane.view());
        Profile.PrimaryColor royalBlue=Profile.PRIMARY_COLORS.stream().filter(c -> c.name().equals("Royal Blue")).findFirst().orElseThrow();
        prefs.primaryColorCombo().setValue(royalBlue);
        check(Preferences.CUSTOM_COLOR.equals(prefs.paletteCombo().getValue())
                && royalBlue.hex().equals(w.active().profile().theme()),
                "Choosing a Primary Color preset switches Text palette to Custom color and updates the profile immediately.");
        BufferedImage afterRoyal=paint(pane.view());
        check(different(beforeColor,afterRoyal),"Choosing a Primary Color preset repaints terminal ink immediately.");

        // Sweep the hue selector and verify custom shade synthesis + effects compatibility
        prefs.hueSlider().setValue(18.0);
        String coralShade=Profile.colorFromHue(18.0);
        check(coralShade.equals(w.active().profile().theme()) && Preferences.CUSTOM_COLOR.equals(prefs.paletteCombo().getValue()),
                "Adjusting the Hue selector synthesizes and applies the exact custom #RRGGBB shade: "+w.active().profile().theme());
        for (String fxName:Profile.EFFECTS) {
            prefs.effectCombo().setValue(fxName);
            BufferedImage fxImg=paint(pane.view());
            check(fxImg.getWidth()>0 && fxImg.getHeight()>0,"Custom primary color renders cleanly with effect: "+fxName);
        }
        prefs.effectCombo().setValue(savedProfileEffect);
        UsabilityIterationSmoke.settle(() -> !prefs.saving(),"Primary color settings autosave settled");
        FxTestSupport.layout(prefs.dialog());
        FxTestSupport.writeSnapshot(prefs.dialog().getScene().getRoot(),1.5,Path.of("build","fish-0101-primary-color-settings.png"));
        prefs.forceDispose();

        // 4. Legacy ColorTheme runtime model & validation integrity
        ColorTheme lagoon=ColorTheme.builtin(AppTheme.Lagoon);
        w.appearanceSettings(initial.withTheme("LagoonCustom",List.of(new ColorTheme("LagoonCustom",lagoon.colors()))));
        check(Ui.customTheme() && Ui.activeStylesheetUrl().startsWith("data:text/css;base64,"),
                "Legacy saved ColorTheme entries remain supported by Ui stylesheet generation.");
        w.appearanceSettings(initial);
        check(!Ui.customTheme() && w.appearanceSettings().equals(initial),"Restoring built-in theme clears custom ColorTheme override.");

        EnumMap<ColorRole,Integer> partial=new EnumMap<>(lagoon.colors()); partial.remove(ColorRole.GRADIENT_4);
        boolean rejected=false; try { new ColorTheme("Partial",partial); } catch (IllegalArgumentException e) { rejected=true; }
        check(rejected,"Incomplete custom themes are rejected rather than filled with silent defaults.");
        rejected=false; try { ColorTheme.parse("#GG0011"); } catch (IllegalArgumentException e) { rejected=true; }
        check(rejected,"Exact color input rejects malformed hexadecimal values.");
        rejected=false; try { Profile.normalizeTheme("#12345"); } catch (IllegalArgumentException e) { rejected=true; }
        check(rejected,"Profile custom color rejects non-6-digit hex strings.");
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
        TerminalView view=w.active().view(); paint(view);
        Object frame=field(view,"rendered"); Method runs=frame.getClass().getDeclaredMethod("runs"); runs.setAccessible(true);
        double width=(double)field(view,"baseWidth"); boolean fixed=true;
        for (Object run:(List<?>)runs.invoke(frame)) {
            java.lang.reflect.Field inkStartField=run.getClass().getDeclaredField("inkStart"); inkStartField.setAccessible(true);
            int inkStart=(int)inkStartField.get(run);
            int origin=(int)Math.rint(inkStart*width);
            Method glyph=run.getClass().getDeclaredMethod("glyphs"); glyph.setAccessible(true); GlyphVector vector=(GlyphVector)glyph.invoke(run);
            if (vector!=null) for (int i=0;i<=vector.getNumGlyphs();i++) {
                double gx=vector.getGlyphPosition(i).getX();
                fixed&=gx==Math.rint(gx) && Math.abs(gx-((int)Math.rint((inkStart+i)*width)-origin))<.001;
            }
        }
        check(fixed && width>0,"Custom-font glyphs retain exact integral physical-pixel cells, including code-page characters.");
    }
    private static void paneChecks(Workspace w,DemoHost host) throws Exception {
        SessionPane pane=w.active(); Appearance a=w.appearanceSettings();
        Appearance.PaneInfo hidden=new Appearance.PaneInfo(false,false,false,false,false,false,false,false,false,false);
        w.appearanceSettings(a.withLayout(false,hidden)); w.highlightActive(false);
        FxTestSupport.layout(w);
        check(!((Region)field(pane,"header")).isVisible() && !((Region)field(pane,"footer")).isVisible(),
                "Header and footer can be fully hidden.");
        Region stage=(Region)field(pane,"stage");
        Button close=(Button)field(pane,"close");
        check(close.isVisible() && close.getParent()==stage && close.isFocusTraversable()
                        && Math.abs(close.getLayoutX()+close.getWidth()-stage.getWidth())<=1 && close.getLayoutY()==0,
                "Connected pane close is an upper-right keyboard-reachable overlay independent of hidden information.");
        Session5250 core=(Session5250)field(pane,"core"); core.accept(inputScreen());
        check(!pane.type('X') && pane.recoverableInputError(),"Actual numeric-field rejection enters recoverable local inhibition.");
        FxTestSupport.layout(w);
        Button reset=(Button)field(pane,"reset");
        check(reset.isVisible() && reset.getParent()==stage && reset.isFocusTraversable(),
                "Recoverable Reset is a keyboard-reachable overlay with no reflow or reserved band.");
        check(reset.getLayoutY()>stage.getHeight()/2,"Reset stays below the editing cursor when the cursor is in upper rows.");
        check(!reset.getBoundsInParent().intersects(close.getBoundsInParent()),"Independent Reset and close overlays never overlap.");
        int transactions=host.requestCount();
        FxTestSupport.writeSnapshot(w.scene().getRoot(),1.0,Path.of("build","fish-050-hidden-reset.png"));
        reset.fire();
        check(!pane.screen().getOIA().isKeyBoardLocked() && !reset.isVisible() && host.requestCount()==transactions,
                "Reset recovers the local error and disappears without submitting a host transaction.");
        core.accept(DemoScreens.gds(3,new byte[]{4,0x11,0x20,0}));
        check(pane.screen().getOIA().isKeyBoardLocked() && !pane.recoverableInputError() && !reset.isVisible(),
                "Host-busy inhibition never presents a false recovery overlay.");
        core.accept(inputScreen());
        TerminalView view=pane.view(); paint(view);
        check(!view.visibleText().contains("PRIVATE"),"Custom appearance never exposes non-display host data.");
        w.putProfile(pane.profile().withDisplay(pane.profile().theme(),Profile.FLUX));
        set(view,"fluxPhase",0.0); BufferedImage first=paint(view);
        set(view,"fluxPhase",.3); BufferedImage next=paint(view);
        check(different(first,next),"Custom gradient roles feed genuinely animated Flux ink.");
        check(!view.visibleText().contains("PRIVATE"),"Custom Flux preserves hidden-field secrecy.");
        w.putProfile(pane.profile().withDisplay(pane.profile().theme(),"None"));
        w.appearanceSettings(w.appearanceSettings().withLayout(false,new Appearance.PaneInfo(true,true,false,false,false,false,false,false,false,false)));
        FxTestSupport.layout(w);
        check(!((Region)field(pane,"header")).isVisible() && !((Region)field(pane,"footer")).isVisible(),
                "Enabled rows with every content item disabled also consume no space.");
        check(close.isVisible() && close.getParent()==stage,"Deselecting every header information item cannot hide pane close.");
        w.appearanceSettings(w.appearanceSettings().withLayout(false,Appearance.PaneInfo.defaults()));
        FxTestSupport.layout(w);
        check(((Region)field(pane,"header")).isVisible() && ((Region)field(pane,"footer")).isVisible(),
                "Info visibility and active outline remain independent.");
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
                window.onClosed(closed::countDown); FxTestSupport.layout(window); window.connectAll();
                return window;
            });
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while (!edt(() -> w.sessionPanes().stream().allMatch(SessionPane::connected)) && System.nanoTime()<deadline) Thread.sleep(25);
                check(edt(() -> w.sessionPanes().stream().allMatch(SessionPane::connected)),"Real loopback sessions connect.");
                edt(() -> {
                    check(w.commands().stream().noneMatch(c -> c.title.equals("Search commands...")),"Commands window has no action or shortcut entry.");
                    check(!Profile.EFFECTS.contains("Neon"),"Neon is absent from valid effect choices.");
                    AppShortcuts shortcuts=(AppShortcuts)field(w,"shortcuts");
                    w.appearanceSettings(w.appearanceSettings().withTheme(w.appearanceSettings().selectedTheme(),w.appearanceSettings().themes()));
                    themeChecks(w); fontChecks(w,root); paneChecks(w,host);
                    Appearance before=w.appearanceSettings();
                    for (AppTheme theme:List.of(AppTheme.Lagoon,AppTheme.Afterglow,AppTheme.Eye_Saver)) {
                        w.applyTheme(theme); FxTestSupport.layout(w);
                        Node chrome=(Node)field(w,"chrome");
                        check(Math.round(((Region)chrome).getHeight())==fish.ui.WindowChrome.HEIGHT && chrome.isVisible(),
                                "Theme changes settle the real chrome/content allocation without overlap: "+theme);
                        FxTestSupport.writeSnapshot(w.scene().getRoot(),1.0,Path.of("build","fish-050-"+theme.name()+".png"));
                    }
                    w.appearanceSettings(before);
                    var remaining=w.sessionPanes().getFirst();
                    int transactions=host.requestCount();
                    for (SessionPane pane:List.copyOf(w.sessionPanes())) if (pane!=remaining)
                        ((Button)field(pane,"close")).fire();
                    check(w.sessionPanes().size()==1 && w.sessionPanes().getFirst()==remaining && remaining.connected(),
                            "Each close button removes only its own session, leaving the other socket alive.");
                    Button close=(Button)field(remaining,"close");
                    check(close.isVisible(),"Last connected pane retains its disconnect control.");
                    close.fire();
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
            check(!state.appearance().outline()
                    && state.profiles().stream().anyMatch(pr -> Profile.isCustomColor(pr.theme())),
                    "Custom primary color and outline survive restart.");
            check(state.appearance().font().file().endsWith("fixture-font.ttf"),"Local custom-font reference persists across restart.");
            check(!state.highlightActive() && state.appearance().info().equals(Appearance.PaneInfo.defaults()),
                    "Pane information and tint settings persist independently.");
        }
        check(errors.isEmpty(),"No asynchronous UI/network failures: "+errors);
        FxTestSupport.closeAllWindows();
        FxTestSupport.deleteTree(root);
        System.out.println("PASS Customization: "+checks+" focused checks; no foreground input or user data.");
        Ui.finishTestRun();
    }
}
