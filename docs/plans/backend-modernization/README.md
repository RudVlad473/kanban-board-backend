# Backend Modernization Plan

**Purpose:** close the technology gaps in the stack (Kafka, JPA/Hibernate performance, Flyway,
Redis depth, Kubernetes, observability) *inside the existing kanban-board-backend codebase*, as
real features with real justification — not bolted-on demo code.

This plan is split into one file per epic so each can be worked, reviewed, and merged as its own
PR without the whole plan living in your head at once. See [STATUS.md](STATUS.md) for the current
checklist.

**Repo:** `RudVlad473/kanban-board-backend` — Spring Boot 3.5.0, Java 21, Gradle, PostgreSQL,
Hibernate/JPA, Spring Security (session-based), MapStruct, springdoc-openapi, Testcontainers
PostgreSQL for tests (Epic 5, delivered — see below), single-EC2 Docker deploy via GitHub Actions.

**Domain, confirmed from code:** `UserEntity → BoardEntity → ColumnEntity → TaskEntity →
SubtaskEntity`, all child→parent `@ManyToOne`, ownership enforced by
`OwnershipVerifierService`, which walks the chain with **sequential `repository.findById()` calls**.
This is a real, present-tense N+1 pattern — not a contrived example — and Epic 2 fixes it.

**Correction to the earlier tech-gap report:** `springdoc-openapi-starter-webmvc-ui` is *already*
a dependency and wired into `SecurityConfiguration`. OpenAPI/Swagger is **not a gap** — what's
missing is *quality* (no `@Operation`/`@ApiResponse`/schema annotations). That's folded into
Epic 3 as a ~1-hour polish item.

## How to work through this

1. Go epic by epic, in order — it's ROI-ranked and each epic builds on the last.
2. Open the epic's file, and use it directly as the brief for a Claude Code session in this repo.
3. Review the diff before accepting — especially the N+1 fix in Epic 2, since it changes query
   semantics.
4. Run `./gradlew test` and `./gradlew spotlessCheck` after each epic (matches existing CI).
5. Update the README's testing-philosophy section if a new test category is introduced (e.g.
   Kafka consumer tests don't cleanly fit the existing "unit = services/DTOs, integration =
   controllers" rule — say so explicitly rather than silently breaking the stated convention).
6. Do not do all epics in one giant PR. Each is a separate, defensible unit of work you can
   describe individually.

## Epics

| # | Epic | Effort | Priority |
|---|---|---|---|
| 1 | [Kafka + event-driven activity feed](01-kafka-activity-feed.md) | 1–2 weeks | Highest — biggest capability gain |
| 2 | [Fix N+1 chain + optimistic locking](02-n-plus-one-optimistic-locking.md) | 3–5 days | Highest — directly answers the JPA-depth question |
| 3 | [Flyway migrations + OpenAPI polish](03-flyway-openapi.md) | 1–2 days | High — cheap, expected, do it early |
| 4 | [Redis: cache + rate limiting](04-redis.md) | 3–5 days | Medium-high |
| 5 | [Testcontainers, drop H2](05-testcontainers.md) — ✅ delivered (Phase 04.2) | 2–3 days | Medium — do once Epics 1 & 3 exist |
| 6 | [Observability: Actuator + Micrometer + Prometheus](06-observability.md) | 2–3 days | Medium — cheap, often overlooked |
| 7 | [Kubernetes, local only (stretch)](07-kubernetes-stretch.md) | 2–4 days | Low — scoped intentionally small |

Total realistic timeline: roughly **3–5 weeks** of evenings/weekends work. Treat Epics 1–3 as the
ones worth having *done* first, and the rest as ongoing.

## Deliberately excluded / deferred

- **GraphQL, Elasticsearch, WebFlux/reactive, Kotlin** — niche or senior-leaning for this segment;
  low ROI relative to effort.
- **Oracle/PL-SQL** — only relevant for Polish banking/enterprise specifically; not practicable
  inside a personal PostgreSQL project. Separate, later decision if you target that segment.
- **Angular** — only relevant if broadening into Java shops that use Angular instead of React;
  a frontend-stack decision, out of scope for this backend plan.
- **Full microservice extraction** of the activity-log consumer into a separate deployable
  service/repo — the in-process `@KafkaListener` from Epic 1 already demonstrates the
  event-driven pattern and ownership boundary. Only do this if you want a second "how do two
  services talk to each other in prod" story — treat as a possible Epic 8 later.

## Source

Original gap-analysis doc: `B:\downloads\claude_desktop\kanban-board-backend-gap-plan.md`
(kept external; this directory is the working copy we iterate on).

