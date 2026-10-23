package org.tn5250j.framework.transport;

/** The legacy upstream transport is not a supported connection entry point. */
public final class SocketConnector {
    public void setSSLType(String type) {
        if (type != null && !"NONE".equals(type)) throw new IllegalArgumentException("TLS is out of scope.");
    }
    public java.net.Socket createSocket(String host, int port) {
        throw new UnsupportedOperationException("Use Pane5250 NetworkSession.");
    }
}
