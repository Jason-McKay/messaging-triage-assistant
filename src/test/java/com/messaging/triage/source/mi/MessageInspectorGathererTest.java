package com.messaging.triage.source.mi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaging.triage.source.Failure;
import com.messaging.triage.source.FailureSource.Gathered;
import com.messaging.triage.source.SourceUnavailableException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MessageInspectorGathererTest {

  private static final String ORDERS_DLQ = "{\"_AMQ_ORIG_QUEUE\":\"orders\",\"errorReason\":\"Invalid quantity -3\"}";

  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private final McpStubs mcp = new McpStubs();

  @Test
  void eachDeadLetterBecomesAFailureByQueueOriginAndRecordedReason() {
    mcp.on("list_queues", "[\"DLQ\",\"ExpiryQueue\",\"orders\",\"payments\"]")
      .on("browse_messages", arguments -> McpStubs.text("DLQ".equals(arguments.get("queueName"))
        ? McpStubs.page("DLQ", 3, false,
          McpStubs.message("ID:1", "1762164000000", ORDERS_DLQ, "{\"orderId\":\"o-1\",\"qty\":-3}", false),
          McpStubs.message("ID:2", "0", "{\"_AMQ_ORIG_ADDRESS\":\"payments\"}", "<payment/>", false),
          McpStubs.message("ID:3", "not a time", "{}", "{\"orderId\":\"o-3\",\"qty\":", true))
        : McpStubs.page("ExpiryQueue", 0, false)));

    Gathered gathered = gatherer().gather();

    assertThat(gathered.counts()).containsEntry("DLQ.messages", 3L).containsEntry("ExpiryQueue.messages", 0L);
    assertThat(gathered.notes()).isEmpty();
    List<Failure> failures = gathered.failures();
    assertThat(failures).hasSize(3);

    Failure orders = failures.get(0);
    assertThat(orders.dimensions()).containsExactly(
      java.util.Map.entry("queue", "DLQ"), java.util.Map.entry("origin", "orders"));
    assertThat(orders.error()).isEqualTo("Invalid quantity -3");
    assertThat(orders.reference()).isEqualTo("ID:1");
    assertThat(orders.at()).isEqualTo(Instant.ofEpochMilli(1762164000000L));
    assertThat(orders.payloadKeys()).containsExactly("orderId", "qty");
    assertThat(orders.attributes()).containsEntry("payloadLength", "26").doesNotContainKey("payloadTruncated");

    Failure payments = failures.get(1);
    assertThat(payments.dimensions()).containsEntry("origin", "payments");
    assertThat(payments.error()).isEqualTo(MessageInspectorGatherer.NO_RECORDED_REASON);
    assertThat(payments.at()).isNull();
    assertThat(payments.payloadKeys()).isEmpty();

    Failure cutOff = failures.get(2);
    assertThat(cutOff.dimensions()).containsEntry("origin", MessageInspectorGatherer.UNKNOWN_ORIGIN);
    assertThat(cutOff.at()).isNull();
    assertThat(cutOff.payloadKeys()).as("a payload cut off mid-object has no readable keys").isEmpty();
    assertThat(cutOff.attributes()).containsEntry("payloadTruncated", "true");
  }

  @Test
  void aMissingFailureQueueAndAPartialPageAreNoted() {
    mcp.on("list_queues", "[\"DLQ\"]")
      .on("browse_messages", McpStubs.page("DLQ", 250, true,
        McpStubs.message("ID:1", "1762164000000", ORDERS_DLQ, "{}", false)));

    Gathered gathered = gatherer().gather();

    assertThat(gathered.failures()).hasSize(1);
    assertThat(gathered.counts()).containsEntry("DLQ.messages", 250L);
    assertThat(gathered.notes()).containsExactly(
      "MI: no queue named ExpiryQueue on the broker",
      "MI: 250 messages on DLQ, the first 1 read");
  }

  @Test
  void anUnreadableQueueIsSkippedAndNoted() {
    mcp.on("list_queues", "[\"DLQ\",\"ExpiryQueue\"]")
      .on("browse_messages", arguments -> "DLQ".equals(arguments.get("queueName"))
        ? McpStubs.error("Unreadable queue")
        : McpStubs.text(McpStubs.page("ExpiryQueue", 1, false,
          McpStubs.message("ID:9", "1762164000000", "{}", "{}", false))));

    Gathered gathered = gatherer().gather();

    assertThat(gathered.failures()).extracting(Failure::reference).containsExactly("ID:9");
    assertThat(gathered.notes()).containsExactly(
      "MI: could not read DLQ (message-inspector browse_messages: Unreadable queue)");
  }

  @Test
  void noReadableFailureQueueMeansTheSourceIsUnavailable() {
    mcp.on("list_queues", "[\"DLQ\",\"ExpiryQueue\"]")
      .on("browse_messages", arguments -> McpStubs.error("Broker unavailable"));

    assertThatThrownBy(() -> gatherer().gather())
      .isInstanceOf(SourceUnavailableException.class)
      .hasMessageStartingWith("no failure queue could be read: DLQ (");
  }

  @Test
  void aBrokerThatCannotListQueuesIsUnavailable() {
    mcp.on("list_queues", arguments -> McpStubs.error("Broker unavailable"));

    assertThatThrownBy(() -> gatherer().gather())
      .isInstanceOf(SourceUnavailableException.class)
      .hasMessage("message-inspector list_queues: Broker unavailable");
  }

  @Test
  void epochMillisIsReadLeniently() {
    assertThat(MessageInspectorGatherer.epochMillis("1762164000000")).isEqualTo(Instant.ofEpochMilli(1762164000000L));
    assertThat(MessageInspectorGatherer.epochMillis(" 5 ")).isEqualTo(Instant.ofEpochMilli(5));
    assertThat(MessageInspectorGatherer.epochMillis("0")).isNull();
    assertThat(MessageInspectorGatherer.epochMillis("2026-11-03")).isNull();
    assertThat(MessageInspectorGatherer.epochMillis(null)).isNull();
  }

  private MessageInspectorGatherer gatherer() {
    return new MessageInspectorGatherer(new McpMessageInspectorClient(mcp.holder(), jsonMapper),
      List.of("DLQ", "ExpiryQueue"), "errorReason", 100, jsonMapper);
  }
}
