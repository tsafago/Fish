package pane5250.update;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongConsumer;
import pane5250.*;
import pane5250.model.*;

/** Isolated state and injected HTTP/installer fixtures; never runs a real installer. */
public final class MaintenanceSmoke {
    private static int checks;
    private static final byte[] EXE="MZ synthetic installer bytes - never executed".getBytes(StandardCharsets.UTF_8);
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    @FunctionalInterface interface Operation { void run() throws Exception; }
    private static void rejects(Operation action,String message) throws Exception {
        boolean failed=false;
        try { action.run(); } catch (IOException | IllegalArgumentException e) { failed=true; }
        check(failed,message);
    }
    private static Map<String,Object> asset(String version,String name,byte[] content) {
        return new LinkedHashMap<>(Map.of("name",name,"size",content.length,"state","uploaded","digest","sha256:"+Updates.hash(content),
                "browser_download_url","https://github.com/tsafago/Fish/releases/download/v"+version+"/"+name));
    }
    public static Map<String,Object> release(String version,boolean preview) {
        String installer="Fish-"+version+"-setup.exe";
        byte[] manifest=bytes(Updates.hash(EXE)+"  "+installer+"\r\n");
        return new LinkedHashMap<>(Map.of("draft",false,"prerelease",preview,"tag_name","v"+version,
                "html_url","https://github.com/tsafago/Fish/releases/tag/v"+version,"body","Synthetic release, not a real host capture.",
                "assets",List.of(asset(version,installer,EXE),asset(version,"SHA256SUMS.txt",manifest),
                        asset(version,"Fish-"+version+"-windows-x64.zip",bytes("synthetic portable metadata")))));
    }
    private static Optional<Updates.Release> select(List<?> values,UpdateSettings.Channel channel) throws IOException {
        return Updates.select(bytes(ConfigSchema.json(values)),Updates.Version.parse("0.7.1"),channel);
    }
    private static void versions() throws Exception {
        check(Updates.Version.parse("0.7.10").compareTo(Updates.Version.parse("v0.7.2"))>0,"Numeric, not lexical version comparison");
        for (String invalid:List.of("latest","0.7","v0.7.1-beta","1.2.3.4","01.2.3","-1.0.0","9999999.1.1","1/2/3"))
            rejects(() -> Updates.Version.parse(invalid),"Reject malformed version "+invalid);
        var channel=UpdateSettings.Channel.PREVIEW_PATCHES;
        check(select(List.of(release("0.7.1",true),release("0.7.0",true)),channel).isEmpty(),"No same-version install/downgrade");
        check(select(List.of(release("0.7.2",true),release("0.7.10",true)),channel).orElseThrow().version().patch()==10,"Preview patches included and sorted");
        check(select(List.of(release("0.8.0",true),release("1.0.0",false),release("0.8.1",false)),channel).isEmpty(),"Default channel never changes minor/major");
        check(select(List.of(release("0.7.2",true)),UpdateSettings.Channel.STABLE).isEmpty(),"Stable channel excludes previews");
        check(select(List.of(release("0.8.1",false)),UpdateSettings.Channel.STABLE).isPresent(),"Explicit stable channel permits same-major stable minor");
        for (String property:List.of("draft","html_url","tag_name","assets")) {
            Map<String,Object> malformed=release("0.7.3",true);
            malformed.put(property,switch (property) { case "draft" -> true; case "html_url" -> "https://evil.invalid/release";
                case "tag_name" -> "v0.7.3/../../evil"; default -> List.of(); });
            check(select(List.of(malformed,release("0.7.2",true)),channel).orElseThrow().version().patch()==2,"Filter ineligible "+property);
        }
        Map<String,Object> wrongAsset=release("0.7.3",true);
        var bad=asset("0.7.3","Fish-0.7.3-setup.exe",EXE); bad.put("browser_download_url","https://evil.invalid/evil.exe");
        wrongAsset.put("assets",List.of(bad));
        check(select(List.of(wrongAsset),channel).isEmpty(),"Reject wrong asset origin");
        for (String invalid:List.of("{}","[true] garbage","[{\"draft\":false,\"draft\":true}]","[1e99999999999999999]","[\"\\ud800\"]"))
            rejects(() -> Updates.select(bytes(invalid),Updates.Version.parse("0.7.1"),channel),"Reject invalid/ambiguous JSON");
        rejects(() -> Updates.select(new byte[2_000_001],Updates.Version.parse("0.7.1"),channel),"Bound JSON bytes");
        rejects(() -> new UpdateSettings(true,channel,"v0.7.2"),"Ignore key is exact numeric version");
        check(UpdateSettings.defaults().ignore("0.7.2").ignoredVersion().equals("0.7.2"),"Exact-version ignore persists");
        URI assetUrl=URI.create("https://github.com/tsafago/Fish/releases/download/v0.7.2/Fish-0.7.2-setup.exe");
        check(UpdateHttp.allowed(assetUrl,URI.create("https://release-assets.githubusercontent.com/a?signature=x")),"Allow exact GitHub CDN");
        for (String invalid:List.of("http://release-assets.githubusercontent.com/a","https://evil.invalid/a",
                "https://user@release-assets.githubusercontent.com/a","https://release-assets.githubusercontent.com:444/a",
                "https://github.com/attacker/Fish/releases/download/x/y","https://release-assets.githubusercontent.com/a#fragment"))
            check(!UpdateHttp.allowed(assetUrl,URI.create(invalid)),"Reject unsafe redirect "+invalid);
        check(!UpdateHttp.allowed(Updates.API,URI.create("https://api.github.com/repos/attacker/Fish/releases")),"API cannot redirect repository");
        URI attacker=URI.create("https://evil.invalid/setup.exe");
        check(!UpdateHttp.allowed(attacker,attacker),"Untrusted initial URL cannot approve itself");
        check(!UpdateHttp.allowed(attacker,URI.create("https://release-assets.githubusercontent.com/a")),"CDN redirect requires trusted initial origin");
    }
    private static void state(Path directory) throws Exception {
        Profile first=Profile.kerberosDefault(),second=first.duplicate(),third=first.duplicate();
        Layout layout=Layout.bounded(List.of(new Layout.Leaf(UUID.randomUUID(),first.id()),new Layout.Leaf(UUID.randomUUID(),second.id())));
        first=first.withLayout(layout.encode()); second=second.withLayout(layout.encode());
        var updates=new UpdateSettings(false,UpdateSettings.Channel.STABLE,"0.7.2");
        StateStore.State state=new StateStore.State(List.of(first,second,third),layout,1200,800,AppTheme.Charcoal,first.id(),
                Map.of(),false,Appearance.defaults(),StateStore.Tutorial.COMPLETED,true,updates);
        List<Profile> moved=ProfileManagement.move(state.profiles(),second.id(),-1);
        check(moved.getFirst().id().equals(second.id()) && moved.get(1).id().equals(first.id()),"Move preserves stable IDs and exact order");
        check(ProfileManagement.move(moved,second.id(),-1).equals(moved),"Top boundary is safe");
        UUID deleted=first.id();
        StateStore.State result=ProfileManagement.delete(state,deleted);
        check(result.profiles().size()==2 && result.lastProfile().equals(second.id()),"Delete chooses first remaining profile");
        UUID replacement=second.id();
        check(result.layout().leaves().size()==2 && result.layout().leaves().stream().allMatch(l -> l.profile().equals(replacement)),"Delete remaps panes, does not remove them");
        check(Layout.decode(result.profiles().getFirst().savedLayout()).leaves().stream().noneMatch(l -> l.profile().equals(deleted)),"Saved layouts have no dangling profiles");
        check(result.updates().equals(updates) && result.connectOnStartup(),"Delete preserves unrelated settings");
        StateStore.State one=StateStore.freshState();
        rejects(() -> ProfileManagement.delete(one,one.profiles().getFirst().id()),"Last profile cannot disappear silently");
        Profile source=new Profile(UUID.randomUUID(),"Login fixture","fixture.invalid",23,"","FIXTURE",true,true,"37","Phosphor","None",1,Map.of(),"");
        Profile dependent=new Profile(UUID.randomUUID(),"Dependent","other.invalid",23,"","",false,false,"37","Phosphor","None",1,Map.of(),"",source.id());
        StateStore.State linked=new StateStore.State(List.of(source,dependent),new Layout.Leaf(UUID.randomUUID(),dependent.id()),1200,800);
        rejects(() -> ProfileManagement.delete(linked,source.id()),"Dependent login blocks source deletion");
        check(ProfileManagement.delete(linked,dependent.id()).profiles().getFirst().equals(source),"Deleting dependent retains source login metadata");
        Path active=directory.resolve("Pane5250"); Files.createDirectory(active);
        Path untouched=active.resolve("unrelated-user-file.txt"); Files.writeString(untouched,"synthetic unrelated file");
        try (StateStore store=new StateStore(active)) {
            store.save(state);
            check(Main.defaultDirectory(directory).equals(active),"Legacy workspace selection remains stable");
            var legacy=StateStore.properties(state); legacy.setProperty("version","10");
            legacy.remove("windows"); legacy.remove("info.footerDevice");
            legacy.remove("updates.startup"); legacy.remove("updates.channel"); legacy.remove("updates.ignoredVersion");
            var migrated=store.decode(legacy);
            check(migrated.profiles().equals(state.profiles()) && migrated.connectOnStartup(),"Version 10 Kerberos/profile/startup metadata preserved");
            check(migrated.updates().equals(UpdateSettings.defaults()),"Version 10 receives explicit update defaults");
            var bad=(Properties)legacy.clone(); bad.setProperty("updates.startup","false");
            rejects(() -> store.decode(bad),"Reject relabeled new preferences");
            StateStore.State loaded=store.load();
            check(loaded.equals(state),"Complete format 11 roundtrip");
            rejects(() -> new StateStore(active),"Exclusive workspace lock retained");
            store.factoryReset();
            rejects(() -> store.save(state),"Old autosave cannot resurrect reset workspace");
            check(Files.readString(untouched).equals("synthetic unrelated file"),"Factory reset touches no unknown files");
        }
        try (StateStore store=new StateStore(active)) {
            var fresh=store.load(); Profile profile=fresh.profiles().getFirst();
            check(fresh.profiles().size()==1 && !profile.id().equals(deleted),"Fresh default gets a new identity");
            check(profile.authentication()==Profile.Authentication.KERBEROS && profile.host().equals("10.1.2.3") && profile.port()==23,"Factory Kerberos endpoint");
            check(fresh.tutorial()==StateStore.Tutorial.NEW && fresh.connectOnStartup(),"New/factory startup uses the user-approved connection default");
            check(fresh.appearance().equals(Appearance.defaults()) && fresh.updates().equals(UpdateSettings.defaults()),"Reset clears custom appearance and hidden update choices");
            check(Main.defaultDirectory(directory).equals(active),"Legacy state cannot resurrect through fallback");
            String revision=store.revision(); Files.writeString(store.file(),"\n",StandardOpenOption.APPEND);
            rejects(store::factoryReset,"Stale reset cannot overwrite an external write");
            check(store.revision().equals(revision),"Rejected reset keeps revision");
        }
        Path config=directory.resolve("config"); Files.createDirectory(config);
        try (StateStore store=new StateStore(config)) { store.save(result); }
        Path exported=directory.resolve("export.xml");
        ByteArrayOutputStream output=new ByteArrayOutputStream(),error=new ByteArrayOutputStream();
        for (String command:List.of("export","validate","apply")) {
            int code=Configuration.run(new String[]{command,"--data-dir",config.toString(),"--file",exported.toString()},new PrintStream(output),new PrintStream(error));
            check(code==0,"Production configuration "+command+": "+error);
        }
        try (StateStore store=new StateStore(config)) { check(store.load().equals(result),"Config apply retains ordering/update metadata"); }
        check(ConfigSchema.document().contains("updates.ignoredVersion"),"Complete schema includes update settings");
    }
    private static final class FakeTransport implements Updates.Transport {
        final Map<URI,byte[]> data=new HashMap<>();
        int requests;
        boolean fail,truncate,oversize,cancelDownload;
        @Override public void fetch(URI url,OutputStream out,long limit,Updates.Cancel cancel,LongConsumer progress) throws IOException {
            cancel.check(); requests++;
            if (fail) throw new IOException("Synthetic network failure");
            byte[] content=data.get(url);
            if (content==null) throw new IOException("No synthetic response for "+url);
            if (url.toString().endsWith(".exe")) {
                if (cancelDownload) { cancel.cancel(); cancel.check(); }
                if (truncate) content=Arrays.copyOf(content,content.length-1);
                if (oversize) content=Arrays.copyOf(content,content.length+1);
            }
            if (content.length>limit) throw new IOException("Synthetic bounded transport rejected oversized body");
            out.write(content); progress.accept(content.length);
        }
    }
    private static final class FakePlatform implements Updates.Platform {
        int inspections,launches; boolean refuse;
        @Override public void inspectAndMark(Path file) throws IOException {
            inspections++; if (refuse) throw new IOException("Synthetic signature/policy rejection");
        }
        @Override public void launch(Path file,String hash,long pid,Path directory) { launches++; }
    }
    private static void downloads(Path root) throws Exception {
        Updates.Release release=select(List.of(release("0.7.2",true)),UpdateSettings.Channel.PREVIEW_PATCHES).orElseThrow();
        FakeTransport http=new FakeTransport(); FakePlatform platform=new FakePlatform();
        http.data.put(Updates.API,bytes(ConfigSchema.json(List.of(release("0.7.2",true)))));
        http.data.put(release.installer().url(),EXE);
        http.data.put(release.checksums().url(),bytes(Updates.hash(EXE)+"  "+release.installer().name()+"\r\n"));
        Path staging=root.resolve("staging"); Files.createDirectory(staging);
        try (Updates service=new Updates(http,platform,staging)) {
            check(service.check("0.7.1",UpdateSettings.Channel.PREVIEW_PATCHES,new Updates.Cancel()).orElseThrow().equals(release),"Production check uses injected public feed");
            AtomicLong progress=new AtomicLong();
            try (Updates.Download download=service.download(release,new Updates.Cancel(),progress::set)) {
                check(Files.size(download.installer())==EXE.length && progress.get()==EXE.length,"Verified file and progress");
                check(platform.inspections==1 && platform.launches==0,"Download never launches implicitly");
                service.launch(download,12345,root);
                check(platform.launches==1,"Explicit handoff invokes injected launcher only");
                Files.writeString(download.installer(),"tampered");
                rejects(() -> service.launch(download,12345,root),"Launch revalidates staged bytes");
                check(platform.launches==1,"Tampering never executes");
            }
            for (int mode=0;mode<5;mode++) {
                http.fail=mode==0; http.truncate=mode==1; http.oversize=mode==2; http.cancelDownload=mode==3; platform.refuse=mode==4;
                rejects(() -> service.download(release,new Updates.Cancel(),count -> {}),"Download failure/cancel path "+mode);
                try (var files=Files.list(staging)) { check(files.findAny().isEmpty(),"Failed/cancelled staging is removed"); }
            }
            http.fail=false; http.truncate=false; http.oversize=false; http.cancelDownload=false; platform.refuse=false;
            byte[] manifest=http.data.get(release.checksums().url()).clone(); manifest[0]^=1; http.data.put(release.checksums().url(),manifest);
            rejects(() -> service.download(release,new Updates.Cancel(),count -> {}),"Checksum file hash mismatch is rejected");
            int before=http.requests;
            var unsafe=new Updates.Release(release.version(),release.tag(),true,URI.create("https://evil.invalid/"),release.notes(),release.installer(),release.checksums());
            rejects(() -> service.download(unsafe,new Updates.Cancel(),count -> {}),"Wrong origin is rejected before network");
            check(http.requests==before,"Invalid release triggers no download");
            Updates.Cancel cancelled=new Updates.Cancel(); cancelled.cancel();
            rejects(() -> service.check("0.7.1",UpdateSettings.Channel.PREVIEW_PATCHES,cancelled),"Cancelled check cannot succeed");
        }
    }
    private static void remove(Path directory) throws IOException {
        try (var paths=Files.walk(directory)) { for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("Fish-071-fixtures-");
        try { versions(); state(root); downloads(root); }
        finally { remove(root); }
        System.out.println("Fish 0.7.3 noninteractive maintenance/update checks passed: "+checks);
    }
}
