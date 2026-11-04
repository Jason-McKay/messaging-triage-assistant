package com.messaging.triage.source.np;

import com.messaging.triage.source.Failure;
import com.messaging.triage.source.FailureSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Failures from notification-platform: the dead-lettered notifications, then those still
 * FAILED on their way to the dead-letter topic.
 * <ol>
 *   <li>The 24-hour stats, for context.</li>
 *   <li>A page each of DEAD_LETTERED and FAILED, newest first.</li>
 *   <li>One detail read per notification, at most {@code maxDetailFetches} in all: the list
 *       carries no error text, only the attempts in the detail do.</li>
 *   <li>The error is the detail of the last FAILED attempt. The DEAD_LETTERED attempt row
 *       is only a "parked on ..." marker and says nothing about why.</li>
 * </ol>
 * Grouped by channel; the template key travels in the error text and as an attribute.
 * Recipient and destination are left out: they are contact details and explain nothing.
 */
public class NotificationWorkerGatherer implements FailureSource {

  static final List<String> FAILURE_STATUSES = List.of("DEAD_LETTERED", "FAILED");
  static final String PARKED_MARKER = "parked on ";
  static final String NO_FAILED_ATTEMPT = "no failed attempt recorded";

  private final NotificationWorkerClient client;
  private final int pageSize;
  private final int maxDetailFetches;

  public NotificationWorkerGatherer(NotificationWorkerClient client, int pageSize, int maxDetailFetches) {
    this.client = client;
    this.pageSize = pageSize;
    this.maxDetailFetches = maxDetailFetches;
  }

  @Override
  public String id() {
    return "NP";
  }

  @Override
  public String name() {
    return "notification-platform";
  }

  @Override
  public Gathered gather() {
    Map<String, Long> counts = new LinkedHashMap<>();
    List<String> notes = new ArrayList<>();

    WorkerViews.Stats stats = client.stats();
    if (stats != null && stats.byStatus() != null) {
      stats.byStatus().forEach((status, count) -> counts.put("last24h." + status, count));
    }

    List<WorkerViews.Summary> candidates = new ArrayList<>();
    for (String status : FAILURE_STATUSES) {
      WorkerViews.Page page = client.list(status, pageSize);
      List<WorkerViews.Summary> items = page == null || page.items() == null ? List.of() : page.items();
      long total = page == null ? 0 : page.totalItems();
      counts.put("listed." + status, total);
      if (total > items.size()) {
        notes.add(id() + ": " + total + " " + status + " notifications, the newest " + items.size() + " read");
      }
      candidates.addAll(items);
    }

    List<Failure> failures = new ArrayList<>();
    int fetched = 0;
    int missing = 0;
    for (WorkerViews.Summary summary : candidates) {
      if (fetched == maxDetailFetches) {
        break;
      }
      fetched++;
      Optional<WorkerViews.Status> detail = client.get(summary.eventId());
      if (detail.isEmpty()) {
        missing++;
        continue;
      }
      failures.add(toFailure(detail.get()));
    }
    if (candidates.size() > fetched) {
      notes.add(id() + ": detail read for " + fetched + " of " + candidates.size()
        + " notifications (limit " + maxDetailFetches + "); the other " + (candidates.size() - fetched)
        + " are counted above but in no group");
    }
    if (missing > 0) {
      notes.add(id() + ": " + missing + " notifications were gone by the time their detail was read");
    }
    return new Gathered(failures, counts, notes);
  }

  static String lastFailedDetail(List<WorkerViews.Attempt> attempts) {
    if (attempts == null) {
      return NO_FAILED_ATTEMPT;
    }
    for (int i = attempts.size() - 1; i >= 0; i--) {
      WorkerViews.Attempt attempt = attempts.get(i);
      if ("FAILED".equals(attempt.status()) && attempt.detail() != null && !attempt.detail().startsWith(PARKED_MARKER)) {
        return attempt.detail();
      }
    }
    return NO_FAILED_ATTEMPT;
  }

  private static Failure toFailure(WorkerViews.Status status) {
    Map<String, String> dimensions = new LinkedHashMap<>();
    dimensions.put("channel", String.valueOf(status.channel()));
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("templateKey", String.valueOf(status.templateKey()));
    attributes.put("status", String.valueOf(status.status()));
    // Tries only: the parked marker is an attempt row but not a try.
    long tries = status.attempts() == null ? 0
      : status.attempts().stream().filter(attempt -> !"DEAD_LETTERED".equals(attempt.status())).count();
    attributes.put("tries", String.valueOf(tries));
    return new Failure(
      dimensions,
      lastFailedDetail(status.attempts()),
      String.valueOf(status.eventId()),
      status.updatedAt(),
      null,
      Set.of(),
      attributes);
  }
}
