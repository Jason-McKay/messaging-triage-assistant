package com.messaging.triage.triage;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks a parsed answer against the report it answers. Ollama's schema-constrained output
 * makes a well-formed object likely, not certain, and says nothing about whether the group
 * ids are the report's: a model will happily diagnose NP-7 in a report that stops at NP-3.
 */
public class AnswerValidator {

  static final int MAX_ID_CHARS = 40;

  private final Validator validator;

  public AnswerValidator(Validator validator) {
    this.validator = validator;
  }

  /** Everything wrong with {@code answer}, in plain words; empty when it can be used. */
  public List<String> problems(TriageAnswer answer, List<String> groupIds) {
    List<String> problems = new ArrayList<>();
    if (answer == null) {
      problems.add("the answer is empty");
      return problems;
    }
    validator.validate(answer).stream()
      .map(AnswerValidator::describe)
      .sorted()
      .forEach(problems::add);
    if (answer.diagnoses() == null) {
      return problems;
    }
    Set<String> seen = new LinkedHashSet<>();
    for (GroupDiagnosis diagnosis : answer.diagnoses()) {
      if (diagnosis == null || diagnosis.groupId() == null || diagnosis.groupId().isBlank()) {
        continue; // already reported by the constraints
      }
      String id = diagnosis.groupId();
      if (!groupIds.contains(id)) {
        problems.add("unknown groupId " + shortened(id) + " (the report's ids are " + String.join(", ", groupIds) + ")");
      } else if (!seen.add(id)) {
        problems.add("groupId " + id + " is diagnosed more than once");
      }
    }
    List<String> missing = groupIds.stream().filter(id -> !seen.contains(id)).toList();
    if (!missing.isEmpty()) {
      problems.add("no diagnosis for " + String.join(", ", missing));
    }
    return problems;
  }

  private static String describe(ConstraintViolation<TriageAnswer> violation) {
    return violation.getPropertyPath() + " " + violation.getMessage();
  }

  private static String shortened(String id) {
    return id.length() <= MAX_ID_CHARS ? id : id.substring(0, MAX_ID_CHARS) + "...";
  }
}
