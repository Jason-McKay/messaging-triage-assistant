# Testing notes

- **No test needs Ollama, Docker or either sibling app.** `./mvnw clean verify` passes on a
  machine with nothing running, which is what CI does. The model is a Mockito `ChatModel`,
  notification-platform is WireMock, message-inspector's MCP client is a Mockito
  `McpSyncClient` (`McpStubs`).
- **`./mvnw test` does not run `*IT` classes**; `./mvnw verify` runs them under failsafe.
  There is one, `OllamaDownIT`, and it needs nothing either.
- **`LiveEvalTest` is the one test that needs Ollama**, and it is off unless
  `-Deval.live=true` is set, which `demo/eval` does. It reports rather than asserts.
- What each class pins:

| Class | What it pins |
|---|---|
| `FailureGrouperTest` | Normalization (ids, numbers, quoted values), order and numbering, same groups for any input order, the OTHER merge, sample choice, text caps, prompt-budget fitting |
| `TriagePromptTest` | The rules in the system message; one group per prompt, every value fenced and ids bare; a payload cannot close its fence or start a line; the seeded injections reach the model as placeholders; reference cleaning; caps; the repair and repeat messages |
| `TriagerTest` | Each group asked alone and its diagnosis filed under its own id, whatever id the model writes; schema-constrained calls with thinking off and no tools; one repair per group; a group still unusable left out and named, the others kept; blank fields refused; evidence kept, or flagged and dropped; advice to change state flagged, capped and warned in any field; a copied cause sends both groups back, and a copy that stays is unusable; the same cause for the same error kept; a model that fails stops further calls and the rest are skipped; parallel up to the limit; think blocks and fences stripped; no groups means no call |
| `TriageSummaryTest` | Each source's failures, groups and largest group; a source not read, a source with none, and groups with no usable diagnosis named; the groups under each flag; no value from the systems echoed |
| `ModelViewTest` | ADR 0006: a plain identifier passes, anything else becomes a placeholder of kind and length, in the attributes and in every error that quotes it, also where normalization changed it or a cap cut it; a JSON payload keeps its shape and loses its prose; an MCP tool result has its payloads abstracted inside the text content; the texts the evidence check reads are the lines the prompt writes |
| `RepeatedDiagnosisCheckTest` | The same cause or next step for different errors is a repeat of both; a near copy is one, a different cause or a negation is not; groups with the same error may share a cause; a merged group is never compared |
| `EvidenceCheckTest` | A quote holds despite case, spacing, quote marks and edge punctuation, and with a label or fence the prompt wrote; with an ellipsis, only if every part does; across two fields, never; placeholders and scraps are not evidence; a count must be the group's; none is `UNSUPPORTED`; only the quotes that held are kept |
| `ActionGuardTest` | Each action, paraphrases, the passive, modals, conditional advice, spelling tricks and a reported instruction are advice; a tight negation, a description of what happened, a verb modifying a noun and look-alike words are not; the warning names no action and goes in front once |
| `NotificationWorkerClientTest` | The bearer token, its reuse and refresh margin, a 401 retried once, list and detail parsing, the GET-only surface |
| `NotificationWorkerGathererTest` | The template key cleaned and cut to 128 characters for the report |
| `NotificationReadToolsTest` | An unsafe template key is a placeholder in chat, in the notification and in the attempts that quote it; a plain key is shown as it is |
| `McpMessageInspectorClientTest` | The three reads over MCP; an MCP error result is an answer, a failed call reconnects and drops the token once; only read tools are ever called |
| `MessageInspectorGathererTest` | Grouping dimensions (queue, origin, recorded reason); missing queue and partial page noted; one unreadable queue skipped; none readable is UNAVAILABLE |
| `ReadOnlyToolsTest` | ADR 0002: six advertised tools give three; the chat model sees no write name; a leaked write tool stops the chat before the model is called; the triage prompt has no tools |
| `ChatToolLoopTest` | The tool loop: a tool call runs and its fenced result goes back before the answer; a name not in the list runs nothing; rounds and calls both capped; advice to change state warned about and flagged; the latest triage reaches chat fenced, with its flags |
| `RecordingToolCallbackTest` | Tool output fenced, payload prose abstracted first; a throwing tool becomes text and resets its source; past the call limit, refused, then stopped |
| `OllamaDownIT` | The whole app, notification-platform stubbed, model and message-inspector on a dead port: latest 404, triage 200 with the facts and `usable=false`, chat 503, latest 200 |
| `EvalTest` | Every eval case passes through the real `Triager` with scripted answers; each guard a case names is what makes it pass, since the same answers taken as written fail; the set holds the demo, both injections and a case per guard. Writes `target/eval/scripted-results.md` |
| `LiveEvalTest` | Nothing: it sends the eval set to the live model and reports, only with `-Deval.live=true` (`demo/eval`). Writes `target/eval/live-results.md` |

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
  (`whatToCheck`), not on Hibernate Validator's wording.
- **Consecutive stubs do not work for a triage of more than one group.** The calls run in
  parallel on virtual threads, so `thenReturn(a, b)` hands answers out in whatever order the
  threads arrive. `ScriptedChatModel` answers from a script per group id, read from the
  prompt, and records every prompt. Running out of script throws an `AssertionError`, which
  the `Triager` does not catch, rather than an exception it would take for a model that is
  down.
- **`clean` deletes the live eval's output**, `target/eval/live-results.md`; copy it out
  before a `clean verify` to keep it. `scripted-results.md` comes back with any test run.
- The live checks (real model, both demo stacks) are not tests; they are the steps in the
  README's "Demo" and their observed results are in "Honest limits".
