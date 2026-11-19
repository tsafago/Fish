package fish.ui;

import java.util.UUID;
import java.util.function.UnaryOperator;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import fish.LayoutShortcuts;
import fish.model.SavedLayout;

/** Explicit operations on window shortcuts, independent of Settings autosave and keyboard bindings. */
final class DesktopShortcutForm extends VBox {
    final TextField name=new TextField();
    final CheckBox autostart=new CheckBox("Open this shortcut when I sign in to Windows");
    final Button create;
    final Label status=feedback();
    private final Workspace owner;
    private Workspace source;
    private boolean switchingWindow;
    final ListView<SavedLayout> list=new ListView<>();
    final CheckBox signIn=new CheckBox("Open at Windows sign-in");
    private final VBox management=new VBox(8);
    private final Label empty=new Label("No saved shortcuts yet.");
    private final Label result=feedback();

    DesktopShortcutForm(Workspace workspace) {
        super(8);
        owner=source=workspace;
        Label label=new Label("Shortcut name");
        label.setLabelFor(name);
        name.setText(defaultName());
        name.setAccessibleText(label.getText());
        name.textProperty().addListener((o,old,value) -> {
            if (!switchingWindow && !value.isBlank()) owner.tutorialAction("shortcut-named");
        });
        create=Ui.button("Export current window",() -> {
            setDisable(true);
            message(status,"Exporting shortcut…",false);
            source().exportDesktopShortcut(name.getText(),autostart.isSelected(),file -> {
                setDisable(false); refresh();
                list.getSelectionModel().selectLast();
                message(status,"Created "+file.getFileName()+" on your Desktop."
                        +(LayoutShortcuts.areDesktopIconsHidden() ? " To see it, turn on Show desktop icons in Windows." : ""),false);
                owner.tutorialAction("shortcut-created");
            },error -> { setDisable(false); message(status,"Shortcut not created: "+error,true); });
        });

        list.setAccessibleText("Saved shortcuts");
        list.setFixedCellSize(30);
        list.getSelectionModel().selectedItemProperty().addListener((o,old,value) -> {
            signIn.setSelected(value!=null && value.autostart());
            management.setDisable(value==null);
            result.setVisible(false);
        });
        signIn.setOnAction(e -> {
            boolean enabled=signIn.isSelected();
            edit(selected(),s -> s.withAutostart(enabled),enabled ? "Windows sign-in enabled." : "Windows sign-in disabled.");
        });
        Button replace=Ui.button("Save over…",() -> {
            SavedLayout s=selected();
            if (s!=null && confirm("Save over shortcut","Replace the pane arrangement in “"+s.name()+"” with this window?","Save over"))
                edit(s,old -> source().captureShortcut(old.id(),old.name(),old.autostart()),"Saved the current window.");
        });
        Button rename=Ui.button("Rename…",() -> {
            SavedLayout s=selected(); if (s==null) return;
            TextField field=new TextField(s.name()); field.selectAll();
            Label prompt=new Label("Shortcut name"); prompt.setLabelFor(field);
            if (Ui.form(getScene().getWindow(),"Rename shortcut",new VBox(6,prompt,field),"Rename"))
                edit(s,old -> old.renamed(field.getText()),"Shortcut renamed.");
        });
        Button delete=Ui.button("Delete…",() -> {
            SavedLayout s=selected();
            if (s!=null && confirm("Delete shortcut","Delete “"+s.name()+"” and its Desktop and Startup links? Open windows won't change.","Delete"))
                edit(s,old -> null,"Shortcut deleted.");
        });
        management.getChildren().addAll(signIn,new FlowPane(8,8,replace,rename,delete));
        Label saved=Preferences.section("Saved shortcuts");
        VBox.setMargin(saved,new Insets(12,0,0,0));
        Ui.role(empty,"muted");
        Label help=new Label("Reopen this window’s panes from your Desktop."); Ui.role(help,"muted"); help.setWrapText(true);
        getChildren().addAll(Preferences.section("Current window"),help,label,name,autostart,create,status,
                saved,empty,list,management,result);
        for (var node:java.util.List.of(empty,list,management)) node.managedProperty().bind(node.visibleProperty());
        refresh();
    }

    private Workspace source() { return source.closing() ? owner : source; }
    private String defaultName() {
        return source().sessionPanes().size()==1 && source().active()!=null ? source().active().profile().name() : "Work panes";
    }
    void window(Workspace workspace) {
        if (source==workspace) return;
        boolean useDefault=name.getText().equals(defaultName());
        source=workspace;
        if (useDefault) {
            switchingWindow=true;
            try { name.setText(defaultName()); } finally { switchingWindow=false; }
        }
    }
    private boolean confirm(String title,String question,String action) {
        Label text=new Label(question); text.setWrapText(true);
        return Ui.form(getScene().getWindow(),title,text,action);
    }
    private SavedLayout selected() { return list.getSelectionModel().getSelectedItem(); }
    void refresh() {
        var saved=owner.savedLayouts();
        if (!list.getItems().equals(saved)) {
            UUID id=selected()==null ? null : selected().id();
            int index=Math.max(0,list.getSelectionModel().getSelectedIndex());
            list.getItems().setAll(saved);
            if (!saved.isEmpty()) list.getSelectionModel().select(saved.stream().filter(s -> s.id().equals(id))
                    .findFirst().orElse(saved.get(Math.min(index,saved.size()-1))));
        }
        empty.setVisible(saved.isEmpty()); list.setVisible(!saved.isEmpty()); management.setVisible(!saved.isEmpty());
        list.setPrefHeight(Math.min(4,Math.max(1,saved.size()))*list.getFixedCellSize()+4);
    }
    private void edit(SavedLayout before,UnaryOperator<SavedLayout> mutation,String success) {
        if (before==null) return;
        setDisable(true); message(result,"Saving…",false);
        source().editShortcut(before,mutation,change -> {
            setDisable(false); refresh();
            message(result,change.warning().isEmpty() ? success : change.warning(),!change.warning().isEmpty());
        },error -> {
            setDisable(false); refresh(); signIn.setSelected(selected()!=null && selected().autostart());
            message(result,"Shortcut not changed: "+error,true);
        });
    }
    private static Label feedback() {
        Label label=new Label(); label.setWrapText(true);
        label.setVisible(false); label.managedProperty().bind(label.visibleProperty()); return label;
    }
    private static void message(Label label,String text,boolean failed) {
        Ui.role(label,failed ? "error" : "muted");
        label.setText(text); label.setVisible(true);
    }
}
