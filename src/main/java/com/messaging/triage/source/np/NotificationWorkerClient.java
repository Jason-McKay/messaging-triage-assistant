package com.messaging.triage.source.np;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * The notification worker's read API, and only that: every method is a GET, and the
 * {@link RestClient} is private, so nothing built on this class can redrive, resend or
 * delete (ADR 0002).
 * <p>
 * On a 401 the token is dropped, a new one minted and the call made once more; a second 401
 * is thrown to the caller.
 */
@Slf4j
public class NotificationWorkerClient {

  static final String BASE = "/api/notifications";

  private final RestClient worker;
  private final NotificationTokenProvider tokens;

  public NotificationWorkerClient(RestClient worker, NotificationTokenProvider tokens) {
    this.worker = worker;
    this.tokens = tokens;
  }

  public WorkerViews.Stats stats() {
    return get(uri -> uri.path(BASE + "/stats").build(), WorkerViews.Stats.class);
  }

  /** Newest first, at most {@code size} (the worker caps it at 100). */
  public WorkerViews.Page list(String status, int size) {
    return get(uri -> uri.path(BASE).queryParam("status", status).queryParam("size", size).build(),
      WorkerViews.Page.class);
  }

  public Optional<WorkerViews.Status> get(UUID eventId) {
    try {
      return Optional.ofNullable(get(uri -> uri.path(BASE + "/{eventId}").build(eventId), WorkerViews.Status.class));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
  }

  private <T> T get(Function<UriBuilder, URI> uri, Class<T> type) {
    try {
      return fetch(uri, type);
    } catch (HttpClientErrorException.Unauthorized e) {
      log.info("notification worker answered 401; minting a new token and retrying once");
      tokens.invalidate();
      return fetch(uri, type);
    }
  }

  private <T> T fetch(Function<UriBuilder, URI> uri, Class<T> type) {
    return worker.get()
      .uri(uri)
      .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
      .retrieve()
      .body(type);
  }
}
