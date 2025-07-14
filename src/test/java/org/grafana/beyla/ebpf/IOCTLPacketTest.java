package org.grafana.beyla.ebpf;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import org.grafana.beyla.instrumentations.Connection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IOCTLPacketTest {

    @Test
    void testWritePacketPrefixWithNullSocket() {
        Pointer mem = new Memory(64);
        int off = 0;
        OperationType type = OperationType.SEND;
        int bufLen = 10;

        int newOff = IOCTLPacket.writePacketPrefix(mem, off, type, (java.net.Socket) null, bufLen);

        assertEquals(IOCTLPacket.packetPrefixSize, newOff);
        assertEquals(type.code, mem.getByte(0));
        assertEquals(bufLen, mem.getInt(1 + 36));
    }

    @Test
    void testWritePacketPrefixWithNullConnection() {
        Pointer mem = new Memory(64);
        int off = 0;
        OperationType type = OperationType.RECEIVE;
        int bufLen = 20;

        int newOff = IOCTLPacket.writePacketPrefix(mem, off, type, (Connection) null, bufLen);

        assertEquals(IOCTLPacket.packetPrefixSize, newOff);
        assertEquals(type.code, mem.getByte(0));
        assertEquals(bufLen, mem.getInt(1 + 36));
    }

    @Test
    void testWritePacketPrefixWithConnection() {
        Pointer mem = new Memory(64);
        int off = 0;
        OperationType type = OperationType.RECEIVE;
        int bufLen = 20;

        int newOff = IOCTLPacket.writePacketPrefix(mem, off, type, (Connection) null, bufLen);

        assertEquals(IOCTLPacket.packetPrefixSize, newOff);
        assertEquals(type.code, mem.getByte(0));
        assertEquals(bufLen, mem.getInt(1 + 36));
    }

    @Test
    void testWritePacketBuffer() {
        Pointer mem = new Memory(32);
        int off = 5;
        byte[] buf = {1, 2, 3, 4};

        int newOff = IOCTLPacket.writePacketBuffer(mem, off, buf);

        assertEquals(off + buf.length, newOff);
        for (int i = 0; i < buf.length; i++) {
            assertEquals(buf[i], mem.getByte(off + i));
        }
    }
}