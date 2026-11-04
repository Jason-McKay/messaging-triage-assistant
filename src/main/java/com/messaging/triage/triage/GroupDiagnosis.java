package com.messaging.triage.triage;

import java.util.ArrayList;
import java.util.List;

/**
 * One group's diagnosis as a triage returns it: the model's {@link GroupAnswer} for that
 * group, under the id code assigned from the prompt it sent, after code's checks (ADR 0005).
 *
 * @param evidence the quotes from the answer that this group's data holds; the others are dropped
 * @param flags    what code's checks found; any flag caps {@code confidence} at LOW
 */
public record GroupDiagnosis(
  String groupId,
  String probableCause,
  Confidence confidence,
  String suggestedNextStep,
  List<String> whatToCheck,
  List<String> evidence,
  List<DiagnosisFlag> flags) {

  static GroupDiagnosis of(String groupId, GroupAnswer answer, List<String> evidence) {
    return new GroupDiagnosis(groupId, answer.probableCause(), answer.confidence(), answer.suggestedNextStep(),
      List.copyOf(answer.whatToCheck()), List.copyOf(evidence), List.of());
  }

  /** This diagnosis with {@code flag} added and its confidence capped at LOW. */
  GroupDiagnosis flagged(DiagnosisFlag flag) {
    List<DiagnosisFlag> added = new ArrayList<>(flags);
    if (!added.contains(flag)) {
      added.add(flag);
    }
    return new GroupDiagnosis(groupId, probableCause, Confidence.LOW, suggestedNextStep, whatToCheck, evidence,
      List.copyOf(added));
  }
}
