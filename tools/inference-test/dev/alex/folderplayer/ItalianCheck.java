package dev.alex.folderplayer;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/** Real inference through both production stages; no network or Android runtime. */
public class ItalianCheck {
 public static void main(String[] args)throws Exception {
  File root=new File(args[0]);
  String text="Ti amo.\nMia madre mi ha dato una lettera.\nLa conserverò per sempre.\nNon chiudere la porta.\nAspettiamo il sorgere del sole.\n\nTi amo.\nTi amo.";
  String intermediate,result;
  try(OfflineTranslator it=new OfflineTranslator(new File(root,"it-en"),80378)) {
   if(!Arrays.equals(it.encode("Ti amo."),new long[]{551,9,220,2,0}) ||
      !Arrays.equals(it.encode("Non chiudere la porta."),new long[]{107,12208,15,1125,2,0}))throw new AssertionError("Italian tokenizer differs from source SentencePiece");
   String longLine="Perché il mio cuore è sempre con te? ".repeat(40);
   List<String> chunks=it.chunks(longLine);
   if(!String.join(" ",chunks).equals(longLine.trim()))throw new AssertionError("Italian chunking lost characters");
   for(String chunk:chunks)if(it.encode(chunk).length>70)throw new AssertionError("Oversized Italian chunk");
   int[] count={0};
   intermediate=it.translate(text,()->false,(done,total)->{if(done!=++count[0] || total!=8)throw new AssertionError("Invalid Italian progress");});
   try{it.translate(text,()->true,(d,t)->{});throw new AssertionError("Italian cancellation ignored");}catch(InterruptedException expected){}
  }
  try(OfflineTranslator ru=new OfflineTranslator(new File(root,"en-ru"))) {
   result=ru.translate(intermediate,()->false,(d,t)->{});
  }
  String[] lines=result.split("\n",-1);
  if(lines.length!=8 || !lines[5].isEmpty() || !lines[0].equals(lines[6]) || !lines[0].equals(lines[7]))throw new AssertionError("Italian paragraph or chorus lost");
  if(!lines[0].toLowerCase().contains("люб") || !lines[1].toLowerCase().contains("письм") || !lines[3].toLowerCase().contains("не") || !lines[4].toLowerCase().contains("солнц"))throw new AssertionError("Italian meaning lost: "+result);
  System.out.println(result);
  String[] quality={"Non ti amo.","Non chiudere la finestra.","Lei è mia sorella e lui è mio fratello.","Ieri abbiamo camminato sotto la pioggia.","Domani tornerò a casa.","Perché non mi rispondi?","Ci sono tre libri sul tavolo.","Il mio cuore è con te."};
  String translated;
  for(int i=0;i<quality.length;i++) {
   try(OfflineTranslator it=new OfflineTranslator(new File(root,"it-en"),80378)) {translated=it.translate(quality[i],()->false,(d,t)->{});}
   try(OfflineTranslator ru=new OfflineTranslator(new File(root,"en-ru"))) {translated=ru.translate(translated,()->false,(d,t)->{});}
   if(translated.isBlank() || !translated.matches("(?s).*[А-Яа-яЁё].*"))throw new AssertionError("No Russian output");
   if(i<2 && !translated.toLowerCase().contains("не"))throw new AssertionError("Italian negation lost: "+translated);
   System.out.println(quality[i]+"\t"+translated);
  }
  System.out.println("Italian pipeline, source tokenizer, accents, paragraph, chorus, negation and cancellation PASS");
 }
}
