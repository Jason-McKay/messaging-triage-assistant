package com.messaging.triage.triage;

import com.messaging.triage.report.ErrorTextNormalizer;
import com.messaging.triage.report.FailureGroup;
import com.messaging.triage.report.Sample;
import com.messaging.triage.source.mi.MessageInspectorGatherer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the model is shown of values a client controls (ADR 0006). The report keeps every
 * value as gathered, for the operator; the model gets a copy in which:
 * <ul>
 *   <li>a dimension or attribute value that is not a plain identifier (a template key with
 *       spaces in it, say) becomes a placeholder naming its length, and so does every place
 *       it occurs in the group's error, sample errors and payloads;</li>
 *   <li>a string inside a JSON payload that is long or wordy becomes a placeholder naming its
 *       length, so the payload keeps its shape and loses its prose.</li>
 * </ul>
 * The error text itself is evidence and stays, fenced (ADR 0003). Payloads that are not JSON
 * (XML, plain text) stay too.
 */
public final class ModelView {

  static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
  static final int MAX_PLAIN_CHARS = 64;
  static final int MAX_PLAIN_WORDS = 6;
  /** Shorter unsafe values are still replaced as values, but not searched for in text. */
  static final int MIN_REPLACED_CHARS = 4;
  /** A value cut short in text is still found, if this much of it is left before the "...". */
  static final int MIN_CUT_PREFIX_CHARS = 12;
  static final String ELLIPSIS = "...";

  /** Values this app writes itself, which are not identifiers but carry nothing a client chose. */
  private static final Set<String> CODE_WRITTEN = Set.of(MessageInspectorGatherer.UNKNOWN_ORIGIN);
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\[(?:unsafe [a-z ]+|text), \\d+ chars]");
  private static final JsonMapper JSON = JsonMapper.shared();

  private ModelView() {
  }

  /** The value if it is a plain identifier, else a placeholder such as {@code [unsafe template key, 104 chars]}. */
  public static String identifier(String name, String value) {
    if (value == null || CODE_WRITTEN.contains(value) || SAFE_IDENTIFIER.matcher(value).matches()) {
      return value;
    }
    return placeholder(name, value);
  }

  /** True for a placeholder this class wrote, which is not evidence of anything. */
  public static boolean isPlaceholder(String text) {
    return text != null && PLACEHOLDER.matcher(text.strip()).matches();
  }

  /** The group as the model sees it: unsafe values abstracted, JSON prose abstracted. */
  public static FailureGroup group(FailureGroup group) {
    Map<String, String> replacements = new LinkedHashMap<>();
    Map<String, String> dimensions = abstracted(group.dimensions(), replacements);
    List<Sample> samples = new ArrayList<>(group.samples().size());
    List<Map<String, String>> attributes = new ArrayList<>(group.samples().size());
    for (Sample sample : group.samples()) {
      attributes.add(abstracted(sample.attributes(), replacements));
    }
    for (int i = 0; i < group.samples().size(); i++) {
      Sample sample = group.samples().get(i);
      samples.add(new Sample(sample.reference(), sample.at(), replaced(sample.error(), replacements),
        payload(replaced(sample.payload(), replacements)), attributes.get(i)));
    }
    return new FailureGroup(group.id(), dimensions, replaced(group.error(), replacements), group.count(),
      group.firstSeen(), group.lastSeen(), group.merged(), samples);
  }

  /**
   * The texts the model is shown for a group, squashed as the prompt shows them, for checking
   * what it quotes as evidence: dimensions, the error, and each sample's error, payload and
   * attributes. Times and references are left out; they say when and which, not why.
   */
  static List<String> shownTexts(FailureGroup group) {
    FailureGroup shown = group(group);
    List<String> texts = new ArrayList<>();
    shown.dimensions().forEach((name, value) -> texts.add(name + "=" + value));
    texts.add(shown.error());
    for (Sample sample : shown.samples()) {
      texts.add(sample.error());
      texts.add(sample.payload());
      sample.attributes().forEach((name, value) -> texts.add(name + "=" + value));
    }
    return texts.stream().filter(Objects::nonNull).map(ModelView::squash).toList();
  }

  /**
   * A payload with every long or wordy JSON string replaced by {@code [text, N chars]}. Only
   * a payload that starts like JSON is touched; a cut-off one is handled too, its last string
   * left open as it was.
   */
  public static String payload(String payload) {
    if (payload == null || !startsLikeJson(payload)) {
      return payload;
    }
    StringBuilder out = new StringBuilder(payload.length());
    int at = 0;
    while (at < payload.length()) {
      char c = payload.charAt(at);
      if (c != '"') {
        out.append(c);
        at++;
        continue;
      }
      int end = at + 1;
      while (end < payload.length() && payload.charAt(end) != '"') {
        end += payload.charAt(end) == '\\' ? 2 : 1;
      }
      String content = payload.substring(at + 1, Math.min(end, payload.length()));
      out.append('"').append(isProse(content) ? "[text, " + content.length() + " chars]" : content);
      if (end < payload.length()) {
        out.append('"');
      }
      at = end + 1;
    }
    return out.toString();
  }

  /**
   * A chat tool's output with every {@code payload} field abstracted, at any depth and
   * inside JSON carried as a string (MCP wraps its JSON in text content). Output that is not
   * JSON, or has no payload to abstract, is returned exactly as it came.
   */
  public static String toolOutput(String output) {
    if (output == null || !startsLikeJson(output)) {
      return output;
    }
    try {
      JsonNode root = JSON.readTree(output);
      return walk(root) ? JSON.writeValueAsString(root) : output;
    } catch (JacksonException e) {
      return output;
    }
  }

  /** The text with a value {@link #identifier} replaced taken out of it as well. */
  public static String replaced(String text, String value, String shown) {
    return value == null || value.equals(shown) ? text : replaced(text, Map.of(value, shown));
  }

  private static Map<String, String> abstracted(Map<String, String> values, Map<String, String> replacements) {
    Map<String, String> view = new LinkedHashMap<>();
    values.forEach((name, value) -> {
      String shown = identifier(words(name), value);
      if (value != null && !value.equals(shown)) {
        replacements.putIfAbsent(value, shown);
      }
      view.put(name, shown);
    });
    return view;
  }

  /** Every replaced value taken out of the text; the text unchanged when none occurs in it. */
  private static String replaced(String text, Map<String, String> replacements) {
    if (text == null || replacements.isEmpty()) {
      return text;
    }
    String view = squash(text);
    boolean changed = false;
    for (Map.Entry<String, String> replacement : replacements.entrySet()) {
      for (String form : forms(replacement.getKey())) {
        if (view.contains(form)) {
          view = view.replace(form, replacement.getValue());
          changed = true;
        }
      }
      String cut = cutPrefix(view, squash(replacement.getKey()));
      if (cut != null) {
        view = view.substring(0, view.length() - ELLIPSIS.length() - cut.length()) + replacement.getValue()
          + ELLIPSIS;
        changed = true;
      }
    }
    return changed ? view : text;
  }

  /** The value as it can appear in text: as gathered, and as the error normalizer leaves it. */
  private static Set<String> forms(String value) {
    Set<String> forms = new LinkedHashSet<>();
    for (String form : List.of(squash(value), ErrorTextNormalizer.normalize(squash(value)))) {
      if (form.length() >= MIN_REPLACED_CHARS) {
        forms.add(form);
      }
    }
    return forms;
  }

  /** The start of the value, if the text ends with it and then "...", having been cut there. */
  private static String cutPrefix(String text, String value) {
    if (!text.endsWith(ELLIPSIS)) {
      return null;
    }
    String body = text.substring(0, text.length() - ELLIPSIS.length());
    for (int length = Math.min(value.length() - 1, body.length()); length >= MIN_CUT_PREFIX_CHARS; length--) {
      String prefix = value.substring(0, length);
      if (body.endsWith(prefix)) {
        return prefix;
      }
    }
    return null;
  }

  /** True when anything under the node changed. */
  private static boolean walk(JsonNode node) {
    boolean changed = false;
    if (node instanceof ObjectNode object) {
      for (String name : List.copyOf(object.propertyNames())) {
        JsonNode child = object.get(name);
        if (child.isString()) {
          String text = child.stringValue();
          String view = "payload".equals(name) ? payload(text) : nested(text);
          if (!view.equals(text)) {
            object.put(name, view);
            changed = true;
          }
        } else {
          changed |= walk(child);
        }
      }
    } else if (node instanceof ArrayNode array) {
      for (int i = 0; i < array.size(); i++) {
        JsonNode child = array.get(i);
        if (child.isString()) {
          String text = child.stringValue();
          String view = nested(text);
          if (!view.equals(text)) {
            array.set(i, view);
            changed = true;
          }
        } else {
          changed |= walk(child);
        }
      }
    }
    return changed;
  }

  /** A string that holds JSON of its own, walked and written back if anything changed. */
  private static String nested(String text) {
    String view = toolOutput(text);
    return view == null ? text : view;
  }

  private static boolean isProse(String content) {
    String trimmed = content.strip();
    return content.length() > MAX_PLAIN_CHARS
      || !trimmed.isEmpty() && WHITESPACE.split(trimmed).length > MAX_PLAIN_WORDS;
  }

  private static boolean startsLikeJson(String text) {
    String start = text.stripLeading();
    return start.startsWith("{") || start.startsWith("[");
  }

  private static String placeholder(String name, String value) {
    return "[unsafe " + name + ", " + value.length() + " chars]";
  }

  /** {@code templateKey} as "template key", for a placeholder a model can read. */
  private static String words(String name) {
    return name.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("[^A-Za-z ]", "").toLowerCase();
  }

  /** Hidden characters and fence markers gone, whitespace collapsed, as the prompt will show it. */
  static String squash(String text) {
    return WHITESPACE.matcher(Untrusted.neutralize(text, Integer.MAX_VALUE)).replaceAll(" ").strip();
  }
}
