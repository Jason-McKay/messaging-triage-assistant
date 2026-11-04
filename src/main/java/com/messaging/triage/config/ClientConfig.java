package com.messaging.triage.config;

import com.messaging.triage.chat.NotificationReadTools;
import com.messaging.triage.chat.ReadOnlyMcpTools;
import com.messaging.triage.source.mi.McpClientHolder;
import com.messaging.triage.source.mi.McpMessageInspectorClient;
import com.messaging.triage.source.mi.MessageInspectorGatherer;
import com.messaging.triage.source.mi.MiTokenProvider;
import com.messaging.triage.source.np.NotificationTokenProvider;
import com.messaging.triage.source.np.NotificationWorkerClient;
import com.messaging.triage.source.np.NotificationWorkerGatherer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The clients to the two systems. Each source can be switched off
 * ({@code triage.sources.*.enabled=false}); a switched-off source has no beans, so it is
 * neither gathered nor offered to chat.
 * <p>
 * Nothing here connects at startup: tokens are minted and the MCP session opened on first
 * use, so the app boots with both systems down.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TriageProperties.class)
public class ClientConfig {

  static final String CLIENT_NAME = "messaging-triage-assistant";
  static final String CLIENT_VERSION = "0.0.1";

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** HTTP/1.1 pinned: the JDK client's default h2c upgrade attempt buys nothing on localhost. */
  static ClientHttpRequestFactory requestFactory(TriageProperties.Sources sources) {
    HttpClient http = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(sources.connectTimeout())
      .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(sources.readTimeout());
    return factory;
  }

  static RestClient restClient(RestClient.Builder builder, String baseUrl, TriageProperties.Sources sources) {
    return builder.baseUrl(baseUrl).requestFactory(requestFactory(sources)).build();
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBooleanProperty(name = "triage.sources.notification-platform.enabled", matchIfMissing = true)
  static class NotificationPlatformConfig {

    @Bean
    NotificationTokenProvider notificationTokenProvider(TriageProperties properties, RestClient.Builder builder,
      Clock clock) {
      TriageProperties.NotificationPlatform np = properties.sources().notificationPlatform();
      return new NotificationTokenProvider(restClient(builder, np.apiUrl(), properties.sources()), np.clientId(),
        np.clientSecret(), clock);
    }

    @Bean
    NotificationWorkerClient notificationWorkerClient(TriageProperties properties, RestClient.Builder builder,
      NotificationTokenProvider tokens) {
      TriageProperties.NotificationPlatform np = properties.sources().notificationPlatform();
      return new NotificationWorkerClient(restClient(builder, np.workerUrl(), properties.sources()), tokens);
    }

    @Bean
    @Order(1)
    NotificationWorkerGatherer notificationWorkerGatherer(TriageProperties properties,
      NotificationWorkerClient client) {
      return new NotificationWorkerGatherer(client, properties.limits().pageSize(),
        properties.limits().maxDetailFetches());
    }

    @Bean
    NotificationReadTools notificationReadTools(TriageProperties properties, NotificationWorkerClient client) {
      return new NotificationReadTools(client, properties.limits().pageSize());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBooleanProperty(name = "triage.sources.message-inspector.enabled", matchIfMissing = true)
  static class MessageInspectorConfig {

    @Bean
    MiTokenProvider miTokenProvider(TriageProperties properties, RestClient.Builder builder, Clock clock) {
      TriageProperties.MessageInspector mi = properties.sources().messageInspector();
      return new MiTokenProvider(restClient(builder, mi.url(), properties.sources()), mi.username(), mi.password(),
        clock);
    }

    /**
     * The token goes on per request, from the provider, so a refreshed token is picked up
     * without rebuilding the client. message-inspector's MCP server is stateless, so a new
     * client costs one initialize round trip.
     */
    @Bean(destroyMethod = "close")
    McpClientHolder mcpClientHolder(TriageProperties properties, MiTokenProvider tokens) {
      TriageProperties.Sources sources = properties.sources();
      String url = sources.messageInspector().url();
      Supplier<McpSyncClient> factory = () -> McpClient.sync(
          HttpClientStreamableHttpTransport.builder(url)
            .endpoint("/mcp")
            .connectTimeout(sources.connectTimeout())
            .httpRequestCustomizer((request, method, uri, body, context) ->
              request.setHeader("Authorization", "Bearer " + tokens.token()))
            .build())
        .requestTimeout(sources.readTimeout())
        .clientInfo(McpSchema.Implementation.builder(CLIENT_NAME, CLIENT_VERSION).build())
        .build();
      return new McpClientHolder(factory, tokens::invalidate);
    }

    @Bean
    McpMessageInspectorClient messageInspectorClient(McpClientHolder holder, JsonMapper jsonMapper) {
      return new McpMessageInspectorClient(holder, jsonMapper);
    }

    @Bean
    @Order(2)
    MessageInspectorGatherer messageInspectorGatherer(TriageProperties properties,
      McpMessageInspectorClient client, JsonMapper jsonMapper) {
      TriageProperties.MessageInspector mi = properties.sources().messageInspector();
      return new MessageInspectorGatherer(client, mi.failureQueues(), mi.errorProperty(),
        properties.limits().pageSize(), jsonMapper);
    }

    @Bean
    ReadOnlyMcpTools readOnlyMcpTools(McpClientHolder holder) {
      return new ReadOnlyMcpTools(holder);
    }
  }
}
