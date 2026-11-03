package com.messaging.triage.source.mi;

import io.modelcontextprotocol.client.McpSyncClient;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * One MCP client to message-inspector, connected on first use rather than at startup, so
 * the app boots and the other source still reports while message-inspector is down.
 * <p>
 * {@link #reset()} throws the client away (and runs {@code onReset}, which drops the
 * token); the next {@link #get()} builds a fresh one. Callers reset after a failure, so a
 * restarted server or an expired token costs one failed call, not a stuck client.
 */
@Slf4j
public class McpClientHolder implements AutoCloseable {

  private final Supplier<McpSyncClient> factory;
  private final Runnable onReset;
  private McpSyncClient client;

  public McpClientHolder(Supplier<McpSyncClient> factory, Runnable onReset) {
    this.factory = factory;
    this.onReset = onReset;
  }

  public synchronized McpSyncClient get() {
    if (client == null) {
      McpSyncClient fresh = factory.get();
      try {
        fresh.initialize();
      } catch (RuntimeException e) {
        closeQuietly(fresh);
        onReset.run();
        throw e;
      }
      client = fresh;
      log.debug("message-inspector MCP client connected");
    }
    return client;
  }

  public synchronized void reset() {
    if (client != null) {
      closeQuietly(client);
      client = null;
    }
    onReset.run();
  }

  @Override
  public synchronized void close() {
    if (client != null) {
      closeQuietly(client);
      client = null;
    }
  }

  private static void closeQuietly(McpSyncClient mcp) {
    try {
      mcp.close();
    } catch (RuntimeException e) {
      log.debug("closing the MCP client failed: {}", e.getClass().getSimpleName());
    }
  }
}
