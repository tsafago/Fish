package fish.ui;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.List;
import javax.imageio.ImageIO;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import fish.model.ColorRole;

/** One wireframe geometry for the title mark, runtime icons and Windows package. */
public final class FishIcon {
    private static final Shape OUTLINE=outline();
    private final int width,height;
    public FishIcon(int width,int height) { this.width=width; this.height=height; }
    public int getIconWidth() { return width; }
    public int getIconHeight() { return height; }
    public void paint(Graphics graphics,int x,int y) {
        Graphics2D g=(Graphics2D)graphics.create();
        try {
            double scale=Math.min(width/64.0,height/48.0);
            g.translate(x+(width-64*scale)/2,y+(height-48*scale)/2); g.scale(scale,scale);
            draw(g);
        } finally { g.dispose(); }
    }
    private static void draw(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        Color[] colors={Ui.color(ColorRole.LOGO_1),Ui.color(ColorRole.LOGO_2),Ui.color(ColorRole.LOGO_3)};
        g.setPaint(new LinearGradientPaint(4,24,58,24,new float[]{0,.5f,1},colors));
        g.setStroke(new BasicStroke(3.4f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND));
        g.draw(OUTLINE);
    }
    private static Shape outline() {
        Path2D fish=new Path2D.Double();
        fish.moveTo(4,6); fish.lineTo(40,42); fish.lineTo(58,24);
        fish.lineTo(40,6); fish.lineTo(4,42);
        return fish;
    }
    public static Canvas canvas(double width,double height) {
        Canvas canvas=new Canvas(width,height);
        repaintCanvas(canvas);
        return canvas;
    }
    public static void repaintCanvas(Canvas canvas) {
        double w=canvas.getWidth(),h=canvas.getHeight();
        GraphicsContext gc=canvas.getGraphicsContext2D();
        gc.clearRect(0,0,w,h);
        double scale=Math.min(w/64.0,h/48.0);
        gc.save();
        gc.translate((w-64*scale)/2.0,(h-48*scale)/2.0);
        gc.scale(scale,scale);
        gc.setStroke(new javafx.scene.paint.LinearGradient(4,24,58,24,false,CycleMethod.NO_CYCLE,
                new Stop(0,Ui.fxColor(ColorRole.LOGO_1)),
                new Stop(0.5,Ui.fxColor(ColorRole.LOGO_2)),
                new Stop(1,Ui.fxColor(ColorRole.LOGO_3))));
        gc.setLineWidth(3.4);
        gc.setLineCap(StrokeLineCap.BUTT);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        gc.beginPath();
        gc.moveTo(4,6); gc.lineTo(40,42); gc.lineTo(58,24); gc.lineTo(40,6); gc.lineTo(4,42);
        gc.stroke();
        gc.restore();
    }
    public static BufferedImage image(int size) {
        BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=image.createGraphics();
        try { new FishIcon(size,size).paint(g,0,0); }
        finally { g.dispose(); }
        return image;
    }
    private record LogoKey(int c1,int c2,int c3) {}
    private static final java.util.Map<LogoKey,List<javafx.scene.image.Image>> FX_ICON_CACHE=new java.util.concurrent.ConcurrentHashMap<>();

    public static List<javafx.scene.image.Image> fxImages() {
        var palette=Ui.colorTheme();
        LogoKey key=new LogoKey(palette.rgb(ColorRole.LOGO_1),palette.rgb(ColorRole.LOGO_2),palette.rgb(ColorRole.LOGO_3));
        return FX_ICON_CACHE.computeIfAbsent(key,k -> {
            if (javafx.application.Platform.isFxApplicationThread()) {
                javafx.scene.SnapshotParameters params=new javafx.scene.SnapshotParameters();
                params.setFill(javafx.scene.paint.Color.TRANSPARENT);
                int[] sizes={16,24,32,48,64,256};
                javafx.scene.image.Image[] icons=new javafx.scene.image.Image[sizes.length];
                for (int i=0;i<sizes.length;i++) {
                    int sz=sizes[i];
                    icons[i]=canvas(sz,sz).snapshot(params,new javafx.scene.image.WritableImage(sz,sz));
                }
                return List.of(icons);
            }
            return List.of(
                    SwingFXUtils.toFXImage(image(16),null),
                    SwingFXUtils.toFXImage(image(24),null),
                    SwingFXUtils.toFXImage(image(32),null),
                    SwingFXUtils.toFXImage(image(48),null),
                    SwingFXUtils.toFXImage(image(64),null),
                    SwingFXUtils.toFXImage(image(256),null));
        });
    }
    public static void main(String[] args) throws IOException {
        int[] sizes={16,24,32,48,64,128,256};
        byte[][] png=new byte[sizes.length][];
        int bytes=6+16*sizes.length;
        for (int i=0;i<sizes.length;i++) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            if (!ImageIO.write(image(sizes[i]),"png",out)) throw new IOException("PNG encoder unavailable.");
            png[i]=out.toByteArray(); bytes+=png[i].length;
        }
        ByteBuffer ico=ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short)0).putShort((short)1).putShort((short)sizes.length);
        int offset=6+16*sizes.length;
        for (int i=0;i<sizes.length;i++) {
            ico.put((byte)sizes[i]).put((byte)sizes[i]).put((byte)0).put((byte)0);
            ico.putShort((short)1).putShort((short)32).putInt(png[i].length).putInt(offset);
            offset+=png[i].length;
        }
        for (byte[] image:png) ico.put(image);
        Files.write(Path.of(args[0]),ico.array());
    }
}
