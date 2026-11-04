# Testing notes

- **No test needs Ollama, Docker or either sibling app.** `./mvnw clean verify` passes on a
  machine with nothing running, which is what CI does. The model is a Mockito `ChatModel`,
  notification-platform is WireMock, message-inspector's MCP client is a Mockito
  `McpSyncClient` (`McpStubs`).
- **`./mvnw test` does not run `*IT` classes**; `./mvnw verify` runs them under failsafe.
  There is one, `OllamaDownIT`, and it needs nothing either.
- What each class pins:

| Class | What it pins |
|---|---|
| `FailureGrouperTest` | Normalization (ids, numbers, quoted values), order and numbering, same groups for any input order, the OTHER merge, sample choice, text caps, prompt-budget fitting |
| `TriagePromptTest` | The rules in the system message; every value fenced and ids bare; a payload cannot close its fence or start a line; reference cleaning; caps; the repair message |
| `TriagerTest` | Schema-constrained call with thinking off and no tools; one repair; unknown, missing, repeated and blank groups refused; think blocks and fences stripped; model down is "model unavailable"; no groups means no call |
| `NotificationWorkerClientTest` | The bearer token, its reuse and refresh margin, a 401 retried once, list and detail parsing, the GET-only surface |
| `McpMessageInspectorClientTest` | The three reads over MCP; an MCP error result is an answer, a failed call reconnects and drops the token once; only read tools are ever called |
| `MessageInspectorGathererTest` | Grouping dimensions (queue, origin, recorded reason); missing queue and partial page noted; one unreadable queue skipped; none readable is UNAVAILABLE |
| `ReadOnlyToolsTest` | ADR 0002: six advertised tools give three; the chat model sees no write name; a leaked write tool stops the chat before the model is called; the triage prompt has no tools |
| `ChatToolLoopTest` | The tool loop: a tool call runs and its fenced result goes back before the answer; a name not in the list runs nothing; rounds and calls both capped |
| `RecordingToolCallbackTest` | Tool output fenced; a throwing tool becomes text and resets its source; past the call limit, refused, then stopped |
| `OllamaDownIT` | The whole app, notification-platform stubbed, model and message-inspector on a dead port: latest 404, triage 200 with the facts and `usable=false`, chat 503, latest 200 |

- **WireMock is `wiremock-standalone`** with `WireMockExtension` on a dynamic port, registered
  `static` so `@DynamicPropertySource` can read its URL before the context starts.
- **A Mockito `McpSyncClient` must stub `getClientInfo()` and `getClientCapabilities()`**,
  or `SyncMcpToolCallbackProvider` fails with "clientCapabilities should not be null" before
  any filtering happens, and the test reads as if the tools were missing. `McpStubs` and
  `ReadOnlyToolsTest.advertisingAllSix` both do.
- **`OllamaChatOptions.getToolCallbacks()` is `null`, not empty, when no tools were set.**
  Assert `isNullOrEmpty()`. There is no `getToolNames()` in Spring AI 2.0.2.
- **WorkerViews records use primitive ints**, so a stubbed page must carry `page`, `size`,
  `totalItems` and `totalPages`: Jackson 3 refuses to map a missing field into an `int`
  ("Cannot map `null` into type `int`").
- **Validator messages are locale-dependent.** `TriagerTest` asserts on the field path
  (`diagnoses[0].groupId`), not on Hibernate Validator's wording.
- The live checks (real model, both demo stacks) are not tests; they are the steps in the
  README's "Demo" and their observed results are in "Honest limits".
