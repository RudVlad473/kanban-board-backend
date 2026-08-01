# Epic 2 — Fix the real N+1 / query-waste chain + add optimistic locking (JPA/Hibernate depth)

[← back to plan index](README.md) · Effort: 3–5 days · Priority: **Highest**

**Why this matters:** N+1 detection/fixing is the one JPA topic that shows up at mid-level almost
universally in Poland. You don't need to manufacture an example — this codebase has two distinct,
real problems in this area, confirmed by reading the code (not assumed from the earlier report):

1. **Chatty/redundant queries in `OwnershipVerifierService`** — not classic N+1, but real waste.
2. **Genuine N+1 in `TaskService.deleteAllByColumnId`** — this one does scale with list size.

They need different fixes, so they're tracked as separate tasks below rather than one blob.

### Finding 1: `OwnershipVerifierService` is chatty, not N+1

All the parent-side associations (`SubtaskEntity.task`, `TaskEntity.column`,
`ColumnEntity.board`, `BoardEntity.user`) are `@ManyToOne` with no explicit `fetch`, which
defaults to `FetchType.EAGER`, and none of the repositories override it with
`@Fetch(FetchMode.SELECT)`. That means a single `subtaskRepository.findById(id)` already pulls
subtask→task→column→board→user via SQL `LEFT JOIN`s in **one** query — Hibernate does this
automatically for chained EAGER-to-one associations.

`OwnershipVerifierService.verifyOwnershipOfSubtask` doesn't take advantage of that: it recurses
and calls a **fresh `repository.findById()` at every level** (subtask, then task, then column,
then board), discarding the already-loaded chain from the previous query and re-triggering the
eager-join cascade each time. Net effect: ~5 round trips per single ownership check, most of them
redundant re-fetches of data Hibernate already had.

This is real waste, but it's **bounded per request** (constant, not proportional to data volume)
— so it's not the textbook N+1 pattern. The security necessity (walk the chain to prove
ownership) is genuine; the *implementation* (fresh query per level instead of one `JOIN
FETCH`/`@EntityGraph`, or reusing the already-loaded associations) is what's wasteful.

### Finding 2: `TaskService.deleteAllByColumnId` is genuine N+1

```java
// TaskService.java
public void deleteAllByColumnId(String userId, String columnId) {
    var pair = ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId);
    // TODO: delete all subtasks in batch using list of task ids
    for (var task : findAllByColumnId(userId, pair.getSecond().getId())) {
        subtaskService.deleteAllByTaskId(userId, task.getId());  // re-verifies task→column→board→user
        deleteById(userId, task.getId());                        // re-verifies task→column→board→user AGAIN
    }
}
```

Column/board/user ownership is already established once, before the loop. But for **every task**
in the column, this re-runs the entire chain verification twice. Query count scales linearly with
the number of tasks (`~2N × chain depth`) for information that's already known — that's the real
N+1, and it's exactly what the existing `// TODO: delete all subtasks in batch using list of task
ids` comment is flagging.

## Tasks (work in this order, run `./gradlew test` after each)

1. **Diagnostic test first, in a way you can show:** set
   `spring.jpa.properties.hibernate.generate_statistics=true` and
   `logging.level.org.hibernate.stat=debug` in `application-test.properties`, then write tests that
   assert query counts via `SessionFactory.getStatistics().getQueryExecutionCount()` — one hitting
   a single-subtask endpoint (documents Finding 1: ~5 queries for one ownership check) and one
   hitting `deleteAllByColumnId` with multiple tasks in the column (documents Finding 2: query
   count scaling with task count). Commit both in a "red"/documenting state first — this
   before/after pair is the single best demonstration artifact from this whole plan.
2. **Fix Finding 1 — collapse the ownership chain into one query per entry point.** Two acceptable
   approaches — implement the first, mention you considered the second:
   1. Add a repository method like
      `SubtaskRepository.findByIdWithOwnershipChain(String subtaskId)` using a JPQL `JOIN FETCH`
      across `subtask.task.column.board.user` in one query, returning the subtask with the whole
      chain already loaded, then do the ownership `.equals()` check in memory.
   2. Or add `@EntityGraph(attributePaths = {"task.column.board.user"})` on the repository method.

   Apply the same pattern at each entry point (`verifyOwnershipOfBoard/Column/Task/Subtask`) so
   none of them re-`findById()` a level that a prior query already loaded. This also resolves the
   two `// TODO: optimize verification logic, by passing already fetched entities` comments in
   `OwnershipVerifierService` and the related TODO in `ColumnService`.
3. **Fix Finding 2 — stop re-verifying known ownership in the delete loop.** Verify column
   ownership once (already happens), then pass the already-verified column/task entities down
   instead of re-checking per task, and batch the subtask deletion by task-id list instead of
   looping one `deleteAllByTaskId` call per task. Resolves the
   `// TODO: delete all subtasks in batch using list of task ids` comment.
4. **Add a real "get full board" endpoint** (`GET /boards/{boardId}/full` — currently there's no
   single endpoint that returns a board with its columns, tasks, and subtasks nested; a client has
   to make N+1 round trips itself to render the initial board view). Implement it with a single
   `JOIN FETCH` query across `board.columns.tasks.subtasks` (watch out for Cartesian-product
   blowup from fetching two `List` collections in one JOIN FETCH — this is itself worth
   understanding and being able to explain: use `@BatchSize` on the collections instead of a triple
   JOIN FETCH, or fetch in two queries and stitch in Java, and be ready to explain why you chose
   that over a naive triple join).
5. **Add optimistic locking:** add `@Version private Long version;` to `TaskEntity` and
   `ColumnEntity`. Justify it with a real scenario: two clients drag-and-drop the same task
   concurrently (reorder within a column, or move across columns) — without a version check, one
   update silently overwrites the other. Add a test that loads the same task in two "sessions",
   updates both, and asserts the second save throws `ObjectOptimisticLockingFailureException`.
   Map that exception to a 409 in `GlobalExceptionHandler`.

## Explanation to have afterward

Be able to explain the difference between "chatty but bounded" (Finding 1) and "genuine N+1"
(Finding 2) — that distinction itself is the key takeaway. Also be able to draw the before/after
query count, explain `JOIN FETCH` vs `@EntityGraph` vs `@BatchSize` and when you'd reach for each,
and explain optimistic vs pessimistic locking using your drag-and-drop scenario as the concrete
example.
