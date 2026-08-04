# Running the stack locally

This project ships a `docker-compose.yml` at the repo root that stands up the full local
development stack: Postgres, a single-node KRaft-mode Kafka broker, and the app itself. This
document covers how to run it, why it's shaped the way it is, and — importantly — what it is
*not*.

## Local development only

**`docker-compose.yml` is not a deployment artifact.** The production pipeline
(`.github/workflows/deploy.yml`) deploys the app with a single `docker run` of the built image —
it never runs `docker compose up`, and it does not stand up a Kafka broker at all. Concretely,
this means the deployed app on EC2 receives no `KAFKA_BOOTSTRAP_SERVERS` environment variable, so
it falls back to `localhost:9092`, finds no broker there, and logs a publish failure per mutation
while every mutation still succeeds at the HTTP level (see "Why the app waits for Kafka" below for
the mechanism that makes failed publishes non-fatal). This is the deliberate, documented
consequence of shipping Kafka for local dev and tests only — it is not a bug to hot-fix.

Standing up a real Kafka broker in production — its own listener/network configuration, TLS/SASL,
volume durability under EC2's constraints — is a separate, still-deferred decision tracked as
`KAFKA-V2-01`. Do not reuse this compose file unmodified as a deploy artifact without that review.

## Prerequisites

- Docker, with the Compose v2 plugin (`docker compose version` should succeed)
- JDK 21, only if you want to run the app outside of Compose via `./gradlew bootRun`

## Quickstart

```bash
cp .env.example .env
docker compose up
```

That's it. Once everything is healthy:

- App: `http://localhost:8080/api`
- Swagger UI: `http://localhost:8080/api/swagger-ui/index.html`
- Kafka's host-facing listener: `localhost:9092` (for local tooling such as a Kafka UI)
- Postgres's host-facing port: `localhost:5433` (not the well-known 5432 — see "Why Postgres is on
  a non-default host port" below)

## Why Postgres is on a non-default host port

The compose `postgres` service publishes host port **5433**, not the default 5432. On some
developer machines, a pre-existing native (non-Docker) PostgreSQL install already owns host port
5432 and silently answers connections meant for this container instead of refusing them outright.
The symptom is not "connection refused" — it's `FATAL: password authentication failed`, because a
different Postgres server with different credentials answered the connection. This cost a real
diagnosis session two hours (`.planning/phases/04-schema-registry/04-04-SUMMARY.md`) before the
root cause — a native `postgresql-x64-17` Windows service bound to 5432 — was found. Remapping the
container off the contested port sidesteps the conflict entirely, for every developer, without
needing administrator rights to stop or reconfigure the native service.

The container-internal port is untouched: the `app` service reaches Postgres over the compose
network at `postgres:5432` and needs no change.

Any JVM you run **on the host** (outside `docker compose`) — most notably the
`rehearseHistoricalSchemas` Gradle task, which deliberately resolves the app's real (non-test)
datasource config instead of the test profile's H2 URL — must target the host-published port
explicitly via `DB_PORT`:

```bash
DB_HOST=127.0.0.1 DB_PORT=5433 DB_NAME=kanban DB_USER=kanban DB_PASS=changeme \
  ./gradlew rehearseHistoricalSchemas
```

`DB_PORT` defaults to 5432 when unset, so nothing else in this repo (the compose `app` service, CI,
`.github/workflows/deploy.yml`) needs to know it exists.

## Why the app waits for Kafka

The `kafka` service carries a `healthcheck:` (a bare TCP-connect probe — see below) and the `app`
service declares `depends_on: kafka: condition: service_healthy`. Compose therefore won't start
the `app` container until `kafka` reports healthy, within a bounded ~55-second window (5s
interval, 8 retries, 15s start period). If the broker never becomes healthy in that window, Compose
marks `kafka` unhealthy and the `app` container never starts at all — there is no app-level
polling/retry code for this; Compose's own health gate does the whole job.

## Why the healthcheck is a TCP probe, not a Kafka admin command

The image used here, `apache/kafka-native`, ships no JVM and no `bin/*.sh` admin-script tree — its
runtime layer is bare Alpine. Any healthcheck built around a Kafka admin CLI command (the pattern
in almost every Kafka Docker tutorial, which targets the *other*, JVM-based `apache/kafka` image)
fails every single attempt with a file-not-found error on this image. The reliable alternative is a
bare TCP-connect probe against the broker's internal listener using bash's `/dev/tcp` pseudo-device.
If a future contributor is tempted to "fix" this into an admin-command probe, don't — it will break
the healthcheck, not improve it.

This does mean the healthcheck only proves the listener socket is open, not that KRaft controller
election has fully settled. The `start_period` and multiple `retries` exist to give that a bit of
headroom; a transient publish failure on the very first mutation right after startup is possible
and, per the resilience decisions below, non-fatal.

## Resetting state

- `docker compose down` stops and removes the containers but keeps the named volumes
  (`postgres-data`, `kafka-data`) — your data and the broker's log directory survive, and the next
  `docker compose up` picks up where you left off.
- `docker compose down -v` additionally destroys those named volumes. This is destructive: it wipes
  both the database and the broker's log directory. Only use it when you actually want a clean
  slate.

## Testcontainers-based tests on Windows

The Testcontainers-based Kafka tests (`*E2ETest` classes under
`src/test/java/com/vrudenko/kanban_board/activitylog/`) spin up their own broker in a container —
separate from the `docker compose up` stack above. On a fresh Windows machine with Docker Desktop
running, `./gradlew test` just works — no host configuration, no environment variables, nothing to
click. If you previously hit `BadRequestException (Status 400)` / `Could not find a valid Docker
environment` running these tests, that was a real, now-fixed incompatibility — see below, you don't
need to do anything.

**Root cause (already fixed in code — this section is background, not a setup step):**
`docker-java` (bundled by Testcontainers 1.21.0) negotiates a Docker Engine API version that Docker
Engine 29.x rejects with a malformed `400 Bad Request` — on every transport (named pipe and TCP
alike), and confirmed unrelated to Windows specifically. This is
[testcontainers-java#11212](https://github.com/testcontainers/testcontainers-java/issues/11212), a
known Docker 29.x / testcontainers-java 1.21.0 incompatibility, fixed as the new default in
testcontainers-java 2.x. `AbstractKafkaContainerTest` pins the client to API `1.44` (Docker's own
confirmed-working floor for this Engine generation) via `System.setProperty("api.version", "1.44")`
in a static initializer — see its Javadoc. Per
[docs/CODE_STYLE.md rule 8](CODE_STYLE.md#8-test-setup-must-be-fully-automated--never-a-manual-step-for-the-developer),
this lives in test code specifically so no developer has to discover or repeat a manual workaround.

**A related bug this surfaced, also fixed:** with the Docker connectivity issue in the way, a
second, independent bug was masked — `KafkaConsumerConfig`'s dead-letter producer bean was
accidentally suppressing Spring Boot's own default `KafkaTemplate` bean (Spring's
`@ConditionalOnMissingBean(KafkaTemplate.class)` doesn't distinguish generic parameterizations), so
every unqualified `@Autowired KafkaTemplate<String, Object>` — including the real event publisher —
silently resolved to the DLT-flavored template instead, which never picked up a `@ServiceConnection`
override. `KafkaConsumerConfig` now defines both templates explicitly, sharing one correctly-wired
producer factory; see its Javadoc for the full explanation.

If you ever hit a *new* Docker/Testcontainers connectivity failure on Windows in the future, the
right move is the same as it was here: encode the fix in the codebase (a system property, a config
file in version control), not a runbook — see CODE_STYLE.md rule 8.

**More reliable alternative — defer to CI:** this project's GitHub Actions runner is Linux and uses
a Unix domain socket, so it won't hit this Windows-specific issue at all; the tests can be verified
there as part of the PR instead of locally.

## What happens if Kafka is unreachable

Task/Board/Column mutations never fail because Kafka is down — the write path to Postgres and the
Kafka publish are decoupled. A failed publish is always logged (SLF4J `ERROR`, naming the event
type and its `eventId`) rather than silently swallowed, but the HTTP request that triggered it
still returns its normal success response. This holds whether Kafka is down locally (broker
stopped, `docker compose stop kafka`) or in production (no broker configured at all, per the
"Local development only" section above).
