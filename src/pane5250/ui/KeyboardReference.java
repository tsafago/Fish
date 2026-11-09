package pane5250.ui;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import pane5250.model.*;

/** Searchable syntax and action reference derived from the live parser and command catalog. */
final class KeyboardReference extends JDialog {
    static final String EXAMPLES="Single: F5 or ENTER.  Ctrl+F5: ctrl F5.  Ctrl+Shift+F5: ctrl shift F5.";
    static final String ALTERNATIVES="Use spaces, not + signs. One alternative per line. Main: ENTER. Keypad: ENTER@numpad.";
    private final JTextField search=new JTextField();
    private final JTabbedPane tabs=new JTabbedPane();
    private final JTable keys,actions;
    private final JTextArea syntax=new JTextArea(3,30);
    private final JButton use;
    private final List<TableRowSorter<DefaultTableModel>> sorters=new ArrayList<>();

    KeyboardReference(Window owner,Consumer<String> chooseAction) {
        super(owner,"Allowed keys and actions",ModalityType.MODELESS);
        JPanel body=new JPanel(new BorderLayout(0,12)); body.setBorder(Ui.padding(16));
        JPanel top=new JPanel(new BorderLayout(8,8));
        JLabel label=new JLabel("Find key or action"); label.setLabelFor(search);
        search.getAccessibleContext().setAccessibleName("Find allowed key or assignable action");
        top.add(label,BorderLayout.WEST); top.add(search,BorderLayout.CENTER);
        JTextArea help=new JTextArea(EXAMPLES+"\n"+ALTERNATIVES+"\n"
                +"Modifiers: ctrl, shift, alt, altGraph. Locations: "+String.join(", ",KeyChord.locations())+".\n"
                +"Windows keys, Ctrl+Escape, Alt+F4 and Alt+Space are reserved. Unmodified typing keys cannot run Fish actions.");
        help.setEditable(false); help.setLineWrap(true); help.setWrapStyleWord(true); help.setOpaque(false);
        help.setBorder(BorderFactory.createEmptyBorder());
        help.getAccessibleContext().setAccessibleName("Binding syntax and restrictions"); top.add(help,BorderLayout.SOUTH);
        body.add(top,BorderLayout.NORTH);
        keys=table("Allowed action keys",new String[]{"Key","Type this token"},
                KeyChord.supportedKeys().stream().map(s -> new Object[]{KeyChord.parse(s).label(),s}).toList());
        actions=table("Assignable actions",new String[]{"Scope","Action","Default entry"},
                KeyBindings.defaultsByAction().entrySet().stream().filter(e -> !Shortcuts.SETTINGS_ALIASES.contains(e.getKey()))
                        .map(e -> new Object[]{e.getKey().equals(KeyBindings.UNBOUND) ? "Terminal"
                                : KeyBindings.command(e.getKey()).host()==null ? "Fish" : "Terminal",
                                e.getKey(),KeyChord.text(e.getValue()).replace("\n"," ; ")}).toList());
        actions.getColumnModel().getColumn(0).setPreferredWidth(65);
        actions.getColumnModel().getColumn(1).setPreferredWidth(240);
        actions.getColumnModel().getColumn(2).setPreferredWidth(340);
        tabs.addTab("Keys",scroll(keys)); tabs.addTab("Actions",scroll(actions));
        tabs.getAccessibleContext().setAccessibleName("Allowed keys and assignable actions");
        body.add(tabs,BorderLayout.CENTER);
        syntax.setEditable(false); syntax.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));
        syntax.getAccessibleContext().setAccessibleName("Selected key token or action default bindings, one per line");
        JPanel bottom=new JPanel(new BorderLayout(8,8)); bottom.add(new JScrollPane(syntax),BorderLayout.CENTER);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));
        use=Ui.button("Edit selected action",KeyEvent.VK_E,() -> {
            if (actions.getSelectedRow()<0) return;
            chooseAction.accept(actions.getValueAt(actions.getSelectedRow(),1).toString()); dispose();
        }); use.setEnabled(false); buttons.add(use);
        buttons.add(Ui.button("Done",KeyEvent.VK_D,this::dispose)); bottom.add(buttons,BorderLayout.SOUTH);
        body.add(bottom,BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                String query=search.getText().strip().toLowerCase(Locale.ROOT);
                for (TableRowSorter<DefaultTableModel> sorter:sorters) sorter.setRowFilter(new RowFilter<>() {
                    @Override public boolean include(Entry<? extends DefaultTableModel,? extends Integer> row) {
                        for (int n=0;n<row.getValueCount();n++) if (row.getStringValue(n).toLowerCase(Locale.ROOT).contains(query)) return true;
                        return false;
                    }
                });
                describe();
            }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        });
        keys.getSelectionModel().addListSelectionListener(e -> describe());
        actions.getSelectionModel().addListSelectionListener(e -> describe());
        tabs.addChangeListener(e -> describe());
        setContentPane(body); Ui.decorate(this,this::dispose); setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().registerKeyboardAction(e -> dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        setMinimumSize(new Dimension(620,480)); setSize(760,580); setLocationRelativeTo(owner);
    }
    private JTable table(String name,String[] columns,List<Object[]> values) {
        DefaultTableModel model=new DefaultTableModel(columns,0) {
            @Override public boolean isCellEditable(int row,int column) { return false; }
        };
        values.forEach(model::addRow);
        JTable table=new JTable(model); table.setRowHeight(27); table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.getAccessibleContext().setAccessibleName(name);
        TableRowSorter<DefaultTableModel> sorter=new TableRowSorter<>(model); table.setRowSorter(sorter); sorters.add(sorter);
        return table;
    }
    private static JScrollPane scroll(JTable table) {
        JScrollPane scroll=new JScrollPane(table); scroll.setColumnHeaderView(table.getTableHeader()); return scroll;
    }
    private void describe() {
        boolean action=tabs.getSelectedIndex()==1;
        use.setEnabled(action && actions.getSelectedRow()>=0);
        JTable current=action ? actions : keys;
        syntax.setText(current.getSelectedRow()<0 ? "Select a row to see its exact entry syntax."
                : action ? KeyBindings.defaultText(current.getValueAt(current.getSelectedRow(),1).toString())
                : current.getValueAt(current.getSelectedRow(),1).toString());
        syntax.setCaretPosition(0);
    }
    void focusSearch() { search.requestFocusInWindow(); }
}
