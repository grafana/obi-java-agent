package io.opentelemetry.obi.java.ebpf;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import io.opentelemetry.obi.java.Agent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

public class ProxyOutputStream extends OutputStream {
    private final OutputStream delegate;
    private final Socket socket;

    public ProxyOutputStream(OutputStream delegate, Socket socket) {
        this.delegate = delegate;
        this.socket = socket;
    }

    @Override
    public void write(int b) throws IOException {
        delegate.write(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        delegate.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (len > 0) {
            Pointer p = new Memory(IOCTLPacket.packetPrefixSize + b.length);
            int wOff = IOCTLPacket.writePacketPrefix(p, 0, OperationType.SEND, socket, b.length);
            IOCTLPacket.writePacketBuffer(p, wOff, b);
            Agent.CLibrary.INSTANCE.ioctl(0, Agent.IOCTL_CMD, Pointer.nativeValue(p));
        }
        delegate.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
