package com.messaging.triage.triage;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The model's reading of one failure group. The descriptions end up in the JSON schema the
 * model is given (and that Ollama constrains its output to), so they are written for it.
 */
public record GroupDiagnosis(
  @JsonPropertyDescription("The group id exactly as given in the report, e.g. NP-1 or MI-2.")
  @NotBlank String groupId,

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
  @NotNull List<@NotBlank String> whatToCheck) {
}
