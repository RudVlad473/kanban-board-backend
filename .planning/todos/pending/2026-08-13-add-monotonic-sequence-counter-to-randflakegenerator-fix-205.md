---
created: 2026-08-13T15:49:08.182Z
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
