package pane5250.ui;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

/** Bundled, offline manual with a full-text topic index and document highlights. */
public final class ManualDialog extends JDialog {
    public record Topic(String title,String anchor,String text) {
        @Override public String toString() { return title; }
        public boolean matches(String query) {
            String content=(title+" "+text).toLowerCase(Locale.ROOT);
            return Arrays.stream(query.toLowerCase(Locale.ROOT).strip().split("\\s+")).allMatch(content::contains);
        }
    }
    private final List<Topic> topics;
    private final DefaultListModel<Topic> index=new DefaultListModel<>();
    private final JList<Topic> list=new JList<>(index);
    private final JTextField find=new JTextField();
    private final JEditorPane page=new JEditorPane();
    private final JLabel results=new JLabel();
    private boolean filtering;
    private final String documentTitle;

    public static List<Topic> topics() throws IOException {
        return topics("/MANUAL.md");
    }
    private static List<Topic> topics(String resource) throws IOException {
        String text;
        try (InputStream input=ManualDialog.class.getResourceAsStream(resource)) {
            if (input==null) throw new IOException(resource+" is missing from this application package.");
            text=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        List<Topic> result=new ArrayList<>();
        for (String section:text.split("(?m)^## ")) {
            if (section.startsWith("# ")) continue;
            int newline=section.indexOf('\n'); if (newline<0) continue;
            String title=section.substring(0,newline).strip();
            result.add(new Topic(title,"topic-"+result.size(),section.substring(newline+1).strip()));
        }
        if (result.isEmpty() || resource.equals("/MANUAL.md") && !result.getFirst().title().equals("Getting Started"))
            throw new IOException("Document index is missing its opening topic.");
        return List.copyOf(result);
    }
    public ManualDialog(Workspace owner) throws IOException {
        this(owner,"/MANUAL.md","Fish manual");
    }
    public ManualDialog(Workspace owner,String resource,String title) throws IOException {
        super(owner,title,false);
        documentTitle=title;
        topics=topics(resource);
        find.getAccessibleContext().setAccessibleName("Find in "+title);
        JLabel label=new JLabel("Find"); label.setLabelFor(find);
        JPanel search=new JPanel(new BorderLayout(10,0));
        search.add(label,BorderLayout.WEST); search.add(find,BorderLayout.CENTER); search.add(results,BorderLayout.EAST);
        page.setContentType("text/html"); page.setEditable(false);
        page.getAccessibleContext().setAccessibleName(title);
        page.setText(html()); page.setCaretPosition(0);
        page.addHyperlinkListener(e -> {
            if (e.getEventType()==HyperlinkEvent.EventType.ACTIVATED && e.getDescription().startsWith("#"))
                page.scrollToReference(e.getDescription().substring(1));
        });
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(28); list.getAccessibleContext().setAccessibleName("Manual topic index");
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> owner,Object value,int row,boolean selected,boolean focus) {
                super.getListCellRendererComponent(owner,value,row,selected,focus);
                setToolTipText(value instanceof Topic topic ? topic.title() : null); return this;
            }
        });
        list.addListSelectionListener(e -> {
            if (!filtering && !e.getValueIsAdjusting() && list.getSelectedValue()!=null)
                page.scrollToReference(list.getSelectedValue().anchor());
        });
        find.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { filter(); }
            @Override public void removeUpdate(DocumentEvent e) { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { filter(); }
        });
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(list),new JScrollPane(page));
        split.setResizeWeight(.27); split.setDividerLocation(250); split.setBorder(BorderFactory.createEmptyBorder());
        JPanel body=new JPanel(new BorderLayout(0,12)); body.setBorder(Ui.padding(14));
        body.add(search,BorderLayout.NORTH); body.add(split,BorderLayout.CENTER);
        setContentPane(body); Ui.decorate(this,this::dispose);
        getRootPane().registerKeyboardAction(e -> find.requestFocusInWindow(),KeyStroke.getKeyStroke("ctrl F"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(980,710); setMinimumSize(new Dimension(560,380)); setLocationRelativeTo(owner);
        filter();
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { find.requestFocusInWindow(); }
            @Override public void windowClosed(WindowEvent e) {
                if (owner.isShowing() && owner.active()!=null) owner.active().focusTerminal();
            }
        });
    }
    private static String escape(String text) {
        return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }
    private String html() {
        StringBuilder html=new StringBuilder("<html><head><style>body {font-family:Segoe UI; font-size:12pt; margin:18px;"
                +"background:#"+hex(Ui.FIELD)+";color:#"+hex(Ui.TEXT)+";} h1 {font-size:21pt;} h2 {font-size:16pt;margin-top:24px;}"
                +"p {margin-top:9px;margin-bottom:12px;} a {color:#"+hex(Ui.FOCUS)+";}</style></head><body>"
                +"<h1>"+escape(documentTitle)+"</h1><p>"+pane5250.Main.VERSION+" experimental preview. Offline reference.</p>");
        for (Topic topic:topics) {
            html.append("<h2><a name=\"").append(topic.anchor()).append("\"></a>").append(escape(topic.title())).append("</h2>");
            for (String paragraph:topic.text().split("\\R\\s*\\R")) html.append("<p>").append(escape(paragraph).replace("\n","<br>")).append("</p>");
        }
        return html.append("</body></html>").toString();
    }
    private static String hex(Color color) { return String.format("%06x",color.getRGB()&0xffffff); }
    public void themeChanged() { page.setText(html()); filter(); }
    private void filter() {
        filtering=true;
        index.clear();
        for (Topic topic:topics) if (topic.matches(find.getText())) index.addElement(topic);
        results.setText(index.isEmpty() ? "No matching topics" : index.size()+" topics");
        if (!index.isEmpty()) list.setSelectedIndex(0);
        filtering=false;
        page.getHighlighter().removeAllHighlights();
        String query=find.getText().strip().toLowerCase(Locale.ROOT);
        if (!query.isEmpty()) {
            try {
                Document doc=page.getDocument();
                String text=doc.getText(0,doc.getLength()).toLowerCase(Locale.ROOT);
                for (String word:query.split("\\s+")) {
                    int from=0,count=0;
                    while ((from=text.indexOf(word,from))>=0 && count++<500) {
                        page.getHighlighter().addHighlight(from,from+word.length(),
                                new DefaultHighlighter.DefaultHighlightPainter(Ui.SELECTION));
                        from+=word.length();
                    }
                }
            } catch (BadLocationException e) { throw new IllegalStateException("Manual search index lost its document positions.",e); }
        }
        if (!index.isEmpty()) SwingUtilities.invokeLater(() -> {
            if (list.getSelectedValue()!=null) page.scrollToReference(list.getSelectedValue().anchor());
        });
    }
}
