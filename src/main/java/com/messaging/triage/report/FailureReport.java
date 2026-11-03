package com.messaging.triage.report;

import com.messaging.triage.source.SourceSection;
import java.time.Instant;
import java.util.List;

/**
 * What was found, before any model sees it. Complete on its own: a triage whose model call
 * failed still returns this, so the deterministic half of the work is never lost.
 *
 * @param truncation every cap that bit, in plain words; empty when nothing was cut
 */
public record FailureReport(Instant gatheredAt, List<SourceSection> sources, List<String> truncation) {

  public List<FailureGroup> allGroups() {
    return sources.stream().flatMap(section -> section.groups().stream()).toList();
  }

  public List<String> groupIds() {
    return allGroups().stream().map(FailureGroup::id).toList();
  }
}
