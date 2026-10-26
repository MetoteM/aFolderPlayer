package dev.alex.folderplayer;

import java.util.*;

public final class DocumentTranslation {
  public static List<String> paragraphs(String source) {
    return Arrays.asList(source.replace("\r", "").split("\\n[ \\t]*\\n", -1));
  }

  public static String assemble(List<String> translated) {
    return String.join("\n\n", translated);
  }

  public static LinkedHashSet<String> requestUnits(List<String> paragraphs, boolean compareBlocks) {
    LinkedHashSet<String> units = new LinkedHashSet<>();
    for (String paragraph : paragraphs) {
      if (compareBlocks) units.add(CompletenessRules.block(paragraph));
      units.addAll(CompletenessRules.units(paragraph));
    }
    units.remove("");
    return units;
  }

  public static String assembleSentences(
      List<String> paragraphs, Map<String, String> translations) {
    List<String> result = new ArrayList<>();
    for (String paragraph : paragraphs) {
      List<String> parts = new ArrayList<>();
      for (String unit : CompletenessRules.units(paragraph)) {
        String value = translations.get(unit);
        if (value == null || (!unit.isEmpty() && value.trim().isEmpty()))
          throw new IllegalArgumentException("Движок вернул неполный результат");
        parts.add(value);
      }
      result.add(String.join("\n", parts));
    }
    return assemble(result);
  }
}
