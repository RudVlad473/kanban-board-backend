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
separate from the `docker compose up` stack above — and on Windows this can fail even when Docker
Desktop itself is completely healthy.

**Symptom:** `docker version`, `docker info`, and `docker run --rm hello-world` all succeed from
the CLI, but the Testcontainers-driven tests fail to start a container, with `docker-java` (the
HTTP client Testcontainers uses) returning a `BadRequestException (Status 400)` with an empty body.

**Root cause:** `docker-java`'s HTTP client can't reliably talk to Docker Desktop's Windows named
pipe transport (`npipe:////./pipe/dockerDesktopLinuxEngine`) on some Desktop/client version
combinations, even though the real Docker CLI — which uses a different transport — works fine
against the same daemon. This is a client-library/Desktop-version incompatibility local to Windows,
not a defect in the test code, and not something a `testcontainers`/`docker-java` version bump can
always fix (this project's `build.gradle` is also not meant to be modified just to chase it).

**Fix — expose the daemon over TCP instead of the named pipe:**

1. Docker Desktop → **Settings → General** → enable *"Expose daemon on tcp://localhost:2375
   without TLS"*.
2. Run the tests with `DOCKER_HOST` pointed at that port:
   ```bash
   DOCKER_HOST=tcp://localhost:2375 ./gradlew test --tests '*ActivityLog*E2ETest'
   ```
   (PowerShell: `$env:DOCKER_HOST='tcp://localhost:2375'` first, then run gradle in the same shell.)

**Security note:** this exposes the Docker daemon on an unauthenticated local TCP port. That's a
commonly-accepted tradeoff for local dev on a single-user machine, but it is a real one — anything
with access to `localhost:2375` gets root-equivalent control of your Docker daemon. Turn the
setting back off if that's a concern on your machine.

**Alternative — skip the Windows transport entirely:** run the tests from WSL2, where Docker
Desktop communicates over a Unix socket rather than a Windows named pipe, and this issue doesn't
come up.

**Alternative — defer to CI:** this project's GitHub Actions runner is Linux and uses a Unix domain
socket, so it won't hit this Windows-specific issue at all; the tests can be verified there as part
of the PR instead of locally.

## What happens if Kafka is unreachable

Task/Board/Column mutations never fail because Kafka is down — the write path to Postgres and the
Kafka publish are decoupled. A failed publish is always logged (SLF4J `ERROR`, naming the event
type and its `eventId`) rather than silently swallowed, but the HTTP request that triggered it
still returns its normal success response. This holds whether Kafka is down locally (broker
stopped, `docker compose stop kafka`) or in production (no broker configured at all, per the
"Local development only" section above).
