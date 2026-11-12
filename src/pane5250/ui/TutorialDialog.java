package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.ChangeListener;
import pane5250.model.StateStore;

/** Pass-through highlights and explicit production-action events; never performs a host or credential operation. */
final class TutorialDialog extends JDialog {
    enum Guide {
        GETTING_STARTED("Getting started"),APPEARANCE("Appearance, colors and pane info"),
        PASSWORD_PROFILE("Non-Kerberos profile"),KEYBOARD("Keyboard shortcuts"),PANES("Pane tips and tricks"),
        DESKTOP_SHORTCUTS("Desktop profile / layout shortcuts");
        final String title;
        Guide(String title) { this.title=title; }
    }
    private record Step(String title,String text,String surface,String control,String event) {}
    private final Workspace workspace;
    private final boolean firstRun;
    private final Guide guide;
    private final List<Step> steps;
    private final JLabel title=new JLabel(),progress=new JLabel();
    private final JTextArea text=new JTextArea();
    private final JButton back,next,tryIt;
    private final Highlight highlight=new Highlight();
    private ProfilesEditor editor;
    private int step;
    private boolean ended,refreshing,queued;
    private Component anchor;
    private Window targetWindow;
    private final ComponentAdapter bounds=new ComponentAdapter() {
        @Override public void componentMoved(ComponentEvent e) { refreshTarget(); }
        @Override public void componentResized(ComponentEvent e) { refreshTarget(); }
        @Override public void componentShown(ComponentEvent e) { refreshTarget(); }
        @Override public void componentHidden(ComponentEvent e) { refreshTarget(); }
    };
    private final HierarchyBoundsAdapter ancestors=new HierarchyBoundsAdapter() {
        @Override public void ancestorMoved(HierarchyEvent e) { refreshTarget(); }
        @Override public void ancestorResized(HierarchyEvent e) { refreshTarget(); }
    };
    private final HierarchyListener showing=e -> refreshTarget();
    private final ChangeListener menus=e -> menuChanged();

    TutorialDialog(Workspace workspace,boolean firstRun) { this(workspace,Guide.GETTING_STARTED,firstRun); }
    TutorialDialog(Workspace workspace,Guide guide,boolean firstRun) {
        super(workspace,guide.title,false);
        this.workspace=workspace; this.firstRun=firstRun; this.guide=guide; steps=steps(guide);
        setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE); setAutoRequestFocus(false);
        if (firstRun) workspace.tutorialStatus(StateStore.Tutorial.IN_PROGRESS);
        JPanel body=new JPanel(new BorderLayout(0,12)); body.setBorder(Ui.padding(12));
        JPanel heading=new JPanel(new BorderLayout(12,0)); heading.add(title,BorderLayout.CENTER); heading.add(progress,BorderLayout.EAST);
        Ui.role(progress,"muted");
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setOpaque(false);
        text.setBorder(BorderFactory.createEmptyBorder()); text.getAccessibleContext().setAccessibleName("Tutorial step instructions");
        JPanel actions=new JPanel(new BorderLayout(0,8));
        tryIt=Ui.button("Show control",KeyEvent.VK_M,this::showControl);
        JPanel contextual=new JPanel(new BorderLayout(8,0)); contextual.add(tryIt,BorderLayout.WEST);
        contextual.add(Ui.button("Skip tutorial",KeyEvent.VK_S,() -> finish(false)),BorderLayout.EAST);
        actions.add(contextual,BorderLayout.NORTH);
        JPanel navigation=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        back=Ui.button("Back",KeyEvent.VK_B,() -> go(step-1));
        next=Ui.button("Next",KeyEvent.VK_N,this::advance);
        navigation.add(back); navigation.add(Ui.button("Skip step",KeyEvent.VK_K,this::advance)); navigation.add(next);
        actions.add(navigation,BorderLayout.SOUTH);
        JScrollPane reading=new JScrollPane(text); reading.setBorder(BorderFactory.createEmptyBorder());
        reading.setOpaque(false); reading.getViewport().setOpaque(false);
        reading.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        body.add(heading,BorderLayout.NORTH); body.add(reading,BorderLayout.CENTER); body.add(actions,BorderLayout.SOUTH);
        setContentPane(body); Ui.decorate(this,() -> finish(false)); getRootPane().setDefaultButton(next);
        setMinimumSize(new Dimension(340,245)); setSize(400,275);
        workspace.addComponentListener(bounds); MenuSelectionManager.defaultManager().addChangeListener(menus); go(0);
    }
    private void menuChanged() {
        if (guide==Guide.GETTING_STARTED && step==steps.size()-1)
            for (MenuElement element:MenuSelectionManager.defaultManager().getSelectedPath())
                if (element instanceof JMenu menu && menu.getText().equals("Tutorials") && menu.isPopupMenuVisible()) {
                    finish(true); return;
                }
        refreshTarget();
    }
    void go(int value) { step=Math.max(0,Math.min(steps.size()-1,value)); refreshTarget(); }
    private void advance() { if (step==steps.size()-1) finish(true); else go(step+1); }
    void action(String event) { if (!ended) { if (steps.get(step).event().equals(event)) advance(); else refreshTarget(); } }
    void focusGuide() { if (isShowing()) { requestFocus(); next.requestFocusInWindow(); } }
    private void showControl() {
        Step current=steps.get(step);
        switch(current.surface()) {
            case "Appearance","Profiles","Keyboard Shortcuts","General" -> workspace.preferences(current.surface());
            case "settings" -> workspace.showTutorialSettings();
            case "layouts","layout-destination" -> workspace.savedLayoutsDialog();
            case "Help","Panes","View","Session" -> workspace.showTutorialMenu(current.surface());
            default -> { if (workspace.isShowing()) workspace.toFront(); }
        }
        refreshTarget();
    }
    private static Step s(String title,String text,String surface,String control,String event) {
        return new Step(title,text,surface,control,event);
    }
    private static List<Step> steps(Guide guide) {
        return switch(guide) {
            case GETTING_STARTED -> List.of(
                    s("Choose your profile","Use the profile selector beside +. Selection alone changes no session. The default Kerberos profile needs your administrator's IBM i/domain setup; Edit > Settings > Profiles supports password sign-in.","add-profile","","add-profile-selected"),
                    s("Add and connect","Press + to connect the selected profile in a fresh pane. A lone never-used offline tile is reused. Each connection is independent; on failure use Reconnect in the pane or Session menu.","add","","Add pane"),
                    s("Close just one pane","Click X to remove a pane. The last pane is kept offline. With hidden headers, X is at the pane's upper-right edge.","close","","Close pane"),
                    s("Find your settings","Open Edit > Settings. Appearance brings application colors, terminal colors, fonts and pane information together. The left index goes straight to other controls.","settings","","settings-Appearance"),
                    s("Explore more guides","Open Help > Tutorials for appearance, profiles, keyboard shortcuts, pane tips and desktop layout shortcuts. Every guide has Back, Next, Skip step and Skip tutorial.","Help","Tutorials",""));
            case APPEARANCE -> List.of(
                    s("Open Appearance","Open Edit > Settings, then Appearance. Common appearance controls are all on this page; scroll to the sections below.","Appearance","theme","settings-Appearance"),
                    s("Choose application colors","Choose Application theme. Valid settings apply automatically. Watch the Saved / error status below. Custom theme editing is optional.","Appearance","theme","appearance-theme"),
                    s("Choose terminal colors","Check Terminal profile, then choose Text palette. Only that profile and its panes change. Text effect is optional and off by default.","Appearance","palette","appearance-palette"),
                    s("Choose your font","Choose a monospaced family. A local custom font file is optional; Check font verifies alignment and characters before Apply.","Appearance","font","appearance-font"),
                    s("Set header information","Scroll to Pane information. Show header controls visibility; the checkboxes below select its information.","Appearance","header","appearance-header"),
                    s("Set footer information","Show footer controls connection status, device name, screen size and cursor location. Extra information is optional. Window and focus outlines are just below.","Appearance","footer","appearance-footer"),
                    s("Check the save status","Valid changes apply automatically and save after a short pause. Saved confirms the disk write; errors stay visible with recovery controls. Close keeps saved changes.","Appearance","apply","settings-applied"));
            case PASSWORD_PROFILE -> List.of(
                    s("Open Profiles","Open Settings > Profiles. The actual workstation editor is here; Session > Profiles remains an operational workflow.","Profiles","list","settings-Profiles"),
                    s("Start a profile","Choose New, or select a saved profile and use Next. New starts a draft; the guide never creates or saves one for you.","Profiles","new","profile-new"),
                    s("Choose password sign-in","Set Sign in with to Password / manual. Username and password controls appear; no Kerberos ticket is requested.","Profiles","authentication","profile-password-mode"),
                    s("Enter the host","Enter your IBM i host and a recognizable profile name. This is configuration only, not a connection.","Profiles","host","profile-address"),
                    s("Enter your username","Enter your username, or choose an existing saved login when available. The guide never reads, records or supplies a password.","Profiles","username","profile-username"),
                    s("Review sign-in preferences","New password profiles have automatic sign-in and Save password on. Keep or adjust them. A blank password keeps an existing saved password; none is invented.","Profiles","remember","profile-login-options"),
                    s("Optional advanced fields","Advanced contains port, requested device and code page. The optional Kerberos DNS identity is here in Kerberos mode. Leave defaults unless your administrator specifies otherwise.","Profiles","advanced","profile-advanced"),
                    s("Create, then select and add","Choose Create profile for a new profile. Existing valid profile edits save automatically; password-store changes require Save login changes. Nothing connects until you use + or Reconnect.","Profiles","apply","profile-saved"));
            case KEYBOARD -> List.of(
                    s("Open Keyboard Shortcuts","Open Settings > Keyboard Shortcuts. Fish commands and host keys share this editor; host keys still act only in the terminal.","Keyboard Shortcuts","keyboard-search","settings-Keyboard Shortcuts"),
                    s("Find an action","Type part of an action or key in Find action. The table shows every effective binding, not just the one displayed in a menu.","Keyboard Shortcuts","keyboard-search","keyboard-search"),
                    s("Select the action","Select the action row. Its complete binding list appears below.","Keyboard Shortcuts","keyboard-table","keyboard-action"),
                    s("Edit or record a binding","F5 is a single key. For Ctrl+F5 type ctrl F5; for Ctrl+Shift+F5 type ctrl shift F5 (spaces, not +). Use one alternative per line or Record and add. Allowed keys and actions lists every supported token/action. ENTER@numpad is keypad Enter. Two custom owners conflict.","Keyboard Shortcuts","keyboard-keys","keyboard-binding"),
                    s("Check saved bindings","Complete conflict-free bindings apply and save after a short pause. Invalid or conflicting entries stay editable without replacing saved keys. Saved confirms persistence; Close keeps it.","Keyboard Shortcuts","apply","settings-applied"));
            case PANES -> List.of(
                    s("Add an independent pane","Choose a profile beside +, then press + to add and connect it. A lone never-used offline tile is reused; later adds create new panes, up to eight.","add","","Add pane"),
                    s("Resize side by side","Drag the highlighted divider horizontally. Panes > Decrease / Increase divider are keyboard equivalents. Pane size scales text, not host dimensions.","divider-horizontal","","divider-horizontal"),
                    s("Stack panes","Drag a header to another pane's top or bottom edge for stacked panes; left/right places them side by side. With hidden headers use the upper edge. The shaded region previews the result. Panes > Arrange pane is the keyboard equivalent. Invalid two-lane/minimum-size positions are refused.","Panes","Arrange pane...","panes-arranged"),
                    s("Resize vertically","Drag the row divider vertically. Decrease / Increase divider resizes the active pane's nearest divider without changing the host's screen dimensions.","divider-vertical","","divider-vertical"),
                    s("Swap or undo","Drop in another pane's center to swap positions, or use Panes > Swap panes. Undo pane arrangement restores the previous arrangement without reconnecting. Up to eight panes; no 3x3 or unrestricted nesting.","Panes","Swap panes...","panes-swapped"),
                    s("Choose startup connections","Edit > Settings > General controls ordinary restored-window startup. Its off choice is kept. Explicit +, New window and saved-layout launches still connect once; Disconnect stays disconnected until you explicitly retry.","General","startup","startup-changed"),
                    s("Fit and navigate","View > Fit host screen fits the active pane. Ctrl+Tab moves between panes; X closes one and keeps the last offline. Settings lists your complete custom bindings.","View","Fit host screen","Fit host screen"),
                    s("Create a layout shortcut","Panes > Saved layouts / shortcuts names this window. Create desktop shortcut saves the layout and writes a Windows .lnk after destination confirmation. Saved layouts below manages existing recipes; each launch opens a new window.","Panes","Saved layouts / shortcuts...","layout-saved"));
            case DESKTOP_SHORTCUTS -> List.of(
                    s("Choose a profile or keep this layout","For a profile shortcut, use a window with just that profile. Choose it beside +, then use the next step. For your current multi-pane layout, skip these first two steps; no session changes just by opening this guide.","add-profile","","add-profile-selected"),
                    s("Prepare the window","Press + to add and connect the selected profile, or skip if the window already has the panes you want. To make a one-profile shortcut, use Session > New window and replay this guide there. Saving a recipe never captures screen contents or passwords.","add","","Add pane"),
                    s("Open saved layouts","Open Panes > Saved layouts / shortcuts. Show control opens that menu; choose the item. Existing layouts and their shortcuts stay separate.","Panes","Saved layouts / shortcuts...","layouts-opened"),
                    s("Give the layout a name","Under Shortcut for this window, enter a recognizable name, such as Morning work or Inventory. The context above shows a single profile or your current pane count. Use a new name to keep recipes separate.","layouts","name","layout-named"),
                    s("Create the shortcut","Choose Create desktop shortcut. It saves this window's profiles, arrangement, proportions and fit/zoom when you confirm the destination. No separate Save step is required. Source/Java and Demo launches cannot export runnable shortcuts.","layouts","export","layout-destination-open"),
                    s("Choose Desktop or a folder","The save chooser starts at Desktop. Keep it or choose another folder, check the .lnk name, then confirm. Cancel writes nothing. Existing files are never overwritten; failures stay visible in Saved layouts so you can retry.","layout-destination","destination","layout-shortcut"),
                    s("Open it when you need it","Double-click the created .lnk for one new auto-connecting Fish window. Saved layouts below manages existing recipes; renaming/updating keeps their links working. Moving portable Fish requires a new link. This guide never launches it.","layouts","message",""));
        };
    }
    private Component target(Step current) {
        Preferences settings=workspace.tutorialPreferences();
        return switch(current.surface()) {
            case "connect" -> workspace.active().tutorialConnectionTarget(); case "add" -> workspace.tutorialAddTarget();
            case "add-profile" -> workspace.tutorialProfileTarget();
            case "layouts","layout-destination" -> workspace.tutorialLayoutTarget(current.control());
            case "close" -> workspace.active().tutorialCloseTarget(); case "settings" -> workspace.tutorialSettingsTarget();
            case "divider-horizontal","divider-vertical" -> {
                Component divider=workspace.tutorialDivider(current.surface().equals("divider-horizontal"));
                yield divider==null ? workspace.tutorialAddTarget() : divider;
            }
            case "Appearance","Keyboard Shortcuts","General" -> settings!=null && settings.category().equals(current.surface())
                    ? settings.tutorialTarget(current.control()) : workspace.tutorialSettingsTarget();
            case "Profiles" -> editor!=null ? editor.tutorialTarget(current.control()) : workspace.tutorialSettingsTarget();
            default -> workspace.tutorialMenuTarget(current.surface(),current.control());
        };
    }
    void refreshTarget() {
        if (ended || refreshing) return;
        refreshing=true;
        try {
            editor=workspace.tutorialProfileEditor(); Step current=steps.get(step);
            Component target=target(current); String message=current.text();
            if (guide==Guide.APPEARANCE && current.control().equals("palette") && workspace.tutorialPreferences()!=null
                    && workspace.tutorialPreferences().category().equals("Appearance") && workspace.tutorialPreferences().customPalette()) {
                message+=" This custom theme owns host colors. Choose a built-in application theme to enable a profile palette, or skip.";
                target=workspace.tutorialPreferences().tutorialTarget("theme");
            }
            if (current.surface().startsWith("divider-") && workspace.tutorialDivider(current.surface().equals("divider-horizontal"))==null)
                message+=" There is no matching divider yet. Choose a profile and use + if you want one, or skip.";
            if (current.surface().equals("add") && workspace.sessionPanes().size()==8)
                message+=" All eight slots are occupied. Close a pane first or skip.";
            if (current.surface().equals("close") && workspace.sessionPanes().size()==1 && !workspace.active().busy())
                message="Only the last offline pane remains; there is nothing to close. Use Next or Skip step.";
            title.setText(current.title()); progress.setText((step+1)+" of "+steps.size());
            if (!text.getText().equals(message)) { text.setText(message); text.setCaretPosition(0); }
            back.setEnabled(step>0); next.setText(step==steps.size()-1 ? "Done" : "Next");
            if (anchor!=target) {
                if (anchor!=null) { anchor.removeComponentListener(bounds); anchor.removeHierarchyBoundsListener(ancestors); anchor.removeHierarchyListener(showing); }
                anchor=target;
                if (anchor!=null) { anchor.addComponentListener(bounds); anchor.addHierarchyBoundsListener(ancestors); anchor.addHierarchyListener(showing); }
            }
            Window window=anchor==null ? workspace : SwingUtilities.getWindowAncestor(anchor);
            if (window==null) window=workspace;
            if (targetWindow!=window) {
                if (targetWindow!=null && targetWindow!=workspace) targetWindow.removeComponentListener(bounds);
                targetWindow=window;
                if (targetWindow!=workspace) targetWindow.addComponentListener(bounds);
                Container old=highlight.getParent(); if (old!=null) { old.remove(highlight); old.repaint(); }
                if (window instanceof RootPaneContainer root) root.getLayeredPane().add(highlight,JLayeredPane.DRAG_LAYER);
            }
            if (highlight.getParent() instanceof JLayeredPane layer) {
                highlight.setBounds(0,0,layer.getWidth(),layer.getHeight());
                highlight.target=anchor==null || anchor.getParent()==null ? null
                        : SwingUtilities.convertRectangle(anchor.getParent(),anchor.getBounds(),layer);
                highlight.repaint();
            }
            position();
        } finally { refreshing=false; }
        if (!queued) {
            queued=true; SwingUtilities.invokeLater(() -> {
                queued=false; if (ended) return;
                refreshing=true;
                try { position(); if (editor!=null && editor.isShowing() && isShowing()) toFront(); }
                finally { refreshing=false; }
            });
        }
    }
    private void position() {
        if (anchor==null || anchor.getParent()==null || targetWindow==null) return;
        Point point=SwingUtilities.convertPoint(anchor,0,0,targetWindow);
        Rectangle box=new Rectangle(targetWindow.getX()+point.x,targetWindow.getY()+point.y,anchor.getWidth(),anchor.getHeight());
        GraphicsConfiguration config=targetWindow.getGraphicsConfiguration();
        Rectangle usable=new Rectangle(config.getBounds()); Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(config);
        usable.x+=insets.left; usable.y+=insets.top; usable.width-=insets.left+insets.right; usable.height-=insets.top+insets.bottom;
        Point[] choices={new Point(box.x+box.width+12,box.y),new Point(box.x-getWidth()-12,box.y),
                new Point(box.x,box.y+box.height+12),new Point(box.x,box.y-getHeight()-12)};
        Point best=null; long score=Long.MAX_VALUE;
        for (Point choice:choices) {
            int x=Math.max(usable.x,Math.min(choice.x,usable.x+usable.width-getWidth()));
            int y=Math.max(usable.y,Math.min(choice.y,usable.y+usable.height-getHeight()));
            Rectangle overlap=box.intersection(new Rectangle(x,y,getWidth(),getHeight()));
            long cost=(overlap.isEmpty() ? 0 : (long)overlap.width*overlap.height*1000)+Math.abs(x-choice.x)+Math.abs(y-choice.y);
            if (cost<score) { score=cost; best=new Point(x,y); }
        }
        if (best!=null && !getLocation().equals(best)) setLocation(best);
    }
    void finish(boolean completed) {
        if (ended) return;
        if (firstRun) workspace.tutorialStatus(completed ? StateStore.Tutorial.COMPLETED : StateStore.Tutorial.SKIPPED);
        dispose();
    }
    boolean ended() { return ended; }
    @Override public void dispose() {
        if (!ended) {
            ended=true; workspace.removeComponentListener(bounds); MenuSelectionManager.defaultManager().removeChangeListener(menus);
            if (targetWindow!=null && targetWindow!=workspace) targetWindow.removeComponentListener(bounds);
            if (anchor!=null) { anchor.removeComponentListener(bounds); anchor.removeHierarchyBoundsListener(ancestors); anchor.removeHierarchyListener(showing); anchor=null; }
            Container parent=highlight.getParent(); if (parent!=null) { parent.remove(highlight); parent.repaint(); }
        }
        super.dispose();
    }
    private static final class Highlight extends JComponent {
        Rectangle target;
        Highlight() { setOpaque(false); setFocusable(false); }
        @Override public boolean contains(int x,int y) { return false; }
        @Override protected void paintComponent(Graphics g) {
            if (target!=null && target.width>0 && target.height>0) Hairline.highlight(g,target.x,target.y,target.width,target.height);
        }
    }
}
