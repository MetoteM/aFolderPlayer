package dev.alex.folderplayer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.nio.ByteOrder

/** Bounded PCM pipeline; neither playback, microphone nor MP3 upload is used. */
internal object LocalAudio {
    fun decode(
        context: Context,
        uri: String,
        startMs: Long,
        durationMs: Long,
        checkActive: () -> Unit,
    ): ShortArray {
        require(uri.startsWith("content:") || uri.startsWith("file:")) { "Нужен локальный файл" }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor
                        .getTrackFormat(it)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: error("Звуковая дорожка не найдена")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val start = startMs.coerceAtLeast(0).coerceAtMost((durationMs - 30000).coerceAtLeast(0))
            val startUs = start * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val output = ShortArray(30 * 16000)
            var count = 0
            var resampler = PcmResampler(rate)
            var inputEnded = false
            var ended = false
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 45000
            while (!ended && count < output.size) {
                checkActive()
                check(SystemClock.elapsedRealtime() < deadline) {
                    "Чтение фрагмента заняло слишком долго"
                }
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer =
                            decoder.getInputBuffer(index) ?: error("Не удалось прочитать аудио")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                index,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding =
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        else AudioFormat.ENCODING_PCM_16BIT
                    require(
                        rate in 8000..192000 &&
                            channels in 1..8 &&
                            encoding in
                                setOf(
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    AudioFormat.ENCODING_PCM_FLOAT,
                                )
                    ) {
                        "Неподдерживаемый формат аудио"
                    }
                    resampler = PcmResampler(rate)
                } else if (index >= 0) {
                    try {
                        val buffer =
                            decoder.getOutputBuffer(index) ?: error("Нет звукового фрагмента")
                        buffer.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val frameBytes =
                            channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        var frame = 0
                        while (buffer.remaining() >= frameBytes && count < output.size) {
                            var mono = 0.0
                            repeat(channels) {
                                mono +=
                                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT)
                                        buffer.float.toDouble() * 32767
                                    else buffer.short.toDouble()
                            }
                            mono /= channels
                            if (info.presentationTimeUs + frame * 1_000_000L / rate >= startUs)
                                resampler.add(mono) { sample ->
                                    if (count < output.size) output[count++] = sample
                                }
                            frame++
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
            require(count >= 3 * 16000) { "Слишком короткий фрагмент для определения" }
            return output.copyOf(count)
        } finally {
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            extractor.release()
        }
    }
}

/** Streaming linear resampling survives arbitrary decoder buffer boundaries. */
internal class PcmResampler(private val rate: Int) {
    private var frame = 0L
    private var next = 0.0
    private var previous = 0.0

    init {
        require(rate in 8000..192000)
    }

    fun add(value: Double, emit: (Short) -> Unit) {
        val current = if (value.isFinite()) value.coerceIn(-32768.0, 32767.0) else 0.0
        while (next <= frame) {
            val fraction = if (frame == 0L) 1.0 else (next - (frame - 1)).coerceIn(0.0, 1.0)
            val sample = previous + (current - previous) * fraction
            emit(sample.toInt().coerceIn(-32768, 32767).toShort())
            next += rate / 16000.0
        }
        previous = current
        frame++
    }
}
