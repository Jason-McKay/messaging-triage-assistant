package com.messaging.triage.triage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A model that answers each triage call from a script kept per group id, read from the
 * group's prompt. Calls run in parallel, so consecutive mock stubs would hand answers out in
 * whatever order the threads arrive; this keeps each group's answers its own. Every prompt is
 * recorded. Running out of script is an {@link AssertionError}, not an exception the triager
 * would take for an unavailable model.
 */
public class ScriptedChatModel implements ChatModel {

  private static final Pattern GROUP = Pattern.compile("^-- Group (\\S+):", Pattern.MULTILINE);

  private final Map<String, Deque<Supplier<ChatResponse>>> scripts = new ConcurrentHashMap<>();
  private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

  /** {@code groupId} answers these texts, one per call; null is a response with no output. */
  public ScriptedChatModel answer(String groupId, String... answers) {
    for (String answer : answers) {
      script(groupId).add(() -> answer == null ? new ChatResponse(List.of()) : response(answer));
    }
    return this;
  }

  /** {@code groupId}'s next call throws {@code failure}. */
  public ScriptedChatModel fail(String groupId, RuntimeException failure) {
    script(groupId).add(() -> {
      throw failure;
    });
    return this;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    prompts.add(prompt);
    String groupId = groupOf(prompt);
    Supplier<ChatResponse> next;
    synchronized (this) {
      Deque<Supplier<ChatResponse>> script = scripts.get(groupId);
      next = script == null ? null : script.poll();
    }
    if (next == null) {
      throw new AssertionError("no scripted answer left for " + groupId);
    }
    return next.get();
  }

  public List<Prompt> prompts() {
    return List.copyOf(prompts);
  }

  /** The prompts sent for one group, in the order they were sent. */
  public List<Prompt> prompts(String groupId) {
    return prompts.stream().filter(prompt -> groupId.equals(groupOf(prompt))).toList();
  }

  /** The group id in the first user message, which is the group's own prompt. */
  public static String groupOf(Prompt prompt) {
    for (Message message : prompt.getInstructions()) {
      if (message.getMessageType() == MessageType.USER) {
        Matcher matcher = GROUP.matcher(message.getText());
        return matcher.find() ? matcher.group(1) : "none";
      }
    }
    return "none";
  }

  public static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private synchronized Deque<Supplier<ChatResponse>> script(String groupId) {
    return scripts.computeIfAbsent(groupId, id -> new ArrayDeque<>());
  }
}
