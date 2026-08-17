# Running the stack locally

This project ships a `docker-compose.yml` at the repo root that stands up the full local
development stack: Postgres, a single-node KRaft-mode Kafka broker, and the app itself. This
document covers how to run it, why it's shaped the way it is, and — importantly — what it is
*not*.

## Local development only

**`docker-compose.yml` is not a deployment artifact — `docker-compose.prod.yml` is a separate,
standalone one.** This section originally described a single-`docker run` EC2 deploy with no Kafka
broker at all; that is stale. As of v1.2's infra migration (Phase 5), production deploys to a
Netcup VPS via `docker compose -f docker-compose.prod.yml up -d`, which **does** stand up a
self-hosted, resource-capped Redpanda broker (see `docs/INFRA_RUNBOOK.md` and
`docs/INFRA_ARCHITECTURE.md` for the current shape). `docker-compose.yml` (this file's subject,
local dev only) and `docker-compose.prod.yml` (production) remain two genuinely different files
with different service sets — `docker-compose.prod.yml` defines no `postgres` service (Neon is the
production database) and adds `caddy` (automatic public HTTPS) — so the caution below about not
reusing *this* file unmodified as a deploy artifact still holds, just for a different reason than
originally written.

Kafka-in-production is no longer a deferred decision (`KAFKA-V2-01` is resolved by the Redpanda
service in `docker-compose.prod.yml`) — its listener/network configuration and volume durability
are documented in `docs/INFRA_RUNBOOK.md`'s "Manual deploy" and "Log Rotation Observation"
sections. TLS/SASL on the internal Kafka listener remains out of scope, since that listener never
leaves the VM's internal Docker network (no host port is published) — see
`docs/INFRA_ARCHITECTURE.md`'s Physical/Deployment view for the trust-boundary reasoning.

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
datasource config instead of the test profile's Testcontainers-managed PostgreSQL URL — must
target the host-published port explicitly via `DB_PORT`:

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

Every `@SpringBootTest` class in this repository now starts a Testcontainers-managed container —
the shared `postgres:16` instance via `AbstractPostgresContainerTest`, plus a Kafka broker for the
`*E2ETest` classes under `src/test/java/com/vrudenko/kanban_board/activitylog/` via
`AbstractKafkaContainerTest`. Docker is therefore required for `./gradlew test` and
`./gradlew fastTest` alike — there is no container-free test path left in this repository. On a
fresh Windows machine with Docker Desktop running, `./gradlew test` just works — no host
configuration, no environment variables, nothing to click. If you previously hit
`BadRequestException (Status 400)` / `Could not find a valid Docker environment` running these
tests, that was a real, now-fixed incompatibility — see below, you don't need to do anything.

**Root cause (already fixed in code — this section is background, not a setup step):**
`docker-java` (bundled by Testcontainers 1.21.0) negotiates a Docker Engine API version that Docker
Engine 29.x rejects with a malformed `400 Bad Request` — on every transport (named pipe and TCP
alike), and confirmed unrelated to Windows specifically. This is
[testcontainers-java#11212](https://github.com/testcontainers/testcontainers-java/issues/11212), a
known Docker 29.x / testcontainers-java 1.21.0 incompatibility, fixed as the new default in
testcontainers-java 2.x. The pin now lives in `AbstractPostgresContainerTest` — the shared
container ancestor both `AbstractAppTest` and `AbstractKafkaContainerTest` extend — via
`System.setProperty("api.version", "1.44")` (Docker's own confirmed-working floor for this Engine
generation) in a static initializer, so it fires once, before either container type starts (JVM
superclass-first static init order); see its Javadoc. Per
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

## Testcontainers reuse: evaluated, not enabled

`fastTest` now requires Docker (see above), so Testcontainers reuse — keeping the PostgreSQL
container alive across separate `./gradlew` invocations instead of starting and destroying it
every run — was named as the mitigation to evaluate for local commit latency (decision D-03 in
this phase's context record under `.planning/phases/`). Evaluated 2026-08-06, on Docker Server
**29.4.1**:

- Three consecutive `./gradlew fastTest --rerun-tasks` runs, same session, same machine, no
  `clean` between runs: **232s, 224s, 242s** wall-clock (155 tests each, all green).
- The PostgreSQL container's own start-duration, read from its Testcontainers log line
  (`Container postgres:16 started in PT2.287909S`, captured via
  `./gradlew test --tests FlywaySchemaProvenanceTest -i`): **~2.29s**. This is the honest upper
  bound on what reuse could ever save per invocation — nothing more, since the container starts
  only once per JVM run regardless (see below).

**Decision: reuse is NOT enabled.**

1. **Not a material fraction of the wall-clock.** ~2.29s against a ~230s run is roughly 1% — an
   order of magnitude smaller than the 18-second run-to-run variance already visible across the
   three timings above (224s-242s). There is no meaningful cost here for reuse to recover.
2. **Structurally capped regardless of the number.** D-01 pins exactly one static PostgreSQL
   container per JVM run (`AbstractPostgresContainerTest`'s imperative `static { start(); }`), so
   startup inside a single `./gradlew` invocation is already paid exactly once, at the first
   Spring context creation. Reuse can only ever help a *second, separate* `./gradlew` invocation —
   it cannot make any one run faster than it already is.
3. **No opt-in mechanism would satisfy rule 8 anyway.** The standard Testcontainers reuse opt-in
   is a per-machine `~/.testcontainers.properties` file (`testcontainers.reuse.enable=true`) — a
   hand-edited file living outside version control, which is exactly the manual host-level setup
   step [`docs/CODE_STYLE.md` rule
   8](CODE_STYLE.md#8-test-setup-must-be-fully-automated--never-a-manual-step-for-the-developer)
   forbids. A `TESTCONTAINERS_REUSE_ENABLE` environment variable exists as a possible alternative,
   but this project's own research flagged it at MEDIUM confidence (sourced from a doc summary,
   not primary Testcontainers text), and nothing in this repository sets arbitrary environment
   variables into a developer's shell before Gradle runs — the same manual-step problem would just
   move one layer over.
4. **A real correctness cost, independent of the above.** A reused container keeps its data
   between separate `./gradlew` invocations. This phase's isolation model (D-02) is `@AfterEach`
   row deletion in `AbstractAppTest.cleanup()` — it only cleans what it explicitly knows about
   (users, cascading to boards/columns/tasks/subtasks, plus `activity_log` per D-02a). Anything it
   doesn't know about — `spring_session` / `spring_session_attributes` rows in particular, since
   Flyway deliberately does not own those tables (D-04) — would carry over from one invocation to
   the next under reuse, turning a suite that is currently deterministic (fresh container, fresh
   schema, every run) into one whose behavior depends on whether you happened to run it before.
   That is a genuine argument against reuse on this codebase specifically, not a generic caution
   copied from upstream docs.

No `withReuse` call, `testcontainers.reuse.enable` property, or `TESTCONTAINERS_REUSE_ENABLE`
reference exists anywhere in `build.gradle` or `src/test` as a result — no manual, host-level setup
step was introduced by this phase.

**Revisit if:** a future container image or startup dependency pushes the measured start duration
into double-digit seconds *and* a version-controlled, zero-manual-step opt-in becomes available
(for example, a Gradle-property-driven reuse toggle that doesn't require
`~/.testcontainers.properties`). Until then, the numbers above are the reason this stays off, not
an assumption.

## Test-suite speed: BCrypt cost factor lowered, fork-level parallelism adopted, in-JVM parallelism and per-tier splitting rejected

Quick task 260811-ixj investigated the pending "test parallelization and other suite speed"
todo. Measured, not assumed, on this machine (8 CPUs, 7.728 GiB Docker memory):

| Task | Before (1 fork, cost factor 10) | After (2 forks, cost factor 4) | Delta |
|---|---|---|---|
| `./gradlew test` | 433.5s avg (440s, 427s), 385 tests | 276.5s avg (283s, 270s), 388 tests | -157.0s / -36.2% |
| `./gradlew fastTest` | 337.5s avg (334s, 341s), 348 tests | 242.5s avg (233s, 252s), 351 tests | -95.0s / -28.1% |

(388/351 vs. 385/348 is 3 new `PasswordEncoderStrengthTest` methods, not shrinkage — every run
above is cross-checked against its own test count.) Full run-by-run data, including the
intermediate lever-1-only numbers and the rejected 4-fork measurements, lives in
`.planning/quick/260811-ixj-investigate-and-implement-test-suite-spe/260811-ixj-MEASUREMENTS.md`.

**What was pulled — the test-profile BCrypt cost factor (lever 1).** `AbstractAppTest.setup()` ran
three real BCrypt encodes (`createUser()` x3) at Spring Security's default cost factor of 10 before
every one of 318 fixture-bearing test methods, plus one more per `signinCookie(` call site (~117
sites) — measured at ~89s / 37% of total test-execution time, the single largest cost in the suite.
`BeanConfiguration.passwordEncoder()` now takes an injectable
`@Value("${security.bcrypt.strength:10}") int strength`, and only
`src/main/resources/application-test.properties` overrides it, to 4 (`BCryptPasswordEncoder`'s
minimum permitted value). The fallback of 10 IS the production value, so any deployment that never
activates the `test` Spring profile — every real deployment — is unchanged; that fallback lives in
a version-controlled properties file, not a developer's machine, satisfying
[`docs/CODE_STYLE.md` rule
8](CODE_STYLE.md#8-test-setup-must-be-fully-automated--never-a-manual-step-for-the-developer).
`PasswordEncoderStrengthTest` asserts the fallback string and the key's absence from
`application.properties` reflectively, so an accidental production-side change goes red, not
unnoticed.

**The security trade-off, stated plainly, not glossed.** The `test` profile now exercises a weaker
BCrypt cost factor (4) than production (10), so a hypothetical regression that only manifests at
strength 10 would go unseen by this suite. BCrypt's cost parameter changes only the number of
key-expansion rounds — never the output format or `matches()` semantics — so this is a theoretical
rather than practical gap. The structural mitigations: the `@Value` fallback is the production
value (absence of configuration fails safe), and `PasswordEncoderStrengthTest` asserts both that
fallback and the key's absence from the default properties file. Neither mitigation covers a
deploy-time environment variable or `SPRING_APPLICATION_JSON` override setting the key in
production — that residual is accepted and stated here, not claimed closed, because the property
name is new and appears nowhere in deployment configuration today.

**What was measured for fork-level parallelism (lever 2), and what was decided.** `maxParallelForks`
was measured on `fastTest` at 2 and 4, after lever 1 landed (the optimal fork count differs once
BCrypt's CPU-bound cost is gone, so measuring before would have answered a stale question). 2 forks
averaged 242.5s (233s, 252s) against a 1-fork average of 285.0s — both runs beat it by far more than
this project's documented ~18s run-to-run variance. 4 forks averaged 267.0s (277s, 257s) — slower
than 2 forks, and only one of its two runs cleared the variance bar, so it was not adopted. 2 forks
was then measured on `test` too (which runs the full Kafka tier, a different container-memory
profile) and won there as well: 276.5s avg vs. a 370.5s 1-fork baseline, both runs clearing variance
by 87.5s and 100.5s. `maxParallelForks = 2` is now committed on both `test` and `fastTest` in
`build.gradle`, with the measured numbers recorded as a comment above each assignment. `forkEvery`
remains unset (default 0) — a non-zero value restarts the JVM, and therefore the static
Testcontainers initializer, per class, which on this codebase would be catastrophic rather than
merely slower. Safe here specifically because each Gradle test-worker fork is a separate JVM with
its own classloader: `AbstractPostgresContainerTest`'s `static { postgres.start(); }` runs once per
fork, so N forks means N independent PostgreSQL containers and N independent databases — the D-02
`@AfterEach`-deletion isolation model, which only ever wipes its own fork's database, is untouched.
Live container census during both fork-count runs confirmed exactly N `postgres:16` containers (one
per fork) with no startup or memory failures against this machine's 7.728 GiB Docker budget.

**JUnit 5 in-JVM parallel execution — evaluated, rejected.** `junit.jupiter.execution.parallel.*`
shares one JVM, one classloader, one static Testcontainers instance, one cached Spring context, and
therefore one live database across every concurrently-running test — categorically different from
fork-level parallelism above. Five independent blockers, any one sufficient to reject it: (1)
`AbstractAppTest.cleanup()`'s `userService.deleteAll()` is an unscoped global wipe — one test's
`@AfterEach` firing mid-execution of another test destroys that test's fixtures; (2) the
`countQueries()` helper reads Hibernate's `Statistics.getPrepareStatementCount()`, which is
`SessionFactory`-global, not per-thread, so any concurrent query pollutes the count every
query-count assertion in `TaskServiceTest`/`OwnershipVerifierServiceTest` relies on; (3)
`AbstractKafkaContainerTest.producerSchemaRegistryUrlOverride` is a mutable `volatile` field whose
own Javadoc names sequential execution as a precondition; (4) every Kafka E2E class shares one
`kanban.activity` topic and one `activity-log` consumer group, so concurrent classes would consume
each other's records; (5) positional assertions over shared tables (e.g. `BoardServiceTest`'s
`findAll().getFirst()`) depend on fixture-creation order staying stable. `@ResourceLock` does not
rescue this: its shared resource would have to be the database itself, which all 318
fixture-bearing tests write to, so correct locking re-serializes precisely the 188.4s of
test-execution time that constitutes the cost — a correct but pointless suite.

**Per-tier Gradle task splitting — evaluated, rejected.** Splitting `test` into per-tier tasks
(unit / MockMvc / Kafka-backed) is mechanically additive — `fastTest` already proves the pattern —
but buys no parallelism: Gradle's `--parallel` flag executes tasks from different *subprojects* in
parallel, and this is a single-project build, so N tier tasks would run sequentially, each paying
its own JVM startup, its own PostgreSQL container start, and its own Spring context boots. Strictly
worse than today; `maxParallelForks` above is Gradle's actual supported intra-task parallelism
mechanism and achieves the same distribute-classes-across-JVMs effect without the multiplied fixed
costs.

**Remaining headroom, deliberately not taken.** `AbstractAppTest.setup()`'s per-test fixture build
(31 entities: 3 users, 4 boards, 9 columns, 8 tasks, 7 subtasks) still costs roughly 29s
suite-wide net of the now-removed BCrypt cost, and ~29 test classes depend on its current shape,
including positional assertions — trimming it to only what each test needs is a real remaining
lever but a large refactor, out of this quick task's scope.

**Revisit if:** the remaining ~29s fixture-cost headroom above becomes worth pursuing, or if a
future Docker/host memory increase makes re-measuring `maxParallelForks` at higher counts (6, 8)
worthwhile — this session capped exploration at 4 per Gradle's own cores/2 starting-point guidance
on an 8-CPU machine.

## What happens if Kafka is unreachable

Task/Board/Column mutations never fail because Kafka is down — the write path to Postgres and the
Kafka publish are decoupled. A failed publish is always logged (SLF4J `ERROR`, naming the event
type and its `eventId`) rather than silently swallowed, but the HTTP request that triggered it
still returns its normal success response. This holds whether Kafka is down locally (broker
stopped, `docker compose stop kafka`) or in production (no broker configured at all, per the
"Local development only" section above).
