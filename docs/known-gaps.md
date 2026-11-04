# Known gaps

What is still open, and what closing each would take. The observations behind the first
two sections are in the README's "Honest limits": v1 on 2026-11-03, and the guards (ADRs
0004 to 0006) on 2026-11-04, both with `qwen3:8b` on the seeded demo.

Closed by the guards, and kept here only as a pointer: diagnoses copied onto groups they do
not fit (ADR 0004, 0005), the injected template key and payload swaying the answers
(ADR 0004, 0006), unflagged advice to change message state (ADR 0005), and the missing eval
set (`EvalTest`, `demo/eval`).

## Diagnosis quality

- **The guards are lexical.** They read words, not meaning (ADR 0005).
  - `ActionGuard` misses an action word not in its lists, another language, and advice
    spelled out over several clauses. It also flags more than advice: "No, you should not
    resend ... before considering any resends" was flagged in chat, and "the payload says to
    delete every message" is flagged on purpose, since words alone cannot tell a quote of
    the data from advice.
  - `EvidenceCheck` proves a quote is in the group, not that it supports the cause. It is
    strict where the model is sloppy: `qwen3:8b` quoted `status=DEAD_LETTER` for
    `DEAD_LETTERED` in five eval cases, and each correct diagnosis lost its HIGH as
    `UNSUPPORTED`. A repair call for unsupported evidence was rejected for now: shown what it
    may quote, the model can fit a quote to the cause it already chose.
  - `RepeatedDiagnosisCheck` takes a copy reworded below 80% word overlap for a different
    cause.
- **A repeat sends both groups back**, the original too: the check cannot tell which came
  first. Two groups whose errors differ but whose cause really is the same are repaired and
  may end unusable. Not seen live, since the demo's one shared cause has the same error each
  time.
- **No diagnosis relates one group to another.** Each call sees one group (ADR 0004), and the
  summary is code that names ids, counts and flags only. "NP-1, NP-2 and NP-3 fail on the
  same missing template" is for the reader to see. A model pass across the groups would bring
  back the one field an injection reached in v1.
- **The prompt's rule to report instructions in the data has never been seen working.** In v1
  the model never reported one; in the guarded runs it had none to report, since both demo
  injections reach it as placeholders (ADR 0006). An injection in an error text, an XML
  payload, a message property or a plain identifier still reaches the model, fenced, and no
  seed or eval case carries one yet. There, only the guards stand behind the fence.
- **The system prompt is shared by both sources**, and message-inspector answers sometimes
  tell the operator to check a notification-platform template. A line per source in the
  prompt is the likely fix; it is not built.
- **The model cannot read an unsafe template key.** A key that is not a plain identifier
  reaches it as `[unsafe template key, N chars]`, so it can say the key is not usable, not
  which template was meant. The operator still has the key in the report (ADR 0006).
- **Normalization hides a clue.** `ErrorTextNormalizer` replaces quoted values, so
  `Unexpected character ('<' ...)` becomes `('<value>' ...)` in the group's error. The `<`
  survives in each sample's raw error and payload, which the prompt also carries; the model
  named XML in 2 of 3 v1 runs and in all 7 guarded ones. Keeping single punctuation
  characters would cost a separate group per stray character.
- **The eval set is small and written by hand.** Nine cases (`src/test/resources/eval/`)
  whose scripted answers show each guard at work, and the same reports sent to the live
  model on demand (`demo/eval`), which reports rather than asserts. Its measures are lexical
  too: word overlap for a copy, named words for a cause. Recorded live runs scored against
  the seeds would make a larger set, and a fair comparison of models.
- **Chat stops short on "the payments DLQ messages".** In v1 it browsed the empty `payments`
  queue; with the guards it named `DLQ` and `_AMQ_ORIG_QUEUE`, then ended without browsing.
  Told to browse `DLQ` for the messages from payments, it did. A `browse_messages` filter on
  the original queue would be a message-inspector change.

## Model

- **Only `qwen3:8b` with thinking off was tried**, on one 12 GB GPU. `TRIAGE_MODEL` takes any
  Ollama model, but the schema-constrained output and tool calling were seen to work only
  with this one. `demo/eval` is the way to try another.
- **A triage takes 31 to 39 s on the demo**, held on one HTTP request: nine calls of 2.8 to
  4.7 s, one after another, since this Ollama serves one request at a time. Time grows with
  the groups, up to `max-groups` per source and two calls each. A smaller GPU or CPU only
  takes minutes per call, which is why the read timeout is 5 minutes. A job with a status to
  poll would suit a caller better than a held request.
- **Two triage requests at once are not serialized.** Their calls share one limit
  (`max-parallel-calls`), so both take longer, and whichever finishes last becomes `latest`.
- **The prompt budget is still the report's.** `prompt-budget-chars` caps the sample text of
  all groups together, as when one prompt held them all. With one group per call no prompt
  comes near it, yet a report past it still loses samples.

## Security

- **The API has no authentication.** It listens on 127.0.0.1 only (`server.address`), so
  anything on the machine can call it, and nothing off it can. Exposing it needs a security
  chain first.
- **notification-platform's token carries the write scope too.** Its api mints one token with
  both scopes for its one client credential. This app only ever GETs the worker
  (`NotificationWorkerClientTest` pins that), but the token it holds could do more. A
  read-only credential needs notification-platform to separate callers (its own known gap).
- **The defaults are the siblings' demo credentials** (`dev-secret`, the message-inspector
  USER). They are labelled as such in `application.properties` and must be overridden
  anywhere else.
- **Chat history is the caller's.** The server keeps no conversation, so `history` is
  whatever the caller sends, assistant turns included. The caller is the one asking, so this
  grants nothing new, but a stored conversation would need its own trust decision.

## Scope

- **On demand only.** Nothing runs on a schedule, nothing alerts.
- **The latest triage is held in memory, one slot** (`LatestTriageHolder`). A restart forgets
  it; there is no history and no way to compare runs.
- **The reads are capped.** notification-platform: one page (100) each of `DEAD_LETTERED`
  and `FAILED`, newest first, and detail reads for 50 of them, since the list carries no
  error text. message-inspector: the first page (100) of each failure queue. Each cap that
  bites is reported in `truncation`. Past them, older failures are counted but not grouped.
- **message-inspector's MCP previews payloads at 500 characters**, so a long JSON payload is
  cut mid-document and its shape is lost.
- **A JMS failure's reason comes from a message property** (`errorReason` by default,
  `triage.sources.message-inspector.error-property`). Artemis records none of its own, so on
  a broker whose consumers set no such property every message groups as "no recorded reason"
  and the model has only the payload to go on.
- **The demo's message-inspector failures are simulated** (written straight onto `DLQ`), and
  its notification-platform stack is the one per machine, whose counts grow with each seed.

## Toward an incident agent

v1 is the read half of an agent that would watch both systems and act on what it finds.
These are the seams it would build on, and what each next step needs.

**The seams, as they stand:**

- `FailureSource`: one per system, `gather()` returning failures, counts and notes. A third
  system is a new implementation and a bean; nothing downstream changes.
- `FailureReport`: the deterministic facts, independent of the model. It is what a
  scheduler would persist and diff.
- `Triager`: report in, verdict out (`TriageAnswer` plus `ModelOutcome`). Per-group calls
  and the guards already sit behind it; a different model or a second checking pass would
  too.
- `LatestTriageHolder`: one slot today. It is where a store would go.

**The steps, in order:**

1. **Persist and schedule.** A store for `TriageResult`, a scheduled trigger, and the report
   kept even when the model fails. Cheap, and it gives the eval set real cases.
2. **Diff and alert.** Compare a report with the last one: a new group, a group growing
   faster than a threshold, a source going `UNAVAILABLE`. The diff is code; the model
   explains it. Alerting is a sink (chat, mail) behind its own interface.
3. **An eval gate.** `EvalTest` runs in CI, but it gates the guards, not the model: its
   answers are scripted. Before anything acts, score recorded live runs against known
   causes, and keep a threshold as a check on prompt or model changes.
4. **Write capability, last and separately designed** (ADR 0002). An ADMIN credential held
   apart from the read one; the model proposes an action, a person confirms it, and an audit
   line records who confirmed what. The proposal must name evidence that `EvidenceCheck`
   holds. Resend only after the cause is fixed, which is a fact a person supplies, not one
   the model infers.

Each step keeps ADR 0001's split: code decides what is gathered and what may happen, and the
model explains.
