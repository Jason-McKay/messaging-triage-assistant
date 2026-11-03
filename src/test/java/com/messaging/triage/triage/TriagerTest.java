package com.messaging.triage.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.messaging.triage.report.FailureGroup;
import com.messaging.triage.report.FailureReport;
import com.messaging.triage.source.SourceSection;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.web.client.ResourceAccessException;

class TriagerTest {

  private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
  private static final String MODEL = "qwen3:8b";

  private ChatModel chatModel;
  private Triager triager;

  @BeforeEach
  void setUp() {
    chatModel = mock(ChatModel.class);
    AnswerValidator validator = new AnswerValidator(Validation.buildDefaultValidatorFactory().getValidator());
    triager = new Triager(chatModel, MODEL, validator, 300);
  }

  @Test
  void aValidAnswerIsUsedAsItIs() {
    answers(valid("NP-1", "MI-1"));

    Triager.Verdict verdict = triager.triage(report("NP-1", "MI-1"));

    assertThat(verdict.outcome().usable()).isTrue();
    assertThat(verdict.outcome().attempts()).isEqualTo(1);
    assertThat(verdict.outcome().problem()).isNull();
    assertThat(verdict.outcome().note()).isNull();
    assertThat(verdict.outcome().model()).isEqualTo(MODEL);
    assertThat(verdict.answer().diagnoses()).extracting(GroupDiagnosis::groupId).containsExactly("NP-1", "MI-1");
    // Lenient on case: "high" is HIGH.
    assertThat(verdict.answer().diagnoses().getFirst().confidence()).isEqualTo(Confidence.HIGH);
  }

  @Test
  void theCallIsSchemaConstrainedWithThinkingOffAndNoTools() {
    answers(valid("NP-1"));

    triager.triage(report("NP-1"));

    Prompt prompt = onlyPrompt();
    assertThat(prompt.getInstructions()).extracting(message -> message.getMessageType())
      .containsExactly(MessageType.SYSTEM, MessageType.USER);
    OllamaChatOptions options = (OllamaChatOptions) prompt.getOptions();
    assertThat(options.getModel()).isEqualTo(MODEL);
    assertThat(options.getFormat()).isInstanceOf(Map.class);
    assertThat(options.getThinkOption()).isEqualTo(ThinkOption.ThinkBoolean.DISABLED);
    assertThat(options.getToolCallbacks()).as("no tools").isNullOrEmpty();
  }

  @Test
  void anUnusableFirstAnswerIsRepairedOnce() {
    answers("Sure! Here is the triage you asked for.", valid("NP-1"));

    Triager.Verdict verdict = triager.triage(report("NP-1"));

    assertThat(verdict.outcome().usable()).isTrue();
    assertThat(verdict.outcome().attempts()).isEqualTo(2);
    assertThat(verdict.outcome().note()).startsWith("repaired on the second try; the first answer had: ");
    assertThat(verdict.answer().diagnoses()).hasSize(1);

    ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel, times(2)).call(prompts.capture());
    Prompt repair = prompts.getAllValues().get(1);
    assertThat(repair.getInstructions()).extracting(message -> message.getMessageType())
      .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
    assertThat(repair.getInstructions().get(2).getText()).isEqualTo("Sure! Here is the triage you asked for.");
    assertThat(repair.getInstructions().get(3).getText())
      .startsWith("Your answer could not be used: not a JSON object matching the schema")
      .contains("these group ids: NP-1");
  }

  @Test
  void malformedTwiceIsUnusableWithTheReason() {
    answers("{\"summary\": \"cut off", "still not json");

    Triager.Verdict verdict = triager.triage(report("NP-1"));

    assertThat(verdict.answer()).isNull();
    assertThat(verdict.outcome().usable()).isFalse();
    assertThat(verdict.outcome().attempts()).isEqualTo(2);
    assertThat(verdict.outcome().problem()).startsWith("model answer unusable: not a JSON object matching the schema");
  }

  @Test
  void anUnknownGroupIdIsRefused() {
    answers(valid("NP-1", "NP-9"), valid("NP-1", "NP-9"));

    Triager.Verdict verdict = triager.triage(report("NP-1"));

    assertThat(verdict.outcome().usable()).isFalse();
    assertThat(verdict.outcome().problem()).contains("unknown groupId NP-9 (the report's ids are NP-1)");
  }

  @Test
  void aMissingOrRepeatedGroupIdIsRefused() {
    answers(valid("NP-1", "NP-1"), valid("NP-1"));

    Triager.Verdict verdict = triager.triage(report("NP-1", "MI-1"));

    assertThat(verdict.outcome().usable()).isFalse();
    assertThat(verdict.outcome().attempts()).isEqualTo(2);
    assertThat(verdict.outcome().problem()).contains("no diagnosis for MI-1");
    ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel, times(2)).call(prompts.capture());
    assertThat(prompts.getAllValues().get(1).getInstructions().get(3).getText())
      .contains("groupId NP-1 is diagnosed more than once")
      .contains("no diagnosis for MI-1");
  }

  @Test
  void blankFieldsAreRefused() {
    String blankSummary = valid("NP-1").replace("\"summary\":\"Two groups are failing.\"", "\"summary\":\" \"");
    String noGroupId = valid("NP-1").replace("\"groupId\":\"NP-1\",", "");
    answers(blankSummary, noGroupId);

    Triager.Verdict verdict = triager.triage(report("NP-1"));

    assertThat(verdict.outcome().usable()).isFalse();
    assertThat(verdict.outcome().problem()).contains("diagnoses[0].groupId").contains("no diagnosis for NP-1");
  }

  @Test
  void thinkBlocksAndCodeFencesAreStripped() {
    answers("<think>\nThe user wants JSON. {not this}\n</think>\n```json\n" + valid("NP-1") + "\n```\n");

    Triager.Verdict verdict = triager.triage(report("NP-1"));

    assertThat(verdict.outcome().usable()).isTrue();
    assertThat(verdict.outcome().attempts()).isEqualTo(1);
  }

  @Test
  void cleanKeepsTheOutermostObject() {
    assertThat(Triager.clean("<think>{x}</think> text {\"a\":{\"b\":1}} trailing")).isEqualTo("{\"a\":{\"b\":1}}");
    assertThat(Triager.clean("no braces")).isEqualTo("no braces");
    assertThat(Triager.clean(null)).isEmpty();
  }

  @Test
  void aModelThatCannotBeReachedIsModelUnavailable() {
    when(chatModel.call(any(Prompt.class))).thenThrow(new ResourceAccessException("I/O error on POST request",
      new java.net.ConnectException("Connection refused")));

    Triager.Verdict verdict = triager.triage(report("NP-1"));

    assertThat(verdict.answer()).isNull();
    assertThat(verdict.outcome().usable()).isFalse();
    assertThat(verdict.outcome().attempts()).isEqualTo(1);
    assertThat(verdict.outcome().problem())
      .startsWith("model unavailable: ResourceAccessException")
      .contains("ConnectException: Connection refused");
  }

  @Test
  void anEmptyResponseIsRepairedLikeAnyOtherUnusableAnswer() {
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()), response(valid("NP-1")));

    Triager.Verdict verdict = triager.triage(report("NP-1"));

    assertThat(verdict.outcome().usable()).isTrue();
    assertThat(verdict.outcome().note()).contains("the answer is empty");
  }

  @Test
  void noGroupsMeansNoModelCall() {
    Triager.Verdict verdict = triager.triage(new FailureReport(T0, List.of(), List.of()));

    assertThat(verdict.answer()).isNull();
    assertThat(verdict.outcome().usable()).isTrue();
    assertThat(verdict.outcome().attempts()).isZero();
    assertThat(verdict.outcome().note()).isEqualTo(Triager.NOTHING_TO_TRIAGE);
    verifyNoInteractions(chatModel);
  }

  private void answers(String first, String... rest) {
    ChatResponse[] more = new ChatResponse[rest.length];
    for (int i = 0; i < rest.length; i++) {
      more[i] = response(rest[i]);
    }
    when(chatModel.call(any(Prompt.class))).thenReturn(response(first), more);
  }

  private Prompt onlyPrompt() {
    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    return prompt.getValue();
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private static String valid(String... groupIds) {
    StringBuilder json = new StringBuilder("{\"summary\":\"Two groups are failing.\",\"diagnoses\":[");
    for (int i = 0; i < groupIds.length; i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"groupId\":\"").append(groupIds[i]).append("\",")
        .append("\"probableCause\":\"The template is missing.\",\"confidence\":\"high\",")
        .append("\"suggestedNextStep\":\"Add the template.\",\"whatToCheck\":[\"the template store\"]}");
    }
    return json.append("]}").toString();
  }

  private static FailureReport report(String... groupIds) {
    List<FailureGroup> np = new java.util.ArrayList<>();
    List<FailureGroup> mi = new java.util.ArrayList<>();
    for (String id : groupIds) {
      FailureGroup group = new FailureGroup(id, Map.of("channel", "EMAIL"), "unknown template", 1, T0, T0, false,
        List.of());
      (id.startsWith("NP") ? np : mi).add(group);
    }
    return new FailureReport(T0, List.of(
      new SourceSection("notification-platform", "NP", SourceSection.Status.OK, null, Map.of(), np.size(), np),
      new SourceSection("message-inspector", "MI", SourceSection.Status.OK, null, Map.of(), mi.size(), mi)),
      List.of());
  }
}
