---
phase: quick-260816-tqc
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - docs/diagrams/infra-delivery-scenario.mmd
  - docs/diagrams/infra-delivery-scenario.png
  - docs/INFRA_ARCHITECTURE.md
autonomous: true
requirements: [QUICK-260816-tqc-CICD-SCENARIO-DIAGRAM]
user_setup: []

estimate:
  tokens: 30000
  raw_tokens: 30000
  tasks: 2
  confidence: low

must_haves:
  truths:
    - "docs/INFRA_ARCHITECTURE.md's Scenario (+1) section carries a sequence diagram whose job names and job-graph edges match .github/workflows/deploy.yml exactly as it stands today -- all seven jobs, with build-and-push-docker-image and flyway-verify shown as concurrent (both need only [setup, run-tests]) and deploy-to-netcup shown as gated on both."
    - "The diagram shows the VM-side container switch: `app` is the only service recreated by `docker compose up -d`, while `caddy` and `redpanda` are left running untouched because their image/config did not change."
    - "The diagram states why `name: kanban-board-backend` in docker-compose.prod.yml is load-bearing for that outcome -- it pins project identity (and every named-volume prefix) independent of CWD, so Compose converges the already-running stack instead of starting a second one with fresh empty volumes."
    - "No claim in the Scenario section describes a future/planned state or a job name that deploy.yml does not define -- the section reads as a description of what runs today."
    - "The checked-in PNG is a render of the checked-in .mmd (re-rendered in the same change), and is no wider than the widest diagram PNG already in docs/diagrams/, so it stays legible at GitHub's content width."
    - "No secret value, VM hostname/IP, host-key fingerprint, or .env.prod content appears anywhere in the new source or prose -- secrets are referenced by NAME only."
  artifacts:
    - "docs/diagrams/infra-delivery-scenario.mmd (rewritten as the current-state CI/CD sequence diagram)"
    - "docs/diagrams/infra-delivery-scenario.png (re-rendered from the rewritten source)"
    - "docs/INFRA_ARCHITECTURE.md (Scenario (+1) section prose + Maintenance Note reconciled)"
    - ".planning/quick/260816-tqc-create-a-sequence-diagram-documenting-th/260816-tqc-SUMMARY.md"
  key_links:
    - "Diagram job names <-> the seven job keys under `jobs:` in .github/workflows/deploy.yml. A job rename or addition silently invalidates the diagram, which is why the verify gate counts jobs as well as naming them."
    - "Diagram VM-side beats <-> docker-compose.prod.yml's top-level `name: kanban-board-backend` pin and the `app` service's `image: rudenkovladimir/kanban-board-backend:${IMAGE_TAG}` interpolation. These two lines are the entire mechanism the container-switch half of the diagram describes."
    - "Markdown image/source links in INFRA_ARCHITECTURE.md <-> the two files in docs/diagrams/. The filename is deliberately reused, so both existing links keep resolving with no markdown link edit."
    - "The Maintenance Note is the only instruction telling a future editor that these docs pin deploy.yml/docker-compose.prod.yml facts -- if it is not extended, the next job rename lands with nothing pointing at this diagram."
---

<objective>
Replace `docs/diagrams/infra-delivery-scenario.mmd` with an accurate, current-state Kruchten **Scenario (+1)** sequence diagram of the full CI/CD delivery path -- push to `master`, through all seven `deploy.yml` jobs, through to the Docker Compose container-switch mechanics on the Netcup VM -- re-render its PNG, and reconcile the surrounding prose in `docs/INFRA_ARCHITECTURE.md`.

Purpose: the existing delivery-path diagram was authored in plan 05-02 as a *forecast* of what plans 05-04/05-05 would build. Those plans have since landed and diverged from the forecast: the DDL-verification job shipped under a different name and a different mechanism (Flyway CLI container, not `psql`), a pooled-endpoint guard was added, the cleanup jobs are not depicted at all, the parallelism between the build and verify jobs is not shown, and the whole VM-side half -- which service actually gets replaced and why -- is a single arrow. The surrounding prose still disclaims the diagram as unbuilt and still names a deploy job that no longer exists. This closes that gap and adds the container-switch detail the forecast never had.

Output: one sequence diagram (source + render) that a reader can trust as a description of what happens today, plus prose that no longer contradicts it.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@docs/DIAGRAM_CONVENTIONS.md
@docs/INFRA_ARCHITECTURE.md
@.github/workflows/deploy.yml
@docker-compose.prod.yml
</context>

<approach_analysis>

Required by `.claude/CLAUDE.md`: alternatives considered, trade-off matrix, and the non-obvious trade-offs, before any PLAN is approved.

## Trade-off Matrix

| Approach | Pros / Cons | Why Picked / Rejected |
|---|---|---|
| **A. Rewrite `infra-delivery-scenario.mmd` in place** -- same filename, same section, expanded to the real seven-job graph plus the VM-side container switch; fix the stale prose around it. | **Pros:** exactly one picture of this scenario exists, so there is nothing for a reader to reconcile; both markdown links keep resolving with zero link edits; the known-wrong forecast stops being checked-in documentation; git history still holds the old version for anyone who wants the 05-02-era forecast. **Cons:** loses the diff-friendliness of an "old vs new" side-by-side; a reviewer must read the whole new source rather than an addition. | **PICKED.** `INFRA_ARCHITECTURE.md`'s own Maintenance Note claims the file is "the single checked-in description of what actually runs where." Leaving a superseded picture of the same scenario next to a correct one directly violates that claim, and a reader has no way to tell which is current. |
| **B. Add a new diagram file + a new section**, leave the existing one untouched. | **Pros:** purely additive, zero risk of losing content, smallest diff to review. **Cons:** ships two competing diagrams of one scenario, one of which is now factually wrong (names a verification job that does not exist, disclaims itself as unbuilt); doubles the maintenance surface; the reader has to guess which is authoritative. | **REJECTED.** Additive-only is the safe default when the existing artifact is merely incomplete. Here it is *wrong*, and additive-only would preserve the wrongness. |
| **C. Split into two diagrams** -- keep the pipeline-level Scenario view, add a second VM-only Process view of the container switch. | **Pros:** most literal reading of `DIAGRAM_CONVENTIONS.md`'s one-view-per-diagram rule; each diagram stays narrow and legible; the container switch could grow detail later without crowding the pipeline. **Cons:** two renders, two sources, two places to update on one job rename; the container switch is the *tail of the same scenario*, not a separate concurrency story, so splitting cuts a single causal chain in half at its most interesting point (`up -d` -> what actually changed). | **REJECTED,** but it is the strongest rejected option and worth revisiting if the diagram renders too wide. The width gate in Task 1's verify is precisely the trigger that would force this split -- if the render exceeds the existing PNGs' width, fall back to C rather than shipping an illegible single diagram. |

## Non-obvious trade-offs

- **Legibility at GitHub's content width is the real performance constraint** (this repo's own prior lesson, quick task 260806-nyj: a diagram rendered legibly but "far too wide for GitHub's content column" and had to be reworked). Measured during planning rather than assumed: every checked-in sequence diagram PNG in `docs/diagrams/` renders at exactly **3136px** wide regardless of content (`architecture-mutation-sequence`, `architecture-signin-scenario`, `architecture-error-response-split`, `architecture-activity-feed-read`, and the current `infra-delivery-scenario`), varying only in height. So raw pixel width is a near-useless gate -- the renderer converges on it. The metric that actually governs legibility at a fixed width is **participant density**, and the in-repo precedent is generous: `architecture-mutation-sequence.mmd` ships **10** participants at 3136x1100 and is legible. This diagram's 8 participants are comfortably inside that precedent, which is why approach A (one diagram) is viable at all. Task 1's gate therefore checks participant count against that measured precedent plus a loose width sanity check, and pairs both with a human legibility check -- no invented pixel threshold.
- **State-invalidation risk is the dominant long-term cost.** The diagram hard-codes seven job names, two image references, one Compose project name and three volume names. Any rename in `deploy.yml` or `docker-compose.prod.yml` silently falsifies it with nothing failing. Two mitigations: (1) Task 1's verify counts the jobs defined under `jobs:` as well as grepping each name, so a *newly added* job -- not just a renamed one -- trips the gate on the next run; (2) Task 2 extends the Maintenance Note to name the specific pinned facts, so the next editor of those files is told.
- **Security / information disclosure is a genuine concern, not a checkbox.** This diagram describes the production deploy path of a live, internet-reachable VM. It must reference GitHub secrets by *name* only. `.githooks/pre-commit` runs gitleaks over the staged diff and would refuse a pasted credential, but it will **not** catch a VM hostname, IP, or the `APP_DOMAIN` value -- those are ordinary strings. So this is a discipline control, not a tool-enforced one, and it is written into Task 1's `<done>` rather than assumed.
- **Two accuracy hazards must not be smoothed over.** (1) `cleanup-old-images` runs and reports success, but its Docker Hub DELETE calls are currently rejected (open todo `2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md`) -- the diagram must not depict tag pruning as working. (2) `docker compose up -d` returns once `app` is *started*, not once it is *healthy* (`start_period: 30s`, and nothing `depends_on` the app) -- so a green `deploy-to-netcup` job does not by itself prove the new container reached `UP`. Drawing an implied health gate that does not exist would be a worse error than omitting the detail.
- **Compose-diff mechanics, stated precisely so the diagram is defensible.** `docker compose up -d` compares each service's resolved config against the running container's stored config hash. `app`'s resolved `image:` string changes every deploy (the tag is the commit's short SHA), so `app` is recreated. `caddy` (`caddy:2`) and `redpanda` (`v26.2.1`) resolve byte-identically to what is already running, so Compose leaves them alone -- it is a no-op for them, not a restart. The project-name pin is what makes "already running" resolvable at all: without it Compose derives the project from the CWD basename, and a moved file yields a different project, a different `<project>_*` volume namespace, and therefore fresh empty volumes -- which is exactly the incident recorded in `docs/INFRA_RUNBOOK.md` (Let's Encrypt cert re-issued, 14 Avro subjects temporarily lost). The diagram documents the **fixed** state and cites the incident as the reason the pin exists.

## Data-flow mechanism, in three sentences

A push to `master` fans out to a test job that gates everything, then to two concurrent jobs -- one builds and pushes an `amd64` image tagged with the commit's short SHA to Docker Hub, the other applies this repo's Flyway migrations against Neon's direct (non-pooled) endpoint. Only when both are green does the deploy job SCP the Compose manifest and Caddyfile to the VM over a fingerprint-pinned SSH connection and run `docker compose pull app && docker compose up -d` with `IMAGE_TAG` exported into the shell so it outranks `--env-file`. On the VM, Compose resolves to the pinned `kanban-board-backend` project, finds only the `app` service's image reference changed, recreates that one container against the already-healthy `redpanda`, and leaves `caddy` and `redpanda` -- and their named volumes holding the TLS certificate and the Avro schema registry -- entirely alone.

</approach_analysis>

<source_audit>

Only two source types apply to this quick task: the GOAL (the task description) and CONSTRAINTS. There is no ROADMAP requirement, no RESEARCH.md, and no CONTEXT.md D-NN decision set.

| Source item | Covered by | Status |
|---|---|---|
| GOAL: sequence diagram of push -> run-tests -> build-and-push -> flyway-verify -> deploy-to-netcup | Task 1, beats 1-5 | COVERED |
| GOAL: SCP + SSH mechanics of the deploy step | Task 1, beat 5 | COVERED |
| GOAL: Docker Compose container-switch mechanics on the VM | Task 1, beat 6 | COVERED |
| GOAL: pinned project name, app recreated, caddy/redpanda untouched | Task 1, beat 6 + Task 2 prose paragraph | COVERED |
| GOAL: follow DIAGRAM_CONVENTIONS.md 4+1, Process/Scenario view | Task 1 (declared as Scenario (+1)); Task 2 keeps the file's view declaration honest | COVERED |
| GOAL: match existing diagram location/convention (INFRA_ARCHITECTURE.md, .mmd source + .png render) | Task 1 (same directory, same filename, same render toolchain) + Task 2 | COVERED |
| CONSTRAINT: all seven jobs incl. cleanup-old-images / cleanup-unused-image reflected accurately | Task 1, beats 2-7, gated by the job-count + job-name verify | COVERED |
| CONSTRAINT: actual `name:` pin and `${IMAGE_TAG}` interpolation appear accurately | Task 1, beat 6, gated by literal greps | COVERED |
| CONSTRAINT: documentation-only, no deploy.yml / docker-compose.prod.yml changes | `files_modified` lists three docs files only; Task 2 verify asserts a clean diff on both source files | COVERED |
| CONSTRAINT: do not scope-creep into re-documenting the whole system | Physical/Deployment section left byte-identical except where it names a stale job; no new sections added | COVERED |

No unplanned items.

</source_audit>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| working tree -> public git history | Anything written here is permanent and world-readable once pushed; a leaked value cannot be un-published by a later commit |
| local shell -> npm registry / Docker Hub | An external, unpinned tool is fetched to render the diagram |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-tqc-01 | Information Disclosure | `docs/diagrams/infra-delivery-scenario.mmd`, `docs/INFRA_ARCHITECTURE.md` | medium | mitigate | Reference GitHub secrets by NAME only (`secrets.NETCUP_HOST`, `secrets.NETCUP_HOST_FINGERPRINT`, `secrets.DOCKERHUB_TOKEN`, `secrets.DB_*`). No VM hostname/IP, no `APP_DOMAIN` value, no fingerprint value, no `.env.prod` content, no DB connection string. Matches the existing diagram, which names no host. Written into Task 1's `<done>` and gated by a negative grep -- gitleaks would refuse a credential but will not catch a hostname, so this cannot rely on the hook. |
| T-tqc-02 | Information Disclosure | the topology itself (ports, paths, job graph) | low | accept | Every fact the diagram states is already public in the same document, in `deploy.yml`, and in `docker-compose.prod.yml`. It adds no non-public fact. Deploy-path security rests on key-based auth as a non-root user with a pinned host-key fingerprint, not on topology obscurity. |
| T-tqc-03 | Tampering | Mermaid renderer fetched at render time (`@mermaid-js/mermaid-cli@11` via npx, or the `minlag/mermaid-cli` Docker image) | medium | mitigate | Package-legitimacy gate assessed and found not to apply: no package-manager install enters this project's dependency graph (no `build.gradle`, `package.json`, or lockfile is touched), the tool is ephemeral and build-time-only, its sole output is a PNG under `docs/diagrams/`, and **both** candidates are prior art in this repo's own reviewed history (`minlag/mermaid-cli` in plan 05-02, `@mermaid-js/mermaid-cli@11` in quick task 260806-nyj). Pin to those two; do not substitute an unvetted alternative. Recorded here rather than skipped silently. |
| T-tqc-04 | Repudiation | the diagram's own accuracy claims | medium | mitigate | Every job name and job-graph edge is gated by an automated check against `deploy.yml` (name greps **plus** a job count, so an added job also trips it), and the two Compose literals are gated by greps against `docker-compose.prod.yml`. The diagram cannot silently claim a pipeline shape the source files do not have. |
</threat_model>

<tasks>

<task type="tracer" tdd="false">
  <name>Task 1: Rewrite the delivery-path sequence diagram as current-state, and re-render it</name>
  <files>docs/diagrams/infra-delivery-scenario.mmd, docs/diagrams/infra-delivery-scenario.png</files>
  <precondition>A Mermaid renderer is obtainable -- either `npx` can reach the npm registry, or the Docker daemon is running. No renderer is vendored in this repo. Both were confirmed available on this machine during planning (Docker 29.7.2; node v24.6.0 / npx 11.5.1); assert one still works before authoring, and halt rather than hand-editing the checked-in PNG if neither does.</precondition>
  <read_first>
    Read `.github/workflows/deploy.yml` and `docker-compose.prod.yml` in full before writing a single line of the diagram. Every beat below must be checked against those two files as they stand right now -- do not reconstruct any of it from the existing `.mmd`, which is a forecast written before either file reached its current shape. Also read `docs/DIAGRAM_CONVENTIONS.md` (the view declaration this diagram must honour) and `docs/diagrams/architecture-mutation-sequence.mmd` (the house style for a sequence diagram in this repo: `alt`/`Note over` usage, `<br/>` line breaks, short aliases).
  </read_first>
  <action>
    Replace the contents of `docs/diagrams/infra-delivery-scenario.mmd` with a Mermaid `sequenceDiagram` that is one deliberate Kruchten **Scenario (+1)** view -- one end-to-end flow, one point in time -- not a topology or component picture. Do not add a second file; reuse this filename so both markdown links in `INFRA_ARCHITECTURE.md` keep resolving unchanged.

    Participants, left to right, with short aliases and the platform annotations this project's conventions require on nodes: an actor for the Developer; the GitHub Actions runner (annotate `x86_64, ubuntu-latest`); Docker Hub; Neon Postgres (annotate that this is the direct, non-pooled endpoint); then a `box` grouping the Netcup VPS (annotate `x86_64, Vienna`) containing four participants -- the Compose CLI itself (label it with the pinned project name), and the `app`, `caddy` and `redpanda` containers.

    The diagram must carry these beats, in this order, each verified against the source files:

    1. Developer pushes to `master` -- the workflow's only trigger.
    2. `setup` derives the base image name from the workflow-level `DOCKERHUB_USER`/`DOCKERHUB_REPOSITORY` env values.
    3. `run-tests` (needs `setup`) runs `./gradlew test` then `./gradlew spotlessCheck` on Temurin JDK 21. Make it visually clear this job gates everything downstream.
    4. A `par` block for the two jobs that both need only `[setup, run-tests]` and therefore run **concurrently** -- this parallelism is a real property of the job graph and the current diagram misses it:
       - `build-and-push-docker-image`: derives the image tag from the commit's short SHA (`${GITHUB_SHA::7}`), builds `linux/amd64` natively on the runner (state explicitly that no QEMU cross-compilation is involved, since the runner and the VM are the same architecture), and pushes to Docker Hub.
       - `flyway-verify`: first refuses to proceed if the configured DB host carries Neon's pooled-endpoint marker, then runs the pinned Flyway CLI container's `migrate` against Neon over `sslmode=require`. Add a `Note` recording the honest consequence: this applies the migrations to the real production database at this point in the pipeline -- before the new image ever reaches the VM -- so a schema change and the code that consumes it are not deployed atomically.
    5. `deploy-to-netcup` (needs **both** of the above). Show, in order: the SCP of `docker-compose.prod.yml` and `Caddyfile` into the deploy directory over an SSH connection whose host key is pinned by fingerprint, noting that no remove-orphans/`rm` behaviour is enabled so the never-committed production env file on the VM survives; then the SSH step exporting the image tag into the shell (note *why*: a shell variable outranks `--env-file` precedence, so this commit's freshly built tag always wins); then `docker compose pull app`, drawn as the VM pulling from Docker Hub, not the runner. Add a `Note` on the concurrency group: a second push during an in-flight deploy queues behind it rather than cancelling it, precisely so the SCP step cannot be interrupted half-copied.
    6. The container-switch half -- the payload of this diagram, and the part the current version reduces to one arrow. Show `docker compose up -d`, then:
       - a `Note over` the Compose participant explaining that the top-level `name: kanban-board-backend` key pins project identity, and with it every named volume's `<project>_*` prefix, independent of the working directory the command runs from -- so Compose resolves to the **already-running** stack instead of starting a second, unrelated one;
       - Compose diffing each service's resolved config against what is running;
       - `app` **recreated**, because its `image:` reference resolves to a new tag every deploy, and started only after the `service_healthy` condition on `redpanda` is satisfied (already true, since redpanda was untouched);
       - `caddy` and `redpanda` explicitly **not** recreated -- their resolved config is byte-identical to what is running, so this is a no-op for them, not a restart. Use a distinct arrow style (e.g. `--x`) so "left alone" reads differently from "acted on". State what that preserves: caddy's named volume keeps the existing Let's Encrypt certificate (no rate-limited re-request) and redpanda's keeps the Kafka log and the registered Avro subjects;
       - a `Note` tying the two together: this is exactly the outcome the project-name pin buys, and it is the fixed state of the incident recorded in `docs/INFRA_RUNBOOK.md`, where a directory-derived project name produced a fresh empty volume namespace. Reference the runbook rather than re-telling the incident.
       - a `Note` on the honest limit of the deploy gate: `up -d` returns once `app` is started, not once its healthcheck passes, and nothing in the pipeline waits on that healthcheck -- so a green deploy job does not by itself prove the new container reached `UP`.
    7. An `alt` on the run's outcome covering the two cleanup jobs: on success, `cleanup-old-images` attempts to delete every Docker Hub tag except the one just deployed -- and must be annotated as currently **not** achieving that, its DELETE calls being rejected (cite the open todo `2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md`), so tags still accumulate while the job reports success; on any failure, `cleanup-unused-image` resolves the just-pushed manifest's digest and deletes by digest.

    Reference every credential by secret NAME only. No VM hostname or IP, no application domain value, no host-key fingerprint value, no database connection string, no env-file contents.

    Then re-render `docs/diagrams/infra-delivery-scenario.png` from the rewritten source using one of the two renderers this repo has already used, and commit the regenerated PNG in the same change as its source -- a source and a render that disagree is the failure mode this file's checked-in PNG exists to avoid. If the render comes out wider than the diagrams already in `docs/diagrams/`, do not ship it: fall back to approach C from the trade-off matrix (split the VM-side half into its own Process-view diagram) rather than checking in something illegible at GitHub's content width.
  </action>
  <verify>
    <automated>
cd "C:/Dev/Repos/kanban-board-backend" &&
npx -y @mermaid-js/mermaid-cli@11 -i docs/diagrams/infra-delivery-scenario.mmd -o docs/diagrams/infra-delivery-scenario.png &&
for j in setup run-tests build-and-push-docker-image flyway-verify deploy-to-netcup cleanup-old-images cleanup-unused-image; do
  grep -v '^[[:space:]]*%%' docs/diagrams/infra-delivery-scenario.mmd | grep -qw -- "$j" || { echo "DIAGRAM MISSING JOB: $j"; exit 1; }
done &&
test "$(awk '/^jobs:/{f=1;next} f&&/^  [a-z][a-z0-9-]*:$/{c++} END{print c+0}' .github/workflows/deploy.yml)" = "7" &&
grep -q 'name: kanban-board-backend' docs/diagrams/infra-delivery-scenario.mmd &&
grep -q 'IMAGE_TAG' docs/diagrams/infra-delivery-scenario.mmd &&
grep -qE 'linux/amd64' docs/diagrams/infra-delivery-scenario.mmd &&
! grep -nEi '[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|SHA256:|BEGIN [A-Z ]*PRIVATE KEY' docs/diagrams/infra-delivery-scenario.mmd &&
test "$(grep -cE '^[[:space:]]*(participant|actor)[[:space:]]' docs/diagrams/infra-delivery-scenario.mmd)" -le 10 &&
node -e "const fs=require('fs'),d='docs/diagrams',w=f=>fs.readFileSync(d+'/'+f).readUInt32BE(16),t=w('infra-delivery-scenario.png'),m=Math.max(...fs.readdirSync(d).filter(f=>f.endsWith('.png')&&f!=='infra-delivery-scenario.png').map(w));console.log('new width',t,'| existing max',m);if(t>m){console.error('RENDER WIDER THAN EVERY EXISTING DIAGRAM -- fall back to approach C');process.exit(1)}" &&
test docs/diagrams/infra-delivery-scenario.png -nt docs/diagrams/infra-delivery-scenario.mmd &&
git diff --quiet -- .github/workflows/deploy.yml docker-compose.prod.yml && echo "OK: render clean and newer than source, all 7 jobs present, exactly 7 jobs defined, participants within the 10-participant in-repo precedent, no secret-shaped literal, source files untouched"
    </automated>
    <human-check>Open the regenerated PNG and confirm the VM-side half reads clearly at GitHub content width -- specifically that "app recreated" and "caddy/redpanda left alone" are visually distinguishable, not two identically-styled arrows.</human-check>
  </verify>
  <done>
    `docs/diagrams/infra-delivery-scenario.mmd` is a Scenario (+1) sequence diagram of the current pipeline: all seven jobs named, `build-and-push-docker-image` and `flyway-verify` drawn as concurrent, `deploy-to-netcup` gated on both, the SCP+SSH steps and their fingerprint pinning shown, and the VM-side switch showing `app` recreated while `caddy` and `redpanda` are left untouched, with the `name:` pin named as the reason. The Flyway-applies-to-production note, the `up -d`-does-not-wait-for-health note, and the currently-failing tag-cleanup annotation are all present. `infra-delivery-scenario.png` is a fresh render of that source and is no wider than the diagrams already checked in. No secret value, host, IP, fingerprint, or env-file content appears in the source. `deploy.yml` and `docker-compose.prod.yml` are byte-identical to their pre-task state.
  </done>
</task>

<task type="auto" tdd="false">
  <name>Task 2: Reconcile the surrounding INFRA_ARCHITECTURE.md prose with the new diagram</name>
  <files>docs/INFRA_ARCHITECTURE.md</files>
  <read_first>
    Read the whole of `docs/INFRA_ARCHITECTURE.md` -- specifically its view-declaration paragraph near the top, the entire "Scenario (+1) View — Delivery Path" section, and the closing "Maintenance Note". Read `.github/workflows/deploy.yml` again for the exact current job name of the migration-verification job and the exact platform string the build job targets.
  </read_first>
  <action>
    Edit only the Scenario (+1) section and the Maintenance Note. Leave the Physical/Deployment section alone except where it names something the new diagram contradicts. Use scoped `Edit` calls, never a whole-file rewrite.

    In the Scenario (+1) section:
    - Delete the paragraph that disclaims the diagram as a forecast of a future state rather than a description of the live one, and delete the sentence about the old, disabled AWS EC2 deploy job. Both plans have landed; there is nothing left to forecast and no such job in the file. Replace them with a short lead-in framing this as the delivery path as it runs today, naming the date or the plan that made it current so a future reader can date the claim.
    - Correct every reference to the migration-verification job to the name `deploy.yml` actually defines today (`flyway-verify`), and correct its described mechanism: it runs the pinned Flyway CLI container against this repo's own `src/main/resources/db/migration` scripts, not a hand-rolled sequence of DDL scripts. Keep the existing, still-correct point that it targets Neon's direct (non-pooled) endpoint, and add the guard that refuses to run if the configured host carries the pooler marker.
    - Fix the malformed final clause of the "Externally reachable vs. internal-only (delivery path)" paragraph -- as written it runs two thoughts together and ends mid-sentence. The intended point is that no delivery-path connection touches Redpanda or the app's internal-only listeners: the pipeline talks to the VM's SSH port only, and Caddy's public HTTPS is not part of the delivery path at all.
    - Add one new short paragraph -- the prose companion to the diagram's container-switch half, and the thing a reader most needs in words rather than arrows. It must state: which single service `docker compose up -d` recreates and why (its resolved image reference changes every deploy); that `caddy` and `redpanda` are left running because their resolved config is unchanged, so this is a no-op for them rather than a restart; and why the pinned top-level project name in `docker-compose.prod.yml` is load-bearing for that -- it makes project identity, and every named volume's prefix, independent of the directory the command runs from, so Compose converges the running stack instead of creating a second one against empty volumes. Cite `docs/INFRA_RUNBOOK.md` for the incident that motivated the pin rather than re-telling it here. Close with the honest limit: nothing in the pipeline waits for the new `app` container's healthcheck, so a green deploy job is not by itself proof the new container reached `UP`.

    In the Maintenance Note: correct the stale platform claim about the build job (it targets amd64 now, not ARM64 -- the deploy target pivoted from Oracle to Netcup), and extend the note to name the specific facts these docs now pin, so the next person renaming a job or a service is told this diagram depends on them: the seven job names and the job graph in `.github/workflows/deploy.yml`, and the top-level project-name key plus the `app` service's tag interpolation in `docker-compose.prod.yml`.

    Change no other section, and touch no file outside `docs/`.
  </action>
  <verify>
    <automated>
cd "C:/Dev/Repos/kanban-board-backend" &&
if grep -nE 'ddl-verify|deploy-to-ec2|linux/arm64|target state after plan 05-05|still built by later plans' docs/INFRA_ARCHITECTURE.md; then echo "STALE CLAIM REMAINS"; exit 1; fi &&
grep -q 'flyway-verify' docs/INFRA_ARCHITECTURE.md &&
grep -q 'linux/amd64' docs/INFRA_ARCHITECTURE.md &&
grep -q 'name: kanban-board-backend' docs/INFRA_ARCHITECTURE.md &&
grep -q 'INFRA_RUNBOOK.md' docs/INFRA_ARCHITECTURE.md &&
grep -q 'diagrams/infra-delivery-scenario.png' docs/INFRA_ARCHITECTURE.md &&
grep -q 'diagrams/infra-delivery-scenario.mmd' docs/INFRA_ARCHITECTURE.md &&
test -f docs/diagrams/infra-delivery-scenario.png && test -f docs/diagrams/infra-delivery-scenario.mmd &&
test "$(grep -c '^## ' docs/INFRA_ARCHITECTURE.md)" = "3" &&
git diff --quiet -- .github/workflows/deploy.yml docker-compose.prod.yml &&
! git status --porcelain | awk '{print $NF}' | grep -qvE '^(docs/|\.planning/)' &&
echo "OK: no stale claim, new facts present, links resolve, section count unchanged, only docs/ and .planning/ modified"
    </automated>
  </verify>
  <done>
    The Scenario (+1) section reads as a description of what runs today: no forecast disclaimer, no reference to a job `deploy.yml` does not define, the migration-verification job named and described correctly including its pooled-endpoint guard, the truncated internal-vs-external sentence repaired, and one new paragraph explaining the container switch (`app` recreated; `caddy`/`redpanda` untouched; why the project-name pin makes that true; the runbook cited; the healthcheck caveat stated). The Maintenance Note names the correct build platform and lists the specific `deploy.yml` and `docker-compose.prod.yml` facts these docs now pin. Both markdown links still resolve to the two files in `docs/diagrams/`. `deploy.yml` and `docker-compose.prod.yml` are unchanged; no file outside `docs/` and `.planning/` is modified.
  </done>
</task>

</tasks>

<verification>
1. `./gradlew spotlessCheck` is not required -- no `src/**/*.java` file is touched -- but confirm the working tree has no Java diff before committing (`git diff --name-only | grep -c '\.java$'` returns 0).
2. `.githooks/pre-commit` runs gitleaks over the staged diff first; the commit must pass it without any new `.gitleaks.toml` entry. If gitleaks fires, treat it as a real finding and remove the offending literal from the diagram -- do not add an exemption.
3. Both `docs/diagrams/infra-delivery-scenario.mmd` and `.png` are staged in the same commit, and the PNG's mtime is newer than the `.mmd`'s.
4. Re-read the rendered PNG once more against `.github/workflows/deploy.yml` end to end -- job names, `needs:` edges, and the two cleanup jobs' `if:` conditions -- confirming by file:line rather than by recollection.
</verification>

<success_criteria>
- `docs/INFRA_ARCHITECTURE.md`'s Scenario (+1) section presents one sequence diagram of the current CI/CD delivery path, with no competing or superseded diagram of the same scenario anywhere in the repo.
- The diagram's seven job names and job-graph edges match `.github/workflows/deploy.yml` exactly, and the automated gate fails if a job is added or renamed without updating the diagram.
- The VM-side container switch is documented in both the diagram and prose: `app` recreated, `caddy` and `redpanda` untouched, with the `name: kanban-board-backend` pin named as the mechanism and `docs/INFRA_RUNBOOK.md` cited for the incident that motivated it.
- Two accuracy hazards are stated rather than smoothed over: `flyway-verify` applies migrations to the real production database before the image is deployed, and `up -d` does not wait for the new `app` container's healthcheck.
- No secret value, VM hostname/IP, host-key fingerprint, or env-file content appears in any new content.
- `.github/workflows/deploy.yml` and `docker-compose.prod.yml` are byte-identical to their pre-task state.
</success_criteria>

<output>
Create `.planning/quick/260816-tqc-create-a-sequence-diagram-documenting-th/260816-tqc-SUMMARY.md` when done.
</output>
