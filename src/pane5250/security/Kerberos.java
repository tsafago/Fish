package pane5250.security;

import java.io.IOException;
import java.net.*;
import java.util.*;
import pane5250.NativeLibrary;
import pane5250.model.Profile;

/** RFC 4777 initial GSS token, using only the signed-in Windows Kerberos SSP. */
public final class Kerberos {
    public static final int MAX_TOKEN=16384;
    @FunctionalInterface public interface Provider { byte[] ticket(String principal) throws IOException; }
    @FunctionalInterface public interface Resolver { String server(String endpoint) throws IOException; }
    private Kerberos() {}

    public static String principal(String endpoint,String override,Resolver resolver) throws IOException {
        String server=Profile.serverName(override);
        if (server.isEmpty()) server=Profile.serverName(resolver.server(endpoint));
        if (server.isEmpty()) throw new IOException("Set Kerberos server to the IBM i's fully qualified DNS name.");
        return "krbsvr400/"+server;
    }
    public static String resolve(String endpoint) throws IOException {
        if (!endpoint.matches("[0-9.]+") && !endpoint.contains(":")) {
            try { return Profile.serverName(endpoint); }
            catch (IllegalArgumentException e) { throw new IOException("Use a fully qualified host name, or set Kerberos server separately.",e); }
        }
        InetAddress address=InetAddress.getByName(endpoint);
        String server=address.getCanonicalHostName();
        try {
            server=Profile.serverName(server);
            if (server.isEmpty() || Arrays.stream(InetAddress.getAllByName(server)).noneMatch(address::equals))
                throw new IOException("The reverse DNS name does not resolve back to this IP. Set Kerberos server explicitly.");
            return server;
        } catch (IllegalArgumentException e) {
            throw new IOException("This IP has no usable Kerberos DNS identity. Set Kerberos server to the IBM i's fully qualified DNS name; ask your administrator for its krbsvr400 service name.",e);
        }
    }
    public static boolean available() {
        NativeLibrary.load();
        return available0();
    }
    public static byte[] ticket(String principal) throws IOException {
        NativeLibrary.load();
        if (!principal.startsWith("krbsvr400/") || !principal.equals("krbsvr400/"+Profile.serverName(principal.substring(10))))
            throw new IOException("Invalid IBM i Kerberos service identity.");
        return ticket0(principal);
    }
    private static native boolean available0();
    private static native byte[] ticket0(String principal) throws IOException;
}
