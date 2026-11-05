package pane5250.net;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** One socket and bounded outbound queue per pane; never waits for a socket on the EDT. */
public final class NetworkSession implements AutoCloseable {
    private final Socket socket = new Socket();
    private final ArrayBlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(128);
    private final Consumer<byte[]> records;
    private final Consumer<String> status;
    private volatile boolean closed;
    private volatile boolean connected;
    private volatile String allocatedDevice="",connectionDetails="";
    private Thread reader, writer;
    private byte[] pendingTicket;
    private final OutputStream queued = new OutputStream() {
        private final SensitiveBuffer pending = new SensitiveBuffer();
        @Override public synchronized void write(int value) throws IOException {
            if (closed) throw new IOException("Session is disconnected.");
            pending.write(value);
        }
        @Override public synchronized void write(byte[] value, int offset, int length) throws IOException {
            if (closed) throw new IOException("Session is disconnected.");
            pending.write(value, offset, length);
        }
        @Override public synchronized void flush() throws IOException {
            if (closed) throw new IOException("Session is disconnected.");
            if (pending.size() == 0) return;
            byte[] bytes = pending.toByteArray();
            pending.reset();
            if (!outbound.offer(bytes)) {
                Arrays.fill(bytes, (byte) 0);
                fail("Host is not reading; outbound queue filled. Session stopped.");
                throw new IOException("Outbound queue full.");
            }
        }
        @Override public synchronized void close() { pending.close(); }
    };

    public NetworkSession(Consumer<byte[]> records, Consumer<String> status) {
        this.records = records;
        this.status = status;
    }

    public OutputStream output() { return queued; }
    public boolean isConnected() { return connected && !closed; }
    public String allocatedDevice() { return allocatedDevice; }
    public String connectionDetails() { return connectionDetails; }

    public void connect(String host, int port, Map<String, String> environment) {
        connect(host,port,environment,null);
    }
    public synchronized void connect(String host,int port,Map<String,String> environment,byte[] ticket) {
        if (reader != null || closed) throw new IllegalStateException("Session cannot be reused.");
        if (ticket!=null && (ticket.length==0 || ticket.length>pane5250.security.Kerberos.MAX_TOKEN))
            throw new IllegalArgumentException("Kerberos token must be 1-16384 bytes.");
        Map<String, String> copy = new LinkedHashMap<>(environment);
        pendingTicket=ticket==null ? null : ticket.clone();
        reader = Thread.ofPlatform().daemon().name("tn5250-reader").start(() -> {
            try {
                socket.connect(new InetSocketAddress(host, port), 10000);
                if (closed) return;
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setSoTimeout(20000);
                startWriter();
                TelnetCodec prepared;
                synchronized (NetworkSession.this) {
                    if (closed) return;
                    prepared=new TelnetCodec(socket.getInputStream(),queued,copy,message -> {
                        String detail=connectionDetails+(connectionDetails.isEmpty() ? "" : "\n")+message;
                        connectionDetails=detail.substring(Math.max(0,detail.length()-4096));
                        System.getLogger(NetworkSession.class.getName()).log(System.Logger.Level.DEBUG,message);
                        if (!message.startsWith("Host startup I902")) status.accept(message);
                    },pendingTicket);
                    wipeTicket();
                }
                try (TelnetCodec codec = prepared) {
                    copy.clear();
                    boolean first = true;
                    while (!closed) {
                        byte[] record = codec.readRecord();
                        if (closed) return;
                        SwingUtilities.invokeAndWait(() -> {
                            if (!closed) records.accept(record);
                        });
                        if (first) {
                            socket.setSoTimeout(0);
                            connected = true;
                            allocatedDevice=codec.allocatedDevice();
                            connectionDetails+=(connectionDetails.isEmpty() ? "" : "\n")+"IBM-3477-FC"
                                    +(allocatedDevice.isEmpty() ? "" : " - device "+allocatedDevice)+codec.authenticationStatus();
                            status.accept("Connected");
                            first = false;
                        }
                    }
                }
            } catch (Exception e) {
                if (!closed) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    fail(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
                }
            } finally {
                copy.clear();
                close();
            }
        });
    }

    private synchronized void startWriter() {
        if (closed) return;
        writer = Thread.ofPlatform().daemon().name("tn5250-writer").unstarted(this::writeLoop);
        writer.start();
    }

    private void writeLoop() {
        try {
            OutputStream wire = socket.getOutputStream();
            while (!closed) {
                byte[] bytes = outbound.take();
                try {
                    wire.write(bytes);
                    wire.flush();
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed) fail("Write failed: " + e.getMessage());
        }
    }

    private void fail(String message) {
        status.accept("Disconnected - " + message);
        close();
    }
    private void wipeTicket() {
        if (pendingTicket!=null) Arrays.fill(pendingTicket,(byte)0);
        pendingTicket=null;
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            connected = false;
            wipeTicket();
        }
        // Queue failure calls close while holding its own lock; do not invert that lock order.
        try { socket.close(); }
        catch (IOException e) { status.accept("Socket close failed: " + e.getMessage()); }
        if (writer != null) writer.interrupt();
        byte[] bytes;
        while ((bytes = outbound.poll()) != null) Arrays.fill(bytes, (byte) 0);
        try { queued.close(); }
        catch (IOException e) { status.accept("Outbound cleanup failed: "+e.getMessage()); }
    }
}
