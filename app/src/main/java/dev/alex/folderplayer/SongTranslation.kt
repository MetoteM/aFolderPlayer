package dev.alex.folderplayer

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/** Conservative local language hints. Ambiguous/unsupported text needs a per-song choice. */
internal object SongLanguage {
    const val RUSSIAN = "ru"
    private val words =
        linkedMapOf(
                "opus-en-ru-v2" to
                    "i me it the and you your yours my mine we they this that these those is are was were have has had not don't can't will would with without love heart when where what how why never always tonight inside",
                "opus-de-ru-v1" to
                    "ich du nicht und mein meine dein deine mich dich wir der die das ist ein eine mit auf für hast bist habe wenn liebe liebt liebst sonne mutter herz augen träume mädchen schwarz feuer himmel alle zwei drei vier fünf",
                "opus-fr-ru-v1" to
                    "le la du est je suis avec nous vous pas dans une cette pour mon mes ton tes les des amour toujours jamais veux sous sans quand aussi être aime cœur coeur bonjour nuit moi toi ne elle tout rien",
                "opus-es-ru-v1" to
                    "yo tú usted ustedes nosotros vosotros ellos ellas soy eres somos estoy estás están quiero quieres podemos puedo contigo siempre nunca porque cuando pero también ahora mañana aquí corazón gracias hola dónde mi mis tu tus el los las del que no sin amo amor vida nada todo una te",
            )
            .mapValues { it.value.split(' ').toSet() }

    fun detect(value: String): String? {
        val text = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val tokens = Regex("[\\p{L}]+(?:'[\\p{L}]+)?").findAll(text).map { it.value }.toSet()
        val letters = text.count { it.isLetter() }
        if (letters == 0) return null
        val chinese = text.count { it in '\u4e00'..'\u9fff' }
        val latin = text.count { it in 'a'..'z' }
        if (text.any { it in '\u3040'..'\u30ff' || it in '\uac00'..'\ud7af' }) return null
        if (chinese >= 3 && text.any { it in "我你的是不爱愛们們这這有了" })
            return if (latin.toDouble() / letters < .25) "opus-zh-ru-v1" else null
        val cyrillic = text.count { it in 'а'..'я' || it == 'ё' }
        val russian =
            setOf(
                "я",
                "ты",
                "мы",
                "вы",
                "он",
                "она",
                "они",
                "не",
                "и",
                "в",
                "на",
                "что",
                "это",
                "мне",
                "тебя",
                "меня",
                "мой",
                "твой",
                "как",
                "но",
                "любовь",
                "привет",
                "мир",
                "моя",
                "мать",
                "русский",
                "текст",
                "песни",
            )
        if (
            cyrillic.toDouble() / letters > .6 &&
                tokens.count { it in russian } >= 2 &&
                text.none { it in "іїєґ" }
        )
            return RUSSIAN
        if (cyrillic > 0 || chinese > 0) return null
        // Avoid interpreting clear Portuguese/Italian hints as Spanish by shared words.
        if (
            tokens.count {
                it in
                    setOf(
                        "não",
                        "você",
                        "voces",
                        "saudade",
                        "coração",
                        "sono",
                        "della",
                        "perché",
                        "grazie",
                        "buongiorno",
                    )
            } >= 2
        )
            return null
        val scores =
            words.mapValues { (_, dictionary) -> tokens.count { it in dictionary } }.toMutableMap()
        if (text.any { it in "äöüß" })
            scores["opus-de-ru-v1"] = scores.getValue("opus-de-ru-v1") + 2
        if (text.any { it in "ñ¿¡" }) scores["opus-es-ru-v1"] = scores.getValue("opus-es-ru-v1") + 2
        if (
            text.contains("je t'aime") ||
                "je" in tokens && "suis" in tokens ||
                tokens.any { it in setOf("cœur", "coeur", "bonjour") }
        )
            scores["opus-fr-ru-v1"] = scores.getValue("opus-fr-ru-v1") + 2
        if (
            tokens.any { it in setOf("sonne", "mutter", "himmel") } ||
                "du" in tokens && "hast" in tokens
        )
            scores["opus-de-ru-v1"] = scores.getValue("opus-de-ru-v1") + 2
        if ("te" in tokens && tokens.any { it in setOf("quiero", "amo") })
            scores["opus-es-ru-v1"] = scores.getValue("opus-es-ru-v1") + 2
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked[0]
        val second = ranked[1].value
        return best.key.takeIf {
            best.value >= 3 &&
                best.value - second >= 2 &&
                (second < 3 || second.toDouble() / best.value < .5)
        }
    }
}

internal class SongTranslationChoice(context: Context) {
    private val prefs = context.getSharedPreferences("song-translation", 0)

    private fun key(uri: String, source: String) = LyricsDocument.key(uri + "\n" + source)

    fun read(uri: String, source: String) =
        prefs.getString(key(uri, source), null)?.takeIf { it in TranslationClient.MODELS }

    fun save(uri: String, source: String, model: String) {
        require(model in TranslationClient.MODELS)
        check(prefs.edit().putString(key(uri, source), model).commit())
    }

    fun clear(uri: String, source: String) {
        check(prefs.edit().remove(key(uri, source)).commit())
    }
}

internal object SongTranslation {
    fun cachedModels(expected: String?): List<String> =
        if (expected == "opus-en-ru-v2") listOf(expected, TranslationClient.MODEL)
        else listOfNotNull(expected).filter { it in TranslationClient.MODELS }

    sealed class Decision {
        data class Ready(val model: String) : Decision()

        data class Missing(val model: String) : Decision()

        object Choose : Decision()

        object Russian : Decision()
    }

    fun decide(source: String, installed: Set<String>, override: String? = null): Decision {
        val expected = override ?: SongLanguage.detect(source) ?: return Decision.Choose
        if (expected == SongLanguage.RUSSIAN) return Decision.Russian
        val model =
            if (
                expected == "opus-en-ru-v2" &&
                    expected !in installed &&
                    TranslationClient.MODEL in installed
            )
                TranslationClient.MODEL
            else expected
        return if (model in installed) Decision.Ready(model) else Decision.Missing(expected)
    }
}
