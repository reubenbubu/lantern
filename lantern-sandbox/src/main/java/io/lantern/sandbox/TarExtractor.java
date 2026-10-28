package io.lantern.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Extracts a tar stream that comes out of a build container, so it is treated as hostile: only
 * regular files and directories are written, every path must stay inside the target directory, and
 * the total size and number of entries are limited. Symbolic links, hard links, devices and other
 * entry types are skipped. File modes and owners in the archive are ignored.
 *
 * <p>Reads the POSIX ustar format with pax extended headers and GNU long names, which covers what
 * {@code docker cp} writes.
 */
final class TarExtractor {

  /** A tar stream that is malformed or breaks a limit; nothing it extracted should be trusted. */
  static final class TarException extends IOException {
    TarException(String message) {
      super(message);
    }
  }

  private static final int BLOCK = 512;
  private static final int MAX_HEADER_BYTES = 1024 * 1024;

  private final long maxBytes;
  private final long maxEntries;

  /**
   * @param maxBytes the most bytes of file content to extract
   * @param maxEntries the most archive entries to read
   */
  TarExtractor(long maxBytes, long maxEntries) {
    this.maxBytes = maxBytes;
    this.maxEntries = maxEntries;
  }

  /**
   * Extracts the stream into the directory, dropping the first path component of every entry (the
   * name of the directory that was archived).
   *
   * @param in the tar stream; read to its end-of-archive marker
   * @param target an existing directory
   * @throws TarException if the stream is malformed, an entry escapes the directory, or a limit is
   *     exceeded
   */
  void extract(InputStream in, Path target) throws IOException {
    Path root = target.toRealPath();
    long bytes = 0;
    long entries = 0;
    String longName = null;
    String paxPath = null;
    Long paxSize = null;
    byte[] header = new byte[BLOCK];
    while (true) {
      if (!readBlock(in, header)) {
        throw new TarException("Archive ends without an end marker");
      }
      if (isZero(header)) {
        return;
      }
      if (++entries > maxEntries) {
        throw new TarException("Archive has more than " + maxEntries + " entries");
      }
      if (!checksumOk(header)) {
        throw new TarException("Bad tar header checksum");
      }
      char type = (char) header[156];
      long size = number(header, 124, 12);
      switch (type) {
        case 'x' -> {
          byte[] pax = readSmall(in, size);
          paxPath = paxValue(pax, "path");
          String paxSizeText = paxValue(pax, "size");
          paxSize = paxSizeText == null ? null : parseLong(paxSizeText);
          continue;
        }
        case 'L' -> {
          longName = cString(readSmall(in, size), 0, (int) size);
          continue;
        }
        case 'g', 'K' -> {
          skip(in, size);
          continue;
        }
        default -> {}
      }
      if (paxSize != null) {
        size = paxSize;
      }
      if (size < 0) {
        throw new TarException("Negative entry size");
      }
      String name = paxPath != null ? paxPath : longName != null ? longName : ustarName(header);
      longName = null;
      paxPath = null;
      paxSize = null;
      Path destination = destination(root, name);
      if (type == '0' || type == '\0' || type == '7') {
        bytes += size;
        if (bytes > maxBytes) {
          throw new TarException("Archive content exceeds " + maxBytes + " bytes");
        }
        if (destination == null) {
          skip(in, size);
          continue;
        }
        createDirectories(root, destination.getParent());
        if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
          throw new TarException("File entry over a directory: " + name);
        }
        try (OutputStream out =
            Files.newOutputStream(
                destination,
                LinkOption.NOFOLLOW_LINKS,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
          copy(in, out, size);
        }
        skipPadding(in, size);
      } else if (type == '5') {
        if (destination != null) {
          createDirectories(root, destination);
        }
        skip(in, size);
      } else {
        // Links, devices, FIFOs and unknown types are not extracted.
        skip(in, size);
      }
    }
  }

  /**
   * Where an entry goes, or null for the archived directory itself.
   *
   * @throws TarException if the entry would land outside the root
   */
  private static Path destination(Path root, String name) throws TarException {
    String[] parts = name.replace('\\', '/').split("/");
    // Drop the first component, the archived directory, and empty or "." parts.
    StringBuilder relative = new StringBuilder();
    boolean first = true;
    for (String part : parts) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (first) {
        first = false;
        if (part.equals("..")) {
          throw new TarException("Entry escapes the target directory: " + name);
        }
        continue;
      }
      if (part.equals("..") || part.indexOf('\0') >= 0) {
        throw new TarException("Entry escapes the target directory: " + name);
      }
      if (!relative.isEmpty()) {
        relative.append('/');
      }
      relative.append(part);
    }
    if (name.startsWith("/")) {
      throw new TarException("Absolute entry path: " + name);
    }
    if (relative.isEmpty()) {
      return null;
    }
    Path destination = root.resolve(relative.toString()).normalize();
    if (!destination.startsWith(root) || destination.equals(root)) {
      throw new TarException("Entry escapes the target directory: " + name);
    }
    return destination;
  }

  /** Creates directories below the root, refusing to pass through anything but directories. */
  private static void createDirectories(Path root, Path dir) throws IOException {
    Path current = root;
    for (Path part : root.relativize(dir)) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)
          || (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
              && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))) {
        throw new TarException("Entry path passes through a non-directory: " + current);
      }
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        Files.createDirectory(current);
      }
    }
  }

  private static String ustarName(byte[] header) {
    String name = cString(header, 0, 100);
    String magic = cString(header, 257, 6);
    if (magic.startsWith("ustar")) {
      String prefix = cString(header, 345, 155);
      if (!prefix.isEmpty()) {
        return prefix + "/" + name;
      }
    }
    return name;
  }

  /** The value of a key in pax extended header records ({@code "<len> key=value\n"}). */
  private static String paxValue(byte[] records, String key) throws TarException {
    String value = null;
    int i = 0;
    while (i < records.length) {
      int space = i;
      while (space < records.length && records[space] != ' ') {
        space++;
      }
      if (space == records.length) {
        break;
      }
      int length;
      try {
        length = Integer.parseInt(new String(records, i, space - i, StandardCharsets.US_ASCII));
      } catch (NumberFormatException e) {
        throw new TarException("Bad pax record");
      }
      int end = i + length;
      if (length <= 0 || end <= space + 1 || end > records.length || records[end - 1] != '\n') {
        throw new TarException("Bad pax record");
      }
      String record = new String(records, space + 1, end - space - 2, StandardCharsets.UTF_8);
      int eq = record.indexOf('=');
      if (eq > 0 && record.substring(0, eq).equals(key)) {
        value = record.substring(eq + 1);
      }
      i = end;
    }
    return value;
  }

  private static long parseLong(String text) throws TarException {
    try {
      return Long.parseLong(text.strip());
    } catch (NumberFormatException e) {
      throw new TarException("Bad number in tar header: " + text);
    }
  }

  /** An octal number field, or a base-256 one when the top bit of its first byte is set. */
  private static long number(byte[] header, int offset, int length) throws TarException {
    if ((header[offset] & 0x80) != 0) {
      long value = header[offset] & 0x7f;
      for (int i = 1; i < length; i++) {
        if (value > (Long.MAX_VALUE >> 8)) {
          throw new TarException("Tar number too large");
        }
        value = (value << 8) | (header[offset + i] & 0xff);
      }
      return value;
    }
    String text = cString(header, offset, length).strip();
    if (text.isEmpty()) {
      return 0;
    }
    try {
      return Long.parseLong(text, 8);
    } catch (NumberFormatException e) {
      throw new TarException("Bad number in tar header: " + text);
    }
  }

  private static boolean checksumOk(byte[] header) throws TarException {
    long expected = number(header, 148, 8);
    long sum = 0;
    for (int i = 0; i < BLOCK; i++) {
      sum += (i >= 148 && i < 156) ? ' ' : (header[i] & 0xff);
    }
    return sum == expected;
  }

  private static String cString(byte[] bytes, int offset, int length) {
    int end = offset;
    while (end < offset + length && end < bytes.length && bytes[end] != 0) {
      end++;
    }
    return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
  }

  private static boolean isZero(byte[] block) {
    for (byte b : block) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean readBlock(InputStream in, byte[] block) throws IOException {
    int read = in.readNBytes(block, 0, BLOCK);
    if (read == 0) {
      return false;
    }
    if (read < BLOCK) {
      throw new TarException("Truncated tar header");
    }
    return true;
  }

  private static byte[] readSmall(InputStream in, long size) throws IOException {
    if (size > MAX_HEADER_BYTES) {
      throw new TarException("Extended tar header too large: " + size);
    }
    byte[] data = in.readNBytes((int) size);
    if (data.length < size) {
      throw new TarException("Truncated tar entry");
    }
    skipPadding(in, size);
    return data;
  }

  private static void copy(InputStream in, OutputStream out, long size) throws IOException {
    byte[] buffer = new byte[64 * 1024];
    long left = size;
    while (left > 0) {
      int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
      if (read < 0) {
        throw new TarException("Truncated tar entry");
      }
      out.write(buffer, 0, read);
      left -= read;
    }
  }

  private static void skip(InputStream in, long size) throws IOException {
    long total = size + padding(size);
    in.skipNBytes(total);
  }

  private static void skipPadding(InputStream in, long size) throws IOException {
    in.skipNBytes(padding(size));
  }

  private static long padding(long size) {
    long rest = size % BLOCK;
    return rest == 0 ? 0 : BLOCK - rest;
  }
}
