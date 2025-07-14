package org.grafana.beyla.instrumentations.util;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class WeakConcurrentMapTest {
    @Test
    public void testWeakReferenceIsDropped() throws InterruptedException, NoSuchAlgorithmException {
        WeakConcurrentMap<SSLSession, String> map = new WeakConcurrentMap<>(true);
        System.setProperty("javax.net.ssl.sessionCacheSize", "0");

        addToMap(map);

        System.gc();

        await().atMost(60, TimeUnit.SECONDS).until(map::isEmpty);
        assertTrue(map.isEmpty(), "Map should be empty after key is GC'd");
    }

    private void addToMap(WeakConcurrentMap<SSLSession, String> map) {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) factory.createSocket("www.example.com", 443)) {
            socket.startHandshake();
            SSLSession session = socket.getSession();
            map.put(session, "value");

            assertEquals("value", map.get(session));

            // remove from the JDK internal cache, otherwise they keep
            // a soft cache reference that lasts for 24 hours
            session.invalidate();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}