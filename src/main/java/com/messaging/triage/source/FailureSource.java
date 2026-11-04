package com.messaging.triage.source;

import java.util.List;
import java.util.Map;

/**
 * A system failures are gathered from. Gathering is deterministic and model-free: what a
 * source returns is fixed by the system's state, never by a model's choice of tool call
 * (ADR 0001). This is the seam a later source plugs in at.
 * <p>
 * {@link #gather()} throws when the system cannot be read; the report then marks the
 * section UNAVAILABLE with the reason, and the other sources still report.
 */
public interface FailureSource {

  /** Short prefix for this source's group ids: {@code NP}, {@code MI}. */
  String id();

  /** The system's name as people say it: {@code notification-platform}. */
  String name();

  Gathered gather();

  /**
   * @param failures what was found, before grouping
   * @param counts   context numbers from the system itself (totals by status, queue depths)
   * @param notes    what this source could not fetch in full, in plain words
   */
  record Gathered(List<Failure> failures, Map<String, Long> counts, List<String> notes) {
  }
}
