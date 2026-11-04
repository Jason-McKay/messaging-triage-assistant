package com.messaging.triage.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A chat question. The server keeps no conversation: a client that wants follow-ups sends
 * the earlier turns back, and only the last few are used ({@code triage.chat.max-history-turns}).
 */
public record ChatRequest(
  @NotBlank @Size(max = 4000) String message,
  @Size(max = 50) List<@NotNull @Valid Turn> history) {

  public record Turn(
    @NotBlank @Pattern(regexp = "user|assistant") String role,
    @NotBlank @Size(max = 8000) String text) {
  }
}
