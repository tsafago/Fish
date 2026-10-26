package pane5250.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.*;
import javax.swing.plaf.basic.*;
import javax.swing.border.AbstractBorder;

/** Small shared Swing delegates: flat surfaces, one border treatment, explicit focus. */
public final class FlatControls {
    private FlatControls() {}
    public static final class Edge extends AbstractBorder implements UIResource {
        private final int vertical,horizontal;
        public Edge(int vertical,int horizontal) { this.vertical=vertical; this.horizontal=horizontal; }
        @Override public Insets getBorderInsets(Component c) { return new Insets(vertical,horizontal,vertical,horizontal); }
        @Override public void paintBorder(Component c,Graphics g,int x,int y,int width,int height) {
            g.setColor(c.hasFocus() ? Ui.FOCUS : Ui.LINE);
            g.drawRect(x,y,width-1,height-1);
        }
    }
    public static final class Button extends BasicButtonUI {
        public static ComponentUI createUI(JComponent c) { return new Button(); }
        @Override public void installDefaults(AbstractButton b) {
            super.installDefaults(b);
            b.setOpaque(false); b.setRolloverEnabled(true);
        }
        @Override public void update(Graphics g,JComponent c) {
            AbstractButton button=(AbstractButton)c;
            if (button.isContentAreaFilled() || button.getModel().isRollover() || button.getModel().isPressed()) {
                g.setColor(!button.isEnabled() ? Ui.PANEL : button.getModel().isPressed()
                        ? Ui.SELECTION : button.getModel().isRollover() ? Ui.HOVER : Ui.FIELD);
                g.fillRect(0,0,c.getWidth(),c.getHeight());
            }
            super.paint(g,c);
        }
        @Override protected void paintButtonPressed(Graphics g,AbstractButton b) {}
        @Override protected void paintFocus(Graphics g,AbstractButton b,Rectangle view,Rectangle text,Rectangle icon) {
            g.setColor(Ui.FOCUS); g.drawRect(2,2,b.getWidth()-5,b.getHeight()-5);
        }
        @Override protected void paintText(Graphics g,AbstractButton b,Rectangle text,String value) {
            g.setColor(b.isEnabled() ? b.getForeground() : Ui.MUTED);
            BasicGraphicsUtils.drawStringUnderlineCharAt(g,value,b.getDisplayedMnemonicIndex(),text.x,
                    text.y+g.getFontMetrics().getAscent());
        }
    }
    public static final class Combo extends BasicComboBoxUI {
        public static ComponentUI createUI(JComponent c) { return new Combo(); }
        @Override protected JButton createArrowButton() {
            return Ui.iconButton("Show choices","down",() -> {});
        }
        @Override public void paintCurrentValueBackground(Graphics g,Rectangle bounds,boolean focus) {
            g.setColor(Ui.FIELD); g.fillRect(bounds.x,bounds.y,bounds.width,bounds.height);
        }
        @Override protected ListCellRenderer<Object> createRenderer() {
            return new DefaultListCellRenderer.UIResource() {
                @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus) {
                    super.getListCellRendererComponent(list,value,index,selected,focus);
                    setBorder(BorderFactory.createEmptyBorder(4,8,4,8));
                    if (index < 0) { setBackground(Ui.FIELD); setForeground(comboBox.isEnabled() ? Ui.TEXT : Ui.MUTED); }
                    return this;
                }
            };
        }
    }
    public static final class Scroll extends BasicScrollBarUI {
        public static ComponentUI createUI(JComponent c) { return new Scroll(); }
        @Override protected void configureScrollBarColors() { thumbColor=Ui.LINE; trackColor=Ui.PANEL; }
        private JButton empty() { JButton b=new JButton(); b.setPreferredSize(new Dimension(0,0)); return b; }
        @Override protected JButton createDecreaseButton(int direction) { return empty(); }
        @Override protected JButton createIncreaseButton(int direction) { return empty(); }
        @Override protected void paintThumb(Graphics g,JComponent c,Rectangle r) {
            if (!c.isEnabled()) return;
            g.setColor(isThumbRollover() ? Ui.MUTED : Ui.LINE);
            g.fillRect(r.x+2,r.y+2,Math.max(2,r.width-4),Math.max(2,r.height-4));
        }
    }
    public static final class Split extends BasicSplitPaneUI {
        public static ComponentUI createUI(JComponent c) { return new Split(); }
        @Override public BasicSplitPaneDivider createDefaultDivider() {
            BasicSplitPaneDivider divider=new BasicSplitPaneDivider(this) {
                @Override public void paint(Graphics g) {
                    g.setColor(Ui.BACKGROUND); g.fillRect(0,0,getWidth(),getHeight());
                    g.setColor(Ui.LINE);
                    if (getOrientation()==JSplitPane.HORIZONTAL_SPLIT)
                        g.drawLine(getWidth()/2,0,getWidth()/2,getHeight());
                    else g.drawLine(0,getHeight()/2,getWidth(),getHeight()/2);
                }
            };
            divider.setBorder(BorderFactory.createEmptyBorder());
            return divider;
        }
    }
    public static final class Tabs extends BasicTabbedPaneUI {
        public static ComponentUI createUI(JComponent c) { return new Tabs(); }
        @Override protected void installDefaults() {
            super.installDefaults(); tabInsets=new Insets(7,12,7,12); contentBorderInsets=new Insets(8,0,0,0);
        }
        @Override protected void paintTabBackground(Graphics g,int placement,int index,int x,int y,int w,int h,boolean selected) {
            g.setColor(selected ? Ui.HOVER : Ui.PANEL); g.fillRect(x,y,w,h);
        }
        @Override protected void paintTabBorder(Graphics g,int placement,int index,int x,int y,int w,int h,boolean selected) {
            if (selected) { g.setColor(Ui.FOCUS); g.fillRect(x,y+h-2,w,2); }
        }
        @Override protected void paintContentBorder(Graphics g,int placement,int selected) {}
        @Override protected void paintFocusIndicator(Graphics g,int placement,Rectangle[] rects,int index,Rectangle icon,Rectangle text,boolean selected) {
            if (selected && tabPane.hasFocus()) { Rectangle r=rects[index]; g.setColor(Ui.FOCUS); g.drawRect(r.x+2,r.y+2,r.width-5,r.height-5); }
        }
    }
    public static final class CheckIcon implements Icon,UIResource {
        @Override public int getIconWidth() { return 17; }
        @Override public int getIconHeight() { return 17; }
        @Override public void paintIcon(Component c,Graphics g,int x,int y) {
            AbstractButton b=(AbstractButton)c;
            g.setColor(Ui.FIELD); g.fillRect(x+1,y+1,14,14);
            g.setColor(b.hasFocus() ? Ui.FOCUS : Ui.LINE); g.drawRect(x+1,y+1,14,14);
            if (b.isSelected()) {
                g.setColor(b.isEnabled() ? Ui.FOCUS : Ui.MUTED);
                g.drawLine(x+4,y+8,x+7,y+11); g.drawLine(x+7,y+11,x+12,y+5);
            }
        }
    }
}
