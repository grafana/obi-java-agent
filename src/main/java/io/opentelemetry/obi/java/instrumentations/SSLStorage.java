package io.opentelemetry.obi.java.instrumentations;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import javax.net.ssl.SSLEngine;

public class SSLStorage {
    public static boolean debugOn = false;

    private static final int MAX_CONCURRENT = 5_000;
    private static final Cache<SSLEngine, Connection> sslConnections = Caffeine.newBuilder().maximumSize(MAX_CONCURRENT).build();
    private static final Cache<String, BytesWithLen> bufToBuf = Caffeine.newBuilder().maximumSize(MAX_CONCURRENT).build();

    private static final Cache<String, Connection> bufConn = Caffeine.newBuilder().maximumSize(MAX_CONCURRENT).build();;
    private static final Cache<Connection, Connection> activeConnections = Caffeine.newBuilder().maximumSize(MAX_CONCURRENT).build();

    public static Connection getConnectionForSession(SSLEngine session) {
        return sslConnections.getIfPresent(session);
    }

    public static void setConnectionForSession(SSLEngine session, Connection c) {
        sslConnections.put(session, c);
    }

    public static Connection getConnectionForBuf(String buf) {
        return bufConn.getIfPresent(buf);
    }

    public static boolean connectionUntracked(Connection c) {
        return activeConnections.getIfPresent(c) == null;
    }

    public static Connection getActiveConnection(Connection c) {
        return activeConnections.getIfPresent(c);
    }

    public static void setConnectionForBuf(String buf, Connection c) {
        c.setBufferKey(buf);
        bufConn.put(buf, c);
        activeConnections.put(c, c);
    }

    public static void cleanupConnectionBufMapping(Connection c) {
        bufConn.invalidate(c.getBufferKey());
        activeConnections.invalidate(c);
    }

    public static void setBufferMapping(String encrypted, BytesWithLen plain) {
        bufToBuf.put(encrypted, plain);
    }

    public static BytesWithLen getUnencryptedBuffer(String encrypted) {
        return bufToBuf.getIfPresent(encrypted);
    }

    public static void removeBufferMapping(String encrypted) {
        bufToBuf.invalidate(encrypted);
    }
}
