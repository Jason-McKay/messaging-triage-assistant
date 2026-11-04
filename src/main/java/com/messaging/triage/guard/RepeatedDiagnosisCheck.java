package com.messaging.triage.guard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds a diagnosis given to two groups that did not fail the same way (ADR 0005). Groups are
 * split by their error, so one cause, or one next step, for two different errors is more
 * likely a copy than a finding.
 * <ul>
 *   <li>Two texts are the same when their words, once normalized and with filler words left
 *       out, overlap by {@value #SAME_DIAGNOSIS} or more (Jaccard). Negations are kept, so
 *       "is missing" and "is not missing" stay apart.</li>
 *   <li>Two errors differ when their words overlap by less than {@value #SAME_ERROR}. The same
 *       error on two channels, or with two keys, may share a cause.</li>
 *   <li>A merged group holds several errors, so it is never compared.</li>
 * </ul>
 */
public final class RepeatedDiagnosisCheck {

  public static final String CAUSE = "probableCause";
  public static final String NEXT_STEP = "suggestedNextStep";
  static final double SAME_DIAGNOSIS = 0.8;
  static final double SAME_ERROR = 0.5;

  private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");
  /** Words that say nothing about a cause. No negation is here, on purpose. */
  private static final Set<String> FILLER = Set.of("a", "an", "the", "is", "are", "was", "were", "be", "been",
    "being", "of", "to", "in", "on", "for", "at", "by", "with", "from", "and", "or", "as", "that", "this", "these",
    "those", "it", "its", "which", "has", "have", "had", "likely", "probably", "possibly", "most", "may", "might",
    "could", "appears", "seems", "because", "due", "so");

  private RepeatedDiagnosisCheck() {
  }

  /** One group's diagnosis, with the error the model was shown for it. */
  public record Diagnosis(String id, String cause, String nextStep, String error, boolean merged) {
  }

  /** {@code id}'s {@code field} repeats {@code otherId}'s, which reads {@code otherText}. */
  public record Repeat(String id, String otherId, String field, String otherText) {
  }

  /**
   * One repeat for each group whose diagnosis repeats another's, naming the first it repeats,
   * in the order the groups were given. The cause is compared before the next step.
   */
  public static List<Repeat> find(List<Diagnosis> diagnoses) {
    List<Repeat> repeats = new ArrayList<>();
    for (Diagnosis diagnosis : diagnoses) {
      if (diagnosis.merged()) {
        continue;
      }
      for (Diagnosis other : diagnoses) {
        if (other == diagnosis || other.merged() || !differ(diagnosis.error(), other.error())) {
          continue;
        }
        if (same(diagnosis.cause(), other.cause())) {
          repeats.add(new Repeat(diagnosis.id(), other.id(), CAUSE, other.cause()));
          break;
        }
        if (same(diagnosis.nextStep(), other.nextStep())) {
          repeats.add(new Repeat(diagnosis.id(), other.id(), NEXT_STEP, other.nextStep()));
          break;
        }
      }
    }
    return List.copyOf(repeats);
  }

  static boolean same(String one, String other) {
    if (one == null || other == null) {
      return false;
    }
    Set<String> words = words(one);
    Set<String> otherWords = words(other);
    if (words.isEmpty() || otherWords.isEmpty()) {
      return EvidenceCheck.normalize(one).equals(EvidenceCheck.normalize(other));
    }
    return overlap(words, otherWords) >= SAME_DIAGNOSIS;
  }

  static boolean differ(String error, String otherError) {
    return overlap(words(error), words(otherError)) < SAME_ERROR;
  }

  /** Jaccard: shared words over all words; two empty sets are the same. */
  static double overlap(Set<String> words, Set<String> otherWords) {
    if (words.isEmpty() && otherWords.isEmpty()) {
      return 1;
    }
    Set<String> shared = new HashSet<>(words);
    shared.retainAll(otherWords);
    Set<String> all = new HashSet<>(words);
    all.addAll(otherWords);
    return (double) shared.size() / all.size();
  }

  static Set<String> words(String text) {
    return Arrays.stream(NOT_ALPHANUMERIC.split(EvidenceCheck.normalize(text)))
      .filter(word -> !word.isEmpty() && !FILLER.contains(word))
      .collect(Collectors.toSet());
  }
}
