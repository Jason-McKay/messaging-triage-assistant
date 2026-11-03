package com.messaging.triage.report;

import java.time.Instant;
import java.util.Map;

/** One example from a group, with its error and payload cut to the configured length. */
public record Sample(
  String reference,
  Instant at,
  String error,
  String payload,
  Map<String, String> attributes) {

  /** Characters this sample adds to a prompt, near enough to budget with. */
  int size() {
    int size = length(reference) + length(error) + length(payload);
    for (Map.Entry<String, String> attribute : attributes.entrySet()) {
      size += length(attribute.getKey()) + length(attribute.getValue());
    }
    return size;
  }

  private static int length(String text) {
    return text == null ? 0 : text.length();
  }
}
