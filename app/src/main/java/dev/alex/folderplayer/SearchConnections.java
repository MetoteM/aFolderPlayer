package dev.alex.folderplayer;

import android.content.Context;
import android.os.Build;
import android.net.http.HttpEngine;
import java.io.IOException;
import java.net.*;

/** Shared, lazy system Chromium transport. Does not change global TLS or playback networking. */
public final class SearchConnections implements LyricsSearch.Connections {
 private final Context context;
 private static LyricsSearch.Connections selected;
 public SearchConnections(Context context) { this.context=context.getApplicationContext(); }
 public HttpURLConnection open(URL url) throws IOException {
  if(!"https".equals(url.getProtocol()) || !("musicbrainz.eu".equals(url.getHost()) || "lrclib.net".equals(url.getHost())))throw new IOException("Unexpected search endpoint");
  synchronized(SearchConnections.class) {
  if(selected==null) {
   if(Build.VERSION.SDK_INT>=34) {
    try { selected=Modern.create(context); } catch(UnsupportedOperationException|IllegalStateException|LinkageError unavailable) { selected=u -> (HttpURLConnection)u.openConnection(); }
   } else selected=u -> (HttpURLConnection)u.openConnection();
  }
  }
  return selected.open(url);
 }
 @android.annotation.TargetApi(34)
 private static class Modern {
  static LyricsSearch.Connections create(Context context) {
   HttpEngine engine=new HttpEngine.Builder(context).setEnableHttp2(true).setEnableQuic(true)
    .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISABLED,0).build();
   return url -> (HttpURLConnection)engine.openConnection(url);
  }
 }
}
