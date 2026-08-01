# Project Research Summary

**Project:** kanban-board-backend — Epic 2 completion (backend modernization plan)
**Domain:** JPA/Hibernate depth work on an existing Spring Boot 3.5.0 REST API — nested-aggregate fetching (GET /boards/{boardId}/full) + retrofitted optimistic locking (@Version on TaskEntity/ColumnEntity)
**Researched:** 2026-07-31
**Confidence:** HIGH

## Executive Summary

This is not a greenfield product-discovery project — it is a narrow, well-understood slice of JPA/Hibernate depth work being retrofitted onto an existing, already-functioning Spring Boot 3.5.0 kanban board API. Both target problems (bag-fetch Cartesian products across nested List collections, and optimistic locking plus HTTP conflict mapping) are canonical, extensively documented Hibernate 6.x patterns with clear industry consensus. No new dependencies are needed; everything ships with the existing spring-boot-starter-data-jpa. The two deliverables are architecturally independent, touching disjoint seams of the same five-layer stack (new DTOs/mapper/repo methods for the /full endpoint; a new entity field plus exception-handler fix plus one-off DDL for locking), and can be built and reviewed as separate, small phases.

The recommended approach is: (1) for /full, fetch the aggregate in a small, fixed number of queries — one JOIN FETCH for Board+Columns, then flat IN-clause queries for Tasks and Subtasks, stitched in Java — rather than a naive triple JOIN FETCH (which throws MultipleBagFetchException or silently produces a Cartesian-product row explosion), or the weaker-guarantee @BatchSize alternative; (2) for optimistic locking, add @Version private Long version directly to TaskEntity and ColumnEntity (not BaseEntity), and fix the codebase's existing GlobalExceptionHandler, which already catches OptimisticLockingFailureException but incorrectly maps it to 423 Locked instead of the epic-required 409 Conflict.

The key risks are not "will this work" (it will — the patterns are standard) but "will it quietly regress adjacent code the team already fixed." Specifically: the codebase's own recently-completed N+1 fix introduced bulk JPQL deletes (deleteAllByIdInBatch, @Modifying bulk delete) that will silently bypass @Version checks entirely once the field is added — this must be documented as an accepted tradeoff, not treated as a bug to fix. A second concrete landmine is that the real Postgres profile has ddl-auto unset (defaults to none), so adding @Version in code will NOT create the column in a persistent database — a one-off manual ALTER TABLE is required. A third is Lombok's @Data/@EqualsAndHashCode on ColumnEntity silently including the new version field in equals/hashCode, breaking entity identity semantics across saves.

## Key Findings

### Recommended Stack

No new dependencies — this is purely an annotation/configuration/query-shape change on top of the existing org.springframework.boot:spring-boot-starter-data-jpa (which transitively provides Hibernate ORM 6.x and Spring Data JPA, matching Spring Boot 3.5.0's BOM). Confirm the resolved Hibernate patch version with a gradlew dependencies command before phase planning, but do not hand-pin it.

Core technologies:
- Hibernate ORM 6.x (already on classpath) — provides @BatchSize, JOIN FETCH, and @Version semantics natively; no exotic tech needed.
- Spring Data JPA (already on classpath) — its PersistenceExceptionTranslationPostProcessor automatically wraps JPA's OptimisticLockException as Spring's ObjectOptimisticLockingFailureException, requiring zero extra config.
- @Version (jakarta.persistence) — codebase already uses the Jakarta namespace throughout; no migration needed, just add the field.

Explicitly not adopted: MULTISET-based fetching (Hibernate 6.5+ native, or Blaze Persistence/jOOQ) is the "next generation" fix for the same Cartesian-product problem in one query, but requires a version floor or new dependency — out of scope for this epic; note only as background context.

### Expected Features

Must have (table stakes):
- Single nested GET /boards/{boardId}/full returning board -> columns -> tasks -> subtasks in one response, ordered at every level.
- version field surfaced on Task/Column response DTOs so clients can reason about optimistic-lock state.
- 409 Conflict (not 500, not silent overwrite) on concurrent conflicting update.
- Ownership verification still applied to /full (reuse existing OwnershipVerifierService, verified once at the board level — not per-level, which would reintroduce the N+1 the prior epic finding already fixed).

Should have / correctly scoped as backend-only:
- A well-formed, promptly-returned 409 body with enough info (resource id/reason) for the client to decide to refetch. Client-side refetch-and-reapply (the OCAPI-documented industry pattern) is explicitly out of this backend's scope.

Defer / explicitly anti-features (do not build):
- Pagination on /full — conflicts with the endpoint's stated purpose (avoiding round trips for initial render); revisit only if board sizes ever grow far beyond personal-project scale.
- Archive/soft-delete filtering — no archive concept exists anywhere in this data model; nothing to filter.
- Server-side auto-merge or retry/backoff on 409 — wrong tool for a user-driven drag conflict; industry precedent (Salesforce OCAPI) resolves at the HTTP layer and pushes resolution to the client.
- ETag/If-Match header-based concurrency, field-level partial-conflict detection — no functional gain over the codebase's existing flat-DTO/body-field conventions.
- Pessimistic locking — wrong tool for this low-contention, human-paced conflict scenario; the epic explicitly asks for optimistic locking.

### Architecture Approach

Both deliverables are additive slices through the existing five-layer architecture (Controller -> Service -> Mapper -> Repository -> Entity), not a new layer or pattern. The /full endpoint gets a new nested DTO subpackage (dto/board_dto/full/) and a dedicated BoardFullMapper (isolated from the existing flat mappers, reusing SubtaskResponseDTO as the leaf), fed by three new flat repository queries (findByIdWithColumns via one JOIN FETCH, findAllByColumnIdIn, findAllByTaskIdIn) stitched together in a new BoardService.getFullBoard() method — 4 total queries (1 ownership + 3 fetch levels), constant regardless of board size. Optimistic locking adds @Version directly to TaskEntity/ColumnEntity and fixes the existing GlobalExceptionHandler's status-code mapping.

Major components:
1. BoardController/BoardService (extended) — new getFullBoard() route/orchestration method; ownership verified once at the top, not per nested level.
2. BoardFullMapper + dto/board_dto/full/* (new) — nested DTO tree, physically separated from the existing flat-DTO convention to avoid ambiguity and lazy-init risk.
3. ColumnRepository/TaskRepository/SubtaskRepository (extended) — new flat, single-bag-level fetch methods; never JOIN FETCH across two List associations in one query.
4. TaskEntity/ColumnEntity (extended) — new @Version private Long version field, added directly (not via BaseEntity) to keep blast radius scoped to the actual concurrent-edit scenario.
5. GlobalExceptionHandler (extended) — status-code fix from 423 to 409 for OptimisticLockingFailureException.

### Critical Pitfalls

1. Naive triple JOIN FETCH across board.columns.tasks.subtasks — throws MultipleBagFetchException or silently multiplies rows combinatorially (a modest 5x8x3 board already produces thousands of joined SQL rows). Avoid by fetching one collection level via JOIN FETCH, then flat IN-clause queries for the rest, stitched in Java (or @BatchSize as a documented alternative with weaker query-count guarantees).
2. N+1 despite JOIN FETCH being present — if the JOIN FETCH only covers the first level and the DTO mapper later touches an un-fetched association (e.g., column.getTask()), it silently reverts to lazy-load-per-row. Guard with a query-count test scoped to the whole service/controller path, not just the repository call.
3. Bulk JPQL/deleteAllByIdInBatch deletes silently bypass @Version entirely — this codebase's own recently-completed N+1 fix (TaskService.deleteAllByColumn, SubtaskRepository.deleteAllByTaskIdIn) already uses bulk delete statements that never load entities, so there is no version to check. This must be explicitly documented as an accepted delete-wins tradeoff, not silently discovered later by a reviewer.
4. ddl-auto is unset (defaults to none) in the real Postgres profile — adding @Version in code will NOT create the column against a persistent database; requires one manual, one-off ALTER TABLE ADD COLUMN version bigint NOT NULL DEFAULT 0 per table (not a Flyway migration).
5. Existing GlobalExceptionHandler already has a handler for OptimisticLockingFailureException mapped to 423 Locked — this is a live, ground-truth-confirmed bug relative to the epic's 409 requirement; fix the existing handler's status code (Option A) rather than adding a duplicate/overlapping handler.

## Implications for Roadmap

Based on research, suggested phase structure:

### Phase 1: Optimistic locking (@Version + 409 mapping)
Rationale: Smaller, more self-contained change (one field x 2 entities + one exception-handler fix + one manual DDL step + one test) — architecture research explicitly recommends this order to produce a quick, clean PR and to surface the ddl-auto=none schema question early, before it can be conflated with the larger /full endpoint work. No hard dependency either way, but this is the lower-risk starting point.
Delivers: @Version on TaskEntity/ColumnEntity; GlobalExceptionHandler fixed to return 409 (not 423); one-off manual ALTER TABLE DDL against the real Postgres schema; @EqualsAndHashCode.Exclude audit on both entities; concurrent-update test proving ObjectOptimisticLockingFailureException at the E2E/status-code level.
Addresses: Version field in response DTOs, 409 Conflict on concurrent update (FEATURES.md table stakes).
Avoids: Pitfall 8 (423 vs 409), Pitfall 7 (Lombok equals/hashCode), Pitfall 9 (detached-entity version mismatch regression risk), Pitfall 5/6 (bulk-delete/derived-delete version-bypass — must be explicitly documented as accepted scope boundary in this phase, not silently discovered later).

### Phase 2: Full-board nested read endpoint (GET /boards/{boardId}/full)
Rationale: Larger surface area (3 new DTOs, 1 new mapper, 3 new repository methods, stitching logic, its own dedicated test) — sequenced second per architecture research's build-order preference, though the two phases have no hard dependency and touch disjoint code.
Delivers: New dto/board_dto/full/ subpackage, BoardFullMapper, three new flat repository fetch methods, BoardService.getFullBoard() stitching logic, query-count AND row-count regression tests at realistic board size.
Uses: @BatchSize or one-query-per-level-and-stitch (STACK.md/ARCHITECTURE.md recommend the latter for deterministic query counts) — decide explicitly in phase planning, do not discover mid-implementation.
Implements: Pattern 1 (scoped nested DTO tree) and Pattern 3 (batch-fetch-and-stitch) from ARCHITECTURE.md.

### Phase Ordering Rationale

- Optimistic locking first because it is smaller and self-contained, and because it forces an explicit decision about the ddl-auto=none real-schema gap before that question can get mixed up with the larger /full endpoint's own complexity.
- The /full endpoint second because its fetch-strategy decision (JOIN FETCH + stitch vs. @BatchSize) should be made deliberately during phase planning, not discovered mid-implementation, and because it has more moving parts (3 new DTOs, 1 new mapper, 3 new repo methods) that benefit from a clean, focused phase.
- Both phases must explicitly test at the "whole path" altitude (controller/service, not just repository) — this is the single most repeated verification note across ARCHITECTURE.md and PITFALLS.md, since both major pitfalls (N+1-despite-JOIN-FETCH, and 423-not-409) are invisible at a narrower test scope.

### Research Flags

Phases likely needing deeper research during planning:
- Neither phase needs additional research-phase treatment — both are already grounded in HIGH-confidence, codebase-verified findings across all four research files, with concrete code examples, exact file/line-level ground truth (GlobalExceptionHandler.java, entity files, application properties files), and cross-checked external sources (Vlad Mihalcea, Baeldung, Thorben Janssen, Salesforce OCAPI docs).

Phases with standard patterns (skip research-phase):
- Phase 1 (optimistic locking): Extensively documented, canonical JPA/Hibernate pattern; existing bug in codebase already identified with exact fix.
- Phase 2 (full-board endpoint): Well-trodden Hibernate bag-fetch problem with clear consensus solution; exact query/entity shapes already specified in ARCHITECTURE.md.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | No new dependencies; both patterns are canonical Hibernate 6.x behavior, cross-checked across Hibernate-core-alumnus and independent sources. Exact Hibernate patch version not independently pinned — verify with one gradlew dependencies command before phase planning (MEDIUM sub-note only). |
| Features | HIGH | Endpoint scoping is a well-precedented REST pattern (Trello/Jira); this codebase's lack of archive/soft-delete concepts cleanly eliminates a whole category of scope questions those competitors have to solve. |
| Architecture | HIGH | Grounded directly in this codebase's actual entities, DTOs, mappers, repositories, services, and both application properties files — read directly, not inferred. |
| Pitfalls | HIGH (codebase-verified) / MEDIUM (general ecosystem gotchas not yet reproduced locally) | The most consequential pitfalls (423-not-409 handler, bulk-delete version bypass, ddl-auto=none) are confirmed against actual source files, not general knowledge. |

Overall confidence: HIGH

### Gaps to Address

- Exact Hibernate patch version: not independently verified against Maven Central in this research pass — run a gradlew dependencies command before finalizing phase plans if precision matters for documentation.
- Real Postgres schema provenance: unclear whether the dev/deploy Postgres instance is long-lived or recreated from scratch (Docker volume wipe) — this determines whether the one-off manual ALTER TABLE DDL is strictly necessary or moot. Confirm during Phase 1 planning by checking for Docker Compose/init scripts.
- Fetch-strategy final choice for /full (@BatchSize vs. one-query-per-level-and-stitch): both are valid per research; ARCHITECTURE.md leans toward the stitch approach for deterministic query counts, but this should be an explicit decision recorded in Phase 2's plan, not assumed.
- ColumnRepository.deleteAllByBoardId behavior post-@Version: this derived (non-bulk) delete method will start honoring version checks once @Version is added to ColumnEntity, creating an asymmetry with the sibling bulk task-delete path that does not. Needs an explicit test/decision during Phase 1, not silent discovery later.

## Sources

### Primary (HIGH confidence)
- Direct codebase reads: GlobalExceptionHandler.java, TaskEntity.java, ColumnEntity.java, BoardEntity.java, SubtaskEntity.java, BaseEntity.java, application.properties, application-test.properties, repository/service classes — ground truth for current behavior and the existing 423 bug.
- .planning/PROJECT.md, docs/plans/backend-modernization/02-n-plus-one-optimistic-locking.md, docs/plans/backend-modernization/STATUS.md — epic scope and prior-fix context.
- vladmihalcea.com hibernate-multiplebagfetchexception and related Vlad Mihalcea posts — Hibernate core team alumnus, canonical source.
- Salesforce B2C Commerce (OCAPI) Optimistic Locking docs — clearest published precedent for 409 + client refetch-and-reapply.
- Trello and Jira Software Cloud REST API docs — official, for full-board endpoint scoping comparison.

### Secondary (MEDIUM confidence)
- Baeldung — MultipleBagFetchException, Thorben Janssen — batch fetching — cross-checked, consistent with primary sources but single-level examples extrapolated to this 3-level hierarchy.
- Baeldung — JPA Optimistic Locking — referenced via search snippet (direct fetch blocked), cross-checked against multiple consistent sources.

### Tertiary (LOW confidence)
- None flagged — all findings in this research round were either codebase-verified or cross-checked across 2+ independent HIGH/MEDIUM sources.

---
Research completed: 2026-07-31
Ready for roadmap: yes
