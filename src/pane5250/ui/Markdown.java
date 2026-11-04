package pane5250.ui;

import java.util.*;
import java.util.regex.*;

/** Offline document subset: all source HTML is escaped, with no image/resource loading. */
final class Markdown {
    private static final Pattern ITEM=Pattern.compile("^\\s*(?:([-+*])|\\d+\\.)\\s+(.+)$");
    private Markdown() {}
    static String escape(String text) {
        return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }
    static String inline(String text) {
        StringBuilder out=new StringBuilder();
        for (int i=0;i<text.length();) {
            char c=text.charAt(i);
            if (c=='`') {
                int end=text.indexOf('`',i+1);
                if (end>i+1) { out.append("<code>").append(escape(text.substring(i+1,end))).append("</code>"); i=end+1; continue; }
            }
            String marker=text.startsWith("**",i) ? "**" : c=='*' ? "*" : "";
            if (!marker.isEmpty()) {
                int end=text.indexOf(marker,i+marker.length());
                if (end>i+marker.length()) {
                    String tag=marker.length()==2 ? "strong" : "em";
                    out.append("<").append(tag).append(">").append(inline(text.substring(i+marker.length(),end)))
                            .append("</").append(tag).append(">"); i=end+marker.length(); continue;
                }
            }
            if (c=='[') {
                int label=text.indexOf("](",i+1),end=label<0 ? -1 : text.indexOf(')',label+2);
                if (end>label) {
                    String href=text.substring(label+2,end);
                    String title=escape(text.substring(i+1,label));
                    if (href.startsWith("#") || href.startsWith("https://") || href.startsWith("http://"))
                        out.append("<a href=\"").append(escape(href)).append("\">").append(title).append("</a>");
                    else out.append(title);
                    i=end+1; continue;
                }
            }
            out.append(escape(Character.toString(c))); i++;
        }
        return out.toString();
    }
    static String render(String text) {
        String[] lines=text.split("\\R",-1);
        StringBuilder out=new StringBuilder(),paragraph=new StringBuilder();
        String list="",fence="";
        for (int i=0;i<lines.length;i++) {
            String raw=lines[i],line=raw.strip();
            if (!fence.isEmpty()) {
                if (line.equals(fence)) { out.append("</code></pre>"); fence=""; }
                else out.append(escape(raw)).append('\n');
                continue;
            }
            if (line.startsWith("```") || line.startsWith("~~~")) {
                flush(out,paragraph); if (!list.isEmpty()) { out.append("</li></").append(list).append(">"); list=""; }
                fence=line.substring(0,3); out.append("<pre><code>"); continue;
            }
            if (line.isEmpty()) {
                flush(out,paragraph); if (!list.isEmpty()) { out.append("</li></").append(list).append(">"); list=""; }
                continue;
            }
            if (i+1<lines.length && lines[i+1].replace(" ","").matches("\\|?:?-+:?(\\|:?-+:?)+\\|?")) {
                flush(out,paragraph); if (!list.isEmpty()) { out.append("</li></").append(list).append(">"); list=""; }
                out.append("<table cellspacing=\"0\" cellpadding=\"6\"><tr>");
                for (String cell:cells(line)) out.append("<th>").append(inline(cell)).append("</th>");
                out.append("</tr>"); i++;
                while (i+1<lines.length && lines[i+1].strip().startsWith("|")) {
                    out.append("<tr>");
                    for (String cell:cells(lines[++i])) out.append("<td>").append(inline(cell)).append("</td>");
                    out.append("</tr>");
                }
                out.append("</table>"); continue;
            }
            Matcher item=ITEM.matcher(raw);
            if (item.matches()) {
                flush(out,paragraph);
                String type=item.group(1)==null ? "ol" : "ul";
                if (type.equals(list)) out.append("</li>");
                else {
                    if (!list.isEmpty()) out.append("</li></").append(list).append(">");
                    list=type; out.append("<").append(list).append(">");
                }
                out.append("<li>").append(inline(item.group(2))); continue;
            }
            if (!list.isEmpty() && raw.startsWith("  ")) { out.append(' ').append(inline(line)); continue; }
            if (!list.isEmpty()) { out.append("</li></").append(list).append(">"); list=""; }
            if (line.matches("#{1,6} .+")) {
                flush(out,paragraph); int level=Math.max(3,line.indexOf(' '));
                out.append("<h").append(level).append(">").append(inline(line.substring(line.indexOf(' ')+1)))
                        .append("</h").append(level).append(">"); continue;
            }
            if (!paragraph.isEmpty()) paragraph.append(' ');
            paragraph.append(line);
        }
        flush(out,paragraph);
        if (!list.isEmpty()) out.append("</li></").append(list).append(">");
        if (!fence.isEmpty()) out.append("</code></pre>");
        return out.toString();
    }
    private static void flush(StringBuilder out,StringBuilder paragraph) {
        if (!paragraph.isEmpty()) { out.append("<p>").append(inline(paragraph.toString())).append("</p>"); paragraph.setLength(0); }
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
        cells.add(cell.toString().strip()); return cells;
    }
}
