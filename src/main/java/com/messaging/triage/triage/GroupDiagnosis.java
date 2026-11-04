package com.messaging.triage.triage;

import java.util.List;

/**
 * One group's diagnosis as a triage returns it: the model's {@link GroupAnswer} for that
 * group, under the id code assigned from the prompt it sent.
 */
public record GroupDiagnosis(
  String groupId,
  String probableCause,
  Confidence confidence,
  String suggestedNextStep,
  List<String> whatToCheck) {

  static GroupDiagnosis of(String groupId, GroupAnswer answer) {
    return new GroupDiagnosis(groupId, answer.probableCause(), answer.confidence(), answer.suggestedNextStep(),
      List.copyOf(answer.whatToCheck()));
  }
}
