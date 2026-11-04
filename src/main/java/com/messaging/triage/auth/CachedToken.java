package com.messaging.triage.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bearer token and when it stops working.
 * <p>
 * The expiry is read from the JWT's {@code exp} claim <b>without verifying the signature</b>.
 * That is safe here: the token is only ever sent back to the service that issued it, which
 * does verify it, so a forged {@code exp} could at worst make this client refresh early or
 * late. Neither sibling's token response carries an expires-in field, hence the decode.
 */
public record CachedToken(String value, Instant expiresAt) {

  /** Assumed lifetime when the token is not a JWT or has no {@code exp}. */
  static final Duration UNKNOWN_EXPIRY_TTL = Duration.ofMinutes(10);

  private static final Pattern EXP = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)");

  public static CachedToken fromJwt(String jwt, Instant now) {
    return new CachedToken(jwt, readExpiry(jwt).orElse(now.plus(UNKNOWN_EXPIRY_TTL)));
  }

  public boolean expiresWithin(Duration margin, Instant now) {
    return !now.plus(margin).isBefore(expiresAt);
  }

  private static Optional<Instant> readExpiry(String jwt) {
    String[] parts = jwt.split("\\.");
    if (parts.length < 2) {
      return Optional.empty();
    }
    try {
      String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      Matcher matcher = EXP.matcher(payload);
      return matcher.find()
        ? Optional.of(Instant.ofEpochSecond(Long.parseLong(matcher.group(1))))
        : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** Redacted, so the token cannot reach a log line by accident. */
  @Override
  public String toString() {
    return "CachedToken[value=<redacted>, expiresAt=" + expiresAt + "]";
  }
}
