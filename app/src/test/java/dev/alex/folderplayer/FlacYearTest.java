package dev.alex.folderplayer;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class FlacYearTest {
 static byte[] comments(String... fields) throws IOException {
  ByteArrayOutputStream b=new ByteArrayOutputStream();number(b,0);number(b,fields.length);
  for(String field:fields) { byte[] bytes=field.getBytes(StandardCharsets.UTF_8);number(b,bytes.length);b.write(bytes); }
  return b.toByteArray();
 }
 static void number(OutputStream b,long n)throws IOException { for(int i=0;i<4;i++) b.write((int)(n>>(8*i))&255); }
 static byte[] flac(byte[] comments)throws IOException {
  ByteArrayOutputStream b=new ByteArrayOutputStream();b.write("fLaC".getBytes(StandardCharsets.US_ASCII));b.write(0x84);
  b.write(comments.length>>16);b.write(comments.length>>8);b.write(comments.length);b.write(comments);return b.toByteArray();
 }
 @Test public void syntheticCommentsRead1988AndStopBeforeAudio()throws Exception {
  byte[] fixture;try(InputStream in=getClass().getResourceAsStream("/synthetic-year-comments.flac")){ assertNotNull(in);fixture=in.readAllBytes(); }
  InputStream guarded=new ByteArrayInputStream(fixture) {
   @Override public synchronized int read(){if(pos>=count)throw new AssertionError("Audio read attempted");return super.read();}
   @Override public synchronized int read(byte[] b,int o,int n){if(pos>=count)throw new AssertionError("Audio read attempted");return super.read(b,o,n);}
  };
  assertEquals(Integer.valueOf(1988),FlacYearReader.readYear(guarded));assertEquals(0,guarded.available());
 }
 @Test public void dateCaseIsoAndYearFallback()throws Exception {
  assertEquals(Integer.valueOf(1988),FlacYearReader.readYear(new ByteArrayInputStream(flac(comments("date=1988-09-07")))));
  assertEquals(Integer.valueOf(1991),FlacYearReader.readYear(new ByteArrayInputStream(flac(comments("DATE=unknown","YEAR=1991")))));
  assertEquals(Integer.valueOf(1988),FlacYearReader.readYear(new ByteArrayInputStream(flac(comments("YEAR=2020","DATE=1988")))));
  assertNull(FlacYearReader.parseYear("19880"));assertNull(FlacYearReader.parseYear("0000"));assertNull(FlacYearReader.parseYear("unknown"));
 }
 @Test public void skipsLargeUnrelatedFieldsAndShortReads()throws Exception {
  byte[] data=flac(comments("WAVEFORM="+"x".repeat(200000),"DATE=1984"));
  InputStream input=new ByteArrayInputStream(data) {
   @Override public synchronized long skip(long n){return 0;}
   @Override public synchronized int read(byte[] b,int o,int n){return super.read(b,o,Math.min(n,3));}
  };
  assertEquals(Integer.valueOf(1984),FlacYearReader.readYear(input));
 }
 @Test public void missingMalformedAndOtherFormatsStayUnknown()throws Exception {
  assertNull(FlacYearReader.readYear(new ByteArrayInputStream(flac(comments("TITLE=Song")))));
  assertNull(FlacYearReader.readYear(new ByteArrayInputStream(new byte[]{1,2,3,4})));
  byte[] valid=flac(comments("DATE=1983"));assertNull(FlacYearReader.readYear(new ByteArrayInputStream(java.util.Arrays.copyOf(valid,valid.length-1))));
  ByteArrayOutputStream body=new ByteArrayOutputStream();number(body,0xffffffffL);assertNull(FlacYearReader.readYear(new ByteArrayInputStream(flac(body.toByteArray()))));
  body.reset();number(body,0);number(body,100001);assertNull(FlacYearReader.readYear(new ByteArrayInputStream(flac(body.toByteArray()))));
 }
}
