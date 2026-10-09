package dev.alex.folderplayer;
import java.net.URI;
import java.io.IOException;
public class ExtensionHttpCheck {
 public static void main(String[] args) throws Exception {
  URI source=URI.create("https://github.com/MetoteM/aFolderPlayer/releases/download/extensions-v1/catalog.json");
  String valid="https://release-assets.githubusercontent.com/github-production-release-asset/1/file?token=abc";
  if(!ExtensionHttp.redirect(source,source,valid).toString().equals(valid))throw new AssertionError();
  int checks=1;
  for(String bad:new String[]{"http://release-assets.githubusercontent.com/file","https://evil.example/file","https://github.com.evil.example/file","https://user:pass@github.com/file","https://github.com:444/file","https://github.com/file#fragment","//evil.example/file",""}) {
   try { ExtensionHttp.redirect(source,source,bad);throw new AssertionError(bad); } catch(IOException expected) {checks++;}
  }
  for(String original:new String[]{"https://example.org/catalog.json","https://github.com/MetoteM/aFolderPlayer/blob/main/file"}) {
   try { URI uri=URI.create(original);ExtensionHttp.redirect(uri,uri,valid);throw new AssertionError(); } catch(IOException expected) { checks++; }
  }
  System.out.println("PASS: "+checks+" redirect policy checks");
 }
}
