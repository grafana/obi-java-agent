package io.opentelemetry.obi.java.instrumentations;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import io.opentelemetry.obi.java.Agent;
import io.opentelemetry.obi.java.ebpf.IOCTLPacket;
import io.opentelemetry.obi.java.ebpf.OperationType;
import io.opentelemetry.obi.java.instrumentations.util.ByteBufferExtractor;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class SSLEngineInst {
    public static ElementMatcher<? super TypeDescription> type() {
        return ElementMatchers.is(SSLEngine.class);
    }

    public static AgentBuilder.Transformer transformer() {
        return (builder, type, classLoader, module, protectionDomain) ->
                builder
                        .visit(Advice.to(UnwrapAdvice.class)
                                .on(ElementMatchers
                                        .named("unwrap")
                                        .and(ElementMatchers.takesArguments(2))
                                        .and(ElementMatchers.takesArgument(1, ByteBuffer.class))
                                ))
                        .visit(Advice.to(UnwrapAdviceArray.class)
                                .on(ElementMatchers
                                        .named("unwrap")
                                        .and(ElementMatchers.takesArguments(2))
                                        .and(ElementMatchers.takesArgument(1, ByteBuffer[].class))
                                ))
                        .visit(Advice.to(WrapAdvice.class)
                                .on(ElementMatchers
                                        .named("wrap")
                                        .and(ElementMatchers.takesArguments(2))
                                        .and(ElementMatchers.takesArgument(0, ByteBuffer.class))
                                )
                        )
                        .visit(Advice.to(WrapAdviceArray.class)
                                .on(ElementMatchers
                                        .named("wrap")
                                        .and(ElementMatchers.takesArguments(2))
                                        .and(ElementMatchers.takesArgument(0, ByteBuffer[].class))
                                )
                        );
    }

    public static final class UnwrapAdvice {
        @Advice.OnMethodExit
        public static void unwrap(
                @Advice.This final javax.net.ssl.SSLEngine engine,
                @Advice.Argument(0) final ByteBuffer src,
                @Advice.Argument(1) final ByteBuffer dst,
                @Advice.Return SSLEngineResult result) {
            Connection c = SSLStorage.getConnectionForSession(engine);

            if (c == null) {
                String bufKey = ByteBufferExtractor.bufferKey(src);
                c = SSLStorage.getConnectionForBuf(bufKey);

                if (c == null) {
                    if (SSLStorage.debugOn) {
                        System.out.println("Can't find connection " + engine);
                    }
                } else {
                    SSLStorage.setConnectionForSession(engine, c);
                }
            }

            if (engine.getSession().getId().length == 0) {
                return;
            }

            if (result.bytesProduced() > 0 && dst.limit() >= result.bytesProduced()) {
                ByteBuffer dstBuffer = ByteBufferExtractor.bufferArray(dst, result.bytesProduced());
                int bufferSize = Math.min(result.bytesProduced(), ByteBufferExtractor.MAX_SIZE);
                byte[] b = new byte[bufferSize];
                dstBuffer.flip();
                dstBuffer.get(b, 0, bufferSize);

                Pointer p = new Memory(IOCTLPacket.packetPrefixSize + b.length);
                int wOff = IOCTLPacket.writePacketPrefix(p, 0, OperationType.RECEIVE, c, b.length);
                IOCTLPacket.writePacketBuffer(p, wOff, b);
                Agent.CLibrary.INSTANCE.ioctl(0, Agent.IOCTL_CMD, Pointer.nativeValue(p));
            }
        }
    }

    public static final class UnwrapAdviceArray {
        @Advice.OnMethodExit
        public static void unwrap(
                @Advice.This final javax.net.ssl.SSLEngine engine,
                @Advice.Argument(1) final ByteBuffer[] dsts,
                @Advice.Return SSLEngineResult result) {
            Connection c = SSLStorage.getConnectionForSession(engine);

            if (c == null) {
                ByteBuffer dstBuffer = ByteBufferExtractor.flattenByteBufferArray(dsts, ByteBufferExtractor.MAX_KEY_SIZE);
                String bufKey = Arrays.toString(dstBuffer.array());
                c = SSLStorage.getConnectionForBuf(bufKey);

                if (c == null) {
                    if (SSLStorage.debugOn) {
                        System.out.println("Can't find connection for dst array");
                    }
                } else {
                    SSLStorage.setConnectionForSession(engine, c);
                }
            }

            if (dsts.length == 0 || engine.getSession().getId().length == 0) {
                return;
            }

            if (result.bytesProduced() > 0) {
                ByteBuffer dstBuffer = ByteBufferExtractor.flattenByteBufferArray(dsts, result.bytesProduced());
                byte[] b = dstBuffer.array();
                int len = dstBuffer.limit();

                Pointer p = new Memory(IOCTLPacket.packetPrefixSize + len);
                int wOff = IOCTLPacket.writePacketPrefix(p, 0, OperationType.RECEIVE, c, len);
                IOCTLPacket.writePacketBuffer(p, wOff, b);
                Agent.CLibrary.INSTANCE.ioctl(0, Agent.IOCTL_CMD, Pointer.nativeValue(p));
            }
        }
    }

    public static final class WrapAdvice {
        @Advice.OnMethodExit//(suppress = Throwable.class)
        public static void wrap(
                @Advice.This final javax.net.ssl.SSLEngine engine,
                @Advice.Argument(0) final ByteBuffer src,
                @Advice.Argument(1) final ByteBuffer dst,
                @Advice.Return SSLEngineResult result) {
            if (engine.getSession().getId().length == 0) {
                return;
            }
            if (result.bytesConsumed() > 0) {
                int bufferSize = Math.min(result.bytesConsumed(), 1024);
                byte[] b = new byte[bufferSize];
                int oldPos = src.position();
                src.position(src.arrayOffset());
                src.get(b, 0, bufferSize);
                src.position(oldPos);

                String encrypted = ByteBufferExtractor.bufferKey(dst);
                SSLStorage.setBufferMapping(encrypted, new BytesWithLen(b, bufferSize));
            }
        }
    }

    public static final class WrapAdviceArray {
        @Advice.OnMethodExit//(suppress = Throwable.class)
        public static void wrap(
                @Advice.This final javax.net.ssl.SSLEngine engine,
                @Advice.Argument(0) final ByteBuffer[] srcs,
                @Advice.Argument(1) final ByteBuffer dst,
                @Advice.Return SSLEngineResult result) {
            if (srcs.length == 0 || engine.getSession().getId().length == 0) {
                return;
            }
            if (result.bytesConsumed() > 0) {
                ByteBuffer dstBuffer = ByteBufferExtractor.flattenByteBufferArray(srcs, result.bytesConsumed());
                byte[] b = dstBuffer.array();
                int len = dstBuffer.limit();

                String encrypted = ByteBufferExtractor.bufferKey(dst);
                SSLStorage.setBufferMapping(encrypted, new BytesWithLen(b, len));
            }
        }
    }
}
