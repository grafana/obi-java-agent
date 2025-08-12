package io.opentelemetry.obi.java.instrumentations;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import io.opentelemetry.obi.java.instrumentations.data.BytesWithLen;
import io.opentelemetry.obi.java.instrumentations.data.Connection;
import io.opentelemetry.obi.java.instrumentations.data.SSLStorage;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import io.opentelemetry.obi.java.Agent;
import io.opentelemetry.obi.java.ebpf.IOCTLPacket;
import io.opentelemetry.obi.java.ebpf.OperationType;
import io.opentelemetry.obi.java.instrumentations.util.ByteBufferExtractor;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

public class SocketChannelInst {
    public static ElementMatcher<? super TypeDescription> type() {
        return ElementMatchers.isSubTypeOf(SocketChannel.class)
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isInterface()));
    }

    public static boolean matches(Class<?> clazz) {
        return SocketChannel.class.isAssignableFrom(clazz);
    }

    public static AgentBuilder.Transformer transformer() {
        return (builder, type, classLoader, module, protectionDomain) ->
                builder
                        .visit(Advice.to(WriteAdvice.class)
                                .on(ElementMatchers
                                        .named("write")
                                        .and(ElementMatchers.takesArgument(0, ByteBuffer.class))
                                )
                        )
                        .visit(Advice.to(WriteAdviceArray.class)
                                .on(ElementMatchers
                                        .named("write")
                                        .and(ElementMatchers.takesArgument(0, ByteBuffer[].class))
                                )
                        )
                        .visit(Advice.to(ReadAdvice.class)
                                .on(ElementMatchers
                                        .named("read")
                                        .and(ElementMatchers.takesArguments(1))
                                        .and(ElementMatchers.takesArgument(0, ByteBuffer.class))
                                )
                        )
                        .visit(Advice.to(ReadAdviceArray.class)
                                .on(ElementMatchers
                                        .named("read")
                                        .and(ElementMatchers.takesArguments(3))
                                        .and(ElementMatchers.takesArgument(0, ByteBuffer[].class))
                                )
                        )
                        .visit(Advice.to(CleanupAdvice.class)
                                .on(ElementMatchers
                                        .named("shutdownInput")
                                        .or(ElementMatchers.named("shutdownOutput"))
                                        .or(ElementMatchers.named("kill"))
                                        .or(ElementMatchers.named("tryClose"))
                                )
                        );
    }

    public static final class WriteAdvice {
        @Advice.OnMethodEnter
        public static void write(
                @Advice.Argument(0) final ByteBuffer src
        ) {
            SSLStorage.bufPos.set(src.position());
        }

        @Advice.OnMethodExit//(suppress = Throwable.class)
        public static void write(
                @Advice.Argument(0) final ByteBuffer src,
                @Advice.FieldValue("localAddress") SocketAddress localSocket,
                @Advice.FieldValue("remoteAddress") SocketAddress remoteSocket) {
            if (!(localSocket instanceof InetSocketAddress) || !(remoteSocket instanceof InetSocketAddress)) {
                SSLStorage.bufPos.remove();
                return;
            }

            int oldPos = src.position();

            Integer savedPos = SSLStorage.bufPos.get();
            if (savedPos == null) {
                System.out.println("Error reading saved source buffer pos");
                return;
            }

            src.position(savedPos);
            String bufKey = ByteBufferExtractor.srcBufferKey(src);
            src.position(oldPos);

            if (SSLStorage.debugOn) {
                System.out.println("write advice, lookup: " + bufKey);
            }

            BytesWithLen unencrypted = SSLStorage.getUnencryptedBuffer(bufKey);
            if (unencrypted == null) {
                return;
            }
            InetSocketAddress inetSocketAddress = (InetSocketAddress)localSocket;
            InetSocketAddress remoteSocketAddress = (InetSocketAddress)remoteSocket;

            Connection c = new Connection(inetSocketAddress.getAddress(), inetSocketAddress.getPort(), remoteSocketAddress.getAddress(), remoteSocketAddress.getPort());

            Pointer p = new Memory(IOCTLPacket.packetPrefixSize + unencrypted.len);
            int wOff = IOCTLPacket.writePacketPrefix(p, 0, OperationType.SEND, c, unencrypted.len);
            IOCTLPacket.writePacketBuffer(p, wOff, unencrypted.buf, 0, unencrypted.len);
            Agent.CLibrary.INSTANCE.ioctl(0, Agent.IOCTL_CMD, Pointer.nativeValue(p));
        }
    }

    public static final class WriteAdviceArray {
        @Advice.OnMethodEnter
        public static void write(
                @Advice.Argument(0) final ByteBuffer[] srcs
        ) {
            int[] positions = new int[srcs.length];
            for (int i = 0; i < srcs.length; i++) {
                positions[i] = srcs[i].position();
            }

            SSLStorage.bufPositions.set(positions);
        }

        @Advice.OnMethodExit//(suppress = Throwable.class)
        public static void write(
                @Advice.Argument(0) final ByteBuffer[] srcs,
                @Advice.FieldValue("localAddress") SocketAddress localSocket,
                @Advice.FieldValue("remoteAddress") SocketAddress remoteSocket) {
            if (!(localSocket instanceof InetSocketAddress) || !(remoteSocket instanceof InetSocketAddress)) {
                SSLStorage.bufPositions.remove();
                return;
            }

            int[] oldSrcPositions = new int[srcs.length];
            int[] savedSrcPositions = SSLStorage.bufPositions.get();
            if (savedSrcPositions == null) {
                System.out.println("Can't find saved source positions");
                return;
            }

            for (int i = 0; i < srcs.length; i++) {
                oldSrcPositions[i] = srcs[i].position();
                srcs[i].position(savedSrcPositions[i]);
            }

            ByteBuffer srcBuffer = ByteBufferExtractor.flattenSrcByteBufferArray(srcs);

            for (int i = 0; i < srcs.length; i++) {
                srcs[i].position(oldSrcPositions[i]);
            }

            String bufKey = ByteBufferExtractor.bufferKey(srcBuffer);

            SSLStorage.bufPositions.remove();

            if (SSLStorage.debugOn) {
                System.out.println("write array advice, lookup: " + bufKey);
            }

            BytesWithLen unencrypted = SSLStorage.getUnencryptedBuffer(bufKey);
            if (unencrypted == null) {
                if (SSLStorage.debugOn) {
                    System.out.println("unable to find buffer mapping");
                }
                return;
            }

            SSLStorage.removeBufferMapping(bufKey);

            InetSocketAddress inetSocketAddress = (InetSocketAddress)localSocket;
            InetSocketAddress remoteSocketAddress = (InetSocketAddress)remoteSocket;

            Connection c = new Connection(inetSocketAddress.getAddress(), inetSocketAddress.getPort(), remoteSocketAddress.getAddress(), remoteSocketAddress.getPort());

            Pointer p = new Memory(IOCTLPacket.packetPrefixSize + unencrypted.len);
            int wOff = IOCTLPacket.writePacketPrefix(p, 0, OperationType.SEND, c, unencrypted.len);
            IOCTLPacket.writePacketBuffer(p, wOff, unencrypted.buf, 0, unencrypted.len);
            Agent.CLibrary.INSTANCE.ioctl(0, Agent.IOCTL_CMD, Pointer.nativeValue(p));
        }
    }

    public static final class ReadAdvice {
        @Advice.OnMethodExit//(suppress = Throwable.class)
        public static void read(
                @Advice.Argument(0) final ByteBuffer dst,
                @Advice.FieldValue("localAddress") SocketAddress localSocket,
                @Advice.FieldValue("remoteAddress") SocketAddress remoteSocket) {
            if (!(localSocket instanceof InetSocketAddress) || !(remoteSocket instanceof InetSocketAddress)) {
                return;
            }
            InetSocketAddress localSocketAddress = (InetSocketAddress)localSocket;
            InetSocketAddress remoteSocketAddress = (InetSocketAddress)remoteSocket;

            Connection c = new Connection(localSocketAddress.getAddress(), localSocketAddress.getPort(), remoteSocketAddress.getAddress(), remoteSocketAddress.getPort());

            if (SSLStorage.connectionUntracked(c)) {
                String bufKey = ByteBufferExtractor.bufferKey(dst);
                SSLStorage.setConnectionForBuf(bufKey, c);
                if (SSLStorage.debugOn) {
                    System.out.println("Setting connection for: " + bufKey);
                }
            }
        }
    }

    public static final class ReadAdviceArray {
        @Advice.OnMethodExit//(suppress = Throwable.class)
        public static void read(
                @Advice.Argument(0) final ByteBuffer[] dsts,
                @Advice.FieldValue("localAddress") SocketAddress localSocket,
                @Advice.FieldValue("remoteAddress") SocketAddress remoteSocket) {
            if (!(localSocket instanceof InetSocketAddress) || !(remoteSocket instanceof InetSocketAddress)) {
                return;
            }
            InetSocketAddress localSocketAddress = (InetSocketAddress)localSocket;
            InetSocketAddress remoteSocketAddress = (InetSocketAddress)remoteSocket;

            Connection c = new Connection(localSocketAddress.getAddress(), localSocketAddress.getPort(), remoteSocketAddress.getAddress(), remoteSocketAddress.getPort());

            if (SSLStorage.connectionUntracked(c)) {
                ByteBuffer dstBuffer = ByteBufferExtractor.flattenDstByteBufferArray(dsts, ByteBufferExtractor.MAX_KEY_SIZE);
                String bufKey = Arrays.toString(dstBuffer.array());
                SSLStorage.setConnectionForBuf(bufKey, c);

                if (SSLStorage.debugOn) {
                    System.out.println("Setting connection for: " + bufKey);
                }
            }
        }
    }

    public static final class CleanupAdvice {
        @Advice.OnMethodEnter//(suppress = Throwable.class)
        public static void cleanup(
                @Advice.FieldValue("localAddress") SocketAddress localSocket,
                @Advice.FieldValue("remoteAddress") SocketAddress remoteSocket) {
            if (!(localSocket instanceof InetSocketAddress) || !(remoteSocket instanceof InetSocketAddress)) {
                return;
            }
            InetSocketAddress localSocketAddress = (InetSocketAddress)localSocket;
            InetSocketAddress remoteSocketAddress = (InetSocketAddress)remoteSocket;

            Connection c = new Connection(localSocketAddress.getAddress(), localSocketAddress.getPort(), remoteSocketAddress.getAddress(), remoteSocketAddress.getPort());

            Connection tracked = SSLStorage.getActiveConnection(c);

            if (SSLStorage.debugOn) {
                System.out.println("Cleanup connection " + tracked);
            }

            if (tracked != null) {
                SSLStorage.cleanupConnectionBufMapping(tracked);
            }
        }
    }
}
