package dev.alex.folderplayer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.json.*;

/** One explicit, bounded request. No automatic retries or music uploads. */
public final class LyricsSearch {
  public static final int MAX_RESPONSE = 2 * 1024 * 1024;

  public interface Connections {
    HttpURLConnection open(URL url) throws IOException;
  }

  private final Connections connections;
  private long nextAllowedAt;

  public synchronized long cooldown() {
    return nextAllowedAt;
  }

  public synchronized void restoreCooldown(long value) {
    nextAllowedAt = Math.max(nextAllowedAt, value);
  }

  public LyricsSearch() {
    this(url -> (HttpURLConnection) url.openConnection());
  }

  public LyricsSearch(Connections value) {
    connections = value;
  }

  public static final class Cancel {
    private boolean cancelled;
    private HttpURLConnection active;
    private final long deadline = System.nanoTime() + 45_000_000_000L;

    synchronized int remaining() throws IOException {
      check();
      return (int) Math.max(1, Math.min(8000, (deadline - System.nanoTime()) / 1_000_000L));
    }

    public synchronized void cancel() {
      cancelled = true;
      if (active != null) active.disconnect();
    }

    synchronized void attach(HttpURLConnection c) throws IOException {
      if (cancelled) {
        c.disconnect();
        throw new IOException("Поиск отменён");
      }
      ;
      active = c;
    }

    synchronized void check() throws IOException {
      if (cancelled || Thread.currentThread().isInterrupted())
        throw new IOException("Поиск отменён");
      if (System.nanoTime() > deadline)
        throw new SocketTimeoutException("Поиск занял слишком долго");
    }

    synchronized void clear() {
      active = null;
    }
  }

  public static final class Result {
    public final long id;
    public final String title, artist, album, text;
    public final double duration;
    public final boolean timed, instrumental;

    Result(JSONObject o) throws JSONException {
      id = o.getLong("id");
      title = o.optString("trackName", "");
      artist = o.optString("artistName", "");
      album = o.optString("albumName", "");
      duration = o.optDouble("duration", 0);
      instrumental = o.optBoolean("instrumental", false);
      String sync = o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", "");
      String plain = o.isNull("plainLyrics") ? "" : o.optString("plainLyrics", "");
      LyricsDocument parsed = null;
      if (!sync.trim().isEmpty())
        try {
          parsed = new LyricsDocument(sync);
        } catch (IllegalArgumentException ignored) {
        }
      timed = parsed != null && !parsed.cues.isEmpty();
      text = timed ? sync : plain;
      if (!text.isEmpty()) new LyricsDocument(text);
    }
  }

  public static URL url(String title, String artist) throws IOException {
    title = title.trim();
    artist = artist.trim();
    if (title.isEmpty()) throw new IOException("Введи название песни");
    if (title.length() > 200 || artist.length() > 200)
      throw new IOException("Запрос слишком длинный");
    return new URL(
        "https://lrclib.net/api/search?track_name="
            + URLEncoder.encode(title, "UTF-8")
            + (artist.isEmpty() ? "" : "&artist_name=" + URLEncoder.encode(artist, "UTF-8")));
  }

  public static long retryMillis(String header, long now) {
    if (header != null) {
      try {
        long seconds = Long.parseLong(header.trim());
        if (seconds >= 0 && seconds < Long.MAX_VALUE / 1000) return Math.max(1000, seconds * 1000);
      } catch (NumberFormatException ignored) {
      }
      try {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        f.setLenient(false);
        return Math.max(1000, f.parse(header).getTime() - now);
      } catch (Exception ignored) {
      }
    }
    return 60000;
  }

  public void beforeRequest() throws IOException {
    long now = System.currentTimeMillis();
    synchronized (this) {
      if (now < nextAllowedAt)
        throw new IOException(
            "Подожди " + ((nextAllowedAt - now + 999) / 1000) + " с перед следующим поиском");
      nextAllowedAt = now + 500;
    }
  }

  public static List<Result> parse(String body, long durationMs) throws Exception {
    if (body.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE)
      throw new IOException("Ответ слишком большой");
    JSONArray data = new JSONArray(body);
    List<Result> results = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (int i = 0; i < Math.min(data.length(), 100); i++) {
      try {
        Result result = new Result(data.getJSONObject(i));
        if (seen.add(result.id) && (!result.text.trim().isEmpty() || result.instrumental))
          results.add(result);
      } catch (JSONException | IllegalArgumentException ignored) {
      }
    }
    results.sort(
        Comparator.comparingDouble(
            result ->
                durationMs > 0 && result.duration > 0 && Double.isFinite(result.duration)
                    ? Math.abs(result.duration - durationMs / 1000.0)
                    : Double.MAX_VALUE));
    return results.subList(0, Math.min(20, results.size()));
  }

  public List<Result> search(String title, String artist, long durationMs, Cancel cancel)
      throws Exception {
    return request(url(title, artist), durationMs, cancel);
  }

  private List<Result> request(URL url, long durationMs, Cancel cancel) throws Exception {
    beforeRequest();
    cancel.check();
    HttpURLConnection c = connections.open(url);
    cancel.attach(c);
    try {
      c.setConnectTimeout(cancel.remaining());
      c.setReadTimeout(cancel.remaining());
      c.setInstanceFollowRedirects(false);
      c.setRequestProperty("User-Agent", "FolderPlayer/0.5.0 (personal Android music player)");
      c.setRequestProperty("Accept", "application/json");
      int status = c.getResponseCode();
      cancel.check();
      if (status == 429 || status == 503) {
        long wait = retryMillis(c.getHeaderField("Retry-After"), System.currentTimeMillis());
        synchronized (this) {
          nextAllowedAt =
              wait > Long.MAX_VALUE - System.currentTimeMillis()
                  ? Long.MAX_VALUE
                  : System.currentTimeMillis() + wait;
        }
        throw new IOException(
            (status == 503 ? "Сервис текстов временно недоступен" : "LRCLIB ограничил запросы")
                + ". Повтори через "
                + (wait / 1000)
                + " с");
      }
      if (status != 200)
        throw new IOException("LRCLIB недоступен (HTTP " + status + "). Попробуй позже");
      try (InputStream stream =
          "gzip".equalsIgnoreCase(c.getContentEncoding())
              ? new GZIPInputStream(c.getInputStream())
              : c.getInputStream()) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] bytes = new byte[4096];
        int n;
        while ((n = stream.read(bytes)) != -1) {
          cancel.check();
          if (body.size() + n > MAX_RESPONSE)
            throw new IOException("Ответ слишком большой. Уточни название и исполнителя");
          body.write(bytes, 0, n);
        }
        return parse(new String(body.toByteArray(), StandardCharsets.UTF_8), durationMs);
      }
    } finally {
      c.disconnect();
      cancel.clear();
    }
  }

  /** Explicit search with at most three queries. Covers are returned for user review only. */
  public List<Result> searchExpanded(String title, String artist, long durationMs, Cancel cancel)
      throws Exception {
    url(title, artist); // Validate before normalization or network access.
    List<Result> found = search(title, artist, durationMs, cancel);
    if (found.stream().anyMatch(r -> !r.text.trim().isEmpty())) return found;
    String name = cleanTitle(title), performer = clean(artist);
    if (name.isEmpty()) return found;
    if (!name.equals(title.trim()) || !performer.equals(artist.trim())) {
      awaitReady(cancel);
      found = search(name, performer, durationMs, cancel);
      if (found.stream().anyMatch(r -> !r.text.trim().isEmpty())) return found;
    }
    awaitReady(cancel);
    found =
        request(
            new URL("https://lrclib.net/api/search?q=" + URLEncoder.encode(name, "UTF-8")),
            durationMs,
            cancel);
    List<Result> useful = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Result r : found) {
      if (!r.text.trim().isEmpty() && related(name, r.title)) {
        String key =
            clean(r.artist) + "|" + new LyricsDocument(r.text).plain.replaceAll("\\s+", " ").trim();
        if (seen.add(key)) useful.add(r);
      }
    }
    useful.sort(Comparator.comparingInt(r -> clean(r.artist).equals(performer) ? 0 : 1));
    return useful;
  }

  private void awaitReady(Cancel cancel) throws Exception {
    while (true) {
      cancel.check();
      long remaining = cooldown() - System.currentTimeMillis();
      if (remaining <= 0) return;
      Thread.sleep(Math.min(50, remaining));
    }
  }

  public static String clean(String value) {
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .replaceAll("[\\p{Cf}\\p{Cc}]", " ")
        .replace('ё', 'е')
        .replace('Ё', 'Е')
        .replaceAll("\\s+", " ")
        .trim()
        .toLowerCase(Locale.ROOT);
  }

  public static String cleanTitle(String value) {
    return clean(value)
        .replaceAll(
            "(?i)\\s*\\([^)]*(?:remaster|official|audio|video|feat\\.|ft\\.|ремастер)[^)]*\\)", "")
        .replaceAll("(?i)\\s+(?:feat\\.|ft\\.)\\s+.*$", "")
        .replaceAll("[\\p{Punct}«»“”‘’]", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  public static boolean related(String requested, String candidate) {
    Set<String> wanted = new HashSet<>(Arrays.asList(cleanTitle(requested).split(" "))),
        actual = new HashSet<>(Arrays.asList(cleanTitle(candidate).split(" ")));
    wanted.remove("");
    actual.remove("");
    if (wanted.isEmpty()) return false;
    Set<String> shared = new HashSet<>(wanted);
    shared.retainAll(actual);
    return shared.size() >= Math.max(1, (int) Math.ceil(wanted.size() * .75))
        && shared.size() >= Math.max(1, (int) Math.ceil(actual.size() * .6));
  }
}
