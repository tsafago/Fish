package fish.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import org.tn5250j.Session5250;
import org.tn5250j.keyboard.KeyMnemonic;
import fish.demo.DemoHost;
import fish.demo.DemoScreens;
import fish.model.*;

/** Pixel-exact dirty repaint and actual hidden JavaFX controls; synthetic loopback data only. */
public final class CursorRenderingSmoke {
    private static int checks;
    private static Path evidence;
    private static void check(boolean value,String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static Object field(Object owner,String name) throws Exception { return FxTestSupport.field(owner,name); }
    private static void set(Object owner,String name,Object value) throws Exception { FxTestSupport.setField(owner,name,value); }
    private static <T> T edt(Callable<T> action) throws Exception { return FxTestSupport.fx(action); }
    private static void layout(Workspace owner) { FxTestSupport.layout(owner); }

    private static final class View extends TerminalView {
        boolean focused=true;
        final List<Rectangle> dirty=new ArrayList<>();
        View(SessionPane pane) { super(pane); }
        @Override protected boolean hasTerminalFocus() { return focused; }
        @Override protected void recordDirtyRect(Rectangle rect) {
            if (dirty!=null) dirty.add(new Rectangle(rect));
        }
    }

    private static BufferedImage paint(View view,double scale,double translation,BufferedImage into,Rectangle clip) {
        BufferedImage image=into==null ? new BufferedImage((int)Math.ceil(view.getWidth()*scale)+4,
                (int)Math.ceil(view.getHeight()*scale)+4,BufferedImage.TYPE_INT_RGB) : into;
        Graphics2D g=image.createGraphics();
        try {
            if (into==null) { g.setColor(Color.MAGENTA); g.fillRect(0,0,image.getWidth(),image.getHeight()); }
            g.translate(translation,translation); g.scale(scale,scale);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            if (clip!=null) g.clip(clip);
            AffineTransform transform=g.getTransform(); Shape clipping=g.getClip();
            view.paintComponent(g);
            check(transform.equals(g.getTransform()) && Objects.equals(clipping==null ? null : clipping.getBounds(),
                    g.getClip()==null ? null : g.getClip().getBounds()),"Production paint preserves caller transform/clip");
        } finally { g.dispose(); }
        return image;
    }

    private static int differences(BufferedImage first,BufferedImage next) {
        int count=0;
        for (int y=0;y<first.getHeight();y++) for (int x=0;x<first.getWidth();x++)
            if (first.getRGB(x,y)!=next.getRGB(x,y)) count++;
        return count;
    }

    private static List<Object> geometry(View view) throws Exception {
        List<Object> result=new ArrayList<>();
        for (String name:List.of("cellWidth","cellHeight","originX","originY","fontPixels","baseWidth","baseHeight","ascent"))
            result.add(field(view,name));
        return result;
    }

    private static Rectangle cell(View view,double scale,double translation,int position) throws Exception {
        double bw=(double)field(view,"baseWidth"),bh=(double)field(view,"baseHeight");
        int columns=((SessionPane)field(view,"pane")).screen().getColumns();
        int col=position%columns,row=position/columns;
        int x0=(int)Math.rint(col*bw),x1=(int)Math.rint((col+1)*bw);
        int y0=(int)Math.rint(row*bh),y1=(int)Math.rint((row+1)*bh);
        return new Rectangle((int)Math.rint(translation+(double)field(view,"originX")*scale)+x0,
                (int)Math.rint(translation+(double)field(view,"originY")*scale)+y0,
                Math.max(1,x1-x0),Math.max(1,y1-y0));
    }

    private static int outside(BufferedImage first,BufferedImage next,Rectangle cell) {
        int count=0;
        for (int y=0;y<first.getHeight();y++) for (int x=0;x<first.getWidth();x++)
            if (!cell.contains(x,y) && first.getRGB(x,y)!=next.getRGB(x,y)) count++;
        return count;
    }

    private static double luminance(int rgb) {
        double result=0;
        for (int channel=0;channel<3;channel++) {
            double value=(rgb>>(16-8*channel)&255)/255.0;
            result+=new double[]{.2126,.7152,.0722}[channel]*(value<=.04045 ? value/12.92 : Math.pow((value+.055)/1.055,2.4));
        }
        return result;
    }

    private static void evidence(BufferedImage image,String name) throws Exception {
        if (evidence!=null) ImageIO.write(image,"png",evidence.resolve(name+".png").toFile());
    }

    private static void rendering(Workspace w,SessionPane pane,View view,boolean wide) throws Exception {
        w.appearanceSettings(w.appearanceSettings().withCursorStyle(Appearance.CursorStyle.IBM));
        w.putProfile(pane.profile().withDisplay(pane.profile().theme(),"None"));
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        if (wide) DemoScreens.command(out,4,0x20,0); else DemoScreens.command(out,4,0x40);
        DemoScreens.command(out,4,0x11,0,8);
        DemoScreens.at(out,1,1,0x32,"DB");
        int rows=wide ? 27 : 24,columns=wide ? 132 : 80;
        for (int row:new int[]{2,13,rows}) {
            DemoScreens.at(out,row,1,0x32,"Restart.. DB 0123456789 abcdefghijklmnopqrstuvwxyz");
            DemoScreens.at(out,row,columns-1,0x32,"M");
        }
        DemoScreens.command(out,4,0x42,0,0);
        ((Session5250)field(pane,"core")).accept(DemoScreens.gds(3,out.toByteArray()));
        var screen=pane.screen();
        if (screen.getOIA().isInsertMode()) pane.send(KeyMnemonic.INSERT);
        screen.setCursorActive(true);
        view.screenChanged();
        ButtonBase logo=(ButtonBase)field(field(w,"chrome"),"caption");
        for (boolean fit:new boolean[]{true,false}) for (double scale:new double[]{1,1.25,1.5,1.75,2})
            for (double translation:new double[]{0,.25,.75}) {
                view.setFit(fit); view.resize(1001,641); view.focused=false;
                BufferedImage before=paint(view,scale,translation,null,null);
                List<Object> metrics=geometry(view);
                view.dirty.clear();
                for (int requested:new int[]{0,1,columns,columns*12+40,columns*(rows-1),columns*rows-1}) {
                    screen.moveCursor(requested); int position=screen.getCurrentPos()-1;
                    view.dirty.clear(); view.restartCursor(); view.focused=true;
                    check(view.dirty.stream().allMatch(r -> r.width<100 && r.height<100),"Cursor paths remain local, not a full-screen workaround");
                    BufferedImage fresh=paint(view,scale,translation,null,null);
                    Rectangle rectangle=cell(view,scale,translation,position);
                    check(fresh.getRGB(rectangle.x,rectangle.y+rectangle.height-1)==Ui.color(ColorRole.CURSOR).getRGB(),
                            "Cursor origin shares the glyph device grid at "+rows+"x"+columns+"/"+scale+"/"+translation+"/"+position
                                    +"/"+screen.getCurrentPos()+"/"+screen.getOIA().isInsertMode()+"/"+field(view,"cursorPhase")
                                    +": "+Integer.toHexString(fresh.getRGB(rectangle.x,rectangle.y+rectangle.height-1)));
                    check(outside(before,fresh,rectangle)==0,"Cursor never shifts/changes a pixel outside its exact device cell");
                    for (Rectangle dirty:view.dirty) paint(view,scale,translation,before,dirty);
                    check(differences(before,fresh)==0,"Cursor-only dirty repaint equals full paint: "+fit+"/"+scale+"/"+translation+"/"+position+" ("+differences(before,fresh)+" pixels)");
                    view.dirty.clear(); view.focused=false; view.restartCursor();
                    for (Rectangle dirty:view.dirty) paint(view,scale,translation,before,dirty);
                    BufferedImage erased=paint(view,scale,translation,null,null);
                    check(differences(before,erased)==0,"Erased cursor restores exact underlying ink at fractional transforms");
                    view.dirty.clear(); before=erased;
                    check(metrics.equals(geometry(view)),"Cursor movement cannot change physical metrics or grid origin");
                }
                Object frame=field(view,"rendered");
                logo.fire();
                set(view,"fluxPhase",.25);
                BufferedImage flux=paint(view,scale,translation,null,null);
                check(metrics.equals(geometry(view)) && frame==field(view,"rendered"),"Actual Fish button None/Flux retains origins/metrics/glyph frame");
                Color[] palette=HostPalette.colors(pane.profile().theme());
                Paint[] paints=(Paint[])field(view,"fluxPaints");
                for (int n=0;n<paints.length;n++) paints[n]=palette[n];
                set(view,"sharedPalette",null);
                BufferedImage same=paint(view,scale,translation,null,null);
                check(differences(before,same)==0,"Identical None/Flux colors have exact same pixel coverage ("+differences(before,same)+")");
                check(differences(before,flux)>50,"Actual effect changes ink color, not a missing repaint");
                logo.fire();
                check(differences(before,paint(view,scale,translation,null,null))==0,"None returns exact original pixels");
                view.dirty.clear();
            }
        for (Appearance.CursorStyle style:Appearance.CursorStyle.values()) for (String effect:List.of("None",Profile.FLUX,"Soft glow","Prism"))
            for (double scale:new double[]{1,1.25,1.5,2}) for (boolean insert:new boolean[]{false,true}) {
                w.appearanceSettings(w.appearanceSettings().withCursorStyle(style));
                w.putProfile(pane.profile().withDisplay(pane.profile().theme(),effect));
                if (screen.getOIA().isInsertMode()!=insert) pane.send(KeyMnemonic.INSERT);
                check(screen.getOIA().isInsertMode()==insert,"Actual Insert host key selects rendering mode");
                view.setFit(false); view.resize(1001,641); set(view,"fluxPhase",.25);
                for (int requested:new int[]{1,81,80*12+70,80*24-1}) {
                    screen.moveCursor(requested); int position=screen.getCurrentPos()-1; view.focused=false;
                    BufferedImage back=paint(view,scale,.75,null,null);
                    view.dirty.clear(); view.restartCursor(); view.focused=true;
                    BufferedImage on=paint(view,scale,.75,null,null);
                    Rectangle rectangle=cell(view,scale,.75,position);
                    check(outside(back,on,rectangle)==0,"Style/effect/Insert modifies only its own cell");
                    for (Rectangle dirty:view.dirty) paint(view,scale,.75,back,dirty);
                    check(differences(back,on)==0,"Filled/outline/underline local repaint equals full paint under every effect");
                    if (insert || style==Appearance.CursorStyle.FILLED) {
                        int fill=Ui.color(ColorRole.CURSOR).getRGB(),filled=0;
                        boolean interior=true;
                        for (int y=rectangle.y;y<rectangle.y+rectangle.height;y++) for (int x=rectangle.x;x<rectangle.x+rectangle.width;x++)
                            if (on.getRGB(x,y)==fill) filled++;
                            else if (y<rectangle.y+rectangle.height-4) interior=false;
                        check(position==80*12+70 ? interior : filled>=rectangle.width*rectangle.height*.3,
                                "Insert fills blank cell interior while retaining host separators and contrasting occupied glyphs: "
                                +style+"/"+effect+"/"+scale+"/"+position+"/"+insert+" "+filled+"/"+rectangle.width*rectangle.height);
                    }
                    if (evidence!=null && scale==1.5 && position==81 && effect.equals("None"))
                        evidence(on.getSubimage(rectangle.x-rectangle.width,rectangle.y-rectangle.height,
                                rectangle.width*5,rectangle.height*3),"cursor-"+style.name()+"-"+insert);
                }
            }
        w.appearanceSettings(w.appearanceSettings().withCursorStyle(Appearance.CursorStyle.IBM));
        for (AppTheme theme:new AppTheme[]{AppTheme.Charcoal,AppTheme.Paper,AppTheme.High_Contrast,AppTheme.Light,AppTheme.Slate})
            for (String paletteName:List.of("Phosphor","Azure vivid","Crimson vivid"))
            for (String effect:List.of("None",Profile.FLUX)) for (double scale:new double[]{1,1.5}) {
            w.applyTheme(theme);
            w.putProfile(pane.profile().withDisplay(paletteName,effect));
            if (!screen.getOIA().isInsertMode()) pane.send(KeyMnemonic.INSERT);
            for (int attribute:new int[]{0x20,0x21,0x27,0x2a,0x22}) {
                byte[] first=attributes(attribute,"D");
                ((Session5250)field(pane,"core")).accept(first); view.screenChanged(); screen.moveCursor(81);
                set(view,"anchor",81); set(view,"end",81); set(view,"phase",false); set(view,"fluxPhase",.25);
                if (!screen.getOIA().isInsertMode()) pane.send(KeyMnemonic.INSERT);
                view.focused=true;
                BufferedImage ink=paint(view,scale,.25,null,null);
                Rectangle rectangle=cell(view,scale,.25,81);
                ((Session5250)field(pane,"core")).accept(attributes(attribute,"W")); view.screenChanged(); screen.moveCursor(81);
                if (!screen.getOIA().isInsertMode()) pane.send(KeyMnemonic.INSERT);
                BufferedImage other=paint(view,scale,.25,null,null);
                int difference=differences(ink.getSubimage(rectangle.x,rectangle.y,rectangle.width,rectangle.height),
                        other.getSubimage(rectangle.x,rectangle.y,rectangle.width,rectangle.height));
                check((attribute==0x27 || attribute==0x2a) ? difference==0 : difference>0,
                        "Filled cell preserves non-display/blink secrecy or contrasting visible glyph under reverse/selection/effect: "
                                +Integer.toHexString(attribute)+"/"+effect+"/"+scale+"/"+difference);
                if (attribute!=0x27 && attribute!=0x2a) {
                    double fill=luminance(Ui.color(ColorRole.CURSOR).getRGB()),contrast=0;
                    for (int y=rectangle.y;y<rectangle.y+rectangle.height;y++) for (int x=rectangle.x;x<rectangle.x+rectangle.width;x++) {
                        double glyph=luminance(ink.getRGB(x,y));
                        contrast=Math.max(contrast,(Math.max(fill,glyph)+.05)/(Math.min(fill,glyph)+.05));
                    }
                    check(contrast>=4.5,"Filled cursor contains a readable glyph at >=4.5:1 in "+theme+"/"+effect+"/"+scale);
                    if (theme==AppTheme.Paper && scale==1.5 && attribute==0x20 && effect.equals("None"))
                        evidence(ink.getSubimage(rectangle.x-rectangle.width,rectangle.y-rectangle.height,
                                rectangle.width*5,rectangle.height*3),"cursor-paper-filled");
                }
            }
            set(view,"anchor",-1); set(view,"end",-1); set(view,"phase",true);
        }
        pane.send(KeyMnemonic.RESET);
        w.applyTheme(AppTheme.Charcoal);
        if (screen.getOIA().isInsertMode()) pane.send(KeyMnemonic.INSERT);
        view.focused=false;
    }

    private static byte[] attributes(int attribute,String glyph) {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        DemoScreens.command(out,4,0x40,4,0x11,0,8);
        DemoScreens.at(out,2,1,attribute,glyph);
        DemoScreens.command(out,4,0x42,0,0);
        return DemoScreens.gds(3,out.toByteArray());
    }

    private static void controls(UsabilityIterationSmoke.Fixture fixture,Workspace w,SessionPane pane,View view) throws Exception {
        edt(() -> {
            w.putProfile(pane.profile().withDisplay(pane.profile().theme(),"None"));
            w.setSize(1121,761); layout(w);
            ButtonBase logo=(ButtonBase)field(field(w,"chrome"),"caption");
            for (boolean fit:new boolean[]{true,false}) for (double scale:new double[]{1,1.25,1.5,1.75,2}) {
                view.setFit(fit); layout(w); paint(view,scale,.75,null,null);
                List<Object> before=geometry(view);
                List<javafx.geometry.Bounds> bounds=new ArrayList<>();
                for (Node c:new Node[]{pane,view,(Node)field(pane,"stage"),(Node)field(pane,"footer")})
                    bounds.add(c.getBoundsInParent());
                logo.fire(); layout(w); paint(view,scale,.75,null,null);
                List<javafx.geometry.Bounds> after=new ArrayList<>();
                for (Node c:new Node[]{pane,view,(Node)field(pane,"stage"),(Node)field(pane,"footer")})
                    after.add(c.getBoundsInParent());
                check(bounds.equals(after) && before.equals(geometry(view)),"Actual icon action + revalidation leaves entire pane interior/metrics unchanged");
                logo.fire(); layout(w); paint(view,scale,.75,null,null);
                check(before.equals(geometry(view)),"Return to None has no cell-height/origin drift");
            }
            return null;
        });
        Preferences preferences=edt(() -> { w.preferences("Appearance"); return w.tutorialPreferences(); });
        UsabilityIterationSmoke.settle(() -> !fixture.app.saving(),"Profile effects did not settle");
        Workspace peer=edt(() -> { check(fixture.app.newWindow(w),"Independent peer is admitted after pending saves"); return fixture.app.windows().getLast(); });
        UsabilityIterationSmoke.settle(() -> peer.active().connected(),"Peer loopback connection did not settle");
        for (Appearance.CursorStyle style:Appearance.CursorStyle.values()) {
            edt(() -> {
                @SuppressWarnings("unchecked") ComboBox<Appearance.CursorStyle> cs=(ComboBox<Appearance.CursorStyle>)field(preferences,"cursorStyle");
                cs.setValue(style);
                return null;
            });
            UsabilityIterationSmoke.settle(() -> !preferences.saving() && w.appearanceSettings().cursorStyle()==style,"Cursor preference did not autosave");
            check(fixture.store.load().appearance().cursorStyle()==style && edt(() -> peer.appearanceSettings().cursorStyle()==style),
                    "Saved style acknowledges disk write and propagates across windows without reconnecting");
        }
        edt(() -> {
            @SuppressWarnings("unchecked") ComboBox<Appearance.CursorStyle> cs=(ComboBox<Appearance.CursorStyle>)field(preferences,"cursorStyle");
            cs.setValue(Appearance.CursorStyle.IBM);
            return null;
        });
        UsabilityIterationSmoke.settle(() -> !preferences.saving(),"Default style did not persist");
        edt(() -> {
            SavedLayoutsDialog dialog=new SavedLayoutsDialog(w);
            TextField name=(TextField)field(dialog,"name");
            for (String text:List.of("Office layout","Wide W / thin iii","Layout \u5317 \u0394","Office layout")) {
                name.setText(text);
                FxTestSupport.layout(dialog.stage());
                check(name.getText().equals(text),"Actual name control supports Unicode");
            }
            for (AppTheme theme:new AppTheme[]{AppTheme.Charcoal,AppTheme.Paper}) {
                w.applyTheme(theme); name.setText("Work layout \u5317"); FxTestSupport.layout(dialog.stage());
                for (double scale:new double[]{1,1.5}) {
                    evidence(FxTestSupport.snapshot(dialog.stage(),scale),"shortcut-name-"+theme+"-"+scale);
                }
            }
            w.applyTheme(AppTheme.Charcoal); dialog.dispose();
            Preferences current=w.tutorialPreferences();
            if (evidence!=null) {
                evidence(FxTestSupport.snapshot(current.dialog(),1.0),"cursor-preference");
            }
            current.forceDispose();
            return null;
        });
    }

    private static void persistence(Path root) throws Exception {
        try (StateStore store=new StateStore(root.resolve("model"))) {
            StateStore.State state=StateStore.freshState();
            Properties values=StateStore.properties(state);
            Properties old=new Properties(); old.putAll(values); old.setProperty("version","17"); old.remove("appearance.cursorStyle");
            check(store.decode(old).appearance().cursorStyle()==Appearance.CursorStyle.IBM,"Actual format17 upgrades to documented mode-aware cursor default");
            for (Appearance.CursorStyle style:Appearance.CursorStyle.values()) {
                values.setProperty("appearance.cursorStyle",style.name());
                StateStore.State decoded=store.decode(values); store.save(decoded);
                check(store.load().equals(decoded),"Typed style import and persistence roundtrip");
            }
            byte[] original=Files.readAllBytes(store.file());
            values.setProperty("appearance.cursorStyle","BLOCK_INSERT_UNDERSCORE_REPLACE");
            try { store.decode(values); throw new AssertionError("Foreign cursor option accepted"); } catch (java.io.IOException expected) { checks++; }
            values.remove("appearance.cursorStyle");
            try { store.decode(values); throw new AssertionError("Incomplete new format accepted"); } catch (java.io.IOException expected) { checks++; }
            check(Arrays.equals(original,Files.readAllBytes(store.file())),"Invalid/missing style cannot overwrite saved state");
        }
    }

    public static void main(String[] args) throws Exception {
        Ui.install();
        if (args.length>0) { evidence=Path.of(args[0]); Files.createDirectories(evidence); }
        Path root=Files.createTempDirectory(Path.of("build"),"cursor-render-");
        persistence(root);
        try (DemoHost host=new DemoHost(); UsabilityIterationSmoke.Fixture fixture=new UsabilityIterationSmoke.Fixture(root)) {
            Workspace w=fixture.first();
            SessionPane pane=edt(() -> {
                SessionPane p=w.active();
                Profile old=p.profile();
                w.putProfile(new Profile(old.id(),old.name(),"127.0.0.1",host.port(),"","",false,false,"37","Phosphor","None",.8,Map.of(),""));
                p.connectOnStartup(); return p;
            });
            UsabilityIterationSmoke.settle(() -> pane.connected() && pane.screen().getScreenFields().getSize()>0,"Synthetic terminal did not connect");
            View view=edt(() -> {
                TerminalView old=pane.view();
                ScrollPane scroll=(ScrollPane)field(pane,"scroll");
                old.dispose(); View result=new View(pane); set(pane,"view",result); scroll.setContent(result); pane.updateHeader(); layout(w); return result;
            });
            edt(() -> { rendering(w,pane,view,false); return null; });
            edt(() -> { rendering(w,pane,view,true); return null; });
            controls(fixture,w,pane,view);
            check(host.failures().isEmpty(),"No synthetic host/session failures");
            check(Window.getWindows().stream().noneMatch(Window::isShowing),"No shared desktop exposure");
        } finally {
            FxTestSupport.closeAllWindows();
            FxTestSupport.deleteTree(root);
        }
        System.out.println("PASS cursor/Flux rendering: "+checks);
        Ui.finishTestRun();
    }
}
