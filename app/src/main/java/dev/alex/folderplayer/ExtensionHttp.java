package dev.alex.folderplayer;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;

/** Only GitHub asset redirects are accepted; ordinary hosts retain the no-redirect policy. */
public final class ExtensionHttp {
 private ExtensionHttp() {}
 private static final Set<String> GITHUB_HOSTS = Set.of("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com");
 static void validate(URI uri) throws IOException {
  if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443))
   throw new IOException("Нужен безопасный HTTPS-адрес загрузки");
 }
 static URI redirect(URI original, URI current, String location) throws IOException {
  if (location == null || location.isBlank()) throw new IOException("Сервер не указал адрес загрузки");
  URI target;
  try { target = current.resolve(location); } catch (IllegalArgumentException e) { throw new IOException("Некорректный адрес загрузки", e); }
  validate(target);
  String host = original.getHost();
  if (host == null || !host.equalsIgnoreCase("github.com") || !original.getPath().matches("/[^/]+/[^/]+/releases/download/[^/]+/[^/]+") || !GITHUB_HOSTS.contains(target.getHost().toLowerCase(java.util.Locale.ROOT)))
   throw new IOException("Перенаправление загрузки не разрешено");
  return target;
 }
 public static HttpURLConnection connect(URI original) throws IOException {
  validate(original);
  URI uri = original;
  Set<URI> visited = new HashSet<>();
  for (int hop = 0; hop <= 5; hop++) {
   if (!visited.add(uri)) throw new IOException("Сервер повторяет адрес загрузки");
   HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
   c.setInstanceFollowRedirects(false);
   c.setConnectTimeout(10000);
   c.setReadTimeout(30000);
   c.setRequestProperty("User-Agent", "aFolderPlayer");
   try {
    int status = c.getResponseCode();
    if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) return c;
    if (hop == 5) throw new IOException("Слишком много перенаправлений загрузки");
    uri = redirect(original, uri, c.getHeaderField("Location"));
   } catch (IOException | RuntimeException e) { c.disconnect(); throw e; }
   c.disconnect();
  }
  throw new IOException("Не удалось открыть загрузку");
 }
}
