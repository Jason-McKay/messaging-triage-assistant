package com.messaging.triage.triage;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The most recent triage run, in memory only: lost on restart, by design for v1. Chat reads
 * it for context; a later incident agent would read it (or replace this with a store) to
 * decide whether something new has started (docs/known-gaps.md).
 */
public class LatestTriageHolder {

  private final AtomicReference<TriageResult> latest = new AtomicReference<>();

  public Optional<TriageResult> latest() {
    return Optional.ofNullable(latest.get());
  }

  public void set(TriageResult result) {
    latest.set(result);
  }
}
