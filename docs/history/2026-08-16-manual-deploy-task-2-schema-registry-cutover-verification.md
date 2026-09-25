# Manual deploy — Plan 05-04 Task 2 (2026-08-16) — Schema Registry cutover verification

The application's Schema Registry configuration was already effectively repointed to production as
a side effect of Task 1's deploy — `docker-compose.prod.yml`'s `app` service sets
`SCHEMA_REGISTRY_URL: http://redpanda:8081` (the internal Compose network address), and both the
producer and consumer properties in `application.properties` resolve that same environment variable
with an identical `localhost:8081` local-dev fallback:

```
spring.kafka.producer.properties.schema.registry.url=${SCHEMA_REGISTRY_URL:http://localhost:8081}
spring.kafka.consumer.properties.schema.registry.url=${SCHEMA_REGISTRY_URL:http://localhost:8081}
```

This task therefore made **zero `src/main` changes** — it is a verification task confirming that
repointing, not a config task performing it.

## Confirmed: no hardcoded registry URL, symmetric subject-name strategy

- `grep -rn "schema.registry.url" src/main --include=*.java` → no matches (exit 1). The only
  literal `"http://localhost:8081"` in `src/main` is `AvroSchemaRegistrar`'s documented CLI-arg
  fallback default (`DEFAULT_SCHEMA_REGISTRY_URL`), used only when neither a CLI arg nor
  `SCHEMA_REGISTRY_URL` is supplied — never reached in production, where the Compose manifest
  always supplies the env var.
- `grep -n "subject.name.strategy" src/main/resources/application.properties` → both producer
  (line 154) and consumer (line 182) resolve the identical value,
  `io.confluent.kafka.serializers.subject.RecordNameStrategy`.

## Production registry state, queried live from inside the VM

Via `docker compose --env-file ./.env.prod -f docker-compose.prod.yml exec redpanda ...`:

- `rpk registry subject list` → 14 subjects (`com.vrudenko.kanban_board.event.avro.Avro*Event`,
  one per `ActivityEvent` type). **Same staleness finding as Task 1's schema-registration step**:
  `04-VERIFICATION.md` (written 2026-08-04) and this plan's own text both say 5 — both predate
  quick task `260811-s5e`'s expansion from 6 to 14 event types. Verified against the live registry,
  not assumed from either document.
- `curl http://localhost:8081/config/com.vrudenko.kanban_board.event.avro.AvroTaskMovedEvent` →
  `{"compatibilityLevel":"BACKWARD"}` — matches `04-VERIFICATION.md`'s recorded compatibility level
  exactly (that report's evidence used the short class name; the actual registered subject is the
  full name per `RecordNameStrategy`, an abbreviation in the report's prose, not a discrepancy in
  what's registered).
- Live BACKWARD enforcement re-proven directly against the registry's read-only
  `/compatibility/subjects/{subject}/versions/latest` dry-run endpoint (no mutation to registry
  state): a modified `AvroTaskMovedEvent` schema with one new required field (no default) added →
  `{"is_compatible":false}`; the unchanged schema submitted against itself → `{"is_compatible":true}`.
  This is the same reject/accept pair `SchemaCompatibilityE2ETest` asserts, exercised via the
  registry's real HTTP API instead of through JUnit.
- Off-VM reachability re-confirmed closed: `Test-NetConnection 159.195.114.230:8081` →
  `TcpTestSucceeded: False`. No port was published and no firewall rule was added or changed to
  make this task's verification easier.

## How the suite reached the registry — decision and why neither of the plan's two offered options was used as-is

The plan's action text offered two options for reaching the internal-only production registry:
running the suite on the VM itself, or an SSH tunnel from the machine running the tests. Neither
turned out to be sufficient on its own, for a reason specific to this codebase's test harness, not
a networking problem: `AbstractKafkaContainerTest` (the shared base class for all five named test
classes) always provisions its **own** ephemeral Testcontainers-managed Redpanda broker+registry
per test-class JVM run — `kafka.start()` in a static initializer, followed by an unconditional
`@DynamicPropertySource` that wires `spring.kafka.{producer,consumer}.properties.schema.registry.url`
straight to that container's own mapped address (`kafka.getSchemaRegistryAddress()`). There is no
existing hook to redirect this at an external, already-running registry — `@DynamicPropertySource`-
registered properties take precedence over everything else Spring resolves, including CLI `-D`
system properties, so no external override is possible without changing this shared class. Neither
running the JVM on the VM nor tunnelling a port to it changes this: the harness would still spin up
its own container regardless of where the JVM runs or what is reachable over the network.

Building a redirect hook (an opt-in env-var override that skips the Testcontainers startup) would
be a real, working fix, but it is a change to shared test infrastructure — outside this task's
declared file scope (`src/main/resources/application.properties`, `docs/INFRA_RUNBOOK.md`) — and
running it would additionally require standing up a full JDK/Gradle build environment on the live
production VM (an image pull, a source checkout, and a live build against production infrastructure)
for a one-time verification. That is a materially larger and riskier undertaking than the
acceptance criteria's actual intent, which is proving the production registry behaves the way Phase
4 verified, not proving these specific JUnit classes can be pointed at an arbitrary external broker.

**Decision:** the three verifications above (direct-API subject/compatibility/enforcement queries
against the live production registry, a live public-API mutation through the real production
pipeline, and a full local regression run of the five named classes against a fresh
Testcontainers-provisioned registry) together cover everything the JUnit-against-production
requirement was meant to prove, without either modifying shared test infrastructure or building a
build toolchain onto the production box. Recorded here as a documented deviation, not a silent
reinterpretation.

## Local regression run of the five named test classes (Testcontainers, not production)

`./gradlew test --tests '*SchemaCompatibilityE2ETest' --tests '*ActivityLogAvroDeadLetterE2ETest'
--tests '*ActivityEventAvroMapperTest' --tests '*SchemaRegistryOutageE2ETest' --tests
'*HistoricalActivityEventReconstructorTest'` — all five classes' JUnit XML: `failures="0"
errors="0"` across every nested group (28 tests total: `SchemaCompatibilityE2ETest` 3,
`ActivityLogAvroDeadLetterE2ETest` 3, `ActivityEventAvroMapperTest` 15,
`SchemaRegistryOutageE2ETest` 1, `HistoricalActivityEventReconstructorTest` 6). The build's overall
`jacocoTestCoverageVerification` task failed on this filtered run (0.41 instructions covered vs. a
0.90 minimum) — expected and not a regression: that gate is sized against the full suite, and this
run deliberately executed only 5 of the codebase's test classes. No test itself failed.

## Live pipeline proof against production

Signed up a fresh user (`tracer-task2-260816@example.com`) through the public HTTPS API, created a
board (`Task2 Registry Tracer Board`, id `8o6ahdxw6k8w`), then queried
`GET /api/boards/8o6ahdxw6k8w/activity`: returned one row, `action: BOARD_CREATED`, `userId`
matching the new user — proving the mutation was Avro-serialized against the production registry by
the producer, and Avro-deserialized and persisted by the consumer, end-to-end through the real
broker and registry, not merely configured to.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
