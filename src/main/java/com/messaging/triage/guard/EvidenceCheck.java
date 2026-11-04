package com.messaging.triage.guard;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks that what a diagnosis gives as evidence is in the group it diagnoses (ADR 0005).
 * <ul>
 *   <li>A quote counts when, once normalized, every part of it (split at "...") occurs in one
 *       of the texts the model was shown for that group, and it holds at least
 *       {@value #MIN_LETTERS} letters or digits besides placeholders.</li>
 *   <li>A count counts when its one number is the group's count and its other words only say
 *       what was counted ("3 failures").</li>
 * </ul>
 * Anything else is unmatched. An answer copied from another group, or swayed into quoting
 * what is not there, shows here, whatever its prose says.
 */
public final class EvidenceCheck {

  static final int MIN_LETTERS = 4;
  /** Matched quotes past this many are dropped; every quote is still checked. */
  static final int MAX_KEPT = 5;

  private static final Pattern QUOTE_MARKS = Pattern.compile("[\\p{Pi}\\p{Pf}\"'`]");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern ELLIPSIS = Pattern.compile("\\.{3,}");
  private static final Pattern EDGES = Pattern.compile("^[\\s.,;:!?()]+|[\\s.,;:!?()]+$");
  /** What the error normalizer and the model view write in place of values, once normalized. */
  private static final Pattern PLACEHOLDER = Pattern.compile("<[a-z]+>|\\[(?:unsafe [a-z ]+|text), \\d+ chars]");
  private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");
  private static final Pattern NUMBER = Pattern.compile("\\d+");
  private static final Set<String> COUNTED = Set.of("failure", "failures", "failed", "message", "messages",
    "notification", "notifications", "occurrence", "occurrences", "time", "times", "in", "of", "the", "this",
    "group", "seen", "count", "total", "all", "x");

  private EvidenceCheck() {
  }

  /**
   * @param matched the quotes that held, as the model wrote them, at most {@value #MAX_KEPT}
   * @param unmatched how many did not, blank and missing ones included
   */
  public record Result(List<String> matched, int unmatched) {

    /** At least one quote held and none failed. */
    public boolean supported() {
      return !matched.isEmpty() && unmatched == 0;
    }
  }

  /**
   * @param evidence what the model gave; null is no evidence
   * @param shown    the texts the model was shown for this group
   * @param count    the group's count
   */
  public static Result check(List<String> evidence, List<String> shown, int count) {
    if (evidence == null) {
      return new Result(List.of(), 0);
    }
    List<String> texts = shown.stream().map(EvidenceCheck::normalize).toList();
    List<String> matched = new ArrayList<>();
    int unmatched = 0;
    for (String quote : evidence) {
      if (quote != null && (isCount(quote, count) || occurs(quote, texts))) {
        if (matched.size() < MAX_KEPT) {
          matched.add(quote.strip());
        }
      } else {
        unmatched++;
      }
    }
    return new Result(List.copyOf(matched), unmatched);
  }

  /** Every part occurs in one of the texts, and the quote says more than its placeholders do. */
  private static boolean occurs(String quote, List<String> texts) {
    List<String> parts = new ArrayList<>();
    for (String part : ELLIPSIS.split(normalize(quote))) {
      String trimmed = EDGES.matcher(part).replaceAll("");
      if (!trimmed.isEmpty()) {
        parts.add(trimmed);
      }
    }
    if (parts.isEmpty() || parts.stream().mapToInt(EvidenceCheck::letters).sum() < MIN_LETTERS) {
      return false;
    }
    return parts.stream().allMatch(part -> texts.stream().anyMatch(text -> text.contains(part)));
  }

  private static boolean isCount(String quote, int count) {
    String[] words = NOT_ALPHANUMERIC.split(normalize(quote).strip());
    int numbers = 0;
    for (String word : words) {
      if (word.isEmpty()) {
        continue;
      }
      if (NUMBER.matcher(word).matches()) {
        numbers++;
        if (numbers > 1 || !word.equals(Integer.toString(count))) {
          return false;
        }
      } else if (!COUNTED.contains(word)) {
        return false;
      }
    }
    return numbers == 1;
  }

  /** Letters and digits outside placeholders. */
  private static int letters(String part) {
    return NOT_ALPHANUMERIC.matcher(PLACEHOLDER.matcher(part).replaceAll("")).replaceAll("").length();
  }

  /** Compatibility forms folded, lower case, quote marks gone, whitespace collapsed. */
  static String normalize(String text) {
    if (text == null) {
      return "";
    }
    String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    folded = QUOTE_MARKS.matcher(folded).replaceAll("");
    return WHITESPACE.matcher(folded).replaceAll(" ").strip();
  }
}
