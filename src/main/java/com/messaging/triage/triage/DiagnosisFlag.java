package com.messaging.triage.triage;

/**
 * What code found in a diagnosis it kept (ADR 0005). Any flag caps the diagnosis's
 * confidence at LOW, and the summary names the groups that carry it.
 */
public enum DiagnosisFlag {

  /**
   * A field the model wrote advises resending, replaying, deleting, purging, moving or
   * bulk-retrying messages; the next step is put behind a fixed warning.
   */
  RECOMMENDS_STATE_CHANGE("Advice to change message state, which v1 never acts on (confidence capped at LOW): "),

  /** The evidence was missing, or quoted something this group's own data does not hold. */
  UNSUPPORTED("Evidence not in the group's own data (confidence capped at LOW): ");

  private final String summary;

  DiagnosisFlag(String summary) {
    this.summary = summary;
  }

  /** The start of the summary sentence that lists the groups with this flag. */
  String summary() {
    return summary;
  }
}
