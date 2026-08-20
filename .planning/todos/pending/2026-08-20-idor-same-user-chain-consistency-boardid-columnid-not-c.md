---
created: 2026-08-20T00:00:00.000Z
title: "IDOR chain consistency: nested path segments (boardId, columnId) are never cross-checked against the leaf resource's actual parent"
area: security
severity: moderate
files:
  - src/main/java/com/vrudenko/kanban_board/service/OwnershipVerifierService.java
  - src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java
  - src/main/java/com/vrudenko/kanban_board/controller/TaskController.java
  - src/main/java/com/vrudenko/kanban_board/controller/SubtaskController.java
  - src/test/java/com/vrudenko/kanban_board/security/AuthorizationGatingTest.java
---

## Problem

Filed from the OWASP API Security Top 10 audit closing
`.planning/todos/completed/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md`
(API1:2023 Broken Object Level Authorization).

`OwnershipVerifierService.verifyOwnershipOfColumn/Task/Subtask` each resolve ownership by walking
**up** from the leaf path id only:

```java
public Pair<UserEntity, ColumnEntity> verifyOwnershipOfColumn(String userId, String columnId) {
    var column = columnRepository.findById(columnId);
    ...
    var pair = verifyOwnershipOfBoard(userId, column.get().getBoard().getId());
    return Pair.of(pair.getFirst(), column.get());
}
```

`ColumnController`/`TaskController`/`SubtaskController` are all board-nested in their
`@RequestMapping` (e.g. `ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS`), but confirmed by
reading all three controller classes directly: **none of their mutating handler methods
(`PUT`/`DELETE`/`PATCH .../columns/{columnId}`, `PUT`/`DELETE .../tasks/{taskId}`,
`PUT`/`DELETE .../subtasks/{subtaskId}`) even declare a `@PathVariable` for the URL's other
ownership-chain segments** (`boardId` on `ColumnController`; `boardId`/`columnId` on
`TaskController`; `boardId`/`columnId`/`taskId` on `SubtaskController`) — only the leaf id
(`columnId`/`taskId`/`subtaskId`) is bound and passed to the service layer. Spring silently
ignores the unbound URL segments; the service never sees them.

**Consequence:** a caller who owns two boards (A and B) can address
`PUT /boards/{A}/columns/{columnId-belonging-to-B}` and have it succeed against board B's column —
the URL names board A, but the request is served entirely against the column's real parent, board
B. This is same-user chain confusion, not cross-user IDOR: ownership is still enforced (the caller
genuinely owns the column being mutated), so no other user's data is exposed or modified. The
actual harm is API contract violation / silent misdirection — a client that believes it is
operating on board A's column list can end up mutating board B's instead, with no error signal.

**Confirmed not exercised by `AuthorizationGatingTest.CrossUserSweep`:** that sweep only varies
the *user* (owning vs. foreign) against one fixed, self-consistent path
(`mockPopulatedBoard`/`mockPopulatedColumn`/`mockPopulatedTask`/`mockSubtasks` all belong to the
same owning user) — it never constructs a request where the *same* owning user's own path
segments disagree with each other (e.g. board A's URL against board B's column id).

**Severity reasoning (not defaulted to `security`):** classified `moderate`, not `critical`,
because the ownership boundary itself is never crossed — the exploit surface is limited to a user
silently misdirecting their own mutations across their own resources, not gaining access to
another user's data. Still worth fixing: it is a real correctness/API-contract bug with a
plausible confused-deputy shape (e.g. a multi-tab client sending a stale board-scoped URL after
switching boards) and a straightforward fix.

## Solution

Two independent fixes are both in scope, and should probably both land together:

1. **Enforce chain consistency in `OwnershipVerifierService`.** Give `verifyOwnershipOfColumn`
   (and cascading `verifyOwnershipOfTask`/`verifyOwnershipOfSubtask`) an overload that accepts the
   expected parent id(s) and asserts the walked-up chain actually matches — e.g.
   `verifyOwnershipOfColumn(userId, boardId, columnId)` throwing `AppEntityNotFoundException` (not
   `AppAccessDeniedException` — the column is not "not owned," the URL is simply wrong) when
   `column.getBoard().getId()` does not equal the supplied `boardId`.
2. **Bind and pass the full path** from every nested controller method (`ColumnController`,
   `TaskController`, `SubtaskController`) instead of only the leaf id, so the new chain-checked
   overload actually has something to compare against.
3. **Add a same-user chain-consistency test** to `AuthorizationGatingTest` (or a new nested class)
   that constructs exactly the scenario above — one owning user, two of their own boards, a request
   whose leaf id belongs to board B addressed via board A's URL — asserting a 404/400, not a silent
   200/2xx.

Do not conflate this with `AuthorizationGatingTest.CrossUserSweep`'s existing cross-user coverage,
which is real and should stay as-is — this is an additive, same-user-scoped test case.
