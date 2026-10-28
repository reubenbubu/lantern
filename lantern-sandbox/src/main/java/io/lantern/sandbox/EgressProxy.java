package io.lantern.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The only way out of a build container's network: an HTTP proxy that tunnels {@code CONNECT}
 * requests to an allowed list of {@code host:port} targets (the artifact repository) and refuses
 * everything else.
 *
 * <p>It runs in its own container, attached to the build's internal network and to a network with
 * internet access. The sandbox copies this class file into that container and runs it with {@code
 * java}, so it must stay one self-contained class file: no nested classes and only JDK types.
 */
final class EgressProxy {

  /** Printed once the proxy accepts connections. */
  static final String READY = "lantern egress proxy listening";

  private static final int MAX_HEADER_BYTES = 16 * 1024;
  private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
  private static final int IDLE_TIMEOUT_MILLIS = 120_000;

  private final ServerSocket server;
  private final Set<String> allowed;

  /**
   * @param server the bound socket to accept connections on
   * @param allowed the {@code host:port} targets that may be reached, in any letter case
   */
  EgressProxy(ServerSocket server, Set<String> allowed) {
    this.server = server;
    this.allowed =
        allowed.stream().map(target -> target.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
  }

  /** Arguments: the port to listen on, then the allowed targets, comma separated. */
  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println("usage: EgressProxy <port> <host:port>[,<host:port>...]");
      System.exit(2);
    }
    ServerSocket server = new ServerSocket();
    server.bind(new InetSocketAddress(Integer.parseInt(args[0])));
    Set<String> allowed =
        Arrays.stream(args[1].split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    EgressProxy proxy = new EgressProxy(server, allowed);
    System.out.println(READY + " on port " + server.getLocalPort() + ", allowing " + allowed);
    System.out.flush();
    proxy.serve();
  }

  /** Accepts connections until the server socket is closed. */
  void serve() {
    while (!server.isClosed()) {
      Socket client;
      try {
        client = server.accept();
      } catch (IOException e) {
        if (server.isClosed()) {
          return;
        }
        continue;
      }
      Thread.ofVirtual().start(() -> handle(client));
    }
  }

  private void handle(Socket client) {
    try (client) {
      client.setSoTimeout(IDLE_TIMEOUT_MILLIS);
      InputStream in = client.getInputStream();
      OutputStream out = client.getOutputStream();
      String head = readHead(in);
      if (head == null) {
        respond(out, "400 Bad Request");
        return;
      }
      String[] request = head.lines().findFirst().orElse("").split(" ");
      if (request.length != 3 || !request[0].equals("CONNECT")) {
        log("refused " + (request.length > 0 ? request[0] : "?") + " request");
        respond(out, "405 Method Not Allowed");
        return;
      }
      String target = request[1].toLowerCase(Locale.ROOT);
      int colon = target.lastIndexOf(':');
      if (!allowed.contains(target) || colon <= 0) {
        log("denied CONNECT " + target);
        respond(out, "403 Forbidden");
        return;
      }
      int port;
      try {
        port = Integer.parseInt(target.substring(colon + 1));
      } catch (NumberFormatException e) {
        respond(out, "400 Bad Request");
        return;
      }
      try (Socket upstream = new Socket()) {
        try {
          upstream.connect(
              new InetSocketAddress(target.substring(0, colon), port), CONNECT_TIMEOUT_MILLIS);
        } catch (IOException e) {
          log("cannot reach " + target + ": " + e.getMessage());
          respond(out, "502 Bad Gateway");
          return;
        }
        upstream.setSoTimeout(IDLE_TIMEOUT_MILLIS);
        out.write(
            "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream upstreamIn = upstream.getInputStream();
        Thread back = Thread.ofVirtual().start(() -> pump(upstreamIn, upstream, client));
        pump(in, client, upstream);
        back.join();
      }
    } catch (IOException e) {
      // The client went away; nothing to do.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Copies from one socket to the other until end of stream, then half-closes both directions. */
  private static void pump(InputStream source, Socket from, Socket to) {
    try {
      source.transferTo(to.getOutputStream());
    } catch (IOException e) {
      // Closed or timed out.
    } finally {
      try {
        to.shutdownOutput();
      } catch (IOException e) {
        // Already closed.
      }
      try {
        from.shutdownInput();
      } catch (IOException e) {
        // Already closed.
      }
    }
  }

  /** Reads the request line and headers, up to the blank line, or null if malformed or too long. */
  private static String readHead(InputStream in) throws IOException {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    int matched = 0;
    byte[] end = {'\r', '\n', '\r', '\n'};
    while (head.size() < MAX_HEADER_BYTES) {
      int b = in.read();
      if (b < 0) {
        return null;
      }
      head.write(b);
      matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
      if (matched == end.length) {
        return head.toString(StandardCharsets.ISO_8859_1);
      }
    }
    return null;
  }

  private static void respond(OutputStream out, String status) throws IOException {
    out.write(
        ("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    out.flush();
  }

  private static void log(String message) {
    System.out.println(message);
  }
}
