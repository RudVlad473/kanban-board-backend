# Phase 4: Schema Registry - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-04
**Phase:** 4-Schema Registry
**Areas discussed:** Schema registry down resilience, Compatibility mode (BACKWARD vs FULL), Schema granularity (per-type vs union)

---

## Schema registry down resilience

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, same as broker-down | Extends D-01/D-02 uniformly: HTTP mutation always succeeds regardless of what fails inside the async post-commit publish (broker unreachable OR registry unreachable OR schema rejected) — logged via the existing `whenComplete` error callback, never swallowed, never blocks the caller. | ✓ |
| Treat schema rejection differently | A broker being down is transient/infra; a schema being REJECTED is a code bug that should be louder — e.g. fail fast in CI/tests rather than just log-and-continue at runtime. | |

**User's choice:** Same as broker-down (recommended option).
**Notes:** One resilience policy for the whole publish path, not a special case for registry failures. Captured as D-01 in CONTEXT.md.

---

## Compatibility mode: BACKWARD vs FULL

| Option | Description | Selected |
|--------|-------------|----------|
| BACKWARD | Standard for a same-deployable producer+consumer; required if the append-only feed is ever replayed from the beginning; allows safe evolution like removing a field with a default. | ✓ |
| FULL | Stricter (both backward AND forward compatibility); more conservative-sounding but would reject some changes that are actually safe given this project's same-deploy-unit topology. | |

**User's choice:** BACKWARD (recommended option).
**Notes:** Research (FEATURES.md) recommended BACKWARD; PITFALLS.md had flagged FULL as a counter-argument worth considering. Resolved in favor of BACKWARD given the concrete reasoning matches this project's actual deployment topology. Captured as D-02 in CONTEXT.md.

---

## Schema granularity: one per event type vs one union schema

| Option | Description | Selected |
|--------|-------------|----------|
| One schema per event type | 5 separate `.avsc` files, 1:1 with the 5 sealed-interface records. A 6th event type later means one new schema file. | ✓ |
| One union schema | Single Avro schema with a union type covering all 5, registered as one subject. Fewer subjects to manage, but a change to any one type touches the shared file. | |

**User's choice:** One schema per event type (recommended option).
**Notes:** Mirrors the existing Java structure exactly; each subject evolves independently under BACKWARD compatibility. Captured as D-03 in CONTEXT.md.

---

## Claude's Discretion

- Exact Avro schema field types/logical types (e.g. `timestamp-millis` vs plain `long`)
- Per-field required-vs-optional-with-default classification per event type (the bulk of the actual schema-authoring work)
- Mapping-layer implementation shape (hand-authored mapper classes vs. Avro's reflection-based `@Union` serialization) — no existing pattern to copy, genuinely open per research
- Subject-naming-strategy configuration (`TopicNameStrategy` vs `RecordNameStrategy`) given all 5 schemas share one topic
- Whether to stand up a standalone local Schema Registry container or point at a local Redpanda instance for Phase 4's local verification

## Deferred Ideas

- Pre-merge schema-compatibility CI check (SCHEMA-V2-01) — deferred to v2 during requirements definition, not raised again during this discussion
- Documented compatibility-mode rationale as in-code Javadoc (SCHEMA-V2-02) — deferred to v2 during requirements definition; the rationale itself is captured in CONTEXT.md's D-02, only the Javadoc polish task is deferred
- "Use Snowflake ID generator for activity log events" todo — reviewed as a phase-4 candidate match by keyword overlap, found unrelated to schema-registry scope, left deferred
</content>
