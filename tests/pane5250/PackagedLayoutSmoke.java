package pane5250;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import pane5250.model.*;

/** Actual delivered JVM/DLL/executable; only temporary Shell links, offline CLI and loopback IPC. */
public final class PackagedLayoutSmoke {
    private static int checks;
    private static void check(boolean value,String text) { checks++; if (!value) throw new AssertionError(text); }
    private static void rejects(Io action,String text) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IOException expected) { checks++; return; }
        throw new AssertionError(text);
    }
    @FunctionalInterface interface Io { void run() throws Exception; }
    private static void cli(Path exe,Path log,String... args) throws Exception {
        List<String> command=new ArrayList<>(); command.add(exe.toString()); command.addAll(List.of(args));
        Process child=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(20,TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(5,TimeUnit.SECONDS); throw new AssertionError("Packaged offline CLI timeout"); }
        check(child.exitValue()==0,"Packaged offline CLI succeeds: "+List.of(args)+"\n"+Files.readString(log));
    }
    public static void main(String[] args) throws Exception {
        Path jar=Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path executable=jar.getParent().getParent().resolve("Fish.exe").toAbsolutePath();
        check(Files.isRegularFile(executable),"Actual packaged executable exists beside private runtime");
        Path root=Files.createTempDirectory(Path.of("build"),"packaged-layout-075-").toAbsolutePath();
        try {
            UUID id=UUID.randomUUID();
            Path directory=Files.createDirectory(root.resolve("workspace \u5317 spaces"));
            Profile profile=new Profile(UUID.randomUUID(),"Synthetic profile","",23,"","FIXTURE",false,false,"37","Phosphor","None",1,Map.of(),"");
            Layout pane=new Layout.Split(false,.37,new Layout.Leaf(UUID.randomUUID(),profile.id()),new Layout.Leaf(UUID.randomUUID(),profile.id()));
            SavedLayout recipe=new SavedLayout(id,"Synthetic \u5317",new StateStore.WindowState(pane,1000,700,profile.id()),
                    Map.of(pane.leaves().getFirst().pane(),new SavedLayout.Display(false,1.25),
                            pane.leaves().getLast().pane(),new SavedLayout.Display(true,1)));
            try (StateStore store=new StateStore(directory)) {
                store.save(new StateStore.State(List.of(profile),pane,1000,700).withSavedLayouts(List.of(recipe),id));
            }
            Path link=root.resolve("Fish \u5317 shortcut.lnk");
            LayoutShortcuts.create(link,executable,directory,id);
            String[] properties=LayoutShortcuts.inspect(link);
            check(properties.length==4 && Path.of(properties[0]).equals(executable),"Windows Shell link target is the real Fish.exe");
            check(properties[1].equals(LayoutShortcuts.arguments(id,directory)),"Shell stores exact quoted Unicode data-dir and stable layout UUID arguments");
            check(Path.of(properties[2]).equals(executable.getParent()) && Path.of(properties[3]).equals(executable),
                    "Native .lnk has the application working directory and Fish icon");
            check(!properties[1].contains("FIXTURE") && !properties[1].contains("Synthetic"),"Shortcut never exposes profile/username/host details");
            byte[] original=Files.readAllBytes(link);
            rejects(() -> LayoutShortcuts.create(link,executable,directory,id),"Never overwrite an existing .lnk");
            check(Arrays.equals(original,Files.readAllBytes(link)),"Refused overwrite leaves exact shortcut bytes intact");
            rejects(() -> LayoutShortcuts.create(root.resolve("..\\invalid.lnk"),executable,directory,id),"Reject output parent traversal");
            rejects(() -> LayoutShortcuts.create(root.resolve("invalid.cmd"),executable,directory,id),"Never generate scripts");
            rejects(() -> LayoutShortcuts.arguments(id,Path.of("C:\\"+"x".repeat(30001))),"Oversized path rejected");
            Path exported=root.resolve("export \u5317 snapshot.xml"),log=root.resolve("offline.log");
            cli(executable,log,"--config","export","--data-dir",directory.toString(),"--file",exported.toString());
            check(Files.isRegularFile(exported),"UTF-8 manifest preserves real Unicode/spaced native argv");
            cli(executable,log,"--config","validate","--data-dir",directory.toString(),"--file",exported.toString());
            cli(executable,log,"--config","apply","--data-dir",directory.toString(),"--file",exported.toString());
            try (StateStore store=new StateStore(directory)) {
                check(store.load().savedLayouts().equals(List.of(recipe)),"Packaged offline export/validate/atomic apply retains exact recipes");
            }
            AtomicInteger opened=new AtomicInteger();
            var owner=LaunchBroker.claim(directory,"private-"+Main.VERSION,null,layout -> {
                if (!id.equals(layout)) return 2;
                opened.incrementAndGet(); return 1;
            });
            try {
                check(LaunchBroker.claim(directory,"private-"+Main.VERSION,id,ignored -> 0).forwarded() && opened.get()==1,
                        "Actual private JVM sends a restricted recipe UUID through authenticated loopback IPC");
                rejects(() -> LaunchBroker.claim(directory,"private-"+Main.VERSION,UUID.randomUUID(),ignored -> 0),"Unavailable recipe fails explicitly");
                check(opened.get()==1,"Rejected reference never becomes a default window");
            } finally { owner.broker().close(); owner.store().close(); }
            System.out.println("PASS packaged Fish "+Main.VERSION+" native .lnk properties, Unicode executable CLI, exact recipe roundtrip and private UUID IPC: "+checks);
        } finally {
            try (var files=Files.walk(root)) {
                for (Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
