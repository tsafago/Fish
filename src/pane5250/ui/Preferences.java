package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import pane5250.model.*;

/** One indexed settings surface, with shared profile and keyboard editors. */
final class Preferences {
    private final Workspace workspace;
    private final JList<String> navigation=new JList<>(new String[]{"Appearance","Profiles","Keyboard Shortcuts","General","Updates"});
    private final JPanel pages=new JPanel(new CardLayout());
    private final JCheckBox startup,window,pane,tint,checkUpdates;
    private final List<JCheckBox> info=new ArrayList<>();
    private final JComboBox<Object> theme=new JComboBox<>();
    private final JComboBox<Profile> scope=new JComboBox<>();
    private final JComboBox<String> palette=new JComboBox<>(Profile.THEMES.toArray(String[]::new));
    private final JComboBox<String> effect=new JComboBox<>(Profile.EFFECTS.toArray(String[]::new));
    private final JComboBox<Appearance.CursorStyle> cursorStyle=new JComboBox<>(Appearance.CursorStyle.values());
    private final JComboBox<String> family=new JComboBox<>();
    private final JTextField fontPath=new JTextField(24);
    private final JComboBox<UpdateSettings.Channel> channel=new JComboBox<>(UpdateSettings.Channel.values());
    private final Map<UUID,Display> displays=new LinkedHashMap<>(),originalDisplays=new LinkedHashMap<>();
    private final Map<String,JComponent> targets=new HashMap<>();
    private UUID displayProfile;
    private boolean loading,saving,ended,syncing,applying,unsaved,closeRequested,conflict,editPending;
    private final javax.swing.Timer edits=new javax.swing.Timer(300,e -> apply());
    private final javax.swing.Timer writes=new javax.swing.Timer(200,e -> persist());
    private long generation;
    private String validation="",saveFailure="";
    private String ignored;
    private final KeyboardEditor keyboard;
    final ProfilesEditor profiles;
    private final JLabel error=new JLabel(" ");
    private final JTextArea fontStatus=note(""),paletteStatus=note("");
    private final JButton designer,closeButton,retryButton,reloadButton;
    private final JDialog dialog;
    private Draft saved;
    private Shared baseline;
    private record Shared(Appearance appearance,AppTheme theme,boolean tint,boolean startup,UpdateSettings updates,Map<String,String> keys) {}
    private Shared currentShared() {
        return new Shared(workspace.appearanceSettings(),Ui.theme(),workspace.highlightActive(),workspace.connectOnStartup(),
                workspace.updateSettings(),workspace.shortcutOverrides());
    }
    private record Display(String palette,String effect) {}
    private record Draft(boolean startup,Object theme,Appearance.FontChoice font,boolean window,boolean pane,boolean tint,
                         Appearance.PaneInfo info,Appearance.CursorStyle cursorStyle,UpdateSettings updates,Map<String,String> keys,Map<UUID,Display> displays) {
        Draft withDisplays(Map<UUID,Display> value) { return new Draft(startup,theme,font,window,pane,tint,info,cursorStyle,updates,keys,Map.copyOf(value)); }
    }

    Preferences(Workspace workspace,String category) {
        this.workspace=workspace;
        dialog=new JDialog(workspace,"Settings",false) {
            @Override public void dispose() {
                if (!workspace.closing() && !requestClose()) return;
                ended=true; edits.stop(); writes.stop(); editPending=false; workspace.updateController().detach(this); profiles.discard();
                super.dispose(); workspace.preferencesClosed(Preferences.this);
            }
        };
        Appearance a=workspace.appearanceSettings();
        cursorStyle.setSelectedItem(a.cursorStyle());
        startup=new JCheckBox("Connect restored panes on startup",workspace.connectOnStartup());
        startup.addActionListener(e -> workspace.tutorialAction("startup-changed"));
        window=new JCheckBox("Show whole-window outline",a.windowOutline());
        pane=new JCheckBox("Outline active pane",a.outline());
        tint=new JCheckBox("Tint active pane background",workspace.highlightActive());
        checkUpdates=new JCheckBox("Check for updates at startup",workspace.updateSettings().startup());
        ignored=workspace.updateSettings().ignoredVersion(); channel.setSelectedItem(workspace.updateSettings().channel());
        keyboard=new KeyboardEditor(workspace);
        profiles=new ProfilesEditor(workspace,workspace.active()==null ? workspace.lastProfile() : workspace.active().profile(),true,dialog::dispose);
        designer=Ui.button("Theme designer...",0,this::designTheme);
        loadThemes();
        for (Profile profile:workspace.profileList()) {
            scope.addItem(profile); displays.put(profile.id(),new Display(profile.theme(),profile.effect()));
        }
        originalDisplays.putAll(displays);
        scope.setSelectedItem(workspace.active()==null ? workspace.lastProfile() : workspace.active().profile());
        loadDisplay();
        scope.addActionListener(e -> { if (!loading) { loadDisplay(); refresh(); } });
        palette.addActionListener(e -> displayChanged("appearance-palette"));
        effect.addActionListener(e -> displayChanged("appearance-effect"));
        theme.addActionListener(e -> { paletteEnabled(); workspace.tutorialAction("appearance-theme"); });
        family.addItem("Automatic"); TerminalFonts.installed().forEach(family::addItem);
        family.setSelectedItem(a.font().family()); fontPath.setText(a.font().file());
        family.addActionListener(e -> workspace.tutorialAction("appearance-font"));
        Appearance.PaneInfo i=a.info();
        String[] names={"Show header","Profile name","Connection state","Host","Requested device name",
                "Show footer","Connection / input status","Screen size","Cursor row:column","Insert indicator","Device name"};
        boolean[] flags={i.header(),i.profile(),i.connection(),i.host(),i.device(),i.footer(),i.status(),i.dimensions(),i.cursor(),i.insert(),i.footerDevice()};
        for (int n=0;n<names.length;n++) info.add(new JCheckBox(names[n],flags[n]));
        info.get(0).addActionListener(e -> workspace.tutorialAction("appearance-header"));
        info.get(5).addActionListener(e -> workspace.tutorialAction("appearance-footer"));
        addPage("Appearance",appearancePage(),true); addPage("Profiles",profiles,false);
        addPage("Keyboard Shortcuts",keyboard,false); addPage("General",general(),true);
        addPage("Updates",updates(),false);
        navigation.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); navigation.setFixedCellHeight(34);
        navigation.setPreferredSize(new Dimension(164,380));
        navigation.getAccessibleContext().setAccessibleName("Settings categories");
        navigation.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || navigation.getSelectedValue()==null) return;
            ((CardLayout)pages.getLayout()).show(pages,navigation.getSelectedValue());
            workspace.tutorialAction("settings-"+navigation.getSelectedValue());
            workspace.tutorialChanged();
            if (navigation.getSelectedValue().equals("Updates")) workspace.updateController().ensureCheck();
        });
        edits.setRepeats(false);
        writes.setRepeats(false);
        closeButton=Ui.button("Close",KeyEvent.VK_C,dialog::dispose);
        retryButton=Ui.button("Retry save",KeyEvent.VK_R,() -> { saveFailure=""; apply(); });
        reloadButton=Ui.button("Reload saved values",0,this::reload);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));
        buttons.add(reloadButton); buttons.add(retryButton); buttons.add(closeButton);
        JPanel footer=new JPanel(new BorderLayout(12,8)); Ui.role(error,"muted");
        error.getAccessibleContext().setAccessibleName("Settings save status");
        footer.add(error,BorderLayout.NORTH); footer.add(buttons,BorderLayout.SOUTH);
        JPanel content=new JPanel(new BorderLayout(18,16)); content.setBorder(Ui.padding(16));
        content.add(navigation,BorderLayout.WEST); content.add(pages,BorderLayout.CENTER); content.add(footer,BorderLayout.SOUTH);
        dialog.setContentPane(content); Ui.decorate(dialog,dialog::dispose);
        dialog.getRootPane().setDefaultButton(closeButton);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        Rectangle screen=workspace.getGraphicsConfiguration().getBounds();
        dialog.setSize(Math.min(1060,screen.width-40),Math.min(740,screen.height-40));
        dialog.setMinimumSize(new Dimension(760,560)); dialog.setResizable(true); dialog.setLocationRelativeTo(workspace);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { navigation.requestFocusInWindow(); workspace.tutorialChanged(); }
        });
        saved=draft(); baseline=currentShared();
        for (Component control:List.of(startup,window,pane,tint,checkUpdates,theme,palette,effect,cursorStyle,family,channel)) {
            if (control instanceof AbstractButton button) button.addItemListener(e -> edited(false));
            if (control instanceof JComboBox<?> combo) combo.addItemListener(e -> {
                if (e.getStateChange()==ItemEvent.SELECTED) edited(false);
            });
        }
        Ui.observeEdits(fontPath,() -> edited(true));
        info.forEach(control -> control.addItemListener(e -> edited(false)));
        keyboard.addPropertyChangeListener("preferences.draft",e -> edited(true));
        profiles.addPropertyChangeListener("settings.draft",e -> {
            syncProfiles(); refresh();
            if (closeRequested && !saving && !profiles.saving()) SwingUtilities.invokeLater(dialog::dispose);
        });
        select(category); paletteEnabled(); refresh();
    }
    JDialog dialog() { return dialog; }
    boolean ended() { return ended; }
    void focus() { navigation.requestFocusInWindow(); }
    void syncProfiles() {
        if (syncing || applying || saved==null) return;
        syncing=true;
        try {
            Map<UUID,Display> baseline=new LinkedHashMap<>(saved.displays());
            Set<UUID> ids=new HashSet<>();
            for (Profile profile:workspace.profileList()) {
                ids.add(profile.id()); Display current=new Display(profile.theme(),profile.effect());
                Display original=originalDisplays.get(profile.id());
                if (original==null || original.equals(displays.get(profile.id()))) {
                    displays.put(profile.id(),current); originalDisplays.put(profile.id(),current); baseline.put(profile.id(),current);
                }
            }
            for (UUID id:new HashSet<>(displays.keySet())) if (!ids.contains(id)) {
                displays.remove(id); originalDisplays.remove(id); baseline.remove(id);
            }
            saved=saved.withDisplays(baseline);
            UUID selected=displayProfile; loading=true; scope.removeAllItems();
            workspace.profileList().forEach(scope::addItem);
            for (int n=0;n<scope.getItemCount();n++) if (scope.getItemAt(n).id().equals(selected)) scope.setSelectedIndex(n);
            loading=false; loadDisplay(); profiles.rebaseDisplay();
        } finally { syncing=false; }
    }
    boolean saving() { return saving || unsaved && saveFailure.isEmpty() || editPending || profiles.saving(); }
    boolean customPalette() { return theme.getSelectedItem() instanceof ColorTheme; }
    String category() { return navigation.getSelectedValue(); }
    void select(String category) {
        String name=switch(category) {
            case "Terminal","Panes" -> "Appearance"; case "Keyboard" -> "Keyboard Shortcuts"; default -> category;
        };
        navigation.setSelectedValue(name,true);
        if (navigation.getSelectedIndex()<0) navigation.setSelectedIndex(0);
    }
    Component tutorialTarget(String control) {
        if (control.startsWith("keyboard-")) return keyboard.tutorialTarget(control.substring(9));
        JComponent component=control.equals("apply") ? error : targets.getOrDefault(control,navigation);
        component.scrollRectToVisible(new Rectangle(0,0,component.getWidth(),component.getHeight())); return component;
    }
    private void loadDisplay() {
        if (!(scope.getSelectedItem() instanceof Profile profile)) return;
        loading=true;
        displayProfile=profile.id(); Display value=displays.get(profile.id());
        palette.setSelectedItem(value.palette()); effect.setSelectedItem(value.effect()); loading=false;
    }
    private void displayChanged(String action) {
        if (loading || displayProfile==null) return;
        displays.put(displayProfile,new Display((String)palette.getSelectedItem(),(String)effect.getSelectedItem()));
        refresh(); workspace.tutorialAction(action);
    }
    private void paletteEnabled() {
        boolean custom=theme.getSelectedItem() instanceof ColorTheme;
        palette.setEnabled(!custom);
        paletteStatus.setText(custom ? "This custom application theme supplies host colors. Choose a built-in theme to use the profile palette." : "");
        paletteStatus.setVisible(custom);
    }
    private void designTheme() {
        if (dirty() || saving()) return;
        workspace.executeCommand("Theme designer...");
        loadThemes(); saved=draft(); baseline=currentShared(); paletteEnabled(); refresh(); designer.requestFocusInWindow();
    }
    private void addPage(String name,JComponent body,boolean scroll) {
        JPanel page=new JPanel(new BorderLayout(0,14));
        JLabel heading=new JLabel(name); heading.setFont(heading.getFont().deriveFont(Font.BOLD,16f)); page.add(heading,BorderLayout.NORTH);
        if (scroll) {
            JPanel top=new JPanel(new BorderLayout()); top.add(body,BorderLayout.NORTH);
            JScrollPane view=new JScrollPane(top); view.setBorder(BorderFactory.createEmptyBorder());
            view.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); view.getVerticalScrollBar().setUnitIncrement(24);
            page.add(view,BorderLayout.CENTER);
        } else page.add(body,BorderLayout.CENTER);
        pages.add(page,name);
    }
    JPanel appearancePage() {
        JPanel body=column(); body.add(section("Colors"));
        body.add(row("Application theme",theme)); body.add(row("Terminal profile",scope));
        body.add(note("Palette and effect apply to the selected profile, including its other panes. This does not switch your session."));
        body.add(row("Text palette",palette)); body.add(paletteStatus); Ui.role(paletteStatus,"muted");
        body.add(row("Text effect (opt-in)",effect));
        body.add(row("Cursor style",cursorStyle));
        cursorStyle.setToolTipText("Replace mode uses an underline or outline; Insert fills the cell. Filled uses a block in both modes.");
        body.add(row("Custom theme",designer));
        targets.put("theme",theme); targets.put("palette",palette); targets.put("effect",effect); targets.put("scope",scope);
        body.add(section("Terminal font"));
        body.add(row("Monospaced family",family)); targets.put("font",family);
        JPanel file=new JPanel(new BorderLayout(8,0)); file.add(fontPath,BorderLayout.CENTER);
        file.add(Ui.button("Browse...",0,() -> {
            JFileChooser chooser=new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("Local monospaced fonts (*.ttf, *.otf)","ttf","otf"));
            if (Ui.openFile(dialog,"Choose terminal font",chooser)) fontPath.setText(chooser.getSelectedFile().getAbsolutePath());
        }),BorderLayout.EAST);
        fontPath.getAccessibleContext().setAccessibleName("Local custom TTF or OTF font path");
        body.add(row("Custom file (optional)",file));
        JLabel preview=new JLabel("Aa 0O 1Il | 0123456789 [] {} @ #"); preview.setFont(new Font(Font.MONOSPACED,Font.PLAIN,16));
        JPanel sample=new JPanel(new BorderLayout(8,0)); sample.add(preview,BorderLayout.CENTER);
        sample.add(Ui.button("Check font",0,() -> {
            try {
                Font font=TerminalFonts.resolve(fontChoice()); TerminalFonts.validate(font,workspace.profileList());
                preview.setFont(font); fontStatus.setText(""); fontStatus.setVisible(false);
            } catch (IOException | IllegalArgumentException e) {
                fontStatus.setText(e.getMessage()); fontStatus.setToolTipText(e.getMessage()); fontStatus.setVisible(true);
            }
        }),BorderLayout.EAST);
        body.add(sample); Ui.role(fontStatus,"error"); fontStatus.setVisible(false); body.add(fontStatus);
        body.add(section("Pane information"));
        JPanel columns=new JPanel(new GridLayout(1,2,20,0));
        for (int group=0;group<2;group++) {
            JPanel part=column(); int start=group*5;
            for (int n=start;n<start+5;n++) part.add(info.get(n));
            if (group==1) part.add(info.get(10));
            Runnable dependencies=() -> {
                for (int n=start+1;n<start+5;n++) info.get(n).setEnabled(info.get(start).isSelected());
                if (start==5) info.get(10).setEnabled(info.get(5).isSelected());
            };
            info.get(start).addActionListener(e -> dependencies.run()); dependencies.run(); columns.add(part);
        }
        targets.put("header",info.get(0)); targets.put("footer",info.get(5)); body.add(columns);
        body.add(section("Window and focus")); body.add(window); body.add(pane); body.add(tint); targets.put("outline",window);
        return body;
    }
    private JPanel general() {
        JPanel body=column(); body.add(section("Startup")); body.add(startup); targets.put("startup",startup);
        body.add(note("Controls ordinary restored-window startup. Existing off choices are kept. Explicit +, New window and saved-layout launches still connect once; failures offer Reconnect."));
        body.add(section("Reset"));
        body.add(Ui.button("Revert to factory defaults...",0,workspace::factoryDefaults));
        body.add(note("After confirmation, ends sessions and exits. Profiles and named layouts are cleared. Windows passwords, local font files and exported shortcut files are kept."));
        return body;
    }
    private JPanel updates() {
        JPanel body=new JPanel(new BorderLayout(0,16)); JPanel settings=column();
        settings.add(checkUpdates); settings.add(row("Update channel",channel));
        settings.add(note("Preview: patches in this minor version. Stable: releases in this major version. No downgrade, reinstall, or automatic new experimental minor."));
        JLabel hidden=new JLabel(ignored.isEmpty() ? "No hidden version" : "Hidden on startup: "+ignored);
        JPanel visibility=new JPanel(new BorderLayout(8,0)); visibility.add(hidden,BorderLayout.CENTER);
        visibility.add(Ui.button("Show hidden updates",0,() -> { ignored=""; hidden.setText("No hidden version"); edited(false); }),BorderLayout.EAST);
        settings.add(visibility); body.add(settings,BorderLayout.NORTH);
        body.add(workspace.updateController().panel(dialog,() -> dirty() || saving() || profiles.dirty(),
                () -> (UpdateSettings.Channel)channel.getSelectedItem()),BorderLayout.CENTER);
        return body;
    }
    private static JPanel row(String title,JComponent input) {
        JPanel row=new JPanel(new BorderLayout(12,0)); JLabel label=new JLabel(title); label.setLabelFor(input);
        label.setPreferredSize(new Dimension(166,28)); row.add(label,BorderLayout.WEST); row.add(input,BorderLayout.CENTER);
        if (input.getAccessibleContext().getAccessibleName()==null) input.getAccessibleContext().setAccessibleName(title); return row;
    }
    private static JPanel section(String name) {
        JPanel panel=new JPanel(new BorderLayout(10,0)); JLabel label=new JLabel(name);
        label.setFont(label.getFont().deriveFont(Font.BOLD)); panel.add(label,BorderLayout.WEST); panel.add(new JSeparator(),BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createEmptyBorder(10,0,2,0)); return panel;
    }
    private static JPanel column() {
        return new JPanel(new GridBagLayout()) {
            @Override public Component add(Component child) {
                GridBagConstraints cell=new GridBagConstraints(); cell.gridx=0; cell.gridy=getComponentCount(); cell.weightx=1;
                cell.fill=GridBagConstraints.HORIZONTAL; cell.anchor=GridBagConstraints.NORTHWEST; cell.insets=new Insets(0,0,9,0);
                super.add(child,cell); return child;
            }
        };
    }
    private static JTextArea note(String value) {
        JTextArea area=new JTextArea(value,2,36); area.setEditable(false); area.setFocusable(false); area.setLineWrap(true);
        area.setWrapStyleWord(true); area.setOpaque(false); area.setBorder(BorderFactory.createEmptyBorder()); Ui.role(area,"muted"); return area;
    }
    private void loadThemes() {
        theme.removeAllItems(); for (AppTheme value:AppTheme.values()) theme.addItem(value);
        workspace.appearanceSettings().themes().forEach(theme::addItem);
        theme.setSelectedItem(workspace.appearanceSettings().custom().<Object>map(t -> t).orElse(Ui.theme()));
    }
    private Appearance.FontChoice fontChoice() {
        return new Appearance.FontChoice(Objects.toString(family.getSelectedItem(),"Automatic"),fontPath.getText().strip());
    }
    private Draft draft() {
        return new Draft(startup.isSelected(),theme.getSelectedItem(),fontChoice(),window.isSelected(),pane.isSelected(),tint.isSelected(),
                new Appearance.PaneInfo(info.get(0).isSelected(),info.get(5).isSelected(),info.get(1).isSelected(),info.get(2).isSelected(),
                        info.get(3).isSelected(),info.get(4).isSelected(),info.get(6).isSelected(),info.get(7).isSelected(),info.get(8).isSelected(),info.get(9).isSelected(),info.get(10).isSelected()),
                (Appearance.CursorStyle)cursorStyle.getSelectedItem(),
                new UpdateSettings(checkUpdates.isSelected(),(UpdateSettings.Channel)channel.getSelectedItem(),ignored),keyboard.draft(),Map.copyOf(displays));
    }
    private boolean dirty() { try { return unsaved || conflict || !draft().equals(saved); } catch (IllegalArgumentException e) { return true; } }
    private void edited(boolean typing) {
        if (saved==null || ended || loading || syncing || applying) return;
        if (displayProfile!=null) displays.put(displayProfile,new Display((String)palette.getSelectedItem(),(String)effect.getSelectedItem()));
        if (typing) { editPending=true; edits.restart(); refresh(); }
        else apply();
    }
    private void refresh() {
        if (saved==null || closeButton==null) return;
        boolean changed=dirty();
        designer.setEnabled(!changed && !saving());
        designer.setToolTipText(changed ? "Finish or repair pending edits before opening the theme designer." : null);
        retryButton.setVisible(!saveFailure.isEmpty());
        reloadButton.setVisible(conflict || !validation.isEmpty());
        String text=!saveFailure.isEmpty() ? "Not saved: "+saveFailure : profiles.failedSave() ? "Profile not saved; use Retry save in Profiles."
                : conflict ? "Changed elsewhere. Reload saved values to review; your edits are kept here."
                : !validation.isEmpty() ? "Not applied: "+validation : profiles.saving() ? "Saving profile..."
                : profiles.dirty() ? "Profile edits need review in Profiles."
                : saving || unsaved || editPending ? "Saving..." : "Saved";
        error.setText(text); error.setToolTipText(text);
        Ui.role(error,!saveFailure.isEmpty() || profiles.failedSave() || conflict || !validation.isEmpty() ? "error" : "muted");
        workspace.updateController().refreshAvailability();
        workspace.refreshActions();
    }
    private boolean apply() { return apply(false); }
    private boolean apply(boolean finish) {
        if (ended || applying) return false;
        edits.stop(); editPending=false;
        try {
            if (!Objects.equals(baseline,currentShared())) { conflict=true; refresh(); return false; }
            if (conflict) { refresh(); return false; }
            validation="";
            Appearance.FontChoice font=saved.font();
            try {
                font=fontChoice();
                if (!font.equals(saved.font())) { Font resolved=TerminalFonts.resolve(font); TerminalFonts.validate(resolved,workspace.profileList()); }
            } catch (IOException | IllegalArgumentException e) { validation=e.getMessage(); font=saved.font(); }
            Map<String,String> keys=saved.keys();
            try { keys=keyboard.draft(); }
            catch (IllegalArgumentException e) { validation=e.getMessage(); }
            Draft raw=new Draft(startup.isSelected(),theme.getSelectedItem(),font,window.isSelected(),pane.isSelected(),tint.isSelected(),
                    new Appearance.PaneInfo(info.get(0).isSelected(),info.get(5).isSelected(),info.get(1).isSelected(),info.get(2).isSelected(),
                            info.get(3).isSelected(),info.get(4).isSelected(),info.get(6).isSelected(),info.get(7).isSelected(),info.get(8).isSelected(),info.get(9).isSelected(),info.get(10).isSelected()),
                    (Appearance.CursorStyle)cursorStyle.getSelectedItem(),
                    new UpdateSettings(checkUpdates.isSelected(),(UpdateSettings.Channel)channel.getSelectedItem(),ignored),keys,Map.copyOf(displays));
            Draft next=raw;
            for (Profile profile:workspace.profileList()) {
                Display desired=next.displays().get(profile.id()),previous=saved.displays().get(profile.id());
                Display live=new Display(profile.theme(),profile.effect());
                if (!Objects.equals(desired,previous) && !Objects.equals(live,previous) && !Objects.equals(live,desired))
                    throw new IllegalArgumentException("Terminal colors/effect changed for "+profile.name()+". Reload saved values to review them.");
            }
            for (var entry:next.displays().entrySet()) {
                Display original=originalDisplays.get(entry.getKey()); if (original.equals(entry.getValue())) continue;
                Profile current=workspace.profileList().stream().filter(p -> p.id().equals(entry.getKey())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("The appearance profile was deleted. Reopen Settings to choose another."));
                if (!original.equals(new Display(current.theme(),current.effect())))
                    throw new IllegalArgumentException("Profile colors changed elsewhere. Reopen Settings to review them.");
            }
            applying=true;
            if (!next.equals(saved)) {
                Appearance previous=workspace.appearanceSettings();
                Appearance value=new Appearance(next.pane(),next.window(),next.info(),next.font(),
                        next.theme() instanceof ColorTheme custom ? custom.name() : "",previous.themes(),previous.retiredShortcuts(),next.cursorStyle());
                workspace.beginSettings();
                try {
                if (!next.keys().equals(saved.keys())) workspace.applyShortcuts(next.keys());
                if (!value.equals(previous)) workspace.appearanceSettings(value);
                if (next.theme() instanceof AppTheme app && (Ui.theme()!=app || Ui.customTheme())) workspace.applyTheme(app);
                if (next.tint()!=workspace.highlightActive()) workspace.highlightActive(next.tint());
                if (next.startup()!=workspace.connectOnStartup()) workspace.connectOnStartup(next.startup());
                if (!next.updates().equals(workspace.updateSettings())) workspace.updateSettings(next.updates());
                for (var entry:next.displays().entrySet()) if (!entry.getValue().equals(originalDisplays.get(entry.getKey()))) {
                    Profile profile=workspace.profileList().stream().filter(p -> p.id().equals(entry.getKey())).findFirst().orElseThrow();
                    workspace.putProfile(profile.withDisplay(entry.getValue().palette(),entry.getValue().effect()));
                }
                } finally { workspace.endSettings(); }
                originalDisplays.clear(); originalDisplays.putAll(next.displays());
                saved=next; baseline=currentShared(); unsaved=true; generation++;
            }
            applying=false;
            if (unsaved) writes.restart();
            refresh();
            return true;
        } catch (IllegalArgumentException e) {
            applying=false; validation=e.getMessage(); refresh(); return false;
        }
    }
    private void persist() {
        writes.stop();
        if (saving || !unsaved || ended) return;
        long written=generation; saving=true; refresh();
        workspace.persistSettings(() -> {
            saving=false; saveFailure=""; unsaved=written!=generation;
            refresh(); workspace.tutorialAction("settings-applied");
            if (unsaved) writes.restart();
            else if (closeRequested) dialog.dispose();
        },message -> {
            saving=false; closeRequested=false; saveFailure=message; refresh();
            if (written!=generation) writes.restart();
        });
    }
    private boolean requestClose() {
        profiles.flush();
        if (saving || profiles.saving()) { closeRequested=true; return false; }
        if (!saveFailure.isEmpty() || profiles.failedSave()) {
            closeRequested=false;
            return Ui.confirm(dialog,"Settings could not be saved. Close without saving?\n"
                    +"Applied changes remain in memory, but unsaved edits may be lost when Fish exits.\n"
                    +"Cancel to keep this recovery state open and fix the error before Retry save.","Settings not saved");
        }
        apply();
        if (saving || unsaved) { closeRequested=true; persist(); return false; }
        if ((conflict || !validation.isEmpty() || profiles.dirty()) && !Ui.confirm(dialog,
                "Some edits could not be saved or need an explicit profile/login action.\nClose and discard only those edits? Already saved changes stay applied.","Unsaved edits")) return false;
        closeRequested=false; return true;
    }
    private void reload() {
        edits.stop(); editPending=false; loading=true;
        try {
            Appearance a=workspace.appearanceSettings();
            cursorStyle.setSelectedItem(a.cursorStyle());
            startup.setSelected(workspace.connectOnStartup()); window.setSelected(a.windowOutline()); pane.setSelected(a.outline());
            tint.setSelected(workspace.highlightActive()); checkUpdates.setSelected(workspace.updateSettings().startup());
            channel.setSelectedItem(workspace.updateSettings().channel()); ignored=workspace.updateSettings().ignoredVersion();
            loadThemes(); family.setSelectedItem(a.font().family()); fontPath.setText(a.font().file());
            Appearance.PaneInfo i=a.info();
            boolean[] flags={i.header(),i.profile(),i.connection(),i.host(),i.device(),i.footer(),i.status(),i.dimensions(),i.cursor(),i.insert(),i.footerDevice()};
            for (int n=0;n<flags.length;n++) info.get(n).setSelected(flags[n]);
            keyboard.reload(workspace.shortcutOverrides());
            displays.clear(); originalDisplays.clear();
            for (Profile p:workspace.profileList()) { Display display=new Display(p.theme(),p.effect()); displays.put(p.id(),display); originalDisplays.put(p.id(),display); }
            loadDisplay(); saved=draft(); baseline=currentShared(); conflict=false; validation="";
        } finally { loading=false; }
        refresh();
    }
    void sharedChanged() {
        if (saved==null || applying || syncing || ended) return;
        if (!Objects.equals(baseline,currentShared())) {
            boolean pending;
            try { pending=conflict || !draft().equals(saved); }
            catch (IllegalArgumentException e) { pending=true; }
            if (!pending) reload();
            else { conflict=true; edits.stop(); editPending=false; refresh(); }
        }
    }
}
