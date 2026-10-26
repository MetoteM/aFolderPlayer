package dev.alex.folderplayer;
import java.io.File;import java.util.*;
public class GermanCheck {
 public static void main(String[] args)throws Exception {
  File root=new File(args[0]);String text="Ich liebe dich.\nMeine Mutter gab mir einen Brief.\nIch werde ihn für immer aufbewahren.\nBitte lass meine Hand nicht los.\nWir warten auf den Sonnenaufgang.\n\nIch liebe dich.\nIch liebe dich.";
  String intermediate,result;
  try(OfflineTranslator de=new OfflineTranslator(new File(root,"de-en"),58100)) {
   intermediate=de.translate(text,()->false,(d,t)->{});
   String longLine="Über den großen Fluss führte eine alte Brücke. ".repeat(40);List<String> chunks=de.chunks(longLine);
   if(!String.join(" ",chunks).equals(longLine.trim()))throw new AssertionError("Chunking lost German characters");
   for(String chunk:chunks)if(de.encode(chunk).length>70)throw new AssertionError("Invalid chunk");
   try{de.translate(text,()->true,(d,t)->{});throw new AssertionError("cancel ignored");}catch(InterruptedException expected){}
  }
  try(OfflineTranslator ru=new OfflineTranslator(new File(root,"en-ru"))) {result=ru.translate(intermediate,()->false,(d,t)->{});}
  if(result.split("\n",-1).length!=text.split("\n",-1).length || !result.endsWith("Я люблю тебя.\nЯ люблю тебя.") || !result.startsWith("Я люблю тебя."))throw new AssertionError("Structure or translation lost");
  System.out.println(result);System.out.println("German pipeline, umlauts, paragraph, chorus and cancellation PASS");
 }
}
