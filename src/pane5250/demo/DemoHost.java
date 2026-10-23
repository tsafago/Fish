package pane5250.demo;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import pane5250.net.TelnetCodec;

/** Loopback-only fixture host. Each connection maintains its own screen and request count. */
public final class DemoHost implements AutoCloseable {
    private final ServerSocket server;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Queue<String> failures = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;
    private volatile int connections;
    public DemoHost() throws IOException {
        server = new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
        Thread.ofPlatform().daemon().name("synthetic-host").start(() -> {
            while (!closed) {
                try {
                    Socket socket = server.accept();
                    connections++;
                    clients.add(socket);
                    Thread.ofPlatform().daemon().name("synthetic-session").start(() -> serve(socket));
                } catch (IOException e) {
                    if (!closed) failures.add("Accept failed: " + e.getMessage());
                }
            }
        });
    }
    public int port() { return server.getLocalPort(); }
    public int connectionCount() { return connections; }
    public List<String> failures() { return List.copyOf(failures); }
    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(15000);
            socket.setTcpNoDelay(true);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            byte[] invitation = {(byte)255,(byte)253,39,(byte)255,(byte)253,24,
                    (byte)255,(byte)250,24,1,(byte)255,(byte)240,
                    (byte)255,(byte)253,0,(byte)255,(byte)251,0,
                    (byte)255,(byte)253,25,(byte)255,(byte)251,25,
                    (byte)255,(byte)250,39,1,0,3,(byte)255,(byte)240};
            // Intentionally fragment Telnet triples to exercise real stream framing.
            for (byte value : invitation) { output.write(value); output.flush(); }
            String device = handshake(input);
            int kind = device.contains("DEMOA2") ? 2 : device.contains("DEMOA3") ? 3 : 1;
            output.write(TelnetCodec.frame(DemoScreens.screen(kind,false,"")));
            output.flush();
            socket.setSoTimeout(0);
            int count = 0;
            boolean wide = false;
            while (!closed && !socket.isClosed()) {
                byte[] record = clientRecord(input);
                count++;
                int aid = record.length > 12 ? record[12] & 255 : 0;
                if (aid == 0x35) wide = true;
                if (aid == 0x36) wide = false;
                String key = aid == 0xf1 ? "Enter" : aid >= 0x31 && aid <= 0x3c ? "F" + (aid - 0x30)
                        : aid >= 0xb1 && aid <= 0xbc ? "F" + (aid - 0xb0 + 12) : String.format("AID %02X",aid);
                String result = "Host received " + key + " - independent session request " + count;
                output.write(TelnetCodec.frame(DemoScreens.screen(kind,wide,result)));
                output.flush();
            }
        } catch (EOFException | SocketException e) {
            // Closing a pane or workspace normally ends its loopback session.
        } catch (IOException | RuntimeException e) {
            if (!closed) failures.add("Synthetic session failed: " + e.getMessage());
        } finally { clients.remove(socket); }
    }
    private static String handshake(InputStream input) throws IOException {
        boolean type = false, environment = false, binaryWill = false, binaryDo = false, eorWill = false, eorDo = false;
        String device = "";
        while (!(type && environment && binaryWill && binaryDo && eorWill && eorDo)) {
            if (required(input) != 255) throw new IOException("Client sent data before negotiation.");
            int command = required(input);
            int option = required(input);
            if (command == 250) {
                byte[] sub = sub(input);
                if (option == 24) {
                    if (!new String(sub,StandardCharsets.US_ASCII).equals("\0IBM-3477-FC"))
                        throw new IOException("Client did not request IBM-3477-FC.");
                    type = true;
                }
                if (option == 39) {
                    environment = true;
                    String text = new String(sub,StandardCharsets.US_ASCII);
                    if (text.contains("DEMOA2")) device = "DEMOA2";
                    else if (text.contains("DEMOA3")) device = "DEMOA3";
                }
            } else {
                if (command == 251 && option == 0) binaryWill = true;
                if (command == 253 && option == 0) binaryDo = true;
                if (command == 251 && option == 25) eorWill = true;
                if (command == 253 && option == 25) eorDo = true;
            }
        }
        return device;
    }
    public static byte[] clientRecord(InputStream input) throws IOException {
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        while (true) {
            int value = required(input);
            if (value == 255) {
                int command = required(input);
                if (command == 239) {
                    byte[] result = record.toByteArray();
                    TelnetCodec.validateRecord(result);
                    return result;
                }
                if (command == 255) record.write(255);
                else if (command == 250) { required(input); sub(input); }
                else if (command >= 251 && command <= 254) required(input);
                else throw new IOException("Unexpected client Telnet command.");
            } else record.write(value);
            if (record.size() > 65535) throw new IOException("Client record too large.");
        }
    }
    private static byte[] sub(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int value = required(input);
            if (value == 255) {
                int next = required(input);
                if (next == 240) return bytes.toByteArray();
                if (next != 255) throw new IOException("Invalid subnegotiation.");
            }
            bytes.write(value);
            if (bytes.size() > 4096) throw new IOException("Subnegotiation too large.");
        }
    }
    private static int required(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException();
        return value;
    }
    @Override public void close() {
        closed = true;
        try {
            server.close();
            for (Socket socket : clients) socket.close();
        } catch (IOException e) { failures.add("Synthetic host close failed: " + e.getMessage()); }
    }
}
