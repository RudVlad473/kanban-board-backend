---
phase: quick
plan: 260911-gkz
type: execute
wave: 1
depends_on: []
files_modified:
  - .planning/quick/260911-gkz-debug-why-several-grafana-postgres-inter/FINDINGS.md
  - docker/grafana/provisioning/dashboards/json/postgres-exporter.json
  - docker-compose.prod.yml
autonomous: false
requirements: [QUICK-260911-GKZ]
user_setup: []

estimate:
  tokens: 95000
  raw_tokens: 95000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "Each of the 7 reported tiles (PostgreSQL Uptime, Max Connections, Query rate, Average query runtime, Shared Buffers, Locks by state, Deadlocks by database) has a named root cause backed by a live observation, not an inference."
    - "The root cause for each tile identifies WHICH layer breaks the chain: Postgres itself, the exporter's collector set, Prometheus ingestion, or the dashboard's PromQL label matching."
    - "The already-known asymmetry — pg_settings_server_version_num and the unfiltered pg_settings_max_connections render, while the instance-filtered pg_settings_max_connections and pg_settings_shared_buffers_bytes do not — is explained by observed label sets rather than assumed."
    - "No Grafana dashboard, datasource, alert or user is modified through the live UI."
    - "The Grafana viewer credentials never appear in any committed file, command echo, screenshot, log or SUMMARY."
    - "Every fix lands as a repository change, verified locally, not as a production hotfix."
  artifacts:
    - ".planning/quick/260911-gkz-debug-why-several-grafana-postgres-inter/FINDINGS.md"
    - ".planning/quick/260911-gkz-debug-why-several-grafana-postgres-inter/COVERAGE.md"
  key_links:
    - "postgres-exporter command flags in docker-compose.prod.yml -> which collectors emit metrics at all"
    - "exporter /metrics label set -> the instance matcher in each dashboard panel"
    - "Database template variable (multi=true, includeAll=true) -> panels matching datname with an exact-match operator"
    - "any NEW mounted config file -> deploy.yml SCP source list, gated by scripts/verify-deploy-scp-coverage.py"
---

<objective>
Root-cause, with live evidence, why seven tiles on the production "Postgres Internals" Grafana
dashboard show N/A or render empty, then land the fix as a repository change.

Purpose: the observability stack shipped in Phase 12 is only worth its resource budget if the
panels actually answer questions. Seven dead tiles on the flagship Postgres dashboard means the
stack is silently under-delivering, and the dead set is not random — it splits cleanly along lines
that point at three different mechanisms.

Output: a FINDINGS.md carrying a per-tile root cause with the probe output that proves it, a
user-approved fix scope, and the repo changes implementing it with local (not production)
verification.
</objective>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@docker/grafana/provisioning/dashboards/json/postgres-exporter.json
@docker/prometheus/prometheus.yml
</context>

## Mechanism (core data flow, 3 sentences)

Postgres exposes its internals through catalog views (`pg_settings`, `pg_stat_database`,
`pg_locks`, `pg_stat_statements`), which `postgres-exporter` reads via a *collector* per view and
renders as Prometheus metrics on `:9187/metrics`. Prometheus scrapes that single target every 15s
under `job=postgres`, stamping every series it receives with `instance="postgres-exporter:9187"`.
Grafana panels then select from those series with PromQL matchers built from the dashboard's
`$Instance` and `$Database` template variables — so a tile can die at any one of four points: the
view is absent, the collector is off, the scrape failed, or the matcher does not match.

## Approaches considered

Three ways to find the break in that chain were weighed before picking one.

| Approach | Pros / Cons | Why picked / rejected |
|---|---|---|
| **A. Read the repo config and reason to a conclusion** (compose flags + dashboard JSON + upstream README, no live access) | + Zero cost, zero credential exposure, no production contact. − Cannot distinguish "metric absent" from "metric present under a different label set", which is precisely the discriminator the evidence already demands: the unfiltered server-version tile renders while the instance-filtered max-connections tile does not, and static files cannot tell you which case that is. | **Rejected as the whole answer, kept as the starting hypotheses.** Static reading already produced four named, falsifiable candidates (below); it cannot rank or confirm them. |
| **B. SSH to the VPS and read the exporter's raw metrics endpoint + psql the catalogs** | + Ground truth at the source: exactly which series exist, with exactly which labels, plus an extension listing. − Touches production directly, needs the netcup SSH key, and skips the Grafana-side half of the chain (variable interpolation, panel matchers) where at least one suspected bug lives. | **Adopted as one half.** Read-only commands only — a metrics fetch, catalog selects, an extension listing; nothing mutating. |
| **C. Headless browser against live Grafana as a Viewer, driving Explore / the datasource query API** | + Observes the chain exactly as the dashboard does, including template-variable interpolation, which is where the label-match hypotheses live; Grafana is the only publicly reachable surface (Caddy proxies grafana:3000 only — Prometheus is not exposed). − Viewer role may not have Explore; credential handling risk; cannot see what the exporter *would* emit if a collector were enabled. | **Adopted as the other half**, with the datasource-proxy query API as fallback for when Viewer lacks Explore (a Viewer can always query a datasource — that is how dashboards render at all). |

**B and C together, not either alone.** The chain has four links and each method only sees two of
them; the decisive probes below deliberately compare what B sees at the exporter against what C
sees at the panel.

### Non-obvious trade-offs the fix must weigh (carry these into the Task 2 checkpoint)

- **The statements extension is not free to enable.** It requires a `shared_preload_libraries`
  entry, which is **not** settable at runtime — it needs a **restart of the production Postgres
  container that serves both `kanban_prod` and `kanban_nonprod`**. That is a production outage
  window bought for two tiles.
- **Enabling the statements collector publishes query text.** Those series carry query identifiers
  and, depending on configuration, normalized SQL text. Grafana's hostname is public with Grafana
  login as the *only* gate, so every Grafana Viewer would gain read access to the shape of every
  SQL statement the application runs. The collector's own query-length and limit options bound
  this; leaving it off entirely also bounds it.
- **Exporter memory is measured, not guessed.** `docker-compose.prod.yml` records a *measured*
  `mem_limit` for `postgres-exporter`, descended down a ladder under real load. Every collector
  added widens the scrape's working set, so the recorded measurement becomes stale and the ladder
  note must be updated or explicitly re-flagged as provisional — silently invalidating a measured
  limit is how an OOM ships.
- **A new mounted file is a deploy trap this repo has already been bitten by.** The exporter
  currently mounts nothing. If the fix introduces a custom queries file, it must be added to
  `deploy.yml`'s SCP source list or it never reaches the VPS — the exact gap quick task 260908-sj9
  closed, now gated by `scripts/verify-deploy-scp-coverage.py`.
- **The auto-discover-databases flag is deprecated in v0.20.1.** Anything built on its labelling
  behaviour is building on a flag upstream has marked for removal.

### Named candidates going in (hypotheses to confirm or kill, NOT conclusions)

Established by reading the repo; each is falsifiable by a probe in Task 1.

1. **Uptime** — the postmaster start-time metric comes from the `postmaster` collector, which
   upstream documents as **disabled by default** in v0.20.1. The service passes only the
   auto-discover flag, so no collector flag enables it.
2. **Query rate / Average query runtime** — the statements metrics need BOTH the `stat_statements`
   collector (also **disabled by default**) AND the extension. No `shared_preload_libraries`
   setting exists anywhere in the repo's Postgres configuration.
3. **Max Connections / Shared Buffers** — the `settings` collector is **enabled by default**, and
   two tiles that read settings metrics *without* an instance matcher both render live (the
   server-version tile and the connections-used gauge, whose denominator is the very max-connections
   metric the dead tile selects). So these series plausibly EXIST and the instance-filtered panels
   still miss them — pointing at a label-set or variable-interpolation problem, not a collection
   problem. This is the one candidate static reading cannot settle, and it is the highest-value
   probe in Task 1.
4. **Locks by state / Deadlocks by database (per-database row)** — the `Database` template variable
   is `multi: true, includeAll: true`, while those panels match `datname` with an **exact-match**
   operator. A multi-valued interpolation cannot be matched exactly, so those panels select nothing
   whenever more than one database is selected. The same defect pattern appears across most panels
   in that row.

</context>

<tasks>

<task type="tracer">
  <name>Task 1: Trace one dead tile through all four layers, then apply the same probe to the rest</name>
  <files>.planning/quick/260911-gkz-debug-why-several-grafana-postgres-inter/FINDINGS.md</files>
  <precondition>The Grafana viewer credentials and the monitoring hostname are available from the user's session message, and `ssh netcup-prod` resolves (documented in docs/INFRA_RUNBOOK.md). If neither surface is reachable, halt and report which one failed rather than substituting inference.</precondition>
  <read_first>
    docs/INFRA_RUNBOOK.md sections "Monitoring role and metrics targets" (around line 3576) and
    "postgres_exporter credential handling" (around line 3737) — these record the monitoring role's
    grants and the deliberate three-variable credential split. Read before touching anything live.
  </read_first>
  <action>
    Establish ground truth per layer for ONE tile first — Max Connections — because it is the tile
    whose static evidence is self-contradictory and therefore the one that teaches the method.
    Then run the identical four-layer probe across the remaining six tiles.

    THE FOUR LAYERS, probed in this order (cheapest and least invasive first):

    Layer 4, Grafana panel. Drive a headless browser (Playwright MCP, or the Playwright CLI against
    the project's own setup) to the monitoring hostname. Authenticate with the viewer credentials
    the user provided in the session — never type, echo, log or screenshot the literal value, and
    never write it into any file. Open the Postgres Internals dashboard. For the Max Connections
    panel use Inspect to read the interpolated query and the returned frame: the single decisive
    question is what the Instance and Database variables actually interpolate to at render time,
    versus what the JSON's matcher expects. Capture the interpolated query text and the row count.

    Layer 3, Prometheus through Grafana. Prometheus is NOT publicly proxied — Caddy fronts only
    grafana:3000 — so query it through Grafana's datasource proxy. Prefer Explore; if the Viewer
    role has no Explore access, POST to Grafana's datasource query API instead, which a Viewer can
    always reach because dashboards depend on it. Run the decisive pair in one session and diff the
    returned label sets: the bare max-connections settings metric against the same selector with the
    instance matcher the panel uses. Record the FULL label set of whatever the bare query returns.
    That diff alone settles candidate 3 — it distinguishes "series absent" from "series present
    under labels the panel does not match". Repeat for the bare forms of the postmaster start-time,
    shared-buffers, locks-count and statements metrics.

    Layer 2, the exporter. Over ssh netcup-prod, read the exporter's own metrics endpoint from
    inside the container network with curl, and search for each of the five metric families above.
    This distinguishes "the exporter never emitted it" from "Prometheus did not ingest it". Also
    capture the container's actual argv to confirm which collector flags are in force. Read-only
    commands only.

    Layer 1, Postgres. Over the same ssh session, connect as the monitoring role and confirm: the
    installed extension list (to settle whether the statements extension exists at all), the
    shared_preload_libraries setting, and that selecting from the settings and locks catalog views
    as that role succeeds rather than raising a permission error. The runbook records that this role
    holds pg_monitor, which would make a permissions root cause unlikely — confirm or kill that, do
    not assume it.

    Then expand: run the same four-layer probe for PostgreSQL Uptime, Query rate, Average query
    runtime, Shared Buffers, Locks by state and Deadlocks by database. Note that Locks by state and
    Deadlocks by database each appear TWICE on this dashboard — once in the top row with an instance
    matcher and once in the per-database row with a datname matcher — so determine which occurrence
    the user saw empty before assigning a cause.

    Observe only. Do not save, edit, duplicate or star any dashboard; do not touch datasource,
    alert, user or org settings. The Viewer role should refuse these anyway; the instruction is the
    control, the role is the backstop.

    Write FINDINGS.md with one section per tile. Each section states the tile name, the exact PromQL
    the panel runs, the layer at which the chain breaks, the root cause, and a line beginning with
    the evidence marker carrying the literal probe output and the command that produced it. Where a
    named candidate from the plan's context section is killed by the evidence, say so explicitly —
    a disproved hypothesis is a finding. Close with a proposed fix per tile and the cost of each,
    drawing on the non-obvious trade-offs recorded above.
  </action>
  <verify>
    <automated>test -s .planning/quick/260911-gkz-debug-why-several-grafana-postgres-inter/FINDINGS.md && test "$(grep -v '^#' .planning/quick/260911-gkz-debug-why-several-grafana-postgres-inter/FINDINGS.md | grep -c 'EVIDENCE:')" -ge 7</automated>
    <automated>! grep -rniE 'passw|GF_SECURITY|bearer [A-Za-z0-9]' .planning/quick/260911-gkz-debug-why-several-grafana-postgres-inter/FINDINGS.md</automated>
  </verify>
  <done>
    All seven tiles have a root cause naming the breaking layer, each backed by a literal probe
    output. The bare-versus-instance-matched label-set diff for the settings metrics is recorded
    verbatim. No Grafana object was modified. No credential appears in any written file.
  </done>
</task>

<task type="checkpoint:decision">
  <name>Task 2: Approve fix scope before any file changes</name>

  <decision>
    Which of the root causes found in Task 1 should be fixed now, and which should be recorded and
    left alone? Scope for Task 3 is authorized ONLY by what Task 1 actually observed — nothing in
    this plan pre-authorizes a change to the exporter's flags, the dashboard JSON, the Postgres
    configuration or the monitoring role's grants.
  </decision>

  <options>
    <option name="A — dashboard-only fixes">
      Correct the PromQL matchers in the committed dashboard JSON for whichever tiles Task 1
      attributes to a matcher or variable-interpolation defect. Consequence: zero operational cost,
      no production restart, no new data exported, reverted by reverting one commit. Cost is bounded
      to the panels changed, and the panel-invariance diff in Task 3 proves nothing else moved.
      This is the recommended baseline — take it unless Task 1 kills the matcher hypothesis.
    </option>
    <option name="B — enable additional exporter collectors (no extension needed)">
      Add collector flags to the postgres-exporter command for the families Task 1 shows the
      exporter is simply not emitting, where the underlying catalog view already exists.
      Consequence: requires a redeploy, widens the scrape's working set, and therefore invalidates
      the recorded measured mem_limit for that container — the ladder note must be updated in the
      same commit or an OOM is being scheduled. No production database restart.
    </option>
    <option name="C — enable the statements collector and preload the extension">
      Everything in B, plus a `shared_preload_libraries` change. Consequence: a **restart of the
      production Postgres container serving BOTH kanban_prod and kanban_nonprod** — a real outage
      window — and it publishes query identifiers and normalized SQL text to every Grafana Viewer
      on a publicly-reachable hostname. Buys exactly two tiles (Query rate, Average query runtime).
      Bounded, if taken, by the collector's query-length and limit options.
    </option>
    <option name="D — widen the monitoring role's grants">
      Admissible ONLY if Task 1 captured a literal permission-denied error as that role. The runbook
      records the role as already holding pg_monitor, which covers the settings, locks and
      statistics catalog views, so this option is expected to be unnecessary. Consequence: widens the
      blast radius of an exporter compromise. Superuser and blanket grants are out of scope; a
      narrow read-only grant is the only admissible form.
    </option>
    <option name="E — record and leave broken">
      Document the root cause in FINDINGS.md and fix nothing for that tile. Consequence: the tile
      stays N/A, but the reason is now written down instead of rediscovered. A legitimate outcome
      for any tile whose fix costs more than the tile is worth — most obviously C.
    </option>
  </options>

  <action>
    Present the FINDINGS.md root causes, then stop and ask. Put each applicable option in front of
    the user with its consequence attached, not just its name, restricted to the options Task 1's
    evidence actually makes live. Name a recommendation and say what happens if the user says
    nothing.

    Ask in the message body as a short numbered list in plain text. Do not use a multi-choice
    question tool — this box's terminal handles that badly enough that the call has to be
    interrupted.

    Options C and D are never auto-approvable, regardless of any auto-advance setting: both change
    production state beyond provisioned config (a database restart, a grant), and C additionally
    exports new data across a public trust boundary. Option B is not auto-approvable either while it
    invalidates a measured memory limit. Record the user's selection, and every rejection with its
    reason, into FINDINGS.md before resuming.
  </action>

  <verify>
    <human-check>User has selected which fixes to implement, and the selection — including each rejected option and its reason — is recorded in FINDINGS.md.</human-check>
  </verify>

  <resume-signal>
    The user has replied with their selection and it is written into FINDINGS.md. Resume at Task 3,
    implementing only the selected options.
  </resume-signal>

  <done>An approved fix list exists, written into FINDINGS.md, with any rejected fix recorded alongside the reason.</done>
</task>

<task type="auto">
  <name>Task 3: Implement the approved fixes in-repo and verify locally, never in production</name>
  <files>docker/grafana/provisioning/dashboards/json/postgres-exporter.json, docker-compose.prod.yml, docker-compose.nonprod.yml</files>
  <reversibility rating="reversible">Dashboard JSON and compose flags are provisioned config — reverting the commit and redeploying fully restores prior behaviour. Preloading an extension into the production database would be `costly` (restart, migration-shaped), which is why it sits behind the Task 2 checkpoint rather than here.</reversibility>
  <action>
    Implement only what Task 2 approved. Every change is a repository commit; nothing is applied
    through the Grafana UI or by hand on the VPS.

    Where the fix is dashboard-side, correct the matchers in the dashboard JSON. Note that the
    per-database row's exact-match datname matchers are the suspected defect, so the correction is
    an operator change to a regex matcher, applied consistently across the panels sharing the
    defect rather than only to the two tiles the user happened to notice — a population fix, not a
    site fix. State the population and the command that counted it.

    Where the fix is exporter-side, add the collector flag to the postgres-exporter command in
    docker-compose.prod.yml, keeping the existing three-variable credential split untouched. If the
    added collector widens the scrape's working set, update the measured mem_limit note in that
    service block to record that the measurement predates the new collector — do not leave a
    measured claim standing over a changed workload. Mirror into docker-compose.nonprod.yml only if
    that file defines its own exporter.

    If any fix introduces a NEW mounted file, add its path to the SCP source list in
    .github/workflows/deploy.yml in the same commit — the coverage gate below will fail otherwise,
    and that failure is the point.

    Verify locally rather than by deploying. Bring up the local Postgres from docker-compose.yml
    per the project's documented local-dev procedure, run the pinned exporter image against it with
    the proposed flags, and fetch its metrics endpoint to confirm the previously-absent metric
    families now appear. For dashboard-side changes, follow the precedent script at
    .planning/quick/260908-r16-restructure-the-postgres-internals-grafa/verify-dashboards.sh:
    validate the JSON, diff panel invariance against the base commit so unintended panels are not
    collaterally changed, and provision the file into a real Grafana container so a structurally
    valid but unrenderable dashboard cannot pass. Tear down every container afterwards and confirm
    with a container listing — this repo has a recorded history of orphaned test containers.

    Record in the SUMMARY which tiles are fixed and verified locally, which are fixed but can only
    be confirmed after the next deploy, and which were deliberately left broken with the reason.
    A tile left broken by an explicit decision is a result, not a gap.
  </action>
  <verify>
    <automated>python3 -m json.tool docker/grafana/provisioning/dashboards/json/postgres-exporter.json > /dev/null</automated>
    <automated>python3 scripts/verify-deploy-scp-coverage.py</automated>
    <automated>python3 scripts/verify-deploy-scp-coverage-selftest.py</automated>
    <automated>python3 scripts/verify-compose-ports.py</automated>
  </verify>
  <done>
    Every approved fix is committed as a repo change. Exporter-side changes are proven by a local
    metrics-endpoint capture showing the metric family present where it was previously absent.
    Dashboard-side changes pass JSON validation, panel-invariance diff, and real-Grafana
    provisioning. The deploy SCP coverage gate passes. No production system was modified.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| agent session → public Grafana | The viewer credential crosses into a headless browser and, if the datasource query API is used, into HTTP request bodies. Anything captured here can land in a transcript or a file. |
| agent session → production VPS (ssh) | Read-only catalog and metrics probes run against the live host serving both `kanban_prod` and `kanban_nonprod`. |
| postgres-exporter → Postgres | The `monitoring` role's grants bound what the exporter can read; any fix that widens them widens the blast radius of an exporter compromise. |
| Postgres internals → public Grafana | Whatever a collector exports becomes readable by every Grafana Viewer, because Grafana login is the only gate on that public hostname. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-gkz-01 | Information disclosure | Grafana viewer credential in agent session | high | mitigate | Credential referenced only as "the Grafana viewer credentials the user provided in the session" in every plan artifact, FINDINGS.md and SUMMARY. Never echoed to a shell, never written to a file, never captured in a screenshot of a filled login form. Task 1 carries a negative-grep gate over FINDINGS.md; the pre-commit gitleaks scan and the `secret-scan.yml` full-history workflow are the backstops, not the primary control. |
| T-gkz-02 | Information disclosure | statements collector on a publicly-reachable Grafana | medium | mitigate | Enabling it exports query identifiers and normalized SQL text to every Grafana Viewer. Not enabled by this plan; surfaced as an explicit consequence at the Task 2 decision checkpoint. If approved, bound with the collector's query-length and limit options rather than accepted wholesale. |
| T-gkz-03 | Elevation of privilege | `monitoring` database role grants | medium | mitigate | The runbook records this role as holding `pg_monitor`, which already covers the settings, locks and statistics catalog views. Any proposed `GRANT` must cite a live permission-denied error captured in Task 1 — an assumed permission gap is not sufficient justification. Superuser and blanket grants are out of scope; a narrow read-only grant is the only admissible form. |
| T-gkz-04 | Tampering | Live Grafana dashboards / datasources / alerts | medium | mitigate | Investigation is observe-only by instruction; the Viewer role's inability to save is the backstop. All fixes land as repository commits to provisioned config, so a live UI edit would be silently overwritten on next provisioning anyway. |
| T-gkz-05 | Denial of service | postgres-exporter container memory limit | medium | mitigate | The existing `mem_limit` is a measured value descended down a ladder under real load. Adding collectors invalidates that measurement; Task 3 requires updating the note rather than leaving a stale measured claim over a changed workload. |
| T-gkz-06 | Tampering | Config file reaching the VPS | low | mitigate | A newly mounted file that is not in `deploy.yml`'s SCP source list silently never deploys. `scripts/verify-deploy-scp-coverage.py` runs as a Task 3 gate and in CI. |
| T-gkz-SC | Tampering | Package-manager installs | low | accept | No npm/pip/cargo installs are in scope. The only pinned third-party artifact touched is the already-pinned `prometheuscommunity/postgres-exporter:v0.20.1` image, whose tag is unchanged by this plan. No package legitimacy audit is required. |
</threat_model>

<verification>
- FINDINGS.md exists with a root cause per tile, each carrying literal probe output.
- No credential appears in any file under the quick task directory or in git history.
- No live Grafana object was modified.
- Dashboard JSON remains valid, panel-invariant except where intentionally changed, and provisions
  into a real Grafana.
- Exporter-side changes are proven against a locally-run exporter, not a production deploy.
- `python3 scripts/verify-deploy-scp-coverage.py` passes.
- Every test container started during verification is torn down, confirmed by a container listing.
</verification>

<success_criteria>
All seven reported tiles have an evidence-backed root cause. Fixes for the approved subset are
committed as repository changes and verified locally. Tiles deliberately left unfixed are recorded
with the decision and its reason. Production is unmodified by this plan.
</success_criteria>

<output>
Create `.planning/quick/260911-gkz-debug-why-several-grafana-postgres-inter/260911-gkz-SUMMARY.md` when done
</output>
