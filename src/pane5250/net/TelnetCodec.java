package pane5250.net;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** RFC 854/855/856/885/1091/1572/1205/4777 stream boundary, independent of TCP packets. */
public final class TelnetCodec implements AutoCloseable {
    private static final int IAC = 255, SE = 240, EOR = 239, SB = 250;
    private static final int WILL = 251, WONT = 252, DO = 253, DONT = 254;
    private final InputStream input;
    private final OutputStream output;
    private final Map<String, String> environment;
    private final Consumer<String> notice;
    private final boolean[] local = new boolean[256], remote = new boolean[256];
    private final boolean[] requestedRemote = new boolean[256];
    private boolean deviceSent,deviceRetry;
    private String allocatedDevice="";

    public TelnetCodec(InputStream input, OutputStream output, Map<String, String> environment,
                       Consumer<String> notice) {
        this.input = input;
        this.output = output;
        this.environment = new LinkedHashMap<>(environment);
        this.notice = notice;
    }

    public byte[] readRecord() throws IOException {
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (record.size() > 0) throw new EOFException("Host closed during a GDS record.");
                throw new EOFException("Host closed the connection."
                        +(environment.containsKey("DEVNAME") && allocatedDevice.isEmpty()
                        ? " Requested device "+environment.get("DEVNAME")+(deviceRetry ? " was requested again (possible collision)." : ".")
                        +" No startup rejection code was supplied." : ""));
            }
            if (value != IAC) {
                record.write(value);
            } else {
                int command = required();
                if (command == IAC) {
                    record.write(IAC);
                } else if (command == EOR) {
                    if (record.size() == 0) continue;
                    byte[] bytes = record.toByteArray();
                    validateRecord(bytes);
                    if ((bytes[4]&0x80)!=0) {
                        StartupResponse startup=StartupResponse.parse(bytes);
                        notice.accept(startup.diagnostic(environment.getOrDefault("DEVNAME","")));
                        allocatedDevice=startup.device();
                        record.reset();
                        continue;
                    }
                    if (environment.containsKey("DEVNAME") && !deviceSent)
                        throw new IOException("Host began presentation without negotiating requested device "+environment.get("DEVNAME")+". No automatic-name fallback was used.");
                    return bytes;
                } else if (command >= WILL && command <= DONT) {
                    option(command, required());
                } else if (command == SB) {
                    subnegotiation();
                } else if (command == 241 || command == 242 || command == 249) {
                    // RFC 854 NOP, Data Mark and Go Ahead carry no presentation data.
                } else if (command == 246) {
                    send(new byte[]{(byte) IAC, (byte) 241});
                } else {
                    throw new IOException("Unsupported Telnet command " + command + "; session stopped.");
                }
            }
            if (record.size() > 65535) throw new IOException("GDS record exceeds its 16-bit length.");
        }
    }

    public static void validateRecord(byte[] bytes) throws IOException {
        if (bytes.length < 10) throw new IOException("GDS record shorter than its header.");
        int length = (bytes[0] & 255) << 8 | bytes[1] & 255;
        if (length != bytes.length) throw new IOException("GDS length does not match EOR boundary.");
        if ((bytes[2] & 255) != 0x12 || (bytes[3] & 255) != 0xa0)
            throw new IOException("Unsupported GDS record type.");
        int header = bytes[6] & 255;
        if (header < 4 || 6 + header > length) throw new IOException("Invalid GDS variable header.");
        if ((bytes[7] & 0x80) != 0) throw new IOException("Host sent a GDS negative response.");
    }

    private int required() throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException("Host closed during Telnet negotiation.");
        return value;
    }

    private void option(int command, int option) throws IOException {
        boolean supportedLocal = option == 0 || option == 3 || option == 24 || option == 25 || option == 39;
        boolean supportedRemote = option == 0 || option == 3 || option == 25;
        if (command == DO) {
            if (!supportedLocal) {
                reply(WONT, option);
            } else if (!local[option]) {
                local[option] = true;
                reply(WILL, option);
                if ((option == 0 || option == 25) && !remote[option] && !requestedRemote[option]) {
                    requestedRemote[option] = true;
                    reply(DO, option);
                }
            }
        } else if (command == WILL) {
            if (!supportedRemote) {
                reply(DONT, option);
            } else if (!remote[option]) {
                remote[option] = true;
                if (!requestedRemote[option]) reply(DO, option);
                requestedRemote[option] = false;
            }
        } else if (command == DONT) {
            if (local[option]) reply(WONT, option);
            local[option] = false;
            if (option == 0 || option == 25) throw new IOException("Host refused required BINARY/EOR option.");
            if (option == 39 && environment.containsKey("IBMSUBSPW"))
                notice.accept("Host declined NEW-ENVIRON; automatic sign-on was not negotiated. Sign on manually.");
            if (option==39 && environment.containsKey("DEVNAME"))
                throw new IOException("Host declined NEW-ENVIRON; requested device "+environment.get("DEVNAME")+" cannot be negotiated. No automatic-name fallback was used.");
        } else {
            if (remote[option]) reply(DONT, option);
            remote[option] = false;
            requestedRemote[option] = false;
            if (option == 0 || option == 25) throw new IOException("Host refused required BINARY/EOR option.");
        }
    }

    private void reply(int command, int option) throws IOException {
        send(new byte[]{(byte) IAC, (byte) command, (byte) option});
    }

    private void subnegotiation() throws IOException {
        int option = required();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        while (true) {
            int value = required();
            if (value == IAC) {
                int next = required();
                if (next == SE) break;
                if (next != IAC) throw new IOException("Malformed Telnet subnegotiation.");
            }
            payload.write(value);
            if (payload.size() > 4096) throw new IOException("Telnet subnegotiation exceeds 4096 bytes.");
        }
        byte[] data = payload.toByteArray();
        if (option == 24 && local[24]) {
            if (data.length != 1 || data[0] != 1) throw new IOException("Malformed terminal-type request.");
            ByteArrayOutputStream type = new ByteArrayOutputStream();
            type.write(0);
            type.writeBytes("IBM-3477-FC".getBytes(StandardCharsets.US_ASCII));
            sendSub(24, type.toByteArray());
        } else if (option == 39 && local[39]) {
            if (data.length == 0 || data[0] != 1) throw new IOException("Unsupported NEW-ENVIRON request.");
            sendEnvironment(requestedNames(data));
        } else {
            // An option we rejected may still arrive; it is length-delimited, not terminal data.
            notice.accept("Ignored subnegotiation for unnegotiated Telnet option " + option + ".");
        }
    }

    private record Requested(int type,String name) {}
    private static java.util.List<Requested> requestedNames(byte[] data) throws IOException {
        if (data.length==1) return null;
        java.util.List<Requested> names = new ArrayList<>();
        ByteArrayOutputStream name = new ByteArrayOutputStream();
        int type=-1;
        for (int i = 1; i < data.length; i++) {
            int value = data[i] & 255;
            if (value == 0 || value == 3) {
                if (type!=-1) names.add(new Requested(type,name.toString(StandardCharsets.ISO_8859_1)));
                name.reset();
                type=value;
            } else {
                if (type==-1 || value == 1) throw new IOException("Malformed NEW-ENVIRON SEND list.");
                if (value == 2) {
                    if (++i >= data.length) throw new IOException("Truncated NEW-ENVIRON escape.");
                    value = data[i] & 255;
                }
                name.write(value);
            }
        }
        if (type!=-1) names.add(new Requested(type,name.toString(StandardCharsets.ISO_8859_1)));
        return names;
    }

    private void sendEnvironment(java.util.List<Requested> requested) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(0);
        if (requested==null) requested=java.util.List.of(new Requested(0,""),new Requested(3,""));
        if (deviceSent && requested.size()==1 && requested.getFirst().name.equals("DEVNAME")) {
            deviceRetry=true;
            notice.accept("Host requested DEVNAME again; possible device collision. Requested name "+environment.get("DEVNAME")+" is retained; no automatic retry name was invented.");
        }
        for (Requested request : requested) {
            if (request.name.isEmpty()) {
                for (var entry : environment.entrySet()) {
                    if ((entry.getKey().equals("USER") ? 0 : 3)==request.type)
                        environmentValue(data,request.type,entry.getKey(),entry.getValue());
                }
            } else {
                String name=request.name.startsWith("IBMRSEED") ? "IBMRSEED" : request.name;
                boolean sameType=(name.equals("USER") ? 0 : 3)==request.type;
                environmentValue(data,request.type,name,sameType ? environment.get(name) : null);
            }
        }
        if (data.size() > 1024) throw new IOException("RFC 4777 environment exceeds 1024 bytes.");
        sendSub(39, data.toByteArray());
    }
    private void environmentValue(ByteArrayOutputStream data,int type,String name,String value) throws IOException {
        data.write(type); escapedEnvironment(data,name);
        if (value!=null) { data.write(1); escapedEnvironment(data,value); }
        if (name.equals("DEVNAME") && value!=null) deviceSent=true;
    }
    public String allocatedDevice() { return allocatedDevice; }

    private static void escapedEnvironment(ByteArrayOutputStream out, String value) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            int c = value.charAt(i);
            if (c > 255) throw new IOException("NEW-ENVIRON requires byte-valued strings; profile sign-on values must be US-ASCII.");
            if (c <= 3) out.write(2);
            out.write(c);
        }
    }

    private void sendSub(int option, byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[]{(byte) IAC, (byte) SB, (byte) option});
        for (byte value : data) {
            bytes.write(value);
            if ((value & 255) == IAC) bytes.write(IAC);
        }
        bytes.write(IAC);
        bytes.write(SE);
        send(bytes.toByteArray());
    }

    private void send(byte[] bytes) throws IOException {
        synchronized (output) {
            output.write(bytes);
            output.flush();
        }
    }

    public static byte[] frame(byte[] record) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte value : record) {
            bytes.write(value);
            if ((value & 255) == IAC) bytes.write(IAC);
        }
        bytes.write(IAC);
        bytes.write(EOR);
        return bytes.toByteArray();
    }

    @Override public void close() { environment.clear(); }
}
