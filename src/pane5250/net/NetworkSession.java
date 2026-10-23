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
    private Thread reader, writer;
    private final OutputStream queued = new OutputStream() {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        @Override public synchronized void write(int value) { pending.write(value); }
        @Override public synchronized void write(byte[] value, int offset, int length) {
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
    };

    public NetworkSession(Consumer<byte[]> records, Consumer<String> status) {
        this.records = records;
        this.status = status;
    }

    public OutputStream output() { return queued; }
    public boolean isConnected() { return connected && !closed; }

    public void connect(String host, int port, Map<String, String> environment) {
        if (reader != null) throw new IllegalStateException("Session cannot be reused.");
        Map<String, String> copy = new LinkedHashMap<>(environment);
        reader = Thread.ofPlatform().daemon().name("tn5250-reader").start(() -> {
            try {
                socket.connect(new InetSocketAddress(host, port), 10000);
                if (closed) return;
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setSoTimeout(20000);
                startWriter();
                try (TelnetCodec codec = new TelnetCodec(socket.getInputStream(), queued, copy, status)) {
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
                            status.accept("Connected - IBM-3477-FC");
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

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        connected = false;
        try { socket.close(); }
        catch (IOException e) { status.accept("Socket close failed: " + e.getMessage()); }
        if (writer != null) writer.interrupt();
        byte[] bytes;
        while ((bytes = outbound.poll()) != null) Arrays.fill(bytes, (byte) 0);
    }
}
