package dev.alex.folderplayer;
import java.util.*;
public final class DocumentRepeatTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 @org.junit.Test public void scenarios(){
  List<String> src=CompletenessRules.units("I came home\nI came home");check(src.equals(Arrays.asList("I came home","I came home")));
  List<String> out=Arrays.asList("Я вернулся домой.","Я вернулся домой.");check(!CompletenessRules.warning("Я вернулся домой.",out,src).isEmpty());
  check(CompletenessRules.warning("Я вернулся домой. Я вернулся домой.",out,src).isEmpty());
  check(CompletenessRules.warning("Я вернулся домой домой.",out,src).length()>0);
  check(CompletenessRules.units("She asked me\nto close the window.").size()==1);
  check(CompletenessRules.units("I did not open the letter\nnor throw it away.").size()==1);
  check(CompletenessRules.units("Before sunrise.\nGo home\nGo home\nShe asked me\nto close the window.").equals(Arrays.asList("Before sunrise.","Go home","Go home","She asked me to close the window.")));
  check(CompletenessRules.units("Go home\nGo home\nGo home").size()==3);
  check(CompletenessRules.warning("Да.",Arrays.asList("Да.","Да."),Arrays.asList("Yes.","Indeed.")).isEmpty());
  check(!CompletenessRules.warning("Я пришёл домой дважды.",out,src).isEmpty());
  check(CompletenessRules.units("Go home\r\nGo home").size()==2);
  check(!CompletenessRules.warning("Я вернулся домой.",out,Arrays.asList("I came home.","I came home.")).isEmpty());
  System.out.println("PASS: 12 repeated-source, no-punctuation, preserved-repeat, paraphrase-warning and dependency checks");
 }
}
