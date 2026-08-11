# Task 1 Findings: Audit the mutating surface and settle the Avro-subject question empirically

Produced by direct read of `src/main` and `src/test` on 2026-08-11, HEAD `b678b3f`. No production
code changed in this task.

## Section 1 — Mutating-operation matrix

Verified every row of the planning inventory against the actual controller/service code.

| # | Route (controller:line) | Service method (file:line) | Event today | Disposition |
|---|---|---|---|---|
| 1 | `POST /boards` (`BoardController.java:49-56`) | `BoardService.save` (`BoardService.java:163-175`) | `BoardCreatedEvent` | EVENT-EXISTS |
| 2 | `PUT /boards/{id}` (`BoardController.java:66-72`) | `BoardService.updateById` (`BoardService.java:118-154`) | none | GAP-CLOSE-HERE — BoardUpdated |
| 3 | `DELETE /boards/{id}` (`BoardController.java:58-64`) | `BoardService.deleteById` (`BoardService.java:60-67`) | none | GAP-CLOSE-HERE — BoardDeleted |
| 4 | `POST /boards/{id}/columns` (`BoardController.java:74-82`) | `ColumnService.save` (`ColumnService.java:81-101`) | `ColumnCreatedEvent` | EVENT-EXISTS |
| 5 | `PUT /boards/{b}/columns/{c}` (`ColumnController.java:55-61`) | `ColumnService.updateById` (name only, `ColumnService.java:128-154`) | none | GAP-CLOSE-HERE — ColumnUpdated |
| 6 | `PATCH /boards/{b}/columns/{c}/reorder` (`ColumnController.java:73-79`) | `ColumnService.reorder` (`ColumnService.java:173-205`) | none | GAP-CLOSE-HERE — fork D-A |
| 7 | `DELETE /boards/{b}/columns/{c}` (`ColumnController.java:63-69`) | `ColumnService.deleteById` (`ColumnService.java:237-258`) | `ColumnDeletedEvent` | EVENT-EXISTS |
| 8 | `POST /boards/{b}/columns/{c}` add task (`ColumnController.java:45-53`) | `TaskService.save` (`TaskService.java:56-79`) | `TaskCreatedEvent` | EVENT-EXISTS |
| 9 | `PUT .../tasks/{t}` (`TaskController.java:51-57`) | `TaskService.updateById` (`TaskService.java:113-142`) | none | GAP-CLOSE-HERE — TaskUpdated |
| 10 | `PATCH /tasks/{t}/move` (`TaskMoveController.java:33-39`) | `TaskService.moveToColumn` (`TaskService.java:164-238`) | `TaskMovedEvent` | EVENT-EXISTS (see fork D-E — position asymmetry) |
| 11 | `DELETE .../tasks/{t}` (`TaskController.java:43-49`) | `TaskService.deleteById` (`TaskService.java:246-266`) | `TaskDeletedEvent` | EVENT-EXISTS |
| 12 | `POST .../tasks/{t}/subtasks` (`TaskController.java:59-67`) | `SubtaskService.save` via `TaskService.addSubtaskByTaskId` (`SubtaskService.java:30-38`) | none | GAP-CLOSE-HERE — SubtaskCreated |
| 13 | `PUT .../subtasks/{s}` (`SubtaskController.java:53-59`) | `SubtaskService.updateById` (title + isCompleted, `SubtaskService.java:65-97`) | none | GAP-CLOSE-HERE — fork D-B |
| 14 | `DELETE .../subtasks/{s}` (`SubtaskController.java:45-51`) | `SubtaskService.deleteById` (`SubtaskService.java:99-104`) | none | GAP-CLOSE-HERE — SubtaskDeleted |
| 15 | `PUT /users/me/theme` (`UserController.java:44-48`) | `UserService.updateTheme` (`UserService.java:113-121`) | none | OUT-OF-SCOPE — `ActivityEvent.boardId()` is mandatory and non-null (see `ActivityEvent.java:10-12`'s own Javadoc); theme is a user-scoped preference with no board at all, structurally cannot satisfy the interface without inventing a fake boardId. `UserService` is also the identity root (no ownership chain above it) per `UserController.java`'s own Javadoc, reinforcing that this mutation is architecturally outside the board-scoped activity feed's domain. |
| 16 | `POST /api/signup` (`AuthenticationController.java:109`) | `UserService.save` | none | OUT-OF-SCOPE — same reason as #15: creates a `UserEntity`, no `boardId` exists yet. Not in the planning inventory; added here for completeness. |
| 17 | `POST /api/signin` (`AuthenticationController.java:70`) | n/a (authentication only) | none | OUT-OF-SCOPE — not a domain mutation on board/column/task/subtask state. |

No additional mutating operation was found beyond the planning inventory's 15 rows plus the two
authentication routes added above for completeness (both clearly out-of-scope, not previously
missing from consideration — the planning inventory scoped itself to "board/column/task/subtask"
mutations and `AuthenticationController` was never implied to be in scope).

`GET /boards/{boardId}/full` (`BoardController.java:91-95`, `BoardService.findFullById`) and
`GET /boards/{boardId}/activity` (`ActivityController.java`) are both read-only and correctly
excluded from the inventory.

## Section 2 — Cascade-path inventory

| Cascade path | Cascades to | Publishes today? |
|---|---|---|
| `BoardService.deleteById` (`BoardService.java:60-67`) | `ColumnService.deleteAllByBoardId` (all columns on the board) | Parent (`BoardDeletedEvent`, once this task lands) yes; children no |
| `BoardService.deleteAllByUserId` (`BoardService.java:69-76`) | Loops `deleteById` once per board owned by the user | One `BoardDeletedEvent` per board (once this task lands); no separate account-deletion event |
| `ColumnService.deleteAllByBoardId` (`ColumnService.java:63-72`) | Per-column `TaskService.deleteAllByColumn`, then a derived (fetch-then-remove-per-entity) bulk column delete | Nothing — no per-child event for any cascaded column |
| `TaskService.deleteAllByColumn` (`TaskService.java:303-315`) | `SubtaskService.deleteAllByTaskIds` + `taskRepository.deleteAllByIdInBatch` (bulk JPQL, bypasses persistence context and `@Version`) | Nothing — no per-child event for any cascaded task or subtask |
| `SubtaskService.deleteAllByTaskId` (`SubtaskService.java:106-110`) | Called from `TaskService.deleteById` before the single-task delete | Nothing (leaf cascade, single task's subtasks) |
| `SubtaskService.deleteAllByTaskIds` (`SubtaskService.java:116-122`) | Called from `TaskService.deleteAllByColumn`, bulk JPQL `deleteAllByTaskIdIn` | Nothing (leaf cascade, whole-column subtasks) |

**Precedent established:** only the directly-requested delete emits an event; every cascaded
child delete is silent, and this is already true for the two cascade paths that exist today
(`ColumnDeletedEvent`'s cascaded tasks/subtasks, `TaskDeletedEvent`'s cascaded subtasks both
publish nothing). This is the evidence base for fork D-D's D1 precedent claim — confirmed, not
assumed.

## Section 3 — Avro/Schema Registry disposition (the todo's question 4)

**RecordNameStrategy, confirmed by file:line, not assumed:**
- Producer: `application.properties:79` — `spring.kafka.producer.properties.value.subject.name.strategy=io.confluent.kafka.serializers.subject.RecordNameStrategy`
- Consumer: `application.properties:107` — identical value.
- `AvroSchemaRegistrar.registerOne` (`AvroSchemaRegistrar.java:82-83`) — `String subject = schema.getFullName();` — subjects are derived from the record's own full name, never hardcoded or topic-derived.

**Conclusion (re-verified, matches planning assumption):** under `RecordNameStrategy`, a brand-new
`ActivityEvent` record with a brand-new Avro record name (e.g. `AvroSubtaskCreatedEvent`) is a
**new subject at version 1**, not a new version of any existing subject. Compatibility checks
(`BACKWARD`) only ever run within a subject; since none of this plan's new event types share a
record name with an existing one, the checks that fire when `AvroSchemaRegistrar.registerOne`
registers each new schema are all "brand-new subject, first version" registrations, which the
registry accepts unconditionally (nothing to compare against yet — confirmed by
`SchemaCompatibilityE2ETest.EnforcementTest`'s own comment, "a brand-new subject's first version is
always accepted regardless of compatibility setting"). **The d-02 BACKWARD-non-transitivity gap
does not apply to any new subject this plan adds**, for exactly the reason the plan stated.

**`git log --diff-filter=M -- src/main/avro/` does NOT return nothing — this contradicts a
planning-time assumption and is flagged for the gate.** Full output:

```
11fb2ad feat(06-07): switch activity-log eventId to a RandFlake-generated string (GAP-07)
```

This single commit (2026-08-09, already merged, predates this quick task) modified all 6 existing
`.avsc` files, changing `eventId`'s Avro type from `{"type":"string","logicalType":"uuid"}` to
plain `"string"` (dropping the `uuid` logical type; the underlying Avro wire type stayed `string`
the whole time — logical types are reader-side decoding metadata, not part of the encoded bytes).

This is significant because the **source todo's own sibling, `2026-08-06-d-02-backward-non-transitive-vs-replay-from-zero.md`, explicitly asserted the opposite as its load-bearing premise**: *"All five subjects are still at exactly one version (`git log --diff-filter=M -- src/main/avro/` returns nothing; the schemas were authored in `617caab`/`2fbc97e` and have never been modified)."* That premise was true on 2026-08-06 and became false three days later, on 2026-08-09 — after the d-02 todo was filed but before this task started. The d-02 todo has not been updated to reflect this.

**Why this does not, on inspection, change this plan's own additive-subjects-only claim:** the
modification was to *existing* subjects' `eventId` field, made in a prior, already-merged session
— it is not part of this plan's diff, and it does not retroactively make any of *this plan's* new
subjects a "new version of an existing subject." The RecordNameStrategy/new-subject conclusion
above is unaffected.

**Why it still matters and is being surfaced rather than silently reasoned past:** the finding
directly undermines the "free today, cost grows later" cost argument the d-02 todo uses to justify
deferring the BACKWARD vs. BACKWARD_TRANSITIVE decision — that argument depends on "all five
subjects at exactly one version," which was already false by the time this task started (all 6
subjects have in fact had 2 distinct schema shapes exist in git history, even though no live,
persisted registry has ever held both versions at once — every registry instantiation to date is
either an ephemeral Testcontainers instance recreated per test run, or a local dev
`docker-compose` instance the operator has periodically wiped with `down -v`). This is exactly the
"evidence that contradicts a planning-time assumption" category the checkpoint gate below is meant
to catch, so it is being raised there rather than resolved unilaterally, per this task's own
governing instruction not to guess.

## Section 4 — Compile-time safety-net map

| Switch/list/registry | File:line | Exhaustiveness |
|---|---|---|
| `ActivityEvent`'s `permits` clause | `ActivityEvent.java:19-25` | COMPILER-ENFORCED (sealed interface; adding a permitted type has no direct compile effect itself, but every unguarded switch below over it becomes non-exhaustive) |
| `ActivityEventAvroMapper.toAvro` | `ActivityEventAvroMapper.java:44-98` | COMPILER-ENFORCED — no `default` arm; confirmed by direct read, exactly as documented in the method's own Javadoc (`:38-43`) |
| `ActivityEventAvroMapper.toDomain` | `ActivityEventAvroMapper.java:106-154` | **SILENT** — has a `default` arm (`:150-152`) throwing `IllegalArgumentException` at runtime, confirmed by direct read and by the method's own Javadoc (`:100-105`) explaining why: `SpecificRecord` is an ordinary (non-sealed) interface, so the compiler cannot prove exhaustiveness here |
| `ActivityLogConsumer.deriveActionAndDetailIds` | `ActivityLogConsumer.java:81-115` | COMPILER-ENFORCED — no `default` arm, confirmed by direct read, exactly as its own Javadoc (`:75-80`) states |
| `AvroSchemaRegistrar.SCHEMAS` | `AvroSchemaRegistrar.java:54-61` | **SILENT** — a plain `List.of(...)`, not a switch; omitting a schema here is not a compile error, and the class's own Javadoc (`:20-21`) states that a producer whose schema is missing here fails at runtime under `auto.register.schemas=false` |
| `SchemaCompatibilityE2ETest.productionSubjects()` | `SchemaCompatibilityE2ETest.java:45-52` | **SILENT** — a plain `List.of(...)`; omitting a subject here silently under-covers the BACKWARD assertion with no test failure |

Four of six touchpoints are compiler-enforced (2 switches + the sealed `permits` clause covering
both switches); two are silent lists that must be updated by hand. This matches the plan's
must-have truth exactly.

## Section 5 — Pre-existing defects found while auditing

| # | Discrepancy | Disposition |
|---|---|---|
| 1 | `SchemaCompatibilityE2ETest.productionSubjects()` lists 5 of the 6 production subjects — `AvroColumnDeletedEvent` is missing (confirmed: `SchemaCompatibilityE2ETest.java:45-52` has no `AvroColumnDeletedEvent` entry, and `AvroSchemaRegistrar.SCHEMAS` at `:54-61` does include it). | FIX-HERE (Task 3, per plan's own instruction) |
| 2 | `build.gradle`'s `registerSchemas` task `description` (`build.gradle:261-262`) says "Registers all 5 Avro schemas" while `AvroSchemaRegistrar`'s own class Javadoc (`AvroSchemaRegistrar.java:21`) and `SCHEMAS` list both say/hold 6. | FIX-HERE (Task 3) |
| 3 | The d-02 todo's "all five subjects at exactly one version" premise is stale as of the 2026-08-09 `eventId` type change (see Section 3). | Flagged at the gate below rather than unilaterally resolved — touches the same schema-evolution area the gate exists to govern, and the d-02 todo's own text says "deliberately left as a decision for the operator." |
| 4 | `TaskService.deleteAllByColumnId(String userId, String columnId)` (`TaskService.java:268-273`), a public method, has zero production callers — every real caller path goes through `ColumnService.deleteAllByBoardId`'s package-private `deleteAllByColumn(ColumnEntity)` overload directly. Only referenced from `TaskServiceTest`. | FILE-TODO — out of this task's scope (not a mutating-surface gap, not touched by any file this plan modifies); noted for completeness only. |

No other count discrepancy or stale reference was found in the files this task's `read_first` list
covered.

## Design forks for the gate

Restating D-A through D-E from the checkpoint, annotated with what the audit actually found. No
fork was resolved outright by the audit, and no genuinely new *design* fork was surfaced — the
audit's only surprise is the Section 3/Section 5 #3 finding above, which is a factual correction to
supporting evidence for the d-02 rationale, not a sixth design fork with its own options.

- **D-A** (column reorder: dedicated event or folded into ColumnUpdatedEvent) — audit found no new
  evidence either way; `ColumnService.reorder` (`ColumnService.java:173-205`) is confirmed
  structurally distinct exactly as the plan described (own DTO, own route, own version-guard
  ordering). Recommendation A1 stands unchanged.
- **D-B** (subtask completion toggle) — audit confirms `SubtaskService.updateById`
  (`SubtaskService.java:65-97`) is the single method handling both title and `isCompleted` through
  one DTO, exactly as described. Recommendation B2 stands unchanged.
- **D-C** (Updated-event granularity) — no new evidence. Recommendation C1 stands unchanged.
- **D-D** (cascade deletes) — Section 2 above empirically confirms the D1 precedent (only the
  directly-requested delete publishes; cascaded children stay silent) already holds for both
  existing cascade paths. Recommendation D1 is now evidence-backed, not just pattern-matched.
- **D-E** (TaskMovedEvent position asymmetry) — no new evidence changing the tradeoff. **This is
  the fork most directly adjacent to the Section 3 finding**: E1 is what keeps this task
  "additive-subjects-only," and the Section 3 finding is specifically about whether that framing's
  supporting evidence (no subject has ever been modified) still holds. E1 itself is unaffected —
  choosing E1 still means this task modifies zero existing `.avsc` files — but the operator should
  weigh the Section 3 finding when confirming E1, since the premise that made "everything modified
  here is provably new" a clean, uncomplicated story is not as clean as planning time believed.

**No new fork beyond D-A through D-E was surfaced by the audit.** The one item raised above (d-02's
stale premise) is presented as a finding requiring the operator's attention at the gate, not as a
sixth option set, since the plan's own five forks do not have a slot for it and inventing one would
be overreach for an audit task.

## Task 5 — Suite impact measurement (measured, not assumed)

Two full `./gradlew test --rerun-tasks` runs, same machine, same session:

| | Baseline (`b678b3f`, pre-Task-3, detached HEAD, working tree confirmed clean before/after) | Current (`000f889`, after Tasks 3-5) | Delta |
|---|---|---|---|
| Test count | 398 | 417 | +19, zero shrinkage |
| Wall clock | 368s (6m 6s) | 411s (6m 49s) | +43s (+11.7%) |
| Failures/errors | 0 | 0 | none |

The +43s exceeds this repo's documented ~18s run-to-run variance (`260811-ixj`), so per the plan's
own instruction this was investigated rather than waved through. Explained, not mysterious:

- **+19 tests** run in the after case (new publication-tier, Avro round-trip, per-domain Kafka E2E,
  and `ActivityReadTest` coverage added across Tasks 3-5).
- **`SubtaskService.save` now publishes** where it previously published nothing — every
  `AbstractAppTest.setup()` fixture run creates `MOCK_SUBTASKS_AMOUNT` (7) subtasks, so every one of
  the ~19 test classes sharing that fixture base now does 7 more publish-and-consume round trips per
  test method than the baseline measured.
- **3 new real-broker Kafka E2E tests** (`ActivityLogConsumerE2ETest`'s `BoardUpdatedEvent`,
  `ColumnReorderedEvent`, `TaskUpdatedEvent` additions) each carry Awaitility polling overhead on top
  of the publish/consume round trip itself.

**`kafkaPublishExecutor` did not saturate.** Zero test failures and zero errors in either run; no
`TaskRejectedException` surfaced in either build's output. The threat model's named fallback (raise
`queueCapacity` in the test profile, or make the async dispatch non-fatal) was evaluated as
unnecessary and **not applied** — the executor's `core 2 / max 4 / queue 200` bounds absorbed the
~50% publish-volume increase from `SubtaskCreatedEvent`/`BoardDeletedEvent`/etc. without exhausting
the queue. This is a measured negative result (the failure mode was checked for and not found), not
an absence of checking.

**Conclusion:** the regression is real, modest (+11.7%), and fully attributable to legitimately more
work being done (more tests, more publishes, more real-broker round trips) rather than any
performance defect introduced by this task. No mitigation needed.
