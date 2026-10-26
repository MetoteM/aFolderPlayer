package dev.alex.folderplayer;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.security.KeyStore;
import java.security.cert.*;
import javax.net.ssl.*;

public class SearchSupportTest {
 @Test public void normalizesOnlyReleaseAnnotationsAndKeepsMeaningfulTitles() {
  assertEquals("Load",SearchSupport.albumTitle("Load (LP)"));assertEquals("Load",SearchSupport.albumTitle("Load (1996 Remastered) [FLAC]"));
  assertEquals("Live (1988)",SearchSupport.albumTitle("Live (1988)"));assertEquals("Album (Live)",SearchSupport.albumTitle("Album (Live)"));assertEquals("(LP)",SearchSupport.albumTitle("(LP)"));
 }
 @Test public void classifiesWrappedTlsResetTimeoutAndDnsWithoutRawStackTrace() {
  String tls=SearchSupport.message(new java.io.IOException(new SSLHandshakeException("Trust anchor for certification path not found")),"MusicBrainz");
  assertTrue(tls.contains("Сертификат"));assertFalse(tls.contains("javax.net"));
  assertTrue(SearchSupport.message(new SocketException("Connection reset"),"MusicBrainz").contains("оборвалось"));
  assertTrue(SearchSupport.message(new SocketTimeoutException(),"MusicBrainz").contains("вовремя"));
  assertTrue(SearchSupport.message(new java.io.IOException("net::ERR_CONNECTION_RESET"),"MusicBrainz").contains("оборвалось"));
  assertTrue(SearchSupport.message(new java.io.IOException("net::ERR_TIMED_OUT"),"MusicBrainz").contains("вовремя"));
  assertTrue(SearchSupport.message(new UnknownHostException(),"MusicBrainz").contains("DNS"));
 }
 @Test public void browserLinksUseHttpsAndOnlyEnteredQuery() throws Exception {
  URL url=SearchSupport.browserUrl("Load (LP)","Metallica & other",false);assertEquals("https",url.getProtocol());assertEquals("musicbrainz.eu",url.getHost());
  String decoded=URLDecoder.decode(url.getQuery(),"UTF-8");assertTrue(decoded.contains("releasegroup:\"Load\""));assertTrue(decoded.contains("Metallica \\& other"));assertFalse(decoded.contains("LP"));
  assertEquals("ru.wikipedia.org",SearchSupport.browserUrl("Load","Metallica",true).getHost());
 }
 private X509Certificate cert()throws Exception {try(java.io.InputStream in=getClass().getResourceAsStream("/tls-test-cert.pem")){return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);}}
 @Test public void diagnosticRecordsCertificateAndPropagatesTrustRejection() throws Exception {
  TlsDiagnostics trace=new TlsDiagnostics();CertificateException rejection=new CertificateException("Rejected by platform");int[] calls={0};
  X509TrustManager delegate=new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw rejection;}public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{calls[0]++;throw rejection;}};
  X509TrustManager observer=trace.observing(delegate);
  try {observer.checkServerTrusted(new X509Certificate[]{cert()},"RSA");fail("Must reject");}catch(CertificateException e){assertSame(rejection,e);}
  assertEquals(1,calls[0]);String report=trace.report(rejection,0);assertTrue(report.contains("SHA-256:"));assertTrue(report.contains("Issuer:"));assertFalse(report.contains("BEGIN PRIVATE KEY"));
 }
 @Test public void diagnosticCannotMakeAnUntrustedCertificateTrusted() throws Exception {
  TrustManagerFactory f=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());f.init((KeyStore)null);
  X509TrustManager system=null;for(TrustManager manager:f.getTrustManagers())if(manager instanceof X509TrustManager)system=(X509TrustManager)manager;
  assertNotNull(system);X509TrustManager observer=new TlsDiagnostics().observing(system);
  assertThrows(CertificateException.class,()->observer.checkServerTrusted(new X509Certificate[]{cert()},"RSA"));
 }
}
