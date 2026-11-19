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
import fish.security.KioskMode;

/** One indexed settings surface, with shared profile and keyboard editors and current-user portable install. */
public final class Preferences {
    public static final List<String> CATEGORIES=List.of("Appearance","Profiles","Keyboard Shortcuts","General","Updates");
    public static final String CUSTOM_COLOR="Custom color";
    private final Workspace workspace;
    private final ListView<String> navigation=new ListView<>(FXCollections.observableArrayList(CATEGORIES));
    private final StackPane pages=new StackPane();
    private final Map<String,Node> pageMap=new LinkedHashMap<>();
    private final CheckBox startup,window,pane,tint,reducedMotion,checkUpdates;
    private final List<CheckBox> info=new ArrayList<>();
    private final ComboBox<Object> theme=new ComboBox<>();
    private final CheckBox gradients=new CheckBox("Surface gradients");
    private final Set<AppTheme> flatThemes=EnumSet.noneOf(AppTheme.class);
    private final ComboBox<Profile> scope=new ComboBox<>();
    private final ComboBox<String> palette=new ComboBox<>(paletteOptions());
    private final PrimaryColorBar primaryColor=new PrimaryColorBar(this::primaryChanged);
    private final HBox palettePreviewStrip=new HBox(10);
    private final Label[] palettePreviewSamples={
            new Label("Primary"),new Label("Header"),new Label("Accent"),new Label("Warn"),new Label("Error")
    };
    private final ComboBox<String> effect=new ComboBox<>(FXCollections.observableArrayList(Profile.EFFECTS));
    private final ComboBox<Appearance.CursorStyle> cursorStyle=new ComboBox<>(FXCollections.observableArrayList(Appearance.CursorStyle.values()));
    private final ComboBox<String> family=new ComboBox<>();
    private final TextField fontPath=new TextField();
    private final Map<UUID,Display> displays=new LinkedHashMap<>(),originalDisplays=new LinkedHashMap<>();
    private final Map<UUID,String> customColors=new LinkedHashMap<>();
    private final Map<String,Node> targets=new HashMap<>();
    private UUID displayProfile;
    private boolean loading,saving,ended,syncing,applying,unsaved,closeRequested,conflict,editPending;
    private final Timeline edits=new Timeline(new KeyFrame(Duration.millis(300),e -> apply()));
    private final Timeline writes=new Timeline(new KeyFrame(Duration.millis(200),e -> persist()));
    private long generation;
    private String validation="",saveFailure="";
    public final KeyboardEditor keyboard;
    public final ProfilesEditor profiles;
    private final Label error=new Label(" ");
    private final Label fontStatus=note(""),paletteStatus=note("");
    private final Button retryButton,reloadButton;
    private final Stage dialog;
    private Draft saved;
    private Shared baseline;

    private record Shared(Appearance appearance,AppTheme theme,boolean tint,boolean startup,UpdateSettings updates,Map<String,String> keys) {}
    private Shared currentShared() {
        return new Shared(workspace.appearanceSettings(),Ui.theme(),workspace.highlightActive(),workspace.connectOnStartup(),
                workspace.updateSettings(),workspace.shortcutOverrides());
    }
    private record Display(String palette,String effect) {}
    private record Draft(boolean startup,Object theme,Appearance.FontChoice font,boolean window,boolean pane,boolean tint,boolean reducedMotion,Set<AppTheme> flatThemes,
                         Appearance.PaneInfo info,Appearance.CursorStyle cursorStyle,UpdateSettings updates,Map<String,String> keys,Map<UUID,Display> displays) {
        Draft withDisplays(Map<UUID,Display> value) { return new Draft(startup,theme,font,window,pane,tint,reducedMotion,flatThemes,info,cursorStyle,updates,keys,Map.copyOf(value)); }
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
        reducedMotion=new CheckBox("Reduce motion"); reducedMotion.setSelected(a.reducedMotion());
        reducedMotion.setTooltip(new Tooltip("Skips in-window transitions and pauses animated terminal text effects."));
        checkUpdates=new CheckBox("Check for updates at startup"); checkUpdates.setSelected(workspace.updateSettings().startup());

        keyboard=new KeyboardEditor(workspace);
        profiles=new ProfilesEditor(workspace,workspace.active()==null ? workspace.lastProfile() : workspace.active().profile(),true,this::closeSettings);
        loadThemes();
        flatThemes.addAll(a.flatThemes());
        syncGradientCheck();
        gradients.setTooltip(new Tooltip("Use subtle surface gradients for this application theme. Terminal ink stays unchanged."));
        gradients.setOnAction(e -> {
            if (theme.getValue() instanceof AppTheme selected) {
                if (gradients.isSelected()) flatThemes.remove(selected); else flatThemes.add(selected);
                edited(false);
            }
        });
        configureColorControls();

        scope.setItems(FXCollections.observableArrayList(workspace.profileList()));
        for (Profile profile:workspace.profileList()) {
            displays.put(profile.id(),new Display(profile.theme(),profile.effect()));
            if (Profile.isCustomColor(profile.theme())) customColors.put(profile.id(),Profile.normalizeTheme(profile.theme()));
        }
        originalDisplays.putAll(displays);
        scope.setValue(workspace.active()==null ? workspace.lastProfile() : workspace.active().profile());
        loadDisplay();

        scope.valueProperty().addListener((obs,oldV,newV) -> { if (!loading && newV!=null) { loadDisplay(); refresh(); } });
        palette.valueProperty().addListener((obs,oldV,newV) -> {
            if (!loading && newV!=null && displayProfile!=null) {
                if (CUSTOM_COLOR.equals(newV)) {
                    String hex=activeCustomHex();
                    customColors.put(displayProfile,hex);
                    primaryColor.setColor(hex);
                }
                displayChanged("appearance-palette");
                refreshColorControls();
                edited(false);
            }
        });
        primaryColor.hue.pressedProperty().addListener((o,was,pressed) -> {
            if (!pressed && editPending && !loading) apply();
        });
        effect.valueProperty().addListener((obs,oldV,newV) -> { if (!loading && newV!=null) { displayChanged("appearance-effect"); edited(false); } });
        theme.valueProperty().addListener((obs,oldV,newV) -> {
            if (!loading && newV!=null) {
                paletteEnabled();
                syncGradientCheck();
                edited(false);
                refreshColorControls();
                workspace.tutorialAction("appearance-theme");
            }
        });
        cursorStyle.valueProperty().addListener((obs,oldV,newV) -> { if (!loading && newV!=null) edited(false); });

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
        navigation.setFixedCellSize(40);
        navigation.setMinWidth(184);
        navigation.setPrefWidth(192);
        navigation.setAccessibleText("Settings categories");
        navigation.setCellFactory(l -> new ListCell<>() {
            @Override protected void updateItem(String item,boolean empty) {
                super.updateItem(item,empty);
                setText(empty ? null : item);
                setGraphic(empty || item==null ? null : Ui.icon(switch(item) {
                    case "Appearance" -> "display"; case "Profiles" -> "layers"; case "Keyboard Shortcuts" -> "keyboard";
                    case "General" -> "gear"; default -> "refresh";
                },ColorRole.TEXT));
                setGraphicTextGap(12);
            }
        });
        navigation.getSelectionModel().selectedItemProperty().addListener((obs,oldV,newV) -> {
            if (newV==null) return;
            pageMap.forEach((k,node) -> { boolean match=k.equals(newV); node.setVisible(match); node.setManaged(match); });
            Node activePage=pageMap.get(newV);
            if (oldV!=null && activePage!=null) Ui.animateSubtle(activePage,0.65,0.0,75);
            workspace.tutorialAction("settings-"+newV);
            workspace.tutorialChanged();
            if (newV.equals("Updates")) workspace.updateController().ensureCheck();
        });

        retryButton=Ui.button("Retry save",() -> { saveFailure=""; apply(); });
        reloadButton=Ui.button("Reload saved values",this::reload);

        Ui.role(error,"muted");
        error.setAccessibleText("Settings save status");
        error.setWrapText(true);
        error.setMaxWidth(Double.MAX_VALUE);
        HBox status=new HBox(8,Ui.icon("check",ColorRole.MUTED),error);
        status.setAlignment(Pos.CENTER_LEFT);
        VBox footer=new VBox(6,status,reloadButton,retryButton);
        footer.getStyleClass().add("fish-settings-footer");

        VBox rail=new VBox(navigation,footer);
        rail.setMinWidth(192); rail.setPrefWidth(192); rail.setMaxWidth(192);
        VBox.setVgrow(navigation,Priority.ALWAYS);
        BorderPane content=new BorderPane();
        content.getStyleClass().add("fish-settings");
        content.setLeft(rail);
        content.setCenter(pages);

        Ui.decorateSettings(dialog,content,rail,this::closeSettings,980,714);
        dialog.setMinWidth(760);
        dialog.setMinHeight(540);
        dialog.setOnShown(e -> { navigation.requestFocus(); workspace.tutorialChanged(); });

        saved=draft();
        baseline=currentShared();

        startup.selectedProperty().addListener((o,oldV,newV) -> {
            if (!loading) { edited(false); workspace.tutorialAction("startup-changed"); }
        });
        for (CheckBox cb:List.of(window,pane,tint,reducedMotion,checkUpdates)) {
            cb.selectedProperty().addListener((o,oldV,newV) -> edited(false));
        }
        for (int idx=0;idx<info.size();idx++) {
            CheckBox cb=info.get(idx);
            cb.selectedProperty().addListener((o,oldV,newV) -> edited(false));
        }
        fontPath.textProperty().addListener((o,oldV,newV) -> edited(true));
        keyboard.setOnDraftChanged(() -> edited(true));
        profiles.setOnDraftChanged(() -> {
            refresh();
            if (profiles.failedSave()) closeRequested=false;
            if (closeRequested && !saving && !profiles.saving()) Ui.runLater(() -> {
                if (closeRequested && !ended) closeSettings();
            });
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
    public Slider primaryHue() { return primaryColor.hue; }
    public TextField primaryHex() { return primaryColor.hex; }
    public ComboBox<String> effectCombo() { return effect; }
    public ComboBox<Appearance.CursorStyle> cursorStyleCombo() { return cursorStyle; }
    public ComboBox<String> familyCombo() { return family; }
    public TextField fontPathField() { return fontPath; }
    public CheckBox startupCheck() { return startup; }
    public CheckBox windowOutlineCheck() { return window; }
    public CheckBox paneOutlineCheck() { return pane; }
    public CheckBox tintCheck() { return tint; }
    public CheckBox reducedMotionCheck() { return reducedMotion; }
    public CheckBox gradientCheck() { return gradients; }
    public Label errorLabel() { return error; }
    public Button reloadButton() { return reloadButton; }
    public Button retryButton() { return retryButton; }
    public boolean hasConflict() { return conflict; }
    public String validationError() { return validation; }

    public void closeSettings() {
        if (!workspace.closing() && !requestClose()) return;
        forceDispose();
    }

    public void forceDispose() {
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
                if (Profile.isCustomColor(profile.theme())) customColors.put(profile.id(),Profile.normalizeTheme(profile.theme()));
                Display original=originalDisplays.get(profile.id());
                if (original==null || original.equals(displays.get(profile.id()))) {
                    displays.put(profile.id(),current); originalDisplays.put(profile.id(),current); base.put(profile.id(),current);
                }
            }
            for (UUID id:new HashSet<>(displays.keySet())) if (!ids.contains(id)) {
                displays.remove(id); originalDisplays.remove(id); customColors.remove(id); base.remove(id);
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

    private static javafx.collections.ObservableList<String> paletteOptions() {
        List<String> items=new ArrayList<>(Profile.THEMES);
        items.add(CUSTOM_COLOR);
        return FXCollections.observableArrayList(items);
    }

    private String activeCustomHex() {
        if (displayProfile!=null && customColors.containsKey(displayProfile)) return customColors.get(displayProfile);
        Display display=displays.get(displayProfile);
        return Profile.formatCustomColor(HostPalette.primaryPreviewRgb(display==null ? Profile.THEMES.getFirst() : display.palette()));
    }

    private String effectivePaletteValue() {
        String selected=palette.getValue();
        if (CUSTOM_COLOR.equals(selected)) return activeCustomHex();
        return selected!=null ? selected : "Phosphor";
    }

    private static Region colorSwatch(int rgb,double size) {
        Region dot=new Region();
        dot.setMinSize(size,size);
        dot.setPrefSize(size,size);
        dot.setMaxSize(size,size);
        String border=Ui.theme().dark ? "rgba(255,255,255,0.26)" : "rgba(0,0,0,0.22)";
        dot.setStyle(String.format(Locale.ROOT,
                "-fx-background-color: #%06X; -fx-background-radius: 3px; -fx-border-color: %s; -fx-border-radius: 3px; -fx-border-width: 1px;",
                rgb&0xffffff,border));
        return dot;
    }

    private ListCell<String> newPaletteCell() {
        return new ListCell<>() {
            @Override protected void updateItem(String item,boolean empty) {
                super.updateItem(item,empty);
                if (empty || item==null) { setText(null); setGraphic(null); return; }
                setText(item);
                setGraphic(CUSTOM_COLOR.equals(item) ? null : colorSwatch(HostPalette.primaryPreviewRgb(item),11));
                setGraphicTextGap(8);
            }
        };
    }

    private void configureColorControls() {
        palette.setCellFactory(lv -> newPaletteCell());
        palette.setButtonCell(newPaletteCell());

        palettePreviewStrip.setAlignment(Pos.CENTER_LEFT);
        palettePreviewStrip.setPadding(new Insets(4,10,4,10));
        palettePreviewStrip.getChildren().setAll(palettePreviewSamples);
    }

    private void refreshColorControls() {
        palette.setButtonCell(newPaletteCell());
        if (CUSTOM_COLOR.equals(palette.getValue())) refreshInkPreview();
    }

    private void primaryChanged(String hex) {
        if (loading || displayProfile==null || !CUSTOM_COLOR.equals(palette.getValue())) return;
        customColors.put(displayProfile,hex);
        refreshInkPreview();
        workspace.tutorialAction("appearance-palette");
        // The samples follow the pointer; coalesce profile writes during continuous drags.
        edited(primaryColor.hue.isPressed());
    }

    private void refreshInkPreview() {
        String effective=effectivePaletteValue();

        java.awt.Color[] slots=HostPalette.colors(effective);
        int bgRgb=Ui.color(ColorRole.HOST_0).getRGB()&0xffffff;
        int borderRgb=Ui.color(ColorRole.DIVIDER).getRGB()&0xffffff;
        palettePreviewStrip.setStyle(String.format(Locale.ROOT,
                "-fx-background-color: #%06X; -fx-background-radius: 5px; -fx-border-color: #%06X; -fx-border-radius: 5px; -fx-border-width: 1px;",
                bgRgb,borderRgb));
        int[] sampleSlots={2,1,3,6,4};
        for (int i=0;i<sampleSlots.length;i++) {
            int sampleRgb=slots[sampleSlots[i]].getRGB()&0xffffff;
            palettePreviewSamples[i].setStyle(String.format(Locale.ROOT,
                    "-fx-font-family: 'Consolas'; -fx-font-size: 11.5px; -fx-font-weight: bold; -fx-text-fill: #%06X;",
                    sampleRgb));
        }
    }

    private void loadDisplay() {
        Profile profile=scope.getValue();
        if (profile==null) return;
        loading=true;
        try {
            displayProfile=profile.id();
            Display value=displays.get(profile.id());
            if (value!=null) {
                String pal=value.palette();
                if (Profile.isCustomColor(pal)) {
                    String hex=Profile.normalizeTheme(pal);
                    customColors.put(profile.id(),hex);
                    palette.setValue(CUSTOM_COLOR);
                    primaryColor.setColor(hex);
                } else {
                    palette.setValue(pal);
                }
                effect.setValue(value.effect());
            }
        } finally { loading=false; }
        refreshColorControls();
    }

    private void displayChanged(String action) {
        if (loading || displayProfile==null) return;
        displays.put(displayProfile,new Display(effectivePaletteValue(),effect.getValue()));
        refresh();
        workspace.tutorialAction(action);
    }

    private void paletteEnabled() {
        boolean isCustom=theme.getValue() instanceof ColorTheme;
        palette.setDisable(isCustom);
        primaryColor.setDisable(isCustom);
        palettePreviewStrip.setOpacity(isCustom ? 0.55 : 1.0);
        paletteStatus.setText(isCustom ? "This legacy custom application theme supplies host colors. Choose a built-in theme to use profile palettes or custom colors." : "");
        paletteStatus.setVisible(isCustom);
        paletteStatus.setManaged(isCustom);
    }

    private void syncGradientCheck() {
        gradients.setDisable(!(theme.getValue() instanceof AppTheme));
        gradients.setSelected(!flatThemes.contains(theme.getValue()));
    }

    private void addPage(String name,Region body,boolean scroll) {
        BorderPane page=new BorderPane();
        Label heading=new Label(name);
        heading.getStyleClass().add("fish-page-heading");
        heading.setMaxWidth(Double.MAX_VALUE);
        page.setTop(heading);
        body.getStyleClass().add("fish-settings-body");
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
        VBox body=new VBox(8);
        for (ComboBox<?> cb:List.of(theme,scope,palette,effect,cursorStyle,family)) cb.setMaxWidth(Double.MAX_VALUE);

        body.getChildren().add(section("Colors"));
        HBox.setHgrow(theme,Priority.ALWAYS);
        theme.setMinWidth(0);
        gradients.setMinWidth(Region.USE_PREF_SIZE);
        HBox themeRow=new HBox(12,theme,gradients);
        themeRow.setAlignment(Pos.CENTER_LEFT);
        body.getChildren().add(rowNode("Application theme",themeRow,theme));
        body.getChildren().add(row("Terminal profile",scope));
        scope.setTooltip(new Tooltip("Palette, primary color, and effect apply to all panes using this profile."));

        body.getChildren().add(row("Text palette",palette));
        primaryColor.setMaxWidth(Double.MAX_VALUE);
        HBox colorInput=rowNode("Primary color",primaryColor,primaryColor.hue);
        HBox.setHgrow(palettePreviewStrip,Priority.ALWAYS);
        HBox colorPreview=rowNode("Terminal preview",new HBox(8,palettePreviewStrip,primaryColor.hex),palettePreviewStrip);
        VBox customRow=new VBox(4,colorInput,colorPreview);
        customRow.visibleProperty().bind(palette.valueProperty().isEqualTo(CUSTOM_COLOR));
        customRow.managedProperty().bind(customRow.visibleProperty());
        body.getChildren().add(customRow);

        body.getChildren().add(paletteStatus);
        body.getChildren().add(row("Text effect (opt-in)",effect));
        cursorStyle.setTooltip(new Tooltip("Replace mode uses an underline or outline; Insert fills the cell. Filled uses a block in both modes."));
        body.getChildren().add(row("Cursor style",cursorStyle));

        targets.put("theme",theme); targets.put("palette",palette); targets.put("primary-color",primaryColor.hue);
        targets.put("effect",effect); targets.put("scope",scope);

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
        HBox sample=new HBox(10,preview,checkFont);
        sample.setAlignment(Pos.CENTER_LEFT);
        preview.setMinWidth(0);
        preview.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(preview,Priority.ALWAYS);
        checkFont.setMinWidth(Region.USE_PREF_SIZE);
        body.getChildren().add(rowNode("Preview",sample,checkFont));
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
            CheckBox visibility=info.get(start);
            part.getChildren().add(visibility);
            VBox fields=new VBox(7);
            fields.getChildren().addAll(info.subList(start+1,group==1 ? 11 : 5));
            TitledPane disclosure=new TitledPane("Configure fields",fields);
            disclosure.setAccessibleText((group==0 ? "Header" : "Footer")+" fields");
            disclosure.setExpanded(false); disclosure.setAnimated(false);
            disclosure.getStyleClass().add("fish-fields-disclosure");
            part.getChildren().add(disclosure);
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
        body.getChildren().addAll(window,pane,tint,reducedMotion);
        targets.put("outline",window);
        return body;
    }

    private VBox general() {
        VBox body=new VBox(8);
        body.getChildren().add(section("Startup"));
        body.getChildren().add(startup);
        targets.put("startup",startup);

        DesktopShortcutForm shortcut=new DesktopShortcutForm(workspace);
        body.getChildren().addAll(section("Desktop shortcuts"),note("Open this window’s pane arrangement from your Desktop."),shortcut);
        targets.put("shortcut-name",shortcut.name);
        targets.put("shortcut-create",shortcut.create);
        targets.put("shortcut-status",shortcut.status);
        targets.put("shortcut-autostart",shortcut.autostart);

        body.getChildren().add(section("Installation"));
        Optional<Path> appImage=UserInstaller.currentAppImage();
        Path targetInstallDir=UserInstaller.defaultInstallDirectory();
        Path systemInstallDir=UserInstaller.defaultSystemInstallDirectory();
        boolean alreadyInstalled=appImage.isPresent() && UserInstaller.isInstalledLocation(appImage.get());
        boolean alreadySystemInstalled=appImage.isPresent() && UserInstaller.isSystemInstalledLocation(appImage.get());
        Label installStatus=note(appImage.isEmpty()
                ? "Development build"
                : alreadySystemInstalled
                ? "Installed for all users"
                : alreadyInstalled
                ? "Installed for this account"
                : "Portable copy");
        CheckBox desktopLink=new CheckBox("Also create a Desktop shortcut");
        Label installFeedback=note("");
        installFeedback.setVisible(false); installFeedback.setManaged(false);
        Button installBtn=Ui.button(alreadyInstalled ? "Installed in user profile" : "Install for current user...",() -> {});
        installBtn.setId("prefs-install-user");
        installBtn.setGraphic(Ui.icon("install"));
        installBtn.setDisable(appImage.isEmpty() || alreadyInstalled);
        Button systemInstallBtn=Ui.button(alreadySystemInstalled ? "Installed system-wide" : "Install system-wide (Administrator)...",() -> {});
        systemInstallBtn.setId("prefs-install-system");
        systemInstallBtn.setGraphic(Ui.icon("install"));
        systemInstallBtn.setDisable(appImage.isEmpty() || alreadySystemInstalled);
        installBtn.setOnAction(e -> {
            if (appImage.isEmpty()) return;
            installBtn.setDisable(true);
            systemInstallBtn.setDisable(true);
            installFeedback.setText("Installing Fish into "+targetInstallDir+"...");
            Ui.role(installFeedback,"muted");
            installFeedback.setVisible(true); installFeedback.setManaged(true);
            boolean makeDesktop=desktopLink.isSelected();
            CompletableFuture.supplyAsync(() -> {
                try { return UserInstaller.install(appImage.get(),targetInstallDir,true,makeDesktop,true); }
                catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
            }).whenComplete((installedExe,err) -> Ui.runLater(() -> {
                systemInstallBtn.setDisable(appImage.isEmpty() || alreadySystemInstalled);
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
        systemInstallBtn.setOnAction(e -> {
            if (appImage.isEmpty()) return;
            installBtn.setDisable(true);
            systemInstallBtn.setDisable(true);
            installFeedback.setText("Requesting Administrator permission to install Fish into "+systemInstallDir+"...");
            Ui.role(installFeedback,"muted");
            installFeedback.setVisible(true); installFeedback.setManaged(true);
            boolean makeDesktop=desktopLink.isSelected();
            CompletableFuture.supplyAsync(() -> {
                try { return UserInstaller.installSystemWide(appImage.get(),systemInstallDir,true,makeDesktop); }
                catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
            }).whenComplete((installedExe,err) -> Ui.runLater(() -> {
                installBtn.setDisable(appImage.isEmpty() || alreadyInstalled);
                if (err!=null) {
                    Throwable cause=err.getCause()!=null ? err.getCause() : err;
                    if (cause instanceof java.io.UncheckedIOException uio && uio.getCause()!=null) cause=uio.getCause();
                    Ui.role(installFeedback,"error");
                    installFeedback.setText("System-wide install failed: "+(cause.getMessage()==null ? cause.toString() : cause.getMessage()));
                    systemInstallBtn.setDisable(false);
                } else {
                    Ui.role(installFeedback,"muted");
                    installFeedback.setText("Installed system-wide at "+installedExe+" (All Users Start Menu and Windows Installed Apps updated).");
                }
            }));
        });
        HBox installActions=new HBox(8,installBtn,systemInstallBtn);
        installActions.setAlignment(Pos.CENTER_LEFT);
        body.getChildren().addAll(installStatus,desktopLink,installActions,installFeedback);

        body.getChildren().add(section("Kiosk Mode (Administrator)"));
        Label kioskNote=note("Only desktop shortcuts can open Fish. Panes and settings are locked.");
        Button kioskToggle=Ui.button("",() -> {});
        kioskToggle.setId("prefs-kiosk-toggle");
        Runnable syncKioskUi=() -> {
            boolean active=KioskMode.isEnabled();
            kioskToggle.setText(active ? "Disable Kiosk Mode (Administrator)..." : "Enable Kiosk Mode (Administrator)...");
        };
        syncKioskUi.run();
        kioskToggle.setOnAction(e -> {
            boolean target=!KioskMode.isEnabled();
            if (target) {
                String warning=workspace.savedLayouts().isEmpty()
                        ? "No workspace shortcuts exist yet. Enable kiosk mode anyway? Fish won't open normally."
                        : "Lock Fish to workspace shortcuts on this computer?";
                if (!Ui.confirm(dialog,warning,"Enable Kiosk Mode")) return;
                if (dirty()) persist();
            } else {
                if (!Ui.confirm(dialog,"Disable Kiosk mode on this computer (requires Administrator)?","Disable Kiosk Mode")) return;
            }
            try {
                if (!KioskMode.setEnabledWithAdmin(target)) {
                    Ui.error(dialog,"Kiosk mode was not changed. Administrator permission was cancelled or unavailable.");
                }
            } catch (IOException ex) {
                Ui.error(dialog,"Could not update Kiosk mode: "+ex.getMessage());
            }
            syncKioskUi.run();
        });
        targets.put("kiosk",kioskToggle);
        body.getChildren().addAll(kioskNote,kioskToggle);

        body.getChildren().add(section("Reset"));
        body.getChildren().add(Ui.button("Revert to factory defaults...",workspace::factoryDefaults));
        return body;
    }

    private BorderPane updates() {
        BorderPane body=new BorderPane();
        body.setTop(checkUpdates);
        BorderPane.setMargin(checkUpdates,new Insets(0,0,16,0));
        body.setCenter(workspace.updateController().panel(dialog,() -> dirty() || saving() || profiles.dirty(),() -> workspace.updateSettings().channel()));
        return body;
    }

    private UpdateSettings updateChoices() {
        UpdateSettings policy=workspace.updateSettings();
        return new UpdateSettings(checkUpdates.isSelected(),policy.channel(),policy.ignoredVersion());
    }

    private static HBox row(String title,Control input) {
        return rowNode(title,input,input);
    }

    private static HBox rowNode(String title,Node container,Node target) {
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

    private static Label section(String name) {
        Label label=new Label(name);
        label.getStyleClass().add("fish-section-title");
        return label;
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
        return new Draft(startup.isSelected(),theme.getValue(),fontChoice(),window.isSelected(),pane.isSelected(),tint.isSelected(),reducedMotion.isSelected(),Set.copyOf(flatThemes),
                new Appearance.PaneInfo(info.get(0).isSelected(),info.get(5).isSelected(),info.get(1).isSelected(),info.get(2).isSelected(),
                        info.get(3).isSelected(),info.get(4).isSelected(),info.get(6).isSelected(),info.get(7).isSelected(),info.get(8).isSelected(),info.get(9).isSelected(),info.get(10).isSelected()),
                cursorStyle.getValue(),
                updateChoices(),keyboard.draft(),Map.copyOf(displays));
    }

    private boolean dirty() {
        try { return unsaved || conflict || !draft().equals(saved); }
        catch (IllegalArgumentException e) { return true; }
    }

    private void edited(boolean typing) {
        if (saved==null || ended || loading || syncing || applying) return;
        if (displayProfile!=null) displays.put(displayProfile,new Display(effectivePaletteValue(),effect.getValue()));
        if (typing) { editPending=true; edits.playFromStart(); refresh(); }
        else apply();
    }

    private void refresh() {
        if (saved==null) return;
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
            Draft next=new Draft(startup.isSelected(),theme.getValue(),font,window.isSelected(),pane.isSelected(),tint.isSelected(),reducedMotion.isSelected(),Set.copyOf(flatThemes),
                    new Appearance.PaneInfo(info.get(0).isSelected(),info.get(5).isSelected(),info.get(1).isSelected(),info.get(2).isSelected(),
                            info.get(3).isSelected(),info.get(4).isSelected(),info.get(6).isSelected(),info.get(7).isSelected(),info.get(8).isSelected(),info.get(9).isSelected(),info.get(10).isSelected()),
                    cursorStyle.getValue(),
                    updateChoices(),keys,Map.copyOf(displays));
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
                        next.theme() instanceof ColorTheme custom ? custom.name() : "",previous.themes(),previous.retiredShortcuts(),next.cursorStyle(),next.reducedMotion(),next.flatThemes());
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
            else if (closeRequested) closeSettings();
        },message -> {
            saving=false; closeRequested=false; saveFailure=message; refresh();
            if (written!=generation) writes.playFromStart();
        });
    }

    private boolean requestClose() {
        if (saving || profiles.saving()) { closeRequested=true; return false; }
        if (!saveFailure.isEmpty() || profiles.failedSave()) {
            closeRequested=false;
            return Ui.confirm(dialog,"Settings could not be saved. Close without saving?\n"
                    +"Applied changes remain in memory, but unsaved edits may be lost when Fish exits.\n"
                    +"Cancel to keep this recovery state open and fix the error before Retry save.","Settings not saved");
        }
        apply();
        if (saving || unsaved) { closeRequested=true; persist(); return false; }
        closeRequested=false;
        if ((conflict || !validation.isEmpty() || profiles.dirty()) && !Ui.confirm(dialog,
                "Some edits could not be saved or need an explicit profile/login action.\nClose and discard only those edits? Already saved changes stay applied.","Unsaved edits")) return false;
        return true;
    }

    public void reload() {
        edits.stop(); editPending=false; loading=true;
        try {
            Appearance a=workspace.appearanceSettings();
            cursorStyle.setValue(a.cursorStyle());
            startup.setSelected(workspace.connectOnStartup()); window.setSelected(a.windowOutline()); pane.setSelected(a.outline());
            tint.setSelected(workspace.highlightActive()); reducedMotion.setSelected(a.reducedMotion()); checkUpdates.setSelected(workspace.updateSettings().startup());
            loadThemes(); flatThemes.clear(); flatThemes.addAll(a.flatThemes()); syncGradientCheck();
            family.setValue(a.font().family()); fontPath.setText(a.font().file());
            Ui.markInvalid(fontPath,false);
            Appearance.PaneInfo i=a.info();
            boolean[] flags={i.header(),i.profile(),i.connection(),i.host(),i.device(),i.footer(),i.status(),i.dimensions(),i.cursor(),i.insert(),i.footerDevice()};
            for (int n=0;n<flags.length;n++) info.get(n).setSelected(flags[n]);
            keyboard.reload(workspace.shortcutOverrides());
            displays.clear(); originalDisplays.clear(); customColors.clear();
            for (Profile p:workspace.profileList()) {
                Display display=new Display(p.theme(),p.effect());
                displays.put(p.id(),display); originalDisplays.put(p.id(),display);
                if (Profile.isCustomColor(p.theme())) customColors.put(p.id(),Profile.normalizeTheme(p.theme()));
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
