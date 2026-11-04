package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import pane5250.model.*;

/** Draft-only editor shared by Preferences and the Keyboard shortcut route. */
final class KeyboardEditor extends JPanel {
    private final Map<String,String> values=new LinkedHashMap<>();
    private final List<String> actions=new ArrayList<>(KeyBindings.defaultsByAction().keySet().stream()
            .filter(action -> !Shortcuts.SETTINGS_ALIASES.contains(action)).toList());
    private final DefaultTableModel rows=new DefaultTableModel(new String[]{"Scope","Action","Effective keys"},0) {
        @Override public boolean isCellEditable(int row,int column) { return false; }
    };
    private final JTable table=new JTable(rows);
    private final JTextArea keys=new JTextArea(3,34);
    private final JLabel status=new JLabel(" ");
    private final JTextField search=new JTextField();
    private JButton record;
    private boolean loading;
    KeyboardEditor(Workspace workspace) {
        super(new BorderLayout(0,10));
        values.putAll(workspace.shortcutOverrides());
        for (String action:actions) rows.addRow(new Object[]{action.equals(KeyBindings.UNBOUND) ? "Terminal"
                : KeyBindings.command(action).host()==null ? "Fish" : "Terminal",action,summary(text(action))});
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setRowHeight(27);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(65);
        table.getColumnModel().getColumn(1).setPreferredWidth(200);
        table.getColumnModel().getColumn(2).setPreferredWidth(260);
        table.getAccessibleContext().setAccessibleName("Application-wide Fish and host key actions");
        TableRowSorter<DefaultTableModel> sorter=new TableRowSorter<>(rows); table.setRowSorter(sorter);
        search.getAccessibleContext().setAccessibleName("Find keyboard action");
        search.getDocument().addDocumentListener(listener(() -> {
            String query=search.getText().strip().toLowerCase(Locale.ROOT);
            sorter.setRowFilter(new RowFilter<>() {
                @Override public boolean include(Entry<? extends DefaultTableModel,? extends Integer> row) {
                    return (row.getStringValue(0)+" "+row.getStringValue(1)+" "+row.getStringValue(2)).toLowerCase(Locale.ROOT).contains(query);
                }
            });
            if (!query.isEmpty()) workspace.tutorialAction("keyboard-search");
        }));
        JPanel top=new JPanel(new BorderLayout(8,8));
        JLabel find=new JLabel("Find action"); find.setLabelFor(search);
        top.add(find,BorderLayout.WEST); top.add(search,BorderLayout.CENTER);
        if (workspace.keyboardRecovery().containsKey("report")) {
            JLabel conflict=new JLabel("Migration retained conflicting old definitions. Review them below.");
            Ui.role(conflict,"muted"); top.add(conflict,BorderLayout.SOUTH);
        }
        add(top,BorderLayout.NORTH); add(new JScrollPane(table),BorderLayout.CENTER);
        keys.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));
        keys.getAccessibleContext().setAccessibleName("Exact key chords, one per line");
        keys.getDocument().addDocumentListener(listener(() -> {
            if (loading || selected()==null) return;
            values.put(selected(),keys.getText().strip());
            refreshRows(); describe();
            try { KeyBindings.effective(values); if (!keys.getText().isBlank()) workspace.tutorialAction("keyboard-binding"); }
            catch (IllegalArgumentException e) { /* The visible validation message keeps this step actionable. */ }
        }));
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) { load(); if (selected()!=null) workspace.tutorialAction("keyboard-action"); }
        });
        JPanel editor=new JPanel(new BorderLayout(0,8));
        JLabel label=new JLabel("Bindings (one chord per line; your list replaces the defaults)"); label.setLabelFor(keys);
        editor.add(label,BorderLayout.NORTH); editor.add(new JScrollPane(keys),BorderLayout.CENTER);
        JPanel controls=new JPanel(new GridLayout(2,1,0,6));
        JPanel primary=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0)),secondary=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
        record=Ui.button("Record and add...",KeyEvent.VK_R,() -> {
            if (selected()!=null) Ui.showDialog(KeyRecorder.dialog(SwingUtilities.getWindowAncestor(this),chord -> {
                String existing=keys.getText().strip();
                keys.setText(existing.isEmpty() ? chord.toString() : existing+"\n"+chord);
            }));
        }); primary.add(record);
        primary.add(Ui.button("Remove line",0,() -> {
            if (selected()==null || keys.getText().isEmpty()) return;
            try {
                int line=keys.getLineOfOffset(keys.getCaretPosition());
                List<String> lines=new ArrayList<>(keys.getText().lines().toList()); lines.remove(Math.min(line,lines.size()-1));
                keys.setText(String.join("\n",lines));
            } catch (javax.swing.text.BadLocationException e) { throw new IllegalStateException(e); }
        }));
        primary.add(Ui.button("Unbind action",0,() -> { if (selected()!=null) keys.setText(""); }));
        secondary.add(Ui.button("Restore default",0,() -> {
            if (selected()==null) return;
            values.remove(selected()); load(); refreshRows();
        }));
        secondary.add(Ui.button("Migrated definitions...",0,() ->
                Ui.message(this,KeyboardMigration.report(workspace.keyboardRecovery()),"Keyboard migration")));
        controls.add(primary); controls.add(secondary);
        JPanel bottom=new JPanel(new BorderLayout(0,8));
        bottom.add(controls,BorderLayout.NORTH);
        JPanel help=new JPanel(new GridLayout(0,1,0,4));
        help.add(new JLabel("ENTER: main. ENTER@numpad: keypad. ctrl / shift / alt / altGraph."));
        help.add(new JLabel("Custom keys replace defaults; duplicate custom owners conflict."));
        status.getAccessibleContext().setAccessibleName("Keyboard validation and default replacements");
        help.add(status); bottom.add(help,BorderLayout.SOUTH); editor.add(bottom,BorderLayout.SOUTH);
        add(editor,BorderLayout.SOUTH);
        refreshRows();
        if (table.getRowCount()>0) table.setRowSelectionInterval(0,0);
    }
    private void refreshRows() {
        try {
            Map<KeyChord,String> effective=KeyBindings.effective(values);
            for (int n=0;n<actions.size();n++) {
                String action=actions.get(n);
                String actual=action.equals(KeyBindings.UNBOUND) ? values.getOrDefault(action,"")
                        : KeyChord.text(KeyChord.list(text(action)).stream()
                                .filter(key -> action.equals(effective.get(key))).toList());
                rows.setValueAt(summary(actual),n,2);
            }
        } catch (IllegalArgumentException e) {
            if (selected()!=null) rows.setValueAt("Invalid draft",actions.indexOf(selected()),2);
        }
    }
    private static DocumentListener listener(Runnable run) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { run.run(); }
            @Override public void removeUpdate(DocumentEvent e) { run.run(); }
            @Override public void changedUpdate(DocumentEvent e) { run.run(); }
        };
    }
    private String selected() { return table.getSelectedRow()<0 ? null : actions.get(table.convertRowIndexToModel(table.getSelectedRow())); }
    private String text(String action) { return values.getOrDefault(action,KeyBindings.defaultText(action)); }
    private static String summary(String text) {
        try { return text.isBlank() ? "Unbound" : AppCommand.describeStroke(text); }
        catch (IllegalArgumentException e) { return text.replace('\n',';'); }
    }
    private void load() {
        loading=true;
        try { keys.setText(selected()==null ? "" : text(selected())); keys.setEnabled(selected()!=null); keys.setCaretPosition(0); }
        finally { loading=false; }
        describe();
    }
    private void describe() {
        try {
            KeyBindings.effective(values);
            List<String> claims=KeyBindings.defaultClaims(values);
            status.setText(claims.isEmpty() ? " " : "Default replacements: "+claims.size()+" (hover for details)");
            status.setToolTipText(claims.isEmpty() ? null : String.join("; ",claims));
            Ui.role(status,"muted");
        } catch (IllegalArgumentException e) { status.setText(e.getMessage()); status.setToolTipText(e.getMessage()); Ui.role(status,"error"); }
    }
    Map<String,String> draft() { KeyBindings.effective(values); return KeyBindings.normalize(values); }
    Component tutorialTarget(String control) { return switch(control) { case "search" -> search; case "keys" -> keys; case "record" -> record; default -> table; }; }
    boolean dirty(Map<String,String> saved) {
        try { return !draft().equals(saved); }
        catch (IllegalArgumentException e) { return true; }
    }
}
