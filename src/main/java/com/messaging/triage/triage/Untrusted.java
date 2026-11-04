package com.messaging.triage.triage;

/**
 * Fences text that came from the systems being triaged, so the model can be told to treat
 * it as data (ADR 0003). The fence only works if the text cannot close it early, so:
 * <ul>
 *   <li>every run of three or more {@code <} or {@code >} is broken up, so no marker can be
 *       forged from inside, however it is spelled;</li>
 *   <li>control, format and line-separator characters become spaces (no zero-width or
 *       bidi-override tricks, no fake new "turn" on a line of its own);</li>
 *   <li>the length is capped.</li>
 * </ul>
 * This lowers the odds of a prompt injection working; it cannot rule one out. What bounds
 * the damage is that the model has no write tools (ADR 0002).
 */
public final class Untrusted {

  public static final String END = "<<<END UNTRUSTED>>>";
  static final String ELLIPSIS = "...";

  private Untrusted() {
  }

  public static String open(String kind) {
    return "<<<UNTRUSTED kind=" + kindOf(kind) + ">>>";
  }

  public static String wrap(String kind, String value, int maxChars) {
    return open(kind) + neutralize(value, maxChars) + END;
  }

  /** The text with the markers made impossible and the length capped; null becomes "". */
  public static String neutralize(String value, int maxChars) {
    if (value == null) {
      return "";
    }
    StringBuilder clean = new StringBuilder(value.length());
    value.codePoints().forEach(codePoint -> {
      if (isHidden(codePoint)) {
        clean.append(' ');
      } else {
        clean.appendCodePoint(codePoint);
      }
    });
    String text = clean.toString();
    while (text.contains("<<<")) {
      text = text.replace("<<<", "<< <");
    }
    while (text.contains(">>>")) {
      text = text.replace(">>>", "> >>");
    }
    return cap(text, maxChars);
  }

  private static boolean isHidden(int codePoint) {
    int type = Character.getType(codePoint);
    return type == Character.CONTROL
      || type == Character.FORMAT
      || type == Character.LINE_SEPARATOR
      || type == Character.PARAGRAPH_SEPARATOR;
  }

  private static String cap(String text, int maxChars) {
    if (text.length() <= maxChars) {
      return text;
    }
    int end = Math.max(0, maxChars - ELLIPSIS.length());
    if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(0, end) + ELLIPSIS;
  }

  /** Kinds are set in code, but a stray one must not be able to break the opening marker. */
  private static String kindOf(String kind) {
    String clean = kind == null ? "" : kind.replaceAll("[^a-z0-9-]", "");
    return clean.isEmpty() ? "data" : clean;
  }
}
