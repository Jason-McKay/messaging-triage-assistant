# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**This file holds the rules; the reasons are in `docs/`.** Read a rule's reason before
changing or deleting it, and put new reasoning in the matching `docs/` file, not here.

| Topic | Reasons in |
|---|---|
| Spring AI 2.0 and Ollama, Boot 4, the demo stacks, Windows | `docs/gotchas.md` |
| What each test pins, and why none needs a running service | `docs/testing.md` |
| What is still open, and the way toward an incident agent | `docs/known-gaps.md` |
| Deterministic gathering, read-only tools, untrusted data, per-group calls, the output guards, abstracted values | `docs/adr/` |

## What this is

**messaging-triage-assistant**: explains failed messages in two sibling systems with a local
model. Code gathers and groups the failures; Ollama (`qwen3:8b`, thinking off) explains each
group as JSON, one call per group; code checks every answer and writes the summary; a chat
endpoint answers questions with read-only tools. On demand only, and it changes nothing
anywhere. Spring Boot 4.1 / Java 21 / Spring AI 2.0, single module.

- `message-inspector`: JMS dead-letter queues on Artemis, read over its MCP server (`/mcp`).
- `notification-platform`: dead-lettered notifications, read over its worker API (:8081).

Both are separate public repos. **This repo never modifies them**; the demo runs their own
compose files with an override for ports.

Roadmap: a local planning doc kept outside the repo. The repo is public, so it stays out.

## Commands

Maven wrapper (`./mvnw` in Bash, `.\mvnw.cmd` in PowerShell):

```bash
./mvnw test                                  # unit tests (surefire)
./mvnw clean verify                          # plus OllamaDownIT (failsafe); what CI runs
./mvnw test -Dtest=ChatToolLoopTest          # one class
./mvnw test -Dtest=EvalTest                  # eval set, scripted: target/eval/scripted-results.md
./mvnw spring-boot:run                       # 127.0.0.1:8085
```

`demo/eval.sh` (or `.ps1`) runs the eval set against the live model: Ollama only, no demo
stack, answers in `target/eval/live-results.md`, which `clean` deletes. In PowerShell quote
extra `-D` arguments: `'-Deval.parallel=1'`.

Demo (each script has a `.ps1` twin): `demo/up.sh`, `demo/seed.sh`, then `spring-boot:run`,
`demo/smoke.sh`, `demo/down.sh`. Needs the sibling repos next to this one, or `NP_DIR` /
`MI_DIR`. From PowerShell run the `.sh` files through Git Bash
(`& "C:\Program Files\Git\bin\bash.exe" ./demo/up.sh`) or use the `.ps1`.

CI (`.github/workflows/ci.yml`) runs `./mvnw -B clean verify`. `security.yml` runs OWASP
Dependency-Check weekly and on dispatch; it needs the `NVD_API_KEY` repo secret.

## Packages

Under `com.messaging.triage`, named by role:

| Package | Holds |
|---|---|
| `source` | `FailureSource` (the seam), `Failure`, `SourceSection` |
| `source.np` | notification-platform: token, GET-only worker client, gatherer |
| `source.mi` | message-inspector: login token, lazy MCP client, read-only client, gatherer |
| `report` | `FailureGrouper`, `ErrorTextNormalizer`, `ReportBuilder`, `FailureReport` |
| `triage` | `Triager`, `TriagePrompt`, `ModelView`, `AnswerValidator`, `TriageSummary`, `Untrusted`, `LatestTriageHolder` |
| `guard` | `RepeatedDiagnosisCheck`, `EvidenceCheck`, `ActionGuard`: pure checks on what the model wrote |
| `chat` | `ChatService` (the tool loop), `ReadOnlyMcpTools`, `NotificationReadTools`, `RecordingToolCallback` |
| `web` | the two controllers |
| `auth`, `config`, `support` | token caching, beans and properties, `Problems` |

The seams a later incident agent builds on are `FailureSource`, `FailureReport`, `Triager`
and `LatestTriageHolder` (`docs/known-gaps.md`, "Toward an incident agent").

## Rules

Reasons in the ADRs and `docs/gotchas.md`:

- **The read-only allowlist lives in one place**, `MessageInspectorClient.READ_ONLY_TOOLS`.
  Add a read tool there and nowhere else. Never add a write tool (ADR 0002).
- **Write tools never reach the model.** The MCP filter, the fail-closed name check
  (`UnsafeToolException`, never caught and degraded) and the USER account all stay.
  `NotificationWorkerClient` stays GET-only; `NotificationWorkerClientTest` checks by
  reflection.
- **The tool loop lives in `ChatService`**, because 2.0.2's `ChatModel.call` returns tool
  calls unexecuted. It runs only names in the request's own list, each through
  `RecordingToolCallback`, and caps rounds as well as calls.
- **`ChatModel`, not `ChatClient`**: the template renderer reads the JSON schema's braces as
  placeholders.
- **A triage call holds one group, has no tools** and is constrained by the answer schema;
  the model never decides what is gathered (ADR 0001, 0004).
- **Code owns ids, flags and the summary.** `GroupAnswer` has no group id and no flag field:
  code files each answer under the group it asked about, runs the three guards on every
  usable one, and writes the summary (`TriageSummary`) from ids and counts, never from a
  value in the systems (ADR 0004, 0005). Never let the model set or clear a flag.
- **Everything from the systems is fenced with `Untrusted.wrap`** before it reaches a
  prompt: errors, payloads, template keys, dimensions, tool results, the latest triage in
  chat (ADR 0003).
- **Client-controlled values reach the model through `ModelView`**: a key or dimension that
  is not a plain identifier, and JSON payload prose, become placeholders, in triage and in
  chat tool output alike. The report keeps the value as gathered (ADR 0006).
- **Chat answers go through `ActionGuard` too**; replies code writes ("Stopped: ...") do not.
- **Log no prompts, completions, payloads or tokens.** Counts, ids, durations and exception
  types only. Token holders redact `toString`.
- **A source that is down is a section with `UNAVAILABLE` and a reason**, never an exception
  out of `POST /api/triage`, which always answers 200. A failed model call is
  `usable=false`, with the facts still returned.
- **No test needs Ollama, Docker or the sibling apps.** Fake the `ChatModel`, stub MCP with
  `McpStubs`, use WireMock for HTTP. `OllamaDownIT` points at a closed port on purpose.
  `LiveEvalTest` is the exception, off unless `-Deval.live=true`.
- **A triage of more than one group uses `ScriptedChatModel`**, not consecutive stubs: the
  calls run in parallel.
- **A new guard gets a unit test fed bad output and an eval case** whose `guardedAgainst`
  names what it protects; `EvalTest` then checks the case fails without it.
- **The repo carries no secrets and no local paths.** The defaults in
  `application.properties` are the siblings' throwaway demo credentials and are labelled so.
- **`.ps1` files stay ASCII; `.sh` files and `mvnw` stay LF** (`.gitattributes`).

## Conventions

- Java is 2-space indented, records for data, constructor injection, `@Slf4j`.
- `@ConfigurationProperties` records under `triage.*`, every property commented in
  `application.properties`.
- Commits: conventional style, short subject.
- Non-obvious decisions go in `docs/adr/` (Nygard-style, numbered, append-only).
- A new test goes in the `docs/testing.md` table; a new gap in `docs/known-gaps.md`.
