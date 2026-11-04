package com.messaging.triage.chat;

import com.messaging.triage.source.np.NotificationWorkerClient;
import com.messaging.triage.source.np.WorkerViews;
import com.messaging.triage.triage.ModelView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * notification-platform's reads, as chat tools. Every one is a GET on the worker
 * ({@link NotificationWorkerClient} has no other verb), and none returns a recipient or a
 * destination: an address or a phone number never helps explain a failure, so the model is
 * not given one to repeat. A template key that is not a plain identifier is shown as a
 * placeholder, in the key field and in the error details that quote it (ADR 0006).
 */
public class NotificationReadTools {

  static final int DEFAULT_LIST_SIZE = 20;

  private final NotificationWorkerClient client;
  private final int maxListSize;

  public NotificationReadTools(NotificationWorkerClient client, int maxListSize) {
    this.client = client;
    this.maxListSize = maxListSize;
  }

  public record Attempt(String status, String detail, Instant attemptedAt) {
  }

  public record Notification(UUID eventId, String channel, String templateKey, String status, Instant requestedAt,
    Instant updatedAt, List<Attempt> attempts) {
  }

  public record NotificationSummary(UUID eventId, String channel, String templateKey, String status,
    Instant updatedAt, long attemptCount) {
  }

  public record Listing(long totalItems, int shown, List<NotificationSummary> items) {
  }

  @Tool(name = "notification_stats", description = "Counts of notification-platform notifications over the last "
    + "24 hours, by status and by channel. Read-only.")
  public WorkerViews.Stats notificationStats() {
    return client.stats();
  }

  @Tool(name = "list_dead_lettered_notifications", description = "The newest dead-lettered notifications on "
    + "notification-platform: event id, channel, template key, status, attempt count. No error text; call "
    + "get_notification for one notification's attempts and errors. Read-only.")
  public Listing listDeadLettered(
    @ToolParam(description = "How many to return, 1 to 100. Default 20.", required = false) Integer size) {
    int applied = size == null ? DEFAULT_LIST_SIZE : Math.clamp(size, 1, maxListSize);
    WorkerViews.Page page = client.list("DEAD_LETTERED", applied);
    List<NotificationSummary> items = page == null || page.items() == null ? List.of()
      : page.items().stream()
        .map(summary -> new NotificationSummary(summary.eventId(), summary.channel(),
          templateKey(summary.templateKey()), summary.status(), summary.updatedAt(), summary.attemptCount()))
        .toList();
    return new Listing(page == null ? 0 : page.totalItems(), items.size(), items);
  }

  @Tool(name = "get_notification", description = "One notification-platform notification by event id: its "
    + "channel, template key, status and every delivery attempt with its error detail. Read-only.")
  public Object getNotification(
    @ToolParam(description = "The event id, a UUID, exactly as listed.") String eventId) {
    UUID id;
    try {
      id = UUID.fromString(eventId == null ? "" : eventId.trim());
    } catch (IllegalArgumentException e) {
      return "Not a valid event id. Use an eventId exactly as list_dead_lettered_notifications returned it.";
    }
    Optional<WorkerViews.Status> status = client.get(id);
    if (status.isEmpty()) {
      return "No notification with that event id.";
    }
    WorkerViews.Status found = status.get();
    String key = templateKey(found.templateKey());
    List<Attempt> attempts = found.attempts() == null ? List.of()
      : found.attempts().stream()
        .map(attempt -> new Attempt(attempt.status(),
          ModelView.replaced(attempt.detail(), found.templateKey(), key), attempt.attemptedAt()))
        .toList();
    return new Notification(found.eventId(), found.channel(), key, found.status(),
      found.requestedAt(), found.updatedAt(), attempts);
  }

  /** The key a client chose, as the model may see it: itself if a plain identifier, else a placeholder. */
  static String templateKey(String key) {
    return ModelView.identifier("template key", key);
  }
}
