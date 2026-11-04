package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import javax.swing.*;
import pane5250.model.StateStore;

/** A modeless guide over the actual workspace; no synthetic profiles or host actions. */
final class TutorialDialog extends JDialog {
    private final Workspace workspace;
    private final boolean firstRun;
    private final JLabel title=new JLabel(), progress=new JLabel();
    private final JTextArea text=new JTextArea();
    private final JButton back,next,tryIt;
    private final Highlight highlight=new Highlight();
    private int step;
    private boolean ended;
    private Component anchor;
    private final ComponentAdapter anchorBounds=new ComponentAdapter() {
        @Override public void componentMoved(ComponentEvent e) { refreshTarget(); }
        @Override public void componentResized(ComponentEvent e) { refreshTarget(); }
        @Override public void componentShown(ComponentEvent e) { refreshTarget(); }
        @Override public void componentHidden(ComponentEvent e) { refreshTarget(); }
    };
    private final HierarchyBoundsAdapter ancestors=new HierarchyBoundsAdapter() {
        @Override public void ancestorMoved(HierarchyEvent e) { refreshTarget(); }
        @Override public void ancestorResized(HierarchyEvent e) { refreshTarget(); }
    };
    private final ComponentAdapter ownerBounds=new ComponentAdapter() {
        @Override public void componentResized(ComponentEvent e) { position(); refreshTarget(); }
        @Override public void componentMoved(ComponentEvent e) { position(); refreshTarget(); }
    };
    TutorialDialog(Workspace workspace,boolean firstRun) {
        super(workspace,"Getting started",false);
        this.workspace=workspace; this.firstRun=firstRun;
        if (firstRun) workspace.tutorialStatus(StateStore.Tutorial.IN_PROGRESS);
        JPanel body=new JPanel(new BorderLayout(0,14)); body.setBorder(Ui.padding(14));
        JPanel heading=new JPanel(new BorderLayout(12,0)); heading.add(title,BorderLayout.CENTER); heading.add(progress,BorderLayout.EAST);
        Ui.role(progress,"muted");
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        text.setOpaque(false); text.setBorder(BorderFactory.createEmptyBorder());
        text.getAccessibleContext().setAccessibleName("Tutorial step instructions");
        JPanel actions=new JPanel(new BorderLayout(0,12));
        tryIt=Ui.button("Edit profile",KeyEvent.VK_E,this::tryStep);
        JPanel contextual=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0)); contextual.add(tryIt);
        actions.add(contextual,BorderLayout.NORTH);
        JPanel navigation=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        navigation.add(Ui.button("Skip",KeyEvent.VK_S,() -> finish(false)));
        back=Ui.button("Back",KeyEvent.VK_B,() -> go(step-1));
        next=Ui.button("Next",KeyEvent.VK_N,() -> { if (step==5) finish(true); else go(step+1); });
        navigation.add(back); navigation.add(next); actions.add(navigation,BorderLayout.SOUTH);
        JScrollPane reading=new JScrollPane(text);
        reading.setBorder(BorderFactory.createEmptyBorder()); reading.setOpaque(false); reading.getViewport().setOpaque(false);
        reading.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        body.add(heading,BorderLayout.NORTH); body.add(reading,BorderLayout.CENTER); body.add(actions,BorderLayout.SOUTH);
        setContentPane(body); Ui.decorate(this,() -> finish(false)); getRootPane().setDefaultButton(next);
        setMinimumSize(new Dimension(340,300)); setSize(430,410);
        workspace.addComponentListener(ownerBounds);
        workspace.getLayeredPane().add(highlight,JLayeredPane.DRAG_LAYER);
        go(0); position();
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { next.requestFocusInWindow(); refreshTarget(); }
        });
    }
    private String binding(String action) {
        String key=workspace.shortcutLabel(action);
        return key.isEmpty() ? "the "+action+" menu action" : key;
    }
    void go(int value) {
        step=Math.max(0,Math.min(5,value));
        String[] titles={"Your workspace","Create a workstation","Connect and work","Add and close panes","Move and resize","Ready when you are"};
        String[] instructions={
            "Each pane is an independent 5250 session. Session contains profiles and connection actions; View contains appearance and zoom.\n\nThis guide does not connect or change profiles for you. Use Next and Back at your own pace, or Skip. The workspace stays usable while the guide is open.",
            "Open the real profile editor below. Edit Local IBM i or choose New, then enter your host and username. Device is optional; port 23 is the normal plain-TCP default.\n\nFor everyday use on your trusted network, choose Use recommended sign-in: automatic sign-in plus Save password in Windows Credential Manager. Plain TCP is unencrypted, and the host must support RFC 4777 plain automatic sign-on. Manual sign-in remains available.\n\nApply saves and stays; Done saves without connecting. You can leave host and credentials blank and continue this guide.",
            "Choose a saved profile in the offline pane, then Connect ("+binding("Connect / disconnect")+"). Only your action starts a connection. A TCP connection is not proof of IBM i sign-on.\n\nType in host input fields; Enter normally submits. If a local misentry locks input, the first Enter performs Reset only; the next Enter submits normally. Escape sends Attention; Shift+Escape sends System Request.\n\n"+binding("Copy selection / screen")+" copies the selection or screen; "+binding("Paste into fields")+" pastes without submitting Enter. Hidden fields are never copied.",
            "Use + Pane or "+binding("Add pane")+" to add an offline pane, up to eight. Adding a pane never connects it automatically.\n\nThe X closes that pane's connection and removes its slot. The final pane stays as an offline tile. Its X is available whenever it can disconnect; with a hidden header it appears in the top-right overlay.\n\nClick a pane to focus it, or use "+binding("Next pane")+" / "+binding("Previous pane")+".",
            "Drag a pane header onto another pane to swap them. If headers are hidden, drag near the top beside the X. The drop preview shows the target; sockets and host screens stay independent. "+binding("Swap panes...")+" is the keyboard alternative.\n\nDrag the thin dividers to resize panes, or use "+binding("Decrease divider")+" / "+binding("Increase divider")+". Resize the whole window by its edges. None of these changes the host's rows or columns.\n\n"+binding("Fit host screen")+" fits the display; "+binding("Zoom in")+" / "+binding("Zoom out")+" changes zoom.",
            "You can replay this guide from Help > Getting started tutorial at any time. Replaying does not create profiles, reconnect sessions or reset settings.\n\nEdit > Keyboard > Shortcuts shows every current assignment. Appearance > More settings holds fonts, pane information and window details.\n\nHelp > Configuration for external tools explains the local, validated configuration interface for bulk edits by you or an external chatbot. No AI service is built into Fish."
        };
        title.setText(titles[step]); progress.setText((step+1)+" of 6"); text.setText(instructions[step]); text.setCaretPosition(0);
        back.setEnabled(step>0); next.setText(step==5 ? "Done" : "Next");
        next.setMnemonic(step==5 ? KeyEvent.VK_D : KeyEvent.VK_N);
        tryIt.setVisible(List.of(1,3,5).contains(step));
        tryIt.setText(step==1 ? "Create / edit profile" : step==3 ? "Add pane" : "Keyboard settings");
        tryIt.setMnemonic(step==1 ? KeyEvent.VK_E : step==3 ? KeyEvent.VK_A : KeyEvent.VK_K);
        tryIt.setEnabled(step!=3 || workspace.sessionPanes().size()<8);
        refreshTarget();
    }
    private void tryStep() {
        if (step==1) workspace.profiles();
        else if (step==3) workspace.split(true);
        else if (step==5) workspace.showShortcuts();
        go(step);
    }
    void refreshTarget() {
        if (ended) return;
        JLayeredPane layer=workspace.getLayeredPane();
        highlight.setBounds(0,0,layer.getWidth(),layer.getHeight());
        Component target=workspace.tutorialTarget(step);
        if (anchor!=target) {
            if (anchor!=null) { anchor.removeComponentListener(anchorBounds); anchor.removeHierarchyBoundsListener(ancestors); }
            anchor=target;
            if (anchor!=null) { anchor.addComponentListener(anchorBounds); anchor.addHierarchyBoundsListener(ancestors); }
        }
        Rectangle box=target==null || target.getParent()==null ? null
                : SwingUtilities.convertRectangle(target.getParent(),target.getBounds(),layer);
        highlight.target=box; highlight.repaint();
    }
    private void position() {
        GraphicsConfiguration config=workspace.getGraphicsConfiguration();
        Rectangle usable=new Rectangle(config.getBounds()); Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(config);
        usable.x+=insets.left; usable.y+=insets.top; usable.width-=insets.left+insets.right; usable.height-=insets.top+insets.bottom;
        int x=workspace.getX()+workspace.getWidth()-getWidth()-18, y=workspace.getY()+workspace.getHeight()-getHeight()-18;
        setLocation(Math.max(usable.x,Math.min(x,usable.x+usable.width-getWidth())),
                Math.max(usable.y,Math.min(y,usable.y+usable.height-getHeight())));
    }
    void finish(boolean completed) {
        if (ended) return;
        if (firstRun) workspace.tutorialStatus(completed ? StateStore.Tutorial.COMPLETED : StateStore.Tutorial.SKIPPED);
        dispose();
    }
    boolean ended() { return ended; }
    @Override public void dispose() {
        if (!ended) {
            ended=true; workspace.removeComponentListener(ownerBounds);
            if (anchor!=null) { anchor.removeComponentListener(anchorBounds); anchor.removeHierarchyBoundsListener(ancestors); anchor=null; }
            Container parent=highlight.getParent();
            if (parent!=null) { Rectangle old=highlight.getBounds(); parent.remove(highlight); parent.repaint(old.x,old.y,old.width,old.height); }
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
