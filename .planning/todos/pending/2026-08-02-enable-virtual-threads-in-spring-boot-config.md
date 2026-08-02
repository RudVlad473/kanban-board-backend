---
created: 2026-08-02T11:56:43.911Z
title: Enable virtual threads in Spring Boot config
area: backend
severity: minor
files:
  - src/main/resources/application.properties
---

## Problem

Spring Boot 3.5.0 on Java 21 supports virtual threads (`spring.threads.virtual.enabled=true`) for the servlet request-handling thread pool, which could improve throughput under blocking I/O (DB calls, and — as of v1.1 — synchronous Kafka publish calls in the request path). Not currently enabled.

## Solution

Evaluate before enabling — not a drop-in flip:
- Spring Data JPA/Hibernate and the JDBC driver make blocking calls; under virtual threads, `synchronized` blocks or JDBC connection-pool internals that pin the carrier thread can erase the benefit (or cause pool exhaustion under load). Check HikariCP behavior under virtual threads specifically.
- Spring Session JDBC's session handling also does blocking JDBC work per-request — same pinning risk applies there.
- Verify no `synchronized` blocks remain in the request path (services/controllers) that would pin carrier threads.
- If clear, add `spring.threads.virtual.enabled=true` to `application.properties` and re-run the full test suite plus a basic load check before shipping.
