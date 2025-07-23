package io.opentelemetry.obi.java.instrumentations.util;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ByteBufferExtractor {
    public static final int MAX_SIZE = 1024;
    public static final int MAX_KEY_SIZE = 64;

    public static ByteBuffer flattenByteBufferArray(ByteBuffer[] dsts, int len) {
        ByteBuffer dstBuffer = ByteBuffer.allocate(Math.min(len, MAX_SIZE));
        if (dsts == null) {
            return dstBuffer;
        }
        int consumed = 0;
        for (int i = 0; i < dsts.length && consumed <= dstBuffer.limit(); i++) {
            int oldPos = dsts[i].position();
            dsts[i].flip();

            if (dsts[i].remaining() <= dstBuffer.remaining()) {
                dstBuffer.put(dsts[i]);
            } else {
                ByteBuffer slice = dsts[i].slice();
                slice.limit(Math.min(slice.remaining(), dstBuffer.remaining()));
                dstBuffer.put(slice);
            }
            dsts[i].position(oldPos);
            consumed += oldPos;
        }

        return dstBuffer;
    }

    public static ByteBuffer bufferArray(ByteBuffer dst, int len) {
        int bufSize = Math.min(len, MAX_SIZE);
        ByteBuffer dstBuffer = ByteBuffer.allocate(bufSize);
        if (dst != null) {
            int oldPos = dst.position();
            dst.flip();
            ByteBuffer slice = dst.slice();
            slice.limit(bufSize);
            dstBuffer.put(slice);
            dst.position(oldPos);
        }

        return dstBuffer;
    }

    public static String bufferKey(ByteBuffer buf) {
        int oldPosition = buf.position();
        int oldLimit = buf.limit();

        int keySize = Math.min(buf.position(), MAX_KEY_SIZE);
        buf.flip();
        byte[] bytes = new byte[keySize];
        buf.get(bytes);

        buf.position(oldPosition);
        buf.limit(oldLimit);

        return Arrays.toString(bytes);
    }
}
