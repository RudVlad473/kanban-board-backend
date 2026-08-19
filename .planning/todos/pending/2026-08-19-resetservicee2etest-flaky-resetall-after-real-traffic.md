---
created: 2026-08-19T11:25:40.000Z
title: ResetServiceE2ETest.ResetAllTest.should_emptyBothStores_when_resetAllCalledAfterRealTraffic is flaky in CI
area: testing
severity: minor
resolves_phase: null
files:
  - src/test/java/com/vrudenko/kanban_board/e2e/reset/ResetServiceE2ETest.java
---

## Problem

`ResetServiceE2ETest > ResetAllTest > should_emptyBothStores_when_resetAllCalledAfterRealTraffic()`
failed with an `org.opentest4j.AssertionFailedError` at line 191 on CI run `32247040963`
(2026-08-19, `run-tests` job) — 1 of 474 tests. The surrounding log is dense with Kafka
consumer-group rebalancing activity (`activity-log` consumer group repeatedly resetting
generation/rejoining) immediately before the failure, suggesting a timing-sensitive
assertion racing against consumer rebalance/settle time rather than a real logic bug.

Immediately re-ran only the failed `run-tests` job (`gh run rerun --failed`) against the
identical commit with no code changes — passed clean on the retry. Confirmed as flaky, not a
regression: the triggering commit only changed a schema-registry URL string in
`deploy.yml`, unrelated to this test's Java code path.

## Suggested fix

Investigate whether the test's Kafka consumer setup/teardown has an insufficiently bounded
wait for group rebalancing to settle before asserting store contents, and whether the
existing Testcontainers Kafka broker's consumer-group session/rebalance timeouts are tuned
tightly enough to introduce this race under CI's (slower, shared) runner conditions. Compare
against how other Kafka-consuming E2E tests in this suite await consumer readiness.
