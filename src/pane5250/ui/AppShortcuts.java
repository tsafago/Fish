package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.event.*;
import javax.swing.text.JTextComponent;
import org.tn5250j.keyboard.KeyMnemonic;
import pane5250.model.Profile;
import pane5250.model.Shortcuts;

/** Window-scoped dispatch precedes Swing traversal and the host's key listener. */
final class AppShortcuts implements KeyEventDispatcher {
    private final Workspace workspace;
    private final List<AppCommand> commands;
    private Map<String,String> overrides=Map.of();
    private Map<KeyStroke,AppCommand> bindings=Map.of();
    private final Set<Integer> claimed=new HashSet<>();
    private final Set<KeyStroke> retired=new HashSet<>();

    AppShortcuts(Workspace workspace,List<AppCommand> commands,Map<String,String> saved) {
        this.workspace=workspace; this.commands=commands;
        for (String stroke:workspace.appearanceSettings().retiredShortcuts()) {
            KeyStroke key=KeyStroke.getKeyStroke(stroke);
            if (key==null) throw new IllegalArgumentException("Invalid retired shortcut: "+stroke);
            retired.add(key);
        }
        apply(saved,workspace.profileList());
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
        workspace.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) { claimed.clear(); }
        });
    }
    Map<String,String> overrides() { return overrides; }
    Set<KeyMnemonic> reassignedHostActions() {
        Set<KeyMnemonic> result=new HashSet<>();
        for (AppCommand command:commands)
            if (command.hostKey!=null && overrides.containsKey(command.title)) result.add(command.hostKey);
        return Set.copyOf(result);
    }
    static String defaultStroke(AppCommand command) {
        String value=Shortcuts.defaultStroke(command.title);
        return value.isBlank() ? "" : KeyStroke.getKeyStroke(value).toString();
    }
    void validate(Map<String,String> values,List<Profile> profiles) {
        Shortcuts.validate(values,profiles);
    }
    private static KeyStroke stroke(String text) {
        return Shortcuts.stroke(text);
    }
    void apply(Map<String,String> values,List<Profile> profiles) {
        validate(values,profiles);
        Map<KeyStroke,AppCommand> next=new HashMap<>();
        for (AppCommand command:commands) {
            String value=values.getOrDefault(command.title,defaultStroke(command));
            command.setStroke(value);
            if ((command.hostKey==null || values.containsKey(command.title)) && !value.isBlank())
                next.put(stroke(value),command);
        }
        Shortcuts.aliasBindings(values,profiles,workspace.appearanceSettings().retiredShortcuts()).forEach((key,title) ->
                next.put(key,commands.stream().filter(c -> c.title.equals(title)).findFirst().orElseThrow()));
        overrides=Map.copyOf(values); bindings=Map.copyOf(next); claimed.clear();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.isAltGraphDown()) return false;
        Component source=event.getComponent();
        Window owner=source instanceof Window w ? w : SwingUtilities.getWindowAncestor(source);
        if (owner!=workspace) return false;
        if (event.getID()==KeyEvent.KEY_RELEASED) {
            if (!claimed.remove(event.getKeyCode())) return false;
        } else if (event.getID()==KeyEvent.KEY_TYPED) {
            if (claimed.isEmpty()) return false;
        } else if (event.getID()==KeyEvent.KEY_PRESSED) {
            if (source instanceof TerminalView && event.getKeyCode()==KeyEvent.VK_ENTER && event.getModifiersEx()==0
                    && workspace.active().recoverableInputError()) {
                claimed.add(event.getKeyCode());
                workspace.active().send(KeyMnemonic.RESET);
                event.consume(); return true;
            }
            AppCommand command=bindings.get(KeyStroke.getKeyStrokeForEvent(event));
            if (command==null && !retired.contains(KeyStroke.getKeyStrokeForEvent(event))) return false;
            if (source instanceof JTextComponent && command!=null
                    && List.of("Copy selection / screen","Paste into fields").contains(command.title)) return false;
            claimed.add(event.getKeyCode());
            if (command!=null) command.actionPerformed(new ActionEvent(workspace,ActionEvent.ACTION_PERFORMED,"shortcut"));
        } else return false;
        event.consume(); return true;
    }
    void dispose() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);
        claimed.clear();
    }
    void show() {
        Ui.showDialog(dialog());
    }
    JDialog dialog() {
        Set<String> explicit=new HashSet<>(overrides.keySet());
        boolean[] restoring={false};
        DefaultTableModel model=new DefaultTableModel(new String[]{"Action","Shortcut","Default"},0) {
            @Override public boolean isCellEditable(int row,int column) { return column==1; }
        };
        for (AppCommand c:commands) model.addRow(new Object[]{c.title,
                overrides.getOrDefault(c.title,defaultStroke(c)),defaultStroke(c)});
        model.addTableModelListener(e -> {
            if (!restoring[0] && e.getType()==TableModelEvent.UPDATE && e.getColumn()==1)
                for (int row=e.getFirstRow();row<=e.getLastRow();row++) explicit.add((String)model.getValueAt(row,0));
        });
        JTable table=new JTable(model);
        table.setRowHeight(29); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getAccessibleContext().setAccessibleName("All Fish actions and global shortcut overrides");
        table.setPreferredScrollableViewportSize(new Dimension(710,360));
        DefaultTableCellRenderer keys=new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable owner,Object value,boolean selected,boolean focus,int row,int column) {
                super.getTableCellRendererComponent(owner,value,selected,focus,row,column);
                String label=AppCommand.describeStroke(Objects.toString(value,""));
                setText(label.isEmpty() ? "Not assigned" : label);
                return this;
            }
        };
        table.getColumnModel().getColumn(1).setCellRenderer(keys);
        table.getColumnModel().getColumn(2).setCellRenderer(keys);
        TableRowSorter<DefaultTableModel> sorter=new TableRowSorter<>(model); table.setRowSorter(sorter);
        JTextField search=new JTextField();
        search.getAccessibleContext().setAccessibleName("Find shortcut action");
        search.getDocument().addDocumentListener(new DocumentListener() {
            private void update() {
                sorter.setRowFilter(new RowFilter<>() {
                    @Override public boolean include(Entry<? extends DefaultTableModel,? extends Integer> row) {
                        return commands.get(row.getIdentifier()).matches(search.getText());
                    }
                });
            }
            @Override public void insertUpdate(DocumentEvent e) { update(); }
            @Override public void removeUpdate(DocumentEvent e) { update(); }
            @Override public void changedUpdate(DocumentEvent e) { update(); }
        });
        JPanel header=new JPanel(new BorderLayout(8,0));
        JLabel label=new JLabel("Find action"); label.setLabelFor(search);
        header.add(label,BorderLayout.WEST); header.add(search,BorderLayout.CENTER);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));
        actions.add(Ui.button("Record shortcut",KeyEvent.VK_R,() -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            int row=table.getSelectedRow(); if (row<0) return;
            JTextField capture=new JTextField("Press the shortcut",30);
            capture.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,Set.of(KeyStroke.getKeyStroke("TAB")));
            capture.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,Set.of(KeyStroke.getKeyStroke("shift TAB")));
            String[] value={null};
            capture.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (List.of(KeyEvent.VK_CONTROL,KeyEvent.VK_ALT,KeyEvent.VK_SHIFT,KeyEvent.VK_META).contains(e.getKeyCode())) return;
                    value[0]=KeyStroke.getKeyStrokeForEvent(e).toString(); capture.setText(AppCommand.describeStroke(value[0])); e.consume();
                }
                @Override public void keyTyped(KeyEvent e) { e.consume(); }
            });
            if (Ui.form(table,"Record shortcut",capture,"Use shortcut") && value[0]!=null)
                model.setValueAt(value[0],table.convertRowIndexToModel(row),1);
        }));
        actions.add(Ui.button("Unbind",KeyEvent.VK_U,() -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            if (table.getSelectedRow()>=0) model.setValueAt("",table.convertRowIndexToModel(table.getSelectedRow()),1);
        }));
        actions.add(Ui.button("Restore default",KeyEvent.VK_D,() -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            if (table.getSelectedRow()>=0) {
                int row=table.convertRowIndexToModel(table.getSelectedRow());
                explicit.remove((String)model.getValueAt(row,0));
                restoring[0]=true;
                try { model.setValueAt(model.getValueAt(row,2),row,1); }
                finally { restoring[0]=false; }
            }
        }));
        JPanel bottom=new JPanel(new BorderLayout(0,10));
        bottom.add(actions,BorderLayout.NORTH);
        bottom.add(new JLabel("<html>Global within Fish, not system-wide. Blank means unbound.<br>"
                +"Conflicting host keys are never silently replaced. Recording Escape does not send Attention.</html>"),BorderLayout.SOUTH);
        JPanel body=new JPanel(new BorderLayout(0,12));
        body.add(header,BorderLayout.NORTH); body.add(new JScrollPane(table),BorderLayout.CENTER); body.add(bottom,BorderLayout.SOUTH);
        JLabel error=new JLabel(" "); Ui.role(error,"error");
        bottom.add(error,BorderLayout.CENTER);
        java.util.function.Supplier<Map<String,String>> draft=() -> {
            Map<String,String> values=new LinkedHashMap<>();
            for (int row=0;row<model.getRowCount();row++) {
                String value=Objects.toString(model.getValueAt(row,1),"").strip();
                if (!value.isEmpty()) value=stroke(value).toString();
                String action=(String)model.getValueAt(row,0);
                if (explicit.contains(action) || !value.equals(model.getValueAt(row,2))) values.put(action,value);
            }
            return Map.copyOf(values);
        };
        java.util.function.BooleanSupplier dirty=() -> {
            if (table.isEditing()) return true;
            try {
                Map<String,String> normalized=new LinkedHashMap<>();
                overrides.forEach((action,value) -> normalized.put(action,value.isBlank() ? "" : stroke(value).toString()));
                return !draft.get().equals(normalized);
            }
            catch (IllegalArgumentException e) { return true; }
        };
        return Ui.settingsDialog(workspace,"Shortcuts - all actions",body,dirty,() -> {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return false;
            try {
                workspace.applyShortcuts(draft.get()); error.setText(" "); error.setToolTipText(null); return true;
            }
            catch (IllegalArgumentException e) { error.setText(e.getMessage()); error.setToolTipText(e.getMessage()); return false; }
        });
    }
}
