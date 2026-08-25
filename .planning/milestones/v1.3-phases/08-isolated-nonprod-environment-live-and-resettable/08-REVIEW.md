---
phase: 08-isolated-nonprod-environment-live-and-resettable
reviewed: 2026-08-18T00:00:00Z
depth: standard
files_reviewed: 13
files_reviewed_list:
  - docker-compose.nonprod.yml
  - docker-compose.prod.yml
  - Caddyfile
  - .env.nonprod.example
  - .gitignore
  - src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java
  - src/main/java/com/vrudenko/kanban_board/service/ResetService.java
  - src/main/java/com/vrudenko/kanban_board/service/ResetTruncateService.java
  - src/main/java/com/vrudenko/kanban_board/controller/ResetController.java
  - src/main/java/com/vrudenko/kanban_board/security/NonprodResetSecurityConfiguration.java
  - src/test/java/com/vrudenko/kanban_board/e2e/reset/ResetServiceE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/e2e/reset/ResetControllerE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/security/ResetEndpointProfileGatingTest.java
findings:
  critical: 0
  warning: 3
  info: 2
  total: 5
status: issues_found
---

# Phase 08: Code Review Report

**Reviewed:** 2026-08-18
**Depth:** standard
**Files Reviewed:** 13
**Status:** issues_found

## Summary

Reviewed the infra-config half of phase 08 (docker-compose.nonprod.yml, docker-compose.prod.yml,
Caddyfile, .env.nonprod.example, .gitignore) and the reset-endpoint half (ResetService,
ResetTruncateService, ResetController, NonprodResetSecurityConfiguration, and their three test
classes). Read the phase's three PLAN.md files and three SUMMARY.md files, including each plan's own
STRIDE threat register, to avoid re-litigating decisions the plans already reasoned about and
accepted (separate Compose project vs. in-place profile extension, provisional-then-measured memory
caps, the two-control profile+token defense-in-depth on the reset endpoint, `required = false` on the
token header to avoid a presence oracle, `deleteRecords()` vs. topic delete/recreate).

The security-critical properties the plan set out to prove — profile gating (bean does not exist
outside `nonprod`), constant-time token comparison, absent-header/wrong-token indistinguishability,
`flyway_schema_history` exclusion, and production's `SecurityConfiguration.java` staying untouched —
all check out by direct code reading, matching what the live verification in `docs/INFRA_RUNBOOK.md`
and the E2E test suite claim. No hardcoded secrets, no injection vectors (all SQL in
`ResetTruncateService` is a static string with no user input), no `eval`/dangerous-function usage,
and no empty catch blocks were found.

One real correctness gap was found in `ResetService.resetAll()`'s listener-restart guarantee (WR-01
below): the class's own Javadoc and the phase's threat register (T-08-18) both claim listeners "come
back even if a truncate throws," but the code's `try/finally` only wraps the truncate calls, not the
initial listener-stop loop — a failure while stopping a container is not covered by the restart
guarantee at all. Two further Warnings and two Info items round out the findings; none are Critical.

## Warnings

### WR-01: Listener-restart guarantee does not cover a failure in the initial stop loop

**File:** `src/main/java/com/vrudenko/kanban_board/service/ResetService.java:66-79`
**Issue:** `resetAll()`'s class-level Javadoc states the fourth step "restart every listener
container" runs in a `finally` block "so the listeners come back even if a truncate throws," and the
phase's own threat register (08-02-PLAN.md, T-08-18) makes the same claim ("The restart runs in a
`finally`"). Looking at the actual control flow:

```java
public void resetAll() {
    kafkaListenerEndpointRegistry
            .getListenerContainers()
            .forEach(container -> container.stop());   // NOT inside the try

    try {
        truncateActivityTopics();
        resetTruncateService.truncateAll();
    } finally {
        kafkaListenerEndpointRegistry
                .getListenerContainers()
                .forEach(container -> container.start());
    }
}
```

The initial `container.stop()` loop runs *before* the `try` block, not inside it. If `.stop()` throws
on any container — a real possibility if a container is already in a transitional state, if the
configured shutdown timeout is exceeded, or once a second `@KafkaListener` is added (the Javadoc and
the plural "every listener container" phrasing anticipate more than one) — the exception propagates
straight out of `resetAll()` and the `finally` block is never reached, so no container is ever
restarted. Today there is exactly one `@KafkaListener` in the app
(`ActivityLogConsumer`), which bounds the practical blast radius, but the documented invariant is
false as written, and the failure mode it exists to prevent (T-08-18: "listener containers left
stopped after a failed reset") is exactly the one this gap reopens for a stop-time failure rather
than a truncate-time one.
**Fix:** Move the stop loop inside the `try`, or wrap the whole method in its own `try/finally` so
every path restarts the listeners:
```java
public void resetAll() {
    try {
        kafkaListenerEndpointRegistry.getListenerContainers().forEach(container -> container.stop());
        truncateActivityTopics();
        resetTruncateService.truncateAll();
    } finally {
        kafkaListenerEndpointRegistry.getListenerContainers().forEach(container -> container.start());
    }
}
```

### WR-02: `AdminClient` calls in `truncateActivityTopics()` carry no explicit timeout override

**File:** `src/main/java/com/vrudenko/kanban_board/service/ResetService.java:89-121`
**Issue:** `AdminClient.create(kafkaAdmin.getConfigurationProperties())` is constructed with no
`AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG` / `DEFAULT_API_TIMEOUT_MS_CONFIG` override, and neither
`listOffsets(...).get()` nor `deleteRecords(...).all().get()` is called with an explicit timeout
argument. This falls back to the Kafka client defaults (`request.timeout.ms` = 30s,
`default.api.timeout.ms` = 60s), applied per call, per topic, twice (once for `ACTIVITY`, once for
`ACTIVITY_DLT`). In a broker-connectivity-degraded scenario, a single `POST /admin/reset` call — which
runs synchronously on the servlet request thread with no async/timeout wrapper at the controller level
— can block for a large multiple of a minute before the exception finally surfaces, holding a request
thread the whole time. The test file itself demonstrates the fix is trivial
(`ResetServiceE2ETest.should_propagateFailure_when_databaseTruncateFails` sets
`REQUEST_TIMEOUT_MS_CONFIG`/`DEFAULT_API_TIMEOUT_MS_CONFIG` to `1000` on its own throwaway
`AdminClient`), but production code never applies the same tightened timeout.
**Fix:** Pass explicit, short timeouts on the two blocking calls (or set
`AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG` when building the properties map), e.g.:
```java
admin.listOffsets(Map.of(partition, OffsetSpec.latest()))
        .partitionResult(partition)
        .get(5, TimeUnit.SECONDS);
```

### WR-03: `ResetTruncateService`'s single-statement TRUNCATE list ordering has no defensive comment on why CASCADE is required, and the eight hardcoded table names have no compile-time link back to the schema

**File:** `src/main/java/com/vrudenko/kanban_board/service/ResetTruncateService.java:52-56`
**Issue:** The eight table names (`users, boards, columns, tasks, subtasks, activity_log,
spring_session_attributes, spring_session`) are a hand-typed literal string with no reference to a
single source of truth (e.g., a constants list, or a runtime `information_schema` enumeration minus an
exclude-list). This is a real, if narrow, drift risk: `V*.sql` Flyway migrations are the actual owner
of table names, and a future migration that adds a ninth domain table (or renames one of the existing
eight) would compile and pass every existing test silently while leaving that table permanently
un-truncated by reset — a partial, silently "successful" reset that specifically violates this plan's
own transparency prohibition ("MUST NOT return a success status when only one of the two stores was
actually cleared" — this is the same failure mode one level down, at the single-store granularity).
Today's `E2E` test (`should_emptyBothStores_when_resetAllCalledAfterRealTraffic`) only asserts the
eight tables it already knows about are empty; it cannot catch a ninth table the developer forgot to
add to both places.
**Fix:** At minimum, add a code comment near the migrations (`V*.sql`) pointing back at this literal
list so a future migration author is warned; consider deriving the truncate list from
`information_schema.tables` at runtime excluding `flyway_schema_history`, so a new table is included
by construction rather than by remembering to update a second file.

## Info

### IN-01: `ResetService.truncateActivityTopics()` has wider visibility than its only caller needs

**File:** `src/main/java/com/vrudenko/kanban_board/service/ResetService.java:89`
**Issue:** The method is package-private (`void truncateActivityTopics()`), but its only caller is
`resetAll()` in the same class, and no test in `src/test/java/com/vrudenko/kanban_board/e2e/reset/`
calls it directly (the tests call `resetService.resetAll()` and independently verify the Kafka topic
offsets/DB row counts through their own AdminClient/EntityManager instances). The widened visibility
buys nothing today and slightly weakens encapsulation.
**Fix:** Make it `private` unless a concrete future caller is already known.

### IN-02: Concurrent `POST /admin/reset` calls are not addressed by the documented single-caller assumption

**File:** `src/main/java/com/vrudenko/kanban_board/controller/ResetController.java:78-90`,
`src/main/java/com/vrudenko/kanban_board/service/ResetService.java:66-79`
**Issue:** The plan's backstop truth covers "a write issued through the public API concurrently with
an in-flight reset" — i.e., ordinary domain traffic racing a reset. It does not explicitly address two
concurrent `POST /admin/reset` calls racing each other (e.g., a flaky Playwright retry firing the
`beforeEach` reset twice). Two concurrent `ResetTruncateService.truncateAll()` invocations will
serialize safely on Postgres's `TRUNCATE`'s `ACCESS EXCLUSIVE` lock, and `truncateActivityTopics()` is
idempotent by construction, but calling `container.start()` on an already-running
`MessageListenerContainer` (from the second call's `finally` racing the first call's normal
completion) is not verified anywhere in this test suite. This is a narrow, low-probability edge case
in a nonprod-only tool, not a proven bug — recorded as Info since no test or code path establishes
either outcome (safe no-op vs. exception) either way.
**Fix:** No action required for this phase; if a future Playwright suite issues resets concurrently
(e.g., parallel test workers), consider a lightweight in-process mutex around `resetAll()` or document
that resets must be serialized by the caller.

---

_Reviewed: 2026-08-18_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
