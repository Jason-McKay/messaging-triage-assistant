package com.messaging.triage.source.mi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** A mocked MCP client to message-inspector that answers each tool from a function of its arguments. */
final class McpStubs {

  private final Map<String, Function<Map<String, Object>, McpSchema.CallToolResult>> tools = new HashMap<>();
  final McpSyncClient client = mock(McpSyncClient.class);

  McpStubs() {
    when(client.getClientInfo()).thenReturn(McpSchema.Implementation.builder("test", "0").build());
    when(client.callTool(any())).thenAnswer(invocation -> {
      McpSchema.CallToolRequest request = invocation.getArgument(0);
      Function<Map<String, Object>, McpSchema.CallToolResult> tool = tools.get(request.name());
      if (tool == null) {
        throw new IllegalStateException("no stub for tool " + request.name());
      }
      return tool.apply(request.arguments());
    });
  }

  McpStubs on(String tool, Function<Map<String, Object>, McpSchema.CallToolResult> answer) {
    tools.put(tool, answer);
    return this;
  }

  McpStubs on(String tool, String text) {
    return on(tool, arguments -> text(text));
  }

  McpClientHolder holder() {
    return new McpClientHolder(() -> client, () -> { });
  }

  static McpSchema.CallToolResult text(String text) {
    return McpSchema.CallToolResult.builder().addTextContent(text).isError(false).build();
  }

  static McpSchema.CallToolResult error(String text) {
    return McpSchema.CallToolResult.builder().addTextContent(text).isError(true).build();
  }

  static String message(String id, String timestamp, String properties, String payload, boolean truncated) {
    return "{\"messageId\":\"" + id + "\",\"queueName\":\"DLQ\",\"timestamp\":\"" + timestamp + "\","
      + "\"properties\":" + properties + ",\"payload\":" + quote(payload) + ",\"payloadLength\":"
      + payload.length() + ",\"payloadTruncated\":" + truncated + "}";
  }

  static String page(String queue, int total, boolean hasMore, String... messages) {
    return "{\"queueName\":\"" + queue + "\",\"offset\":0,\"limit\":100,\"totalMessages\":" + total
      + ",\"hasMore\":" + hasMore + ",\"messages\":[" + String.join(",", messages) + "]}";
  }

  private static String quote(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
