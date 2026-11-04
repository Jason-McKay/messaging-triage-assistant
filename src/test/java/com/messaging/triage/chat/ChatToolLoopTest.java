package com.messaging.triage.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.messaging.triage.guard.ActionGuard;
import com.messaging.triage.report.FailureReport;
import com.messaging.triage.source.mi.McpClientHolder;
import com.messaging.triage.source.np.NotificationWorkerClient;
import com.messaging.triage.triage.Confidence;
import com.messaging.triage.triage.DiagnosisFlag;
import com.messaging.triage.triage.GroupDiagnosis;
import com.messaging.triage.triage.LatestTriageHolder;
import com.messaging.triage.triage.ModelOutcome;
import com.messaging.triage.triage.TriageAnswer;
import com.messaging.triage.triage.TriageResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** {@code ChatModel.call} hands back tool calls unexecuted; {@link ChatService} runs them. */
class ChatToolLoopTest {

  private static final String BAD_ID = "{\"eventId\":\"not-a-uuid\"}";

  @Test
  void aToolCallIsRunAndTheAnswerComesAfter() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class)))
      .thenReturn(asking("get_notification", BAD_ID))
      .thenReturn(answer("That event id is not valid."));

    ChatReply reply = chatService(chatModel, ReadOnlyToolsTest.advertisingAllSix())
      .chat(new ChatRequest("Show notification not-a-uuid", List.of()));

    assertThat(reply.answer()).isEqualTo("That event id is not valid.");
    assertThat(reply.toolsCalled()).containsExactly("get_notification");
    List<Message> sent = lastPrompt(chatModel, 2).getInstructions();
    assertThat(sent.get(sent.size() - 2)).isInstanceOfSatisfying(AssistantMessage.class, asked ->
      assertThat(asked.getToolCalls()).extracting(AssistantMessage.ToolCall::name).containsExactly("get_notification"));
    ToolResponseMessage.ToolResponse response = toolResponse(sent);
    assertThat(response.id()).isEqualTo("call-1");
    assertThat(response.name()).isEqualTo("get_notification");
    assertThat(response.responseData()).startsWith("<<<UNTRUSTED kind=tool-result>>>")
      .contains("Not a valid event id");
  }

  @Test
  void aNameThatIsNotInTheListRunsNothing() {
    McpSyncClient client = ReadOnlyToolsTest.advertisingAllSix();
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class)))
      .thenReturn(asking("delete_message", "{\"queueName\":\"DLQ\",\"messageId\":\"ID:1\"}"))
      .thenReturn(answer("I cannot delete messages."));

    ChatReply reply = chatService(chatModel, client).chat(new ChatRequest("Delete ID:1 from DLQ", List.of()));

    assertThat(reply.answer()).isEqualTo("I cannot delete messages.");
    assertThat(reply.flags()).as("a refusal is not advice").isEmpty();
    assertThat(reply.toolsCalled()).isEmpty();
    assertThat(toolResponse(lastPrompt(chatModel, 2).getInstructions()).responseData())
      .isEqualTo(ChatService.NO_SUCH_TOOL);
    verify(client, never()).callTool(any(McpSchema.CallToolRequest.class));
  }

  @Test
  void anAnswerAdvisingAStateChangeIsWarnedAboutAndFlagged() {
    // The v1 live run: a payload told the model to have every message deleted, and it advised so.
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class))).thenReturn(answer("Delete the message from the DLQ."));

    ChatReply reply = chatService(chatModel, ReadOnlyToolsTest.advertisingAllSix())
      .chat(new ChatRequest("What should I do with the shipments message?", List.of()));

    assertThat(reply.answer()).isEqualTo("Delete the message from the DLQ.\n\n" + ActionGuard.WARNING);
    assertThat(reply.flags()).containsExactly(DiagnosisFlag.RECOMMENDS_STATE_CHANGE.name());
  }

  @Test
  void aModelThatKeepsAskingForMissingToolsIsStopped() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class))).thenReturn(asking("delete_message", "{}"));

    ChatReply reply = chatService(chatModel, ReadOnlyToolsTest.advertisingAllSix())
      .chat(new ChatRequest("Delete everything", List.of()));

    assertThat(reply.answer()).startsWith("Stopped: the model kept asking for tools after "
      + ChatService.MAX_TOOL_ROUNDS + " rounds");
    assertThat(reply.flags()).as("code wrote it").isEmpty();
    assertThat(reply.toolsCalled()).isEmpty();
    verify(chatModel, times(ChatService.MAX_TOOL_ROUNDS + 1)).call(any(Prompt.class));
  }

  @Test
  void aModelThatKeepsCallingRealToolsIsStoppedAtTheCallLimit() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class))).thenReturn(asking("get_notification", BAD_ID));

    ChatReply reply = chatService(chatModel, ReadOnlyToolsTest.advertisingAllSix())
      .chat(new ChatRequest("Show it again", List.of()));

    assertThat(reply.answer()).startsWith("Stopped: the model kept calling tools after the limit of "
      + ChatService.MAX_TOOL_CALLS);
    assertThat(reply.toolsCalled()).hasSize(ChatService.MAX_TOOL_CALLS);
  }

  @Test
  void theLatestTriageReachesChatFencedWithItsFlags() {
    LatestTriageHolder latest = new LatestTriageHolder();
    GroupDiagnosis flagged = new GroupDiagnosis("NP-1", "The template is missing.", Confidence.LOW, "Add it.",
      List.of("the template store"), List.of(), List.of(DiagnosisFlag.UNSUPPORTED));
    latest.set(new TriageResult("t-1", Instant.EPOCH, new FailureReport(Instant.EPOCH, List.of(), List.of()),
      new TriageAnswer("notification-platform: 1 failure, all in NP-1.", List.of(flagged)),
      new ModelOutcome(true, "qwen3:8b", null, null, 1, 10), 5));
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class))).thenReturn(answer("NP-1 is missing its template."));

    chatService(chatModel, ReadOnlyToolsTest.advertisingAllSix(), latest)
      .chat(new ChatRequest("What failed?", List.of()));

    assertThat(lastPrompt(chatModel, 1).getInstructions().getFirst().getText())
      .contains("<<<UNTRUSTED kind=latest-triage>>>notification-platform: 1 failure, all in NP-1. "
        + "| NP-1 (flagged UNSUPPORTED): The template is missing.<<<END UNTRUSTED>>>");
  }

  private static ChatResponse asking(String tool, String arguments) {
    AssistantMessage message = AssistantMessage.builder()
      .content("")
      .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", tool, arguments)))
      .build();
    return new ChatResponse(List.of(new Generation(message)));
  }

  private static ChatResponse answer(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private static Prompt lastPrompt(ChatModel chatModel, int calls) {
    ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel, times(calls)).call(prompts.capture());
    return prompts.getValue();
  }

  private static ToolResponseMessage.ToolResponse toolResponse(List<Message> sent) {
    assertThat(sent.getLast()).isInstanceOf(ToolResponseMessage.class);
    List<ToolResponseMessage.ToolResponse> responses = ((ToolResponseMessage) sent.getLast()).getResponses();
    assertThat(responses).hasSize(1);
    return responses.getFirst();
  }

  private static ChatService chatService(ChatModel chatModel, McpSyncClient client) {
    return chatService(chatModel, client, new LatestTriageHolder());
  }


  private static ChatService chatService(ChatModel chatModel, McpSyncClient client, LatestTriageHolder latest) {
    NotificationReadTools notifications = new NotificationReadTools(mock(NotificationWorkerClient.class), 100);
    ReadOnlyMcpTools mcp = new ReadOnlyMcpTools(new McpClientHolder(() -> client, () -> { }));
    return new ChatService(chatModel, "qwen3:8b", provider(NotificationReadTools.class, notifications),
      provider(ReadOnlyMcpTools.class, mcp), new StaticListableBeanFactory().getBeanProvider(McpClientHolder.class),
      latest, 10, 8000);
  }

  private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
    return new StaticListableBeanFactory(Map.of("bean", bean)).getBeanProvider(type);
  }
}
