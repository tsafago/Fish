package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.JTextComponent;
import pane5250.model.Profile;

public final class ProfilesDialog extends JDialog {
    private final Workspace workspace;
    private final DefaultListModel<Profile> model = new DefaultListModel<>();
    private final JList<Profile> list = new JList<>(model);
    private final JTextField name = new JTextField(24), host = new JTextField(24), port = new JTextField(6);
    private final JTextField device = new JTextField(16), username = new JTextField(16);
    private final JPasswordField password = new JPasswordField(24);
    private final JCheckBox automatic = new JCheckBox("Automatic sign-in (recommended)");
    private final JCheckBox remember = new JCheckBox("Save password in Windows Credential Manager (recommended)");
    private final JComboBox<Object> loginFrom=new JComboBox<>();
    private JLabel loginLabel;
    private final JButton recommended;
    private final JTextArea loginHint=new JTextArea(7,36);
    private final JComboBox<String> codePage = new JComboBox<>(Profile.CODE_PAGES.toArray(String[]::new));
    private final JComboBox<String> theme = new JComboBox<>(Profile.THEMES.toArray(String[]::new));
    private final JComboBox<String> effect = new JComboBox<>(Profile.EFFECTS.toArray(String[]::new));
    private final JTextArea keymap = new JTextArea(8,32);
    private final JLabel error = new JLabel(" ");
    private final JButton save, done, use, cancel, add, duplicate;
    private Profile selected;
    private java.util.List<Object> baseline;
    private boolean loading, dirty, saving;

    public ProfilesDialog(Workspace workspace, Profile initial) {
        super(workspace,"Workstation profiles",true);
        this.workspace = workspace;
        workspace.profileList().forEach(model::addElement);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.getAccessibleContext().setAccessibleName("Saved workstation profiles");
        list.setFixedCellHeight(28);
        JPanel sidebar = new JPanel(new BorderLayout(0,8));
        sidebar.setPreferredSize(new Dimension(180,300));
        sidebar.add(new JScrollPane(list),BorderLayout.CENTER);
        JPanel listActions = new JPanel(new GridLayout(1,2,6,0));
        add = Ui.button("New",KeyEvent.VK_N,() -> addProfile(false));
        duplicate = Ui.button("Duplicate",KeyEvent.VK_D,() -> addProfile(true));
        listActions.add(add); listActions.add(duplicate);
        sidebar.add(listActions,BorderLayout.SOUTH);
        JTabbedPane tabs = new JTabbedPane();
        JPanel connection = new JPanel(new GridBagLayout());
        connection.setBorder(Ui.padding(12));
        int row = 0;
        field(connection,row++,"Name",name,KeyEvent.VK_A);
        field(connection,row++,"Host",host,KeyEvent.VK_H);
        field(connection,row++,"Port",port,KeyEvent.VK_O);
        field(connection,row++,"Device (optional)",device,KeyEvent.VK_E);
        loginLabel=field(connection,row++,"Use login from...",loginFrom,KeyEvent.VK_F);
        field(connection,row++,"Username",username,KeyEvent.VK_U);
        field(connection,row++,"Code page",codePage,KeyEvent.VK_C);
        recommended=Ui.button("Use recommended sign-in",KeyEvent.VK_I,() -> {
            automatic.setSelected(true); remember.setSelected(true); username.requestFocusInWindow();
        });
        wide(connection,row++,recommended);
        wide(connection,row++,automatic);
        wide(connection,row++,remember);
        field(connection,row++,"Remembered password",password,KeyEvent.VK_W);
        loginHint.setEditable(false); loginHint.setFocusable(false); loginHint.setLineWrap(true); loginHint.setWrapStyleWord(true);
        loginHint.setOpaque(false); loginHint.setBorder(BorderFactory.createEmptyBorder());
        Ui.role(loginHint,"muted");
        password.setToolTipText("Blank keeps an existing OS password. Changing the independent host/port/username requires entering it again.");
        wide(connection,row,loginHint);
        JScrollPane connectionScroll=new JScrollPane(connection);
        connectionScroll.setBorder(BorderFactory.createEmptyBorder());
        connectionScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        tabs.addTab("Connection",connectionScroll); tabs.setMnemonicAt(0,KeyEvent.VK_1);
        JPanel display = new JPanel(new GridBagLayout());
        display.setBorder(Ui.padding(12));
        field(display,0,"Palette",theme,KeyEvent.VK_T);
        field(display,1,"Text effect",effect,KeyEvent.VK_F);
        wide(display,2,new JLabel("<html>Host colors, reverse video, underline and hidden fields remain distinct.<br>"
                + "Effects are off by default. Ctrl+0 fits without changing host dimensions.</html>"));
        tabs.addTab("Display",display); tabs.setMnemonicAt(1,KeyEvent.VK_2);
        JPanel keyboard = new JPanel(new BorderLayout(0,10));
        keyboard.setBorder(Ui.padding(12));
        JTextArea defaults = new JTextArea(Keymap.describeDefaults());
        defaults.setEditable(false);
        defaults.setFocusable(false);
        defaults.setFont(new Font(Font.MONOSPACED,Font.PLAIN,11));
        defaults.setBackground(Ui.PANEL);
        defaults.setBorder(BorderFactory.createEmptyBorder());
        keyboard.add(defaults,BorderLayout.NORTH);
        keymap.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));
        keymap.getAccessibleContext().setAccessibleName("Keyboard overrides: KEY = ACTION");
        keyboard.add(new JScrollPane(keymap),BorderLayout.CENTER);
        keyboard.add(Ui.button("All actions / global shortcuts...",KeyEvent.VK_J,() -> workspace.showShortcuts()),BorderLayout.SOUTH);
        tabs.addTab("Keyboard",keyboard); tabs.setMnemonicAt(2,KeyEvent.VK_3);
        JPanel body = new JPanel(new BorderLayout(12,0));
        body.setBorder(Ui.padding(12));
        body.add(sidebar,BorderLayout.WEST); body.add(tabs,BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(8,0));
        bottom.setBorder(BorderFactory.createEmptyBorder(0,12,12,12));
        Ui.role(error,"error");
        bottom.add(error,BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        cancel = Ui.button("Cancel",KeyEvent.VK_L,this::dismiss);
        save = Ui.button("Apply",KeyEvent.VK_S,() -> save(false,false));
        done = Ui.button("Done",KeyEvent.VK_D,() -> { if (dirty) save(false,true); else dismiss(); });
        use = Ui.button("Save & connect",KeyEvent.VK_G,() -> save(true,true));
        buttons.add(cancel); buttons.add(save); buttons.add(done); buttons.add(use); bottom.add(buttons,BorderLayout.EAST);
        add(body,BorderLayout.CENTER); add(bottom,BorderLayout.SOUTH);
        getRootPane().setDefaultButton(done);
        getRootPane().registerKeyboardAction(e -> dismiss(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        DocumentListener changes = new DocumentListener() {
            private void changed() { if (!loading) updateDirty(); }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        };
        for (JTextComponent text : java.util.List.of(name,host,port,device,username,password,keymap)) text.getDocument().addDocumentListener(changes);
        for (JComboBox<String> combo : java.util.List.of(codePage,theme,effect)) combo.addActionListener(e -> { if (!loading) updateDirty(); });
        automatic.addItemListener(e -> { if (!loading) updateDirty(); }); automatic.setMnemonic(KeyEvent.VK_B);
        remember.addItemListener(e -> { if (!loading) updateDirty(); }); remember.setMnemonic(KeyEvent.VK_R);
        loginFrom.addActionListener(e -> {
            if (loading) return;
            loading=true;
            if (loginFrom.getSelectedItem() instanceof Profile source) {
                username.setText(source.username()); automatic.setSelected(source.automaticSignOn()); remember.setSelected(source.rememberPassword());
            } else remember.setSelected(false);
            password.setText("");
            loading=false; updateLoginControls(); updateDirty();
        });
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || loading || list.getSelectedValue() == null) return;
            if (!discardChanges()) {
                loading = true; list.setSelectedValue(selected,true); loading = false; return;
            }
            load(list.getSelectedValue());
        });
        selected = initial;
        list.setSelectedValue(initial,true);
        if (name.getText().isEmpty()) load(initial);
        Ui.decorate(this,this::dismiss);
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { name.requestFocusInWindow(); }
        });
        setSize(850,650);
        setMinimumSize(new Dimension(760,550));
        setLocationRelativeTo(workspace);
    }

    private void load(Profile profile) {
        selected = profile;
        loading = true;
        name.setText(profile.name()); host.setText(profile.host()); port.setText(Integer.toString(profile.port()));
        device.setText(profile.device()); username.setText(profile.username()); password.setText("");
        loginFrom.removeAllItems(); loginFrom.addItem("Independent login");
        for (Profile source:workspace.profileList()) if (!source.id().equals(profile.id()) && source.loginSource()==null
                && !source.username().isEmpty() && source.rememberPassword()) loginFrom.addItem(source);
        Profile login=workspace.loginProfile(profile);
        if (profile.loginSource()!=null) loginFrom.setSelectedItem(login);
        loginFrom.setVisible(loginFrom.getItemCount()>1); loginLabel.setVisible(loginFrom.isVisible());
        username.setText(login.username());
        automatic.setSelected(login.automaticSignOn()); remember.setSelected(login.rememberPassword());
        codePage.setSelectedItem(profile.codePage()); theme.setSelectedItem(profile.theme()); effect.setSelectedItem(profile.effect());
        StringBuilder text = new StringBuilder();
        profile.keymap().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> text.append(e.getKey()).append(" = ").append(e.getValue()).append('\n'));
        keymap.setText(text.toString());
        error.setText(" ");
        baseline=fields(); dirty = false; loading = false; updateLoginControls(); updateDirty();
    }
    private UUID loginSource() { return loginFrom.getSelectedItem() instanceof Profile source ? source.id() : null; }
    private void updateLoginControls() {
        boolean independent=loginSource()==null;
        for (JComponent input:java.util.List.of(username,password,automatic,remember,recommended)) input.setEnabled(!saving && independent);
        recommended.setVisible(independent);
        loginHint.setText(independent
                ? "Recommended on your trusted IBM i network: automatic sign-in + Save password. Passwords stay in Windows Credential Manager. Blank keeps an existing OS password. Plain TCP is unencrypted; automatic sign-in needs host support for RFC 4777 plain sign-on (1-128 printable ASCII characters). Manual sign-in remains available."
                : "This profile uses the selected login for its own host when you connect. Username, sign-in preferences and the OS password come from that source; the password is not copied. A missing OS password prompts on Connect. Select Independent login to stop sharing. Plain TCP remains unencrypted.");
        loginHint.setCaretPosition(0);
    }
    private java.util.List<Object> fields() {
        return java.util.List.of(name.getText(),host.getText(),port.getText(),device.getText(),username.getText(),
                password.getDocument().getLength(),automatic.isSelected(),remember.isSelected(),
                codePage.getSelectedItem(),theme.getSelectedItem(),effect.getSelectedItem(),keymap.getText(),
                Objects.toString(loginSource(),""));
    }
    private void updateDirty() {
        dirty=baseline==null || !baseline.equals(fields()) || workspace.profileList().stream().noneMatch(p -> p.id().equals(selected.id()));
        save.setEnabled(dirty && !saving);
    }
    private boolean discardChanges() {
        return !dirty || Ui.confirm(this,"Discard unsaved profile edits?","Unsaved edits");
    }
    private void dismiss() {
        if (!saving && discardChanges()) { password.setText(""); dispose(); }
    }
    private void addProfile(boolean copy) {
        if (!discardChanges()) return;
        Profile profile = copy ? workspace.connectionProfile(selected).duplicate() : Profile.create("New workstation");
        loading = true; model.addElement(profile); list.setSelectedValue(profile,true); loading = false;
        load(profile); updateDirty(); name.requestFocusInWindow(); name.selectAll();
    }
    private void save(boolean connect,boolean finish) {
        Profile updated;
        char[] secret = password.getPassword();
        try {
            UUID source=loginSource();
            updated = new Profile(selected.id(),name.getText(),host.getText(),Integer.parseInt(port.getText().strip()),
                    device.getText(),source==null ? username.getText() : "",source==null && automatic.isSelected(),source==null && remember.isSelected(),
                    Objects.requireNonNull((String)codePage.getSelectedItem()),Objects.requireNonNull((String)theme.getSelectedItem()),
                    Objects.requireNonNull((String)effect.getSelectedItem()),selected.zoom(),Keymap.parse(keymap.getText()),selected.savedLayout(),source);
            workspace.validateProfile(updated);
            if (secret.length > 0 && !updated.rememberPassword())
                throw new IllegalArgumentException("Enable Remember password to save this password, or clear the password field.");
        } catch (IllegalArgumentException e) {
            Arrays.fill(secret,'\0');
            error.setText(e instanceof NumberFormatException ? "Port must be a number." : e.getMessage());
            error.setToolTipText(error.getText());
            return;
        }
        Profile previous = selected;
        setSaving(true);
        new SwingWorker<Void,Void>() {
            @Override protected Void doInBackground() throws java.io.IOException {
                if (updated.rememberPassword() && secret.length > 0) workspace.credentials().write(updated,secret);
                if (previous.rememberPassword() && (!updated.rememberPassword()
                        || !previous.credentialTarget().equals(updated.credentialTarget()))) workspace.credentials().delete(previous);
                return null;
            }
            @Override protected void done() {
                Arrays.fill(secret,'\0');
                password.setText("");
                setSaving(false);
                try {
                    get();
                    workspace.putProfile(updated);
                    dirty = false;
                    if (finish) { dispose(); if (connect) workspace.useProfile(updated); }
                    else {
                        int index = list.getSelectedIndex();
                        loading = true; model.set(index,updated); loading = false;
                        load(updated);
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    error.setText("Not saved: " + cause.getMessage());
                    error.setToolTipText(error.getText());
                }
            }
        }.execute();
    }
    private void setSaving(boolean value) {
        saving = value;
        for (JButton button : java.util.List.of(save,done,use,cancel,add,duplicate)) button.setEnabled(!value);
        for (JComponent input:java.util.List.of(name,host,port,device,username,password,keymap,codePage,theme,effect,automatic,remember,loginFrom,recommended))
            input.setEnabled(!value);
        updateLoginControls();
        if (!value) updateDirty();
        list.setEnabled(!value);
        error.setText(value ? "Saving..." : " ");
    }
    private static JLabel field(JPanel panel,int row,String text,JComponent component,int mnemonic) {
        JLabel label = new JLabel(text); label.setLabelFor(component); label.setDisplayedMnemonic(mnemonic);
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0; left.gridy = row; left.anchor = GridBagConstraints.LINE_START; left.insets = new Insets(0,0,10,12);
        panel.add(label,left);
        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1; right.gridy = row; right.weightx = 1; right.fill = GridBagConstraints.HORIZONTAL; right.insets = new Insets(0,0,10,0);
        panel.add(component,right);
        return label;
    }
    private static void wide(JPanel panel,int row,JComponent component) {
        GridBagConstraints cell = new GridBagConstraints();
        cell.gridx = 0; cell.gridy = row; cell.gridwidth = 2; cell.weightx = 1; cell.anchor = GridBagConstraints.LINE_START;
        cell.fill = GridBagConstraints.HORIZONTAL; cell.insets = new Insets(0,0,10,0);
        panel.add(component,cell);
    }
}
