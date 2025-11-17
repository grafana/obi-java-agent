package io.opentelemetry.obi.java.instrumentations.data;

public class BytesWithLen {
  public final byte[] buf;
  public final int len;

  public BytesWithLen(byte[] buf, int len) {
    this.buf = buf;
    this.len = len;
  }
}
