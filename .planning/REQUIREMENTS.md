# Requirements: Kanban Board Backend — Epic 2 Completion

**Defined:** 2026-07-31
**Core Value:** Ship the remaining Epic 2 deliverables as clean, independently reviewable, interview-defensible work

## v1 Requirements

Requirements for this GSD project's scope. Each maps to roadmap phases.

### Optimistic Locking

- [x] **LOCK-01**: `TaskEntity` and `ColumnEntity` have a `@Version` field, backed by a manual one-off `ALTER TABLE ... ADD COLUMN version bigint NOT NULL DEFAULT 0` against the real Postgres schema (`ddl-auto` is unset there, so Hibernate won't create the column automatically)
- [x] **LOCK-02**: A conflicting concurrent update to the same task/column returns HTTP 409 Conflict, not 423 Locked (fixes the existing but incorrect `GlobalExceptionHandler` mapping of `OptimisticLockingFailureException`)
- [x] **LOCK-03**: A test proves that two concurrent updates to the same task/column produce `ObjectOptimisticLockingFailureException`, asserted at the E2E/HTTP-status-code level (409), not just as a service-level exception type
- [x] **LOCK-04**: `ColumnEntity`'s `@Data`-generated equals/hashCode and `TaskEntity`'s equals/hashCode exclude the new `version` field, so entity identity is not broken across saves

## v2 Requirements

Deferred to a future release. Acknowledged from research but not in this project's roadmap.

### Full Board Endpoint

- **FULL-01**: `GET /boards/{boardId}/full` returns a board with nested columns → tasks → subtasks, ordered at every level, in a small fixed number of queries (no Cartesian-product blowup, no N+1)
- **FULL-02**: The nested `/full` response surfaces the `version` field on task/column entries
- **FULL-03**: A regression test asserts both query count and row count stay bounded at a realistic board size (not just 1 column/1 task)

## Out of Scope

Explicitly excluded from this GSD project. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Epics 1, 3–7 of the backend modernization plan (Kafka activity feed, Flyway/OpenAPI polish, Redis, Testcontainers, Observability, Kubernetes) | Separate epics, not part of this project's scope — deferred to future milestones |
| Re-fixing `OwnershipVerifierService`'s ownership-chain `findById()` calls (Finding 1) | Measured and confirmed non-issue — already resolves in 1 query via EAGER join chain; no code change needed |
| Pagination on `/full` | Conflicts with the endpoint's purpose of avoiding round trips for initial render; anti-feature per research |
| Archive/soft-delete filtering on `/full` | No archive concept exists anywhere in this data model — nothing to filter |
| Server-side auto-merge or retry/backoff on 409 conflicts | Wrong tool for a user-driven drag conflict; industry precedent pushes resolution to the client, not the backend |
| ETag/If-Match header-based concurrency | No functional gain over the existing flat-DTO/body-field conventions |
| Pessimistic locking | Wrong tool for this low-contention, human-paced conflict scenario; epic explicitly calls for optimistic locking |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| LOCK-01 | Phase 1 | Complete |
| LOCK-02 | Phase 1 | Complete |
| LOCK-03 | Phase 1 | Complete |
| LOCK-04 | Phase 1 | Complete |

**Coverage:**

- v1 requirements: 4 total
- Mapped to phases: 4 ✓
- Unmapped: 0

---
*Requirements defined: 2026-07-31*
*Last updated: 2026-07-31 after roadmap creation*
