package dev.alex.folderplayer;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/** Real production inference on the pinned direct Spanish model. */
public class SpanishCheck {
 public static void main(String[] args)throws Exception {
  long started=System.nanoTime();
  try(OfflineTranslator engine=new OfflineTranslator(new File(args[0],"es-ru"),63429)) {
   if(!Arrays.equals(engine.encode("Te quiero."),new long[]{955,1606,4,0}) ||
    !Arrays.equals(engine.encode("El niño preguntó: ¿dónde está mi corazón?"),new long[]{53,1853,13413,40,50,10264,129,200,6139,26,0}))throw new AssertionError("Tokenization differs from source SentencePiece");
   String longLine="El niño preguntó: ¿dónde está mi corazón? ".repeat(40);
   List<String> chunks=engine.chunks(longLine);
   if(!String.join(" ",chunks).equals(longLine.trim()))throw new AssertionError("Spanish chunking lost characters");
   for(String chunk:chunks)if(engine.encode(chunk).length>70)throw new AssertionError("Invalid chunk");
   String text="Te quiero.\nMi madre me dio una carta.\nLa guardaré para siempre.\nNo cierres la puerta.\nEsperamos la salida del sol.\n\nTe quiero.\nTe quiero.";
   int[] count={0};
   String result=engine.translate(text,()->false,(done,total)->{
    if(done!=++count[0] || total!=8)throw new AssertionError("Invalid direct-stage progress");
   });
   String[] lines=result.split("\n",-1);
   if(lines.length!=8 || !lines[5].isEmpty() || !lines[0].equals(lines[6]) || !lines[0].equals(lines[7]))throw new AssertionError("Chorus or paragraph lost");
   if(!lines[0].toLowerCase().contains("люб") || !lines[1].toLowerCase().contains("письм") || !lines[3].toLowerCase().contains("не") || !lines[4].toLowerCase().contains("солнц"))throw new AssertionError("Spanish meaning lost: "+result);
   try { engine.translate(text,()->true,(d,t)->{});throw new AssertionError("Cancel ignored"); }catch(InterruptedException expected){}
   System.out.println(result);
   String[] quality={"No te quiero.","No cierres la ventana.","Ella es mi hermana y él es mi hermano.","Ayer caminamos bajo la lluvia.","Mañana volveré a casa.","Si tú quieres, podemos ir juntos.","¿Por qué no me respondes?","Ya no tengo miedo del silencio.","El niño está jugando en el jardín.","¿Dónde están los niños?","Me dijiste que todo saldría bien.","Hay tres libros sobre la mesa.","Estoy cansado, pero sigo caminando.","Aunque la noche es fría, buscamos la luz.","Guardaré esta carta para siempre.","Mi corazón está contigo."};
   for(int i=0;i<quality.length;i++) {
    String translated=engine.translate(quality[i],()->false,(d,t)->{});
    if(translated.isBlank() || !translated.matches("(?s).*[А-Яа-яЁё].*"))throw new AssertionError("No Russian output");
    if(i<2 && !translated.toLowerCase().contains("не"))throw new AssertionError("Negation lost: "+translated);
    System.out.println(quality[i]+"\t"+translated);
   }
  }
  System.out.printf("Spanish direct pipeline, accents, paragraphs, chorus, cancellation and quality corpus PASS; %.2f seconds%n",(System.nanoTime()-started)/1e9);
 }
}
