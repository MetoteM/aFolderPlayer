package dev.alex.folderplayer;

import java.util.*;
import java.util.regex.*;

/** Simple LRC: multiple timestamps, fractions, global offset, stable simultaneous cues. */
public final class LyricsDocument {
 public static final int MAX_BYTES=512*1024;
 private static final Pattern TIME=Pattern.compile("\\[(\\d{1,6}):([0-5]\\d)(?:[.:](\\d{1,3}))?\\]");
 private static final Pattern OFFSET=Pattern.compile("(?im)^\\s*\\[offset:([+-]?\\d{1,9})\\]\\s*$");
 public static final class Cue {
  public final long timeMs; public final String text;
  Cue(long time,String value) { timeMs=time; text=value; }
 }
 public final List<Cue> cues;
 public final String plain;
 public LyricsDocument(String raw) {
  String value=raw.startsWith("\uFEFF")?raw.substring(1):raw;
  if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES) throw new IllegalArgumentException("Текст больше 512 КиБ");
  String[] lines=value.replace("\r\n","\n").replace('\r','\n').split("\n",-1);
  if(lines.length>4000) throw new IllegalArgumentException("Слишком много строк (максимум 4000)");
  long offset=0; Matcher offsets=OFFSET.matcher(value);while(offsets.find()) offset=Long.parseLong(offsets.group(1));
  List<Cue> result=new ArrayList<>();long expanded=0;
  for(String line:lines) {
   Matcher m=TIME.matcher(line);List<Long> times=new ArrayList<>();int end=0;
   while(m.find() && line.substring(end,m.start()).trim().isEmpty()) {
    String fraction=m.group(3);long ms=fraction==null?0:Long.parseLong((fraction+"000").substring(0,3));
    times.add(Long.parseLong(m.group(1))*60000+Long.parseLong(m.group(2))*1000+ms);end=m.end();
   }
   if(times.size()+result.size()>8000) throw new IllegalArgumentException("Слишком много меток времени");
   String body=line.substring(end).trim();expanded+=(long)times.size()*(body.length()+1);
   if(expanded>MAX_BYTES) throw new IllegalArgumentException("Развёрнутый LRC слишком большой");
   for(long time:times) result.add(new Cue(Math.max(0,time-offset),body));
  }
  result.sort(Comparator.comparingLong(c->c.timeMs));cues=Collections.unmodifiableList(result);
  plain=result.isEmpty()?value:String.join("\n",result.stream().map(c->c.text).toArray(String[]::new));
 }
 public int activeIndex(long position) {
  int lo=0,hi=cues.size();while(lo<hi) { int mid=(lo+hi)>>>1;if(cues.get(mid).timeMs<=position)lo=mid+1;else hi=mid; }return lo-1;
 }
 public int groupStart(int index) { while(index>0 && cues.get(index-1).timeMs==cues.get(index).timeMs)index--;return index; }
 public static String timestamp(long timeMs) {
  long t=Math.max(0,timeMs);return String.format(Locale.ROOT,"[%02d:%02d.%03d]",t/60000,(t/1000)%60,t%1000);
 }
 public static String key(String uri) {
  try {byte[] b=java.security.MessageDigest.getInstance("SHA-256").digest(uri.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
  catch(java.security.NoSuchAlgorithmException e) {throw new AssertionError(e);}
 }
}
