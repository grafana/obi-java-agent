package org.grafana.beyla.instrumentations;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.grafana.beyla.Agent;
import org.grafana.beyla.ebpf.IOCTLPacket;
import org.grafana.beyla.ebpf.OperationType;

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;

public class SocketChannelInst {
    public static ElementMatcher<? super TypeDescription> type() {
        return ElementMatchers.isSubTypeOf(SocketChannel.class)
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isInterface()));
    }

    public static AgentBuilder.Transformer transformer() {
        return (builder, type, classLoader, module, protectionDomain) ->
                builder
                        .visit(Advice.to(WriteAdvice.class)
                                .on(ElementMatchers.named("write")))
                        .visit(Advice.to(ReadAdvice.class)
                                .on(ElementMatchers.named("read")));
    }

    public static final class WriteAdvice {
        @Advice.OnMethodExit//(suppress = Throwable.class)
        public static void write(
                @Advice.FieldValue("localAddress") SocketAddress localSocket,
                @Advice.FieldValue("remoteAddress") SocketAddress remoteSocket) {
            if (!(localSocket instanceof InetSocketAddress) || !(remoteSocket instanceof InetSocketAddress)) {
                return;
            }
            InetSocketAddress inetSocketAddress = (InetSocketAddress)localSocket;
            InetSocketAddress remoteSocketAddress = (InetSocketAddress)remoteSocket;

            Connection c = new Connection(inetSocketAddress.getAddress(), inetSocketAddress.getPort(), remoteSocketAddress.getAddress(), remoteSocketAddress.getPort());

            BytesWithLen data = SSLStorage.threadBuffer.get();
            SSLSession session = SSLStorage.threadSSLSession.get();
            if (session != null) {
                SSLStorage.setConnectionForSession(session, c);
            }
            if (data != null) {
                Pointer p = new Memory(IOCTLPacket.packetPrefixSize + data.len);
                int wOff = IOCTLPacket.writePacketPrefix(p, 0, OperationType.SEND, c, data.len);
                IOCTLPacket.writePacketBuffer(p, wOff, data.buf);
                Agent.CLibrary.INSTANCE.ioctl(0, Agent.IOCTL_CMD, Pointer.nativeValue(p));
                SSLStorage.threadBuffer.remove();
            }
        }
    }

    public static final class ReadAdvice {
        @Advice.OnMethodExit//(suppress = Throwable.class)
        public static void read(
                @Advice.FieldValue("localAddress") SocketAddress localSocket,
                @Advice.FieldValue("remoteAddress") SocketAddress remoteSocket) {
            if (!(localSocket instanceof InetSocketAddress) || !(remoteSocket instanceof InetSocketAddress)) {
                return;
            }
            InetSocketAddress localSocketAddress = (InetSocketAddress)localSocket;
            InetSocketAddress remoteSocketAddress = (InetSocketAddress)remoteSocket;

            Connection c = new Connection(localSocketAddress.getAddress(), localSocketAddress.getPort(), remoteSocketAddress.getAddress(), remoteSocketAddress.getPort());
            SSLStorage.threadConnection.set(c);
        }
    }
}
