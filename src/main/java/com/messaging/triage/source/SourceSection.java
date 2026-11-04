package com.messaging.triage.source;

import com.messaging.triage.report.FailureGroup;
import java.util.List;
import java.util.Map;

/**
 * One source's part of a report. An UNAVAILABLE section has a reason and no groups, and is
 * still in the report: "could not look" must not read as "nothing wrong".
 */
public record SourceSection(
  String source,
  String id,
  Status status,
  String reason,
  Map<String, Long> counts,
  int failuresSeen,
  List<FailureGroup> groups) {

  public enum Status { OK, UNAVAILABLE }

  public static SourceSection unavailable(FailureSource source, String reason) {
    return new SourceSection(source.name(), source.id(), Status.UNAVAILABLE, reason, Map.of(), 0, List.of());
  }

  public SourceSection withGroups(List<FailureGroup> replaced) {
    return new SourceSection(source, id, status, reason, counts, failuresSeen, replaced);
  }
}
