package com.messaging.triage.chat;

import java.util.List;

/**
 * @param toolsCalled every tool call the model made, in order, by name; all of them reads
 */
public record ChatReply(String answer, List<String> toolsCalled, String model, long durationMs) {
}
