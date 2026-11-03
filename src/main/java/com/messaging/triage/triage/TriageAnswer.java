package com.messaging.triage.triage;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** What the model must answer: a summary and one diagnosis for every group, no more. */
public record TriageAnswer(
  @JsonPropertyDescription("Two or three sentences on the state of both systems: what is failing, how much, "
    + "and which group matters most.")
  @NotBlank String summary,

  @JsonPropertyDescription("Exactly one entry per group id in the report.")
  @NotNull List<@NotNull @Valid GroupDiagnosis> diagnoses) {
}
