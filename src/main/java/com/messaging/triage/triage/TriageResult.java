package com.messaging.triage.triage;

import com.messaging.triage.report.FailureReport;
import java.time.Instant;

/**
 * One triage run: the facts gathered, and what the model made of them. The report stands on
 * its own, so a run is useful even when {@code answer} is null.
 *
 * @param answer   the model's answer, or null when there was none to use
 * @param gatherMs time spent reading the sources
 */
public record TriageResult(
  String id,
  Instant createdAt,
  FailureReport report,
  TriageAnswer answer,
  ModelOutcome model,
  long gatherMs) {
}
