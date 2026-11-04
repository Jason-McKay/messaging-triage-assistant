package com.messaging.triage.triage;

import com.messaging.triage.report.FailureGroup;
import com.messaging.triage.report.FailureReport;
import com.messaging.triage.source.SourceSection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The triage summary, written by code rather than the model (ADR 0004). It was the one field
 * that saw every group at once, which is where an injection in one group spoke for all of
 * them. It names sources, ids and counts only, never a value from the systems, so nothing a
 * client wrote is echoed.
 */
final class TriageSummary {

  private TriageSummary() {
  }

  static String write(FailureReport report, List<GroupDiagnosis> diagnoses) {
    StringBuilder text = new StringBuilder();
    for (SourceSection section : report.sources()) {
      if (!text.isEmpty()) {
        text.append(' ');
      }
      text.append(section(section));
    }
    Set<String> diagnosed = diagnoses.stream().map(GroupDiagnosis::groupId).collect(Collectors.toSet());
    List<String> undiagnosed = report.groupIds().stream().filter(id -> !diagnosed.contains(id)).toList();
    if (!undiagnosed.isEmpty()) {
      text.append(" No usable diagnosis for ").append(String.join(", ", undiagnosed)).append('.');
    }
    return text.toString();
  }

  private static String section(SourceSection section) {
    String name = section.source();
    if (section.status() == SourceSection.Status.UNAVAILABLE) {
      return name + " could not be read, so its failures are not known.";
    }
    List<FailureGroup> groups = section.groups();
    if (groups.isEmpty()) {
      return name + ": no failures found.";
    }
    String failures = plural(section.failuresSeen(), "failure");
    if (groups.size() == 1) {
      return name + ": " + failures + ", all in " + groups.getFirst().id() + ".";
    }
    FailureGroup largest = groups.getFirst();
    for (FailureGroup group : groups) {
      if (group.count() > largest.count()) {
        largest = group;
      }
    }
    return name + ": " + failures + " in " + groups.size() + " groups; the largest is " + largest.id() + " with "
      + largest.count() + (largest.merged() ? ", merged from smaller groups." : ".");
  }

  private static String plural(int count, String noun) {
    return count + " " + noun + (count == 1 ? "" : "s");
  }
}
