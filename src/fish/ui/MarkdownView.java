package fish.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.*;
import fish.model.ColorRole;

/** Safe native JavaFX offline Markdown renderer: zero WebKit bloat, no script/image execution, live theme colors. */
public final class MarkdownView extends ScrollPane {
    private static final Pattern ITEM=Pattern.compile("^\\s*(?:([-+*])|(\\d+)\\.)\\s+(.+)$");
    private final VBox container=new VBox(8);
    private final Map<String,Node> anchors=new LinkedHashMap<>();
    private String rawMarkdown="";
    private String htmlCache="";
    private String highlightQuery="";
    private String sectionDocTitle;
    private List<ManualDialog.Topic> sectionTopics;
    private Consumer<String> internalAnchorHandler=this::scrollToAnchor;

    public MarkdownView(String accessibleName) {
        getStyleClass().add("fish-markdown-view");
        setAccessibleText(accessibleName);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        container.setPadding(new Insets(14));
        setContent(container);
        WeakReference<MarkdownView> ref=new WeakReference<>(this);
        Ui.onThemeChanged(new Runnable() {
            @Override public void run() {
                MarkdownView view=ref.get();
                if (view==null) Ui.removeThemeListener(this);
                else view.themeChanged();
            }
        });
        sceneProperty().addListener((obs,oldS,newS) -> {
            if (newS!=null) themeChanged();
        });
        themeChanged();
    }

    public static boolean safeLink(URI uri) {
        return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost()!=null && uri.getRawUserInfo()==null;
    }

    public void setInternalAnchorHandler(Consumer<String> handler) {
        this.internalAnchorHandler=Objects.requireNonNull(handler);
    }

    public void markdown(String value) {
        if (value.length()>200_000) throw new IllegalArgumentException("Release notes exceed the display limit.");
        sectionDocTitle=null;
        sectionTopics=null;
        anchors.clear();
        rawMarkdown=value;
        htmlCache=Markdown.render(value);
        rebuild();
    }

    public String htmlSnapshot() {
        return htmlCache;
    }

    public String plainText() {
        StringBuilder sb=new StringBuilder();
        collectText(container,sb);
        return sb.toString();
    }

    private static void collectText(Node node,StringBuilder sb) {
        switch (node) {
            case Text t -> sb.append(t.getText());
            case Labeled l -> {
                if (l.getText()!=null) sb.append(l.getText());
                if (!(l instanceof Hyperlink)) sb.append('\n');
            }
            case javafx.scene.Parent p -> {
                for (Node child:p.getChildrenUnmodifiable()) collectText(child,sb);
                if (p instanceof TextFlow) sb.append('\n');
            }
            default -> {}
        }
    }

    public void highlight(String query) {
        this.highlightQuery=query==null ? "" : query.strip().toLowerCase(Locale.ROOT);
        rebuild();
    }

    public void registerAnchor(String name,Node target) {
        anchors.put(name,target);
    }

    public void scrollToAnchor(String anchor) {
        Node node=anchors.get(anchor);
        if (node==null) return;
        container.applyCss();
        container.layout();
        double totalH=Math.max(1,container.getHeight()-getViewportBounds().getHeight());
        double y=node.getBoundsInParent().getMinY();
        setVvalue(Math.max(0,Math.min(1,y/totalH)));
    }

    public void themeChanged() {
        double v=getVvalue();
        container.setStyle("-fx-background-color: "+Ui.hex(ColorRole.FIELD)+";");
        if (sectionDocTitle!=null && sectionTopics!=null) {
            renderSections(sectionDocTitle,sectionTopics);
        } else {
            rebuild();
        }
        setVvalue(v);
    }

    public VBox contentBox() { return container; }

    public void setSections(String docTitle,List<ManualDialog.Topic> topics,String query) {
        this.highlightQuery=query==null ? "" : query.strip().toLowerCase(Locale.ROOT);
        this.sectionDocTitle=docTitle;
        this.sectionTopics=List.copyOf(topics);
        StringBuilder fullMd=new StringBuilder("# "+docTitle+"\n\n");
        for (ManualDialog.Topic t:topics) fullMd.append("## ").append(t.title()).append("\n").append(t.text()).append("\n\n");
        this.rawMarkdown=fullMd.toString();
        this.htmlCache=Markdown.render(rawMarkdown);
        renderSections(docTitle,this.sectionTopics);
    }

    private void renderSections(String docTitle,List<ManualDialog.Topic> topics) {
        container.getChildren().clear();
        anchors.clear();

        Label h1=new Label(docTitle);
        h1.setWrapText(true);
        h1.setStyle("-fx-font-size: 20px; -fx-text-fill: "+Ui.hex(ColorRole.TEXT)+";");
        container.getChildren().add(h1);

        for (ManualDialog.Topic topic:topics) {
            Label h2=new Label(topic.title());
            h2.setWrapText(true);
            h2.setPadding(new Insets(12,0,2,0));
            h2.setStyle("-fx-font-size: 15.5px; -fx-text-fill: "+Ui.hex(ColorRole.TEXT)+";");
            container.getChildren().add(h2);
            anchors.put(topic.anchor(),h2);
            anchors.put(topic.title().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 -]","").replace(' ','-'),h2);
            renderBlocksInto(topic.text(),container);
        }
    }

    private void rebuild() {
        if (sectionDocTitle!=null && sectionTopics!=null) {
            renderSections(sectionDocTitle,sectionTopics);
            return;
        }
        container.getChildren().clear();
        renderBlocksInto(rawMarkdown,container);
        setVvalue(0);
    }

    private void renderBlocksInto(String text,VBox out) {
        String[] lines=text.split("\\R",-1);
        StringBuilder paragraph=new StringBuilder();
        List<String> listItems=new ArrayList<>();
        boolean[] orderedList={false};
        String fence="";
        StringBuilder codeBuf=new StringBuilder();

        Runnable flushParagraph=() -> {
            if (!paragraph.isEmpty()) {
                out.getChildren().add(buildInlineFlow(paragraph.toString(),12.5));
                paragraph.setLength(0);
            }
        };
        Runnable flushList=() -> {
            if (!listItems.isEmpty()) {
                VBox listBox=new VBox(4);
                listBox.setPadding(new Insets(0,0,4,12));
                for (int idx=0;idx<listItems.size();idx++) {
                    String bullet=orderedList[0] ? (idx+1)+". " : "\u2022 ";
                    Label marker=new Label(bullet);
                    marker.setMinWidth(Region.USE_PREF_SIZE);
                    marker.setStyle("-fx-text-fill: "+Ui.hex(ColorRole.MUTED)+";");
                    TextFlow itemFlow=buildInlineFlow(listItems.get(idx),12.5);
                    HBox.setHgrow(itemFlow,Priority.ALWAYS);
                    HBox row=new HBox(4,marker,itemFlow);
                    listBox.getChildren().add(row);
                }
                out.getChildren().add(listBox);
                listItems.clear();
            }
        };

        for (int i=0;i<lines.length;i++) {
            String raw=lines[i],line=raw.strip();
            if (!fence.isEmpty()) {
                if (line.equals(fence)) {
                    Label code=new Label(codeBuf.toString().stripTrailing());
                    code.setWrapText(true);
                    code.setMaxWidth(Double.MAX_VALUE);
                    code.setPadding(new Insets(8,10,8,10));
                    code.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11.5px; -fx-background-color: "
                            +Ui.hex(ColorRole.PANEL)+"; -fx-border-color: "+Ui.hex(ColorRole.DIVIDER)+"; -fx-border-width: 1; -fx-text-fill: "+Ui.hex(ColorRole.TEXT)+";");
                    out.getChildren().add(code);
                    codeBuf.setLength(0);
                    fence="";
                } else {
                    codeBuf.append(raw).append('\n');
                }
                continue;
            }
            if (line.startsWith("```") || line.startsWith("~~~")) {
                flushParagraph.run(); flushList.run();
                fence=line.substring(0,3);
                continue;
            }
            if (line.isEmpty()) {
                flushParagraph.run(); flushList.run();
                continue;
            }
            if (i+1<lines.length && lines[i+1].replace(" ","").matches("\\|?:?-+:?(\\|:?-+:?)+\\|?")) {
                flushParagraph.run(); flushList.run();
                String px=Ui.px(Ui.hairline(Ui.scaleFor(this)));
                GridPane grid=new GridPane();
                grid.setMaxWidth(Double.MAX_VALUE);
                grid.setStyle("-fx-border-color: "+Ui.hex(ColorRole.DIVIDER)+"; -fx-border-width: "+px+" 0 0 "+px+";");
                List<String> headers=cells(line);
                List<List<String>> rows=new ArrayList<>();
                i++;
                while (i+1<lines.length && lines[i+1].strip().startsWith("|")) {
                    rows.add(cells(lines[++i]));
                }
                int colCount=headers.size();
                for (List<String> r:rows) colCount=Math.max(colCount,r.size());
                int[] maxChars=new int[colCount];
                for (int c=0;c<headers.size();c++) maxChars[c]=Math.max(maxChars[c],visibleLength(headers.get(c)));
                for (List<String> r:rows) {
                    for (int c=0;c<r.size();c++) maxChars[c]=Math.max(maxChars[c],visibleLength(r.get(c)));
                }
                for (int c=0;c<colCount;c++) {
                    ColumnConstraints cc=new ColumnConstraints();
                    cc.setFillWidth(true);
                    if (colCount==1) {
                        cc.setHgrow(Priority.ALWAYS);
                    } else if (c<colCount-1) {
                        double w=colCount==2
                                ? Math.clamp(maxChars[c]*7.5+24.0,112.0,236.0)
                                : Math.clamp(maxChars[c]*7.0+20.0,88.0,178.0);
                        cc.setMinWidth(w);
                        cc.setPrefWidth(w);
                        cc.setHgrow(Priority.NEVER);
                    } else {
                        cc.setMinWidth(120.0);
                        cc.setHgrow(Priority.ALWAYS);
                    }
                    grid.getColumnConstraints().add(cc);
                }
                for (int c=0;c<colCount;c++) {
                    String hText=c<headers.size() ? headers.get(c) : "";
                    grid.add(tableCell(hText,true,px),c,0);
                }
                for (int rIdx=0;rIdx<rows.size();rIdx++) {
                    List<String> rowCells=rows.get(rIdx);
                    for (int c=0;c<colCount;c++) {
                        String cText=c<rowCells.size() ? rowCells.get(c) : "";
                        grid.add(tableCell(cText,false,px),c,rIdx+1);
                    }
                }
                out.getChildren().add(grid);
                continue;
            }
            Matcher m=ITEM.matcher(raw);
            if (m.matches()) {
                flushParagraph.run();
                boolean nextOrdered=m.group(1)==null;
                if (!listItems.isEmpty() && orderedList[0]!=nextOrdered) flushList.run();
                orderedList[0]=nextOrdered;
                listItems.add(m.group(3));
                continue;
            }
            if (!listItems.isEmpty() && raw.startsWith("  ")) {
                int last=listItems.size()-1;
                listItems.set(last,listItems.get(last)+" "+line);
                continue;
            }
            flushList.run();
            if (line.matches("#{1,6} .+")) {
                flushParagraph.run();
                int level=line.indexOf(' ');
                double sz=switch (level) { case 1 -> 18.0; case 2 -> 15.5; default -> 13.5; };
                TextFlow heading=buildInlineFlow(line.substring(level+1),sz);
                heading.setPadding(new Insets(level<=2 ? 10 : 6,0,2,0));
                for (Node n:heading.getChildren()) if (n instanceof Text t) t.setStyle(t.getStyle()+" -fx-font-weight: bold;");
                out.getChildren().add(heading);
                continue;
            }
            if (!paragraph.isEmpty()) paragraph.append(' ');
            paragraph.append(line);
        }
        flushParagraph.run();
        flushList.run();
    }

    private static int visibleLength(String markdownCell) {
        if (markdownCell==null || markdownCell.isEmpty()) return 0;
        String stripped=markdownCell
                .replaceAll("!?\\[([^\\]]*)]\\([^)]*\\)","$1")
                .replace("`","")
                .replace("**","")
                .replace("*","");
        return stripped.strip().length();
    }

    private StackPane tableCell(String content,boolean header,String px) {
        TextFlow flow=buildInlineFlow(content,12);
        if (header) for (Node n:flow.getChildren()) if (n instanceof Text t) t.setStyle(t.getStyle()+" -fx-font-weight: bold;");
        StackPane box=new StackPane(flow);
        StackPane.setAlignment(flow,Pos.TOP_LEFT);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setPadding(new Insets(5,8,5,8));
        box.setStyle("-fx-background-color: "+(header ? Ui.hex(ColorRole.PANEL) : Ui.hex(ColorRole.FIELD))
                +"; -fx-border-color: "+Ui.hex(ColorRole.DIVIDER)+"; -fx-border-width: 0 "+px+" "+px+" 0;");
        return box;
    }

    private TextFlow buildInlineFlow(String text,double fontSize) {
        TextFlow flow=new TextFlow();
        flow.setLineSpacing(2);
        parseInlineNodes(text,fontSize,false,false,flow.getChildren());
        return flow;
    }

    private void parseInlineNodes(String text,double fontSize,boolean bold,boolean italic,List<Node> out) {
        StringBuilder plain=new StringBuilder();
        Runnable flushPlain=() -> {
            if (!plain.isEmpty()) {
                appendHighlightedText(plain.toString(),fontSize,bold,italic,false,out);
                plain.setLength(0);
            }
        };
        for (int i=0;i<text.length();) {
            char c=text.charAt(i);
            if (c=='!' && i+1<text.length() && text.charAt(i+1)=='[') {
                int label=text.indexOf("](",i+2),end=label<0 ? -1 : text.indexOf(')',label+2);
                if (end>label) {
                    plain.append(text,i+2,label);
                    i=end+1;
                    continue;
                }
            }
            if (c=='`') {
                int end=text.indexOf('`',i+1);
                if (end>i+1) {
                    flushPlain.run();
                    appendHighlightedText(text.substring(i+1,end),fontSize-1,bold,italic,true,out);
                    i=end+1;
                    continue;
                }
            }
            String marker=text.startsWith("**",i) ? "**" : c=='*' ? "*" : "";
            if (!marker.isEmpty()) {
                int end=text.indexOf(marker,i+marker.length());
                if (end>i+marker.length()) {
                    flushPlain.run();
                    parseInlineNodes(text.substring(i+marker.length(),end),fontSize,
                            bold || marker.length()==2,italic || marker.length()==1,out);
                    i=end+marker.length();
                    continue;
                }
            }
            if (c=='[') {
                int label=text.indexOf("](",i+1),end=label<0 ? -1 : text.indexOf(')',label+2);
                if (end>label) {
                    String href=text.substring(label+2,end);
                    String title=text.substring(i+1,label);
                    if (href.startsWith("#") || href.startsWith("https://") || href.startsWith("http://")) {
                        flushPlain.run();
                        Hyperlink link=new Hyperlink(title);
                        link.setStyle("-fx-font-size: "+fontSize+"px; -fx-text-fill: "+Ui.hex(ColorRole.FOCUS)+";");
                        link.setOnAction(e -> openHref(href));
                        out.add(link);
                    } else {
                        plain.append(title);
                    }
                    i=end+1;
                    continue;
                }
            }
            plain.append(c);
            i++;
        }
        flushPlain.run();
    }

    private void appendHighlightedText(String content,double fontSize,boolean bold,boolean italic,boolean mono,List<Node> out) {
        if (highlightQuery.isEmpty()) {
            out.add(styledText(content,fontSize,bold,italic,mono,false));
            return;
        }
        String[] words=Arrays.stream(highlightQuery.split("\\s+")).filter(w -> !w.isEmpty()).toArray(String[]::new);
        if (words.length==0) {
            out.add(styledText(content,fontSize,bold,italic,mono,false));
            return;
        }
        String lower=content.toLowerCase(Locale.ROOT);
        int idx=0;
        while (idx<content.length()) {
            int bestPos=-1,bestLen=0;
            for (String w:words) {
                int p=lower.indexOf(w,idx);
                if (p>=0 && (bestPos<0 || p<bestPos || (p==bestPos && w.length()>bestLen))) {
                    bestPos=p; bestLen=w.length();
                }
            }
            if (bestPos<0) {
                out.add(styledText(content.substring(idx),fontSize,bold,italic,mono,false));
                break;
            }
            if (bestPos>idx) out.add(styledText(content.substring(idx,bestPos),fontSize,bold,italic,mono,false));
            out.add(styledText(content.substring(bestPos,bestPos+bestLen),fontSize,bold,italic,mono,true));
            idx=bestPos+bestLen;
        }
    }

    private Text styledText(String value,double fontSize,boolean bold,boolean italic,boolean mono,boolean highlighted) {
        Text t=new Text(value);
        StringBuilder style=new StringBuilder();
        style.append("-fx-font-family: ").append(mono ? "'Consolas'" : "'Segoe UI'").append("; ");
        style.append("-fx-font-size: ").append(fontSize).append("px; ");
        if (bold) style.append("-fx-font-weight: bold; ");
        if (italic) style.append("-fx-font-style: italic; ");
        style.append("-fx-fill: ").append(Ui.hex(highlighted ? ColorRole.FOCUS : ColorRole.TEXT)).append(";");
        if (highlighted) style.append(" -fx-underline: true; -fx-font-weight: bold;");
        t.setStyle(style.toString());
        return t;
    }

    private void openHref(String href) {
        if (href.startsWith("#")) {
            internalAnchorHandler.accept(href.substring(1));
            return;
        }
        try {
            URI uri=URI.create(href);
            if (!safeLink(uri)) throw new IllegalArgumentException("Only explicit HTTP or HTTPS links are supported.");
            Desktop.getDesktop().browse(uri);
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException e) {
            Ui.error(getScene()==null ? null : getScene().getWindow(),"Could not open this link: "+e.getMessage());
        }
    }

    private static List<String> cells(String row) {
        List<String> cells=new ArrayList<>(); StringBuilder cell=new StringBuilder(); boolean code=false;
        String text=row.strip();
        if (text.startsWith("|")) text=text.substring(1);
        if (text.endsWith("|")) text=text.substring(0,text.length()-1);
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (c=='\\' && i+1<text.length() && text.charAt(i+1)=='|') { cell.append('|'); i++; continue; }
            if (c=='`') code=!code;
            if (c=='|' && !code) { cells.add(cell.toString().strip()); cell.setLength(0); } else cell.append(c);
        }
        cells.add(cell.toString().strip());
        return cells;
    }
}
