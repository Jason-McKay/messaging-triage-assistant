package com.messaging.triage.triage;

import com.messaging.triage.guard.ActionGuard;
import com.messaging.triage.guard.EvidenceCheck;
import com.messaging.triage.guard.RepeatedDiagnosisCheck;
import com.messaging.triage.report.FailureGroup;
import com.messaging.triage.report.FailureReport;
import com.messaging.triage.source.SourceSection;
import com.messaging.triage.support.Problems;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks the model to explain each group of a report, and decides which answers can be used.
 * <ol>
 *   <li>No groups: the model is not called.</li>
 *   <li>One call per group, showing that group alone, with no tools, the output constrained
 *       to {@link GroupAnswer}'s JSON schema (Ollama's {@code format}), thinking off. Calls
 *       run in parallel up to a limit shared by every triage (ADR 0004).</li>
 *   <li>Think blocks and code fences stripped, then parsed and checked ({@link AnswerValidator}).</li>
 *   <li>If that fails, one repair call for that group that shows the model its answer and
 *       what was wrong.</li>
 *   <li>A diagnosis repeated across groups that failed differently sends each of them back
 *       once, shown the other's words; one that still repeats, or has no call left, is
 *       unusable rather than kept as a copy (ADR 0005).</li>
 *   <li>Each usable answer is checked in code: its evidence must be in its group's own data,
 *       and no field it wrote may advise a change to message state, or it is flagged and its
 *       confidence capped (ADR 0005).</li>
 *   <li>The answer is assembled in code: each diagnosis under the id of the group it was
 *       asked about, and a summary written from the report's counts ({@link TriageSummary}).
 *       A group still unusable is left out and named; if none is usable, {@code usable=false}
 *       with the reasons.</li>
 *   <li>A call that fails outright makes the model "unavailable" for the whole triage, and no
 *       further calls start. That is not an error: the report is still worth returning.</li>
 * </ol>
 * {@code ChatModel} directly rather than {@code ChatClient}: the client renders prompt text
 * as a template, and the schema's braces break the renderer (docs/gotchas.md).
 */
@Slf4j
public class Triager {

  static final String NOTHING_TO_TRIAGE = "nothing to triage";
  static final int MAX_ECHOED_ANSWER_CHARS = 4000;
  static final int MAX_PROBLEM_CHARS = 300;
  static final double TEMPERATURE = 0.1;
  static final String NOT_ASKED = "not asked: the model failed for another group first";

  private static final Pattern THINK_BLOCK = Pattern.compile("(?s)<think>.*?</think>");

  private final ChatModel chatModel;
  private final String model;
  private final AnswerValidator validator;
  private final BeanOutputConverter<GroupAnswer> converter;
  private final Map<String, Object> format;
  private final TriagePrompt prompt;
  /** Shared by every triage, so two at once still keep to the limit. */
  private final Semaphore slots;

  public Triager(ChatModel chatModel, String model, AnswerValidator validator, int maxValueChars,
    int maxParallelCalls) {
    this.chatModel = chatModel;
    this.model = model;
    this.validator = validator;
    // Lenient on purpose: an answer with "high" or an extra field is still the answer.
    JsonMapper lenient = JsonMapper.builder()
      .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();
    this.converter = new BeanOutputConverter<>(GroupAnswer.class, lenient);
    this.format = converter.getJsonSchemaMap();
    this.prompt = new TriagePrompt(converter.getJsonSchema(), maxValueChars);
    this.slots = new Semaphore(maxParallelCalls, true);
  }

  public record Verdict(TriageAnswer answer, ModelOutcome outcome) {
  }

  public Verdict triage(FailureReport report) {
    List<GroupRun> runs = new ArrayList<>();
    for (SourceSection section : report.sources()) {
      section.groups().forEach(group -> runs.add(new GroupRun(report, section, group)));
    }
    if (runs.isEmpty()) {
      return new Verdict(null, new ModelOutcome(true, model, null, NOTHING_TO_TRIAGE, 0, 0));
    }
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    long started = System.nanoTime();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      runAll(runs, run -> diagnose(run, failure), executor);
      if (failure.get() == null) {
        separateRepeats(runs, failure, executor);
      }
    }
    long durationMs = elapsedMs(started);

    List<GroupOutcome> outcomes = runs.stream().map(GroupRun::outcome).toList();
    outcomes.forEach(outcome -> log.info("triage {}: {} after {} attempts in {} ms", outcome.groupId(),
      outcome.status(), outcome.attempts(), outcome.durationMs()));
    int attempts = outcomes.stream().mapToInt(GroupOutcome::attempts).sum();
    if (failure.get() != null) {
      return new Verdict(null, new ModelOutcome(false, model, "model unavailable: "
        + Problems.describe(failure.get()), null, attempts, durationMs, outcomes));
    }
    List<GroupDiagnosis> diagnoses = runs.stream()
      .filter(run -> run.answer != null)
      .map(Triager::checked)
      .toList();
    if (diagnoses.isEmpty()) {
      return new Verdict(null, new ModelOutcome(false, model, "model answer unusable: " + Problems.cap(
        String.join("; ", outcomes.stream().map(o -> o.groupId() + ": " + o.problem()).toList()), MAX_PROBLEM_CHARS),
        null, attempts, durationMs, outcomes));
    }
    TriageAnswer answer = new TriageAnswer(TriageSummary.write(report, diagnoses), diagnoses);
    return new Verdict(answer, new ModelOutcome(true, model, null, note(outcomes), attempts, durationMs, outcomes));
  }

  /** The first call and, if its answer cannot be used, the one repair. */
  private void diagnose(GroupRun run, AtomicReference<RuntimeException> failure) {
    run.messages.add(prompt.system());
    run.messages.add(prompt.group(run.report, run.section, run.group));
    String first = call(run, failure);
    if (first == null) {
      return;
    }
    Parsed parsed = parse(first);
    if (parsed.usable()) {
      run.answer = parsed.answer();
      run.raw = first;
      return;
    }
    log.info("triage {}: first answer unusable ({} problems); asking once more", run.group.id(),
      parsed.problems().size());
    run.problems = parsed.problems();
    run.messages.add(new AssistantMessage(Problems.cap(first, MAX_ECHOED_ANSWER_CHARS)));
    run.messages.add(prompt.repair(parsed.problems()));
    String second = call(run, failure);
    if (second == null) {
      return;
    }
    Parsed repaired = parse(second);
    run.answer = repaired.answer();
    run.raw = second;
    if (!repaired.usable()) {
      run.problems = repaired.problems();
    }
  }

  /**
   * A diagnosis repeated across groups that failed differently is a problem with both, since
   * which one was copied is not known (ADR 0005). Each group in a repeat is asked once more,
   * shown the other's words, if it has a call left; one with none is unusable at once. A group
   * asked again that still repeats another is unusable too, so the copy is never kept.
   */
  private void separateRepeats(List<GroupRun> runs, AtomicReference<RuntimeException> failure,
    ExecutorService executor) {
    List<RepeatedDiagnosisCheck.Repeat> repeats = RepeatedDiagnosisCheck.find(diagnosed(runs));
    if (repeats.isEmpty()) {
      return;
    }
    Map<String, GroupRun> byId = new HashMap<>();
    runs.forEach(run -> byId.put(run.group.id(), run));
    List<GroupRun> asked = new ArrayList<>();
    for (RepeatedDiagnosisCheck.Repeat repeat : repeats) {
      GroupRun run = byId.get(repeat.id());
      String problem = repeat.field() + " repeats " + repeat.otherId() + "'s, which failed differently";
      if (run.attempts > 1) {
        log.info("triage {}: {} repeats {}'s, with no call left", run.group.id(), repeat.field(), repeat.otherId());
        run.reject(problem + ", and no call was left to ask again");
      } else {
        run.repeat = repeat;
        run.problems = List.of(problem);
        asked.add(run);
      }
    }
    runAll(asked, run -> reconsider(run, failure), executor);
    if (failure.get() != null) {
      return;
    }
    for (RepeatedDiagnosisCheck.Repeat repeat : RepeatedDiagnosisCheck.find(diagnosed(runs))) {
      GroupRun run = byId.get(repeat.id());
      if (run.repeat != null) {
        log.info("triage {}: {} still repeats {}'s", run.group.id(), repeat.field(), repeat.otherId());
        run.reject(String.join("; ", run.problems) + "; asked again, its " + repeat.field() + " repeats "
          + repeat.otherId() + "'s");
      }
    }
  }

  /** The one repair for an answer that repeats another group's. */
  private void reconsider(GroupRun run, AtomicReference<RuntimeException> failure) {
    log.info("triage {}: {} repeats {}'s; asking once more", run.group.id(), run.repeat.field(),
      run.repeat.otherId());
    run.messages.add(new AssistantMessage(Problems.cap(run.raw, MAX_ECHOED_ANSWER_CHARS)));
    run.messages.add(prompt.conflict(run.repeat.otherId(), run.repeat.field(), run.repeat.otherText()));
    run.answer = null;
    String again = call(run, failure);
    if (again == null) {
      return;
    }
    Parsed parsed = parse(again);
    run.answer = parsed.answer();
    run.raw = again;
    if (!parsed.usable()) {
      run.problems = Stream.concat(run.problems.stream(), parsed.problems().stream()).toList();
    }
  }

  /** The groups with an answer, as the repeat check compares them: by the error the model was shown. */
  private static List<RepeatedDiagnosisCheck.Diagnosis> diagnosed(List<GroupRun> runs) {
    return runs.stream()
      .filter(run -> run.answer != null)
      .map(run -> new RepeatedDiagnosisCheck.Diagnosis(run.group.id(), run.answer.probableCause(),
        run.answer.suggestedNextStep(), ModelView.group(run.group).error(), run.group.merged()))
      .toList();
  }

  private static void runAll(List<GroupRun> runs, Consumer<GroupRun> task, ExecutorService executor) {
    runs.stream()
      .map(run -> CompletableFuture.runAsync(() -> task.accept(run), executor))
      .toList()
      .forEach(CompletableFuture::join);
  }

  /**
   * The answer as a diagnosis, after the checks code makes of what the model wrote (ADR 0005):
   * <ul>
   *   <li>evidence that is missing or not in the group's own data flags it UNSUPPORTED, and
   *       only the quotes that held are kept;</li>
   *   <li>advice to change message state in any field it wrote, evidence included, flags it
   *       RECOMMENDS_STATE_CHANGE and puts its next step behind the fixed warning.</li>
   * </ul>
   */
  private static GroupDiagnosis checked(GroupRun run) {
    EvidenceCheck.Result evidence = EvidenceCheck.check(run.answer.evidence(), ModelView.shownTexts(run.group),
      run.group.count());
    GroupDiagnosis diagnosis = GroupDiagnosis.of(run.group.id(), run.answer, evidence.matched());
    if (!evidence.supported()) {
      log.info("triage {}: evidence unsupported ({} quotes held, {} did not)", run.group.id(),
        evidence.matched().size(), evidence.unmatched());
      diagnosis = diagnosis.flagged(DiagnosisFlag.UNSUPPORTED);
    }
    List<String> advising = fieldsAdvisingStateChange(run.answer);
    if (!advising.isEmpty()) {
      log.info("triage {}: advice to change message state in {}", run.group.id(), String.join(", ", advising));
      diagnosis = diagnosis.flagged(DiagnosisFlag.RECOMMENDS_STATE_CHANGE)
        .withNextStep(ActionGuard.warned(diagnosis.suggestedNextStep()));
    }
    return diagnosis;
  }

  /** The names of the fields the model wrote that advise a change to message state. */
  private static List<String> fieldsAdvisingStateChange(GroupAnswer answer) {
    Map<String, List<String>> fields = new LinkedHashMap<>();
    fields.put("probableCause", Collections.singletonList(answer.probableCause()));
    fields.put("suggestedNextStep", Collections.singletonList(answer.suggestedNextStep()));
    fields.put("whatToCheck", answer.whatToCheck() == null ? List.of() : answer.whatToCheck());
    fields.put("evidence", answer.evidence() == null ? List.of() : answer.evidence());
    return fields.entrySet().stream()
      .filter(field -> field.getValue().stream().anyMatch(ActionGuard::recommendsStateChange))
      .map(Map.Entry::getKey)
      .toList();
  }

  /** One call in a free slot; null, with nothing sent, once the model has failed for any group. */
  private String call(GroupRun run, AtomicReference<RuntimeException> failure) {
    slots.acquireUninterruptibly();
    try {
      if (failure.get() != null) {
        return null;
      }
      run.attempts++;
      long started = System.nanoTime();
      try {
        return call(run.messages);
      } catch (RuntimeException e) {
        log.warn("triage {}: model call failed: {}", run.group.id(), Problems.describe(e));
        run.failure = e;
        failure.compareAndSet(null, e);
        return null;
      } finally {
        run.callMs += elapsedMs(started);
      }
    } finally {
      slots.release();
    }
  }

  private String call(List<Message> messages) {
    OllamaChatOptions options = OllamaChatOptions.builder()
      .model(model)
      .temperature(TEMPERATURE)
      .format(format)
      .disableThinking()
      .build();
    ChatResponse response = chatModel.call(new Prompt(List.copyOf(messages), options));
    if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
      return "";
    }
    String text = response.getResult().getOutput().getText();
    return text == null ? "" : text;
  }

  record Parsed(GroupAnswer answer, List<String> problems) {

    boolean usable() {
      return answer != null && problems.isEmpty();
    }
  }

  Parsed parse(String raw) {
    String json = clean(raw);
    if (json.isBlank()) {
      return new Parsed(null, List.of("the answer is empty"));
    }
    GroupAnswer answer;
    try {
      answer = converter.convert(json);
    } catch (RuntimeException e) {
      return new Parsed(null, List.of("not a JSON object matching the schema (" + Problems.describe(e) + ")"));
    }
    List<String> problems = validator.problems(answer);
    return new Parsed(problems.isEmpty() ? answer : null, problems);
  }

  /** Think blocks out, then the outermost braces, which also drops any code fence. */
  static String clean(String raw) {
    if (raw == null) {
      return "";
    }
    String text = THINK_BLOCK.matcher(raw).replaceAll("").strip();
    int open = text.indexOf('{');
    int close = text.lastIndexOf('}');
    if (open >= 0 && close > open) {
      return text.substring(open, close + 1);
    }
    return text;
  }

  /** Which groups took a repair and which were left out; the details are in each group's outcome. */
  private static String note(List<GroupOutcome> outcomes) {
    List<String> parts = new ArrayList<>();
    List<String> repaired = idsWith(outcomes, GroupOutcome.Status.REPAIRED);
    if (!repaired.isEmpty()) {
      parts.add("repaired on the second try: " + String.join(", ", repaired));
    }
    List<String> unusable = idsWith(outcomes, GroupOutcome.Status.UNUSABLE);
    if (!unusable.isEmpty()) {
      parts.add("no usable diagnosis for " + String.join(", ", unusable));
    }
    return parts.isEmpty() ? null : String.join("; ", parts);
  }

  private static List<String> idsWith(List<GroupOutcome> outcomes, GroupOutcome.Status status) {
    return outcomes.stream().filter(outcome -> outcome.status() == status).map(GroupOutcome::groupId).toList();
  }

  private static long elapsedMs(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }

  /** One group's calls. Only its own task touches it while its calls run; each join orders what follows. */
  private static final class GroupRun {

    final FailureReport report;
    final SourceSection section;
    final FailureGroup group;
    final List<Message> messages = new ArrayList<>();
    int attempts;
    long callMs;
    GroupAnswer answer;
    List<String> problems = List.of();
    RuntimeException failure;
    /** The text of the answer kept, to show the model when it is asked again. */
    String raw;
    /** Set when this group was asked again because its diagnosis repeated another's. */
    RepeatedDiagnosisCheck.Repeat repeat;

    GroupRun(FailureReport report, SourceSection section, FailureGroup group) {
      this.report = report;
      this.section = section;
      this.group = group;
    }

    /** No usable answer after all, for this reason, which replaces any earlier ones. */
    void reject(String problem) {
      answer = null;
      problems = List.of(problem);
    }

    GroupOutcome outcome() {
      String problem = Problems.cap(String.join("; ", problems), MAX_PROBLEM_CHARS);
      if (answer != null) {
        return attempts > 1
          ? new GroupOutcome(group.id(), GroupOutcome.Status.REPAIRED, attempts, callMs, problem)
          : new GroupOutcome(group.id(), GroupOutcome.Status.OK, attempts, callMs, null);
      }
      if (attempts == 0) {
        return new GroupOutcome(group.id(), GroupOutcome.Status.SKIPPED, 0, 0, NOT_ASKED);
      }
      if (failure != null) {
        problem = "model unavailable: " + Problems.describe(failure);
      }
      return new GroupOutcome(group.id(), GroupOutcome.Status.UNUSABLE, attempts, callMs, problem);
    }
  }
}
