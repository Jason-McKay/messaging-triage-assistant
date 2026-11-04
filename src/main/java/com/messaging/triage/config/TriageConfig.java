package com.messaging.triage.config;

import com.messaging.triage.chat.ChatService;
import com.messaging.triage.chat.NotificationReadTools;
import com.messaging.triage.chat.ReadOnlyMcpTools;
import com.messaging.triage.report.FailureGrouper;
import com.messaging.triage.report.ReportBuilder;
import com.messaging.triage.source.FailureSource;
import com.messaging.triage.source.mi.McpClientHolder;
import com.messaging.triage.triage.AnswerValidator;
import com.messaging.triage.triage.LatestTriageHolder;
import com.messaging.triage.triage.TriageService;
import com.messaging.triage.triage.Triager;
import jakarta.validation.Validator;
import java.time.Clock;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Grouping, the model, and chat. The sources are whatever {@link ClientConfig} defined. */
@Configuration(proxyBeanMethods = false)
public class TriageConfig {

  @Bean
  FailureGrouper failureGrouper(TriageProperties properties) {
    return new FailureGrouper(properties.limits());
  }

  /** Sources in {@code @Order}: notification-platform, then message-inspector. */
  @Bean
  ReportBuilder reportBuilder(ObjectProvider<FailureSource> sources, FailureGrouper grouper,
    TriageProperties properties, Clock clock) {
    return new ReportBuilder(sources.orderedStream().toList(), grouper, properties.limits().promptBudgetChars(), clock);
  }

  @Bean
  AnswerValidator answerValidator(Validator validator) {
    return new AnswerValidator(validator);
  }

  @Bean
  Triager triager(ChatModel chatModel, TriageProperties properties, AnswerValidator validator) {
    return new Triager(chatModel, properties.model(), validator, properties.limits().maxTextChars());
  }

  @Bean
  LatestTriageHolder latestTriageHolder() {
    return new LatestTriageHolder();
  }

  @Bean
  TriageService triageService(ReportBuilder reportBuilder, Triager triager, LatestTriageHolder latest, Clock clock) {
    return new TriageService(reportBuilder, triager, latest, clock);
  }

  @Bean
  ChatService chatService(ChatModel chatModel, TriageProperties properties,
    ObjectProvider<NotificationReadTools> notificationTools, ObjectProvider<ReadOnlyMcpTools> mcpTools,
    ObjectProvider<McpClientHolder> mcpHolder, LatestTriageHolder latest) {
    return new ChatService(chatModel, properties.model(), notificationTools, mcpTools, mcpHolder, latest,
      properties.chat().maxHistoryTurns(), properties.chat().maxToolOutputChars());
  }
}
