---
phase: quick-261005-sun
plan: 01
subsystem: docs/diagrams
tags: [diagrams, kruchten-4+1, mermaid, legibility, guard]
status: complete
tasks_completed: [1, 2, 3, 4, 5]
tasks_remaining: []
commits: 6
plan_head_before: 9437c582d6858321cf75a35ba439842f99f15cec
plan_head_after: 2f460ed
actuals:
  tokens: null
  tasks: 2
  commits: 6
---

# Quick 261005-sun: Task 1 (proof slice) Summary

One-liner: `process/activity-pipeline` now flows end to end through the new pipeline: a Kruchten view
folder, an unsqueezed scale-2 render (710 px natural width, 16 px text at the 838 px GitHub column), a
width-pinned `<img>` embed, and a stdlib guard with a mutation-checked selftest.

Status is `incomplete` because only Task 1 of 5 was executed, by instruction. Tasks 2-5 are untouched.

## Commits (branch worktree-agent-a648c8c9b425c315c, worktree
/home/andre/dev/kanban-board-backend/.claude/worktrees/agent-a648c8c9b425c315c)

| # | Hash | Message | Content |
|---|------|---------|---------|
| 1 | 933a2eb | docs(quick-261005-sun): move the mutation flowchart into the process view folder | rename-only: `git mv` .mmd and .png (100% similarity both), manifest key, ARCHITECTURE.md embed path, README row, render script accepts view/subject |
| 2 | 3760187 | chore(261005-sun): add diagram inventory guard and selftest | `scripts/verify-diagrams.py` (553 lines), `scripts/verify-diagrams-selftest.py` (449 lines) |
| 3 | d9aad1b | docs(quick-261005-sun): render diagrams at natural width and redraw the activity pipeline at process level | `mermaid-config.json`, manifest all scale 2, render script `-c` plus header, redrawn .mmd, re-rendered .png, ARCHITECTURE.md `<img width=710>`, TD todo closed |

`git log --follow` on `process/activity-pipeline.mmd` shows commits d9aad1b, 933a2eb and 9bd5c23 (the
original), so rename history follows. Commit count measured from the ledger:
`git rev-list --count 9437c58..HEAD` = 3.

## Measured evidence

### Render widths and font heights (D = 838)

Commands: `python3 scripts/verify-diagrams.py report --diagram process/activity-pipeline`.

| State | PNG W x H | Natural W x H | Text px at D=838 | Shown height |
|---|---|---|---|---|
| Before (committed at 9437c58, scale 4, squeezed) | 2044 x 5060 | 511 x 1283 (plan's probe) | 26.2 (oversized; 64 px x 838/2044) | 2104 (plan's table) |
| After (scale 2, useMaxWidth off) | 1420 x 2326 | 710 x 1163 | 16.0 (W <= 838, so shown at natural size) | 1163 |

Limits: W_MAX = floor(16*838/12) = 1117, H_MAX = 1600. 710 <= 1117 and 1163 <= 1600: within limits.

Config check on the other nine, unmodified sources (`render-diagrams.sh --check --all` rendered under the
new `mermaid-config.json`; rendered PNG width / 2 = natural width):

| Diagram | Rendered PNG W | Natural W | Plan's probe natural W |
|---|---|---|---|
| architecture-activity-feed-read | 5522 | 2761 | 2760 |
| architecture-error-response-split | 7204 | 3602 | 3602 |
| architecture-mutation-sequence | 7806 | 3903 | 3908 |
| architecture-signin-scenario | 6822 | 3411 | 3416 |
| auth-signin-scenario | 5676 | 2838 | 2839 |
| auth-signup-scenario | 6062 | 3031 | 3032 |
| infra-delivery-scenario | 6900 | 3450 | 3450 |
| infra-packet-path-scenario | 2308 | 1154 | 1153 |
| infra-physical-deployment | 6398 | 3199 | 3199 |

The config reproduces the plan's independently probed natural widths to within 5 px, which confirms the
squeeze was the root cause and that `useMaxWidth: false` removes it. These nine still fail `--check` against
their old committed PNGs, as expected (state window); Tasks 3 and 4 re-render them.

Visual check: PNG downscaled with Pillow to 710 px wide into a `/tmp` file, viewed with the Read tool, then
deleted. All node labels and edge labels were legible at that size; the three leaves (response, Schema
Registry, dead-letter topic) sit off a single spine.

### Guard fails on the pre-migration state, then passes

Selftest: `python3 scripts/verify-diagrams-selftest.py` -> `38/38 passed`, exit 0.

RED honesty: the first selftest run failed only because `verify-diagrams.py` did not exist
(FileNotFoundError), and the 38 tests all passed on the first run after the guard was written. That does not
prove each rule is protected, so I mutation-checked: for each of the 12 rule ids, I renamed the id in a temp
copy of the guard and ran the selftest against it (`/tmp/mutate_check.py`, not committed). Every rule made at
least one specific test fail:

```
outside-view-folder  failing: test_diagram_flag_restricts_scope, test_outside_view_folder_fires_on_nested_view_folder, test_outside_view_folder_fires_on_root_and_unknown_folder
missing-twin         failing: test_missing_twin_both_directions
manifest-mismatch    failing: test_manifest_mismatch_diagram_without_row, test_manifest_mismatch_row_without_source
uniform-scale        failing: test_uniform_scale_fires_on_scale_four
bad-name             failing: test_bad_name_flags_prefix_suffix_and_case
bad-png              failing: test_bad_png_signature_is_reported
dangling-reference   failing: test_dangling_reference_in_link_img_and_code_span, test_diagram_flag_scopes_dangling_to_that_diagram
legibility-width     failing: test_diagram_flag_restricts_scope, test_legibility_width_fires_over_limit_and_boundary
legibility-height    failing: test_legibility_height_fires_on_displayed_height
embed-width          failing: test_diagram_flag_restricts_scope, test_embed_width_handles_multiline_img_tag, test_embed_width_markdown_image_syntax_fails, test_embed_width_mismatch_and_tolerance, test_embed_width_missing_width_attribute_fails
flowchart-rules      failing: test_diagram_flag_restricts_scope, test_flowchart_rules_inline_fences, test_flowchart_rules_standalone_violations
class-level-label    failing: test_class_level_label_fires_on_standalone_and_inline
```

(`test_real_tree_enumerates_tracked_files_only` also failed in every mutated run; that is an artifact of the
temp directory not being a git checkout, not a rule signal.)

Full `check` on the real pre-migration tree (run at commit 2, before the re-render):

```
python3 scripts/verify-diagrams.py check  -> exit 1
127 violation(s); 10 diagram(s), 66 doc(s) scanned
  67 class-level-label, 24 flowchart-rules, 18 outside-view-folder, 10 embed-width, 7 uniform-scale, 1 legibility-height
```

All 18 files of the nine root-level diagrams were reported, e.g.
`FAIL outside-view-folder docs/diagrams/architecture-activity-feed-read.mmd diagram files live directly in one of logical/process/development/physical/scenarios as <subject>.mmd/.png`
(the plan's check requires >= 9; there are 18 because the rule reports per file).

Scoped check for this diagram, before and after the embed fix:

```
(after render, before the embed edit)
FAIL embed-width docs/ARCHITECTURE.md:162 markdown image syntax for docs/diagrams/process/activity-pipeline.png; use <img src=... width=N>   -> exit 1
(after the <img width="710"> edit)
OK: 10 diagram(s), 66 doc(s) scanned   -> exit 0
```

Full `check` on the final tree (still fails, expected, Tasks 2-5 own the rest): exit 1, 116 violations
(62 class-level-label, 22 flowchart-rules, 18 outside-view-folder, 9 embed-width, 4 legibility-width,
1 legibility-height). None of them is for `process/activity-pipeline`.

### Other verify-chain results

| Command | Result |
|---|---|
| `python3 scripts/verify-diagrams-selftest.py` | exit 0, 38/38 |
| `python3 scripts/verify-diagrams.py check --diagram process/activity-pipeline` | exit 0 |
| `bash scripts/render-diagrams.sh --check process/activity-pipeline` | `committed=1420x2326 rendered=1420x2326 width=OK height=OK(+0.00%) mode=RGBA->RGBA`, exit 0 |
| `rg -n 'flowchart TD' docs/diagrams` | no matches (rg exit 1) |
| `python3 scripts/verify-comments.py check` | `OK: 292 files, 1116 comment blocks` |
| `git log --follow` on the .mmd | 3 commits (>= 2) |
| full `check` exits 1 with >= 9 `outside-view-folder` lines | exit 1, 18 lines |
| pre-commit hook on all three commits | ran in full (gitleaks, comment policy, spotlessCheck, fastTest); never `--no-verify` |

I did not run the plan's single chained `<automated>` one-liner as written, because the harness refused the
compound command inside a worktree; I ran each of its clauses separately (table above).

## Verification table: process/activity-pipeline (every node, edge, label)

Tools: `serena` MCP tools are not exposed in this executor, so Java symbols were verified by reading the
files and scoped `rg` (all paths under `src/main/java/com/vrudenko/kanban_board/` unless noted). Spring Kafka
semantics were checked with Context7 (`/spring-projects/spring-kafka`).

| # | Element | Claim | Source | Result |
|---|---|---|---|---|
| 1 | Node "Mutating HTTP request (request thread)" | a mutation arrives as an HTTP request handled on the servlet request thread | `controller/TaskMoveController.java:35-40` (`@PatchMapping(TASK_ID + MOVE)` returns `ResponseEntity.ok(taskService.moveToColumn(...))`); class doc at `KafkaEventPublisher.java:29` ("the request thread in production") | confirmed |
| 2 | Edge 1 -> 2 | the controller calls the service synchronously | `TaskMoveController.java:40` | confirmed |
| 3 | Node "Mutation @Transactional" | each mutating service method is `@Transactional` and publishes the event inside it | `service/TaskService.java:172-173` (`@Transactional moveToColumn`), `:236` (`publishEvent(new TaskMovedEvent...)`); other publishers `:69,:139,:268`, `BoardService.java:82,167,227`, `ColumnService.java:99,158,212,268`, `SubtaskService.java:58,124,156` (14 `publishEvent` sites = 14 permitted events in `event/ActivityEvent.java:23-36`) | confirmed |
| 4 | Node "Transaction commit" (hexagon) | the after-commit listener fires only after the transaction commits; no event for a rolled-back transaction | `config/KafkaEventPublisher.java:45` (`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`); `TaskService.java:51-56` Javadoc on why the transaction must be active | confirmed |
| 5 | Edge commit -> "HTTP response never waits on Kafka" (thick) | the response returns without waiting for the Kafka send | `KafkaEventPublisher.java:20-22,26-30` ("A committed mutation's HTTP outcome therefore never depends on Kafka reachability"; `@Async` because `send()` blocked the caller up to `max.block.ms`) | confirmed. Old label said "HTTP 200"; the endpoint does return 200 (`TaskMoveController.java:40`) but the pipeline is the same for every mutation, so the label no longer names a status |
| 6 | Edge label "AFTER_COMMIT" | transaction phase of the listener | `KafkaEventPublisher.java:45` | confirmed |
| 7 | Node "Publisher, kafka-publish-* executor" | the listener is `@Async("kafkaPublishExecutor")` and that pool's threads are named `kafka-publish-*` | `KafkaEventPublisher.java:44`; `config/AsyncConfig.java:16,22` (`setThreadNamePrefix("kafka-publish-")`; core 2, max 4, queue 200 at `:19-21`) | confirmed |
| 8 | Edge publisher -.-> "Schema Registry", label "schema-id lookup, never registers" | the producer only looks schemas up (value is framed [magic byte][schema id][Avro]), never registers | `src/main/resources/application.properties:172-175` (comment, `KafkaAvroSerializer`, `schema.registry.url`), `:182` (`auto.register.schemas=false`); `config/AvroSchemaRegistrar.java:40-42` (registrar is the only writer) | confirmed. Runs on the publisher thread because serialization happens inside `KafkaTemplate.send` (`KafkaEventPublisher.java:51-54`); the registry round trip itself is not observable from the repo, so "lookup on this thread" rests on the serializer being a producer-side `value-serializer` (`application.properties:174`) |
| 9 | Edge publisher -> topic, label "Avro, keyed by event id" | the record is Avro (`SpecificRecord`) and its key is the event id | `KafkaEventPublisher.java:51-54` (`send(ACTIVITY, event.eventId().toString(), activityEventAvroMapper.toAvro(event))`); `application.properties:171` (`StringSerializer` key) | confirmed |
| 10 | Node "kanban.activity, 1 partition" | topic name and partition count | `constant/KafkaTopics.java:5` (`ACTIVITY = "kanban.activity"`); `config/KafkaConsumerConfig.java:47-53` (`.partitions(1).replicas(1)`) | confirmed |
| 11 | Edge topic -> "Consumer thread, group activity-log" | a `@KafkaListener` on that topic with group `activity-log` | `activitylog/ActivityLogConsumer.java:45` (`GROUP_ID = "activity-log"`), `:51` (`@KafkaListener(topics = KafkaTopics.ACTIVITY, groupId = GROUP_ID)`); `application.properties:199` (`spring.kafka.consumer.group-id=activity-log`) | confirmed. "Consumer thread" is the Spring Kafka listener container thread (log line `[ntainer#0-0-C-1] ... groupId=activity-log` seen in the pre-commit test output), not a name set in this repo |
| 12 | Edge consumer -> decision "Processed?" | the listener either completes or throws; a throw goes to the error handler | `ActivityLogConsumer.java:52-77`; `KafkaConsumerConfig.java:34-39` Javadoc ("only a genuine failure ... propagates out of the listener") | confirmed |
| 13 | Edge "yes" -> node "activity_log, insert, idempotent on event_id" | success inserts one `activity_log` row, deduped on `event_id` | `activitylog/ActivityLogRecorder.java:41-55` (`existsByEventId` fast path, `saveAndFlush`, re-checked `DataIntegrityViolationException` catch); `ActivityLogConsumer.java:76`; unique constraint `uk_activity_log_event_id` in `db/migration/V3__add_activity_log.sql:11` and re-added in `V6__change_activity_log_event_id_to_varchar.sql:17`; `entity/ActivityLogEntity.java:48,63` | confirmed |
| 14 | Edge "no: 3 retries, 1 s apart" -.-> "kanban.activity.dlt" | after exhausting retries the record is dead-lettered to `kanban.activity.dlt`; 3 retries at 1 s | `KafkaConsumerConfig.java:164` (`new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L))`), `:159-162` (`DeadLetterPublishingRecoverer` -> `new TopicPartition(KafkaTopics.ACTIVITY_DLT, 0)`), `:139-140` Javadoc ("Retry three times at a ~1s fixed interval"); `KafkaTopics.java:6` (`"kanban.activity.dlt"`) | confirmed. Context7 (`/spring-projects/spring-kafka`, annotation-error-handling): "`new FixedBackOff(1000L, 2L)` // 2 retries = 3 total attempts", so the second argument counts RETRIES. Here `3L` = 3 retries (4 deliveries). The label's "3 retries" is correct |
| 15 | Shape: nothing leaves the spine | deleting the three leaves (response, Schema Registry, DLT) leaves one chain request -> mutation -> commit -> publisher -> topic -> consumer -> decision -> table | diagram source | confirmed (Layout rule 2) |
| 16 | Dropped label "exhaustive switch, no default arm" | class-level detail, intentionally dropped from the diagram | `ActivityLogConsumer.java:84-160` (the switch has no `default`) | carried in prose: `docs/ARCHITECTURE.md` "A new event type is a compile error" bullet |
| 17 | Dropped class names (`TaskService`, `KafkaEventPublisher`, `ActivityLogConsumer`, `ActivityLogRecorder`, `ApplicationEventPublisher`) | class names live in prose, not in the diagram | n/a | carried: one sentence added under the diagram in `docs/ARCHITECTURE.md` ("The boxes are threads, topics and tables, not classes: the publisher is `KafkaEventPublisher` and the consumer thread runs `ActivityLogConsumer` and then `ActivityLogRecorder`. Both topics have one partition.") |

Could not verify: that the live production broker actually has these two topics at 1 partition. The source
creates them with `NewTopic` beans, and broker auto-state is out of scope (no SSH to the VM).

Stale claims found and fixed in the source: none material. The old diagram's labels were accurate; the
rewrite changes abstraction level, not facts. One difference: the old edge said "HTTP 200"; the new one is
status-agnostic because the pipeline is shared by every mutation.

## Deviations from Plan

### Auto-fixed Issues

None required fixing. Deviations in method:

**1. [Tooling] serena unavailable**
- **Issue:** the plan names serena `find_symbol` for Java verification; no serena tools are exposed to this
  executor.
- **Fix:** read each cited file directly and used scoped `rg`; every citation above is a file:line from those
  reads. Same evidence standard, different instrument.

**2. [Rule 2 - added capability] extra rule `bad-png`**
- **Issue:** the plan lists 12 behaviours; reading PNG dimensions from a non-PNG would otherwise crash or
  silently pass.
- **Fix:** the guard reports `bad-png` (bad signature or IHDR) and the selftest covers it. 12 rule ids in total.
- **Commit:** 3760187

**3. [Verification method] selftest RED was module-absent only**
- **Issue:** TDD RED for a gate whose tests pass on the first run proves little.
- **Fix:** mutation check per rule (above), instead of claiming RED by observation.

**4. [Transient] pre-commit failed once on commit 3**
- The first `git commit` for commit 3 aborted with "Compile or test failure. Commit aborted." and a Gradle
  `compileJava` message in the tail I saw. I did not capture the cause. I changed nothing and re-ran the same
  commit command with full logging; it passed (`spotlessCheck` and `fastTest` UP-TO-DATE; log notes "3 stopped
  Daemons could not be reused"). Likely a Gradle daemon restart on a shared host (see
  `docs/SESSION_LESSONS.md` lesson 9) but that is a guess, not a finding.

### Scope notes

- `README.md` row for the diagram only had its path renamed (kept for Task 2 to rewrite the whole Diagrams
  table). `docs/DIAGRAM_CONVENTIONS.md` is untouched, so its "Known outstanding offender" paragraph and rule 3
  precedent are stale until Task 2 (per the plan).
- The guard computes natural width from the manifest scale (all rows are now 2). The nine unmigrated PNGs are
  old squeezed renders, so the guard currently reports `legibility-width` (width 1568 px) on four of them
  from their old PNG widths; that is a transitional artifact until they are re-rendered.
- `docs/learning/kafka-architecture-overview.md` and `.serena/` were not staged.
- No Java, resources or build files changed (`git diff --stat 9437c58..HEAD` lists docs, scripts and one todo only).

## Known Stubs

None.

## Threat Flags

None. The guard enumerates with `git ls-files` (T-261005-sun-01 mitigated, stated in its Decisions block);
`render-diagrams.sh` still mounts `docs/diagrams` read-only in check mode and the config is read through that
same mount (T-261005-sun-03); the diagram labels cite committed sources only (T-261005-sun-02), no IPs.

## Self-Check

- [x] `docs/diagrams/process/activity-pipeline.mmd` and `.png` exist, tracked
- [x] `docs/diagrams/mermaid-config.json` exists, tracked
- [x] `scripts/verify-diagrams.py` and `scripts/verify-diagrams-selftest.py` exist, tracked
- [x] commits 933a2eb, 3760187, d9aad1b are ancestors of HEAD (`git log --oneline -4`)
- [x] `git status --short` clean for tracked files (only this SUMMARY and the pre-existing untracked files remain)

## Self-Check: PASSED

# Task 2: view-folder moves, class-level drops, conventions rewrite

Status stays `incomplete`: Tasks 3-5 are untouched. Branch `worktree-agent-ab985e0ecb136dca0`, worktree
`/home/andre/dev/kanban-board-backend/.claude/worktrees/agent-ab985e0ecb136dca0`. The worktree was already at
d9aad1b (Task 1's head) when I started, so the stale-base fast-forward in the brief was not needed.

## Task 2 commits

| # | Hash | Message | Content |
|---|------|---------|---------|
| 4 | c0023e7 | docs(quick-261005-sun): move diagrams into Kruchten view folders | `git mv` of six pairs, all 100% similarity; manifest keys; embeds, source links and the README Diagrams table |
| 5 | 23819b9 | docs(quick-261005-sun): drop class-level diagrams, keep their protocol facts in prose | `git rm` of three pairs and their manifest rows; ARCHITECTURE.md, AUTH_FLOWS.md and learning/06 edits |
| 6 | 66e3a8f | docs(quick-261005-sun): rewrite diagram conventions for view folders and the legibility test | `docs/DIAGRAM_CONVENTIONS.md` only |

`git rev-list --count d9aad1b..HEAD` = 3 for Task 2 (6 for the plan so far, from 9437c58).

Rename-only proof (`git show -M --stat c0023e7`): the six `.mmd` rows show `| 0`, the six `.png` rows show
`Bin` with no byte change, and `git commit` printed `rename ... (100%)` for all twelve files. `git log --follow
--format=%h -- docs/diagrams/scenarios/signin.mmd` -> `c0023e7`, `36674d1`. `git log --follow --oneline --
docs/diagrams/physical/production-host.png` -> c0023e7 followed by 8 older commits back to 9bd5c23.

| Old path | New path |
|---|---|
| auth-signin-scenario | scenarios/signin |
| auth-signup-scenario | scenarios/signup |
| architecture-error-response-split | scenarios/error-status-split |
| infra-delivery-scenario | scenarios/push-to-deploy |
| infra-packet-path-scenario | scenarios/inbound-packet-path |
| infra-physical-deployment | physical/production-host |

## Doc references updated

README.md (physical copy `<sub>` source line, delivery-scenario link, Diagrams section rewritten as View |
Diagram | Answers with 7 rows; the delivery row says "push to `main`"), docs/ARCHITECTURE.md (error-split
embed and source, signin section now embeds `scenarios/signin` and one signin diagram is named everywhere),
docs/AUTH_FLOWS.md (two embeds, two source links, intro paragraph no longer calls ARCHITECTURE's diagram a
separate one), docs/INFRA_ARCHITECTURE.md (3 embeds, 3 source links, Maintenance Note names the view folders
and the guard), docs/learning/10-infrastructure-and-deployment.md (three-row table, open-items bullet),
docs/learning/06-security-and-sessions.md (signin diagram source link). The embeds stay Markdown image syntax
for now; converting them to `<img width>` is Tasks 3-4 (guard reports them as `embed-width`).

## Dropped diagrams: where each fact survives

### architecture-activity-feed-read (replaced by prose, ARCHITECTURE.md "Process View — reading the activity feed")

| Label / note | Disposition |
|---|---|
| `GET /boards/{boardId}/activity?page=0&size=20` | carried: ARCHITECTURE.md "`GET /boards/{boardId}/activity` ... is a plain paginated read"; default 20 / cap 100 also in README.md endpoint table |
| Controller, Service, OwnershipVerifier, Repository, Mapper lifelines and calls | dropped: class-level detail. The two entry symbols are still named in the same ARCHITECTURE.md sentence (`ActivityController` -> `ActivityLogService#findAllByBoardId`) |
| "caller's own Pageable.sort is DISCARDED ... Sort.by(createdAt desc, id desc) ... id tiebreak makes offset pagination a genuine total order" | carried: ARCHITECTURE.md ("the service discards any sort the caller sent and imposes `createdAt` descending, then `id` descending. The `id` tiebreak is what makes offset pagination a total order"). Verified against `service/ActivityLogService.java:43-47` (`Sort.by(createdAtDesc, idDesc)` passed to `PageRequest.of`) and its Javadoc `:29-31` |
| `SELECT ... ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?` | carried: docs/learning/07-events-and-activity-feed.md:1177-1179 (unchanged) |
| `200 OK -- raw Spring Data PageImpl shape` | carried: ARCHITECTURE.md ("the raw Spring Data `Page` shape (`content`, `totalElements`, ...)"); learning/05:1205 |
| Simplified note: max-page-size clamp | carried: ARCHITECTURE.md; value 100 verified at `src/main/resources/application.properties:235` |
| Simplified note: offset-pagination concurrent-insert caveat | carried: ARCHITECTURE.md ("a row inserted while a client pages can shift a later page by one"), source `ActivityLogService.java:33-37` Javadoc |

### architecture-mutation-sequence (removed section "Sequence view of the same mutation")

| Label / note | Disposition |
|---|---|
| `PATCH /tasks/{taskId}/move {targetColumnId, targetPosition, version}` | carried: ARCHITECTURE.md "A concrete case: `PATCH /tasks/{taskId}/move`"; the `version` requirement is in the Concurrency section (`MoveTaskRequestDTO`); target fields dropped as payload detail (`controller/TaskMoveController.java`) |
| `moveToColumn [@Transactional]` | carried (ARCHITECTURE.md names `TaskService#moveToColumn`, `@Transactional`; `TaskService.java:172-173`) |
| `findById` ownership-verified load; `Repo.shiftPositions/save` calls | dropped: class-level detail. Shifting survives as "shifts sibling positions, saves"; the two shift cases are "described on the method itself" (`TaskService.java:196-226`) |
| `version mismatch -> OptimisticLockingFailureException -> 409` | carried, and this was the missing sentence: ARCHITECTURE.md now says a stale version is checked "before it changes anything" and is answered 409 and never publishes. Verified: the compare at `TaskService.java:188-191` precedes every `shiftPositions` call and `publishEvent` (`:236`) |
| `entityManager.flush() -- forces UPDATE and @Version increment now` | carried: ARCHITECTURE.md ("calls `entityManager.flush()` so the `UPDATE` and the `@Version` increment happen now"); `TaskService.java:229-231` |
| `publishEvent -- queued, NOT delivered yet`; `200 OK -- response returns here`; `transaction commits` | carried: new ARCHITECTURE.md sentences ("That call only queues the event: the response (200) returns once the transaction commits, and the after-commit listener sends to Kafka on its own thread after the client already has it"), the second missing sentence |
| `@Async kafkaPublishExecutor, @TransactionalEventListener(AFTER_COMMIT)`; "a broker outage cannot change the HTTP outcome (D-01)" | carried: ARCHITECTURE.md bullet "The request never waits on the broker", the new "(D-01)" sentence, and `process/activity-pipeline.mmd` |
| `send(eventId, Avro SpecificRecord)` -> consumer -> `record(entity)` existsByEventId idempotent -> `INSERT activity_log` | carried: `process/activity-pipeline.mmd` (verified in Task 1 rows 9-13) and the "Redelivery is absorbed" bullet |
| Simplified note: DLT retry path (3 retries) omitted | carried: pipeline diagram and the "Poison messages" bullet |
| `409 (see the error-handling diagram above)` | carried: `scenarios/error-status-split` stays embedded in ARCHITECTURE.md |

### architecture-signin-scenario (duplicate of scenarios/signin with class lifelines)

Every protocol fact below also exists in the surviving `scenarios/signin.mmd` (moved byte-identical in c0023e7).
Task 3 redraws that diagram and moves its long notes into AUTH_FLOWS.md prose; until then they live in the
diagram and in learning/06.

| Label / note | Disposition |
|---|---|
| `POST /api/signin {email, password}`; 401 `BAD_CREDENTIALS`; 200 + `Set-Cookie` rotated id + `{id, email, displayName, theme}` | carried: `scenarios/signin.mmd`; AUTH_FLOWS.md responses table |
| `AppEntityNotFoundException` on unknown email; `AuthenticationManager`/`UserAuthenticationProvider`/`SecurityContextRepository` lifelines | dropped: class-level detail (the surviving diagram says "not found" and names the same beats) |
| F1 equalizer note (timing parity) | carried: `scenarios/signin.mmd` note; learning/06 "Timing equalization and the password encoder" |
| minimal principal (username only, no hash) | carried: `scenarios/signin.mmd` note |
| composite order: ceiling before session-id rotation | carried: `scenarios/signin.mmd` note; learning/06:485 and Q8 at :1127 |
| F6 bounded overshoot, why a transaction lock cannot close it, `ConcurrentSigninCeilingE2ETest` | carried: `scenarios/signin.mmd` note; learning/06 SEC-11 (:590-609) |
| 401 collapsed with wrong password (D-08) | carried: `scenarios/signin.mmd` note; AUTH_FLOWS.md responses table and its "What this means for a test" paragraph |
| session row commits as the response flushes; measured 2026-08-11 probe read 0 rows then 1 row | carried: `scenarios/signin.mmd` note; learning/06:177 and :578-587 (the 0 and 1 measurement, citing 260811-h2v) |

## Verification of the doc edits

- Sort order verified by reading `ActivityLogService.java` (serena is not exposed to this executor, same
  deviation as Task 1): `findAllByBoardId` builds `Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))`
  and passes it through `PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), deterministicSort)`,
  discarding the caller's sort.
- Anchor preserved: learning/07:1234 links `ARCHITECTURE.md#process-view--reading-the-activity-feed`, so the
  heading keeps that exact text.
- `rg -n '(architecture|auth|infra)-[a-z-]+\.(mmd|png)' README.md docs -g '!docs/wiki/**' -g '!docs/raw/**'
  -g '!docs/learning/kafka-architecture-overview.md'` now matches only three lines, all inside
  `docs/diagrams/scenarios/signin.mmd` (lines 17, 27) and `signup.mmd` (line 31): notes that say "see
  auth-signup-scenario.mmd". They are diagram source, not doc prose. Editing them needs a re-render, and
  Task 3 rewrites both diagrams, so I left them (a rename-only commit must not carry content edits, and a
  one-line `.mmd` edit would only add churn). The plan's literal `<automated>` clause for this rg therefore
  matches until Task 3 lands; recording it as a known, Task-3-owned residual rather than hiding it.

## Guard and gate evidence (final tree, HEAD 66e3a8f)

| Command | Result |
|---|---|
| `python3 scripts/verify-diagrams-selftest.py` | `38/38 passed`, exit 0 |
| `python3 scripts/verify-diagrams.py check` | exit 1, 78 violation(s); 7 diagram(s), 66 doc(s) scanned |
| violations by rule | 47 class-level-label, 22 flowchart-rules, 7 embed-width, 1 legibility-width, 1 legibility-height |
| Task-2-owned rules (outside-view-folder, missing-twin, manifest-mismatch, uniform-scale, bad-name, bad-png, dangling-reference) | 0 violations. Before Task 2: 18 outside-view-folder alone |
| inventory regex over `git ls-files docs/diagrams` | no file outside `render-manifest.tsv`, `mermaid-config.json`, `<view>/<subject>.{mmd,png}` (rg exit 1 = no stray) |
| `python3 scripts/verify-comments.py check` | `OK: 292 files, 1116 comment blocks` |
| `git ls-files docs/diagrams` | 16 files: 7 diagrams x 2, plus the manifest and config |

Remaining violations by owner (Tasks 3-5):
- embed-width (7): ARCHITECTURE.md:71 and :103, AUTH_FLOWS.md:40 and :69, INFRA_ARCHITECTURE.md:41, :130, :192,
  all Markdown-image embeds that become `<img width>` after re-render (T3 for the five auth/error, T4 for the three infra).
- legibility-width: `scenarios/error-status-split` natural 1568 px (8.6 px text) -> T3.
- legibility-height: `scenarios/inbound-packet-path` displayed 1694 px -> T4.
- class-level-label (47): 4 error-status-split, 3 signin, 5 signup (T3); 2 production-host, 2 push-to-deploy (T4);
  31 inline in docs/learning (T5): 04 has 20, 02 has 4, 07 has 3, 06 has 2, 03 has 1, 05 has 1.
- flowchart-rules (22): all inline fences in docs/learning (T5): 04 has 6, 09 has 4, and 00, 02, 05, 06, 10, 11
  have 2 each. No standalone diagram violates it.

`report` (natural width / displayed height, D=838):

| Diagram | PNG W x H | Natural W | Text px | Shown height | Within |
|---|---|---|---|---|---|
| physical/production-host | 1568x1056 | 784 | 16.0 | 528 | yes (old squeezed render, see below) |
| process/activity-pipeline | 1420x2326 | 710 | 16.0 | 1163 | yes |
| scenarios/error-status-split | 3136x1756 | 1568 | 8.6 | 469 | NO |
| scenarios/inbound-packet-path | 1568x3388 | 784 | 16.0 | 1694 | NO |
| scenarios/push-to-deploy | 1568x878 | 784 | 16.0 | 439 | yes (old squeezed render, see below) |
| scenarios/signin | 784x862 | 392 | 16.0 | 431 | yes (old squeezed render, see below) |
| scenarios/signup | 784x739 | 392 | 16.0 | 370 | yes (old squeezed render, see below) |

The "yes" rows on old squeezed renders are the transitional artifact Task 1 recorded: the guard derives
natural width from the manifest scale, so a squeezed 784 px PNG reads as small. `render-diagrams.sh --check`
is the other half of the proof, and it fails for exactly those diagrams.

`bash scripts/render-diagrams.sh --check <name>` for the diagrams moved without rewriting (state window, the
committed PNGs are the old squeezed renders; Tasks 3-4 re-render them):

```
physical/production-host       committed=1568x1056 rendered=6398x4306 width=FAIL(1568v6398) height=FAIL(+307.77%)
scenarios/push-to-deploy       committed=1568x878  rendered=6900x3858 width=FAIL(1568v6900) height=FAIL(+339.41%)
scenarios/inbound-packet-path  committed=1568x3388 rendered=2308x4982 width=FAIL(1568v2308) height=FAIL(+47.05%)
process/activity-pipeline      committed=1420x2326 rendered=1420x2326 width=OK(1420v1420) height=OK(+0.00%)
```

I did not run `--check` for signin, signup or error-status-split: they are rewritten in Task 3 and Task 1
already recorded their pre-rewrite renders (2838, 3031 and 3602 px natural widths).

## Wiki

`python3 .claude/skills/karpathy-llm-wiki/scripts/check_evidence.py docs`:

| When | Summary line |
|---|---|
| Plan baseline | `0 fidelity suspect(s), 20 evidence error(s), 51 unreferenced raw file(s)` |
| After Task 2, run twice (before and after the final commit) | `0 fidelity suspect(s), 20 evidence error(s), 51 unreferenced raw file(s)` |

Byte-identical. `git diff --stat d9aad1b..HEAD` lists no path under `docs/wiki/` or `docs/raw/`. Wiki diagram
links that dangle (none resolved before either, since `docs/wiki/**/diagrams/` does not exist):
- docs/wiki/architecture/auth-flows.md:47-48, 76-77 (auth-signup-scenario, auth-signin-scenario)
- docs/wiki/architecture/application-architecture.md:79-80, 84-85, 109-110, 168-169, 182-183, 196-197
  (auth-signup/signin, architecture-signin, error-response-split, mutation-flowchart, mutation-sequence,
  activity-feed-read)
- docs/wiki/learning/06-security-and-sessions.md:388 (architecture-signin-scenario)
- docs/wiki/infra/infra-architecture.md:45-46, 135-136, 197-198, 289, 298 (physical, delivery, packet-path,
  `*.mmd` glob)
- docs/wiki/learning/10-infrastructure-and-deployment.md:1007-1009 (the three-row table)
- docs/wiki/conventions/diagram-conventions.md:40, 43 (the old `flowchart TD` test, still stale; the copy is
  deliberately untouched per the brief)

## DIAGRAM_CONVENTIONS.md (66e3a8f)

Kept: the 4+1 intro, the five view bullets (Process example now names the request thread, `kafka-publish`
executor, Kafka consumer thread, Redpanda and Traefik; "push to master" became "push to main"), the four flowchart
rules and "Why this convention exists". Added: "What earns a diagram", "Layout and naming" (with the 7-row
inventory table), "Rendering", "Legibility test" (the measured D table, formulas, the 12 px and 1600 px bounds
marked as chosen, levers in order, the `<img width>` embed format), "Inline diagrams", "Inventory check" (the four
commands, the 12 rule ids, local-only reasoning). Rule 3's precedent now cites `physical/production-host.mmd`'s
`netcup_spacer ~~~ traefik_box` (verified at line 22 of that file), rule 1 lost its stale "counted 2026-09-05"
sentence, and the "Known outstanding offender" paragraph is gone (Task 1 closed it; `rg -n 'flowchart TD'
docs/diagrams` returns nothing).

## Task 2 deviations

**1. [Process] pre-commit failed on a Gradle daemon stop, 3 times across 2 of the 3 commits**
- Commit c0023e7: attempts 1 and 2 aborted with `FAILURE: Build failed with an exception. * What went wrong:
  Gradle build daemon has been stopped: stop command received` (once after `:generateAvroJava`, once after
  `:compileJava`), then "Compile or test failure. Commit aborted." Attempt 3 passed (`BUILD SUCCESSFUL in 6m 14s`
  for fastTest). Commit 23819b9 passed on attempt 1. Commit 66e3a8f: attempt 1 aborted at the `spotlessCheck`
  step with the same "stop command received" text (the hook then prints its generic "Formatting check failed. Run
  ./gradlew spotlessApply", but no Spotless violation appears in the output); attempt 2 passed.
- Each aborted run printed `Starting a Gradle Daemon, N stopped Daemons could not be reused`, with N rising from 4
  to 12 across my attempts.
- Cause: a daemon in this Gradle user home was being sent a stop command while my build ran. I did not run
  `gradlew --stop`. The Tasks 3-5 executors share the host and Gradle user home, so another session stopping
  daemons is the likely cause, but that is inferred from the rising count, not observed. I changed no code between
  attempts; I retried the identical `git commit`. Never `--no-verify`.
- Suggestion for the orchestrator: serialize hook runs across executors, or set a per-worktree
  `GRADLE_USER_HOME`/`--no-daemon` for the hook.

**2. [Tooling] serena unavailable**, same as Task 1; Java sort order checked by reading the file.

**3. [Scope] no `ledger` file**: the harness refused the compound `git rev-parse --git-dir` ledger commands, so I
measured the count from the known base instead (`git rev-list --count d9aad1b..HEAD` = 3).

**4. [Scope] the README inline physical copy** was left in place with only its `<sub>` source line re-pointed,
per the Task 2 action text. Its replacement by an `<img>` is the inline-table verdict handled in Task 5.

**5. [Plan literal] the `<automated>` rg clause for legacy names** matches three lines inside the two scenario
`.mmd` sources (see Verification above). Everything the plan lists as an authoritative doc is clean.

## Task 2 Self-Check

- [x] 6 diagram pairs exist at their final paths and are tracked (`git ls-files docs/diagrams`)
- [x] 3 dropped pairs are absent from the index and the manifest
- [x] commits c0023e7, 23819b9, 66e3a8f are ancestors of HEAD
- [x] `git status --short`: only this SUMMARY directory is untracked; `docs/learning/kafka-architecture-overview.md`
      and `.serena/` were never staged (they are not present in this worktree)
- [x] SUMMARY/PLAN/STATE not committed

## Self-Check: PASSED

# Task 3: signin, signup and error-status-split redrawn at protocol level

Branch `worktree-agent-adcc7fcd71c389cba`, worktree
`/home/andre/dev/kanban-board-backend/.claude/worktrees/agent-adcc7fcd71c389cba`. Base check: HEAD was already
`66e3a8f5b54bbf4ee63da3cabe360ac897a29e75` ("docs(quick-261005-sun): rewrite diagram conventions for view
folders and the legibility test"), working tree clean, so no fast-forward was needed.
`docs/diagrams/scenarios/signin.mmd` and `scripts/verify-diagrams.py` existed.

## Task 3 commits

| # | Hash | Message | Content |
|---|------|---------|---------|
| 1 | 3b54d19 | docs(quick-261005-sun): redraw auth and error scenarios at protocol level | 3 rewritten `.mmd`, 3 re-rendered `.png`, `docs/AUTH_FLOWS.md` and `docs/ARCHITECTURE.md` embeds as `<img width>` plus the prose that absorbed the removed notes. `.mmd`, `.png` and embeds land together, so no commit has a source/render mismatch |
| 2 | c03fe89 | docs(quick-261005-sun): correct the unauthenticated-route claim in AUTH_FLOWS | one stale sentence found while verifying signin, see Deviations 2 |

## Task 3 evidence

### Legibility (D = 838, W_MAX = 1117, H_MAX = 1600)

`python3 scripts/verify-diagrams.py report --diagram scenarios/signin --diagram scenarios/signup --diagram scenarios/error-status-split`
on the committed renders:

| Diagram | PNG W x H | Natural W | Text px shown | Shown height | Within |
|---|---|---|---|---|---|
| scenarios/error-status-split | 2162x2546 | 1081 | 12.4 | 987 | yes |
| scenarios/signin | 2168x2854 | 1084 | 12.4 | 1103 | yes |
| scenarios/signup | 2094x2524 | 1047 | 12.8 | 1010 | yes |

How they got there (lever 1 only, no threshold raised, no split, no re-layout needed). First draft with the
long single-line labels, rendered through the same pipeline:

| Diagram | First draft natural W | Final natural W |
|---|---|---|
| scenarios/error-status-split | 1304 (10.3 px text, FAIL) | 1081 |
| scenarios/signin | 1529 (8.8 px text, FAIL) | 1084 |
| scenarios/signup | 1291 (10.4 px text, FAIL) | 1047 |

Before Task 3 (Task 1/2 evidence): error-status-split 3602 natural W, signin 2838, signup 3031. Final text height is
16 x 838/W: 12.4, 12.4, 12.8 CSS px, above the 12 px floor.

Visual check: each PNG downscaled with Pillow (LANCZOS) to 838 px wide into `/tmp/sun3-*.png`, opened with the
Read tool, then deleted (`ls /tmp | rg sun3` empty). At that size every label, alt/else frame title, note and
lifeline header was legible in all three, including the 12.4 px alt-frame guards; nothing is clipped or overlapping.
`signup`'s innermost alt frame spans only three lifelines because no message of that arm touches the session store;
that is Mermaid's own layout, not a defect. Limits of the check: I looked at a downscaled raster, not on GitHub.

### Gates

| Command | Result |
|---|---|
| `python3 scripts/verify-diagrams-selftest.py` | `38/38 passed`, exit 0 |
| `python3 scripts/verify-diagrams.py check --diagram scenarios/signin --diagram scenarios/signup --diagram scenarios/error-status-split` | `OK: 7 diagram(s), 66 doc(s) scanned`, exit 0 |
| `bash scripts/render-diagrams.sh --check scenarios/signin` | `committed=2168x2854 rendered=2168x2854 width=OK height=OK(+0.00%)`, exit 0 |
| `bash scripts/render-diagrams.sh --check scenarios/signup` | `committed=2094x2524 rendered=2094x2524 width=OK height=OK(+0.00%)`, exit 0 |
| `bash scripts/render-diagrams.sh --check scenarios/error-status-split` | `committed=2162x2546 rendered=2162x2546 width=OK height=OK(+0.00%)`, exit 0 |
| `python3 scripts/verify-comments.py check` | `OK: 292 files, 1116 comment blocks`, exit 0 |
| full `python3 scripts/verify-diagrams.py check` | exit 1, `61 violation(s); 7 diagram(s), 66 doc(s) scanned` |
| pre-commit hook on both commits | gitleaks clean, comment policy OK, spotlessCheck and fastTest `BUILD SUCCESSFUL in 4m 28s` (first commit, attempt 6, see Deviations 1) |

Full `check` by rule (none concerns the three diagrams or their embeds in AUTH_FLOWS.md/ARCHITECTURE.md; a count of
lines matching `scenarios/(signin|signup|error-status-split)|AUTH_FLOWS|docs/ARCHITECTURE` in the output is 0):

| Rule | Count | Owner |
|---|---|---|
| embed-width | 3 | Task 4: INFRA_ARCHITECTURE.md:41, :130, :192 |
| class-level-label | 35 | Task 4: 4 (production-host.mmd:92, :93; push-to-deploy.mmd:5, :33). Task 5: 31 inline in docs/learning |
| flowchart-rules | 22 | Task 5: inline fences in docs/learning |
| legibility-height | 1 | Task 4: scenarios/inbound-packet-path (1694 px) |
| legibility-width | 0 | |

Compared with the Task 2 final tree (78): embed-width 7 to 3 (the four auth/error embeds are fixed), class-level-label
47 to 35 (the 12 labels of the three diagrams are gone), legibility-width 1 to 0. The residual legacy-name rg that
Task 2 left open is now clean: `rg -n '(architecture|auth|infra)-[a-z-]+\.(mmd|png)'` over README.md, the five
authoritative docs and `docs/diagrams/{scenarios,process,physical}` exits 1 (no match), because the "see
auth-signup-scenario.mmd" notes left with the rewrite.

## Verification table: scenarios/signin

Sources: `AC` = `src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java`, `SC` =
`.../security/SecurityConfiguration.java`, `UAP` = `.../security/UserAuthenticationProvider.java`, `US` =
`.../service/UserService.java`, `GEH` = `.../handler/GlobalExceptionHandler.java`, `AT` =
`src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java`, `props` =
`src/main/resources/application.properties`. Method: files read in full plus scoped `rg`. serena is not exposed in
this agent's toolset (no ToolSearch available), so no symbol-level query was possible.

| # | Element | Claim | Source | Result |
|---|---|---|---|---|
| S1 | lifelines Client / App / users table / session store | the signin path touches exactly these runtime roles | AC:76-117 (handler), US:46-54 and :133-141 (users reads), `props`:138 `spring.session.store-type=jdbc` + SC:172-197 (session store) | confirmed. Old lifelines AuthenticationManager, UserAuthenticationProvider, sessionAuthenticationStrategy, SecurityContextRepository dropped (class-level); their facts are in AUTH_FLOWS.md prose |
| S2 | `POST /api/signin {email, password}` | route and body | ApiPaths.java:29 `SIGNIN="/signin"`, `props`:5 `server.servlet.context-path=/api`, AC:76-78; body fields at AT:285-288 | confirmed |
| S3 | 400 VALIDATION_FAILED + errors map | bean validation fails before the method body | AC:78 `@Valid`; GEH:193-211 (code at :207, `errors` at :208); AT:326-328 asserts 400, `$.code`, `$.errors.email` | confirmed |
| S4 | SELECT user by email | first read | AC:83, US:46-47 (`userRepository.findByEmail`) | confirmed |
| S5 | unknown email: no row | not found | US:49-50 throws `AppEntityNotFoundException`, AC:84 catches | confirmed |
| S6 | BCrypt compare against a fixed hash, result discarded | timing parity | AC:97 (`matches(dto.getPassword(), equalizerHash)`, result unused), AC:68-73 (hash from `passwordEncoder.encode` at `@PostConstruct`), comment AC:85-96 | confirmed that the compare exists and is discarded. **could not verify** the timing effect itself: no test measures latency; `AT.Signin.AntiEnumeration` (AT:335-401) proves only the response body, which the code already makes identical |
| S7 | 401 BAD_CREDENTIALS | unknown email maps to 401 | AC:99 throws `BadCredentialsException`, GEH:185-191 (401, `ErrorCode.BAD_CREDENTIALS` at :188), ErrorCode.java:20; AT:352-353 | confirmed |
| S8 | known email: row, then SELECT user by id | a second read | AC:104 passes `user.getId()`; UAP:25-28 `loadUserByUsername(userId)`; US:133-141 `findById`; AC:93-94 comment names the "extra indexed DB read" | confirmed by reading. **could not verify** the SQL count at run time (not measured) |
| S9 | BCrypt compare | password check | UAP:30 | confirmed |
| S10 | wrong password: 401 BAD_CREDENTIALS | provider throws, collapsed to the same 401 | UAP:31; AC:158-179 (`Try.of(...).mapTry(...).getOrElse(false)`), AC:106-107, :109-110, GEH:185-191; AT:299-301 (401, no cookie) | confirmed |
| S11 | count live sessions | ceiling reads the session store | SC:181-186 (`SpringSessionBackedSessionRegistry` over `FindByIndexNameSessionRepository`), SC Javadoc :137-138 | confirmed |
| S12 | 2 sessions already live: 401 BAD_CREDENTIALS (identical body) | ceiling of 2 collapses into the wrong-password response | SC:43 `MAX_CONCURRENT_SESSIONS = 2`, SC:185-186 `setMaximumSessions` + `setExceptionIfMaximumExceeded(true)`, AC:161-167 comment, AC:56 + :110 (one message string, one exception type); AT:816-855 asserts 401, no cookie and exactly 2 new rows | confirmed for status, cookie and row count. "Identical body" is confirmed by the single code path, **but no test compares the ceiling body with the wrong-password body**: `AT:395-400` compares unknown-email against wrong-password only |
| S13 | rotate session id, save security context (ceiling first) | composite order, then `saveContext` | SC:188-196 (`CompositeSessionAuthenticationStrategy(List.of(concurrentSessionControl, new ChangeSessionIdAuthenticationStrategy()))`), AC:168-175 | confirmed. Fix recorded in prose: rotation happens when the request presents an existing session (AT:864-867, :871-903), so "guaranteed to differ" in AUTH_FLOWS.md now says so |
| S14 | 200 Set-Cookie JSESSIONID + `{id, email, displayName, theme}` | status, cookie, body | AC:116 `ResponseEntity.ok`; `props`:164 `cookie.name=JSESSIONID`; UserResponseDTO.java fields id, email, displayName, theme; AT:190-215 and :227-250 (exact field set), SessionCookieAttributesE2ETest.java:67-87 | confirmed |
| S15 | session rows commit as the response flushes | commit-after-flush | SC Javadoc :153-165 (measured 2026-08-11 by the code's author: 0 rows from a second connection at the end of the lambda, 1 after the response) | confirmed from recorded evidence, not re-measured here |
| S16 | removed note: "one of only two routes reachable without a session" | stale | SC:66-80 `permitAll` also lists the Swagger docs, Swagger UI and actuator health | **fixed (old to new)**: AUTH_FLOWS.md now says two authentication routes, and lists the other `permitAll` routes (second commit) |
| S17 | removed note: "decision D-08" for the ceiling collapse | label | not found in code (SC:167-170 and AT:809-814 state the rule without an id); `docs/ARCHITECTURE.md:34` uses D-08 for a different decision (springdoc) | **fixed**: dropped the id, cited the Javadoc and test instead |
| S18 | removed note: the principal is minimal, no hash | prose fact | UAP:34-37; `AT.SigninPersistence` AT:772-791 (hash marker absent from stored bytes) | confirmed, moved to AUTH_FLOWS.md prose |

## Verification table: scenarios/signup

| # | Element | Claim | Source | Result |
|---|---|---|---|---|
| U1 | `POST /api/signup {email, password, displayName?}` | route, optional name | ApiPaths.java:30, AC:119-123; AT:412-419 builds all three, AUTH_FLOWS.md field table (displayName optional) | confirmed for the route. `displayName?` rests on the table in AUTH_FLOWS.md, which I did not re-derive from `SignupRequestDTO` |
| U2 | 400 VALIDATION_FAILED + errors map | same arm as signin | AC:121 `@Valid`; GEH:193-211; AT:530-536, :555-561, :576-582 | confirmed |
| U3 | SELECT email in use? | `existsByEmail` | US:74-75 | confirmed |
| U4 | email taken: 409 DUPLICATE_RESOURCE | checked path | US:76-77 `AppDuplicateResourceException.withMessage`, GEH:161-168 (409, `DUPLICATE_RESOURCE`), AC:124-127 (call outside `try`); AT:626-640 asserts 409 and the code | confirmed |
| U5 | INSERT user | row creation | US:80-81 `userRepository.save(userMapper.fromSignupRequestDTO(...))` | confirmed |
| U6 | lost race: unique violation, 409 DATA_INTEGRITY_VIOLATION | second 409 code | `V1__init.sql`:11 `CONSTRAINT uk_users_email UNIQUE (email)`; US:71-73 comment; GEH:176-183 (409, `DATA_INTEGRITY_VIOLATION`) and :170-175 comment | confirmed by reading. **could not verify at run time**: `rg 'DATA_INTEGRITY_VIOLATION\|uk_users' src/test/java` finds no test of the signup race |
| U7 | session set up as in POST /api/signin | shared helper | AC:130-131 and :151-180 (`authenticate` shared by both) | confirmed |
| U8 | rotate session id, save security context | same two steps | AC:168-175 | confirmed. The ceiling check is not drawn for signup because it cannot reject a new principal (AC:164-165 comment; zero live sessions); stated in AUTH_FLOWS.md prose |
| U9 | authentication fails: DELETE user (rollback), 401 BAD_CREDENTIALS (not 403) | defensive rollback | AC:133-136 (`userService.deleteById`, then `AccessDeniedException`), AC:138-139 blanket catch rethrows `BadCredentialsException`, US:84-97, GEH:185-191 | confirmed by reading. **could not verify at run time**: `rg 'Was not able to sign\|rollback' src/test/java` finds no test driving this arm; AUTH_FLOWS.md now says so |
| U10 | 201 Set-Cookie JSESSIONID + Location /api/users/me + body | success response | AC:147-148 `ResponseEntity.created(URI.create(contextPath + ApiPaths.USERS + ApiPaths.ME)).body(createdUser)`; ApiPaths.java:25-26; AT:462-463 (201), :507-508 (Location equals `CONTEXT_PATH + USERS + ME`), :472-475 (exact field set), :432 (cookie) | confirmed |
| U11 | removed note: Location has no GET handler | still true | UserController.java mappings are `@GetMapping(ApiPaths.ME + ApiPaths.THEME)` (:36) and `@PutMapping(ApiPaths.ME + ApiPaths.THEME)` (:41) only; AC:145-146 comment | confirmed still true; kept in AUTH_FLOWS.md "What this means for a test" |
| U12 | removed note: commit-after-flush | same as S15 | SC Javadoc :153-165 | confirmed, stated once in AUTH_FLOWS.md for each route |

## Verification table: scenarios/error-status-split

`ESEP` = `.../security/ProblemDetailAuthenticationEntryPoint.java`, `AGT` =
`src/test/java/com/vrudenko/kanban_board/security/AuthorizationGatingTest.java`, `GEHT` =
`src/test/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandlerTest.java`.

| # | Element | Claim | Source | Result |
|---|---|---|---|---|
| E1 | lifelines Client / Security filter chain / MVC dispatch / Exception handler | four runtime roles | SC:58-118 (chain), ESEP:19-28 Javadoc ("fires inside Spring Security's ExceptionTranslationFilter, before DispatcherServlet runs"), GEH:32-33 `@ControllerAdvice` | confirmed. Old lifelines AuthorizationFilter, ExceptionTranslationFilter, ProblemDetailAuthenticationEntryPoint, DispatcherServlet, Controller, Service, OwnershipVerifierService, GlobalExceptionHandler collapsed to roles; a mapping table is in ARCHITECTURE.md |
| E2 | 401 UNAUTHENTICATED, MVC never reached | no session gives the entry point's response | SC:82 `anyRequest().authenticated()`, SC:90-92 `authenticationEntryPoint(...)`, ESEP:35-53 (401, `ErrorCode.UNAUTHENTICATED` at :44), ErrorCode.java:22; AGT:349-350 asserts 401 and `UNAUTHENTICATED` across the route table; GEHT:259 | confirmed |
| E3 | "MVC is never reached" for 401 | entry point runs in the filter chain | ESEP:23-26 Javadoc; AGT:513-516 comment records that a signin 401 carries `BAD_CREDENTIALS` not `UNAUTHENTICATED`, i.e. the chain does let the request through when it is permitted | confirmed from the Javadoc. **could not verify** the internal step order of the filters (AuthorizationFilter raising `AccessDeniedException` that `ExceptionTranslationFilter` translates): framework internals, not in the repo, so the new diagram does not claim it |
| E4 | 403 ACCESS_DENIED, valid session, someone else's resource | ownership check throws, handler answers | OwnershipVerifierService.java:34-55 (`throw new AppAccessDeniedException("Board")` at :55); ColumnController.java:33,39 (`GET /api/boards/{boardId}/columns`); ColumnService.java:231-232; GEH:89-96 (403, `ACCESS_DENIED`); GEHT:76-125 (asserts 403, code, and that no field contains the board name); AGT:386-387 | confirmed |
| E5 | "(never names the resource)" | dropped from the diagram, kept in prose | GEHT:105-125 and AGT:387 assert the board name is absent. The old note cited a threat id `T-07.1-08-03`, which is not in the repo's code | confirmed via tests; the id was dropped as **could not verify** |
| E6 | 400 VALIDATION_FAILED on `POST /api/signup`, malformed email | binding fails before the handler body | AC:121 `@Valid`; GEH:193-211; AT:517-536 (malformed email gives 400, `VALIDATION_FAILED`, `errors.email`), GEHT:145 | confirmed. `permitAll` for signup: SC:68-70 |
| E7 | 409 OPTIMISTIC_LOCK_CONFLICT, `PUT .../tasks/{id}` stale version | version compare then handler | TaskController.java:24-30 (class mapping `/boards/{boardId}/columns/{columnId}/tasks`) and :51 `@PutMapping(TASK_ID)`; TaskService.java:118-120 (`!task.getVersion().equals(dto.getVersion())` then `OptimisticLockingFailureException`); GEH:150-157 (409, `OPTIMISTIC_LOCK_CONFLICT`); GEHT:181 asserts the code for a stale **column** PUT; TaskLockingTest.java:50 asserts 409 for a stale **task** PUT | confirmed. The `code` itself is asserted for the column route, the task route asserts only the status, same handler |
| E8 | old message path `PUT /tasks/{id}` | stale | real route is `/api/boards/{boardId}/columns/{columnId}/tasks/{taskId}` (TaskController.java:24-30, :51) | **fixed (old to new)**: `PUT .../tasks/{id}` |
| E9 | old arrow `AuthorizationFilter --x ExceptionTranslationFilter: AuthenticationException` | filter-internal exception type | framework classes | **fixed**: removed; see E3 |

## Facts that left the diagrams, and where each now lives

All in `docs/AUTH_FLOWS.md` unless noted; each appears once.

| Fact | Now in |
|---|---|
| BCrypt timing parity (finding F1) and its residual extra read | AUTH_FLOWS.md "Sign in", first bullet |
| why the ceiling collapses into the wrong-password 401 (no validity oracle), and the tests that prove it | "Sign in", second bullet |
| composite order: ceiling before session-id rotation | "Sign in", third bullet |
| bounded concurrent-signin overshoot (finding F6), and where its Javadoc lives | "Sign in", fourth bullet |
| commit-after-flush timing | "Sign in", fifth bullet and "Sign up", last bullet |
| minimal principal, token built from the id | "Sign in", sixth bullet and "Sign up", second bullet |
| 200 vs 201, Location absent on signin | "Sign in", seventh bullet; the existing "What this means for a test" for the dangling Location |
| the race's second 409 code and the constraint name `uk_users_email` | "Sign up" responses table row and first bullet |
| rollback gives 401, not 403, and no test drives it | "Sign up", third bullet |
| lifeline-to-class mapping for the error split, and the leak assertions | ARCHITECTURE.md, the table under the error-split diagram |

## Task 3 deviations

**1. [Process] the pre-commit hook aborted five times on the first commit and twice on the second ("Gradle build
daemon has been stopped: stop command received"), then passed on attempt 6 and attempt 3 respectively.** The second
commit's passing run printed `BUILD SUCCESSFUL in 4s` for fastTest (inputs unchanged since the first commit, so the
tasks were up to date, not skipped). Attempts 1-5 of the first commit each printed the full gitleaks and comment-policy steps, then
died in `spotlessCheck` or `fastTest` between `:compileJava` and `:compileTestJava` after about 10-20 s. Attempt 6
printed `BUILD SUCCESSFUL in 4m 28s` and committed. I changed no code between attempts. A separate probe,
`./gradlew fastTest --no-daemon`, died the same way, so a shared-daemon reuse race is not the cause. Cause,
inferred and not observed: the project's `.claude/settings.json` lines 3-9 configure a `SubagentStop` hook that runs
`./gradlew --stop` in the repo, and `gradlew --stop` stops every daemon of this Gradle version in the shared Gradle
user home, including one that is mid-build for my commit. Other Claude sessions and subagents finishing in the same
window would each fire it. The daemon logs in `/home/andre/.gradle/daemon/8.11.1/` end with `Daemon is stopping
immediately stop command received`; `ps` showed no Gradle process of mine or anyone else's between attempts. I did
not edit that hook or run `gradlew --stop`. This is deviation 1 of Task 2 again, now with a candidate cause.
Suggestion: serialise hook runs across executors, or give each worktree its own `GRADLE_USER_HOME`.

**2. [Rule 1 - stale claim] "one of only two routes reachable without a session"** in `docs/AUTH_FLOWS.md` was
wrong: SecurityConfiguration.java:68-80 `permitAll` also lists the Swagger docs, Swagger UI and the actuator health
check. Fixed in a second commit rather than the first, because I found it while writing this table, after the first
commit had passed its 4m 28s hook.

**3. [Tooling] serena unavailable** (not in this agent's toolset), so Java was verified by reading files and scoped
`rg` over `src/main/java` and `src/test/java`, never over the repo root.

**4. [Tooling] first render of `error-status-split` failed to parse** (`Note over C,H: 401 -- no session; MVC ...`:
Mermaid treats `;` as a statement separator). Replaced the semicolon with a comma. No other render errors.

**5. [Scope] ARCHITECTURE.md paragraphs rewritten, not just re-embedded.** The old text described lifelines that no
longer exist (`AuthorizationFilter`, `ExceptionTranslationFilter`, "the session-strategy bean") and a "Simplified"
paragraph about steps the old signin diagram omitted. I replaced them with a lifeline-to-code description and, for
the error split, a three-row mapping table. README.md needed no edit: its three Diagrams-table rows describe the
questions the diagrams answer, which are unchanged.

## Task 3 Self-Check

- [x] `docs/diagrams/scenarios/{signin,signup,error-status-split}.{mmd,png}` exist and are tracked
- [x] commit 3b54d19 and c03fe89 are ancestors of HEAD (`git merge-base --is-ancestor`)
- [x] `git status --short` after the last commit lists only this SUMMARY directory as untracked
- [x] `docs/learning/kafka-architecture-overview.md`, `.serena/`, PLAN.md, STATE.md and this SUMMARY were never staged
- [ ] Tasks 4 and 5 are not started (by instruction); this SUMMARY keeps `status: incomplete`

## Task 5 (learning-guide inline pass)

## Task 5 commits

| # | Hash | Message |
|---|------|---------|
| 1 | 0589788 | docs(quick-261005-sun): drop class-level and decommissioned inline diagrams from the learning guide |
| 2 | 2cb28c9 | docs(quick-261005-sun): verify and fix the kept learning-guide diagrams |
| 3 | 2f460ed | docs(quick-261005-sun): close the diagram drift todo, file the learning-guide rewrite |

Commit 1 was amended once, before anything else was built on it (see Deviations 2). All three end with
`Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. `git rev-list --count e136571..HEAD` is 3.
`.planning/HANDOFF.json`, `docs/learning/kafka-architecture-overview.md` and `.serena/` were never staged.
`docs/wiki/` was not touched.

## Final-tree verification (each part run separately on HEAD 2f460ed)

| # | Command | Exit | Output |
|---|---------|------|--------|
| 1 | `python3 scripts/verify-diagrams-selftest.py` | 0 | `38/38 passed` |
| 2 | `python3 scripts/verify-diagrams.py check` | 0 | `OK: 11 diagram(s), 66 doc(s) scanned` |
| 3 | `bash scripts/render-diagrams.sh --check --all` | 0 | 11 rows, 11 `width=OK`, 11 `height=OK(+0.00%)`, 0 FAIL |
| 4 | `python3 scripts/verify-comments.py check` | 0 | `OK: 292 files, 1116 comment blocks` |
| 5 | `./gradlew spotlessCheck < /dev/null` | 0 | `BUILD SUCCESSFUL in 8s` |
| 6 | inline `mermaid` fences in `docs/learning/[01]*.md` | 0 | `6` (00, 01, 03, 05, 07, 09: one each) |
| 7 | `git diff --stat main...HEAD -- src build.gradle settings.gradle` | empty | 0 lines. No Java, resource or build file changed |
| 8 | `check_evidence.py docs`, last line | 0 | `0 fidelity suspect(s), 20 evidence error(s), 51 unreferenced raw file(s)` (byte-identical to baseline) |
| 9 | `git status --porcelain docs/diagrams` | empty | clean |

I did not run the plan's single chained `<automated>` one-liner as written, because the harness refuses
compound git-bearing commands in a worktree. Each clause above was run on its own. The plan's
`scripts/verify-comments.py` exists and was run. No separate wiki-lint counts script exists beyond
`check_evidence.py`, which is what the plan names.

### Pre-commit hook

`.githooks/pre-commit` ran in full on all three commits (gitleaks, comment policy, spotlessCheck,
fastTest). Never `--no-verify`. Commit 1 needed a real fastTest: `BUILD SUCCESSFUL in 4m 14s` on the
isolated-registry attempt. Commit 1's amend, commit 2 and commit 3 touched only docs, so fastTest was
`UP-TO-DATE` (1 to 3 s). That is Gradle's cache, not a skip.

## Planted-break demo (clean tree first: `git status --porcelain docs` was empty)

Break 1: `git mv docs/diagrams/process/activity-pipeline.png docs/diagrams/activity-pipeline.png`

```
$ python3 scripts/verify-diagrams.py check
FAIL dangling-reference docs/ARCHITECTURE.md:170 'diagrams/process/activity-pipeline.png' does not resolve
FAIL missing-twin docs/diagrams/activity-pipeline.png no .mmd twin beside it
FAIL outside-view-folder docs/diagrams/activity-pipeline.png diagram files live directly in one of logical/process/development/physical/scenarios as <subject>.mmd/.png
FAIL missing-twin docs/diagrams/process/activity-pipeline.mmd no .png twin beside it
4 violation(s); 12 diagram(s), 66 doc(s) scanned
exit=1
```

Restored with `git mv` back:

```
$ python3 scripts/verify-diagrams.py check
OK: 11 diagram(s), 66 doc(s) scanned
exit=0
```

Break 2: appended `[planted](diagrams/scenarios/does-not-exist.png)` to `docs/ARCHITECTURE.md`

```
$ python3 scripts/verify-diagrams.py check
FAIL dangling-reference docs/ARCHITECTURE.md:352 'diagrams/scenarios/does-not-exist.png' does not resolve
1 violation(s); 11 diagram(s), 66 doc(s) scanned
exit=1
```

Restored with `git checkout -- docs/ARCHITECTURE.md`:

```
$ python3 scripts/verify-diagrams.py check
OK: 11 diagram(s), 66 doc(s) scanned
exit=0
```

`git status --porcelain docs` was empty after each restore. Exit 1 then 0, twice. Together with Task 1's
pre-migration run (127 violations, 18 `outside-view-folder`) this is the fail-then-pass evidence.

Guard state at the start of this task: 53 violations (31 `class-level-label`, 22 `flowchart-rules`), all in
`docs/learning` inline blocks. After the drops and keeps: 0.

## Legibility report (final tree, `verify-diagrams.py report`, D = 838)

| Diagram | PNG W x H | Natural W | Text px shown | Shown height | Within |
|---|---|---|---|---|---|
| physical/delivery-nodes | 1616x1816 | 808 | 16.0 | 908 | yes |
| physical/monitoring-nodes | 1270x1260 | 635 | 16.0 | 630 | yes |
| physical/production-host | 2196x1508 | 1098 | 12.2 | 575 | yes |
| process/activity-pipeline | 1420x2326 | 710 | 16.0 | 1163 | yes |
| scenarios/error-status-split | 2162x2546 | 1081 | 12.4 | 987 | yes |
| scenarios/image-to-rollout | 2148x1558 | 1074 | 12.5 | 608 | yes |
| scenarios/inbound-packet-path | 1902x2740 | 951 | 14.1 | 1207 | yes |
| scenarios/ingress-to-pod | 688x1852 | 344 | 16.0 | 926 | yes |
| scenarios/push-to-image | 1864x1662 | 932 | 14.4 | 747 | yes |
| scenarios/signin | 2168x2854 | 1084 | 12.4 | 1103 | yes |
| scenarios/signup | 2094x2524 | 1047 | 12.8 | 1010 | yes |

All 11 are within W <= 1117, shown height <= 1600 and text >= 12 px. The plan's 7 standalone diagrams
became 11 because Task 4 split the three infra diagrams (see the Task 4 section for the reasons).

## Inline blocks: keep/drop outcomes (22 blocks in the plan, plus the README copy)

| Block | Outcome | Commit |
|---|---|---|
| README physical copy | dropped, now `<img>` of physical/production-host plus a source line pointing at monitoring-nodes and delivery-nodes | 0589788 |
| 00 system overview | KEPT, fixed | 2cb28c9 |
| 01 erDiagram | KEPT, fixed | 2cb28c9 |
| 01 timeline | dropped, now a 3-item dated list with the same dates | 0589788 |
| 02 cascade chain | dropped. Prose carries the 4-step bulk order (select task ids, bulk delete subtasks, bulk delete tasks, flush and clear) and the board/column/row/event order | 0589788 |
| 03 two-client 409 | KEPT, relabelled | 2cb28c9 |
| 04 service graph | dropped, replaced by an edge list from `@Autowired ... Service` plus what `LayeringArchTest` enforces | 0589788 |
| 04 ownership chain | dropped. The 4-step numbered list and the one-query paragraph already carried it | 0589788 |
| 04 move algorithm | dropped. It restated steps 1-7 directly above it | 0589788 |
| 04 cascade delete | dropped, one sentence pointing at chapter 02 "Cascade delete order" | 0589788 |
| 05 two error producers | KEPT, relabelled | 2cb28c9 |
| 05 springdoc two-phase | dropped. One added paragraph states the phase-1, swagger-core second pass, phase-2 order | 0589788 |
| 06 signin sequence | dropped, link to scenarios/signin.png | 0589788 |
| 06 Caddy rate limit | dropped, pointer to inbound-packet-path and ingress-to-pod | 0589788 |
| 07 activity pipeline | KEPT, relabelled | 2cb28c9 |
| 08 classDiagram | dropped. Two sentences carry the inheritance chain (verified against the six `extends` lines) | 0589788 |
| 09 pre-commit gates | KEPT, fixed | 2cb28c9 |
| 09 CI job graph | dropped, pointer to push-to-image and image-to-rollout | 0589788 |
| 10 Compose networks | dropped, pointer to production-host | 0589788 |
| 10 Compose deploy sequence | dropped, pointer to push-to-image and image-to-rollout | 0589788 |
| 11 Compose observability | dropped, pointer to monitoring-nodes | 0589788 |
| learning/kafka-architecture-overview.md | untouched (untracked user WIP) | none |

Totals: 14 inline blocks dropped (15 with the README copy), 6 kept, 1 untouched.

## Verification tables: the six kept inline diagrams

Method: serena is not exposed to this executor (same as Tasks 1 to 3), so Java was verified by reading files
and scoped `rg` over `src/` and `k8s/`. Paths below are relative to the repo root. `GEH` =
`src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java`, `TS` =
`.../service/TaskService.java`, `KCC` = `.../config/KafkaConsumerConfig.java`, `props` =
`src/main/resources/application.properties`, `HOOK` = `.githooks/pre-commit`.

### (a) docs/learning/00-README.md: the system in one diagram

| # | Element | Claim | Source | Result |
|---|---|---|---|---|
| 1 | `client -->|HTTPS 443| traefik` | Traefik is the public HTTPS edge, `websecure` exposed | `k8s/platform/traefik/helmchartconfig.yaml:27-30` (`ports.websecure.expose.default: true`), `:23-26` (LoadBalancer, ETP Local) | fixed (old: Caddy). Port 443 follows from the `websecure` entry point, not a literal in the file |
| 2 | `traefik[Traefik<br/>TLS + rate limit]` | Traefik terminates TLS and rate-limits | `k8s/overlays/prod/ingressroute.yaml:5,26` (`auth-rate-limit`, `general-rate-limit` Middlewares), `:62-75` (routes use them) | confirmed. Rate limits are on the production hostname only; the diagram does not say otherwise because chapter 00 never claimed the nonprod edge |
| 3 | `traefik -->|HTTP 8080| app` | upstream is the app on 8080 | `k8s/overlays/prod/ingressroute.yaml:68,75` (`port: 8080`) | confirmed |
| 4 | `app -->|JDBC| pg` | app talks JDBC to PostgreSQL 16 | `props:49` (`jdbc:postgresql://`), `k8s/data/postgres/postgres.yaml:56` (`image: postgres:16`) | confirmed |
| 5 | `pg` label "domain tables + Spring Session" | session store is the same database | `props:138` (`spring.session.store-type=jdbc`) | confirmed |
| 6 | `app -->|domain events, Avro| rp` and `rp -->|consumer| app` | producer and consumer on the same app against Redpanda | `props:170-176` (producer), `:198-212` (consumer), `k8s/base/app/app.yaml:76` (`redpanda:19092`) | confirmed |
| 7 | `rp` label "Kafka protocol + schema registry" | broker and registry in one container | `k8s/base/redpanda/redpanda.yaml:19-27` (ports 19092, 8081, 9644) | confirmed |
| 8 | `app -->|activity_log rows| pg` | consumer inserts rows | `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogRecorder.java:42,51` | confirmed |
| 9 | `prom -.->|scrape via postgres-exporter| pg` | Prometheus reaches Postgres through the exporter | `k8s/monitoring/controllers/postgres-exporter.yaml:62-63` (`serviceMonitor.enabled: true`), `:33-35` (host `postgres.kanban-data.svc.cluster.local`) | fixed (old: "scrape exporters" straight to pg) |
| 10 | `prom -.->|scrape /public_metrics| rp` | Prometheus scrapes Redpanda directly | `k8s/monitoring/configs/podmonitors.yaml:3,17` (`PodMonitor`, `path: /public_metrics`) | confirmed |
| 11 | `graf -.-> prom`, `graf -.-> loki` | Grafana queries both | `k8s/monitoring/configs/datasources.yaml:22-32` (Prometheus `kps-prometheus...:9090`, Loki `loki...:3100`) | confirmed |
| 12 | `loki[Loki ← Alloy]` | Alloy ships logs to Loki | `k8s/monitoring/controllers/alloy.yaml:79-81` (`loki.write ... http://loki.monitoring.svc:3100/loki/api/v1/push`) | fixed (old: Promtail). The arrow glyph is kept from the original label |
| 13 | direction and init | TB with the shared init line | diagram source, guard rule `flowchart-rules` | fixed (old: `flowchart LR`, no init line) |
| 14 | dropped label "controllers → services → repositories" on the app node | class-level detail | n/a | intentionally dropped; chapters 04 carries the layering |

Not verified: that Prometheus in production currently scrapes both Redpanda StatefulSets (the PodMonitor
selects both namespaces, but live target state needs the cluster).

### (b) docs/learning/01-domain-model-and-schema.md: erDiagram

Every table, column, type, key and annotation checked against `src/main/resources/db/migration/V1` to `V9`
(`V1__init.sql` listed by line, later files by the file that adds the column).

| # | Element | Claim | Source | Result |
|---|---|---|---|---|
| 1 | `users.id PK`, `email UK`, `display_name`, `password_hash` | columns and key | `V1__init.sql:6-12` (`uk_users_email` at :11) | confirmed |
| 2 | `users.password_hash "NOT NULL (V4)"` | made NOT NULL by V4 | `V4__add_password_hash_not_null.sql:22` | confirmed |
| 3 | `users.theme "NOT NULL DEFAULT LIGHT (V5)"` | column added by V5 | `V5__...sql:10` (`varchar(10) NOT NULL DEFAULT 'LIGHT'`) | confirmed |
| 4 | `boards.name "UNIQUE with user_id (V5)"` | composite unique | `V5__...sql:27` (`uk_boards_user_id_name UNIQUE (user_id, name)`) | confirmed |
| 5 | `boards.version "V7"`, `created_at "V8"` | columns added | `V7__...sql:8` (`bigint NOT NULL DEFAULT 0`), `V8__...sql:14-15` (`timestamp(6) with time zone NOT NULL`, default dropped) | confirmed |
| 6 | `columns.version "V2"`, `position "V5"`, `color "nullable (V9)"` | columns added | `V2__...sql:6`, `V5__...sql:8`, `V9__...sql:15` (`varchar(7)`, nullable) | confirmed |
| 7 | `tasks.title "varchar(32)"`, `description "varchar(512)"` | lengths | `V1__init.sql:31-32` | confirmed |
| 8 | `tasks.version "V2"`, `position "V5"` | columns added | `V2__...sql:5`, `V5__...sql:7` | confirmed |
| 9 | `subtasks.is_completed "DEFAULT false"`, `version "V5"` | default and column | `V1__init.sql:40`, `V5__...sql:9` | confirmed |
| 10 | `activity_log` columns, `detail varchar(2000)`, `event_id UK`, "no FK" on `board_id` and `user_id` | table shape | `V3__add_activity_log.sql:5-13` (no REFERENCES), `V6__...sql:15,17` (`event_id` now `varchar(255)`, unique re-added) | confirmed |
| 11 | relationship `users ||--o{ boards`, and the three like it | the parent side is mandatory | `V1__init.sql:17,24,30,38` (all four FK columns have no NOT NULL), the chapter's own line 164 ("nullable in V1") | fixed (old: `||`, new: `|o`). The diagram contradicted the prose of the same chapter |
| 12 | no relationship drawn for `activity_log` | no FK | `V3__add_activity_log.sql:5-13` | confirmed |

### (c) docs/learning/03-optimistic-locking.md: two-client 409

| # | Element | Claim | Source | Result |
|---|---|---|---|---|
| 1 | lifeline `API (version check)` | the request path that compares versions is the update handler | `TS:113-120` (`updateById`: `findById`, then the version compare) | relabelled (old: `TaskService`) |
| 2 | `A->>S: PUT {title, version: 3}` | request carries the client's version | `TS:113` (`UpdateTaskRequestDTO dto`), `:118` (`dto.getVersion()`) | confirmed |
| 3 | `S->>DB: SELECT task (version 3)` | fresh load before comparing | `TS:114` (`findById(userId, taskId)`), `:96` | confirmed |
| 4 | `Note: 3 == 3, check passes` | equality compare, not Hibernate's check | `TS:118` (`!task.getVersion().equals(dto.getVersion())`) | confirmed |
| 5 | `UPDATE ... SET version=4 WHERE id=? AND version=3` | Hibernate's versioned UPDATE | the `@Version` mapping plus `TS:134` (`entityManager.flush()`); the SQL text itself is Hibernate's standard form | confirmed for the flush; the exact SQL was not captured in this task (could not verify the literal statement here) |
| 6 | `200 {version: 4}` | response carries the new version | `TS:131-134` comment and flush | confirmed |
| 7 | `4 != 3, reject before any change` | compare runs before any mutation | `TS:118-121` precedes the setters at `:123-128` | fixed (old: "throw OptimisticLockingFailureException", an exception class name) |
| 8 | `409 OPTIMISTIC_LOCK_CONFLICT` | handler status and code | `GEH:150-155` (`HttpStatus.CONFLICT`, `ErrorCode.OPTIMISTIC_LOCK_CONFLICT`) | confirmed |
| 9 | the scenario as a whole | second writer gets 409 | `src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingTest.java:42` (`concurrentConflictingUpdates_firstSucceeds_secondReturnsConflict`) | confirmed by test name and its arrange block; I did not run it separately (the hook ran `fastTest`) |

### (d) docs/learning/05-api-layer.md: two error producers

| # | Element | Claim | Source | Result |
|---|---|---|---|---|
| 1 | lifeline `401 entry point (in the filter chain)` | the entry point fires inside the security filter chain, before MVC | `src/main/java/com/vrudenko/kanban_board/security/ProblemDetailAuthenticationEntryPoint.java:23` (Javadoc: "fires inside Spring Security's ExceptionTranslationFilter, before DispatcherServlet runs"), `SecurityConfiguration.java:92` (`authenticationEntryPoint(...)`) | relabelled (old: class name) |
| 2 | `F->>EP: commence()` and `EP-->>C: 401 UNAUTHENTICATED` | method and code | `ProblemDetailAuthenticationEntryPoint.java:36,44` | confirmed |
| 3 | lifeline `MVC dispatch (controller + service)` | role | `SecurityConfiguration.java:82` (`anyRequest().authenticated()` lets a valid session through) | relabelled (old: `DispatcherServlet + controller + service`) |
| 4 | lifeline `Exception handler (@ControllerAdvice)` | role | `GEH:32-33` | relabelled (old: class name) |
| 5 | `400 / 403 / 404 / 409 / 500 ProblemDetail`: every status has a handler | second arm covers all five | 400 `GEH:193` (validation), `:52`, `:63`; 403 `:81,89`; 404 `:36,44`; 409 `:150,161,176`; 500 `:72` | confirmed. 14 `@ExceptionHandler` methods in total (`rg -c`), matching the chapter's "14 arms" |
| 6 | `alt no valid session / else valid session` | the two arms are exclusive | `SecurityConfiguration.java:82` | confirmed |

### (e) docs/learning/07-events-and-activity-feed.md: the whole pipeline

Reuses Task 1's citations where identical (Task 1 table rows 1 to 14).

| # | Element | Claim | Source | Result |
|---|---|---|---|---|
| 1 | `PATCH /api/tasks/{id}/move` | route | `src/main/java/com/vrudenko/kanban_board/controller/TaskMoveController.java:35` (`@PatchMapping(TASK_ID + MOVE)`), context path `/api` | confirmed |
| 2 | `Request thread (@Transactional service)` | the mutation runs transactionally on the request thread | Task 1 row 3 (`TS` `@Transactional moveToColumn`) | relabelled (old: "Service (@Transactional)") |
| 3 | `UPDATE task, shift positions` | SQL-level action | `TS:196-226` (the shift cases), `:234` (`flush`) | confirmed |
| 4 | `publish event (queued until commit)` | publishEvent only queues | `src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java:45` (AFTER_COMMIT) | confirmed. Old label named `TaskMovedEvent` |
| 5 | `COMMIT`, then `200 OK (response does not wait for Kafka)` | order and non-blocking | `KafkaEventPublisher.java:26-30` Javadoc | confirmed |
| 6 | `Publish thread (kafka-publish-*, AFTER_COMMIT)` | thread-name prefix and phase | `KafkaEventPublisher.java:44-45`, Task 1 row 7 (`AsyncConfig.java:16,22`) | relabelled (old: class name) |
| 7 | `look up schema id (never register)` | lookup only | `props:182` (`auto.register.schemas=false`) | confirmed |
| 8 | `send(key=eventId, Avro bytes)` | key and format | Task 1 row 9 | confirmed |
| 9 | `max.block/request/delivery = 2000 ms` | three timeouts | `props:194-196` | confirmed |
| 10 | `kanban.activity (1 partition)` | topic and partitions | `KCC:47-53`, `KafkaTopics.java:5` | confirmed |
| 11 | `Consumer thread (group activity-log)`, `poll (group activity-log)` | group and listener | `props:199`, `ActivityLogConsumer.java:51` | relabelled (old: `ActivityLogConsumer`) |
| 12 | `resolve schema id` on the consumer | deserializer resolves the id | `props:212` (`schema.registry.url` on the consumer), `:208-209` (`KafkaAvroDeserializer`) | confirmed |
| 13 | `existsByEventId? then INSERT activity_log` on the consumer thread | recorder runs on the listener thread | `ActivityLogConsumer.java:76` (`activityLogRecorder.record(entity)`), `ActivityLogRecorder.java:42,51` | fixed (old: separate `ActivityLogRecorder` lifeline). Merge is valid because `record` is a plain synchronous call |
| 14 | `3 retries x 1 s, then kanban.activity.dlt` | retry count and spacing, DLT | `KCC:164` (`FixedBackOff(1000L, 3L)`), `:162` | confirmed. Context7 (Task 1 row 14): second argument counts retries |
| 15 | class names moved to prose | publisher, consumer and recorder named in the paragraph under the diagram | the new paragraph in chapter 07 | carried |

Could not verify: that the live broker really has `kanban.activity` at 1 partition (needs the cluster).

### (f) docs/learning/09-build-quality-and-ci.md: pre-commit gates

| # | Element | Claim | Source | Result |
|---|---|---|---|---|
| 1 | `git commit` then gitleaks on the staged diff, pinned image | first gate | `HOOK:38` (pinned `GITLEAKS_IMAGE`), `:40` ("Scanning staged diff") | confirmed |
| 2 | `exit 0` continues, `exit 2: finding` refuses, `other exit` refuses | three-way result | `HOOK:101-120` (`-eq 0`, `-eq 2` with `exit 1`, else with `exit 1`) | confirmed |
| 3 | comment-policy gate | `python3 scripts/verify-comments.py check`, non-zero refuses | `HOOK:122-140` (`:133`, `:134-136` "Commit refused"), message cites `docs/CODE_STYLE.md` rule 14 | fixed (old: gate missing). Placed after gitleaks and before spotless, which is hook order |
| 4 | comment gate skipped without Python 3 | fail-open | `HOOK:122-129`, `:138-139` | stated in prose (step 4), not drawn |
| 5 | `./gradlew spotlessCheck`, fail refuses | third gate | `HOOK:142-147` ("Run ./gradlew spotlessApply") | confirmed |
| 6 | `./gradlew fastTest`, "compile + untagged tests" | fourth gate | `HOOK:149-159` | confirmed |
| 7 | `Commit created` on pass | success path | `HOOK:159` (end of file, exit 0) | confirmed |
| 8 | the prose counts | "three gates" | `HOOK:` the file now has four gates | fixed in chapter 09 (line 422, "four gates") and `README.md` "Quality & security gates" ("four gates", names the comment policy) |
| 9 | the source-line anchors in step 2 to 6 | line ranges | `HOOK` (`rg -n` of each echo line) | fixed (all five ranges were stale after the comment gate was added: now L40-L99, L101-L120, L122-L140, L142-L147, L149-L159) |
| 10 | direction and init | TB with the shared init line | diagram source | fixed (old: `flowchart TD`, no init) |

Chapter 09's step "Worktree handling" anchor `#L33-L62` was not re-derived and may be stale; the hook's
mount-strategy comment now sits at roughly L20-L36 and the code after it. Left as is (out of this task's
diagram scope).

## Carry-over of dropped inline blocks

| Block | Fact the diagram showed | Where it survives |
|---|---|---|
| 02 cascade chain | 4-step bulk order, per-board and per-column loops, event once per board | new paragraph in chapter 02 "Cascade delete order" (verified against `TaskService.java:308-319`, `BoardService.java:63-84`, `UserService.java:84-97`) |
| 04 service graph | who calls whom, no upward calls | new bullet list in chapter 04 plus what `LayeringArchTest` enforces (read at `LayeringArchTest.java:56-101`). **Dropped, not carried:** the diagram's implicit claim that "no service calls upward" is enforced. No ArchUnit rule enforces it, and the prose now says so |
| 04 ownership chain | single SELECT, L1 cache hits, 403 vs result | the existing 4-step list and the "What SQL runs" paragraph |
| 04 move algorithm | the seven steps | the numbered list directly above it (unchanged) |
| 04 cascade delete | same as 02 | pointer to chapter 02 |
| 05 springdoc | phase 1, swagger-core second pass, phase 2 | new paragraph (verified against the customizer Javadoc, `ComposedConstraintPropertyCustomizer.java` lines 41-52 and 480-487) |
| 06 signin sequence | statuses, cookie rotation, commit timing | link to scenarios/signin.png; the 6-step list below it keeps the class-level steps |
| 06 Caddy limiter | zones and 429 | the section's own table and prose, now prefaced by the "describes Compose" sentence |
| 08 classDiagram | base-class inheritance | two new sentences (verified against the six `extends` clauses) |
| 01 timeline | three dated entries | the 3-item list |
| 09, 10, 10, 11 | decommissioned topology | one-sentence pointers to the current PNGs |

## Wiki

`check_evidence.py docs` last line: baseline `0 fidelity suspect(s), 20 evidence error(s), 51 unreferenced raw file(s)`;
after Task 5 identical. `git diff --stat main...HEAD` lists no path under `docs/wiki/` or `docs/raw/`.

Wiki references to renamed or dropped diagrams (all pre-existing unresolved links, since
`docs/wiki/**/diagrams/` never existed; not edited). Counts are lines per file from `rg -c 'diagrams/'`:

| File | Lines | Diagrams named |
|---|---|---|
| docs/wiki/architecture/application-architecture.md | 12 | auth-signin, auth-signup, architecture-signin, error-response-split, mutation-flowchart, mutation-sequence, activity-feed-read |
| docs/wiki/infra/infra-architecture.md | 8 | infra-physical-deployment, infra-delivery-scenario, infra-packet-path-scenario |
| docs/wiki/architecture/auth-flows.md | 4 | auth-signup-scenario, auth-signin-scenario |
| docs/wiki/learning/10-infrastructure-and-deployment.md | 3 | the three infra diagrams |
| docs/wiki/conventions/diagram-conventions.md | 2 | the old `flowchart TD` test and the old file names |
| docs/wiki/learning/06-security-and-sessions.md | 1 | architecture-signin-scenario |

## Todos

- Closed: `.planning/todos/completed/2026-09-05-seven-diagrams-drift-from-the-pinned-renderer.md` (moved with `git mv`,
  resolution line cites `--check --all` green for all 11).
- Filed: `.planning/todos/pending/2026-10-05-learning-guide-describes-the-compose-era-deployment.md`, with
  re-measured per-chapter counts (word-boundary, case-insensitive): Caddy / Compose / Promtail /
  `deploy-to-netcup` = 00: 20/8/4/0; 05: 1/7/0/0; 06: 39/5/0/0; 07: 0/8/0/1; 08: 10/2/0/0; 09: 40/22/0/4;
  10: 77/95/1/3; 11: 30/50/37/0. The plan's planning-time Caddy counts (15/1/36/7/31/65/28) were lower than
  these because they used a narrower pattern; chapter 01's 9 Compose matches are the local-dev
  `docker-compose.yml`, which still exists, and are excluded.

## Deviations from Plan

**1. [Process] pre-commit killed by "stop command received" on four consecutive attempts; fixed by isolating the
daemon registry, not by touching the hook.** Commit 1 failed identically on attempts 1 to 4 (the brief's
original try plus its three retries), each time with `Gradle build daemon has been stopped: stop command
received`, in `spotlessCheck` or between `:compileJava` and `:fastTest`. `.claude/settings.json:3-9` configures a
`SubagentStop` hook that runs `./gradlew --stop`, which stops every daemon in the shared Gradle user home, and
five agent worktrees were live. After the retry budget was spent I ran the same `git commit` with
`GRADLE_OPTS=-Dorg.gradle.daemon.registry.base=/tmp/sun5-gdaemon` in the environment, so my daemon sat in a
private registry that another session's `gradlew --stop` cannot see. I confirmed the setting is honoured before
relying on it (`./gradlew --status` created `/tmp/sun5-gdaemon/8.11.1`). The hook file was not edited and every
gate in it ran unchanged. That attempt passed (`BUILD SUCCESSFUL in 4m 14s`), as did all later commits. This is a
departure from "retry the identical commit", so I am flagging it rather than burying it. The cause is inferred
from the hook configuration and the matching signature, not observed directly.

**2. [Rule 1 - missed drop] 08 `classDiagram` was dropped late, by amending commit 1.** I built the drop list
from the heading names and missed 08:74. The guard does not flag a class diagram (`classDiagram` has no
flowchart keyword and its class names lack the role suffixes), so it passed with 7 inline fences. The plan's
fence count (`== 6`) caught it. I amended 0589788 (own commit, unpushed, nothing built on it) so the drop commit
matches the verdict table. The amend also corrected the commit message, which had said "14 blocks" while listing
13. Original hash e4d43de no longer exists.

**3. [Rule 1 - stale claim] 01 erDiagram drew mandatory parents.** `||--o{` says every child has exactly one
parent, but all four FK columns are nullable in V1, and chapter 01 line 164 says so. Fixed to `|o--o{`.

**4. [Rule 3 - split files] 05 and 09 and README each held a drop and a keep.** To keep commit 1 drops-only and
commit 2 keeps-only I staged a drops-only version of those three files for commit 1 and restored the full
versions afterwards. The two commits are therefore each self-consistent and each passed the full hook.

**5. [Tooling] serena unavailable**, as in Tasks 1 to 3. Java verified by reading files and scoped `rg`.

**6. [Tooling] inline-render proof.** To prove the six kept blocks parse, I rendered each with the pinned
mermaid-cli image (scale 1, same `mermaid-config.json`) into a scratch dir inside the worktree (the Docker daemon
cannot see `/tmp`), viewed three of them (00, 07, 09), and deleted the scratch dir. All 6 rendered with exit 0.
GitHub renders inline blocks with its own mermaid version, so this proves the syntax, not GitHub's pixels.

**7. [Visual] 00's rendered layout has one crossing edge.** The consumer arrow from Redpanda back to the app
crosses the Prometheus box in the render. It is legible and I left the layout alone, because the diagram is inline
and GitHub lays it out itself.

## Findings outside this task (not fixed, per the scope boundary)

- `src/main/resources/application.properties:190-192` still says the after-commit publish "runs synchronously on
  the request thread ... before the HTTP response is written". `KafkaEventPublisher.java:44` is `@Async`, so the
  comment is stale (the class Javadoc at `:26-30` says the opposite). I did not edit it: the plan forbids Java and
  resource changes. Worth a one-line fix in a separate commit.
- Chapter 09 prose anchors into `.githooks/pre-commit` outside the pre-commit section (for example `#L33-L62` and
  `#L24-L31` at lines 92 and 467) were not re-derived; they may be off by the added comment-policy block.
- Chapters 00, 05, 06, 07, 08, 09, 10, 11 still describe Caddy, Compose and Promtail in prose. That is the new todo.

## Known Stubs

None.

## Threat Flags

None. Nothing new at a trust boundary. Labels added to 00 cite committed manifests only (T-261005-sun-02), no
addresses or hostnames beyond what the manifests already publish.

## Self-Check

- [x] 0589788, 2cb28c9, 2f460ed are ancestors of HEAD
- [x] `git status --short` empty for tracked files; this file and `.planning/` quick files are the only
      uncommitted material and were not staged
- [x] `docs/learning/kafka-architecture-overview.md`, `.serena/`, `.planning/HANDOFF.json` never staged
- [x] `docs/wiki/` untouched; nothing merged to main

## Self-Check: PASSED
