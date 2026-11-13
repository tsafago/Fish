package fish.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ButtonBase;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/** Shared JavaFX test helpers for never-shown offscreen layout, reflection, snapshots, and nested loop settling. */
public final class FxTestSupport {
    private FxTestSupport() {}

    public static <T> T fx(Callable<T> work) throws Exception { return Ui.callAndWait(work); }
    public static void fxRun(Runnable work) { Ui.runAndWait(work); }

    public static void await(Callable<Boolean> condition,String message) throws Exception {
        await(condition,12,message);
    }

    public static void await(Callable<Boolean> condition,int seconds,String message) throws Exception {
        if (Platform.isFxApplicationThread()) {
            settle(condition,seconds,message);
            return;
        }
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime()<end) {
            if (fx(condition)) return;
            Thread.sleep(15);
        }
        if (!fx(condition)) throw new AssertionError(message);
    }

    public static void settle(Callable<Boolean> condition,String message) throws Exception {
        settle(condition,12,message);
    }

    public static void settle(Callable<Boolean> condition,int seconds,String message) throws Exception {
        if (!Platform.isFxApplicationThread()) {
            await(condition,seconds,message);
            return;
        }
        if (condition.call()) return;
        Object key=new Object();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        Exception[] failure={null};
        boolean[] ready={false};
        Timeline[] ref={null};
        Timeline poller=new Timeline(new KeyFrame(Duration.millis(15),e -> {
            try { ready[0]=condition.call(); }
            catch (Exception problem) { failure[0]=problem; }
            if (ready[0] || failure[0]!=null || System.nanoTime()>end) {
                if (ref[0]!=null) ref[0].stop();
                Platform.exitNestedEventLoop(key,null);
            }
        }));
        poller.setCycleCount(Timeline.INDEFINITE);
        ref[0]=poller;
        poller.play();
        Platform.enterNestedEventLoop(key);
        poller.stop();
        if (failure[0]!=null) throw failure[0];
        if (!ready[0]) throw new AssertionError(message);
    }

    public static Object field(Object owner,String name) throws Exception {
        if (owner instanceof ProfilesDialog dialog) {
            try { return fieldDirect(dialog,name); }
            catch (NoSuchFieldException ignored) { owner=dialog.editor; }
        }
        return fieldDirect(owner,name);
    }

    private static Object fieldDirect(Object owner,String name) throws Exception {
        for (Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()) {
            try { Field f=type.getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name+" on "+owner.getClass().getName());
    }

    public static void setField(Object owner,String name,Object value) throws Exception {
        if (owner instanceof ProfilesDialog dialog) {
            try { setFieldDirect(dialog,name,value); return; }
            catch (NoSuchFieldException ignored) { owner=dialog.editor; }
        }
        setFieldDirect(owner,name,value);
    }

    private static void setFieldDirect(Object owner,String name,Object value) throws Exception {
        for (Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()) {
            try { Field f=type.getDeclaredField(name); f.setAccessible(true); f.set(owner,value); return; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name+" on "+owner.getClass().getName());
    }

    public static Object invoke(Object owner,String name) throws Exception {
        for (Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()) {
            try { Method m=type.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(owner); }
            catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(name+" on "+owner.getClass().getName());
    }

    public static List<Node> children(Stage stage) {
        return stage==null || stage.getScene()==null ? List.of() : children(stage.getScene().getRoot());
    }

    public static List<Node> children(Workspace workspace) {
        return children(workspace.stage());
    }

    public static List<Node> children(Parent root) {
        List<Node> all=new ArrayList<>();
        if (root==null) return all;
        for (Node child:root.getChildrenUnmodifiable()) {
            all.add(child);
            if (child instanceof Parent nested) all.addAll(children(nested));
        }
        return all;
    }

    public static ButtonBase button(Stage stage,String text) {
        return stage==null || stage.getScene()==null ? null : button(stage.getScene().getRoot(),text);
    }

    public static ButtonBase button(Workspace workspace,String text) {
        return button(workspace.stage(),text);
    }

    public static ButtonBase button(Parent root,String text) {
        for (Node node:children(root)) {
            if (node instanceof ButtonBase b && (text.equals(b.getText()) || text.equals(b.getAccessibleText()))) return b;
        }
        return null;
    }

    public static void layout(Workspace workspace) {
        workspace.layoutNow();
    }

    public static void layout(Stage stage) {
        if (stage==null || stage.getScene()==null) return;
        Scene scene=stage.getScene();
        Parent root=scene.getRoot();
        double w=stage.getWidth()>1 ? stage.getWidth() : (scene.getWidth()>1 ? scene.getWidth() : Math.max(330,root.prefWidth(-1)));
        double h=stage.getHeight()>1 ? stage.getHeight() : (scene.getHeight()>1 ? scene.getHeight() : Math.max(240,root.prefHeight(w)));
        if (root instanceof Region region) region.resize(w,h);
        root.applyCss();
        root.layout();
    }

    public static void layout(Parent root) {
        if (root==null) return;
        if (root instanceof Region r && (r.getWidth()<=1 || r.getHeight()<=1)) {
            double w=Math.max(100,r.prefWidth(-1));
            double h=Math.max(32,r.prefHeight(w));
            r.resize(w,h);
        }
        root.applyCss();
        root.layout();
    }

    public static BufferedImage snapshot(Workspace workspace,double scale) {
        return snapshot(workspace.stage(),scale);
    }

    public static BufferedImage snapshot(Stage stage,double scale) {
        layout(stage);
        return snapshot(stage.getScene().getRoot(),scale);
    }

    public static BufferedImage snapshot(Node node,double scale) {
        if (node instanceof Parent p) {
            layout(p);
            for (Node child:children(p)) if (child instanceof TerminalView tv) tv.forceSurfaceRender();
        } else if (node instanceof TerminalView tv) {
            tv.forceSurfaceRender();
        }
        SnapshotParameters params=new SnapshotParameters();
        if (scale!=1.0) params.setTransform(Transform.scale(scale,scale));
        params.setFill(Ui.fxColor(fish.model.ColorRole.BACKGROUND));
        WritableImage fxImage=node.snapshot(params,null);
        return SwingFXUtils.fromFXImage(fxImage,null);
    }

    public static void writeSnapshot(Node node,double scale,Path destination) throws IOException {
        BufferedImage img=snapshot(node,scale);
        if (destination.getParent()!=null) Files.createDirectories(destination.getParent());
        ImageIO.write(img,"png",destination.toFile());
    }

    public static java.awt.Rectangle boundsIn(Node child,Node ancestor) {
        Bounds local=child.getBoundsInLocal();
        Bounds inScene=child.localToScene(local);
        Bounds rel=ancestor.sceneToLocal(inScene);
        return new java.awt.Rectangle(
                (int)Math.round(rel.getMinX()),
                (int)Math.round(rel.getMinY()),
                (int)Math.round(rel.getWidth()),
                (int)Math.round(rel.getHeight()));
    }

    public static void closeAllWindows() {
        Ui.runAndWait(() -> {
            for (Window w:List.copyOf(Window.getWindows())) {
                if (w instanceof Stage s) s.close();
                else w.hide();
            }
        });
    }

    public static void deleteTree(Path root) throws IOException {
        if (root==null || !Files.exists(root)) return;
        try (var paths=Files.walk(root)) {
            for (Path file:paths.sorted(Comparator.reverseOrder()).toList()) {
                for (int attempt=0;;attempt++) {
                    try { Files.deleteIfExists(file); break; }
                    catch (IOException busy) {
                        if (attempt==8) throw busy;
                        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw busy; }
                    }
                }
            }
        }
    }
}
