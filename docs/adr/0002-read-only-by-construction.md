# 2. Read-only by construction: write tools never reach the model

- **Status:** Accepted
- **Date:** 2026-11-03, with v1

## Context

message-inspector's MCP server advertises six tools: three reads (`list_queues`,
`browse_messages`, `get_message`) and three writes (`resend_message`, `delete_message`,
`move_message_to_dlq`). A dead-letter queue is exactly where a confused or manipulated model
would do damage: resending a poison message re-runs the failure, deleting one destroys the
evidence. And the payloads on that queue are written by whoever produced the messages
(ADR 0003), so "the model will not want to" is not a control.

A prompt that says "never call write tools" is advice. The question is what the model can
do when it ignores the advice.

## Decision

The model is never shown a write tool. Three walls, any one of which is enough:

1. **The allowlist filter.** `ReadOnlyMcpTools` builds its callbacks through
   `SyncMcpToolCallbackProvider` with a `McpToolFilter` that keeps only the three read
   names. A tool off the list never becomes a callback.
2. **The check after the filter, failing closed.** Every callback that comes out is checked
   by name again, both the name the model would see and the server's original name. One
   that is not on the allowlist (a rename, a prefix, a provider bug) throws
   `UnsafeToolException`, which stops the chat before the model is called. It is never
   caught and degraded into "message-inspector unavailable".
3. **The account.** The MCP client logs in as a USER (`jason` in the demo), and
   message-inspector refuses its write tools to anyone but an ADMIN (its ADR 0009). Even a
   write call that got past this repo would be refused there.

The tool loop is this repo's own too (Spring AI 2.0's `ChatModel` returns tool calls
unexecuted), and it can only run what it holds: `ChatService` looks each call up by name in
the request's callbacks, and a name that is not there, `delete_message` included, is answered
"no such tool" with nothing run.

notification-platform's worker has no write endpoints at all, and `NotificationWorkerClient`
has exactly three public methods, all GETs. Its chat tools (`NotificationReadTools`) are
plain `@Tool` methods over that client.

The triage call itself has no tools at all (ADR 0001).

## Consequences

- `ReadOnlyToolsTest` pins it: a fake server advertising all six gives three callbacks; the
  chat model is shown exactly the six read tools of both systems and no write name; a write
  tool forced past the filter stops the chat with zero model calls; the triage prompt
  carries no tools. `ChatToolLoopTest.aNameThatIsNotInTheListRunsNothing` pins the loop: a
  `delete_message` call from the model reaches no MCP call.
  `NotificationWorkerClientTest.onlyEverReadsTheWorker` pins the GET-only surface by
  reflection.
- **The assistant cannot fix anything.** Asked to resend or delete, it says a person has to,
  in message-inspector or notification-platform, once the cause is fixed. That is the
  intended blast radius, not a missing feature.
- A future write capability is a different design, not a flag: an ADMIN credential, human
  confirmation, and an audit line per action (`docs/known-gaps.md`).
- The allowlist lives in one place, `MessageInspectorClient.READ_ONLY_TOOLS`. A new read tool
  on the server stays invisible until it is added there, which is the safe direction.

## Alternatives considered

- **Prompt-only restraint.** Rejected: it is the control a prompt injection attacks first.
- **Show all tools, confirm writes with a person.** Deferred to the incident agent; v1 has
  no UI to confirm in and no audit trail of its own.
- **Rely on the USER account alone.** Rejected as the only wall: message-inspector lets a
  USER resend over REST (its ADR 0009), so the account's limits are a property of another
  repo that could change. This repo enforces its own.
