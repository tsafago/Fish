package pane5250.ui;

import java.awt.*;
import java.nio.file.Path;
import java.util.Objects;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import pane5250.LayoutShortcuts;
import pane5250.model.SavedLayout;

/** Modeless, explicit snapshot management; selecting a recipe never opens or connects it. */
final class SavedLayoutsDialog extends JDialog {
    private final Workspace workspace;
    private final JComboBox<SavedLayout> layouts=new JComboBox<>();
    private final JTextField name=new JTextField(24);
    private final JLabel message=new JLabel(" ");
    private final JButton replace,rename,delete,open,export;
    private boolean refreshing,working;

    SavedLayoutsDialog(Workspace workspace) {
        super(workspace,"Saved layouts / shortcuts",false);
        this.workspace=workspace;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel body=new JPanel(new BorderLayout(0,14)); body.setBorder(BorderFactory.createEmptyBorder(18,18,14,18));
        JPanel fields=new JPanel(new GridLayout(0,2,12,10));
        JLabel savedLabel=new JLabel("Saved layout"); savedLabel.setLabelFor(layouts);
        JLabel nameLabel=new JLabel("Name"); nameLabel.setLabelFor(name);
        fields.add(savedLabel); fields.add(layouts); fields.add(nameLabel); fields.add(name);
        layouts.getAccessibleContext().setAccessibleName("Saved layout");
        name.getAccessibleContext().setAccessibleName("Layout name");
        Font nameFont=name.getFont();
        Ui.observeEdits(name,() -> name.setFont(Ui.textFont(nameFont,name.getText())));
        name.setToolTipText("1-80 characters; renaming keeps existing shortcuts working");
        JPanel top=new JPanel(new BorderLayout(0,12));
        top.add(new JLabel("Save this window's pane arrangement and profiles, not its screen contents."),BorderLayout.NORTH);
        top.add(fields,BorderLayout.CENTER); body.add(top,BorderLayout.NORTH);
        JPanel actions=new JPanel(new GridLayout(2,3,8,8));
        actions.add(Ui.button("Save current as new",0,() -> act(() -> {
            SavedLayout value=workspace.saveCurrentLayout(null,name.getText()); refresh();
            layouts.setSelectedItem(value); showMessage("Layout saved. Create a shortcut, or open a new window.");
        })));
        replace=Ui.button("Update from current",0,() -> act(() -> {
            SavedLayout selected=selected();
            if (Ui.confirm(this,"Replace \""+selected.name()+"\" with this window's current layout?\nExisting shortcuts will open the updated layout.","Update saved layout")) {
                workspace.saveCurrentLayout(selected.id(),selected.name()); refresh(); showMessage("Layout updated; existing shortcuts keep working.");
            }
        })); actions.add(replace);
        rename=Ui.button("Rename",0,() -> act(() -> {
            workspace.renameSavedLayout(selected().id(),name.getText()); refresh(); showMessage("Renamed; existing shortcuts keep working.");
        })); actions.add(rename);
        open=Ui.button("Open new window",0,() -> act(() -> {
            if (!workspace.openSavedLayout(selected().id())) throw new IllegalArgumentException("Fish is saving or closing; try again shortly.");
            showMessage("Opened a new window with independent connections.");
        })); actions.add(open);
        export=Ui.button("Create shortcut...",0,this::chooseShortcut); actions.add(export);
        delete=Ui.button("Delete",0,() -> act(() -> {
            SavedLayout selected=selected();
            if (Ui.confirm(this,"Delete \""+selected.name()+"\"?\nExported shortcut files are not removed, but will report this layout as unavailable.","Delete saved layout")) {
                workspace.deleteSavedLayout(selected.id()); refresh(); showMessage("Layout deleted. No exported shortcut files were removed.");
            }
        })); actions.add(delete); body.add(actions,BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout(12,10)); Ui.role(message,"muted"); bottom.add(message,BorderLayout.CENTER);
        bottom.add(Ui.button("Done",0,this::dispose),BorderLayout.EAST); body.add(bottom,BorderLayout.SOUTH);
        setContentPane(body);
        layouts.addActionListener(e -> {
            if (!refreshing && layouts.getSelectedItem() instanceof SavedLayout selected) {
                workspace.selectSavedLayout(selected.id()); name.setText(selected.name()); showMessage(" ");
            }
        });
        getRootPane().registerKeyboardAction(e -> dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        refresh(); pack(); setMinimumSize(new Dimension(620,getPreferredSize().height));
        setLocationRelativeTo(workspace);
    }
    private SavedLayout selected() {
        if (!(layouts.getSelectedItem() instanceof SavedLayout value)) throw new IllegalArgumentException("Save or select a layout first.");
        return value;
    }
    void refresh() {
        SavedLayout old=(SavedLayout)layouts.getSelectedItem();
        boolean edited=old!=null && !name.getText().equals(old.name());
        refreshing=true;
        try {
            layouts.setModel(new DefaultComboBoxModel<>(workspace.savedLayouts().toArray(SavedLayout[]::new)));
            SavedLayout selected=workspace.savedLayouts().stream().filter(s -> Objects.equals(s.id(),workspace.selectedLayout()))
                    .findFirst().orElse(workspace.savedLayouts().isEmpty() ? null : workspace.savedLayouts().getFirst());
            layouts.setSelectedItem(selected);
            if (!edited) name.setText(selected==null ? "" : selected.name());
            boolean available=selected!=null && !working;
            for (JButton button:new JButton[]{replace,rename,delete,open,export}) button.setEnabled(available);
        } finally { refreshing=false; }
    }
    private void showMessage(String value) { message.setText(value); message.setToolTipText(value); }
    private void act(Runnable action) {
        if (working) return;
        try { action.run(); }
        catch (IllegalArgumentException e) { showMessage(e.getMessage()); }
    }
    private void chooseShortcut() {
        act(() -> {
            SavedLayout selected=selected();
            try {
                JFileChooser chooser=new JFileChooser(LayoutShortcuts.desktop().toFile());
                chooser.setDialogTitle("Create layout shortcut");
                chooser.setFileFilter(new FileNameExtensionFilter("Windows shortcut (*.lnk)","lnk"));
                String safe=selected.name().replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]","_").replaceAll("[. ]+$","");
                if (safe.isBlank()) safe="Fish layout";
                chooser.setSelectedFile(Path.of("Fish - "+safe+".lnk").toFile());
                if (chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION) return;
                Path output=chooser.getSelectedFile().toPath();
                if (!output.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".lnk"))
                    output=output.resolveSibling(output.getFileName()+".lnk");
                working=true; refresh(); showMessage("Saving layout and creating shortcut...");
                workspace.exportSavedLayout(selected.id(),output,() -> {
                    working=false; refresh(); showMessage("Shortcut created. It opens a new, auto-connecting Fish window.");
                },error -> { working=false; refresh(); showMessage(error); });
            } catch (java.io.IOException e) { showMessage(e.getMessage()); }
        });
    }
}
