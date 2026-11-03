package com.messaging.triage.source;

/** A source answered, but with something that means it cannot be read right now. */
public class SourceUnavailableException extends RuntimeException {

  public SourceUnavailableException(String message) {
    super(message);
  }
}
