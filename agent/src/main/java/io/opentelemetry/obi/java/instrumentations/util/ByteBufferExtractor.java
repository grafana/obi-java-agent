package io.opentelemetry.obi.java.instrumentations.util;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ByteBufferExtractor {
    public static final int MAX_SIZE = 1024;
    public static final int MAX_KEY_SIZE = 64;

    public static ByteBuffer flattenDstByteBufferArray(ByteBuffer[] dsts, int len) {
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

    public static ByteBuffer flattenSrcByteBufferArray(ByteBuffer[] srcs) {
        ByteBuffer dstBuffer = ByteBuffer.allocate(MAX_SIZE);
        if (srcs == null) {
            return dstBuffer;
        }
        int consumed = 0;
        for (int i = 0; i < srcs.length && consumed <= dstBuffer.limit(); i++) {
            int oldPos = srcs[i].position();
            int oldLimit = srcs[i].limit();

            if (srcs[i].remaining() <= dstBuffer.remaining()) {
                dstBuffer.put(srcs[i]);
            } else {
                ByteBuffer slice = srcs[i].slice();
                slice.limit(Math.min(slice.remaining(), dstBuffer.remaining()));
                dstBuffer.put(slice);
            }
            srcs[i].position(oldPos);
            srcs[i].limit(oldLimit);
            consumed += oldPos;
        }

        return dstBuffer;
    }

    public static ByteBuffer dstBufferArray(ByteBuffer dst, int len) {
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

    public static ByteBuffer srcBufferArray(ByteBuffer src, int len) {
        int bufSize = Math.min(len, MAX_SIZE);
        ByteBuffer dstBuffer = ByteBuffer.allocate(bufSize);
        if (src != null) {
            int oldPos = src.position();
            int oldLimit = src.limit();
            ByteBuffer slice = src.slice();
            slice.limit(bufSize);
            dstBuffer.put(slice);
            src.position(oldPos);
            src.limit(oldLimit);
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

    public static String srcBufferKey(ByteBuffer buf) {
        int oldPosition = buf.position();
        int oldLimit = buf.limit();

        int keySize = Math.min(buf.position(), MAX_KEY_SIZE);
        byte[] bytes = new byte[keySize];
        buf.get(bytes);

        buf.position(oldPosition);
        buf.limit(oldLimit);

        return Arrays.toString(bytes);
    }

}
