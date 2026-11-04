package com.messaging.triage.source.mi;

import com.messaging.triage.auth.RefreshingTokenProvider;
import java.time.Clock;
import java.util.List;
import org.springframework.web.client.RestClient;

/**
 * Logs in to message-inspector for the bearer token its MCP endpoint takes. The account
 * should be a USER, not an ADMIN: the server refuses the write tools to a USER, which is
 * the second wall behind the allowlist (ADR 0002).
 */
public class MiTokenProvider extends RefreshingTokenProvider {

  private final RestClient api;
  private final String username;
  private final String password;

  public MiTokenProvider(RestClient api, String username, String password, Clock clock) {
    super(clock);
    this.api = api;
    this.username = username;
    this.password = password;
  }

  record LoginRequest(String username, String password) {

    @Override
    public String toString() {
      return "LoginRequest[username=" + username + ", password=<redacted>]";
    }
  }

  record LoginResponse(String token, String username, List<String> roles) {

    @Override
    public String toString() {
      return "LoginResponse[token=<redacted>, username=" + username + ", roles=" + roles + "]";
    }
  }

  @Override
  protected String mint() {
    LoginResponse response = api.post()
      .uri("/api/auth/login")
      .body(new LoginRequest(username, password))
      .retrieve()
      .body(LoginResponse.class);
    if (response == null || response.token() == null || response.token().isBlank()) {
      throw new IllegalStateException("message-inspector login returned no token");
    }
    return response.token();
  }

  @Override
  protected String name() {
    return "message-inspector";
  }
}
