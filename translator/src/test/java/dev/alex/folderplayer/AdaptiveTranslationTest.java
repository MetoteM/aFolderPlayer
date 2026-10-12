package dev.alex.folderplayer;
import org.junit.Test;import static org.junit.Assert.*;import java.util.concurrent.atomic.AtomicInteger;
public class AdaptiveTranslationTest {
 @Test public void retryRetainsAllWordsAndIsBounded()throws Exception {
  AtomicInteger calls=new AtomicInteger();String source="first second third fourth fifth sixth";
  String result=OfflineTranslator.adaptiveRetry(source,()->false,0,text->{if(calls.getAndIncrement()==0)throw new OfflineTranslator.IncompleteTranslation("incomplete");return text;});
  assertEquals(source,result);assertEquals(3,calls.get());
 }
 @Test public void failedRetryDoesNotReturnPartialText()throws Exception {
  AtomicInteger calls=new AtomicInteger();
  try {OfflineTranslator.adaptiveRetry("first second third fourth",()->false,0,text->{calls.incrementAndGet();throw new OfflineTranslator.IncompleteTranslation("incomplete");});fail();}
  catch(OfflineTranslator.IncompleteTranslation expected){}
  assertEquals(2,calls.get());
 }
 @Test public void cancellationPreventsRetry()throws Exception {
  AtomicInteger calls=new AtomicInteger();
  try {OfflineTranslator.adaptiveRetry("first second third fourth",()->true,0,text->{calls.incrementAndGet();throw new OfflineTranslator.IncompleteTranslation("incomplete");});fail();}
  catch(InterruptedException expected){}
  assertEquals(1,calls.get());
 }
}
