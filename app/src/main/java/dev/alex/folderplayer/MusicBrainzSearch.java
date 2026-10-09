package dev.alex.folderplayer;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Explicit album search, bounded response, no retries, no audio uploads. */
public final class MusicBrainzSearch {
 public static final int MAX_RESPONSE=1024*1024;
 private static long nextRequestAt;
 private final LyricsSearch.Connections connections;
 private long retryUntil;
 public MusicBrainzSearch() { this(url -> (HttpURLConnection)url.openConnection()); }
 public MusicBrainzSearch(LyricsSearch.Connections connections) { this.connections=connections; }
 public synchronized long cooldown() { return retryUntil; }
 public synchronized void restoreCooldown(long value) { retryUntil=Math.max(retryUntil,value); }
 private static synchronized void reserve(long now) throws IOException {
  if(now<nextRequestAt)throw new IOException("Подожди секунду перед следующим поиском");nextRequestAt=now+1100;
 }
 private static String literal(String value) {
  StringBuilder s=new StringBuilder("\"");
  for(char c:value.toCharArray()) { if("+-!(){}[]^\"~*?:\\/&|".indexOf(c)>=0)s.append('\\');s.append(c); }
  return s.append('"').toString();
 }
 public static URL url(String album,String artist) throws IOException {
  album=SearchSupport.albumTitle(album);artist=artist.trim();if(album.isEmpty())throw new IOException("Введи название альбома");
  if(album.length()>200 || artist.length()>200)throw new IOException("Запрос слишком длинный");
  String query="releasegroup:"+literal(album)+(artist.isEmpty()?"":" AND artist:"+literal(artist));
  return new URL("https://musicbrainz.eu/ws/2/release-group/?fmt=json&limit=20&query="+URLEncoder.encode(query,"UTF-8"));
 }
 public static final class Result {
  public final String id,title,artist,date,type,comment;public final Integer year;
  Result(JSONObject o) throws JSONException {
   id=o.getString("id");if(!id.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw new JSONException("Invalid MBID");
   title=o.getString("title").trim();if(title.isEmpty())throw new JSONException("Empty title");
   String d=o.optString("first-release-date","");date=d.matches("[0-9]{4}(?:-[0-9]{2}(?:-[0-9]{2})?)?")?d:"";year=YearTags.parse(date);
   StringBuilder credit=new StringBuilder();JSONArray a=o.optJSONArray("artist-credit");
   if(a!=null)for(int i=0;i<a.length();i++) { JSONObject c=a.optJSONObject(i);if(c==null)continue;JSONObject artist=c.optJSONObject("artist");credit.append(c.optString("name",artist==null?"":artist.optString("name",""))).append(c.optString("joinphrase","")); }
   artist=credit.toString();String t=o.optString("primary-type","");JSONArray secondary=o.optJSONArray("secondary-types");
   if(secondary!=null)for(int i=0;i<secondary.length();i++) { String part=secondary.optString(i,"");if(!part.isEmpty())t+=(t.isEmpty()?"":" · ")+part; }
   type=t;comment=o.optString("disambiguation","");
  }
  public String sourceUrl() { return "https://musicbrainz.eu/release-group/"+id; }
 }
 public static List<Result> parse(String body) throws JSONException {
  if(body.getBytes(StandardCharsets.UTF_8).length>MAX_RESPONSE)throw new JSONException("Ответ слишком большой");
  JSONArray data=new JSONObject(body).getJSONArray("release-groups");List<Result> found=new ArrayList<>();Set<String> seen=new HashSet<>();
  for(int i=0;i<Math.min(data.length(),100) && found.size()<20;i++)try { Result result=new Result(data.getJSONObject(i));if(seen.add(result.id.toLowerCase(Locale.ROOT)))found.add(result); } catch(JSONException ignored) {}
  return found;
 }
 public void beforeRequest() throws IOException {
  long now=System.currentTimeMillis();
  synchronized(this) { if(now<retryUntil)throw new IOException("MusicBrainz: повтори поиск через "+((retryUntil-now+999)/1000)+" с"); }
  reserve(now);
 }
 public List<Result> search(String album,String artist,LyricsSearch.Cancel cancel) throws Exception {
  URL url=url(album,artist);cancel.check();beforeRequest();
  HttpURLConnection c=connections.open(url);cancel.attach(c);long started=System.nanoTime();
  try {
   c.setConnectTimeout(10000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);
   c.setRequestProperty("User-Agent","FolderPlayer/0.5.0 (Alex personal Android music player; source distributed with APK)");
   c.setRequestProperty("Accept","application/json");
   int status=c.getResponseCode();cancel.check();
   if(status==429 || status==503) {
    long wait=LyricsSearch.retryMillis(c.getHeaderField("Retry-After"),System.currentTimeMillis());
    synchronized(this) { long clock=System.currentTimeMillis();retryUntil=wait>Long.MAX_VALUE-clock?Long.MAX_VALUE:clock+wait; }
    throw new IOException("MusicBrainz занят. Повтори через "+(wait/1000)+" с");
   }
   if(status!=200)throw new IOException("MusicBrainz недоступен (HTTP "+status+"). Попробуй позже");
   try(InputStream stream="gzip".equalsIgnoreCase(c.getContentEncoding())?new GZIPInputStream(c.getInputStream()):c.getInputStream()) {
    ByteArrayOutputStream body=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
    while((n=stream.read(b))!=-1) { cancel.check();if(System.nanoTime()-started>45_000_000_000L)throw new SocketTimeoutException("Поиск занял слишком долго");if(body.size()+n>MAX_RESPONSE)throw new IOException("Ответ слишком большой. Уточни запрос");body.write(b,0,n); }
    cancel.check();try { return parse(new String(body.toByteArray(),StandardCharsets.UTF_8)); } catch(JSONException malformed) { throw new IOException("MusicBrainz вернул непонятный ответ. Попробуй позже",malformed); }
   }
  } finally { c.disconnect();cancel.clear(); }
 }
}
