package com.messaging.triage.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.messaging.triage.report.FailureGroup;
import com.messaging.triage.report.FailureReport;
import com.messaging.triage.source.SourceSection;
import com.messaging.triage.source.mi.McpClientHolder;
import com.messaging.triage.source.np.NotificationWorkerClient;
import com.messaging.triage.triage.AnswerValidator;
import com.messaging.triage.triage.LatestTriageHolder;
import com.messaging.triage.triage.Triager;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** ADR 0002: whatever message-inspector advertises, the model is only ever shown read tools. */
class ReadOnlyToolsTest {

  private static final List<String> READ_TOOLS = List.of("list_queues", "browse_messages", "get_message");
  private static final List<String> WRITE_TOOLS = List.of("resend_message", "delete_message", "move_message_to_dlq");

  @Test
  void sixAdvertisedToolsGiveTheThreeReads() {
    List<ToolCallback> callbacks = ReadOnlyMcpTools.readOnly(advertisingAllSix());

    assertThat(callbacks).extracting(callback -> callback.getToolDefinition().name())
      .containsExactlyInAnyOrderElementsOf(READ_TOOLS);
  }

  @Test
  void theChatModelSeesNoWriteTool() {
    ChatModel chatModel = answering("Two messages are on DLQ.");
    ChatService chat = chatService(chatModel, provider(ReadOnlyMcpTools.class,
      new ReadOnlyMcpTools(new McpClientHolder(ReadOnlyToolsTest::advertisingAllSix, () -> { }))));

    ChatReply reply = chat.chat(new ChatRequest("What is on the DLQ?", List.of()));

    assertThat(reply.answer()).isEqualTo("Two messages are on DLQ.");
    List<ToolCallback> shown = toolsShown(chatModel);
    assertThat(shown).extracting(callback -> callback.getToolDefinition().name())
      .containsExactlyInAnyOrder("notification_stats", "list_dead_lettered_notifications", "get_notification",
        "list_queues", "browse_messages", "get_message")
      .doesNotContainAnyElementsOf(WRITE_TOOLS);
    assertThat(shown).allMatch(RecordingToolCallback.class::isInstance);
  }

  @Test
  void aSourceThatCannotListItsToolsIsLeftOutAndTheModelIsTold() {
    ChatModel chatModel = answering("message-inspector is down.");
    McpSyncClient down = mock(McpSyncClient.class);
    when(down.initialize()).thenThrow(new IllegalStateException("Connection refused"));
    ChatService chat = chatService(chatModel, provider(ReadOnlyMcpTools.class,
      new ReadOnlyMcpTools(new McpClientHolder(() -> down, () -> { }))));

    chat.chat(new ChatRequest("What is on the DLQ?", List.of()));

    Prompt prompt = onlyPrompt(chatModel);
    assertThat(((OllamaChatOptions) prompt.getOptions()).getToolCallbacks())
      .extracting(callback -> callback.getToolDefinition().name())
      .containsExactlyInAnyOrder("notification_stats", "list_dead_lettered_notifications", "get_notification");
    assertThat(prompt.getInstructions().getFirst().getText())
      .contains("Not available for this question: message-inspector cannot be reached right now");
  }

  @Test
  void aWriteToolThatGetsPastTheFilterStopsTheChat() {
    ReadOnlyMcpTools leaky = mock(ReadOnlyMcpTools.class);
    when(leaky.callbacks()).thenThrow(new ReadOnlyMcpTools.UnsafeToolException("delete_message"));
    ChatModel chatModel = answering("unreachable");
    ChatService chat = chatService(chatModel, provider(ReadOnlyMcpTools.class, leaky));

    assertThatThrownBy(() -> chat.chat(new ChatRequest("Delete everything", List.of())))
      .isInstanceOf(ReadOnlyMcpTools.UnsafeToolException.class);
    org.mockito.Mockito.verifyNoInteractions(chatModel);
  }

  @Test
  void theTriagePromptCarriesNoTools() {
    ChatModel chatModel = answering("{}");
    Triager triager = new Triager(chatModel, "qwen3:8b",
      new AnswerValidator(Validation.buildDefaultValidatorFactory().getValidator()), 300, 2);
    FailureGroup group = new FailureGroup("MI-1", Map.of("queue", "DLQ"), "bad", 1, null, null, false, List.of());
    FailureReport report = new FailureReport(Instant.EPOCH, List.of(new SourceSection("message-inspector", "MI",
      SourceSection.Status.OK, null, Map.of(), 1, List.of(group))), List.of());

    triager.triage(report);

    ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel, org.mockito.Mockito.atLeastOnce()).call(prompts.capture());
    assertThat(prompts.getAllValues()).allSatisfy(prompt ->
      assertThat(((OllamaChatOptions) prompt.getOptions()).getToolCallbacks()).isNullOrEmpty());
  }

  private static ChatService chatService(ChatModel chatModel, ObjectProvider<ReadOnlyMcpTools> mcpTools) {
    NotificationReadTools notifications = new NotificationReadTools(mock(NotificationWorkerClient.class), 100);
    return new ChatService(chatModel, "qwen3:8b", provider(NotificationReadTools.class, notifications), mcpTools,
      new StaticListableBeanFactory().getBeanProvider(McpClientHolder.class), new LatestTriageHolder(), 10, 8000);
  }

  private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
    return new StaticListableBeanFactory(Map.of("bean", bean)).getBeanProvider(type);
  }

  /** message-inspector's real tool list: three reads and three writes. */
  static McpSyncClient advertisingAllSix() {
    McpSyncClient client = mock(McpSyncClient.class);
    List<McpSchema.Tool> tools = java.util.stream.Stream.concat(READ_TOOLS.stream(), WRITE_TOOLS.stream())
      .map(name -> McpSchema.Tool.builder()
        .name(name)
        .description(name + " on a queue")
        .inputSchema(Map.of("type", "object", "properties", Map.of()))
        .build())
      .toList();
    when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(tools, null));
    when(client.getClientInfo()).thenReturn(McpSchema.Implementation.builder("test", "0").build());
    when(client.getClientCapabilities()).thenReturn(McpSchema.ClientCapabilities.builder().build());
    return client;
  }

  private static ChatModel answering(String text) {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class)))
      .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    return chatModel;
  }

  private static Prompt onlyPrompt(ChatModel chatModel) {
    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    return prompt.getValue();
  }

  private static List<ToolCallback> toolsShown(ChatModel chatModel) {
    return ((OllamaChatOptions) onlyPrompt(chatModel).getOptions()).getToolCallbacks();
  }
}
