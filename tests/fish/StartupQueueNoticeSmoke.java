package fish.ui;

import fish.Main;
import fish.demo.DemoHost;
import fish.demo.DemoScreens;
import fish.model.Profile;
import fish.model.StateStore;
import fish.net.TelnetCodec;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Menu;
import org.tn5250j.keyboard.KeyMnemonic;
import static fish.demo.DemoScreens.*;
import static fish.ui.FxTestSupport.*;

/** Real NetworkSession/TN5250J/AID wire path against a synthetic loopback peer. */
public final class StartupQueueNoticeSmoke {
    private static byte[] notice(String change,boolean clear,boolean invite,boolean wide) {
        var out=new ByteArrayOutputStream();
        if (clear) command(out,wide ? new int[]{4,0x20,0} : new int[]{4,0x40});
        command(out,4,0x11,0,8);
        at(out,1,change.equals("off-center") ? 3 : wide ? 53 : 27,0x22,"Display Program Messages");
        String account=wide ? "EXAMPLE9" : "TESTUSER";
        at(out,3,2,0x20,wide ? "Job 654321/EXAMPLE9/DSP98X started on 12/31/25 at 22:41:12 in subsystem QINTER"
                : "Job 123456/TESTUSER/TESTSCREEN started on 01/02/25 at 01:02:03 in subsystem Q");
        at(out,4,2,0x20,"Message queue "+(change.equals("account") ? "OTHER" : account)+" is allocated to another job.");
        if (change.equals("mixed")) at(out,5,2,0x20,"Actual program message; leave this on screen.");
        if (change.equals("editable")) field(out,5,2,4,0x40,0x24);
        if (change.equals("protected")) field(out,5,2,4,0x60,0x24);
        if (change.equals("modified")) field(out,5,2,4,0x68,0x24);
        if (change.equals("hidden")) at(out,5,2,0x27,"Extra hidden message");
        at(out,change.equals("early-prompt") ? 6 : wide ? 22 : 19,2,0x3a,"Press Enter to continue.");
        at(out,wide ? 26 : 23,2,0x3a,"F3=Exit   F12=Cancel");
        if (invite) command(out,4,0x42,0,0);
        return gds(invite ? 3 : 2,out.toByteArray());
    }

    private static byte[] read(int opcode,int command) {
        return gds(opcode,new byte[]{4,(byte)command,0,0});
    }

    private static void send(Socket peer,byte[]... records) throws Exception {
        for (byte[] bytes:records) peer.getOutputStream().write(TelnetCodec.frame(bytes));
        peer.getOutputStream().flush();
    }
    private static void expectEnter(Socket peer) throws Exception {
        peer.setSoTimeout(3500);
        byte[] record=DemoHost.clientRecord(peer.getInputStream());
        if (record.length!=13 || (record[12]&255)!=0xf1) throw new AssertionError("Expected exactly an input-free Enter AID");
    }
    private static void noEnter(Socket peer) throws Exception {
        peer.setSoTimeout(750);
        try { DemoHost.clientRecord(peer.getInputStream()); throw new AssertionError("Unexpected client record"); }
        catch (SocketTimeoutException expected) {}
    }
    private static boolean pending(SessionPane pane) throws Exception {
        return (boolean)field(pane,"startupNoticePending");
    }
    private static Socket connect(ServerSocket listener,SessionPane pane,boolean again) throws Exception {
        fxRun(again ? pane::reconnect : pane::connect);
        Socket peer=listener.accept();
        peer.getOutputStream().write(new byte[]{(byte)255,(byte)253,39,
                (byte)255,(byte)250,39,1,0,3,(byte)255,(byte)240});
        peer.getOutputStream().flush();
        return peer;
    }

    private static void migration(Path root) throws Exception {
        try (StateStore store=new StateStore(root)) {
            var demo=Main.demoState(23);
            var profiles=new ArrayList<>(demo.profiles());
            UUID id=profiles.getFirst().id();
            UUID linked=profiles.get(1).id();
            profiles.set(0,new Profile(id,"Retired","127.0.0.1",23,"","TESTUSER",true,true,"37","Phosphor","None",1,Map.of(),""));
            profiles.set(1,new Profile(linked,"Linked","127.0.0.1",23,"","",false,false,"37","Phosphor","None",1,Map.of(),"",id));
            profiles.set(2,new Profile(profiles.get(2).id(),"Plain","127.0.0.1",23,"","TESTPLAIN",true,true,"37","Phosphor","None",1,Map.of(),""));
            profiles.add(new Profile(new UUID(0,4),"Kerberos","host.example",23,"","",false,false,"37","Phosphor","None",
                    1,Map.of(),"",null,Profile.Authentication.KERBEROS,"host.example"));
            var original=new StateStore.State(profiles,demo.layout(),1500,900);
            var props=StateStore.properties(original);
            String prefix="profile."+id+".";
            props.setProperty(prefix+"authentication","PASSWORD_SHA1");
            try (var out=Files.newOutputStream(store.file())) {
                props.storeToXML(out,"Synthetic retired preview workspace");
            }
            var migrated=store.load();
            var profile=migrated.profiles().getFirst();
            var inherited=Profile.effective(migrated.profiles().get(1),migrated.profiles());
            if (!profile.id().equals(id) || profile.authentication()!=Profile.Authentication.PASSWORD
                    || profile.automaticSignOn() || !profile.rememberPassword()
                    || !profile.credentialTarget().equals(original.profiles().getFirst().credentialTarget())
                    || profile.environment(new char[]{'X'}).containsKey("IBMSUBSPW")
                    || inherited.automaticSignOn() || inherited.environment(new char[]{'X'}).containsKey("IBMSUBSPW")
                    || !migrated.profiles().subList(1,migrated.profiles().size()).equals(original.profiles().subList(1,original.profiles().size())))
                throw new AssertionError("Retired preview sign-in must never send a raw password or change other profiles");
            store.save(migrated);
            if (!store.load().profiles().equals(migrated.profiles())) throw new AssertionError("Migrated login did not round-trip");
        }
    }

    public static void main(String[] args) throws Exception {
        Ui.install();
        boolean diagnose=Boolean.getBoolean(Main.NOTICE_DIAGNOSTICS_PROPERTY);
        Path root=Files.createTempDirectory(Path.of("build"),"queue-notice-");
        CountDownLatch closed=new CountDownLatch(1);
        try (ServerSocket server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(3500);
            migration(root.resolve("migration"));
            StateStore store=new StateStore(root.resolve("settings"));
            Workspace w=fx(() -> {
                var window=new Workspace(store,Main.demoState(server.getLocalPort()),Path.of("scripts","credential-store.ps1"),false);
                window.onClosed(closed::countDown);
                layout(window);
                return window;
            });
            try {
                if (fx(() -> ((Menu)field(w,"hostMenu")).getItems().stream()
                        .anyMatch(item -> "Startup notice diagnostic...".equals(item.getText())))!=diagnose)
                    throw new AssertionError("Diagnostic menu must be opt-in");
                var panes=fx(() -> w.sessionPanes().stream().toList());
                SessionPane first=panes.get(0),second=panes.get(1);
                if (java.util.Arrays.asList(args).contains("--typing-reset-only")) {
                    try (Socket a=connect(server,first,false); Socket b=connect(server,second,false)) {
                        operatorReset(w,store,first,second,a,b);
                    }
                    return;
                }
                byte[] exact=notice("",true,true,false);
                try (Socket a=connect(server,first,false); Socket b=connect(server,second,false)) {
                    for (String change:new String[]{"mixed","editable","modified","hidden","account","off-center","early-prompt"}) {
                        send(a,notice(change,true,true,false));
                        noEnter(a);
                    }
                    send(a,exact,DemoScreens.screen(1,false,"Another screen"));
                    noEnter(a);
                    send(a,exact,exact);
                    expectEnter(a);
                    send(a,exact);
                    noEnter(a);
                    send(b,notice("",true,true,true));
                    expectEnter(b);
                    noEnter(b);
                    operatorReset(w,store,first,second,a,b);
                }
                try (Socket a=connect(server,first,true)) {
                    // This host writes the exact screen without issuing Clear Unit, then reads it.
                    send(a,notice("",false,false,false));
                    noEnter(a);
                    send(a,read(3,0x52),gds(11,new byte[0]));
                    expectEnter(a);
                    assertDiagnostic(first,"automatic Enter sent",0);
                    send(a,exact);
                    noEnter(a);
                }
                try (Socket a=connect(server,first,true)) {
                    send(a,notice("protected",true,false,false),read(2,0x42),gds(12,new byte[0]));
                    noEnter(a);
                    assertDiagnostic(first,"waiting for a host invite record",1);
                    send(a,gds(1,new byte[0]));
                    expectEnter(a);
                }
                try (Socket a=connect(server,first,true)) {
                    send(a,DemoScreens.screen(1,false,"Another screen"));
                    noEnter(a);
                    send(a,notice("",true,false,false));
                    noEnter(a);
                    assertDiagnostic(first,"waiting for a host input-read record",0);
                    // A plain repaint cannot inherit an old read; an explicit invite alone isn't a read either.
                    send(a,gds(1,new byte[0]));
                    noEnter(a);
                    send(a,read(2,0x52));
                    expectEnter(a);
                }
                try (Socket a=connect(server,first,true)) {
                    send(a,exact);
                    await(() -> {
                        if (!pending(first)) return false;
                        if (!first.send(KeyMnemonic.ENTER)) throw new AssertionError("Manual Enter was not accepted");
                        return true;
                    },"Manual Enter missed the quiet interval");
                    expectEnter(a);
                    send(a,exact);
                    noEnter(a);
                }
                try (Socket a=connect(server,first,true)) {
                    send(a,exact);
                    await(() -> {
                        if (!pending(first)) return false;
                        first.screen().getOIA().setInputInhibited(1,1);
                        return true;
                    },"Could not inhibit the pending screen");
                    noEnter(a);
                    assertDiagnostic(first,"host input inhibited",0);
                }
                try (Socket a=connect(server,first,true)) {
                    var setting=fx(() -> {
                        w.preferences("General");
                        var preferences=w.tutorialPreferences();
                        var check=(CheckBox)preferences.dialog().getScene().lookup("#prefs-skip-queue-notice");
                        if (check==null || !check.isVisible() || !check.isSelected())
                            throw new AssertionError("Expected visible, default-on General setting");
                        return check;
                    });
                    send(a,exact);
                    await(() -> {
                        if (!pending(first)) return false;
                        setting.fire();
                        if (w.skipQueueNotice() || pending(first)) throw new AssertionError("Unchecking did not cancel the pending Enter");
                        return true;
                    },"Could not disable the pending notice");
                    noEnter(a);
                    send(a,exact);
                    noEnter(a);
                    if (!fx(() -> first.connected() && !pending(first))) throw new AssertionError("Off switch changed the connection or armed an Enter");
                    assertSavedSetting(w,store,false);
                    fxRun(setting::fire);
                    send(a,gds(11,new byte[0]));
                    noEnter(a);
                    send(a,exact);
                    expectEnter(a);
                    assertSavedSetting(w,store,true);
                    fxRun(() -> w.tutorialPreferences().forceDispose());
                }
            } finally {
                fxRun(() -> w.close(false));
                if (!closed.await(5,TimeUnit.SECONDS)) throw new AssertionError("Workspace did not close");
            }
            try (StateStore reopened=new StateStore(root.resolve("settings"))) {
                if (!reopened.load().autoResetTypingErrors() || !reopened.load().skipQueueNotice())
                    throw new AssertionError("Both input settings must survive closing and reopening the workspace");
            }
        } finally {
            deleteTree(root);
            javafx.application.Platform.exit();
        }
        System.out.println("Startup queue notice passed: no-clear writes, split reads and invites, harmless status, stale-read rejection, safe fields, exact-screen exclusions and one AID per connection.");
    }

    private static byte[] numericDisplay() {
        var out=new ByteArrayOutputStream();
        command(out,4,0x40,4,0x11,0,8);
        at(out,1,1,0x22,"NUMERIC CHECK");
        field(out,4,1,8,0x43,0x24);
        command(out,4,0x42,0,0);
        return gds(3,out.toByteArray());
    }

    private static void numericReady(Socket peer,SessionPane pane) throws Exception {
        send(peer,numericDisplay());
        await(() -> pane.connected() && !pane.screen().getOIA().isKeyBoardLocked()
                && pane.screen().getScreenFields().getSize()==1 && pane.screen().getScreenFields().getField(0).isNumeric()
                && !pane.recoverableInputError(),"Numeric display did not become ready");
        fxRun(() -> fieldStart(pane));
    }

    private static int numericStart(SessionPane pane) { return pane.screen().getScreenFields().getField(0).startPos(); }

    private static void fieldStart(SessionPane pane) {
        int position=numericStart(pane),cols=pane.screen().getColumns();
        pane.screen().setCursor(position/cols+1,position%cols+1);
    }

    private static void expectNumericError(Socket peer) throws Exception {
        peer.setSoTimeout(3500);
        byte[] record=DemoHost.clientRecord(peer.getInputStream());
        if (record.length!=12 || (record[7]&255)!=1 || (record[9]&255)!=0 || (record[11]&255)!=0x09)
            throw new AssertionError("Expected the existing numeric operator error response");
    }

    private static void operatorReset(Workspace w,StateStore store,SessionPane first,SessionPane second,Socket a,Socket b) throws Exception {
        numericReady(a,first);
        numericReady(b,second);
        var check=fx(() -> {
            w.preferences("General");
            var setting=(CheckBox)w.tutorialPreferences().dialog().getScene().lookup("#prefs-auto-reset-typing-errors");
            if (setting==null || !setting.isVisible() || setting.isSelected() || w.autoResetTypingErrors())
                throw new AssertionError("Expected visible, default-off General setting");
            if (first.type('X') || !first.recoverableInputError() || !first.resetCalloutBox().isVisible())
                throw new AssertionError("Opt-out must still show the manual Reset and reject the key");
            if (children(first).stream().filter(n -> n instanceof javafx.scene.control.Button button && "Reset".equals(button.getText())).count()!=1)
                throw new AssertionError("Manual recovery must have exactly one Reset button");
            if (!first.send(KeyMnemonic.RESET) || first.recoverableInputError())
                throw new AssertionError("Manual Reset did not clear the local typing error");
            setting.fire();
            return setting;
        });
        expectNumericError(a);
        noEnter(a);
        await(() -> !w.tutorialPreferences().saving(),"Opt-in did not finish saving");
        if (!store.load().autoResetTypingErrors())
            throw new AssertionError("Opt-in was not persisted");
        var calloutShown=new java.util.concurrent.atomic.AtomicBoolean();
        fx(() -> {
            javafx.beans.value.ChangeListener<Boolean> watcher=(o,old,visible) -> {
                if (visible) calloutShown.set(true);
            };
            first.resetCalloutBox().visibleProperty().addListener(watcher);
            try {
                for (char key:"XXX[]XYZ".toCharArray()) {
                    if (first.type(key) || first.recoverableInputError() || first.screen().getOIA().isKeyBoardLocked()
                            || (boolean)field(first.screen().getScreenFields().getCurrentField(),"mdt"))
                        throw new AssertionError("Repeated bad keys must be discarded without leaving input locked");
                }
                int start=first.screen().getCurrentPos();
                for (int i=0;i<3;i++) {
                    if (first.send(KeyMnemonic.BACK_SPACE) || first.recoverableInputError()
                            || first.screen().getOIA().isKeyBoardLocked() || first.screen().getCurrentPos()!=start)
                        throw new AssertionError("Backspace at the field boundary must reject without moving the cursor or locking");
                }
            } finally {
                first.resetCalloutBox().visibleProperty().removeListener(watcher);
            }
            if (calloutShown.get() || first.resetCalloutBox().isVisible()
                    || !first.noticeOverlay().isVisible() || !first.noticeOverlay().isMouseTransparent()
                    || !first.noticeOverlay().messageText().contains("Input reset automatically") || !first.type('5')
                    || first.screen().getCharacters()[numericStart(first)]!='5'
                    || second.screen().getCurrentPos()-1!=numericStart(second) || second.recoverableInputError())
                throw new AssertionError("A rejected key must be locally reset without mutation or impacting another pane");
            if (!first.send(KeyMnemonic.BACK_SPACE) || first.screen().getCharacters()[numericStart(first)]=='5'
                    || !first.type('5'))
                throw new AssertionError("Valid Backspace and typing must still work inside the field");
            second.screen().setCursor(1,1);
            char before=second.screen().getCharacters()[0];
            if (second.type('X') || second.recoverableInputError() || second.screen().getOIA().isKeyBoardLocked()
                    || second.screen().getCharacters()[0]!=before
                    || !field(second,"lastProblem").toString().contains("Key rejected"))
                throw new AssertionError("Protected text was mutated or the local keyboard stayed locked");
            for (var key:java.util.List.of(KeyMnemonic.BACK_SPACE,KeyMnemonic.DELETE,KeyMnemonic.ERASE_EOF,
                    KeyMnemonic.ERASE_FIELD,KeyMnemonic.FIELD_EXIT,KeyMnemonic.FIELD_PLUS,KeyMnemonic.END_OF_FIELD)) {
                if (second.send(key) || second.recoverableInputError() || second.resetCalloutBox().isVisible()
                        || second.screen().getOIA().isKeyBoardLocked() || second.screen().getCharacters()[0]!=before)
                    throw new AssertionError("Protected area did not recover from "+key);
            }
            fieldStart(second);
            if (second.send(KeyMnemonic.DUP_FIELD) || second.recoverableInputError())
                throw new AssertionError("DUP disabled by the field must be rejected locally");
            return null;
        });
        noEnter(a);
        noEnter(b);
        await(() -> !first.noticeOverlay().isVisible(),"Automatic reset toast did not dismiss");

        fx(() -> {
            if (first.paste("6X7") || first.recoverableInputError() || first.resetCalloutBox().isVisible()
                    || first.screen().getCharacters()[numericStart(first)+1]!='6'
                    || first.screen().getCharacters()[numericStart(first)+2]=='7'
                    || !first.noticeOverlay().messageText().equals("Paste stopped after 1 character. Input reset automatically.")
                    || !first.type('8') || first.screen().getCharacters()[numericStart(first)+2]!='8')
                throw new AssertionError("Paste must stop at the bad character, recover locally and accept the next key");
            return null;
        });
        noEnter(a);
        send(a,gds(2,new byte[]{4,0x21,0x20,(byte)0xc5}));
        await(first::recoverableInputError,"Host Write Error Code did not inhibit input");
        fxRun(() -> {
            if (first.type('7') || first.send(KeyMnemonic.BACK_SPACE) || !first.recoverableInputError()
                    || !first.screen().getOIA().isKeyBoardLocked())
                throw new AssertionError("Opt-in must not reset a host Write Error Code");
        });
        noEnter(a);
        numericReady(a,first);
        fxRun(() -> {
            if (!first.send(KeyMnemonic.ENTER) || !first.screen().getOIA().isKeyBoardLocked()
                    || first.recoverableInputError() || first.type('7') || first.send(KeyMnemonic.DELETE)
                    || !first.screen().getOIA().isKeyBoardLocked())
                throw new AssertionError("Opt-in must not reset a host-busy pane after an AID");
        });
        expectEnter(a);
        noEnter(a);
        var autoEnter=new ByteArrayOutputStream();
        command(autoEnter,4,0x40,4,0x11,0,8);
        command(autoEnter,0x11,4,1,0x1d,0x40,0x88,0x24,0,2); // Mandatory, Auto Enter.
        command(autoEnter,4,0x42,0,0);
        send(b,gds(3,autoEnter.toByteArray()));
        await(() -> second.screen().getScreenFields().getSize()==1
                && second.screen().getScreenFields().getField(0).isMandatoryEnter()
                && !second.screen().getOIA().isKeyBoardLocked(),"Mandatory Auto Enter field wasn't ready");
        fxRun(() -> {
            fieldStart(second);
            if (second.send(KeyMnemonic.FIELD_EXIT) || second.recoverableInputError()
                    || second.screen().getOIA().isKeyBoardLocked() || second.resetCalloutBox().isVisible())
                throw new AssertionError("Rejected Field Exit must recover without running the field's Auto Enter");
        });
        noEnter(b);
        fxRun(check::fire);
        await(() -> !w.tutorialPreferences().saving(),"Opt-out did not finish saving");
        if (store.load().autoResetTypingErrors()) throw new AssertionError("Opt-out was not persisted");
        numericReady(b,second);
        fxRun(() -> {
            if (second.paste("X7") || !second.recoverableInputError() || !second.resetCalloutBox().isVisible())
                throw new AssertionError("Opted-out paste must still require manual Reset");
        });
        expectNumericError(b);
        fxRun(check::fire);
        await(() -> w.autoResetTypingErrors() && !w.tutorialPreferences().saving(),"Re-enabled option did not save");
        fxRun(() -> w.tutorialPreferences().forceDispose());
        System.out.println("Input reset passed: repeated typing and editing errors, Auto Enter on rejection, persistence, failed paste and host inhibition.");
    }

    private static void assertDiagnostic(SessionPane pane,String reason,int fields) throws Exception {
        String report=fx(pane::startupNoticeDiagnostic);
        if (!report.contains(reason) || !report.contains("Display: 24 x 80; format fields: "+fields)
                || report.contains("TESTUSER") || report.contains("TESTSCREEN") || report.contains("123456")
                || report.contains("127.0.0.1") || report.contains("DEMOA") || report.contains("Display Program Messages"))
            throw new AssertionError("Startup notice diagnostic did not report only the expected state");
    }

    private static void assertSavedSetting(Workspace workspace,StateStore store,boolean enabled) throws Exception {
        await(() -> !workspace.tutorialPreferences().saving(),"Checkbox did not finish saving");
        if (store.load().skipQueueNotice()!=enabled) throw new AssertionError("Checkbox change was not persisted");
    }
}
