package dev.alex.folderplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseDocumentTest {
    @Test
    fun chineseSentencesNeedNoSpaces() {
        assertEquals(
            listOf("妈妈给了我一封信。", "我会永远珍藏它！", "你呢？"),
            CompletenessRules.units("妈妈给了我一封信。我会永远珍藏它！你呢？"),
        )
    }

    @Test
    fun repeatedChorusAndParagraphsRemain() {
        assertEquals(
            listOf("我爱你。", "我爱你。", "", "不要放开我的手。"),
            CompletenessRules.units("我爱你。\n我爱你。\n\n不要放开我的手。"),
        )
    }

    @Test
    fun punctuationInsideQuotesStaysWithSentence() {
        assertEquals(
            listOf("他说：“我爱你。”", "她微笑了。"),
            CompletenessRules.units("他说：“我爱你。”她微笑了。"),
        )
    }
}
