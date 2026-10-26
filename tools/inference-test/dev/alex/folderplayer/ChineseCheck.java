package dev.alex.folderplayer;
import java.io.File;import java.util.*;
public class ChineseCheck {
 public static void main(String[] args)throws Exception {
  File root=new File(args[0]);String text="我爱你。\n妈妈给了我一封信，我会永远珍藏它。\n月光照在窗前，我想起了故乡。\n无论你在哪里，我都会等你回来。\n今天下雨了。\n不要放开我的手。\n\n我爱你。\n我爱你。";
  String intermediate,result;
  try(OfflineTranslator zh=new OfflineTranslator(new File(root,"zh-en"),65000)) {
   intermediate=zh.translate(text,()->false,(d,t)->{});
   String longLine="月光照在窗前，我想起了故乡。".repeat(30)+"𠮷";List<String> chunks=zh.chunks(longLine);
   if(!String.join("",chunks).equals(longLine))throw new AssertionError("Chunking lost characters");
   for(String chunk:chunks)if(zh.encode(chunk).length>70 || Character.isHighSurrogate(chunk.charAt(chunk.length()-1)))throw new AssertionError("Invalid chunk");
   try{zh.translate(text,()->true,(d,t)->{});throw new AssertionError("cancel ignored");}catch(InterruptedException expected){}
   System.out.println("Long Chinese text, Unicode and cancellation PASS");
  }
  try(OfflineTranslator ru=new OfflineTranslator(new File(root,"en-ru"))) {result=ru.translate(intermediate,()->false,(d,t)->{});}
  if(result.split("\n",-1).length!=text.split("\n",-1).length || !result.endsWith("Я люблю тебя.\nЯ люблю тебя.") || !result.startsWith("Я люблю тебя."))throw new AssertionError("Structure or translation lost");
  System.out.println(result);System.out.println("Two-stage translation, paragraph and repeated chorus PASS");
 }
}
