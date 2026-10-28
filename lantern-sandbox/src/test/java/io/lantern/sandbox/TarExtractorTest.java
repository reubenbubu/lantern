package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TarExtractorTest {

  @TempDir Path temp;

  /** Builds tar streams the way docker cp writes them. */
  private static final class Tar {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Tar entry(String name, char type, byte[] content, String link) {
      byte[] header = new byte[512];
      put(header, 0, 100, name);
      put(header, 100, 8, "0000644");
      put(header, 108, 8, "0000000");
      put(header, 116, 8, "0000000");
      put(header, 124, 12, String.format("%011o", content.length));
      put(header, 136, 12, "00000000000");
      header[156] = (byte) type;
      put(header, 157, 100, link);
      put(header, 257, 6, "ustar");
      put(header, 263, 2, "00");
      Arrays.fill(header, 148, 156, (byte) ' ');
      long sum = 0;
      for (byte b : header) {
        sum += b & 0xff;
      }
      put(header, 148, 8, String.format("%06o", sum));
      header[155] = ' ';
      out.writeBytes(header);
      out.writeBytes(content);
      int padding = (512 - content.length % 512) % 512;
      out.writeBytes(new byte[padding]);
      return this;
    }

    Tar dir(String name) {
      return entry(name, '5', new byte[0], "");
    }

    Tar file(String name, String content) {
      return entry(name, '0', content.getBytes(StandardCharsets.UTF_8), "");
    }

    Tar pax(String key, String value) {
      String record = " " + key + "=" + value + "\n";
      int length = record.length();
      length += Integer.toString(length + Integer.toString(length).length()).length();
      return entry("PaxHeaders/x", 'x', (length + record).getBytes(StandardCharsets.UTF_8), "");
    }

    byte[] end() {
      out.writeBytes(new byte[1024]);
      return out.toByteArray();
    }

    private static void put(byte[] header, int offset, int length, String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      System.arraycopy(bytes, 0, header, offset, Math.min(length, bytes.length));
    }
  }

  private void extract(byte[] tar) throws IOException {
    new TarExtractor(1_000_000, 1_000).extract(new ByteArrayInputStream(tar), temp);
  }

  @Test
  void extractsFilesAndDirectoriesBelowTheArchivedDirectory() throws IOException {
    extract(
        new Tar()
            .dir("out/")
            .dir("out/lib/")
            .file("out/lib/a.jar", "jar")
            .file("out/top.txt", "top")
            .dir("out/empty/")
            .end());

    assertThat(temp.resolve("lib/a.jar")).hasContent("jar");
    assertThat(temp.resolve("top.txt")).hasContent("top");
    assertThat(temp.resolve("empty")).isEmptyDirectory();
  }

  @Test
  void skipsLinksAndSpecialFiles() throws IOException {
    extract(
        new Tar()
            .dir("out/")
            .entry("out/passwd", '2', new byte[0], "/etc/passwd")
            .entry("out/hard", '1', new byte[0], "out/x")
            .entry("out/fifo", '6', new byte[0], "")
            .file("out/kept", "kept")
            .end());

    try (var files = Files.list(temp)) {
      assertThat(files.map(p -> p.getFileName().toString())).containsExactly("kept");
    }
  }

  @Test
  void refusesPathsOutsideTheTarget() {
    assertThatThrownBy(() -> extract(new Tar().file("out/../escape", "x").end()))
        .isInstanceOf(TarExtractor.TarException.class);
    assertThatThrownBy(() -> extract(new Tar().file("../escape", "x").end()))
        .isInstanceOf(TarExtractor.TarException.class);
    assertThatThrownBy(() -> extract(new Tar().file("/out/abs", "x").end()))
        .isInstanceOf(TarExtractor.TarException.class);
    assertThat(temp.resolveSibling("escape")).doesNotExist();
  }

  @Test
  void refusesPathsThroughFiles() {
    assertThatThrownBy(
            () -> extract(new Tar().file("out/a", "file").file("out/a/b", "under a file").end()))
        .isInstanceOf(TarExtractor.TarException.class);
  }

  @Test
  void enforcesTheLimits() {
    byte[] big = new Tar().file("out/a", "x".repeat(2000)).end();
    assertThatThrownBy(
            () -> new TarExtractor(1000, 100).extract(new ByteArrayInputStream(big), temp))
        .isInstanceOf(TarExtractor.TarException.class)
        .hasMessageContaining("1000 bytes");
    byte[] many = new Tar().file("out/a", "").file("out/b", "").file("out/c", "").end();
    assertThatThrownBy(
            () -> new TarExtractor(1000, 2).extract(new ByteArrayInputStream(many), temp))
        .isInstanceOf(TarExtractor.TarException.class)
        .hasMessageContaining("entries");
  }

  @Test
  void readsPaxAndGnuLongNames() throws IOException {
    String deep = "out/" + "d/".repeat(80) + "file.txt";
    String gnu = "out/" + "g/".repeat(80) + "file.txt";
    extract(
        new Tar()
            .pax("path", deep)
            .file("ignored", "pax")
            .entry("././@LongLink", 'L', (gnu + "\0").getBytes(StandardCharsets.UTF_8), "")
            .file("ignored", "gnu")
            .end());

    assertThat(temp.resolve(deep.substring(4))).hasContent("pax");
    assertThat(temp.resolve(gnu.substring(4))).hasContent("gnu");
  }

  @Test
  void refusesMalformedArchives() {
    byte[] good = new Tar().file("out/a", "content").end();
    byte[] truncated = Arrays.copyOf(good, 700);
    assertThatThrownBy(() -> extract(truncated)).isInstanceOf(IOException.class);
    byte[] noEnd = Arrays.copyOf(good, 1024);
    assertThatThrownBy(() -> extract(noEnd)).isInstanceOf(TarExtractor.TarException.class);
    byte[] badChecksum = good.clone();
    badChecksum[0] = 'b';
    assertThatThrownBy(() -> extract(badChecksum))
        .isInstanceOf(TarExtractor.TarException.class)
        .hasMessageContaining("checksum");
  }
}
