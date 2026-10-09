package dev.alex.folderplayer;

import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;

/** Explicit diagnostic probe. Records only public certificate facts, and delegates every trust check. */
public final class TlsDiagnostics {
 private volatile String certificates="Сертификат не получен: соединение могло оборваться до проверки TLS.";
 private volatile String addresses="Не получены (или диагностика не начиналась).";
 private volatile String endpoint="https://musicbrainz.eu/ws/2/release-group/";
 public HttpURLConnection open(URL url) throws Exception {
  if(!"https".equals(url.getProtocol()) || !"musicbrainz.eu".equals(url.getHost()))throw new IllegalArgumentException("Unexpected diagnostic host");
  endpoint=url.getProtocol()+"://"+url.getHost()+url.getPath();
  try { StringJoiner ips=new StringJoiner(", ");InetAddress[] resolved=InetAddress.getAllByName(url.getHost());for(int i=0;i<Math.min(resolved.length,6);i++)ips.add(resolved[i].getHostAddress());addresses=ips.toString(); } catch(UnknownHostException missing) { addresses="Системный DNS не вернул адрес."; }
  HttpsURLConnection c=(HttpsURLConnection)url.openConnection();
  TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init((KeyStore)null);
  X509TrustManager delegate=null;for(TrustManager manager:factory.getTrustManagers())if(manager instanceof X509TrustManager){delegate=(X509TrustManager)manager;break;}
  if(delegate==null)throw new GeneralSecurityException("No system trust manager");
  SSLContext context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{observing(delegate)},null);c.setSSLSocketFactory(context.getSocketFactory());
  // Keep HttpsURLConnection's existing hostname verifier. No user CA, pins, or trust-all fallback.
  return c;
 }
 X509TrustManager observing(X509TrustManager delegate) {
  return new X509TrustManager() {
   public X509Certificate[] getAcceptedIssuers(){return delegate.getAcceptedIssuers();}
   public void checkClientTrusted(X509Certificate[] chain,String authType)throws CertificateException{delegate.checkClientTrusted(chain,authType);}
   public void checkServerTrusted(X509Certificate[] chain,String authType)throws CertificateException{
    record(chain);delegate.checkServerTrusted(chain,authType);
   }
  };
 }
 private void record(X509Certificate[] chain) {
  StringBuilder b=new StringBuilder();int n=chain==null?0:Math.min(chain.length,6);
  for(int i=0;i<n;i++)try {
   X509Certificate c=chain[i];b.append("\nСертификат ").append(i+1).append("\nSubject: ").append(c.getSubjectX500Principal().getName())
    .append("\nIssuer: ").append(c.getIssuerX500Principal().getName()).append("\nДействует: ").append(c.getNotBefore()).append(" — ").append(c.getNotAfter())
    .append("\nSHA-256: ").append(hex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()))).append('\n');
  }catch(Exception ignored){}
  if(b.length()>0)certificates=b.toString();
 }
 private static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte x:bytes)b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString();}
 public String report(Throwable failure,int count) {
  StringBuilder b=new StringBuilder("Folder Player 0.1.10 · проверка MusicBrainz\nEndpoint: ").append(endpoint)
   .append("\nСистемный DNS (не обязательно фактический IP соединения): ").append(addresses).append("\nВремя телефона: ").append(new Date()).append("\n").append(failure==null?"API ответил, результатов: "+count:"API не ответил успешно.").append('\n');
  Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());int depth=0;
  for(Throwable e=failure;e!=null && seen.add(e) && depth++<6;e=e.getCause())b.append(e.getClass().getSimpleName()).append(": ").append(String.valueOf(e.getMessage()).replaceAll("[\\r\\n]"," ")).append('\n');
  b.append(certificates).append("\nПроверка сертификатов и имени сервера не отключалась. Данные никуда автоматически не отправляются.");
  return b.toString();
 }
}
