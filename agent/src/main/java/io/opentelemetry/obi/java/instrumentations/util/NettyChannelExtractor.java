package io.opentelemetry.obi.java.instrumentations.util;

import io.opentelemetry.obi.java.instrumentations.data.Connection;
import io.opentelemetry.obi.java.instrumentations.data.SSLStorage;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;

public class NettyChannelExtractor {
  // Called always by reflection, that's why unused
  @SuppressWarnings("unused")
  public static Connection extractConnectionFromChannelHandlerContext(Object ctx) {
    Connection c = null;
    try {
      Method channelMethod = ctx.getClass().getMethod("channel");
      channelMethod.setAccessible(true);
      Object channel = channelMethod.invoke(ctx);

      Method localAddressMethod = channel.getClass().getMethod("localAddress");
      localAddressMethod.setAccessible(true);
      InetSocketAddress localAddress = (InetSocketAddress) localAddressMethod.invoke(channel);

      Method remoteAddressMethod = channel.getClass().getMethod("remoteAddress");
      remoteAddressMethod.setAccessible(true);
      InetSocketAddress remoteAddress = (InetSocketAddress) remoteAddressMethod.invoke(channel);

      if (SSLStorage.debugOn) {
        System.out.println("Netty channel localAddress: " + localAddress);
        System.out.println("Netty channel remoteAddress: " + remoteAddress);
      }
      c =
          new Connection(
              localAddress.getAddress(),
              localAddress.getPort(),
              remoteAddress.getAddress(),
              remoteAddress.getPort());
    } catch (Exception e) {
      e.printStackTrace();
    }

    return c;
  }
}
