package pane5250.ui;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.UIResource;
import pane5250.model.ColorRole;

/** Structural edges are filled in device coordinates, never stroked at logical-pixel width. */
public class Hairline extends AbstractBorder implements UIResource {
    private final Insets insets;
    private final boolean top,left,bottom,right;
    public Hairline(int vertical,int horizontal) { this(vertical,horizontal,true,true,true,true); }
    private Hairline(int vertical,int horizontal,boolean top,boolean left,boolean bottom,boolean right) {
        insets=new Insets(vertical,horizontal,vertical,horizontal);
        this.top=top; this.left=left; this.bottom=bottom; this.right=right;
    }
    public static Hairline perimeter() { return new Hairline(1,1); }
    public static Hairline bottom() {
        Hairline edge=new Hairline(0,0,false,false,true,false);
        edge.insets.bottom=1; return edge;
    }
    @Override public Insets getBorderInsets(Component c) { return (Insets)insets.clone(); }
    @Override public Insets getBorderInsets(Component c,Insets value) {
        value.set(insets.top,insets.left,insets.bottom,insets.right); return value;
    }
    @Override public void paintBorder(Component c,Graphics g,int x,int y,int width,int height) {
        edges(g,x,y,width,height,top,left,bottom,right);
    }
    public static void rectangle(Graphics g,double x,double y,double width,double height) {
        edges(g,x,y,width,height,true,true,true,true);
    }
    static void rectangle(Graphics g,double x,double y,double width,double height,
                          boolean top,boolean left,boolean bottom,boolean right) {
        edges(g,x,y,width,height,top,left,bottom,right);
    }
    public static void horizontal(Graphics g,double x,double y,double width) {
        edges(g,x,y,width,0,true,false,false,false);
    }
    public static void vertical(Graphics g,double x,double y,double height) {
        edges(g,x,y,0,height,false,true,false,false);
    }
    private static void edges(Graphics graphics,double x,double y,double width,double height,
                              boolean top,boolean left,boolean bottom,boolean right) {
        if (width<0 || height<0) return;
        Graphics2D g=(Graphics2D)graphics.create();
        try {
            Rectangle2D bounds=g.getTransform().createTransformedShape(new Rectangle2D.Double(x,y,width,height)).getBounds2D();
            // Match Swing's fractional-DPI buffer copy (Region.clipRound), including half-pixel ties.
            int x1=pixel(bounds.getMinX()),y1=pixel(bounds.getMinY());
            int x2=pixel(bounds.getMaxX()),y2=pixel(bounds.getMaxY());
            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(Ui.color(ColorRole.DIVIDER));
            if (top) g.fillRect(x1,y1,Math.max(1,x2-x1),1);
            if (bottom && y2>y1) g.fillRect(x1,y2-1,Math.max(1,x2-x1),1);
            int start=y1+(top ? 1 : 0),end=y2-(bottom ? 1 : 0);
            if (left) g.fillRect(x1,start,1,Math.max(0,end-start));
            if (right && x2>x1) g.fillRect(x2-1,start,1,Math.max(0,end-start));
        } finally { g.dispose(); }
    }
    private static int pixel(double coordinate) { return (int)Math.ceil(coordinate-.5); }
}
