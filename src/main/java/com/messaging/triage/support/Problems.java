package com.messaging.triage.support;

/**
 * Turns an exception into one short line for a report or a response. Only the exception
 * types and messages go in: these are client-side errors (connection refused, an HTTP
 * status and its body), never request bodies or tokens.
 */
public final class Problems {

  static final int MAX_CHARS = 300;

  private Problems() {
  }

  public static String describe(Throwable e) {
    StringBuilder line = new StringBuilder(e.getClass().getSimpleName());
    if (e.getMessage() != null && !e.getMessage().isBlank()) {
      line.append(": ").append(e.getMessage());
    }
    Throwable root = rootCause(e);
    if (root != e) {
      line.append(" (caused by ").append(root.getClass().getSimpleName());
      if (root.getMessage() != null && !root.getMessage().isBlank()) {
        line.append(": ").append(root.getMessage());
      }
      line.append(')');
    }
    return cap(line.toString().replaceAll("\\s+", " ").trim(), MAX_CHARS);
  }

  public static String cap(String text, int maxChars) {
    if (text == null || text.length() <= maxChars) {
      return text;
    }
    return text.substring(0, maxChars) + "...";
  }

  private static Throwable rootCause(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root;
  }
}
