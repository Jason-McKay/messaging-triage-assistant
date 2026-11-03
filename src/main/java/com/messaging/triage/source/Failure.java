package com.messaging.triage.source;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * One failed message or notification, as a source gathered it and before any grouping.
 * Everything in it except the map keys came from the systems being triaged, so all of it is
 * untrusted text by the time it reaches a prompt.
 *
 * @param dimensions  what it is grouped by besides the error, in a stable order (channel;
 *                    or queue and origin)
 * @param error       the recorded reason, raw
 * @param reference   the event id or message id, so a human can find it
 * @param at          when it last changed, or null if unknown
 * @param payload     a payload excerpt, or null if the source does not expose payloads
 * @param payloadKeys the payload's top-level JSON keys; empty when it is not a JSON object
 * @param attributes  other fixed fields worth showing (template key, attempt count)
 */
public record Failure(
  Map<String, String> dimensions,
  String error,
  String reference,
  Instant at,
  String payload,
  Set<String> payloadKeys,
  Map<String, String> attributes) {
}
