package dev.alex.afptorrent

import java.net.URLDecoder

/** Accept copied/shared text, while preserving escaping inside tracker URLs. */
internal object MagnetLink {
    fun normalize(text:String):String {
        require(text.length<=32768) { "Ссылка слишком длинная" }
        var input=text.replace(Regex("[\\u200B-\\u200F\\u2060\\uFEFF]"),"").trim()
        if(!input.contains(Regex("(?i)magnet:")) && input.contains(Regex("(?i)magnet%3a")))
            input=runCatching { URLDecoder.decode(input,"UTF-8") }.getOrElse { error("Повреждённое кодирование magnet-ссылки") }
        input=input.replace(Regex("(?i)&(?:amp;|#0*38;|#x0*26;)"),"&")
            .replace(Regex("[\\r\\n]+[ \\t]*(?=&(?:xt|tr|dn|xl|ws|as|xs|x\\.pe)=)"),"")
        val match=requireNotNull(Regex("(?i)magnet:(?://)?\\?[^\\s<>\"`]+").find(input)) { "В тексте нет magnet-ссылки. Вставь ссылку, начинающуюся с magnet:?" }
        val raw=match.value.trimEnd(')',']','}',',',';','.', '\u00bb')
        val query=raw.substringAfter('?')
        val hashes=query.split('&').filter { it.substringBefore('=').equals("xt",true) }.map {
            runCatching { URLDecoder.decode(it.substringAfter('=',""),"UTF-8") }.getOrDefault("")
        }
        require(hashes.any { Regex("(?i)urn:btih:(?:[0-9a-f]{40}|[a-z2-7]{32})").matches(it) || Regex("(?i)urn:btmh:1220[0-9a-f]{64}").matches(it) }) {
            "В magnet-ссылке нет корректного хеша раздачи. Возможно, ссылка скопирована не полностью"
        }
        require(query.length<=16000) { "Ссылка слишком длинная" }
        // Native parser expects the canonical scheme and parameter names.
        return "magnet:?"+query.split('&').joinToString("&") { part ->
            val key=part.substringBefore('=');key.lowercase(java.util.Locale.ROOT)+part.removePrefix(key)
        }
    }
}
