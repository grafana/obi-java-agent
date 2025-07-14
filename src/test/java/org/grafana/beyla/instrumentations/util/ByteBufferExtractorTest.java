package org.grafana.beyla.instrumentations.util;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class ByteBufferExtractorTest {

    @Test
    void testFlattenByteBufferArray() {
        ByteBuffer b1 = ByteBuffer.allocate(4);
        ByteBuffer b2 = ByteBuffer.allocate(4);
        b1.put(new byte[]{1, 2, 3, 4});
        b2.put(new byte[]{5, 6, 7, 8});

        ByteBuffer[] buffers = new ByteBuffer[]{b1, b2};
        int totalLen = b1.position() + b2.position();

        ByteBuffer result = ByteBufferExtractor.flattenByteBufferArray(buffers, totalLen);

        result.flip();
        byte[] expected = {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] actual = new byte[result.remaining()];
        result.get(actual);

        assertArrayEquals(expected, actual);
    }

    @Test
    void testFlattenByteBufferArrayWithLimit() {
        ByteBuffer b1 = ByteBuffer.allocate(4);
        ByteBuffer b2 = ByteBuffer.allocate(4);
        b1.put(new byte[]{1, 2, 3, 4});
        b2.put(new byte[]{5, 6, 7, 8});

        ByteBuffer[] buffers = new ByteBuffer[]{b1, b2};
        int totalLen = 5; // less than total data

        ByteBuffer result = ByteBufferExtractor.flattenByteBufferArray(buffers, totalLen);

        result.flip();
        byte[] actual = new byte[result.remaining()];
        result.get(actual);

        assertEquals(5, actual.length);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, actual);
    }

    @Test
    void testFlattenByteBufferArrayWithZeroLen() {
        ByteBuffer[] buffers = new ByteBuffer[] {
                ByteBuffer.allocate(4)
        };
        ByteBuffer result = ByteBufferExtractor.flattenByteBufferArray(buffers, 0);
        assertNotNull(result);
        assertEquals(0, result.capacity());
        assertEquals(0, result.position());
        assertEquals(0, result.limit());
    }

    @Test
    void testFlattenByteBufferArrayWithNullDsts() {
        ByteBuffer result = ByteBufferExtractor.flattenByteBufferArray(null, 10);
        assertNotNull(result);
        assertEquals(10, result.capacity());
        assertEquals(0, result.position());
        assertEquals(10, result.limit());
    }
}