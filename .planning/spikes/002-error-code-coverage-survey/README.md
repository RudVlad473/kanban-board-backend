---
spike: 002
idea: openapi-error-coverage
name: error-code-coverage-survey
type: standard
validates: "Given every operation's actual thrown-exception surface (service layer + GlobalExceptionHandler's 14 @ExceptionHandler arms + ProblemDetailAuthenticationEntryPoint), when cross-referenced against ProblemDetailOpenApiCustomizer's six generic bucket descriptions, then produce a concrete, prioritized list of operations where the generic bucket is too vague to be useful."
verdict: PARTIAL
related: [001]
tags: [openapi, error-documentation, audit]
---

# Spike 002: Error-Code Coverage Survey

## What This Validates

Whether `ProblemDetailOpenApiCustomizer`'s six generic bucket descriptions (400/401/403/404/409/500)
leave any real, discoverable gap — either an `ErrorCode` never named by any bucket, or a specific
operation whose primary failure mode is materially undocumented by the generic text alone.

## Method

1. Read `GlobalExceptionHandler.java` in full (all 14 `@ExceptionHandler` arms) and
   `ProblemDetailAuthenticationEntryPoint.java`.
2. Read the `ErrorCode` enum (12 members) and cross-referenced each against
   `ProblemDetailOpenApiCustomizer.ERROR_RESPONSES`'s six description strings by keyword.
3. Catalogued all 24 non-nonprod operations across `BoardController`, `ColumnController`,
   `TaskController`, `SubtaskController`, `TaskMoveController`, `UserController`,
   `ActivityController`, and `AuthenticationController` (`ResetController` excluded — it's
   `nonprod`-profiled and absent from the production document, matching
   `ProblemDetailOpenApiCustomizerTest`'s own count).
4. For each operation with a write path (POST/PUT/PATCH/DELETE), traced its service-layer method
   to find every exception it can actually throw, then judged whether the generic bucket text
   materially helps or actively obscures the real cause.

## Results

### Finding 0 — No ErrorCode is undocumented

All 12 `ErrorCode` enum members are named (by keyword) in at least one of the six generic bucket
descriptions:

| Status | ErrorCode(s) named |
|---|---|
| 400 | `VALIDATION_FAILED`, `CONSTRAINT_VIOLATION`, `ILLEGAL_ARGUMENT`, `MALFORMED_REQUEST_BODY` |
| 401 | `UNAUTHENTICATED`, `BAD_CREDENTIALS` |
| 403 | `ACCESS_DENIED` |
| 404 | `ENTITY_NOT_FOUND` |
| 409 | `OPTIMISTIC_LOCK_CONFLICT`, `DUPLICATE_RESOURCE`, `DATA_INTEGRITY_VIOLATION` |
| 500 | `INTERNAL_ERROR` |

12 codes named, 12 codes in the enum. This rules out the "missing code" flavor of gap entirely —
every possible `code` value a caller could see is at least mentioned somewhere on every operation
that can produce it. **The real gaps, below, are all about specificity, not coverage.**

### Finding 1 — `POST /api/signup`'s 409 is the direct sibling of spike 001

`UserService.save()` (called from `AuthenticationController.signup`):

```java
if (userRepository.existsByEmail(userDTO.getEmail())) {
    throw AppDuplicateResourceException.withMessage(
            "Email '" + userDTO.getEmail() + "' is already taken");
}
```

Same shape as board creation exactly: a checked, expected `DUPLICATE_RESOURCE` 409, backstopped by
the unique constraint `uk_users_email` (V1) for the concurrent-signup race, mirroring
`BoardService.updateById`'s pattern (per that method's own comment: "the same pattern
`BoardService.updateById` already relies on for board-name uniqueness"). This is the single
strongest remaining override candidate — same mechanism spike 001 proved, applied to
`AuthenticationController.signup` instead of `BoardController.save`.

### Finding 2 — `PUT /api/boards/{boardId}`'s 409 bucket hides that TWO distinct causes apply

`BoardService.updateById` checks, in order: optimistic-lock version mismatch, *then*
duplicate-name (skipped only for a no-op rename). Both throw distinct exceptions
(`OptimisticLockingFailureException` → `OPTIMISTIC_LOCK_CONFLICT`,
`AppDuplicateResourceException` → `DUPLICATE_RESOURCE`) that both land on this one operation's
409. The generic bucket text lists all three possible 409 causes across the *whole API*, so a
caller reading only this operation's doc cannot tell which of the three actually apply here (the
third, `DATA_INTEGRITY_VIOLATION`, can also still occur here as the unchecked-race backstop). An
override for this operation is a genuinely different shape than spike 001's — it needs to show
*two* named causes, not one, which is worth flagging as a design question for the follow-up task
rather than assuming spike 001's single-example pattern copies over unchanged.

### Finding 3 — `PATCH /api/tasks/{taskId}/move`'s 400 hides a non-obvious business rule

`TaskService.moveToColumn`:

```java
if (!targetColumn.getBoard().getId().equals(sourceBoardId)) {
    throw new IllegalArgumentException(
            "Cannot move a task to a column on a different board.");
}
```

The generic 400 bucket text ("failed field validation... or a violated path/param constraint")
reads as if 400 here means malformed input. It doesn't — the request is well-formed; it violates a
domain invariant (tasks cannot cross boards) that nothing in the schema or the generic description
hints at. This is arguably a stronger candidate for a specific override than either duplicate-name
case, precisely because the generic bucket's framing actively points a reader in the wrong
direction rather than merely being non-specific.

### Finding 4 — `POST /api/signin`'s indistinguishable 401 is deliberate, NOT a gap

Unknown email, wrong password, and a failed post-authentication check all collapse into the same
`BadCredentialsException` / `INVALID_CREDENTIALS_MESSAGE` / `BAD_CREDENTIALS` response — by
explicit design (that controller's own comments cite finding F1, a 2026-08-10 security scan: a
distinguishable response would let response *latency* enumerate registered accounts even after the
response *body* was already made identical). Documenting this operation's 401 more specifically
would be actively wrong — it would either leak which failure mode occurred (defeating the
mitigation) or just restate what's already there. **Recorded here so the follow-up task does not
"fix" this into a regression.**

### Finding 5 — `UserController`'s two theme endpoints document an impossible 409

`UserEntity` deliberately carries no `@Version` field (last-write-wins theme preference, a
documented trade-off — see `UpdateThemeRequestDTO`'s Javadoc). Neither `GET /api/users/me/theme`
nor `PUT /api/users/me/theme` can structurally produce a 409 of any kind, yet the generic
customizer stamps one on both anyway. This is the flip side of `ProblemDetailOpenApiCustomizerTest`'s own `shouldPreserveGeneratedSuccessResponse_whenCustomizerHasRun`
guarantee — "the customizer inserts, it does not replace" applies uniformly, including to
operations that can never hit that status. **Not a gap to fix** under this API's own D-08
philosophy (uniform coverage over precision) — recorded as an observation, not a recommendation,
since suppressing it per-operation would reintroduce exactly the "must remember every time" failure
mode D-08 exists to eliminate.

### Non-findings — operations checked and found adequately covered by the generic bucket

`ColumnService.updateById`, `ColumnService.reorder`, `TaskService.updateById`, and
`SubtaskService.updateById` each have exactly **one** possible 409 cause
(`OptimisticLockingFailureException` only — none of these three resources carry a uniqueness
constraint). A single-cause 409 is exactly what the generic bucket's "optimistic-lock version
mismatch" clause already communicates unambiguously; an override here would add ceremony without
adding information.

## Investigation Trail

Read-only throughout — no source files were modified for this spike (per its directive). The
"observable, not just reading" requirement was satisfied by: (a) grepping every
`existsBy*`/`existsByUserIdAndName`/`existsByEmail` call site across `repository/` and `service/`
to get an exhaustive, not assumed, list of every uniqueness-driven 409 in the codebase (exactly
two: board name, user email) rather than trusting a manual read to have caught them all; (b)
reusing spike 001's live-document harness pattern conceptually — the actual JSON-backed
confirmation that findings 1-3 are *currently* undocumented (not already covered by some override
I missed) is still pending the same gradle-collision recovery blocking spike 001's final capture,
recorded honestly in Results below rather than assumed.

## Results

**Verdict: PARTIAL.** The survey itself is complete and evidence-based (every finding above traces
to an actual code read, not a guess) — five real findings (one non-gap correctly identified as
such, one "leave alone" observation, three genuine override candidates ranked by how actively
misleading vs. merely non-specific the generic bucket is for that operation). What's still
pending: a live JSON pull confirming none of findings 1-3's operations already have a
better-than-generic 409/400 documented by some other mechanism I didn't find by reading — blocked
on the same gradle-daemon collision affecting spike 001, to be closed out once that clears.

**Priority order for a follow-up quick task**, most to least valuable:
1. Finding 3 (`PATCH /api/tasks/{taskId}/move` 400) — actively misleading, not just vague.
2. Finding 1 (`POST /api/signup` 409) — direct copy of spike 001's proven pattern, cheapest to add.
3. Finding 2 (`PUT /api/boards/{boardId}` 409) — highest value but needs a two-cause example shape,
   worth designing deliberately rather than copy-pasting spike 001's single-cause example.

Findings 4 and 5 are explicitly **not** recommended for action — recorded so they don't get
"fixed" into a regression or busywork by someone re-deriving them from scratch later.
