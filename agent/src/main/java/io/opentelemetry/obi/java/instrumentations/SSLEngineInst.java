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
        return ElementMatchers.isSubTypeOf(SSLEngine.class);
    }

    public static boolean matches(Class<?> clazz) {
        return SSLEngine.class.isAssignableFrom(clazz);
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
        @Advice.OnMethodEnter
        public static void unwrap(
                @Advice.This final javax.net.ssl.SSLEngine engine,
                @Advice.Argument(1) final ByteBuffer dst) {
            if (engine.getSession().getId().length == 0) {
                return;
            }

            SSLStorage.bufPos.set(dst.position());
        }

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
                    c = (Connection) SSLStorage.nettyConnection.get();
                }

                if (c == null) {
                    if (SSLStorage.debugOn) {
                        System.out.println("Can't find connection " + engine);
                    }
                } else {
                    SSLStorage.setConnectionForSession(engine, c);
                }
            }

            if (engine.getSession().getId().length == 0) {
                SSLStorage.bufPos.remove();
                return;
            }

            if (result.bytesProduced() > 0 && dst.limit() >= result.bytesProduced()) {
                int oldPos = dst.position();

                Integer savedPos = SSLStorage.bufPos.get();
                if (savedPos == null) {
                    System.out.println("Error reading saved Buffer pos");
                    return;
                }

                dst.position(savedPos);
                ByteBuffer dstBuffer = ByteBufferExtractor.srcBufferArray(dst, result.bytesProduced());
                dst.position(oldPos);

                byte[] b = dstBuffer.array();

                if (SSLStorage.debugOn) {
                    System.out.println("unwrap:" + new String(b, java.nio.charset.StandardCharsets.UTF_8));
                }

                Pointer p = new Memory(IOCTLPacket.packetPrefixSize + b.length);
                int wOff = IOCTLPacket.writePacketPrefix(p, 0, OperationType.RECEIVE, c, b.length);
                IOCTLPacket.writePacketBuffer(p, wOff, b);
                Agent.CLibrary.INSTANCE.ioctl(0, Agent.IOCTL_CMD, Pointer.nativeValue(p));
            }

            SSLStorage.bufPos.remove();
        }
    }

    public static final class UnwrapAdviceArray {
        @Advice.OnMethodEnter

        public static void unwrap(
                @Advice.This final javax.net.ssl.SSLEngine engine,
                @Advice.Argument(1) final ByteBuffer[] dsts) {
            if (dsts.length == 0 || engine.getSession().getId().length == 0) {
                return;
            }

            int[] positions = new int[dsts.length];
            for (int i = 0; i < dsts.length; i++) {
                positions[i] = dsts[i].position();
            }

            SSLStorage.bufPositions.set(positions);
        }

        @Advice.OnMethodExit
        public static void unwrap(
                @Advice.This final javax.net.ssl.SSLEngine engine,
                @Advice.Argument(1) final ByteBuffer[] dsts,
                @Advice.Return SSLEngineResult result) {
            Connection c = SSLStorage.getConnectionForSession(engine);

            if (c == null) {
                ByteBuffer dstBuffer = ByteBufferExtractor.flattenDstByteBufferArray(dsts, ByteBufferExtractor.MAX_KEY_SIZE);
                String bufKey = Arrays.toString(dstBuffer.array());
                c = SSLStorage.getConnectionForBuf(bufKey);

                if (c == null) {
                    c = (Connection) SSLStorage.nettyConnection.get();
                }

                if (c == null) {
                    if (SSLStorage.debugOn) {
                        System.out.println("Can't find connection for dst array");
                    }
                } else {
                    SSLStorage.setConnectionForSession(engine, c);
                }
            }

            if (dsts.length == 0 || engine.getSession().getId().length == 0) {
                SSLStorage.bufPositions.remove();
                return;
            }

            if (result.bytesProduced() > 0) {
                int[] oldDstPositions = new int[dsts.length];
                int[] savedDstPositions = SSLStorage.bufPositions.get();
                if (savedDstPositions == null) {
                    System.out.println("Can't find saved destination positions");
                    return;
                }

                for (int i = 0; i < dsts.length; i++) {
                    oldDstPositions[i] = dsts[i].position();
                    dsts[i].position(savedDstPositions[i]);
                }

                ByteBuffer dstBuffer = ByteBufferExtractor.flattenSrcByteBufferArray(dsts);

                for (int i = 0; i < dsts.length; i++) {
                    dsts[i].position(oldDstPositions[i]);
                }

                byte[] b = dstBuffer.array();
                int len = dstBuffer.position();

                if (SSLStorage.debugOn) {
                    System.out.println("unwrap array:" + new String(b, java.nio.charset.StandardCharsets.UTF_8));
                }

                Pointer p = new Memory(IOCTLPacket.packetPrefixSize + len);
                int wOff = IOCTLPacket.writePacketPrefix(p, 0, OperationType.RECEIVE, c, len);
                IOCTLPacket.writePacketBuffer(p, wOff, b, 0, len);
                Agent.CLibrary.INSTANCE.ioctl(0, Agent.IOCTL_CMD, Pointer.nativeValue(p));
            }

            SSLStorage.bufPositions.remove();
        }
    }

    public static final class WrapAdvice {
        @Advice.OnMethodEnter//(suppress = Throwable.class)
        public static void wrap(
                @Advice.This final javax.net.ssl.SSLEngine engine,
                @Advice.Argument(0) final ByteBuffer src) {
            if (engine.getSession().getId().length == 0) {
                return;
            }

            if (!src.hasRemaining()) {
                return;
            }

            ByteBuffer buf = ByteBufferExtractor.srcBufferArray(src, src.remaining());
            byte[] b = buf.array();
            int len = buf.position();

            SSLStorage.unencrypted.set(new BytesWithLen(b, len));
        }

        @Advice.OnMethodExit//(suppress = Throwable.class)
        public static void wrap(
                @Advice.This final javax.net.ssl.SSLEngine engine,
                @Advice.Argument(0) final ByteBuffer src,
                @Advice.Argument(1) final ByteBuffer dst,
                @Advice.Return SSLEngineResult result) {
            if (engine.getSession().getId().length == 0) {
                SSLStorage.unencrypted.remove();
                return;
            }

            if (result.bytesConsumed() > 0) {
                BytesWithLen bLen = SSLStorage.unencrypted.get();
                if (bLen == null) {
                    System.out.println("Error, empty bytes");
                    return;
                }

                if (SSLStorage.debugOn) {
                    System.out.println("wrap :" + new String(bLen.buf, java.nio.charset.StandardCharsets.UTF_8));
                }

                Connection c = (Connection) SSLStorage.nettyConnection.get();
                if (SSLStorage.debugOn) {
                    System.out.println("Found netty connection " + c + " thread " + Thread.currentThread().getName());
                }
                if (c != null) {
                    Pointer p = new Memory(IOCTLPacket.packetPrefixSize + bLen.len);
                    int wOff = IOCTLPacket.writePacketPrefix(p, 0, OperationType.SEND, c, bLen.len);
                    IOCTLPacket.writePacketBuffer(p, wOff, bLen.buf, 0, bLen.len);
                    Agent.CLibrary.INSTANCE.ioctl(0, Agent.IOCTL_CMD, Pointer.nativeValue(p));
                } else {
                    String encrypted = ByteBufferExtractor.bufferKey(dst);
                    if (SSLStorage.debugOn) {
                        System.out.println("buf mapping on: " + encrypted);
                    }
                    SSLStorage.setBufferMapping(encrypted, bLen);
                }
            }

            SSLStorage.unencrypted.remove();
        }
    }

    public static final class WrapAdviceArray {
        @Advice.OnMethodEnter//(suppress = Throwable.class)
        public static void wrap(
                @Advice.This final javax.net.ssl.SSLEngine engine,
                @Advice.Argument(0) final ByteBuffer[] srcs) {
            if (srcs.length == 0 || engine.getSession().getId().length == 0) {
                return;
            }

            ByteBuffer buf = ByteBufferExtractor.flattenSrcByteBufferArray(srcs);
            byte[] b = buf.array();
            int len = buf.position();

            SSLStorage.unencrypted.set(new BytesWithLen(b, len));
        }

        @Advice.OnMethodExit//(suppress = Throwable.class)
        public static void wrap(
                @Advice.This final javax.net.ssl.SSLEngine engine,
                @Advice.Argument(0) final ByteBuffer[] srcs,
                @Advice.Argument(1) final ByteBuffer dst,
                @Advice.Return SSLEngineResult result) {
            if (srcs.length == 0 || engine.getSession().getId().length == 0) {
                SSLStorage.unencrypted.remove();
                return;
            }

            if (result.bytesConsumed() > 0) {
                BytesWithLen bLen = SSLStorage.unencrypted.get();
                if (bLen == null) {
                    System.out.println("Error, empty bytes");
                    return;
                }

                if (SSLStorage.debugOn) {
                    System.out.println("wrap array :[" + bLen.len + "]" + new String(bLen.buf, java.nio.charset.StandardCharsets.UTF_8));
                }

                Connection c = (Connection) SSLStorage.nettyConnection.get();
                if (SSLStorage.debugOn) {
                    System.out.println("Found netty connection " + c + " thread " + Thread.currentThread().getName());
                }
                if (c != null) {
                    Pointer p = new Memory(IOCTLPacket.packetPrefixSize + bLen.len);
                    int wOff = IOCTLPacket.writePacketPrefix(p, 0, OperationType.SEND, c, bLen.len);
                    IOCTLPacket.writePacketBuffer(p, wOff, bLen.buf, 0, bLen.len);
                    Agent.CLibrary.INSTANCE.ioctl(0, Agent.IOCTL_CMD, Pointer.nativeValue(p));
                } else {
                    String encrypted = ByteBufferExtractor.bufferKey(dst);
                    if (SSLStorage.debugOn) {
                        System.out.println("buf array mapping on: " + encrypted);
                    }
                    SSLStorage.setBufferMapping(encrypted, bLen);
                }
            }

            SSLStorage.unencrypted.remove();
        }
    }
}
