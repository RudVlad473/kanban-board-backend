---
created: 2026-08-06
title: D-02's replay-from-zero rationale is not delivered by non-transitive BACKWARD
area: backend
severity: minor
files:
  - src/main/java/com/vrudenko/kanban_board/config/AvroSchemaRegistrar.java
  - .planning/phases/04-schema-registry/04-CONTEXT.md
---

## Problem

Phase 4's **D-02** (`04-CONTEXT.md:20`) gives two justifications for choosing BACKWARD compatibility.
The first holds. The second does not:

> "Also required if the append-only activity feed is ever replayed from the beginning: old messages
> must stay readable by newer schemas."

Non-transitive **BACKWARD** checks a new schema version only against the **immediately previous**
version. **BACKWARD_TRANSITIVE** is the mode that checks against *all* previous versions, and it is
the one that actually delivers replay-from-zero.

A chain of individually-legal BACKWARD steps can therefore produce a schema that cannot read
version 1. Concretely:

- **V1** `{a: string, b: int}`
- **V2** `{a: string}` — drops `b`. BACKWARD check passes: a writer field absent from the reader is
  ignored during Avro resolution.
- **V3** `{a: string, b: string (default "")}` — re-adds `b` as a different type. BACKWARD check
  against V2 passes: a reader field absent from the writer resolves to its default.
- **V3 reading V1 data fails**: the writer encoded `b` as `int`, the reader expects `string`, and
  `int` is not promotable to `string`. Each step was legal; the endpoints are not compatible.

## Why the actual risk is low (do not treat this as urgent)

This does **not** reopen the schema-evolution risk that
`.planning/todos/completed/2026-08-01-account-for-schema-evolution-risk-when-changing-activityeven.md`
described — that one is about the *immediately previous* message shape, which is exactly what plain
BACKWARD covers. It was verified closed on 2026-08-06.

More to the point, Phase 4's own later work already concluded that topic replay-from-zero is not a
capability this project has:

- `04-04-PLAN.md:80` rejected replaying the `kanban.activity` topic from offset zero as
  **"unavailable, not merely inconvenient"** — the local broker volume is disposable and the
  production broker it would have come from no longer exists.
- `04-04-PLAN.md:45` states the durable historical record is the `activity_log` Postgres table,
  **not** the Kafka topic, which "has no retention guarantee."

So D-02 justifies its mode partly by a property the project elsewhere states it does not have and
does not rely on. That makes this a **rationale inconsistency, not a live defect**. The reason to
record it is that a future reader tightening or loosening compatibility will read D-02 and
reasonably believe replay-from-zero is a supported property of this pipeline.

## Solution

Two independent options — the first is worth doing regardless, the second is a genuine judgement call:

**1. Correct the rationale (low cost, do this either way).** Amend D-02's second justification to
say what is actually true: BACKWARD is chosen because producer and consumer ship in one deployable
(the first justification, which stands on its own), and topic replay-from-zero is explicitly *not*
a supported capability per `04-04-PLAN.md`. This removes the contradiction without touching any
running configuration.

**2. Consider tightening to BACKWARD_TRANSITIVE — and note the cost only ever goes up.** All five
subjects are still at exactly one version (`git log --diff-filter=M -- src/main/avro/` returns
nothing; the schemas were authored in `617caab`/`2fbc97e` and have never been modified). With one
version per subject, BACKWARD and BACKWARD_TRANSITIVE are behaviourally identical, so the change is
free *today* — there is no evolution history to re-audit. That audit cost grows with every schema
version added from here.

Weighed against that: D-02 is a locked decision from a completed phase, rated **costly** to reverse
in `04-02-PLAN.md:234`, and if replay-from-zero genuinely is not a capability then transitive
compatibility buys a property nobody needs. Deliberately left as a decision for the operator rather
than folded into todo cleanup.

If option 2 is taken, it is a one-constant change in `AvroSchemaRegistrar` (`BACKWARD_COMPATIBILITY`)
plus the `BACKWARD` assertion in `SchemaCompatibilityE2ETest.ConfiguredCompatibilityTest`, and it
should be done **before** Phase 5 repoints `schema.registry.url` at the production Redpanda registry,
so the production subjects are created under the final mode rather than migrated into it.

## Correction (quick task 260811-s5e, 2026-08-11) — factual, not a resolution

**Option 2's supporting premise above is now factually false and has been since 2026-08-09.**
As of commit `11fb2ad` (2026-08-09, `feat(06-07): switch activity-log eventId to a RandFlake-generated
string`, GAP-07), **all 6 pre-existing `.avsc` schemas were modified** — `eventId`'s Avro type
changed from `{"type":"string","logicalType":"uuid"}` to plain `"string"` (the underlying Avro wire
type stayed `string` throughout; only the `uuid` logical-type annotation was dropped). This
directly contradicts the claim above that "`git log --diff-filter=M -- src/main/avro/` returns
nothing" and "the schemas... have never been modified" — that was true on 2026-08-06 when this todo
was filed, and became false three days later, before this todo was ever revisited.

Discovered during 260811-s5e's Task 1 audit (`260811-s5e-FINDINGS.md` Section 3) while adding 8 new
event types to the `kanban.activity` pipeline. **Whoever picks up this todo next should re-run
`git log --diff-filter=M -- src/main/avro/` and re-verify current schema history from scratch
before acting on option 2's "free today" cost claim — do not trust the count above as still
accurate.** In practice this correction likely does not change option 2's actual cost much: every
registry instance to date (Testcontainers-backed test runs, local `docker-compose`) has been
ephemeral or periodically wiped, so no live registry has ever held two coexisting versions of any
subject — but the "zero modifications, ever" framing that made this look like a uniquely clean,
zero-audit-cost moment is no longer accurate, and the actual audit (checking whether any subject
now has more than one version in whatever registry BACKWARD_TRANSITIVE would be evaluated against)
was not re-run as part of this correction.

**This todo's actual open question (BACKWARD vs. BACKWARD_TRANSITIVE) remains unresolved** — this
correction only updates option 2's supporting evidence, deliberately, per 260811-s5e's own scope
boundary (that quick task's audit is not a mandate to resolve every schema-governance question it
brushes past). 260811-s5e's own new event types (14 subjects total now, up from 6) are unaffected by
either this correction or by whichever mode is eventually chosen here: under `RecordNameStrategy`
each is a brand-new subject at version 1, so this todo's non-transitivity concern does not apply to
any of them — see `260811-s5e-FINDINGS.md` Section 3 for the full reasoning. The one place this
todo's question would become genuinely live is the deferred `TaskMovedEvent` position-asymmetry
todo (fork D-E, resolved E1) — filed separately, see
`.planning/todos/pending/2026-08-11-taskmovedevent-position-asymmetry-not-fixed-in-s5e-fork-d-e.md`
— since evolving an *existing* subject (option E2 there) is exactly the scenario this todo's
question governs.
