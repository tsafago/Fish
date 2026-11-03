package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.*;
import pane5250.model.*;

/** Draft edits never touch persistence until Save/Apply; previews are reversible. */
public final class ThemeDesigner extends JDialog {
    private final Workspace workspace;
    private final JComboBox<Object> saved=new JComboBox<>();
    private final JTextField name=new JTextField(22);
    private final JLabel status=new JLabel(" ");
    private final DefaultTableModel model=new DefaultTableModel(new String[]{"Area","Color role","#RRGGBB"},0) {
        @Override public boolean isCellEditable(int row,int column) { return column==2; }
    };
    private final JTable table=new JTable(model);
    private final JButton apply,done;
    private ColorTheme applied;
    private boolean loading;

    public ThemeDesigner(Workspace workspace) {
        super(workspace,"Theme designer",true);
        this.workspace=workspace;
        saved.getAccessibleContext().setAccessibleName("Load built-in or saved custom theme");
        saved.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list,Object value,int row,boolean selected,boolean focus) {
                return super.getListCellRendererComponent(list,value instanceof ColorTheme theme ? "Custom: "+theme.name() : value,row,selected,focus);
            }
        });
        name.getAccessibleContext().setAccessibleName("Custom theme name");
        JPanel top=new JPanel(new GridLayout(0,2,12,8));
        Ui.labeled(top,"Load theme",saved); Ui.labeled(top,"Custom theme name",name);
        table.setRowHeight(26); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.putClientProperty("terminateEditOnFocusLost",true);
        table.getAccessibleContext().setAccessibleName("Every semantic color role; edit exact RGB values");
        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable owner,Object value,boolean selected,boolean focus,int row,int column) {
                super.getTableCellRendererComponent(owner,value,selected,focus,row,column);
                try {
                    int rgb=ColorTheme.parse(Objects.toString(value,""));
                    setBackground(new Color(rgb)); setForeground(new Color((rgb>>16&255)*.2126+(rgb>>8&255)*.7152+(rgb&255)*.0722<128 ? 0xffffff : 0));
                } catch (IllegalArgumentException e) { setBackground(Ui.FIELD); setForeground(Ui.ERROR); }
                return this;
            }
        });
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));
        buttons.add(Ui.button("Cancel",KeyEvent.VK_C,this::dismiss));
        buttons.add(Ui.button("Preview",KeyEvent.VK_P,this::preview));
        buttons.add(Ui.button("Save",KeyEvent.VK_S,() -> save(false)));
        apply=Ui.button("Apply",KeyEvent.VK_A,() -> save(true));
        done=Ui.button("Done",KeyEvent.VK_D,() -> { if (!changed() || save(true)) dismiss(); });
        buttons.add(apply); buttons.add(done);
        JPanel bottom=new JPanel(new BorderLayout(0,10)); bottom.add(status); bottom.add(buttons,BorderLayout.SOUTH);
        JPanel content=new JPanel(new BorderLayout(0,12)); content.setBorder(Ui.padding(12));
        content.add(top,BorderLayout.NORTH); content.add(new JScrollPane(table)); content.add(bottom,BorderLayout.SOUTH);
        setContentPane(content); Ui.decorate(this,this::dismiss);
        saved.addActionListener(e -> {
            if (loading) return;
            Object value=saved.getSelectedItem();
            if (value instanceof ColorTheme theme) load(theme);
            else if (value instanceof AppTheme theme) load(ColorTheme.builtin(theme));
        });
        reload();
        EnumMap<ColorRole,Integer> current=new EnumMap<>(Ui.colorTheme().colors());
        Color[] host=HostPalette.colors(workspace.active().profile().theme());
        for (int n=0;n<16;n++) current.put(ColorRole.valueOf("HOST_"+n),host[n].getRGB()&0xffffff);
        load(new ColorTheme(workspace.appearanceSettings().custom().map(ColorTheme::name).orElse("My theme"),current));
        applied=draft();
        Ui.observeEdits(top,this::refreshActions);
        model.addTableModelListener(e -> refreshActions());
        table.addPropertyChangeListener("tableCellEditor",e -> refreshActions());
        refreshActions(); getRootPane().setDefaultButton(done);
        setSize(680,640); setMinimumSize(new Dimension(560,400)); setLocationRelativeTo(workspace);
    }
    private void reload() {
        loading=true;
        saved.removeAllItems(); for (AppTheme theme:AppTheme.values()) saved.addItem(theme);
        workspace.appearanceSettings().themes().forEach(saved::addItem);
        loading=false;
    }
    private void load(ColorTheme theme) {
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        model.setRowCount(0); name.setText(theme.name());
        for (ColorRole role:ColorRole.values())
            model.addRow(new Object[]{role.group,role,String.format("#%06X",theme.rgb(role))});
        refreshActions();
    }
    private boolean changed() {
        if (applied==null || table.isEditing()) return true;
        try {
            ColorTheme value=draft();
            return !value.equals(applied) || workspace.appearanceSettings().themes().contains(value)
                    && !workspace.appearanceSettings().selectedTheme().equals(value.name());
        } catch (IllegalArgumentException e) { return true; }
    }
    private void refreshActions() { if (apply!=null) apply.setEnabled(changed()); }
    public ColorTheme draft() {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) throw new IllegalArgumentException("Finish editing the color first.");
        EnumMap<ColorRole,Integer> values=new EnumMap<>(ColorRole.class);
        for (int row=0;row<model.getRowCount();row++) {
            ColorRole role=(ColorRole)model.getValueAt(row,1);
            try { values.put(role,ColorTheme.parse(Objects.toString(model.getValueAt(row,2),"").strip())); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException(role+": "+e.getMessage(),e); }
        }
        return new ColorTheme(name.getText(),values);
    }
    public void preview() {
        try {
            ColorTheme theme=draft(); Ui.applyTheme(Ui.theme(),theme);
            status.setForeground(Ui.MUTED);
            status.setText(contrastWarning(theme)); status.setToolTipText(null);
        } catch (IllegalArgumentException e) { status.setForeground(Ui.ERROR); status.setText(e.getMessage()); status.setToolTipText(e.getMessage()); }
    }
    private static String contrastWarning(ColorTheme theme) {
        Color text=new Color(theme.rgb(ColorRole.TEXT)),background=new Color(theme.rgb(ColorRole.FIELD));
        double diff=Math.abs(text.getRed()*.2126+text.getGreen()*.7152+text.getBlue()*.0722
                -background.getRed()*.2126-background.getGreen()*.7152-background.getBlue()*.0722);
        return diff<80 ? "Warning: text/field contrast may be low; chosen values are retained." : "";
    }
    public boolean save(boolean activate) {
        try {
            ColorTheme value=draft();
            Appearance current=workspace.appearanceSettings();
            List<ColorTheme> themes=new ArrayList<>(current.themes());
            ColorTheme previous=themes.stream().filter(t -> t.name().equalsIgnoreCase(value.name())).findFirst().orElse(null);
            if (previous!=null && !previous.equals(value) && !Ui.confirm(this,"Replace saved custom theme \""+previous.name()+"\"?","Theme name exists")) return false;
            themes.removeIf(t -> t.name().equalsIgnoreCase(value.name()));
            if (themes.size()>=32) throw new IllegalArgumentException("Up to 32 custom themes are supported. Edit an existing theme or choose another workspace.");
            themes.add(value);
            String selected=activate || previous!=null && current.selectedTheme().equals(previous.name()) ? value.name() : current.selectedTheme();
            workspace.appearanceSettings(current.withTheme(selected,themes));
            reload();
            saved.setSelectedItem(value);
            status.setForeground(Ui.MUTED);
            status.setText(contrastWarning(value)); status.setToolTipText(null);
            if (selected.equals(value.name())) applied=value;
            refreshActions(); return true;
        } catch (IllegalArgumentException e) {
            status.setForeground(Ui.ERROR); status.setText(e.getMessage()); status.setToolTipText(e.getMessage()); return false;
        }
    }
    public void dismiss() {
        Ui.applyTheme(Ui.theme(),workspace.appearanceSettings().custom().orElse(null)); dispose();
    }
}
