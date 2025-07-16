package io.opentelemetry.obi.java.ebpf;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import io.opentelemetry.obi.java.instrumentations.Connection;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionInfoTest {
    @Test
    void testWriteSendConnectionInfoWithConnection() throws Exception {
        Pointer mem = new Memory(64);
        int off = 0;

        InetAddress local = InetAddress.getByAddress(null, new byte[]{ 127, 0, 0, 1});
        InetAddress remote = InetAddress.getByAddress(null, new byte[]{ 127, 0, 0, 2});
        Connection conn = new Connection(local, 1234, remote, 5678);

        int newOff = ConnectionInfo.writeSendConnectionInfo(mem, off, conn);

        assertEquals(off + 36, newOff);
        // Optionally, check that the IP bytes are written at the expected offset
        byte[] ipBytes = remote.getAddress();
        for (int i = 0; i < ipBytes.length; i++) {
            assertEquals(ipBytes[i], mem.getByte(off + i + 16 + 12)); // adjust offset to the second element + ipv6 offset
        }
    }

    @Test
    void testWriteReceiveConnectionInfoWithConnection() throws Exception {
        Pointer mem = new Memory(64);
        int off = 0;

        InetAddress local = InetAddress.getByAddress(null, new byte[]{ 127, 0, 0, 1});
        InetAddress remote = InetAddress.getByAddress(null, new byte[]{ 127, 0, 0, 2});
        Connection conn = new Connection(local, 1234, remote, 5678);

        int newOff = ConnectionInfo.writeRecvConnectionInfo(mem, off, conn);

        assertEquals(off + 36, newOff);
        // Optionally, check that the IP bytes are written at the expected offset
        byte[] ipBytes = remote.getAddress();
        for (int i = 0; i < ipBytes.length; i++) {
            assertEquals(ipBytes[i], mem.getByte(off + i + 12)); // adjust offset to the first element + ipv6 offset
        }
    }
}