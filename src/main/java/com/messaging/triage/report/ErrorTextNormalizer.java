package com.messaging.triage.report;

import java.util.regex.Pattern;

/**
 * Reduces an error text to its shape, so "order 1041 failed" and "order 1042 failed" group
 * together. Replaces what varies per message (UUIDs, JMS ids, long hex, quoted values,
 * numbers) and keeps the words, which is where template keys and variable names live.
 */
public final class ErrorTextNormalizer {

  private static final Pattern UUID = Pattern.compile(
    "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
  private static final Pattern JMS_ID = Pattern.compile("\\bID:[\\w:.\\-]+");
  private static final Pattern HEX = Pattern.compile("\\b0x[0-9a-fA-F]+\\b|\\b[0-9a-fA-F]{16,}\\b");
  private static final Pattern SINGLE_QUOTED = Pattern.compile("'[^'\\n]{0,200}'");
  private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"[^\"\\n]{0,200}\"");
  private static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:[.,]\\d+)*\\b");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private ErrorTextNormalizer() {
  }

  public static String normalize(String error) {
    if (error == null || error.isBlank()) {
      return "";
    }
    String text = UUID.matcher(error).replaceAll("<uuid>");
    text = JMS_ID.matcher(text).replaceAll("<id>");
    text = HEX.matcher(text).replaceAll("<hex>");
    text = SINGLE_QUOTED.matcher(text).replaceAll("'<value>'");
    text = DOUBLE_QUOTED.matcher(text).replaceAll("\"<value>\"");
    text = NUMBER.matcher(text).replaceAll("<n>");
    return WHITESPACE.matcher(text).replaceAll(" ").trim();
  }
}
