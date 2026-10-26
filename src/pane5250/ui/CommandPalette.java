package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.*;

public final class CommandPalette extends JDialog {
    private final JTextField search=new JTextField();
    private final DefaultListModel<AppCommand> results=new DefaultListModel<>();
    private final JList<AppCommand> list=new JList<>(results);
    private final JLabel status=new JLabel();
    private AppCommand chosen;
    public CommandPalette(Workspace workspace) {
        super(workspace,"Commands",true);
        JPanel content=new JPanel(new BorderLayout(0,10)); content.setBorder(Ui.padding(12));
        search.getAccessibleContext().setAccessibleName("Search commands");
        search.setToolTipText("Search actions, descriptions or aliases, e.g. add window, reset, theme");
        content.add(search,BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.setFixedCellHeight(48);
        list.getAccessibleContext().setAccessibleName("Matching commands");
        list.setCellRenderer((values,command,index,selected,focused) -> {
            JPanel row=new JPanel(new BorderLayout(12,0)); row.setBorder(BorderFactory.createEmptyBorder(5,10,5,10));
            row.setBackground(selected ? Ui.SELECTION : Ui.FIELD);
            JPanel labels=new JPanel(new GridLayout(2,1)); labels.setOpaque(false);
            JLabel name=new JLabel(command.title), detail=new JLabel(command.description), keys=new JLabel(command.shortcut);
            name.setForeground(command.isEnabled() ? Ui.TEXT : Ui.MUTED);
            detail.setForeground(Ui.MUTED); keys.setForeground(Ui.MUTED);
            detail.setFont(detail.getFont().deriveFont(11f)); keys.setFont(keys.getFont().deriveFont(11f));
            labels.add(name); labels.add(detail); row.add(labels,BorderLayout.CENTER); row.add(keys,BorderLayout.EAST);
            return row;
        });
        content.add(new JScrollPane(list),BorderLayout.CENTER);
        Ui.role(status,"muted"); content.add(status,BorderLayout.SOUTH);
        setContentPane(content); Ui.decorate(this,this::dispose);
        search.getDocument().addDocumentListener(new DocumentListener() {
            private void update() {
                results.clear();
                for (AppCommand command : workspace.commands()) {
                    command.refresh();
                    if (command.matches(search.getText())) results.addElement(command);
                }
                if (!results.isEmpty()) list.setSelectedIndex(0);
                status.setText(results.isEmpty() ? "No matching commands. Try pane, connect, reset or theme."
                        : "Up/Down to choose   Enter to run   Esc to return");
            }
            @Override public void insertUpdate(DocumentEvent e) { update(); }
            @Override public void removeUpdate(DocumentEvent e) { update(); }
            @Override public void changedUpdate(DocumentEvent e) { update(); }
        });
        for (String key : new String[]{"UP","DOWN"}) {
            search.getInputMap().put(KeyStroke.getKeyStroke(key),key);
            search.getActionMap().put(key,new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    if (!results.isEmpty()) {
                        list.setSelectedIndex(Math.max(0,Math.min(results.size()-1,list.getSelectedIndex()+(key.equals("UP") ? -1 : 1))));
                        list.ensureIndexIsVisible(list.getSelectedIndex());
                    }
                }
            });
        }
        search.addActionListener(e -> choose());
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"run");
        list.getActionMap().put("run",new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { choose(); } });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount()==2) choose(); }
        });
        addWindowListener(new WindowAdapter() { @Override public void windowOpened(WindowEvent e) { search.requestFocusInWindow(); } });
        search.setText(" "); search.setText("");
        setSize(640,440); setLocationRelativeTo(workspace);
    }
    private void choose() {
        AppCommand command=list.getSelectedValue();
        if (command==null) return;
        command.refresh();
        if (!command.isEnabled()) { status.setText("This action is unavailable for the current session."); return; }
        chosen=command; dispose();
    }
    public AppCommand chosen() { return chosen; }
}
