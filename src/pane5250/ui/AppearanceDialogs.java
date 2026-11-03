package pane5250.ui;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.*;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import pane5250.model.Appearance;

final class AppearanceDialogs {
    private AppearanceDialogs() {}
    static void fonts(Workspace workspace) {
        Ui.showDialog(fontsDialog(workspace));
    }
    static JDialog fontsDialog(Workspace workspace) {
        Appearance.FontChoice[] saved={workspace.appearanceSettings().font()};
        JComboBox<String> family=new JComboBox<>();
        family.addItem("Automatic"); TerminalFonts.installed().forEach(family::addItem);
        family.setSelectedItem(saved[0].family());
        JTextField path=new JTextField(saved[0].file(),32);
        path.getAccessibleContext().setAccessibleName("Local custom TTF or OTF font path");
        JPanel file=new JPanel(new BorderLayout(8,0)); file.add(path);
        file.add(Ui.button("Browse",KeyEvent.VK_B,() -> {
            JFileChooser chooser=new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("Local monospaced fonts (*.ttf, *.otf)","ttf","otf"));
            if (Ui.openFile(workspace,"Choose terminal font",chooser)) path.setText(chooser.getSelectedFile().getAbsolutePath());
        }),BorderLayout.EAST);
        JPanel fields=new JPanel(new GridLayout(0,1,0,8));
        JLabel installed=new JLabel("Installed monospaced family"); installed.setLabelFor(family);
        fields.add(installed); fields.add(family);
        JLabel local=new JLabel("Custom file (takes precedence; leave blank to use the family)"); local.setLabelFor(path);
        fields.add(local); fields.add(file);
        JLabel preview=new JLabel("Aa 0O 1Il | 0123456789 [] {} @ #");
        preview.setBorder(Ui.padding(12)); fields.add(preview);
        JLabel status=new JLabel(" ");
        Ui.role(status,"muted"); fields.add(status);
        java.util.function.Supplier<Appearance.FontChoice> draft=() -> new Appearance.FontChoice(
                Objects.toString(family.getSelectedItem(),"Automatic"),path.getText().strip());
        Runnable show=() -> {
            try {
                Font face=TerminalFonts.resolve(draft.get());
                TerminalFonts.validate(face,workspace.profileList());
                preview.setFont(face); status.setText(" "); status.setToolTipText(null);
                status.setForeground(Ui.MUTED);
            } catch (IOException | IllegalArgumentException e) {
                status.setText(e.getMessage()); status.setToolTipText(e.getMessage()); status.setForeground(Ui.ERROR);
            }
        };
        fields.add(Ui.button("Check / preview font",KeyEvent.VK_P,show)); show.run();
        return Ui.settingsDialog(workspace,"Terminal fonts",fields,() -> !draft.get().equals(saved[0]),() -> {
            try {
                Appearance.FontChoice choice=draft.get();
                Font face=TerminalFonts.resolve(choice); TerminalFonts.validate(face,workspace.profileList());
                workspace.appearanceSettings(workspace.appearanceSettings().withFont(choice));
                saved[0]=choice; preview.setFont(face); status.setText(" "); status.setToolTipText(null); return true;
            } catch (IOException | IllegalArgumentException e) {
                status.setText(e.getMessage()); status.setToolTipText(e.getMessage()); status.setForeground(Ui.ERROR);
                return false;
            }
        });
    }
    static void paneInfo(Workspace workspace) {
        Ui.showDialog(paneInfoDialog(workspace));
    }
    static JDialog paneInfoDialog(Workspace workspace) {
        Appearance.PaneInfo[] saved={workspace.appearanceSettings().info()};
        Appearance.PaneInfo i=saved[0];
        String[] names={"Show header","Profile name","Connection state","Host","Requested device name",
                "Show footer","Status / input message","Host dimensions","Cursor row:column","Insert indicator"};
        boolean[] flags={i.header(),i.profile(),i.connection(),i.host(),i.device(),i.footer(),i.status(),i.dimensions(),i.cursor(),i.insert()};
        java.util.List<JCheckBox> boxes=new ArrayList<>();
        JPanel form=new JPanel(new GridLayout(1,2,24,0));
        for (int group=0;group<2;group++) {
            JPanel column=new JPanel(new GridLayout(0,1,0,8));
            for (int n=group*5;n<(group+1)*5;n++) {
                JCheckBox box=new JCheckBox(names[n],flags[n]); boxes.add(box); column.add(box);
            }
            int first=group*5;
            Runnable dependencies=() -> { for (int n=first+1;n<first+5;n++) boxes.get(n).setEnabled(boxes.get(first).isSelected()); };
            boxes.get(first).addActionListener(e -> dependencies.run()); dependencies.run(); form.add(column);
        }
        java.util.function.Supplier<Appearance.PaneInfo> draft=() -> new Appearance.PaneInfo(boxes.get(0).isSelected(),boxes.get(5).isSelected(),boxes.get(1).isSelected(),
                    boxes.get(2).isSelected(),boxes.get(3).isSelected(),boxes.get(4).isSelected(),boxes.get(6).isSelected(),
                    boxes.get(7).isSelected(),boxes.get(8).isSelected(),boxes.get(9).isSelected());
        return Ui.settingsDialog(workspace,"Pane information",form,() -> !draft.get().equals(saved[0]),() -> {
            Appearance.PaneInfo value=draft.get();
            Appearance current=workspace.appearanceSettings();
            workspace.appearanceSettings(current.withLayout(current.outline(),value)); saved[0]=value; return true;
        });
    }
    static void windowFocus(Workspace workspace) {
        Ui.showDialog(windowFocusDialog(workspace));
    }
    static JDialog windowFocusDialog(Workspace workspace) {
        JPanel form=windowFocusPanel(workspace);
        JPanel choices=(JPanel)form.getComponent(0);
        JCheckBox window=(JCheckBox)choices.getComponent(0),pane=(JCheckBox)choices.getComponent(1),tint=(JCheckBox)choices.getComponent(2);
        record Choices(boolean window,boolean pane,boolean tint) {}
        java.util.function.Supplier<Choices> draft=() -> new Choices(window.isSelected(),pane.isSelected(),tint.isSelected());
        Choices[] saved={draft.get()};
        return Ui.settingsDialog(workspace,"Window and focus",form,() -> !draft.get().equals(saved[0]),() -> {
            Appearance current=workspace.appearanceSettings();
            workspace.appearanceSettings(current.withWindowOutline(window.isSelected()).withLayout(pane.isSelected(),current.info()));
            workspace.highlightActive(tint.isSelected()); saved[0]=draft.get(); return true;
        });
    }
    static JPanel windowFocusPanel(Workspace workspace) {
        Appearance a=workspace.appearanceSettings();
        JPanel form=new JPanel(new BorderLayout(0,16));
        JPanel choices=new JPanel(new GridLayout(0,1,0,10));
        JCheckBox window=new JCheckBox("Show whole-window outline",a.windowOutline());
        JCheckBox pane=new JCheckBox("Outline active pane",a.outline());
        JCheckBox tint=new JCheckBox("Tint active pane background",workspace.highlightActive());
        choices.add(window); choices.add(pane); choices.add(tint); form.add(choices,BorderLayout.NORTH);
        return form;
    }
}
