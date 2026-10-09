package dev.alex.folderplayer;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;

/** Signed data only. Executable code is installed by Android as a separately signed APK. */
public final class ExtensionCatalog {
 public static final class Entry {
  public final String id,kind,title,file,hash,model;public final long size;
  Entry(JSONObject item) throws Exception {
   id=item.getString("id");kind=item.getString("kind");title=item.getString("title");file=item.getString("file");hash=item.getString("sha256");size=item.getLong("size");model=item.optString("model","");
   if(!Set.of("engine","model").contains(kind) || !file.matches("[A-Za-z0-9_.-]{1,100}") || !hash.matches("[a-f0-9]{64}") || size<=0 || size>150_000_000 || title.length()>150)throw new IOException("Некорректный каталог расширений");
   if(kind.equals("engine") && !id.equals("engine") || kind.equals("model") && !model.matches("[a-z0-9][a-z0-9_-]{1,63}"))throw new IOException("Пакет пока не поддерживается этой версией");
  }
 }
 public final int version;public final List<Entry> entries;
 private ExtensionCatalog(JSONObject payload) throws Exception {
  if(payload.getInt("schema")!=1)throw new IOException("Нужна новая версия плеера");version=payload.getInt("version");if(version<1)throw new IOException("Некорректная версия каталога");
  JSONArray items=payload.getJSONArray("entries");if(items.length()>20)throw new IOException("Каталог слишком большой");List<Entry> values=new ArrayList<>();Set<String> ids=new HashSet<>();
  for(int i=0;i<items.length();i++) { Entry e=new Entry(items.getJSONObject(i));if(!ids.add(e.id))throw new IOException("Повторяющиеся пакеты");values.add(e); }entries=Collections.unmodifiableList(values);
 }
 public static ExtensionCatalog verify(byte[] envelope,byte[] certificate,int minimumVersion)throws Exception {
  if(envelope.length>200_000)throw new IOException("Каталог слишком большой");JSONObject object=new JSONObject(new String(envelope,java.nio.charset.StandardCharsets.UTF_8));
  byte[] payload=Base64.getDecoder().decode(object.getString("payload")),signature=Base64.getDecoder().decode(object.getString("signature"));
  java.security.cert.Certificate cert=CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate));Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(cert);verifier.update(payload);
  if(!verifier.verify(signature))throw new IOException("Подпись каталога не подтверждена");ExtensionCatalog catalog=new ExtensionCatalog(new JSONObject(new String(payload,java.nio.charset.StandardCharsets.UTF_8)));
  if(catalog.version<minimumVersion)throw new IOException("Сервер предлагает устаревший каталог");return catalog;
 }
 public static URI base(String address)throws Exception {
  URI uri=new URI(address.trim());if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)throw new IOException("Нужен HTTPS-адрес каталога без параметров");
  return new URI(uri.toString().endsWith("/")?uri.toString():uri+"/");
 }
 public static byte[] read(InputStream in,int limit)throws IOException {
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
  while((n=in.read(b))!=-1) { if(out.size()+n>limit)throw new IOException("Файл больше допустимого размера");out.write(b,0,n); }return out.toByteArray();
 }
 public static HttpURLConnection connect(URI uri)throws IOException {
  return ExtensionHttp.connect(uri);
 }
}
