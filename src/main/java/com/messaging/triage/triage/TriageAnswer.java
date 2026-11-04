package com.messaging.triage.triage;

import java.util.List;

/**
 * What a triage returns: a summary that code writes from the report's ids and counts, and a
 * diagnosis for each group the model answered usably, in report order (ADR 0004).
 */
public record TriageAnswer(String summary, List<GroupDiagnosis> diagnoses) {
}
