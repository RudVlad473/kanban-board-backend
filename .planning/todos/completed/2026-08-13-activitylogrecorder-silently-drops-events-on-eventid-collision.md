---
created: 2026-08-13T15:12:35.000Z
resolved: 2026-08-13
title: ActivityLogRecorder silently drops events on an eventId collision (measured, not theoretical)
area: backend
severity: minor
files:
  - src/main/java/com/vrudenko/kanban_board/service/ActivityLogRecorder.java
  - src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java
---

## Problem

Filed by quick task 260813-ncx's D-08 (`.planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/PROBE-FINDINGS.md`),
which measured the production consequence of `RandFlakeGenerator`'s documented (and now
measurement-confirmed) same-millisecond `eventId` collision behaviour rather than reasoning about
it.

`ActivityLogRecorder.persist` short-circuits at `existsByEventId(entry.getEventId())`
(`ActivityLogRecorder.java:46`, re-checked in its catch block at line 58), backed by the
`uk_activity_log_event_id` unique constraint (`V3__add_activity_log.sql`). A genuine `eventId`
collision is therefore silently treated as a redelivery: the second event is dropped with no
exception and no dead letter, and one activity-log row is lost instead of two.

The measurement: a throwaway probe (`RandFlakeCollisionProbeTest`, deleted before commit, see the
plan directory above for T=200/C=13/E=10.63 raw evidence) measured a **worst-case** figure of ~47
expected colliding `eventId` pairs across 1,000,000 lifetime `activity_log` events, computed by
reusing the tight-loop's observed same-millisecond clustering (~790 calls per distinct millisecond)
as the assumed production burst size — a deliberately pessimistic input, since real traffic
(`eventIdGenerator.generate()` has ~14 call sites, one per user mutation) is structurally far
sparser in time than a synthetic loop. The same probe's direct, more realistic measurement (20
back-to-back calls, a generous per-request upper bound) found a collision rate of 1.5e-4 per burst.
Both figures clear the 0.01-pairs bar D-08 set for filing rather than dismissing.

## Solution

Not yet decided. This todo exists to track the finding, not to prescribe a fix — D-08 explicitly
scoped this quick task to *quantify*, not to widen the id, add a counter, or change the dedupe
strategy, since any of those is a behavioural change to every entity id and every `event_id` row in
the system (see `RandFlakeGenerator`'s own IdentifierGenerator role behind `@RandFlakeId` for every
entity insert, and `event_id`'s `varchar` column since `V6__change_activity_log_event_id_to_varchar.sql`).

Candidates for whoever picks this up:

1. **Accept as-is** — at the measured rate (~47 lost rows per 1,000,000 lifetime events in the
   pessimistic case, and a 1.5e-4 per-burst rate realistically), this may be an acceptable trade-off
   for an append-only activity feed with no correctness-critical read path depending on completeness.
   Document the acceptance explicitly rather than leaving it silent.
2. **Widen the random field** — trades away the Base36 lexicographic-ordering caveat
   `EventIdGeneratorTest`'s third test already documents, and changes the rendered string width for
   every existing `event_id` row. A real schema/behaviour change, not a quick task.
3. **Make `ActivityLogRecorder.persist` distinguish a true redelivery from a collision** — e.g. by
   also comparing a secondary field (aggregate id + action + timestamp) before treating
   `existsByEventId` as proof of redelivery, so a genuine collision inserts rather than silently
   drops. Changes the redelivery-detection semantics of the consumer; needs its own design pass.

Low priority: the measured rate is low, and no user-facing correctness guarantee currently depends
on `activity_log` being complete.

## Resolution (2026-08-13, quick task 260813-os9)

**The premise this todo was filed against is fixed.** Quick task 260813-os9 replaced
`RandFlakeGenerator`'s 23 random low bits with a monotonic shared sequence (a single `static
AtomicLong`, `updateAndGet(previous -> max(candidate, previous + 1))`), so a genuine same-tick
`eventId` collision from `RandFlakeGenerator`/`EventIdGenerator` is now structurally impossible,
not merely rare — the entire T=200/C=13/E=10.63 measurement this todo's exposure figures were
derived from describes a design that no longer exists. `ActivityLogRecorder.record`'s
`existsByEventId` short-circuit (`ActivityLogRecorder.java:46`, re-checked at line 58) can
therefore no longer swallow a *generated* `eventId` collision — every `eventId` reaching that
method is now unique by construction, so the only way `existsByEventId` can return `true` is a
genuine redelivery of an id that was already recorded, which is exactly the behavior it exists to
detect.

**What this todo still legitimately covers, unaffected by 260813-os9:** a duplicate `eventId`
arriving from a real Kafka redelivery (broker-level at-least-once delivery, consumer rebalance
replay, producer retry after an ack timeout) is a different source of duplication than a
generator collision and is untouched by this change — `existsByEventId`'s fast path and the
`DataIntegrityViolationException` backstop are exactly the idempotency mechanism `record`'s own
Javadoc (ACTLOG-03, D-05) already describes for that case, and they continue to work as designed.

**Decision: closed, not left open.** The todo's own three candidate solutions (accept as-is,
widen the random field, or make `record` distinguish a true redelivery from a collision) were all
framed around a *generator*-collision source that no longer exists; the redelivery-dedupe
behavior they'd have modified was never actually broken. There is no remaining action item here —
moved to `completed/` with this note as its resolution.
