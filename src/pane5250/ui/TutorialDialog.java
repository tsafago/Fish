package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.ChangeListener;
import pane5250.model.StateStore;

/** Hints follow the real controls; only the user edits profiles or starts connections. */
final class TutorialDialog extends JDialog {
    private final Workspace workspace;
    private final boolean firstRun;
    private final JLabel title=new JLabel(), progress=new JLabel();
    private final JTextArea text=new JTextArea();
    private final JButton back,next,tryIt;
    private final Highlight highlight=new Highlight();
    private ProfilesDialog editor;
    private int step, paneCount;
    private boolean ended, menuShown, refreshing, queued;
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
    private final ChangeListener menus=e -> refreshTarget();

    TutorialDialog(Workspace workspace,boolean firstRun) {
        super(workspace,"Getting started",false);
        this.workspace=workspace; this.firstRun=firstRun;
        // The guide stays usable while the real, modal profile editor is open.
        setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        setAutoRequestFocus(false);
        if (firstRun) workspace.tutorialStatus(StateStore.Tutorial.IN_PROGRESS);
        JPanel body=new JPanel(new BorderLayout(0,12)); body.setBorder(Ui.padding(12));
        JPanel heading=new JPanel(new BorderLayout(12,0)); heading.add(title,BorderLayout.CENTER); heading.add(progress,BorderLayout.EAST);
        Ui.role(progress,"muted");
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        text.setOpaque(false); text.setBorder(BorderFactory.createEmptyBorder());
        text.getAccessibleContext().setAccessibleName("Tutorial step instructions");
        JPanel actions=new JPanel(new BorderLayout(0,8));
        tryIt=Ui.button("Show menu",KeyEvent.VK_M,this::showMenu);
        JPanel contextual=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0)); contextual.add(tryIt);
        actions.add(contextual,BorderLayout.NORTH);
        JPanel navigation=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        navigation.add(Ui.button("Skip",KeyEvent.VK_S,() -> finish(false)));
        back=Ui.button("Back",KeyEvent.VK_B,() -> go(step-1));
        next=Ui.button("Next",KeyEvent.VK_N,() -> {
            if (step==6) finish(true);
            else if (step==0 && editor==null && !menuShown) showMenu();
            else go(step==0 && editor==null ? 4 : step+1);
        });
        navigation.add(back); navigation.add(next); actions.add(navigation,BorderLayout.SOUTH);
        JScrollPane reading=new JScrollPane(text);
        reading.setBorder(BorderFactory.createEmptyBorder()); reading.setOpaque(false); reading.getViewport().setOpaque(false);
        reading.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        body.add(heading,BorderLayout.NORTH); body.add(reading,BorderLayout.CENTER); body.add(actions,BorderLayout.SOUTH);
        setContentPane(body); Ui.decorate(this,() -> finish(false)); getRootPane().setDefaultButton(next);
        setMinimumSize(new Dimension(320,220)); setSize(350,240);
        workspace.addComponentListener(bounds);
        MenuSelectionManager.defaultManager().addChangeListener(menus);
        go(0);
    }
    void profileOpened(ProfilesDialog dialog) {
        editor=dialog;
        go(1);
    }
    void profileClosed(ProfilesDialog dialog) {
        if (editor!=dialog) return;
        editor=null;
        go(4);
    }
    void go(int value) {
        int nextStep=Math.max(0,Math.min(6,value));
        if (nextStep==5 && step!=5) paneCount=workspace.sessionPanes().size();
        step=nextStep;
        refreshTarget();
    }
    void focusGuide() {
        if (isShowing()) { requestFocus(); next.requestFocusInWindow(); }
    }
    private String binding(String action) {
        String key=workspace.shortcutLabel(action);
        return key.isEmpty() ? "" : " ("+key+")";
    }
    private void showMenu() {
        if (editor!=null) return;
        menuShown=true;
        workspace.showTutorialMenu(step==5 ? "Panes" : "Session");
        refreshTarget();
    }
    void refreshTarget() {
        if (ended || refreshing) return;
        refreshing=true;
        try {
            String[] titles={"Start with Session","Set up your connection","Sign in once","Save your profile","Connect your pane","Add another pane","Make room"};
            String[] instructions={
                "Open Session > Profiles. Edit Local IBM i or choose New. Up/Down changes the order; Delete removes a saved profile.",
                "Check the host address, then enter your username. Give the profile a name you'll recognize. Device is optional.",
                "Leave both recommended options on to skip typing your login next time. Enter your password below. Windows saves it; plain TCP is unencrypted.",
                "Click Done to save without connecting. Apply saves and keeps editing; Cancel drops only unapplied changes.",
                "Pick your profile in this pane, then Connect. For next time, Session > Connect panes on startup can reopen your sessions.",
                "Open Panes > Add pane"+binding("Add pane")+". Each pane has its own connection. Next pane"+binding("Next pane")+" moves between them.",
                "Drag headers to swap panes and dividers to resize. View > Fit host screen"+binding("Fit host screen")+" fits. Edit > Preferences"+binding("Preferences...")+" indexes settings and exact key recording. Help has updates and this guide."
            };
            Component target=editor==null ? workspace.tutorialTarget(step) : editor.tutorialTarget(step);
            String message=instructions[step];
            if (editor!=null && step==1) message=editor.tutorialConnectionText();
            if (editor!=null && step==2) message=editor.tutorialSignInText();
            if (editor!=null && editor.kerberos() && step==2) titles[2]="Use Windows sign-in";
            if (editor==null && step>=1 && step<=3) message="Open Session > Profiles to try this step. You can also keep going without changing a profile.";
            if (editor!=null && (step==0 || step>=4)) message="Finish with Done, or Cancel to return to your panes. You can leave this guide open while you edit.";
            if (editor==null && step==4 && workspace.active()!=null && workspace.active().busy())
                message="This pane is already connected or connecting. Copy"+binding("Copy selection / screen")+"; paste"+binding("Paste into fields")+". Enter submits. Host > ATTN"+binding("ATTN")+" sends Attention.";
            if (editor==null && step==5 && (workspace.sessionPanes().size()>paneCount || workspace.sessionPanes().size()==8)) {
                titles[5]="Close just one pane";
                message="The X closes this pane, not the whole window. Close pane"+binding("Close pane")+" does the same. Next pane"+binding("Next pane")+" moves between panes.";
                target=workspace.active().tutorialCloseTarget();
            }
            if (editor==null && step==0 && target instanceof JMenuItem && !(target instanceof JMenu)) {
                titles[0]="Choose Profiles";
                message="Click Profiles here to set up a workstation. These are the real menus, so you can use them normally.";
            }
            title.setText(titles[step]); progress.setText((step+1)+" of 7");
            if (!text.getText().equals(message)) { text.setText(message); text.setCaretPosition(0); }
            back.setEnabled(step>0); next.setText(step==6 ? "Done" : step==0 && menuShown && editor==null ? "Skip setup" : "Next");
            next.setMnemonic(step==6 ? KeyEvent.VK_D : KeyEvent.VK_N);
            tryIt.setVisible(editor==null && (step==0 || step==4 || step==5));
            if (anchor!=target) {
                if (anchor!=null) {
                    anchor.removeComponentListener(bounds); anchor.removeHierarchyBoundsListener(ancestors); anchor.removeHierarchyListener(showing);
                }
                anchor=target;
                if (anchor!=null) {
                    anchor.addComponentListener(bounds); anchor.addHierarchyBoundsListener(ancestors); anchor.addHierarchyListener(showing);
                }
            }
            Window window=anchor==null ? workspace : SwingUtilities.getWindowAncestor(anchor);
            if (window==null) window=workspace;
            if (targetWindow!=window) {
                if (targetWindow!=null && targetWindow!=workspace) targetWindow.removeComponentListener(bounds);
                targetWindow=window;
                if (targetWindow!=workspace) targetWindow.addComponentListener(bounds);
                Container old=highlight.getParent();
                if (old!=null) { old.remove(highlight); old.repaint(); }
                if (window instanceof RootPaneContainer root) root.getLayeredPane().add(highlight,JLayeredPane.DRAG_LAYER);
            }
            if (highlight.getParent() instanceof JLayeredPane layer) {
                highlight.setBounds(0,0,layer.getWidth(),layer.getHeight());
                highlight.target=anchor==null || anchor.getParent()==null || anchor instanceof javax.swing.plaf.basic.BasicSplitPaneDivider ? null
                        : SwingUtilities.convertRectangle(anchor.getParent(),anchor.getBounds(),layer);
                highlight.repaint();
            }
            position();
        } finally { refreshing=false; }
        if (!queued) {
            queued=true;
            SwingUtilities.invokeLater(() -> {
                queued=false;
                if (ended) return;
                // Layout, popup peers and modal visibility settle after their originating event.
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
            long cost=(overlap.isEmpty() ? 0 : (long)overlap.width*overlap.height*1000)
                    +Math.abs(x-choice.x)+Math.abs(y-choice.y);
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
            ended=true; workspace.removeComponentListener(bounds);
            MenuSelectionManager.defaultManager().removeChangeListener(menus);
            if (targetWindow!=null && targetWindow!=workspace) targetWindow.removeComponentListener(bounds);
            if (anchor!=null) {
                anchor.removeComponentListener(bounds); anchor.removeHierarchyBoundsListener(ancestors); anchor.removeHierarchyListener(showing); anchor=null;
            }
            Container parent=highlight.getParent();
            if (parent!=null) { parent.remove(highlight); parent.repaint(); }
        }
        super.dispose();
    }
    private static final class Highlight extends JComponent {
        Rectangle target;
        Highlight() { setOpaque(false); setFocusable(false); }
        @Override public boolean contains(int x,int y) { return false; }
        @Override protected void paintComponent(Graphics g) {
            if (target!=null && target.width>0 && target.height>0)
                Hairline.highlight(g,target.x,target.y,target.width,target.height);
        }
    }
}
