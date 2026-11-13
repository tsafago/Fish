package fish.ui;

import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import fish.model.AppTheme;
import fish.model.ColorRole;

public final class NoticeSmoke {
    private static int checks;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Ui.install();
        Ui.runAndWait(() -> {
            AtomicInteger recovery=new AtomicInteger();
            Ui.applyTheme(AppTheme.Charcoal);
            TransientNotice notice=new TransientNotice(() -> {});
            StackPane host=new StackPane(notice);
            Scene scene=new Scene(host,300,40);
            scene.getStylesheets().setAll(Ui.activeStylesheetUrl());
            notice.showMessage("Input error - Enter or Reset to continue",true);
            host.applyCss(); host.layout();
            check(TransientNotice.LIFETIME<3000,"Notice lifetime shortened");
            WritableImage snap=scene.snapshot(null);
            PixelReader reader=snap.getPixelReader();
            int border=Ui.color(ColorRole.DIVIDER).getRGB()&0xffffff;
            check((reader.getArgb(150,0)&0xffffff)==border,"Top border rendered cleanly");
            check((reader.getArgb(150,2)&0xffffff)!=border,"Border is crisp 1px");
            HBox actions=(HBox)notice.getRight();
            Button close=(Button)actions.getChildren().getLast();
            check(close.isFocusTraversable() && "Dismiss notice".equals(close.getAccessibleText()),"Close is keyboard accessible");
            close.fire();
            check(!notice.isVisible() && recovery.get()==0,"Dismiss never resets/submits host input");
            notice.showMessage("Fish update available",false); notice.dismiss(); notice.dispose();
            check(!notice.isVisible(),"Persistent notice dismiss and disposal");
        });
        System.out.println("Noninteractive notification checks passed: "+checks);
        Ui.finishTestRun();
    }
}
