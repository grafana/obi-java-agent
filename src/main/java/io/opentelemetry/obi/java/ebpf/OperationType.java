package io.opentelemetry.obi.java.ebpf;

public enum OperationType {
    SEND((byte) 1),
    RECEIVE((byte) 2),
    CLOSE((byte) 3);

    public final byte code;

    OperationType(byte code) {
        this.code = code;
    }
}