package fish.ui;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.util.Duration;
import fish.model.Profile;

public final class ProfilesEditor extends BorderPane {
    private final Workspace workspace;
    private final Runnable close;
    private final ObservableList<Profile> items=FXCollections.observableArrayList();
    private final ListView<Profile> list=new ListView<>(items);
    private final TextField name=new TextField(),host=new TextField(),port=new TextField();
    private final TextField device=new TextField(),username=new TextField();
    private final PasswordField password=new PasswordField();
    private final HBox passwordWrapper;
    private final ComboBox<Profile.Authentication> authentication=new ComboBox<>(FXCollections.observableArrayList(Profile.Authentication.values()));
    private final TextField kerberosServer=new TextField();
    private Label kerberosLabel,usernameLabel,passwordLabel,loginLabel;
    private final CheckBox automatic=new CheckBox("Automatic sign-in (recommended)");
    private final CheckBox remember=new CheckBox("Save password (recommended)");
    private final ComboBox<Object> loginFrom=new ComboBox<>();
    private final Label loginHint=new Label();
    private final GridPane connection=new GridPane();
    private final GridPane advanced=new GridPane();
    private final HBox identity=new HBox(12);
    private final ToggleButton disclosure=new ToggleButton("Advanced");
    private final ComboBox<String> codePage=new ComboBox<>(FXCollections.observableArrayList(Profile.CODE_PAGES));
    private final Label error=new Label(" ");
    private final Button save,done,use,cancel,add,duplicate,up,down,delete;
    private Profile selected;
    private List<Object> baseline;
    private boolean loading,dirty,saving,credentialSaving,passwordModeInitialized;
    private final boolean automaticEdits;
    private final Timeline edits=new Timeline(new KeyFrame(Duration.millis(400),e -> autoSave()));
    private final Timeline writes=new Timeline(new KeyFrame(Duration.millis(200),e -> persist(() -> {})));
    private long generation;
    private boolean diskUnsaved,editPending;
    private String saveFailure="";
    private Runnable onDraftChanged=() -> {};

    public ProfilesEditor(Workspace workspace,Profile initial,boolean embedded,Runnable close) {
        this.workspace=workspace;
        this.close=close;
        this.automaticEdits=embedded;
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
        codePage.setMaxWidth(Double.MAX_VALUE);

        int row=0;
        field(connection,row++,"Name",name);
        field(connection,row++,"Host",host);
        field(connection,row++,"Sign in with",authentication);
        loginLabel=field(connection,row++,"Use login from...",loginFrom);
        usernameLabel=field(connection,row++,"Username",username);
        wide(connection,row++,automatic);
        wide(connection,row++,remember);
        password.setTooltip(new Tooltip("Leave blank to keep a saved password. Automatic sign-in supports 1-128 printable ASCII characters."));
        passwordWrapper=Ui.revealablePassword(password);
        passwordLabel=fieldNode(connection,row++,"Password",passwordWrapper,password);

        loginHint.setWrapText(true);
        Ui.role(loginHint,"muted");
        wide(connection,row++,loginHint);

        disclosure.setAccessibleText("Show advanced profile settings");
        disclosure.setGraphic(Ui.icon("right"));
        disclosure.setGraphicTextGap(6);
        disclosure.setOnAction(e -> {
            showAdvanced(disclosure.isSelected());
            if (disclosure.isSelected()) workspace.tutorialAction("profile-advanced");
        });
        wide(connection,row++,disclosure);

        advanced.setHgap(12); advanced.setVgap(10);
        advanced.getColumnConstraints().addAll(new ColumnConstraints(125),col1);
        kerberosLabel=new Label("Kerberos server"); kerberosLabel.setLabelFor(kerberosServer);
        kerberosLabel.setMinWidth(125);
        kerberosServer.setTooltip(new Tooltip("Optional fully qualified IBM i DNS name, separate from the TCP host. Blank: use the host name or forward-confirmed reverse DNS."));
        HBox.setHgrow(kerberosServer,Priority.ALWAYS);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.getChildren().addAll(kerberosLabel,kerberosServer);
        wide(advanced,0,identity);
        field(advanced,1,"Port",port);
        field(advanced,2,"Device (optional)",device);
        field(advanced,3,"Code page",codePage);
        Label appearanceHint=new Label("Colors and fonts: Settings > Appearance.");
        Ui.role(appearanceHint,"muted");
        wide(advanced,4,appearanceHint);
        advanced.setVisible(false);
        advanced.setManaged(false);
        wide(connection,row,advanced);

        ScrollPane connectionScroll=new ScrollPane(connection);
        connectionScroll.setFitToWidth(true);
        connectionScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        BorderPane body=new BorderPane();
        if (!embedded) body.setPadding(new Insets(8));
        body.setLeft(sidebar);
        BorderPane.setMargin(sidebar,new Insets(0,12,0,0));
        body.setCenter(connectionScroll);

        Ui.role(error,"error");
        cancel=Ui.button("Cancel",this::dismiss);
        save=Ui.button("Apply",() -> save(false,false));
        done=Ui.button("Done",() -> { if (dirty) save(false,true); else dismiss(); });
        done.setDefaultButton(!embedded);
        use=Ui.button("Save & connect",() -> save(true,true));

        BorderPane bottom=new BorderPane();
        bottom.setPadding(embedded ? new Insets(8,0,0,0) : new Insets(4,8,8,8));
        bottom.setCenter(error);
        BorderPane.setAlignment(error,Pos.CENTER_LEFT);
        if (!embedded) {
            HBox buttons=new HBox(6,cancel,save,done,use);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            bottom.setRight(buttons);
        } else {
            save.setText("Create profile");
            Button discardBtn=Ui.button("Discard pending edits",() -> {
                if (!saving && discardChanges()) {
                    edits.stop(); saveFailure="";
                    refreshList(workspace.profileList().stream().filter(p -> p.id().equals(selected.id())).findFirst().orElse(workspace.profileList().getFirst()));
                }
            });
            HBox explicit=new HBox(6,discardBtn,save);
            explicit.setAlignment(Pos.CENTER_RIGHT);
            bottom.setRight(explicit);
        }

        setCenter(body);
        setBottom(bottom);

        for (TextField tf:List.of(name,host,port,device,username,kerberosServer)) {
            tf.textProperty().addListener((o,a,b) -> {
                if (!loading) {
                    Ui.markInvalid(tf,false);
                    updateIdentity();
                    updateDirty();
                    if (tf==host) workspace.tutorialAction("profile-address");
                    if (tf==username) workspace.tutorialAction("profile-username");
                }
            });
        }
        password.textProperty().addListener((o,a,b) -> {
            if (!loading) { updateIdentity(); updateDirty(); }
        });
        codePage.valueProperty().addListener((obs,oldV,newV) -> {
            if (!loading && newV!=null) { updateDirty(); autoSave(); }
        });
        automatic.selectedProperty().addListener((o,a,b) -> {
            if (!loading) { updateDirty(); workspace.tutorialAction("profile-login-options"); autoSave(); }
        });
        remember.selectedProperty().addListener((o,a,b) -> {
            if (!loading) { updateDirty(); workspace.tutorialAction("profile-login-options"); autoSave(); }
        });
        authentication.valueProperty().addListener((obs,oldV,newV) -> {
            if (loading || newV==null) return;
            if (kerberos()) {
                loading=true; password.clear(); loading=false;
            } else if (!passwordModeInitialized) {
                passwordModeInitialized=true;
                automatic.setSelected(true);
                remember.setSelected(true);
            }
            updateLoginControls();
            updateDirty();
            autoSave();
            if (!kerberos()) workspace.tutorialAction("profile-password-mode");
        });
        loginFrom.valueProperty().addListener((obs,oldV,newV) -> {
            if (loading || newV==null) return;
            loading=true;
            if (newV instanceof Profile source) {
                username.setText(source.username());
                automatic.setSelected(source.automaticSignOn());
                remember.setSelected(source.rememberPassword());
            } else {
                automatic.setSelected(true);
                remember.setSelected(true);
                passwordModeInitialized=true;
            }
            password.clear();
            loading=false;
            updateLoginControls();
            updateDirty();
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
    public ToggleButton disclosureButton() { return disclosure; }
    public GridPane advancedPane() { return advanced; }
    public ListView<Profile> profileListView() { return list; }
    public TextField nameField() { return name; }
    public TextField hostField() { return host; }
    public TextField portField() { return port; }
    public TextField deviceField() { return device; }
    public TextField usernameField() { return username; }
    public PasswordField passwordField() { return password; }
    public TextField kerberosServerField() { return kerberosServer; }
    public ComboBox<Profile.Authentication> authenticationCombo() { return authentication; }
    public ComboBox<String> codePageCombo() { return codePage; }
    public CheckBox automaticCheck() { return automatic; }
    public CheckBox rememberCheck() { return remember; }
    public Label errorLabel() { return error; }

    public boolean dirty() { return dirty || diskUnsaved; }
    public boolean saving() { return saving || editPending || diskUnsaved && saveFailure.isEmpty(); }
    public void flush() { if (editPending) autoSave(); if (diskUnsaved && !saving) persist(() -> {}); }
    public boolean failedSave() { return !saveFailure.isEmpty(); }
    public String error() { return error.getText(); }
    public void discard() {
        edits.stop(); writes.stop(); editPending=false;
        loading=true; password.clear(); loading=false;
        updateDirty();
    }

    public void rebaseDisplay() {
        if (dirty) return;
        workspace.profileList().stream().filter(p -> p.id().equals(selected.id())).findFirst().ifPresent(current -> {
            if (!current.equals(selected) || !new ArrayList<>(items).equals(workspace.profileList())) refreshList(current);
        });
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
            case "advanced" -> disclosure;
            case "apply" -> save;
            default -> list;
        };
    }

    public Node tutorialTarget(int step) {
        if (step==0) return cancel;
        if (step>=3) return done;
        return step==1 ? host : kerberos() ? authentication : automatic;
    }

    public String tutorialSignInText() {
        if (kerberos()) return "Kerberos uses your Windows sign-in. No password to type or save. Connect requests a ticket for this IBM i; your administrator must enable Kerberos and map your account. Advanced contains optional device, port and display settings. Terminal traffic is still unencrypted.";
        if (loginSource()!=null) return "This profile uses the selected login. Its username and saved password come from there; no need to enter them again.";
        return (automatic.isSelected() && remember.isSelected() ? "Leave" : "Turn")
                +" both recommended options on to skip typing your login next time. Enter your password below. Windows saves it; plain TCP is unencrypted.";
    }

    public boolean kerberos() { return authentication.getValue()==Profile.Authentication.KERBEROS; }

    public String tutorialConnectionText() {
        return kerberos() ? "Check the host address. Advanced contains the optional separate Kerberos server DNS name, device and port."
                : "Check the host address, then enter your username. Give the profile a name you'll recognize. Device is optional.";
    }

    private void load(Profile profile) {
        edits.stop(); editPending=false;
        selected=profile;
        loading=true;
        name.setText(profile.name()); host.setText(profile.host()); port.setText(Integer.toString(profile.port()));
        device.setText(profile.device()); username.setText(profile.username()); password.clear();
        authentication.setValue(profile.authentication()); kerberosServer.setText(profile.kerberosServer());
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
        codePage.setValue(profile.codePage());
        passwordModeInitialized=profile.authentication()==Profile.Authentication.PASSWORD;
        error.setText(" ");
        for (Node n:List.of(name,host,port,device,username,kerberosServer)) Ui.markInvalid(n,false);
        baseline=fields();
        dirty=false; loading=false;
        updateLoginControls();
        updateDirty();
    }

    private UUID loginSource() { return !kerberos() && loginFrom.getValue() instanceof Profile source ? source.id() : null; }

    public void showAdvanced(boolean show) {
        disclosure.setSelected(show);
        advanced.setVisible(show);
        advanced.setManaged(show);
        disclosure.setGraphic(Ui.icon(show ? "down" : "right"));
        disclosure.setAccessibleText(show ? "Hide advanced profile settings" : "Show advanced profile settings");
    }

    private void updateIdentity() {
        boolean k=kerberos();
        identity.setVisible(k);
        identity.setManaged(k);
    }

    private void updateLoginControls() {
        boolean independent=loginSource()==null;
        boolean windows=kerberos();
        for (Node input:List.of(username,passwordWrapper,automatic,remember)) {
            input.setDisable(windows || saving || !independent);
            input.setVisible(!windows);
            input.setManaged(!windows);
        }
        password.setDisable(windows || saving || !independent);
        usernameLabel.setVisible(!windows); usernameLabel.setManaged(!windows);
        passwordLabel.setVisible(!windows); passwordLabel.setManaged(!windows);
        boolean showLogin=!windows && loginFrom.getItems().size()>1;
        loginFrom.setVisible(showLogin); loginFrom.setManaged(showLogin);
        loginLabel.setVisible(showLogin); loginLabel.setManaged(showLogin);
        kerberosServer.setVisible(windows); kerberosServer.setManaged(windows);
        kerberosLabel.setVisible(windows); kerberosLabel.setManaged(windows);
        kerberosServer.setDisable(!windows || saving);
        updateIdentity();
        loginHint.setText(windows ? "Use your Windows sign-in. No password saving or NTLM fallback. IBM i needs Kerberos and EIM set up; ask your administrator if Connect cannot sign you in. Terminal traffic is still unencrypted."
                : independent
                ? "Skip typing your login next time. Saved passwords stay in Windows Credential Manager. Plain TCP is unencrypted; automatic sign-in needs host support."
                : "Uses the selected profile's username and saved password for this host. Choose Independent login to stop sharing. Plain TCP is unencrypted.");
    }

    private List<Object> fields() {
        return List.of(name.getText(),host.getText(),port.getText(),device.getText(),username.getText(),
                password.getText().length(),automatic.isSelected(),remember.isSelected(),
                Objects.toString(codePage.getValue(),""),
                Objects.toString(loginSource(),""),Objects.toString(authentication.getValue(),""),kerberosServer.getText());
    }

    private void updateDirty() {
        if (loading) return;
        dirty=baseline==null || !baseline.equals(fields()) || workspace.profileList().stream().noneMatch(p -> p.id().equals(selected.id()));
        save.setDisable(!(dirty || diskUnsaved) || saving);
        boolean isSaved=workspace.profileList().stream().anyMatch(p -> p.id().equals(selected.id()));
        if (automaticEdits) {
            save.setText(!saveFailure.isEmpty() ? "Retry save" : isSaved ? "Save login changes" : "Create profile");
            boolean loginBoundary=!password.getText().isEmpty() || selected.authentication()==Profile.Authentication.PASSWORD
                    && selected.rememberPassword() && !selected.username().isEmpty() && !kerberos()
                    && (!remember.isSelected() || loginSource()!=null || !selected.username().equals(username.getText().strip())
                    || !selected.host().equals(host.getText().strip()) || !Integer.toString(selected.port()).equals(port.getText().strip()));
            boolean vis=!isSaved || loginBoundary || !saveFailure.isEmpty();
            save.setVisible(vis);
            save.setManaged(vis);
            if (!credentialSaving && dirty && saveFailure.isEmpty()) { editPending=true; edits.playFromStart(); }
        }
        int selIdx=list.getSelectionModel().getSelectedIndex();
        up.setDisable(saving || !isSaved || selIdx<=0);
        down.setDisable(saving || !isSaved || selIdx<0 || selIdx>=workspace.profileList().size()-1);
        delete.setDisable(saving || !isSaved);
        onDraftChanged.run();
    }

    private boolean discardChanges() {
        return !dirty() || Ui.confirm(getScene()==null ? workspace.stage() : getScene().getWindow(),
                "Discard only unsaved profile edits? Already saved changes stay applied.","Unsaved edits");
    }

    public void dismiss() {
        if (!saving && discardChanges()) { password.clear(); close.run(); }
    }

    public void addProfile(boolean copy) {
        if (!discardChanges()) return;
        Profile profile=copy ? workspace.connectionProfile(selected).duplicate() : Profile.createKerberos("New workstation");
        loading=true; items.add(profile); list.getSelectionModel().select(profile); loading=false;
        load(profile); updateDirty(); name.requestFocus(); name.selectAll();
        workspace.tutorialAction("profile-new");
    }

    public void refreshList(Profile profile) {
        loading=true; items.setAll(workspace.profileList());
        list.getSelectionModel().select(profile); loading=false; load(profile);
    }

    public void move(int direction) {
        if (saving) return;
        try {
            workspace.moveProfile(selected.id(),direction);
            loading=true; items.setAll(workspace.profileList());
            list.getSelectionModel().select(selected); loading=false; updateDirty();
            list.requestFocus();
        } catch (IllegalArgumentException e) {
            error.setText(e.getMessage());
            error.setTooltip(new Tooltip(e.getMessage()));
        }
    }

    public void delete() {
        if (saving || !discardChanges()) return;
        try {
            workspace.validateDeleteProfile(selected.id());
            if (!Ui.confirm(getScene()==null ? workspace.stage() : getScene().getWindow(),
                    "Delete \""+selected.name()+"\"?\nPanes using it will disconnect and select the first remaining profile. Legacy profile layouts will be updated; named layouts block deletion until updated or removed.\n"
                    +"Windows saved passwords are kept; this never deletes another profile's login.","Delete profile")) return;
            workspace.deleteProfile(selected.id());
            refreshList(workspace.profileList().getFirst());
            list.requestFocus();
        } catch (IllegalArgumentException e) {
            error.setText(e.getMessage());
            error.setTooltip(new Tooltip(e.getMessage()));
        }
    }

    static final class Pending implements AutoCloseable {
        Profile updated;
        final Profile previous;
        final char[] secret;
        Pending(Profile updated,Profile previous,char[] secret) { this.updated=updated; this.previous=previous; this.secret=secret; }
        @Override public void close() { Arrays.fill(secret,'\0'); }
    }

    Pending prepare(boolean connect) {
        char[] secret=password.getText().toCharArray();
        try {
            Profile previous=workspace.profileList().stream().filter(p -> p.id().equals(selected.id())).findFirst().orElse(null);
            if (previous!=null && !previous.withDisplay(selected.theme(),selected.effect()).equals(selected))
                throw new IllegalArgumentException("This profile changed elsewhere. Select it again before applying edits.");
            Profile display=previous==null ? selected : previous;
            UUID source=loginSource();
            Profile updated=new Profile(selected.id(),name.getText(),host.getText(),Integer.parseInt(port.getText().strip()),
                    device.getText(),source==null ? username.getText() : "",!kerberos() && source==null && automatic.isSelected(),!kerberos() && source==null && remember.isSelected(),
                    Objects.requireNonNull(codePage.getValue()),display.theme(),
                    display.effect(),display.zoom(),Map.of(),display.savedLayout(),source,
                    Objects.requireNonNull(authentication.getValue()),kerberosServer.getText());
            workspace.validateProfile(updated);
            Profile login=workspace.loginProfile(updated);
            if (secret.length>0 && login.username().isEmpty())
                throw new IllegalArgumentException("Enter a username before saving a password.");
            if (connect && login.automaticSignOn() && login.username().isEmpty())
                throw new IllegalArgumentException("Enter a username for automatic sign-in, or turn it off.");
            if (secret.length>0 && !updated.rememberPassword())
                throw new IllegalArgumentException("Enable Save password, or clear the password field.");
            error.setText(" ");
            Ui.role(error,"muted");
            return new Pending(updated,previous,secret);
        } catch (IllegalArgumentException e) {
            Arrays.fill(secret,'\0');
            String msg=e instanceof NumberFormatException ? "Port must be a number." : e.getMessage();
            error.setText(msg);
            error.setTooltip(new Tooltip(msg));
            Ui.role(error,"error");
            if (e instanceof NumberFormatException || msg.startsWith("Port")) { showAdvanced(true); Ui.markInvalid(port,true); }
            else if (msg.startsWith("Device")) { showAdvanced(true); Ui.markInvalid(device,true); }
            else if (msg.startsWith("Kerberos server")) { showAdvanced(true); Ui.markInvalid(kerberosServer,true); kerberosServer.requestFocus(); }
            else if (msg.startsWith("Username")) Ui.markInvalid(username,true);
            else if (msg.startsWith("Profile name")) Ui.markInvalid(name,true);
            else if (msg.contains("hostname")) Ui.markInvalid(host,true);
            return null;
        }
    }

    public void save(boolean connect,boolean finish) {
        edits.stop(); editPending=false;
        if (diskUnsaved && !dirty) {
            saveFailure=""; persist(() -> { if (finish) { close.run(); if (connect) workspace.useProfile(selected); } }); return;
        }
        Pending pending=prepare(connect);
        if (pending!=null) commit(pending,() -> {
            diskUnsaved=true;
            persist(() -> { if (finish) { close.run(); if (connect) workspace.useProfile(selected); } });
        },msg -> {});
    }

    private static boolean credentialChange(Pending value) {
        Profile previous=value.previous,updated=value.updated;
        return value.secret.length>0 || previous!=null && previous.authentication()==Profile.Authentication.PASSWORD
                && updated.authentication()==Profile.Authentication.PASSWORD && previous.rememberPassword() && !previous.username().isEmpty()
                && (!updated.rememberPassword() || !previous.credentialTarget().equals(updated.credentialTarget()));
    }

    private void autoSave() {
        edits.stop(); editPending=false;
        if (!automaticEdits || loading || credentialSaving) return;
        if (workspace.profileList().stream().noneMatch(p -> p.id().equals(selected.id()))) {
            error.setText("New profile is not saved. Choose Create profile when ready.");
            Ui.role(error,"muted");
            onDraftChanged.run();
            return;
        }
        if (dirty) {
            Pending pending=prepare(false);
            if (pending==null) { onDraftChanged.run(); return; }
            try {
                if (credentialChange(pending)) {
                    error.setText("Login/password changes need Save login changes. Nothing in Windows was changed.");
                    Ui.role(error,"muted");
                    onDraftChanged.run();
                    return;
                }
                Profile updated=pending.updated;
                workspace.putProfile(updated);
                selected=updated; baseline=fields(); dirty=false; diskUnsaved=true; generation++;
                loading=true;
                try {
                    int index=list.getSelectionModel().getSelectedIndex();
                    if (index>=0) items.set(index,updated);
                } finally { loading=false; }
            } finally { pending.close(); }
        }
        if (diskUnsaved) writes.playFromStart();
        else if (!dirty) { error.setText("Saved"); Ui.role(error,"muted"); updateDirty(); }
    }

    private void persist(Runnable done) {
        writes.stop();
        if (saving || !diskUnsaved) return;
        long written=generation;
        saving=true;
        error.setText("Saving profile...");
        Ui.role(error,"muted");
        onDraftChanged.run();
        workspace.persistSettings(() -> {
            saving=false; diskUnsaved=written!=generation; saveFailure="";
            error.setText(dirty ? "Editing; not saved yet." : diskUnsaved ? "Saving profile..." : "Saved");
            Ui.role(error,"muted");
            updateDirty(); workspace.tutorialAction("profile-saved"); done.run();
            if (diskUnsaved) writes.playFromStart();
        },message -> {
            saving=false; saveFailure=message;
            error.setText("Not saved: "+message+" Choose Retry save.");
            error.setTooltip(new Tooltip(error.getText()));
            Ui.role(error,"error");
            save.setText("Retry save"); save.setVisible(true); save.setManaged(true); save.setDisable(false);
            onDraftChanged.run(); workspace.refreshActions();
            if (written!=generation) writes.playFromStart();
        });
    }

    void commit(Pending pending,Runnable success,java.util.function.Consumer<String> failed) {
        Profile updated=pending.updated,previous=pending.previous; char[] secret=pending.secret;
        setSaving(true);
        CompletableFuture.runAsync(() -> {
            try {
                if (updated.rememberPassword() && secret.length>0) workspace.credentials().write(updated,secret);
                if (previous!=null && previous.authentication()==Profile.Authentication.PASSWORD && updated.authentication()==Profile.Authentication.PASSWORD
                        && previous.rememberPassword() && !previous.username().isEmpty() && (!updated.rememberPassword()
                        || !previous.credentialTarget().equals(updated.credentialTarget()))) workspace.credentials().delete(previous);
            } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }).whenComplete((unused,err) -> Ui.runLater(() -> {
            pending.close();
            loading=true; password.clear(); loading=false;
            setSaving(false);
            try {
                if (err!=null) throw err;
                Profile current=workspace.profileList().stream().filter(p -> p.id().equals(updated.id())).findFirst().orElse(null);
                if (!Objects.equals(current,previous))
                    throw new IllegalArgumentException("Profile changed during save; reopen it to review the saved state.");
                workspace.putProfile(updated);
                dirty=false;
                refreshList(updated);
                success.run();
            } catch (Throwable e) {
                Throwable cause=e.getCause()==null ? e : e.getCause();
                if (cause instanceof java.io.UncheckedIOException uio && uio.getCause()!=null) cause=uio.getCause();
                error.setText("Not saved: "+cause.getMessage());
                error.setTooltip(new Tooltip(error.getText()));
                Ui.role(error,"error");
                failed.accept(error.getText());
            }
        }));
    }

    private void setSaving(boolean value) {
        saving=value; credentialSaving=value;
        for (Button button:List.of(save,done,use,cancel,add,duplicate,up,down,delete)) button.setDisable(value);
        for (Node input:List.of(name,host,port,device,username,password,passwordWrapper,codePage,automatic,remember,loginFrom,authentication,kerberosServer,disclosure))
            input.setDisable(value);
        updateLoginControls();
        if (!value) updateDirty();
        workspace.refreshActions();
        list.setDisable(value);
        error.setText(value ? "Saving..." : " ");
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
