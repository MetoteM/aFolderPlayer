package dev.alex.folderplayer;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.*;import java.util.*;import java.util.zip.CRC32;
public class MusicFingerprintTest {
 @Test public void silenceHasValidUnsignedCrcAndNoInventedPeaks(){
  byte[] data=Base64.getDecoder().decode(MusicFingerprint.encode(new short[16000*12]).split(",",2)[1]);
  ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
  assertEquals(0xCAFE2580,b.getInt(0));assertEquals(56,data.length);assertEquals(data.length-48,b.getInt(8));
  CRC32 crc=new CRC32();crc.update(data,8,data.length-8);assertEquals(crc.getValue(),Integer.toUnsignedLong(b.getInt(4)));
  assertEquals(12*16000+3840,b.getInt(40));
 }
 @Test(expected=IllegalArgumentException.class) public void rejectsUnboundedRecording(){MusicFingerprint.encode(new short[16000*31]);}
 @Test public void interruptedFingerprintStopsBeforeProcessing(){
  Thread.currentThread().interrupt();try{MusicFingerprint.encode(new short[16000*12]);fail();}catch(java.util.concurrent.CancellationException expected){}finally{Thread.interrupted();}
 }
}
