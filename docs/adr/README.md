# Architecture Decision Records

Short records of decisions that were **not obvious**, written so the reasoning survives the
people who made it. Each one states the context, the decision, and what it cost.

Format is Nygard-style, numbered, append-only: supersede a record rather than editing it
once it is accepted.

| # | Title | Status |
|---|---|---|
| [0001](0001-deterministic-gathering-lazy-mcp.md) | Code gathers the failures; the model only explains them. MCP connects lazily | Accepted |
| [0002](0002-read-only-by-construction.md) | Read-only by construction: write tools never reach the model | Accepted |
| [0003](0003-data-from-the-systems-is-untrusted.md) | Data from the systems is untrusted, and fenced as such | Accepted; amended by 0006 |
| [0004](0004-per-group-calls-and-a-code-written-summary.md) | One model call per group, and a summary written by code | Accepted |
| [0005](0005-deterministic-output-guards.md) | Guards in code on what the model writes | Accepted |
| [0006](0006-abstract-client-controlled-values.md) | Values a client controls reach the model abstracted | Accepted |

0001 to 0003 were written with v1 on 2026-11-03. They record the three decisions an incident
agent built on this repo would have to keep, or knowingly reverse. 0004 to 0006 were written
on 2026-11-04, after the v1 live run showed copied diagnoses and an injection swaying the
prose; they move the checks on the model's output into code.
