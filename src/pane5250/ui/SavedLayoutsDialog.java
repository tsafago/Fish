package pane5250.ui;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import pane5250.LayoutShortcuts;
import pane5250.model.SavedLayout;

/** Current-window shortcut creation first; existing recipes remain explicit, stable references. */
final class SavedLayoutsDialog extends JDialog {
    interface ShortcutExport {
        Path desktop() throws IOException;
        int choose(JFileChooser chooser,Component owner);
        void write(UUID id,Path output,Runnable done,Consumer<String> failed);
    }
    private final Workspace workspace;
    private final ShortcutExport shortcutExport;
    private final JTextField name=new JTextField(24),existingName=new JTextField(24);
    private final JComboBox<SavedLayout> layouts=new JComboBox<>();
    private final JLabel context=new JLabel(),message=new JLabel(" ");
    private final JButton create,save,replace,rename,delete,open,export;
    private final JToggleButton manage=new JToggleButton("Saved layouts");
    private final JPanel library=new JPanel(new BorderLayout(0,12));
    private JFileChooser destination;
    private boolean refreshing,working;

    SavedLayoutsDialog(Workspace workspace) {
        this(workspace,new ShortcutExport() {
            @Override public Path desktop() throws IOException { return LayoutShortcuts.desktop(); }
            @Override public int choose(JFileChooser chooser,Component owner) { return chooser.showSaveDialog(owner); }
            @Override public void write(UUID id,Path output,Runnable done,Consumer<String> failed) {
                workspace.exportSavedLayout(id,output,done,failed);
            }
        });
    }
    SavedLayoutsDialog(Workspace workspace,ShortcutExport shortcutExport) {
        super(workspace,"Saved layouts / shortcuts",false);
        this.workspace=workspace; this.shortcutExport=shortcutExport;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel body=new JPanel(new BorderLayout(0,16)); body.setBorder(Ui.padding(18));
        JPanel current=new JPanel(new BorderLayout(0,10));
        JLabel heading=new JLabel("Shortcut for this window"); heading.setFont(heading.getFont().deriveFont(Font.BOLD,16f));
        JPanel introduction=new JPanel(new BorderLayout(0,6)); introduction.add(heading,BorderLayout.NORTH);
        introduction.add(context,BorderLayout.CENTER); current.add(introduction,BorderLayout.NORTH);
        name.setText(workspace.sessionPanes().size()==1 ? workspace.active().profile().name() : "Work layout");
        name.getAccessibleContext().setAccessibleName("New shortcut layout name");
        Ui.observeEdits(name,() -> {
            name.setFont(Ui.textFont(name.getFont(),name.getText()));
            if (!refreshing && !name.getText().isBlank()) workspace.tutorialAction("layout-named");
        });
        current.add(row("Name",name),BorderLayout.CENTER);
        JPanel primary=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));
        create=Ui.button("Create desktop shortcut...",0,() -> chooseShortcut(true));
        Dimension createSize=create.getPreferredSize();
        create.setPreferredSize(new Dimension(createSize.width+16,createSize.height));
        save=Ui.button("Save layout only",0,() -> act(() -> {
            workspace.saveCurrentLayout(null,name.getText()); refresh(); showLibrary();
            working=true; refresh(); showMessage("Saving layout...");
            workspace.persistSettings(() -> {
                working=false; refresh(); showMessage("Layout saved. Open it or create its shortcut below.");
            },error -> {
                working=false; refresh(); showMessage("Not saved: "+error+" Layout kept here for retry.");
            });
        }));
        primary.add(create); primary.add(save); current.add(primary,BorderLayout.SOUTH);
        body.add(current,BorderLayout.NORTH);
        JPanel existing=new JPanel(new BorderLayout(0,10));
        manage.setHorizontalAlignment(SwingConstants.LEFT); manage.setIcon(Ui.symbol("right"));
        manage.addActionListener(e -> { library.setVisible(manage.isSelected()); manage.setIcon(Ui.symbol(manage.isSelected() ? "down" : "right")); pack(); });
        existing.add(manage,BorderLayout.NORTH); existing.add(library,BorderLayout.CENTER); library.setVisible(false);
        JPanel fields=new JPanel(new GridLayout(0,1,0,8));
        fields.add(row("Saved layout",layouts)); fields.add(row("Rename to",existingName)); library.add(fields,BorderLayout.NORTH);
        JPanel actions=new JPanel(new GridLayout(2,3,8,8));
        open=Ui.button("Open new window",0,() -> act(() -> {
            if (!workspace.openSavedLayout(selected().id())) throw new IllegalArgumentException("Fish is saving or closing; try again shortly.");
            showMessage("Opened an independent window.");
        }));
        export=Ui.button("Create shortcut...",0,() -> chooseShortcut(false));
        replace=Ui.button("Update from this window",0,() -> act(() -> {
            SavedLayout value=selected();
            if (Ui.confirm(this,"Replace \""+value.name()+"\" with this window's current panes?\nExisting shortcuts will use the new arrangement.","Update saved layout")) {
                workspace.saveCurrentLayout(value.id(),value.name()); refresh(); showMessage("Updated; existing shortcuts keep working.");
            }
        }));
        rename=Ui.button("Rename",0,() -> act(() -> {
            workspace.renameSavedLayout(selected().id(),existingName.getText()); refresh(); showMessage("Renamed; existing shortcuts keep working.");
        }));
        delete=Ui.button("Delete",0,() -> act(() -> {
            SavedLayout value=selected();
            if (Ui.confirm(this,"Delete \""+value.name()+"\"?\nExported shortcuts are kept, but will report this layout as unavailable.","Delete saved layout")) {
                workspace.deleteSavedLayout(value.id()); refresh(); showMessage("Deleted; no exported shortcut files were removed.");
            }
        }));
        actions.add(open); actions.add(export); actions.add(replace); actions.add(rename); actions.add(delete);
        library.add(actions,BorderLayout.CENTER); body.add(existing,BorderLayout.CENTER);
        JPanel footer=new JPanel(new BorderLayout(12,10)); Ui.role(message,"muted");
        message.getAccessibleContext().setAccessibleName("Shortcut creation status");
        footer.add(message,BorderLayout.CENTER); footer.add(Ui.button("Close",0,this::dispose),BorderLayout.EAST); body.add(footer,BorderLayout.SOUTH);
        layouts.addActionListener(e -> {
            if (!refreshing && layouts.getSelectedItem() instanceof SavedLayout value) {
                workspace.selectSavedLayout(value.id()); existingName.setText(value.name());
            }
        });
        setContentPane(body); Ui.decorate(this,this::dispose);
        getRootPane().setDefaultButton(create);
        getRootPane().registerKeyboardAction(e -> dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        refresh(); pack(); setMinimumSize(new Dimension(640,getPreferredSize().height)); setLocationRelativeTo(workspace);
    }
    private static JPanel row(String text,JComponent control) {
        JPanel row=new JPanel(new BorderLayout(12,0)); JLabel label=new JLabel(text); label.setLabelFor(control);
        label.setPreferredSize(new Dimension(100,28)); row.add(label,BorderLayout.WEST); row.add(control,BorderLayout.CENTER); return row;
    }
    private void showLibrary() { manage.setSelected(true); library.setVisible(true); manage.setIcon(Ui.symbol("down")); pack(); }
    private SavedLayout selected() {
        if (!(layouts.getSelectedItem() instanceof SavedLayout value)) throw new IllegalArgumentException("Save or select a layout first.");
        return value;
    }
    void refresh() {
        SavedLayout old=(SavedLayout)layouts.getSelectedItem();
        boolean edited=old!=null && !existingName.getText().equals(old.name());
        refreshing=true;
        try {
            int panes=workspace.sessionPanes().size();
            context.setText(panes==1 ? "One profile: "+workspace.active().profile().name() : panes+" panes with their current profiles and arrangement");
            context.setToolTipText("Captures profiles, arrangement, proportions and fit/zoom; never terminal contents or passwords.");
            layouts.setModel(new DefaultComboBoxModel<>(workspace.savedLayouts().toArray(SavedLayout[]::new)));
            SavedLayout value=workspace.savedLayouts().stream().filter(s -> Objects.equals(s.id(),workspace.selectedLayout()))
                    .findFirst().orElse(workspace.savedLayouts().isEmpty() ? null : workspace.savedLayouts().getFirst());
            layouts.setSelectedItem(value);
            if (!edited) existingName.setText(value==null ? "" : value.name());
            for (JButton button:new JButton[]{replace,rename,delete,open,export}) button.setEnabled(value!=null && !working);
            layouts.setEnabled(value!=null && !working); existingName.setEnabled(value!=null && !working);
            create.setEnabled(!working); save.setEnabled(!working); name.setEnabled(!working);
            manage.setText("Saved layouts ("+workspace.savedLayouts().size()+")");
        } finally { refreshing=false; }
    }
    private void showMessage(String value) { message.setText(value); message.setToolTipText(value); }
    private void act(Runnable action) {
        if (working) return;
        try { action.run(); }
        catch (IllegalArgumentException e) { showMessage(e.getMessage()); }
    }
    private void chooseShortcut(boolean current) {
        act(() -> {
            SavedLayout value=current ? null : selected();
            String title=current ? name.getText().strip() : value.name();
            if (current && (title.isEmpty() || title.length()>80 || title.chars().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("Name must be 1-80 printable characters.");
            if (current && workspace.savedLayouts().stream().anyMatch(s -> s.name().equalsIgnoreCase(title)))
                throw new IllegalArgumentException("That name is already saved. Use Saved layouts below, or enter a new name.");
            if (current && workspace.savedLayouts().size()>=SavedLayout.MAX_SAVED)
                throw new IllegalArgumentException("Keep at most 64 saved layouts. Update or remove one in Saved layouts below.");
            try {
                JFileChooser chooser=new JFileChooser(shortcutExport.desktop().toFile()); destination=chooser;
                chooser.addHierarchyListener(e -> workspace.tutorialChanged());
                chooser.setDialogTitle("Create layout shortcut");
                chooser.setFileFilter(new FileNameExtensionFilter("Windows shortcut (*.lnk)","lnk"));
                String safe=title.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]","_").replaceAll("[. ]+$","");
                if (safe.isBlank()) safe="Fish layout";
                chooser.setSelectedFile(Path.of("Fish - "+safe+".lnk").toFile());
                workspace.tutorialAction("layout-destination-open");
                if (shortcutExport.choose(chooser,this)!=JFileChooser.APPROVE_OPTION) return;
                Path output=chooser.getSelectedFile().toPath();
                if (!output.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".lnk"))
                    output=output.resolveSibling(output.getFileName()+".lnk");
                if (java.nio.file.Files.exists(output)) throw new IllegalArgumentException("That file already exists. Choose a new shortcut filename.");
                if (current) value=workspace.saveCurrentLayout(null,title);
                working=true; refresh(); showMessage("Saving layout and creating shortcut...");
                shortcutExport.write(Objects.requireNonNull(value).id(),output,() -> {
                    working=false; refresh(); showMessage("Shortcut created. Opens a new Fish window.");
                    workspace.tutorialAction("layout-shortcut");
                },error -> { working=false; refresh(); showMessage("Layout kept; shortcut not created: "+error); });
            } catch (IOException e) { showMessage(e.getMessage()); }
            finally { destination=null; workspace.tutorialChanged(); }
        });
    }
    Component tutorialTarget(String control) {
        return switch(control) {
            case "name" -> name; case "save" -> save; case "export" -> create;
            case "destination" -> destination==null ? create : destination;
            case "message" -> message; default -> manage;
        };
    }
}
