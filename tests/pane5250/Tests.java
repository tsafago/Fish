package pane5250;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.tn5250j.Session5250;
import org.tn5250j.encoding.CharMappings;
import org.tn5250j.framework.tn5250.Screen5250;
import org.tn5250j.keyboard.KeyMnemonic;
import pane5250.demo.*;
import pane5250.model.*;
import pane5250.net.*;
import pane5250.security.*;
import pane5250.ui.*;
import static org.tn5250j.TN5250jConstants.*;

public final class Tests {
    private static int checks;
    private static volatile Throwable asynchronousFailure;
    // Hand-authored classic datastream: clear, WTD, white HELLO, 5-character input field, read.
    private static final byte[] CLASSIC = hex("002612a000000400000304400411000811010122c8c5d3d3d61102011d400024000504420000");
    private Tests() {}
    public static void main(String[] args) throws Exception {
        try {
            Thread.setDefaultUncaughtExceptionHandler((thread,error) -> {
                asynchronousFailure = error;
                error.printStackTrace();
            });
            Ui.install();
            codec();
            models();
            if (Arrays.asList(args).contains("--protocol-only")) {
                System.out.println("PASS - "+checks+" protocol/model fixtures; no credentials or desktop access.");
                return;
            }
            themesAndCommands();
            core();
            effectRendering();
            if (!Arrays.asList(args).contains("--core-only")) { credentials(); desktop(); }
            check(asynchronousFailure == null,"No uncaught EDT or background-thread errors.");
            System.out.println("PASS - " + checks + " checks"+(args.length>0 ? " (non-interactive fixtures)." : ", including credentials and live desktop."));
        } catch (Throwable e) {
            e.printStackTrace();
            for (Window window : Window.getWindows()) SwingUtilities.invokeAndWait(window::dispose);
            System.exit(1);
        }
    }
    private static void check(boolean condition,String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static byte[] hex(String text) { return HexFormat.of().parseHex(text.replace(" ","")); }
    private static byte[] concat(byte[]... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] value : values) out.writeBytes(value);
        return out.toByteArray();
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        try { return task.get(); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw (Error) e.getCause();
        }
    }
    private interface Checked { void run() throws Exception; }
    private static void fails(Checked action,String description) throws Exception {
        boolean failed = false;
        try { action.run(); }
        catch (IOException | IllegalArgumentException | IllegalStateException e) { failed = true; }
        check(failed,description);
    }
    private static byte[] readCodec(byte[] wire,ByteArrayOutputStream output,Map<String,String> env,List<String> notices) throws IOException {
        // A one-byte source stresses every possible segmentation of Telnet commands.
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(wire)) {
            @Override public int read(byte[] buffer,int offset,int length) throws IOException {
                return super.read(buffer,offset,Math.min(1,length));
            }
        };
        try (TelnetCodec codec = new TelnetCodec(fragmented,output,env,notices::add)) { return codec.readRecord(); }
    }
    private static void codec() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<String> notices = new ArrayList<>();
        byte[] wire = concat(hex("fffd18fffd18fffa1801fff0fffa1801fff0fffd00fffb00fffd19fffb19"),
                TelnetCodec.frame(CLASSIC));
        check(Arrays.equals(readCodec(wire,output,Map.of(),notices),CLASSIC),"Fragmented negotiation preserves exact GDS bytes.");
        String response = HexFormat.of().formatHex(output.toByteArray());
        check(response.indexOf("fffb18") == response.lastIndexOf("fffb18"),"Repeated DO does not loop WILL.");
        check(response.contains("fffa180049424d2d333437372d4643fff0"),"Only IBM-3477-FC is announced.");
        check(count(response,"fffa180049424d2d333437372d4643fff0") == 2,"Repeated terminal-type SEND gets a consistent reply.");
        check(response.contains("fffb00fffd00"),"Binary is negotiated in both directions.");
        check(response.contains("fffb19fffd19"),"EOR is negotiated in both directions.");
        byte[] literalFF = DemoScreens.gds(0,hex("ff"));
        output.reset();
        check(Arrays.equals(readCodec(TelnetCodec.frame(literalFF),output,Map.of(),notices),literalFF),"IAC byte stuffing is reversed before GDS length validation.");
        byte[] embedded = concat(Arrays.copyOf(CLASSIC,13),hex("fff1fffb01"),Arrays.copyOfRange(CLASSIC,13,CLASSIC.length),hex("ffef"));
        output.reset();
        check(Arrays.equals(readCodec(embedded,output,Map.of(),notices),CLASSIC),"Optional negotiation interleaved in GDS preserves synchronization.");
        check(Arrays.equals(output.toByteArray(),hex("fffe01")),"Unsupported ECHO is declined, not accepted.");
        Profile auto = new Profile(UUID.randomUUID(),"Fixture","127.0.0.1",23,"WORK01","DUMMYUSR",
                true,false,"37","Phosphor","None",1,Map.of(),"");
        output.reset();
        Map<String,String> environment = auto.environment("DUMMYPW".toCharArray());
        wire = concat(hex("fffd27fffa27010349424d52534545447d3e488f180804040349424d5355425350570300fff0"),TelnetCodec.frame(CLASSIC));
        readCodec(wire,output,environment,notices);
        String envResponse = new String(output.toByteArray(),StandardCharsets.ISO_8859_1);
        check(envResponse.contains("USER\1DUMMYUSR"),"RFC 4777 USER is negotiated.");
        check(envResponse.contains("IBMRSEED\1\3IBMSUBSPW\1DUMMYPW"),"Plain sign-on uses the RFC 4777 empty seed mechanism.");
        check(envResponse.contains("DEVNAME\1WORK01"),"Workstation name is negotiated.");
        check(envResponse.contains("KBDTYPE\1USB") && envResponse.contains("CODEPAGE\1" + "37"),"Code page includes required keyboard type.");
        Profile manual=new Profile(UUID.randomUUID(),"Manual","",23,"","",false,false,"37","Phosphor","None",1,Map.of(),"");
        check(!manual.environment(new char[0]).containsKey("USER"),"Manual sign-on does not negotiate credentials.");
        namedDevices();
        output.reset(); notices.clear();
        Map<String,String> autoDevice=new LinkedHashMap<>(environment); autoDevice.remove("DEVNAME");
        readCodec(concat(hex("fffe27"),TelnetCodec.frame(CLASSIC)),output,autoDevice,notices);
        check(notices.size() == 1 && notices.getFirst().contains("not negotiated"),"Declined auto-sign-on is visible, never success-shaped.");
        fails(() -> readCodec(hex("000a12a00000040000ffef"),new ByteArrayOutputStream(),Map.of(),new ArrayList<>()),"Truncated GDS is rejected.");
        fails(() -> readCodec(hex("000a12a0000004000000ff"),new ByteArrayOutputStream(),Map.of(),new ArrayList<>()),"Truncated IAC is rejected.");
        fails(() -> readCodec(hex("fffd19fffe19"),new ByteArrayOutputStream(),Map.of(),new ArrayList<>()),"Required EOR refusal is an explicit failure.");
        fails(() -> readCodec(hex("fffa1801fffd"),new ByteArrayOutputStream(),Map.of(),new ArrayList<>()),"Malformed subnegotiation cannot consume presentation data.");
        fails(() -> TelnetCodec.validateRecord(hex("000a89a0000004000000")),"Unsupported record type does not invent a success response.");
        byte[] extendedHeader = hex("000c12a000000600000b0000");
        check(Arrays.equals(readCodec(TelnetCodec.frame(extendedHeader),new ByteArrayOutputStream(),Map.of(),new ArrayList<>()),extendedHeader),
                "Longer valid variable headers are accepted.");
        System.out.println("PASS Telnet framing and RFC negotiation");
    }
    private static void namedDevices() throws Exception {
        Map<String,String> explicit=new LinkedHashMap<>();
        explicit.put("DEVNAME","WORK@01"); explicit.put("IBMSENDCONFREC","YES");
        ByteArrayOutputStream out=new ByteArrayOutputStream(); List<String> notices=new ArrayList<>();
        byte[] request=hex("fffd27fffa2701034445564e414d450349424d53454e44434f4e46524543fff0");
        byte[] reply=hex("fffb27fffa2700034445564e414d4501574f524b4030310349424d53454e44434f4e4652454301594553fff0");
        readCodec(concat(request,TelnetCodec.frame(CLASSIC)),out,explicit,notices);
        check(Arrays.equals(out.toByteArray(),reply),"Exactly one requested explicit-device IS response; no unsolicited allocation attempt.");
        out.reset();
        readCodec(concat(hex("fffd27fffa270100fff0"),TelnetCodec.frame(CLASSIC)),out,Map.of("CODEPAGE","WRONG","USER","RIGHT"),notices);
        // A VAR-only wildcard must not leak USERVAR values.
        check(Arrays.equals(out.toByteArray(),hex("fffb27fffa27000055534552015249474854fff0")),"VAR-only wildcard respects RFC 1572 variable types.");
        out.reset();
        readCodec(concat(hex("fffd27fffa270103fff0"),TelnetCodec.frame(CLASSIC)),out,Map.of(),notices);
        check(Arrays.equals(out.toByteArray(),hex("fffb27fffa2700fff0")),"Automatic device selection sends no invented DEVNAME.");
        out.reset();
        readCodec(concat(hex("fffd27fffa270103580203fff0"),TelnetCodec.frame(CLASSIC)),out,Map.of("X\3","\0\1\2\3\u00ff"),notices);
        check(Arrays.equals(out.toByteArray(),hex("fffb27fffa270003580203010200020102020203fffffff0")),"NEW-ENVIRON escaping and IAC doubling are byte-exact.");
        out.reset();
        readCodec(concat(hex("fffd27fffa270103554e4b4e4f574e034445564e414d45fff0"),TelnetCodec.frame(CLASSIC)),out,explicit,notices);
        check(Arrays.equals(out.toByteArray(),hex("fffb27fffa270003554e4b4e4f574e034445564e414d4501574f524b403031fff0")),
                "Explicit SEND order and undefined unknown variables are preserved.");
        fails(() -> readCodec(concat(hex("fffe27"),TelnetCodec.frame(CLASSIC)),new ByteArrayOutputStream(),explicit,new ArrayList<>()),
                "A requested device cannot silently fall back when NEW-ENVIRON is refused.");
        fails(() -> readCodec(TelnetCodec.frame(CLASSIC),new ByteArrayOutputStream(),explicit,new ArrayList<>()),
                "A requested device cannot silently fall back when the host never requests environment.");
        byte[] success=concat(hex("004912a090000560060020c0003d0000c9f9f0f2e3c1d9c7c5e34040d7c3d7d9c9d5e3c5d940"),new byte[35]);
        out.reset(); notices.clear();
        check(Arrays.equals(readCodec(concat(TelnetCodec.frame(success),TelnetCodec.frame(CLASSIC)),out,Map.of(),notices),CLASSIC)
                        && notices.getFirst().contains("I902") && notices.getFirst().contains("PCPRINTER"),
                "Exact RFC 4777 success fixture is consumed without reaching the display engine.");
        byte[] rejection=success.clone(); rejection[10]=(byte)0x82; rejection[11]=0;
        System.arraycopy(hex("f8f9f0f2"),0,rejection,16,4);
        try {
            readCodec(TelnetCodec.frame(rejection),new ByteArrayOutputStream(),Map.of(),new ArrayList<>());
            check(false,"Startup rejection must fail.");
        } catch (IOException e) {
            check(e.getMessage().contains("8902") && e.getMessage().contains("Device not available") && e.getMessage().contains("PCPRINTER"),
                    "Startup 8902 identifies the actual rejected device and availability reason.");
        }
        byte[] unknown=success.clone(); System.arraycopy(hex("c9f9f9f9"),0,unknown,16,4);
        fails(() -> readCodec(TelnetCodec.frame(unknown),new ByteArrayOutputStream(),Map.of(),new ArrayList<>()),"Unknown startup results never invent success.");
        fails(() -> readCodec(concat(request,TelnetCodec.frame(success)),new ByteArrayOutputStream(),explicit,new ArrayList<>()),
                "A different allocated device cannot silently satisfy an explicit requested device.");
        byte[] malformed=success.clone(); malformed[13]=0x3c;
        fails(() -> readCodec(TelnetCodec.frame(malformed),new ByteArrayOutputStream(),Map.of(),new ArrayList<>()),
                "Startup diagnostic length must match its actual record.");
        out.reset(); notices.clear();
        readCodec(concat(request,hex("fffa2701034445564e414d45fff0"),TelnetCodec.frame(CLASSIC)),out,explicit,notices);
        check(notices.stream().anyMatch(n -> n.contains("collision")) && count(new String(out.toByteArray(),StandardCharsets.ISO_8859_1),"WORK@01")==2,
                "Collision retry retains the requested name and produces a diagnostic, never a fabricated alternative.");
    }
    private static int count(String text,String needle) {
        int count = 0, position = 0;
        while ((position = text.indexOf(needle,position)) >= 0) { count++; position += needle.length(); }
        return count;
    }
    private static void models() throws Exception {
        Profile first = Profile.create("A");
        Profile second = first.duplicate();
        check(!first.id().equals(second.id()) && second.device().isEmpty() && !second.rememberPassword(),"Duplicate gets independent identity and no credential/device collision.");
        Layout.Leaf a = new Layout.Leaf(UUID.randomUUID(),first.id()), b = new Layout.Leaf(UUID.randomUUID(),second.id());
        Layout.Leaf c = new Layout.Leaf(UUID.randomUUID(),first.id());
        Layout original = new Layout.Split(true,.43,a,new Layout.Split(false,.6,b,c));
        check(original.equals(Layout.decode(original.encode())),"Nested layout ratios and identities round trip.");
        Layout swapped = original.move(a.pane(),b.pane(),Layout.Dock.CENTER);
        check(swapped.leaves().get(0).equals(b) && swapped.leaves().get(1).equals(a),"Center docking swaps without duplicating IDs.");
        for (Layout.Dock dock : Layout.Dock.values()) {
            Layout moved = original.move(a.pane(),c.pane(),dock);
            check(moved.leaves().size() == 3 && new HashSet<>(moved.leaves()).size() == 3,"Dock " + dock + " preserves every session.");
            check(Layout.decode(moved.encode()).equals(moved),"Dock " + dock + " is persistent.");
        }
        check(original.remove(a.pane()).leaves().size() == 2,"Removing a pane collapses its parent split.");
        check(Layout.decode("E") instanceof Layout.Empty && Layout.decode("E").leaves().isEmpty(),"An empty workspace round trips without a phantom session.");
        check(new Layout.Empty().insert(null,a,Layout.Dock.RIGHT).equals(a),"Adding to an empty workspace creates exactly one leaf.");
        fails(() -> Layout.decode("H|NaN|" + a.encode() + "|" + b.encode()),"Invalid ratios are rejected.");
        fails(() -> Layout.decode("H|0.5|" + a.encode() + "|" + a.encode()),"Duplicate pane IDs are rejected.");
        fails(() -> Layout.decode("L|bad|bad"),"Malformed layout is not silently reset.");
        fails(() -> new Keymap(Map.of("ctrl alt F1","PF1")),"Application shortcut namespace cannot be remapped to host keys.");
        fails(() -> Keymap.parse("F1 = NOT_A_KEY"),"Unknown key mappings are errors.");
        check(Keymap.parse("ctrl ENTER = FIELD_EXIT\nF12 = PF24").size() == 2,"Custom host mappings parse.");
        fails(() -> Keymap.parse("alt SPACE = ENTER"),"Host overrides cannot steal the native window menu.");
        fails(() -> Keymap.parse("alt F4 = PF4"),"Host overrides cannot steal window close.");
        Path directory = Files.createTempDirectory(Path.of("build"),"state-test-");
        try (StateStore store = new StateStore(directory)) {
            Profile saved = first.withLayout(original.encode());
            store.save(new StateStore.State(List.of(saved,second),original,1280,800,AppTheme.Midnight));
            StateStore.State loaded = store.load();
            check(loaded.profiles().getFirst().equals(saved) && loaded.layout().equals(original),"Profiles and per-profile layouts round trip.");
            check(loaded.appTheme()==AppTheme.Midnight,"Application theme persists independently of host palettes.");
            String xml = Files.readString(store.file());
            check(!xml.contains("IBMSUBSPW") && !xml.contains("password</"),"Workspace persistence contains no password property.");
            Properties legacy=new Properties(); legacy.loadFromXML(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            legacy.setProperty("version","1"); legacy.remove("appTheme");
            try (OutputStream migrated=Files.newOutputStream(store.file())) { legacy.storeToXML(migrated,"Legacy fixture"); }
            check(store.load().appTheme()==AppTheme.Charcoal && store.load().profiles().getFirst().equals(saved),
                    "Existing version-1 profiles/layouts migrate with the original application theme.");
            fails(() -> new StateStore(directory),"Concurrent workspace writers are blocked.");
            Files.writeString(store.file(),"<bad>");
            fails(store::load,"Corrupt workspace is surfaced, never overwritten with defaults.");
            check(Files.readString(store.file()).equals("<bad>"),"Corrupt file remains unchanged for recovery.");
        } finally {
            Files.deleteIfExists(directory.resolve("workspace.xml")); Files.deleteIfExists(directory.resolve("workspace.lock"));
            Files.deleteIfExists(directory.resolve("workspace.xml.tmp")); Files.delete(directory);
        }
        System.out.println("PASS layout, profiles and persistence");
    }
    private static void themesAndCommands() throws Exception {
            edt(() -> {
                JComboBox<String> persistentChoice=new JComboBox<>(new String[]{"One","Two"});
                for (AppTheme theme : AppTheme.values()) {
                    Ui.applyTheme(theme);
                    SwingUtilities.updateComponentTreeUI(persistentChoice);
                    persistentChoice.getRenderer().getListCellRendererComponent(new JList<>(),"One",-1,false,false);
                    check(persistentChoice.getRenderer() instanceof javax.swing.plaf.UIResource,
                            "Live combo theme refresh replaces the renderer with its owning delegate.");
                    for (Color background : List.of(Ui.PANEL,Ui.FIELD,Ui.SELECTION)) {
                        check(contrast(Ui.TEXT,background)>=4.5,"Primary text contrast in "+theme);
                        check(contrast(Ui.MUTED,background)>=4.5,"Secondary/disabled text contrast in "+theme);
                    }
                    check(contrast(Ui.FOCUS,Ui.BACKGROUND)>=3,"Visible focus contrast in "+theme);
                    for (String palette : Profile.THEMES) {
                        Color[] colors=HostPalette.colors(palette);
                        check(colors.length==16 && colors[0].equals(Ui.BACKGROUND),"Full host palette follows "+theme+" background.");
                        for (int i=1;i<16;i++) check(contrast(colors[i],colors[0])>=4.5,"Readable host color "+i+" in "+theme+"/"+palette);
                    }
                    JButton button=Ui.button("Example",KeyEvent.VK_E,() -> {});
                    check(button.getUI() instanceof FlatControls.Button,"Buttons use the shared flat delegate in "+theme);
                    check(new JComboBox<>(new String[]{"One","Two"}).getUI() instanceof FlatControls.Combo,"Choices use the shared flat delegate.");
                }
                Ui.applyTheme(AppTheme.Charcoal);
                return null;
            });
            java.util.concurrent.atomic.AtomicBoolean available=new java.util.concurrent.atomic.AtomicBoolean(false);
            int[] runs={0};
            AppCommand add=new AppCommand("Split left / right","Create an independent session","ctrl alt H",
                    () -> runs[0]++,available::get,"add pane","add window","new session");
            check(add.matches("add window") && add.matches("new session") && add.matches("independent") && !add.matches("unrelated"),
                    "Command search supports descriptions and common aliases.");
            add.actionPerformed(null); check(runs[0]==0,"Unavailable commands do not execute.");
            available.set(true); add.actionPerformed(null); check(runs[0]==1,"All command routes execute the shared enabled handler.");
            System.out.println("PASS application themes, contrast, host palettes, flat delegates and command aliases");
        }
        private static double contrast(Color a,Color b) {
            double x=luminance(a),y=luminance(b);
            return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);
        }
        private static double luminance(Color color) {
            double[] rgb={color.getRed()/255.0,color.getGreen()/255.0,color.getBlue()/255.0};
            for (int i=0;i<3;i++) rgb[i]=rgb[i]<=.04045 ? rgb[i]/12.92 : Math.pow((rgb[i]+.055)/1.055,2.4);
            return .2126*rgb[0]+.7152*rgb[1]+.0722*rgb[2];
        }
    private record Core(Session5250 session,ByteArrayOutputStream output) {}
    private static Core fresh(byte[] fixture) throws Exception {
        TelnetCodec.validateRecord(fixture);
        return edt(() -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Session5250 session = new Session5250("Fixture","37",output,ignored -> {});
            session.accept(fixture);
            return new Core(session,output);
        });
    }
    private static byte[] reply(Core core) throws IOException {
        return DemoHost.clientRecord(new ByteArrayInputStream(core.output.toByteArray()));
    }
    private static void core() throws Exception {
        Core classic = fresh(CLASSIC);
        edt(() -> {
            Screen5250 screen = classic.session.getScreen();
            check(screen.getRows() == 24 && screen.getColumns() == 80,"Classic presentation space is 24x80.");
            check(new String(screen.getScreenAsChars(),1,5).equals("HELLO"),"Golden EBCDIC fixture reaches the mature screen engine.");
            check(screen.getScreenFields().getSize() == 1 && screen.isInField(81),"Start Field defines actual input cells.");
            check(screen.getCurrentRow() == 2 && screen.getCurrentCol() == 2,"Host read positions the cursor at the field.");
            screen.sendKeys("A"); screen.sendKeys(KeyMnemonic.ENTER);
            return null;
        });
        byte[] entered = reply(classic);
        check(entered[9] == 0 && (entered[10] & 255) == 2 && (entered[11] & 255) == 3 && (entered[12] & 255) == 0xf1,
                "Enter sends the RFC 1205 client opcode, exact cursor row, column and AID.");
        check((entered[13] & 255) == 0xc1,"Field input is encoded back to EBCDIC.");
        edt(() -> {
            classic.session.getScreen().sendKeys(KeyMnemonic.RESET);
            check(classic.session.getScreen().getOIA().isKeyBoardLocked(),"Reset cannot release host-controlled input inhibition.");
            return null;
        });
        byte[] nulls = DemoScreens.gds(3,concat(Arrays.copyOfRange(CLASSIC,10,CLASSIC.length - 4),
                hex("1000050000000000"),Arrays.copyOfRange(CLASSIC,CLASSIC.length - 4,CLASSIC.length)));
        for (int read : new int[]{0x52,0x82}) {
            byte[] fixture = nulls.clone();
            fixture[fixture.length - 3] = (byte) read;
            Core alternate = fresh(fixture);
            edt(() -> {
                Screen5250 screen = alternate.session.getScreen();
                screen.moveCursor(83); screen.sendKeys("B"); screen.sendKeys(KeyMnemonic.ENTER);
                return null;
            });
            byte[] response = reply(alternate);
            byte[] expected = read == 0x52 ? hex("1102024040c2") : hex("1102020000c2");
            check(Arrays.equals(Arrays.copyOfRange(response,13,response.length),expected),
                    "Read " + Integer.toHexString(read) + " preserves SBA, field position and embedded nulls: "
                            + HexFormat.of().formatHex(response));
        }
        Core immediate = fresh(nulls);
        edt(() -> {
            immediate.session.getScreen().moveCursor(83);
            immediate.session.getScreen().sendKeys("B");
            immediate.session.accept(DemoScreens.gds(2,hex("0483")));
            return null;
        });
        check(Arrays.equals(Arrays.copyOfRange(reply(immediate),10,reply(immediate).length),hex("0205001102020000c2")),
                "Read Immediate Alternate replies immediately, with AID zero and preserved embedded nulls.");
        Core attention = fresh(CLASSIC);
        edt(() -> { attention.session.getScreen().sendKeys(KeyMnemonic.ATTN); return null; });
        check(Arrays.equals(reply(attention),hex("000a12a0000004400000")),"Attention is the RFC 1205 header-only attention flag.");
        Core systemRequest = fresh(CLASSIC);
        edt(() -> { systemRequest.session.getVT().systemRequest("3"); return null; });
        check(Arrays.equals(reply(systemRequest),hex("000b12a0000004040000f3")),"System Request sends the SRQ flag and EBCDIC option.");
        for (int i = 1; i <= 24; i++) {
            Core function = fresh(CLASSIC);
            int number = i;
            edt(() -> { function.session.getScreen().sendKeys(KeyMnemonic.valueOf("PF" + number)); return null; });
            check((reply(function)[12] & 255) == (i <= 12 ? 0x30 + i : 0xb0 + i - 12),"F" + i + " produces the correct host AID.");
        }
        for (KeyMnemonic key : List.of(KeyMnemonic.CLEAR,KeyMnemonic.HELP,KeyMnemonic.PAGE_UP,KeyMnemonic.PAGE_DOWN)) {
            Core function = fresh(CLASSIC);
            edt(() -> { function.session.getScreen().sendKeys(key); return null; });
            check((reply(function)[12] & 255) == key.value,key + " produces its host AID.");
        }
        Core fieldExit = fresh(CLASSIC);
        edt(() -> {
            Screen5250 screen = fieldExit.session.getScreen();
            screen.sendKeys("[["); screen.sendKeys("]]");
            check(screen.getScreenAsChars()[81] == '[' && screen.getScreenAsChars()[82] == ']',"Literal brackets cannot inject mnemonic actions.");
            screen.sendKeys(KeyMnemonic.FIELD_EXIT);
            check(screen.getScreenAsChars()[83] == 0 || screen.getScreenAsChars()[83] == ' ',"Field Exit clears the field tail.");
            return null;
        });
        byte[] numeric = CLASSIC.clone(); numeric[29] = 0x43;
        Core number = fresh(numeric);
        edt(() -> {
            Screen5250 screen = number.session.getScreen();
            check(screen.getScreenFields().getField(0).isNumeric(),"Numeric FFW is interpreted.");
            screen.sendKeys("X");
            check(screen.getOIA().isKeyBoardLocked(),"Invalid numeric input inhibits the keyboard.");
            screen.sendKeys(KeyMnemonic.RESET);
            check(!screen.getOIA().isKeyBoardLocked(),"Reset recovers from local input error.");
            screen.sendKeys("4");
            check(screen.getScreenAsChars()[81] == '4',"Valid numeric input works after Reset.");
            return null;
        });
        Core protectedArea = fresh(CLASSIC);
        edt(() -> {
            Screen5250 screen = protectedArea.session.getScreen();
            screen.setCursor(1,3); screen.sendKeys("Z");
            check(screen.getScreenAsChars()[2] == 'E' && screen.getOIA().isKeyBoardLocked(),"Protected host text cannot be overwritten.");
            return null;
        });
        Core dimensions = fresh(DemoScreens.screen(1,true,""));
        edt(() -> {
            check(dimensions.session.getScreen().getRows() == 27 && dimensions.session.getScreen().getColumns() == 132,"Host Clear Unit Alternate switches to 27x132.");
            dimensions.session.accept(CLASSIC);
            check(dimensions.session.getScreen().getRows() == 24 && dimensions.session.getScreen().getColumns() == 80,"Host Clear Unit restores 24x80.");
            return null;
        });
        ByteArrayOutputStream hidden = new ByteArrayOutputStream();
        DemoScreens.command(hidden,4,0x40,4,0x11,0,8);
        DemoScreens.at(hidden,1,1,0x27,"TOPSECRET");
        DemoScreens.at(hidden,2,1,0x21,"REVERSE");
        DemoScreens.at(hidden,3,1,0x2a,"BLINK");
        DemoScreens.field(hidden,4,1,5,0x40,0x24);
        DemoScreens.command(hidden,4,0x42,0,0);
        Core secrecy = fresh(DemoScreens.gds(3,hidden.toByteArray()));
        edt(() -> {
            Screen5250 screen = secrecy.session.getScreen();
            String exported = TerminalView.visibleText(screen,-1,-1);
            check(!exported.contains("TOPSECRET") && exported.contains("REVERSE"),"Copy/export never reveals non-display data.");
            char[] colors = new char[screen.getScreenLength() + 1], effects = colors.clone();
            screen.GetScreen(colors,colors.length,PLANE_COLOR);
            screen.GetScreen(effects,effects.length,PLANE_EXTENDED);
            check((colors[81] >> 8 & 255) == COLOR_BG_GREEN,"Host reverse-video background is preserved.");
            check((effects[161] & EXTENDED_5250_BLINK) != 0,"Host blinking attributes remain distinct.");
            return null;
        });
        Core query = fresh(hex("001112a000000400000304f30005d97000"));
        byte[] queryReply = reply(query);
        check(queryReply.length == 71 && (queryReply[12] & 255) == 0x88,"RFC 1205 Query has the exact basic reply shape.");
        check((queryReply[13] & 255) == 0 && (queryReply[14] & 255) == 58,"Query field length excludes the 3-byte cursor/AID prefix.");
        check((queryReply[60] & 255) == 0x31 && (queryReply[63] & 255) == 0,"Query advertises both screen sizes/color but not enhanced display features.");
        ByteArrayOutputStream password = new ByteArrayOutputStream();
        DemoScreens.command(password,4,0x40,4,0x11,0,8);
        DemoScreens.field(password,2,1,5,0x40,0x27);
        DemoScreens.command(password,4,0x42,0,0);
        Core passwordField = fresh(DemoScreens.gds(3,password.toByteArray()));
        edt(() -> {
            Screen5250 screen = passwordField.session.getScreen();
            SessionPane.checkPasswordField(screen,5);
            fails(() -> SessionPane.checkPasswordField(screen,6),"An overlong password cannot spill into a visible field.");
            screen.moveCursor(83);
            fails(() -> SessionPane.checkPasswordField(screen,4),"Password capacity is measured from the actual cursor, not the field start.");
            screen.moveCursor(1);
            fails(() -> SessionPane.checkPasswordField(screen,1),"Visible/protected cursor positions cannot receive a password.");
            return null;
        });
        fails(() -> fresh(hex("001112a000000400000304f30005d97100")),"Unknown WSF stops explicitly.");
        fails(() -> fresh(hex("001112a000000400000304110008120000")),"Unimplemented WEA stops explicitly.");
        fails(() -> fresh(hex("000a12a000000400007f")),"Unknown GDS opcode stops explicitly.");
        for (String cp : Profile.CODE_PAGES) {
            char test = cp.equals("1140") || cp.equals("1141") || cp.equals("1147") || cp.equals("1148") ? '\u20ac' : 'A';
            var page = CharMappings.getCodePage(cp);
            check(page.ebcdic2uni(page.uni2ebcdic(test) & 255) == test,"Code page " + cp + " has a real bidirectional mapping.");
            Profile profile = new Profile(UUID.randomUUID(),"Code page fixture","",23,"","",false,false,
                    cp,"Phosphor","None",1,Map.of(),"");
            check(profile.charset().newEncoder().canEncode(test),"Code page " + cp + " is usable by the actual UI input path.");
        }
        Core saved = fresh(CLASSIC);
        edt(() -> { saved.session.getScreen().sendKeys("B"); saved.session.getVT().saveScreen(); return null; });
        byte[] saveRecord = reply(saved);
        check((saveRecord[9] & 255) == 4,"Save Screen reply uses the RFC 1205 opcode.");
        byte[] restoration = DemoScreens.gds(5,Arrays.copyOfRange(saveRecord,10,saveRecord.length));
        edt(() -> {
            saved.session.accept(DemoScreens.screen(1,true,""));
            saved.session.accept(restoration);
            check(saved.session.getScreen().getColumns() == 80 && saved.session.getScreen().getScreenAsChars()[81] == 'B',
                    "Save/restore round trip retains host dimensions, fields and edited data.");
            return null;
        });
        System.out.println("PASS 5250 datastreams, host AIDs, validation, attributes and screen state");
    }
    private static void effectRendering() throws Exception {
        Path directory=Files.createTempDirectory(Path.of("build"),"effect-test-");
        DemoHost host=new DemoHost();
        StateStore store=new StateStore(directory);
        CountDownLatch closed=new CountDownLatch(1);
        Workspace workspace=edt(() -> {
            Workspace window=new Workspace(store,Main.demoState(host.port()),Path.of("scripts","credential-store.ps1"),true);
            window.onClosed(closed::countDown);
            window.connectAll();
            return window;
        });
        try {
            await(() -> edtBool(() -> workspace.sessionPanes().stream().allMatch(SessionPane::connected)),12000,
                    "Hidden rendering fixtures connect without taking desktop focus.");
            edt(() -> {
                SessionPane pane=workspace.active();
                TerminalView view=pane.view();
                view.setSize(640,400);
                for (String effect : Profile.EFFECTS) {
                    workspace.putProfile(pane.profile().withDisplay("Phosphor",effect));
                    BufferedImage original=paint(view);
                    KeyEvent selection=new KeyEvent(view,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),
                            InputEvent.SHIFT_DOWN_MASK,KeyEvent.VK_RIGHT,KeyEvent.CHAR_UNDEFINED);
                    for (KeyListener listener : view.getKeyListeners()) listener.keyPressed(selection);
                    check(differentPixels(original,paint(view))>5,"Selection invalidates cached "+effect+" tiles.");
                    view.resetSelection();
                    check(differentPixels(original,paint(view))==0,"Clearing selection exactly restores "+effect+" rendering.");
                    workspace.activate(workspace.sessionPanes().get(1));
                    check(paint(view).getRGB(0,0)==Ui.BACKGROUND.getRGB(),"Inactive "+effect+" tiles use the new pane background.");
                    workspace.activate(pane);
                    check(paint(view).getRGB(0,0)==pane.getBackground().getRGB(),"Active "+effect+" tiles use the coherent pane background.");
                }
                check(!workspace.isVisible(),"Effect fixtures never show a foreground window.");
                return null;
            });
        } finally {
            edt(() -> { workspace.close(true); return null; });
            check(closed.await(5,TimeUnit.SECONDS),"Hidden render workspace closes and releases its store.");
            host.close();
            Files.deleteIfExists(directory.resolve("workspace.xml"));
            Files.deleteIfExists(directory.resolve("workspace.lock"));
            Files.deleteIfExists(directory.resolve("workspace.xml.tmp"));
            Files.delete(directory);
        }
        System.out.println("PASS cached effects, selection and pane-background invalidation without foreground input");
    }
    private static BufferedImage paint(JComponent component) {
        BufferedImage image=new BufferedImage(component.getWidth(),component.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); component.paint(g); g.dispose(); return image;
    }
    private static int differentPixels(BufferedImage a,BufferedImage b) {
        int changed=0;
        for (int y=0;y<a.getHeight();y++) for (int x=0;x<a.getWidth();x++) if (a.getRGB(x,y)!=b.getRGB(x,y)) changed++;
        return changed;
    }
    private static void credentials() throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            System.out.println("SKIP Windows Credential Manager (not Windows)");
            return;
        }
        Profile profile = Profile.create("Credential fixture");
        CredentialStore credentials = new CredentialStore(Path.of("scripts").resolve("credential-store.ps1"));
        char[] fixture = "Synthetic-\u03a9-password".toCharArray();
        try {
            check(credentials.read(profile).isEmpty(),"Unknown credential is distinctly missing.");
            credentials.write(profile,fixture);
            Optional<char[]> returned = credentials.read(profile);
            check(returned.isPresent() && Arrays.equals(returned.get(),fixture),"Windows Credential Manager preserves a Unicode password.");
            returned.ifPresent(p -> Arrays.fill(p,'\0'));
        } finally { credentials.delete(profile); Arrays.fill(fixture,'\0'); }
        check(credentials.read(profile).isEmpty(),"Forgetting a password removes the OS credential.");
        System.out.println("PASS real Windows Credential Manager round trip");
    }
    private static void desktop() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("build"),"desktop-test-");
        DemoHost host = new DemoHost();
        StateStore store = new StateStore(directory);
        CountDownLatch closed=new CountDownLatch(1);
        Workspace workspace = edt(() -> {
            Workspace window = new Workspace(store,Main.demoState(host.port()),Path.of("scripts").resolve("credential-store.ps1"),true);
            window.onClosed(closed::countDown);
            window.setAlwaysOnTop(true);
            window.setVisible(true); window.connectAll();
            return window;
        });
        try {
            await(() -> edtBool(() -> workspace.sessionPanes().stream().allMatch(SessionPane::connected)),12000,"Three independent loopback sessions connect.");
            edt(() -> {
                check(workspace.sessionPanes().size() == 3,"Reference layout has three live panes.");
                check(workspace.sessionPanes().get(0).view().visibleText().contains("Main Menu"),"Left pane is a real host menu.");
                check(workspace.sessionPanes().get(1).view().visibleText().contains("Customer File Inquiry"),"Upper right pane has independent host state.");
                check(workspace.sessionPanes().get(2).view().visibleText().contains("Order Entry"),"Lower right pane has independent host state.");
                workspace.activate(workspace.sessionPanes().get(0)); workspace.active().focusTerminal();
                return null;
            });
            Robot robot = new Robot();
            robot.setAutoDelay(25);
            Point header = edt(() -> workspace.active().getLocationOnScreen());
            robot.mouseMove(header.x + 20,header.y + 12);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            edt(() -> { workspace.active().focusTerminal(); return null; });
            robot.waitForIdle();
            await(() -> edtBool(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == workspace.active().view()),
                    3000,"Native desktop gives the target terminal actual keyboard focus.");
            int inputPosition = edt(() -> workspace.active().screen().getCurrentPos() - 1);
            robot.keyPress(KeyEvent.VK_7); robot.keyRelease(KeyEvent.VK_7);
            robot.waitForIdle();
            edt(() -> {
                Screen5250 screen = workspace.active().screen();
                check(screen.getScreenAsChars()[inputPosition] == '7' && screen.getCurrentPos() - 1 == inputPosition + 1,
                        "Physical keyboard input reaches cell " + inputPosition + " and advances the cursor (actual character "
                                + (int)screen.getScreenAsChars()[inputPosition] + ", cursor " + screen.getCurrentPos() + ").");
                int cursor = screen.getCurrentPos();
                char[] before = screen.getScreenAsChars().clone();
                check(!workspace.active().paste("A\u6f22B"),"Paste rejects unrepresentable characters before editing.");
                check(screen.getCurrentPos() == cursor && Arrays.equals(before,screen.getScreenAsChars()),
                        "Rejected paste neither inserts a prefix nor silently skips characters.");
                return null;
            });
            robot.keyPress(KeyEvent.VK_ADD); robot.keyRelease(KeyEvent.VK_ADD);
            robot.waitForIdle();
            edt(() -> {
                check(workspace.active().screen().getScreenAsChars()[inputPosition] == '7',
                        "A mapped keypad Field Plus does not also type a literal plus into the next field.");
                check(workspace.active().send(KeyMnemonic.ENTER),"Enter is accepted after keypad input; recoverable="
                        +workspace.active().recoverableInputError()+", position="+workspace.active().screen().getCurrentPos());
                return null;
            });
            await(() -> edtBool(() -> workspace.sessionPanes().get(0).view().visibleText().contains("Host received Enter")),5000,
                    "Loopback server receives and answers Enter.");
            edt(() -> { workspace.active().send(KeyMnemonic.PF5); return null; });
            await(() -> edtBool(() -> workspace.active().screen().getColumns() == 132),5000,"Host response to F5 switches dimensions.");
            edt(() -> {
                int originalColumns = workspace.active().screen().getColumns();
                workspace.setSize(1200,750);
                check(workspace.active().screen().getColumns() == originalColumns,"Window resize cannot change host dimensions.");
                List<SessionPane> original = workspace.sessionPanes();
                UUID first = original.get(0).id(), second = original.get(1).id();
                workspace.dock(first,second,Layout.Dock.CENTER);
                check(workspace.sessionPanes().get(1) == original.get(0),"Center dock preserves the actual session object.");
                workspace.dock(first,second,Layout.Dock.LEFT);
                check(workspace.sessionPanes().stream().allMatch(SessionPane::connected),"Edge docking never reconnects live sessions.");
                workspace.dock(first,second,Layout.Dock.CENTER);
                return null;
            });
            robot.waitForIdle();
            robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_ALT); robot.keyPress(KeyEvent.VK_RIGHT);
            robot.keyRelease(KeyEvent.VK_RIGHT); robot.keyRelease(KeyEvent.VK_ALT); robot.keyRelease(KeyEvent.VK_CONTROL);
            robot.waitForIdle();
            edt(() -> {
                check(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof TerminalView,"Keyboard pane navigation leaves visible terminal focus.");
                // Restore the pinned composition for the visual artifact, while retaining live sockets.
                List<SessionPane> panes = workspace.sessionPanes();
                SessionPane main = panes.stream().filter(p -> p.profile().name().equals("Main")).findFirst().orElseThrow();
                SessionPane customers = panes.stream().filter(p -> p.profile().name().equals("Customers")).findFirst().orElseThrow();
                SessionPane orders = panes.stream().filter(p -> p.profile().name().equals("Orders")).findFirst().orElseThrow();
                workspace.dock(main.id(),customers.id(),Layout.Dock.LEFT);
                workspace.dock(orders.id(),customers.id(),Layout.Dock.BOTTOM);
                workspace.setSize(1500,900);
                workspace.activate(main); main.send(KeyMnemonic.PF6);
                return null;
            });
            await(() -> edtBool(() -> workspace.active().screen().getColumns() == 80),5000,"Host response to F6 restores classic dimensions.");
            edt(() -> {
                BufferedImage image = new BufferedImage(workspace.getWidth(),workspace.getHeight(),BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics(); workspace.paint(graphics); graphics.dispose();
                ImageIO.write(image,"png",Path.of("build").resolve("workspace-test.png").toFile());
                check(image.getWidth() >= 1000,"Desktop rendering artifact produced at real window dimensions.");
                return null;
            });
            check(host.failures().isEmpty(),"Synthetic host reports no negotiation or record failures: " + host.failures());
        } finally {
            edt(() -> { workspace.close(true); return null; });
            check(closed.await(5,TimeUnit.SECONDS),"Desktop closes and persists.");
            host.close();
            try (StateStore reopened = new StateStore(directory)) {
                check(reopened.load().layout().leaves().size() == 3,"Desktop layout survives a real close/reopen.");
            }
            Files.deleteIfExists(directory.resolve("workspace.xml")); Files.deleteIfExists(directory.resolve("workspace.lock"));
            Files.deleteIfExists(directory.resolve("workspace.xml.tmp")); Files.delete(directory);
        }
        System.out.println("PASS live TCP + desktop keyboard, independent panes, docking and persistence");
    }
    private static boolean edtBool(Callable<Boolean> action) {
        try { return edt(action); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void await(BooleanSupplier condition,long timeout,String description) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(25);
        check(condition.getAsBoolean(),description);
    }
}
