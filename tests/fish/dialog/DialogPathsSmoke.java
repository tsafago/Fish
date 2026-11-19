package fish.ui;

import fish.model.*;
import fish.update.Updates;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.event.*;
import javafx.geometry.Bounds;
import javafx.geometry.Side;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.HeaderBar;
import javafx.stage.*;

/** Shown production windows, synthetic state, targeted JavaFX events. No global input or external services. */
public final class DialogPathsSmoke {
    private final StateStore store;
    private final Workspace w;
    private final AppTheme theme;
    private final double scale;
    private int checks, failures;
    private final AtomicInteger updateAttempts=new AtomicInteger();
    private final List<String> problems=new ArrayList<>();

    DialogPathsSmoke(Path directory,AppTheme theme,double scale) throws Exception {
        this.theme=theme; this.scale=scale;
        store=new StateStore(directory);
        Profile p=new Profile(UUID.randomUUID(),"Fixture offline","",23,"","",false,false,"37","Phosphor","None",1,Map.of(),"");
        var state0=new StateStore.State(List.of(p),new Layout.Split(true,.5,new Layout.Leaf(UUID.randomUUID(),p.id()),
                new Layout.Leaf(UUID.randomUUID(),p.id())),1030,660);
        var state=new StateStore.State(state0.profiles(),state0.layout(),1030,660,theme,p.id(),Map.of(),false,
                Appearance.defaults(),StateStore.Tutorial.SKIPPED,false,UpdateSettings.defaults());
        var updates=new Updates((uri,out,limit,cancel,progress) -> {
            updateAttempts.incrementAndGet(); throw new IOException("Fixture is offline");
        }, new Updates.Platform() {
            public void inspectAndMark(Path file) { throw new AssertionError("Unexpected install inspection"); }
            public void launch(Path file,String hash,long pid,Path data) { throw new AssertionError("Unexpected install"); }
        },directory.resolve("never-used-staging"));
        store.save(state);
        w=fx(() -> {
            // Demonstration mode suppresses real Startup shortcut synchronization.
            var result=new Workspace(store,state,directory.resolve("absent-credential-helper.ps1"),true,updates);
            result.setVisible(true); return result;
        });
    }

    interface Step { void run() throws Exception; }
    static <T> T fx(Callable<T> work) throws Exception { return FxTestSupport.fx(work); }
    static void run(Runnable r) { FxTestSupport.fxRun(r); }
    void expect(boolean good,String label) {
        checks++;
        if (!good) { failures++; problems.add(label); System.out.println("FAIL "+label); }
    }
    void group(String label,Step work) {
        int before=failures;
        try { work.run(); }
        catch (Exception | AssertionError err) { expect(false,label+": "+err); err.printStackTrace(System.out); }
        finally {
            // Never send input/close to other processes. Unblock nested production showAndWait on a failed case.
            run(() -> {
                var windows=new ArrayList<>(Window.getWindows());
                Collections.reverse(windows);
                for (Window window:windows) if (window!=w.stage()) window.hide();
            });
        }
        System.out.printf("%s group %s%n",failures==before ? "PASS" : "FAIL",label);
    }
    static void waitFor(Callable<Boolean> predicate,String failure) throws Exception {
        FxTestSupport.await(predicate,3,failure);
    }
    static Stage find(String title) {
        return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                .filter(s -> title.equals(s.getTitle())).findFirst().orElse(null);
    }
    static void key(EventTarget target,KeyCode code,boolean ctrl,boolean alt) {
        for (var type:List.of(KeyEvent.KEY_PRESSED,KeyEvent.KEY_RELEASED))
            Event.fireEvent(target,new KeyEvent(type,"","",code,false,ctrl,alt,false));
    }
    static void key(Stage s,KeyCode code) {
        Node focus=s.getScene().getFocusOwner();
        key(focus==null ? s.getScene().getRoot() : focus,code,false,false);
    }
    static ButtonBase button(Stage s,String label) {
        return Objects.requireNonNull(FxTestSupport.button(s,label),label);
    }
    void dismiss(Stage stage,Runnable close,String how) throws Exception {
        run(close);
        waitFor(() -> !stage.isShowing(),stage.getTitle()+" did not close via "+how);
    }
    static Bounds bounds(Node n) { return n.localToScene(n.getLayoutBounds()); }

    record Frame(double sceneW,double sceneH,double x,double y,double headerW,double stageW,double stageH,
                 double outX,double outY,double renderX,double renderY) {}
    void frame(Stage stage,String state) throws Exception {
        waitFor(() -> stage.isShowing() && stage.getScene().getWidth()>0 && stage.getScene().getRoot().getLayoutBounds().getWidth()>0,
                state+" is not laid out");
        // Let normal pulses settle. Do not repair/rescale the root in the test.
        Thread.sleep(100);
        var f=fx(() -> {
            Scene scene=stage.getScene();
            Node header=Objects.requireNonNull(scene.lookup(".fish-header-bar"),"missing Fish header");
            Node close=button(stage,stage==w.stage() ? "Close window" : "Close dialog");
            if (stage!=w.stage()) {
                expect(header instanceof HeaderBar && header.getStyleClass().contains("fish-dialog-caption"),state+": missing Fish caption");
                var heading=scene.getRoot().lookupAll(".fish-page-heading").stream()
                        .filter(n -> n instanceof Label l && stage.getTitle().equals(l.getText())).findFirst().orElse(null);
                expect("Settings".equals(stage.getTitle()) ? heading==null :
                        heading instanceof Label l && stage.getTitle().equals(l.getText())
                        && bounds(heading).getMinY()>=bounds(header).getMaxY()-1,state+": page heading must sit below caption (none for Settings)");
                var buttons=((Parent)header).lookupAll(".fish-chrome-button");
                expect(buttons.equals(Set.of(close)),state+": dialog caption must offer only Close");
            }
            return new Frame(scene.getWidth(),scene.getHeight(),bounds(close).getMaxX(),bounds(close).getMinY(),
                    bounds(header).getWidth(),stage.getWidth(),stage.getHeight(),stage.getOutputScaleX(),stage.getOutputScaleY(),
                    stage.getRenderScaleX(),stage.getRenderScaleY());
        });
        var nativeRect=WindowsDialogProbe.geometry(stage.getTitle());
        System.out.printf(Locale.ROOT,"FRAME %s stage=%.1fx%.1f scene=%.1fx%.1f header=%.1f xRight=%.1f output=%.2fx%.2f render=%.2fx%.2f outer=%dx%d client=%dx%d monitor=%dx%d dpi=%d%n",
                state,f.stageW,f.stageH,f.sceneW,f.sceneH,f.headerW,f.x,f.outX,f.outY,f.renderX,f.renderY,nativeRect.outerW(),
                nativeRect.outerH(),nativeRect.clientW(),nativeRect.clientH(),nativeRect.monitorW(),nativeRect.monitorH(),nativeRect.dpi());
        double requested=scale==0 ? nativeRect.dpi()/96.0 : scale;
        expect(Math.abs(f.outX-requested)<.01 && Math.abs(f.outY-requested)<.01,state+": output scale does not match "+requested);
        expect(Math.abs(f.renderX-f.outX)<.01 && Math.abs(f.renderY-f.outY)<.01,state+": unexpected raster vs output scale");
        expect(Math.abs(f.stageW*f.outX-nativeRect.outerW())<=3 && Math.abs(f.stageH*f.outY-nativeRect.outerH())<=3,
                state+": Stage and HWND outer rectangle disagree");
        expect(Math.abs(f.sceneW*f.outX-nativeRect.clientW())<=4 && Math.abs(f.sceneH*f.outY-nativeRect.clientH())<=4,
                state+": native client and scene disagree");
        expect(Math.abs(f.headerW-f.sceneW)<=4 && Math.abs(f.sceneW-f.x)<=6 && f.y>=-1 && f.y<=5,
                state+": header/X does not reach upper right of scene");
    }
    void minimum(Stage stage,String label) throws Exception {
        run(() -> {
            if (stage.getMinWidth()>0) stage.setWidth(stage.getMinWidth());
            if (stage.getMinHeight()>0) stage.setHeight(stage.getMinHeight());
        });
        frame(stage,label+" minimum");
    }
    void inside(Stage stage,Node node,String label) throws Exception {
        expect(fx(() -> {
            var b=bounds(node); var scene=stage.getScene();
            return node.isVisible() && b.getWidth()>1 && b.getHeight()>1 && b.getMinX()>=-1 && b.getMinY()>=-1
                    && b.getMaxX()<=scene.getWidth()+1 && b.getMaxY()<=scene.getHeight()+1;
        }),label+" outside scene");
    }
    void painted(Stage stage,Runnable open) throws Exception {
        String title=stage.getTitle();
        // Only the two fixture dialogs being sampled. Don't activate a foreground window or send desktop input.
        run(() -> stage.setAlwaysOnTop(true));
        WindowsDialogProbe.Paint paint=new WindowsDialogProbe.Paint(title);
        try { run(open); Thread.sleep(850); } finally { paint.close(); run(() -> stage.setAlwaysOnTop(false)); }
        System.out.printf("DESKTOP %s samples=%d first=%d last=%d nearWhite=%d%n",title,paint.samples,paint.first,paint.last,paint.white);
        expect(paint.samples>=8,title+": insufficient unobscured desktop samples (not verified)");
        if (paint.samples>0) expect(theme==AppTheme.Paper ? paint.last>=185 : paint.white==0 && paint.last<100,
                title+": unexpected desktop caption luminance");
    }
    static PopupWindow showingPopup(Window owner) {
        return Window.getWindows().stream().filter(PopupWindow.class::isInstance).map(PopupWindow.class::cast)
                .filter(p -> p.isShowing() && p.getOwnerWindow()==owner && !(p instanceof Tooltip)).findFirst().orElse(null);
    }
    PopupWindow popup(Window owner,String name) throws Exception {
        waitFor(() -> showingPopup(owner)!=null,name+": popup not shown");
        var p=fx(() -> showingPopup(owner));
        expect(fx(() -> p.getWidth()>8 && p.getHeight()>8 && !p.getScene().getStylesheets().isEmpty()
                && p.getScene().getRoot().getOpacity()>.98),name+": popup bounds, stylesheet or opacity");
        return p;
    }
    void combo(ComboBox<?> c,String label) throws Exception {
        Window owner=fx(() -> c.getScene().getWindow());
        run(c::show); var p=popup(owner,label); run(c::hide);
        expect(fx(() -> !p.isShowing()),label+": popup did not hide");
    }
    void saved() throws Exception {
        var s=fx(() -> w.preferencesDialog("Shortcuts")); painted(s,() -> w.preferences("Shortcuts"));
        var p=fx(w::tutorialPreferences);
        frame(s,"Desktop shortcuts");
        TextField name=fx(() -> (TextField)p.tutorialTarget("shortcut-name"));
        Button create=fx(() -> (Button)p.tutorialTarget("shortcut-create"));
        inside(s,name,"Shortcut name"); inside(s,create,"Export current window");
        run(() -> { name.setText("Fixture two panes"); create.fire(); });
        expect(fx(() -> ((Label)p.tutorialTarget("shortcut-status")).getText().contains("Demo")
                && w.savedLayouts().isEmpty()),"demo export must fail inline without creating a shortcut");
        dismiss(s,() -> button(s,"Close dialog").fire(),"caption X");
    }
    void settings() throws Exception {
        var settings=fx(() -> w.preferencesDialog("Appearance")); painted(settings,() -> w.preferences("Appearance"));
        var p=fx(w::tutorialPreferences); var s=p.dialog(); frame(s,"Settings");
        combo(p.themeCombo(),"Application theme"); combo(p.paletteCombo(),"Workstation palette");
        combo(p.scopeCombo(),"Workstation scope"); combo(p.effectCombo(),"Terminal effect");
        combo(p.cursorStyleCombo(),"Cursor style"); combo(p.familyCombo(),"Font family");
        run(() -> {
            var disclosures=s.getScene().getRoot().lookupAll(".titled-pane").stream().filter(TitledPane.class::isInstance)
                    .map(TitledPane.class::cast).filter(t -> t.getText().equals("Configure fields")).toList();
            expect(!disclosures.isEmpty(),"Appearance Configure fields disclosure missing");
            for (var d:disclosures) { expect(!d.isExpanded(),"Configure fields initially expanded"); d.setExpanded(true); d.setExpanded(false); }
        });
        run(() -> { p.paletteCombo().setValue(Preferences.CUSTOM_COLOR); p.primaryHue().setValue(90);
            p.primaryHex().setText("#12"); });
        expect(fx(() -> p.primaryHex().getPseudoClassStates().stream().anyMatch(c -> c.getPseudoClassName().contains("invalid"))
                || p.primaryHex().getStyleClass().stream().anyMatch(c -> c.contains("invalid"))),"incomplete hex must be marked invalid");
        run(() -> { p.primaryHex().setText("#224466"); p.primaryHex().fireEvent(new ActionEvent()); });
        for (String page:List.of("Profiles","Shortcuts","Keyboard Shortcuts","General","Updates")) {
            run(() -> p.navigationList().getSelectionModel().select(page));
            expect(fx(() -> page.equals(p.category())),page+" rail navigation");
            if (page.equals("Keyboard Shortcuts")) {
                run(() -> { p.keyboard.searchField().setText("Add pane"); p.keyboard.selectAction("Add pane"); });
                expect(fx(() -> !p.keyboard.actionTable().getItems().isEmpty()),"Keyboard action search lost match");
                modal("Keyboard migration",() -> button(s,"Migrated definitions...").fire(),KeyCode.ESCAPE,0);
            } else if (page.equals("General")) {
                modal("Enable Kiosk Mode",() -> ((Button)s.getScene().lookup("#prefs-kiosk-toggle")).fire(),KeyCode.ESCAPE,0);
                modal("Factory defaults",() -> button(s,"Revert to factory defaults...").fire(),KeyCode.ESCAPE,0);
            } else if (page.equals("Updates")) {
                expect(fx(() -> s.getScene().getRoot().lookupAll(".check-box").stream().anyMatch(n -> n instanceof CheckBox c
                        && c.getText().equals("Check for updates at startup"))),"missing update discovery control");
            }
        }
        waitFor(() -> updateAttempts.get()>0 && FxTestSupport.children(s).stream().anyMatch(n -> n instanceof Label l
                && l.getText()!=null && l.getText().startsWith("Update check failed")),"synthetic update failure not surfaced");
        expect(fx(() -> button(s,"Update...").isDisabled()),"Update available after refused check");
        run(() -> p.select("Appearance"));
        minimum(s,"Settings"); inside(s,p.navigationList(),"Settings rail");
        waitFor(() -> !p.saving(),"synthetic preference writes pending");
        dismiss(s,() -> key(s,KeyCode.ESCAPE),"Escape");
        expect(fx(() -> w.tutorialPreferences()==null),"Settings did not clear closed instance");
    }

    <T> T modal(String title,Callable<T> open,KeyCode close,int expectedCombos) throws Exception {
        return modal(title,open,close,expectedCombos,s -> {});
    }
    <T> T modal(String title,Callable<T> open,KeyCode close,int expectedCombos,Consumer<Stage> beforeKey) throws Exception {
        var task=new FutureTask<>(open);
        Platform.runLater(task);
        waitFor(() -> find(title)!=null || task.isDone(),"dialog did not open: "+title);
        Stage s=fx(() -> find(title));
        if (s==null) throw new AssertionError("No visible "+title+"; route returned "+task.get(1,TimeUnit.SECONDS));
        try {
            frame(s,title);
            var boxes=fx(() -> FxTestSupport.children(s).stream().filter(ComboBox.class::isInstance)
                    .map(n -> (ComboBox<?>)n).toList());
            expect(boxes.size()==expectedCombos,title+" expected "+expectedCombos+" selectors, got "+boxes.size());
            for (var c:boxes) combo(c,title+" selector");
            dismiss(s,() -> {
                beforeKey.accept(s);
                var focus=s.getScene().getFocusOwner();
                System.out.printf("KEY %s %s focus=%s%n",title,close,focus instanceof Labeled l ?
                        Objects.toString(l.getAccessibleText(),l.getText()) : focus==null ? "none" : focus.getClass().getSimpleName());
                key(s,close);
            },close.name());
            return task.get(2,TimeUnit.SECONDS);
        } finally { run(s::hide); }
    }
    void modal(String title,Runnable open,KeyCode close,int expectedCombos) throws Exception {
        modal(title,() -> { open.run(); return null; },close,expectedCombos);
    }
    void prompts() throws Exception {
        // Focused Cancel on a warning intentionally wins over the default button. Exercise both choices.
        for (String action:List.of("Cancel","OK")) {
            String title="Confirm fixture / "+action;
            boolean accepted=modal(title,() -> Ui.confirm(w.stage(),"Continue the synthetic test?",title),
                    KeyCode.ENTER,0,s -> button(s,action).requestFocus());
            expect(accepted==action.equals("OK"),"Return did not activate focused "+action);
        }
        expect(!modal("Cancel fixture",() -> Ui.confirm(w.stage(),"Cancel the synthetic test?","Cancel fixture"),
                KeyCode.ESCAPE,0),"Escape accepted confirmation");
        expect(modal("Text fixture",() -> Ui.input(w.stage(),"Text fixture","Synthetic text"),KeyCode.ESCAPE,0)==null,
                "Escape accepted input");
        modal("Fish",() -> Ui.error(w.stage(),"Synthetic error; nothing was changed."),KeyCode.ENTER,0);
        run(() -> w.executeCommand("Keyboard reference"));
        waitFor(() -> w.tutorialPreferences()!=null && w.tutorialPreferences().category().equals("Keyboard Shortcuts"),
                "Keyboard reference did not show the current shortcut assignments");
        var editor=fx(() -> w.tutorialPreferences().dialog());
        dismiss(editor,() -> key(editor,KeyCode.ESCAPE),"Escape");
        run(() -> w.active().problem("Synthetic pane warning"));
        modal("Last pane notice",() -> w.active().showLastNotice(),KeyCode.ESCAPE,0);
    }
    void forms() throws Exception {
        Layout layout=fx(w::currentLayout);
        modal("Swap panes",() -> w.executeCommand("Swap panes..."),KeyCode.ESCAPE,1);
        modal("Arrange pane",() -> w.executeCommand("Arrange pane..."),KeyCode.ESCAPE,3);
        expect(layout.equals(fx(w::currentLayout)),"Cancel rearranged offline panes");
        modal("Switch workstation",() -> w.executeCommand("Switch profile..."),KeyCode.ESCAPE,1);
        // The menu command is disabled offline; inspect the real form, never send a host key.
        modal("Send host key",() -> FxTestSupport.invoke(w,"hostKeys"),KeyCode.ESCAPE,1);
    }
    void profiles() throws Exception {
        var d=fx(() -> new ProfilesDialog(w,w.lastProfile())); var s=d.stage(); run(s::show);
        frame(s,"Profiles password"); combo(d.editor.authenticationCombo(),"Profile authentication");
        inside(s,d.editor.deviceField(),"Optional device in main form");
        run(() -> { d.editor.authenticationCombo().setValue(Profile.Authentication.KERBEROS); });
        expect(fx(d.editor::kerberos),"Kerberos form not selected");
        minimum(s,"Profiles"); inside(s,d.editor.doneButton(),"Profile actions at minimum");
        dismiss(s,() -> { d.editor.nameField().setText("Renamed fixture profile"); key(s,KeyCode.ESCAPE); },"immediate Escape after rename");
        expect(store.load().profiles().getFirst().name().equals("Renamed fixture profile"),"Escape lost profile rename");
    }
    void docs(String resource,String title) throws Exception {
        var d=fx(() -> new ManualDialog(w,resource,title)); var s=d.stage(); run(() -> d.setVisible(true));
        frame(s,title); minimum(s,title);
        int all=fx(() -> d.topicList().getItems().size());
        run(() -> { key(s.getScene().getRoot(),KeyCode.F,true,false); d.searchField().setText("not-a-fixture-topic-9187"); });
        expect(fx(() -> s.getScene().getFocusOwner()==d.searchField() && all>0 && d.topicList().getItems().isEmpty()),title+" search");
        run(() -> d.searchField().clear()); expect(fx(() -> d.topicList().getItems().size()==all),title+" clear search");
        dismiss(s,() -> key(s,KeyCode.ESCAPE),"Escape");
        expect(fx(() -> w.scene().getFocusOwner()==w.active().presetsCombo()),title+" did not return focus to disconnected pane selector");
    }
    void guide(TutorialDialog.Guide g) throws Exception {
        run(() -> { w.stage().setWidth(w.stage().getMinWidth()); w.stage().setHeight(w.stage().getMinHeight()); });
        waitFor(() -> w.scene().getWidth()<=w.stage().getMinWidth() && w.scene().getHeight()<=w.stage().getMinHeight(),
                g.title+": workspace did not reach minimum");
        run(() -> w.openTutorial(g,g==TutorialDialog.Guide.GETTING_STARTED));
        var d=fx(w::activeTutorial); var s=d.hostStage();
        int pos=fx(d::currentStep);
        run(d.skipStepButton()::fire); expect(fx(() -> d.currentStep()>pos),g.title+": skip step");
        run(d.backButton()::fire); expect(fx(() -> d.currentStep()==pos),g.title+": back");
        inside(s,d.skipTutorialButton(),g.title+": Skip tutorial at minimum");
        expect(fx(() -> d.card().isVisible() && d.card().getScene()==s.getScene()
                && d.card().localToScene(d.card().getLayoutBounds()).contains(bounds(d.skipTutorialButton()))),
                g.title+": Skip must be inside the visible card at minimum");
        run(() -> key(d.skipTutorialButton(),KeyCode.ESCAPE,false,false));
        expect(fx(() -> d.ended() && d.card().getScene()==null),g.title+": Escape did not detach guide");
    }

    void tutorialFollowing() throws Exception {
        run(() -> {
            w.stage().setWidth(1080);
            w.addProfileCombo().requestFocus();
            w.openTutorial(TutorialDialog.Guide.KEYBOARD,false);
        });
        var d=fx(w::activeTutorial);
        expect(fx(() -> w.scene().getFocusOwner()==w.addProfileCombo()),"opening guide stole the existing focus");
        Menu edit=fx(() -> w.menuBar().getMenus().stream().filter(m -> "Edit".equals(m.getText())).findFirst().orElseThrow());
        MenuItem settings=fx(() -> edit.getItems().stream().filter(m -> "Settings...".equals(m.getText())).findFirst().orElseThrow());
        run(edit::show);
        waitFor(() -> settings.getStyleableNode()!=null && d.currentAnchor()==settings.getStyleableNode(),
                "expected the visible Edit > Settings row, not the top-bar button");
        expect(fx(() -> {
            var popup=d.targetWindow();
            var card=d.card().localToScreen(d.card().getLayoutBounds());
            return popup instanceof PopupWindow && !card.intersects(popup.getX(),popup.getY(),popup.getWidth(),popup.getHeight())
                    && d.highlightLayer().isMouseTransparent() && edit.isShowing() && d.currentStep()==0;
        }),"guide obscures the open menu or advances on opening rather than selection");
        run(() -> d.currentAnchor().executeAccessibleAction(AccessibleAction.FIRE));
        waitFor(() -> d.currentAnchor() instanceof ListCell<?> c && "Keyboard Shortcuts".equals(c.getItem()),
                "Settings should guide the user to the required category");
        var p=fx(w::tutorialPreferences);
        expect(fx(() -> d.card().getScene()==p.dialog().getScene() && !edit.isShowing()),"guide didn't follow the open Settings window");
        run(() -> p.navigationList().getSelectionModel().select("Keyboard Shortcuts"));
        waitFor(() -> d.currentStep()==1 && d.currentAnchor()==p.tutorialTarget("keyboard-search"),
                "expected real search input after category selection");
        run(() -> { ((TextField)d.currentAnchor()).requestFocus(); ((TextField)d.currentAnchor()).setText("Zoom"); });
        expect(fx(() -> d.currentStep()==2 && p.dialog().getScene().getFocusOwner()==p.tutorialTarget("keyboard-search")),
                "step advancement stole focus from the live input");
        run(p::closeSettings);
        waitFor(() -> d.targetWindow()==w.stage(),"closed Settings left stale guide ownership");
        run(() -> { w.stage().setWidth(400); });
        waitFor(() -> !w.chrome().menuBar().isVisible(),"compact fixture did not collapse menu bar");
        var compact=fx(w.chrome()::openCompactMenu);
        Menu group=fx(() -> (Menu)compact.getItems().stream().filter(i -> "Edit".equals(i.getText())).findFirst().orElseThrow());
        waitFor(() -> d.currentAnchor()==group.getStyleableNode(),"compact root should point at Edit");
        run(() -> {
            var row=group.getStyleableNode();
            // Same local hover the submenu skin uses; never move the desktop pointer.
            Event.fireEvent(row,new MouseEvent(MouseEvent.MOUSE_ENTERED,1,1,1,1,MouseButton.NONE,0,
                    false,false,false,false,false,false,false,false,false,false,null));
            group.show();
        });
        var item=fx(() -> group.getItems().stream().filter(i -> "Settings...".equals(i.getText())).findFirst().orElseThrow());
        waitFor(() -> d.currentAnchor()==item.getStyleableNode() && d.targetWindow()!=compact,"compact submenu row did not become the target");
        run(() -> d.currentAnchor().executeAccessibleAction(AccessibleAction.FIRE));
        waitFor(() -> w.tutorialPreferences()!=null && d.hostStage()==w.tutorialPreferences().dialog(),
                "selecting compact Settings did not transfer guide");
        var other=fx(() -> { Stage stage=new Stage(); stage.setScene(new Scene(new javafx.scene.control.TextField(),220,50)); stage.show(); return stage; });
        try {
            expect(fx(() -> !d.related(other) && d.card().getScene()!=other.getScene()
                    && d.targetWindow()!=other),"unrelated fixture intercepted by guide");
            run(() -> {
                d.backButton().fire();
                d.skipStepButton().fire();
                key(d.skipTutorialButton(),KeyCode.ESCAPE,false,false);
            });
            expect(fx(() -> d.ended() && d.highlightLayer().getParent()==null && d.card().getParent()==null
                    && w.tutorialPreferences().dialog().isShowing()),"Escape on guide closed Settings or left a stale overlay");
        } finally { run(other::close); }
        run(() -> { w.tutorialPreferences().forceDispose(); w.openTutorial(TutorialDialog.Guide.GETTING_STARTED,true);
                    w.addProfileCombo().show(); });
        var intro=fx(w::activeTutorial);
        waitFor(() -> intro.currentAnchor() instanceof ListView<?> && intro.targetWindow() instanceof PopupWindow,
                "guide didn't follow the profile chooser list");
        run(() -> w.addProfileCombo().hide());
        waitFor(() -> intro.currentAnchor()==w.addProfileCombo(),"profile list dismissal left a stale target");
        run(intro.skipTutorialButton()::fire);
        expect(fx(() -> intro.ended() && w.tutorialStatus()==StateStore.Tutorial.SKIPPED),"first-run skip was not persisted");
        run(() -> {
            w.openTutorial(TutorialDialog.Guide.PANES,false);
            w.activeTutorial().go(4);
            Platform.runLater(() -> w.executeCommand("Swap panes..."));
        });
        var panes=fx(w::activeTutorial);
        waitFor(() -> panes.currentAnchor() instanceof ComboBox<?> && "Swap panes".equals(panes.hostStage().getTitle()),
                "pane guide did not follow its owned swap form");
        expect(fx(() -> {
            var n=panes.currentAnchor();
            return panes.card().isVisible() && !panes.card().localToScreen(panes.card().getLayoutBounds())
                    .intersects(n.localToScreen(n.getLayoutBounds()));
        }),"guide obscures the target pane selector on a small modal form");
        run(() -> key(panes.currentAnchor(),KeyCode.ESCAPE,false,false));
        waitFor(() -> panes.targetWindow()==w.stage(),"Escape in the form didn't dismiss the form and return the guide");
        run(() -> FxTestSupport.button(panes.card(),"Close tutorial").fire());
        expect(fx(() -> panes.ended() && panes.card().getScene()==null),"close did not remove the guide");
    }
    void keys() throws Exception {
        AtomicReference<String> action=new AtomicReference<>();
        var d=fx(() -> new KeyboardReference(w.stage(),action::set)); run(d::show);
        frame(d.stage(),"Key reference"); minimum(d.stage(),"Key reference");
        run(() -> { d.tabPane().getSelectionModel().select(1); d.searchField().setText("Add pane");
            d.actionsTable().getSelectionModel().selectFirst(); d.editActionButton().fire(); });
        expect("Add pane".equals(action.get()) && !fx(d::isShowing),"key reference select action");
        var chord=new AtomicReference<KeyChord>();
        var s=fx(() -> KeyRecorder.dialog(w.stage(),chord::set)); run(s::show); frame(s,"Recorder");
        run(() -> {
            var rec=(KeyRecorder)s.getProperties().get("fish.recorder");
            key(rec.capture,KeyCode.F9,true,false); key(s,KeyCode.ENTER);
        });
        expect(chord.get()!=null && !fx(s::isShowing),"recorded Ctrl+F9 not returned by Enter");
        var special=fx(() -> KeyRecorder.dialog(w.stage(),chord::set)); run(special::show);
        var recorder=fx(() -> (KeyRecorder)special.getProperties().get("fish.recorder"));
        run(() -> key(recorder.capture,KeyCode.ESCAPE,false,false));
        expect(fx(() -> special.isShowing() && recorder.chosen()!=null),"recorder promises Escape as a recordable key; scene consumed it");
        if (fx(special::isShowing)) dismiss(special,() -> button(special,"Cancel").fire(),"Cancel");
    }
    void warnings() throws Exception {
        AtomicReference<WorkspaceRecovery.Choice> choice=new AtomicReference<>();
        Stage r=fx(() -> WorkspaceRecovery.dialog("Synthetic workspace read failure",false,choice::set)); run(r::show);
        frame(r,"Unreadable workspace");
        expect(fx(() -> button(r,"Retry load").isDisabled()),"retry enabled without readable backup");
        dismiss(r,() -> key(r,KeyCode.ENTER),"default Exit");
        expect(choice.get()==WorkspaceRecovery.Choice.EXIT,"recovery chose destructive default");
        AtomicReference<Boolean> reset=new AtomicReference<>();
        Stage e=fx(() -> WorkspaceRecovery.startupErrorDialog("Synthetic startup failure",reset::set)); run(e::show);
        frame(e,"Startup error"); dismiss(e,() -> Event.fireEvent(e,new WindowEvent(e,WindowEvent.WINDOW_CLOSE_REQUEST)),"close request");
        expect(Boolean.FALSE.equals(reset.get()),"close request opted into factory reset");
        AtomicReference<Boolean> unlock=new AtomicReference<>();
        Stage k=fx(() -> WorkspaceRecovery.kioskBlockedDialog(w.stage(),unlock::set)); run(k::show);
        frame(k,"Kiosk blocked"); dismiss(k,() -> key(k,KeyCode.ESCAPE),"Escape");
        expect(Boolean.FALSE.equals(unlock.get()),"Escape opted into unlocking");
    }
    void menus() throws Exception {
        run(() -> w.stage().setWidth(1080)); frame(w.stage(),"Workspace full");
        for (Menu m:fx(() -> List.copyOf(w.menuBar().getMenus()))) {
            run(m::show); popup(w.stage(),"Menu "+m.getText()); run(m::hide);
        }
        var selector=w.addProfileCombo();
        var join=fx(() -> w.scene().lookup(".fish-chrome-pill-join"));
        var outline=javafx.css.PseudoClass.getPseudoClass("outlined");
        run(() -> { selector.requestFocus(); selector.show(); });
        popup(w.stage(),"Profile pill");
        expect(fx(() -> join.getPseudoClassStates().contains(outline)),"open selector lost its right focus edge");
        run(() -> { selector.hide(); w.active().presetsCombo().requestFocus(); });
        expect(fx(() -> !join.getPseudoClassStates().contains(outline)),"resting selector retained a focus edge");
        combo(fx(() -> w.active().presetsCombo()),"Disconnected pane selector");
        run(() -> w.zoom(w.active(),.1));
        expect(fx(() -> w.active().zoomHudLabel().isVisible() && !w.active().zoomHudLabel().getText().isBlank()),
                "pane-relative zoom HUD missing");
        run(() -> {
            var a=w.appearanceSettings();
            w.appearanceSettings(a.withLayout(a.outline(),new Appearance.PaneInfo(true,false,true,true,true,false,true,true,true,true)));
            w.active().problem("Synthetic pane error, hidden footer");
        });
        var notice=fx(() -> w.active().noticeOverlay());
        expect(fx(() -> notice.isVisible() && notice.messageText().contains("Synthetic pane error")),"hidden-footer notice missing");
        run(notice.closeButton()::fire); expect(!fx(notice::isVisible),"dismissed notice still visible");
        for (boolean pane:new boolean[]{true,false}) {
            var c=fx(() -> pane ? w.paneMenu(w.active()) : w.clipboardMenu(w.active()));
            run(() -> c.show(w.active(),Side.TOP,0,0)); popup(w.stage(),"pane context");
            expect(fx(() -> !c.getItems().isEmpty()),"context actions missing"); run(c::hide);
        }
        run(w.chrome()::showSystemMenu); var sys=(ContextMenu)popup(w.stage(),"window menu");
        double x=fx(() -> w.stage().getX());
        run(() -> { sys.hide(); sys.getItems().stream().filter(i -> "Move".equals(i.getText())).findFirst().orElseThrow().fire();
            key(w.stage(),KeyCode.RIGHT); key(w.stage(),KeyCode.ESCAPE); });
        expect(fx(() -> Math.abs(w.stage().getX()-x)<=1),"Escape did not undo keyboard Move");
        minimum(w.stage(),"Workspace");
        var compact=fx(w.chrome()::openCompactMenu); popup(w.stage(),"compact menu");
        expect(fx(() -> compact.getItems().size()==w.menuBar().getMenus().size()),"compact menu lost top-level group");
        run(compact::hide);
        // Real tooltip installed by production, not a substitute PopupControl.
        var tip=fx(() -> w.addProfileCombo().getTooltip()); run(() -> tip.show(w.addProfileCombo(),w.stage().getX()+100,w.stage().getY()+80));
        expect(fx(() -> tip.isShowing() && !tip.getScene().getStylesheets().isEmpty()),"profile tooltip not styled"); run(tip::hide);
    }
    void suite(boolean full) {
        group("Desktop shortcuts",this::saved); group("Settings",this::settings);
        if (full) {
            group("Workspace forms",this::forms); group("Prompts and notices",this::prompts); group("Profile editor",this::profiles);
            group("Manual",() -> docs("/MANUAL.md","Fish manual"));
            for (var g:TutorialDialog.Guide.values()) group("Tutorial "+g.title,() -> guide(g));
            group("Live tutorial targets",this::tutorialFollowing);
            group("Key reference and recorder",this::keys); group("Recovery warnings",this::warnings);
        }
        group("Workspace menus and popups",this::menus);
    }
    public static void main(String[] args) {
        Thread.ofPlatform().daemon().start(() -> {
            try { Thread.sleep(100_000); } catch (InterruptedException ignored) { return; }
            System.err.println("FAIL fixture process watchdog"); Runtime.getRuntime().halt(124);
        });
        int exit=1;
        try {
            Ui.startToolkitAsync().get(10,TimeUnit.SECONDS);
            var t=new DialogPathsSmoke(Files.createDirectory(Path.of("workspace")),AppTheme.valueOf(args[0]),
                    args[1].equals("native") ? 0 : Double.parseDouble(args[1]));
            if ("tutorials".equals(args[2])) {
                for (var g:TutorialDialog.Guide.values()) t.group("Tutorial "+g.title,() -> t.guide(g));
                t.group("Live tutorial targets",t::tutorialFollowing);
            } else t.suite(Boolean.parseBoolean(args[2]));
            run(t.w::finishWindow);
            System.out.printf("RESULT theme=%s scale=%s checks=%d failures=%d%n",t.theme,args[1],t.checks,t.failures);
            t.problems.forEach(p -> System.out.println(" - "+p));
            exit=t.failures==0 ? 0 : 1;
        } catch (Throwable problem) { problem.printStackTrace(); }
        finally { Platform.exit(); System.exit(exit); }
    }
}
