package pane5250.demo;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/** Synthetic host datastreams, never screenshots painted into the terminal renderer. */
public final class DemoScreens {
    private static final Charset EBCDIC = Charset.forName("Cp037");
    private DemoScreens() {}

    public static byte[] screen(int kind, boolean wide, String result) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        command(out,wide ? new int[]{4,0x20,0} : new int[]{4,0x40});
        command(out,4,0x11,0,0x08);
        at(out,1,2,0x22,kind == 1 ? "MAIN" : kind == 2 ? "CUS001" : "ORD100");
        at(out,1,31,0x22,kind == 1 ? "Company Systems" : kind == 2 ? "Customer File Inquiry" : "Order Entry");
        if (kind == 1) {
            at(out,3,33,0x22,"Main Menu");
            at(out,5,2,0x22,"Select one of the following:");
            String[] items = {"Customer File Maintenance","Order Entry","Inventory Inquiry","Accounts Receivable",
                    "Accounts Payable","General Ledger","System Utilities","Sign Off"};
            for (int i = 0; i < items.length; i++) at(out,7 + i,9,0x20,(i + 1) + "   " + items[i]);
            at(out,18,2,0x22,"Selection  ===>");
            field(out,18,18,10,0x40,0x24);
        } else if (kind == 2) {
            at(out,3,2,0x22,"Type options, press Enter.");
            at(out,4,2,0x22,"2=Change   4=Delete   5=Display");
            at(out,6,2,0x26,"Opt  Customer  Name                       City            State  Zip");
            String[] names = {"ACME INDUSTRIES","BAKER SUPPLY CO","CARTER MANUFACTURING","DELTA WHOLESALE",
                    "ELLIS DISTRIBUTION","FOSTER CHEMICAL","GARNER FOODS","HAMILTON HARDWARE"};
            String[] cities = {"DALLAS","HOUSTON","AUSTIN","SAN ANTONIO","FORT WORTH","PLANO","LUBBOCK","CORPUS CHRISTI"};
            for (int i = 0; i < names.length; i++) {
                at(out,8 + i,7,0x20,Integer.toString(100100 + 100 * i));
                at(out,8 + i,17,0x20,names[i]);
                at(out,8 + i,44,0x20,cities[i]);
                at(out,8 + i,63,0x20,"TX");
                at(out,8 + i,69,0x20,Integer.toString(75201 + i * 111));
                field(out,8 + i,2,2,0x40,0x24);
            }
            at(out,17,67,0x22,"More...");
        } else {
            at(out,4,2,0x22,"Order No . . . . :");
            at(out,4,24,0x20,"000123");
            at(out,5,2,0x22,"Customer . . . . :");
            at(out,5,24,0x20,"100100");
            at(out,5,34,0x22,"ACME INDUSTRIES");
            at(out,8,2,0x26,"Line  Item       Description              Qty    Unit Price     Amount");
            at(out,10,2,0x20,"001   A100       WIDGET A                  10         12.50     125.00");
            at(out,11,2,0x20,"002   B200       WIDGET B                   5         25.00     125.00");
            at(out,12,2,0x20,"003   C300       WIDGET C                   2        100.00     200.00");
            at(out,15,49,0x22,"Total . . . . :");
            at(out,15,65,0x20,"450.00");
            at(out,19,2,0x22,"Command ===>");
            field(out,19,16,26,0x40,0x24);
        }
        if (wide) at(out,21,90,0x32,"132-column host area");
        at(out,22,2,0x20,result.isBlank() ? "F5=27x132  F6=24x80  Enter=Send fields" : result.substring(0,Math.min(result.length(),73)));
        at(out,24,2,0x22,"LOCAL SYNTHETIC HOST - not an IBM i / ACS acceptance session");
        command(out,4,0x42,0,0);
        return gds(3,out.toByteArray());
    }
    public static byte[] gds(int opcode, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int length = payload.length + 10;
        command(out,length >> 8,length & 255,0x12,0xa0,0,0,4,0,0,opcode);
        out.writeBytes(payload);
        return out.toByteArray();
    }
    public static void at(ByteArrayOutputStream out,int row,int col,int attr,String text) {
        command(out,0x11,row,col,attr);
        out.writeBytes(text.getBytes(EBCDIC));
    }
    public static void field(ByteArrayOutputStream out,int row,int col,int length,int ffw,int attr) {
        command(out,0x11,row,col,0x1d,ffw,0,attr,length >> 8,length & 255);
    }
    public static void command(ByteArrayOutputStream out,int... bytes) {
        for (int value : bytes) out.write(value);
    }
}
