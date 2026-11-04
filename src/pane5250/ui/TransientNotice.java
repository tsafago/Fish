package pane5250.ui;

import java.awt.*;
import javax.swing.*;
import pane5250.model.ColorRole;

final class TransientNotice extends JPanel {
    static final int LIFETIME=2800;
    private final JLabel text=new JLabel();
    private final JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0));
    private final Timer timer=new Timer(LIFETIME,e -> dismiss());
    private final JButton close;
    TransientNotice(Runnable returnFocus) {
        super(new BorderLayout(8,0));
        setBorder(BorderFactory.createCompoundBorder(Hairline.perimeter(),BorderFactory.createEmptyBorder(4,8,4,4)));
        text.putClientProperty("html.disable",Boolean.TRUE);
        Ui.background(this,ColorRole.FOOTER); Ui.foreground(text,ColorRole.FOOTER_TEXT);
        actions.setOpaque(false);
        close=Ui.iconButton("Dismiss notice","close",() -> {
            boolean focused=closeFocused();
            dismiss();
            if (focused) returnFocus.run();
        });
        actions.add(close); add(text,BorderLayout.CENTER); add(actions,BorderLayout.EAST);
        timer.setRepeats(false); setVisible(false);
    }
    private boolean closeFocused() { return close.isFocusOwner(); }
    void action(String title,Runnable run) {
        JButton button=Ui.button(title,0,run);
        button.setBorder(BorderFactory.createEmptyBorder(3,6,3,6));
        actions.add(button,actions.getComponentCount()-1);
    }
    void showMessage(String value,boolean transientMessage) {
        text.setText(value); text.setToolTipText(value);
        setVisible(true); timer.stop();
        if (transientMessage) timer.restart();
        revalidate(); repaint();
    }
    void dismiss() { timer.stop(); setVisible(false); }
    void dispose() { timer.stop(); }
}
