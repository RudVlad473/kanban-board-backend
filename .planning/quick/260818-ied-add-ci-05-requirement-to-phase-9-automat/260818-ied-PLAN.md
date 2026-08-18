---
phase: quick-260818-ied
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - .planning/REQUIREMENTS.md
  - .planning/ROADMAP.md
autonomous: true
requirements: [CI-05]

estimate:
  tokens: 18000
  raw_tokens: 18000
  tasks: 2
  confidence: low

must_haves:
  truths:
    - "REQUIREMENTS.md's `### CI Deploy Automation` section lists five requirements, CI-01 through CI-05, and CI-05's text names both the production and the nonprod schema registry."
    - "REQUIREMENTS.md's traceability table maps CI-05 to Phase 9 with status Pending, its coverage line reads 20/20, and its Phase 9 rationale bullet says five CI-* requirements rather than four."
    - "ROADMAP.md's Phase 9 `**Requirements**` line includes CI-05, and both Phase 9's `**Goal**` and its one-line milestone summary mention schema registration, so the phase's stated scope matches what it now owns."
    - "ROADMAP.md's Phase 9 success criteria carry a fifth criterion that is falsifiable against the two live registries, not a restatement of the requirement."
    - "Nothing outside `.planning/REQUIREMENTS.md` and `.planning/ROADMAP.md` is modified: `.github/workflows/deploy.yml`, `docs/`, `src/`, and every file under `.planning/phases/08-isolated-nonprod-environment-live-and-resettable/` are byte-identical to HEAD."
  artifacts:
    - ".planning/REQUIREMENTS.md — carries CI-05 in the requirements list, the traceability table, the coverage count, and the Phase 9 mapping rationale"
    - ".planning/ROADMAP.md — Phase 9 section and milestone phase listing reflect schema-registry sync as delivered scope"
  key_links:
    - "REQUIREMENTS.md CI-05 bullet -> REQUIREMENTS.md traceability row (CI-05 | Phase 9 | Pending) -> ROADMAP.md Phase 9 `**Requirements**` line: the same ID must appear in all three or the roadmapper's own 'no orphans, no duplicates' invariant breaks"
    - "REQUIREMENTS.md coverage line (20/20) -> traceability table row count: the stated total must equal the rows actually present"
---

<objective>
Add a fifth CI requirement, CI-05, covering automated Avro schema-registry registration for both
production and nonprod as part of the CI deploy pipeline, and propagate it through every place the
requirement set is counted, traced, or summarized.

Purpose: today the 14 Avro schemas are registered by a hand-run one-off container
(`docs/INFRA_RUNBOOK.md`, "Manual deploy — Plan 05-04 Task 2"), and Phase 8 plan 08-01 adds a
*second* hand-run registration against the nonprod broker. Phase 9 (CI-01) makes deploys continuous.
The combination is the actual hazard: once master deploys automatically, a commit that adds or
changes an Avro schema reaches a running app whose registry has never seen that subject, and
`spring.kafka.producer.properties.auto.register.schemas=false` turns that into a runtime publish
failure rather than a lazy self-heal. Automating registration is therefore not polish on top of
CI-01 — it is a correctness precondition of it, and it belongs in the same phase.

Output: `.planning/REQUIREMENTS.md` and `.planning/ROADMAP.md`, edited. No code, no workflow files.
</objective>

<scope_boundary>
This is a DOCUMENTATION-ONLY task. It adds a requirement to the backlog for a phase that has not
been planned yet.

**Do NOT edit** — not even "while you're in there":
- `.github/workflows/deploy.yml` or any other workflow. Phase 9 will edit it when Phase 9 is planned.
- Anything under `.planning/phases/08-isolated-nonprod-environment-live-and-resettable/` — Phase 8 is
  planned, verified, and committed.
- `docs/INFRA_RUNBOOK.md` — it is a historical record of what was done manually; it is cited by the
  new requirement, not invalidated by it.
- `src/`, `build.gradle`, application properties.

**Do NOT edit `.planning/STATE.md` for the requirement count.** Its `last_activity_desc` and Current
Position lines say "19/19 requirements mapped" as a record of *what the roadmapper did on
2026-08-18*. That statement remains true as history; rewriting it would falsify a log entry rather
than correct a stale fact. The quick-task workflow's own state update at completion is the correct
place for any state change.
</scope_boundary>

<tradeoffs>
Required by `.claude/CLAUDE.md`: alternatives, matrix, and non-obvious trade-offs before any PLAN.md
is created.

## Alternative approaches considered

**Approach A (picked) — new standalone requirement CI-05, scoped to Phase 9.**
Schema-registry sync becomes its own tracked, independently verifiable requirement alongside
CI-01..04.

**Approach B — extend CI-01's existing wording instead of adding an ID.**
Fold "…and registers Avro schemas against both registries" into CI-01, keeping the CI-* set at four.

**Approach C — defer to a v2 requirement (alongside the already-deferred SCHEMA-V2-01..02).**
Leave the manual step in place for v1.3; register schemas by hand when they change.

## Trade-off matrix

| Approach | Pros / Cons | Why picked / rejected |
|---|---|---|
| **A. New CI-05 in Phase 9** | **+** One requirement, one outcome, one verification — the project's existing traceability invariant ("each to exactly one phase, no orphans, no duplicates") keeps holding. **+** Auditable: a future reader can ask "was CI-05 done?" and get a yes/no. **−** Costs a documentation ripple across four places in two files (list, table, coverage count, rationale bullet) plus three in ROADMAP. | **Picked.** The ripple cost is one-time and mechanical; the traceability benefit is permanent. Phase 9 is the right home because CI-05 automates precisely the manual step Phase 8 performs by hand, and it shares CI-02's credential-scoping prerequisite. |
| **B. Extend CI-01** | **+** Zero new IDs, no count/table churn. **−** Makes CI-01 a two-outcome requirement, so "CI-01 complete" becomes ambiguous when schemas register but the health check doesn't (or vice versa). **−** ROADMAP's Phase 9 success criteria are already numbered roughly 1:1 against the four CI-* requirements; conflating breaks that audit trail silently rather than loudly. | **Rejected.** It hides scope growth inside an existing requirement — the exact failure mode the user's instruction ("so the phase's stated goal doesn't undersell its own scope") is guarding against. |
| **C. Defer to v2** | **+** Zero v1.3 scope growth; there is real precedent (SCHEMA-V2-01..02 are deferred). **−** Deferral is *not* neutral here: CI-01 lands continuous deploy in the same milestone, and `auto.register.schemas=false` means a deployed-but-unregistered schema fails at publish time, in production, after a green pipeline. **−** The manual burden doubles the moment nonprod exists (two registries, two hand-runs, per schema change). | **Rejected.** SCHEMA-V2-01..02 defer *optional* safety nets (pre-merge compatibility check, rationale doc). This defers a step that continuous deploy makes mandatory. Different risk class. |

## Non-obvious trade-offs recorded into the requirement text

These are the things a Phase 9 planner would plausibly get wrong if the requirement stayed vague:

1. **Ordering vs. isolation are two different constraints, easily conflated.** CI-01's rule is
   *cross-environment*: the nonprod path must never gate, nor be gated by, production's. But
   *within* one environment, registration must complete before that environment's app serves
   traffic — because `auto.register.schemas=false` means an unregistered subject is a hard runtime
   failure, not a self-healing lazy write. A naive reading of "runs parallel, never gates" as
   applying to the whole graph would produce a step that races its own app container. CI-05's text
   states both halves explicitly so they cannot collapse into each other.

2. **The schema registry publishes no host port** — `docs/INFRA_RUNBOOK.md` is explicit that 8080,
   8081 and 9092 stay internal-only, reachable only over the Compose network. So CI *cannot* POST
   schemas over the public internet; the step has to run on the VM over SSH (the existing
   `PropertiesLauncher` one-off-container technique, reusing `deploy-to-netcup`'s SSH pattern). The
   wrong fix — publishing 8081 to satisfy CI — would hand the internet a write-capable registry.
   The requirement names the existing mechanism specifically to foreclose that.

3. **CI-05 inherits CI-02's scoping.** Because the mechanism is SSH-onto-the-VM, the nonprod
   registration needs deploy credentials; those must be the staging-scoped ones, or CI-05 quietly
   reintroduces the unscoped-secret problem CI-02 exists to remove.

4. **Idempotency makes per-deploy execution safe, and failure loud.** Re-registering an unchanged
   schema against a BACKWARD-compatible subject is a no-op (same schema, same version), so running
   on every deploy costs nothing. An *incompatible* change fails the registration step — which is
   the desirable outcome: the deploy fails visibly instead of the app failing at first publish.

5. **Do not hardcode a subject count.** The runbook records that both `05-04-PLAN.md` and
   `04-VERIFICATION.md` said "5 subjects" when the live registry held 14, because quick task
   `260811-s5e` expanded the event types afterwards. Any count written into a requirement today has
   the same decay property, so CI-05 and its success criterion are phrased against "the
   application's Avro schemas", verified against the code's actual event types.

**Performance / memory:** none — this task writes markdown. The mechanism CI-05 describes costs one
short-lived JVM container per environment per deploy on a host whose memory headroom is the subject
of an open Phase 8 blocker (NONPROD-06); the container is `--rm` and runs before/at deploy time, not
concurrently with steady-state load, but Phase 9's planner should not treat it as free on a box
being sized to its floor.
</tradeoffs>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.planning/REQUIREMENTS.md
@.planning/ROADMAP.md
</context>

<tasks>

<task type="auto">
  <name>Task 1: Add CI-05 to REQUIREMENTS.md and reconcile every count that references it</name>
  <files>.planning/REQUIREMENTS.md</files>
  <read_first>
    - `.planning/REQUIREMENTS.md` lines 17-22 — the `### CI Deploy Automation` section. Match CI-01's
      register exactly: single bolded ID, one dense sentence, backticked identifiers, rationale
      carried inline as a because-clause rather than a separate note.
    - `.planning/REQUIREMENTS.md` lines 54-84 — the traceability table, the coverage assertion, and
      the per-phase mapping rationale. All three are counts or claims that this edit falsifies if
      left alone.
  </read_first>
  <action>
Add a fifth bullet, CI-05, at the end of the `### CI Deploy Automation` list, immediately after
CI-04 and before the `### Data Reset Mechanism` heading. Write it in CI-01's voice and shape.

The bullet must carry, in one sentence-plus-clause structure:
  - a step in `deploy.yml` registers the application's Avro schemas against BOTH the production and
    the nonprod schema registries on every deploy;
  - it reuses the existing `AvroSchemaRegistrar` / `PropertiesLauncher` one-off-container mechanism
    rather than introducing a new tool, citing `docs/INFRA_RUNBOOK.md`'s "Manual deploy — Plan 05-04
    Task 2" and Phase 8 plan 08-01's manual invocation as the steps it replaces;
  - it follows CI-01's pattern — extending `deploy.yml`'s existing job graph, with the nonprod
    registration running parallel to, never gating and never gated by, the production deploy path,
    so one CI run covers both environments and neither registry drifts out of step with the other;
  - and the ordering carve-out from the trade-off analysis: within a single environment, registration
    must complete before that environment's app serves traffic, because
    `spring.kafka.producer.properties.auto.register.schemas=false` makes an unregistered subject a
    runtime publish failure rather than a lazy self-heal.

Do not hardcode a subject count anywhere in the bullet — the runbook documents that every previously
written count went stale.

Then reconcile the three downstream claims in the same file:
  1. Traceability table — insert a row after CI-04: requirement `CI-05`, phase `Phase 9`, status
     `Pending`. Keep column alignment consistent with the surrounding rows.
  2. Coverage assertion below the table — the total moves from nineteen to twenty on both sides of
     the fraction. The rest of that sentence ("each to exactly one phase. No orphans, no
     duplicates.") stays true and unchanged.
  3. Phase 9 mapping-rationale bullet — it currently claims a four-requirement count for the CI-*
     set; make it say five. Extend the same bullet with why CI-05 sits in Phase 9 rather than
     Phase 8: Phase 8 registers nonprod's schemas by hand as part of bring-up, and CI-05 replaces
     both that hand-run and production's with the automated step, so it depends on nonprod existing
     but belongs with the automation work; and note that it inherits CI-02's environment scoping,
     because the registry publishes no host port and the step therefore reaches the broker over the
     same SSH path the deploy job uses.

Finally update the file's trailing `*Last updated:*` line to today's date with a short note that
CI-05 was added by quick task 260818-ied.
  </action>
  <verify>
    <automated>grep -c '^- \[ \] \*\*CI-0[1-5]\*\*' .planning/REQUIREMENTS.md   # expect 5</automated>
    <automated>grep -c '| CI-05 | Phase 9 | Pending |' .planning/REQUIREMENTS.md   # expect 1</automated>
    <automated>grep -c '20/20 v1 requirements mapped' .planning/REQUIREMENTS.md   # expect 1</automated>
    <automated>grep -c 'all five CI-\*' .planning/REQUIREMENTS.md   # expect 1</automated>
    <automated>grep -cE 'auto\.register\.schemas|AvroSchemaRegistrar' .planning/REQUIREMENTS.md   # expect >= 1</automated>
    <automated>awk '/^\| CI-/{n++} END{exit !(n==5)}' .planning/REQUIREMENTS.md   # table holds exactly 5 CI rows</automated>
    <automated>awk '/^\| (NONPROD|RESET|CI|HARDEN)-/{n++} END{print n; exit !(n==20)}' .planning/REQUIREMENTS.md   # stated coverage equals actual row count</automated>
  </verify>
  <acceptance_criteria>
    - The `### CI Deploy Automation` section contains exactly five requirement bullets, CI-01 through
      CI-05, in ascending order, with CI-05 last and the `### Data Reset Mechanism` heading still
      immediately after it.
    - CI-05's text names both the production and the nonprod schema registry, cites the existing
      registrar/launcher mechanism, and states the within-environment ordering constraint tied to
      `auto.register.schemas=false`.
    - CI-05's text contains no literal count of Avro subjects or schemas.
    - The traceability table has 20 requirement rows, one of which is CI-05 mapped to Phase 9 with
      status Pending; the coverage line's stated total equals that row count.
    - The Phase 9 rationale bullet states a five-requirement CI-* set and explains CI-05's phase
      placement and its dependence on CI-02's scoping.
  </acceptance_criteria>
  <done>REQUIREMENTS.md carries CI-05 as a first-class requirement and no count, table, or rationale sentence in the file contradicts its presence.</done>
</task>

<task type="auto">
  <name>Task 2: Reflect schema-registry sync in ROADMAP.md's Phase 9 scope</name>
  <files>.planning/ROADMAP.md</files>
  <read_first>
    - `.planning/ROADMAP.md` line 55 — the v1.3 milestone goal paragraph. Read it to decide, not to
      edit reflexively: it is only in scope if it enumerates the CI requirements by ID.
    - `.planning/ROADMAP.md` line 58 — the Phase 9 one-line summary in the milestone phase listing.
    - `.planning/ROADMAP.md` lines 91-103 — the Phase 9 detail block: Goal, Depends on, Requirements,
      the four numbered success criteria, and the `**Plans**: TBD` line.
  </read_first>
  <action>
Bring Phase 9's stated scope in line with the requirement it now owns. Four edits, one deliberate
non-edit.

1. **Phase 9 `**Requirements**` line** — append `, CI-05` so it reads CI-01 through CI-05.

2. **Phase 9 `**Goal**`** — the goal currently enumerates what CI does to nonprod on every push
   (deployed, migrated, health-verified). Add schema registration to that enumeration, and extend
   the sentence so it also carries the both-registries half: the same automated step keeps
   production's and nonprod's registries in step with the deployed code. Preserve the existing
   credential-scoping clause verbatim in meaning — it is the other half of the phase's identity.

3. **Phase 9 one-line summary in the milestone listing (line 58)** — same treatment, compressed:
   every push to master redeploys, re-registers Avro schemas for, and health-checks nonprod through
   environment-scoped secrets, with zero ability to disturb production.

4. **Add a fifth numbered success criterion** to Phase 9's `**Success Criteria** (what must be TRUE)`
   list, keeping the existing four unchanged and unrenumbered. Write it as an observable end state,
   not a restatement of CI-05: a push to master that introduces or changes an Avro schema leaves
   that schema present in BOTH the production and the nonprod registry with no operator running the
   registrar by hand; and a schema change the registry rejects as incompatible fails the deploy
   visibly rather than surfacing later as a runtime publish failure. Match the existing criteria's
   voice (present tense, falsifiable, no implementation detail).

5. **Deliberate non-edit — assert before skipping.** Re-read the milestone goal paragraph (line 55).
   It describes the milestone in prose ("deployed continuously by CI …") and does *not* enumerate
   the CI requirements by ID, so the conditional instruction to update it does not fire and it stays
   byte-identical. State this explicitly in the summary as a checked condition, not as an omission.

Leave `**Plans**: TBD`, the Progress tables, the Execution Order line, and the Deferred section
untouched — this task changes what Phase 9 must deliver, not how many plans it takes or when it runs.
  </action>
  <verify>
    <automated>grep -c 'CI-01, CI-02, CI-03, CI-04, CI-05' .planning/ROADMAP.md   # expect 1</automated>
    <automated>awk '/^### Phase 9:/,/^### Phase 10:/' .planning/ROADMAP.md | grep -ciE 'schema' </automated>
    <automated>awk '/^### Phase 9:/,/^### Phase 10:/' .planning/ROADMAP.md | grep -cE '^  [0-9]+\.'   # expect 5 success criteria</automated>
    <automated>awk '/^- \[ \] \*\*Phase 9:/' .planning/ROADMAP.md | grep -ci 'schema'   # expect 1</automated>
    <automated>git diff HEAD -- .planning/ROADMAP.md | grep -c '^[-+].*Milestone Goal'   # expect 0: milestone paragraph untouched</automated>
    <automated>git diff --name-only HEAD -- .github docs src build.gradle .planning/phases | wc -l   # expect 0</automated>
    <automated>git diff --name-only HEAD | grep -cvE '^\.planning/(REQUIREMENTS|ROADMAP)\.md$|^\.planning/quick/'   # expect 0</automated>
  </verify>
  <acceptance_criteria>
    - Phase 9's `**Requirements**` line lists CI-01 through CI-05.
    - Phase 9's `**Goal**` and its one-line summary in the milestone phase listing both mention
      schema registration, and the goal names both registries being kept in step.
    - Phase 9's success-criteria list has exactly five numbered entries; entries 1-4 are unchanged
      from HEAD and entry 5 is falsifiable against the two live registries.
    - The v1.3 `**Milestone Goal:**` paragraph is byte-identical to HEAD.
    - `git diff --name-only HEAD` lists only `.planning/REQUIREMENTS.md`, `.planning/ROADMAP.md`, and
      files under `.planning/quick/` — no workflow, no `docs/`, no `src/`, nothing under
      `.planning/phases/`.
  </acceptance_criteria>
  <done>Phase 9's roadmap entry states schema-registry sync as delivered scope in its requirements, goal, summary line, and success criteria, and the working tree shows no change outside the two planning documents.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Author prose -> staged diff -> `.githooks/pre-commit` | The gitleaks scan runs against the staged diff of `.planning/` prose exactly as it does against code |
| This planning document -> Phase 9 implementer | Requirement text is the only carrier of the security constraints derived above; anything omitted here is unlikely to be rediscovered |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-260818ied-01 | Information disclosure | `.planning/REQUIREMENTS.md`, `.planning/ROADMAP.md` prose | medium | mitigate | Reference credentials by secret *name* only (e.g. the deploy user/host secrets) and never paste a token, connection string, or registry URL containing credentials. A credential-shaped literal in `.planning/` prose is refused by `.githooks/pre-commit`'s gitleaks scan before formatting/tests run — treat that refusal as correct, never as a false positive to be allowlisted. |
| T-260818ied-02 | Elevation of privilege | Future Phase 9 implementation of CI-05 | high | mitigate | CI-05's text must state that the schema registry publishes no host port, so the step runs over the existing SSH path rather than by exposing 8081. Omitting this invites a Phase 9 implementer to publish a write-capable registry to the internet as the path of least resistance. Enforced by Task 1's acceptance criteria requiring the mechanism to be named, and by the Phase 9 rationale bullet tying CI-05 to CI-02's scoping. |
| T-260818ied-03 | Tampering | Out-of-scope files | medium | mitigate | Task 2's verify asserts `git diff --name-only HEAD` contains nothing outside the two planning documents and `.planning/quick/`, so an accidental edit to `deploy.yml` or a committed Phase 8 plan fails the task rather than riding along in the commit. |
| T-260818ied-SC | Tampering | Package installs | low | accept | No package-manager install occurs in this task — it edits two markdown files. The package-legitimacy gate does not apply. |
</threat_model>

<verification>
Run from the repo root after both tasks:

1. `grep -c '^- \[ \] \*\*CI-0[1-5]\*\*' .planning/REQUIREMENTS.md` -> `5`
2. `awk '/^\| (NONPROD|RESET|CI|HARDEN)-/{n++} END{print n}' .planning/REQUIREMENTS.md` -> `20`,
   matching the file's own stated coverage
3. `grep -c 'CI-05' .planning/REQUIREMENTS.md .planning/ROADMAP.md` -> non-zero in both files
4. `git diff --name-only HEAD` -> only `.planning/REQUIREMENTS.md`, `.planning/ROADMAP.md`, and
   `.planning/quick/260818-ied-*/` artifacts
5. `git diff HEAD -- .github .planning/phases docs src` -> empty

Note on grep-gate scoping: every check above names the two target files or a scoped `git diff`
explicitly. None scan `.planning/` recursively, so this PLAN.md's own prose — which necessarily
contains the string `CI-05` many times — cannot satisfy any of them.

No build, test, or format check applies: no file under `src/` or `build.gradle` is touched, so
`./gradlew spotlessCheck` / `./gradlew test` have nothing to react to. The pre-commit hook will still
run its gitleaks pass over the staged prose, which is the intended gate here.
</verification>

<success_criteria>
- CI-05 exists as a requirement, is traced to Phase 9 as Pending, and is counted in REQUIREMENTS.md's
  coverage assertion and Phase 9 mapping rationale.
- ROADMAP.md's Phase 9 requirements, goal, one-line summary, and success criteria all reflect
  schema-registry sync as delivered scope.
- The four non-obvious constraints derived in `<tradeoffs>` that a Phase 9 planner could get wrong —
  within-environment ordering under `auto.register.schemas=false`, the registry's internal-only
  reachability, CI-02 credential scoping, and no hardcoded subject count — are carried in the
  requirement/rationale text rather than left in this plan only.
- The working tree contains no change to workflows, application code, docs, or Phase 8 artifacts.
</success_criteria>

<output>
Create `.planning/quick/260818-ied-add-ci-05-requirement-to-phase-9-automat/260818-ied-SUMMARY.md`
when done. Record in it: the exact CI-05 text as written, the four REQUIREMENTS.md touch points and
four ROADMAP.md touch points, and the explicit checked-and-skipped finding on the milestone goal
paragraph (Task 2 step 5) so the non-edit reads as a decision rather than an oversight.
</output>
