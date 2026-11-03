package com.messaging.triage.source.np;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The notification worker's read API, as this client binds it. Mirrors
 * {@code NotificationStatusController}'s views in notification-platform; enums are bound as
 * strings so a status added there does not break the read here.
 */
public final class WorkerViews {

  private WorkerViews() {
  }

  public record Attempt(String status, String detail, Instant attemptedAt) {
  }

  public record Status(UUID eventId, String recipientId, String destination, String channel, String templateKey,
    String status, Instant requestedAt, Instant updatedAt, List<Attempt> attempts) {
  }

  public record Summary(UUID eventId, String recipientId, String destination, String channel, String templateKey,
    String status, Instant requestedAt, Instant updatedAt, long attemptCount) {
  }

  public record Page(List<Summary> items, int page, int size, long totalItems, int totalPages) {
  }

  /** Counts over the last 24 hours, by the worker's own window. */
  public record Stats(Instant since, long total, Map<String, Long> byStatus, Map<String, Long> byChannel) {
  }
}
