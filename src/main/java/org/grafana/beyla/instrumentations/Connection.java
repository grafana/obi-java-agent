package org.grafana.beyla.instrumentations;

import java.net.InetAddress;

public class Connection {
    private final InetAddress localAddress;
    private final int localPort;
    private final InetAddress remoteAddress;
    private final int remotePort;

    public Connection(InetAddress localAddress, int localPort, InetAddress remoteAddress, int remotePort) {
        this.localAddress = localAddress;
        this.localPort = localPort;
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
    }

    public InetAddress getLocalAddress() {
        return localAddress;
    }

    public int getLocalPort() {
        return localPort;
    }

    public InetAddress getRemoteAddress() {
        return remoteAddress;
    }

    public int getRemotePort() {
        return remotePort;
    }
}
