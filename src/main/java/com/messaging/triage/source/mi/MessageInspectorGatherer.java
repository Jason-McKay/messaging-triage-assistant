package com.messaging.triage.source.mi;

import com.messaging.triage.source.Failure;
import com.messaging.triage.source.FailureSource;
import com.messaging.triage.source.SourceUnavailableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Failures from message-inspector: the messages sitting on the failure queues (by default
 * {@code DLQ} and {@code ExpiryQueue}), browsed without consuming them.
 * <p>
 * Grouped by queue, by where the message was first sent ({@code _AMQ_ORIG_QUEUE}, which
 * Artemis sets when it moves a message, else {@code _AMQ_ORIG_ADDRESS}), and by the error
 * property the producer or consumer recorded. Artemis itself records no reason for a
 * dead-letter, so without that property every message falls into "no recorded reason".
 * <p>
 * A queue that cannot be read (it holds a non-text message, say) is noted and skipped; the
 * source is UNAVAILABLE only when the broker cannot be read at all.
 */
public class MessageInspectorGatherer implements FailureSource {

  static final String ORIG_QUEUE = "_AMQ_ORIG_QUEUE";
  static final String ORIG_ADDRESS = "_AMQ_ORIG_ADDRESS";
  static final String UNKNOWN_ORIGIN = "unknown origin";
  static final String NO_RECORDED_REASON = "no recorded reason";

  private final MessageInspectorClient client;
  private final List<String> failureQueues;
  private final String errorProperty;
  private final int pageSize;
  private final JsonMapper jsonMapper;

  public MessageInspectorGatherer(MessageInspectorClient client, List<String> failureQueues, String errorProperty,
    int pageSize, JsonMapper jsonMapper) {
    this.client = client;
    this.failureQueues = List.copyOf(failureQueues);
    this.errorProperty = errorProperty;
    this.pageSize = pageSize;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public String id() {
    return "MI";
  }

  @Override
  public String name() {
    return "message-inspector";
  }

  @Override
  public Gathered gather() {
    Map<String, Long> counts = new LinkedHashMap<>();
    List<String> notes = new ArrayList<>();
    List<Failure> failures = new ArrayList<>();

    List<String> present = client.listQueues();
    List<String> missing = failureQueues.stream().filter(queue -> !present.contains(queue)).toList();
    if (!missing.isEmpty()) {
      notes.add(id() + ": no queue named " + String.join(", ", missing) + " on the broker");
    }

    int attempted = 0;
    List<String> unreadable = new ArrayList<>();
    for (String queue : failureQueues) {
      if (!present.contains(queue)) {
        continue;
      }
      attempted++;
      MessageInspectorClient.Page page;
      try {
        page = client.browse(queue, pageSize);
      } catch (SourceUnavailableException e) {
        unreadable.add(queue + " (" + e.getMessage() + ")");
        continue;
      }
      List<MessageInspectorClient.Message> messages = page.messages() == null ? List.of() : page.messages();
      counts.put(queue + ".messages", (long) page.totalMessages());
      if (page.hasMore()) {
        notes.add(id() + ": " + page.totalMessages() + " messages on " + queue + ", the first " + messages.size()
          + " read");
      }
      for (MessageInspectorClient.Message message : messages) {
        failures.add(toFailure(queue, message));
      }
    }
    if (attempted > 0 && unreadable.size() == attempted) {
      throw new SourceUnavailableException("no failure queue could be read: " + String.join("; ", unreadable));
    }
    unreadable.forEach(queue -> notes.add(id() + ": could not read " + queue));
    return new Gathered(failures, counts, notes);
  }

  private Failure toFailure(String queue, MessageInspectorClient.Message message) {
    Map<String, String> properties = message.properties() == null ? Map.of() : message.properties();
    Map<String, String> dimensions = new LinkedHashMap<>();
    dimensions.put("queue", queue);
    dimensions.put("origin", firstPresent(properties.get(ORIG_QUEUE), properties.get(ORIG_ADDRESS), UNKNOWN_ORIGIN));

    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("payloadLength", String.valueOf(message.payloadLength()));
    if (message.payloadTruncated()) {
      attributes.put("payloadTruncated", "true");
    }
    return new Failure(
      dimensions,
      firstPresent(properties.get(errorProperty), null, NO_RECORDED_REASON),
      message.messageId(),
      epochMillis(message.timestamp()),
      message.payload(),
      topLevelKeys(message.payload()),
      attributes);
  }

  /** The top-level keys of a JSON object payload; empty for anything else, or a cut-off one. */
  Set<String> topLevelKeys(String payload) {
    if (payload == null || !payload.stripLeading().startsWith("{")) {
      return Set.of();
    }
    try {
      JsonNode node = jsonMapper.readTree(payload);
      if (!node.isObject()) {
        return Set.of();
      }
      Set<String> keys = new LinkedHashSet<>();
      node.properties().forEach(entry -> keys.add(entry.getKey()));
      return keys;
    } catch (JacksonException e) {
      return Set.of();
    }
  }

  static Instant epochMillis(String timestamp) {
    if (timestamp == null || timestamp.isBlank()) {
      return null;
    }
    try {
      long millis = Long.parseLong(timestamp.trim());
      return millis <= 0 ? null : Instant.ofEpochMilli(millis);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String firstPresent(String first, String second, String fallback) {
    if (first != null && !first.isBlank()) {
      return first;
    }
    if (second != null && !second.isBlank()) {
      return second;
    }
    return fallback;
  }
}
