package com.messaging.triage.source.mi;

import com.messaging.triage.source.SourceUnavailableException;
import com.messaging.triage.support.Problems;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link MessageInspectorClient} over message-inspector's MCP server. Calls only the three
 * read tools, and refuses any other name before it reaches the wire.
 * <p>
 * A failed call resets the connection (and the token) and is tried once more, which covers
 * an expired token and a restarted server alike. A tool that answers with an error result
 * ("Broker unavailable", "Unknown queue") is not retried: it is an answer, not a failure.
 */
@Slf4j
public class McpMessageInspectorClient implements MessageInspectorClient {

  private final McpClientHolder holder;
  private final JsonMapper jsonMapper;

  public McpMessageInspectorClient(McpClientHolder holder, JsonMapper jsonMapper) {
    this.holder = holder;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public List<String> listQueues() {
    return read(call("list_queues", Map.of()), new TypeReference<>() {});
  }

  @Override
  public Page browse(String queueName, int limit) {
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("queueName", queueName);
    arguments.put("limit", limit);
    arguments.put("offset", 0);
    return read(call("browse_messages", arguments), new TypeReference<>() {});
  }

  @Override
  public Optional<Message> get(String queueName, String messageId) {
    String text = call("get_message", Map.of("queueName", queueName, "messageId", messageId));
    // "No message with that ID is on the queue..." is plain text, not an error result.
    if (!text.stripLeading().startsWith("{")) {
      return Optional.empty();
    }
    return Optional.of(read(text, new TypeReference<>() {}));
  }

  private String call(String tool, Map<String, Object> arguments) {
    if (!READ_ONLY_TOOLS.contains(tool)) {
      throw new IllegalArgumentException("not a read-only tool: " + tool);
    }
    McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(tool).arguments(arguments).build();
    McpSchema.CallToolResult result;
    try {
      result = holder.get().callTool(request);
    } catch (RuntimeException first) {
      log.info("message-inspector {} failed ({}); reconnecting and trying once more", tool,
        first.getClass().getSimpleName());
      holder.reset();
      result = holder.get().callTool(request);
    }
    String text = textOf(result);
    if (Boolean.TRUE.equals(result.isError())) {
      throw new SourceUnavailableException("message-inspector " + tool + ": " + Problems.cap(text, 200));
    }
    return text;
  }

  private <T> T read(String json, TypeReference<T> type) {
    try {
      return jsonMapper.readValue(json, type);
    } catch (JacksonException e) {
      throw new SourceUnavailableException("message-inspector returned a result this client cannot read ("
        + e.getClass().getSimpleName() + ")");
    }
  }

  private static String textOf(McpSchema.CallToolResult result) {
    if (result == null || result.content() == null) {
      return "";
    }
    return result.content().stream()
      .filter(McpSchema.TextContent.class::isInstance)
      .map(content -> ((McpSchema.TextContent) content).text())
      .collect(Collectors.joining());
  }
}
