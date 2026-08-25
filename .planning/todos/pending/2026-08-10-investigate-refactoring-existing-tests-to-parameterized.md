---
created: 2026-08-10T11:05:00.000Z
title: Investigate refactoring existing near-duplicate tests to @ParameterizedTest
area: testing
severity: minor
files:

  - src/test/java/com/vrudenko/kanban_board/service/OwnershipVerifierServiceTest.java
  - src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java
  - src/test/java/com/vrudenko/kanban_board/service/ColumnServiceTest.java

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Several existing test classes repeat the same test shape once per resource type or once per field,
as separate hand-written `@Test` methods, rather than as a single `@ParameterizedTest`. The clearest
case is `OwnershipVerifierServiceTest`: the exact 4-method pattern —
`shouldReturnUserAnd{X}_whenUserOwnsThe{X}`, `shouldThrow_whenUserDoesntOwn{X}`,
`shouldThrow_when{X}DoesntExist`, `shouldThrow_whenUserDoesntExist` — is repeated identically for
Board, Column, Task, and Subtask (16 methods total, differing only by which entity/repository/method
is under test). `TaskServiceTest` (20 `@Test` methods) and `ColumnServiceTest` (16) are worth the same
scan for the same shape, not yet confirmed to the same degree.

This mirrors the exact tension plan 07.1-08 (`AuthorizationGatingTest`) resolved deliberately in the
opposite direction for a *new* class — Approach A in that plan's tradeoffs section explicitly chose
one parameterized route table over N hand-written methods specifically because a missing case is
visible as a short table rather than an absent method among many. The same argument likely applies
retroactively to `OwnershipVerifierServiceTest`'s 16 methods.

Not yet investigated: whether AssertJ's exception-assertion style
(`assertThatThrownBy(...).isInstanceOf(...)`) used throughout these classes composes cleanly with
`@ParameterizedTest` + `@MethodSource`, or whether the four resource types differ enough in fixture
setup (different repository mocks, different entity graphs) that a shared parameterized shape would
need an abstraction ugly enough to not be worth it. That trade-off is exactly what should be
evaluated before committing to a refactor, not assumed.

## Solution

Not scoped yet — this is a discovery/investigation item, not a decided approach. Suggested first
step: read `OwnershipVerifierServiceTest.java` in full, sketch what a `@MethodSource`-driven version
would look like (one row per resource type: repository mock, id, expected user/entity pair or thrown
exception), and judge whether the resulting single parameterized test is more or less readable than
today's 16 explicit methods before deciding whether to proceed. If it's a clear win there, do the
same evaluation for `TaskServiceTest` and `ColumnServiceTest`.
