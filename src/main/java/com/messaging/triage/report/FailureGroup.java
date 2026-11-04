package com.messaging.triage.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Failures that share their dimensions and their error once normalized.
 *
 * @param id         stable within a report: {@code NP-1}, {@code MI-3}, numbered by count
 * @param dimensions what the group shares besides the error (channel; or queue and origin)
 * @param error      the normalized error, ids and numbers replaced by placeholders
 * @param merged     true for the one group that holds everything past the group limit
 */
public record FailureGroup(
  String id,
  Map<String, String> dimensions,
  String error,
  int count,
  Instant firstSeen,
  Instant lastSeen,
  boolean merged,
  List<Sample> samples) {

  public FailureGroup withSamples(List<Sample> replaced) {
    return new FailureGroup(id, dimensions, error, count, firstSeen, lastSeen, merged, replaced);
  }
}
