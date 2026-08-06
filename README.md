# Kanban Board — Backend

REST API for a kanban board (`user → board → column → task → subtask`) with session-based
authentication and ownership-based access control: a user can only reach resources that chain back
to their own account.

The parts worth reading are the ones that aren't CRUD — concurrent-edit handling, an event-driven
activity feed with real failure paths, and Avro schema governance in front of the topic.

## Quick start

```bash
cp .env.example .env
docker compose up
```

Brings up Postgres, a single-node Redpanda (broker plus its built-in Schema Registry), and the app,
which waits on the broker's `rpk cluster health` check rather than a bare TCP probe. Postgres is
published on host port **5433** so a pre-existing native install can't silently answer connections
meant for the container. Full runbook: [docs/LOCAL_DEV.md](docs/LOCAL_DEV.md).

```bash
./gradlew test      # everything; E2E classes need Docker for Testcontainers
./gradlew fastTest  # same suite minus the container-backed tests
```

## Engineering highlights

Detail and reasoning for each of these is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

- **Optimistic locking** — concurrent edits to the same task or column return **409** instead of
  silently overwriting → [how](docs/ARCHITECTURE.md#concurrency-optimistic-locking)
- **Activity feed publishes after commit, off the request thread** — so recording history can never
  slow down or fail the mutation that caused it, and poison messages retry then dead-letter →
  [how](docs/ARCHITECTURE.md#event-driven-activity-feed)
- **Avro schemas with enforced BACKWARD compatibility** — registration is owned by a Gradle task,
  not the producer, and a test proves the registry actually rejects an incompatible change →
  [how](docs/ARCHITECTURE.md#schema-governance)
- **Layering enforced by ArchUnit, not code review** — controllers reaching into repositories, or a
  service skipping the ownership-verified loader, fail the build →
  [how](docs/ARCHITECTURE.md#testing)
- **N+1 fixed by measurement, not guesswork** — a bulk delete went from 33 queries for 8 tasks to 4
  regardless of count, with a query-count regression test holding it there →
  [how](docs/ARCHITECTURE.md#testing)

## Stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 21, Gradle 8.11.1 (wrapper) |
| Framework | Spring Boot 3.5.0 — Web, Data JPA, Security, Validation |
| Persistence | PostgreSQL + Hibernate; Flyway for schema history; H2 for the test profile |
| Sessions | Spring Session JDBC (server-side session state in Postgres) |
| Messaging | Spring Kafka against Redpanda; Apache Avro 1.12 + Confluent Schema Registry |
| Mapping | MapStruct 1.5.3 (compile-time generated, no reflection) |
| Ids | ULID via `ulid-creator`, generated in-app by `RandFlakeGenerator` |
| Docs | springdoc-openapi 2.8.8 |
| Testing | JUnit 5, REST Assured, Testcontainers (Redpanda), ArchUnit |
| Build gates | Spotless (google-java-format AOSP), ErrorProne |

## API

All routes sit under the `/api` context path and require an authenticated session except signup and
signin. Child resources are created by `POST`ing to their parent.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/signup` · `/signin` · `/logout` | Session cookie; max 2 concurrent sessions per user |
| `GET` | `/boards` | Boards owned by the caller |
| `PUT` `DELETE` | `/boards/{boardId}` | Delete cascades to columns, tasks, subtasks |
| `GET` | `/boards/{boardId}/columns` | |
| `POST` | `/boards/{boardId}/columns` | Create a column |
| `PUT` | `/boards/{boardId}/columns/{columnId}` | Requires the current `version` |
| `POST` | `/boards/{boardId}/columns/{columnId}` | Create a task in the column |
| `GET` | `…/columns/{columnId}/tasks` | |
| `PUT` `DELETE` | `…/columns/{columnId}/tasks/{taskId}` | `PUT` requires the current `version` |
| `PATCH` | `/tasks/{taskId}/move` | Cross-column move; requires the current `version` |
| `GET` `POST` | `…/tasks/{taskId}/subtasks` | |
| `PUT` `DELETE` | `…/tasks/{taskId}/subtasks/{subtaskId}` | |
| `GET` | `/boards/{boardId}/activity` | Paginated feed — default 20, capped at 100 |

Two gaps worth naming rather than hiding: board creation exists as `UserService.addBoardByUserId`
but is not exposed over HTTP yet (only tests reach it), and columns have no delete route — the
cascade is only reachable by deleting the board.

## Testing

208 test methods across 31 classes: unit tests for services and DTO validation, REST Assured
integration tests for controllers, Testcontainers-backed E2E tests for the Kafka pipeline and
locking, and ArchUnit rules over the whole class graph. Entities and repositories are deliberately
untested — they carry no custom logic. Full breakdown in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#testing).

## Project status

Shipped: optimistic locking (v1.0), the Kafka activity feed (v1.1), Avro Schema Registry governance
and the Flyway migration history (v1.2, phases 4 and 4.1).

**In flight — the deployment target.** This ran on AWS EC2 until the host was deliberately torn down
on cost grounds; the replacement is an Oracle Cloud Always Free A1 instance with Neon serverless
Postgres, a resource-capped Redpanda, and Caddy terminating TLS. Until that lands, the GitHub
Actions pipeline runs the test suite and `spotlessCheck`, then builds and pushes a tagged Docker
image — the deploy job is explicitly disabled rather than left to fail against a host that no longer
exists, and it needs a rewrite (not a re-enable) for the new target.

Not done, deliberately: no rate limiting on the auth endpoints, no refresh-token-style session
renewal (sessions are fixed-duration), and no caching layer.

## Documentation

| | |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How it works and why — the detail behind the highlights above |
| [docs/LOCAL_DEV.md](docs/LOCAL_DEV.md) | Local runbook and the compose stack's scope |
| [docs/CODE_STYLE.md](docs/CODE_STYLE.md) | Judgement-level rules the formatter can't check |
| [docs/plans/backend-modernization/](docs/plans/backend-modernization/) | The remaining modernization epics |
