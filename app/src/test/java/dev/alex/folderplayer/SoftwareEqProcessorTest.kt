package dev.alex.folderplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SoftwareEqProcessorTest {
 private fun processor(settings:DspEqualizer.Settings)=SoftwareEqProcessor().apply {
  setSettings(settings);configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));flush()
 }
 private fun input(offset:Int,frames:Int)=ShortArray(frames*2) { i ->
  (kotlin.math.sin((offset+i/2)*2*Math.PI*1000/48000)*8000).toInt().toShort()
 }
 private fun process(p:SoftwareEqProcessor,samples:ShortArray):ShortArray {
  val b=ByteBuffer.allocateDirect(samples.size*2).order(ByteOrder.nativeOrder());samples.forEach { b.putShort(it) };b.flip();p.queueInput(b)
  val out=p.output.order(ByteOrder.nativeOrder());return ShortArray(out.remaining()/2) { out.short }
 }
 @Test fun enabledNeutralAndDisabledAreBitExact() {
  val samples=input(0,4096)
  for(enabled in listOf(false,true)) assertArrayEquals(samples,process(processor(DspEqualizer.Settings(enabled,0.0,DoubleArray(10))),samples))
 }
 @Test fun resetRestoresOriginalSamplesAfterShortTransition() {
  val p=processor(DspEqualizer.Settings(true,-6.0,DoubleArray(10) { if(it==5)6.0 else 0.0 }))
  process(p,input(0,4096));p.setSettings(DspEqualizer.Settings(true,0.0,DoubleArray(10)))
  process(p,input(4096,960))
  val samples=input(5056,2048);assertArrayEquals(samples,process(p,samples))
  p.setSettings(DspEqualizer.Settings(false,0.0,DoubleArray(10)))
  assertArrayEquals(samples,process(p,samples))
 }
 @Test fun equalSettingsDoNotRestartFilterOrFade() {
  fun settings()=DspEqualizer.Settings(true,0.0,DoubleArray(10) { if(it==0)8.0 else 0.0 })
  val baseline=processor(settings());val repeated=processor(settings())
  for(block in 0 until 30) {
   repeated.setSettings(settings());val samples=input(block*64,64)
   assertArrayEquals(process(baseline,samples),process(repeated,samples))
  }
 }
}
