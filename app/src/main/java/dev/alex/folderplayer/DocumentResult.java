package dev.alex.folderplayer;

import android.content.Context;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Full comparison data without Activity references; no partial result is published. */
final class DocumentResult {
  final String id = UUID.randomUUID().toString();
  final String source, model;
  final List<String> blocks, parts, notices;

  DocumentResult(
      String source, String model, List<String> blocks, List<String> parts, List<String> notices) {
    this.source = source;
    this.model = model;
    this.blocks = blocks;
    this.parts = parts;
    this.notices = notices;
  }

  static DocumentResult translate(
      Context context,
      String source,
      String model,
      AtomicBoolean cancel,
      RetainedOperation.Progress progress)
      throws Exception {
    List<String> original = DocumentTranslation.paragraphs(source);
    DocumentEngine engine = new DocumentEngine(context, model);
    Map<String, String> cache =
        engine.translate(
            original, cancel, (done, total) -> progress.update("Перевод: " + done + " / " + total));
    List<String> blocks = new ArrayList<>(), parts = new ArrayList<>(), notices = new ArrayList<>();
    for (String paragraph : original) {
      if (cancel.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
      String block = cache.get(CompletenessRules.block(paragraph));
      List<String> translated = new ArrayList<>();
      List<String> units = CompletenessRules.units(paragraph);
      for (String unit : units) translated.add(cache.get(unit));
      blocks.add(block);
      parts.add(String.join("\n", translated));
      notices.add(
          units.stream().anyMatch(engine.getIncompleteUnits()::contains)
                  || engine.getIncompleteUnits().contains(CompletenessRules.block(paragraph))
              ? "Не все фрагменты переведены. Оригинал отмечен в результате."
              : CompletenessRules.warning(block, translated, units));
    }
    return new DocumentResult(source, engine.getModel(), blocks, parts, notices);
  }
}
