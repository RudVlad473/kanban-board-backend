# Quick Task 260802-ryf: Enable virtual threads in Spring Boot config — Research

**Researched:** 2026-08-02
**Domain:** JVM concurrency / Spring Boot 3.5 runtime configuration
**Confidence:** MEDIUM-HIGH (in-repo facts HIGH; upstream library state MEDIUM, sourced from upstream issue trackers)

## Summary

**Verdict: DO NOT ENABLE YET.**

Five of the six risks the todo names were checked concretely against this repo. Four came back clean or
better-than-expected — one of them (Spring Session JDBC) turns out not to exist in this app at all, and
another (synchronous Kafka publish in the request path) was already fixed in Phase 2/3 and the todo's
premise is stale. The remaining one is a hard blocker: **HikariCP 6.3.0 — the version Spring Boot 3.5.0
pins — is explicitly named in an open, unfixed upstream issue as saturating every carrier thread under
moderate virtual-thread load on JDK 21.** The fix landed only in HikariCP 7.1.0, which this project cannot
reach without overriding a Boot-managed version.

Separately, the whole exercise has no measured motivation. There is no load-test harness in this repo, no
observed throughput ceiling, and the Testcontainers E2E suite is functional (single-threaded fixture
flows), so it cannot surface pinning even with diagnostics enabled. Flipping this flag would be an
unmeasured change with a known live upstream defect on the exact version in play.

**Primary recommendation:** Leave `spring.threads.virtual.enabled` unset. Re-evaluate when the Java 21→25
/ Spring Boot 3.5→4.x upgrade lands (todo `2026-08-01-bump-java-version-from-21-to-25-current-lts.md`),
which resolves *both* remaining blockers at once — JEP 491 removes `synchronized` pinning, and Boot 4.x
carries HikariCP ≥ 7.1.0.

## Resolved Versions (this repo)

Resolved via `./gradlew -q dependencies --configuration runtimeClasspath` this session.

| Artifact | Version | Verdict for virtual threads |
|---|---|---|
| Java toolchain | 21 `[VERIFIED: build.gradle:12-16 — "languageVersion = JavaLanguageVersion.of(21)"]` | No JEP 491 — `synchronized` still pins |
| `com.zaxxer:HikariCP` | **6.3.0** (Boot-managed) `[VERIFIED: gradle runtimeClasspath]` | **BLOCKER — see below** |
| `org.postgresql:postgresql` | **42.7.5** `[VERIFIED: gradle runtimeClasspath]` | Clean |
| `org.springframework.kafka:spring-kafka` | 3.3.6 `[VERIFIED: gradle runtimeClasspath]` | Clean for the producer path here |
| `org.apache.kafka:kafka-clients` | 3.9.1 `[VERIFIED: gradle runtimeClasspath]` | Not on request path (see #4) |
| `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.41 `[VERIFIED: gradle runtimeClasspath]` | Supports virtual-thread executor |
| `org.springframework.session:*` | **absent — 0 artifacts on runtimeClasspath** `[VERIFIED: gradle runtimeClasspath grep count = 0]` | Risk does not exist |

## Findings Against the Six Named Risks

### 1. `synchronized` in `src/main` — exactly one hit, and it guards nothing

A `Grep` for `synchronized` across all of `src/` returned **one** match in the entire tree:

```
src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java:24:    public synchronized String generateRandflake() {
```

`[VERIFIED: src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java:24]`

Zero hits in `controller/`, `service/`, `security/`, `activitylog/`, `event/`, or `handler/`.

**Is it on the request path?** Yes — it is the Hibernate `IdentifierGenerator` for every entity insert
(`BaseEntity`), so every board/column/task/subtask/user create goes through this monitor.

**Does it pin?** No, and it also isn't needed. The full method body reads only `static final` fields and
allocates locals:

```java
long timestamp = Instant.now().toEpochMilli() - CUSTOM_EPOCH;
long randomBits = ThreadLocalRandom.current().nextLong(1L << RANDOM_BITS);
long id = (timestamp << RANDOM_BITS) | randomBits;
return Long.toString(id, 36);
```
`[VERIFIED: src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java:25-32]`

There is **no shared mutable state** — no counter, no last-timestamp field, nothing a Snowflake-style
generator would normally need the lock for. `ThreadLocalRandom` is per-thread by construction. The
critical section is a few nanoseconds of arithmetic with no blocking call inside it, so a virtual thread
never *parks* while holding this monitor — which is the condition that actually pins a carrier on JDK 21.

The only residual effect is monitor *contention*: on JDK 21, a virtual thread waiting to enter a
`synchronized` block blocks its carrier rather than unmounting. With a nanosecond-scale critical section
and a single-digit carrier pool that is negligible, but it is a global serialization point on every
insert that buys nothing. `[ASSUMED — reasoning from the JDK 21 monitor semantics below, not measured]`

**Actionable regardless of this decision:** the `synchronized` keyword on line 24 is removable with zero
semantic change. That is a clean, independently defensible one-line change and does not need virtual
threads to justify it.

JDK 21–23 pin a virtual thread's carrier whenever the thread blocks inside `synchronized` or contends on
a monitor, because monitor ownership is tracked at the carrier level. JEP 491 (JDK 24) re-tracks ownership
per virtual thread and removes this entirely, with no code changes required.
`[CITED: https://openjdk.org/jeps/491]` **This project is on Java 21, so it gets none of that.**

### 2. HikariCP 6.3.0 — THE BLOCKER

Boot 3.5.0 pins HikariCP **6.3.0**. The upstream situation on that line is worse than the "HikariCP is fine
under virtual threads" folklore suggests:

- **PR #2055** ("Add support for Virtual Threads" — replace `synchronized` with `ReentrantLock`) was
  **closed, not merged**; maintainers chose to wait for JDK 24's JEP 491 to solve it at the JVM level.
  `[CITED: https://github.com/brettwooldridge/HikariCP/pull/2055]`
- **Issue #2293** documents a real carrier-exhaustion freeze on `HikariPool` initialisation under virtual
  threads: *"9/10 - blocked on synchronized & 1/10 - parking on carrier thread, waiting for 'not fair
  lock' will be released."* `[CITED: https://github.com/brettwooldridge/HikariCP/issues/2293]`
- **Issue #2398 (the decisive one)** — `ConcurrentBag.requite()` contains a yield-spin loop whose
  `parkNanos` fallback only fires once every 256 iterations. Under **moderate** virtual-thread load all
  carriers plus the housekeeper burn CPU in that loop, causing pod-level saturation and liveness-probe
  failures. Reported on **JDK 21.0.10**, against **6.3.0+ through 7.0.2**, and the reporter explicitly
  distinguishes it from `synchronized` pinning: *"JDK 24 fixes this"* refers to a **different** problem.
  The only documented workaround is disabling virtual threads.
  `[CITED: https://github.com/brettwooldridge/HikariCP/issues/2398]`
- The fix is **HikariCP 7.1.0**: *"merged #2402 avoid virtual-thread yield spin in ConcurrentBag."*
  `[CITED: https://github.com/brettwooldridge/HikariCP/blob/dev/CHANGES]`

**Second, independent Hikari concern:** `application.properties` sets **no** `spring.datasource.hikari.*`
properties `[VERIFIED: src/main/resources/application.properties:12-15 — only `spring.datasource.url`,
`.username`, `.password`]`, so `maximum-pool-size` is Hikari's default of 10. Virtual threads remove the
Tomcat thread ceiling that currently acts as an implicit admission-control valve; the pool becomes the
only bound, and the classic failure mode is thousands of virtual threads queueing on 10 connections and
timing out. `[CITED: https://blogs.pavanrangani.com/spring-boot-virtual-threads-production/]` Any future
"enable it" plan must tune pool size and `connection-timeout` in the same change, not after.

### 3. Spring Session JDBC — the risk does not exist in this app

`application.properties` configures it:

```
spring.session.timeout=180m
spring.session.store-type=jdbc
spring.session.jdbc.initialize-schema=always
```
`[VERIFIED: src/main/resources/application.properties:22-24]`

But **no `org.springframework.session` artifact is on the runtime classpath** — a grep count over the
resolved `runtimeClasspath` returns `0` `[VERIFIED: gradle runtimeClasspath]`. The only JDBC artifacts
present are `spring-boot-starter-jdbc:3.5.0` / `spring-jdbc:6.2.7`, pulled transitively by
`spring-boot-starter-data-jpa`. Security uses a plain `HttpSessionSecurityContextRepository`
`[VERIFIED: src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java:38]`, i.e.
Tomcat's in-memory `HttpSession`.

So those three properties are **inert** — no `spring_session` table is being written, and there is no
per-request session JDBC round trip to pin on. The virtual-threads risk the todo raises here is zero.

> **Side finding, out of scope for this task but worth a todo:** the app believes it has JDBC-backed
> sessions and does not. The `CLAUDE.md` architecture notes ("Session persistence: All sessions stored in
> PostgreSQL spring_session table… allows horizontal scaling") and the comment at
> `UserAuthenticationProvider.java:35` are both describing behaviour that isn't wired. That is a real
> correctness/documentation drift issue independent of virtual threads.

### 4. Kafka producer path — todo premise is stale; already off the request thread

The todo says "synchronous Kafka publish calls in the request path." That was true when the todo was
filed but was fixed during Phase 3. `KafkaEventPublisher` is dispatched off-thread:

```java
@Async("kafkaPublishExecutor")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onActivityEvent(ActivityEvent event) {
```
`[VERIFIED: src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java:38-40]`

onto a bounded platform-thread pool:

```java
executor.setCorePoolSize(2);
executor.setMaxPoolSize(4);
executor.setQueueCapacity(200);
executor.setThreadNamePrefix("kafka-publish-");
```
`[VERIFIED: src/main/java/com/vrudenko/kanban_board/config/AsyncConfig.java:20-24]`

`KafkaTemplate.send()`'s `waitOnMetadata` block therefore never touches an HTTP request thread, virtual or
not, and producer timeouts are already clamped to 2000 ms
`[VERIFIED: src/main/resources/application.properties:45-47 — "max.block.ms=2000",
"request.timeout.ms=2000", "delivery.timeout.ms=2000"]`.

**Non-obvious interaction, and it cuts in this project's favour:** Spring Boot's docs state *"when a
custom `Executor` bean is registered, the auto-configured `AsyncTaskExecutor` backs off"*
`[CITED: https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html]`.
`kafkaPublishExecutor` is exactly such a bean, so enabling the flag would **not** convert this pool to
virtual threads — `@Async("kafkaPublishExecutor")` names it explicitly and it stays a bounded
`ThreadPoolTaskExecutor`. The deliberate concurrency bound survives. Good, but it also means the flag buys
this path nothing.

**What the flag *would* move:** the Kafka **listener** container gets a virtual-thread executor
`[CITED: https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html]`.
`ActivityLogConsumer` uses `@KafkaListener(topics = KafkaTopics.ACTIVITY, groupId = ...)` with no
`concurrency` attribute `[VERIFIED: src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java:36]`
and the topic has `.partitions(1)` `[VERIFIED: src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java:47-51]`,
so effective listener concurrency is 1. Low risk — but that single listener does JPA persistence, so it
would still pull from the same HikariCP 6.3.0 pool discussed above.

### 5. PostgreSQL JDBC 42.7.5 — clean

pgjdbc replaced virtually all `synchronized` methods (including `QueryExecutorImpl`'s query-execution path,
the historically-cited pinning source) with `ReentrantLock` in **42.6.0**, explicitly for Loom.
`[CITED: https://github.com/quarkusio/quarkus/discussions/33325]` A later regression from pgjdbc PR #3703
reintroduced pinning in **42.7.8**: *"has been occurring since version 42.7.8; previous versions were fine."*
`[CITED: https://github.com/quarkusio/quarkus/issues/50345]`

**42.7.5 sits in the clean window (≥ 42.6.0, < 42.7.8).** Note for the future: this is a reason *not* to
float the driver version blindly if virtual threads are ever enabled.

Worth stating plainly, since the todo frames it as an open question: pure socket I/O does not pin. Only
blocking *inside* a `synchronized` region does. Modern pgjdbc's `ResourceLock`/`ReentrantLock` approach is
precisely the fix for that.

### 6. Observing pinning — the existing test suite cannot do it

- `-Djdk.tracePinnedThreads=full` works on JDK 21 but has known JVM hangs (JDK-8322846: `onPinned` runs
  while the virtual thread is in a transition state) and was **removed** in JDK 24.
  `[CITED: https://bugs.openjdk.org/browse/JDK-8322846]`
- The supported replacement is the JFR `jdk.VirtualThreadPinned` event — enabled by default for blocking
  operations exceeding 20 ms, carrying both the pinning reason and the carrier thread identity.
  `[CITED: https://www.infoq.com/articles/virtual-threads-after-jdk24/]`

**But neither helps here.** The Testcontainers/Kafka E2E suite exercises functional correctness through
sequential REST Assured fixture flows; it generates no concurrency. Pinning and carrier saturation are
*load-dependent* failure modes — issue #2398's symptom explicitly requires "moderate virtual thread load."
Running the existing suite with diagnostics on would produce a clean result that means nothing.

Getting a real answer requires a load harness (k6/Gatling/JMeter against a running stack) that does not
exist in this repo and is well outside a quick task. `[ASSUMED — based on surveying the test tree layout;
no load-testing dependency appears in build.gradle]`

## Decision

| Option | Assessment |
|---|---|
| **ENABLE** | No. HikariCP 6.3.0 is in the affected range of an open, unfixed carrier-saturation issue on JDK 21. |
| **ENABLE-WITH-CAVEATS** (override `hikaricp.version` to 7.1.0+, tune pool, load test) | Technically possible via `ext['hikaricp.version'] = '7.1.0'`, but that de-pins a Boot-managed transitive dependency, needs pool tuning *and* a new load harness, and still leaves `synchronized` pinning unaddressed on Java 21. Far past quick-task scope, and unjustified with no measured throughput problem. |
| **DO-NOT-ENABLE-YET** | **Recommended.** |

### Recommended plan shape

A "leave it disabled, here's why, here's the trigger" plan:

1. **No change to `application.properties`.** Do not add the flag.
2. **Update the todo** (`.planning/todos/pending/2026-08-02-enable-virtual-threads-in-spring-boot-config.md`)
   with the findings and the re-evaluation trigger, rather than deleting it.
3. **Re-evaluation trigger (both conditions, not either):**
   - Java ≥ 24 (JEP 491 removes `synchronized` pinning), **and**
   - HikariCP ≥ 7.1.0 on the runtime classpath (fixes the `ConcurrentBag` yield-spin).

   The existing Java 21→25 / Spring Boot 3.5→4.x upgrade todo satisfies both in one move — this todo
   should be marked as **blocked on** that one.
4. **Additional gate at that point:** a measured baseline. Without a load harness there is no way to show
   the change helped, and no way to detect the failure modes above.
5. **Optional adjacent cleanup, independent of this decision:** drop the `synchronized` modifier from
   `RandFlakeGenerator.generateRandflake()` (line 24). It protects no shared mutable state and is a global
   serialization point on every entity insert. Worth doing on its own merits, but should be its own
   trivially-reviewable change, not smuggled in under a virtual-threads banner.

## Package Legitimacy Audit

**Not applicable — no external packages are introduced by this task.** The recommendation adds no
dependency; the deferred alternative would only re-pin an already-present, Boot-managed artifact
(`com.zaxxer:HikariCP`).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|---|---|---|
| A1 | Monitor *contention* on `RandFlakeGenerator`'s tiny critical section is practically negligible at this app's concurrency | Finding 1 | Low — the recommendation is "don't enable," so this is not load-bearing |
| A2 | No load-testing harness exists in the repo (inferred from `build.gradle` dependency list, not an exhaustive tree scan) | Finding 6 | Low — would only mean step 4 is cheaper than stated |
| A3 | HikariCP issue #2398 remains unfixed on the 6.x line as of 2026-08-02 (issue read this session, but no separate check for a 6.x backport) | Finding 2 | Medium — a 6.3.x backport would soften the blocker, though JDK 21 `synchronized` pinning would still argue for waiting |

## Open Questions

1. **Does Spring Boot 3.5.x allow a clean HikariCP 7.x override?**
   - Known: `ext['hikaricp.version']` is the standard `io.spring.dependency-management` override hook.
   - Unclear: whether HikariCP 7.x's baseline is API-compatible with Boot 3.5's `DataSourceBuilder`.
   - Recommendation: not worth resolving now — the upgrade path makes it moot.

2. **Is the dead Spring Session JDBC config intentional?**
   - Known: properties are set, artifact is absent, docs describe it as active.
   - Recommendation: file as a separate todo. It is a genuine issue (single-node-only sessions, docs drift)
     and has nothing to do with virtual threads.

## Sources

### Primary (documentation / upstream trackers)
- https://openjdk.org/jeps/491 — JEP 491 `synchronized` pinning removal, JDK 24
- https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html — what the flag switches; `Executor` bean back-off
- https://github.com/brettwooldridge/HikariCP/issues/2398 — `ConcurrentBag.requite()` yield-spin, 6.3.0+/7.0.2, JDK 21.0.10
- https://github.com/brettwooldridge/HikariCP/issues/2293 — pool-init carrier exhaustion under virtual threads
- https://github.com/brettwooldridge/HikariCP/pull/2055 — closed ReentrantLock PR
- https://github.com/brettwooldridge/HikariCP/blob/dev/CHANGES — 7.1.0 "merged #2402 avoid virtual-thread yield spin in ConcurrentBag"
- https://github.com/quarkusio/quarkus/issues/50345 — pgjdbc 42.7.8 pinning regression
- https://github.com/quarkusio/quarkus/discussions/33325 — pgjdbc pre-42.6.0 pinning
- https://bugs.openjdk.org/browse/JDK-8322846 — `-Djdk.tracePinnedThreads` hangs

### Secondary
- https://www.infoq.com/articles/virtual-threads-after-jdk24/ — JFR `jdk.VirtualThreadPinned`
- https://blogs.pavanrangani.com/spring-boot-virtual-threads-production/ — connection-pool exhaustion as the dominant production failure mode

### In-repo (read this session)
- `build.gradle`, `src/main/resources/application.properties`
- `src/main/java/com/vrudenko/kanban_board/config/{RandFlakeGenerator,KafkaEventPublisher,AsyncConfig,KafkaConsumerConfig}.java`
- `./gradlew -q dependencies --configuration runtimeClasspath` (version resolution + Spring Session absence)
- `Grep "synchronized" src/` (one hit, whole tree)

## Metadata

**Confidence breakdown:**
- In-repo facts (grep results, resolved versions, bean wiring): **HIGH** — every claim read from source or a tool run this session
- HikariCP 6.3.0 status: **MEDIUM** — sourced from upstream issue tracker, not a maintainer release note; issue state is a moving target
- pgjdbc 42.7.5 clean-window boundary: **MEDIUM** — inferred from "previous versions were fine" plus the 42.6.0 lock migration, not a per-version test
- Recommendation: **HIGH** — holds even if A3 is wrong, since Java 21 `synchronized` pinning and the absence of any measured throughput problem are independently sufficient

**Research date:** 2026-08-02
**Valid until:** ~2026-09-01 (re-check HikariCP issue #2398 and the Boot-managed HikariCP version at that point)
