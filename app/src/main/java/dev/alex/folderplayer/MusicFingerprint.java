package dev.alex.folderplayer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Java port of the ShazamIO 0.4.0.0 fingerprint algorithm (MIT, dotX12). Fixed missing fourth band
 * and spreading history; self-contained radix-2 FFT. Input is signed PCM16, mono, 16000 Hz. No
 * audio or identifying metadata is encoded.
 */
public final class MusicFingerprint {
  private static final int N = 2048;

  private static final class Peak {
    int frame, magnitude, bin;

    Peak(int f, int m, int b) {
      frame = f;
      magnitude = m;
      bin = b;
    }
  }

  public static String encode(short[] samples) {
    if (samples.length < 16000 * 3 || samples.length > 16000 * 30)
      throw new IllegalArgumentException("Audio duration");
    double[][] spectra = new double[256][1025], spread = new double[256][1025];
    short[] ring = new short[N];
    double[] window = new double[N];
    for (int i = 0; i < N; i++) window[i] = .5 - .5 * Math.cos(2 * Math.PI * (i + 1) / (N + 1));
    List<List<Peak>> bands = new ArrayList<>();
    for (int i = 0; i < 4; i++) bands.add(new ArrayList<>());
    int pos = 0, index = 0, frames = 0;
    for (int off = 0; off + 128 <= samples.length; off += 128) {
      if (Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException();
      System.arraycopy(samples, off, ring, pos, 128);
      pos = (pos + 128) & 2047;
      double[] re = new double[N], im = new double[N];
      for (int i = 0; i < N; i++) re[i] = ring[(pos + i) & 2047] * window[i];
      fft(re, im);
      double[] power = spectra[index];
      for (int i = 0; i <= 1024; i++)
        power[i] = Math.max(1e-10, (re[i] * re[i] + im[i] * im[i]) / 131072);
      double[] current = power.clone();
      for (int i = 0; i < 1022; i++)
        current[i] = Math.max(power[i], Math.max(power[i + 1], power[i + 2]));
      for (int lag : new int[] {1, 3, 6}) {
        double[] old = spread[(index - lag) & 255];
        for (int i = 0; i <= 1024; i++) old[i] = Math.max(old[i], current[i]);
      }
      spread[index] = current;
      index = (index + 1) & 255;
      frames++;
      if (frames < 46) continue;
      double[] center = spectra[(index - 46) & 255], nearby = spread[(index - 49) & 255];
      for (int bin = 10; bin < 1015; bin++) {
        double p = center[bin];
        if (p < 1.0 / 64 || p < nearby[bin - 1]) continue;
        double max = 0;
        for (int d : new int[] {-10, -7, -4, -3, 1, 2, 5, 8}) max = Math.max(max, nearby[bin + d]);
        if (p <= max) continue;
        for (int d :
            new int[] {-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249})
          max = Math.max(max, spread[(index + d) & 255][bin - 1]);
        if (p <= max) continue;
        double m = mag(p), before = mag(center[bin - 1]), after = mag(center[bin + 1]);
        double curve = 2 * m - before - after;
        if (curve <= 0) continue;
        int corrected = (int) (bin * 64 + (after - before) * 32 / curve);
        double hz = corrected * 16000.0 / 131072;
        int band =
            hz > 250 && hz < 520
                ? 0
                : hz >= 520 && hz < 1450
                    ? 1
                    : hz >= 1450 && hz < 3500 ? 2 : hz >= 3500 && hz < 5500 ? 3 : -1;
        if (band >= 0) bands.get(band).add(new Peak(frames - 46, (int) m, corrected));
      }
    }
    ByteArrayOutputStream contents = new ByteArrayOutputStream();
    for (int b = 0; b < 4; b++) {
      if (bands.get(b).isEmpty()) continue;
      ByteArrayOutputStream peaks = new ByteArrayOutputStream();
      int prev = 0;
      for (Peak p : bands.get(b)) {
        if (p.frame - prev >= 255) {
          peaks.write(255);
          putInt(peaks, p.frame);
          prev = p.frame;
        }
        peaks.write(p.frame - prev);
        putShort(peaks, p.magnitude);
        putShort(peaks, p.bin);
        prev = p.frame;
      }
      putInt(contents, 0x60030040 + b);
      putInt(contents, peaks.size());
      byte[] bytes = peaks.toByteArray();
      contents.write(bytes, 0, bytes.length);
      while ((contents.size() & 3) != 0) contents.write(0);
    }
    ByteBuffer out = ByteBuffer.allocate(56 + contents.size()).order(ByteOrder.LITTLE_ENDIAN);
    out.putInt(0xCAFE2580).putInt(0).putInt(contents.size() + 8).putInt(0x94119C00);
    out.putInt(0)
        .putInt(0)
        .putInt(0)
        .putInt(3 << 27)
        .putInt(0)
        .putInt(0)
        .putInt(samples.length / 128 * 128 + 3840)
        .putInt((15 << 19) + 0x40000);
    out.putInt(0x40000000).putInt(contents.size() + 8).put(contents.toByteArray());
    CRC32 crc = new CRC32();
    crc.update(out.array(), 8, out.capacity() - 8);
    out.putInt(4, (int) crc.getValue());
    return "data:audio/vnd.shazam.sig;base64," + Base64.getEncoder().encodeToString(out.array());
  }

  private static double mag(double p) {
    return Math.log(Math.max(1.0 / 64, p)) * 1477.3 + 6144;
  }

  private static void putInt(ByteArrayOutputStream s, int x) {
    for (int i = 0; i < 4; i++) s.write(x >>> (8 * i));
  }

  private static void putShort(ByteArrayOutputStream s, int x) {
    s.write(x);
    s.write(x >>> 8);
  }

  private static void fft(double[] re, double[] im) {
    for (int i = 1, j = 0; i < N; i++) {
      int bit = N >> 1;
      for (; (j & bit) != 0; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) {
        double t = re[i];
        re[i] = re[j];
        re[j] = t;
      }
    }
    for (int len = 2; len <= N; len <<= 1) {
      double angle = -2 * Math.PI / len, wr = Math.cos(angle), wi = Math.sin(angle);
      for (int start = 0; start < N; start += len) {
        double r = 1, v = 0;
        for (int j = 0; j < len / 2; j++) {
          int a = start + j, b = a + len / 2;
          double br = re[b] * r - im[b] * v, bi = re[b] * v + im[b] * r;
          re[b] = re[a] - br;
          im[b] = im[a] - bi;
          re[a] += br;
          im[a] += bi;
          double next = r * wr - v * wi;
          v = r * wi + v * wr;
          r = next;
        }
      }
    }
  }
}
