package dev.alex.folderplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SoftwareEqProcessor : BaseAudioProcessor() {
 @Volatile private var desired=DspEqualizer.Settings(false,0.0,DoubleArray(5))
 private var applied: DspEqualizer.Settings?=null
 private var current=DspEqualizer()
 private var previous: DspEqualizer?=null
 private var fadeFrame=0
 private var fadeLength=1
 fun setSettings(settings: DspEqualizer.Settings) { desired=settings }
 override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
  if(inputAudioFormat.encoding!=C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
  return inputAudioFormat
 }
 override fun onFlush() {
  if(inputAudioFormat.sampleRate<=0 || inputAudioFormat.channelCount<=0) { applied=null; previous=null; return }
  applied=desired; previous=null; fadeFrame=0
  current=DspEqualizer().apply { configure(inputAudioFormat.sampleRate,inputAudioFormat.channelCount,desired) }
  fadeLength=(inputAudioFormat.sampleRate/50).coerceAtLeast(1) // 20 ms crossfade for control changes
 }
 override fun queueInput(inputBuffer: ByteBuffer) {
  // Media3 drains the pipeline with EMPTY_BUFFER before the first PCM frame.
  // Do not allocate/copy an empty output: it may be the very same buffer.
  if(!inputBuffer.hasRemaining()) return
  val settings=desired
  if(settings!==applied) {
   previous=current
   current=DspEqualizer().apply { configure(inputAudioFormat.sampleRate,inputAudioFormat.channelCount,settings) }
   applied=settings; fadeFrame=0
  }
  val output=replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
  if(current.isBypass && previous==null) { output.put(inputBuffer); output.flip(); return }
  inputBuffer.order(ByteOrder.nativeOrder())
  val channels=inputAudioFormat.channelCount
  while(inputBuffer.remaining()>=channels*2) {
   val alpha=(fadeFrame.toDouble()/fadeLength).coerceAtMost(1.0)
   for(channel in 0 until channels) {
    val sample=inputBuffer.short.toDouble()/32768.0
    val next=current.process(sample,channel)
    val old=previous
    val value=if(old==null) next else old.process(sample,channel)*(1-alpha)+next*alpha
    output.putShort((value*32768.0).roundToInt().coerceIn(-32768,32767).toShort())
   }
   if(previous!=null && ++fadeFrame>=fadeLength) previous=null
  }
  output.flip()
 }
}
