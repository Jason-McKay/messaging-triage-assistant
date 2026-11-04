package com.messaging.triage.source.mi;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * message-inspector's read tools, typed. The only tool names this app ever calls or shows a
 * model are {@link #READ_ONLY_TOOLS}; the server's write tools (resend, delete, move to
 * DLQ) are filtered out by name before anything sees them (ADR 0002).
 */
public interface MessageInspectorClient {

  Set<String> READ_ONLY_TOOLS = Set.of("list_queues", "browse_messages", "get_message");

  List<String> listQueues();

  /** The first page of a queue, without consuming anything. The server caps limit at 100. */
  Page browse(String queueName, int limit);

  Optional<Message> get(String queueName, String messageId);

  /** A message as the {@code browse_messages} and {@code get_message} tools return it. */
  record Message(String messageId, String queueName, String timestamp, Map<String, String> properties,
    String payload, int payloadLength, boolean payloadTruncated) {
  }

  record Page(String queueName, int offset, int limit, int totalMessages, boolean hasMore, List<Message> messages) {
  }
}
