package com.messaging.triage.triage;

/**
 * How the model call went, separately from what it said, so a caller can tell "the model
 * said nothing useful" from "there was nothing to say" from "the model is down".
 *
 * @param usable     true when {@code answer} can be relied on, or when there was nothing to triage
 * @param model      the model asked
 * @param problem    why the answer is not usable, or null
 * @param note       anything else worth knowing (repaired on the second try; nothing to triage)
 * @param attempts   model calls made: 0, 1 or 2
 * @param durationMs time spent in model calls
 */
public record ModelOutcome(boolean usable, String model, String problem, String note, int attempts, long durationMs) {
}
