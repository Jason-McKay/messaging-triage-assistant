package com.messaging.triage.triage;

import com.messaging.triage.report.FailureReport;
import com.messaging.triage.report.ReportBuilder;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * One triage run, end to end: gather, explain, remember. On demand only in v1; a scheduler
 * or an incident agent would call {@link #run()} the same way.
 */
@Slf4j
public class TriageService {

  private final ReportBuilder reportBuilder;
  private final Triager triager;
  private final LatestTriageHolder latest;
  private final Clock clock;

  public TriageService(ReportBuilder reportBuilder, Triager triager, LatestTriageHolder latest, Clock clock) {
    this.reportBuilder = reportBuilder;
    this.triager = triager;
    this.latest = latest;
    this.clock = clock;
  }

  public TriageResult run() {
    Instant createdAt = clock.instant();
    long started = System.nanoTime();
    FailureReport report = reportBuilder.build();
    long gatherMs = (System.nanoTime() - started) / 1_000_000;

    Triager.Verdict verdict = triager.triage(report);
    TriageResult result = new TriageResult(UUID.randomUUID().toString(), createdAt, report, verdict.answer(),
      verdict.outcome(), gatherMs);
    latest.set(result);
    log.info("triage {}: {} groups, gathered in {} ms, model usable={} after {} attempts in {} ms", result.id(),
      report.groupIds().size(), gatherMs, verdict.outcome().usable(), verdict.outcome().attempts(),
      verdict.outcome().durationMs());
    return result;
  }
}
