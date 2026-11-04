# Gotchas

Most of these cost real time on the way in.

## Spring AI 2.0 and Ollama

- **`ChatModel`, not `ChatClient`.** `ChatClient` renders prompt text as a template, and the
  JSON schema in the triage system message is full of braces the renderer reads as
  placeholders. `Triager` and `ChatService` build a `Prompt` and call `ChatModel` directly.
- **`spring.ai.retry.max-attempts=0`.** A local model that failed once fails again, and each
  retry holds the HTTP request for another read timeout (5 minutes). Triage has its own
  single repair call instead, for a bad answer rather than a failed call.
- **`spring.ai.ollama.init.pull-model-strategy=never`.** Otherwise a missing model is a
  multi-gigabyte download at startup. Missing, it is "model unavailable" in the result.
- **Thinking is turned off per request** (`OllamaChatOptions.disableThinking()`), for triage
  and chat both: qwen3 thinks by default, which multiplies latency and, for triage, puts a
  `<think>` block before the JSON. The parser strips think blocks and code fences anyway.
- **The triage output is constrained by Ollama's `format`** set to the answer's JSON schema
  (`BeanOutputConverter.getJsonSchemaMap()`). That makes a well-formed object likely, not
  certain, and says nothing about the group ids, hence `AnswerValidator`.
- **`ChatModel.call` does not run tools in 2.0.2.** `OllamaChatModel` sends the tool
  definitions and hands the model's tool calls back unexecuted (tool execution moved to
  `ChatClient`'s advisors), so a chat with tools comes back with empty text and nothing called.
  `ChatService` runs the loop itself: only names in its own list, each through
  `RecordingToolCallback`, at most `MAX_TOOL_ROUNDS` rounds. qwen3:8b with thinking off does
  emit tool calls.
- **`OllamaChatOptions.getToolCallbacks()` is `null` when no tools were set**, and there is no
  `getToolNames()` in 2.0.2.
- **The MCP client starter is not used**, only `spring-ai-mcp`: the starter connects while the
  context starts, so a message-inspector that is down would stop the app booting (ADR 0001).
  `SyncMcpToolCallbackProvider` asserts that the client has client info and capabilities;
  `ClientConfig` sets the info, and test mocks must stub both.
- **message-inspector's MCP previews payloads at 500 characters.** A JSON payload cut there
  does not parse, so its top-level keys are empty and sample selection treats it as
  shapeless.
- **This Ollama serves one request at a time.** Its server log shows `OLLAMA_NUM_PARALLEL:1`,
  so at `triage.limits.max-parallel-calls=2` the second call waits inside Ollama: warm
  triages took the same time at 1 and at 2 (ADR 0004). The wait is invisible to the app. It
  counts in the group's `durationMs`, which leaves out only the wait for the app's own slot,
  and in the call's read timeout, since the request has been sent. More slots in Ollama
  each take their own context memory.

## Boot 4

- **Jackson 3** (`tools.jackson`, not `com.fasterxml`), and `readTree` throws unchecked
  `JacksonException`. A record component that is a primitive `int` makes a missing JSON
  field an error ("Cannot map `null` into type `int`"); `WorkerViews` relies on that for the
  fields notification-platform always sends.
- **The MVC starter is `spring-boot-starter-webmvc`.**
- **`server.address=127.0.0.1`.** The API has no authentication of its own, so it listens on
  loopback only (`docs/known-gaps.md`).

## The demo stacks

- **Ports.** The shared dev stack already holds `dev-artemis` on 8161/61616 (and `dev-mysql`
  3306, `dev-redis` 6379), and notification-platform takes 8080 and 8081. message-inspector's
  own compose wants 8080, 8161 and 61616, so `demo/message-inspector.ports.yaml` replaces its
  port lists with `ports: !override` (Compose 2.24 or later): app 8082, Artemis console and
  Jolokia 8162, JMS 61617. Neither sibling repo is edited.
- **message-inspector runs as compose project `mi-demo`**, so its containers and anonymous
  volumes are this demo's own and `demo/down` can remove them with `-v`.
  notification-platform's compose sets fixed `np-*` container names and a named volume
  (`np_mysql_data`), so there is one notification-platform stack per machine: the demo uses
  it as it is, and `demo/down` keeps the volume. Each seed therefore adds to its counts.
- **The message-inspector failures are simulated.** `demo/seed` writes them onto `DLQ`
  through Jolokia's `sendMessage` with `_AMQ_ORIG_QUEUE` and `errorReason` set, the
  properties a failing consumer would leave. Artemis records no reason of its own when it
  dead-letters a message, so without such a property every message groups as "no recorded
  reason".
- **Jolokia needs no `Origin` header from a script.** The broker's `jolokia-access.xml` allows
  only the origin `*://0.0.0.0*` and has no `<strict-checking/>`, so a POST without the header
  is served. `Origin: http://localhost` would not match that allowlist anyway; `demo/seed`
  sends no `Origin`.
- **notification-platform accepts any template key**, up to `varchar(128)`, so the injected
  "template key" in the demo is a real notification that really dead-letters.

## Windows

- **`.sh` files and `mvnw` must stay LF** (`.gitattributes` forces it). Run them from
  PowerShell through Git Bash, `& "C:\Program Files\Git\bin\bash.exe" ./demo/up.sh`, or use
  the `.ps1` twin.
- **Keep the `.ps1` files ASCII.** Windows PowerShell 5.1 reads a BOM-less file as the ANSI
  code page, so one em dash, even in a comment, becomes a parser error on the wrong line.
  Check with `grep -nP '[^\x00-\x7F]' demo/*.ps1`.
- **Git Bash rewrites absolute paths** in arguments to native programs, `docker exec` among
  them; prefix `MSYS_NO_PATHCONV=1` if you exec into a demo container.
- **Quote `-D` arguments in Windows PowerShell.** It splits `-Deval.parallel=1` at the dot
  before Maven sees it. `demo/eval.ps1` quotes its own, and extra ones need quotes too:
  `demo/eval.ps1 '-Deval.parallel=1'`.
