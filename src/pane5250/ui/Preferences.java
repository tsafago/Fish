package pane5250.ui;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import pane5250.model.*;

/** One indexed, draft-based settings surface; quick menu routes open the same controls. */
final class Preferences {
    private final Workspace workspace;
    private final JList<String> navigation=new JList<>(new String[]{"General","Appearance","Terminal","Panes","Keyboard","Updates"});
    private final JPanel pages=new JPanel(new CardLayout());
    private final JCheckBox startup,window,pane,tint,checkUpdates;
    private final List<JCheckBox> info=new ArrayList<>();
    private final JComboBox<Object> theme=new JComboBox<>();
    private final JComboBox<String> family=new JComboBox<>();
    private final JTextField fontPath=new JTextField(32);
    private final JComboBox<UpdateSettings.Channel> channel=new JComboBox<>(UpdateSettings.Channel.values());
    private String ignored;
    private final KeyboardEditor keyboard;
    private final JLabel error=new JLabel(" "),fontStatus=new JLabel(" ");
    private final JButton designer;
    private JDialog dialog;
    private Draft saved;
    private record Draft(boolean startup,Object theme,Appearance.FontChoice font,boolean window,boolean pane,boolean tint,
                         Appearance.PaneInfo info,UpdateSettings updates,Map<String,String> keys) {}
    Preferences(Workspace workspace,String category) {
        this.workspace=workspace;
        Appearance a=workspace.appearanceSettings();
        startup=new JCheckBox("Connect restored panes on startup",workspace.connectOnStartup());
        window=new JCheckBox("Show whole-window outline",a.windowOutline());
        pane=new JCheckBox("Outline active pane",a.outline());
        tint=new JCheckBox("Tint active pane background",workspace.highlightActive());
        checkUpdates=new JCheckBox("Check for updates at startup",workspace.updateSettings().startup());
        ignored=workspace.updateSettings().ignoredVersion();
        channel.setSelectedItem(workspace.updateSettings().channel());
        keyboard=new KeyboardEditor(workspace);
        designer=Ui.button("Theme designer...",0,this::designTheme);
        loadThemes();
        family.addItem("Automatic"); TerminalFonts.installed().forEach(family::addItem);
        family.setSelectedItem(a.font().family()); fontPath.setText(a.font().file());
        Appearance.PaneInfo i=a.info();
        String[] names={"Show header","Profile name","Connection state","Host","Requested device name",
                "Show footer","Status / input message","Host dimensions","Cursor row:column","Insert indicator"};
        boolean[] flags={i.header(),i.profile(),i.connection(),i.host(),i.device(),i.footer(),i.status(),i.dimensions(),i.cursor(),i.insert()};
        for (int n=0;n<names.length;n++) info.add(new JCheckBox(names[n],flags[n]));
        addPage("General",general()); addPage("Appearance",appearancePage()); addPage("Terminal",terminal());
        addPage("Panes",panes()); addPage("Keyboard",keyboard); addPage("Updates",updates());
        navigation.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); navigation.setFixedCellHeight(34);
        navigation.setPreferredSize(new Dimension(132,380));
        navigation.getAccessibleContext().setAccessibleName("Preferences categories");
        navigation.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && navigation.getSelectedValue()!=null)
                ((CardLayout)pages.getLayout()).show(pages,navigation.getSelectedValue());
        });
        JPanel content=new JPanel(new BorderLayout(18,12));
        content.add(navigation,BorderLayout.WEST); content.add(pages,BorderLayout.CENTER);
        Ui.role(error,"error"); content.add(error,BorderLayout.SOUTH);
        saved=draft();
        dialog=Ui.settingsDialog(workspace,"Preferences",content,this::dirty,this::apply);
        Ui.observeEdits(content,() -> {
            designer.setEnabled(!dirty());
            designer.setToolTipText(dirty() ? "Apply or cancel preference edits before opening the separate theme designer." : null);
        });
        dialog.setSize(Math.min(1040,workspace.getGraphicsConfiguration().getBounds().width-40),
                Math.min(720,workspace.getGraphicsConfiguration().getBounds().height-40));
        dialog.setMinimumSize(new Dimension(800,580)); dialog.setResizable(true); dialog.setLocationRelativeTo(workspace);
        navigation.setSelectedValue(category,true);
        if (navigation.getSelectedIndex()<0) navigation.setSelectedIndex(0);
    }
    JDialog dialog() { return dialog; }
    private void designTheme() {
        workspace.executeCommand("Theme designer...");
        loadThemes(); saved=draft(); designer.requestFocusInWindow();
    }
    private void addPage(String name,JComponent body) {
        JPanel page=new JPanel(new BorderLayout(0,14));
        JLabel heading=new JLabel(name); heading.setFont(heading.getFont().deriveFont(Font.BOLD,16f));
        page.add(heading,BorderLayout.NORTH);
        if (name.equals("Keyboard")) page.add(body,BorderLayout.CENTER);
        else {
            JPanel top=new JPanel(new BorderLayout()); top.add(body,BorderLayout.NORTH);
            JScrollPane scroll=new JScrollPane(top); scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); page.add(scroll,BorderLayout.CENTER);
        }
        pages.add(page,name);
    }
    private JPanel general() {
        JPanel body=column();
        body.add(startup);
        body.add(note("Off by default. Incomplete logins stay offline; Kerberos uses Windows credentials only when connecting."));
        body.add(link("Workstation profiles...","Profiles..."));
        body.add(note("Host, sign-in, device, code page, palette, effects and saved layouts belong to profiles. Keyboard bindings do not."));
        body.add(link("Replay getting started","Getting started tutorial"));
        body.add(link("Configuration export / validation","Configuration for external tools"));
        body.add(new JSeparator());
        body.add(Ui.button("Revert to factory defaults...",0,workspace::factoryDefaults));
        body.add(note("Requires confirmation, ends sessions and exits. Next startup is fresh. Windows passwords and font files are kept."));
        return body;
    }
    JPanel appearancePage() {
        JPanel body=column();
        Ui.labeled(body,"Application theme",theme); body.add(designer);
        body.add(note("Custom themes include host colors. The separate theme designer has its own Apply / Cancel."));
        body.add(link("Profile palettes and optional text effects...","Profiles..."));
        body.add(note("Effects stay off by default. Host attributes and hidden fields retain their meaning."));
        return body;
    }
    private JPanel terminal() {
        JPanel body=column(); Ui.labeled(body,"Installed monospaced family",family);
        JPanel file=new JPanel(new BorderLayout(8,0)); file.add(fontPath,BorderLayout.CENTER);
        file.add(Ui.button("Browse...",KeyEvent.VK_B,() -> {
            JFileChooser chooser=new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("Local monospaced fonts (*.ttf, *.otf)","ttf","otf"));
            if (Ui.openFile(dialog,"Choose terminal font",chooser)) fontPath.setText(chooser.getSelectedFile().getAbsolutePath());
        }),BorderLayout.EAST);
        Ui.labeled(body,"Custom font file (optional; takes precedence)",file);
        fontPath.getAccessibleContext().setAccessibleName("Local custom TTF or OTF font path");
        JLabel preview=new JLabel("Aa 0O 1Il | 0123456789 [] {} @ #"); preview.setBorder(Ui.padding(12)); body.add(preview);
        body.add(Ui.button("Check / preview font",0,() -> {
            try {
                Font font=TerminalFonts.resolve(fontChoice()); TerminalFonts.validate(font,workspace.profileList());
                preview.setFont(font); fontStatus.setText(" "); fontStatus.setToolTipText(null);
            } catch (IOException | IllegalArgumentException e) { fontStatus.setText(e.getMessage()); fontStatus.setToolTipText(e.getMessage()); }
        }));
        Ui.role(fontStatus,"error"); body.add(fontStatus);
        JPanel scale=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
        for (String action:List.of("Fit host screen","Zoom in","Zoom out")) scale.add(link(action,action));
        body.add(scale); body.add(note("Scale actions affect the active pane immediately; host screen dimensions never change."));
        body.add(link("Code page and workstation settings...","Profiles..."));
        return body;
    }
    private JPanel panes() {
        JPanel body=column(); body.add(window); body.add(pane); body.add(tint); body.add(new JSeparator());
        JPanel columns=new JPanel(new GridLayout(1,2,20,0));
        for (int group=0;group<2;group++) {
            JPanel part=column(); int start=group*5;
            for (int n=start;n<start+5;n++) part.add(info.get(n));
            Runnable dependencies=() -> { for (int n=start+1;n<start+5;n++) info.get(n).setEnabled(info.get(start).isSelected()); };
            info.get(start).addActionListener(e -> dependencies.run()); dependencies.run(); columns.add(part);
        }
        body.add(columns); body.add(new JSeparator());
        body.add(note("Layout actions are immediate and are also available from Panes."));
        JPanel layout=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
        for (String title:List.of("Swap panes...","Save layout to profile","Restore profile layout")) layout.add(link(title,title));
        body.add(layout); return body;
    }
    private JPanel updates() {
        JPanel body=column(); body.add(checkUpdates); Ui.labeled(body,"Update channel",channel);
        body.add(note("Preview patches stay in this minor version. Stable releases stay in this major version. No new experimental minor or major is selected automatically."));
        JLabel hidden=new JLabel(ignored.isEmpty() ? "No hidden version" : "Hidden on startup: "+ignored);
        body.add(hidden);
        body.add(Ui.button("Show hidden updates again",0,() -> {
            ignored=""; hidden.setText("No hidden version");
            // This draft-only choice is not a document/item edit.
            checkUpdates.firePropertyChange("preferences.draft",false,true);
            dialog.getRootPane().getDefaultButton().repaint();
        }));
        JButton check=link("Check for updates...","Check for updates...");
        check.setToolTipText("Uses the applied update channel; manual checks include hidden versions."); body.add(check);
        body.add(note("Public tsafago/Fish only. No account, telemetry or workspace upload. Downloads require consent; unsigned binaries remain subject to Windows and IT policy."));
        return body;
    }
    private JButton link(String text,String action) { return Ui.button(text,0,() -> workspace.executeCommand(action)); }
    private static JPanel column() {
        return new JPanel(new GridBagLayout()) {
            @Override public Component add(Component child) {
                GridBagConstraints cell=new GridBagConstraints();
                cell.gridx=0; cell.gridy=getComponentCount(); cell.weightx=1;
                cell.fill=GridBagConstraints.HORIZONTAL; cell.anchor=GridBagConstraints.NORTHWEST;
                cell.insets=new Insets(0,0,10,0); super.add(child,cell); return child;
            }
        };
    }
    private static JTextArea note(String text) {
        JTextArea area=new JTextArea(text,2,36); area.setEditable(false); area.setFocusable(false);
        area.setLineWrap(true); area.setWrapStyleWord(true); area.setOpaque(false); area.setBorder(BorderFactory.createEmptyBorder());
        Ui.role(area,"muted"); return area;
    }
    private void loadThemes() {
        theme.removeAllItems(); for (AppTheme value:AppTheme.values()) theme.addItem(value);
        workspace.appearanceSettings().themes().forEach(theme::addItem);
        theme.setSelectedItem(workspace.appearanceSettings().custom().<Object>map(t -> t).orElse(Ui.theme()));
    }
    private Appearance.FontChoice fontChoice() { return new Appearance.FontChoice(Objects.toString(family.getSelectedItem(),"Automatic"),fontPath.getText().strip()); }
    private Draft draft() {
        return new Draft(startup.isSelected(),theme.getSelectedItem(),fontChoice(),window.isSelected(),pane.isSelected(),tint.isSelected(),
                new Appearance.PaneInfo(info.get(0).isSelected(),info.get(5).isSelected(),info.get(1).isSelected(),info.get(2).isSelected(),
                        info.get(3).isSelected(),info.get(4).isSelected(),info.get(6).isSelected(),info.get(7).isSelected(),info.get(8).isSelected(),info.get(9).isSelected()),
                new UpdateSettings(checkUpdates.isSelected(),(UpdateSettings.Channel)channel.getSelectedItem(),ignored),keyboard.draft());
    }
    private boolean dirty() { try { return !draft().equals(saved); } catch (IllegalArgumentException e) { return true; } }
    private boolean apply() {
        try {
            Draft next=draft();
            Font font=TerminalFonts.resolve(next.font()); TerminalFonts.validate(font,workspace.profileList());
            Appearance previous=workspace.appearanceSettings();
            Appearance value=new Appearance(next.pane(),next.window(),next.info(),next.font(),
                    next.theme() instanceof ColorTheme custom ? custom.name() : "",previous.themes(),previous.retiredShortcuts());
            workspace.applyShortcuts(next.keys());
            if (next.theme() instanceof AppTheme app) workspace.applyTheme(app);
            workspace.appearanceSettings(value); workspace.highlightActive(next.tint());
            workspace.connectOnStartup(next.startup()); workspace.updateSettings(next.updates());
            saved=next; designer.setEnabled(true); error.setText(" "); error.setToolTipText(null); return true;
        } catch (IOException | IllegalArgumentException e) {
            error.setText(e.getMessage()); error.setToolTipText(e.getMessage()); return false;
        }
    }
}
