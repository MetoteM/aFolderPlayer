package dev.alex.folderplayer;

import ai.onnxruntime.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Pure local CPU inference; this class has no networking or Android dependencies. */
public final class OfflineTranslator implements AutoCloseable {
  public static final String MODEL_ID = "opus-en-ru-v1";
  private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
  private final OrtSession encoder, decoder;
  private final Node trie = new Node();
  private final String[] vocabulary;
  private final int padding;
  private double unknownScore = -100;

  private static final class Node {
    final Map<Character, Node> children = new HashMap<>();
    int id = -1;
    double score;
  }

  public OfflineTranslator(File directory) throws Exception {
    this(directory, 62517);
  }

  public OfflineTranslator(File directory, int padding) throws Exception {
    this.padding = padding;
    environment.setTelemetry(false);
    JSONArray words =
        new JSONObject(read(new File(directory, "tokenizer.json")))
            .getJSONObject("model")
            .getJSONArray("vocab");
    vocabulary = new String[words.length()];
    if (padding < 2 || padding >= words.length()) throw new IOException("Invalid vocabulary");
    double minimum = 0;
    for (int i = 0; i < words.length(); i++) {
      JSONArray item = words.getJSONArray(i);
      String token = item.getString(0);
      double score = item.getDouble(1);
      vocabulary[i] = token;
      if (score <= -1e8) continue;
      minimum = Math.min(minimum, score);
      if (i == 0 || i == 1 || i == padding) continue;
      Node node = trie;
      for (int j = 0; j < token.length(); j++)
        node = node.children.computeIfAbsent(token.charAt(j), k -> new Node());
      node.id = i;
      node.score = score;
    }
    unknownScore = minimum - 10;
    try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
      options.setIntraOpNumThreads(2);
      options.setInterOpNumThreads(1);
      encoder =
          environment.createSession(new File(directory, "encoder.onnx").getAbsolutePath(), options);
      try {
        decoder =
            environment.createSession(
                new File(directory, "decoder.onnx").getAbsolutePath(), options);
      } catch (Exception e) {
        encoder.close();
        throw e;
      }
    }
  }

  static String read(File f) throws IOException {
    try (InputStream in = new FileInputStream(f)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  public long[] encode(String text) {
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).trim();
    List<Long> ids = new ArrayList<>();
    for (String word : normalized.split("\\s+"))
      if (!word.isEmpty()) {
        String value = "▁" + word;
        int length = value.length();
        double[] best = new double[length + 1];
        Arrays.fill(best, Double.NEGATIVE_INFINITY);
        best[0] = 0;
        int[] previous = new int[length + 1], tokens = new int[length + 1];
        for (int i = 0; i < length; i++)
          if (Double.isFinite(best[i])) {
            Node node = trie;
            boolean single = false;
            for (int j = i; j < length; j++) {
              node = node.children.get(value.charAt(j));
              if (node == null) break;
              if (node.id >= 0) {
                if (j == i) single = true;
                double score = best[i] + node.score;
                if (score > best[j + 1]) {
                  best[j + 1] = score;
                  previous[j + 1] = i;
                  tokens[j + 1] = node.id;
                }
              }
            }
            if (!single) {
              int end = i + Character.charCount(value.codePointAt(i));
              double score = best[i] + unknownScore;
              if (score > best[end]) {
                best[end] = score;
                previous[end] = i;
                tokens[end] = 1;
              }
            }
          }
        List<Long> reversed = new ArrayList<>();
        for (int end = length; end > 0; end = previous[end]) reversed.add((long) tokens[end]);
        Collections.reverse(reversed);
        ids.addAll(reversed);
      }
    ids.add(0L);
    long[] result = new long[ids.size()];
    for (int i = 0; i < result.length; i++) result[i] = ids.get(i);
    return result;
  }

  private void check(BooleanSupplier cancelled) throws InterruptedException {
    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
      throw new InterruptedException("Translation cancelled");
  }

  public String sentence(String text, BooleanSupplier cancelled) throws Exception {
    check(cancelled);
    long started = System.nanoTime();
    long[] input = encode(text);
    if (input.length > 100)
      throw new IllegalArgumentException("Строка слишком длинная для перевода");
    long[][] mask = new long[1][input.length];
    Arrays.fill(mask[0], 1);
    try (OnnxTensor ids = OnnxTensor.createTensor(environment, new long[][] {input});
        OnnxTensor attention = OnnxTensor.createTensor(environment, mask);
        OrtSession.Result encoded =
            encoder.run(Map.of("input_ids", ids, "attention_mask", attention))) {
      OnnxTensor hidden = (OnnxTensor) encoded.get("last_hidden_state").orElseThrow();
      List<Long> output = new ArrayList<>();
      output.add((long) padding);
      boolean completed = false;
      for (int step = 0; step < Math.min(160, input.length * 3 + 24); step++) {
        check(cancelled);
        if (System.nanoTime() - started > java.util.concurrent.TimeUnit.SECONDS.toNanos(45))
          throw new IncompleteTranslation("Превышено время перевода фрагмента");
        long[][] target = new long[1][output.size()];
        for (int i = 0; i < output.size(); i++) target[0][i] = output.get(i);
        try (OnnxTensor targetIds = OnnxTensor.createTensor(environment, target);
            OrtSession.Result decoded =
                decoder.run(
                    Map.of(
                        "input_ids",
                        targetIds,
                        "encoder_hidden_states",
                        hidden,
                        "encoder_attention_mask",
                        attention))) {
          OnnxTensor logits = (OnnxTensor) decoded.get("logits").orElseThrow();
          java.nio.FloatBuffer values = logits.getFloatBuffer();
          int size = vocabulary.length;
          int offset = (output.size() - 1) * size;
          int best = 0;
          float score = Float.NEGATIVE_INFINITY;
          for (int i = 0; i < size; i++)
            if (i != padding && i != 1) {
              float value = values.get(offset + i);
              if (value > score) {
                score = value;
                best = i;
              }
            }
          if (best == 0) {
            completed = true;
            break;
          }
          output.add((long) best);
        }
      }
      if (!completed) throw new IncompleteTranslation("Не удалось завершить перевод фрагмента");
      StringBuilder result = new StringBuilder();
      for (int i = 1; i < output.size(); i++) result.append(vocabulary[output.get(i).intValue()]);
      return result.toString().replace('▁', ' ').trim();
    }
  }

  static final class IncompleteTranslation extends IOException {
    IncompleteTranslation(String message) {
      super(message);
    }
  }

  interface SentenceDecoder {
    String decode(String text) throws Exception;
  }

  /** Recover only unfinished generation; never retry cancellation or resource failures. */
  private String adaptive(String text, BooleanSupplier cancelled, RetryBudget budget)
      throws Exception {
    return adaptiveRetry(text, cancelled, 0, value -> sentence(value, cancelled), budget);
  }

  static final class RetryBudget {
    int attempts;
    long started;
    final long[] extraTime;

    RetryBudget() {
      this(new long[1]);
    }

    RetryBudget(long[] extraTime) {
      this.extraTime = extraTime;
    }

    boolean available() {
      return extraTime[0] < java.util.concurrent.TimeUnit.SECONDS.toNanos(120)
          && attempts < 14
          && (started == 0
              || System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(90));
    }

    void use() {
      if (started == 0) started = System.nanoTime();
      attempts++;
    }
  }

  static String adaptiveRetry(
      String text, BooleanSupplier cancelled, int depth, SentenceDecoder decode) throws Exception {
    return adaptiveRetry(text, cancelled, depth, decode, new RetryBudget());
  }

  private static String adaptiveRetry(
      String text, BooleanSupplier cancelled, int depth, SentenceDecoder decode, RetryBudget budget)
      throws Exception {
    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
      throw new InterruptedException("Translation cancelled");
    try {
      long before = System.nanoTime();
      try {
        return decode.decode(text);
      } finally {
        if (depth > 0) budget.extraTime[0] += System.nanoTime() - before;
      }
    } catch (IncompleteTranslation failure) {
      if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
        throw new InterruptedException("Translation cancelled");
      List<String> pieces = splitRecovery(text);
      if (depth >= 3 || pieces.size() < 2 || !budget.available()) throw failure;
      List<String> result = new ArrayList<>();
      for (String piece : pieces) {
        if (!budget.available()) throw failure;
        budget.use();
        result.add(adaptiveRetry(piece, cancelled, depth + 1, decode, budget));
      }
      return String.join(" ", result);
    }
  }

  static List<String> splitRecovery(String text) {
    text = text.trim();
    // Prefer sentence/clause boundaries near the middle; preserve all source characters.
    int middle = text.length() / 2, best = -1, distance = Integer.MAX_VALUE;
    for (int i = 1; i < text.length() - 1; i++) {
      if (".!?;,:。！？；，：".indexOf(text.charAt(i)) >= 0 && Math.abs(i + 1 - middle) < distance) {
        best = i + 1;
        distance = Math.abs(best - middle);
      }
    }
    if (best < 0) {
      for (int i = 1; i < text.length() - 1; i++) {
        if (Character.isWhitespace(text.charAt(i)) && Math.abs(i - middle) < distance) {
          best = i;
          distance = Math.abs(i - middle);
        }
      }
    }
    // CJK has no spaces; split safely between Unicode code points.
    if (best < 0
        && text.codePoints()
            .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
      int count = text.codePointCount(0, text.length());
      if (count > 1) best = text.offsetByCodePoints(0, count / 2);
    }
    if (best < 1 || best >= text.length()) return Collections.singletonList(text);
    String left = text.substring(0, best).trim(), right = text.substring(best).trim();
    if (left.isEmpty() || right.isEmpty()) return Collections.singletonList(text);
    return Arrays.asList(left, right);
  }

  public interface Progress {
    void update(int done, int total);
  }

  public String translate(String text, BooleanSupplier cancelled, Progress progress)
      throws Exception {
    return translateResult(text, cancelled, progress, false).text;
  }

  public static final class TranslationResult {
    public final String text;
    public final List<Integer> failedLines;

    TranslationResult(String text, List<Integer> failedLines) {
      this.text = text;
      this.failedLines = failedLines;
    }
  }

  public TranslationResult translateResult(
      String text, BooleanSupplier cancelled, Progress progress, boolean allowPartial)
      throws Exception {
    String[] lines = text.replace("\r", "").split("\n", -1);
    if (lines.length > 500 || text.length() > 20000)
      throw new IllegalArgumentException("Перевод поддерживает до 20 000 символов и 500 строк");
    StringBuilder result = new StringBuilder();
    Map<String, String> repeated = new HashMap<>();
    List<Integer> failed = new ArrayList<>();
    Set<String> failedSources = new HashSet<>();
    long[] extraTime = new long[1];
    for (int i = 0; i < lines.length; i++) {
      check(cancelled);
      String line = lines[i].trim();
      if (i > 0) result.append('\n');
      if (!line.isEmpty()) {
        String translated = repeated.get(line);
        if (failedSources.contains(line)) {
          failed.add(i);
          translated = line;
        }
        if (translated == null) {
          try {
            RetryBudget budget = new RetryBudget(extraTime);
            StringBuilder value = new StringBuilder();
            for (String chunk : chunks(line)) {
              if (value.length() > 0) value.append(' ');
              value.append(adaptive(chunk, cancelled, budget));
            }
            translated = value.toString();
            repeated.put(line, translated);
          } catch (IncompleteTranslation failure) {
            if (!allowPartial) throw failure;
            failed.add(i);
            failedSources.add(line);
            translated = line;
          }
        }
        result.append(translated);
      }
      progress.update(i + 1, lines.length);
    }
    return new TranslationResult(result.toString(), failed);
  }

  public List<String> chunks(String line) {
    List<String> result = new ArrayList<>();
    StringBuilder pending = new StringBuilder();
    for (String word : line.split("\\s+")) {
      if (word.isEmpty()) continue;
      String next = pending.length() == 0 ? word : pending + " " + word;
      if (encode(next).length <= 70) {
        pending.setLength(0);
        pending.append(next);
        continue;
      }
      if (pending.length() > 0) {
        result.add(pending.toString());
        pending.setLength(0);
      }
      for (int offset = 0; offset < word.length(); ) {
        int cp = word.codePointAt(offset);
        String character = new String(Character.toChars(cp));
        if (encode(pending + character).length > 70 && pending.length() > 0) {
          result.add(pending.toString());
          pending.setLength(0);
        }
        pending.append(character);
        offset += Character.charCount(cp);
        if ("。！？；!?;".indexOf(cp) >= 0) {
          result.add(pending.toString());
          pending.setLength(0);
        }
      }
    }
    if (pending.length() > 0) result.add(pending.toString());
    return result;
  }

  @Override
  public void close() throws OrtException {
    try {
      decoder.close();
    } finally {
      encoder.close();
    }
  }
}
