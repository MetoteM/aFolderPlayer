package dev.alex.folderplayer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.FloatBuffer
import java.text.Normalizer
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import java.util.function.Function
import org.json.JSONArray
import org.json.JSONObject

/** Pure local CPU inference; this class has no networking or Android dependencies. */
class OfflineTranslator : AutoCloseable {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val trie = Node()
    private val vocabulary: Array<String>
    private val padding: Int
    private var unknownScore = -100.0

    private class Node {
        val children = HashMap<Char, Node>()
        var id = -1
        var score = 0.0
    }

    constructor(directory: File) : this(directory, 62517)

    constructor(directory: File, padding: Int) {
        this.padding = padding
        environment.setTelemetry(false)
        val words: JSONArray =
            JSONObject(read(File(directory, "tokenizer.json")))
                .getJSONObject("model")
                .getJSONArray("vocab")
        vocabulary = arrayOfNulls<String>(words.length()).also { fill ->
            if (padding < 2 || padding >= words.length()) throw IOException("Invalid vocabulary")
            var minimum = 0.0
            for (i in 0 until words.length()) {
                val item: JSONArray = words.getJSONArray(i)
                val token = item.getString(0)
                val score = item.getDouble(1)
                fill[i] = token
                if (score <= -1e8) continue
                minimum = minOf(minimum, score)
                if (i == 0 || i == 1 || i == padding) continue
                var node = trie
                for (j in token.indices) node = node.children.computeIfAbsent(token[j]) { Node() }
                node.id = i
                node.score = score
            }
            unknownScore = minimum - 10
        } as Array<String>
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            encoder = environment.createSession(File(directory, "encoder.onnx").absolutePath, options)
            try {
                decoder =
                    environment.createSession(File(directory, "decoder.onnx").absolutePath, options)
            } catch (e: Exception) {
                encoder.close()
                throw e
            }
        }
    }

    fun encode(text: String): LongArray {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
        val ids = ArrayList<Long>()
        for (word in normalized.split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            val value = "▁$word"
            val length = value.length
            val best = DoubleArray(length + 1)
            Arrays.fill(best, Double.NEGATIVE_INFINITY)
            best[0] = 0.0
            val previous = IntArray(length + 1)
            val tokens = IntArray(length + 1)
            for (i in 0 until length) {
                if (!best[i].isFinite()) continue
                var node = trie
                var single = false
                for (j in i until length) {
                    node = node.children[value[j]] ?: break
                    if (node.id >= 0) {
                        if (j == i) single = true
                        val score = best[i] + node.score
                        if (score > best[j + 1]) {
                            best[j + 1] = score
                            previous[j + 1] = i
                            tokens[j + 1] = node.id
                        }
                    }
                }
                if (!single) {
                    val end = i + Character.charCount(value.codePointAt(i))
                    val score = best[i] + unknownScore
                    if (score > best[end]) {
                        best[end] = score
                        previous[end] = i
                        tokens[end] = 1
                    }
                }
            }
            val reversed = ArrayList<Long>()
            var end = length
            while (end > 0) {
                reversed.add(tokens[end].toLong())
                end = previous[end]
            }
            reversed.reverse()
            ids.addAll(reversed)
        }
        ids.add(0L)
        val result = LongArray(ids.size)
        for (i in result.indices) result[i] = ids[i]
        return result
    }

    @Throws(Exception::class)
    fun sentence(text: String, cancelled: BooleanSupplier): String {
        check(cancelled)
        val started = System.nanoTime()
        val input = encode(text)
        if (input.size > 100)
            throw IllegalArgumentException("Строка слишком длинная для перевода")
        val mask = arrayOf(LongArray(input.size))
        Arrays.fill(mask[0], 1L)
        OnnxTensor.createTensor(environment, arrayOf(input)).use { ids ->
            OnnxTensor.createTensor(environment, mask).use { attention ->
                encoder
                    .run(mapOf("input_ids" to ids, "attention_mask" to attention))
                    .use { encoded ->
                        val hidden = encoded.get("last_hidden_state").orElseThrow() as OnnxTensor
                        val output = ArrayList<Long>()
                        output.add(padding.toLong())
                        var completed = false
                        val maxSteps = minOf(160, input.size * 3 + 24)
                        for (step in 0 until maxSteps) {
                            check(cancelled)
                            if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(45))
                                throw IncompleteTranslation("Превышено время перевода фрагмента")
                            val target = arrayOf(LongArray(output.size))
                            for (i in output.indices) target[0][i] = output[i]
                            OnnxTensor.createTensor(environment, target).use { targetIds ->
                                decoder
                                    .run(
                                        mapOf(
                                            "input_ids" to targetIds,
                                            "encoder_hidden_states" to hidden,
                                            "encoder_attention_mask" to attention,
                                        )
                                    )
                                    .use { decoded ->
                                        val logits =
                                            decoded.get("logits").orElseThrow() as OnnxTensor
                                        val values: FloatBuffer = logits.floatBuffer
                                        val size = vocabulary.size
                                        val offset = (output.size - 1) * size
                                        var best = 0
                                        var score = Float.NEGATIVE_INFINITY
                                        for (i in 0 until size) {
                                            if (i != padding && i != 1) {
                                                val value = values.get(offset + i)
                                                if (value > score) {
                                                    score = value
                                                    best = i
                                                }
                                            }
                                        }
                                        if (best == 0) {
                                            completed = true
                                            return@use
                                        }
                                        output.add(best.toLong())
                                    }
                            }
                            if (completed) break
                        }
                        if (!completed)
                            throw IncompleteTranslation("Не удалось завершить перевод фрагмента")
                        val result = StringBuilder()
                        for (i in 1 until output.size) result.append(vocabulary[output[i].toInt()])
                        return result.toString().replace('▁', ' ').trim()
                    }
            }
        }
    }

    class IncompleteTranslation(message: String?) : IOException(message)

    fun interface SentenceDecoder {
        @Throws(Exception::class)
        fun decode(text: String): String
    }

    /** Recover only unfinished generation; never retry cancellation or resource failures. */
    class RetryBudget {
        var attempts = 0
        var started = 0L
        val extraTime: LongArray

        constructor() {
            this.extraTime = LongArray(1)
        }

        constructor(extraTime: LongArray) {
            this.extraTime = extraTime
        }

        fun available(): Boolean =
            extraTime[0] < TimeUnit.SECONDS.toNanos(120) &&
                attempts < 14 &&
                (started == 0L ||
                    System.nanoTime() - started < TimeUnit.SECONDS.toNanos(90))

        fun use() {
            if (started == 0L) started = System.nanoTime()
            attempts++
        }
    }

    companion object {
        const val MODEL_ID: String = "opus-en-ru-v1"

        @Throws(IOException::class)
        internal fun read(f: File): String {
            FileInputStream(f).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    out.write(buffer, 0, n)
                }
                return String(out.toByteArray(), Charsets.UTF_8)
            }
        }

        @Throws(InterruptedException::class)
        private fun check(cancelled: BooleanSupplier) {
            if (Thread.currentThread().isInterrupted() || cancelled.asBoolean)
                throw InterruptedException("Translation cancelled")
        }

        @JvmStatic
        @Throws(Exception::class)
        fun adaptiveRetry(
            text: String,
            cancelled: BooleanSupplier,
            depth: Int,
            decode: SentenceDecoder,
        ): String = adaptiveRetry(text, cancelled, depth, decode, RetryBudget())

        @Throws(Exception::class)
        private fun adaptiveRetry(
            text: String,
            cancelled: BooleanSupplier,
            depth: Int,
            decode: SentenceDecoder,
            budget: RetryBudget,
        ): String {
            if (Thread.currentThread().isInterrupted() || cancelled.asBoolean)
                throw InterruptedException("Translation cancelled")
            try {
                val before = System.nanoTime()
                try {
                    return decode.decode(text)
                } finally {
                    if (depth > 0) budget.extraTime[0] += System.nanoTime() - before
                }
            } catch (failure: IncompleteTranslation) {
                if (Thread.currentThread().isInterrupted() || cancelled.asBoolean)
                    throw InterruptedException("Translation cancelled")
                val pieces = splitRecovery(text)
                if (depth >= 3 || pieces.size < 2 || !budget.available()) throw failure
                val result = ArrayList<String>()
                for (piece in pieces) {
                    if (!budget.available()) throw failure
                    budget.use()
                    result.add(adaptiveRetry(piece, cancelled, depth + 1, decode, budget))
                }
                return result.joinToString(" ")
            }
        }

        @JvmStatic
        fun splitRecovery(text0: String): List<String> {
            val text = text0.trim()
            // Prefer sentence/clause boundaries near the middle; preserve all source characters.
            val middle = text.length / 2
            var best = -1
            var distance = Int.MAX_VALUE
            for (i in 1 until text.length - 1) {
                if ("。！？；，：.!?;,:".indexOf(text[i]) >= 0 && Math.abs(i + 1 - middle) < distance) {
                    best = i + 1
                    distance = Math.abs(best - middle)
                }
            }
            if (best < 0) {
                for (i in 1 until text.length - 1) {
                    if (Character.isWhitespace(text[i]) && Math.abs(i - middle) < distance) {
                        best = i
                        distance = Math.abs(i - middle)
                    }
                }
            }
            // CJK has no spaces; split safely between Unicode code points.
            if (best < 0 &&
                    text.codePoints().anyMatch { cp ->
                        Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
                    }
            ) {
                val count = text.codePointCount(0, text.length)
                if (count > 1) best = text.offsetByCodePoints(0, count / 2)
            }
            if (best < 1 || best >= text.length) return listOf(text)
            val left = text.substring(0, best).trim()
            val right = text.substring(best).trim()
            if (left.isEmpty() || right.isEmpty()) return listOf(text)
            return listOf(left, right)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun translateUnits(
            text: String,
            cancelled: BooleanSupplier,
            progress: Progress,
            allowPartial: Boolean,
            chunks: Function<String, List<String>>,
            decode: SentenceDecoder,
        ): TranslationResult {
            val lines = text.replace("\r", "").split("\n")
            if (lines.size > 500 || text.length > 20000)
                throw IllegalArgumentException("Перевод поддерживает до 20 000 символов и 500 строк")
            val result = StringBuilder()
            val repeated = HashMap<String, String>()
            val failed = ArrayList<Int>()
            val failedSources = HashSet<String>()
            val extraTime = LongArray(1)
            for (i in lines.indices) {
                check(cancelled)
                val line = lines[i].trim()
                if (i > 0) result.append('\n')
                if (line.isNotEmpty()) {
                    var translated = repeated[line]
                    if (line in failedSources) {
                        failed.add(i)
                        translated = line
                    }
                    if (translated == null) {
                        try {
                            val budget = RetryBudget(extraTime)
                            val value = StringBuilder()
                            for (chunk in chunks.apply(line)) {
                                if (value.isNotEmpty()) value.append(' ')
                                value.append(adaptiveRetry(chunk, cancelled, 0, decode, budget))
                            }
                            translated = value.toString()
                            repeated[line] = translated
                        } catch (failure: IncompleteTranslation) {
                            if (!allowPartial) throw failure
                            failed.add(i)
                            failedSources.add(line)
                            translated = line
                        }
                    }
                    result.append(translated)
                }
                progress.update(i + 1, lines.size)
            }
            check(cancelled)
            return TranslationResult(result.toString(), failed)
        }
    }

    fun interface Progress {
        fun update(done: Int, total: Int)
    }

    @Throws(Exception::class)
    fun translate(text: String, cancelled: BooleanSupplier, progress: Progress): String {
        return translateResult(text, cancelled, progress, false).text
    }

    class TranslationResult(val text: String, val failedLines: List<Int>)

    @Throws(Exception::class)
    fun translateResult(
        text: String,
        cancelled: BooleanSupplier,
        progress: Progress,
        allowPartial: Boolean,
    ): TranslationResult {
        return translateUnits(
            text,
            cancelled,
            progress,
            allowPartial,
            Function { line -> chunks(line) },
            SentenceDecoder { value -> sentence(value, cancelled) },
        )
    }

    fun chunks(line: String): List<String> {
        val result = ArrayList<String>()
        val pending = StringBuilder()
        for (word in line.split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            val next = if (pending.isEmpty()) word else "$pending $word"
            if (encode(next).size <= 70) {
                pending.setLength(0)
                pending.append(next)
                continue
            }
            if (pending.isNotEmpty()) {
                result.add(pending.toString())
                pending.setLength(0)
            }
            var offset = 0
            while (offset < word.length) {
                val cp = word.codePointAt(offset)
                val character = String(Character.toChars(cp))
                if (encode(pending.toString() + character).size > 70 && pending.isNotEmpty()) {
                    result.add(pending.toString())
                    pending.setLength(0)
                }
                pending.append(character)
                offset += Character.charCount(cp)
                if ("。！？；!?;".indexOf(cp.toChar()) >= 0) {
                    result.add(pending.toString())
                    pending.setLength(0)
                }
            }
        }
        if (pending.isNotEmpty()) result.add(pending.toString())
        return result
    }

    @Throws(OrtException::class)
    override fun close() {
        try {
            decoder.close()
        } finally {
            encoder.close()
        }
    }
}
