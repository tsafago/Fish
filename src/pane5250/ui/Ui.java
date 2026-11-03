package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.*;
import pane5250.model.*;

public final class Ui {
    public static Color BACKGROUND,PANEL,FIELD,HOVER,LINE,TEXT,MUTED,FOCUS,ERROR,SELECTION;
    private static AppTheme theme=AppTheme.Charcoal;
    private static ColorTheme colors=ColorTheme.builtin(theme);
    private static boolean custom;
    private static boolean altDown;
    private Ui() {}
    static { colors(theme); }
    private static Color color(int rgb) { return new ColorUIResource(rgb); }
    private static void colors(AppTheme value) {
        theme=value;
        BACKGROUND=color(ColorRole.BACKGROUND); PANEL=color(ColorRole.PANEL); FIELD=color(ColorRole.FIELD); HOVER=color(ColorRole.HOVER);
        LINE=color(ColorRole.DIVIDER); TEXT=color(ColorRole.TEXT); MUTED=color(ColorRole.MUTED); FOCUS=color(ColorRole.FOCUS);
        ERROR=color(ColorRole.ERROR); SELECTION=color(ColorRole.SELECTION);
    }
    public static AppTheme theme() { return theme; }
    public static Color color(ColorRole role) { return color(colors.rgb(role)); }
    public static ColorTheme colorTheme() { return colors; }
    public static boolean customTheme() { return custom; }
    public static boolean dark() { return (BACKGROUND.getRed()*.2126+BACKGROUND.getGreen()*.7152+BACKGROUND.getBlue()*.0722)<128; }
    public static void install() throws Exception {
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        PopupFactory.setSharedInstance(new FlatPopups());
        UIManager.put("PopupMenu.dropShadowEnabled",false);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getKeyCode()==KeyEvent.VK_ALT) {
                altDown=e.getID()==KeyEvent.KEY_PRESSED && !e.isControlDown();
                for (Window w:Window.getWindows()) if (w.isShowing()) w.repaint();
            }
            return false;
        });
        applyTheme(AppTheme.Charcoal);
    }
    public static boolean mnemonicsVisible() { return altDown || MenuSelectionManager.defaultManager().getSelectedPath().length>0; }
    public static void applyTheme(AppTheme value) {
        applyTheme(value,null);
    }
    public static void applyTheme(AppTheme value,ColorTheme override) {
        colors=override==null ? ColorTheme.builtin(value) : override; custom=override!=null;
        colors(value);
        HostPalette.clear();
        Font font=new FontUIResource("Segoe UI",Font.PLAIN,12);
        for (Object key : java.util.Collections.list(UIManager.getDefaults().keys())) {
            if (key.toString().endsWith(".font")) UIManager.put(key,font);
        }
        for (String name : new String[]{"Panel","RootPane","MenuBar","Menu","MenuItem","CheckBoxMenuItem","RadioButtonMenuItem",
                "PopupMenu","TabbedPane","OptionPane","Label","Button","ToggleButton","CheckBox","RadioButton","ComboBox","List",
                "ScrollPane","Viewport","TextField","PasswordField","TextArea","Spinner","SplitPane","ToolTip",
                "Table","TableHeader","EditorPane","TextPane","Tree"}) {
            UIManager.put(name+".background",PANEL); UIManager.put(name+".foreground",TEXT);
            UIManager.put(name+".disabledForeground",color(ColorRole.DISABLED));
            UIManager.put(name+".disabledText",color(ColorRole.DISABLED));
        }
        for (String name : new String[]{"TextField","PasswordField","TextArea","ComboBox","List","Table","EditorPane","TextPane","Tree"}) {
            UIManager.put(name+".background",FIELD);
            UIManager.put(name+".selectionBackground",SELECTION); UIManager.put(name+".selectionForeground",color(ColorRole.SELECTION_TEXT));
            UIManager.put(name+".inactiveForeground",MUTED); UIManager.put(name+".caretForeground",FOCUS);
        }
        for (String name : new String[]{"Menu","MenuItem","CheckBoxMenuItem","RadioButtonMenuItem"}) {
            UIManager.put(name+".background",color(ColorRole.MENU)); UIManager.put(name+".foreground",color(ColorRole.MENU_TEXT));
            UIManager.put(name+".selectionBackground",color(ColorRole.MENU_HOVER));
            UIManager.put(name+".selectionForeground",color(ColorRole.MENU_SELECTED_TEXT));
            UIManager.put(name+".acceleratorForeground",MUTED);
            UIManager.put(name+".acceleratorSelectionForeground",color(ColorRole.MENU_SELECTED_TEXT));
            UIManager.put(name+".border",new BorderUIResource.EmptyBorderUIResource(6,10,6,10));
        }
        UIManager.put("PopupMenu.background",color(ColorRole.MENU));
        UIManager.put("Panel.background",color(ColorRole.DIALOG));
        UIManager.put("RootPane.background",color(ColorRole.DIALOG));
        UIManager.put("MenuBar.background",color(ColorRole.CHROME));
        UIManager.put("ToolTip.background",color(ColorRole.TOOLTIP)); UIManager.put("ToolTip.foreground",color(ColorRole.TOOLTIP_TEXT));
        UIManager.put("ToolTip.backgroundInactive",color(ColorRole.TOOLTIP));
        UIManager.put("ToolTip.foregroundInactive",color(ColorRole.DISABLED));
        UIManager.put("OptionPane.background",color(ColorRole.DIALOG));
        UIManager.put("ButtonUI",FlatControls.Button.class.getName());
        UIManager.put("ToggleButtonUI",FlatControls.Button.class.getName());
        UIManager.put("LabelUI",FlatControls.Label.class.getName());
        UIManager.put("MenuBarUI","javax.swing.plaf.basic.BasicMenuBarUI");
        UIManager.put("MenuUI",FlatControls.Menu.class.getName());
        UIManager.put("MenuItemUI",FlatControls.MenuItem.class.getName());
        UIManager.put("CheckBoxMenuItemUI","javax.swing.plaf.basic.BasicCheckBoxMenuItemUI");
        UIManager.put("RadioButtonMenuItemUI","javax.swing.plaf.basic.BasicRadioButtonMenuItemUI");
        UIManager.put("CheckBoxUI",FlatControls.Check.class.getName());
        UIManager.put("MenuBar.gradient",null);
        UIManager.put("Menu.arrowIcon",new Symbol("right"));
        UIManager.put("MenuItem.checkIcon",null);
        UIManager.put("CheckBoxMenuItem.checkIcon",new FlatControls.CheckIcon());
        UIManager.put("RadioButtonMenuItem.checkIcon",new FlatControls.CheckIcon());
        UIManager.put("ComboBoxUI",FlatControls.Combo.class.getName());
        UIManager.put("ScrollBarUI",FlatControls.Scroll.class.getName());
        UIManager.put("TabbedPaneUI",FlatControls.Tabs.class.getName());
        UIManager.put("SplitPaneUI",FlatControls.Split.class.getName());
        UIManager.put("SeparatorUI",FlatControls.Separator.class.getName());
        UIManager.put("PopupMenuSeparatorUI",FlatControls.Separator.class.getName());
        UIManager.put("TableUI",FlatControls.Table.class.getName());
        for (String name : new String[]{"Button","ToggleButton","TextField","PasswordField","TextArea","ComboBox","Spinner"})
            UIManager.put(name+".border",new FlatControls.Edge(5,8));
        UIManager.put("CheckBox.icon",new FlatControls.CheckIcon());
        UIManager.put("RadioButton.icon",new FlatControls.CheckIcon());
        UIManager.put("CheckBox.focus",FOCUS); UIManager.put("RadioButton.focus",FOCUS);
        UIManager.put("PopupMenu.border",Hairline.perimeter());
        UIManager.put("MenuBar.border",new BorderUIResource.EmptyBorderUIResource(0,0,0,0));
        UIManager.put("ScrollPane.border",Hairline.perimeter());
        UIManager.put("List.focusCellHighlightBorder",Hairline.perimeter());
        UIManager.put("Table.gridColor",LINE);
        UIManager.put("Table.focusCellHighlightBorder",Hairline.perimeter());
        UIManager.put("TableHeader.cellBorder",Hairline.bottom());
        UIManager.put("OptionPane.messageForeground",TEXT);
        UIManager.put("Separator.foreground",LINE); UIManager.put("Separator.background",PANEL);
        UIManager.put("SplitPane.border",new BorderUIResource.EmptyBorderUIResource(0,0,0,0));
        UIManager.put("ToolTip.border",Hairline.perimeter());
        UIManager.put("Spinner.arrowButtonBorder",BorderFactory.createEmptyBorder());
        UIManager.put("ScrollBar.width",12);
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
            refresh(window);
            window.validate();
            window.repaint();
        }
    }
    private static void refresh(Component component) {
        if (component instanceof JRootPane root && !(SwingUtilities.getWindowAncestor(root) instanceof Workspace))
            root.setBorder(SwingUtilities.getWindowAncestor(root) instanceof JDialog ? Hairline.perimeter() : BorderFactory.createEmptyBorder());
        if (component instanceof JComponent c) {
            if ("muted".equals(c.getClientProperty("theme.role"))) c.setForeground(MUTED);
            if ("error".equals(c.getClientProperty("theme.role"))) c.setForeground(ERROR);
            if (c.getClientProperty("theme.foreground") instanceof ColorRole role) c.setForeground(color(role));
            if (c.getClientProperty("theme.background") instanceof ColorRole role) c.setBackground(color(role));
        }
        if (component instanceof Workspace w) w.themeChanged();
        if (component instanceof SessionPane p) p.themeChanged();
        if (component instanceof TerminalView v) v.themeChanged();
        if (component instanceof WindowChrome c) c.themeChanged();
        if (component instanceof ManualDialog manual) manual.themeChanged();
        if (component instanceof Container parent)
            for (Component child : parent.getComponents()) refresh(child);
    }
    public static void role(JComponent component,String role) {
        component.putClientProperty("theme.role",role);
        component.setForeground(role.equals("error") ? ERROR : MUTED);
    }
    public static void foreground(JComponent component,ColorRole role) {
        component.putClientProperty("theme.foreground",role); component.setForeground(color(role));
    }
    public static void background(JComponent component,ColorRole role) {
        component.putClientProperty("theme.background",role); component.setBackground(color(role));
    }
    static void labeled(JPanel form,String text,JComponent input) {
        JLabel label=new JLabel(text); label.setLabelFor(input); form.add(label); form.add(input);
    }
    public static JButton button(String text,int mnemonic,Runnable action) {
        JButton button=new JButton(text); button.setMnemonic(mnemonic);
        button.addActionListener(e -> action.run()); return button;
    }
    public static JButton iconButton(String name,String kind,Runnable action) {
        JButton button=new JButton(new Symbol(kind));
        button.setToolTipText(name); button.getAccessibleContext().setAccessibleName(name);
        button.setPreferredSize(new Dimension(25,24));
        button.setBorder(BorderFactory.createEmptyBorder(3,5,3,5));
        button.setContentAreaFilled(false);
        button.addActionListener(e -> action.run());
        return button;
    }
    public static Icon symbol(String kind) { return new Symbol(kind); }
    public static void quality(Graphics graphics) {
        if (graphics instanceof Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            Object hints=Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            if (hints instanceof java.util.Map<?,?> map) {
                for (var entry:map.entrySet()) if (entry.getKey() instanceof RenderingHints.Key key)
                    g.setRenderingHint(key,entry.getValue());
            } else g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        }
    }
    private record Symbol(String kind) implements Icon {
        @Override public int getIconWidth() { return 12; }
        @Override public int getIconHeight() { return 12; }
        @Override public void paintIcon(Component c,Graphics graphics,int x,int y) {
            Graphics2D g=(Graphics2D)graphics.create();
            try {
                quality(g);
                g.setStroke(new BasicStroke(1.35f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                g.translate(x,y); g.setColor(c.isEnabled() ? c.getForeground() : color(ColorRole.DISABLED));
                if (kind.equals("close")) { g.drawLine(2,2,10,10); g.drawLine(10,2,2,10); }
                else if (kind.equals("plus")) { g.drawLine(1,6,11,6); g.drawLine(6,1,6,11); }
                else if (kind.equals("search")) { g.drawOval(1,1,7,7); g.drawLine(8,8,11,11); }
                else if (kind.equals("connect")) { g.drawLine(1,6,10,6); g.drawLine(6,2,10,6); g.drawLine(10,6,6,10); }
                else if (kind.equals("right")) { g.drawLine(4,2,8,6); g.drawLine(8,6,4,10); }
                else { g.drawLine(2,4,6,8); g.drawLine(6,8,10,4); }
            } finally { g.dispose(); }
        }
    }
    public static Border padding(int size) { return BorderFactory.createEmptyBorder(size,size,size,size); }
    public static Color paneBackground(boolean active) {
        return color(active ? ColorRole.ACTIVE_TINT : ColorRole.BACKGROUND);
    }

    public static void decorate(JDialog dialog,Runnable dismiss) {
        dialog.setUndecorated(true);
        dialog.setIconImages(FishIcon.images());
        Container content=dialog.getContentPane();
        JPanel shell=new JPanel(new BorderLayout());
        JPanel title=new JPanel(new BorderLayout());
        title.setBorder(BorderFactory.createEmptyBorder(4,12,4,4));
        JLabel label=new JLabel(dialog.getTitle());
        title.add(label,BorderLayout.CENTER);
        title.add(iconButton("Close dialog","close",dismiss),BorderLayout.EAST);
        Point[] pressed={null}; Point[] original={null};
        MouseAdapter move=new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) { pressed[0]=e.getLocationOnScreen(); original[0]=dialog.getLocation(); }
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (pressed[0]!=null) dialog.setLocation(original[0].x+e.getXOnScreen()-pressed[0].x,original[0].y+e.getYOnScreen()-pressed[0].y);
            }
            @Override public void mouseReleased(MouseEvent e) { pressed[0]=null; }
        };
        title.addMouseListener(move); title.addMouseMotionListener(move);
        label.addMouseListener(move); label.addMouseMotionListener(move);
        shell.add(title,BorderLayout.NORTH); shell.add(content,BorderLayout.CENTER);
        dialog.setContentPane(shell);
        background(dialog.getRootPane(),ColorRole.DIALOG);
        dialog.getRootPane().setBorder(Hairline.perimeter());
        dialog.addHierarchyListener(e -> {
            if ((e.getChangeFlags()&HierarchyEvent.DISPLAYABILITY_CHANGED)!=0 && dialog.isDisplayable()) NativeWindow.flatten(dialog);
        });
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { dismiss.run(); } });
        dialog.getRootPane().registerKeyboardAction(e -> dismiss.run(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
    }
    public static boolean form(Component owner,String title,JComponent body,String accept) {
        return prompt(owner,title,body,accept,true);
    }
    public static void present(Component owner,String title,JComponent body,String accept) {
        prompt(owner,title,body,accept,false);
    }
    static boolean openFile(Window owner,String title,JFileChooser chooser) {
        JDialog dialog=new JDialog(owner,title,Dialog.ModalityType.APPLICATION_MODAL);
        boolean[] accepted={false};
        chooser.addActionListener(e -> {
            accepted[0]=JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand());
            dialog.dispose();
        });
        dialog.setContentPane(chooser);
        decorate(dialog,dialog::dispose);
        dialog.pack(); dialog.setLocationRelativeTo(owner); dialog.setVisible(true);
        return accepted[0];
    }
    private static boolean prompt(Component owner,String title,JComponent body,String accept,boolean cancel) {
        boolean[] accepted={false};
        JDialog dialog=promptDialog(owner,title,body,accept,cancel,() -> accepted[0]=true);
        showDialog(dialog);
        return accepted[0];
    }
    static JDialog promptDialog(Component owner,String title,JComponent body,String accept,boolean cancel,Runnable onAccept) {
        Window parent=owner instanceof Window w ? w : owner==null
                ? KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow() : SwingUtilities.getWindowAncestor(owner);
        JDialog dialog=new JDialog(parent,title,Dialog.ModalityType.APPLICATION_MODAL);
        JPanel content=new JPanel(new BorderLayout(0,18)); content.setBorder(padding(16));
        content.add(body,BorderLayout.CENTER);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));
        if (cancel) actions.add(button("Cancel",KeyEvent.VK_C,dialog::dispose));
        JButton ok=button(accept,KeyEvent.VK_O,() -> { onAccept.run(); dialog.dispose(); });
        actions.add(ok); content.add(actions,BorderLayout.SOUTH); dialog.setContentPane(content);
        decorate(dialog,dialog::dispose); dialog.getRootPane().setDefaultButton(ok);
        dialog.pack();
        Rectangle screen=(parent==null ? GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration()
                : parent.getGraphicsConfiguration()).getBounds();
        dialog.setSize(Math.min(screen.width-40,Math.max(360,dialog.getWidth())),Math.min(screen.height-40,dialog.getHeight()));
        dialog.setLocationRelativeTo(parent);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) {
                Component initial=firstInput(body);
                (initial==null ? ok : initial).requestFocusInWindow();
            }
        });
        return dialog;
    }
    static void showDialog(JDialog dialog) {
        Component focus=KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        dialog.setVisible(true);
        if (focus!=null && focus.isShowing()) focus.requestFocusInWindow();
    }
    public static boolean confirm(Component owner,String message,String title) {
        return form(owner,title,messageBody(message),"OK");
    }
    private static JComponent messageBody(String text) {
        JTextArea message=new JTextArea(text);
        message.setEditable(false); message.setFocusable(false); message.setOpaque(false);
        message.setBorder(BorderFactory.createEmptyBorder());
        message.setLineWrap(true); message.setWrapStyleWord(true);
        int columns=Math.min(62,Math.max(30,text.lines().mapToInt(String::length).max().orElse(30)));
        message.setColumns(columns);
        message.setRows(Math.min(20,text.lines().mapToInt(line -> Math.max(1,(line.length()+columns-1)/columns)).sum()));
        JScrollPane scroll=new JScrollPane(message);
        scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        return scroll;
    }
    private static Component firstInput(Component component) {
        if (component.isEnabled() && (component instanceof javax.swing.text.JTextComponent text && text.isEditable()
                || component instanceof JComboBox<?> || component instanceof AbstractButton)) return component;
        if (component instanceof Container parent) {
            for (Component child : parent.getComponents()) {
                Component input=firstInput(child); if (input!=null) return input;
            }
        }
        return null;
    }
    public static void message(Component owner,String message,String title) {
        prompt(owner,title,messageBody(message),"Close",false);
    }
    public static String input(Component owner,String title,String message) {
        JPanel form=new JPanel(new BorderLayout(0,10));
        JTextField field=new JTextField(30);
        JLabel label=new JLabel(message); label.setLabelFor(field);
        form.add(label,BorderLayout.NORTH); form.add(field,BorderLayout.CENTER);
        return form(owner,title,form,"Send") ? field.getText() : null;
    }
    public static void error(Component owner,String message) { message(owner,message,"Fish"); }
    private static final class FlatPopups extends PopupFactory {
        @Override public Popup getPopup(Component owner,Component contents,int x,int y) {
            Popup popup=super.getPopup(owner,contents,x,y);
            Window window=SwingUtilities.getWindowAncestor(contents);
            Window parent=owner instanceof Window w ? w : owner==null ? null : SwingUtilities.getWindowAncestor(owner);
            if (window!=null && window!=parent && window.getType()==Window.Type.POPUP) {
                if (window instanceof RootPaneContainer root) {
                    root.getRootPane().setBorder(BorderFactory.createEmptyBorder());
                    background(root.getRootPane(),contents instanceof JToolTip ? ColorRole.TOOLTIP : ColorRole.MENU);
                }
                if (!window.isDisplayable()) window.addNotify();
                NativeWindow.flatten(window);
            }
            return popup;
        }
    }
}
