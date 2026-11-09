package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.JTextComponent;
import pane5250.model.Profile;

final class ProfilesEditor extends JPanel {
    private final Workspace workspace;
    private final Runnable close;
    private final DefaultListModel<Profile> model = new DefaultListModel<>();
    private final JList<Profile> list = new JList<>(model);
    private final JTextField name = new JTextField(24), host = new JTextField(24), port = new JTextField(6);
    private final JTextField device = new JTextField(16), username = new JTextField(16);
    private final JPasswordField password = new JPasswordField(24);
    private final JComboBox<Profile.Authentication> authentication=new JComboBox<>(Profile.Authentication.values());
    private final JTextField kerberosServer=new JTextField(24);
    private JLabel kerberosLabel,usernameLabel,passwordLabel;
    private final JCheckBox automatic = new JCheckBox("Automatic sign-in (recommended)");
    private final JCheckBox remember = new JCheckBox("Save password (recommended)");
    private final JComboBox<Object> loginFrom=new JComboBox<>();
    private JLabel loginLabel;
    private final JTextArea loginHint=new JTextArea(3,36);
    private final JPanel connection=new JPanel(new GridBagLayout());
    private final JPanel advanced=new JPanel(new GridBagLayout());
    private final JPanel advancedIdentity=new JPanel(new BorderLayout());
    private final JPanel identity=new JPanel(new BorderLayout(12,0));
    private final JToggleButton disclosure=new JToggleButton("Advanced");
    private final JComboBox<String> codePage = new JComboBox<>(Profile.CODE_PAGES.toArray(String[]::new));
    private final JLabel error = new JLabel(" ");
    private final JButton save, done, use, cancel, add, duplicate, up, down, delete;
    private Profile selected;
    private java.util.List<Object> baseline;
    private boolean loading, dirty, saving,passwordModeInitialized;

    ProfilesEditor(Workspace workspace, Profile initial,boolean embedded,Runnable close) {
        super(new BorderLayout());
        this.workspace = workspace;
        this.close=close;
        workspace.profileList().forEach(model::addElement);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.getAccessibleContext().setAccessibleName("Saved workstation profiles");
        list.setFixedCellHeight(28);
        JPanel sidebar = new JPanel(new BorderLayout(0,8));
        sidebar.setPreferredSize(new Dimension(180,300));
        sidebar.add(new JScrollPane(list),BorderLayout.CENTER);
        JPanel listActions = new JPanel(new GridLayout(0,1,0,6));
        JPanel createActions=new JPanel(new GridLayout(1,2,6,0));
        add = Ui.button("New",KeyEvent.VK_N,() -> addProfile(false));
        duplicate = Ui.button("Duplicate",KeyEvent.VK_D,() -> addProfile(true));
        createActions.add(add); createActions.add(duplicate); listActions.add(createActions);
        JPanel manageActions=new JPanel(new GridLayout(1,3,4,0));
        up=Ui.button("Up",0,() -> move(-1));
        down=Ui.button("Down",0,() -> move(1));
        delete=Ui.button("Delete",0,this::delete);
        up.setToolTipText("Move profile up (Ctrl+Up in the list); order applies immediately");
        down.setToolTipText("Move profile down (Ctrl+Down in the list); order applies immediately");
        up.getAccessibleContext().setAccessibleName("Move profile up");
        down.getAccessibleContext().setAccessibleName("Move profile down");
        manageActions.add(up); manageActions.add(down); manageActions.add(delete); listActions.add(manageActions);
        list.getInputMap().put(KeyStroke.getKeyStroke("ctrl UP"),"move-up");
        list.getInputMap().put(KeyStroke.getKeyStroke("ctrl DOWN"),"move-down");
        list.getInputMap().put(KeyStroke.getKeyStroke("DELETE"),"delete-profile");
        list.getActionMap().put("move-up",new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { up.doClick(); } });
        list.getActionMap().put("move-down",new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { down.doClick(); } });
        list.getActionMap().put("delete-profile",new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { delete.doClick(); } });
        sidebar.add(listActions,BorderLayout.SOUTH);
        connection.setBorder(Ui.padding(12));
        int row = 0;
        field(connection,row++,"Name",name,KeyEvent.VK_A);
        field(connection,row++,"Host",host,KeyEvent.VK_H);
        field(connection,row++,"Sign in with",authentication,KeyEvent.VK_I);
        kerberosLabel=new JLabel("Kerberos server"); kerberosLabel.setLabelFor(kerberosServer); kerberosLabel.setDisplayedMnemonic(KeyEvent.VK_K);
        identity.add(kerberosLabel,BorderLayout.WEST); identity.add(kerberosServer,BorderLayout.CENTER);
        advancedIdentity.add(identity,BorderLayout.CENTER);
        kerberosServer.setToolTipText("Optional fully qualified IBM i DNS name, separate from the TCP host. Blank: use the host name or forward-confirmed reverse DNS. No realm or SPN needed.");
        loginLabel=field(connection,row++,"Use login from...",loginFrom,KeyEvent.VK_F);
        usernameLabel=field(connection,row++,"Username",username,KeyEvent.VK_U);
        wide(connection,row++,automatic);
        wide(connection,row++,remember);
        passwordLabel=field(connection,row++,"Password",password,KeyEvent.VK_W);
        loginHint.setEditable(false); loginHint.setFocusable(false); loginHint.setLineWrap(true); loginHint.setWrapStyleWord(true);
        loginHint.setOpaque(false); loginHint.setBorder(BorderFactory.createEmptyBorder());
        Ui.role(loginHint,"muted");
        password.setToolTipText("Leave blank to keep a saved password. Automatic sign-in supports 1-128 printable ASCII characters.");
        wide(connection,row++,loginHint);
        disclosure.setMnemonic(KeyEvent.VK_V);
        disclosure.getAccessibleContext().setAccessibleName("Show advanced profile settings");
        disclosure.addActionListener(e -> showAdvanced(disclosure.isSelected()));
        disclosure.addActionListener(e -> { if (disclosure.isSelected()) workspace.tutorialAction("profile-advanced"); });
        disclosure.setIcon(Ui.symbol("right")); advanced.setVisible(false);
        wide(connection,row++,disclosure);
        wide(advanced,0,advancedIdentity);
        field(advanced,1,"Port",port,KeyEvent.VK_O);
        field(advanced,2,"Device (optional)",device,KeyEvent.VK_E);
        field(advanced,3,"Code page",codePage,KeyEvent.VK_C);
        wide(advanced,4,new JLabel("Colors and fonts: Settings > Appearance."));
        wide(connection,row,advanced);
        JPanel connectionTop=new JPanel(new BorderLayout());
        connectionTop.add(connection,BorderLayout.NORTH);
        JScrollPane connectionScroll=new JScrollPane(connectionTop);
        connectionScroll.setBorder(BorderFactory.createEmptyBorder());
        connectionScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JPanel body = new JPanel(new BorderLayout(12,0));
        body.setBorder(Ui.padding(12));
        body.add(sidebar,BorderLayout.WEST); body.add(connectionScroll,BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(8,0));
        bottom.setBorder(BorderFactory.createEmptyBorder(0,12,12,12));
        Ui.role(error,"error");
        bottom.add(error,BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        cancel = Ui.button("Cancel",KeyEvent.VK_L,this::dismiss);
        save = Ui.button("Apply",KeyEvent.VK_S,() -> save(false,false));
        done = Ui.button("Done",KeyEvent.VK_D,() -> { if (dirty) save(false,true); else dismiss(); });
        use = Ui.button("Save & connect",KeyEvent.VK_G,() -> save(true,true));
        buttons.add(cancel); buttons.add(save); buttons.add(done); buttons.add(use);
        if (!embedded) bottom.add(buttons,BorderLayout.EAST);
        add(body,BorderLayout.CENTER); add(bottom,BorderLayout.SOUTH);
        DocumentListener changes = new DocumentListener() {
            private void changed(DocumentEvent e) {
                if (!loading) {
                    updateIdentity(); updateDirty();
                    if (host.getDocument()==e.getDocument()) workspace.tutorialAction("profile-address");
                    if (username.getDocument()==e.getDocument()) workspace.tutorialAction("profile-username");
                }
            }
            @Override public void insertUpdate(DocumentEvent e) { changed(e); }
            @Override public void removeUpdate(DocumentEvent e) { changed(e); }
            @Override public void changedUpdate(DocumentEvent e) { changed(e); }
        };
        for (JTextComponent text : java.util.List.of(name,host,port,device,username,password,kerberosServer)) text.getDocument().addDocumentListener(changes);
        codePage.addActionListener(e -> { if (!loading) updateDirty(); });
        automatic.addItemListener(e -> { if (!loading) updateDirty(); }); automatic.setMnemonic(KeyEvent.VK_B);
        remember.addItemListener(e -> { if (!loading) updateDirty(); }); remember.setMnemonic(KeyEvent.VK_R);
        automatic.addActionListener(e -> workspace.tutorialAction("profile-login-options"));
        remember.addActionListener(e -> workspace.tutorialAction("profile-login-options"));
        authentication.addActionListener(e -> {
            if (loading) return;
            if (kerberos()) {
                loading=true; password.setText(""); loading=false;
            } else if (!passwordModeInitialized) {
                passwordModeInitialized=true;
                automatic.setSelected(true); remember.setSelected(true);
            }
            updateLoginControls(); updateDirty();
            if (!kerberos()) workspace.tutorialAction("profile-password-mode");
        });
        loginFrom.addActionListener(e -> {
            if (loading) return;
            loading=true;
            if (loginFrom.getSelectedItem() instanceof Profile source) {
                username.setText(source.username()); automatic.setSelected(source.automaticSignOn()); remember.setSelected(source.rememberPassword());
            } else { automatic.setSelected(true); remember.setSelected(true); passwordModeInitialized=true; }
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
    }
    JButton doneButton() { return done; }
    boolean dirty() { return dirty; }
    boolean saving() { return saving; }
    String error() { return error.getText(); }
    void discard() { password.setText(""); }
    void rebaseDisplay() {
        if (dirty) return;
        workspace.profileList().stream().filter(p -> p.id().equals(selected.id())).findFirst().ifPresent(current -> {
            if (!current.equals(selected) || !Collections.list(model.elements()).equals(workspace.profileList())) refreshList(current);
        });
    }
    void focusName() { name.requestFocusInWindow(); }
    Component tutorialTarget(String control) {
        JComponent target=switch(control) {
            case "new" -> add; case "authentication" -> authentication; case "host" -> host;
            case "username" -> username; case "password" -> password; case "remember" -> remember;
            case "advanced" -> disclosure; case "apply" -> save; default -> list;
        };
        target.scrollRectToVisible(new Rectangle(0,0,target.getWidth(),target.getHeight())); return target;
    }
    Component tutorialTarget(int step) {
        if (step==0) return cancel;
        if (step>=3) return done;
        JComponent target=step==1 ? host : kerberos() ? authentication : automatic;
        target.scrollRectToVisible(new Rectangle(0,0,target.getWidth(),target.getHeight()));
        return target;
    }
    String tutorialSignInText() {
        if (kerberos()) return "Kerberos uses your Windows sign-in. No password to type or save. Connect requests a ticket for this IBM i; your administrator must enable Kerberos and map your account. Advanced contains optional device, port and display settings. Terminal traffic is still unencrypted.";
        if (loginSource()!=null) return "This profile uses the selected login. Its username and saved password come from there; no need to enter them again.";
        return (automatic.isSelected() && remember.isSelected() ? "Leave" : "Turn")
                +" both recommended options on to skip typing your login next time. Enter your password below. Windows saves it; plain TCP is unencrypted.";
    }
    boolean kerberos() { return authentication.getSelectedItem()==Profile.Authentication.KERBEROS; }
    String tutorialConnectionText() {
        return kerberos() ? "Check the host address. Advanced contains the optional separate Kerberos server DNS name, device and port."
                : "Check the host address, then enter your username. Give the profile a name you'll recognize. Device is optional.";
    }

    private void load(Profile profile) {
        selected = profile;
        loading = true;
        name.setText(profile.name()); host.setText(profile.host()); port.setText(Integer.toString(profile.port()));
        device.setText(profile.device()); username.setText(profile.username()); password.setText("");
        authentication.setSelectedItem(profile.authentication()); kerberosServer.setText(profile.kerberosServer());
        loginFrom.removeAllItems(); loginFrom.addItem("Independent login");
        for (Profile source:workspace.profileList()) if (!source.id().equals(profile.id()) && source.loginSource()==null
                && source.authentication()==Profile.Authentication.PASSWORD && !source.username().isEmpty() && source.rememberPassword()) loginFrom.addItem(source);
        Profile login=workspace.loginProfile(profile);
        if (profile.loginSource()!=null) loginFrom.setSelectedItem(login);
        loginFrom.setVisible(loginFrom.getItemCount()>1); loginLabel.setVisible(loginFrom.isVisible());
        username.setText(login.username());
        automatic.setSelected(login.automaticSignOn()); remember.setSelected(login.rememberPassword());
        codePage.setSelectedItem(profile.codePage()); passwordModeInitialized=profile.authentication()==Profile.Authentication.PASSWORD;
        error.setText(" ");
        baseline=fields(); dirty = false; loading = false; updateLoginControls(); updateDirty();
    }
    private UUID loginSource() { return !kerberos() && loginFrom.getSelectedItem() instanceof Profile source ? source.id() : null; }
    private void showAdvanced(boolean show) {
        disclosure.setSelected(show); advanced.setVisible(show);
        disclosure.setIcon(Ui.symbol(show ? "down" : "right"));
        disclosure.getAccessibleContext().setAccessibleName(show ? "Hide advanced profile settings" : "Show advanced profile settings");
        connection.revalidate(); connection.repaint();
    }
    private void updateIdentity() {
        advancedIdentity.setVisible(kerberos());
        identity.setVisible(kerberos());
        connection.revalidate(); connection.repaint();
    }
    private void updateLoginControls() {
        boolean independent=loginSource()==null;
        boolean windows=kerberos();
        for (JComponent input:java.util.List.of(username,password,automatic,remember)) {
            input.setEnabled(!windows && !saving && independent); input.setVisible(!windows);
        }
        usernameLabel.setVisible(!windows); passwordLabel.setVisible(!windows);
        loginFrom.setVisible(!windows && loginFrom.getItemCount()>1); loginLabel.setVisible(loginFrom.isVisible());
        kerberosServer.setVisible(windows); kerberosLabel.setVisible(windows);
        kerberosServer.setEnabled(windows && !saving);
        updateIdentity();
        loginHint.setText(windows ? "Use your Windows sign-in. No password saving or NTLM fallback. IBM i needs Kerberos and EIM set up; ask your administrator if Connect cannot sign you in. Terminal traffic is still unencrypted."
                : independent
                ? "Skip typing your login next time. Saved passwords stay in Windows Credential Manager. Plain TCP is unencrypted; automatic sign-in needs host support."
                : "Uses the selected profile's username and saved password for this host. Choose Independent login to stop sharing. Plain TCP is unencrypted.");
        loginHint.setCaretPosition(0);
        loginHint.getParent().revalidate(); loginHint.getParent().repaint();
    }
    private java.util.List<Object> fields() {
        return java.util.List.of(name.getText(),host.getText(),port.getText(),device.getText(),username.getText(),
                password.getDocument().getLength(),automatic.isSelected(),remember.isSelected(),
                codePage.getSelectedItem(),
                Objects.toString(loginSource(),""),authentication.getSelectedItem(),kerberosServer.getText());
    }
    private void updateDirty() {
        dirty=baseline==null || !baseline.equals(fields()) || workspace.profileList().stream().noneMatch(p -> p.id().equals(selected.id()));
        save.setEnabled(dirty && !saving);
        boolean saved=workspace.profileList().stream().anyMatch(p -> p.id().equals(selected.id()));
        up.setEnabled(!saving && saved && list.getSelectedIndex()>0);
        down.setEnabled(!saving && saved && list.getSelectedIndex()<workspace.profileList().size()-1);
        delete.setEnabled(!saving && saved);
        firePropertyChange("settings.draft",false,true);
    }
    private boolean discardChanges() {
        return !dirty || Ui.confirm(this,"Discard unsaved profile edits?","Unsaved edits");
    }
    void dismiss() {
        if (!saving && discardChanges()) { password.setText(""); close.run(); }
    }
    private void addProfile(boolean copy) {
        if (!discardChanges()) return;
        Profile profile = copy ? workspace.connectionProfile(selected).duplicate() : Profile.createKerberos("New workstation");
        loading = true; model.addElement(profile); list.setSelectedValue(profile,true); loading = false;
        load(profile); updateDirty(); name.requestFocusInWindow(); name.selectAll();
        workspace.tutorialAction("profile-new");
    }
    private void refreshList(Profile profile) {
        loading=true; model.clear(); workspace.profileList().forEach(model::addElement);
        list.setSelectedValue(profile,true); loading=false; load(profile);
    }
    private void move(int direction) {
        if (saving) return;
        try {
            workspace.moveProfile(selected.id(),direction);
            loading=true; model.clear(); workspace.profileList().forEach(model::addElement);
            list.setSelectedValue(selected,true); loading=false; updateDirty();
            list.requestFocusInWindow();
        } catch (IllegalArgumentException e) { error.setText(e.getMessage()); error.setToolTipText(e.getMessage()); }
    }
    private void delete() {
        if (saving || !discardChanges()) return;
        try {
            workspace.validateDeleteProfile(selected.id());
            if (!Ui.confirm(this,"Delete \""+selected.name()+"\"?\nPanes using it will disconnect and select the first remaining profile. Legacy profile layouts will be updated; named layouts block deletion until updated or removed.\n"
                    +"Windows saved passwords are kept; this never deletes another profile's login.","Delete profile")) return;
            workspace.deleteProfile(selected.id());
            refreshList(workspace.profileList().getFirst()); list.requestFocusInWindow();
        } catch (IllegalArgumentException e) { error.setText(e.getMessage()); error.setToolTipText(e.getMessage()); }
    }
    static final class Pending implements AutoCloseable {
        Profile updated;
        final Profile previous;
        final char[] secret;
        Pending(Profile updated,Profile previous,char[] secret) { this.updated=updated; this.previous=previous; this.secret=secret; }
        @Override public void close() { Arrays.fill(secret,'\0'); }
    }
    Pending prepare(boolean connect) {
        char[] secret = password.getPassword();
        try {
            Profile previous=workspace.profileList().stream().filter(p -> p.id().equals(selected.id())).findFirst().orElse(null);
            if (previous!=null && !previous.withDisplay(selected.theme(),selected.effect()).equals(selected))
                throw new IllegalArgumentException("This profile changed elsewhere. Select it again before applying edits.");
            Profile display=previous==null ? selected : previous;
            UUID source=loginSource();
            Profile updated = new Profile(selected.id(),name.getText(),host.getText(),Integer.parseInt(port.getText().strip()),
                    device.getText(),source==null ? username.getText() : "",!kerberos() && source==null && automatic.isSelected(),!kerberos() && source==null && remember.isSelected(),
                    Objects.requireNonNull((String)codePage.getSelectedItem()),display.theme(),
                    display.effect(),display.zoom(),Map.of(),display.savedLayout(),source,
                    (Profile.Authentication)Objects.requireNonNull(authentication.getSelectedItem()),kerberosServer.getText());
            workspace.validateProfile(updated);
            Profile login=workspace.loginProfile(updated);
            if (secret.length>0 && login.username().isEmpty())
                throw new IllegalArgumentException("Enter a username before saving a password.");
            if (connect && login.automaticSignOn() && login.username().isEmpty())
                throw new IllegalArgumentException("Enter a username for automatic sign-in, or turn it off.");
            if (secret.length > 0 && !updated.rememberPassword())
                throw new IllegalArgumentException("Enable Save password, or clear the password field.");
            error.setText(" ");
            return new Pending(updated,previous,secret);
        } catch (IllegalArgumentException e) {
            Arrays.fill(secret,'\0');
            error.setText(e instanceof NumberFormatException ? "Port must be a number." : e.getMessage());
            error.setToolTipText(error.getText());
            if (e instanceof NumberFormatException || error.getText().startsWith("Port") || error.getText().startsWith("Device")
                    || error.getText().startsWith("Unsupported") || error.getText().startsWith("Unknown palette")) {
                showAdvanced(true);
            }
            if (error.getText().startsWith("Kerberos server")) {
                showAdvanced(true);
                kerberosServer.scrollRectToVisible(new Rectangle(0,0,kerberosServer.getWidth(),kerberosServer.getHeight()));
                kerberosServer.requestFocusInWindow();
            }
            return null;
        }
    }
    private void save(boolean connect,boolean finish) {
        Pending pending=prepare(connect);
        if (pending!=null) commit(pending,() -> { if (finish) { close.run(); if (connect) workspace.useProfile(selected); } },message -> {});
    }
    void commit(Pending pending,Runnable success,java.util.function.Consumer<String> failed) {
        Profile updated=pending.updated,previous=pending.previous; char[] secret=pending.secret;
        setSaving(true);
        new SwingWorker<Void,Void>() {
            @Override protected Void doInBackground() throws java.io.IOException {
                if (updated.rememberPassword() && secret.length > 0) workspace.credentials().write(updated,secret);
                if (previous!=null && previous.authentication()==Profile.Authentication.PASSWORD && updated.authentication()==Profile.Authentication.PASSWORD
                        && previous.rememberPassword() && !previous.username().isEmpty() && (!updated.rememberPassword()
                        || !previous.credentialTarget().equals(updated.credentialTarget()))) workspace.credentials().delete(previous);
                return null;
            }
            @Override protected void done() {
                pending.close();
                password.setText("");
                setSaving(false);
                try {
                    get();
                    Profile current=workspace.profileList().stream().filter(p -> p.id().equals(updated.id())).findFirst().orElse(null);
                    if (!Objects.equals(current,previous)) throw new IllegalArgumentException("Profile changed during save; reopen it to review the saved state.");
                    workspace.putProfile(updated);
                    dirty = false;
                    refreshList(updated);
                    workspace.tutorialAction("profile-saved");
                    success.run();
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    error.setText("Not saved: " + cause.getMessage());
                    error.setToolTipText(error.getText());
                    failed.accept(error.getText());
                }
            }
        }.execute();
    }
    private void setSaving(boolean value) {
        saving = value;
        for (JButton button : java.util.List.of(save,done,use,cancel,add,duplicate,up,down,delete)) button.setEnabled(!value);
        for (JComponent input:java.util.List.of(name,host,port,device,username,password,codePage,automatic,remember,loginFrom,authentication,kerberosServer,disclosure))
            input.setEnabled(!value);
        updateLoginControls();
        if (!value) updateDirty();
        workspace.refreshActions();
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
