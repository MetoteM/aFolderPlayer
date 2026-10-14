package dev.alex.folderplayer;

import java.io.IOException;
import java.net.URI;

public class ExtensionHttpCheck {
  public static void main(String[] args) throws Exception {
    URI source =
        URI.create(
            "https://github.com/MetoteM/aFolderPlayer/releases/download/extensions-v1/catalog.json");
    String valid =
        "https://release-assets.githubusercontent.com/github-production-release-asset/1/file?token=abc";
    if (!ExtensionHttp.redirect(source, source, valid).toString().equals(valid))
      throw new AssertionError();
    URI latest =
        URI.create(
            "https://github.com/MetoteM/aFolderPlayer/releases/latest/download/catalog.json");
    URI pinned =
        URI.create(
            "https://github.com/MetoteM/aFolderPlayer/releases/download/v0.8.1/catalog.json");
    if (!ExtensionHttp.redirect(latest, latest, pinned.toString()).equals(pinned))
      throw new AssertionError("latest to tagged release");
    if (!ExtensionHttp.redirect(latest, pinned, valid).toString().equals(valid))
      throw new AssertionError("tagged release to asset");
    int checks = 3;
    for (String bad :
        new String[] {
          "http://release-assets.githubusercontent.com/file",
          "https://evil.example/file",
          "https://github.com.evil.example/file",
          "https://user:pass@github.com/file",
          "https://github.com:444/file",
          "https://github.com/file#fragment",
          "//evil.example/file",
          ""
        }) {
      try {
        ExtensionHttp.redirect(source, source, bad);
        throw new AssertionError(bad);
      } catch (IOException expected) {
        checks++;
      }
    }
    for (String original :
        new String[] {
          "https://example.org/catalog.json",
          "https://github.com/MetoteM/aFolderPlayer/blob/main/file",
          "https://github.com/MetoteM/aFolderPlayer/releases/latest"
        }) {
      try {
        URI uri = URI.create(original);
        ExtensionHttp.redirect(uri, uri, valid);
        throw new AssertionError();
      } catch (IOException expected) {
        checks++;
      }
    }
    System.out.println("PASS: " + checks + " redirect policy checks");
    if (args.length == 2) {
      java.net.HttpURLConnection connection = ExtensionHttp.connect(URI.create(args[0]));
      try {
        if (connection.getResponseCode() != 200)
          throw new AssertionError("HTTP " + connection.getResponseCode());
        byte[] bytes;
        try (java.io.InputStream input = connection.getInputStream()) {
          bytes = input.readNBytes(200001);
        }
        if (bytes.length > 200000) throw new AssertionError("Catalogue too large");
        String digest =
            java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!digest.equals(args[1])) throw new AssertionError("Catalogue checksum differs");
        System.out.println(
            "PASS: live catalogue download through production HTTP policy and full-byte SHA-256");
      } finally {
        connection.disconnect();
      }
    }
  }
}
