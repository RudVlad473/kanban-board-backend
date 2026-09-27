# Knowledge Base Index

## architecture

How the Spring Boot application is built: layering, access control, persistence, errors, and the authentication flows a client observes.

| Article | Summary | Updated |
|---------|---------|---------|
| [Architecture](architecture/application-architecture.md) | Engineering mechanisms behind the README summary, each claim tied to the class, task, migration or test that proves it. | 2026-09-04 |
| [Authentication Flows](architecture/auth-flows.md) | What a client observes on the signup and signin routes, and what silently breaks an automated test suite driving them. | 2026-08-19 |

## conventions

How work in this repo is written and run: Java code style, diagram conventions and GSD session lessons.

| Article | Summary | Updated |
|---------|---------|---------|
| [Code Style Guide](conventions/code-style.md) | Append-only Java code-style rules that agents and contributors follow in this repository. | 2026-08-20 |
| [Diagram Conventions](conventions/diagram-conventions.md) | How architecture diagrams snap to Kruchten's 4+1 view model, plus flowchart layout rules. | 2026-09-05 |
| [Session Lessons](conventions/session-lessons.md) | Append-only operational lessons from running GSD workflows here: git hygiene, push cadence, hooks, host memory contention. | 2026-09-26 |

## infra

Where and how the system runs: production infrastructure, its operations runbook and the local development stack.

| Article | Summary | Updated |
|---------|---------|---------|
| [Infrastructure Architecture](infra/infra-architecture.md) | Production deployment topology after the k3s cutover: single-node k3s on a Netcup VPS, Flux, Traefik, cert-manager, self-hosted Postgres and Redpanda. | 2026-09-27 |
| [Production Infrastructure Runbook](infra/infra-runbook.md) | The actual provisioned state of the production VM and how it is locked down, with no secrets recorded. | 2026-09-27 |
| [Running the stack locally](infra/local-dev-stack.md) | Running the local docker-compose development stack, why it is shaped that way, and what it is not. | 2026-09-25 |

## learning

A numbered study guide explaining each layer's decisions, read in order from 00.

| Article | Summary | Updated |
|---------|---------|---------|
| [00 — Learning guide: how to read it](learning/00-README.md) | How to read the learning guide: audience, chapter order and the Simplified Technical English it uses. | 2026-09-23 |
| [01 — Domain model and database schema](learning/01-domain-model-and-schema.md) | The five domain entities, their relationships, primary keys and PostgreSQL schema. | 2026-09-23 |
| [02 — Persistence and queries](learning/02-persistence-and-queries.md) | Reading and writing the Kanban graph through Spring Data JPA and Hibernate, where request cost is a count of SQL statements. | 2026-09-23 |
| [03 — Optimistic locking](learning/03-optimistic-locking.md) | Detecting conflicting writes with versions: stale writes get 409, truly parallel losers get 500 (a known gap). | 2026-09-23 |
| [04 — Service layer and access control](learning/04-service-layer-and-access-control.md) | Where every business rule and the only access control live: ownership checks, ordering, uniqueness, cascades, event publication. | 2026-09-23 |
| [05 — API layer](learning/05-api-layer.md) | Turning HTTP into service calls and results into JSON; the status-code and error-code contract the frontend reads. | 2026-09-23 |
| [06 — Security and sessions](learning/06-security-and-sessions.md) | Caller identity, server-side sessions, refusing unauthenticated requests and limiting password attempts. | 2026-09-25 |
| [07 — Events and the activity feed](learning/07-events-and-activity-feed.md) | Domain events to Kafka and their consumption into each board's activity log: delivery semantics, idempotency, schema evolution. | 2026-09-23 |
| [08 — Testing strategy](learning/08-testing-strategy.md) | Proving every layer against real Postgres, a real Spring context and a real Kafka-protocol broker instead of mocks. | 2026-09-23 |
| [09 — Build, quality gates and CI](learning/09-build-quality-and-ci.md) | From source tree to a tested, scanned, pinned Docker image, and the gates deciding what reaches production. | 2026-09-25 |
| [10 — Infrastructure and deployment](learning/10-infrastructure-and-deployment.md) | The physical deployment on one Netcup VPS, and the guarantees and limits of that box. | 2026-09-25 |
| [11 — Observability](learning/11-observability.md) | Host, container, Postgres and Redpanda metrics and container logs, shown in self-hosted Grafana. | 2026-09-25 |
