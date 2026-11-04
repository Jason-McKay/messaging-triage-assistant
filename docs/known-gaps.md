# Known gaps

What v1 leaves open, and what closing each would take. The observations behind the first
section are in the README's "Honest limits" (2026-11-03, `qwen3:8b`, the seeded demo).

## Diagnosis quality

- **One diagnosis copied onto groups it does not fit.** In every run read in full, the
  missing-`orderId` diagnosis was given to several notification-platform groups whose error
  was a missing template. `AnswerValidator` checks shape (every group once, no unknown ids,
  no blank fields), not fit. Candidate fixes, none built:
  - flag a `probableCause` repeated word for word across groups whose errors differ, and
    spend the existing repair call on it;
  - one model call per group, or per source, which trades latency for focus;
  - an `evidence` field the validator can check against the group's own error text.
- **Injected instructions were partly followed, and never reported.** Both system messages
  tell the model to say when the data contains instructions; it never did. The
  notification-platform injection made every notification-platform group LOW in all three
  runs, and twice put "resend all dead letters" into the summary; in chat the model advised deleting a message because its payload said
  to. Fencing (ADR 0003) lowers the odds; it does not make them zero. Candidate fixes:
  - a deterministic flag on any answer that recommends a resend, delete or move, shown to the
    operator beside it;
  - a deterministic flag when an answer repeats an action named inside fenced data;
  - a second, tool-less check of the answer against the report.
  None of this can change either system today; the risk is an operator taking the advice.
- **Normalization hides a clue.** `ErrorTextNormalizer` replaces quoted values, so
  `Unexpected character ('<' ...)` becomes `('<value>' ...)` in the group's error. The `<`
  survives in each sample's raw error and payload, which the prompt also carries, and the
  model named XML in 2 of 3 runs. Keeping single punctuation characters would cost a
  separate group per stray character.
- **No eval set.** The observations are a handful of runs read by hand. Recording runs (the
  report and the answer) and scoring them against the seeds would turn "it seemed right"
  into a number, and is the precondition for trying another model or prompt.
- **Chat took "the payments DLQ messages" to mean the `payments` queue**, even after the
  system message explained `_AMQ_ORIG_QUEUE`, and when told to browse `DLQ` it showed the
  first message rather than a payments one. A `browse_messages` filter on the original
  queue would be a message-inspector change.

## Model

- **Only `qwen3:8b` with thinking off was tried**, on one 12 GB GPU. `TRIAGE_MODEL` takes any
  Ollama model, but the schema-constrained output and tool calling were seen to work only
  with this one.
- **A triage answer takes 27 to 39 s**, held on one HTTP request. A smaller GPU or CPU only
  takes minutes, which is why the read timeout is 5 minutes. A job with a status to poll
  would suit a caller better than a held request.
- **Two triage requests at once are not serialized.** Each gathers and calls the model;
  Ollama queues the second, and whichever finishes last becomes `latest`.

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
- `Triager`: report in, verdict out (`TriageAnswer` plus `ModelOutcome`). A different model,
  per-group calls or a second checking pass all fit behind it.
- `LatestTriageHolder`: one slot today. It is where a store would go.

**The steps, in order:**

1. **Persist and schedule.** A store for `TriageResult`, a scheduled trigger, and the report
   kept even when the model fails. Cheap, and it gives the eval set above its data.
2. **Diff and alert.** Compare a report with the last one: a new group, a group growing
   faster than a threshold, a source going `UNAVAILABLE`. The diff is code; the model
   explains it. Alerting is a sink (chat, mail) behind its own interface.
3. **An eval gate.** Before anything acts, score recorded runs against known causes, and
   keep the score as a CI check for prompt or model changes.
4. **Write capability, last and separately designed** (ADR 0002). An ADMIN credential held
   apart from the read one; the model proposes an action, a person confirms it, and an audit
   line records who confirmed what. The proposal must name its evidence, and the
   deterministic flags above must run on it. Resend only after the cause is fixed, which is
   a fact a person supplies, not one the model infers.

Each step keeps ADR 0001's split: code decides what is gathered and what may happen, and the
model explains.
