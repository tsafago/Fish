package pane5250.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import pane5250.demo.DemoHost;
import pane5250.model.*;

/** Canonical shapes and actual never-shown Swing geometry, using isolated loopback sessions only. */
public final class SimplePaneSmoke {
    private static int checks;
    private static Path evidence;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> result=new FutureTask<>(action); SwingUtilities.invokeAndWait(result); return result.get();
    }
    private static void await(Callable<Boolean> action,String message) throws Exception { UsabilityIterationSmoke.settle(action,message); }
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void layout(Container owner) {
        owner.doLayout(); for (Component c:owner.getComponents()) if (c instanceof Container child) layout(child);
    }
    private static Layout pair(boolean horizontal,Layout a,Layout b) { return new Layout.Split(horizontal,.5,a,b); }
    private static Layout legacyRow(List<Layout.Leaf> panes) {
        return panes.size()==1 ? panes.getFirst()
                : new Layout.Split(true,1.0/panes.size(),panes.getFirst(),legacyRow(panes.subList(1,panes.size())));
    }
    private static Layout half(List<Layout.Leaf> panes,boolean horizontal,boolean first) {
        Layout.Leaf a=panes.get(0),b=panes.get(1),c=panes.get(2);
        Layout rest=pair(!horizontal,b,c);
        return pair(horizontal,first ? a : rest,first ? rest : a);
    }
    private static void samePanes(Layout result,Layout before,String text) {
        check(Layout.constrained(result) && new HashSet<>(result.leaves()).equals(new HashSet<>(before.leaves())),text);
        check(Layout.decode(result.encode()).equals(result),"Exact topology/ratios/IDs roundtrip");
    }
    private static void equalStrips(Layout result,boolean horizontal,UUID last) {
        check(result.leaves().getLast().pane().equals(last),"Whole-edge placement keeps source at the requested outer end");
        for (int length:new int[]{329,330,331,1000,1121,1300}) {
            Map<UUID,Rectangle> bounds=Workspace.project(result,new Rectangle(0,0,horizontal ? length : 900,horizontal ? 700 : length));
            int min=Integer.MAX_VALUE,max=0;
            for (Rectangle r:bounds.values()) {
                int size=horizontal ? r.width : r.height; min=Math.min(min,size); max=Math.max(max,size);
                check(horizontal ? r.y==0 && r.height==700 : r.x==0 && r.width==900,"Thirds span the whole opposite axis");
            }
            check(max-min<=1,"All three strips differ by at most one pixel, including both dividers");
        }
    }
    private static void model(Path root) throws Exception {
        UUID profile=UUID.randomUUID();
        List<Layout.Leaf> panes=new ArrayList<>();
        for (int n=0;n<8;n++) panes.add(new Layout.Leaf(UUID.randomUUID(),profile));
        for (boolean horizontal:List.of(true,false)) for (boolean first:List.of(true,false)) {
            Layout start=half(panes,horizontal,first);
            Layout.Dock opposite=horizontal ? (first ? Layout.Dock.RIGHT : Layout.Dock.LEFT) : (first ? Layout.Dock.BOTTOM : Layout.Dock.TOP);
            Layout mirrored=start.move(panes.get(0).pane(),panes.get(1).pane(),opposite,Layout.Scope.WINDOW);
            check(mirrored.equals(half(panes,horizontal,!first)),"Large half crosses intact; opposite pair moves as a unit, including rotations");
            Layout.Dock stripEnd=horizontal ? Layout.Dock.BOTTOM : Layout.Dock.RIGHT;
            Layout strips=start.move(panes.get(0).pane(),panes.get(2).pane(),stripEnd,Layout.Scope.WINDOW);
            equalStrips(strips,!horizontal,panes.get(0).pane());
        }
        for (int n=1;n<=8;n++) {
            Layout start=Layout.bounded(panes.subList(0,n));
            check(Layout.constrained(start) && Layout.normalize(start).equals(start),"Default "+n+" is canonical");
            for (Layout.Leaf source:start.leaves()) {
                if (n>1) samePanes(Objects.requireNonNull(start.remove(source.pane())),
                        Layout.bounded(start.leaves().stream().filter(p -> !p.pane().equals(source.pane())).toList()),"Every removal remains simple and loses only the removed pane");
                if (n<8) {
                    Layout added=start.add(panes.get(n),source.pane());
                    check(Layout.constrained(added) && added.leaves().size()==n+1 && added.leaves().containsAll(start.leaves()),"Add from every active quadrant reaches "+(n+1));
                }
                for (Layout.Leaf target:start.leaves()) for (Layout.Dock edge:Layout.Dock.values())
                    samePanes(start.move(source.pane(),target.pane(),edge),start,"Local moves use slots, never recursive slivers");
            }
        }
        Layout full=Layout.bounded(panes);
        for (int i=0;i<600;i++) {
            Layout.Leaf source=full.leaves().get((i*3)%8),target=full.leaves().get((i*5+1)%8);
            full=full.move(source.pane(),target.pane(),Layout.Dock.values()[i%5]);
            samePanes(full,Layout.bounded(panes),"Repeated eight-pane placements preserve canonical slots and every ID");
        }
        try { full.add(new Layout.Leaf(UUID.randomUUID(),profile),panes.getFirst().pane()); throw new AssertionError("Ninth pane accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { full.move(panes.get(0).pane(),panes.get(1).pane(),Layout.Dock.BOTTOM,Layout.Scope.WINDOW); throw new AssertionError("Eight-pane whole-edge nesting accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        Layout manual=new Layout.Split(true,.62,panes.get(0),panes.get(1));
        check(Layout.normalize(manual).equals(manual),"Reasonable intentional divider resizing survives persistence");
        check(!Layout.constrained(new Layout.Split(true,.05,panes.get(0),panes.get(1))),"Tiny manual bands are not valid current state");
        Layout bizarre=panes.get(7);
        for (int n=6;n>=0;n--) bizarre=new Layout.Split(n%2==0,.12,panes.get(n),bizarre);
        Layout normalized=Layout.normalize(bizarre);
        samePanes(normalized,Layout.bounded(panes),"Experimental nested geometry normalizes without losing identities");
        try (StateStore store=new StateStore(root.resolve("migration"))) {
            Profile p=new Profile(profile,"Synthetic","",23,"","",false,false,"37","Phosphor","None",1,Map.of(),"");
            Map<UUID,SavedLayout.Display> displays=new HashMap<>();
            panes.forEach(leaf -> displays.put(leaf.pane(),new SavedLayout.Display(false,1.25)));
            SavedLayout recipe=new SavedLayout(UUID.randomUUID(),"Synthetic recipe",new StateStore.WindowState(normalized,1100,760,profile),displays);
            StateStore.State state=new StateStore.State(List.of(p),normalized,1100,760).withSavedLayouts(List.of(recipe),recipe.id())
                    .withWindows(List.of(new StateStore.WindowState(normalized,1100,760,profile)));
            Properties legacy=StateStore.properties(state); legacy.remove("appearance.cursorStyle"); legacy.setProperty("version","16");
            legacy.setProperty("layout",bizarre.encode()); legacy.setProperty("window.0.layout",bizarre.encode());
            legacy.setProperty("profile."+profile+".layout",bizarre.encode());
            legacy.setProperty("savedLayout."+recipe.id()+".layout",bizarre.encode()); legacy.setProperty("savedLayout."+recipe.id()+".version","2");
            StateStore.State migrated=store.decode(legacy);
            check(migrated.layout().equals(normalized) && migrated.windows().getFirst().layout().equals(normalized)
                    && migrated.savedLayouts().getFirst().window().layout().equals(normalized)
                    && Layout.decode(migrated.profiles().getFirst().savedLayout()).equals(normalized),"Format16 migrates primary, additional, profile and named layouts coherently");
            check(migrated.savedLayouts().getFirst().id().equals(recipe.id()) && migrated.savedLayouts().getFirst().displays().equals(displays)
                    && store.loadNotice().contains("simplified"),"Migration reports changed geometry and retains stable recipe/display references");
            store.save(migrated); check(store.load().equals(migrated),"Format17 / recipe3 persist exact normalized state");
            Layout oldEight=pair(false,legacyRow(panes.subList(0,4)),legacyRow(panes.subList(4,8)));
            Properties version15=new Properties(); version15.putAll(legacy); version15.setProperty("version","15");
            for (String key:List.of("layout","window.0.layout","profile."+profile+".layout","savedLayout."+recipe.id()+".layout")) version15.setProperty(key,oldEight.encode());
            version15.setProperty("savedLayout."+recipe.id()+".version","1");
            StateStore.State from15=store.decode(version15);
            check(Layout.constrained(from15.layout()) && from15.savedLayouts().getFirst().displays().equals(displays)
                    && new HashSet<>(from15.layout().leaves()).equals(new HashSet<>(panes)),"Actual legacy format15 / recipe1 eight-pane rows migrate without losing IDs or display settings");
            Properties supported=StateStore.properties(new StateStore.State(List.of(p),manual,1100,760)); supported.remove("appearance.cursorStyle"); supported.setProperty("version","16");
            check(store.decode(supported).layout().equals(manual),"Supported older shapes and deliberate reasonable divider sizes remain exact");
            Properties relabeled=new Properties(); relabeled.putAll(legacy); relabeled.setProperty("version","17");
            relabeled.setProperty("savedLayout."+recipe.id()+".version","3");
            byte[] before=Files.readAllBytes(store.file());
            try { store.decode(relabeled); throw new AssertionError("Invalid current topology accepted"); }
            catch (java.io.IOException expected) { check(Arrays.equals(before,Files.readAllBytes(store.file())),"Invalid current imports leave exact bytes unchanged"); }
        }
    }
    private static void install(Workspace w,Layout shape) throws Exception {
        Field f=Workspace.class.getDeclaredField("layout"); f.setAccessible(true); f.set(w,shape);
        Method build=Workspace.class.getDeclaredMethod("buildTree"); build.setAccessible(true); build.invoke(w); layout(w);
    }
    private static void actual(Workspace w,Map<UUID,Rectangle> expected) throws Exception {
        Container content=(Container)field(w,"content");
        for (SessionPane pane:w.sessionPanes())
            check(SwingUtilities.convertRectangle(pane.getParent(),pane.getBounds(),content).equals(expected.get(pane.id())),
                    "Preview matches actual Swing pane bounds including all divider rounding: "+expected);
    }
    private static void snapshot(Workspace w,String name) throws Exception {
        if (evidence==null) return;
        for (double scale:new double[]{1,1.5}) {
            Component owner=w.getRootPane();
            BufferedImage image=new BufferedImage((int)(owner.getWidth()*scale),(int)(owner.getHeight()*scale),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics(); g.scale(scale,scale); owner.printAll(g); g.dispose();
            ImageIO.write(image,"png",evidence.resolve(name+"-"+scale+".png").toFile());
        }
    }
    private static void production(UsabilityIterationSmoke.Fixture f,DemoHost host) throws Exception {
        Workspace w=f.first();
        edt(() -> {
            Profile old=w.profileList().getFirst();
            Profile local=new Profile(old.id(),old.name(),"127.0.0.1",host.port(),"","",false,false,"37","Phosphor","None",1,Map.of(),"");
            w.putProfile(local); w.selectProfile(w.active(),local); w.connectAll(); w.split(true); w.split(true); layout(w); return null;
        });
        await(() -> w.sessionPanes().size()==3 && w.sessionPanes().stream().allMatch(p -> p.connected() && p.screen().getScreenFields().getSize()>0),"Three independent loopback sessions did not connect");
        edt(() -> {
            List<Layout.Leaf> panes=w.currentLayout().leaves(); Layout initial=half(panes,true,true); install(w,initial);
            for (boolean horizontal:List.of(true,false)) for (boolean first:List.of(true,false)) {
                install(w,half(panes,horizontal,first));
                Layout.Dock edge=horizontal ? (first ? Layout.Dock.RIGHT : Layout.Dock.LEFT) : (first ? Layout.Dock.BOTTOM : Layout.Dock.TOP);
                Workspace.DropPlan plan=w.planDock(panes.get(0).pane(),panes.get(1).pane(),edge,Layout.Scope.WINDOW);
                check(plan!=null && plan.layout().equals(half(panes,horizontal,!first)),"Production target handles every large-half mirror/rotation");
                w.dock(panes.get(0).pane(),panes.get(1).pane(),edge,Layout.Scope.WINDOW); layout(w); actual(w,plan.bounds());
            }
            install(w,half(panes,false,true));
            Workspace.DropPlan columns=w.planDock(panes.get(0).pane(),panes.get(1).pane(),Layout.Dock.RIGHT,Layout.Scope.WINDOW);
            w.dock(panes.get(0).pane(),panes.get(1).pane(),Layout.Dock.RIGHT,Layout.Scope.WINDOW); layout(w); actual(w,columns.bounds());
            check(columns.bounds().values().stream().mapToInt(r -> r.width).max().orElseThrow()-columns.bounds().values().stream().mapToInt(r -> r.width).min().orElseThrow()<=1,
                    "Actual Swing three-column widths are equal within one pixel");
            install(w,initial);
            snapshot(w,"half-and-pair");
            UUID a=panes.get(0).pane(),b=panes.get(1).pane();
            Workspace.DropPlan mirror=w.planDock(a,b,Layout.Dock.RIGHT,Layout.Scope.WINDOW);
            check(mirror!=null && mirror.layout().equals(half(panes,true,false)),"Production window-right target moves the large half across intact");
            check(w.previewDock(a,b,Layout.Dock.RIGHT,Layout.Scope.WINDOW),"Whole-side preview is offered");
            snapshot(w,"whole-side-preview");
            w.clearPreviews(); check(Layout.normalize(w.currentLayout()).leaves().equals(initial.leaves()),"Cancel leaves identities/order intact");
            w.dock(a,b,Layout.Dock.RIGHT,Layout.Scope.WINDOW); layout(w); actual(w,mirror.bounds());
            w.undoArrangement(); layout(w); check(w.currentLayout().leaves().equals(initial.leaves()),"Undo restores half and pair");
            Container content=(Container)field(w,"content");
            Point bottom=new Point(content.getWidth()/2,content.getHeight()-2);
            Workspace.DockTarget edge=w.dropTarget(b,bottom);
            check(edge.scope()==Layout.Scope.WINDOW && edge.dock()==Layout.Dock.BOTTOM,"Outer band explicitly identifies whole-window bottom");
            Workspace.DropPlan thirds=w.planDock(a,b,edge.dock(),edge.scope());
            check(thirds!=null && thirds.layout().arrangement().equals("Three equal rows"),"Window bottom produces thirds, not a local nested split");
            w.previewDock(a,b,edge.dock(),edge.scope()); snapshot(w,"whole-bottom-thirds-preview");
            w.dock(a,b,edge.dock(),edge.scope()); layout(w); actual(w,thirds.bounds()); snapshot(w,"three-even-rows");
            BufferedImage paint=new BufferedImage(w.getWidth(),w.getHeight(),BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics=paint.createGraphics(); w.getRootPane().printAll(graphics); graphics.dispose();
            List<Rectangle> rectangles=new ArrayList<>(thirds.bounds().values());
            check(rectangles.stream().mapToInt(r -> r.height).max().orElseThrow()-rectangles.stream().mapToInt(r -> r.height).min().orElseThrow()<=1,"Committed thirds have a one-pixel maximum size difference");
            Dimension original=w.getSize();
            for (int n=0;n<40;n++) {
                w.setSize(original.width+n,original.height+n); layout(w);
                Map<UUID,Rectangle> resized=Workspace.project(w.currentLayout(),new Rectangle(0,0,content.getWidth(),content.getHeight()));
                check(resized.values().stream().mapToInt(r -> r.height).max().orElseThrow()-resized.values().stream().mapToInt(r -> r.height).min().orElseThrow()<=1,
                        "Incremental one-pixel window resizing does not accumulate uneven strip sizes");
                actual(w,resized);
            }
            w.setSize(original); layout(w);
            Rectangle row=rectangles.get(1);
            Workspace.DockTarget center=w.dropTarget(w.sessionPanes().get(1).id(),new Point(row.x+row.width/2,row.y+row.height/2));
            check(center.scope()==Layout.Scope.PANE && center.dock()==Layout.Dock.CENTER,"Center remains distinct from window targets");
            check(w.dropTarget(b,new Point(-1,20))==null && !w.previewDock(a,a,Layout.Dock.TOP),"Outside/self drops have no accepted preview");
            JSplitPane divider=(JSplitPane)field(w,"tree"); int saved=divider.getDividerLocation();
            w.resizeDivider(divider,1); layout(w);
            check(divider.getDividerLocation()==saved && Layout.constrained(w.currentLayout()),"Extreme divider drag cannot create a sliver: before="+saved+", after="+divider.getDividerLocation()+", layout="+w.currentLayout().encode());
            w.resizeDivider(divider,(int)Math.round((divider.getHeight()-4)*.4)); layout(w);
            Layout resized=w.currentLayout();
            check(Layout.normalize(resized).equals(resized) && ((Layout.Split)resized).ratio()>.39,"Reasonable manual divider sizes persist outside structural changes");
            Workspace.DropPlan balanced=w.planDock(a,b,Layout.Dock.TOP,Layout.Scope.WINDOW);
            w.dock(a,b,Layout.Dock.TOP,Layout.Scope.WINDOW); layout(w); actual(w,balanced.bounds());
            int min=balanced.bounds().values().stream().mapToInt(r -> r.height).min().orElseThrow(),max=balanced.bounds().values().stream().mapToInt(r -> r.height).max().orElseThrow();
            check(max-min<=1,"A later structural move resets intentionally unequal thirds to equal default heights");
            for (int n=3;n<8;n++) { w.split(true); layout(w); check(w.sessionPanes().size()==n+1,"Production add reaches "+(n+1)); }
            return null;
        });
        await(() -> w.sessionPanes().size()==8 && w.sessionPanes().stream().allMatch(p -> p.connected() && p.screen().getScreenFields().getSize()>0),"Eight independent loopback sessions did not connect");
        edt(() -> {
            List<SessionPane> live=w.sessionPanes();
            check(live.getFirst().type('2'),"Create an unsent local field edit before rearranging");
            Map<UUID,char[]> characters=new HashMap<>(); Map<UUID,Object> screens=new HashMap<>();
            for (SessionPane pane:live) { screens.put(pane.id(),pane.screen()); characters.put(pane.id(),pane.screen().getCharacters().clone()); }
            for (int n=0;n<80;n++) {
                UUID source=live.get(n%8).id(),target=live.get((n+3)%8).id();
                Layout.Dock edge=Layout.Dock.values()[n%5];
                Workspace.DropPlan plan=w.planDock(source,target,edge);
                if (plan!=null) { w.dock(source,target,edge); layout(w); actual(w,plan.bounds()); }
                check(Layout.constrained(w.currentLayout()) && w.sessionPanes().size()==8,"Repeated production moves cannot nest or grow beyond eight");
            }
            check(live.stream().allMatch(p -> w.sessionPanes().contains(p) && p.screen()==screens.get(p.id())
                    && Arrays.equals(p.screen().getCharacters(),characters.get(p.id()))) && host.activeConnections()==8,"Controllers, unsent field characters, screens and sockets survive every move without reconnecting");
            w.split(true); check(w.sessionPanes().size()==8 && host.activeConnections()==8,"Ninth add creates neither pane nor socket");
            check(w.planDock(live.get(0).id(),live.get(1).id(),Layout.Dock.TOP,Layout.Scope.WINDOW)==null,"Eight-pane whole-edge nesting is rejected honestly");
            Container content=(Container)field(w,"content");
            check(w.dropTarget(w.sessionPanes().getFirst().id(),new Point(2,2)).scope()==Layout.Scope.PANE,"At four to eight panes outer edges operate on quadrant slots, not impossible whole-window bands");
            snapshot(w,"eight-simple-panes");
            if (evidence!=null) { w.applyTheme(AppTheme.Paper); layout(w); snapshot(w,"eight-simple-panes-paper"); w.applyTheme(AppTheme.Charcoal); }
            SavedLayout saved=w.saveCurrentLayout(null,"Eight simple panes");
            check(saved.window().layout().equals(w.currentLayout()),"Named recipe captures actual simple geometry");
            content.setSize(100,100);
            check(w.planDock(live.get(0).id(),live.get(1).id(),Layout.Dock.TOP)==null
                    && !w.previewDock(live.get(0).id(),live.get(1).id(),Layout.Dock.TOP),"Production threshold rejects exact known-too-small geometry without a valid-looking preview");
            layout(w);
            w.setSize(520,360); layout(w);
            Workspace.DropPlan small=w.planDock(live.get(0).id(),live.get(1).id(),Layout.Dock.TOP);
            check(small==null || small.bounds().values().stream().allMatch(r -> r.width>=70 && r.height>=70),"Small-window candidates never promise undersized panes");
            if (small!=null) {
                w.previewDock(live.get(0).id(),live.get(1).id(),Layout.Dock.TOP);
                snapshot(w,"eight-minimum-drop-preview"); w.clearPreviews();
            }
            for (SessionPane pane:new ArrayList<>(w.sessionPanes())) if (w.sessionPanes().size()>1) { w.closePane(pane); layout(w); check(Layout.constrained(w.currentLayout()),"Production removal preserves simple layouts"); }
            check(w.sessionPanes().size()==1,"The final live pane is retained"); return null;
        });
    }
    public static void main(String[] args) throws Exception {
        Ui.install();
        String path=System.getProperty("fish.test.simpleEvidence","");
        if (!path.isBlank()) { evidence=Path.of(path); Files.createDirectories(evidence); }
        Path root=Files.createTempDirectory(Path.of("build"),"simple-panes-").toAbsolutePath();
        try {
            model(root);
            try (UsabilityIterationSmoke.Fixture fixture=new UsabilityIterationSmoke.Fixture(root.resolve("workspace")); DemoHost host=new DemoHost()) {
                production(fixture,host);
                edt(() -> { check(Arrays.stream(Window.getWindows()).noneMatch(Window::isShowing),"No test window was shown"); return null; });
            }
        } finally {
            edt(() -> { for (Window w:Window.getWindows()) w.dispose(); return null; });
            try (var files=Files.walk(root)) {
                for (Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
        System.out.println("PASS canonical halves / thirds / half-and-pair / once-split quadrants / migration / live geometry: "+checks);
    }
}
