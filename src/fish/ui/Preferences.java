package fish.ui;

import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import fish.install.UserInstaller;
import fish.model.*;

/** One indexed settings surface, with shared profile and keyboard editors and current-user portable install. */
public final class Preferences {
    public static final List<String> CATEGORIES=List.of("Appearance","Profiles","Keyboard Shortcuts","General","Updates");
    private final Workspace workspace;
    private final ListView<String> navigation=new ListView<>(FXCollections.observableArrayList(CATEGORIES));
    private final StackPane pages=new StackPane();
    private final Map<String,Node> pageMap=new LinkedHashMap<>();
    private final CheckBox startup,window,pane,tint,checkUpdates;
    private final List<CheckBox> info=new ArrayList<>();
    private final ComboBox<Object> theme=new ComboBox<>();
    private final ComboBox<Profile> scope=new ComboBox<>();
    private final ComboBox<String> palette=new ComboBox<>(FXCollections.observableArrayList(Profile.THEMES));
    private final ComboBox<String> effect=new ComboBox<>(FXCollections.observableArrayList(Profile.EFFECTS));
    private final ComboBox<Appearance.CursorStyle> cursorStyle=new ComboBox<>(FXCollections.observableArrayList(Appearance.CursorStyle.values()));
    private final ComboBox<String> family=new ComboBox<>();
    private final TextField fontPath=new TextField();
    private final ComboBox<UpdateSettings.Channel> channel=new ComboBox<>(FXCollections.observableArrayList(UpdateSettings.Channel.values()));
    private final Map<UUID,Display> displays=new LinkedHashMap<>(),originalDisplays=new LinkedHashMap<>();
    private final Map<String,Node> targets=new HashMap<>();
    private UUID displayProfile;
    private boolean loading,saving,ended,syncing,applying,unsaved,closeRequested,conflict,editPending;
    private final Timeline edits=new Timeline(new KeyFrame(Duration.millis(300),e -> apply()));
    private final Timeline writes=new Timeline(new KeyFrame(Duration.millis(200),e -> persist()));
    private long generation;
    private String validation="",saveFailure="";
    private String ignored;
    public final KeyboardEditor keyboard;
    public final ProfilesEditor profiles;
    private final Label error=new Label(" ");
    private final Label fontStatus=note(""),paletteStatus=note("");
    private final Button designer,closeButton,retryButton,reloadButton;
    private final Stage dialog;
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

    public Preferences(Workspace workspace,String category) {
        this.workspace=workspace;
        this.dialog=Ui.dialogStage(workspace.stage(),"Settings",false);
        Appearance a=workspace.appearanceSettings();
        cursorStyle.setValue(a.cursorStyle());
        startup=new CheckBox("Connect restored panes on startup"); startup.setSelected(workspace.connectOnStartup());
        window=new CheckBox("Show whole-window outline"); window.setSelected(a.windowOutline());
        pane=new CheckBox("Outline active pane"); pane.setSelected(a.outline());
        tint=new CheckBox("Tint active pane background"); tint.setSelected(workspace.highlightActive());
        checkUpdates=new CheckBox("Check for updates at startup"); checkUpdates.setSelected(workspace.updateSettings().startup());
        ignored=workspace.updateSettings().ignoredVersion();
        channel.setValue(workspace.updateSettings().channel());

        keyboard=new KeyboardEditor(workspace);
        profiles=new ProfilesEditor(workspace,workspace.active()==null ? workspace.lastProfile() : workspace.active().profile(),true,this::closeSettings);
        designer=Ui.button("Theme designer...",this::designTheme);
        loadThemes();

        scope.setItems(FXCollections.observableArrayList(workspace.profileList()));
        for (Profile profile:workspace.profileList()) {
            displays.put(profile.id(),new Display(profile.theme(),profile.effect()));
        }
        originalDisplays.putAll(displays);
        scope.setValue(workspace.active()==null ? workspace.lastProfile() : workspace.active().profile());
        loadDisplay();

        scope.valueProperty().addListener((obs,oldV,newV) -> { if (!loading && newV!=null) { loadDisplay(); refresh(); } });
        palette.valueProperty().addListener((obs,oldV,newV) -> { if (!loading && newV!=null) { displayChanged("appearance-palette"); edited(false); } });
        effect.valueProperty().addListener((obs,oldV,newV) -> { if (!loading && newV!=null) { displayChanged("appearance-effect"); edited(false); } });
        theme.valueProperty().addListener((obs,oldV,newV) -> { if (!loading && newV!=null) { paletteEnabled(); edited(false); workspace.tutorialAction("appearance-theme"); } });
        cursorStyle.valueProperty().addListener((obs,oldV,newV) -> { if (!loading && newV!=null) edited(false); });
        channel.valueProperty().addListener((obs,oldV,newV) -> { if (!loading && newV!=null) edited(false); });

        List<String> families=new ArrayList<>();
        families.add("Automatic");
        families.addAll(TerminalFonts.installed());
        family.setItems(FXCollections.observableArrayList(families));
        family.setValue(a.font().family());
        fontPath.setText(a.font().file());
        family.valueProperty().addListener((obs,oldV,newV) -> { if (!loading && newV!=null) { edited(false); workspace.tutorialAction("appearance-font"); } });

        Appearance.PaneInfo i=a.info();
        String[] names={"Show header","Profile name","Connection state","Host","Requested device name",
                "Show footer","Connection / input status","Screen size","Cursor row:column","Insert indicator","Device name"};
        boolean[] flags={i.header(),i.profile(),i.connection(),i.host(),i.device(),i.footer(),i.status(),i.dimensions(),i.cursor(),i.insert(),i.footerDevice()};
        for (int n=0;n<names.length;n++) {
            CheckBox cb=new CheckBox(names[n]);
            cb.setSelected(flags[n]);
            info.add(cb);
        }
        info.get(0).selectedProperty().addListener((o,oldV,newV) -> { if (!loading) workspace.tutorialAction("appearance-header"); });
        info.get(5).selectedProperty().addListener((o,oldV,newV) -> { if (!loading) workspace.tutorialAction("appearance-footer"); });

        addPage("Appearance",appearancePage(),true);
        addPage("Profiles",profiles,false);
        addPage("Keyboard Shortcuts",keyboard,false);
        addPage("General",general(),true);
        addPage("Updates",updates(),false);

        navigation.getStyleClass().add("fish-sidebar");
        navigation.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        navigation.setFixedCellSize(34);
        navigation.setPrefWidth(172);
        navigation.setAccessibleText("Settings categories");
        navigation.getSelectionModel().selectedItemProperty().addListener((obs,oldV,newV) -> {
            if (newV==null) return;
            pageMap.forEach((k,node) -> { boolean match=k.equals(newV); node.setVisible(match); node.setManaged(match); });
            workspace.tutorialAction("settings-"+newV);
            workspace.tutorialChanged();
            if (newV.equals("Updates")) workspace.updateController().ensureCheck();
        });

        closeButton=Ui.button("Close",this::closeSettings);
        closeButton.setDefaultButton(true);
        retryButton=Ui.button("Retry save",() -> { saveFailure=""; apply(); });
        reloadButton=Ui.button("Reload saved values",this::reload);

        HBox buttons=new HBox(8,reloadButton,retryButton,closeButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Ui.role(error,"muted");
        error.setAccessibleText("Settings save status");
        VBox footer=new VBox(8,error,buttons);
        footer.getStyleClass().add("fish-settings-footer");

        BorderPane content=new BorderPane();
        content.setPadding(new Insets(16));
        content.setLeft(navigation);
        BorderPane.setMargin(navigation,new Insets(0,18,0,0));
        content.setCenter(pages);
        content.setBottom(footer);
        BorderPane.setMargin(footer,new Insets(10,0,0,0));

        Ui.decorateDialog(dialog,content,this::closeSettings,980,690);
        dialog.setMinWidth(760);
        dialog.setMinHeight(540);
        dialog.setOnShown(e -> { navigation.requestFocus(); workspace.tutorialChanged(); });

        saved=draft();
        baseline=currentShared();

        startup.selectedProperty().addListener((o,oldV,newV) -> {
            if (!loading) { edited(false); workspace.tutorialAction("startup-changed"); }
        });
        for (CheckBox cb:List.of(window,pane,tint,checkUpdates)) {
            cb.selectedProperty().addListener((o,oldV,newV) -> edited(false));
        }
        for (int idx=0;idx<info.size();idx++) {
            CheckBox cb=info.get(idx);
            cb.selectedProperty().addListener((o,oldV,newV) -> edited(false));
        }
        fontPath.textProperty().addListener((o,oldV,newV) -> edited(true));
        keyboard.setOnDraftChanged(() -> edited(true));
        profiles.setOnDraftChanged(() -> {
            syncProfiles();
            refresh();
            if (closeRequested && !saving && !profiles.saving()) Ui.runLater(this::closeSettings);
        });

        select(category);
        paletteEnabled();
        refresh();
    }

    public Stage dialog() { return dialog; }
    public boolean ended() { return ended; }
    public void focus() { navigation.requestFocus(); }
    public ListView<String> navigationList() { return navigation; }
    public ComboBox<Object> themeCombo() { return theme; }
    public ComboBox<Profile> scopeCombo() { return scope; }
    public ComboBox<String> paletteCombo() { return palette; }
    public ComboBox<String> effectCombo() { return effect; }
    public ComboBox<Appearance.CursorStyle> cursorStyleCombo() { return cursorStyle; }
    public ComboBox<String> familyCombo() { return family; }
    public TextField fontPathField() { return fontPath; }
    public CheckBox startupCheck() { return startup; }
    public CheckBox windowOutlineCheck() { return window; }
    public CheckBox paneOutlineCheck() { return pane; }
    public CheckBox tintCheck() { return tint; }
    public Label errorLabel() { return error; }
    public Button reloadButton() { return reloadButton; }
    public Button retryButton() { return retryButton; }
    public Button closeButton() { return closeButton; }
    public boolean hasConflict() { return conflict; }
    public String validationError() { return validation; }

    public void closeSettings() {
        if (!workspace.closing() && !requestClose()) return;
        forceDispose();
    }

    void forceDispose() {
        if (ended) return;
        ended=true;
        edits.stop();
        writes.stop();
        editPending=false;
        workspace.updateController().detach(dialog);
        profiles.discard();
        dialog.close();
        workspace.preferencesClosed(this);
    }

    void syncProfiles() {
        if (syncing || applying || saved==null) return;
        syncing=true;
        try {
            Map<UUID,Display> base=new LinkedHashMap<>(saved.displays());
            Set<UUID> ids=new HashSet<>();
            for (Profile profile:workspace.profileList()) {
                ids.add(profile.id()); Display current=new Display(profile.theme(),profile.effect());
                Display original=originalDisplays.get(profile.id());
                if (original==null || original.equals(displays.get(profile.id()))) {
                    displays.put(profile.id(),current); originalDisplays.put(profile.id(),current); base.put(profile.id(),current);
                }
            }
            for (UUID id:new HashSet<>(displays.keySet())) if (!ids.contains(id)) {
                displays.remove(id); originalDisplays.remove(id); base.remove(id);
            }
            saved=saved.withDisplays(base);
            UUID selId=displayProfile;
            loading=true;
            scope.setItems(FXCollections.observableArrayList(workspace.profileList()));
            for (Profile p:scope.getItems()) if (p.id().equals(selId)) { scope.setValue(p); break; }
            loading=false;
            loadDisplay();
            profiles.rebaseDisplay();
        } finally { syncing=false; }
    }

    public boolean saving() { return saving || unsaved && saveFailure.isEmpty() || editPending || profiles.saving(); }
    public boolean customPalette() { return theme.getValue() instanceof ColorTheme; }
    public String category() { return navigation.getSelectionModel().getSelectedItem(); }

    public void select(String category) {
        String name=switch (category) {
            case "Terminal","Panes" -> "Appearance";
            case "Keyboard" -> "Keyboard Shortcuts";
            default -> category;
        };
        if (CATEGORIES.contains(name)) navigation.getSelectionModel().select(name);
        else navigation.getSelectionModel().selectFirst();
    }

    public Node tutorialTarget(String control) {
        if (control.startsWith("keyboard-")) return keyboard.tutorialTarget(control.substring(9));
        Node resolved=control.equals("apply") ? error : targets.getOrDefault(control,navigation);
        scrollIntoView(resolved);
        return resolved;
    }

    private static void scrollIntoView(Node target) {
        if (target==null) return;
        for (Node cur=target.getParent();cur!=null;cur=cur.getParent()) {
            if (cur instanceof ScrollPane sp && sp.getContent() instanceof Region content) {
                sp.applyCss();
                sp.layout();
                double contentH=content.getBoundsInLocal().getHeight();
                double viewportH=sp.getViewportBounds().getHeight();
                if (contentH>viewportH+1.0 && target.getScene()!=null) {
                    javafx.geometry.Bounds sceneBounds=target.localToScene(target.getBoundsInLocal());
                    javafx.geometry.Bounds inContent=content.sceneToLocal(sceneBounds);
                    if (inContent!=null) {
                        double maxScroll=contentH-viewportH;
                        double curTop=sp.getVvalue()*maxScroll;
                        double minY=inContent.getMinY(),maxY=inContent.getMaxY();
                        double nextTop=curTop;
                        if (minY<curTop+8.0) nextTop=Math.max(0.0,minY-12.0);
                        else if (maxY>curTop+viewportH-8.0) nextTop=Math.min(maxScroll,maxY-viewportH+16.0);
                        if (Math.abs(nextTop-curTop)>0.5) {
                            sp.setVvalue(Math.max(0.0,Math.min(1.0,nextTop/maxScroll)));
                            sp.layout();
                        }
                    }
                }
                break;
            }
        }
    }

    private void loadDisplay() {
        Profile profile=scope.getValue();
        if (profile==null) return;
        loading=true;
        displayProfile=profile.id();
        Display value=displays.get(profile.id());
        if (value!=null) {
            palette.setValue(value.palette());
            effect.setValue(value.effect());
        }
        loading=false;
    }

    private void displayChanged(String action) {
        if (loading || displayProfile==null) return;
        displays.put(displayProfile,new Display(palette.getValue(),effect.getValue()));
        refresh();
        workspace.tutorialAction(action);
    }

    private void paletteEnabled() {
        boolean isCustom=theme.getValue() instanceof ColorTheme;
        palette.setDisable(isCustom);
        paletteStatus.setText(isCustom ? "This custom application theme supplies host colors. Choose a built-in theme to use the profile palette." : "");
        paletteStatus.setVisible(isCustom);
        paletteStatus.setManaged(isCustom);
    }

    private void designTheme() {
        if (dirty() || saving()) return;
        workspace.executeCommand("Theme designer...");
        loadThemes(); saved=draft(); baseline=currentShared(); paletteEnabled(); refresh(); designer.requestFocus();
    }

    private void addPage(String name,Region body,boolean scroll) {
        BorderPane page=new BorderPane();
        Label heading=new Label(name);
        heading.getStyleClass().add("fish-page-heading");
        page.setTop(heading);
        BorderPane.setMargin(heading,new Insets(0,0,12,0));
        if (scroll) {
            ScrollPane sp=new ScrollPane(body);
            sp.setFitToWidth(true);
            sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            page.setCenter(sp);
        } else {
            page.setCenter(body);
        }
        pageMap.put(name,page);
        pages.getChildren().add(page);
    }

    public VBox appearancePage() {
        VBox body=new VBox(9);
        for (ComboBox<?> cb:List.of(theme,scope,palette,effect,cursorStyle,family)) cb.setMaxWidth(Double.MAX_VALUE);

        body.getChildren().add(section("Colors"));
        body.getChildren().add(row("Application theme",theme));
        body.getChildren().add(row("Terminal profile",scope));
        body.getChildren().add(note("Palette and effect apply to the selected profile, including its other panes. This does not switch your session."));
        body.getChildren().add(row("Text palette",palette));
        body.getChildren().add(paletteStatus);
        body.getChildren().add(row("Text effect (opt-in)",effect));
        cursorStyle.setTooltip(new Tooltip("Replace mode uses an underline or outline; Insert fills the cell. Filled uses a block in both modes."));
        body.getChildren().add(row("Cursor style",cursorStyle));
        body.getChildren().add(row("Custom theme",designer));

        targets.put("theme",theme); targets.put("palette",palette); targets.put("effect",effect); targets.put("scope",scope);

        body.getChildren().add(section("Terminal font"));
        body.getChildren().add(row("Monospaced family",family));
        targets.put("font",family);

        fontPath.setAccessibleText("Local custom TTF or OTF font path");
        fontPath.setPromptText("Optional .ttf or .otf path");
        HBox.setHgrow(fontPath,Priority.ALWAYS);
        Button browse=Ui.button("Browse...",() -> {
            FileChooser chooser=new FileChooser();
            chooser.setTitle("Choose terminal font");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Local monospaced fonts (*.ttf, *.otf)","*.ttf","*.otf"));
            File chosen=chooser.showOpenDialog(dialog);
            if (chosen!=null) fontPath.setText(chosen.getAbsolutePath());
        });
        body.getChildren().add(rowNode("Custom file (optional)",new HBox(8,fontPath,browse),fontPath));

        Label preview=new Label("Aa 0O 1Il | 0123456789 [] {} @ #");
        preview.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 15px;");
        Button checkFont=Ui.button("Check font",() -> {
            try {
                Font resolved=TerminalFonts.resolve(fontChoice());
                TerminalFonts.validate(resolved,workspace.profileList());
                preview.setStyle("-fx-font-family: '"+resolved.getFamily()+"'; -fx-font-size: 15px;");
                fontStatus.setText(""); fontStatus.setVisible(false); fontStatus.setManaged(false);
                Ui.markInvalid(fontPath,false);
            } catch (IOException | IllegalArgumentException e) {
                fontStatus.setText(e.getMessage());
                fontStatus.setTooltip(new Tooltip(e.getMessage()));
                fontStatus.setVisible(true); fontStatus.setManaged(true);
                Ui.markInvalid(fontPath,true);
            }
        });
        BorderPane sample=new BorderPane();
        sample.setLeft(preview);
        BorderPane.setAlignment(preview,Pos.CENTER_LEFT);
        sample.setRight(checkFont);
        body.getChildren().add(sample);
        Ui.role(fontStatus,"error");
        fontStatus.setVisible(false); fontStatus.setManaged(false);
        body.getChildren().add(fontStatus);

        body.getChildren().add(section("Pane information"));
        GridPane columns=new GridPane();
        columns.setHgap(24);
        ColumnConstraints half=new ColumnConstraints(); half.setPercentWidth(50);
        columns.getColumnConstraints().addAll(half,half);
        for (int group=0;group<2;group++) {
            VBox part=new VBox(7);
            int start=group*5;
            for (int n=start;n<start+5;n++) part.getChildren().add(info.get(n));
            if (group==1) part.getChildren().add(info.get(10));
            Runnable dependencies=() -> {
                for (int n=start+1;n<start+5;n++) info.get(n).setDisable(!info.get(start).isSelected());
                if (start==5) info.get(10).setDisable(!info.get(5).isSelected());
            };
            info.get(start).selectedProperty().addListener((o,a,b) -> dependencies.run());
            dependencies.run();
            columns.add(part,group,0);
        }
        targets.put("header",info.get(0)); targets.put("footer",info.get(5));
        body.getChildren().add(columns);

        body.getChildren().add(section("Window and focus"));
        body.getChildren().addAll(window,pane,tint);
        targets.put("outline",window);
        return body;
    }

    private VBox general() {
        VBox body=new VBox(9);
        body.getChildren().add(section("Startup"));
        body.getChildren().add(startup);
        targets.put("startup",startup);
        body.getChildren().add(note("Controls ordinary restored-window startup. Existing off choices are kept. Explicit +, New window and saved-layout launches still connect once; failures offer Reconnect."));

        body.getChildren().add(section("Installation (Current User)"));
        Optional<Path> appImage=UserInstaller.currentAppImage();
        Path targetInstallDir=UserInstaller.defaultInstallDirectory();
        boolean alreadyInstalled=appImage.isPresent() && UserInstaller.isInstalledLocation(appImage.get());
        Label installStatus=note(appImage.isEmpty()
                ? "Running from development classes/JAR. Package Fish (build.ps1 Package) or run Install-Fish.ps1 to install for your Windows account."
                : alreadyInstalled
                ? "Installed for your Windows account in: "+appImage.get()
                : "Running in portable mode from: "+appImage.get()+"\nInstall for current user copies Fish cleanly to "+targetInstallDir+" with Start Menu integration and no administrator prompt or SmartScreen block.");
        CheckBox desktopLink=new CheckBox("Also create a Desktop shortcut");
        Label installFeedback=note("");
        installFeedback.setVisible(false); installFeedback.setManaged(false);
        Button installBtn=Ui.button(alreadyInstalled ? "Installed in user profile" : "Install for current user...",() -> {});
        installBtn.setGraphic(Ui.icon("install"));
        installBtn.setDisable(appImage.isEmpty() || alreadyInstalled);
        installBtn.setOnAction(e -> {
            if (appImage.isEmpty()) return;
            installBtn.setDisable(true);
            installFeedback.setText("Installing Fish into "+targetInstallDir+"...");
            Ui.role(installFeedback,"muted");
            installFeedback.setVisible(true); installFeedback.setManaged(true);
            boolean makeDesktop=desktopLink.isSelected();
            CompletableFuture.supplyAsync(() -> {
                try { return UserInstaller.install(appImage.get(),targetInstallDir,true,makeDesktop,true); }
                catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
            }).whenComplete((installedExe,err) -> Ui.runLater(() -> {
                if (err!=null) {
                    Throwable cause=err.getCause()!=null ? err.getCause() : err;
                    if (cause instanceof java.io.UncheckedIOException uio && uio.getCause()!=null) cause=uio.getCause();
                    Ui.role(installFeedback,"error");
                    installFeedback.setText("Install failed: "+(cause.getMessage()==null ? cause.toString() : cause.getMessage()));
                    installBtn.setDisable(false);
                } else {
                    Ui.role(installFeedback,"muted");
                    installFeedback.setText("Installed at "+installedExe+" (Start Menu shortcut and Windows Installed Apps entry updated).");
                }
            }));
        });
        body.getChildren().addAll(installStatus,desktopLink,installBtn,installFeedback);

        body.getChildren().add(section("Reset"));
        body.getChildren().add(Ui.button("Revert to factory defaults...",workspace::factoryDefaults));
        body.getChildren().add(note("After confirmation, ends sessions and exits. Profiles and named layouts are cleared. Windows passwords, local font files and exported shortcut files are kept."));
        return body;
    }

    private BorderPane updates() {
        channel.setMaxWidth(Double.MAX_VALUE);
        VBox settings=new VBox(9);
        settings.getChildren().addAll(
                checkUpdates,
                row("Update channel",channel),
                note("Preview: patches in this minor version. Stable: releases in this major version. No downgrade, reinstall, or automatic new experimental minor."));
        Label hidden=new Label(ignored.isEmpty() ? "No hidden version" : "Hidden on startup: "+ignored);
        Button showHidden=Ui.button("Show hidden updates",() -> {
            ignored="";
            hidden.setText("No hidden version");
            edited(false);
        });
        BorderPane visibility=new BorderPane();
        visibility.setLeft(hidden);
        BorderPane.setAlignment(hidden,Pos.CENTER_LEFT);
        visibility.setRight(showHidden);
        settings.getChildren().add(visibility);

        BorderPane body=new BorderPane();
        body.setTop(settings);
        BorderPane.setMargin(settings,new Insets(0,0,14,0));
        body.setCenter(workspace.updateController().panel(dialog,() -> dirty() || saving() || profiles.dirty(),channel::getValue));
        return body;
    }

    private static HBox row(String title,Control input) {
        return rowNode(title,input,input);
    }

    private static HBox rowNode(String title,Node container,Control target) {
        Label label=new Label(title);
        label.setLabelFor(target);
        label.setMinWidth(166);
        label.setPrefWidth(166);
        if (target.getAccessibleText()==null) target.setAccessibleText(title);
        HBox.setHgrow(container,Priority.ALWAYS);
        HBox box=new HBox(12,label,container);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static HBox section(String name) {
        Label label=new Label(name);
        label.getStyleClass().add("fish-section-title");
        Separator sep=new Separator();
        HBox.setHgrow(sep,Priority.ALWAYS);
        HBox box=new HBox(10,label,sep);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(10,0,2,0));
        return box;
    }

    private static Label note(String value) {
        Label lbl=new Label(value);
        lbl.setWrapText(true);
        Ui.role(lbl,"muted");
        return lbl;
    }

    private void loadThemes() {
        List<Object> items=new ArrayList<>(Arrays.asList(AppTheme.values()));
        items.addAll(workspace.appearanceSettings().themes());
        theme.setItems(FXCollections.observableArrayList(items));
        theme.setValue(workspace.appearanceSettings().custom().<Object>map(t -> t).orElse(Ui.theme()));
    }

    private Appearance.FontChoice fontChoice() {
        return new Appearance.FontChoice(Objects.toString(family.getValue(),"Automatic"),fontPath.getText().strip());
    }

    private Draft draft() {
        return new Draft(startup.isSelected(),theme.getValue(),fontChoice(),window.isSelected(),pane.isSelected(),tint.isSelected(),
                new Appearance.PaneInfo(info.get(0).isSelected(),info.get(5).isSelected(),info.get(1).isSelected(),info.get(2).isSelected(),
                        info.get(3).isSelected(),info.get(4).isSelected(),info.get(6).isSelected(),info.get(7).isSelected(),info.get(8).isSelected(),info.get(9).isSelected(),info.get(10).isSelected()),
                cursorStyle.getValue(),
                new UpdateSettings(checkUpdates.isSelected(),channel.getValue(),ignored),keyboard.draft(),Map.copyOf(displays));
    }

    private boolean dirty() {
        try { return unsaved || conflict || !draft().equals(saved); }
        catch (IllegalArgumentException e) { return true; }
    }

    private void edited(boolean typing) {
        if (saved==null || ended || loading || syncing || applying) return;
        if (displayProfile!=null) displays.put(displayProfile,new Display(palette.getValue(),effect.getValue()));
        if (typing) { editPending=true; edits.playFromStart(); refresh(); }
        else apply();
    }

    private void refresh() {
        if (saved==null || closeButton==null) return;
        boolean changed=dirty();
        designer.setDisable(changed || saving());
        designer.setTooltip(changed ? new Tooltip("Finish or repair pending edits before opening the theme designer.") : null);
        boolean showRetry=!saveFailure.isEmpty();
        retryButton.setVisible(showRetry); retryButton.setManaged(showRetry);
        boolean showReload=conflict || !validation.isEmpty();
        reloadButton.setVisible(showReload); reloadButton.setManaged(showReload);

        String text=!saveFailure.isEmpty() ? "Not saved: "+saveFailure : profiles.failedSave() ? "Profile not saved; use Retry save in Profiles."
                : conflict ? "Changed elsewhere. Reload saved values to review; your edits are kept here."
                : !validation.isEmpty() ? "Not applied: "+validation : profiles.saving() ? "Saving profile..."
                : profiles.dirty() ? "Profile edits need review in Profiles."
                : saving || unsaved || editPending ? "Saving..." : "Saved";
        error.setText(text);
        error.setTooltip(new Tooltip(text));
        Ui.role(error,!saveFailure.isEmpty() || profiles.failedSave() || conflict || !validation.isEmpty() ? "error" : "muted");
        workspace.updateController().refreshAvailability();
        workspace.refreshActions();
    }

    public boolean apply() { return apply(false); }

    public boolean apply(boolean finish) {
        if (ended || applying) return false;
        edits.stop(); editPending=false;
        try {
            if (!Objects.equals(baseline,currentShared())) { conflict=true; refresh(); return false; }
            if (conflict) { refresh(); return false; }
            validation="";
            Appearance.FontChoice font=saved.font();
            try {
                font=fontChoice();
                if (!font.equals(saved.font())) {
                    Font resolved=TerminalFonts.resolve(font);
                    TerminalFonts.validate(resolved,workspace.profileList());
                }
                Ui.markInvalid(fontPath,false);
            } catch (IOException | IllegalArgumentException e) {
                validation=e.getMessage();
                font=saved.font();
                Ui.markInvalid(fontPath,true);
            }
            Map<String,String> keys=saved.keys();
            try { keys=keyboard.draft(); }
            catch (IllegalArgumentException e) { validation=e.getMessage(); }
            Draft next=new Draft(startup.isSelected(),theme.getValue(),font,window.isSelected(),pane.isSelected(),tint.isSelected(),
                    new Appearance.PaneInfo(info.get(0).isSelected(),info.get(5).isSelected(),info.get(1).isSelected(),info.get(2).isSelected(),
                            info.get(3).isSelected(),info.get(4).isSelected(),info.get(6).isSelected(),info.get(7).isSelected(),info.get(8).isSelected(),info.get(9).isSelected(),info.get(10).isSelected()),
                    cursorStyle.getValue(),
                    new UpdateSettings(checkUpdates.isSelected(),channel.getValue(),ignored),keys,Map.copyOf(displays));
            for (Profile profile:workspace.profileList()) {
                Display desired=next.displays().get(profile.id()),previous=saved.displays().get(profile.id());
                Display live=new Display(profile.theme(),profile.effect());
                if (!Objects.equals(desired,previous) && !Objects.equals(live,previous) && !Objects.equals(live,desired))
                    throw new IllegalArgumentException("Terminal colors/effect changed for "+profile.name()+". Reload saved values to review them.");
            }
            for (var entry:next.displays().entrySet()) {
                Display original=originalDisplays.get(entry.getKey());
                if (original.equals(entry.getValue())) continue;
                Profile current=workspace.profileList().stream().filter(p -> p.id().equals(entry.getKey())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("The appearance profile was deleted. Reopen Settings to choose another."));
                if (!original.equals(new Display(current.theme(),current.effect()))) {
                    // Rebase concurrent effect if only palette changed (or vice versa)
                    Display live=new Display(current.theme(),current.effect());
                    boolean paletteEdited=!Objects.equals(entry.getValue().palette(),original.palette());
                    boolean effectEdited=!Objects.equals(entry.getValue().effect(),original.effect());
                    boolean paletteExternal=!Objects.equals(live.palette(),original.palette());
                    boolean effectExternal=!Objects.equals(live.effect(),original.effect());
                    if (!(paletteEdited && paletteExternal) && !(effectEdited && effectExternal)) {
                        Display merged=new Display(paletteEdited ? entry.getValue().palette() : live.palette(),
                                effectEdited ? entry.getValue().effect() : live.effect());
                        displays.put(entry.getKey(),merged);
                        originalDisplays.put(entry.getKey(),live);
                        next=next.withDisplays(displays);
                    } else {
                        throw new IllegalArgumentException("Profile colors changed elsewhere. Reopen Settings to review them.");
                    }
                }
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
            if (unsaved) writes.playFromStart();
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
            if (unsaved) writes.playFromStart();
            else if (closeRequested) forceDispose();
        },message -> {
            saving=false; closeRequested=false; saveFailure=message; refresh();
            if (written!=generation) writes.playFromStart();
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
        closeRequested=false;
        return true;
    }

    public void reload() {
        edits.stop(); editPending=false; loading=true;
        try {
            Appearance a=workspace.appearanceSettings();
            cursorStyle.setValue(a.cursorStyle());
            startup.setSelected(workspace.connectOnStartup()); window.setSelected(a.windowOutline()); pane.setSelected(a.outline());
            tint.setSelected(workspace.highlightActive()); checkUpdates.setSelected(workspace.updateSettings().startup());
            channel.setValue(workspace.updateSettings().channel()); ignored=workspace.updateSettings().ignoredVersion();
            loadThemes(); family.setValue(a.font().family()); fontPath.setText(a.font().file());
            Ui.markInvalid(fontPath,false);
            Appearance.PaneInfo i=a.info();
            boolean[] flags={i.header(),i.profile(),i.connection(),i.host(),i.device(),i.footer(),i.status(),i.dimensions(),i.cursor(),i.insert(),i.footerDevice()};
            for (int n=0;n<flags.length;n++) info.get(n).setSelected(flags[n]);
            keyboard.reload(workspace.shortcutOverrides());
            displays.clear(); originalDisplays.clear();
            for (Profile p:workspace.profileList()) {
                Display display=new Display(p.theme(),p.effect());
                displays.put(p.id(),display); originalDisplays.put(p.id(),display);
            }
            loadDisplay();
            saved=draft(); baseline=currentShared(); conflict=false; validation="";
        } finally { loading=false; }
        refresh();
    }

    public void sharedChanged() {
        if (saved==null || applying || syncing || ended) return;
        if (!Objects.equals(baseline,currentShared())) {
            boolean pending;
            try { pending=conflict || !draft().equals(saved); }
            catch (IllegalArgumentException e) { pending=true; }
            if (!pending) reload();
            else {
                // Allow clean non-conflicting profile display rebases (e.g. concurrent Flux toggle)
                Shared live=currentShared();
                if (Objects.equals(live.appearance(),baseline.appearance())
                        && live.theme()==baseline.theme()
                        && live.tint()==baseline.tint()
                        && live.startup()==baseline.startup()
                        && Objects.equals(live.updates(),baseline.updates())
                        && Objects.equals(live.keys(),baseline.keys())) {
                    baseline=live;
                    return;
                }
                conflict=true; edits.stop(); editPending=false; refresh();
            }
        }
    }
}
