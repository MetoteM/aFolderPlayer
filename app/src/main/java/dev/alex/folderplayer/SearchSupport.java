package dev.alex.folderplayer;

import java.io.IOException;
import java.net.*;
import java.security.cert.CertificateException;
import java.util.*;
import javax.net.ssl.SSLException;

/** UI diagnostics and query cleanup only; never changes TLS trust or the audio metadata. */
public final class SearchSupport {
 public static String albumTitle(String value) {
  String clean=value.trim();String previous;
  do {
   previous=clean;
   clean=clean.replaceFirst("(?i)\\s*[\\[(]\\s*(?:LP|CD|FLAC|MP3|VINYL|(?:[0-9]{4}\\s+)?REMASTER(?:ED)?(?:\\s+[0-9]{4})?)\\s*[\\])]\\s*$","").trim();
  }while(!clean.equals(previous));
  return clean.isEmpty()?value.trim():clean;
 }
 public static String message(Throwable error,String service) {
  Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
  boolean tls=false,reset=false,timeout=false,dns=false;
  for(Throwable e=error;e!=null && seen.add(e);e=e.getCause()) {
   String m=String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
   tls|=e instanceof SSLException || e instanceof CertificateException || m.contains("trust anchor") || m.contains("certpath");
   reset|=e instanceof SocketException && (m.contains("reset") || m.contains("closed") || m.contains("broken pipe"));
   reset|=m.contains("err_connection_reset") || m.contains("err_connection_closed");
   timeout|=e instanceof SocketTimeoutException || m.contains("err_timed_out") || m.contains("err_connection_timed_out");dns|=e instanceof UnknownHostException || m.contains("err_name_not_resolved");
  }
  if(tls)return "Не удалось проверить защищённое соединение с "+service+". Сертификат не принят или TLS-соединение не установлено. Проверь дату телефона и доступность сервиса в текущей сети.";
  if(reset)return "Соединение с "+service+" оборвалось. Это не результат поиска. Попробуй позже или другую сеть.";
  if(timeout)return "Сервис не ответил вовремя. Попробуй позже или открой поиск в браузере.";
  if(dns)return "Не удалось найти адрес "+service+". Проверь интернет и настройки DNS/VPN.";
  return error.getMessage()!=null?error.getMessage():"Не удалось выполнить поиск";
 }
 public static URL browserUrl(String album,String artist,boolean wikipedia) throws IOException {
  album=albumTitle(album);artist=artist.trim();if(album.isEmpty() || album.length()>200 || artist.length()>200)throw new IOException("Проверь название альбома и исполнителя");
  if(wikipedia)return new URL("https://ru.wikipedia.org/w/index.php?search="+URLEncoder.encode(artist+" "+album,"UTF-8"));
  String query="releasegroup:"+literal(album)+(artist.isEmpty()?"":" AND artist:"+literal(artist));
  return new URL("https://musicbrainz.eu/search?type=release_group&method=advanced&query="+URLEncoder.encode(query,"UTF-8"));
 }
 private static String literal(String s) { StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray()){if("+-!(){}[]^\"~*?:\\/&|".indexOf(c)>=0)b.append('\\');b.append(c);}return b.append('"').toString(); }
}
