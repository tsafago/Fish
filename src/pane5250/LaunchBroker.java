package pane5250;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import pane5250.model.StateStore;

/** One locked owner per data directory. IPC accepts only new-window intent and an optional saved UUID. */
public final class LaunchBroker implements AutoCloseable {
    private static final int MAGIC=0x46495348;
    private final Path file;
    private final byte[] token=new byte[32],version;
    private final ServerSocket server;
    private final Function<UUID,Integer> open;
    private volatile boolean closed;
    public record Claim(StateStore store,LaunchBroker broker) {
        public boolean forwarded() { return store==null; }
    }
    private record Address(int port,byte[] token,byte[] version) {}

    public static Claim claim(Path directory,String identity,BooleanSupplier open) throws IOException {
        return claim(directory,identity,null,id -> open.getAsBoolean() ? 1 : 0);
    }
    public static Claim claim(Path directory,String identity,UUID layout,Function<UUID,Integer> open) throws IOException {
        Path root=directory.toAbsolutePath().normalize();
        UUID request=UUID.randomUUID();
        long deadline=System.nanoTime()+8_000_000_000L;
        do {
            try {
                StateStore store=new StateStore(root);
                try { return new Claim(store,new LaunchBroker(root,identity,open)); }
                catch (IOException e) { store.close(); throw e; }
            } catch (StateStore.InUseException busy) {
                Path address=root.resolve("window-launch");
                if (Files.exists(address,LinkOption.NOFOLLOW_LINKS)) {
                    Address target;
                    try { target=readAddress(address); }
                    catch (NoSuchFileException replaced) { continue; }
                    try (Socket socket=new Socket()) {
                        socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),target.port),500);
                        socket.setSoTimeout(10_000);
                        DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                        out.writeInt(MAGIC); out.write(target.token); out.write(version(identity));
                        out.writeLong(request.getMostSignificantBits()); out.writeLong(request.getLeastSignificantBits());
                        out.writeByte(layout==null ? 0 : 1);
                        if (layout!=null) { out.writeLong(layout.getMostSignificantBits()); out.writeLong(layout.getLeastSignificantBits()); }
                        out.flush();
                        socket.shutdownOutput();
                        int result=new DataInputStream(socket.getInputStream()).readInt();
                        if (result==MAGIC) return new Claim(null,null);
                        if (result==3) throw new IOException("Another Fish version or launch mode is running. Close its windows before opening this version.");
                        if (result==2) throw new IOException("Saved layout is unavailable. Open Fish normally and use Panes > Saved layouts / shortcuts to recreate or update this shortcut.");
                        if (result!=0) throw new IOException("The running Fish instance rejected the new-window request.");
                    } catch (SocketException | SocketTimeoutException | EOFException stale) {
                        // The file can outlive a crash, but only the OS file lock elects a new owner.
                    }
                }
                try { Thread.sleep(80); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Window launch interrupted.",e); }
            }
        } while (System.nanoTime()<deadline);
        throw new IOException("Fish is still starting or closing. Try opening it again shortly. If a different version is running, close its windows first.");
    }
    private LaunchBroker(Path directory,String identity,Function<UUID,Integer> open) throws IOException {
        this.open=open; file=directory.resolve("window-launch"); version=version(identity);
        new SecureRandom().nextBytes(token);
        server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        Path temporary=Files.createTempFile(directory,"window-launch-",".tmp");
        try {
            protect(temporary);
            try (DataOutputStream out=new DataOutputStream(Files.newOutputStream(temporary))) {
                out.writeInt(MAGIC); out.writeInt(server.getLocalPort()); out.write(token); out.write(version);
            }
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { server.close(); throw e; }
        finally { Files.deleteIfExists(temporary); }
        Thread.ofPlatform().daemon().name("fish-window-launch").start(this::listen);
    }
    private static byte[] version(String identity) {
        try { return MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void protect(Path path) throws IOException {
        AclFileAttributeView acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if (acl!=null) {
            UserPrincipal owner=currentUser(path);
            Files.setOwner(path,owner);
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        } else Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
    }
    private static UserPrincipal currentUser(Path path) throws IOException {
        String name=System.getProperty("user.name");
        String domain=System.getenv("USERDOMAIN");
        if (System.getProperty("os.name").startsWith("Windows") && domain!=null && !domain.isBlank() && !name.contains("\\"))
            name=domain+"\\"+name;
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name);
    }
    private static Address readAddress(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Fish launch metadata must not be a symbolic link.");
            UserPrincipal owner=Files.getOwner(file,LinkOption.NOFOLLOW_LINKS);
            if (!owner.equals(currentUser(file))) throw new IOException("Fish launch metadata belongs to another Windows user.");
            AclFileAttributeView acl=Files.getFileAttributeView(file,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if (acl!=null) {
                if (acl.getAcl().stream().anyMatch(a -> a.type()==AclEntryType.ALLOW && !a.principal().equals(owner)))
                    throw new IOException("Fish launch metadata is not restricted to its Windows user.");
            } else if (!Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")))
                throw new IOException("Fish launch metadata permissions are not private.");
            try (InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes=input.readNBytes(73);
                if (bytes.length!=72) throw new IOException("Invalid Fish launch metadata.");
                DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
                if (in.readInt()!=MAGIC) throw new IOException("Invalid Fish launch protocol.");
                int port=in.readInt();
                if (port<1 || port>65535) throw new IOException("Invalid Fish launch port.");
                return new Address(port,in.readNBytes(32),in.readNBytes(32));
            }
    }
    private void listen() {
        record Accepted(UUID layout,long time) {}
        Map<UUID,Accepted> acceptedRequests=new LinkedHashMap<>();
        while (!closed) {
            try (Socket socket=server.accept()) {
                socket.setSoTimeout(1500);
                if (!socket.getInetAddress().isLoopbackAddress()) continue;
                DataInputStream in=new DataInputStream(socket.getInputStream());
                if (in.readInt()!=MAGIC || !MessageDigest.isEqual(in.readNBytes(32),token)) continue;
                byte[] requestedVersion=in.readNBytes(32);
                UUID request=new UUID(in.readLong(),in.readLong());
                int mode=in.readUnsignedByte();
                if (mode!=0 && mode!=1) continue;
                UUID layout=mode==1 ? new UUID(in.readLong(),in.readLong()) : null;
                if (in.read()!=-1) continue;
                if (!MessageDigest.isEqual(requestedVersion,version)) {
                    new DataOutputStream(socket.getOutputStream()).writeInt(3); continue;
                }
                long now=System.nanoTime();
                acceptedRequests.values().removeIf(value -> now-value.time()>30_000_000_000L);
                boolean accepted=acceptedRequests.containsKey(request);
                if (accepted && !Objects.equals(acceptedRequests.get(request).layout(),layout)) continue;
                int result=accepted ? 1 : 0;
                if (!accepted && !closed) {
                    if (acceptedRequests.size()>=256) continue;
                    result=open.apply(layout); accepted=result==1;
                    if (accepted) acceptedRequests.put(request,new Accepted(layout,now));
                }
                DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                out.writeInt(accepted ? MAGIC : result==2 ? 2 : 0); out.flush();
            } catch (IOException e) {
                if (!closed && !(e instanceof SocketTimeoutException || e instanceof EOFException || e instanceof SocketException))
                    System.getLogger(LaunchBroker.class.getName()).log(System.Logger.Level.WARNING,"Window launch request failed: "+e.getMessage());
            } catch (RuntimeException e) {
                System.getLogger(LaunchBroker.class.getName()).log(System.Logger.Level.ERROR,"Window launch failed: "+e.getMessage());
            }
        }
    }
    @Override public void close() throws IOException {
        closed=true; server.close();
        if (Files.exists(file,LinkOption.NOFOLLOW_LINKS) && MessageDigest.isEqual(readAddress(file).token,token)) Files.delete(file);
        Arrays.fill(token,(byte)0);
    }
}
