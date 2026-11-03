# 1. Code gathers the failures; the model only explains them. MCP connects lazily

- **Status:** Accepted
- **Date:** 2026-11-03, with v1

## Context

Two systems hold the failures worth triaging. notification-platform parks notifications it
could not deliver (status `DEAD_LETTERED`, with the attempts that failed). message-inspector
reads an Artemis broker whose failure queues (`DLQ`, `ExpiryQueue`) hold the JMS messages
nobody could process. Both expose reads: notification-platform's worker over REST,
message-inspector over REST and over an MCP server.

The tempting design is an agent: hand a local model every read tool and ask "what is
failing and why?". With `qwen3:8b` on a laptop GPU that is slow (each tool round trip is a
full generation), and it is not repeatable: the same broker state gives a different set of
calls, a different subset of the evidence and so a different answer. Worse, a model that
decides what to read can decide not to read the source that matters.

## Decision

- **Gathering is code.** Each source is a `FailureSource` that reads its failures in a fixed
  order with fixed limits: stats, a page of `DEAD_LETTERED` and `FAILED`, at most 50 detail
  reads for notification-platform; a browse of each failure queue for message-inspector.
- **Grouping is code.** `FailureGrouper` groups by the failure's dimensions plus its error
  text reduced to a shape (`ErrorTextNormalizer`: ids, numbers and quoted values become
  placeholders). It is pure: the same failures in any order give the same groups with the
  same ids, so `NP-2` means the same thing to the prompt, the validator and the reader.
- **The model gets one call with no tools**, its output constrained to the answer's JSON
  schema, and the answer is checked against the report: every group id diagnosed exactly
  once, no invented ids. One repair call if not; after that the result says
  `usable=false` and why. The report is returned either way: the facts do not depend on
  the model.
- **Chat is where tools live** (ADR 0002), for questions the report does not answer.
- **message-inspector's MCP client connects on first use** (`McpClientHolder`), not at
  startup, and is thrown away after a failure. Spring AI's MCP client starter would connect
  while the context starts, so a message-inspector that is down would stop this app from
  booting, and with it the notification-platform half of every triage. The starter is not
  used; the client is built by hand in `ClientConfig`.

## Consequences

- A triage is repeatable and cheap to test: `FailureGrouperTest` and `TriagePromptTest` pin
  the exact groups and prompt with no model at all.
- **The model cannot ask for more evidence.** If the cause is in a message the gatherer did
  not read (past the first page, past the detail limit), the triage cannot find it. Every
  cut is written into the report's notes and the prompt, so the model and the reader both
  know what was left out.
- A source that is down is a section with `status=UNAVAILABLE` and a reason, not a failed
  triage. The model is told not to guess what it holds.
- The first triage or chat after message-inspector comes up pays its MCP handshake.
- Adding a source is a `FailureSource` bean and nothing else: the grouper, prompt and
  validator take sections, not sources.

## Alternatives considered

- **Agent with all read tools.** Rejected for v1 for the reasons above. It remains the
  shape of the later incident agent, which would start from this report rather than from
  nothing (`docs/known-gaps.md`, "Toward an incident agent").
- **Spring AI's MCP client starter.** Rejected: eager connection at startup, see above.
- **message-inspector's REST API instead of MCP.** Equally possible for the triage reads.
  MCP was chosen because chat needs the MCP tools anyway, so one client and one account
  serve both, and the server's own read/write split is then the third wall in ADR 0002.
