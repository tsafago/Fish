package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.*;
import pane5250.model.AppTheme;

public final class Ui {
    public static Color BACKGROUND,PANEL,FIELD,HOVER,LINE,TEXT,MUTED,FOCUS,ERROR,SELECTION;
    private static AppTheme theme=AppTheme.Charcoal;
    private Ui() {}
    static { colors(theme); }
    private static Color color(int rgb) { return new ColorUIResource(rgb); }
    private static void colors(AppTheme value) {
        theme=value;
        BACKGROUND=color(value.background); PANEL=color(value.panel); FIELD=color(value.field); HOVER=color(value.hover);
        LINE=color(value.line); TEXT=color(value.text); MUTED=color(value.muted); FOCUS=color(value.focus);
        ERROR=color(value.error); SELECTION=color(value.selection);
    }
    public static AppTheme theme() { return theme; }
    public static void install() throws Exception {
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        applyTheme(AppTheme.Charcoal);
    }
    public static void applyTheme(AppTheme value) {
        colors(value);
        Font font=new FontUIResource("Segoe UI",Font.PLAIN,12);
        for (Object key : java.util.Collections.list(UIManager.getDefaults().keys())) {
            if (key.toString().endsWith(".font")) UIManager.put(key,font);
        }
        for (String name : new String[]{"Panel","MenuBar","Menu","MenuItem","CheckBoxMenuItem","RadioButtonMenuItem",
                "PopupMenu","TabbedPane","OptionPane","Label","Button","CheckBox","RadioButton","ComboBox","List",
                "ScrollPane","Viewport","TextField","PasswordField","TextArea","Spinner","SplitPane","ToolTip"}) {
            UIManager.put(name+".background",PANEL); UIManager.put(name+".foreground",TEXT);
            UIManager.put(name+".disabledForeground",MUTED);
            UIManager.put(name+".disabledText",MUTED);
        }
        for (String name : new String[]{"TextField","PasswordField","TextArea","ComboBox","List"}) {
            UIManager.put(name+".background",FIELD);
            UIManager.put(name+".selectionBackground",SELECTION); UIManager.put(name+".selectionForeground",TEXT);
            UIManager.put(name+".inactiveForeground",MUTED); UIManager.put(name+".caretForeground",FOCUS);
        }
        for (String name : new String[]{"Menu","MenuItem","CheckBoxMenuItem","RadioButtonMenuItem"}) {
            UIManager.put(name+".selectionBackground",HOVER); UIManager.put(name+".selectionForeground",TEXT);
            UIManager.put(name+".acceleratorForeground",MUTED); UIManager.put(name+".acceleratorSelectionForeground",TEXT);
            UIManager.put(name+".border",new BorderUIResource.EmptyBorderUIResource(6,10,6,10));
        }
        UIManager.put("ButtonUI",FlatControls.Button.class.getName());
        UIManager.put("MenuBarUI","javax.swing.plaf.basic.BasicMenuBarUI");
        UIManager.put("MenuUI","javax.swing.plaf.basic.BasicMenuUI");
        UIManager.put("MenuItemUI","javax.swing.plaf.basic.BasicMenuItemUI");
        UIManager.put("CheckBoxMenuItemUI","javax.swing.plaf.basic.BasicCheckBoxMenuItemUI");
        UIManager.put("RadioButtonMenuItemUI","javax.swing.plaf.basic.BasicRadioButtonMenuItemUI");
        UIManager.put("CheckBoxUI","javax.swing.plaf.basic.BasicCheckBoxUI");
        UIManager.put("MenuBar.gradient",null);
        UIManager.put("Menu.arrowIcon",new Symbol("right"));
        UIManager.put("MenuItem.checkIcon",null);
        UIManager.put("CheckBoxMenuItem.checkIcon",new FlatControls.CheckIcon());
        UIManager.put("RadioButtonMenuItem.checkIcon",new FlatControls.CheckIcon());
        UIManager.put("ComboBoxUI",FlatControls.Combo.class.getName());
        UIManager.put("ScrollBarUI",FlatControls.Scroll.class.getName());
        UIManager.put("TabbedPaneUI",FlatControls.Tabs.class.getName());
        UIManager.put("SplitPaneUI",FlatControls.Split.class.getName());
        for (String name : new String[]{"Button","TextField","PasswordField","TextArea","ComboBox","Spinner"})
            UIManager.put(name+".border",new FlatControls.Edge(5,8));
        UIManager.put("CheckBox.icon",new FlatControls.CheckIcon());
        UIManager.put("RadioButton.icon",new FlatControls.CheckIcon());
        UIManager.put("CheckBox.focus",FOCUS); UIManager.put("RadioButton.focus",FOCUS);
        UIManager.put("PopupMenu.border",new BorderUIResource.LineBorderUIResource(LINE));
        UIManager.put("MenuBar.border",new BorderUIResource.EmptyBorderUIResource(0,0,0,0));
        UIManager.put("ScrollPane.border",new BorderUIResource.LineBorderUIResource(LINE));
        UIManager.put("List.focusCellHighlightBorder",new BorderUIResource.LineBorderUIResource(FOCUS));
        UIManager.put("OptionPane.messageForeground",TEXT);
        UIManager.put("Separator.foreground",LINE); UIManager.put("Separator.background",PANEL);
        UIManager.put("SplitPane.border",new BorderUIResource.EmptyBorderUIResource(0,0,0,0));
        UIManager.put("ToolTip.border",new BorderUIResource.LineBorderUIResource(LINE));
        UIManager.put("ScrollBar.width",12);
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
            refresh(window);
            window.repaint();
        }
    }
    private static void refresh(Component component) {
        if (component instanceof JRootPane root) root.setBorder(BorderFactory.createLineBorder(LINE));
        if (component instanceof JComponent c) {
            if ("muted".equals(c.getClientProperty("theme.role"))) c.setForeground(MUTED);
            if ("error".equals(c.getClientProperty("theme.role"))) c.setForeground(ERROR);
        }
        if (component instanceof Workspace w) w.themeChanged();
        if (component instanceof SessionPane p) p.themeChanged();
        if (component instanceof TerminalView v) v.themeChanged();
        if (component instanceof WindowChrome c) c.themeChanged();
        if (component instanceof Container parent)
            for (Component child : parent.getComponents()) refresh(child);
    }
    public static void role(JComponent component,String role) {
        component.putClientProperty("theme.role",role);
        component.setForeground(role.equals("error") ? ERROR : MUTED);
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
    private record Symbol(String kind) implements Icon {
        @Override public int getIconWidth() { return 12; }
        @Override public int getIconHeight() { return 12; }
        @Override public void paintIcon(Component c,Graphics graphics,int x,int y) {
            Graphics2D g=(Graphics2D)graphics.create();
            try {
                g.translate(x,y); g.setColor(c.isEnabled() ? TEXT : MUTED);
                if (kind.equals("close")) { g.drawLine(2,2,10,10); g.drawLine(10,2,2,10); }
                else if (kind.equals("plus")) { g.drawLine(1,6,11,6); g.drawLine(6,1,6,11); }
                else if (kind.equals("search")) { g.drawOval(1,1,7,7); g.drawLine(8,8,11,11); }
                else if (kind.equals("right")) { g.drawLine(4,2,8,6); g.drawLine(8,6,4,10); }
                else { g.drawLine(2,4,6,8); g.drawLine(6,8,10,4); }
            } finally { g.dispose(); }
        }
    }
    public static Border padding(int size) { return BorderFactory.createEmptyBorder(size,size,size,size); }
    public static Color paneBackground(boolean active) {
        if (!active) return BACKGROUND;
        return new Color((BACKGROUND.getRed()*24+FOCUS.getRed())/25,
                (BACKGROUND.getGreen()*24+FOCUS.getGreen())/25,(BACKGROUND.getBlue()*24+FOCUS.getBlue())/25);
    }

    public static void decorate(JDialog dialog,Runnable dismiss) {
        dialog.setUndecorated(true);
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
        dialog.getRootPane().setBorder(BorderFactory.createLineBorder(LINE));
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
    private static boolean prompt(Component owner,String title,JComponent body,String accept,boolean cancel) {
        Window parent=owner instanceof Window w ? w : owner==null
                ? KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow() : SwingUtilities.getWindowAncestor(owner);
        Component focus=KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        JDialog dialog=new JDialog(parent,title,Dialog.ModalityType.APPLICATION_MODAL);
        JPanel content=new JPanel(new BorderLayout(0,18)); content.setBorder(padding(16));
        content.add(body,BorderLayout.CENTER);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));
        boolean[] accepted={false};
        if (cancel) actions.add(button("Cancel",KeyEvent.VK_C,dialog::dispose));
        JButton ok=button(accept,KeyEvent.VK_O,() -> { accepted[0]=true; dialog.dispose(); });
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
        dialog.setVisible(true);
        if (focus!=null && focus.isShowing()) focus.requestFocusInWindow();
        return accepted[0];
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
    public static void error(Component owner,String message) { message(owner,message,"Pane5250"); }
}
