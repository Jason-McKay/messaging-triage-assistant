package com.messaging.triage.chat;

import java.util.List;

/**
 * @param toolsCalled every tool call the model made, in order, by name; all of them reads
 * @param flags       what code found in the answer: RECOMMENDS_STATE_CHANGE when it advises a
 *                    change to message state, which also puts the fixed warning at its end (ADR 0005)
 */
public record ChatReply(String answer, List<String> toolsCalled, String model, long durationMs, List<String> flags) {

  public ChatReply(String answer, List<String> toolsCalled, String model, long durationMs) {
    this(answer, toolsCalled, model, durationMs, List.of());
  }
}
