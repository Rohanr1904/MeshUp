# Agent Orchestration & Token Economy

## Hierarchy
Chief of Engineering: Claude Opus 5.5.
Specialists: up to 6.
Roles: mesh-architect, android-engineer, security-engineer, qa-engineer, ux-product, research-scout.

## Routing
Haiku: discovery, simple research, logs, test triage.
Sonnet: normal coding, UI, tests, refactors, integrations.
Opus 5.5: architecture, mesh/protocol, crypto/security, concurrency, difficult debugging, high-risk review.

Always prefer the cheapest model that can safely complete the task.

## Concurrency
Maximum active specialists: 6.
0–1 for simple tasks; 1 for focused work; 2–3 for independent investigations; 4–6 only for genuinely parallel work.
Do not spawn six agents by default.

## Token economy
Use project knowledge/RAG instead of pasting large documents.
Delegate verbose logs, tests and exploration so only concise summaries return.
Do not dump whole files, huge logs or full diffs into the lead context.
Use exact file paths, constraints, deliverables and verification targets in delegation prompts.
Reuse durable docs instead of repeatedly rescanning the repository.
Prefer one owner per file during parallel editing.
Keep specialist reports concise: result, files, tests, risks, recommendation.

## Escalation
Escalate to Opus 5.5 when uncertainty remains or the task affects protocol, crypto, concurrency, data integrity, architecture or security.

Model changes happen at spawn/resume boundaries; do not assume a running teammate can change models in place.

## Agent Teams
Standard subagents are the default for token efficiency.
Use Agent Teams only when agents genuinely need to communicate with each other; they consume substantially more tokens because each teammate keeps a separate context.

## Chief responsibilities
Maintain the single source of truth, prevent duplicate work, choose model/agent count, synthesize findings, make final architecture decisions and verify claims.

No agent may invent crypto, casually change the wire protocol, rewrite stable mesh infrastructure without justification, alter licensing, or claim physical-device verification without physical devices.
