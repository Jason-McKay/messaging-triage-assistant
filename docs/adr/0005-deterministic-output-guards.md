# 5. Guards in code on what the model writes

- **Status:** Accepted
- **Date:** 2026-11-04

## Context

ADR 0003's fences kept injected text from being obeyed as a command, but not from swaying
the prose: in v1 an injected key made every notification-platform group LOW, two summaries
told the operator to resend everything, and in chat the model advised deleting a message
because its payload said so. The validator checked the answer's shape. Nothing checked
whether an answer fitted its group, was copied from another, or told a person to change
message state.

Asking the model to police itself is the thing that failed. The checks have to be code that
reads the model's output as data.

## Decision

Three pure checks in the `guard` package. The `Triager` runs them on the final answer of
every usable group, in this order. Flags and caps are set by code; the model's schema has no
field that could set or clear them.

- **Repeated diagnosis** (`RepeatedDiagnosisCheck`). Two groups repeat each other when their
  causes, or their next steps, share 80% or more of their words (Jaccard, filler words out,
  negations kept), **and** their errors share less than 50%. Merged groups are never
  compared. The errors condition is deliberate: the same missing template on the EMAIL, SMS
  and PUSH channels is one cause, three times, correctly. On a repeat, each group of the pair
  gets its repair call, shown the other's diagnosis (fenced, `kind=other-diagnosis`). A group
  with no call left, or one that still repeats after the repairs, becomes `UNUSABLE`: the copy
  is not kept.
- **Evidence** (`EvidenceCheck`). Each answer gives 1 to 3 short quotes from its group, or a
  count. A quote holds when, normalized (compatibility forms, case, quote marks, spacing),
  every part of it, split at "...", occurs in one of the texts the model was shown for that
  group (`ModelView.shownTexts`: the dimensions line, the error, and each sample's error,
  attributes line and payload), and it has at least 4 letters or digits besides placeholders.
  A label or fence the prompt wrote around a value may be copied into a quote and adds
  nothing. A count holds when its one number is the group's count. No evidence, or any quote
  that does not hold, sets `UNSUPPORTED` and caps confidence at `LOW`. Only the quotes that
  held are kept, so a made-up quote never reaches the reader labelled as evidence. There is
  no repair: the flag is the result.
- **Advice to change message state** (`ActionGuard`). Every field the model wrote (cause,
  next step, what to check, evidence) is read word by word after normalization, for advice
  to resend, replay, requeue, delete, purge, discard, move, remove or bulk-retry messages,
  including "send them again", "put them back" and the passive ("should be resent"). It
  does not count:
  - a tight negation ("do not resend", "must never be moved"), where only filler stands
    between the negation and the verb, so "don't hesitate to resend" still counts;
  - a description of what happened ("was moved to DLQ after 10 attempts");
  - a verb that only modifies a noun ("retry limits", "the purge policy").

  Conditional advice ("resend once fixed") counts. A hit sets `RECOMMENDS_STATE_CHANGE`,
  caps confidence at `LOW`, and puts the fixed `ActionGuard.WARNING` in front of the next
  step. Chat runs the same scan on the model's answer: the warning is appended and the flag
  recorded in the reply's `flags`. Replies written by code ("Stopped: ...") are not scanned.

## Consequences

- **What the guards did on the live run** (2026-11-04, `qwen3:8b`; README, "Honest limits"):
  no copied diagnosis, so the repeat check did not fire; the three groups with the same
  missing template got the same cause, correctly, and were left alone. Chat advice to
  redeliver, resend or remove messages was flagged each time. Four false positives
  were found and fixed on the way, each with a test: "retry configuration" as an action, and
  three quote forms the evidence check missed (the whole attributes line, a leading
  "payload:" label, a copied fence).
- **Still strict where the model is sloppy.** `qwen3:8b` quoted `status=DEAD_LETTER` for
  `status=DEAD_LETTERED` in five eval cases, so a correct diagnosis lost its HIGH. That is
  the check working as written: it proves a quote is there, not that the model meant well.
- **Still conservative.** A chat answer that said "No, you should not resend ... before
  considering any resends" was flagged; so is "the payload says to delete every message",
  on purpose: words alone cannot tell a quote of the data from advice.
- **The guards are lexical.** An action word not in the lists, another language, or advice
  spelled out over several clauses gets past `ActionGuard`. Evidence proves a quote is in
  the group, not that it supports the cause. A repeat whose wording was changed enough is not
  a repeat to the check (`docs/known-gaps.md`).
- **The repeat check sends both groups back**, the copy's source too: it cannot tell which
  is the original. Two groups whose errors differ but whose cause is truly the same are
  repaired and may end unusable.
- Every guard has a unit test fed bad output, and the eval set (`EvalTest`) runs deliberately
  bad scripted answers through the real `Triager`: copied causes, resend advice, fabricated
  evidence, both injections.

## Alternatives considered

- **Ask the model to flag its own advice or rate its own evidence.** Rejected: the model's
  wording is what an injection sways.
- **A second model as judge.** Rejected: it reads the same untrusted text, can be swayed the
  same way, and doubles the calls.
- **A list of exact phrases.** Rejected: "re-send", "send them again", a zero-width space or
  a full-width letter would each get past it. Words are normalized first instead.
- **Embeddings for repeats.** Rejected: another model to run, and not stable across model
  versions. A copy is near verbatim, which word overlap catches.
- **Repair a group with unsupported evidence.** Rejected for now: showing the model what it
  may quote invites a quote fitted to whatever cause it already chose.
