package fish.ui;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** Bundled offline manual with full-text topic index, search clear button, and live highlighting. */
public final class ManualDialog {
    public record Topic(String title,String anchor,String text) {
        @Override public String toString() { return title; }
        public boolean matches(String query) {
            if (query==null || query.isBlank()) return true;
            String content=(title+" "+text).toLowerCase(Locale.ROOT);
            return Arrays.stream(query.toLowerCase(Locale.ROOT).strip().split("\\s+")).allMatch(content::contains);
        }
    }

    private final Stage stage;
    private final List<Topic> topics;
    private final ObservableList<Topic> index=FXCollections.observableArrayList();
    private final ListView<Topic> list=new ListView<>(index);
    private final TextField find=new TextField();
    private final MarkdownView page;
    private final Label results=new Label();
    private boolean filtering;
    private final String documentTitle;
    private final Runnable themeHook=this::themeChanged;

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
        documentTitle=title;
        topics=topics(resource);
        stage=Ui.dialogStage(owner==null ? null : owner.stage(),title,false);
        page=new MarkdownView(title);

        find.setAccessibleText("Find in "+title);
        HBox searchInput=Ui.searchBox(find,"Search "+title.toLowerCase(Locale.ROOT)+"...");
        HBox.setHgrow(searchInput,Priority.ALWAYS);
        Ui.role(results,"muted");
        Label findLabel=new Label("Find");
        findLabel.setLabelFor(find);
        HBox searchBar=new HBox(10,findLabel,searchInput,results);
        searchBar.setAlignment(Pos.CENTER_LEFT);

        list.getStyleClass().add("fish-sidebar");
        list.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        list.setAccessibleText("Manual topic index");
        list.setPrefWidth(240);
        list.getSelectionModel().selectedItemProperty().addListener((obs,oldV,newV) -> {
            if (!filtering && newV!=null) page.scrollToAnchor(newV.anchor());
        });

        find.textProperty().addListener((o,a,b) -> filter());

        SplitPane split=new SplitPane(list,page);
        split.setDividerPositions(0.26);

        BorderPane body=new BorderPane();
        body.setPadding(new Insets(14));
        body.setTop(searchBar);
        BorderPane.setMargin(searchBar,new Insets(0,0,12,0));
        body.setCenter(split);

        Ui.decorateDialog(stage,body,this::dispose,960,700);
        stage.setMinWidth(560);
        stage.setMinHeight(380);
        stage.getScene().addEventFilter(KeyEvent.KEY_PRESSED,e -> {
            if (e.isControlDown() && e.getCode()==KeyCode.F) {
                find.requestFocus();
                find.selectAll();
                e.consume();
            }
        });
        Ui.onThemeChanged(themeHook);
        filter();
        stage.setOnShown(e -> find.requestFocus());
        stage.setOnHidden(e -> {
            Ui.removeThemeListener(themeHook);
            if (owner!=null && owner.isShowing() && owner.active()!=null) owner.active().focusTerminal();
        });
    }

    public Stage stage() { return stage; }
    public TextField searchField() { return find; }
    public ListView<Topic> topicList() { return list; }
    public MarkdownView markdownView() { return page; }

    public void setVisible(boolean visible) {
        if (visible) { stage.show(); stage.toFront(); }
        else dispose();
    }

    public void dispose() {
        Ui.removeThemeListener(themeHook);
        stage.close();
    }

    public void themeChanged() {
        page.themeChanged();
        filter();
    }

    private void filter() {
        filtering=true;
        List<Topic> matched=topics.stream().filter(t -> t.matches(find.getText())).toList();
        index.setAll(matched);
        results.setText(index.isEmpty() ? "No matching topics" : index.size()+" topics");
        page.setSections(documentTitle,matched.isEmpty() ? topics : matched,find.getText());
        if (!index.isEmpty()) list.getSelectionModel().select(0);
        filtering=false;
    }
}
