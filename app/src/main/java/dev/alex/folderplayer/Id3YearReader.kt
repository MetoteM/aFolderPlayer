package dev.alex.folderplayer

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.io.InputStream

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object Id3YearReader {
 private const val MAX_TAG_BYTES=4*1024*1024
 fun fromMetadata(metadata:Metadata?,nativeYear:Int?):Int? {
  val years=YearTags()
  if(metadata!=null) for(i in 0 until metadata.length()) {
   val entry=metadata[i]
   if(entry is TextInformationFrame) {
    val key=if(entry.id=="TXXX" || entry.id=="TXX") entry.description else entry.id
    entry.values.forEach { years.add(key,it) }
   }
  }
  return years.resolve(nativeYear)
 }
 fun read(input:InputStream,nativeYear:Int?):Int? {
  val header=ByteArray(10);if(!readFully(input,header,0,10)) return nativeYear
  if(header[0]!=73.toByte() || header[1]!=68.toByte() || header[2]!=51.toByte()) return nativeYear
  if((6..9).any { header[it].toInt() and 128!=0 }) return nativeYear
  var size=0;for(i in 6..9) size=(size shl 7) or (header[i].toInt() and 127)
  if(size>MAX_TAG_BYTES) return nativeYear
  val tag=ByteArray(size+10);header.copyInto(tag)
  if(!readFully(input,tag,10,size)) return nativeYear
  return fromMetadata(Id3Decoder().decode(tag,tag.size),nativeYear)
 }
 private fun readFully(input:InputStream,b:ByteArray,start:Int,length:Int):Boolean {
  var p=start;val end=start+length
  while(p<end) { val n=input.read(b,p,end-p);if(n<0)return false;if(n==0){val c=input.read();if(c<0)return false;b[p++]=c.toByte()}else p+=n }
  return true
 }
}
