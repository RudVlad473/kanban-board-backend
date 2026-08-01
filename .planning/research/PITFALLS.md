# Pitfalls Research

**Domain:** Adding Kafka event publishing + idempotent consumer + per-board activity log to an existing transactional Spring Boot CRUD app (single-EC2 auto-deploy, session-based auth, H2/Postgres split test setup)
**Researched:** 2026-08-01
**Confidence:** LOW-MEDIUM (general web sources, not cross-verified against a second independent source per claim — see Sources)

## Critical Pitfalls

### Pitfall 1: Publishing the Kafka event from inside the same `@Transactional` method body ("dual write" / ghost events)

**What goes wrong:**
`TaskService`, `BoardService`, and `ColumnService` already wrap every mutation in `@Transactional`. The epic spec says "after each successful mutating operation... publish the corresponding event via `KafkaTemplate`." If `kafkaTemplate.send(...)` is called as a normal line of code inside that same `@Transactional` method, Spring's transaction boundary has no idea Kafka exists — the JDBC transaction and the Kafka publish are two independent systems with no shared coordinator. Two failure modes result:
- Publish happens, then something later in the method (or the transaction's own commit) fails and the DB rolls back → a "ghost event" fires for a board mutation that never actually happened, and the `ActivityLogConsumer` writes a log row for a task move/create that doesn't exist.
- The DB commits, then the publish throws (broker momentarily down, serialization error) → the write succeeds but the event and its activity-log row are silently lost, and nothing downstream knows a mutation happened.

**Why it happens:**
`@Transactional` implicitly limits its guarantee to the JDBC datasource. Calling `kafkaTemplate.send()` as a plain statement anywhere before the method returns "looks" transactional because it's inside the annotated method, but it isn't — this is the textbook dual-write problem.

**How to avoid:**
Full transactional-outbox (durable outbox table + relay process) is the textbook-correct fix but is heavier infrastructure than this portfolio-scope epic needs. A right-sized fix that fits the existing stack (Spring's transaction manager is already present): publish an internal `ApplicationEvent` (e.g. `TaskMovedEvent`) from inside the `@Transactional` service method via `ApplicationEventPublisher`, and do the actual `kafkaTemplate.send()` in a separate `@Component` listener annotated `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`. This guarantees the Kafka publish only fires once the DB transaction has actually committed, without requiring an outbox table. Document explicitly (in the same place the "Explanation to have afterward" section lives) that this still isn't full exactly-once — if the app crashes in the gap between commit and publish, the event is lost — and that this is an accepted tradeoff for an activity log (non-critical, replayable-by-inspection data) vs. a financial/critical event stream, where the full outbox pattern would be warranted.

**Warning signs:**
- Any `kafkaTemplate.send(...)` call sitting as a plain statement inside a method also annotated `@Transactional`.
- No test exercises "DB write fails after Kafka event was constructed" or "Kafka publish fails after DB write succeeded" — if you can't point to a test for either failure path, the ordering was never actually verified, just assumed.

**Phase to address:**
Producer/event-publishing phase (the phase that adds `TaskCreatedEvent`/`TaskMovedEvent`/etc. and wires `KafkaTemplate` into the services) — this is a design decision made once, at the point event publishing is introduced, not something to retrofit later.

---

### Pitfall 2: Idempotency check has a race condition — "check then insert" isn't atomic

**What goes wrong:**
The spec's idempotency design is: give each event a UUID `eventId`, and "before inserting, check `existsByEventId(...)`." Implemented literally as `if (!repo.existsByEventId(id)) { repo.save(...) }`, this is a check-then-act race: if the same event is redelivered twice in close succession (a very real scenario during a consumer-group rebalance, see Pitfall 5), both deliveries can pass the `existsByEventId` check before either has committed its insert, and you get two activity-log rows for one logical event — the exact duplication the idempotency check was meant to prevent.

**Why it happens:**
`existsByEventId` + `save` looks idempotent in a single-threaded mental model, but Kafka consumers process concurrently (default listener container concurrency, or simply two redelivery attempts close in time), and a `SELECT`-then-`INSERT` pair is not atomic unless the database enforces it.

**How to avoid:**
Add a `UNIQUE` constraint on `ActivityLogEntity.eventId` at the DB level (not just an application-level existence check), and catch/ignore the resulting `DataIntegrityViolationException` on duplicate insert. The `existsByEventId` pre-check is still worth keeping as a fast path to skip unnecessary work, but the DB constraint is what actually makes the operation safe — the application-level check alone is not a correctness guarantee, only an optimization.

**Warning signs:**
- `eventId` column defined without a unique index/constraint in the entity or DDL.
- No test simulates concurrent/duplicate delivery of the same `eventId` (a single-threaded "call the consumer method twice in a row" test does not exercise the race — needs either a real concurrent test or an explicit DB-constraint-violation test).

**Phase to address:**
Consumer/idempotency phase (the phase adding `ActivityLogConsumer`, `ActivityLogEntity`, `ActivityLogRepository`).

---

### Pitfall 3: `KafkaTemplate.send()` failures are silently swallowed

**What goes wrong:**
`KafkaTemplate.send()` is asynchronous by default and returns a `CompletableFuture` (older Spring Kafka: `ListenableFuture`). If the calling code just invokes `.send(...)` and moves on without attaching a failure callback or blocking on the future, any publish failure (broker unreachable, serialization error, topic authorization failure) disappears silently — the enclosing service method returns normally, the HTTP response is 200, and no activity-log event was ever actually sent. This is especially easy to miss here because the failure mode looks identical to success in every test that doesn't specifically assert on it.

**Why it happens:**
Fire-and-forget is the default and the "obvious"/minimal-code way to call `send()`; the async contract means errors don't surface as exceptions at the call site the way a synchronous JDBC call would.

**How to avoid:**
Always attach a callback (`.whenComplete((result, ex) -> { if (ex != null) { /* log at minimum */ } })`) to every `kafkaTemplate.send(...)` call, even if the project doesn't yet have metrics/alerting infrastructure — a logged error is the minimum bar so a broker outage doesn't fail silently in production. Do not block on `.get()` in the hot request path unless synchronous confirmation is a deliberate requirement (it defeats the async benefit and adds latency to every mutating request).

**Warning signs:**
- Any `kafkaTemplate.send(...)` call with no `.whenComplete`/`.addCallback` and no assignment of the returned future.
- No log line appears anywhere if you manually stop the Kafka container and exercise a mutation through the API — if nothing errors or logs, the failure is invisible.

**Phase to address:**
Producer/event-publishing phase.

---

### Pitfall 4: Dead-letter topic configured but never verified or monitored

**What goes wrong:**
The spec correctly calls for a `kanban.activity.dlt` dead-letter topic via `DefaultErrorHandler`. Two common half-done states: (1) the DLT is wired up but no retry/backoff policy precedes it, so a single transient failure (e.g. a brief DB blip while persisting the activity log row) immediately dead-letters a perfectly recoverable message; (2) the DLT exists and receives poison messages correctly, but nothing ever reads it or alerts on it, so failures are moved to a quieter place rather than actually handled — the feature "looks done" (a DLT topic exists, code compiles, happy-path test passes) but a poison message in production is invisible forever.

**Why it happens:**
The Spring Kafka reference/tutorial content for `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` demonstrates the wiring, not the operational discipline around it (retry-before-dead-letter tuning, DLT observability). It's also easy to find older tutorials referencing the deprecated `SeekToCurrentErrorHandler` and copy stale patterns.

**How to avoid:**
Configure `DefaultErrorHandler` with a bounded number of retries/backoff (e.g. `FixedBackOff`) before the message is routed to the DLT, and explicitly mark non-retryable exception types (validation/deserialization errors — these will never succeed no matter how many retries) to skip straight to DLT. Since the DLT's default destination resolver keeps the same partition count as the source topic, make sure `kanban.activity.dlt` is created with at least as many partitions as `kanban.activity`. Since this is a portfolio-scope project without existing alerting infra, at minimum write the DLT-consumption test the epic already calls for, and note in the activity-log endpoint or a startup log that DLT depth should be checked manually — don't let "we have a DLT" substitute for "we've verified it actually receives and preserves poison messages."

**Warning signs:**
- No test ever publishes a message that intentionally fails processing and asserts it lands on `kanban.activity.dlt`.
- DLT topic partition count not explicitly set/matched to the source topic.

**Phase to address:**
Consumer/idempotency phase — DLT is inseparable from consumer error-handling design.

---

### Pitfall 5: Auto-commit offsets + non-idempotent side effects amplify duplicate processing during rebalances

**What goes wrong:**
Duplicate delivery during a consumer-group rebalance is normal Kafka behavior, not a rare edge case: if a consumer processed a batch but hadn't yet committed its offset when it lost partition ownership (crash, slow processing exceeding `max.poll.interval.ms`, redeploy), the next owner reprocesses from the last committed offset and redelivers already-handled messages. If offset commit is left on Spring Kafka's default auto-commit behavior, the gap between "processed" and "committed" widens unpredictably, increasing how often this duplicate window is hit — which then depends entirely on Pitfall 2's idempotency check actually being airtight.

**Why it happens:**
Auto-commit is the path of least resistance and works fine in every local/happy-path test; the failure only shows up under real redeployment/rebalance conditions, which single-run local tests don't naturally exercise.

**How to avoid:**
Disable auto-commit (`ENABLE_AUTO_COMMIT_CONFIG=false`) and commit offsets only after the activity-log row has actually been persisted (Spring Kafka's `AckMode.RECORD` or `MANUAL_IMMEDIATE` with the `@KafkaListener` container factory). This narrows — but does not eliminate — the duplicate-delivery window, which is exactly why Pitfall 2's DB-level uniqueness constraint is the real safety net, not offset tuning.

**Warning signs:**
- Consumer container factory left at Spring Boot defaults with no explicit `AckMode` set.
- No test simulates "consumer restarts mid-batch" or "same partition reassigned to a fresh listener" — hard to fully automate, but worth at least a manual verification note.

**Phase to address:**
Consumer/idempotency phase.

---

### Pitfall 6: The dev docker-compose Kafka config gets used as-is in the single-EC2 auto-deploy, wiping state or exposing the broker

**What goes wrong:**
This project auto-deploys `master` to a single EC2 instance on every push. Two distinct docker-compose-related risks apply once Kafka is added to that same compose file:
- **State loss:** Kafka (even single-broker KRaft) is a stateful service that needs its log directory to persist across container recreation. If the compose file relies on the container's ephemeral filesystem (no named volume/bind mount for the Kafka data dir) and the deploy process does `docker compose up` (potentially recreating containers) on every push, every deploy silently resets all topics/offsets/activity-log backlog in Kafka — which won't be noticed until someone asks "where did last week's activity log data go" (the DB rows survive since Postgres is presumably on a persisted volume, but any *un-consumed* Kafka messages at deploy time are gone).
- **Accidental exposure:** a dev-oriented compose file typically binds Kafka's listener to `0.0.0.0:9092` with no auth (`PLAINTEXT`) for local convenience. Deployed unmodified to a public EC2 instance, this can expose the broker to the internet with zero authentication if the EC2 security group happens to allow the port (or if a later change opens it for debugging and is forgotten).

**Why it happens:**
Docker-compose files are typically written and tested for local dev ergonomics first; the "same compose file becomes the prod deploy artifact" pattern (implied by "single-EC2 auto-deploy on push") means dev-convenience defaults ship to production unless explicitly separated.

**How to avoid:**
Give the Kafka service a named volume for its KRaft log directory so `docker compose up` on redeploy doesn't wipe state. If the same compose file is genuinely used for both local dev and the EC2 deploy, either use compose profiles/override files to bind Kafka's port only to the Docker-internal network in the "prod" variant (not published to the host at all — the Spring Boot app talks to it via the Docker network alias, nothing external needs to), or explicitly confirm/document that the EC2 security group does not expose port 9092. Don't defer this to "later" — it's a one-line compose change now vs. a real exposure incident later.

**Warning signs:**
- No `volumes:` entry under the Kafka service in `docker-compose.yml`.
- Kafka's port mapped with `ports: - "9092:9092"` (host-exposed) in the same file used for the EC2 deploy, with no profile/override distinguishing local vs. deployed use.

**Phase to address:**
Local-dev/docker-compose phase (the phase adding `docker-compose.yml`) — but the exposure question specifically should be re-checked again at whatever point this compose file is actually used for the EC2 deploy (may be a separate "ship it" step, not just the initial compose authoring).

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|-----------------|------------------|
| Publish-after-commit via `@TransactionalEventListener` instead of a full outbox table | No new outbox table/relay infra; fits existing `@Transactional` conventions | Small window where app crash between commit and publish loses the event silently (no durable record to replay) | Acceptable for a non-critical, append-only activity log on a portfolio project; not acceptable if activity events ever become the system of record for something business-critical |
| `existsByEventId` app-level check without a DB unique constraint | Simple, obvious code | Race condition under concurrent/duplicate delivery reintroduces the exact duplicate-row bug idempotency was meant to prevent | Never — the DB constraint is cheap to add; skipping it saves no real effort |
| Reusing the same `docker-compose.yml` unmodified for local dev and the EC2 deploy | One file to maintain | Dev-convenience defaults (host-exposed ports, ephemeral storage) ship to the deploy target | Acceptable only if explicitly reviewed once before first prod deploy; not acceptable to leave unreviewed indefinitely |
| Skipping the DLT-consumption test (only testing the happy path) | Saves test-writing time now | Poison-message handling is unverified — a redelivery loop or dropped message in prod is invisible until someone notices missing activity rows | Never for this epic specifically, since the epic spec explicitly names DLT handling as one of the two things worth being able to demonstrate |

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|-----------------|-------------------|
| Spring `@Transactional` + `KafkaTemplate` | Calling `kafkaTemplate.send()` as a plain statement inside the `@Transactional` service method | Publish via `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)` so the Kafka send only happens once the DB transaction actually commits |
| `spring-kafka` `DefaultErrorHandler` | Copying older tutorials that use the deprecated `SeekToCurrentErrorHandler`, or sending everything straight to DLT with no retry/backoff | Use `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` with a bounded `FixedBackOff` before dead-lettering, and explicit non-retryable exception classification |
| `apache/kafka` native KRaft image in docker-compose | Single listener config that "works" for the app container but breaks host-side tools (or vice versa) due to `KAFKA_ADVERTISED_LISTENERS` pointing at the wrong host/port | Configure separate internal (Docker network alias) and external (localhost + mapped port) listeners explicitly |
| Testcontainers Kafka in integration tests | Asserting on the `ActivityLogEntity` row immediately after publishing, before the consumer has had time to receive/process the message | Use polling-style assertions (e.g. Awaitility) that wait for the row to appear, since consumer group formation and message delivery are asynchronous and can take a second or more |
| H2 (unit/integration tests) vs. real Postgres (prod) alongside a real Kafka broker in the same Testcontainers test | Assuming H2's transaction-commit timing behaves identically to Postgres when validating `AFTER_COMMIT` event-listener firing | If the Kafka integration test specifically needs to prove "event fires only after commit," consider running that one test against Testcontainers Postgres rather than H2, since H2 is an approximation of Postgres and transaction-boundary edge cases are exactly where approximations diverge |

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|-----------------|
| Blocking on `kafkaTemplate.send(...).get()` synchronously in the hot request path | Every mutating request (task create/move/delete) gets slower, tail latency spikes if the broker is briefly slow | Use the async callback pattern; only block with a bounded timeout where truly necessary | Noticeable as soon as Kafka has any latency variance — not a "scale" threshold so much as a "first time the broker hiccups" threshold |
| Consumer processing loop occasionally exceeding `max.poll.interval.ms` | Consumer gets kicked from the group as unresponsive, triggers a rebalance, rejoins, repeats — a flapping loop that looks like intermittent "duplicate activity log entries" bug reports | Keep per-record processing fast (a single DB insert should be fine at this scale); if processing ever grows heavier, tune `max.poll.interval.ms`/batch size rather than accepting flapping | At this project's scale (personal/portfolio, low volume) unlikely to occur, but worth a code-review sanity check if the consumer method grows more work over time |
| Single-partition `kanban.activity` topic with per-board ordering assumed but not enforced by a partition key | Two events for the same board can be processed out of order by the consumer if load ever increases and partitions are added later, since default (or unkeyed) partitioning gives no ordering guarantee | Key every published event by `boardId` so all events for one board land on the same partition, preserving per-board order; document that adding partitions later requires care (rehashing changes which partition a given key lands on) | Only matters if the topic is ever given more than one partition — for this scope, a single-partition topic sidesteps the issue entirely, but should be a deliberate choice, not an accident |

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Kafka's local-dev `PLAINTEXT` listener bound to `0.0.0.0` and left published to the host in the same compose file used for the EC2 deploy | Broker reachable from the public internet with zero authentication if the EC2 security group allows the port, allowing anyone to read the entire activity event stream (which includes `userId`s) or inject forged events consumed straight into the activity log | Restrict the deployed variant to Docker-internal networking only (app talks to Kafka via service name, nothing published to the host), or explicitly confirm the EC2 security group blocks 9092 externally |
| Activity log events carrying `userId` and entity IDs consumed and displayed via `GET /boards/{boardId}/activity` without re-verifying ownership at read time | If the consumer ever mis-attributes an event to the wrong `boardId` (e.g. a bug in event construction), the existing `OwnershipVerifierService.verifyOwnershipOfBoard` check on the read endpoint is the only thing preventing cross-user data leakage through the activity feed | Keep the ownership check on the `GET` endpoint (already planned per the epic spec) and additionally validate that the event's `boardId` in the consumer matches the mutation's actual board before persisting, rather than trusting the event body blindly |

## "Looks Done But Isn't" Checklist

- [ ] **Idempotent consumption:** Looks done once `existsByEventId` check + happy-path test pass. Verify: `eventId` has a real DB-level unique constraint, and there's a test that forces a duplicate insert attempt (not just two sequential calls in the same thread) and confirms only one row results.
- [ ] **Dead-letter topic:** Looks done once the topic exists and code references `DeadLetterPublishingRecoverer`. Verify: an actual test publishes a message engineered to fail processing and asserts it lands on `kanban.activity.dlt` with the expected content — not just that the happy path avoids the DLT.
- [ ] **Event publishing after mutation:** Looks done once `kafkaTemplate.send(...)` compiles and a happy-path integration test shows the activity row appearing. Verify: there's a test (or at minimum documented manual verification) for the DB-commit-then-publish-fails case and the publish-then-DB-rollback case — the two dual-write failure modes from Pitfall 1.
- [ ] **`docker-compose.yml`:** Looks done once `docker compose up` gives a working local environment. Verify: Kafka's data directory is on a named volume (survives `docker compose down && up`), and the file has been reviewed once specifically for what happens when it's the artifact used by the EC2 auto-deploy (port exposure, container recreation on every push).
- [ ] **Testcontainers Kafka integration test:** Looks done once one green test exists ("publish `TaskMovedEvent`, assert row appears"). Verify: the assertion uses polling/await rather than an immediate check, since a flaky-looking assertion here is usually a timing bug, not a real regression, and will erode trust in the test if left as a hard sleep or immediate assert.
- [ ] **`PATCH /tasks/{taskId}/move`:** Looks done once it moves the task and publishes `TaskMovedEvent`. Verify: it follows the same explicit client-supplied-`@Version` optimistic-locking discipline established for `TaskService.updateById` in the prior phase — a new mutating endpoint that skips this check reintroduces the exact concurrent-edit bug that phase closed out, on the one entity path most likely to receive concurrent moves (drag-and-drop UIs from multiple tabs/users).

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|----------------|-----------------|
| Duplicate activity-log rows discovered in production (Pitfall 2 or 5) | LOW | Add the missing unique constraint on `eventId`, run a one-off cleanup query to dedupe existing rows (keep earliest `createdAt` per `eventId`), redeploy |
| Ghost or lost activity events discovered (Pitfall 1) | MEDIUM | Retrofit `@TransactionalEventListener(AFTER_COMMIT)` around existing `kafkaTemplate.send()` calls; audit recent activity-log rows against actual entity state for a sanity check of how much drift accumulated |
| Kafka broker state wiped by a deploy (Pitfall 6, state-loss case) | LOW | Add the missing named volume; since the activity log is supplementary (not the system of record — Postgres holds the real board/task/column state), lost in-flight Kafka messages are an acceptable, recoverable loss, not a data-integrity emergency |
| Broker briefly exposed publicly (Pitfall 6, exposure case) | MEDIUM | Immediately restrict the security group / compose network binding; since this is a portfolio project with synthetic data, treat as a config-hygiene incident rather than a real breach, but still fix and document the fix |

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|-------------------|----------------|
| Dual-write / ghost events from publishing inside `@Transactional` (Pitfall 1) | Producer/event-publishing phase | Test asserts no Kafka event is published when the same service method's DB write fails/rolls back |
| Idempotency race condition (Pitfall 2) | Consumer/idempotency phase | `eventId` has a DB unique constraint; a duplicate-insert test confirms exactly one row survives |
| Silently swallowed publish failures (Pitfall 3) | Producer/event-publishing phase | Code review confirms every `kafkaTemplate.send()` has an attached failure callback; manually stopping the broker during a mutation produces a visible log line |
| DLT configured but unverified (Pitfall 4) | Consumer/idempotency phase | A test publishes a deliberately-failing message and asserts it lands on `kanban.activity.dlt` |
| Rebalance-driven duplicate delivery (Pitfall 5) | Consumer/idempotency phase | Consumer container factory has an explicit `AckMode` (not default auto-commit) |
| Compose state loss / broker exposure on EC2 deploy (Pitfall 6) | Local-dev/docker-compose phase, re-checked at deploy time | `docker-compose.yml` has a named volume for Kafka's log dir; port-exposure reviewed against the EC2 security group before the compose file is used for the real deploy |
| Testcontainers assertion timing flakiness | Testing phase | Integration test uses polling/await, not immediate assertion or a hard sleep |
| `PATCH /tasks/{taskId}/move` skipping optimistic-locking discipline | Producer/event-publishing phase (where the move endpoint is added) | New endpoint follows the same explicit client-supplied `@Version` check pattern as `TaskService.updateById` |

## Sources

- [Dual Write Problem: Safe in Code but Breaks in Production](https://dzone.com/articles/dual-write-problem-what-looks-safe-in-code) — web, LOW confidence
- [Exactly-Once Processing Across Kafka and Databases: Using the Outbox Pattern](https://medium.com/threadsafe/exactly-once-processing-across-kafka-and-databases-using-the-outbox-pattern-f08fd640f683) — web, LOW confidence
- [A Use Case for Transactions: Outbox Pattern Strategies in Spring Cloud Stream Kafka Binder (spring.io)](https://spring.io/blog/2023/10/24/a-use-case-for-transactions-adapting-to-transactional-outbox-pattern/) — web, LOW confidence (official Spring blog, treat as more reliable than the median result)
- [Build Idempotent Kafka Consumers: Patterns That Actually Work | Conduktor](https://www.conduktor.io/blog/building-idempotent-consumers) — web, LOW confidence
- [Idempotent Processing with Kafka | Nejc Korasa](https://nejckorasa.github.io/posts/idempotent-kafka-procesing/) — web, LOW confidence
- [Message Delivery Guarantees for Apache Kafka | Confluent Documentation](https://docs.confluent.io/kafka/design/delivery-semantics.html) — web, LOW confidence (official Confluent docs, treat as more reliable than the median result)
- [Dead Letter Topics: Routing Failed Messages with DeadLetterPublishingRecoverer | Devops Monk](https://blog.devops-monk.com/tutorials/spring-kafka/dead-letter-topics/) — web, LOW confidence
- [DeadLetterPublishingRecoverer (Spring for Apache Kafka API docs)](https://docs.spring.io/spring-kafka/api/org/springframework/kafka/listener/DeadLetterPublishingRecoverer.html) — official docs, higher confidence than general web results
- [Handling Exceptions :: Spring Kafka reference docs](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html) — official docs, higher confidence than general web results
- [Testing Kafka Applications: Testcontainers, Embedded Kafka, and Mocks | Conduktor](https://www.conduktor.io/blog/testing-kafka-testcontainers-embedded-mocks) — web, LOW confidence
- [Testing Kafka and Spring Boot | Baeldung](https://www.baeldung.com/spring-boot-kafka-testing) — web, LOW-MEDIUM confidence (Baeldung is a generally reliable Spring-ecosystem source)
- [Spring Framework and Apache Kafka® - Sending Messages to Apache Kafka with Spring Boot (Confluent Developer)](https://developer.confluent.io/courses/spring/send-messages/) — official/vendor course content, higher confidence
- [Running Apache Kafka® KRaft on Docker: Tutorial and best practices | Instaclustr](https://www.instaclustr.com/education/apache-spark/running-apache-kafka-kraft-on-docker-tutorial-and-best-practices/) — web, LOW confidence
- [Developing event-driven applications with Kafka and Docker | Docker Docs](https://docs.docker.com/guides/kafka/) — official Docker docs, higher confidence
- [AWS Kafka - Guide to Design & Kafka Deployment Considerations | Confluent](https://www.confluent.io/blog/design-and-deployment-considerations-for-deploying-apache-kafka-on-aws/) — vendor blog, LOW-MEDIUM confidence
- [What Actually Happens Inside a Kafka Consumer Group Rebalance | Medium](https://medium.com/@phoenixarjun007/what-actually-happens-inside-a-kafka-consumer-group-rebalance-and-why-it-causes-lag-spikes-998cee8dd283) — web, LOW confidence
- [Kafka's Duplicate Message Problem — William](https://williamngeow.com/blog/kafkas-duplicate-message-problem) — web, LOW confidence
- [Kafka Ordering Guarantees: What's Promised and What Isn't](https://pulse.support/kb/kafka-ordering-guarantees) — web, LOW confidence
- [The Art of Kafka Partitioning: Keys, Load Balancing, and Best Practices | Medium](https://dev-aditya.medium.com/kafka-producer-key-management-patterns-pitfalls-and-best-practices-c159eb5bcf8f) — web, LOW confidence
- Project-specific reasoning (existing `@Transactional` conventions, `@Version` optimistic-locking pattern, single-EC2 auto-deploy) synthesized from `.planning/PROJECT.md` and `docs/plans/backend-modernization/01-kafka-activity-feed.md`, not externally sourced.

**Note on confidence:** All web findings here were fetched via general web search with no MCP research provider (Context7/Exa/Tavily/etc.) configured in this environment, so every claim is tagged LOW per the classify-confidence seam except where explicitly marked as official vendor/framework documentation (Spring, Confluent, Docker), which is somewhat more reliable but still not cross-verified against a second independent source. Treat the specific numeric/version claims (e.g. exact Spring Kafka error-handler class names, `max.poll.interval.ms` defaults) as directionally correct but worth a quick doc-check against the actual `spring-kafka` version pinned in `build.gradle` before implementation.

---
*Pitfalls research for: Kafka event-driven activity feed added to an existing transactional Spring Boot CRUD app*
*Researched: 2026-08-01*
