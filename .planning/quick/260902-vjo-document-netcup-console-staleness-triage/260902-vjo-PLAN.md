---
quick_id: 260902-vjo
type: quick
autonomous: true
requirements: [QUICK-01, QUICK-02, QUICK-03, QUICK-04, QUICK-05, QUICK-06]
files_modified:
  - docs/INFRA_RUNBOOK.md
  - .github/workflows/uptime-check.yml

estimate:
  tokens: 45000
  raw_tokens: 45000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "A reader who opens docs/INFRA_RUNBOOK.md after seeing an alarming kernel line on the Netcup SCP 'Screen' tab can date it to wall-clock time without asking anyone, using only commands written in that document (QUICK-01)."
    - "The runbook states, as its own claim rather than an aside, that the Screen tab is a frozen tty1 framebuffer whose bracketed timestamps are seconds since boot — so a fixed, week-old incident renders identically to a live outage (QUICK-01)."
    - "The runbook documents the UTC-vs-Europe/Berlin normalisation step with the concrete 2026-08-26 case (docker inspect StartedAt 19:24:34Z = 21:24 CEST vs the last dmesg kill at 20:28:31 CEST), and states plainly that comparing the two raw inverts the ordering and manufactures an incident (QUICK-02)."
    - "The runbook attributes the four Aug 26 2026 postgres OOM kills to the two named, already-closed measurement sections in this same file, so a future reader treats them as expected historical console noise and escalates only a kill that cannot be dated to one of them (QUICK-03)."
    - "A manual `workflow_dispatch` run of .github/workflows/uptime-check.yml against the live, healthy endpoints finishes green (QUICK-04)."
    - "The identical check script, run against a deliberately bad endpoint, exits non-zero and names which endpoint failed and with what status — proven for all three failure shapes (non-200, unreachable/000, 200-with-wrong-body), not asserted (QUICK-05, QUICK-06)."
    - "A failure on the first endpoint does not stop the second from being checked — one environment being down never masks the other (QUICK-05)."
    - "The workflow carries no secret, no credential-shaped literal, and no `NETCUP_*` reference, so `.githooks/pre-commit`'s gitleaks scan has nothing to find in it."
  artifacts:
    - "docs/INFRA_RUNBOOK.md — a new top-level `## Triage — dating what the Netcup SCP \"Screen\" console shows` section, inserted between `## Access` and `## Firewall — two independent layers`"
    - ".github/workflows/uptime-check.yml — scheduled + dispatchable probe of both public /api/actuator/health endpoints"
  key_links:
    - "The runbook section and the workflow cross-reference each other: the runbook's triage order names the workflow as the standing automated answer to 'was it up recently', and the workflow's header comment names docs/INFRA_RUNBOOK.md's triage section as where to go once a run goes red. Neither is useful alone — the workflow says THAT something is wrong, the runbook says how to date and attribute it."
    - "`management.endpoint.health.show-details=never` (src/main/resources/application.properties) is what makes the health probe both sufficient and limited: Spring's aggregate status is the worst of all contributors including the auto-configured DataSource indicator, so a public `UP` is a genuine end-to-end database answer — but the body carries no component breakdown, so a red run can name the endpoint and its status and can never name the failing subsystem. Widening exposure to fix that would publish datasource internals, which that file's own comment already rejects."
    - "The workflow's `run:` body must contain zero `${{ }}` interpolation — every value arrives through the job's `env:` block. This is simultaneously the repo's documented injection-safety rule (see security-scan.yml's NVD_API_KEY step comment) and the mechanism that makes Task 3's local proof genuinely identical to what CI executes, rather than a hand-retyped approximation of it."
    - "Committing `.github/workflows/uptime-check.yml` WILL trigger a full production+nonprod redeploy: deploy.yml's `paths-ignore` excludes `docs/**`, `**/*.md` and `.planning/**` but deliberately NOT `.github/**` (its own comment states a workflow change genuinely needs a real pipeline run). Expected, not a problem — but it is the reason this commit is not free."
---

<objective>
Two deliverables closing out the 2026-09-02 Netcup console false alarm: (A) triage guidance in
`docs/INFRA_RUNBOOK.md` that lets a future reader date what the SCP "Screen" tab shows before
escalating, and (B) a scheduled GitHub Actions probe of both public health endpoints so the
question "is it actually down?" has a cheap standing answer.

Purpose: a screenshot of a frozen console cost a full triage session for an incident that had been
fixed a week earlier and was already documented in this repo. The missing piece was never the
evidence — it was the procedure for dating the evidence, plus any independent signal of current
liveness.

Output: one new `##` section in the runbook and one new workflow file. No change to the VPS, to any
compose file, or to postgres tuning — nothing is broken there.
</objective>

<context_fidelity>
**LOCKED — do not revisit.** GitHub Actions cron ONLY. UptimeRobot was offered alongside it and
explicitly declined. No third-party monitor, no `healthchecks.io` dead-man switch, no on-VM systemd
timer, no wizard script. Exactly two deliverables; do not add a third.

**Established fact — do not re-derive, and do not SSH to the VPS.** Every finding in the
originating triage (both endpoints 200/`UP`, nonprod signin 401 proving the DB path, 6 containers
Up with `RestartCount=0`, the four kills dated to Aug 26, the `oom_kill 0` per-instance counter,
`memory.peak` 109678592 vs `memory.max` 268435456, the live engine settings) was verified live on
2026-09-02. Treat it as given.
</context_fidelity>

<design_alternatives>
## Alternate approaches considered

### (A) Where the runbook section goes

`docs/INFRA_RUNBOOK.md` is 3,233 lines in two distinct registers: a standing operational reference
in roughly lines 1–281 (Provider and host, Access, Firewall, Verified state, Database, Backups,
DNS), then an append-only chronological log of dated per-plan records from line 282 to the end.

| Approach | Pros / Cons | Why picked / rejected |
|----------|-------------|-----------------------|
| **New `##` section between `## Access` (ends line 47) and `## Firewall` (line 49)** | + Lands at ~line 48 of 3,233 — findable under pressure, which is the only condition this content is ever read under. + `## Access` already enumerates how you reach this box; the SCP console is the out-of-band access surface, and a reader who opened it because SSH looked risky is exactly the reader scrolling to Access. + Sits immediately above the file's only other "the Netcup panel is lying to you" callout (the Layer 2 firewall gotcha), keeping both panel-behavior traps adjacent. − Pushes every subsequent line number in a file that other docs reference by section name (not by line), so the cost is nil. | **PICKED** |
| Fold into the existing **`### Layer 2: Netcup Cloud Firewall` "Known gotcha"** callout | + Zero new headings; both are SCP-panel gotchas. − Wrong subject: the Screen tab has nothing to do with firewall policy, and a triage procedure nested under network configuration is not where anyone looks for it. − Would bury a numbered check order inside a paragraph about rule evaluation. | Rejected — topical adjacency is not the same as belonging. |
| Append a `##` section near **`## Maintenance note`** (end of file) | + Matches the file's dominant append-only habit. − Line ~3,230 of 3,233. − That habit exists for *dated records of one plan*; this is standing guidance with no date-scoped validity, so following the habit here would be cargo-culting the file's shape against its own organising principle. | Rejected on findability. |

### (B) How the uptime check is implemented

| Approach | Pros / Cons | Why picked / rejected |
|----------|-------------|-----------------------|
| **GitHub Actions `schedule` + `workflow_dispatch`, curl both public endpoints** | + Zero new infrastructure, zero credentials, lives in the repo next to the two existing scheduled/gated workflows. + Free on this repository (confirmed PUBLIC at planning time via `gh repo view`), so interval choice is not a billing decision. + Runs from off-box, so it can actually observe the failure mode it exists for. − Detection latency is interval + GitHub's own scheduling jitter. − No paging; a red run is a red run. | **PICKED** — and locked by the user. |
| External monitor (UptimeRobot) | + True ~1–5 min polling with real alerting, independent of GitHub availability. − A third-party account and a second place to look. | **Rejected by the user explicitly.** Not re-opened here. |
| On-VM systemd timer curling `localhost` | + No jitter, no external dependency, sub-minute if wanted. − Structurally incapable: if the VM is down — the exact event being monitored — the monitor is down with it. A self-hosted liveness probe reports "up" or reports nothing, and "nothing" is indistinguishable from "never ran". | Rejected on the mechanism, independent of the user's lockout. |

### Cron interval: `*/15`, not `*/5`

GitHub documents 5 minutes as the floor, and also documents that `schedule` events are queued on
shared runners and can be delayed — materially, and disproportionately near the top of the hour.
So the real detected-outage latency of a `*/5` schedule is not 5 minutes; it is 5 minutes plus a
jitter term of the same order or larger. The nominal 3× latency advantage over `*/15` is therefore
mostly notional, while the 3× run-count difference is exact: 8,640 vs 2,880 runs/month, all of
them landing in the same Actions tab a human reads to find deploy runs. `*/15` buys nearly the
same real latency for a third of the noise.

## Non-obvious trade-offs

- **The probe can name the endpoint, never the subsystem.** `management.endpoint.health.show-details=never`
  means the public body is exactly `{"status":"UP"}`. Spring's aggregate is the worst of all
  contributors, including the auto-configured DataSource indicator, so `UP` is a real end-to-end
  database answer — but a `DOWN` tells you only that *something* rolled up. Widening exposure to
  recover the breakdown would publish datasource internals to the internet, which
  `application.properties`' own comment already rejects. Accepted as-is.
- **A naive single-shot check manufactures the failure class this task exists to reduce.** One
  transient TLS/DNS blip from a shared runner would redden a run and produce exactly the "is it
  down?" scramble that motivated this work. Hence 3 attempts × 10s per endpoint before declaring
  it down — cheap (a healthy run stays sub-minute) and directly on point.
- **Scheduled workflows are auto-disabled after 60 days of repository inactivity.** The monitor
  then stops silently, which reads identically to "everything has been fine". Documented in the
  file's own header comment rather than left to be discovered.
- **This commit is not free.** `deploy.yml`'s `paths-ignore` covers `docs/**`, `**/*.md` and
  `.planning/**` but deliberately not `.github/**`, so pushing the new workflow triggers a full
  build + production + nonprod deploy. Intended by that filter's own comment; called out so it is
  not a surprise.
- **Gradle gates are not semantically relevant, but are still paid.** Neither deliverable touches
  `src/**`, so `spotlessCheck` and `test` cannot have an opinion about either file. They are not
  skipped: `.githooks/pre-commit` runs gitleaks, then `./gradlew spotlessCheck`, then the fast test
  slice on every commit regardless of what changed (~4 min combined per that hook's own comment).
  Budget the time; do not reach for `--no-verify`.
</design_alternatives>

<context>
@.planning/STATE.md
@docs/INFRA_RUNBOOK.md
@.github/workflows/security-scan.yml
@.github/workflows/secret-scan.yml
</context>

<planning_time_observations>
Observed live at planning time (2026-09-02), and the authority for this plan's file scope —
re-observe before editing, per the mutable-scope rule:

- `git status --porcelain -- docs/ .github/` → empty. Both target trees are clean; neither file has
  uncommitted local work to reconcile against.
- `.github/workflows/uptime-check.yml` does not exist. Task 2 creates, never edits.
- `gh repo view` → `RudVlad473/kanban-board-backend`, `"isPrivate": false`. Actions minutes are free
  here; the interval decision above is not a billing one.
- `docs/INFRA_RUNBOOK.md` is 3,233 lines; `## Access` runs lines 33–47, `## Firewall — two
  independent layers` begins at line 49.
- `python3 -c "import yaml"` → PyYAML 6.0.3 present. `yq` is NOT installed. Task 3's extraction
  command is written against PyYAML for that reason.
- `.github/` contains exactly three workflows: `deploy.yml`, `secret-scan.yml`, `security-scan.yml`.
  No `actionlint` configuration exists anywhere in the repo, so there is no YAML linter to satisfy.
- Task 3's four probe URLs, curled at planning time — each already exhibits the branch it is meant
  to exercise, so the negative proof is not resting on an assumption about what they return:

  | URL | `%{http_code}` | curl rc | Branch it proves |
  |-----|----------------|---------|------------------|
  | `https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health` | `200`, body `{"status":"UP"}` | 0 | positive |
  | `https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/does-not-exist` | `401` | 0 | non-200 |
  | `https://this-host-does-not-exist-kanban.invalid/api/actuator/health` | `000` | 6 | unreachable — confirms the `000` fallback is the shape curl actually produces |
  | `https://example.com/` | `200` | 0 | 200 with a body that cannot carry the UP marker |

  The bogus actuator path answers `401`, not `404` — Spring Security rejects it before the
  exposure allowlist is consulted. Immaterial to the branch under test, recorded so the executor
  does not read it as a surprise.
</planning_time_observations>

<tasks>

<task type="auto">
  <name>Task 1: Add the console-triage section to docs/INFRA_RUNBOOK.md</name>
  <files>docs/INFRA_RUNBOOK.md</files>
  <precondition>`git status --porcelain -- docs/INFRA_RUNBOOK.md` is empty (observed empty at planning time). If it is not, stop and reconcile the local change before editing — the insertion point below was located against the committed file.</precondition>
  <action>
Insert one new top-level section titled `## Triage — dating what the Netcup SCP "Screen" console
shows (2026-09-02)` between the end of `## Access` and the `## Firewall — two independent layers`
heading. Match the file's established register: `##` section with `###` subsections, bolded
lead-in claims, markdown tables for field/value pairs, fenced `bash` blocks for anything meant to
be pasted. Anchor the insertion on the `## Firewall — two independent layers` heading text, not on
a line number.

The section must carry all of the following, in this order.

**Lead paragraph.** State as the section's own claim that the SCP "Screen" tab is a frozen tty1
framebuffer, not a live log: kernel console messages persist on it indefinitely, and their
bracketed timestamps (e.g. `[799547.643450]`) are seconds since boot, not wall-clock. The
consequence, stated plainly: a week-old, already-fixed incident renders identically to an outage in
progress. Record the concrete 2026-09-02 case in one or two sentences — a screenshot of four
`Memory cgroup out of memory: Killed process NNNN (postgres) ...` lines was reported as "nonprod is
down"; nonprod was not down, and the kills were seven days old.

**`### Triage order`** — a numbered list, cheapest first, each step with the command to run:

1. Curl both public endpoints, which needs no SSH and answers the actual question:
   `curl -s -o /dev/null -w '%{http_code}\n' https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health`
   and the same for `https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health`
   (drop `-o /dev/null` to see the `{"status":"UP"}` body). Note why this is a real check and not
   just "the process is listening": Spring's aggregate health status is the worst of all
   contributors, including the auto-configured DataSource indicator, so a public `UP` covers the
   database. Note the limit in the same breath: `management.endpoint.health.show-details=never`
   means the body carries no component breakdown, so `DOWN` never names the failing subsystem. For
   a stricter proof that the query path executes, a `POST /api/signin` with deliberately wrong
   credentials should answer `401` — not a `500`, not a timeout.
2. `ssh netcup-prod`, then `docker ps` and, per container,
   `docker inspect -f '{{.Name}} restarts={{.RestartCount}} started={{.State.StartedAt}} oomkilled={{.State.OOMKilled}}' <container>`.
3. `dmesg -T | grep -i "out of memory"` — `-T` is the whole point of the step: it renders the
   seconds-since-boot bracket as local wall-clock, which is what dates whatever the console showed.
4. Normalise the clocks before comparing anything (see the subsection below).
5. Read the per-instance kill counter for the container in question:
   `cat /sys/fs/cgroup/system.slice/docker-$(docker inspect -f '{{.Id}}' <container>).scope/memory.events`
   and, in that same directory, `memory.peak` against `memory.max` for headroom. State the scoping
   caveat explicitly, because it is what makes the counter meaningful: `oom_kill 0` means nothing
   has been killed since **this container instance started** — the counter is per-instance and
   resets with it, so it is evidence about the current instance's lifetime, never about the box's
   history. On 2026-09-02 this read `oom 0` / `oom_kill 0` with `memory.peak` 109678592 against a
   `memory.max` of 268435456 (~40.8% of cap).

**`### The timezone trap`** — a short table plus the worked case. Table rows: `dmesg -T` on the VM
reports **Europe/Berlin** (CEST, UTC+2), example `Wed Aug 26 20:28:31 2026`;
`docker inspect -f '{{.State.StartedAt}}'` reports **UTC, always**, example `2026-08-26T19:24:34Z`.
Then the point, in bold: compared raw, `20:28` looks later than `19:24`, which reads as "the
process was killed after the current container started" — an open incident. Normalised, `19:24Z`
is `21:24` CEST, 56 minutes **after** the last kill, and nothing has been killed since. State that
this exact inversion is what turned a closed experiment into a reported outage.

**`### The Aug 26 2026 kills specifically`** — attribute them so a future reader stops rather than
escalates. The three kills at 16:54:06 / 16:54:17 / 16:54:32 CEST are the `32m` rung of this file's
own **"Self-hosted Postgres resource measurement — Plan 11-03"** section (its `### Step below the
floor` subsection reproduces the identical kernel lines). The single kill at 20:28:31 CEST is the
live reproduction of the unfixed `mem_limit: 64m` / `shared_buffers=128MB` pairing recorded under
**"Postgres memory profile correction — Plan 11-08"** (`### Counter-evidence: the previous
configuration under the same workload`). Both were deliberate ladder experiments, both are closed,
and the profile in force since is the one in this file's Database table (`mem_limit` `256m`,
`shared_buffers=64MB`, `work_mem=4MB`, `max_connections=25`). Close with the escalation rule: these
four are expected historical noise on that console — a kill that cannot be dated to one of them is
genuinely new, and that is when to escalate.

**`### Standing automated check`** — one short paragraph pointing at
`.github/workflows/uptime-check.yml`, with its honest limit stated rather than implied: it answers
"were both endpoints answering within roughly the last quarter-hour", not "is nonprod up right
now", and it pages nobody.

Reference other sections of this file by their heading text (a reader can find those); do not cite
`.planning/` plan identifiers, D-numbers, or quick-task ids anywhere in the prose.
  </action>
  <verify>
    <automated>grep -c 'seconds since boot' docs/INFRA_RUNBOOK.md | grep -qv '^0$' &amp;&amp; grep -q 'Triage — dating what the Netcup SCP' docs/INFRA_RUNBOOK.md &amp;&amp; grep -q 'memory.events' docs/INFRA_RUNBOOK.md &amp;&amp; grep -q '2026-08-26T19:24:34Z' docs/INFRA_RUNBOOK.md &amp;&amp; grep -q 'uptime-check.yml' docs/INFRA_RUNBOOK.md &amp;&amp; awk '/^## /{print NR": "$0}' docs/INFRA_RUNBOOK.md | grep -A1 '## Access' | grep -q 'Triage'</automated>
  </verify>
  <done>
The new `##` section exists, sits immediately after `## Access` and immediately before
`## Firewall — two independent layers` (confirmed by the heading-order check above), and states:
the frozen-framebuffer/seconds-since-boot mechanism, the five-step triage order with runnable
commands, the UTC-vs-CEST normalisation with the concrete 19:24:34Z / 20:28:31 CEST case, the
per-instance scoping caveat on `memory.events`, the attribution of all four Aug 26 kills to the two
named sections of this same file, and the pointer to the uptime workflow with its latency limit.
No `.planning/` identifier or D-number appears in the added prose.
  </done>
</task>

<task type="auto">
  <name>Task 2: Create .github/workflows/uptime-check.yml</name>
  <files>.github/workflows/uptime-check.yml</files>
  <precondition>`.github/workflows/uptime-check.yml` does not exist (confirmed absent at planning time). If it now exists, stop — this task creates a file and must not silently overwrite one.</precondition>
  <action>
Create the workflow. Structure and conventions follow `security-scan.yml` and `secret-scan.yml`:
a substantial header comment above `name:`, `workflow_dispatch: {}` in the `on:` block, a top-level
`permissions:` block, and a `timeout-minutes:` runaway guard on the job.

**Header comment.** Say what it guards and what it cannot do — not what the YAML below does. Cover:
the two public health endpoints it probes; that it exists because a stale console screenshot is not
evidence of an outage and this is the cheap standing answer to "is it actually down?" (name
`docs/INFRA_RUNBOOK.md`'s triage section as where to go once a run goes red — a path a reader can
open); and, honestly, four limits — (a) GitHub's cron is a floor, not a guarantee: scheduled runs
queue on shared runners and fire late, so a red run dates an outage only to within roughly the
interval plus that jitter; (b) GitHub disables scheduled workflows in a public repository after 60
days of repository inactivity, so this stops silently if the repo goes quiet; (c) it is a probe,
not an alerting system — nothing pages anyone; (d) upstream `show-details=never` means a failure
can name the endpoint and its status and can never name the failing subsystem.

**`name:`** `Uptime Check`.

**`on:`** `schedule` with `- cron: '*/15 * * * *'`, carrying an inline comment justifying 15 against
GitHub's documented 5-minute floor — the jitter argument from this plan's `<design_alternatives>`,
in one or two lines, not a restatement of the number. Plus `workflow_dispatch: {}`.

**`permissions: {}`** — the empty map, with a one-line comment on why this diverges from both
sibling workflows' `contents: read`: this job never checks out the repository (the same shape as
`deploy.yml`'s `health-check-nonprod` job, which states the same property), so it needs no scope at
all. Flag the divergence rather than letting it look like drift — that is this repo's own
established habit for sibling-file differences.

**Job `uptime-check`:** `runs-on: ubuntu-latest`, `timeout-minutes: 5` labelled as a runaway guard
against a hung curl rather than a runtime prediction (worst case is ~3 minutes: 2 endpoints × 3
attempts × 20s curl timeout + sleeps).

**Job `env:` block** — every value the script reads lives here, nothing is interpolated into the
script (see the `run:` constraint below):
- `HEALTH_URLS`: a folded scalar holding both URLs separated by whitespace —
  `https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health` and
  `https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health`. Comment that both
  are public and unauthenticated, so this file carries no secret and needs none.
- `ATTEMPTS: 3`, `SLEEP_SECONDS: 10`, `CURL_MAX_TIME: 20`. Comment the retry with the reason, at a
  level above the code: a single-shot probe from a shared runner turns a transient network blip
  into a red run, i.e. manufactures the exact false alarm this file exists to prevent.
- `UP_MARKER`: the literal `"status":"UP"` to match in the body.

**Single step, `Check both public health endpoints`, `run: |`** with a POSIX-sh script. Hard
constraint: **no `${{ }}` interpolation anywhere inside the `run:` body** — every value arrives
through `env:`. That is both the repo's documented injection-safety rule (see `security-scan.yml`'s
NVD_API_KEY step comment for the reasoning) and what lets Task 3 extract and execute this exact
script locally.

Script behavior:
- Iterate over `$HEALTH_URLS` with deliberate unquoted word-splitting as the list mechanism.
- Per URL, loop up to `$ATTEMPTS`. Capture status and body in **one** request:
  `curl -s --max-time "$CURL_MAX_TIME" -w '\n%{http_code}' "$URL"`, falling back to a value whose
  last line is `000` when curl itself exits non-zero, so an unresolvable host or a TLS failure is a
  reportable status rather than an unhandled error. Split the captured text: last line is the
  status, everything above it is the body.
- An endpoint passes only if the status is exactly `200` **and** the body contains `$UP_MARKER`.
  On pass, move to the next URL immediately. On failure, print an attempt line naming the URL and
  the observed status, sleep `$SLEEP_SECONDS`, retry — and skip the sleep after the final attempt.
- Accumulate failing URLs rather than exiting on the first. Checking the second endpoint must not
  depend on the first passing: a production outage masking a nonprod outage is the one failure this
  probe cannot be allowed to have.
- After the loop: if nothing failed, print a single line naming both endpoints as OK and exit 0.
  Otherwise emit one `::error::` annotation per failed endpoint — matching `deploy.yml`'s health
  poll convention — carrying the URL, the last observed status, and which condition failed, keeping
  "200 but the body did not carry the UP marker" textually distinct from a status failure. Then
  exit 1.
  </action>
  <verify>
    <automated>python3 -c "import yaml,sys; d=yaml.safe_load(open('.github/workflows/uptime-check.yml')); j=d['jobs']['uptime-check']; r=j['steps'][0]['run']; assert d[True]['schedule'][0]['cron']=='*/15 * * * *', 'cron'; assert 'workflow_dispatch' in d[True], 'dispatch'; assert d['permissions']=={}, 'permissions'; assert j['timeout-minutes']==5, 'timeout'; assert '\${{' not in r, 'interpolation in run body'; assert 'nonprod.duckdns.org/api/actuator/health' in j['env']['HEALTH_URLS'] and 'kanban-board-rud-vlad-473.duckdns.org/api/actuator/health' in j['env']['HEALTH_URLS'], 'urls'; print('workflow shape OK')"</automated>
  </verify>
  <done>
`.github/workflows/uptime-check.yml` parses as YAML; declares both `schedule` (`*/15 * * * *`) and
`workflow_dispatch`; carries `permissions: {}` with its divergence comment; sets
`timeout-minutes: 5`; holds both public health URLs in `env.HEALTH_URLS`; and its `run:` body
contains no `${{ }}` interpolation. No secret, no `NETCUP_*` reference, and no credential-shaped
literal appears anywhere in the file.
  </done>
</task>

<task type="auto">
  <name>Task 3: Prove the gate bites — five branches locally, then one live dispatch</name>
  <files>.planning/quick/260902-vjo-document-netcup-console-staleness-triage/260902-vjo-SUMMARY.md</files>
  <precondition>Task 2's file exists and parses (Task 2's verify passed). Network egress to `duckdns.org` and `example.com` is available from this machine.</precondition>
  <action>
A check nobody has seen fail is not a check. Extract the workflow's own script — do not retype it —
and drive it through every branch, recording the observed exit code and the emitted message for
each. Extraction (PyYAML is present; `yq` is not):

`python3 -c 'import yaml; print(yaml.safe_load(open(".github/workflows/uptime-check.yml"))["jobs"]["uptime-check"]["steps"][0]["run"])' > /tmp/uptime-probe.sh`

Then run `/tmp/uptime-probe.sh` under `sh`, supplying the same variables the job's `env:` block
supplies, once per branch. Use `ATTEMPTS=1` for the negative branches so the proof does not pay
three sleeps for a result already known after the first.

1. **Positive** — `HEALTH_URLS` set to both real endpoints, `ATTEMPTS=3`. Expect exit 0 and a line
   naming both as OK.
2. **Non-200 on a real, reachable host** — a path that exists on the live host but is not in the
   actuator exposure allowlist, e.g.
   `https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/does-not-exist`. Expect exit 1 with
   the URL and its observed status named. Record whatever status actually comes back (401/403/404
   are all plausible given the security chain) — the branch under test is "non-200 fails loudly",
   not any particular code.
3. **Unreachable host / curl failure** — a `.invalid` hostname such as
   `https://this-host-does-not-exist-kanban.invalid/api/actuator/health`. Expect exit 1 and a
   reported status of `000`, proving curl's own non-zero exit is converted into a reportable
   failure rather than crashing or being read as success.
4. **200 with the wrong body** — `https://example.com/`, a stable 200 whose body cannot contain the
   UP marker. Expect exit 1, and the message must be textually distinguishable from branch 2's:
   this is the branch that proves the probe checks the body at all rather than trusting the status
   code alone.
5. **Mixed** — the real production endpoint first, then `https://example.com/`. Expect exit 1 **and**
   log output showing the production endpoint was still evaluated and passed. This is the proof
   that one endpoint's failure does not mask or short-circuit the other.

Delete `/tmp/uptime-probe.sh` afterwards.

**Then, live.** `workflow_dispatch` is only offered for a workflow present on the repository's
default branch, so this half runs after the commit is on `main` at the remote. Once it is:
`gh workflow run uptime-check.yml --ref main`, resolve the id with
`gh run list --workflow uptime-check.yml --limit 1 --json databaseId --jq '.[0].databaseId'`, and
block on it with `gh run watch <id> --exit-status`. If `gh run watch` fails on authentication (no
PAT can carry the `checks:read` permission it needs), fall back to polling `gh run view <id> --json
status,conclusion` and say in the summary that the fallback was used.

If the workflow is not yet on the remote default branch when this task runs, do **not** claim the
live run happened: record it in the SUMMARY as explicitly pending, with the two commands above as
the follow-up.

Record all five local branches (command, exit code, key output line) and the live-run outcome in
the task summary. Do not claim any branch was checked without pasting what it printed.
  </action>
  <verify>
    <automated>python3 -c 'import yaml; print(yaml.safe_load(open(".github/workflows/uptime-check.yml"))["jobs"]["uptime-check"]["steps"][0]["run"])' > /tmp/uptime-probe.sh &amp;&amp; HEALTH_URLS="https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health" ATTEMPTS=3 SLEEP_SECONDS=10 CURL_MAX_TIME=20 UP_MARKER='"status":"UP"' sh /tmp/uptime-probe.sh &amp;&amp; echo "POSITIVE=pass" &amp;&amp; ! HEALTH_URLS="https://example.com/" ATTEMPTS=1 SLEEP_SECONDS=1 CURL_MAX_TIME=20 UP_MARKER='"status":"UP"' sh /tmp/uptime-probe.sh &amp;&amp; echo "BODY-BRANCH=fails-as-required" &amp;&amp; ! HEALTH_URLS="https://this-host-does-not-exist-kanban.invalid/api/actuator/health" ATTEMPTS=1 SLEEP_SECONDS=1 CURL_MAX_TIME=20 UP_MARKER='"status":"UP"' sh /tmp/uptime-probe.sh &amp;&amp; echo "UNREACHABLE-BRANCH=fails-as-required" &amp;&amp; rm -f /tmp/uptime-probe.sh</automated>
  </verify>
  <done>
All five local branches ran, with the extracted (not retyped) script: the positive branch exited 0
against both live endpoints, and branches 2–4 each exited 1 with a message naming the offending URL
and its observed status, the body-mismatch message distinguishable from the status-failure message.
Branch 5 exited 1 while still showing the healthy endpoint evaluated. The live `workflow_dispatch`
run is either green and recorded by run id, or recorded as explicitly pending with the reason. The
temp script is deleted.
  </done>
</task>

</tasks>

<verification>
- `git status --porcelain` shows exactly the two intended files changed, nothing else.
- `git diff --stat` confirms `src/**` is untouched — so `spotlessCheck`/`test` have no semantic
  bearing on this change, though `.githooks/pre-commit` still runs both (~4 min) plus its gitleaks
  scan on the commit. Let the hook run; do not bypass it.
- The pre-commit gitleaks scan passes with no new finding — neither file contains a secret, a
  credential-shaped literal, or a `NETCUP_*` reference.
- `.planning/` prose in this task's own directory likewise contains no credential-shaped literal
  (it names hostnames and public URLs only).
</verification>

<success_criteria>
- A reader hitting the same false alarm can, from `docs/INFRA_RUNBOOK.md` alone, date a console
  kernel line to wall-clock, normalise the two clocks, read the per-instance kill counter, and
  attribute the Aug 26 kills — without SSH-ing anywhere first and without asking anyone.
- `.github/workflows/uptime-check.yml` runs green on demand against the live endpoints, and has
  been observed to exit non-zero on a bad status, an unreachable host, and a 200 with the wrong
  body — each naming what failed.
- Exactly two files changed. No VPS change, no compose change, no postgres tuning change, no third
  monitoring mechanism.
</success_criteria>

<output>
Create `.planning/quick/260902-vjo-document-netcup-console-staleness-triage/260902-vjo-SUMMARY.md`
when done, carrying the five local branch results (command, exit code, key output line) and the
live `workflow_dispatch` outcome or its explicit pending status.
</output>
</content>
</invoke>
