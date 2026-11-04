# 6. Values a client controls reach the model abstracted

- **Status:** Accepted. Amends 0003.
- **Date:** 2026-11-04

## Context

ADR 0003 fences every value from the systems, and the v1 live run showed what that is
worth: the fenced injections were not obeyed as commands, but they still swayed the prose
(README, "Honest limits", v1). Two values carried them in the demo:

- **A notification-platform template key.** The caller of its API picks the key, up to 128
  characters, and the worker's error writes it raw into the error text ("no template
  configured for key ..."), so the injection sat in the group's error as well as in its
  attributes.
- **A message-inspector payload.** A `note` field in a JSON payload asked for every
  message on the queue to be deleted.

Neither value is the diagnosis. That the key names no template matters; what the key says
does not. That the payload lacks `address` matters; what its note says does not. A model
that never reads the text cannot be swayed by it.

## Decision

- **At gather time** `NotificationWorkerGatherer` cuts the template key to 128 characters
  (notification-platform's own limit) through `Untrusted.neutralize`: hidden characters
  become spaces and fence markers are broken. The report keeps this value, for the
  operator.
- **What the model is shown is a copy** (`ModelView`), made per group by the prompt:
  - A dimension or attribute value that is not a plain identifier
    (`[A-Za-z0-9][A-Za-z0-9._:-]{0,63}`) becomes a placeholder naming its kind and length:
    `[unsafe template key, 108 chars]`. So does every place it occurs in the group's error,
    sample errors and payloads, as gathered and as `ErrorTextNormalizer` leaves it, and where
    a capped text cut it short. Values this app writes itself ("unknown origin") are exempt.
  - A JSON payload keeps its shape. Each string in it that is longer than 64 characters,
    or has more than 6 words, becomes `[text, N chars]`. A payload cut off mid-string is
    handled too.
- **Chat gets the same.** `NotificationReadTools` returns the key through the same rule and
  takes it out of attempt details. `RecordingToolCallback` passes every tool result
  through `ModelView.toolOutput` before fencing it: each `payload` field, at any depth and
  inside JSON carried as a string (as MCP returns it), gets the payload rule. Output that is
  not JSON comes back as it was. ADR 0002's filter and name check run before this and are
  unchanged.
- **A placeholder is not evidence.** `EvidenceCheck` (ADR 0005) does not count its letters,
  so a quote of one proves nothing.
- **Left as they were**, fenced, capped and neutralized (ADR 0003): error texts, including
  message-inspector's error property, because they are the evidence; payloads that are not
  JSON (XML, plain text); message properties in chat tool output.

## Consequences

- **On the live run** (2026-11-04, `qwen3:8b`) the model saw the injected key as
  `[unsafe template key, 108 chars]` in every place, and gave that group a correct cause at
  HIGH, with no advice to resend and no other group made LOW. The injected note became
  `[text, 124 chars]`; the cause (the missing `address`) was right, with no advice to delete.
  In chat, browsing the shipments queue returned the payload abstracted the same way.
- **The operator loses nothing**; the report and its JSON carry the value as gathered. The
  model loses the key's text: it can say the key is not a usable identifier, not which
  template was meant.
- **An injection that is a plain identifier passes as itself**, at most 64 characters with
  no spaces (`ignore-previous-instructions`). Short JSON strings pass too.
- **An injection in error text, in XML or in message properties still reaches the model**,
  fenced. What catches its effect is ADR 0005's guards, not this (`docs/known-gaps.md`).
- 0003's other decisions stand. Its line that the answer "is validated against the report's
  group ids" was replaced by ADR 0004: the model writes no ids.

## Alternatives considered

- **Leave the key out of the prompt.** Rejected: "the key is not an identifier, 108
  characters" is evidence of a bad caller, and the error text names it anyway.
- **A hash of the key.** Rejected: no more readable than a length, and it invites the
  model to treat it as a real key.
- **Abstract error texts too.** Rejected: the error is the diagnosis. An error that quotes
  its input is often the clue.
- **Strip instruction-like text.** Rejected in ADR 0003, for the same reason: there is no
  complete pattern for it.
