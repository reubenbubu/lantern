package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EgressProxyTest {

  private ServerSocket echo;
  private ServerSocket proxySocket;
  private int other;

  @BeforeEach
  void start() throws IOException {
    InetAddress loopback = InetAddress.getLoopbackAddress();
    echo = new ServerSocket(0, 50, loopback);
    Thread.ofVirtual()
        .start(
            () -> {
              while (!echo.isClosed()) {
                try {
                  Socket client = echo.accept();
                  Thread.ofVirtual()
                      .start(
                          () -> {
                            try (client) {
                              client.getInputStream().transferTo(client.getOutputStream());
                            } catch (IOException e) {
                              // Closed.
                            }
                          });
                } catch (IOException e) {
                  return;
                }
              }
            });
    try (ServerSocket unused = new ServerSocket(0, 50, loopback)) {
      other = unused.getLocalPort();
    }
    proxySocket = new ServerSocket(0, 50, loopback);
    EgressProxy proxy = new EgressProxy(proxySocket, Set.of("LOCALHOST:" + echo.getLocalPort()));
    Thread.ofVirtual().start(proxy::serve);
  }

  @AfterEach
  void stop() throws IOException {
    proxySocket.close();
    echo.close();
  }

  private Socket connect() throws IOException {
    Socket socket = new Socket();
    socket.connect(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), proxySocket.getLocalPort()));
    socket.setSoTimeout(10_000);
    return socket;
  }

  private static String statusLine(InputStream in) throws IOException {
    StringBuilder line = new StringBuilder();
    int b;
    while ((b = in.read()) >= 0 && b != '\n') {
      line.append((char) b);
    }
    return line.toString().strip();
  }

  private static void send(Socket socket, String text) throws IOException {
    socket.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
    socket.getOutputStream().flush();
  }

  @Test
  void tunnelsToAnAllowedTarget() throws IOException {
    try (Socket socket = connect()) {
      send(socket, "CONNECT localhost:" + echo.getLocalPort() + " HTTP/1.1\r\nHost: x\r\n\r\n");
      InputStream in = socket.getInputStream();
      assertThat(statusLine(in)).isEqualTo("HTTP/1.1 200 Connection Established");
      assertThat(statusLine(in)).isEmpty();
      OutputStream out = socket.getOutputStream();
      out.write("through the tunnel\n".getBytes(StandardCharsets.UTF_8));
      out.flush();
      assertThat(statusLine(in)).isEqualTo("through the tunnel");
    }
  }

  @Test
  void deniesOtherTargets() throws IOException {
    try (Socket socket = connect()) {
      send(socket, "CONNECT localhost:" + other + " HTTP/1.1\r\n\r\n");
      assertThat(statusLine(socket.getInputStream())).isEqualTo("HTTP/1.1 403 Forbidden");
    }
    try (Socket socket = connect()) {
      send(socket, "CONNECT 127.0.0.1:" + echo.getLocalPort() + " HTTP/1.1\r\n\r\n");
      assertThat(statusLine(socket.getInputStream())).isEqualTo("HTTP/1.1 403 Forbidden");
    }
  }

  @Test
  void refusesPlainRequests() throws IOException {
    try (Socket socket = connect()) {
      send(socket, "GET http://localhost:" + echo.getLocalPort() + "/ HTTP/1.1\r\n\r\n");
      assertThat(statusLine(socket.getInputStream())).isEqualTo("HTTP/1.1 405 Method Not Allowed");
    }
  }

  @Test
  void refusesOversizedOrTruncatedRequests() throws IOException {
    try (Socket socket = connect()) {
      send(socket, "CONNECT localhost:1 HTTP/1.1\r\nX: " + "a".repeat(20_000) + "\r\n\r\n");
      assertThat(statusLine(socket.getInputStream())).isEqualTo("HTTP/1.1 400 Bad Request");
    }
    try (Socket socket = connect()) {
      send(socket, "CONNECT localhost:1 HTTP/1.1\r\n");
      socket.shutdownOutput();
      assertThat(statusLine(socket.getInputStream())).isEqualTo("HTTP/1.1 400 Bad Request");
    }
  }
}
