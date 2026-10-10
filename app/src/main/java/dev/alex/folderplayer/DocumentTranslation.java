package dev.alex.folderplayer;
import java.util.*;
public final class DocumentTranslation {
 public static List<String> paragraphs(String source){return Arrays.asList(source.replace("\r","").split("\\n[ \\t]*\\n",-1));}
 public static String assemble(List<String> translated){return String.join("\n\n",translated);}
}
