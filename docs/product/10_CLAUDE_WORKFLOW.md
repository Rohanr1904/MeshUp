# 10 — Claude Workflow

## Objective
Use Claude as a senior engineering collaborator, not as an uncontrolled autonomous rewriter.

## Golden rule
UNDERSTAND → DOCUMENT → PLAN → IMPLEMENT → TEST → REVIEW

## Before implementation
Claude must:
- inspect affected code
- trace callers and dependencies
- understand lifecycle/concurrency
- check existing tests
- check protocol compatibility
- explain risks

## Small changes
Prefer:
- small PR-sized changes
- focused commits
- feature-specific tests
- build verification after major phases

## Never do
- rewrite the whole mesh
- invent cryptography
- change protocol format casually
- remove licensing
- claim tests passed without running them
- claim physical-device behavior was verified without physical devices

## Required outputs for major work
Before coding:
- implementation plan
- affected files/modules
- risks
- test plan

After coding:
- files changed
- tests run
- build result
- remaining risks
- manual verification required

## Repository documents
Maintain:
- PROJECT_ANALYSIS.md
- MESH_ARCHITECTURE.md
- SECURITY_REVIEW.md
- TECHNICAL_DEBT.md
- TARGET_ARCHITECTURE.md
- IMPLEMENTATION_PLAN.md
- DECISIONS.md

## Prompting protocol

### Analysis prompt
"Do not change code yet. Inspect the relevant architecture and report findings."

### Planning prompt
"Create a minimal-risk implementation plan. Do not code yet."

### Implementation prompt
"Implement only phase X. Do not modify unrelated subsystems. Run tests and build."

### Review prompt
"Review the changes for correctness, security, concurrency, battery impact and regressions."

## Stop conditions
Claude should stop and ask before:
- protocol changes
- crypto changes
- major database migrations
- replacing transport implementations
- changing license files
- deleting large amounts of working code
- introducing new infrastructure with significant maintenance cost

## Source of truth
Project Instructions define behavior and priorities.
Project Knowledge documents define product/architecture context.
Repository CLAUDE.md defines coding/repository conventions.
Source code and tests define actual implementation behavior.

When these conflict:
1. Safety/security
2. Actual code/tests
3. Explicit current project decision
4. General product preference
