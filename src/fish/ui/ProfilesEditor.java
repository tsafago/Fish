package fish.ui;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import fish.model.Profile;

public final class ProfilesEditor extends BorderPane {
    private final Workspace workspace;
    private final Runnable close;
    private final ObservableList<Profile> items=FXCollections.observableArrayList();
    private final ListView<Profile> list=new ListView<>(items);
    private final TextField name=new TextField(),host=new TextField();
    private final TextField device=new TextField(),username=new TextField();
    private final PasswordField password=new PasswordField();
    private final HBox passwordWrapper;
    private final ComboBox<Profile.Authentication> authentication=new ComboBox<>(FXCollections.observableArrayList(Profile.Authentication.values()));
    private final Label usernameLabel,passwordLabel,loginLabel;
    private final CheckBox automatic=new CheckBox("Automatic sign-in");
    private final CheckBox remember=new CheckBox("Save password");
    private final ComboBox<Object> loginFrom=new ComboBox<>();
    private final GridPane connection=new GridPane();
    private final Label error=new Label(" ");
    private final Button save,done,use,discard,add,duplicate,up,down,delete;
    private Profile selected,credentialOrigin;
    private boolean loading,dirty,loginChanges,saving,credentialSaving,passwordModeInitialized;
    private long generation;
    private boolean diskUnsaved,closing;
    private Profile connectAfterSave;
    private String saveFailure="",draftError="";
    private Runnable onDraftChanged=() -> {};

    public ProfilesEditor(Workspace workspace,Profile initial,boolean embedded,Runnable close) {
        this.workspace=workspace;
        this.close=close;
        items.setAll(workspace.profileList());

        list.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        list.setAccessibleText("Saved workstation profiles");
        list.setFixedCellSize(28);

        add=Ui.button("New",() -> addProfile(false));
        duplicate=Ui.button("Duplicate",() -> addProfile(true));
        HBox createActions=new HBox(6,add,duplicate);
        HBox.setHgrow(add,Priority.ALWAYS); add.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(duplicate,Priority.ALWAYS); duplicate.setMaxWidth(Double.MAX_VALUE);

        up=Ui.button("Up",() -> move(-1));
        down=Ui.button("Down",() -> move(1));
        delete=Ui.button("Delete",this::delete);
        up.setTooltip(new Tooltip("Move profile up (Ctrl+Up in the list); order applies immediately"));
        down.setTooltip(new Tooltip("Move profile down (Ctrl+Down in the list); order applies immediately"));
        up.setAccessibleText("Move profile up");
        down.setAccessibleText("Move profile down");
        HBox manageActions=new HBox(4,up,down,delete);
        for (Button b:List.of(up,down,delete)) { HBox.setHgrow(b,Priority.ALWAYS); b.setMaxWidth(Double.MAX_VALUE); }

        list.addEventFilter(KeyEvent.KEY_PRESSED,e -> {
            if (e.isControlDown() && e.getCode()==KeyCode.UP) { move(-1); e.consume(); }
            else if (e.isControlDown() && e.getCode()==KeyCode.DOWN) { move(1); e.consume(); }
            else if (e.getCode()==KeyCode.DELETE) { delete(); e.consume(); }
        });

        VBox listActions=new VBox(6,createActions,manageActions);
        BorderPane sidebar=new BorderPane();
        sidebar.setPrefWidth(190);
        sidebar.setCenter(list);
        sidebar.setBottom(listActions);
        BorderPane.setMargin(listActions,new Insets(8,0,0,0));

        connection.setHgap(12); connection.setVgap(10);
        connection.setPadding(new Insets(8));
        ColumnConstraints col0=new ColumnConstraints(125);
        ColumnConstraints col1=new ColumnConstraints(); col1.setHgrow(Priority.ALWAYS);
        connection.getColumnConstraints().addAll(col0,col1);

        authentication.setMaxWidth(Double.MAX_VALUE);
        loginFrom.setMaxWidth(Double.MAX_VALUE);

        int row=0;
        field(connection,row++,"Name",name);
        field(connection,row++,"Host",host);
        field(connection,row++,"Device (optional)",device);
        field(connection,row++,"Sign in with",authentication);
        loginLabel=field(connection,row++,"Use login from...",loginFrom);
        usernameLabel=field(connection,row++,"Username",username);
        wide(connection,row++,automatic);
        wide(connection,row++,remember);
        password.setTooltip(new Tooltip("Leave blank to keep a saved password. Automatic sign-in supports 1-128 printable ASCII characters."));
        passwordWrapper=Ui.revealablePassword(password);
        passwordLabel=fieldNode(connection,row++,"Password",passwordWrapper,password);

        ScrollPane connectionScroll=new ScrollPane(connection);
        connectionScroll.setFitToWidth(true);
        connectionScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        BorderPane body=new BorderPane();
        if (!embedded) body.setPadding(new Insets(8));
        body.setLeft(sidebar);
        BorderPane.setMargin(sidebar,new Insets(0,12,0,0));
        body.setCenter(connectionScroll);

        Ui.role(error,"error");
        save=Ui.button("Save login changes",this::explicitSave);
        discard=Ui.button("Discard edits",() -> { if (discardChanges()) refreshList(currentOrFirst()); });
        done=Ui.button("Done",this::dismiss);
        done.setDefaultButton(!embedded);
        use=Ui.button("Connect",this::connect);

        BorderPane bottom=new BorderPane();
        bottom.setPadding(embedded ? new Insets(8,0,0,0) : new Insets(4,8,8,8));
        bottom.setCenter(error);
        BorderPane.setAlignment(error,Pos.CENTER_LEFT);
        HBox buttons=new HBox(6,discard,save);
        if (!embedded) buttons.getChildren().addAll(done,use);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        bottom.setRight(buttons);
        error.setWrapText(true);

        setCenter(body);
        setBottom(bottom);

        for (TextField tf:List.of(name,host,device,username)) {
            tf.textProperty().addListener((o,a,b) -> {
                if (!loading) {
                    Ui.markInvalid(tf,false);
                    autoSave();
                    if (tf==device) workspace.tutorialAction("profile-device");
                    if (tf==host) workspace.tutorialAction("profile-address");
                    if (tf==username) workspace.tutorialAction("profile-username");
                }
            });
        }
        password.textProperty().addListener((o,a,b) -> {
            if (!loading) autoSave();
        });
        automatic.selectedProperty().addListener((o,a,b) -> {
            if (!loading) { autoSave(); workspace.tutorialAction("profile-login-options"); }
        });
        remember.selectedProperty().addListener((o,a,b) -> {
            if (!loading) { autoSave(); workspace.tutorialAction("profile-login-options"); }
        });
        authentication.valueProperty().addListener((obs,oldV,newV) -> {
            if (loading || newV==null) return;
            loading=true;
            if (kerberos()) {
                if (!discardPassword()) { authentication.setValue(oldV); loading=false; return; }
            } else if (!passwordModeInitialized) {
                passwordModeInitialized=true;
                automatic.setSelected(true);
                remember.setSelected(true);
            }
            loading=false;
            updateLoginControls();
            autoSave();
            if (!kerberos()) workspace.tutorialAction("profile-password-mode");
        });
        loginFrom.valueProperty().addListener((obs,oldV,newV) -> {
            if (loading || newV==null) return;
            loading=true;
            if (!discardPassword()) { loginFrom.setValue(oldV); loading=false; return; }
            if (newV instanceof Profile source) {
                username.setText(source.username());
                automatic.setSelected(source.automaticSignOn());
                remember.setSelected(source.rememberPassword());
            } else {
                automatic.setSelected(true);
                remember.setSelected(true);
                passwordModeInitialized=true;
            }
            loading=false;
            updateLoginControls();
            autoSave();
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs,oldV,newV) -> {
            if (loading || newV==null || newV.equals(selected)) return;
            if (!discardChanges()) {
                loading=true; list.getSelectionModel().select(selected); loading=false; return;
            }
            load(newV);
        });

        selected=initial;
        list.getSelectionModel().select(initial);
        load(initial);
    }

    public void setOnDraftChanged(Runnable listener) { this.onDraftChanged=Objects.requireNonNull(listener); }
    public Button doneButton() { return done; }
    public Button saveButton() { return save; }
    public Button addButton() { return add; }
    public Button upButton() { return up; }
    public Button downButton() { return down; }
    public Button deleteButton() { return delete; }
    public ListView<Profile> profileListView() { return list; }
    public TextField nameField() { return name; }
    public TextField hostField() { return host; }
    public TextField deviceField() { return device; }
    public TextField usernameField() { return username; }
    public PasswordField passwordField() { return password; }
    public ComboBox<Profile.Authentication> authenticationCombo() { return authentication; }
    public CheckBox automaticCheck() { return automatic; }
    public CheckBox rememberCheck() { return remember; }
    public Label errorLabel() { return error; }

    public boolean dirty() { return dirty || diskUnsaved; }
    public boolean saving() { return saving || credentialSaving; }
    public boolean failedSave() { return !saveFailure.isEmpty(); }
    public String error() { return error.getText(); }
    public void discard() {
        loading=true; password.clear(); loading=false;
    }

    public void rebaseDisplay() {
        if (loading || dirty || credentialSaving) return;
        Profile current=currentOrFirst();
        if (!current.equals(selected) || !items.equals(workspace.profileList())) refreshList(current);
    }

    public void focusName() { name.requestFocus(); }

    public Node tutorialTarget(String control) {
        return switch (control) {
            case "new" -> add;
            case "authentication" -> authentication;
            case "host" -> host;
            case "username" -> username;
            case "password" -> password;
            case "remember" -> remember;
            case "device" -> device;
            default -> list;
        };
    }

    public Node tutorialTarget(int step) {
        if (step==0) return list;
        if (step>=3) return done;
        return step==1 ? host : kerberos() ? authentication : automatic;
    }

    public String tutorialSignInText() {
        if (kerberos()) return "Kerberos uses your Windows sign-in. No password to type or save. Connect requests a ticket for this IBM i. Terminal traffic is still unencrypted.";
        if (loginSource()!=null) return "This profile uses the selected login. Its username and saved password come from there; no need to enter them again.";
        return "For automatic sign-in, enable both options, enter your password and choose Save login changes. Plain TCP is unencrypted.";
    }

    public boolean kerberos() { return authentication.getValue()==Profile.Authentication.KERBEROS; }

    public String tutorialConnectionText() {
        return "Check the host address and give the profile a name you'll recognize. Device is optional.";
    }

    private void load(Profile profile) {
        selected=profile;
        loading=true;
        name.setText(profile.name()); host.setText(profile.host());
        device.setText(profile.device()); username.setText(profile.username()); password.clear();
        authentication.setValue(profile.authentication());
        List<Object> sources=new ArrayList<>();
        sources.add("Independent login");
        for (Profile source:workspace.profileList()) {
            if (!source.id().equals(profile.id()) && source.loginSource()==null
                    && source.authentication()==Profile.Authentication.PASSWORD && !source.username().isEmpty() && source.rememberPassword())
                sources.add(source);
        }
        loginFrom.setItems(FXCollections.observableArrayList(sources));
        Profile login=workspace.loginProfile(profile);
        loginFrom.setValue(profile.loginSource()!=null ? login : "Independent login");
        boolean showLoginFrom=sources.size()>1;
        loginFrom.setVisible(showLoginFrom); loginFrom.setManaged(showLoginFrom);
        loginLabel.setVisible(showLoginFrom); loginLabel.setManaged(showLoginFrom);
        username.setText(login.username());
        automatic.setSelected(login.automaticSignOn());
        remember.setSelected(login.rememberPassword());
        passwordModeInitialized=profile.authentication()==Profile.Authentication.PASSWORD;
        credentialOrigin=hasPasswordTarget(profile) ? profile : null;
        draftError="";
        for (Node n:List.of(name,host,device,username)) Ui.markInvalid(n,false);
        dirty=false; loginChanges=false; loading=false;
        updateLoginControls();
        refreshStatus();
    }

    private UUID loginSource() { return !kerberos() && loginFrom.getValue() instanceof Profile source ? source.id() : null; }

    private void updateLoginControls() {
        boolean independent=loginSource()==null;
        boolean windows=kerberos();
        for (Node input:List.of(username,passwordWrapper,automatic,remember)) {
            input.setDisable(windows || credentialSaving || !independent);
            input.setVisible(!windows);
            input.setManaged(!windows);
        }
        password.setDisable(windows || credentialSaving || !independent);
        usernameLabel.setVisible(!windows); usernameLabel.setManaged(!windows);
        passwordLabel.setVisible(!windows); passwordLabel.setManaged(!windows);
        boolean showLogin=!windows && loginFrom.getItems().size()>1;
        loginFrom.setVisible(showLogin); loginFrom.setManaged(showLogin);
        loginLabel.setVisible(showLogin); loginLabel.setManaged(showLogin);
    }

    private boolean discardPassword() {
        if (password.getLength()==0) return true;
        if (!Ui.confirm(getScene().getWindow(),"Discard the password you entered?","Unsaved password")) return false;
        password.clear();
        return true;
    }

    private void refreshStatus() {
        boolean failed=failedSave();
        String text=failed ? "Not saved: "+saveFailure : !draftError.isEmpty() ? draftError
                : credentialSaving ? "Saving login..." : dirty ? "Login changes not saved."
                : diskUnsaved ? "Saving..." : generation>0 ? "Saved" : "";
        error.setText(text); Ui.role(error,failed || !draftError.isEmpty() ? "error" : "muted");
        save.setText(failed ? "Retry save" : "Save login changes");
        save.setVisible(failed || loginChanges); save.setManaged(save.isVisible());
        save.setDisable(credentialSaving || !save.isVisible());
        discard.setVisible(dirty); discard.setManaged(dirty);
        discard.setDisable(credentialSaving);
        int selIdx=list.getSelectionModel().getSelectedIndex();
        up.setDisable(credentialSaving || selIdx<=0);
        down.setDisable(credentialSaving || selIdx<0 || selIdx>=items.size()-1);
        delete.setDisable(credentialSaving || selIdx<0);
        onDraftChanged.run();
    }

    private boolean discardChanges() {
        return !dirty || Ui.confirm(getScene()==null ? workspace.stage() : getScene().getWindow(),
                "Discard only unsaved profile edits? Already saved changes stay applied.","Unsaved edits");
    }

    public void dismiss() {
        connectAfterSave=null;
        if (saving()) { closing=true; return; }
        closing=false;
        if (failedSave() && !Ui.confirm(getScene().getWindow(),
                "The profile could not be saved to disk. Close without saving?","Profile not saved")) return;
        if (discardChanges()) close.run();
    }

    public void addProfile(boolean copy) {
        if (credentialSaving || !discardChanges()) return;
        Profile profile=copy ? workspace.connectionProfile(selected).duplicate() : Profile.createKerberos("New workstation");
        try {
            loading=true;
            workspace.putProfile(profile);
            refreshList(profile);
            changed();
            name.requestFocus(); name.selectAll();
            workspace.tutorialAction("profile-new");
        } catch (IllegalArgumentException ex) { problem(ex); }
        finally { loading=false; }
    }

    public void refreshList(Profile profile) {
        loading=true; items.setAll(workspace.profileList());
        list.getSelectionModel().select(profile); loading=false; load(profile);
    }

    public void move(int direction) {
        if (credentialSaving) return;
        try {
            workspace.moveProfile(selected.id(),direction);
            loading=true; items.setAll(workspace.profileList());
            list.getSelectionModel().select(selected); loading=false; changed();
            list.requestFocus();
        } catch (IllegalArgumentException e) {
            error.setText(e.getMessage());
            error.setTooltip(new Tooltip(e.getMessage()));
        }
    }

    public void delete() {
        if (credentialSaving || !discardChanges()) return;
        try {
            workspace.validateDeleteProfile(selected.id());
            if (!Ui.confirm(getScene()==null ? workspace.stage() : getScene().getWindow(),
                    "Delete \""+selected.name()+"\"?\nPanes using it will disconnect and select another profile. Saved passwords are kept.","Delete profile")) return;
            workspace.deleteProfile(selected.id());
            refreshList(workspace.profileList().getFirst());
            changed();
            list.requestFocus();
        } catch (IllegalArgumentException e) {
            error.setText(e.getMessage());
            error.setTooltip(new Tooltip(e.getMessage()));
        }
    }

    private Profile currentProfile() {
        return workspace.profileList().stream().filter(p -> p.id().equals(selected.id())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("This profile was deleted. Select another profile."));
    }

    private Profile currentOrFirst() {
        List<Profile> profiles=workspace.profileList();
        return profiles.stream().filter(p -> p.id().equals(selected.id())).findFirst().orElse(profiles.getFirst());
    }

    private Profile draft() {
        Profile p=currentProfile();
        if (!p.withDisplay(selected.theme(),selected.effect()).equals(selected))
            throw new IllegalArgumentException("This profile changed elsewhere. Discard edits to reload it.");
        UUID source=loginSource();
        return new Profile(p.id(),name.getText(),host.getText(),p.port(),device.getText(),
                source==null ? username.getText() : "",!kerberos() && source==null && automatic.isSelected(),
                !kerberos() && source==null && remember.isSelected(),p.codePage(),p.theme(),p.effect(),
                p.zoom(),p.keymap(),p.savedLayout(),source,authentication.getValue(),p.kerberosServer());
    }

    private static boolean hasPasswordTarget(Profile p) {
        return p.authentication()==Profile.Authentication.PASSWORD && p.rememberPassword() && !p.username().isEmpty();
    }

    private boolean retiresCredential(Profile p) {
        // A newly typed username is not evidence that a Windows credential already exists for it.
        return credentialOrigin!=null && p.authentication()==Profile.Authentication.PASSWORD
                && (!hasPasswordTarget(p) || !p.credentialTarget().equals(credentialOrigin.credentialTarget()));
    }

    private void autoSave() {
        if (loading || credentialSaving) return;
        draftError=""; loginChanges=false;
        try {
            Profile p=draft();
            boolean protectedLogin=retiresCredential(p);
            dirty=loginChanges=protectedLogin || password.getLength()!=0;
            if (protectedLogin) {
                // Still autosave name and device; keep the saved login and its endpoint together.
                Profile old=currentProfile();
                p=new Profile(old.id(),p.name(),old.host(),old.port(),p.device(),old.username(),old.automaticSignOn(),
                        old.rememberPassword(),old.codePage(),old.theme(),old.effect(),old.zoom(),old.keymap(),
                        old.savedLayout(),old.loginSource(),old.authentication(),old.kerberosServer());
            }
            applyProfile(p);
            refreshStatus();
        } catch (IllegalArgumentException ex) { problem(ex); }
    }

    private void applyProfile(Profile p) {
        if (p.equals(selected)) return;
        // Updating the list/model must not reload the text fields mid-keystroke (e.g. trailing space).
        loading=true;
        try {
            workspace.putProfile(p);
            int index=items.indexOf(selected);
            if (index>=0) items.set(index,p);
            list.getSelectionModel().select(p);
            selected=p;
        } finally { loading=false; }
        changed();
    }

    private void changed() {
        generation++;
        diskUnsaved=true;
        persist();
    }

    private void persist() {
        if (saving || !diskUnsaved || failedSave()) return;
        long written=generation;
        saving=true;
        refreshStatus();
        workspace.persistSettings(() -> {
            saving=false;
            diskUnsaved=written!=generation;
            if (diskUnsaved) { persist(); return; }
            refreshStatus();
            finishIfReady();
        },message -> {
            saving=false; closing=false; connectAfterSave=null;
            saveFailure=Objects.toString(message,"Could not write profiles.");
            refreshStatus();
        });
    }

    private void finishIfReady() {
        if (saving() || failedSave()) return;
        if (closing) dismiss();
        else if (connectAfterSave!=null) {
            Profile requested=connectAfterSave;
            connectAfterSave=null;
            if (requested.equals(selected)) connect();
        }
    }

    private void connect() {
        if (dirty || failedSave()) { refreshStatus(); return; }
        Profile login=workspace.loginProfile(selected);
        if (login.automaticSignOn() && login.username().isEmpty()) {
            draftError="Enter a username for automatic sign-in, or turn it off.";
            refreshStatus(); return;
        }
        if (saving()) { connectAfterSave=selected; return; }
        close.run();
        workspace.useProfile(selected);
    }

    private void explicitSave() {
        if (credentialSaving) return;
        if (failedSave()) { saveFailure=""; persist(); return; } // Disk retry never repeats credential operations.
        try {
            Profile p=draft(),previous=currentProfile(),retired=retiresCredential(p) ? credentialOrigin : null;
            workspace.validateProfile(p);
            if (password.getLength()>0 && !hasPasswordTarget(p))
                throw new IllegalArgumentException("Enter a username and enable Save password, or clear the password.");
            if (retired==null && password.getLength()==0) { autoSave(); return; }
            char[] secret=password.getText().toCharArray();
            setCredentialSaving(true);
            CompletableFuture.runAsync(() -> {
                try {
                    if (secret.length>0) workspace.credentials().write(p,secret);
                    if (retired!=null) workspace.credentials().delete(retired);
                } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                finally { Arrays.fill(secret,'\0'); }
            }).whenComplete((unused,err) -> Ui.runLater(() -> {
                setCredentialSaving(false);
                try {
                    if (err!=null) throw new IllegalArgumentException("Login not saved: "+err.getCause().getMessage());
                    if (!previous.equals(currentProfile())) throw new IllegalArgumentException("Profile changed during login save. Review your edits.");
                    applyProfile(p);
                    refreshList(p); // Only clear the password after success.
                    finishIfReady();
                } catch (IllegalArgumentException ex) { closing=false; problem(ex); }
            }));
        } catch (IllegalArgumentException ex) { problem(ex); }
    }

    private void setCredentialSaving(boolean value) {
        credentialSaving=value;
        for (Node n:List.of(name,host,device,loginFrom,authentication,list,add,duplicate,done,use)) n.setDisable(value);
        updateLoginControls();
        refreshStatus();
    }

    private void problem(IllegalArgumentException ex) {
        dirty=true;
        draftError=ex.getMessage();
        if (draftError.startsWith("Device")) Ui.markInvalid(device,true);
        else if (draftError.startsWith("Username")) Ui.markInvalid(username,true);
        else if (draftError.startsWith("Profile name")) Ui.markInvalid(name,true);
        else if (draftError.contains("hostname")) Ui.markInvalid(host,true);
        refreshStatus();
    }

    private static Label field(GridPane grid,int row,String text,Control control) {
        return fieldNode(grid,row,text,control,control);
    }

    private static Label fieldNode(GridPane grid,int row,String text,Node container,Control target) {
        Label label=new Label(text);
        label.setLabelFor(target);
        grid.add(label,0,row);
        grid.add(container,1,row);
        return label;
    }

    private static void wide(GridPane grid,int row,Node node) {
        grid.add(node,0,row,2,1);
    }
}
