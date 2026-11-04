package com.messaging.triage.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Everything under {@code triage.*}. Defaults live in {@code application.properties}, each
 * overridable from the environment, so this type only says what a valid value is.
 */
@Validated
@ConfigurationProperties("triage")
public record TriageProperties(
  @NotBlank String model,
  @Valid @NotNull Sources sources,
  @Valid @NotNull Limits limits,
  @Valid @NotNull Chat chat) {

  public record Sources(
    @NotNull Duration connectTimeout,
    @NotNull Duration readTimeout,
    @Valid @NotNull NotificationPlatform notificationPlatform,
    @Valid @NotNull MessageInspector messageInspector) {
  }

  /** The worker serves the reads; the api only mints the token. */
  public record NotificationPlatform(
    boolean enabled,
    @NotBlank String apiUrl,
    @NotBlank String workerUrl,
    @NotBlank String clientId,
    @NotBlank String clientSecret) {

    /** Redacted: a record's generated toString would print the secret. */
    @Override
    public String toString() {
      return "NotificationPlatform[enabled=" + enabled + ", apiUrl=" + apiUrl + ", workerUrl=" + workerUrl
        + ", clientId=" + clientId + ", clientSecret=<redacted>]";
    }
  }

  public record MessageInspector(
    boolean enabled,
    @NotBlank String url,
    @NotBlank String username,
    @NotBlank String password,
    @NotEmpty List<@NotBlank String> failureQueues,
    @NotBlank String errorProperty) {

    /** Redacted: a record's generated toString would print the password. */
    @Override
    public String toString() {
      return "MessageInspector[enabled=" + enabled + ", url=" + url + ", username=" + username
        + ", password=<redacted>, failureQueues=" + failureQueues + ", errorProperty=" + errorProperty + "]";
    }
  }

  /**
   * How much of what was gathered reaches the model. Every cap that bites is recorded in
   * the report's truncation notes, so a reader can tell "not there" from "not shown".
   */
  public record Limits(
    @Min(1) int maxGroups,
    @Min(1) int samplesPerGroup,
    @Min(20) int maxTextChars,
    @Min(0) int maxDetailFetches,
    @Min(1) @Max(100) int pageSize,
    @Min(1000) int promptBudgetChars,
    @Min(1) int maxParallelCalls) {
  }

  public record Chat(
    @Min(0) int maxHistoryTurns,
    @Min(100) int maxToolOutputChars) {
  }
}
