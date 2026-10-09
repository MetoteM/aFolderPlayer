package dev.alex.folderplayer;
import java.util.*;
import java.text.Normalizer;
/** Auto-save only an unambiguous metadata/duration match, never the first arbitrary hit. */
public final class LyricsMatch {
 private LyricsMatch() {}
 public static String key(String value) {
  return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");
 }
 public static LyricsSearch.Result automatic(List<LyricsSearch.Result> results,String title,String artist,String album,long duration) {
  if(key(title).isEmpty() || key(artist).isEmpty() || duration<=0)return null;
  List<LyricsSearch.Result> exact=new ArrayList<>();double seconds=duration/1000.0,tolerance=Math.min(5,Math.max(2,seconds*0.02));
  for(LyricsSearch.Result r:results)if(!r.text.trim().isEmpty() && key(r.title).equals(key(title)) && key(r.artist).equals(key(artist)) && Double.isFinite(r.duration) && r.duration>0 && Math.abs(r.duration-seconds)<=tolerance)exact.add(r);
  if(exact.isEmpty())return null;
  if(!key(album).isEmpty()) {
   String clean=album.replaceAll("(?i)\\s*\\((?:LP|CD|FLAC|MP3)\\)\\s*$","");
   List<LyricsSearch.Result> sameAlbum=new ArrayList<>();for(LyricsSearch.Result r:exact)if(key(r.album).equals(key(clean)))sameAlbum.add(r);
   if(!sameAlbum.isEmpty())exact=sameAlbum;
  }
  Set<String> texts=new HashSet<>();for(LyricsSearch.Result r:exact)texts.add(key(new LyricsDocument(r.text).plain));
  if(texts.size()!=1)return null;
  exact.sort(Comparator.comparing((LyricsSearch.Result r)->!r.timed).thenComparingDouble(r->Math.abs(r.duration-seconds)));
  return exact.get(0);
 }
}
