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
        Appearance.FontChoice saved=workspace.appearanceSettings().font();
        JComboBox<String> family=new JComboBox<>();
        family.addItem("Automatic"); TerminalFonts.installed().forEach(family::addItem);
        family.setSelectedItem(saved.family());
        JTextField path=new JTextField(saved.file(),32);
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
        JLabel status=new JLabel("Workspace-wide. Local files stay on this machine; they are not copied or uploaded.");
        Ui.role(status,"muted"); fields.add(status);
        Runnable show=() -> {
            try {
                Font face=TerminalFonts.resolve(new Appearance.FontChoice(Objects.toString(family.getSelectedItem(),"Automatic"),path.getText().strip()));
                TerminalFonts.validate(face,workspace.profileList());
                preview.setFont(face); status.setText("Fixed-width and current code-page glyphs verified. Regular weight; fit and zoom remain available.");
                status.setForeground(Ui.MUTED);
            } catch (IOException | IllegalArgumentException e) {
                status.setText(e.getMessage()); status.setToolTipText(e.getMessage()); status.setForeground(Ui.ERROR);
            }
        };
        fields.add(Ui.button("Check / preview font",KeyEvent.VK_P,show)); show.run();
        while (Ui.form(workspace,"Terminal fonts",fields,"Apply")) {
            try {
                Appearance.FontChoice choice=new Appearance.FontChoice(Objects.toString(family.getSelectedItem(),"Automatic"),path.getText().strip());
                Font face=TerminalFonts.resolve(choice); TerminalFonts.validate(face,workspace.profileList());
                workspace.appearanceSettings(workspace.appearanceSettings().withFont(choice)); return;
            } catch (IOException | IllegalArgumentException e) {
                status.setText(e.getMessage()); status.setToolTipText(e.getMessage()); status.setForeground(Ui.ERROR);
            }
        }
    }
    static void paneInfo(Workspace workspace) {
        Appearance a=workspace.appearanceSettings(); Appearance.PaneInfo i=a.info();
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
        JPanel body=new JPanel(new BorderLayout(0,14)); body.add(form);
        body.add(new JLabel("<html>Workspace-wide. Empty/disabled rows reserve no space.<br>"
                +"With no footer, recoverable input errors show Reset as an overlay; host-busy is not reset.<br>"
                +"Use Alt+drag on a terminal to swap without a header, or Panes > Swap panes.<br>"
                +"Right-click a terminal for pane actions. Error notices remain visible without bars.</html>"),BorderLayout.SOUTH);
        if (Ui.form(workspace,"Pane information",body,"Apply")) {
            Appearance.PaneInfo value=new Appearance.PaneInfo(boxes.get(0).isSelected(),boxes.get(5).isSelected(),boxes.get(1).isSelected(),
                    boxes.get(2).isSelected(),boxes.get(3).isSelected(),boxes.get(4).isSelected(),boxes.get(6).isSelected(),
                    boxes.get(7).isSelected(),boxes.get(8).isSelected(),boxes.get(9).isSelected());
            workspace.appearanceSettings(a.withLayout(a.outline(),value));
        }
    }
    static void windowFocus(Workspace workspace) {
        Ui.showDialog(windowFocusDialog(workspace));
    }
    static JDialog windowFocusDialog(Workspace workspace) {
        Appearance a=workspace.appearanceSettings();
        JPanel form=windowFocusPanel(workspace);
        return Ui.promptDialog(workspace,"Window and focus",form,"Apply",true,() -> {
            JPanel choices=(JPanel)form.getComponent(0);
            JCheckBox window=(JCheckBox)choices.getComponent(0),pane=(JCheckBox)choices.getComponent(1),tint=(JCheckBox)choices.getComponent(2);
            workspace.appearanceSettings(a.withWindowOutline(window.isSelected()).withLayout(pane.isSelected(),a.info()));
            workspace.highlightActive(tint.isSelected());
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
        JLabel note=new JLabel("<html>Workspace-wide. Outlines use the same one-device-pixel divider color.<br>"
                +"Window resizing and keyboard focus do not require a visible outline.<br>"
                +"Changes are saved only when you choose Apply.</html>");
        Ui.role(note,"muted"); form.add(note,BorderLayout.SOUTH);
        return form;
    }
}
