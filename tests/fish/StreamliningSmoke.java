package fish;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderButtonType;
import javafx.scene.layout.HeaderDragType;
import javafx.scene.layout.Region;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import fish.model.*;
import fish.ui.*;

/** Source/packaged JavaFX checks without desktop input, shared clipboard or credentials. */
@SuppressWarnings({"deprecation", "removal"})
public final class StreamliningSmoke {
    private static int checks;
    private static void check(boolean value,String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static <T> T edt(Callable<T> work) throws Exception { return FxTestSupport.fx(work); }
    private static Object field(Object owner,String name) throws Exception { return FxTestSupport.field(owner,name); }
    private static void capture(Node node,String file,double scale) throws Exception {
        FxTestSupport.writeSnapshot(node,scale,Path.of("build",file));
    }
    private static boolean dispatch(Workspace workspace,Node source,javafx.event.EventType<javafx.scene.input.KeyEvent> type,boolean shift,boolean ctrl,KeyCode code) throws Exception {
        AppShortcuts shortcuts=(AppShortcuts)field(workspace,"shortcuts");
        return shortcuts.dispatchKey(source,new javafx.scene.input.KeyEvent(type,"","",code,shift,ctrl,false,false),KeyEvent.KEY_LOCATION_STANDARD);
    }
    private static void sized(Workspace workspace,int width,int height) {
        workspace.setSize(width,height);
        FxTestSupport.layout(workspace);
        for (Node child:FxTestSupport.children(workspace))
            if (child instanceof Workspace.WorkspaceSplit split) split.setUserRatio(0.5);
        FxTestSupport.layout(workspace);
    }
    private static void allocationChecks(Workspace workspace,SessionPane pane,ComboBox<?> preset) throws Exception {
        Profile original=pane.profile();
        try {
            sized(workspace,480,600);
            check(pane.getWidth()<265,"Narrow-preset regression uses a pane smaller than the original row's preferred width.");
            capture(workspace.scene().getRoot(),"fish-sizing-480.png",1.5);
            Profile longName=Profile.create("Warehouse - shipping and receiving workstation");
            workspace.putProfile(longName); workspace.selectProfile(pane,longName);
            sized(workspace,1280,700);
            check(preset.getWidth()>140,"Wide pane grows the selector to fit a preset name.");
            capture(workspace.scene().getRoot(),"fish-sizing-1280.png",1);
            sized(workspace,330,370);
            Rectangle combo=FxTestSupport.boundsIn(preset,pane);
            Button connect=(Button)field(pane,"connectButton");
            Rectangle button=FxTestSupport.boundsIn(connect,pane);
            check(combo.width>=0 && combo.getMaxX()<=button.x+2 && button.getMaxX()<=pane.getWidth()+2,
                    "Genuinely narrow panes preserve the Connect target without overlapping the preset or pane edge.");
        } finally { workspace.selectProfile(pane,original); }

        WindowChrome chrome=(WindowChrome)field(workspace,"chrome");
        MenuBar menus=(MenuBar)field(chrome,"menus");
        for (int width:new int[]{330,620,740,1280}) {
            sized(workspace,width,700);
            Region left=(Region)field(chrome,"left"),controls=(Region)field(chrome,"controls");
            Region drag=(Region)field(chrome,"dragArea");
            check(left.getLayoutX()+left.getWidth()<=controls.getLayoutX()+1 && drag.getWidth()>=16,
                    "Measured titlebar controls retain a drag region without overlap at width "+width);
            check(width!=1280 || menus.isVisible(),"Wide titlebar does not enter compact mode unnecessarily.");
        }
        sized(workspace,1280,700);
    }

    public static void main(String[] args) throws Exception {
        Ui.install();
        Path directory=Files.createTempDirectory(Path.of("build"),"streamlining-smoke-");
        CountDownLatch closed=new CountDownLatch(1);
        StateStore store=new StateStore(directory);
        Workspace workspace=edt(() -> {
            Workspace w=new Workspace(store,Main.demoState(23456),Path.of("scripts","credential-store.ps1"),false);
            w.onClosed(closed::countDown); FxTestSupport.layout(w);
            return w;
        });
        try {
            edt(() -> {
                List<SessionPane> panes=workspace.sessionPanes();
                workspace.applyShortcuts(Map.of("Next pane","ctrl TAB","Previous pane","ctrl shift TAB"));
                ComboBox<?> preset=FxTestSupport.children(panes.getFirst()).stream().filter(c -> c instanceof ComboBox<?>)
                        .map(c -> (ComboBox<?>)c).findFirst().orElseThrow();
                check(dispatch(workspace,preset,javafx.scene.input.KeyEvent.KEY_PRESSED,false,true,KeyCode.TAB)
                        && workspace.active()==panes.get(1),"Ctrl+Tab cycles even from an offline combo before focus traversal.");
                check(dispatch(workspace,preset,javafx.scene.input.KeyEvent.KEY_RELEASED,false,true,KeyCode.TAB),"Claimed shortcut release is consumed.");
                check(dispatch(workspace,panes.get(1).view(),javafx.scene.input.KeyEvent.KEY_PRESSED,true,true,KeyCode.TAB)
                        && workspace.active()==panes.getFirst(),"Ctrl+Shift+Tab cycles the terminal backward.");
                dispatch(workspace,panes.getFirst().view(),javafx.scene.input.KeyEvent.KEY_RELEASED,true,true,KeyCode.TAB);
                boolean rejected=false;
                try { workspace.applyShortcuts(Map.of("Next pane","ENTER","ENTER","ENTER")); }
                catch (IllegalArgumentException e) { rejected=e.getMessage().toLowerCase(Locale.ROOT).contains("enter") || e.getMessage().contains("conflict") || e.getMessage().contains("already"); }
                check(rejected && workspace.shortcutOverrides().containsKey("Previous pane"),"Host-key collisions reject the entire edit, preserving previous shortcuts.");
                rejected=false;
                try { workspace.applyShortcuts(Map.of("Next pane","alt F4")); }
                catch (IllegalArgumentException e) { rejected=true; }
                check(rejected,"Native Windows shortcut namespace cannot be stolen.");
                workspace.applyShortcuts(Map.of("Host F1","ctrl alt F1"));
                check(panes.getFirst().keys().lookup(new KeyEvent(new javax.swing.JPanel(),KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(),0,KeyEvent.VK_F1,KeyEvent.CHAR_UNDEFINED))==null,
                        "A global host-action override removes its original profile/default host binding.");
                workspace.applyShortcuts(Map.of("Next pane","ctrl TAB","Previous pane","ctrl shift TAB"));
                check(workspace.commands().stream().map(c -> c.title).distinct().count()==workspace.commands().size(),
                        "All shortcut actions have unique persistent identities.");
                for (var key:Keymap.hostActions()) check(workspace.commands().stream().anyMatch(c -> c.hostKey==key),
                        "Host action "+key+" is present in the global shortcut list.");

                SessionPane first=panes.getFirst();
                javafx.geometry.Bounds bounds=first.getBoundsInParent();
                for (int i=0;i<30;i++) workspace.activate(panes.get(i%panes.size()));
                FxTestSupport.layout(workspace);
                check(first.getBoundsInParent().equals(bounds),"Focus never shifts pane geometry.");
                workspace.highlightActive(false);
                check(panes.stream().allMatch(p -> p.view().getTerminalBackground().equals(Ui.BACKGROUND)),
                        "Outline-only preference removes selected-pane background tint.");

                Button connect=(Button)field(first,"connectButton");
                Rectangle comboBounds=FxTestSupport.boundsIn(preset,first);
                Rectangle buttonBounds=FxTestSupport.boundsIn(connect,first);
                check(buttonBounds.x>=comboBounds.x+comboBounds.width && Math.abs(buttonBounds.getCenterY()-comboBounds.getCenterY())<=3,
                        "Offline Connect is a labeled-accessible icon directly right of the preset combo.");

                WindowChrome chrome=(WindowChrome)field(workspace,"chrome");
                HBox controls=(HBox)field(chrome,"controls");
                Region drag=(Region)field(chrome,"dragArea");
                check(workspace.stage().getStyle()==StageStyle.EXTENDED,"JavaFX StageStyle.EXTENDED provides native Windows caption/resize/snap support.");
                check(HeaderBar.getButtonType(controls.getChildren().get(1))==HeaderButtonType.MAXIMIZE,
                        "Maximize button declares HeaderButtonType.MAXIMIZE for Windows 11 Snap Layouts.");
                check(HeaderBar.getDragType(drag)==HeaderDragType.DRAGGABLE,
                        "Blank title area declares HeaderDragType.DRAGGABLE for native drag/snap handling.");

                Button logo=(Button)field(chrome,"caption");
                check(logo.isFocusTraversable() && logo.getAccessibleText().contains("animated Flux"),
                        "Logo button is keyboard-accessible and names its effect action.");
                check(Math.round(logo.getWidth())==48 && Math.round(logo.getHeight())==WindowChrome.HEIGHT-1,
                        "Titlebar logo retains 48x"+(WindowChrome.HEIGHT-1)+" keyboard/click target.");
                check(Math.round(chrome.getHeight())==WindowChrome.HEIGHT,"Chrome preserves exact "+WindowChrome.HEIGHT+"px height.");

                workspace.activate(first);
                workspace.putProfile(first.profile().withDisplay(first.profile().theme(),"None"));
                logo.fire();
                check(first.profile().effect().equals(Profile.FLUX),"Logo activation sets actual animated Flux.");
                logo.fire();
                check(first.profile().effect().equals("None"),"Second logo activation returns None.");
                workspace.putProfile(first.profile().withDisplay(first.profile().theme(),"Prism"));
                logo.fire();
                check(first.profile().effect().equals(Profile.FLUX),"Any non-Flux effect, including Prism, toggles to animated Flux.");
                workspace.activate(panes.get(1));
                workspace.putProfile(panes.get(1).profile().withDisplay(panes.get(1).profile().theme(),"None"));
                logo.fire(); logo.fire();
                check(panes.get(1).profile().effect().equals("None") && first.profile().effect().equals(Profile.FLUX),
                        "Toggle targets the active preset without changing another pane's preset.");

                MenuBar menus=(MenuBar)field(chrome,"menus");
                Menu view=menus.getMenus().stream().filter(m -> m.getText().equals("View")).findFirst().orElseThrow();
                check(!(view.getItems().getLast() instanceof SeparatorMenuItem),"View menu has no trailing separator.");

                List<ManualDialog.Topic> topics=ManualDialog.topics();
                check(topics.size()<=6 && topics.getFirst().title().equals("Getting Started"),"Quick guide starts with sign-on basics.");
                check(topics.stream().anyMatch(t -> t.matches("Ctrl Tab")) && topics.stream().anyMatch(t -> t.matches("desktop shortcut")),
                        "Full-text topic index finds keyboard and desktop shortcut instructions.");
                allocationChecks(workspace,first,preset);

                ManualDialog manual=new ManualDialog(workspace); FxTestSupport.layout(manual.stage());
                capture(manual.stage().getScene().getRoot(),"fish-manual.png",1);
                manual.dispose();

                check(AppTheme.values().length>=8 && Profile.THEMES.size()>=12,"Application/host palettes include expanded OLED and high-contrast choices.");
                for (AppTheme theme:List.of(AppTheme.Charcoal,AppTheme.OLED,AppTheme.High_Contrast_Light)) {
                    workspace.applyTheme(theme); FxTestSupport.layout(workspace);
                    capture(workspace.scene().getRoot(),"fish-streamlining-"+theme.name()+".png",1.5);
                    if (theme==AppTheme.OLED) check(workspace.active().view().getTerminalBackground().getRGB()==Color.BLACK.getRGB(),"OLED pane is truly black.");
                }
                workspace.applyTheme(AppTheme.Charcoal);
                workspace.setSize(400,370); FxTestSupport.layout(workspace);
                check(!menus.isVisible(),"Narrow snapped windows expose the compact menu instead of overflowing chrome.");
                capture(workspace.scene().getRoot(),"fish-streamlining-narrow.png",1.5);
                check(!workspace.isVisible(),"All streamlining checks remain off the shared desktop.");
                return null;
            });
        } finally {
            edt(() -> { workspace.close(false); return null; });
            check(closed.await(5,TimeUnit.SECONDS),"Final state persists and releases the workspace lock.");
        }
        try (StateStore reopened=new StateStore(directory)) {
            StateStore.State saved=reopened.load();
            check(!saved.highlightActive() && saved.shortcuts().get("Next pane").contains("TAB"),
                    "Global shortcuts and outline-only preference survive restart.");
            check(saved.profiles().getFirst().effect().equals(Profile.FLUX),"Logo changes persist through the existing profile/state save path.");
        }
        FxTestSupport.closeAllWindows();
        FxTestSupport.deleteTree(directory);
        System.out.println("PASS Streamlining: "+checks+" focused checks; JavaFX HeaderBar chrome, no foreground input.");
        Ui.finishTestRun();
    }
}
