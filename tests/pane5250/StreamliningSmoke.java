package pane5250;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import pane5250.model.*;
import pane5250.ui.*;

/** Source/packaged checks without desktop input, shared clipboard or credentials. */
public final class StreamliningSmoke {
    private static int checks;
    private static void check(boolean value,String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> task=new FutureTask<>(work); SwingUtilities.invokeAndWait(task);
        try { return task.get(); } catch (ExecutionException e) { throw new AssertionError(e.getCause()); }
    }
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static List<Component> children(Container parent) {
        List<Component> result=new ArrayList<>();
        for (Component child:parent.getComponents()) {
            result.add(child); if (child instanceof Container nested) result.addAll(children(nested));
        }
        return result;
    }
    private static void capture(JComponent component,String file,double scale) throws Exception {
        BufferedImage image=new BufferedImage((int)(component.getWidth()*scale),(int)(component.getHeight()*scale),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try { g.scale(scale,scale); component.paint(g); } finally { g.dispose(); }
        ImageIO.write(image,"png",Path.of("build",file).toFile());
    }
    private static boolean dispatch(Workspace workspace,Component source,int id,int modifiers,int code) throws Exception {
        Object dispatcher=field(workspace,"shortcuts");
        Method method=dispatcher.getClass().getDeclaredMethod("dispatchKeyEvent",KeyEvent.class); method.setAccessible(true);
        return (boolean)method.invoke(dispatcher,new KeyEvent(source,id,System.currentTimeMillis(),modifiers,code,KeyEvent.CHAR_UNDEFINED));
    }
    private static String labelText(JLabel label) {
        Insets insets=label.getInsets();
        return SwingUtilities.layoutCompoundLabel(label,label.getFontMetrics(label.getFont()),label.getText(),label.getIcon(),
                label.getVerticalAlignment(),label.getHorizontalAlignment(),label.getVerticalTextPosition(),label.getHorizontalTextPosition(),
                new Rectangle(0,0,Math.max(0,label.getWidth()-insets.left-insets.right),
                        Math.max(0,label.getHeight()-insets.top-insets.bottom)),new Rectangle(),new Rectangle(),label.getIconTextGap());
    }
    private static <T> String comboText(JComboBox<T> combo) {
        combo.doLayout();
        Component value=combo.getRenderer().getListCellRendererComponent(new JList<T>(),
                combo.getItemAt(combo.getSelectedIndex()),-1,false,false);
        JLabel label=(JLabel)value;
        label.setFont(combo.getFont());
        Insets insets=combo.getInsets();
        int arrow=Arrays.stream(combo.getComponents()).filter(c -> c instanceof JButton).mapToInt(Component::getWidth).sum();
        label.setSize(Math.max(0,combo.getWidth()-insets.left-insets.right-arrow),
                Math.max(0,combo.getHeight()-insets.top-insets.bottom));
        return labelText(label);
    }
    private static void layoutTree(Container parent) {
        parent.doLayout();
        for (Component child:parent.getComponents()) if (child instanceof Container container) layoutTree(container);
    }
    private static void sized(Workspace workspace,int width,int height) {
        workspace.setSize(width,height); workspace.validate(); layoutTree(workspace.getRootPane());
        for (Component child:children(workspace)) if (child instanceof JSplitPane split) split.setDividerLocation(.5);
        layoutTree(workspace.getRootPane());
    }
    private static void allocationChecks(Workspace workspace,SessionPane pane,JComboBox<?> preset) throws Exception {
        JButton sample=new JButton("Save & connect");
        check(sample.getInsets().equals(sample.getInsets(new Insets(0,0,0,0))),
                "Shared border reports identical insets to measuring, reused-inset and painting paths.");
        Profile original=pane.profile();
        try {
            sized(workspace,480,600);
            check(pane.getWidth()<265,"Narrow-preset regression uses a pane smaller than the original row's preferred width.");
            int usable=pane.getWidth()-pane.getInsets().left-pane.getInsets().right-16-40;
            check(preset.getWidth()>=Math.min(225,usable),
                    "Narrow pane gives the preset actual available width instead of collapsing to a 60-pixel row.");
            check(comboText(preset).equals(pane.profile().name()),
                    "Short preset name remains complete below the row's preferred width.");
            capture(workspace.getRootPane(),"fish-sizing-480-"+System.getProperty("sun.java2d.uiScale","default")+".png",1.5);
            Profile longName=Profile.create("Warehouse - shipping and receiving workstation");
            workspace.putProfile(longName); workspace.selectProfile(pane,longName);
            sized(workspace,1280,700);
            check(preset.getWidth()>225 && comboText(preset).equals(longName.name()),
                    "Wide pane grows the selector to fit a long preset name, without an artificial 225-pixel limit.");
            capture(workspace.getRootPane(),"fish-sizing-1280-"+System.getProperty("sun.java2d.uiScale","default")+".png",1);
            sized(workspace,330,370);
            Rectangle combo=SwingUtilities.convertRectangle(preset.getParent(),preset.getBounds(),pane);
            JButton connect=(JButton)field(pane,"connectButton");
            Rectangle button=SwingUtilities.convertRectangle(connect.getParent(),connect.getBounds(),pane);
            check(combo.width>=0 && combo.getMaxX()<button.x && button.getMaxX()<=pane.getWidth(),
                    "Genuinely narrow panes preserve the Connect target without overlapping the preset or pane edge.");
            check(!comboText(preset).equals(longName.name()),
                    "Actual insufficient width still uses meaningful ellipsis, not clipping or hidden overflow.");
        } finally { workspace.selectProfile(pane,original); }
        Object chrome=field(workspace,"chrome");
        Container tools=(Container)field(chrome,"tools");
        JMenuBar menus=(JMenuBar)field(chrome,"menus");
        Map<Component,Font> fonts=new LinkedHashMap<>();
        for (Component component:tools.getComponents()) fonts.put(component,component.getFont());
        for (Component component:menus.getComponents()) fonts.put(component,component.getFont());
        for (boolean larger:List.of(false,true)) {
            fonts.forEach((component,font) -> component.setFont(larger ? font.deriveFont(16f) : font));
            for (int width:new int[]{330,620,740,1280}) {
                sized(workspace,width,700);
                Component left=(Component)field(chrome,"left"),controls=(Component)field(chrome,"controls");
                Component drag=(Component)field(chrome,"dragArea");
                check(left.getX()+left.getWidth()<controls.getX() && drag.getWidth()>=32,
                        "Measured titlebar controls retain a drag region without overlap at width "+width+", larger font="+larger);
                for (Component component:tools.getComponents()) if (component instanceof JButton b && !b.getText().isEmpty())
                    check(b.getWidth()>=b.getPreferredSize().width,"Labeled toolbar action receives its full measured width.");
                if (menus.isVisible()) for (Component component:menus.getComponents())
                    check(component.getWidth()>=component.getPreferredSize().width,"Visible menu receives its full measured width.");
                check(width!=1280 || menus.isVisible(),"Wide titlebar does not enter compact mode unnecessarily.");
            }
        }
        fonts.forEach(Component::setFont);
        sized(workspace,1280,700);
    }
    public static void main(String[] args) throws Exception {
        Ui.install();
        Path directory=Files.createTempDirectory(Path.of("build"),"streamlining-smoke-");
        CountDownLatch closed=new CountDownLatch(1);
        StateStore store=new StateStore(directory);
        Workspace workspace=edt(() -> {
            Workspace w=new Workspace(store,Main.demoState(23456),Path.of("scripts","credential-store.ps1"),false);
            w.onClosed(closed::countDown); w.setFocusableWindowState(false); w.addNotify(); w.validate();
            return w;
        });
        System.out.println("Layout device scale: "+workspace.getGraphicsConfiguration().getDefaultTransform().getScaleX());
        try {
            edt(() -> {
                List<SessionPane> panes=workspace.sessionPanes();
                workspace.applyShortcuts(Map.of("Next pane","ctrl TAB","Previous pane","ctrl shift TAB"));
                JComboBox<?> preset=children(panes.getFirst()).stream().filter(c -> c instanceof JComboBox<?>)
                        .map(c -> (JComboBox<?>)c).findFirst().orElseThrow();
                check(dispatch(workspace,preset,KeyEvent.KEY_PRESSED,InputEvent.CTRL_DOWN_MASK,KeyEvent.VK_TAB)
                        && workspace.active()==panes.get(1),"Ctrl+Tab cycles even from an offline combo before Swing traversal.");
                check(dispatch(workspace,preset,KeyEvent.KEY_RELEASED,InputEvent.CTRL_DOWN_MASK,KeyEvent.VK_TAB),"Claimed shortcut release is consumed.");
                check(dispatch(workspace,panes.get(1).view(),KeyEvent.KEY_PRESSED,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK,KeyEvent.VK_TAB)
                        && workspace.active()==panes.getFirst(),"Ctrl+Shift+Tab cycles the terminal backward.");
                dispatch(workspace,panes.getFirst().view(),KeyEvent.KEY_RELEASED,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK,KeyEvent.VK_TAB);
                boolean rejected=false;
                try { workspace.applyShortcuts(Map.of("Next pane","ENTER")); }
                catch (IllegalArgumentException e) { rejected=e.getMessage().contains("host ENTER"); }
                check(rejected && workspace.shortcutOverrides().containsKey("Previous pane"),"Host-key collisions reject the entire edit, preserving previous shortcuts.");
                rejected=false;
                try { workspace.applyShortcuts(Map.of("Next pane","alt F4")); }
                catch (IllegalArgumentException e) { rejected=e.getMessage().contains("operating system"); }
                check(rejected,"Native Windows shortcut namespace cannot be stolen.");
                workspace.applyShortcuts(Map.of("Host F1","ctrl alt F1"));
                check(panes.getFirst().keys().lookup(new KeyEvent(panes.getFirst().view(),KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(),0,KeyEvent.VK_F1,KeyEvent.CHAR_UNDEFINED))==null,
                        "A global host-action override removes its original profile/default host binding.");
                workspace.applyShortcuts(Map.of("Next pane","ctrl TAB","Previous pane","ctrl shift TAB"));
                check(workspace.commands().stream().map(c -> c.title).distinct().count()==workspace.commands().size(),
                        "All shortcut actions have unique persistent identities.");
                for (var key:Keymap.hostActions()) check(workspace.commands().stream().anyMatch(c -> c.hostKey==key),
                        "Host action "+key+" is present in the global shortcut list.");
                SessionPane first=panes.getFirst();
                Object border=first.getBorder(); Insets insets=first.getInsets(); Rectangle bounds=first.getBounds();
                for (int i=0;i<30;i++) workspace.activate(panes.get(i%panes.size()));
                workspace.validate();
                check(first.getBorder()==border && first.getInsets().equals(insets) && first.getBounds().equals(bounds),
                        "Focus never replaces borders, changes insets or shifts pane geometry.");
                workspace.highlightActive(false);
                check(panes.stream().allMatch(p -> p.getBackground().equals(Ui.BACKGROUND)),"Outline-only preference removes selected-pane background tint.");
                JButton connect=children(first).stream().filter(c -> c instanceof JButton b &&
                        "Connect to selected preset".equals(b.getAccessibleContext().getAccessibleName())).map(c -> (JButton)c).findFirst().orElseThrow();
                Rectangle comboBounds=SwingUtilities.convertRectangle(preset.getParent(),preset.getBounds(),first);
                Rectangle buttonBounds=SwingUtilities.convertRectangle(connect.getParent(),connect.getBounds(),first);
                check(buttonBounds.x>comboBounds.x+comboBounds.width && Math.abs(buttonBounds.getCenterY()-comboBounds.getCenterY())<2
                        && (connect.getText()==null || connect.getText().isEmpty()),
                        "Offline Connect is a labeled-accessible icon directly right of the unselected-value combo: "+comboBounds+" / "+buttonBounds);
                Object chrome=field(workspace,"chrome"), nativeWindow=field(chrome,"nativeWindow");
                long handle=(long)field(nativeWindow,"handle");
                Method style=nativeWindow.getClass().getDeclaredMethod("style0",long.class); style.setAccessible(true);
                check(((long)style.invoke(null,handle)&0x00cf0000L)==0x00cf0000L,"Native caption, resize, system menu and maximize/minimize styles are enabled.");
                Method hit=nativeWindow.getClass().getDeclaredMethod("hitTest0",long.class,int.class,int.class); hit.setAccessible(true);
                check((int)hit.invoke(null,handle,1,1)==13,"Windows owns top-left resize hit testing.");
                Component maximize=children(workspace).stream().filter(c -> c instanceof JButton b
                        && "Maximize window".equals(b.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
                Rectangle maxBounds=SwingUtilities.convertRectangle(maximize.getParent(),maximize.getBounds(),workspace);
                double dpi=workspace.getGraphicsConfiguration().getDefaultTransform().getScaleX();
                check((int)hit.invoke(null,handle,(int)(maxBounds.getCenterX()*dpi),(int)(maxBounds.getCenterY()*dpi))==9,
                        "Custom maximize button returns native HTMAXBUTTON for Windows 11 Snap Layouts.");
                Component drag=children(workspace).stream().filter(c -> "window-drag-area".equals(c.getName())).findFirst().orElseThrow();
                Rectangle dragBounds=SwingUtilities.convertRectangle(drag.getParent(),drag.getBounds(),workspace);
                check((int)hit.invoke(null,handle,(int)(dragBounds.getCenterX()*dpi),(int)(dragBounds.getCenterY()*dpi))==2,
                        "Blank title area returns HTCAPTION for native drag/snap handling.");
                JButton logo=(JButton)field(chrome,"caption");
                check(logo.isFocusable() && logo.getAccessibleContext().getAccessibleName().contains("animated Flux"),
                        "Secret logo button is keyboard-accessible and names its effect action.");
                check(logo.getIcon().getIconWidth()==32 && logo.getIcon().getIconHeight()==24
                        && logo.getWidth()==48 && logo.getHeight()==30,
                        "Only the titlebar drawing shrinks, with uniform aspect and unchanged 48x30 keyboard/click target.");
                Container tools=(Container)field(chrome,"tools");
                check(Arrays.stream(tools.getComponents()).allMatch(c -> c instanceof JButton b && !b.isContentAreaFilled()),
                        "Pane/Commands toolbar actions rest on the chrome surface, without separate chip backgrounds.");
                Rectangle logoBounds=SwingUtilities.convertRectangle(logo.getParent(),logo.getBounds(),workspace);
                check((int)hit.invoke(null,handle,(int)(logoBounds.getCenterX()*dpi),(int)(logoBounds.getCenterY()*dpi))==1,
                        "Logo clicks belong to the client button, never native window dragging.");
                check(((Component)chrome).getHeight()==31 && maxBounds.height==30,
                        "Slightly taller chrome propagates actual layout height into native hit regions.");
                workspace.activate(first);
                workspace.putProfile(first.profile().withDisplay(first.profile().theme(),"None"));
                logo.doClick(0);
                check(first.profile().effect().equals(Profile.FLUX),"Keyboard/programmatic logo activation sets actual animated Flux.");
                logo.doClick(0);
                check(first.profile().effect().equals("None"),"Second logo activation returns None.");
                workspace.putProfile(first.profile().withDisplay(first.profile().theme(),"Prism"));
                logo.doClick(0);
                check(first.profile().effect().equals(Profile.FLUX),"Any non-Flux effect, including Prism, toggles to animated Flux.");
                workspace.activate(panes.get(1));
                workspace.putProfile(panes.get(1).profile().withDisplay(panes.get(1).profile().theme(),"None"));
                logo.doClick(0); logo.doClick(0);
                check(panes.get(1).profile().effect().equals("None") && first.profile().effect().equals(Profile.FLUX),
                        "Toggle targets the active preset without changing another pane's preset.");
                Method install=nativeWindow.getClass().getDeclaredMethod("install0",Window.class); install.setAccessible(true);
                check((long)install.invoke(null,workspace)==handle,"Attaching twice to the same HWND is idempotent.");
                workspace.removeNotify();
                check(field(chrome,"nativeWindow")==null,"Removing the peer releases the Java reference to its native handle.");
                workspace.addNotify(); workspace.validate();
                Object recreated=field(chrome,"nativeWindow");
                check(recreated!=nativeWindow && ((long)style.invoke(null,(long)field(recreated,"handle"))&0x00cf0000L)==0x00cf0000L,
                        "A recreated AWT peer receives a fresh native frame attachment with standard Windows styles.");
                Object menus=field(chrome,"menus");
                JMenu view=Arrays.stream(((JMenuBar)menus).getComponents()).filter(c -> c instanceof JMenu m && m.getText().equals("View"))
                        .map(c -> (JMenu)c).findFirst().orElseThrow();
                check(!(view.getMenuComponent(view.getMenuComponentCount()-1) instanceof JSeparator),"View menu has no trailing separator.");
                List<ManualDialog.Topic> topics=ManualDialog.topics();
                check(topics.size()>=25 && topics.getFirst().title().equals("Getting Started"),"Full bundled manual starts with absolute sign-on basics.");
                check(topics.stream().anyMatch(t -> t.matches("Ctrl Tab")) && topics.stream().anyMatch(t -> t.matches("installer administrator")),
                        "Full-text topic index finds shortcut and user-wide installation instructions.");
                allocationChecks(workspace,first,preset);
                ManualDialog manual=new ManualDialog(workspace); manual.addNotify(); manual.validate();
                capture(manual.getRootPane(),"fish-manual.png",1);
                manual.dispose();
                check(AppTheme.values().length>=8 && Profile.THEMES.size()>=12,"Application/host palettes include expanded OLED and high-contrast choices.");
                for (AppTheme theme:List.of(AppTheme.Charcoal,AppTheme.OLED,AppTheme.High_Contrast_Light)) {
                    workspace.applyTheme(theme); workspace.validate();
                    capture(workspace.getRootPane(),"fish-streamlining-"+theme.name()+".png",1.5);
                    if (theme==AppTheme.OLED) check(workspace.active().getBackground().getRGB()==Color.BLACK.getRGB(),"OLED pane is truly black.");
                }
                workspace.applyTheme(AppTheme.Charcoal);
                workspace.setSize(400,370); workspace.validate();
                check(!((JMenuBar)menus).isVisible(),"Narrow snapped windows expose the compact menu instead of overflowing chrome.");
                capture(workspace.getRootPane(),"fish-streamlining-narrow.png",1.5);
                check(!workspace.isVisible(),"All streamlining checks remain off the shared desktop.");
                return null;
            });
        } finally {
            edt(() -> { workspace.close(false); return null; });
            check(closed.await(5,TimeUnit.SECONDS),"Final state persists and releases the workspace lock.");
        }
        try (StateStore reopened=new StateStore(directory)) {
            StateStore.State saved=reopened.load();
            check(!saved.highlightActive() && saved.shortcuts().get("Next pane").equals("ctrl TAB"),
                    "Global shortcuts and outline-only preference survive restart in version-4 state.");
            check(saved.width()==400 && saved.height()==370,"Small snapped window sizes survive restart without obsolete minimum-size clamping.");
            check(saved.profiles().getFirst().effect().equals(Profile.FLUX),"Logo changes persist through the existing profile/state save path.");
        }
        try (var paths=Files.walk(directory)) {
            for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        System.out.println("PASS Streamlining: "+checks+" focused checks; native messages only, no foreground input.");
    }
}
