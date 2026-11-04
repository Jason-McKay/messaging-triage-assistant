package com.messaging.triage.triage;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks a parsed group answer's shape. Ollama's schema-constrained output makes a
 * well-formed object likely, not certain. Group ids are not checked because the model no
 * longer writes them: code assigns each answer to the group it asked about (ADR 0004).
 */
public class AnswerValidator {

  private final Validator validator;

  public AnswerValidator(Validator validator) {
    this.validator = validator;
  }

  /** Everything wrong with {@code answer}, in plain words; empty when it can be used. */
  public List<String> problems(GroupAnswer answer) {
    List<String> problems = new ArrayList<>();
    if (answer == null) {
      problems.add("the answer is empty");
      return problems;
    }
    validator.validate(answer).stream()
      .map(AnswerValidator::describe)
      .sorted()
      .forEach(problems::add);
    return problems;
  }

  private static String describe(ConstraintViolation<GroupAnswer> violation) {
    return violation.getPropertyPath() + " " + violation.getMessage();
  }
}
