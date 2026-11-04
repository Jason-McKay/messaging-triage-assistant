package com.messaging.triage.chat;

import com.messaging.triage.support.Problems;
import com.messaging.triage.triage.ModelView;
import com.messaging.triage.triage.Untrusted;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Wraps a chat tool so that:
 * <ul>
 *   <li>every call is recorded by name, for the reply's {@code toolsCalled};</li>
 *   <li>its output reaches the model fenced as untrusted and capped (ADR 0003), with the prose
 *       in message payloads abstracted ({@link ModelView#toolOutput}, ADR 0006);</li>
 *   <li>a failure becomes text the model can read ("the tool failed: ...") rather than an
 *       exception that ends the chat;</li>
 *   <li>a model stuck in a loop is stopped after {@link Calls#limit()} calls.</li>
 * </ul>
 * Inputs and outputs are not logged: they carry payloads.
 */
public class RecordingToolCallback implements ToolCallback {

  static final String LIMIT_REACHED = "Tool call limit reached for this question. Answer with what you have.";
  static final int MAX_ERROR_CHARS = 300;

  private final ToolCallback delegate;
  private final Calls calls;
  private final int maxOutputChars;
  private final Runnable onFailure;

  public RecordingToolCallback(ToolCallback delegate, Calls calls, int maxOutputChars, Runnable onFailure) {
    this.delegate = delegate;
    this.calls = calls;
    this.maxOutputChars = maxOutputChars;
    this.onFailure = onFailure;
  }

  /**
   * The tool calls of one chat request. Past the limit a call is refused with a message;
   * a model that keeps calling anyway is stopped outright at twice the limit.
   * {@link ChatService} also caps its rounds, for a model asking for names that are not tools.
   */
  public static class Calls {

    private final List<String> names = new ArrayList<>();
    private final int limit;
    private int refused;

    public Calls(int limit) {
      this.limit = limit;
    }

    synchronized boolean tryRecord(String name) {
      if (names.size() < limit) {
        names.add(name);
        return true;
      }
      refused++;
      if (refused >= limit) {
        throw new ToolLoopException(limit);
      }
      return false;
    }

    public synchronized List<String> names() {
      return List.copyOf(names);
    }

    public int limit() {
      return limit;
    }
  }

  /** Ends a chat whose model would not stop calling tools. Not caught by {@link #call}. */
  public static class ToolLoopException extends RuntimeException {

    ToolLoopException(int limit) {
      super("the model kept calling tools after the limit of " + limit);
    }
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  @Override
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  @Override
  public String call(String toolInput) {
    return call(toolInput, null);
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    if (!calls.tryRecord(getToolDefinition().name())) {
      return LIMIT_REACHED;
    }
    try {
      String output = toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
      return Untrusted.wrap("tool-result", ModelView.toolOutput(output), maxOutputChars);
    } catch (RuntimeException e) {
      onFailure.run();
      return "The tool failed: " + Untrusted.wrap("tool-error", Problems.describe(e), MAX_ERROR_CHARS);
    }
  }
}
