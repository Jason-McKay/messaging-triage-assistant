package com.messaging.triage.triage;

import java.util.List;

/**
 * How the model calls went, separately from what they said, so a caller can tell "the model
 * said nothing useful" from "there was nothing to say" from "the model is down".
 *
 * @param usable     true when {@code answer} can be relied on, or when there was nothing to triage
 * @param model      the model asked
 * @param problem    why the answer is not usable, or null
 * @param note       anything else worth knowing (groups repaired or left out; nothing to triage)
 * @param attempts   model calls made in all groups together
 * @param durationMs time from the first call starting to the last one ending
 * @param groups     each group's calls, in report order; empty when nothing was triaged
 */
public record ModelOutcome(boolean usable, String model, String problem, String note, int attempts, long durationMs,
  List<GroupOutcome> groups) {

  public ModelOutcome(boolean usable, String model, String problem, String note, int attempts, long durationMs) {
    this(usable, model, problem, note, attempts, durationMs, List.of());
  }
}
