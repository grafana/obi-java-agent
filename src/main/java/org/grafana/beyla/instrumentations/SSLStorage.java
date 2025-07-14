package org.grafana.beyla.instrumentations;

import org.grafana.beyla.instrumentations.util.WeakConcurrentMap;

import javax.net.ssl.SSLSession;

public class SSLStorage {
    public static final ThreadLocal<Connection> threadConnection = new ThreadLocal<>();
    public static final ThreadLocal<BytesWithLen> threadBuffer = new ThreadLocal<>();
    public static final ThreadLocal<SSLSession> threadSSLSession = new ThreadLocal<>();

    private static final WeakConcurrentMap<SSLSession, Connection> sslConnections = new WeakConcurrentMap<>(true);

    public static Connection getConnectionForSession(SSLSession session) {
        return sslConnections.get(session);
    }

    public static void setConnectionForSession(SSLSession session, Connection c) {
        sslConnections.put(session, c);
    }

    public static void cleanupConnectionForSession(SSLSession session) {
        sslConnections.remove(session);
    }
}
