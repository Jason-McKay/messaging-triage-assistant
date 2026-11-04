package com.messaging.triage.triage;

/**
 * How the model calls for one group went.
 *
 * @param attempts   calls made for this group: 0 when skipped, 1, or 2 with the repair
 * @param durationMs time spent in this group's calls, not counting the wait for a free slot
 * @param problem    what was wrong: with the first answer when REPAIRED, with the last when
 *                   UNUSABLE; why the group was not asked when SKIPPED; null when OK
 */
public record GroupOutcome(String groupId, Status status, int attempts, long durationMs, String problem) {

  public enum Status {
    /** The first answer was used. */
    OK,
    /** The repair call's answer was used. */
    REPAIRED,
    /** No answer could be used; the group is left out of the diagnoses. */
    UNUSABLE,
    /** Not asked: the model had already failed for another group. */
    SKIPPED
  }
}
