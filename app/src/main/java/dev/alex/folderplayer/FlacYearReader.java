package dev.alex.folderplayer;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads native FLAC Vorbis comments only; never decodes or scans audio frames. */
public final class FlacYearReader {
 private FlacYearReader() {}
 private static final long MAX_METADATA=64L*1024*1024;
 public static Integer readYear(InputStream input) throws IOException {
  try {
   byte[] marker=new byte[4];readFully(input,marker,4);
   if(marker[0]!='f'||marker[1]!='L'||marker[2]!='a'||marker[3]!='C') return null;
   long total=4;
   for(int block=0;block<256;block++) {
    byte[] header=new byte[4];readFully(input,header,4);
    int length=((header[1]&255)<<16)|((header[2]&255)<<8)|(header[3]&255);
    total+=4L+length;if(total>MAX_METADATA) return null;
    Block body=new Block(input,length);
    if((header[0]&127)==4) return comments(body);
    body.skip(length);
    if((header[0]&128)!=0) return null;
   }
   return null;
  } catch(EOFException malformed) { return null; }
 }
 private static Integer comments(Block block) throws IOException {
  block.skip(block.number());long count=block.number();
  if(count>100000 || count>block.remaining/4) return null;
  YearTags years=new YearTags();
  for(long i=0;i<count;i++) {
   long length=block.number();if(length>block.remaining) throw new EOFException();
   int prefix=(int)Math.min(length,128);
   byte[] text=new byte[prefix];block.read(text);block.skip(length-prefix);
   // A year/date needs only a short text field. Oversized comments (waveforms/pictures) are skipped.
   if(length>128) continue;
   String field=new String(text,StandardCharsets.UTF_8);int equal=field.indexOf('=');if(equal<0) continue;
   years.add(field.substring(0,equal),field.substring(equal+1));
  }
  return years.resolve(null);
 }
 public static Integer parseYear(String text) {
  return YearTags.parse(text);
 }
 private static void readFully(InputStream input,byte[] bytes,int count) throws IOException {
  int offset=0;while(offset<count) { int n=input.read(bytes,offset,count-offset);if(n<0) throw new EOFException();if(n==0) { int c=input.read();if(c<0)throw new EOFException();bytes[offset++]=(byte)c; } else offset+=n; }
 }
 private static final class Block {
  final InputStream input;long remaining;final byte[] scratch=new byte[8192];
  Block(InputStream input,int size) { this.input=input;remaining=size; }
  void read(byte[] bytes) throws IOException { if(bytes.length>remaining)throw new EOFException();readFully(input,bytes,bytes.length);remaining-=bytes.length; }
  long number() throws IOException { byte[] b=new byte[4];read(b);return (b[0]&255L)|((b[1]&255L)<<8)|((b[2]&255L)<<16)|((b[3]&255L)<<24); }
  void skip(long size) throws IOException {
   if(size<0 || size>remaining) throw new EOFException();
   long left=size;
   while(left>0) {
    long skipped=input.skip(left);
    if(skipped>0) left-=skipped;
    else { int n=(int)Math.min(left,scratch.length);readFully(input,scratch,n);left-=n; }
   }
   remaining-=size;
  }
 }
}
