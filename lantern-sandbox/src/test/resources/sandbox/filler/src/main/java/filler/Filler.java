package filler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes into target/filler.bin, 4 MiB every 100 milliseconds, until it is stopped. */
public class Filler {

  public static void main(String[] args) throws IOException, InterruptedException {
    byte[] chunk = new byte[4 * 1024 * 1024];
    long written = 0;
    try (OutputStream out = Files.newOutputStream(Path.of("target/filler.bin"))) {
      while (true) {
        out.write(chunk);
        out.flush();
        written += chunk.length;
        if (written % (64L * 1024 * 1024) == 0) {
          System.out.println("filler wrote " + written + " bytes");
        }
        Thread.sleep(100);
      }
    }
  }
}
