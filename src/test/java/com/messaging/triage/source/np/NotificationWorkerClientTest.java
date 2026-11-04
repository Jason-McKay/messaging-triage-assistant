package com.messaging.triage.source.np;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

class NotificationWorkerClientTest {

  @RegisterExtension
  static WireMockExtension server = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
  private static final String STATS = "/api/notifications/stats";

  private MutableClock clock;
  private NotificationWorkerClient client;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(T0);
    RestClient rest = RestClient.builder().baseUrl(server.baseUrl()).build();
    NotificationTokenProvider tokens = new NotificationTokenProvider(rest, "notification-client", "dev-secret", clock);
    client = new NotificationWorkerClient(rest, tokens);
    server.stubFor(get(urlEqualTo(STATS)).willReturn(okJson(
      "{\"since\":\"2026-11-02T10:00:00Z\",\"total\":7,\"byStatus\":{\"DEAD_LETTERED\":5},\"byChannel\":{}}")));
  }

  @Test
  void sendsTheMintedTokenAsABearer() {
    String token = tokenStub(jwt(T0.plus(Duration.ofMinutes(15))));

    WorkerViews.Stats stats = client.stats();

    assertThat(stats.byStatus()).containsEntry("DEAD_LETTERED", 5L);
    server.verify(postRequestedFor(urlEqualTo("/api/auth/token"))
      .withRequestBody(equalToJson("{\"clientId\":\"notification-client\",\"clientSecret\":\"dev-secret\"}")));
    server.verify(getRequestedFor(urlEqualTo(STATS)).withHeader("Authorization", equalTo("Bearer " + token)));
  }

  @Test
  void reusesTheTokenUntilItNearsExpiryThenMintsANewOne() {
    tokenStub(jwt(T0.plus(Duration.ofMinutes(15))));

    client.stats();
    client.stats();
    server.verify(1, postRequestedFor(urlEqualTo("/api/auth/token")));

    // 11 minutes on, 4 are left: inside the five-minute margin.
    clock.advance(Duration.ofMinutes(11));
    client.stats();
    server.verify(2, postRequestedFor(urlEqualTo("/api/auth/token")));
  }

  @Test
  void aTokenWithoutAnExpiryIsKeptForTenMinutes() {
    tokenStub("opaque-token");

    client.stats();
    clock.advance(Duration.ofMinutes(4));
    client.stats();
    server.verify(1, postRequestedFor(urlEqualTo("/api/auth/token")));
    clock.advance(Duration.ofMinutes(2));
    client.stats();
    server.verify(2, postRequestedFor(urlEqualTo("/api/auth/token")));
  }

  @Test
  void a401DropsTheTokenAndRetriesOnce() {
    tokenStub(jwt(T0.plus(Duration.ofHours(1))));
    server.stubFor(get(urlEqualTo(STATS)).inScenario("expired").whenScenarioStateIs(Scenario.STARTED)
      .willReturn(aResponse().withStatus(401)).willSetStateTo("refreshed"));
    server.stubFor(get(urlEqualTo(STATS)).inScenario("expired").whenScenarioStateIs("refreshed")
      .willReturn(okJson("{\"total\":1,\"byStatus\":{},\"byChannel\":{}}")));

    WorkerViews.Stats stats = client.stats();

    assertThat(stats.total()).isEqualTo(1);
    server.verify(2, postRequestedFor(urlEqualTo("/api/auth/token")));
    server.verify(2, getRequestedFor(urlEqualTo(STATS)));
  }

  @Test
  void aSecond401IsThrown() {
    tokenStub(jwt(T0.plus(Duration.ofHours(1))));
    server.stubFor(get(urlEqualTo(STATS)).willReturn(aResponse().withStatus(401)));

    assertThatThrownBy(client::stats).isInstanceOf(HttpClientErrorException.Unauthorized.class);
    server.verify(2, getRequestedFor(urlEqualTo(STATS)));
  }

  @Test
  void listsByStatusAndReadsOneNotification() {
    tokenStub(jwt(T0.plus(Duration.ofHours(1))));
    UUID eventId = UUID.fromString("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
    server.stubFor(get(urlPathEqualTo("/api/notifications")).willReturn(okJson(
      "{\"items\":[{\"eventId\":\"" + eventId + "\",\"channel\":\"EMAIL\",\"templateKey\":\"order-refunded\","
        + "\"status\":\"DEAD_LETTERED\",\"attemptCount\":4}],\"page\":0,\"size\":20,\"totalItems\":1,"
        + "\"totalPages\":1}")));
    server.stubFor(get(urlEqualTo("/api/notifications/" + eventId)).willReturn(okJson(
      "{\"eventId\":\"" + eventId + "\",\"channel\":\"EMAIL\",\"templateKey\":\"order-refunded\","
        + "\"status\":\"DEAD_LETTERED\",\"updatedAt\":\"2026-11-03T09:00:00Z\",\"attempts\":["
        + "{\"status\":\"FAILED\",\"detail\":\"Template not found\",\"attemptedAt\":\"2026-11-03T08:59:00Z\"}]}")));
    server.stubFor(get(urlPathMatching("/api/notifications/[0-9a-f-]{36}")).atPriority(10)
      .willReturn(aResponse().withStatus(404)));

    WorkerViews.Page page = client.list("DEAD_LETTERED", 20);
    WorkerViews.Status status = client.get(eventId).orElseThrow();

    assertThat(page.items()).singleElement().extracting(WorkerViews.Summary::templateKey).isEqualTo("order-refunded");
    server.verify(getRequestedFor(urlEqualTo("/api/notifications?status=DEAD_LETTERED&size=20")));
    assertThat(status.attempts()).singleElement().extracting(WorkerViews.Attempt::detail)
      .isEqualTo("Template not found");
    assertThat(status.updatedAt()).isEqualTo(Instant.parse("2026-11-03T09:00:00Z"));
    assertThat(client.get(UUID.randomUUID())).isEmpty();
  }

  @Test
  void onlyEverReadsTheWorker() {
    tokenStub(jwt(T0.plus(Duration.ofHours(1))));
    server.stubFor(get(urlPathEqualTo("/api/notifications"))
      .willReturn(okJson("{\"items\":[],\"page\":0,\"size\":20,\"totalItems\":0,\"totalPages\":0}")));
    server.stubFor(get(urlPathMatching("/api/notifications/[0-9a-f-]{36}")).willReturn(aResponse().withStatus(404)));

    client.stats();
    client.list("FAILED", 5);
    client.get(UUID.randomUUID());

    assertThat(server.getAllServeEvents())
      .filteredOn(event -> event.getRequest().getUrl().startsWith("/api/notifications"))
      .hasSize(3)
      .allSatisfy(event -> assertThat(event.getRequest().getMethod().getName()).isEqualTo("GET"));
    // And there is nothing else to call: the public surface is three reads.
    assertThat(Arrays.stream(NotificationWorkerClient.class.getDeclaredMethods())
      .filter(method -> Modifier.isPublic(method.getModifiers()))
      .map(Method::getName))
      .containsExactlyInAnyOrder("stats", "list", "get");
  }

  private static String tokenStub(String token) {
    server.stubFor(post(urlEqualTo("/api/auth/token"))
      .willReturn(okJson("{\"accessToken\":\"" + token + "\",\"tokenType\":\"Bearer\"}")));
    return token;
  }

  private static String jwt(Instant expiresAt) {
    Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
    String header = base64.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
    String payload = base64.encodeToString(("{\"sub\":\"notification-client\",\"exp\":"
      + expiresAt.getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
    return header + "." + payload + ".signature";
  }

  /** A clock the test moves by hand. */
  static final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
