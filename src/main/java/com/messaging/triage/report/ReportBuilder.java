package com.messaging.triage.report;

import com.messaging.triage.source.FailureSource;
import com.messaging.triage.source.FailureSource.Gathered;
import com.messaging.triage.source.SourceSection;
import com.messaging.triage.support.Problems;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks every source in turn, groups what each returned and fits the result to the prompt
 * budget. A source that throws becomes an UNAVAILABLE section; the others still report.
 */
@Slf4j
public class ReportBuilder {

  private final List<FailureSource> sources;
  private final FailureGrouper grouper;
  private final int promptBudgetChars;
  private final Clock clock;

  public ReportBuilder(List<FailureSource> sources, FailureGrouper grouper, int promptBudgetChars, Clock clock) {
    this.sources = List.copyOf(sources);
    this.grouper = grouper;
    this.promptBudgetChars = promptBudgetChars;
    this.clock = clock;
  }

  public FailureReport build() {
    List<SourceSection> sections = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    for (FailureSource source : sources) {
      try {
        Gathered gathered = source.gather();
        FailureGrouper.Grouping grouping = grouper.group(source.id(), gathered.failures());
        sections.add(new SourceSection(source.name(), source.id(), SourceSection.Status.OK, null,
          new LinkedHashMap<>(gathered.counts()), gathered.failures().size(), grouping.groups()));
        notes.addAll(gathered.notes());
        notes.addAll(grouping.notes());
        log.info("{}: {} failures in {} groups", source.name(), gathered.failures().size(), grouping.groups().size());
      } catch (RuntimeException e) {
        String reason = Problems.describe(e);
        log.warn("{}: unavailable: {}", source.name(), reason);
        sections.add(SourceSection.unavailable(source, reason));
      }
    }
    FailureGrouper.BudgetFit fit = grouper.fitToBudget(sections, promptBudgetChars);
    notes.addAll(fit.notes());
    return new FailureReport(clock.instant(), fit.sections(), List.copyOf(notes));
  }
}
