package pane5250.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;

/** The manual's escaped Markdown subset, with no automatic resources or executable links. */
final class MarkdownView extends JEditorPane {
    private String markdown="";
    MarkdownView(String name) {
        setContentType("text/html"); setEditable(false);
        getAccessibleContext().setAccessibleName(name);
        addHyperlinkListener(event -> {
            if (event.getEventType()!=HyperlinkEvent.EventType.ACTIVATED) return;
            String link=event.getDescription();
            if (link.startsWith("#")) { scrollToReference(link.substring(1)); return; }
            try {
                URI uri=URI.create(link);
                if (!safeLink(uri)) throw new IllegalArgumentException("Only explicit HTTP or HTTPS links are supported.");
                Desktop.getDesktop().browse(uri);
            } catch (IOException | IllegalArgumentException | UnsupportedOperationException e) {
                Ui.error(this,"Could not open this link: "+e.getMessage());
            }
        });
    }
    static boolean safeLink(URI uri) {
        return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost()!=null && uri.getRawUserInfo()==null;
    }
    void markdown(String value) {
        if (value.length()>200_000) throw new IllegalArgumentException("Release notes exceed the display limit.");
        markdown=value; themeChanged();
    }
    void themeChanged() {
        String css="body{font-family:Segoe UI;font-size:12px;margin:12px;background:"+hex(Ui.FIELD)+";color:"+hex(Ui.TEXT)+";}"
                +"h1{font-size:19px;}h2{font-size:17px;}h3{font-size:14px;margin-top:18px;}"
                +"p{margin-bottom:10px;}li{margin-bottom:5px;}a{color:"+hex(Ui.FOCUS)+";}"
                +"code,pre{font-family:Consolas;font-size:11px;}pre{padding:8px;background:"+hex(Ui.PANEL)+";}";
        setText("<html><head><style>"+css+"</style></head><body>"+Markdown.render(markdown)+"</body></html>");
        setCaretPosition(0);
    }
    private static String hex(java.awt.Color color) { return String.format("#%06x",color.getRGB()&0xffffff); }
}
