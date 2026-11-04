package com.messaging.triage.triage;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What the model answers for the one group it was shown. The descriptions end up in the JSON
 * schema the model is given (and that Ollama constrains its output to), so they are written
 * for it. There is no group id: code knows which group it asked about (ADR 0004).
 */
public record GroupAnswer(
  @JsonPropertyDescription("The most likely cause, in one or two plain sentences, citing the evidence.")
  @NotBlank String probableCause,

  @JsonPropertyDescription("HIGH if the error text states the cause, MEDIUM if inferred from the payload or "
    + "pattern, LOW if a guess.")
  @NotNull Confidence confidence,

  @JsonPropertyDescription("The one thing a person should do next. Never resend, delete or move messages "
    + "until the cause is fixed.")
  @NotBlank String suggestedNextStep,

  @JsonPropertyDescription("Specific things to look at to confirm the cause: a config key, a template, a "
    + "producer, a field in the payload.")
  @NotNull List<@NotBlank String> whatToCheck,

  // Not validated: missing or made-up evidence is flagged in code, not sent back for a repair (ADR 0005).
  @JsonPropertyDescription("One to three short quotes copied exactly from this group's error, samples or "
    + "payloads that show the cause, or the group's count such as \"3 failures\". A placeholder such as "
    + "<value> is not evidence.")
  List<String> evidence) {
}
