package dev.alex.folderplayer;
import java.util.*;
public final class DocumentTranslationTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 @org.junit.Test public void scenarios(){
  List<String> p=DocumentTranslation.paragraphs("Verse.\nSecond.\r\n\r\nChorus.\n\nVerse.\nSecond.");check(p.size()==3);check(p.get(0).equals(p.get(2)));check(CompletenessRules.units(p.get(0)).size()==2);
  check(DocumentTranslation.assemble(Arrays.asList("Куплет","Припев","Куплет")).equals("Куплет\n\nПрипев\n\nКуплет"));
  check(DocumentTranslation.paragraphs("One\nline").size()==1);check(CompletenessRules.units("One\nline").equals(Arrays.asList("One line")));
  check(DocumentTranslation.paragraphs("\n\nA.\n\n").equals(Arrays.asList("","A.","")));
  check(DocumentTranslation.assemble(Arrays.asList("A","","B")).equals("A\n\n\n\nB"));
  System.out.println("PASS: 8 document segmentation, chorus, assembly and empty paragraph checks");
 }
}
