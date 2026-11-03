package com.messaging.triage.report;

import com.messaging.triage.config.TriageProperties.Limits;
import com.messaging.triage.source.Failure;
import com.messaging.triage.source.SourceSection;
import com.messaging.triage.support.Problems;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Groups failures and cuts them down to what fits a prompt. Pure: no I/O, no clock, and the
 * same failures in any order give the same groups with the same ids.
 * <ul>
 *   <li>A group is the failure's dimensions plus its {@linkplain ErrorTextNormalizer
 *       normalized} error.</li>
 *   <li>Groups are ordered by count, largest first, ties broken by their key, and numbered
 *       {@code <source>-1} upward.</li>
 *   <li>Past {@code maxGroups} per source, the smallest groups are merged into one OTHER group,
 *       so a source never yields more than {@code maxGroups}.</li>
 *   <li>Up to {@code samplesPerGroup} samples each, newest first, preferring samples whose
 *       payloads have a JSON key set not yet shown (a different shape is more telling than a
 *       second copy of the same one).</li>
 *   <li>Error text and payload excerpts are cut to {@code maxTextChars}.</li>
 * </ul>
 * Every cut is written into the notes.
 */
public final class FailureGrouper {

  public static final String MERGED_DIMENSION = "group";
  public static final String MERGED_VALUE = "OTHER";

  private static final Comparator<Failure> NEWEST_FIRST = Comparator
    .comparing(Failure::at, Comparator.nullsLast(Comparator.reverseOrder()))
    .thenComparing(Failure::reference, Comparator.nullsLast(Comparator.naturalOrder()));

  private final Limits limits;

  public FailureGrouper(Limits limits) {
    this.limits = limits;
  }

  public record Grouping(List<FailureGroup> groups, List<String> notes) {
  }

  public record BudgetFit(List<SourceSection> sections, List<String> notes) {
  }

  public Grouping group(String sourceId, List<Failure> failures) {
    Map<Key, List<Failure>> byKey = new LinkedHashMap<>();
    for (Failure failure : failures) {
      Key key = new Key(failure.dimensions(), ErrorTextNormalizer.normalize(failure.error()));
      byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(failure);
    }

    List<Map.Entry<Key, List<Failure>>> ordered = new ArrayList<>(byKey.entrySet());
    ordered.sort(Comparator
      .comparing((Map.Entry<Key, List<Failure>> entry) -> entry.getValue().size(), Comparator.reverseOrder())
      .thenComparing(entry -> entry.getKey().sortKey()));

    int keep = ordered.size() > limits.maxGroups() ? limits.maxGroups() - 1 : ordered.size();
    Cuts cuts = new Cuts(limits.maxTextChars());
    List<FailureGroup> groups = new ArrayList<>();
    for (int i = 0; i < keep; i++) {
      Map.Entry<Key, List<Failure>> entry = ordered.get(i);
      List<Failure> members = entry.getValue();
      groups.add(new FailureGroup(
        sourceId + "-" + (i + 1),
        entry.getKey().dimensions(),
        cuts.error(entry.getKey().error()),
        members.size(),
        earliest(members),
        latest(members),
        false,
        samples(members, cuts)));
    }

    List<String> notes = new ArrayList<>();
    List<Map.Entry<Key, List<Failure>>> rest = ordered.subList(keep, ordered.size());
    if (!rest.isEmpty()) {
      List<Failure> merged = rest.stream().flatMap(entry -> entry.getValue().stream()).toList();
      // One sample from each merged group first, so OTHER shows its variety.
      List<Failure> representatives = new ArrayList<>();
      for (Map.Entry<Key, List<Failure>> entry : rest) {
        representatives.add(entry.getValue().stream().sorted(NEWEST_FIRST).findFirst().orElseThrow());
      }
      String id = sourceId + "-" + (keep + 1);
      groups.add(new FailureGroup(
        id,
        Map.of(MERGED_DIMENSION, MERGED_VALUE),
        rest.size() + " smaller groups with different errors, merged",
        merged.size(),
        earliest(merged),
        latest(merged),
        true,
        samples(representatives, cuts)));
      notes.add(sourceId + ": " + rest.size() + " smaller groups (" + merged.size() + " failures) merged into "
        + id + " to stay within " + limits.maxGroups() + " groups");
    }
    if (cuts.errors > 0) {
      notes.add(sourceId + ": error text cut to " + limits.maxTextChars() + " characters in " + cuts.errors
        + " places");
    }
    if (cuts.payloads > 0) {
      notes.add(sourceId + ": payload excerpts cut to " + limits.maxTextChars() + " characters in "
        + cuts.payloads + " samples");
    }
    return new Grouping(List.copyOf(groups), List.copyOf(notes));
  }

  /**
   * Drops samples until the report's sample text fits {@code budgetChars}. Takes from the
   * group holding the most samples first (the later, smaller group on a tie), keeping one per
   * group while it can; only then drops last samples, smallest groups first.
   */
  public BudgetFit fitToBudget(List<SourceSection> sections, int budgetChars) {
    List<List<List<Sample>>> samples = new ArrayList<>();
    int total = 0;
    for (SourceSection section : sections) {
      List<List<Sample>> perGroup = new ArrayList<>();
      for (FailureGroup group : section.groups()) {
        perGroup.add(new ArrayList<>(group.samples()));
        total += group.error().length() + group.samples().stream().mapToInt(Sample::size).sum();
      }
      samples.add(perGroup);
    }
    if (total <= budgetChars) {
      return new BudgetFit(sections, List.of());
    }

    int dropped = 0;
    for (int floor : new int[] {1, 0}) {
      while (total > budgetChars) {
        List<Sample> fattest = null;
        for (List<List<Sample>> perGroup : samples) {
          for (List<Sample> groupSamples : perGroup) {
            if (groupSamples.size() > floor && (fattest == null || groupSamples.size() >= fattest.size())) {
              fattest = groupSamples;
            }
          }
        }
        if (fattest == null) {
          break;
        }
        total -= fattest.removeLast().size();
        dropped++;
      }
    }

    List<SourceSection> fitted = new ArrayList<>();
    for (int s = 0; s < sections.size(); s++) {
      SourceSection section = sections.get(s);
      List<FailureGroup> groups = new ArrayList<>();
      for (int g = 0; g < section.groups().size(); g++) {
        groups.add(section.groups().get(g).withSamples(List.copyOf(samples.get(s).get(g))));
      }
      fitted.add(section.withGroups(List.copyOf(groups)));
    }
    return new BudgetFit(List.copyOf(fitted), List.of(
      "prompt budget of " + budgetChars + " characters: " + dropped + " samples dropped"));
  }

  private List<Sample> samples(List<Failure> members, Cuts cuts) {
    List<Failure> newestFirst = members.stream().sorted(NEWEST_FIRST).toList();
    List<Failure> chosen = new ArrayList<>();
    Set<Failure> picked = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<Set<String>> shapesShown = new HashSet<>();
    for (Failure failure : newestFirst) {
      if (chosen.size() == limits.samplesPerGroup()) {
        break;
      }
      if (shapesShown.add(keys(failure)) && picked.add(failure)) {
        chosen.add(failure);
      }
    }
    for (Failure failure : newestFirst) {
      if (chosen.size() == limits.samplesPerGroup()) {
        break;
      }
      if (picked.add(failure)) {
        chosen.add(failure);
      }
    }
    chosen.sort(NEWEST_FIRST);
    return chosen.stream()
      .map(failure -> new Sample(
        failure.reference(),
        failure.at(),
        cuts.error(failure.error()),
        cuts.payload(failure.payload()),
        failure.attributes() == null ? Map.of() : failure.attributes()))
      .toList();
  }

  private static Set<String> keys(Failure failure) {
    return failure.payloadKeys() == null ? Set.of() : failure.payloadKeys();
  }

  private static Instant earliest(List<Failure> failures) {
    return failures.stream().map(Failure::at).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
  }

  private static Instant latest(List<Failure> failures) {
    return failures.stream().map(Failure::at).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
  }

  private record Key(Map<String, String> dimensions, String error) {

    String sortKey() {
      return dimensions + "|" + error;
    }
  }

  /** Cuts text to the limit and counts how often it had to. */
  private static final class Cuts {

    private final int maxChars;
    private int errors;
    private int payloads;

    Cuts(int maxChars) {
      this.maxChars = maxChars;
    }

    String error(String text) {
      if (text != null && text.length() > maxChars) {
        errors++;
      }
      return Problems.cap(text, maxChars);
    }

    String payload(String text) {
      if (text != null && text.length() > maxChars) {
        payloads++;
      }
      return Problems.cap(text, maxChars);
    }
  }
}
