package pane5250.ui;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.Icon;

/** One wireframe geometry for the title mark, runtime icons and Windows package. */
public final class FishIcon implements Icon {
    private static final Shape OUTLINE=outline();
    private final int width,height;
    public FishIcon(int width,int height) { this.width=width; this.height=height; }
    @Override public int getIconWidth() { return width; }
    @Override public int getIconHeight() { return height; }
    @Override public void paintIcon(Component component,Graphics graphics,int x,int y) {
        Graphics2D g=(Graphics2D)graphics.create();
        try {
            double scale=Math.min(width/64.0,height/48.0);
            g.translate(x+(width-64*scale)/2,y+(height-48*scale)/2); g.scale(scale,scale);
            draw(g);
        } finally { g.dispose(); }
    }
    private static void draw(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        Color[] colors={Ui.color(pane5250.model.ColorRole.LOGO_1),Ui.color(pane5250.model.ColorRole.LOGO_2),
                Ui.color(pane5250.model.ColorRole.LOGO_3)};
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
    public static BufferedImage image(int size) {
        BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=image.createGraphics();
        try { new FishIcon(size,size).paintIcon(null,g,0,0); }
        finally { g.dispose(); }
        return image;
    }
    public static List<Image> images() { return List.of(image(16),image(24),image(32),image(48),image(64),image(256)); }
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
