package filler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes up to 1 GiB into target/filler.bin, 4 MiB every 50 milliseconds. */
public class Filler {

  public static void main(String[] args) throws IOException, InterruptedException {
    byte[] chunk = new byte[4 * 1024 * 1024];
    long written = 0;
    try (OutputStream out = Files.newOutputStream(Path.of("target/filler.bin"))) {
      while (written < 1024L * 1024 * 1024) {
        out.write(chunk);
        out.flush();
        written += chunk.length;
        if (written % (64L * 1024 * 1024) == 0) {
          System.out.println("filler wrote " + written + " bytes");
        }
        Thread.sleep(50);
      }
    }
  }
}
