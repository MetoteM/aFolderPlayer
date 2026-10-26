import java.nio.file.*;import java.util.*;import java.security.*;
public class SignCatalog {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);Properties p=new Properties();try(var in=Files.newInputStream(root.resolve("signing/signing.properties"))){p.load(in);}char[] password=p.getProperty("storePassword").toCharArray();KeyStore store=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(root.resolve("signing/folder-player.p12"))){store.load(in,password);}
  var cert=store.getCertificate("folder-player");Files.write(Path.of(args[1]),cert.getEncoded());
  if(args.length>2) { byte[] payload=Files.readAllBytes(Path.of(args[2]));Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign((PrivateKey)store.getKey("folder-player",password));signer.update(payload);String envelope="{\"payload\":\""+Base64.getEncoder().encodeToString(payload)+"\",\"signature\":\""+Base64.getEncoder().encodeToString(signer.sign())+"\"}";Files.writeString(Path.of(args[3]),envelope); }
  Arrays.fill(password,'\0');
 }
}
