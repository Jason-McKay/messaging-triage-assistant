package com.messaging.triage.source.mi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.messaging.triage.source.SourceUnavailableException;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class McpMessageInspectorClientTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private final McpStubs mcp = new McpStubs();

  @Test
  void listsQueues() {
    mcp.on("list_queues", "[\"DLQ\",\"ExpiryQueue\",\"orders\"]");

    assertThat(client().listQueues()).containsExactly("DLQ", "ExpiryQueue", "orders");
  }

  @Test
  void browsesTheFirstPageWithoutConsuming() {
    mcp.on("browse_messages", arguments -> McpStubs.text(McpStubs.page("DLQ", 1, false,
      McpStubs.message("ID:1", "1762164000000", "{\"errorReason\":\"bad qty\"}", "{\"qty\":-1}", false))));

    MessageInspectorClient.Page page = client().browse("DLQ", 100);

    assertThat(page.totalMessages()).isEqualTo(1);
    assertThat(page.messages()).singleElement().satisfies(message -> {
      assertThat(message.messageId()).isEqualTo("ID:1");
      assertThat(message.properties()).containsEntry("errorReason", "bad qty");
      assertThat(message.payload()).isEqualTo("{\"qty\":-1}");
    });
    assertThat(lastRequest().arguments()).isEqualTo(Map.of("queueName", "DLQ", "limit", 100, "offset", 0));
  }

  @Test
  void getsOneMessageOrNothing() {
    mcp.on("get_message", arguments -> "ID:1".equals(arguments.get("messageId"))
      ? McpStubs.text(McpStubs.message("ID:1", "1762164000000", "{}", "<order/>", false))
      : McpStubs.text("No message with that ID is on the queue. Call browse_messages for current IDs."));

    assertThat(client().get("DLQ", "ID:1")).hasValueSatisfying(message ->
      assertThat(message.payload()).isEqualTo("<order/>"));
    assertThat(client().get("DLQ", "ID:2")).isEmpty();
  }

  @Test
  void anErrorResultIsAnAnswerNotAFailure() {
    mcp.on("browse_messages", arguments -> McpStubs.error("Unreadable queue: holds a non-text message"));
    AtomicInteger connects = new AtomicInteger();
    McpClientHolder holder = new McpClientHolder(() -> {
      connects.incrementAndGet();
      return mcp.client;
    }, () -> { });

    assertThatThrownBy(() -> new McpMessageInspectorClient(holder, jsonMapper).browse("DLQ", 100))
      .isInstanceOf(SourceUnavailableException.class)
      .hasMessage("message-inspector browse_messages: Unreadable queue: holds a non-text message");
    assertThat(connects).hasValue(1);
    verify(mcp.client, times(1)).callTool(any());
  }

  @Test
  void aFailedCallReconnectsDropsTheTokenAndRetriesOnce() {
    McpSyncClient broken = mock(McpSyncClient.class);
    when(broken.callTool(any())).thenThrow(new RuntimeException("session gone"));
    mcp.on("list_queues", "[\"DLQ\"]");
    AtomicInteger connects = new AtomicInteger();
    AtomicInteger resets = new AtomicInteger();
    McpClientHolder holder = new McpClientHolder(() -> connects.getAndIncrement() == 0 ? broken : mcp.client,
      resets::incrementAndGet);

    List<String> queues = new McpMessageInspectorClient(holder, jsonMapper).listQueues();

    assertThat(queues).containsExactly("DLQ");
    assertThat(connects).hasValue(2);
    assertThat(resets).hasValue(1);
    verify(broken).close();
  }

  @Test
  void aSecondFailureIsThrown() {
    doThrow(new RuntimeException("connection refused")).when(mcp.client).callTool(any());

    assertThatThrownBy(() -> client().listQueues()).hasMessage("connection refused");
    verify(mcp.client, times(2)).callTool(any());
  }

  @Test
  void aResultThatIsNotTheExpectedJsonIsUnavailable() {
    mcp.on("list_queues", "Broker says hello");

    assertThatThrownBy(() -> client().listQueues())
      .isInstanceOf(SourceUnavailableException.class)
      .hasMessageStartingWith("message-inspector returned a result this client cannot read");
  }

  @Test
  void onlyReadToolsAreEverCalled() {
    mcp.on("list_queues", "[\"DLQ\"]")
      .on("browse_messages", McpStubs.page("DLQ", 0, false))
      .on("get_message", "No message with that ID is on the queue.");
    MessageInspectorClient client = client();

    client.listQueues();
    client.browse("DLQ", 10);
    client.get("DLQ", "ID:1");

    ArgumentCaptor<McpSchema.CallToolRequest> requests = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(mcp.client, atLeastOnce()).callTool(requests.capture());
    assertThat(requests.getAllValues()).extracting(McpSchema.CallToolRequest::name)
      .containsExactly("list_queues", "browse_messages", "get_message")
      .allMatch(MessageInspectorClient.READ_ONLY_TOOLS::contains);
  }

  private MessageInspectorClient client() {
    return new McpMessageInspectorClient(mcp.holder(), jsonMapper);
  }

  private McpSchema.CallToolRequest lastRequest() {
    ArgumentCaptor<McpSchema.CallToolRequest> request = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(mcp.client, atLeastOnce()).callTool(request.capture());
    return request.getValue();
  }
}
