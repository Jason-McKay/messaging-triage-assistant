package com.messaging.triage.eval;

import com.messaging.triage.eval.EvalRunner.EvalCase;
import com.messaging.triage.eval.EvalRunner.Result;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The eval set sent to the live model, on demand only ({@code demo/eval.sh}, or
 * {@code -Deval.live=true}): it needs Ollama with the model pulled, nothing else. It reports
 * rather than asserts, since what a model writes is what is being measured: the table and
 * every answer are left in {@code target/eval/live-results.md}.
 *
 * <p>{@code OLLAMA_BASE_URL} and {@code TRIAGE_MODEL} as for the app; {@code -Deval.parallel}
 * as {@code triage.limits.max-parallel-calls}.
 */
@EnabledIfSystemProperty(named = "eval.live", matches = "true")
class LiveEvalTest {

  /** As the app's {@code spring.http.clients.read-timeout}: a cold load takes most of a minute. */
  private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);

  @Test
  void runTheSetAgainstTheLiveModel() {
    String baseUrl = env("OLLAMA_BASE_URL", "http://localhost:11434");
    String model = env("TRIAGE_MODEL", "qwen3:8b");
    int parallel = Integer.getInteger("eval.parallel", 2);

    List<Result> results = new ArrayList<>();
    for (EvalCase evalCase : EvalRunner.cases()) {
      results.add(EvalRunner.live(evalCase, chatModel(baseUrl, model), model, parallel));
    }

    EvalRunner.write("live-results.md", "Model " + model + ", max parallel calls " + parallel + ".\n\n"
      + EvalRunner.table(results) + "\n" + EvalRunner.answers(results));
  }

  /** No retries, as in the app: the triager has its own one repair call. */
  private static OllamaChatModel chatModel(String baseUrl, String model) {
    HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(READ_TIMEOUT);
    OllamaApi api = OllamaApi.builder()
      .baseUrl(baseUrl)
      .restClientBuilder(RestClient.builder().requestFactory(factory))
      .build();
    return OllamaChatModel.builder()
      .ollamaApi(api)
      .options(OllamaChatOptions.builder().model(model).build())
      .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
      .build();
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
