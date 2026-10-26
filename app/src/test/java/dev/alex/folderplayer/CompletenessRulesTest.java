package dev.alex.folderplayer;
import java.util.*;
public final class CompletenessRulesTest {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 @org.junit.Test public void scenarios(){
  check(CompletenessRules.units("Dr. Smith opened the door.\nMrs. Green answered the phone.").size()==2);
  check(CompletenessRules.units("The box weighed 2.5 kilograms.\nI carried it upstairs.").size()==2);
  check(CompletenessRules.units("She asked me\nto close the window.").equals(Arrays.asList("She asked me to close the window.")));
  check(CompletenessRules.units("I did not open the letter\nnor throw it away.").size()==1);
  check(CompletenessRules.units("My mother gave me a letter\nthat I will keep forever.").size()==1);
  check(CompletenessRules.units("I came home.\nI came home.").equals(Arrays.asList("I came home.","I came home.")));
  check(CompletenessRules.units("First.\r\n\r\nSecond.").equals(Arrays.asList("First.","","Second.")));
  check(!CompletenessRules.warning("Любовь матери к сыну не разглашается.",Arrays.asList("Любовь матери к сыну не разглашается.","Помоги мне быть собой.")).isEmpty());
  check(!CompletenessRules.warning("Я понесла его наверху.",Arrays.asList("Коробка весила 2,5 килограмма.","Я понесла его наверх.")).isEmpty());
  check(CompletenessRules.warning("Я согласился, я сказал да.",Arrays.asList("Я согласился.","Я сказал да.")).isEmpty());
  check(CompletenessRules.warning("Я вернулся домой.",Arrays.asList("Я вернулся домой.","Я вернулся домой.")).isEmpty());
  check(CompletenessRules.warning("One.",Arrays.asList("One.")).isEmpty());
  System.out.println("PASS: 12 boundary, negation scope, repetition, paragraph, warning and control checks");
 }
}
