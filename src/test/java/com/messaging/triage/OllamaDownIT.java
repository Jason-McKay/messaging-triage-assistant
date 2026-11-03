package com.messaging.triage;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole app with no model and no message-inspector: notification-platform is stubbed,
 * the other two point at a port nothing listens on. Triage still answers 200 with the facts
 * and an unusable model outcome; chat answers 503.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OllamaDownIT {

  private static final String NOTHING_LISTENS = "http://127.0.0.1:1";
  private static final String EVENT = "9b2f1c3e-0d4a-4c1b-9e7f-2a6b8c0d1e2f";

  @RegisterExtension
  static WireMockExtension notificationPlatform = WireMockExtension.newInstance()
    .options(wireMockConfig().dynamicPort())
    .build();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("triage.sources.notification-platform.api-url", notificationPlatform::baseUrl);
    registry.add("triage.sources.notification-platform.worker-url", notificationPlatform::baseUrl);
    registry.add("triage.sources.message-inspector.url", () -> NOTHING_LISTENS);
    registry.add("spring.ai.ollama.base-url", () -> NOTHING_LISTENS);
  }

  @Value("${local.server.port}")
  private int port;

  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private RestClient app;

  @BeforeEach
  void setUp() {
    app = RestClient.builder()
      .baseUrl("http://127.0.0.1:" + port)
      .defaultStatusHandler(status -> true, (request, response) -> { })
      .build();
    notificationPlatform.stubFor(post("/api/auth/token")
      .willReturn(okJson("{\"accessToken\":\"test-token\",\"tokenType\":\"Bearer\"}")));
    notificationPlatform.stubFor(get("/api/notifications/stats")
      .willReturn(okJson("{\"since\":\"2026-11-02T00:00:00Z\",\"total\":2,"
        + "\"byStatus\":{\"DEAD_LETTERED\":1,\"DELIVERED\":1},\"byChannel\":{\"EMAIL\":2}}")));
    notificationPlatform.stubFor(get(urlPathEqualTo("/api/notifications"))
      .withQueryParam("status", equalTo("DEAD_LETTERED"))
      .willReturn(okJson("{\"items\":[{\"eventId\":\"" + EVENT + "\",\"recipientId\":\"r-1\","
        + "\"destination\":\"r1@example.com\",\"channel\":\"EMAIL\",\"templateKey\":\"order-refunded\","
        + "\"status\":\"DEAD_LETTERED\",\"requestedAt\":\"2026-11-03T09:00:00Z\","
        + "\"updatedAt\":\"2026-11-03T09:01:00Z\",\"attemptCount\":2}],"
        + "\"page\":0,\"size\":100,\"totalItems\":1,\"totalPages\":1}")));
    notificationPlatform.stubFor(get(urlPathEqualTo("/api/notifications"))
      .withQueryParam("status", equalTo("FAILED"))
      .willReturn(okJson("{\"items\":[],\"page\":0,\"size\":100,\"totalItems\":0,\"totalPages\":0}")));
    notificationPlatform.stubFor(get("/api/notifications/" + EVENT)
      .willReturn(okJson("{\"eventId\":\"" + EVENT + "\",\"recipientId\":\"r-1\",\"destination\":\"r1@example.com\","
        + "\"channel\":\"EMAIL\",\"templateKey\":\"order-refunded\",\"status\":\"DEAD_LETTERED\","
        + "\"requestedAt\":\"2026-11-03T09:00:00Z\",\"updatedAt\":\"2026-11-03T09:01:00Z\",\"attempts\":["
        + "{\"status\":\"FAILED\",\"detail\":\"Template 'order-refunded' not found\","
        + "\"attemptedAt\":\"2026-11-03T09:00:30Z\"},"
        + "{\"status\":\"DEAD_LETTERED\",\"detail\":\"parked on notification.dlt\","
        + "\"attemptedAt\":\"2026-11-03T09:01:00Z\"}]}")));
  }

  @Test
  void withNoModelTriageStillReportsAndChatSays503() {
    assertThat(app.get().uri("/api/triage/latest").retrieve().toBodilessEntity().getStatusCode().value())
      .as("no run yet").isEqualTo(404);

    ResponseEntity<String> triage = app.post().uri("/api/triage").retrieve().toEntity(String.class);
    assertThat(triage.getStatusCode().value()).isEqualTo(200);
    JsonNode result = jsonMapper.readTree(triage.getBody());
    assertThat(result.path("model").path("usable").asBoolean()).isFalse();
    assertThat(result.path("model").path("problem").asString()).startsWith("model unavailable");
    assertThat(result.path("answer").isNull()).isTrue();
    JsonNode sections = result.path("report").path("sources");
    assertThat(section(sections, "NP").path("status").asString()).isEqualTo("OK");
    assertThat(section(sections, "NP").path("groups").path(0).path("error").asString())
      .isEqualTo("Template '<value>' not found");
    assertThat(section(sections, "MI").path("status").asString()).isEqualTo("UNAVAILABLE");

    ResponseEntity<String> chat = app.post().uri("/api/chat")
      .contentType(MediaType.APPLICATION_JSON)
      .body("{\"message\":\"What is on the DLQ?\",\"history\":[]}")
      .retrieve()
      .toEntity(String.class);
    assertThat(chat.getStatusCode().value()).isEqualTo(503);
    assertThat(jsonMapper.readTree(chat.getBody()).path("title").asString()).isEqualTo("Model unavailable");

    ResponseEntity<String> latest = app.get().uri("/api/triage/latest").retrieve().toEntity(String.class);
    assertThat(latest.getStatusCode().value()).isEqualTo(200);
    assertThat(jsonMapper.readTree(latest.getBody()).path("id").asString()).isEqualTo(result.path("id").asString());
  }

  private static JsonNode section(JsonNode sections, String id) {
    for (JsonNode section : sections) {
      if (id.equals(section.path("id").asString())) {
        return section;
      }
    }
    throw new AssertionError("no section " + id + " in " + sections);
  }
}
