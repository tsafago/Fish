package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.event.*;
import org.tn5250j.keyboard.KeyMnemonic;
import pane5250.model.Profile;

/** Window-scoped dispatch precedes Swing traversal and the host's key listener. */
final class AppShortcuts implements KeyEventDispatcher {
    private final Workspace workspace;
    private final List<AppCommand> commands;
    private Map<String,String> overrides=Map.of();
    private Map<KeyStroke,AppCommand> bindings=Map.of();
    private final Set<Integer> claimed=new HashSet<>();

    AppShortcuts(Workspace workspace,List<AppCommand> commands,Map<String,String> saved) {
        this.workspace=workspace; this.commands=commands;
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
        if (command.defaultStroke!=null) return KeyStroke.getKeyStroke(command.defaultStroke).toString();
        if (command.hostKey==null) return "";
        return Keymap.defaultBindings().entrySet().stream().filter(e -> e.getValue()==command.hostKey)
                .map(e -> e.getKey().toString()).min(Comparator.comparingInt(String::length)).orElse("");
    }
    void validate(Map<String,String> values,List<Profile> profiles) {
        Map<String,AppCommand> names=new HashMap<>();
        commands.forEach(c -> names.put(c.title,c));
        Set<KeyMnemonic> reassigned=new HashSet<>();
        for (var entry:values.entrySet()) {
            AppCommand command=names.get(entry.getKey());
            if (command==null) throw new IllegalArgumentException("Unknown shortcut action: "+entry.getKey());
            if (command.hostKey!=null) reassigned.add(command.hostKey);
            stroke(entry.getValue());
        }
        Map<KeyStroke,AppCommand> occupied=new HashMap<>();
        for (AppCommand command:commands) {
            if (command.hostKey!=null && !values.containsKey(command.title)) continue;
            String value=values.getOrDefault(command.title,defaultStroke(command));
            KeyStroke key=stroke(value);
            if (key==null) continue;
            AppCommand previous=occupied.putIfAbsent(key,command);
            if (previous!=null) throw new IllegalArgumentException(key+" is already assigned to "+previous.title+".");
            for (Profile profile:profiles) {
                KeyMnemonic host=new Keymap(profile.keymap(),reassigned).bindings().get(key);
                if (host==null && key.equals(KeyStroke.getKeyStroke("ENTER")) && !reassigned.contains(KeyMnemonic.FIELD_EXIT))
                    host=KeyMnemonic.FIELD_EXIT;
                if (host!=null) throw new IllegalArgumentException(key+" is used by host "+host+" in "+profile.name()
                        +". Unbind or reassign that host action first.");
            }
        }
    }
    private static KeyStroke stroke(String text) {
        if (text.isBlank()) return null;
        KeyStroke key=KeyStroke.getKeyStroke(text.strip());
        if (key==null || key.getKeyCode()==0 || key.isOnKeyRelease())
            throw new IllegalArgumentException("Invalid shortcut: "+text+". Use Record shortcut or a value such as ctrl TAB.");
        if ((key.getModifiers()&InputEvent.META_DOWN_MASK)!=0
                || key.equals(KeyStroke.getKeyStroke("alt F4")) || key.equals(KeyStroke.getKeyStroke("alt SPACE")))
            throw new IllegalArgumentException("Windows keys, Alt+F4 and Alt+Space belong to the operating system.");
        int code=key.getKeyCode();
        if (List.of(KeyEvent.VK_CONTROL,KeyEvent.VK_SHIFT,KeyEvent.VK_ALT,KeyEvent.VK_META,KeyEvent.VK_ALT_GRAPH).contains(code))
            throw new IllegalArgumentException("Record an action key as well as a modifier.");
        if (key.getModifiers()==0 && (code>=KeyEvent.VK_A && code<=KeyEvent.VK_Z
                || code>=KeyEvent.VK_0 && code<=KeyEvent.VK_9 || code>=KeyEvent.VK_NUMPAD0 && code<=KeyEvent.VK_NUMPAD9
                || List.of(KeyEvent.VK_SPACE,KeyEvent.VK_COMMA,KeyEvent.VK_PERIOD,KeyEvent.VK_MINUS,KeyEvent.VK_EQUALS,
                        KeyEvent.VK_SLASH,KeyEvent.VK_BACK_SLASH,KeyEvent.VK_SEMICOLON,KeyEvent.VK_QUOTE,
                        KeyEvent.VK_OPEN_BRACKET,KeyEvent.VK_CLOSE_BRACKET,KeyEvent.VK_BACK_QUOTE).contains(code)))
            throw new IllegalArgumentException("Unmodified typing keys cannot be application shortcuts.");
        return key;
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
            AppCommand command=bindings.get(KeyStroke.getKeyStrokeForEvent(event));
            if (command==null) return false;
            claimed.add(event.getKeyCode());
            command.actionPerformed(new ActionEvent(workspace,ActionEvent.ACTION_PERFORMED,"shortcut"));
        } else return false;
        event.consume(); return true;
    }
    void dispose() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);
        claimed.clear();
    }
    void show() {
        DefaultTableModel model=new DefaultTableModel(new String[]{"Action","Shortcut","Default"},0) {
            @Override public boolean isCellEditable(int row,int column) { return column==1; }
        };
        for (AppCommand c:commands) model.addRow(new Object[]{c.title,
                overrides.getOrDefault(c.title,defaultStroke(c)),defaultStroke(c)});
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
            capture.setFocusTraversalKeysEnabled(false);
            String[] value={null};
            capture.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (List.of(KeyEvent.VK_CONTROL,KeyEvent.VK_ALT,KeyEvent.VK_SHIFT,KeyEvent.VK_META,KeyEvent.VK_ESCAPE).contains(e.getKeyCode())) return;
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
                int row=table.convertRowIndexToModel(table.getSelectedRow()); model.setValueAt(model.getValueAt(row,2),row,1);
            }
        }));
        JPanel bottom=new JPanel(new BorderLayout(0,10));
        bottom.add(actions,BorderLayout.NORTH);
        bottom.add(new JLabel("<html>Global within Fish, not system-wide. Blank means unbound.<br>"
                +"For Ctrl+Tab cycling, select Next pane and record Ctrl+Tab. Conflicting host keys are never silently replaced.</html>"),BorderLayout.SOUTH);
        JPanel body=new JPanel(new BorderLayout(0,12));
        body.add(header,BorderLayout.NORTH); body.add(new JScrollPane(table),BorderLayout.CENTER); body.add(bottom,BorderLayout.SOUTH);
        while (Ui.form(workspace,"Shortcuts - all actions",body,"Save shortcuts")) {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            try {
                Map<String,String> values=new LinkedHashMap<>();
                for (int row=0;row<model.getRowCount();row++) {
                    String value=Objects.toString(model.getValueAt(row,1),"").strip();
                    if (!value.isEmpty()) {
                        KeyStroke parsed=stroke(value); value=parsed.toString();
                    }
                    if (!value.equals(model.getValueAt(row,2))) values.put((String)model.getValueAt(row,0),value);
                }
                workspace.applyShortcuts(values); break;
            }
            catch (IllegalArgumentException e) { Ui.error(workspace,e.getMessage()); }
        }
    }
}
