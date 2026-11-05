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
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",
                System.getProperty("java.class.path"),LaunchBrokerSmoke.class.getName(),"--child",directory.toString(),identity)
                .redirectErrorStream(true).redirectOutput(directory.resolve("child-"+UUID.randomUUID()+".log").toFile()).start();
    }
    private static void child(String[] args) throws Exception {
        Path root=Path.of(args[1]);
        var claim=LaunchBroker.claim(root,args[2],() -> {
            try { Files.writeString(root.resolve("requests"),"window\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            return true;
        });
        if (claim.forwarded()) return;
        Files.writeString(root.resolve("owner"),Long.toString(ProcessHandle.current().pid()));
        try {
            while (!Files.exists(root.resolve("stop"))) Thread.sleep(20);
        } finally { claim.broker().close(); claim.store().close(); }
    }
    private static byte[] token(Path directory) throws IOException { return Arrays.copyOfRange(Files.readAllBytes(directory.resolve("window-launch")),8,40); }
    private static int request(Path directory,byte[] token,String identity,UUID id,boolean read) throws Exception {
        byte[] address=Files.readAllBytes(directory.resolve("window-launch"));
        int port=new DataInputStream(new ByteArrayInputStream(address,4,4)).readInt();
        try (Socket socket=new Socket(InetAddress.getByName("127.0.0.1"),port)) {
            socket.setSoTimeout(2000);
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            out.writeInt(0x46495348); out.write(token);
            out.write(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); out.flush();
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
