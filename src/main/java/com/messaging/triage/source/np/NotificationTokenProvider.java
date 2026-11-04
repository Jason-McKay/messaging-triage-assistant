package com.messaging.triage.source.np;

import com.messaging.triage.auth.RefreshingTokenProvider;
import java.time.Clock;
import org.springframework.web.client.RestClient;

/**
 * Mints the notification-platform token from the api's client-credentials endpoint. The
 * token carries the write scope as well as read (the api issues one token with both), which
 * this app never uses: see docs/known-gaps.md.
 */
public class NotificationTokenProvider extends RefreshingTokenProvider {

  private final RestClient api;
  private final String clientId;
  private final String clientSecret;

  public NotificationTokenProvider(RestClient api, String clientId, String clientSecret, Clock clock) {
    super(clock);
    this.api = api;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
  }

  record TokenRequest(String clientId, String clientSecret) {

    @Override
    public String toString() {
      return "TokenRequest[clientId=" + clientId + ", clientSecret=<redacted>]";
    }
  }

  record TokenResponse(String accessToken, String tokenType) {

    @Override
    public String toString() {
      return "TokenResponse[accessToken=<redacted>, tokenType=" + tokenType + "]";
    }
  }

  @Override
  protected String mint() {
    TokenResponse response = api.post()
      .uri("/api/auth/token")
      .body(new TokenRequest(clientId, clientSecret))
      .retrieve()
      .body(TokenResponse.class);
    if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
      throw new IllegalStateException("notification-platform token endpoint returned no token");
    }
    return response.accessToken();
  }

  @Override
  protected String name() {
    return "notification-platform";
  }
}
