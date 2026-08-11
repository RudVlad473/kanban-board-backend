---
created: 2026-08-11T15:45:00.000Z
title: Decide whether to tag HistoricalActivityEventReconstructorTest as kafka
area: testing
severity: minor
files:
  - src/test/java/com/vrudenko/kanban_board/activitylog/HistoricalActivityEventReconstructorTest.java
  - build.gradle
---

## Problem

`HistoricalActivityEventReconstructorTest` extends `AbstractKafkaContainerTest` (its own class
declaration, line 38) but carries no `@Tag`. Since `fastTest`'s gate membership is opt-in by tag —
untagged classes run in the pre-commit gate by default (D-21, D-22; `docs/CODE_STYLE.md`, "Pre-commit
gate membership is by `@Tag`, not by class name") — this class runs inside `fastTest` and starts a
real Redpanda container on every commit. Evidence: its results are present under
`build/test-results/fastTest/` (confirmed during quick task 260811-ixj's Task 2 measurement, which
also confirmed the class starts a Redpanda broker live via `docker ps` during a `fastTest` run).

**New interaction this surfaced (260811-ixj):** `fastTest` now runs with `maxParallelForks = 2`
(`build.gradle`, quick task 260811-ixj), and each Gradle test-worker fork is a separate JVM with its
own static Testcontainers initializers — so under N forks, this untagged class could start up to N
Redpanda containers simultaneously against this machine's 7.728 GiB Docker budget. This makes the
untagged status a live constraint on how far `maxParallelForks` can be safely raised in the future,
not only a pre-commit-latency annoyance as originally framed.

## Solution

Not scoped yet — this is a decision, not a fix, and deliberately not made inside 260811-ixj (that
quick task's scope was measurement and the two levers it found, not this class). Three framed
options for whoever picks this up:

1. **Tag it `@Tag("kafka")`**, excluding it from `fastTest` like every other `AbstractKafkaContainerTest`
   subclass. Removes real coverage (the historical-schema reconstruction round-trip) from the
   pre-commit gate — consistent with every other Kafka-backed class's tier placement, but a real
   coverage reduction on every commit.
2. **Leave it untagged.** The gate keeps paying for one Redpanda container (or more, under higher
   `maxParallelForks`) on every commit — the status quo, now with a clearer cost given the
   fork-count interaction above.
3. **Split what the class proves across tiers** — e.g. keep a cheap MockMvc/service-level check of
   `HistoricalActivityEventReconstructor`'s pure mapping logic in `fastTest`, and move only the
   real-broker round-trip assertion to the Kafka tier. Larger effort than options 1 or 2; only worth
   it if the class currently proves more than the pure-mapping logic alone would need a real broker
   for.

Whoever picks this up should read the class's own Javadoc first (it explains why the round-trip is
deliberately against the real pipeline, not a reimplementation) before assuming option 3 is free.
