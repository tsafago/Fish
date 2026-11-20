package fish.ui;

import java.util.regex.Pattern;
import org.tn5250j.Session5250;
import org.tn5250j.framework.tn5250.ScreenOIA;
import static org.tn5250j.TN5250jConstants.*;

/** Deliberately English-only, exact-text recognizer; never logs or persists display data. */
final class StartupQueueNotice {
    private static final String NAME="[A-Z0-9_$#@]{1,10}";
    private static final String DATE="(?:[0-9]{1,4}[-/.][0-9]{1,2}[-/.][0-9]{1,4})";
    private static final String TIME="[0-9]{1,2}[:.][0-9]{2}[:.][0-9]{2}(?: [AP]M)?";
    private static final Pattern JOB=Pattern.compile("Job [0-9]{6}/("+NAME+")/"+NAME+
            " started on "+DATE+" at "+TIME+" in subsystem "+NAME+"\\.?");
    private static final String TITLE="Display Program Messages";

    private StartupQueueNotice() {}

    static String inputBlock(Session5250 core) {
        var oia=core.getScreen().getOIA();
        if (core.recoverableInputError()) return "terminal input error";
        if (oia.isKeysBuffered()) return "keys already buffered";
        if (oia.isKeyBoardLocked()) return "keyboard locked";
        if (oia.getInputInhibited()!=ScreenOIA.INPUTINHIBITED_NOTINHIBITED) return "host input inhibited";
        if (!core.getVT().waitingForInput()) return "host is not waiting for input";
        return null;
    }

    static String displayBlock(Session5250 core) {
        var screen=core.getScreen();
        int rows=screen.getRows(),cols=screen.getColumns(),size=rows*cols;
        if (!(rows==24 && cols==80 || rows==27 && cols==132)) return "unsupported display dimensions";
        if (screen.getScreenFields().hasUnsafeStartupNoticeFields()) return "editable or modified display fields";
        char[] text=new char[size+1],attr=new char[size+1],extended=new char[size+1],graphics=new char[size+1];
        screen.GetScreen(text,text.length,PLANE_TEXT);
        screen.GetScreen(attr,attr.length,PLANE_IS_ATTR_PLACE);
        screen.GetScreen(extended,extended.length,PLANE_EXTENDED);
        screen.GetScreen(graphics,graphics.length,PLANE_EXTENDED_GRAPHIC);
        int role=0,jobRow=-1,noticeRow=-1;
        String account=null;
        for (int row=0;row<rows;row++) {
            int offset=row*cols;
            for (int i=offset;i<offset+cols;i++) {
                if (graphics[i]!=0) return "display contains graphics";
                if (attr[i]!=0 || text[i]==0) text[i]=' ';
                if (text[i]!=' ' && ((extended[i]&EXTENDED_5250_NON_DSP)!=0
                        || text[i]<0x20 || text[i]>0x7e)) return "hidden or unsupported display data";
            }
            String raw=new String(text,offset,cols);
            if (raw.isBlank()) continue;
            String line=raw.strip().replaceAll(" +"," ");
            int col=raw.indexOf(line.charAt(0));
            switch (role++) {
                case 0 -> {
                    if (row>2 || !line.equals(TITLE) || Math.abs(col-(cols-TITLE.length())/2)>5) return "notice title or position did not match";
                }
                case 1 -> {
                    var job=JOB.matcher(line);
                    if (row>rows-7 || col>9 || !job.matches()) return "job-start line or position did not match";
                    account=job.group(1); jobRow=row;
                }
                case 2 -> {
                    if (row-jobRow>3 || col>9 || !line.equals("Message queue "+account+" is allocated to another job."))
                        return "queue notice line or position did not match";
                    noticeRow=row;
                }
                case 3 -> {
                    if (row-noticeRow<2 || row<rows-8 || row>rows-5
                            || col>9 || !line.equals("Press Enter to continue.")) return "Enter prompt or position did not match";
                }
                case 4 -> {
                    if (row<rows-3 || col>9 || !line.equals("F3=Exit F12=Cancel")) return "key footer or position did not match";
                }
                default -> { return "extra display content"; }
            }
        }
        return role==5 ? null : "notice display is incomplete";
    }

    static boolean matches(Session5250 core) {
        return inputBlock(core)==null && displayBlock(core)==null;
    }
}
