package probe;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Tries to leave the sandbox and records the results in target/classes/probe.properties. */
public class Probe {

  public static void main(String[] args) throws IOException {
    Properties results = new Properties();
    results.setProperty("direct.name", connect("repo.maven.apache.org", 443));
    results.setProperty("direct.address", connect("1.1.1.1", 443));
    results.setProperty("dns", resolve("example.com"));
    results.setProperty("metadata", connect("169.254.169.254", 80));
    results.setProperty("gateway", gateway());
    results.setProperty("proxy.allowed", viaProxy("CONNECT repo.maven.apache.org:443 HTTP/1.1"));
    results.setProperty("proxy.denied", viaProxy("CONNECT example.com:443 HTTP/1.1"));
    results.setProperty("proxy.extra", viaProxy("CONNECT repo1.maven.org:443 HTTP/1.1"));
    results.setProperty("proxy.plain", viaProxy("GET http://example.com/ HTTP/1.1"));
    results.setProperty("memory.max", read("/sys/fs/cgroup/memory.max"));
    results.setProperty("memory.swap.max", read("/sys/fs/cgroup/memory.swap.max"));
    results.setProperty("pids.max", read("/sys/fs/cgroup/pids.max"));
    results.setProperty("cpu.max", read("/sys/fs/cgroup/cpu.max"));
    results.setProperty("capabilities", status("CapEff:"));
    // The real, effective, saved and file system user IDs.
    results.setProperty("uid", status("Uid:"));
    results.setProperty("git", Boolean.toString(Files.exists(Path.of("/work/.git"))));
    results.setProperty(
        "docker.socket", Boolean.toString(Files.exists(Path.of("/var/run/docker.sock"))));
    try (OutputStream out = Files.newOutputStream(Path.of("target/classes/probe.properties"))) {
      results.store(out, null);
    }
  }

  private static String connect(String host, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 3000);
      return "connected";
    } catch (IOException e) {
      return "blocked";
    }
  }

  private static String resolve(String host) {
    try {
      InetAddress.getByName(host);
      return "resolved";
    } catch (IOException e) {
      return "failed";
    }
  }

  /** "none" without a default route, else whether common ports on the gateway were reachable. */
  private static String gateway() {
    for (String line : read("/proc/net/route").split("\n")) {
      String[] fields = line.trim().split("\\s+");
      if (fields.length > 2 && fields[1].equals("00000000") && !fields[2].equals("00000000")) {
        long hex = Long.parseLong(fields[2], 16);
        String address =
            (hex & 0xff) + "." + ((hex >> 8) & 0xff) + "." + ((hex >> 16) & 0xff) + "." + ((hex >> 24) & 0xff);
        for (int port : new int[] {22, 80, 443, 2375, 5432, 8080}) {
          if (connect(address, port).equals("connected")) {
            return "reached " + address + ":" + port;
          }
        }
        return "blocked";
      }
    }
    return "none";
  }

  /** Sends one request line to the egress proxy and returns its status code. */
  private static String viaProxy(String requestLine) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("lantern-proxy", 3128), 3000);
      socket.setSoTimeout(20000);
      socket
          .getOutputStream()
          .write((requestLine + "\r\nHost: probe\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      InputStream in = socket.getInputStream();
      StringBuilder line = new StringBuilder();
      int b;
      while ((b = in.read()) >= 0 && b != '\n') {
        line.append((char) b);
      }
      String[] parts = line.toString().split(" ");
      return parts.length > 1 ? parts[1] : "none";
    } catch (IOException e) {
      return "error: " + e.getMessage();
    }
  }

  /** A field of /proc/self/status, with tabs between its values replaced by spaces. */
  private static String status(String field) {
    for (String line : read("/proc/self/status").split("\n")) {
      if (line.startsWith(field)) {
        return line.substring(field.length()).strip().replaceAll("\\s+", " ");
      }
    }
    return "unknown";
  }

  private static String read(String file) {
    try {
      return Files.readString(Path.of(file)).strip();
    } catch (IOException e) {
      return "unreadable";
    }
  }
}
