package dev.alex.folderplayer;

import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Shared atomic UTF-8 I/O. A failed replacement preserves the last committed data. */
public final class AtomicTextFiles {
  private AtomicTextFiles() {}

  public static synchronized String read(AtomicFile file) throws IOException {
    try (InputStream in = file.openRead();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
      return out.toString("UTF-8");
    } catch (FileNotFoundException missing) {
      return null;
    }
  }

  public static synchronized void write(AtomicFile file, String value) throws IOException {
    FileOutputStream out = null;
    try {
      out = file.startWrite();
      out.write(value.getBytes(StandardCharsets.UTF_8));
      file.finishWrite(out);
    } catch (IOException | RuntimeException failure) {
      if (out != null) file.failWrite(out);
      throw failure;
    }
  }
}
