package com.messaging.triage.chat;

/** The model could not be asked. Chat has nothing to fall back on, so this is a 503. */
public class ModelUnavailableException extends RuntimeException {

  public ModelUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
