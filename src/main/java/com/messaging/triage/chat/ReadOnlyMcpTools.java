package com.messaging.triage.chat;

import com.messaging.triage.source.mi.McpClientHolder;
import com.messaging.triage.source.mi.MessageInspectorClient;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;

/**
 * message-inspector's MCP tools for chat, cut down to the read-only allowlist (ADR 0002).
 * <p>
 * Two walls in this class: the provider's filter drops every tool not on the allowlist
 * before a callback is built for it, and the result is checked again by name, failing
 * closed: a write tool slipping past the filter (a rename, a prefix, a provider bug) stops
 * the chat rather than reaching the model. The third wall is the server: the account is a
 * USER, and the server refuses its write tools to a USER.
 */
@Slf4j
public class ReadOnlyMcpTools {

  public static final McpToolFilter READ_ONLY =
    (connection, tool) -> MessageInspectorClient.READ_ONLY_TOOLS.contains(tool.name());

  private final McpClientHolder holder;

  public ReadOnlyMcpTools(McpClientHolder holder) {
    this.holder = holder;
  }

  /** The read tools' callbacks, reconnecting once if the first listing fails. */
  public List<ToolCallback> callbacks() {
    try {
      return readOnly(holder.get());
    } catch (UnsafeToolException e) {
      throw e;
    } catch (RuntimeException first) {
      log.info("listing message-inspector tools failed ({}); reconnecting and trying once more",
        first.getClass().getSimpleName());
      holder.reset();
      return readOnly(holder.get());
    }
  }

  /** Thrown, never caught here, when a tool outside the allowlist got through the filter. */
  public static class UnsafeToolException extends IllegalStateException {

    UnsafeToolException(String toolName) {
      super("tool outside the read-only allowlist reached the chat: " + toolName);
    }
  }

  static List<ToolCallback> readOnly(McpSyncClient client) {
    ToolCallback[] callbacks = SyncMcpToolCallbackProvider.builder()
      .mcpClients(List.of(client))
      .toolFilter(READ_ONLY)
      .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
      .build()
      .getToolCallbacks();
    for (ToolCallback callback : callbacks) {
      requireReadOnly(callback.getToolDefinition().name());
      if (callback instanceof SyncMcpToolCallback mcp) {
        requireReadOnly(mcp.getOriginalToolName());
      }
    }
    return Arrays.asList(callbacks);
  }

  private static void requireReadOnly(String name) {
    if (!MessageInspectorClient.READ_ONLY_TOOLS.contains(name)) {
      throw new UnsafeToolException(name);
    }
  }
}
