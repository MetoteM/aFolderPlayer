package dev.alex.folderplayer;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class ChineseDocumentTest {
 @Test public void chineseSentencesNeedNoSpaces(){assertEquals(Arrays.asList("妈妈给了我一封信。","我会永远珍藏它！","你呢？"),CompletenessRules.units("妈妈给了我一封信。我会永远珍藏它！你呢？"));}
 @Test public void repeatedChorusAndParagraphsRemain(){assertEquals(Arrays.asList("我爱你。","我爱你。","","不要放开我的手。"),CompletenessRules.units("我爱你。\n我爱你。\n\n不要放开我的手。"));}
 @Test public void punctuationInsideQuotesStaysWithSentence(){assertEquals(Arrays.asList("他说：“我爱你。”","她微笑了。"),CompletenessRules.units("他说：“我爱你。”她微笑了。"));}
}
