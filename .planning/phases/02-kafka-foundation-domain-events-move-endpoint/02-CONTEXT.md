# Phase 2: Kafka Foundation, Domain Events & Move Endpoint - Context

**Gathered:** 2026-08-01
**Status:** Ready for planning

<domain>
## Phase Boundary

Stand up local Kafka infrastructure (docker-compose, spring-kafka/Testcontainers dependencies), define five typed domain event records, wire producer publishing into TaskService/BoardService/ColumnService via an after-commit pattern (avoiding dual-write ghosts/drops), and add the genuinely-missing `PATCH /tasks/{taskId}/move` endpoint. Covers KAFKA-01/02, EVENT-01/02, MOVE-01/02/03. Does not cover the consumer, activity-log persistence, the read endpoint, dead-letter handling, or end-to-end testing — that's Phase 3. Does not cover task ordering/position within a column (deferred, see below) or production/EC2 Kafka deployment (deferred to v2 per REQUIREMENTS.md).

</domain>

<decisions>
## Implementation Decisions

### Kafka-down resilience
- **D-01:** Task/Board/Column mutations always succeed at the HTTP level even if the Kafka broker is unreachable at publish time — the activity log falls behind, the primary write path is never blocked. Matches the epic's own framing (decoupling the write path from a slower/optional side effect) and research's explicit recommendation. — **Reversibility:** costly — flipping this later (making publish failures roll back the mutation) would require reintroducing publish into the transactional boundary and re-testing every mutation path's failure semantics.
- **D-02:** A failed publish attempt is logged (SLF4J), not silently swallowed — attach a `.whenComplete`/failure callback to the async publish so a broker outage leaves a visible trace, even though the HTTP request still succeeds.
- **D-03:** Local dev startup sequencing: the `kafka` service in `docker-compose.yml` gets a healthcheck (polling its own broker readiness), and the `app` service uses `depends_on: kafka: condition: service_healthy`. This gives "wait a bounded time for Kafka to become reachable, then fail if it doesn't" using Kafka's own healthcheck as the readiness signal — no app-level polling/retry code needed for this. If the healthcheck never passes within its configured retries/timeout, Compose marks `kafka` unhealthy and the `app` container never starts.

### Move endpoint scope
- **D-04:** `PATCH /tasks/{taskId}/move` only reassigns the task's column — no position/order concept. Confirmed via Phase 1 research that no position/order field exists anywhere in the entity layer today; adding one is out of scope for Epic 1. — **Reversibility:** reversible — a future ordering phase adds a new field and reorder logic without needing to touch this endpoint's existing column-reassignment behavior.
- **D-05:** A moved task lands with no defined position within its target column — matches the current reality that tasks aren't ordered at all today (GET endpoints return whatever order the DB gives back).

### Claude's Discretion
- Exact healthcheck command/probe for the `kafka` service in docker-compose.yml (e.g., broker API check vs. a lightweight `kafka-topics.sh --list` probe) — pick whatever is idiomatic for the `apache/kafka-native` image.
- Exact retry/timeout window for the healthcheck (interval, retries, start_period) — pick a reasonable bound (e.g., 30-60s total) for local dev, not tuned for production.
- Log level/format for the publish-failure callback — SLF4J is confirmed, exact message shape is Claude's call.
- `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)` implementation details (not discussed directly, but strongly recommended by research as the mechanism that satisfies D-01/D-02 without a dual-write gap) — planner/researcher to confirm this is still the right mechanism during planning.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Epic spec (source of truth for scope)
- `docs/plans/backend-modernization/01-kafka-activity-feed.md` — the literal spec this milestone implements (Kafka infra, domain events, activity log, move endpoint, DLT, testing)
- `docs/plans/backend-modernization/README.md` — overall modernization plan context, one-epic-per-PR discipline

### Project-level requirements
- `.planning/PROJECT.md` — Core Value, Current Milestone (v1.1), Active requirements
- `.planning/REQUIREMENTS.md` — KAFKA-01/02, EVENT-01/02, MOVE-01/02/03 (this phase's requirements); ACTLOG/READ/RELY/TEST (Phase 3); v2 deferred items (production Kafka, cursor pagination)
- `.planning/ROADMAP.md` — Phase 2 goal, success criteria, dependency on Phase 1

### Research (grounded findings, not general knowledge)
- `.planning/research/SUMMARY.md` — synthesized findings across all 4 dimensions; read first
- `.planning/research/STACK.md` — exact BOM-managed dependency versions (spring-kafka 3.3.6, kafka-clients 3.9.1, Testcontainers 1.21.0), `apache/kafka-native` KRaft compose guidance
- `.planning/research/ARCHITECTURE.md` — producer integration points (KafkaTemplate field injection into existing services), suggested build order, dual-write mitigation pattern
- `.planning/research/PITFALLS.md` — dual-write problem, idempotency race, swallowed publish failures, DLT half-implementation, compose/EC2 state-loss risk (D-01/D-02/D-03 directly address the first three)

### Prior phase context (established conventions this phase reuses)
- `.planning/milestones/v1.0-phases/01-optimistic-locking/01-CONTEXT.md` — the `@Version` explicit-check-before-mutate convention (D-02 from that phase) that MOVE-02 must reuse; the 409 plain-string response shape convention (D-05 from that phase)

### Deploy pipeline (confirms production Kafka scoping is structurally sound, not just a choice)
- `.github/workflows/deploy.yml` — confirms the CI/CD deploy path is a single `docker run` of the app image only (line ~104), NOT docker-compose. The new `docker-compose.yml` from this phase is local-dev-only and has zero interaction with the actual EC2 deploy pipeline — reinforces the v2-deferred "production Kafka deployment" decision from milestone scoping.

### Codebase maps
- `.planning/codebase/ARCHITECTURE.md` — layered architecture, `@Autowired` field-injection convention, existing DTO/mapper/service patterns, ownership-verification chain
- `.planning/codebase/INTEGRATIONS.md` — confirms no existing Kafka/messaging integration, deploy details (single EC2 docker container, port 8080→80)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `TaskService.updateById` (`src/main/java/com/vrudenko/kanban_board/service/TaskService.java`, lines 65-99) — the exact explicit version-check-before-mutate + `entityManager.flush()` pattern that `TaskService.moveToColumn`/the move endpoint must reuse for MOVE-02. Javadoc on lines 65-73 explains WHY the explicit check is needed beyond `@Version`'s own dirty-checking.
- `OwnershipVerifierService` — existing ownership-verification chain (Task → Column → Board → User); the move endpoint needs to verify ownership of both the task AND the target column (to support the cross-board-reject decision, MOVE-03, already locked in REQUIREMENTS.md).
- `GlobalExceptionHandler` — already maps `OptimisticLockingFailureException` to 409 (fixed in Phase 1); the move endpoint's stale-version case reuses this unmodified.

### Established Patterns
- Services use `@Autowired` field injection (not constructor) — established to sidestep circular bean dependency ordering. Follow this for the new `KafkaTemplate`/`ApplicationEventPublisher` dependency in Task/Board/Column services.
- DTO layering: `SaveXRequestDTO` / `UpdateXRequestDTO` / `XResponseDTO` per entity, MapStruct mappers with `componentModel=SPRING`. A `MoveTaskRequestDTO { targetColumnId, version }` should follow this shape.
- No `spring-kafka` on the classpath yet (confirmed via `build.gradle` grep) — this phase adds it from scratch, BOM-managed, no explicit version string (matches the existing no-version-pin convention for other Spring Boot starters).
- No existing docker-compose.yml at repo root — only a `Dockerfile`. This phase creates the first one.

### Integration Points
- New `TaskService.moveToColumn(userId, taskId, dto)` method, mirroring `updateById`'s shape (ownership-verified load → explicit version check → mutate → save → flush → publish event).
- New `PATCH /tasks/{taskId}/move` route in `TaskController`, following the `ApiPaths` constants convention.
- `TaskService`/`BoardService`/`ColumnService` each gain an `ApplicationEventPublisher` (or `KafkaTemplate`, per planner's technical decision) field, publishing at the tail of each existing mutating method.

</code_context>

<specifics>
## Specific Ideas

- User specifically wants the Kafka-reachability wait to feel like Kafka "notifying" the app when it's ready, bounded by a reasonable timeout, with failure if it never becomes ready — resolved as a docker-compose healthcheck + `depends_on: condition: service_healthy` gate, since that's the closest existing mechanism to "broker notifies readiness."

</specifics>

<deferred>
## Deferred Ideas

- **Task ordering/position within a column** (drag-and-drop reorder) — raised during the move-endpoint discussion. Not in Epic 1's spec, not in current REQUIREMENTS.md. Would need a new position field on `TaskEntity`, reorder logic for sibling tasks when one is inserted/moved, and likely its own migration. User explicitly agreed to defer this to a future milestone/phase rather than expand Phase 2's scope. Worth surfacing again at `/gsd-new-milestone` time as a candidate for v1.2+.
- Production (EC2) Kafka deployment — already tracked as v2-deferred in REQUIREMENTS.md (`KAFKA-V2-01`); reinforced here by confirming the deploy pipeline structurally doesn't use docker-compose at all today.

### Reviewed Todos (not folded)
None — no pending todos exist in this project (`.planning/todos/` directory does not exist).

</deferred>

---

*Phase: 2-Kafka Foundation, Domain Events & Move Endpoint*
*Context gathered: 2026-08-01*
