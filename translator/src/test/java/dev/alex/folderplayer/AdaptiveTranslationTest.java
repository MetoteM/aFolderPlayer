package dev.alex.folderplayer;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AdaptiveTranslationTest {
  @Test
  public void retryRetainsAllWords() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    String source = "first second third fourth fifth sixth";
    assertEquals(
        source,
        OfflineTranslator.adaptiveRetry(
            source,
            () -> false,
            0,
            text -> {
              if (calls.getAndIncrement() == 0)
                throw new OfflineTranslator.IncompleteTranslation("incomplete");
              return text;
            }));
    assertEquals(3, calls.get());
  }

  @Test
  public void decomposesAgainAndNeverRepeatsSuccessfulPiece() throws Exception {
    java.util.Map<String, Integer> calls = new java.util.HashMap<>();
    String source = "one two three four five six seven eight";
    assertEquals(
        source,
        OfflineTranslator.adaptiveRetry(
            source,
            () -> false,
            0,
            text -> {
              calls.merge(text, 1, Integer::sum);
              if (text.split(" ").length > 2)
                throw new OfflineTranslator.IncompleteTranslation("incomplete");
              return text;
            }));
    assertEquals(7, calls.size());
    assertTrue(calls.values().stream().allMatch(n -> n == 1));
  }

  @Test
  public void thirdLevelRecoversTwoWordFragment() throws Exception {
    assertEquals(
        "one two",
        OfflineTranslator.adaptiveRetry(
            "one two",
            () -> false,
            0,
            text -> {
              if (text.contains(" "))
                throw new OfflineTranslator.IncompleteTranslation("incomplete");
              return text;
            }));
  }

  @Test
  public void failedRetryDoesNotReturnPartialDecoderOutput() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try {
      OfflineTranslator.adaptiveRetry(
          "one two three four five six seven eight nine ten",
          () -> false,
          0,
          text -> {
            calls.incrementAndGet();
            throw new OfflineTranslator.IncompleteTranslation("incomplete");
          });
      fail();
    } catch (OfflineTranslator.IncompleteTranslation expected) {
    }
    assertTrue(calls.get() <= 4);
  }

  @Test
  public void cancellationPreventsAnyDecode() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try {
      OfflineTranslator.adaptiveRetry(
          "one two",
          () -> true,
          0,
          text -> {
            calls.incrementAndGet();
            return text;
          });
      fail();
    } catch (InterruptedException expected) {
    }
    assertEquals(0, calls.get());
  }

  @Test
  public void cancellationBetweenPiecesStopsImmediately() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try {
      OfflineTranslator.adaptiveRetry(
          "one two three four",
          () -> calls.get() >= 2,
          0,
          text -> {
            if (calls.incrementAndGet() == 1)
              throw new OfflineTranslator.IncompleteTranslation("incomplete");
            return text;
          });
      fail();
    } catch (InterruptedException expected) {
    }
    assertEquals(2, calls.get());
  }

  @Test
  public void resourceErrorsAreNotRetried() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try {
      OfflineTranslator.adaptiveRetry(
          "one two three four",
          () -> false,
          0,
          text -> {
            calls.incrementAndGet();
            throw new IOException("missing model");
          });
      fail();
    } catch (IOException expected) {
      assertEquals("missing model", expected.getMessage());
    }
    assertEquals(1, calls.get());
  }

  @Test
  public void splitPrefersPunctuationAndPreservesUnicode() {
    assertEquals(
        Arrays.asList("First sentence.", "Another sentence"),
        OfflineTranslator.splitRecovery("First sentence. Another sentence"));
    assertEquals(Arrays.asList("你好", "世界"), OfflineTranslator.splitRecovery("你好世界"));
    assertEquals(Arrays.asList("word"), OfflineTranslator.splitRecovery("word"));
  }

  @Test
  public void depthLimitAndAttemptBudgetAreFinite() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try {
      OfflineTranslator.adaptiveRetry(
          "one two",
          () -> false,
          3,
          text -> {
            calls.incrementAndGet();
            throw new OfflineTranslator.IncompleteTranslation("incomplete");
          });
      fail();
    } catch (OfflineTranslator.IncompleteTranslation expected) {
    }
    assertEquals(1, calls.get());
    OfflineTranslator.RetryBudget budget = new OfflineTranslator.RetryBudget();
    for (int i = 0; i < 14; i++) {
      assertTrue(budget.available());
      budget.use();
    }
    assertFalse(budget.available());
  }

  @Test
  public void partialResultRetainsOtherUnitsBlankLinesAndRepeatedFailures() throws Exception {
    AtomicInteger failedCalls = new AtomicInteger();
    OfflineTranslator.TranslationResult result =
        OfflineTranslator.translateUnits(
            "Good.\nBad.\n\nGood.\nBad.\nLast.",
            () -> false,
            (done, total) -> {},
            true,
            text -> java.util.Collections.singletonList(text),
            text -> {
              if (text.equals("Bad.")) {
                failedCalls.incrementAndGet();
                throw new OfflineTranslator.IncompleteTranslation("incomplete");
              }
              return "RU " + text;
            });
    assertEquals("RU Good.\nBad.\n\nRU Good.\nBad.\nRU Last.", result.getText());
    assertEquals(Arrays.asList(1, 4), result.getFailedLines());
    assertEquals(1, failedCalls.get());
  }

  @Test
  public void strictProtocolStillRejectsPartialResult() throws Exception {
    try {
      OfflineTranslator.translateUnits(
          "Good.\nBad.",
          () -> false,
          (done, total) -> {},
          false,
          text -> java.util.Collections.singletonList(text),
          text -> {
            if (text.equals("Bad."))
              throw new OfflineTranslator.IncompleteTranslation("incomplete");
            return "RU " + text;
          });
      fail();
    } catch (OfflineTranslator.IncompleteTranslation expected) {
    }
  }

  @Test
  public void cancelledPartialJobNeverPublishesSuccess() throws Exception {
    java.util.concurrent.atomic.AtomicBoolean cancel =
        new java.util.concurrent.atomic.AtomicBoolean();
    try {
      OfflineTranslator.translateUnits(
          "Good.\nLast.",
          cancel::get,
          (done, total) -> cancel.set(true),
          true,
          text -> java.util.Collections.singletonList(text),
          text -> "RU " + text);
      fail();
    } catch (InterruptedException expected) {
    }
  }

  @Test
  public void sharedExtraWorkBudgetStopsFurtherDecomposition() throws Exception {
    long[] spent = {java.util.concurrent.TimeUnit.SECONDS.toNanos(120)};
    assertFalse(new OfflineTranslator.RetryBudget(spent).available());
  }
}
