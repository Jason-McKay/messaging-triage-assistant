package com.messaging.triage.triage;

import com.messaging.triage.report.FailureReport;
import com.messaging.triage.support.Problems;
import java.util.ArrayList;
import java.util.List;
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
 * Asks the model to explain a report, and decides whether the answer can be used.
 * <ol>
 *   <li>No groups: the model is not called.</li>
 *   <li>One call with no tools, the output constrained to the answer's JSON schema
 *       (Ollama's {@code format}), thinking off.</li>
 *   <li>Think blocks and code fences stripped, then parsed and checked against the report
 *       ({@link AnswerValidator}).</li>
 *   <li>If that fails, one repair call that shows the model its answer and what was wrong.</li>
 *   <li>Still unusable: {@code usable=false} with the reason. The model failing outright is
 *       "model unavailable", not an error: the report is still worth returning.</li>
 * </ol>
 * {@code ChatModel} directly rather than {@code ChatClient}: the client renders prompt text
 * as a template, and the schema's braces break the renderer (docs/gotchas.md).
 */
@Slf4j
public class Triager {

  static final String NOTHING_TO_TRIAGE = "nothing to triage";
  static final int MAX_ECHOED_ANSWER_CHARS = 4000;
  static final double TEMPERATURE = 0.1;

  private static final Pattern THINK_BLOCK = Pattern.compile("(?s)<think>.*?</think>");

  private final ChatModel chatModel;
  private final String model;
  private final AnswerValidator validator;
  private final BeanOutputConverter<TriageAnswer> converter;
  private final TriagePrompt prompt;

  public Triager(ChatModel chatModel, String model, AnswerValidator validator, int maxValueChars) {
    this.chatModel = chatModel;
    this.model = model;
    this.validator = validator;
    // Lenient on purpose: an answer with "high" or an extra field is still the answer.
    JsonMapper lenient = JsonMapper.builder()
      .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();
    this.converter = new BeanOutputConverter<>(TriageAnswer.class, lenient);
    this.prompt = new TriagePrompt(converter.getJsonSchema(), maxValueChars);
  }

  public record Verdict(TriageAnswer answer, ModelOutcome outcome) {
  }

  public Verdict triage(FailureReport report) {
    List<String> groupIds = report.groupIds();
    if (groupIds.isEmpty()) {
      return new Verdict(null, new ModelOutcome(true, model, null, NOTHING_TO_TRIAGE, 0, 0));
    }
    List<Message> messages = new ArrayList<>(List.of(prompt.system(), prompt.user(report)));
    long started = System.nanoTime();
    int attempts = 0;
    try {
      attempts++;
      String first = call(messages);
      Parsed parsed = parse(first, groupIds);
      if (parsed.usable()) {
        return usable(parsed.answer(), null, attempts, started);
      }
      log.info("triage: first answer unusable ({} problems); asking once more", parsed.problems().size());
      messages.add(new AssistantMessage(Problems.cap(first, MAX_ECHOED_ANSWER_CHARS)));
      messages.add(prompt.repair(parsed.problems(), groupIds));
      attempts++;
      Parsed repaired = parse(call(messages), groupIds);
      if (repaired.usable()) {
        return usable(repaired.answer(), "repaired on the second try; the first answer had: "
          + Problems.cap(String.join("; ", parsed.problems()), 300), attempts, started);
      }
      return unusable("model answer unusable: " + Problems.cap(String.join("; ", repaired.problems()), 300),
        attempts, started);
    } catch (RuntimeException e) {
      log.warn("triage: model call failed: {}", Problems.describe(e));
      return unusable("model unavailable: " + Problems.describe(e), attempts, started);
    }
  }

  private String call(List<Message> messages) {
    OllamaChatOptions options = OllamaChatOptions.builder()
      .model(model)
      .temperature(TEMPERATURE)
      .format(converter.getJsonSchemaMap())
      .disableThinking()
      .build();
    ChatResponse response = chatModel.call(new Prompt(List.copyOf(messages), options));
    if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
      return "";
    }
    String text = response.getResult().getOutput().getText();
    return text == null ? "" : text;
  }

  record Parsed(TriageAnswer answer, List<String> problems) {

    boolean usable() {
      return answer != null && problems.isEmpty();
    }
  }

  Parsed parse(String raw, List<String> groupIds) {
    String json = clean(raw);
    if (json.isBlank()) {
      return new Parsed(null, List.of("the answer is empty"));
    }
    TriageAnswer answer;
    try {
      answer = converter.convert(json);
    } catch (RuntimeException e) {
      return new Parsed(null, List.of("not a JSON object matching the schema (" + Problems.describe(e) + ")"));
    }
    List<String> problems = validator.problems(answer, groupIds);
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

  private Verdict usable(TriageAnswer answer, String note, int attempts, long started) {
    return new Verdict(answer, new ModelOutcome(true, model, null, note, attempts, elapsedMs(started)));
  }

  private Verdict unusable(String problem, int attempts, long started) {
    return new Verdict(null, new ModelOutcome(false, model, problem, null, attempts, elapsedMs(started)));
  }

  private static long elapsedMs(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }
}
