package org.grafana.beyla.instrumentations.util;

import java.nio.ByteBuffer;

public class ByteBufferExtractor {
    public static final int MAX_SIZE = 1024;

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
}
