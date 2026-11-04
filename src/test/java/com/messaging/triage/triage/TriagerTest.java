package com.messaging.triage.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.messaging.triage.guard.ActionGuard;
import com.messaging.triage.report.FailureGroup;
import com.messaging.triage.report.FailureReport;
import com.messaging.triage.report.Sample;
import com.messaging.triage.source.SourceSection;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One call per group (ADR 0004). The model answers through {@link ScriptedChatModel}, keyed
 * by group id, because the calls run in parallel.
 */
class TriagerTest {

  private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
  private static final String MODEL = "qwen3:8b";
  private static final String MISSING_KEY = "no template configured for key order-refunded";
  private static final String MISSING_VARIABLE =
    "template order-shipped needs variable orderId, which the payload does not carry";
  private static final String COPIED = "The payload does not carry orderId.";
  private static final String MI_ANSWER = answer("The message has no address.", "Ask the sender to add the address.",
    "Missing required field");

  private final ScriptedChatModel chatModel = new ScriptedChatModel();
  private final Triager triager = triager(chatModel, 2);

  @Test
  void eachGroupIsAskedAloneAndItsDiagnosisFiledUnderItsOwnId() {
    chatModel.answer("NP-1", valid("The template is missing.")).answer("MI-1", valid("The payload has no orderId."));

    Triager.Verdict verdict = triager.triage(report("NP-1", "MI-1"));

    assertThat(verdict.outcome().usable()).isTrue();
    assertThat(verdict.outcome().attempts()).isEqualTo(2);
    assertThat(verdict.outcome().problem()).isNull();
    assertThat(verdict.outcome().note()).isNull();
    assertThat(verdict.outcome().model()).isEqualTo(MODEL);
    assertThat(verdict.outcome().groups())
      .extracting(GroupOutcome::groupId, GroupOutcome::status, GroupOutcome::attempts)
      .containsExactly(tuple("NP-1", GroupOutcome.Status.OK, 1),
        tuple("MI-1", GroupOutcome.Status.OK, 1));
    assertThat(verdict.answer().diagnoses()).extracting(GroupDiagnosis::groupId, GroupDiagnosis::probableCause)
      .containsExactly(tuple("NP-1", "The template is missing."),
        tuple("MI-1", "The payload has no orderId."));
    // Lenient on case: "high" is HIGH.
    assertThat(verdict.answer().diagnoses().getFirst().confidence()).isEqualTo(Confidence.HIGH);
    assertThat(verdict.answer().summary()).as("written by code")
      .isEqualTo("notification-platform: 1 failure, all in NP-1. message-inspector: 1 failure, all in MI-1.");

    String np = userText(chatModel.prompts("NP-1").getFirst());
    assertThat(np).contains("-- Group NP-1: 1 failure --").doesNotContain("MI-1").doesNotContain("message-inspector");
  }

  @Test
  void anIdTheModelWritesIsIgnored() {
    // The model no longer writes ids, so the v1 checks for unknown, missing and repeated ids
    // have nothing to check: an id in the answer is an unknown field, and code files it.
    chatModel.answer("NP-1", valid("The template is missing.").replace("{", "{\"groupId\":\"NP-9\","));

    Triager.Verdict verdict = triager.triage(report("NP-1"));

    assertThat(verdict.answer().diagnoses()).extracting(GroupDiagnosis::groupId).containsExactly("NP-1");
  }

  @Test
  void evidenceInTheGroupIsKeptAndTheDiagnosisLeftUnflagged() {
    chatModel.answer("NP-1", quoting("The template is missing.", "Unknown template", "1 failure"));

    GroupDiagnosis diagnosis = triager.triage(report("NP-1")).answer().diagnoses().getFirst();

    assertThat(diagnosis.flags()).isEmpty();
    assertThat(diagnosis.confidence()).isEqualTo(Confidence.HIGH);
    assertThat(diagnosis.evidence()).containsExactly("Unknown template", "1 failure");
  }

  @Test
  void fabricatedEvidenceIsFlaggedCappedAtLowAndDropped() {
    chatModel.answer("NP-1", quoting("The SMTP relay refused it.", "unknown template",
        "550 relay access denied", "12 failures"))
      .answer("MI-1", valid("The payload has no orderId."));

    Triager.Verdict verdict = triager.triage(report("NP-1", "MI-1"));

    GroupDiagnosis np = verdict.answer().diagnoses().getFirst();
    assertThat(np.flags()).containsExactly(DiagnosisFlag.UNSUPPORTED);
    assertThat(np.confidence()).as("HIGH in the answer, capped by code").isEqualTo(Confidence.LOW);
    assertThat(np.evidence()).as("only what the group holds is shown as evidence")
      .containsExactly("unknown template");
    GroupDiagnosis mi = verdict.answer().diagnoses().get(1);
    assertThat(mi.flags()).isEmpty();
    assertThat(mi.confidence()).isEqualTo(Confidence.HIGH);
    assertThat(verdict.answer().summary())
      .endsWith(" Evidence not in the group's own data (confidence capped at LOW): NP-1.");
    // A flag is not a repair: the answer was well formed.
    assertThat(verdict.outcome().attempts()).isEqualTo(2);
  }

  @Test
  void noEvidenceIsUnsupportedButTheDiagnosisIsStillUsed() {
    String none = valid("The template is missing.").replace(",\"evidence\":[\"unknown template\"]", "");
    chatModel.answer("NP-1", none).answer("MI-1", quoting("The payload has no orderId."));

    Triager.Verdict verdict = triager.triage(report("NP-1", "MI-1"));

    assertThat(verdict.outcome().usable()).isTrue();
    assertThat(verdict.answer().diagnoses())
      .extracting(GroupDiagnosis::groupId, GroupDiagnosis::confidence, GroupDiagnosis::flags)
      .containsExactly(tuple("NP-1", Confidence.LOW, List.of(DiagnosisFlag.UNSUPPORTED)),
        tuple("MI-1", Confidence.LOW, List.of(DiagnosisFlag.UNSUPPORTED)));
  }

  @Test
  void adviceToResendIsFlaggedCappedAtLowAndPutBehindTheWarning() {
    String resend = "Add the template, then resend all of them.";
    chatModel.answer("NP-1", valid("The template is missing.").replace("Add the template.", resend))
      .answer("MI-1", valid("The payload has no orderId."));

    Triager.Verdict verdict = triager.triage(report("NP-1", "MI-1"));

    GroupDiagnosis np = verdict.answer().diagnoses().getFirst();
    assertThat(np.flags()).containsExactly(DiagnosisFlag.RECOMMENDS_STATE_CHANGE);
    assertThat(np.confidence()).as("HIGH in the answer, capped by code").isEqualTo(Confidence.LOW);
    assertThat(np.suggestedNextStep()).isEqualTo(ActionGuard.WARNING + " " + resend);
    GroupDiagnosis mi = verdict.answer().diagnoses().get(1);
    assertThat(mi.flags()).isEmpty();
    assertThat(mi.suggestedNextStep()).isEqualTo("Add the template.");
    assertThat(verdict.answer().summary())
      .endsWith(" Advice to change message state, which v1 never acts on (confidence capped at LOW): NP-1.");
    assertThat(verdict.outcome().attempts()).as("a flag, not a repair").isEqualTo(2);
  }

  @Test
  void adviceInAnyFieldTheModelWroteIsFlagged() {
    chatModel.answer("NP-1", valid("The template is missing, so purge the DLQ."))
      .answer("NP-2", valid("The template is missing.").replace("the template store", "Replay the dead letters."))
      .answer("NP-3", quoting("The template is missing.", "unknown template", "Resend all dead letters now."))
      .answer("MI-1", valid("The payload has no orderId."));

    Triager.Verdict verdict = triager.triage(report("NP-1", "NP-2", "NP-3", "MI-1"));

    assertThat(verdict.answer().diagnoses()).extracting(GroupDiagnosis::groupId, GroupDiagnosis::flags)
      .containsExactly(tuple("NP-1", List.of(DiagnosisFlag.RECOMMENDS_STATE_CHANGE)),
        tuple("NP-2", List.of(DiagnosisFlag.RECOMMENDS_STATE_CHANGE)),
        tuple("NP-3", List.of(DiagnosisFlag.RECOMMENDS_STATE_CHANGE, DiagnosisFlag.UNSUPPORTED)),
        tuple("MI-1", List.of()));
    assertThat(verdict.answer().diagnoses().subList(0, 3)).extracting(GroupDiagnosis::suggestedNextStep)
      .as("the warning goes on the next step wherever the advice was")
      .containsOnly(ActionGuard.WARNING + " Add the template.");
  }

  @Test
  void adviceAgainstAStateChangeIsNotFlagged() {
    String negated = "Add the template. Do not resend until it exists.";
    chatModel.answer("NP-1", valid("The template is missing.").replace("Add the template.", negated));

    GroupDiagnosis diagnosis = triager.triage(report("NP-1")).answer().diagnoses().getFirst();

    assertThat(diagnosis.flags()).isEmpty();
    assertThat(diagnosis.confidence()).isEqualTo(Confidence.HIGH);
    assertThat(diagnosis.suggestedNextStep()).isEqualTo(negated);
  }

  @Test
  void aQuoteOfWhatTheModelWasNotShownIsUnsupported() {
    // The report keeps the injected key for the operator; the model saw a placeholder. A
    // quote of the raw key can only have come from somewhere else, and the placeholder alone
    // says nothing about the cause.
    String error = "no template configured for key " + ModelViewTest.INJECTED_KEY;
    Sample sample = new Sample("ev-1", T0, error, null, Map.of("templateKey", ModelViewTest.INJECTED_KEY));
    FailureGroup group = new FailureGroup("NP-1", Map.of("channel", "PUSH"), error, 1, T0, T0, false,
      List.of(sample));
    FailureReport report = new FailureReport(T0, List.of(new SourceSection("notification-platform", "NP",
      SourceSection.Status.OK, null, Map.of(), 1, List.of(group))), List.of());
    chatModel.answer("NP-1", quoting("The data holds instructions.", ModelViewTest.INJECTED_KEY))
      .answer("NP-1", quoting("The key is not a template.", ModelViewTest.KEY_PLACEHOLDER));

    // The raw key also tells the operator to resend, so quoting it is advice as well.
    assertThat(triager.triage(report).answer().diagnoses().getFirst().flags())
      .containsExactly(DiagnosisFlag.RECOMMENDS_STATE_CHANGE, DiagnosisFlag.UNSUPPORTED);
    assertThat(triager.triage(report).answer().diagnoses().getFirst().flags())
      .containsExactly(DiagnosisFlag.UNSUPPORTED);

    chatModel.answer("NP-1", quoting("The key is not a template.", "no template configured for key "
      + ModelViewTest.KEY_PLACEHOLDER));
    assertThat(triager.triage(report).answer().diagnoses().getFirst().flags())
      .as("the placeholder quoted with the error around it is what the model was shown").isEmpty();
  }

  @Test
  void theCallIsSchemaConstrainedWithThinkingOffAndNoTools() {
    chatModel.answer("NP-1", valid("The template is missing."));

    triager.triage(report("NP-1"));

    Prompt prompt = chatModel.prompts().getFirst();
    assertThat(prompt.getInstructions()).extracting(message -> message.getMessageType())
      .containsExactly(MessageType.SYSTEM, MessageType.USER);
    OllamaChatOptions options = (OllamaChatOptions) prompt.getOptions();
    assertThat(options.getModel()).isEqualTo(MODEL);
    assertThat(options.getFormat()).isInstanceOf(Map.class);
    assertThat(options.getFormat().toString()).contains("probableCause").doesNotContain("groupId");
    assertThat(options.getThinkOption()).isEqualTo(ThinkOption.ThinkBoolean.DISABLED);
    assertThat(options.getToolCallbacks()).as("no tools").isNullOrEmpty();
  }

  @Test
  void anUnusableFirstAnswerIsRepairedOnceForThatGroupOnly() {
    chatModel.answer("NP-1", "Sure! Here is the triage you asked for.", valid("The template is missing."))
      .answer("MI-1", valid("The payload has no orderId."));

    Triager.Verdict verdict = triager.triage(report("NP-1", "MI-1"));

    assertThat(verdict.outcome().usable()).isTrue();
    assertThat(verdict.outcome().attempts()).isEqualTo(3);
    assertThat(verdict.outcome().note()).isEqualTo("repaired on the second try: NP-1");
    GroupOutcome np = verdict.outcome().groups().getFirst();
    assertThat(np.status()).isEqualTo(GroupOutcome.Status.REPAIRED);
    assertThat(np.attempts()).isEqualTo(2);
    assertThat(np.problem()).startsWith("not a JSON object matching the schema");
    assertThat(verdict.outcome().groups().get(1).status()).isEqualTo(GroupOutcome.Status.OK);
    assertThat(verdict.answer().diagnoses()).hasSize(2);

    Prompt repair = chatModel.prompts("NP-1").get(1);
    assertThat(repair.getInstructions()).extracting(message -> message.getMessageType())
      .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
    assertThat(repair.getInstructions().get(2).getText()).isEqualTo("Sure! Here is the triage you asked for.");
    assertThat(repair.getInstructions().get(3).getText())
      .startsWith("Your answer could not be used: not a JSON object matching the schema")
      .contains("for this one group");
    assertThat(chatModel.prompts("MI-1")).hasSize(1);
  }

  @Test
  void malformedTwiceIsUnusableWithTheReason() {
    chatModel.answer("NP-1", "{\"probableCause\": \"cut off", "still not json");

    Triager.Verdict verdict = triager.triage(report("NP-1"));

    assertThat(verdict.answer()).isNull();
    assertThat(verdict.outcome().usable()).isFalse();
    assertThat(verdict.outcome().attempts()).isEqualTo(2);
    // Problems are per group now, so each carries its id.
    assertThat(verdict.outcome().problem())
      .startsWith("model answer unusable: NP-1: not a JSON object matching the schema");
    assertThat(verdict.outcome().groups().getFirst().status()).isEqualTo(GroupOutcome.Status.UNUSABLE);
  }

  @Test
  void aGroupStillUnusableIsLeftOutAndNamedWhileTheOthersAreUsed() {
    chatModel.answer("NP-1", "nope", "still nope").answer("MI-1", valid("The payload has no orderId."));

    Triager.Verdict verdict = triager.triage(report("NP-1", "MI-1"));

    assertThat(verdict.outcome().usable()).isTrue();
    assertThat(verdict.answer().diagnoses()).extracting(GroupDiagnosis::groupId).containsExactly("MI-1");
    assertThat(verdict.outcome().note()).isEqualTo("no usable diagnosis for NP-1");
    assertThat(verdict.answer().summary()).endsWith("No usable diagnosis for NP-1.");
    assertThat(verdict.outcome().groups().getFirst())
      .extracting(GroupOutcome::status, GroupOutcome::attempts)
      .containsExactly(GroupOutcome.Status.UNUSABLE, 2);
  }

  @Test
  void aCauseCopiedAcrossDifferentErrorsSendsBothBackShownTheOther() {
    // v1's defect: the missing-orderId cause also landed on the missing-template group.
    chatModel.answer("NP-1", answer(COPIED, "Add orderId to the payload.", "order-refunded"),
        answer("No template is configured for order-refunded.", "Add the template.", "order-refunded"))
      .answer("NP-2", answer(COPIED, "Add orderId to the payload.", "needs variable orderId"),
        answer(COPIED, "Add orderId to the payload.", "needs variable orderId"))
      .answer("MI-1", MI_ANSWER);

    Triager.Verdict verdict = triager.triage(differing());

    assertThat(verdict.answer().diagnoses()).extracting(GroupDiagnosis::groupId, GroupDiagnosis::probableCause)
      .containsExactly(tuple("NP-1", "No template is configured for order-refunded."), tuple("NP-2", COPIED),
        tuple("MI-1", "The message has no address."));
    assertThat(verdict.outcome().groups())
      .extracting(GroupOutcome::groupId, GroupOutcome::status, GroupOutcome::attempts, GroupOutcome::problem)
      .containsExactly(
        tuple("NP-1", GroupOutcome.Status.REPAIRED, 2, "probableCause repeats NP-2's, which failed differently"),
        tuple("NP-2", GroupOutcome.Status.REPAIRED, 2, "probableCause repeats NP-1's, which failed differently"),
        tuple("MI-1", GroupOutcome.Status.OK, 1, null));
    assertThat(verdict.outcome().attempts()).isEqualTo(5);
    assertThat(verdict.outcome().note()).isEqualTo("repaired on the second try: NP-1, NP-2");

    Prompt again = chatModel.prompts("NP-1").get(1);
    assertThat(again.getInstructions()).extracting(message -> message.getMessageType())
      .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
    assertThat(again.getInstructions().get(2).getText()).contains(COPIED);
    assertThat(again.getInstructions().get(3).getText())
      .startsWith("Your probableCause repeats the one given for group NP-2")
      .contains(Untrusted.wrap("other-diagnosis", COPIED, 300));
  }

  @Test
  void aCopyStillThereWhenAskedAgainIsUnusableRatherThanKept() {
    String copy = answer(COPIED, "Add orderId to the payload.", "Template");
    chatModel.answer("NP-1", copy, copy).answer("NP-2", copy, copy).answer("MI-1", MI_ANSWER);

    Triager.Verdict verdict = triager.triage(differing());

    assertThat(verdict.outcome().usable()).isTrue();
    assertThat(verdict.answer().diagnoses()).extracting(GroupDiagnosis::groupId).containsExactly("MI-1");
    assertThat(verdict.outcome().groups()).extracting(GroupOutcome::status, GroupOutcome::attempts)
      .containsExactly(tuple(GroupOutcome.Status.UNUSABLE, 2), tuple(GroupOutcome.Status.UNUSABLE, 2),
        tuple(GroupOutcome.Status.OK, 1));
    assertThat(verdict.outcome().groups().getFirst().problem()).isEqualTo(
      "probableCause repeats NP-2's, which failed differently; asked again, its probableCause repeats NP-2's");
    assertThat(verdict.outcome().note()).isEqualTo("no usable diagnosis for NP-1, NP-2");
    assertThat(verdict.answer().summary()).endsWith("No usable diagnosis for NP-1, NP-2.");
  }

  @Test
  void aRepeatWithNoCallLeftIsUnusableAtOnceAndTheOtherIsAskedAgain() {
    String copy = answer(COPIED, "Add orderId to the payload.", "Template");
    chatModel.answer("NP-1", "not json", copy).answer("NP-2", copy, copy).answer("MI-1", MI_ANSWER);

    Triager.Verdict verdict = triager.triage(differing());

    // NP-1 spent its repair on its shape. NP-2, asked again, kept its cause; with NP-1 gone
    // nothing repeats it, so it stands.
    assertThat(verdict.answer().diagnoses()).extracting(GroupDiagnosis::groupId).containsExactly("NP-2", "MI-1");
    GroupOutcome np1 = verdict.outcome().groups().getFirst();
    assertThat(np1.status()).isEqualTo(GroupOutcome.Status.UNUSABLE);
    assertThat(np1.attempts()).isEqualTo(2);
    assertThat(np1.problem())
      .isEqualTo("probableCause repeats NP-2's, which failed differently, and no call was left to ask again");
    assertThat(verdict.outcome().groups().get(1))
      .extracting(GroupOutcome::status, GroupOutcome::attempts).containsExactly(GroupOutcome.Status.REPAIRED, 2);
    assertThat(chatModel.prompts("NP-1")).hasSize(2);
  }

  @Test
  void theSameCauseForTheSameErrorIsKeptWithoutAskingAgain() {
    // Repeats count only across different errors: one missing template on two channels has one cause.
    FailureGroup email = group("NP-1", MISSING_KEY);
    FailureGroup push = new FailureGroup("NP-2", Map.of("channel", "PUSH"), MISSING_KEY, 1, T0, T0, false,
      List.of());
    String same = answer("No template is configured for order-refunded.", "Add the template.", "order-refunded");
    chatModel.answer("NP-1", same).answer("NP-2", same);

    Triager.Verdict verdict = triager.triage(new FailureReport(T0, List.of(new SourceSection("notification-platform",
      "NP", SourceSection.Status.OK, null, Map.of(), 2, List.of(email, push))), List.of()));

    assertThat(verdict.outcome().groups()).extracting(GroupOutcome::status, GroupOutcome::attempts)
      .containsExactly(tuple(GroupOutcome.Status.OK, 1), tuple(GroupOutcome.Status.OK, 1));
  }

  @Test
  void aModelThatFailsWhenAskedAgainMakesTheWholeTriageUnavailable() {
    String copy = answer(COPIED, "Add orderId to the payload.", "Template");
    chatModel.answer("NP-1", copy, copy).answer("NP-2", copy)
      .fail("NP-2", new ResourceAccessException("Read timed out")).answer("MI-1", MI_ANSWER);

    Triager.Verdict verdict = triager.triage(differing());

    assertThat(verdict.answer()).isNull();
    assertThat(verdict.outcome().usable()).isFalse();
    assertThat(verdict.outcome().problem()).isEqualTo("model unavailable: ResourceAccessException: Read timed out");
  }

  @Test
  void blankFieldsAreRefused() {
    String blankCause = valid("The template is missing.").replace("\"The template is missing.\"", "\" \"");
    String noChecks = valid("The template is missing.").replace(",\"whatToCheck\":[\"the template store\"]", "");
    chatModel.answer("NP-1", blankCause, noChecks);

    Triager.Verdict verdict = triager.triage(report("NP-1"));

    assertThat(verdict.outcome().usable()).isFalse();
    assertThat(verdict.outcome().problem()).contains("whatToCheck must not be null");
    assertThat(chatModel.prompts("NP-1").get(1).getInstructions().get(3).getText())
      .contains("probableCause must not be blank");
  }

  @Test
  void thinkBlocksAndCodeFencesAreStripped() {
    chatModel.answer("NP-1", "<think>\nThe user wants JSON. {not this}\n</think>\n```json\n"
      + valid("The template is missing.") + "\n```\n");

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
    ChatModel down = mock(ChatModel.class);
    when(down.call(any(Prompt.class))).thenThrow(new ResourceAccessException("I/O error on POST request",
      new java.net.ConnectException("Connection refused")));

    Triager.Verdict verdict = triager(down, 2).triage(report("NP-1"));

    assertThat(verdict.answer()).isNull();
    assertThat(verdict.outcome().usable()).isFalse();
    assertThat(verdict.outcome().attempts()).isEqualTo(1);
    assertThat(verdict.outcome().problem())
      .startsWith("model unavailable: ResourceAccessException")
      .contains("ConnectException: Connection refused");
  }

  @Test
  void onceTheModelFailsNoFurtherCallStartsAndTheWholeTriageIsUnavailable() {
    ChatModel down = mock(ChatModel.class);
    when(down.call(any(Prompt.class))).thenThrow(new ResourceAccessException("I/O error on POST request"));

    // One slot: whichever group goes first fails, and the other finds the model down.
    Triager.Verdict verdict = triager(down, 1).triage(report("NP-1", "MI-1"));

    assertThat(verdict.answer()).isNull();
    assertThat(verdict.outcome().problem()).startsWith("model unavailable: ResourceAccessException");
    assertThat(verdict.outcome().attempts()).isEqualTo(1);
    assertThat(verdict.outcome().groups()).extracting(GroupOutcome::status)
      .containsExactlyInAnyOrder(GroupOutcome.Status.UNUSABLE, GroupOutcome.Status.SKIPPED);
  }

  @Test
  void aFailureInOneGroupDiscardsTheOthersAsInV1() {
    CountDownLatch answered = new CountDownLatch(1);
    ChatModel failsSecond = prompt -> {
      if ("NP-1".equals(ScriptedChatModel.groupOf(prompt))) {
        answered.countDown();
        return ScriptedChatModel.response(valid("The template is missing."));
      }
      await(answered);
      throw new ResourceAccessException("Read timed out");
    };

    Triager.Verdict verdict = triager(failsSecond, 2).triage(report("NP-1", "MI-1"));

    assertThat(verdict.answer()).isNull();
    assertThat(verdict.outcome().usable()).isFalse();
    assertThat(verdict.outcome().problem()).isEqualTo("model unavailable: ResourceAccessException: Read timed out");
    assertThat(verdict.outcome().groups()).extracting(GroupOutcome::status)
      .containsExactly(GroupOutcome.Status.OK, GroupOutcome.Status.UNUSABLE);
  }

  @Test
  void anEmptyResponseIsRepairedLikeAnyOtherUnusableAnswer() {
    chatModel.answer("NP-1", null, valid("The template is missing."));

    Triager.Verdict verdict = triager.triage(report("NP-1"));

    assertThat(verdict.outcome().usable()).isTrue();
    assertThat(verdict.outcome().groups().getFirst().problem()).isEqualTo("the answer is empty");
  }

  @Test
  void callsRunInParallelUpToTheLimitAndNoFurther() {
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger most = new AtomicInteger();
    CountDownLatch firstTwo = new CountDownLatch(2);
    ChatModel slow = prompt -> {
      most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
      firstTwo.countDown();
      // With one call at a time this waits out the timeout and the assertion below fails.
      await(firstTwo);
      inFlight.decrementAndGet();
      return ScriptedChatModel.response(valid("The template is missing."));
    };

    Triager.Verdict verdict = triager(slow, 2).triage(report("NP-1", "NP-2", "MI-1", "MI-2"));

    assertThat(most).hasValue(2);
    assertThat(verdict.answer().diagnoses()).extracting(GroupDiagnosis::groupId)
      .containsExactly("NP-1", "NP-2", "MI-1", "MI-2");
    assertThat(verdict.outcome().durationMs()).isLessThan(5000);
  }

  @Test
  void noGroupsMeansNoModelCall() {
    ChatModel unused = mock(ChatModel.class);

    Triager.Verdict verdict = triager(unused, 2).triage(new FailureReport(T0, List.of(), List.of()));

    assertThat(verdict.answer()).isNull();
    assertThat(verdict.outcome().usable()).isTrue();
    assertThat(verdict.outcome().attempts()).isZero();
    assertThat(verdict.outcome().groups()).isEmpty();
    assertThat(verdict.outcome().note()).isEqualTo(Triager.NOTHING_TO_TRIAGE);
    verifyNoInteractions(unused);
  }

  private static Triager triager(ChatModel chatModel, int maxParallelCalls) {
    AnswerValidator validator = new AnswerValidator(Validation.buildDefaultValidatorFactory().getValidator());
    return new Triager(chatModel, MODEL, validator, 300, maxParallelCalls);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String userText(Prompt prompt) {
    return prompt.getInstructions().get(1).getText();
  }

  static String valid(String cause) {
    return "{\"probableCause\":\"" + cause + "\",\"confidence\":\"high\","
      + "\"suggestedNextStep\":\"Add the template.\",\"whatToCheck\":[\"the template store\"],"
      + "\"evidence\":[\"unknown template\"]}";
  }

  /** A HIGH answer with this evidence, written by Jackson so any quote is escaped. */
  static String quoting(String cause, String... evidence) {
    ObjectNode answer = JsonMapper.shared().createObjectNode()
      .put("probableCause", cause)
      .put("confidence", "HIGH")
      .put("suggestedNextStep", "Add the template.");
    answer.putArray("whatToCheck").add("the template store");
    ArrayNode quotes = answer.putArray("evidence");
    for (String quote : evidence) {
      quotes.add(quote);
    }
    return answer.toString();
  }

  /** An answer with a next step of its own, so only what a test means to repeat does. */
  static String answer(String cause, String nextStep, String evidence) {
    ObjectNode answer = JsonMapper.shared().createObjectNode()
      .put("probableCause", cause)
      .put("confidence", "MEDIUM")
      .put("suggestedNextStep", nextStep);
    answer.putArray("whatToCheck").add("the sender");
    answer.putArray("evidence").add(evidence);
    return answer.toString();
  }

  /** Two notification-platform groups that failed differently, and one from message-inspector. */
  private static FailureReport differing() {
    List<FailureGroup> np = List.of(group("NP-1", MISSING_KEY), group("NP-2", MISSING_VARIABLE));
    List<FailureGroup> mi = List.of(group("MI-1", "Missing required field: address"));
    return new FailureReport(T0, List.of(
      new SourceSection("notification-platform", "NP", SourceSection.Status.OK, null, Map.of(), 2, np),
      new SourceSection("message-inspector", "MI", SourceSection.Status.OK, null, Map.of(), 1, mi)),
      List.of());
  }

  private static FailureGroup group(String id, String error) {
    return new FailureGroup(id, Map.of("channel", "EMAIL"), error, 1, T0, T0, false, List.of());
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
