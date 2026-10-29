package io.lantern.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The only way out of a build container's network: an HTTP proxy that tunnels {@code CONNECT}
 * requests, and forwards plain HTTP {@code GET} and {@code HEAD} requests one per connection, to an
 * allowed list of {@code host:port} targets (the Maven repositories, or the one Maven mirror) and
 * refuses everything else (ADR 0030).
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

  /** Headers a plain HTTP request does not pass on to the server. */
  private static final Set<String> HOP_BY_HOP =
      Set.of("connection", "keep-alive", "proxy-connection", "proxy-authorization", "upgrade");

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
      boolean connect = request.length == 3 && request[0].equals("CONNECT");
      boolean plain =
          request.length == 3
              && (request[0].equals("GET") || request[0].equals("HEAD"))
              && request[2].startsWith("HTTP/1.");
      if (!connect && !plain) {
        log("refused " + (request.length > 0 ? request[0] : "?") + " request");
        respond(out, "405 Method Not Allowed");
        return;
      }
      String target = connect ? request[1].toLowerCase(Locale.ROOT) : plainTarget(request[1]);
      int colon = target == null ? -1 : target.lastIndexOf(':');
      if (target == null || !allowed.contains(target) || colon <= 0) {
        log("denied " + request[0] + " " + (connect ? target : request[1]));
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
        if (connect) {
          out.write(
              "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          out.flush();
        } else {
          OutputStream upstreamOut = upstream.getOutputStream();
          upstreamOut.write(originRequest(head, request).getBytes(StandardCharsets.ISO_8859_1));
          upstreamOut.flush();
        }
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

  /**
   * The {@code host:port} of a plain HTTP request's absolute URL, with port 80 if it names none, or
   * null if it is not a plain {@code http} URL with a host.
   */
  private static String plainTarget(String url) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      return null;
    }
    if (!"http".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getRawUserInfo() != null) {
      return null;
    }
    return uri.getHost().toLowerCase(Locale.ROOT)
        + ":"
        + (uri.getPort() == -1 ? 80 : uri.getPort());
  }

  /**
   * A plain HTTP request as the proxy sends it on to the server: the path instead of the absolute
   * URL, without the headers meant for the proxy or for keeping the connection, and asking the
   * server to close the connection after its response. So each connection carries one request, and
   * only to the target that was checked.
   */
  private static String originRequest(String head, String[] request) {
    URI uri = URI.create(request[1]);
    String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    if (uri.getRawQuery() != null) {
      path += "?" + uri.getRawQuery();
    }
    StringBuilder origin = new StringBuilder();
    origin.append(request[0]).append(' ').append(path).append(' ').append(request[2]);
    origin.append("\r\n");
    head.lines()
        .skip(1)
        .filter(line -> !line.isEmpty())
        .filter(
            line -> {
              String name = line.substring(0, Math.max(0, line.indexOf(':'))).strip();
              return !HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
            })
        .forEach(line -> origin.append(line).append("\r\n"));
    origin.append("Connection: close\r\n\r\n");
    return origin.toString();
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
