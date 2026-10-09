package dev.alex.folderplayer;

import java.util.Locale;

/** One policy shared by FLAC Vorbis and MP3 ID3: original release, release date, native fallback. */
public final class YearTags {
 private Integer year;
 private int rank=Integer.MAX_VALUE;
 public void add(String key,String value) {
  if(key==null)return;
  String k=key.toUpperCase(Locale.ROOT).replace("_","").replace("-","").replace(" ","");
  int priority;
  switch(k) {
   case "ORIGINALDATE":case "ORIGINALRELEASEDATE":case "TDOR":priority=0;break;
   case "ORIGINALYEAR":case "ORIGINALRELEASEYEAR":case "ORIGYEAR":case "TORY":case "TOR":priority=1;break;
   case "DATE":case "RELEASEDATE":case "TDRL":priority=10;break;
   case "TDRC":priority=20;break;
   case "YEAR":case "RELEASEYEAR":case "TYER":case "TYE":priority=30;break;
   default:return;
  }
  Integer parsed=parse(value);if(parsed!=null && priority<rank){year=parsed;rank=priority;}
 }
 public Integer resolve(Integer fallback) {return year!=null?year:fallback;}
 public static Integer parse(String text) {
  if(text==null)return null;String value=text.trim();if(value.length()<4)return null;
  int year=0;for(int i=0;i<4;i++){char c=value.charAt(i);if(c<'0'||c>'9')return null;year=year*10+c-'0';}
  if(value.length()>4 && Character.isDigit(value.charAt(4)))return null;
  return year>=1000 && year<=9999?year:null;
 }
}
