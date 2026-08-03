---
phase: 03-activity-log-consumer-reliability-read-api
fixed_at: 2026-08-02T17:22:00Z
review_path: .planning/phases/03-activity-log-consumer-reliability-read-api/03-REVIEW.md
iteration: 1
findings_in_scope: 4
fixed: 4
skipped: 0
status: all_fixed
---

# Phase 3: Code Review Fix Report

**Fixed at:** 2026-08-02T17:22:00Z
**Source review:** .planning/phases/03-activity-log-consumer-reliability-read-api/03-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 4 (1 critical, 3 warning; Info findings out of scope for this pass)
- Fixed: 4
- Skipped: 0

## Fixed Issues

### CR-01: `ActivityLogRecorder.record()` swallows any constraint violation, not just the intended duplicate-eventId race

**Files modified:** `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogRecorder.java`
**Commit:** `fe2fcf2`
**Applied fix:** Adopted the reviewer's "more robust" alternative rather than the constraint-name-string-matching option (which is fragile across the H2 test schema vs. the hand-written Postgres DDL, whose constraint names are not guaranteed identical): the `catch (DataIntegrityViolationException e)` block now re-checks `activityLogRepository.existsByEventId(entry.getEventId())` before deciding to absorb the exception. If the row is present, the exists-check race happened as documented and the exception is swallowed as before. If it is still absent, the violation was caused by something else (e.g. a `NOT NULL` violation from a semantically-null event field) and is rethrown, so it escapes to `DefaultErrorHandler` and is retried/dead-lettered like any genuine failure instead of being silently dropped. Updated the class Javadoc to describe this behavior precisely.

### WR-01: Dead-letter `KafkaTemplate`'s backing `ProducerFactory` is constructed manually and never becomes a managed bean

**Files modified:** `src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java`
**Commits:** `e4fb50b` (initial attempt, reverted), `c3ecbe9` (revert), `2b46014` (corrected fix)
**Applied fix:** The reviewer's suggested fix (splitting the inline `DefaultKafkaProducerFactory` into its own `@Bean` of type `ProducerFactory<String, Object>`) was applied first, verified with `./gradlew compileJava` (passed), and committed as `e4fb50b`. A subsequent full `./gradlew test` run caught a regression the compile step could not: `KafkaAutoConfiguration.kafkaProducerFactory()` is guarded by a bare-type `@ConditionalOnMissingBean(ProducerFactory.class)`, so the new bean — regardless of its generic parameterisation — silently suppressed the real autoconfigured producer factory (the one carrying the Testcontainers `KafkaConnectionDetails` override), breaking application-context startup for 160 of 168 tests. This is the exact landmine `KafkaConsumerConfig`'s own Javadoc already documents having hit and fixed for `KafkaTemplate`, reproduced here for `ProducerFactory`.

Per the rollback/adapt guidance, the broken commit was reverted (`c3ecbe9`) and a corrected fix applied (`2b46014`): `KafkaConsumerConfig` now implements `DisposableBean` and keeps the dead-letter `DefaultKafkaProducerFactory` as a private field on the `@Configuration` instance itself (a plain, non-`ProducerFactory`-typed bean), closing it from `KafkaConsumerConfig.destroy()`. This achieves the review's goal — the extra producer's lifecycle is now managed and closed on context shutdown — without ever registering a discoverable `ProducerFactory` bean that the autoconfiguration's conditional would see. Verified with a full `./gradlew test` run: `BUILD SUCCESSFUL`, 0 failures.

### WR-02: `ActivityController` is missing class-level `@Validated`, so `@NotBlank` on `boardId` is never enforced

**Files modified:** `src/main/java/com/vrudenko/kanban_board/controller/ActivityController.java`
**Commit:** `663c25e`
**Applied fix:** Added `@Validated` at the class level (matching `BoardController`'s existing pattern and annotation ordering). Confirmed `GlobalExceptionHandler` already has an `@ExceptionHandler(HandlerMethodValidationException.class)` handler that maps the resulting validation failure to a 400 response, so no additional exception-handling changes were needed.

### WR-03: Returning a raw `Page<T>` from the controller relies on an unguaranteed serialization format

**Files modified:** `src/main/java/com/vrudenko/kanban_board/controller/ActivityController.java`
**Commit:** `4cd13e6`
**Applied fix:** Of the reviewer's two offered options, wrapping the response in `org.springframework.data.web.PagedModel` was not applied: it changes the wire shape (`content`/`totalElements`/`totalPages`/`pageable.pageSize` move under a nested `page` key), which would break every assertion in the existing, passing `ActivityReadE2ETest` suite and any real consumer of this endpoint — too large and risky a change to make silently inside an automated fix pass. Applied the reviewer's other sanctioned option instead: added an in-code comment on `ActivityController.findAllByBoardId` documenting the raw-`Page<T>` shape as a deliberate, tracked convention for future paginated endpoints to copy, including the `PagedModel` tradeoff that was considered and explicitly not taken yet. No behavior change; comment-only.

## Skipped Issues

None — all four in-scope findings (1 critical, 3 warning) were fixed. Info findings (IN-01, IN-02) were out of scope for this pass per `fix_scope: critical_warning`.

## Verification

- `./gradlew compileJava` — passed after every individual fix.
- `./gradlew spotlessApply spotlessCheck` — `BUILD SUCCESSFUL`, no formatting violations (spotlessApply also runs automatically via the repo's pre-commit hook on every commit made during this pass).
- `./gradlew test` (full suite, including Testcontainers-backed Kafka/Postgres E2E tests) — `BUILD SUCCESSFUL` in 3m 53s, 0 failures. This run caught the WR-01 regression described above; the corrected fix was re-verified with a second full run before this report was written.
- All work was performed in an isolated git worktree (branch `gsd-reviewfix/03-9917`, forked from `master`) per the workflow's `use_worktrees` default (not overridden in `.planning/config.json`); the cleanup tail (fast-forward `master`, remove worktree, delete temp branch, remove recovery sentinel) runs in the main checkout after this report is written there directly (not inside the worktree, since the worktree is force-removed during cleanup).

---

_Fixed: 2026-08-02T17:22:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
