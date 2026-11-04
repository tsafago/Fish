package pane5250.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import pane5250.model.ColorRole;

public final class NoticeSmoke {
    private static int checks;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger recovery=new AtomicInteger();
            Ui.applyTheme(pane5250.model.AppTheme.Charcoal);
            TransientNotice notice=new TransientNotice(() -> {});
            notice.showMessage("Input error - Enter or Reset to continue",true);
            notice.setSize(300,40); notice.doLayout();
            check(TransientNotice.LIFETIME<3000,"Notice lifetime shortened");
            for (double scale:new double[]{1,1.25,1.5,1.75,2}) {
                int width=(int)Math.ceil(300*scale),height=(int)Math.ceil(40*scale);
                BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
                Graphics2D g=image.createGraphics();
                try { g.scale(scale,scale); notice.paint(g); } finally { g.dispose(); }
                int border=Ui.color(ColorRole.DIVIDER).getRGB()&0xffffff;
                check((image.getRGB(width/2,0)&0xffffff)==border,"Top physical border at "+scale);
                check((image.getRGB(width/2,1)&0xffffff)!=border,"Border is not two pixels at "+scale);
            }
            JPanel actions=(JPanel)notice.getComponent(1);
            JButton close=(JButton)actions.getComponent(actions.getComponentCount()-1);
            check(close.isFocusable() && close.getAccessibleContext().getAccessibleName().equals("Dismiss notice"),"Close is keyboard accessible");
            close.doClick();
            check(!notice.isVisible() && recovery.get()==0,"Dismiss never resets/submits host input");
            notice.showMessage("Fish update available",false); notice.dismiss(); notice.dispose();
            check(!notice.isVisible(),"Persistent notice dismiss and disposal");
        });
        System.out.println("Noninteractive notification checks passed: "+checks);
    }
}
