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
