---
created: 2026-08-13T15:49:08.182Z
resolved: 2026-08-13
title: Add monotonic sequence counter to RandFlakeGenerator, fix 2058 sign overflow
area: backend
severity: minor
files:
  - src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java
  - src/test/java/com/vrudenko/kanban_board/config/EventIdGeneratorTest.java
---

## Problem

`RandFlakeGenerator` (the `IdentifierGenerator` behind `@RandFlakeId` on `BaseEntity`, and also used
directly by `EventIdGenerator`) packs a 41-bit millisecond timestamp and 23 *random* low bits into a
64-bit `long`, base36-encoded to a string. Quick task `260813-ncx` measured this design's real
same-millisecond collision rate empirically (`PROBE-FINDINGS.md`): 13/200 trials of 1000 rapid calls
collided, matching the birthday-paradox prediction for 23 random bits almost exactly (~6.5%). This is
inherent to the design, not a bug in the sense of a mistake -- the low bits give no uniqueness
guarantee within one millisecond tick because they're random, not a counter. `260813-ncx` fixed the
test's overstated assertion (relaxed `MIN_DISTINCT_IDS` to 993, falsification-proven) and filed a
separate todo (`2026-08-13-activitylogrecorder-silently-drops-events-on-eventid-collision.md`) for the
production consequence (silent same-`eventId` swallow in `ActivityLogRecorder.persist`).

This todo is the root-cause-level fix: a proper Snowflake-family generator (Sonyflake, Twitter's
original Snowflake) closes exactly this gap with a **monotonic per-tick sequence counter** instead of
randomness in the low bits, giving deterministic (not probabilistic) same-tick uniqueness up to the
counter's range. Researched during this session whether to adopt a Sonyflake-specific Java library
instead of hand-rolling this: no viable one exists (zero Maven Central artifacts; the only GitHub
ports are single-author repos with 1-4 stars, none shipping a Hibernate `IdentifierGenerator`) --
adopting one would mean vendoring unmaintained source and writing the same wrapper by hand anyway, for
no less effort than fixing the existing generator directly.

**Separately, a related latent bug surfaced during that research**: the current generator uses the
full 64 bits (41 timestamp + 23 random) with no reserved sign bit, so the resulting `long` can go
negative once milliseconds-since-epoch (from the `2023-01-01` custom epoch) passes 2^40 -- around
**2058**. Both Sonyflake and Twitter's Snowflake deliberately cap at 63 bits specifically to avoid
this. Not urgent given the ~32-year horizon, but worth fixing in the same pass since any bit-layout
change here should land as one considered redesign, not two.

## Solution

Replace the random 23 low bits with a monotonic per-millisecond sequence counter (`AtomicLong` or
similar thread-safe counter, matching `RandFlakeGenerator`'s existing "deliberately not synchronized"
design philosophy where possible -- see its own comment on why a lock was never load-bearing for
uniqueness): reset the counter when the millisecond tick advances; within the same tick, increment (or
block/spin to the next tick once the counter's bit-width is exhausted, matching Sonyflake's approach).
Reserve a sign bit (cap at 63 bits total) to close the 2058 overflow alongside this, since both changes
touch the same bit-packing logic.

No dependency needed and no machine-ID scheme required (this app is single-instance today, per
`docker-compose.prod.yml` -- do not add a machine-ID field speculatively for a multi-instance future
that isn't planned). No migration concern: the base36 string format and length are unaffected (driven
by timestamp magnitude, not how the low bits are subdivided), so every already-persisted
`@RandFlakeId` primary key in the live database remains valid -- only future ID generation changes.

Once the counter makes same-millisecond collisions structurally impossible up to the counter's range,
tighten `EventIdGeneratorTest`'s `MIN_DISTINCT_IDS` threshold (currently relaxed to 993 by
`260813-ncx`) back toward 1000, since the probabilistic tolerance that threshold exists for will no
longer apply -- confirm via the same falsification-proof pattern `260813-ncx` used (force a collision
artificially, observe RED at the tightened threshold; restore, observe GREEN).

## Resolution

Closed by quick task 260813-os9. Replaced the 23 random low bits with a monotonic shared sequence:
a single `static AtomicLong LAST_ID`, updated per call via
`updateAndGet(previous -> Math.max((System.currentTimeMillis() - CUSTOM_EPOCH) << SEQUENCE_BITS,
previous + 1))`. Packing `(timestamp, sequence)` into one `long` makes the pair atomic without a
lock; `previous + 1` does triple duty as the same-millisecond increment, the sequence-exhaustion
strategy (borrows into the next millisecond rather than spin-waiting or throwing), and the
backward-clock guard. `LAST_ID` is `static` deliberately, not an instance field as this todo's own
"matching the existing philosophy where possible" phrasing might have suggested taken literally --
Hibernate builds one `RandFlakeGenerator` per `@RandFlakeId` mapping and `EventIdGenerator` builds
its own with `new RandFlakeGenerator()`, so a non-static counter would give each instance its own
sequence and two instances ticking in the same millisecond would emit *identical* ids, converting
the old design's probabilistic collision into a deterministic one -- this is a genuine finding this
plan's own measurement surfaced that the todo did not anticipate.

**Chosen layout: 1 sign bit (never written) + 41 timestamp bits + 22 sequence bits = 64**, closing
this todo's 2058 sign-overflow finding by exhausting **2087-09-07** instead (recomputed via a
throwaway Node.js `BigInt` script during this task, not copied from any illustrative figure;
`node --eval` output archived in the quick task's SUMMARY).

**The "no migration concern" claim above was only half true, and correcting it was the harder half
of this fix.** Persisted ids do stay *valid* -- base36 width is 12 characters under both the old and
new layouts, confirmed by direct measurement, and the `event_id`/primary-key columns are
`varchar`/`varchar(255)` with no width constraint at risk. But *ordering* was not free: narrowing
the low field from 23 to 22 bits alone would have halved every future id's magnitude, so with
`CUSTOM_EPOCH` left at its original 2023-01-01 value, every id generated after this change would
have sorted **below** every id already in the live database until a measured **2030-03-26** --
inverting `@OrderBy("id")` on `BoardEntity.column`, `ColumnEntity.task` and `TaskEntity.subtasks`
for any collection mixing pre- and post-deploy rows (`BaseEntity` carries no `createdAt`, so id
order is the *only* order those collections have). Fixed by moving `CUSTOM_EPOCH` back to
2018-01-01 in the same change: every id generated under the new layout now exceeds
**1058897343291588607**, the largest value the legacy layout (23 random bits, 2023-01-01 epoch)
could ever have produced even if that code kept running until 2027-01-01 -- recomputed with the
legacy layout's maximum possible random-bit contribution included, which is **8,388,607 higher**
than this plan's own illustrative figure (1058897343283200000, the shifted timestamp alone with an
implicit random value of 0); the higher, fully-maximal recomputed value is what
`RandFlakeGeneratorTest` asserts a fresh id decodes above, and is the correct bound, per this
plan's own instruction that a disagreeing recomputation wins.

**Falsification-proven, not merely tested green.** Temporarily replaced the monotonic-carry
fallback (`Math.max(candidate, previous + 1)`) with the bare `candidate`, dropping the sequence
increment: RED on exactly the 5 uniqueness/ordering-dependent assertions across
`RandFlakeGeneratorTest` (3 of 6) and `EventIdGeneratorTest` (2 of 4) -- the 5 assertions that
depend on same-millisecond distinctness or strict ordering; the other 5 (positivity, width, the
legacy-ceiling comparison, non-blank, and the real-millisecond-boundary ordering case) stayed
green, exactly as expected since none of them depend on the monotonic carry. Restored, re-ran: all
10 green, `git diff -- src/main` empty at that point, confirming zero net production-code change
from the falsification round-trip.

`EventIdGeneratorTest`'s `MIN_DISTINCT_IDS=993` threshold (`260813-ncx`'s probabilistic tolerance)
was tightened back to an exact `hasSize(1000)`, and the method renamed back from
`shouldReturnOverwhelminglyDistinctValues_...` to `shouldReturnDistinctValues_...`, per this todo's
own stated next step -- confirmed via the same falsification pattern above.

Full suite green: 451 tests, 0 failures, 0 errors (`./gradlew spotlessCheck test`), a net +7 over
the prior 444-test baseline (6 new `RandFlakeGeneratorTest` tests, 1 new `EventIdGeneratorTest`
test), zero shrinkage.
