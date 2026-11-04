package com.messaging.triage.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.messaging.triage.config.TriageProperties.Limits;
import com.messaging.triage.source.Failure;
import com.messaging.triage.source.SourceSection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FailureGrouperTest {

  private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");

  private final FailureGrouper grouper = new FailureGrouper(new Limits(8, 3, 300, 50, 100, 12000, 2));

  @Test
  void normalizesIdsNumbersAndQuotedValuesSoOneCauseIsOneGroup() {
    List<Failure> failures = List.of(
      failure("EMAIL", "Template 'order-refunded' not found for event 1b4e28ba-2fa1-11d2-883f-0016d3cca427", 1),
      failure("EMAIL", "Template 'order-cancelled' not found for event 6fa459ea-ee8a-3ca4-894e-db77e160355e", 2),
      failure("EMAIL", "Template 'x' not found for event 0f8fad5b-d9cb-469f-a165-70867728950e", 3));

    FailureGrouper.Grouping grouping = grouper.group("NP", failures);

    assertThat(grouping.groups()).singleElement().satisfies(group -> {
      assertThat(group.id()).isEqualTo("NP-1");
      assertThat(group.count()).isEqualTo(3);
      assertThat(group.error()).isEqualTo("Template '<value>' not found for event <uuid>");
      assertThat(group.dimensions()).containsEntry("channel", "EMAIL");
    });
  }

  @Test
  void ordersByCountThenKeyAndNumbersFromOne() {
    List<Failure> failures = new ArrayList<>();
    failures.add(failure("SMS", "missing variable orderId", 1));
    failures.add(failure("EMAIL", "unknown template", 2));
    failures.add(failure("EMAIL", "unknown template", 3));
    failures.add(failure("PUSH", "rejected", 4));

    List<FailureGroup> groups = grouper.group("NP", failures).groups();

    assertThat(groups).extracting(FailureGroup::id).containsExactly("NP-1", "NP-2", "NP-3");
    assertThat(groups.get(0).error()).isEqualTo("unknown template");
    // PUSH and SMS tie on one each; the key ({channel=PUSH}|...) sorts before {channel=SMS}.
    assertThat(groups.get(1).dimensions()).containsEntry("channel", "PUSH");
    assertThat(groups.get(2).dimensions()).containsEntry("channel", "SMS");
  }

  @Test
  void sameFailuresInAnyOrderGiveTheSameGroups() {
    List<Failure> failures = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      failures.add(failure(i % 2 == 0 ? "EMAIL" : "SMS", "error kind " + (char) ('a' + i % 5), i));
    }
    List<Failure> shuffled = new ArrayList<>(failures);
    Collections.shuffle(shuffled, new java.util.Random(7));

    assertThat(grouper.group("NP", shuffled)).isEqualTo(grouper.group("NP", failures));
  }

  @Test
  void mergesPastMaxGroupsIntoOneOtherGroupAndSaysSo() {
    List<Failure> failures = new ArrayList<>();
    // Ten distinct errors; the first has three failures so it stays on top.
    for (int i = 0; i < 3; i++) {
      failures.add(failure("EMAIL", "big problem", i));
    }
    for (char c = 'a'; c <= 'i'; c++) {
      failures.add(failure("EMAIL", "small problem " + c, 10 + c));
    }

    FailureGrouper.Grouping grouping = grouper.group("NP", failures);

    assertThat(grouping.groups()).hasSize(8);
    FailureGroup other = grouping.groups().get(7);
    assertThat(other.id()).isEqualTo("NP-8");
    assertThat(other.merged()).isTrue();
    assertThat(other.dimensions()).isEqualTo(Map.of(FailureGrouper.MERGED_DIMENSION, FailureGrouper.MERGED_VALUE));
    assertThat(other.count()).isEqualTo(3);
    assertThat(other.error()).isEqualTo("3 smaller groups with different errors, merged");
    assertThat(grouping.groups().stream().mapToInt(FailureGroup::count).sum()).isEqualTo(failures.size());
    assertThat(grouping.notes()).anyMatch(note -> note.contains("merged into NP-8"));
  }

  @Test
  void samplesPreferPayloadShapesNotYetShownThenNewest() {
    List<Failure> failures = List.of(
      mi("{\"orderId\":1,\"qty\":-1}", Set.of("orderId", "qty"), 5),
      mi("{\"orderId\":2,\"qty\":-2}", Set.of("orderId", "qty"), 4),
      mi("{\"orderId\":3,\"qty\":-3}", Set.of("orderId", "qty"), 3),
      mi("<order/>", Set.of(), 1));

    FailureGroup group = grouper.group("MI", failures).groups().getFirst();

    assertThat(group.samples()).hasSize(3);
    // Newest of each shape first, then the newest remaining; shown newest first.
    assertThat(group.samples()).extracting(Sample::payload)
      .containsExactly("{\"orderId\":1,\"qty\":-1}", "{\"orderId\":2,\"qty\":-2}", "<order/>");
  }

  @Test
  void cutsLongTextAndRecordsIt() {
    String longError = "x".repeat(400);
    FailureGrouper.Grouping grouping = grouper.group("NP", List.of(failure("EMAIL", longError, 1)));

    FailureGroup group = grouping.groups().getFirst();
    assertThat(group.error()).hasSize(303).endsWith("...");
    assertThat(group.samples().getFirst().error()).hasSize(303);
    assertThat(grouping.notes()).anyMatch(note -> note.contains("error text cut to 300"));
  }

  @Test
  void fitToBudgetDropsSamplesKeepingOnePerGroupFirst() {
    List<Failure> failures = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      failures.add(mi("p".repeat(250) + i, Set.of("k" + i), i));
      failures.add(withQueue(mi("q".repeat(250) + i, Set.of("j" + i), i), "ExpiryQueue"));
    }
    List<FailureGroup> groups = grouper.group("MI", failures).groups();
    SourceSection section = new SourceSection("message-inspector", "MI", SourceSection.Status.OK, null, Map.of(),
      failures.size(), groups);

    FailureGrouper.BudgetFit fit = grouper.fitToBudget(List.of(section), 1200);

    List<FailureGroup> fitted = fit.sections().getFirst().groups();
    assertThat(fitted).allSatisfy(group -> assertThat(group.samples()).isNotEmpty());
    assertThat(fitted.stream().mapToInt(group -> group.samples().size()).sum()).isLessThan(6);
    assertThat(fit.notes()).singleElement().asString().contains("prompt budget of 1200 characters");
  }

  @Test
  void fitToBudgetLeavesAReportThatFitsAlone() {
    List<FailureGroup> groups = grouper.group("NP", List.of(failure("EMAIL", "small", 1))).groups();
    SourceSection section = new SourceSection("notification-platform", "NP", SourceSection.Status.OK, null,
      Map.of(), 1, groups);

    FailureGrouper.BudgetFit fit = grouper.fitToBudget(List.of(section), 12000);

    assertThat(fit.sections()).containsExactly(section);
    assertThat(fit.notes()).isEmpty();
  }

  private static Failure failure(String channel, String error, int minutes) {
    return new Failure(Map.of("channel", channel), error, "ref-" + minutes, T0.plusSeconds(60L * minutes), null,
      Set.of(), Map.of());
  }

  private static Failure mi(String payload, Set<String> keys, int minutes) {
    return new Failure(Map.of("queue", "DLQ", "origin", "orders"), "invalid quantity", "ID:" + minutes,
      T0.plusSeconds(60L * minutes), payload, keys, Map.of());
  }

  private static Failure withQueue(Failure failure, String queue) {
    return new Failure(Map.of("queue", queue, "origin", "orders"), failure.error(), failure.reference(),
      failure.at(), failure.payload(), failure.payloadKeys(), failure.attributes());
  }
}
