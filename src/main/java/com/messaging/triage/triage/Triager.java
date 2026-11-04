package com.messaging.triage.triage;

import com.messaging.triage.report.FailureGroup;
import com.messaging.triage.report.FailureReport;
import com.messaging.triage.source.SourceSection;
import com.messaging.triage.support.Problems;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
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
      runs.stream()
        .map(run -> CompletableFuture.runAsync(() -> diagnose(run, failure), executor))
        .toList()
        .forEach(CompletableFuture::join);
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
      .map(run -> GroupDiagnosis.of(run.group.id(), run.answer))
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
    if (!repaired.usable()) {
      run.problems = repaired.problems();
    }
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

  /** One group's calls. Only its own task touches it until the join, which orders what follows. */
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

    GroupRun(FailureReport report, SourceSection section, FailureGroup group) {
      this.report = report;
      this.section = section;
      this.group = group;
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
