package pane5250.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import pane5250.*;
import pane5250.demo.*;
import pane5250.model.*;
import pane5250.net.*;
import pane5250.security.*;

/** Synthetic tokens/providers only: never acquires the current user's Windows credentials. */
public final class KerberosSmoke {
    private static int checks;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void fails(Work work,String contains) throws Exception {
        try { work.run(); throw new AssertionError("Expected failure: "+contains); }
        catch (IOException | IllegalArgumentException | IllegalStateException e) {
            check(e.getMessage().contains(contains),"Actionable failure: "+e.getMessage());
        }
    }
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void set(Object owner,String name,Object value) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner,value);
    }
    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> task=new FutureTask<>(work); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static void await(Callable<Boolean> work,String message) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (!work.call() && System.nanoTime()<end) Thread.sleep(15);
        check(work.call(),message);
    }
    private static byte[] hex(String text) { return HexFormat.of().parseHex(text.replace(" ","")); }
    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for (byte[] part:parts) out.writeBytes(part);
        return out.toByteArray();
    }
    private static final byte[] SCREEN=DemoScreens.gds(0,new byte[]{4,0x40});
    private static final byte[] REQUEST=hex("fffd27fffa27010349424d52534545447d3e488f180804040003fff0");
    private static byte[] startup(String code) {
        byte[] record=new byte[73];
        System.arraycopy(hex("004912a090000560060020c0003d0000"),0,record,0,16);
        System.arraycopy((code+"FIXTURE "+"AUTOFIX   ").getBytes(Charset.forName("Cp037")),0,record,16,22);
        return record;
    }
    private static byte[] exchange(byte[] request,byte[] ticket,Map<String,String> environment) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try (TelnetCodec codec=new TelnetCodec(new ByteArrayInputStream(concat(request,TelnetCodec.frame(SCREEN))),
                output,environment,text -> {},ticket)) {
            check(Arrays.equals(codec.readRecord(),SCREEN),"Kerberos negotiation preserves the exact GDS boundary.");
        }
        return output.toByteArray();
    }
    private static void protocol() throws Exception {
        byte[] token=hex("00010203ff04");
        byte[] expected=hex("fffb27fffa27000349424d5449434b4554010200020102020203ffff04fff0");
        check(Arrays.equals(exchange(REQUEST,token,Map.of()),expected),
                "RFC 4777 seed/wildcard request yields binary IBMTICKET with exact RFC1572 escaping and IAC doubling.");
        check(Arrays.equals(exchange(hex("fffd27fffa27010349424d5449434b4554fff0"),token,Map.of()),expected),
                "Explicit USERVAR IBMTICKET has the same exact opaque bytes.");
        check(Arrays.equals(token,hex("00010203ff04")),"Codec owns a copy, not the provider/caller's buffer.");
        byte[] max=new byte[Kerberos.MAX_TOKEN]; Arrays.fill(max,(byte)0xff);
        check(exchange(REQUEST,max,Map.of()).length==2*Kerberos.MAX_TOKEN+20,"Exactly 16384 token bytes are allowed after worst-case Telnet quoting.");
        Arrays.fill(max,(byte)0);
        check(exchange(REQUEST,max,Map.of()).length==2*Kerberos.MAX_TOKEN+20,"Worst-case environment control-byte quoting is bounded independently of metadata.");
        fails(() -> exchange(REQUEST,new byte[16385],Map.of()),"16384");
        fails(() -> exchange(REQUEST,new byte[0],Map.of()),"16384");
        fails(() -> exchange(REQUEST,token,Map.of("IBMSUBSPW","fixture")),"without password");
        fails(() -> exchange(REQUEST,token,Map.of("IBMTICKET","forbidden")),"binary");
        fails(() -> exchange(hex("fffe27"),token,Map.of()),"refused NEW-ENVIRON");
        fails(() -> exchange(new byte[0],token,Map.of()),"without requesting IBMTICKET");
        fails(() -> exchange(hex("fffd27fffa27010049424d5449434b4554fff0"),token,Map.of()),"without requesting IBMTICKET");
        byte[] metadata=exchange(REQUEST,token,Map.of("IBMSENDCONFREC","YES"));
        check(new String(metadata,StandardCharsets.ISO_8859_1).contains("IBMSENDCONFREC\1YES"),"Kerberos requests the real RFC 4777 startup result.");
        fails(() -> exchange(REQUEST,token,Map.of("OVERSIZE","x".repeat(1024))),"metadata exceeds");
        for (String code:List.of("0001","0002","0003","0004","0005","0006","1000","2000","6000","7000","9000","A000","B000","D000","I906"))
            fails(() -> {
                try (TelnetCodec codec=new TelnetCodec(new ByteArrayInputStream(concat(REQUEST,TelnetCodec.frame(startup(code)))),
                        OutputStream.nullOutputStream(),Map.of(),text -> {},token)) { codec.readRecord(); }
            },"Host startup "+code);
        fails(() -> StartupResponse.parse(startup("0004")).diagnostic("",true),"EIM");
        fails(() -> StartupResponse.parse(startup("0004")).diagnostic("",false),"Invalid password");
        fails(() -> StartupResponse.parse(startup("0008")).diagnostic("",true),"Unrecognized Kerberos");
        List<String> notices=new ArrayList<>();
        TelnetCodec codec=new TelnetCodec(new ByteArrayInputStream(concat(REQUEST,TelnetCodec.frame(startup("I902")),TelnetCodec.frame(SCREEN))),
                OutputStream.nullOutputStream(),Map.of(),notices::add,token);
        codec.readRecord();
        check(notices.getFirst().contains("authentication not verified") && codec.authenticationStatus().contains("not independently verified"),
                "Neither ticket generation nor device startup asserts application sign-on.");
        byte[] owned=(byte[])field(codec,"ticket"); codec.close();
        check(Arrays.equals(owned,new byte[owned.length]),"Closing the codec wipes its owned token.");
        check(Kerberos.available(),"Non-secret discovery finds the Windows Kerberos SSP without acquiring credentials.");
    }
    private static Profile profile(UUID id,String host,int port,String server) {
        return new Profile(id,"Kerberos fixture",host,port,"","",false,false,"37","Mint","None",1,Map.of(),"",
                null,Profile.Authentication.KERBEROS,server);
    }
    private static void models(Path root) throws Exception {
        Profile fresh;
        try (StateStore store=new StateStore(root.resolve("fresh"))) {
            StateStore.State state=store.load(); fresh=state.profiles().getFirst();
            check(fresh.host().equals("10.1.2.3") && fresh.port()==23 && fresh.authentication()==Profile.Authentication.KERBEROS
                    && !fresh.rememberPassword() && !fresh.automaticSignOn() && fresh.loginSource()==null,"Fresh workspace seeds the requested real Kerberos profile.");
            check(state.lastProfile().equals(fresh.id()) && state.layout().leaves().getFirst().profile().equals(fresh.id())
                    && !state.connectOnStartup() && state.tutorial()==StateStore.Tutorial.NEW,"Fresh default is selected and offline with the first-run guide.");
            store.save(state);
            check(store.load().equals(state),"Fresh seed is persisted once, not recreated with another ID.");
            Profile replacement=Profile.create("User replacement");
            StateStore.State changed=new StateStore.State(List.of(replacement),new Layout.Leaf(UUID.randomUUID(),replacement.id()),900,600);
            store.save(changed);
            check(store.load().profiles().equals(List.of(replacement)),"A user-deleted default is not resurrected.");
        }
        check(fresh.withLayout("").withZoom(1.2).withDisplay("Cool","None").withKeys(Map.of()).authentication()==Profile.Authentication.KERBEROS
                && fresh.duplicate().authentication()==Profile.Authentication.KERBEROS,"All display/layout/duplication transforms preserve authentication mode.");
        check(!fresh.environment(new char[0]).containsKey("USER") && !fresh.environment(new char[0]).containsKey("IBMSUBSPW"),"Kerberos profile never builds password environment values.");
        fails(() -> fresh.environment("fixture".toCharArray()),"never accepts a password");
        fails(fresh::credentialTarget,"does not use");
        CredentialStore absent=new CredentialStore(root.resolve("never-invoke-this-helper"));
        fails(() -> absent.read(fresh),"never Credential Manager");
        fails(() -> absent.write(fresh,new char[0]),"never Credential Manager");
        fails(() -> absent.delete(fresh),"never Credential Manager");
        check(Kerberos.principal("10.1.2.3","",endpoint -> "FIXTURE.INVALID.").equals("krbsvr400/fixture.invalid"),
                "Resolver output is normalized into IBM's krbsvr400 SPN, not the RFC prose typo.");
        check(Kerberos.principal("10.1.2.3","override.fixture.invalid",endpoint -> { throw new AssertionError("Unexpected DNS lookup"); })
                .equals("krbsvr400/override.fixture.invalid"),"Explicit service DNS identity stays separate from TCP IP and bypasses resolver discovery.");
        fails(() -> Kerberos.principal("10.1.2.3","10.1.2.3",endpoint -> ""), "fully qualified DNS");
        fails(() -> Kerberos.principal("10.1.2.3","krbsvr400/fixture.invalid",endpoint -> ""), "fully qualified DNS");
        fails(() -> Kerberos.principal("10.1.2.3",".",endpoint -> ""), "fully qualified DNS");
        fails(() -> Kerberos.principal("10.1.2.3","",endpoint -> "10.1.2.3"),"fully qualified DNS");
        Profile withServer=profile(fresh.id(),"10.1.2.3",23,"FIXTURE.INVALID.");
        StateStore.State state=new StateStore.State(List.of(withServer),new Layout.Leaf(UUID.randomUUID(),withServer.id()),900,600);
        try (StateStore store=new StateStore(root.resolve("roundtrip"))) {
            store.save(state); check(store.load().equals(state),"Authentication/service identity round-trip through canonical persistence.");
            String xml=Files.readString(store.file());
            check(xml.contains("KERBEROS") && xml.contains("fixture.invalid") && !xml.contains("IBMTICKET"),"Configuration contains safe metadata, never token material.");
            Properties bad=StateStore.properties(state); bad.setProperty("profile."+fresh.id()+".remember","true");
            fails(() -> store.decode(bad),"not saved passwords");
            Properties downgraded=StateStore.properties(state); downgraded.setProperty("version","9");
            downgraded.keySet().removeIf(key -> key.toString().startsWith("updates."));
            fails(() -> store.decode(downgraded),"do not relabel");
        }
        Path candidate=root.resolve("kerberos-config.xml"),directory=root.resolve("roundtrip");
        ByteArrayOutputStream errors=new ByteArrayOutputStream();
        PrintStream output=new PrintStream(OutputStream.nullOutputStream()),error=new PrintStream(errors);
        check(Configuration.run(new String[]{"export","--data-dir",directory.toString(),"--file",candidate.toString()},output,error)==0,
                "Production CLI exports Kerberos metadata without a security-provider or credential-store call.");
        Properties exported=StateStore.readProperties(candidate);
        exported.setProperty("profile."+fresh.id()+".kerberosServer","override.fixture.invalid");
        try (OutputStream out=Files.newOutputStream(candidate)) { exported.storeToXML(out,"Synthetic Kerberos edit","UTF-8"); }
        check(Configuration.run(new String[]{"validate","--data-dir",directory.toString(),"--file",candidate.toString()},output,error)==0
                && Configuration.run(new String[]{"apply","--data-dir",directory.toString(),"--file",candidate.toString()},output,error)==0,
                "Full Kerberos config validates/dry-runs and applies offline through revision/locking logic.");
        try (StateStore store=new StateStore(directory)) {
            check(store.load().profiles().getFirst().kerberosServer().equals("override.fixture.invalid"),"Bulk-applied DNS identity persists without connecting.");
        }
        Profile old=Profile.create("Existing password");
        StateStore.State existing=new StateStore.State(List.of(old),new Layout.Leaf(UUID.randomUUID(),old.id()),900,600,
                AppTheme.Charcoal,old.id(),Map.of(),false,Appearance.defaults(),StateStore.Tutorial.COMPLETED,true);
        for (String version:List.of("8","9")) {
            Properties p=StateStore.properties(existing); p.remove("appearance.cursorStyle"); p.setProperty("version",version);
            p.remove("windows"); p.remove("info.footerDevice");
            p.keySet().removeIf(key -> key.toString().startsWith("updates."));
            p.remove("profile."+old.id()+".authentication"); p.remove("profile."+old.id()+".kerberosServer");
            if (version.equals("8")) p.remove("connectOnStartup");
            try (StateStore store=new StateStore(root.resolve("legacy-"+version))) {
                StateStore.State migrated=store.decode(p);
                check(migrated.profiles().equals(existing.profiles()) && migrated.lastProfile().equals(old.id())
                        && migrated.tutorial()==existing.tutorial() && migrated.connectOnStartup()==version.equals("9"),
                        "Existing "+version+" profiles/login/preferences remain password mode, without a new Kerberos seed.");
            }
        }
    }
    private static void layout(Container root) {
        root.doLayout(); for (Component child:root.getComponents()) if (child instanceof Container c) layout(c);
    }
    private static void image(JDialog dialog,String name,double scale) throws IOException {
        layout(dialog);
        JComponent root=dialog.getRootPane();
        BufferedImage image=new BufferedImage((int)(root.getWidth()*scale),(int)(root.getHeight()*scale),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try { g.scale(scale,scale); root.paint(g); } finally { g.dispose(); }
        ImageIO.write(image,"png",Path.of("build","kerberos-"+name+".png").toFile());
        check(!dialog.isVisible(),"Kerberos UI fixture remains hidden from the shared desktop.");
    }
    private static void ui(Path root) throws Exception {
        try (DemoHost host=new DemoHost()) {
            Profile p=profile(UUID.randomUUID(),"127.0.0.1",host.port(),"");
            Profile passwordProfile=new Profile(UUID.randomUUID(),"Previous password","",23,"","FIXTURE",false,true,"37","Mint","None",1,Map.of(),"");
            StateStore.State state=new StateStore.State(List.of(p,passwordProfile),new Layout.Leaf(UUID.randomUUID(),p.id()),1000,700);
            CountDownLatch closed=new CountDownLatch(1);
            StateStore store=new StateStore(root.resolve("ui"));
            AtomicInteger calls=new AtomicInteger();
            AtomicReference<String> principal=new AtomicReference<>();
            AtomicReference<ProfilesDialog> conversionEditor=new AtomicReference<>();
            byte[] token=hex("00010203ff04");
            Workspace w=edt(() -> {
                Workspace window=new Workspace(store,state,root.resolve("absent-helper-no-password-access"),false);
                window.setFocusableWindowState(false); window.onClosed(closed::countDown); window.addNotify(); window.validate();
                set(window.active(),"kerberosResolver",(Kerberos.Resolver)endpoint -> "fixture.invalid");
                set(window.active(),"kerberosProvider",(Kerberos.Provider)target -> {
                    check(!SwingUtilities.isEventDispatchThread(),"Windows credential acquisition stays off the EDT.");
                    calls.incrementAndGet(); principal.set(target); return token;
                });
                window.startup();
                check(calls.get()==0 && !window.active().busy(),"Default startup acquires no credentials and opens no socket.");
                ProfilesDialog editor=new ProfilesDialog(window,p); editor.setFocusableWindowState(false); editor.addNotify(); editor.validate();
                check(!((JCheckBox)field(editor,"remember")).isVisible() && !((JPasswordField)field(editor,"password")).isVisible()
                        && ((JTextField)field(editor,"kerberosServer")).isVisible(),"Kerberos editor shows DNS identity, not password recommendations.");
                check(editor.tutorialSignInText().contains("Windows sign-in") && editor.tutorialTarget(2)==field(editor,"authentication"),
                        "Tutorial explains/anchors the actual Kerberos controls.");
                image(editor,"profile-100",1); image(editor,"profile-150",1.5);
                ((JComboBox<?>)field(editor,"authentication")).setSelectedItem(Profile.Authentication.PASSWORD);
                check(((JPasswordField)field(editor,"password")).isVisible() && !((JTextField)field(editor,"kerberosServer")).isVisible(),
                        "Password/manual UI stays available without an implicit connection.");
                editor.dispose();
                ProfilesDialog conversion=new ProfilesDialog(window,passwordProfile); conversion.setFocusableWindowState(false);
                ((JComboBox<?>)field(conversion,"authentication")).setSelectedItem(Profile.Authentication.KERBEROS);
                ((JButton)field(conversion,"save")).doClick(0);
                conversionEditor.set(conversion);
                return window;
            });
            try {
                await(() -> edt(() -> !(boolean)field(conversionEditor.get(),"saving")),"Actual profile Apply finishes without touching a password helper.");
                edt(() -> {
                    Profile converted=w.profileList().stream().filter(value -> value.id().equals(passwordProfile.id())).findFirst().orElseThrow();
                    check(converted.authentication()==Profile.Authentication.KERBEROS && !converted.rememberPassword()
                            && converted.username().equals(passwordProfile.username()),
                            "Switching password to Kerberos preserves safe username/old OS reference and never deletes the source credential.");
                    conversionEditor.get().dispose(); w.active().connect(); return null;
                });
                await(() -> edt(() -> w.active().connected() && (int)field(w.active(),"credentialOperations")==0),"Injected Kerberos provider reaches the production socket/core path.");
                check(calls.get()==1 && principal.get().equals("krbsvr400/fixture.invalid") && Arrays.equals(token,new byte[token.length]),
                        "One explicit Connect requests the correct service and wipes the returned provider token.");
                edt(() -> {
                    check(((String)field(w.active(),"notice")).contains("not independently verified"),"Connected status does not invent successful IBM i authentication.");
                    w.refreshCommands();
                    check(w.commands().stream().filter(c -> c.title.startsWith("Insert ")).noneMatch(AppCommand::isEnabled),
                            "Kerberos disables both password sign-on helpers.");
                    return null;
                });
                int connections=host.connectionCount();
                CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
                byte[] late=hex("010203ff");
                edt(() -> {
                    w.active().disconnect();
                    set(w.active(),"kerberosProvider",(Kerberos.Provider)target -> {
                        calls.incrementAndGet(); started.countDown();
                        try { if (!release.await(6,TimeUnit.SECONDS)) throw new IOException("Fixture release timeout"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                        return late;
                    });
                    w.active().connect(); return null;
                });
                check(started.await(3,TimeUnit.SECONDS),"Cancelable synthetic Windows operation started.");
                edt(() -> {
                    w.active().disconnect(); w.active().connect();
                    check(calls.get()==2,"Cancel/reconnect cannot accumulate outstanding native lookups.");
                    try {
                        w.validateProfile(profile(p.id(),p.host(),p.port(),"changed.fixture.invalid"));
                        throw new AssertionError("Pending authentication mutation accepted.");
                    } catch (IllegalArgumentException expected) { checks++; }
                    return null;
                });
                release.countDown();
                await(() -> edt(() -> (int)field(w.active(),"credentialOperations")==0),"Late canceled provider result completes cleanup.");
                check(Arrays.equals(late,new byte[late.length]) && host.connectionCount()==connections,
                        "Canceled token is wiped and never opens a socket.");
                edt(() -> {
                    set(w.active(),"kerberosProvider",(Kerberos.Provider)target -> { throw new IOException("Synthetic KDC refusal"); });
                    w.active().connect(); return null;
                });
                await(() -> edt(() -> (int)field(w.active(),"credentialOperations")==0 && !w.active().busy()),"KDC failure completes without fallback.");
                edt(() -> {
                    check(((String)field(w.active(),"notice")).contains("Synthetic KDC refusal") && host.connectionCount()==connections,
                            "Provider rejection is actionable, not a password prompt or a successful connection.");
                    return null;
                });
                CountDownLatch timeoutStarted=new CountDownLatch(1),timeoutRelease=new CountDownLatch(1);
                byte[] timedOut=hex("0001ff");
                edt(() -> {
                    set(w.active(),"kerberosProvider",(Kerberos.Provider)target -> {
                        timeoutStarted.countDown();
                        try { if (!timeoutRelease.await(6,TimeUnit.SECONDS)) throw new IOException("Fixture timeout release"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                        return timedOut;
                    });
                    w.active().connect(); return null;
                });
                check(timeoutStarted.await(3,TimeUnit.SECONDS),"Synthetic slow KDC operation starts.");
                edt(() -> {
                    Method timeout=SessionPane.class.getDeclaredMethod("kerberosTimedOut",long.class); timeout.setAccessible(true);
                    timeout.invoke(w.active(),(long)field(w.active(),"generation"));
                    check(!w.active().busy() && ((String)field(w.active(),"notice")).contains("timed out")
                            && (int)field(w.active(),"credentialOperations")==1,"UI deadline cancels the attempt but retains the outstanding-operation guard.");
                    return null;
                });
                timeoutRelease.countDown();
                await(() -> edt(() -> (int)field(w.active(),"credentialOperations")==0),"Timed-out native lookup eventually releases its pane guard.");
                check(Arrays.equals(timedOut,new byte[timedOut.length]) && host.connectionCount()==connections,
                        "A token returned after the deadline is wiped, never connected.");
            } finally {
                edt(() -> { w.close(false); return null; });
                check(closed.await(5,TimeUnit.SECONDS),"Kerberos fixture workspace closes cleanly.");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Ui.install();
        Path root=Files.createTempDirectory(Path.of("build"),"kerberos-smoke-");
        protocol(); models(root); ui(root);
        System.out.println("PASS "+checks+" Kerberos fixture checks; real Windows-domain/IBM i sign-on remains unverified.");
    }
}
