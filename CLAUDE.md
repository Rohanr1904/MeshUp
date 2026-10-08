# Offline Mesh Messenger — Repository Brief

## Mission

Build a production-grade consumer Android offline-first messenger
on top of the existing BitChat Android foundation.

## Workflow

UNDERSTAND → PLAN → DELEGATE → IMPLEMENT → VERIFY

## Agent Orchestration

You are the Chief of Engineering.

Use the minimum useful number of specialist agents necessary.

Do NOT spawn agents simply because they are available.

Guidelines:
- 0–1 agents for simple tasks
- 1 agent for focused investigation
- 2–3 for independent parallel work
- 4–6 only when genuinely necessary

Optimize for useful progress per token, not maximum parallelism.


## Chief Session

The main Claude Code session acts as the Chief of Engineering.

Use Claude Opus 5.5 as the main session model.

Do not spawn `chief-engineer` as a child agent.
The main session itself owns orchestration, architecture decisions,
delegation, synthesis and final verification.

Specialist agents are workers under the Chief:
- mesh-architect
- android-engineer
- security-engineer
- qa-engineer
- ux-product
- research-scout
- legal-compliance

## Model Selection

Use the cheapest model that can safely complete the task.

- Haiku: discovery, simple research, logs, test triage
- Sonnet: normal implementation, UI, tests, refactoring
- Opus 5.5: architecture, protocol, security, crypto, concurrency,
  difficult debugging, high-risk review

Escalate only when complexity or risk requires it.

## Specialist Agents

Available specialists:
- mesh-architect
- android-engineer
- security-engineer
- qa-engineer
- ux-product
- research-scout
- legal-compliance

Assign only relevant specialists.
Avoid duplicate investigation.
Never allow overlapping edits to the same files.

## Analysis Rule

For unfamiliar or high-risk work:

INSPECT → PLAN → DELEGATE → REVIEW → IMPLEMENT

Do not begin implementation while repository understanding is incomplete.

## Critical Rules

- Do not rewrite working mesh/networking/crypto without evidence.
- Do not invent cryptography.
- Do not casually change wire/protocol formats.
- Treat LICENSE.md as authoritative.
- Do not claim physical-device verification without physical devices.
- Keep product and architecture decisions in docs/.
- Keep this file concise; detailed knowledge belongs in docs/.

## Shared AI State

Read for major work:

- docs/AI_STATUS.md
- docs/AI_HANDOFF.md
- docs/product/08_DECISIONS.md
- docs/product/10_CLAUDE_WORKFLOW.md
- docs/product/11_AGENT_ORCHESTRATION.md