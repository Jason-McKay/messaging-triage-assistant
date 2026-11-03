package com.messaging.triage.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Holds one token in memory and mints a new one when fewer than five minutes are left, or
 * after {@link #invalidate()} (which callers do on a 401). Never written to disk, never
 * logged: only the expiry is.
 */
@Slf4j
public abstract class RefreshingTokenProvider {

  static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

  private final Clock clock;
  private final AtomicReference<CachedToken> cached = new AtomicReference<>();
  private final Object mintLock = new Object();

  protected RefreshingTokenProvider(Clock clock) {
    this.clock = clock;
  }

  public String token() {
    CachedToken current = cached.get();
    if (isFresh(current)) {
      return current.value();
    }
    synchronized (mintLock) {
      current = cached.get();
      if (isFresh(current)) {
        return current.value();
      }
      CachedToken minted = CachedToken.fromJwt(mint(), clock.instant());
      cached.set(minted);
      log.debug("{}: new token, expires {}", name(), minted.expiresAt());
      return minted.value();
    }
  }

  public void invalidate() {
    cached.set(null);
  }

  /** Fetch a new token from the issuing service. */
  protected abstract String mint();

  /** For log lines only. */
  protected abstract String name();

  private boolean isFresh(CachedToken token) {
    return token != null && !token.expiresWithin(REFRESH_MARGIN, clock.instant());
  }
}
