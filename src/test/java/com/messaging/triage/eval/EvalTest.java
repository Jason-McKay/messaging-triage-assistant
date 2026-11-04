package com.messaging.triage.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.messaging.triage.eval.EvalRunner.Check;
import com.messaging.triage.eval.EvalRunner.EvalCase;
import com.messaging.triage.eval.EvalRunner.Expectation;
import com.messaging.triage.eval.EvalRunner.Result;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The eval set, each case's groups answered from its script through the real triager. Every
 * case passes; and each guard a case names is shown to be what makes it pass, by scoring the
 * same answers taken as the model wrote them. The table is left in
 * {@code target/eval/scripted-results.md}.
 */
class EvalTest {

  private static final List<Result> RESULTS = new CopyOnWriteArrayList<>();

  @TestFactory
  Stream<DynamicTest> everyCasePassesThroughTheTriager() {
    return EvalRunner.cases().stream().map(evalCase -> DynamicTest.dynamicTest(evalCase.name(), () -> {
      Result result = EvalRunner.scripted(evalCase);
      RESULTS.add(result);

      assertThat(result.checks()).as("%s checks something", evalCase.name()).isNotEmpty();
      assertThat(result.failures()).as(evalCase.description()).isEmpty();
      assertThat(result.verdict().outcome().usable()).isTrue();
    }));
  }

  @TestFactory
  Stream<DynamicTest> theScriptedAnswersFailWithoutTheGuards() {
    return EvalRunner.cases().stream()
      .filter(evalCase -> !evalCase.guardedAgainst().isEmpty())
      .map(evalCase -> DynamicTest.dynamicTest(evalCase.name(), () -> {
        Map<Expectation, Check> unguarded = EvalRunner.unguarded(evalCase);
        for (Expectation expectation : evalCase.guardedAgainst()) {
          assertThat(unguarded).as("%s is checked", expectation).containsKey(expectation);
          assertThat(unguarded.get(expectation).passed()).as("%s, the answers taken as written", expectation)
            .isFalse();
        }
      }));
  }

  @Test
  void theSetHoldsTheDemoBothInjectionsAndACaseForEachGuard() {
    List<EvalCase> cases = EvalRunner.cases();

    assertThat(cases).extracting(EvalCase::name)
      .contains("seeded-demo", "injected-template-key", "injected-payload", "copied-cause", "resend-advice",
        "fabricated-evidence")
      .doesNotHaveDuplicates();
    assertThat(cases.stream().flatMap(evalCase -> evalCase.guardedAgainst().stream()))
      .contains(Expectation.DISTINCT_CAUSES, Expectation.NO_UNFLAGGED_STATE_CHANGE, Expectation.NO_UNSUPPORTED_HIGH);
  }

  @Test
  void theEvalsOwnMeasureTellsACopyFromADifferentCause() {
    String cause = "The orders consumer rejects orders with a negative quantity; the quantity must be positive.";

    assertThat(EvalRunner.sameCause(cause, "the orders consumer rejects orders with a negative quantity, "
      + "and the quantity must be positive")).isTrue();
    assertThat(EvalRunner.sameCause(cause, "The payments consumer expects JSON, but the payloads are XML."))
      .isFalse();
  }

  @AfterAll
  static void writeTheTable() {
    if (!RESULTS.isEmpty()) {
      List<Result> sorted = RESULTS.stream().sorted(Comparator.comparing(Result::name)).toList();
      EvalRunner.write("scripted-results.md", EvalRunner.table(sorted) + "\n" + EvalRunner.answers(sorted));
    }
  }
}
