package dev.alex.folderplayer

/** Audited package identities; downloaded archives cannot supply executable settings. */
internal object ModelPackages {
 const val GERMAN="opus-de-ru-v1"
 const val CHINESE="opus-zh-ru-v1"
 data class Stage(val directory:String,val padding:Int)
 data class Package(val id:String,val files:Map<String,String>,val stages:List<Stage>)
 private val weights=mapOf("encoder.onnx" to "97f28d8295d231b7da721ded06fa8c034787df2122cd8f85b904abdc4d15bd53","decoder.onnx" to "7e4e602c31130176b086cd4bf31d6d08d44a6a367617e910870e0ea3e5408574")
 private val english=weights+mapOf("tokenizer.json" to "ab59614552269971b70f0bd5e966fe5adacf472cf9e08baf6841d41def76da35")
 val packages=listOf(
  Package(OfflineTranslator.MODEL_ID,weights+mapOf("tokenizer.json" to "981cd3d9fef6bb4dda8082afda716b65deb6717cb3ef35ac0b57002c09dec2bb"),listOf(Stage("",62517))),
  Package("opus-en-ru-v2",english,listOf(Stage("",62517))),
  Package(GERMAN,english.mapKeys { "en-ru/"+it.key }+mapOf("de-en/encoder.onnx" to "4cedda8f8c89b72a42b3c6cd1e7a27f2de24457093e3bf80cb3e46829641fcd8","de-en/decoder.onnx" to "e44c1c4b50e8f51e49d4d5e54a9af1550dc74763d4023644a38af62611e6efc9","de-en/tokenizer.json" to "968de667cf019300d4c726c62831942b69f4303cd68345a098b3423202283d3c"),listOf(Stage("de-en",58100),Stage("en-ru",62517))),
  Package(CHINESE,english.mapKeys { "en-ru/"+it.key }+mapOf("zh-en/encoder.onnx" to "84d5e171b626bc8b6b220d022ac58696e9528c25deeacca62b5cbf4364547a99","zh-en/decoder.onnx" to "debcc3054b9ff9aaed972bd6e251a459abe1e2f1402da1efa63163dc2f46ef73","zh-en/tokenizer.json" to "2b2c8819e69b5885e14d8d47b5cef8147ef4c2abfbc0b6188558afed6ff73463"),listOf(Stage("zh-en",65000),Stage("en-ru",62517)))
 )
 fun find(id:String)=packages.firstOrNull { it.id==id }
}
