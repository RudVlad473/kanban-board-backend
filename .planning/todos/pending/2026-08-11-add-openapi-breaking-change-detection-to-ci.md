---
created: 2026-08-11T00:00:00.000Z
title: Add OpenAPI breaking-change detection to CI (schema diffing against a checked-in baseline)
area: backend
severity: minor
files:

  - .github/workflows/
  - build.gradle
  - src/test/java/com/vrudenko/kanban_board/OpenApiDocsTest.java

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Nothing today prevents a REST response/request shape from changing silently — a renamed or
retyped DTO field ships the moment its PR merges, with no automated signal that a consumer
(internal or partner) may depend on the old shape. This is a real-world failure mode (raised in
conversation: a colleague's day-job incident where a partner-facing response shape changed
mid-migration and broke production for that partner), and this repo has no equivalent guard for
its REST surface today — only for the Kafka `kanban.activity` pipeline, which already has a
Confluent-style Schema Registry with an explicit BACKWARD/FULL compatibility mode
(`docs/plans/backend-modernization/04-*`).

## Solution

Not scoped in detail here — needs its own investigation/design pass before implementation. At
minimum should cover:

1. **Spec generation:** capture a fresh OpenAPI spec artifact on every build. Two candidate
   approaches: the `springdoc-openapi-gradle-plugin` (generates the spec without a running
   server), or extending the existing `OpenApiDocsTest` (which already hits the live `/api/docs`
   endpoint and asserts a 200) to dump the response body to a file.

2. **Baseline + diff:** check a baseline spec into git; diff the fresh spec against it in CI using
   a breaking-change-aware tool (`oasdiff` is the common choice — Go binary, actively maintained,
   has a ready-made GitHub Action).

3. **Gate policy:** decide hard-fail-on-breaking-change vs. warn-only-first-then-tighten, following
   this repo's established measure-first-then-pick-a-rung pattern (see the ErrorProne rollout,
   `.planning/quick/260802-qr8-*` and `260803-v23-*`, for precedent on how that decision was made
   here previously).

4. **Deliberate-change workflow:** when a breaking change is intentional, the PR that makes it must
   also update the checked-in baseline — making the "deliberate choice" show up as a visible diff
   in review, rather than a silent side effect of an unrelated change.

Known limitation to carry into the design: this only catches *structural* shape drift (field
removed/retyped/renamed) — a field that changes meaning without changing name or type will not be
caught. Not a replacement for consumer-driven contract testing (e.g. Pact) if a specific downstream
relationship is important enough to justify that heavier investment; this is the cheaper, broader
first line of defense.
