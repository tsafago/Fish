package pane5250;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import pane5250.model.StateStore;

/** Separate JVMs and loopback IPC only; never launches a visible frame or authenticates. */
public final class LaunchBrokerSmoke {
    private static int checks;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void await(Callable<Boolean> condition,String message) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime()<end) { if (condition.call()) return; Thread.sleep(20); }
        throw new AssertionError(message);
    }
    private static Process child(Path directory,String identity) throws IOException {
        return child(directory,identity,null);
    }
    private static Process child(Path directory,String identity,UUID layout) throws IOException {
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",
                System.getProperty("java.class.path"),LaunchBrokerSmoke.class.getName(),"--child",
                Base64.getUrlEncoder().encodeToString(directory.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),identity));
        if (layout!=null) command.add(layout.toString());
        return new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(directory.resolve("child-"+UUID.randomUUID()+".log").toFile()).start();
    }
    private static void child(String[] args) throws Exception {
        // Stock java.exe on non-UTF-8 Windows has a lossy argv code page; Fish.exe has UTF-8 activation.
        Path root=Path.of(new String(Base64.getUrlDecoder().decode(args[1]),java.nio.charset.StandardCharsets.UTF_8));
        UUID initial=args.length>3 ? UUID.fromString(args[3]) : null;
        var claim=LaunchBroker.claim(root,args[2],initial,id -> {
            if (id!=null && !Set.of(new UUID(0,1),new UUID(0,2)).contains(id)) return 2;
            try { Files.writeString(root.resolve("requests"),id==null ? "window\n" : "layout "+id+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            return 1;
        });
        if (claim.forwarded()) return;
        if (initial!=null) Files.writeString(root.resolve("initial-layout"),initial.toString());
        Files.writeString(root.resolve("owner"),Long.toString(ProcessHandle.current().pid()));
        try {
            while (!Files.exists(root.resolve("stop"))) Thread.sleep(20);
        } finally { claim.broker().close(); claim.store().close(); }
    }
    private static byte[] token(Path directory) throws IOException { return Arrays.copyOfRange(Files.readAllBytes(directory.resolve("window-launch")),8,40); }
    private static int request(Path directory,byte[] token,String identity,UUID id,boolean read) throws Exception {
        return request(directory,token,identity,id,null,false,read);
    }
    private static int request(Path directory,byte[] token,String identity,UUID id,UUID layout,boolean extra,boolean read) throws Exception {
        byte[] address=Files.readAllBytes(directory.resolve("window-launch"));
        int port=new DataInputStream(new ByteArrayInputStream(address,4,4)).readInt();
        try (Socket socket=new Socket(InetAddress.getByName("127.0.0.1"),port)) {
            socket.setSoTimeout(2000);
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            out.writeInt(0x46495348); out.write(token);
            out.write(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); out.writeByte(layout==null ? 0 : 1);
            if (layout!=null) { out.writeLong(layout.getMostSignificantBits()); out.writeLong(layout.getLeastSignificantBits()); }
            if (extra) out.write(new byte[1024]);
            out.flush(); socket.shutdownOutput();
            return read ? new DataInputStream(socket.getInputStream()).readInt() : 0;
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length>0 && args[0].equals("--child")) { child(args); return; }
        Path root=Files.createTempDirectory(Path.of("build"),"launch-074-");
        List<Process> processes=new ArrayList<>();
        try {
            Path directory=Files.createDirectory(root.resolve("race"));
            Process first=child(directory,"synthetic-074"); processes.add(first);
            Process second=child(directory,"synthetic-074"); processes.add(second);
            await(() -> Files.exists(directory.resolve("owner")) && Files.exists(directory.resolve("requests")),"Two initial processes did not elect one owner.");
            await(() -> first.isAlive()!=second.isAlive(),"Secondary process did not exit after forwarding.");
            check(Files.readAllLines(directory.resolve("requests")).size()==1,"Simultaneous launches create one owner and one new-window request");
            try { new StateStore(directory); throw new AssertionError("Offline lock was bypassed"); }
            catch (StateStore.InUseException expected) { checks++; }
            ByteArrayOutputStream rejected=new ByteArrayOutputStream();
            PrintStream quiet=new PrintStream(rejected);
            check(Configuration.run(new String[]{"export","--data-dir",directory.toString(),"--file",directory.resolve("should-not-export.xml").toString()},quiet,quiet)!=0
                    && !Files.exists(directory.resolve("should-not-export.xml")),"Actual offline CLI refuses while any owner window remains");
            try { LaunchBroker.claim(directory,"different-version",() -> false); throw new AssertionError("Different version was accepted"); }
            catch (IOException expected) { check(expected.getMessage().contains("version"),"Different version is actionable, not a stale save"); }
            try { request(directory,new byte[32],"synthetic-074",UUID.randomUUID(),true); throw new AssertionError("Unauthenticated request accepted"); }
            catch (EOFException | SocketException expected) { checks++; }
            check(Files.readAllLines(directory.resolve("requests")).size()==1,"Bad token never opens a window");
            UUID id=UUID.randomUUID();
            request(directory,token(directory),"synthetic-074",id,false);
            check(request(directory,token(directory),"synthetic-074",id,true)==0x46495348,"Lost acknowledgement can be retried");
            check(Files.readAllLines(directory.resolve("requests")).size()==2,"Same authenticated request is admitted exactly once");
            try { request(directory,token(directory),"synthetic-074",id,new UUID(0,1),false,true); throw new AssertionError("Replayed ID with altered payload accepted"); }
            catch (EOFException | SocketException expected) { checks++; }
            try { request(directory,token(directory),"synthetic-074",UUID.randomUUID(),null,true,true); throw new AssertionError("Oversized IPC accepted"); }
            catch (EOFException | SocketException expected) { checks++; }
            check(Files.readAllLines(directory.resolve("requests")).size()==2,"Malformed/replayed requests cannot create sessions");
            Path named=Files.createDirectory(root.resolve("named \u5317 spaces"));
            Process namedOwner=child(named,"synthetic-075",new UUID(0,2)); processes.add(namedOwner);
            await(() -> {
                if (!namedOwner.isAlive()) {
                    try (var files=Files.list(named)) {
                        for (Path log:files.filter(p -> p.toString().endsWith(".log")).toList()) System.err.println(Files.readString(log));
                    }
                    throw new AssertionError("Cold saved-layout child exited "+namedOwner.exitValue());
                }
                return Files.exists(named.resolve("owner"));
            },"Cold saved-layout owner did not start");
            check(Files.readString(named.resolve("initial-layout")).equals(new UUID(0,2).toString())
                    && !Files.exists(named.resolve("requests")),"Cold launch retains exactly its one layout intent, no default extra request");
            Process namedOne=child(named,"synthetic-075",new UUID(0,1)),namedTwo=child(named,"synthetic-075",new UUID(0,2));
            processes.add(namedOne); processes.add(namedTwo);
            check(namedOne.waitFor(10,TimeUnit.SECONDS) && namedTwo.waitFor(10,TimeUnit.SECONDS)
                    && namedOne.exitValue()==0 && namedTwo.exitValue()==0,"Concurrent hot layout launches forward and exit");
            List<String> requests=Files.readAllLines(named.resolve("requests"));
            check(requests.size()==2 && requests.contains("layout "+new UUID(0,1)) && requests.contains("layout "+new UUID(0,2)),
                    "Concurrent shortcuts deliver each distinct UUID exactly once");
            try { LaunchBroker.claim(named,"synthetic-075",UUID.randomUUID(),ignored -> 0); throw new AssertionError("Stale layout silently substituted"); }
            catch (IOException expected) { check(expected.getMessage().contains("unavailable"),"Deleted layout references fail actionably, without retrying forever"); }
            Files.createFile(named.resolve("stop")); check(namedOwner.waitFor(5,TimeUnit.SECONDS),"Named owner closes cleanly");
            Path independent=Files.createDirectory(root.resolve("independent"));
            var separate=LaunchBroker.claim(independent,"synthetic-074",() -> true);
            check(!separate.forwarded(),"Different data directory has an independent owner");
            separate.broker().close(); separate.store().close();
            Process owner=first.isAlive() ? first : second;
            owner.destroyForcibly(); check(owner.waitFor(5,TimeUnit.SECONDS),"Synthetic crashed owner terminated");
            check(Files.exists(directory.resolve("window-launch")),"Crash leaves realistic stale rendezvous metadata");
            var recovered=LaunchBroker.claim(directory,"synthetic-075",() -> true);
            check(!recovered.forwarded(),"OS lock, not stale port/version metadata, permits crash recovery");
            recovered.broker().close(); recovered.store().close();
            check(!Files.exists(directory.resolve("window-launch")),"Orderly close removes only owned rendezvous");
            Path delayed=Files.createDirectory(root.resolve("delayed"));
            StateStore locked=new StateStore(delayed);
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                Future<LaunchBroker.Claim> queued=worker.submit(() -> LaunchBroker.claim(delayed,"synthetic",() -> true));
                Thread.sleep(150); locked.close();
                var won=queued.get(5,TimeUnit.SECONDS);
                check(!won.forwarded(),"Startup/close election retries preserve the actual exclusive lock");
                won.broker().close(); won.store().close();
            } finally { worker.shutdownNow(); }
            System.out.println("Private multi-process launch/race/auth/idempotency/crash/lock checks passed: "+checks);
        } finally {
            for (Process process:processes) if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); }
            try (var files=Files.walk(root)) {
                for (Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
