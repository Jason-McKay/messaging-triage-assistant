package com.messaging.triage.triage;

/** How sure the model says it is about one group's cause. */
public enum Confidence {
  /** The error text states the cause. */
  HIGH,
  /** The cause is inferred from the payload or the pattern of failures. */
  MEDIUM,
  /** A guess: the evidence is thin or points several ways. */
  LOW
}
