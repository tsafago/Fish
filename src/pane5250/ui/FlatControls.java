package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.Objects;
import javax.swing.*;
import javax.swing.plaf.*;
import javax.swing.plaf.basic.*;
import pane5250.model.ColorRole;

/** Small shared Swing delegates: flat surfaces, one border treatment, explicit focus. */
public final class FlatControls {
    private FlatControls() {}
    public static final class Edge extends Hairline {
        public Edge(int vertical,int horizontal) { super(vertical,horizontal); }
    }
    public static final class Button extends BasicButtonUI {
        public static ComponentUI createUI(JComponent c) { return new Button(); }
        @Override public void installDefaults(AbstractButton b) {
            super.installDefaults(b);
            b.setOpaque(false); b.setRolloverEnabled(true);
        }
        @Override public void update(Graphics g,JComponent c) {
            Ui.quality(g);
            AbstractButton button=(AbstractButton)c;
            if (button.isContentAreaFilled() || button.getModel().isRollover() || button.getModel().isPressed() || button.getModel().isSelected()) {
                g.setColor(!button.isEnabled() ? Ui.PANEL : button.getModel().isPressed()
                        || button.getModel().isSelected() ? Ui.color(ColorRole.PRESSED) : button.getModel().isRollover()
                        ? Ui.color(Boolean.TRUE.equals(button.getClientProperty("chrome.control")) ? ColorRole.MENU_HOVER : ColorRole.HOVER) : Ui.FIELD);
                g.fillRect(0,0,c.getWidth(),c.getHeight());
            }
            super.paint(g,c);
        }
        @Override protected void paintButtonPressed(Graphics g,AbstractButton b) {}
        @Override protected void paintFocus(Graphics g,AbstractButton b,Rectangle view,Rectangle text,Rectangle icon) {
            Rectangle ink=text.width>0 ? text : icon;
            Hairline.horizontal(g,ink.x,ink.y+ink.height,Math.max(8,ink.width));
        }
        @Override protected void paintText(Graphics g,AbstractButton b,Rectangle text,String value) {
            buttonText(g,b,text,value);
        }
    }
    private static void buttonText(Graphics g,AbstractButton b,Rectangle text,String value) {
        g.setColor(b.isEnabled() ? b.getForeground() : Ui.color(ColorRole.DISABLED));
        BasicGraphicsUtils.drawStringUnderlineCharAt(g,value,Ui.mnemonicsVisible() ? b.getDisplayedMnemonicIndex() : -1,text.x,
                text.y+g.getFontMetrics().getAscent());
    }
    public static final class Label extends BasicLabelUI {
        public static ComponentUI createUI(JComponent c) { return new Label(); }
        @Override public void paint(Graphics g,JComponent c) { Ui.quality(g); super.paint(g,c); }
        @Override protected void paintEnabledText(JLabel label,Graphics g,String value,int x,int y) {
            g.setColor(label.getForeground());
            BasicGraphicsUtils.drawStringUnderlineCharAt(g,value,Ui.mnemonicsVisible() ? label.getDisplayedMnemonicIndex() : -1,x,y);
        }
    }
    public static final class Menu extends BasicMenuUI {
        public static ComponentUI createUI(JComponent c) { return new Menu(); }
        @Override public void paint(Graphics g,JComponent c) { Ui.quality(g); super.paint(g,c); }
        @Override protected void paintText(Graphics g,JMenuItem item,Rectangle text,String value) { menuText(g,item,text,value); }
    }
    public static final class MenuItem extends BasicMenuItemUI {
        public static ComponentUI createUI(JComponent c) { return new MenuItem(); }
        @Override public Dimension getPreferredSize(JComponent c) { return shortcutSize(c,super.getPreferredSize(c)); }
        @Override public void paint(Graphics g,JComponent c) { Ui.quality(g); super.paint(g,c); paintShortcut(g,(JMenuItem)c); }
        @Override protected void paintText(Graphics g,JMenuItem item,Rectangle text,String value) { menuText(g,item,text,value); }
    }
    public static final class CheckMenuItem extends BasicCheckBoxMenuItemUI {
        public static ComponentUI createUI(JComponent c) { return new CheckMenuItem(); }
        @Override public Dimension getPreferredSize(JComponent c) { return shortcutSize(c,super.getPreferredSize(c)); }
        @Override public void paint(Graphics g,JComponent c) { Ui.quality(g); super.paint(g,c); paintShortcut(g,(JMenuItem)c); }
    }
    private static String shortcut(JMenuItem item) {
        return item.getAction() instanceof AppCommand action ? action.shortcut : "";
    }
    private static Dimension shortcutSize(JComponent c,Dimension size) {
        String text=shortcut((JMenuItem)c);
        if (!text.isEmpty()) size.width+=c.getFontMetrics(c.getFont()).stringWidth(text)+28;
        return size;
    }
    private static void paintShortcut(Graphics g,JMenuItem item) {
        String text=shortcut(item); if (text.isEmpty()) return;
        g.setFont(item.getFont());
        g.setColor(!item.isEnabled() ? Ui.color(ColorRole.DISABLED) : item.getModel().isArmed()
                ? Ui.color(ColorRole.MENU_SELECTED_TEXT) : Ui.MUTED);
        FontMetrics metrics=g.getFontMetrics();
        g.drawString(text,item.getWidth()-metrics.stringWidth(text)-14,(item.getHeight()-metrics.getHeight())/2+metrics.getAscent());
    }
    private static void menuText(Graphics g,JMenuItem item,Rectangle text,String value) {
        boolean selected=item.getModel().isArmed() || item instanceof JMenu && item.getModel().isSelected();
        g.setColor(!item.isEnabled() ? Ui.color(ColorRole.DISABLED)
                : Ui.color(selected ? ColorRole.MENU_SELECTED_TEXT : item instanceof JMenu menu && menu.isTopLevelMenu()
                ? ColorRole.CHROME_TEXT : ColorRole.MENU_TEXT));
        BasicGraphicsUtils.drawStringUnderlineCharAt(g,value,Ui.mnemonicsVisible() ? item.getDisplayedMnemonicIndex() : -1,
                text.x,text.y+g.getFontMetrics().getAscent());
    }
    public static final class Check extends BasicCheckBoxUI {
        public static ComponentUI createUI(JComponent c) { return new Check(); }
        @Override public void paint(Graphics g,JComponent c) { Ui.quality(g); super.paint(g,c); }
        @Override protected void paintText(Graphics g,AbstractButton b,Rectangle text,String value) {
            buttonText(g,b,text,value);
        }
        @Override protected void paintFocus(Graphics g,Rectangle text,Dimension size) {
            Hairline.horizontal(g,text.x,text.y+text.height,text.width);
        }
    }
    public static final class Combo extends BasicComboBoxUI {
        private boolean rollover;
        private final MouseAdapter hover=new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { rollover=true; comboBox.repaint(); }
            @Override public void mouseExited(MouseEvent e) { rollover=false; comboBox.repaint(); }
        };
        private boolean chrome() { return Boolean.TRUE.equals(comboBox.getClientProperty("chrome.control")); }
        private Color surface() {
            return chrome() ? Ui.color(comboBox.isEnabled() && comboBox.isPopupVisible() ? ColorRole.PRESSED
                    : comboBox.isEnabled() && rollover ? ColorRole.MENU_HOVER : ColorRole.CHROME) : Ui.FIELD;
        }
        public static ComponentUI createUI(JComponent c) { return new Combo(); }
        @Override public void installUI(JComponent c) {
            super.installUI(c);
            if (chrome()) {
                comboBox.setOpaque(false);
                comboBox.setBorder(BorderFactory.createEmptyBorder(0,0,0,0));
                comboBox.addMouseListener(hover); arrowButton.addMouseListener(hover);
            }
        }
        @Override public void uninstallUI(JComponent c) {
            comboBox.removeMouseListener(hover);
            if (arrowButton!=null) arrowButton.removeMouseListener(hover);
            super.uninstallUI(c);
        }
        @Override protected JButton createArrowButton() {
            JButton arrow=Ui.iconButton("Show choices","down",() -> {});
            if (chrome()) {
                arrow.putClientProperty("chrome.control",true); arrow.setFocusable(false);
                Ui.foreground(arrow,ColorRole.CHROME_TEXT);
            }
            return arrow;
        }
        @Override public void paint(Graphics g,JComponent c) {
            if (chrome()) { g.setColor(surface()); g.fillRect(0,0,c.getWidth(),c.getHeight()); }
            super.paint(g,c);
            if (chrome() && comboBox.isEnabled() && comboBox.hasFocus()) {
                Hairline.focusUnderline(g,8,c.getHeight()-3,Math.max(8,c.getWidth()-16));
            }
        }
        @Override protected ComboPopup createPopup() {
            return new BasicComboPopup(comboBox) {
                @Override protected void configurePopup() {
                    super.configurePopup(); setBorder(Hairline.perimeter());
                }
                @Override protected void configureScroller() {
                    super.configureScroller(); scroller.setBorder(BorderFactory.createEmptyBorder());
                }
            };
        }
        @Override public void paintCurrentValueBackground(Graphics g,Rectangle bounds,boolean focus) {
            g.setColor(surface()); g.fillRect(bounds.x,bounds.y,bounds.width,bounds.height);
        }
        @Override public void paintCurrentValue(Graphics g,Rectangle bounds,boolean focus) {
            Component value=comboBox.getRenderer().getListCellRendererComponent(listBox,comboBox.getSelectedItem(),-1,false,false);
            value.setFont(Ui.textFont(comboBox.getFont(),Objects.toString(comboBox.getSelectedItem(),"")));
            value.setForeground(comboBox.isEnabled() ? Ui.color(chrome() ? ColorRole.CHROME_TEXT : ColorRole.TEXT) : Ui.color(ColorRole.DISABLED));
            value.setBackground(surface());
            Ui.quality(g);
            currentValuePane.paintComponent(g,value,comboBox,bounds.x,bounds.y,bounds.width,bounds.height,true);
        }
        @Override protected ListCellRenderer<Object> createRenderer() {
            return new DefaultListCellRenderer.UIResource() {
                @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus) {
                    super.getListCellRendererComponent(list,value,index,selected,focus);
                    setFont(Ui.textFont(list.getFont(),Objects.toString(value,"")));
                    setBorder(BorderFactory.createEmptyBorder(4,8,4,8));
                    if (index < 0) { setBackground(Ui.FIELD); setForeground(comboBox.isEnabled() ? Ui.TEXT : Ui.color(ColorRole.DISABLED)); }
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
                    g.setColor(Ui.color(ColorRole.DIVIDER_BACKGROUND)); g.fillRect(0,0,getWidth(),getHeight());
                    if (getOrientation()==JSplitPane.HORIZONTAL_SPLIT)
                        Hairline.vertical(g,getWidth()/2.0,0,getHeight());
                    else Hairline.horizontal(g,0,getHeight()/2.0,getWidth());
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
            if (selected) Hairline.horizontal(g,x,y+h-1,w);
        }
        @Override protected void paintContentBorder(Graphics g,int placement,int selected) {}
        @Override protected void paintFocusIndicator(Graphics g,int placement,Rectangle[] rects,int index,Rectangle icon,Rectangle text,boolean selected) {
            if (selected && tabPane.hasFocus()) Hairline.horizontal(g,text.x,text.y+text.height,text.width);
        }
    }
    public static final class CheckIcon implements Icon,UIResource {
        @Override public int getIconWidth() { return 17; }
        @Override public int getIconHeight() { return 17; }
        @Override public void paintIcon(Component c,Graphics g,int x,int y) {
            Ui.quality(g);
            AbstractButton b=(AbstractButton)c;
            g.setColor(Ui.FIELD); g.fillRect(x+1,y+1,14,14);
            Hairline.rectangle(g,x+1,y+1,15,15);
            if (b.isSelected()) {
                g.setColor(b.isEnabled() ? Ui.FOCUS : Ui.color(ColorRole.DISABLED));
                g.drawLine(x+4,y+8,x+7,y+11); g.drawLine(x+7,y+11,x+12,y+5);
            }
        }
    }
    public static final class Separator extends BasicSeparatorUI {
        public static ComponentUI createUI(JComponent c) { return new Separator(); }
        @Override public Dimension getPreferredSize(JComponent c) {
            return ((JSeparator)c).getOrientation()==SwingConstants.VERTICAL ? new Dimension(5,0) : new Dimension(0,7);
        }
        @Override public void paint(Graphics g,JComponent c) {
            if (((JSeparator)c).getOrientation()==SwingConstants.VERTICAL) Hairline.vertical(g,c.getWidth()/2.0,0,c.getHeight());
            else Hairline.horizontal(g,0,c.getHeight()/2.0,c.getWidth());
        }
    }
    public static final class Table extends BasicTableUI {
        public static ComponentUI createUI(JComponent c) { return new Table(); }
        @Override protected void installDefaults() { super.installDefaults(); table.setShowGrid(false); table.setIntercellSpacing(new Dimension(0,0)); }
        @Override public void paint(Graphics g,JComponent c) {
            super.paint(g,c);
            Rectangle clip=g.getClipBounds();
            int first=Math.max(0,table.rowAtPoint(new Point(0,clip.y)));
            int last=table.rowAtPoint(new Point(0,clip.y+clip.height));
            if (last<0) last=table.getRowCount()-1;
            for (int row=first;row<=last;row++) {
                Rectangle cell=table.getCellRect(row,0,true);
                Hairline.rectangle(g,0,cell.y,table.getWidth(),cell.height,false,false,true,false);
            }
        }
    }
}
