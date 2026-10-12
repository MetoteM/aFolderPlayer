package dev.alex.folderplayer;
import java.util.*;import org.junit.Test;import static org.junit.Assert.*;
public class OrdinaryTranslationTest {
 @Test public void wrappedFragmentsAreSentAsTheSameContextAsFullText(){
  List<String> paragraphs=DocumentTranslation.paragraphs("Hold\nthis promise\nclose to your heart.\n\nRemember me.");
  Set<String> ordinary=DocumentTranslation.requestUnits(paragraphs,false);
  assertEquals(new LinkedHashSet<>(Arrays.asList("Hold this promise close to your heart.","Remember me.")),ordinary);
  assertTrue(DocumentTranslation.requestUnits(paragraphs,true).containsAll(ordinary));assertFalse(ordinary.contains("Hold"));
 }
 @Test public void repeatedChorusAndEmptyParagraphsSurviveDeduplication(){
  List<String> paragraphs=DocumentTranslation.paragraphs("Stay.\nStay.\n\nRemember\nme.\n\nStay.\nStay.");
  assertEquals(2,DocumentTranslation.requestUnits(paragraphs,false).size());Map<String,String> translated=new HashMap<>();translated.put("Stay.","Останься.");translated.put("Remember me.","Помни меня.");translated.put("","");
  assertEquals("Останься.\nОстанься.\n\nПомни меня.\n\nОстанься.\nОстанься.",DocumentTranslation.assembleSentences(paragraphs,translated));
 }
 @Test public void ordinaryAssemblyMatchesFullTextDefault(){
  List<String> paragraphs=DocumentTranslation.paragraphs("First sentence.\nSecond sentence.\n\n第三句。第四句。");Map<String,String> translated=new HashMap<>();translated.put("","");
  for(String unit:DocumentTranslation.requestUnits(paragraphs,true))translated.put(unit,"Перевод "+unit);
  List<String> selected=new ArrayList<>();for(String paragraph:paragraphs){List<String> parts=new ArrayList<>();for(String unit:CompletenessRules.units(paragraph))parts.add(translated.get(unit));selected.add(String.join("\n",parts));}
  assertEquals(DocumentTranslation.assemble(selected),DocumentTranslation.assembleSentences(paragraphs,translated));
 }
 @Test public void missingTranslationCannotBecomeSavedText(){
  try{DocumentTranslation.assembleSentences(Arrays.asList("One. Two."),Collections.singletonMap("One.","Один."));fail();}catch(IllegalArgumentException expected){}
 }
}
