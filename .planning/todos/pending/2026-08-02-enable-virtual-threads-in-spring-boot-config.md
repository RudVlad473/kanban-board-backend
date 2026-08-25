---
created: 2026-08-02T11:56:43.911Z
title: Enable virtual threads in Spring Boot config
area: backend
severity: minor
files:

  - src/main/resources/application.properties

blocked_on: .planning/todos/pending/2026-08-01-bump-java-version-from-21-to-25-current-lts.md
audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Spring Boot 3.5.0 on Java 21 supports virtual threads (`spring.threads.virtual.enabled=true`) for the servlet request-handling thread pool, which could improve throughput under blocking I/O (DB calls, and — as of v1.1 — synchronous Kafka publish calls in the request path). Not currently enabled.

## Research finding (2026-08-02, quick task 260802-ryf) — DO NOT ENABLE YET

Full research: `.planning/quick/260802-ryf-enable-virtual-threads-in-spring-boot-co/260802-ryf-RESEARCH.md`.

**Verdict: leave the flag unset.** Four of the six risks originally named turned out to be non-issues in this codebase (only one harmless `synchronized` exists in all of `src/main`, PostgreSQL JDBC 42.7.5 is in the clean window, Spring Session JDBC's risk doesn't apply because that dependency isn't actually on the classpath — see the separate side-finding todo — and the Kafka publish path was already moved off-thread in Phase 3, making the todo's original premise stale). The blocker is a different one than any originally named:

**HikariCP 6.3.0 — the version Spring Boot 3.5.0 pins — has an open, unfixed upstream issue** (`ConcurrentBag.requite()` yield-spin, [HikariCP#2398](https://github.com/brettwooldridge/HikariCP/issues/2398)) that saturates every carrier thread under moderate virtual-thread load on JDK 21. The only documented workaround is disabling virtual threads. The fix ships only in HikariCP 7.1.0.

There is also no load-testing harness anywhere in this repo, so even a clean bill of health could not be verified under real concurrency — flipping the flag today would be an unmeasured change with a known live defect on the exact HikariCP version in play.

## Re-evaluation trigger (both conditions required, not either)

- Java ≥ 24 (JEP 491 removes `synchronized`/monitor-contention pinning at the JVM level), **and**
- HikariCP ≥ 7.1.0 on the runtime classpath (fixes the `ConcurrentBag` yield-spin)

The parked Java 21→25 / Spring Boot 3.5→4.x upgrade (`2026-08-01-bump-java-version-from-21-to-25-current-lts.md`, Unit C) satisfies both in one move — this todo is blocked on that one landing. At that point, also budget for pool tuning (`spring.datasource.hikari.*` is currently entirely default — max pool size 10, no explicit `connection-timeout` — which becomes the sole admission-control bound once the flag removes the Tomcat thread ceiling) and a real load-test baseline before/after, since none exists today.

## Original Solution (superseded by the research above — kept for history)

Evaluate before enabling — not a drop-in flip:

- Spring Data JPA/Hibernate and the JDBC driver make blocking calls; under virtual threads, `synchronized` blocks or JDBC connection-pool internals that pin the carrier thread can erase the benefit (or cause pool exhaustion under load). Check HikariCP behavior under virtual threads specifically.
- Spring Session JDBC's session handling also does blocking JDBC work per-request — same pinning risk applies there.
- Verify no `synchronized` blocks remain in the request path (services/controllers) that would pin carrier threads.
- If clear, add `spring.threads.virtual.enabled=true` to `application.properties` and re-run the full test suite plus a basic load check before shipping.

This assumed Spring Session JDBC was actually wired up (it isn't — see the separate side-finding todo) and that the Kafka publish path was still synchronous (it wasn't by the time this was researched).
