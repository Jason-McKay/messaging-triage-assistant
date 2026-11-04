package com.messaging.triage.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.messaging.triage.guard.RepeatedDiagnosisCheck.Diagnosis;
import com.messaging.triage.guard.RepeatedDiagnosisCheck.Repeat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR 0005: one diagnosis for two groups that failed differently is a copy until shown otherwise. */
class RepeatedDiagnosisCheckTest {

  private static final String MISSING_KEY = "no template configured for key order-refunded";
  private static final String OTHER_KEY = "no template configured for key order-shipped";
  private static final String MISSING_VARIABLE =
    "template order-shipped needs variable orderId, which the payload does not carry";
  private static final String COPIED = "The payload does not carry orderId, which the template needs.";

  @Test
  void theSameCauseForTwoDifferentErrorsIsARepeatOfBoth() {
    List<Repeat> repeats = RepeatedDiagnosisCheck.find(List.of(
      diagnosis("NP-1", COPIED, "Add orderId.", MISSING_KEY),
      diagnosis("NP-2", COPIED, "Fix the publisher.", MISSING_VARIABLE)));

    assertThat(repeats).extracting(Repeat::id, Repeat::otherId, Repeat::field, Repeat::otherText).containsExactly(
      tuple("NP-1", "NP-2", RepeatedDiagnosisCheck.CAUSE, COPIED),
      tuple("NP-2", "NP-1", RepeatedDiagnosisCheck.CAUSE, COPIED));
  }

  @Test
  void aNearCopyIsARepeatButADifferentCauseIsNot() {
    String reworded = "Most likely the payload does not carry the orderId the template needs!";

    assertThat(RepeatedDiagnosisCheck.find(List.of(diagnosis("NP-1", COPIED, "Add orderId.", MISSING_KEY),
      diagnosis("NP-2", reworded, "Fix the publisher.", MISSING_VARIABLE)))).hasSize(2);
    assertThat(RepeatedDiagnosisCheck.find(List.of(
      diagnosis("NP-1", "No template is configured for order-refunded.", "Add the template.", MISSING_KEY),
      diagnosis("NP-2", COPIED, "Fix the publisher.", MISSING_VARIABLE)))).isEmpty();
  }

  @Test
  void aNegationKeepsTwoCausesApart() {
    assertThat(RepeatedDiagnosisCheck.same("The template is missing.", "The template is not missing.")).isFalse();
    assertThat(RepeatedDiagnosisCheck.same("Don't retry it.", "Retry it.")).isFalse();
    assertThat(RepeatedDiagnosisCheck.same("The template is missing.", "The template is probably missing."))
      .as("a hedge is filler").isTrue();
  }

  @Test
  void theSameNextStepIsARepeatToo() {
    List<Repeat> repeats = RepeatedDiagnosisCheck.find(List.of(
      diagnosis("NP-1", "No template is configured for the key.", "Ask the sender to add orderId.", MISSING_KEY),
      diagnosis("NP-2", COPIED, "Ask the sender to add orderId.", MISSING_VARIABLE)));

    assertThat(repeats).extracting(Repeat::field).containsOnly(RepeatedDiagnosisCheck.NEXT_STEP);
  }

  @Test
  void groupsWithTheSameErrorMayShareACause() {
    // The same missing-template error on two channels, or for two keys, has one cause.
    assertThat(RepeatedDiagnosisCheck.find(List.of(
      diagnosis("NP-1", "No template is configured for the key.", "Add the template.", MISSING_KEY),
      diagnosis("NP-2", "No template is configured for the key.", "Add the template.", OTHER_KEY)))).isEmpty();
  }

  @Test
  void aMergedGroupIsNeverCompared() {
    Diagnosis merged = new Diagnosis("NP-9", COPIED, "Add orderId.", "4 smaller groups with different errors, merged",
      true);

    assertThat(RepeatedDiagnosisCheck.find(List.of(diagnosis("NP-1", COPIED, "Add orderId.", MISSING_VARIABLE),
      merged))).isEmpty();
  }

  @Test
  void eachRepeatNamesTheFirstGroupItRepeats() {
    List<Repeat> repeats = RepeatedDiagnosisCheck.find(List.of(
      diagnosis("NP-1", COPIED, "Add orderId.", MISSING_KEY),
      diagnosis("NP-2", COPIED, "Add orderId.", MISSING_VARIABLE),
      diagnosis("MI-1", COPIED, "Add orderId.", "Missing required field: address")));

    assertThat(repeats).extracting(Repeat::id, Repeat::otherId)
      .containsExactly(tuple("NP-1", "NP-2"), tuple("NP-2", "NP-1"), tuple("MI-1", "NP-1"));
  }

  @Test
  void blankOrMissingTextIsNeverARepeat() {
    assertThat(RepeatedDiagnosisCheck.same(null, null)).isFalse();
    assertThat(RepeatedDiagnosisCheck.same("the", "a")).as("filler only, compared as written").isFalse();
    assertThat(RepeatedDiagnosisCheck.same("The", " the ")).isTrue();
  }

  private static Diagnosis diagnosis(String id, String cause, String nextStep, String error) {
    return new Diagnosis(id, cause, nextStep, error, false);
  }
}
