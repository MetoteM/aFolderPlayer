package dev.alex.folderplayer;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/** Runs the production Java decoder on real pinned French weights. */
public class FrenchCheck {
 public static void main(String[] args) throws Exception {
  String text="Je t'aime.\nMa mère m'a donné une lettre.\nJe la garderai pour toujours.\nNe lâche pas ma main.\nNous attendons le lever du soleil.\n\nJe t'aime.\nJe t'aime.";
  long started=System.nanoTime();
  try(OfflineTranslator engine=new OfflineTranslator(new File(args[0],"fr-ru"),64376)) {
   if(!Arrays.equals(engine.encode("Je t'aime."),new long[]{105,456,14,3656,3,0}) ||
    !Arrays.equals(engine.encode("L'été, le cœur et la lumière."),new long[]{103,14,13461,2,18,6847,12,7,3979,3,0}))throw new AssertionError("Tokenization differs from pinned source SentencePiece");
   List<String> chunks=engine.chunks("L'été, le cœur et la lumière. ".repeat(40));
   if(!String.join(" ",chunks).equals("L'été, le cœur et la lumière. ".repeat(40).trim()))throw new AssertionError("French chunking lost characters");
   for(String chunk:chunks)if(engine.encode(chunk).length>70)throw new AssertionError("Invalid chunk");
   int[] progress={0};
   String result=engine.translate(text,()->false,(done,total)->{
    if(done!=++progress[0] || total!=8)throw new AssertionError("Invalid direct-stage progress");
   });
   if(result.split("\n",-1).length!=8) {
    throw new AssertionError("Structure or translation lost: "+result);
   }
   if(!result.startsWith("Я люблю тебя.") || !result.endsWith("Я люблю тебя.\nЯ люблю тебя.") || !result.contains("\n\n"))throw new AssertionError("Russian chorus or paragraph lost: "+result);
   if(!result.toLowerCase().contains("письм") || !result.toLowerCase().contains("рук") || !result.toLowerCase().contains("солнц"))throw new AssertionError("French meaning lost: "+result);
   try { engine.translate(text,()->true,(d,t)->{});throw new AssertionError("Cancel ignored"); }catch(InterruptedException expected){}
   System.out.println(result);
  }
  System.out.printf("French direct pipeline, accents, paragraph, chorus and cancellation PASS; %.2f seconds%n",(System.nanoTime()-started)/1e9);
 }
}
