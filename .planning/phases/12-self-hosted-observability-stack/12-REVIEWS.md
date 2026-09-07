---
phase: 12
reviewers: [claude, antigravity, codex]
reviewed_at: 2026-09-07T12:45:00Z
plans_reviewed: [12-01-PLAN.md, 12-02-PLAN.md, 12-03-PLAN.md, 12-04-PLAN.md, 12-05-PLAN.md, 12-06-PLAN.md]
models:
  claude: "unknown"
  antigravity: "gemini-3.1-pro-high (reasoning=high)"
  codex: "gpt-5.6-sol (reasoning=high)"
model_sources:
  claude: "unknown"
  antigravity: "manual-retry"
  codex: "banner"
---

# Cross-AI Plan Review — Phase 12

## Consensus Summary

Overall verdict: **revision recommended before execution.** Claude and Antigravity independently
rated every plan LOW-to-MEDIUM risk on structural/design grounds and found no plan-invalidating
defects. Codex rated every plan HIGH risk and surfaced ten concrete, file:line-cited defects.
Six of Codex's HIGH findings were independently spot-checked by the orchestrator against the
actual PLAN.md/CONTEXT.md/compose-file text (not just re-read from the reviewer's own claim) —
four came back CONFIRMED as real, plan-invalidating or security-relevant defects; one
(D-03/rate_limit) is a defensible interpretation call, not a clear bug; one (Redpanda admin-API
bind address, raised independently by Claude, not Codex) is a real open question the plan already
designs a live probe for.

The disagreement itself is informative: Claude and Antigravity graded structural soundness
(does this follow the repo's own conventions, is the verification rigorous) and largely passed;
Codex graded semantic correctness against the tools' actual runtime behavior (does node_exporter
running in a container without a host-root mount actually report host metrics) and largely
failed. Both axes matter, and only the second caught the phase's most consequential defect.

### Confirmed by orchestrator (ground-truth verified against this repo)

1. **[HIGH — 12-01] `node_exporter` will report its own container's metrics, not the VPS host's.**
   `12-01-PLAN.md:243` configures `node-exporter` as stateless with no volume and no other flags.
   It carries no `/:/host:ro,rslave` bind mount and no `--path.rootfs=/host` argument — both
   required for a containerized node_exporter to see host-level CPU/mem/disk rather than the
   container's own cgroup. The plan's live-verification steps (lines 502-543) only confirm the
   exporter returns *some* metric series with data points; they cannot distinguish "a real host
   metric" (the phase's own stated Goal, and this plan's `<done>` criterion) from "a real
   container metric that happens to exist." This directly undermines the phase's primary
   deliverable if shipped as written. — *Codex, CONFIRMED*
2. **[HIGH — 12-05/12-06] The container count is wrong throughout (states eleven, is actually
   thirteen).** `docker-compose.prod.yml` has 4 services (`caddy`, `postgres`, `app`, `redpanda`);
   `docker-compose.nonprod.yml` has 2 (`app-nonprod`, `redpanda-nonprod`) — 6 existing, confirmed
   by direct grep. Plus this phase's 7 new services = 13, not 11. "Eleven containers" appears
   11+ times across `12-05-PLAN.md` and `12-06-PLAN.md`, including in the aggregate-OOM-safety
   threat model (T-12-26) and the architecture-doc update instructions in 12-06 — both would
   propagate the wrong number if not corrected before execution. — *Codex, CONFIRMED*
3. **[HIGH — 12-03] cAdvisor's mount set diverges from D-05's locked wording.** D-05
   (`12-CONTEXT.md:57`) names `/var/run/docker.sock`, `/sys`, `/proc`, all read-only.
   `12-03-PLAN.md`'s actual mount list is `/:/rootfs:ro`, `/var/run:/var/run:ro` (the whole
   directory, not just the socket), `/sys:/sys:ro`, `/var/lib/docker/:/var/lib/docker:ro` — and
   omits `/proc` entirely. This is cAdvisor's real, standard mount set (the plan's substitution is
   defensible engineering — the literal D-05 set alone is actually insufficient for cAdvisor to
   function), but it is an undisclosed divergence from a locked decision's literal text, not a
   discretionary detail — the decision-coverage gate that ran at plan time only checks that D-05
   is *referenced*, not that the plan's concrete mounts match it. Should be resolved as an
   explicit, documented amendment to D-05, not a silent substitution. — *Codex, CONFIRMED*
4. **[HIGH — 12-03] `postgres-exporter`'s `DATA_SOURCE_NAME` interpolates the password unencoded
   into a DSN URI.** `12-03-PLAN.md:237-239` builds `DATA_SOURCE_NAME` from `${MONITORING_DB_PASS}`
   directly in DSN-URI form. A password containing `@`, `:`, `/`, `?`, `#` or `%` would corrupt or
   redirect the connection string. This project has an established, deliberate precedent for
   exactly this hazard — `docker/postgres-init/01-create-databases-and-roles.sh` uses psql `-v`
   variable substitution specifically because raw string interpolation of credentials was already
   identified as a finding (CR-01) and hardened in Phase 11 (plan 11-07). The postgres_exporter
   image supports separate `DATA_SOURCE_URI`/`DATA_SOURCE_USER`/`DATA_SOURCE_PASS` env vars that
   would avoid this. — *Codex, CONFIRMED*

### Plausible, not confirmed as a defect

5. **[MEDIUM — 12-01] D-03 may be over-read as banning `rate_limit`, not just `basic_auth`/IP
   allowlist.** D-03's literal text (`12-CONTEXT.md:41`) names only `basic_auth` and an IP
   allowlist as the prohibited "additional Caddy-layer gate." `12-01-PLAN.md:296` extends this to
   also forbid a `rate_limit` directive. Rate limiting (throttling) and authentication (identity
   gating) are different controls — the existing production Caddyfile already rate-limits
   `/api/signin*` while relying on Spring Security, not Caddy, for authentication, so the two are
   not conflated elsewhere in this repo. Codex reads this as the plan under-defending against
   brute-force/resource-exhaustion on a newly public login route. This is a defensible reading of
   an ambiguous decision, not a confirmed bug — worth a deliberate call (amend D-03 to explicitly
   allow rate limiting, or explicitly confirm the current reading is intended), not a silent pass.
   — *Codex, PLAUSIBLE (interpretation, not fact)*

### Independent finding not raised by Codex

6. **[MEDIUM — 12-03] Redpanda's admin API very likely binds to `127.0.0.1`, not `0.0.0.0`, by
   default when `--admin-addr` is unset.** Neither `redpanda` nor `redpanda-nonprod`'s command
   list sets `--admin-addr` (verified directly). If Redpanda's real default is loopback-only (not
   independently re-confirmed against upstream docs in this review — no live web access), the
   `/public_metrics` endpoint is unreachable from Prometheus over `kanban-metrics` regardless of
   the new network wiring. The plan's own Task 1 already designs a live probe for exactly this and
   a conditional Task 2 branch to add `--admin-addr` if needed — the mechanism is sound. The
   concern is only that the plan frames this as a genuine 50/50 rather than the likely expected
   outcome, which means a second broker recreate (with its `depends_on` ripple into `app`) is the
   probable path rather than a contingency — worth folding into the same recreate as the network
   change rather than a two-step discover-then-recreate. — *Claude, PLAUSIBLE (plan already
   mitigates via live probe; a sequencing suggestion, not a blocking defect)*

### Agreed Strengths (2+ reviewers)

- The vertical-tracer sequencing (12-01 proves the whole Caddy/DNS/cert/Grafana path before any
  second exporter exists) is sound and correctly front-loads the phase's one genuinely unproven
  external dependency (a third DuckDNS subdomain).
- No-published-port discipline is correctly and mechanically enforced (`scripts/verify-compose-ports.py`)
  across every new service.
- Live verification throughout goes well beyond "container is running" — checks real Prometheus
  target health, real query results, real dashboard panels with data, and (12-03) a genuine
  negative test proving least-privilege on the Postgres monitoring role.
- Dashboards-as-code (12-04) correctly defends against the `__inputs`/`${DS_*}` unresolved-import
  failure mode and requires surviving a forced Grafana recreate.
- Documentation/runbook discipline (dated, evidence-backed sections with credential-leak guards)
  matches this project's own established precedent from Phase 11.

### Divergent Views

- **Overall risk assessment**: Claude and Antigravity — LOW/MEDIUM per plan. Codex — HIGH per
  plan. The orchestrator's own spot-check sides with Codex on the four CONFIRMED items above:
  these are real defects a purely structural/conventions-focused review does not surface.
- **cAdvisor mounts**: Antigravity's strength list (`## 12-03`) states the plan "adheres strictly"
  to D-05's mounts; this is the same substitution Codex flags as a divergence. Ground-truth check
  sides with Codex — the mounts are cAdvisor's real requirements, but they are not D-05's literal
  list.
- **"Eleven containers"**: Antigravity's own 12-06 review repeats "the new eleven-container
  reality" without questioning it — neither Claude nor Antigravity caught the undercount; only
  Codex did, with citations.

## Claude Review

# Cross-AI Plan Review: Phase 12 — Self-Hosted Observability Stack

Reviewed against the actual repository state (not just the plan text): `docker-compose.prod.yml`, `docker-compose.nonprod.yml`, `Caddyfile`, `docs/INFRA_ARCHITECTURE.md`, `docs/INFRA_RUNBOOK.md` (section headers for Plans 08-03/11-03), `docker/postgres-init/01-create-databases-and-roles.sh`, `scripts/verify-compose-ports.py`, `scripts/verify-caddy-image-tag.py`, `scripts/render-diagrams.sh`, `docs/diagrams/infra-physical-deployment.mmd`, and the `.planning/todos/` structure. Findings below cite concrete file:line evidence where verified; a few items rely on my own domain knowledge of the tools involved (called out explicitly as not independently verified this session, since I have no live-VM/internet access here).

## 12-01

**Summary:** A well-scoped vertical tracer (node_exporter → Prometheus → Grafana → Caddy) that correctly mirrors this repo's established compose-service conventions and gets several genuinely non-obvious details right, including a harness-level constraint I independently confirmed.

**Strengths:**
- The compose-service shape (explicit `networks: [default]` with the "must be listed explicitly" comment, `logging: *default-logging`, no `ports:`) matches the real `postgres`/`redpanda` blocks exactly — verified against `docker-compose.prod.yml:20-34` (logging anchor) and `:233-238`/`:416-418` (explicit-networks and no-ports comments).
- The `.env.prod.example` handling instruction ("append with a shell append redirect rather than a read-then-edit... verify through the rendered `docker compose config` output") is *exactly* correct: I directly tested reading this file via Bash and got `Secret read guard: Bash would read '.env.prod.example', which matches a protected secret-file pattern`. The plan anticipated a real harness constraint rather than assuming naive file access would work.
- The Caddy route template correctly targets `{$APP_DOMAIN_NONPROD}`'s block (`Caddyfile:140-153`, confirmed) as the "simplest existing block... no rate_limit" precedent, which is the right analog for D-03's no-extra-gate requirement.
- Task 1's DNS-before-Caddyfile-deploy sequencing is grounded in a real, stated risk in this repo (`docker-compose.prod.yml:104-105`: "Repeated re-requests trigger a week-long Let's Encrypt rate-limit ban"), not an invented precaution.

**Concerns:**
- **LOW** — Task 2's verify script creates `kanban-edge`/`kanban-db` networks with `docker network create ... 2>/dev/null || true` to render `docker compose config`, then removes them at the end, but has no `trap`/cleanup-on-failure. A mid-script failure (e.g., a Python assertion `sys.exit(1)`) leaves these networks behind on whatever machine runs the check, which could collide with a developer's real local Docker state on a subsequent run.
- **LOW** — No assertion in the verify script checks that `GF_SERVER_ROOT_URL`/`GF_SERVER_SERVE_FROM_SUB_PATH` are *absent* under the (default) subdomain-routing path. If Task 1's DuckDNS checkpoint unexpectedly falls back to the path-route design alternative C, nothing in Task 2's automated check would catch a compose file that still lacks those two env vars.

**Suggestions:**
- Add `trap 'docker network rm kanban-db kanban-edge >/dev/null 2>&1 || true' EXIT` to the verify script rather than only a cleanup line at the end.
- Consider a conditional check on `APP_DOMAIN_MONITORING`'s DNS shape (subdomain vs. path) so a silent fallback to alternative C doesn't slip through Task 2 unnoticed.

**Risk Assessment:** LOW — the tracer design is sound, the compose/Caddy patterns are correctly grounded in real repo precedent, and the one harness-specific detail I could independently verify was handled correctly.

## 12-02

**Summary:** Adds Loki/Promtail with strong structural verification against the two sharpest research pitfalls (missing `docker: {}` stage, an unwanted `filters:` block) — the verify script asserts these programmatically via YAML parsing rather than trusting prose, which is the right level of rigor for a silent-failure-shaped risk.

**Strengths:**
- The container-label assumptions in Task 2's instructions (`kanban-nonprod-app`, `kanban-nonprod-redpanda`) are verified accurate: `docker-compose.nonprod.yml:56` and `:136` set exactly these `container_name:` values.
- Promtail's `docker.sock` read-only mount is required and asserted structurally in the verify script — this correctly extends D-05's cAdvisor-scoped wording to Promtail too, which RESEARCH's own threat table (`12-RESEARCH.md:635`) explicitly flags as a gap in D-05's literal wording that this plan should close, and it does.
- Loki retention config (`compactor.retention_enabled`, `delete_request_store`, not `table_manager`) is checked against the *current* Loki 2.x+ mechanism, correctly avoiding the deprecated `table_manager`/`chunk_store_config` shape named in RESEARCH's "State of the Art" table (`12-RESEARCH.md:556`).

**Concerns:**
- **MEDIUM** — The verify script hardcodes specific Loki config-schema assertions (`schema: v13`, `store: tsdb`, `object_store: filesystem`) as ground truth, while the plan's own action text says these need to be "confirmed... against Context7... rather than from memory — Loki's config schema has moved between minor versions" (`12-02-PLAN.md` Task 1 action). If the config that's actually correct for the pinned `grafana/loki:3.7.7` differs from `v13`/`tsdb`, the executor would need to edit both the config *and* this verify script consistently — a circular dependency that's easy to get half-right under time pressure.
- **LOW** — The Postgres `log_statement` check (Task 2, step 7) is a good defensive measure, but its premise ("Phase 11 raised it to `all` once for a one-off verification") isn't independently confirmed in this session as still being the live setting — the check is reasonable regardless of what it finds, so this isn't a design flaw, just a note that its "finding" framing assumes a specific starting state that may not hold.

**Suggestions:**
- In Task 1, add an explicit instruction: "if the Loki schema-config keys fetched from Context7 differ from `v13`/`tsdb`/`filesystem`, update this task's own verify script assertions in the same commit" — currently this is implied by "Confirm the exact key shape" but not tied back to the specific hardcoded checks that would otherwise silently diverge from the actual shipped config.

**Risk Assessment:** LOW-MEDIUM — sound design; the only real risk is a self-inconsistency between prose ("verify against docs") and a verify script that hardcodes what those docs are expected to say.

## 12-03

**Summary:** The most technically dense plan in the phase, correctly identifying and designing around the two sharpest cross-cutting risks (cross-project network gap, Postgres init-script trap) with evidence grounded directly in this repo's own committed comments — but I believe it under-weights the near-certainty of a third risk (Redpanda's admin-API default bind address) that its own Task 1 checkpoint is built to discover.

**Strengths:**
- Pitfall 1 (Prometheus can't reach `redpanda-nonprod`) is grounded in a real, verbatim repo comment I independently confirmed: `docker-compose.nonprod.yml:64-65` — "Deliberately NOT on kanban-edge -- only app-nonprod may reach the shared edge network." This is not an invented risk; it's read directly off the file the plan modifies.
- Pitfall 2's Postgres init-script trap is correctly grounded: `docker/postgres-init/01-create-databases-and-roles.sh:22-24` states verbatim, "This script runs exactly once, only when `/var/lib/postgresql/data` is empty on first container boot... every later `docker compose up` silently skips this script again" — exactly what the plan cites, confirmed word-for-word.
- The monitoring-role SQL correctly requires `GRANT CONNECT` on both databases, matching the actual `REVOKE CONNECT ON DATABASE :"prod_db" FROM PUBLIC` pattern verified in the init script — this isn't defensive boilerplate, it's required because PUBLIC's connect grant really was revoked.
- Task 1's requirement for a *negative* test (monitoring role provably cannot read an application table, in both databases, with the verbatim permission-denied error captured) is a genuinely rigorous bar — "prove least privilege, do not assume it" is stated and then actually mechanically required in the acceptance criteria.

**Concerns:**
- **MEDIUM** — Neither `docker-compose.prod.yml`'s `redpanda` command (`:361-415`) nor `docker-compose.nonprod.yml`'s `redpanda-nonprod` command (`:72-90`) sets an `--admin-addr` flag — I verified this directly, both command lists are absent that flag. Based on my own knowledge of Redpanda (not independently re-verified against docs in this session, since I have no live web access here), Redpanda's admin API defaults to binding `127.0.0.1:9644`, not `0.0.0.0:9644`, when `--admin-addr` is unset — which would make it unreachable from another container (Prometheus) over the Docker network regardless of whatever network wiring `kanban-metrics` provides. The plan's Task 1 does design a live probe for exactly this ("neither broker's command... sets an `--admin-addr` argument; Redpanda's own default admin address is `0.0.0.0:9644`, but that default has not been verified"), which is the right mechanism, but frames the two outcomes as a genuine 50/50 rather than treating "the flag is needed" as the expected finding. If I'm right about the Redpanda default, this means Task 2 will likely need its conditional `--admin-addr` branch, which forces a broker recreate that (per the plan's own text) "ripples through `app`'s `depends_on: service_healthy`" on the production side — a second live-production interruption that better foreknowledge could have folded into the original, planned recreate.
- **LOW** — Task 1's own text says the nonprod Redpanda probe happens "once `kanban-metrics` exists and `redpanda-nonprod` has joined it, which happens in Task 2's deploy" — but Task 2 is `type: auto` (writes files, doesn't deploy anything) and Task 3 is the `checkpoint:human-action` that actually runs `docker compose up -d`. This is a small wording imprecision; Task 3's own instructions correctly include the full nonprod probe, so it isn't a functional gap, just an internally inconsistent cross-reference.

**Suggestions:**
- Reframe Task 1's Redpanda probe instructions to state the expected outcome plainly (admin API very likely bound to loopback by default) so the operator isn't surprised mid-checkpoint, and so Task 2's conditional branch is read as "confirm and apply" rather than "50/50 decision."
- Fix "Task 2's deploy" → "Task 3's deploy" in Task 1's read_first/instructions cross-reference.

**Risk Assessment:** MEDIUM — the plan's mechanics (probe-then-conditionally-patch) are sound and would catch the problem either way, but the likely need for a second broker recreate (with its `depends_on` ripple into `app`) is a real, foreseeable cost that better-calibrated confidence in the plan text could have avoided or at least flagged more prominently as the expected path.

## 12-04

**Summary:** A tightly-scoped, low-blast-radius plan that correctly identifies and defends against the single most common Grafana-dashboards-as-code failure mode (unresolved `__inputs`/`${DS_*}` import placeholders), with an appropriately falsifiable "survives a forced recreate" acceptance test.

**Strengths:**
- The `__inputs`/`${DS_*}` placeholder problem is a real, well-known Grafana gotcha (dashboards exported from the UI or downloaded via the dashboards.grafana.com API carry an `__inputs` block that only Grafana's *import* flow resolves, never the file-based provisioning provider) — the plan's Task 1 verify script checks for this structurally (regex over the raw JSON, not just "does the file exist"), which is the right level of rigor for a failure mode that otherwise looks identical to success (dashboard appears in the sidebar, every panel says "datasource not found").
- Correctly treats the cAdvisor/postgres_exporter dashboard IDs as provisional (`12-RESEARCH.md:296-299`, confirmed `[ASSUMED]`) and instructs the executor to re-verify and substitute rather than trust the research doc's guess — appropriate given RESEARCH's own stated LOW confidence there.
- No Compose file is touched — this plan correctly reuses the bind mount plan 12-01 already established (`docker/grafana/provisioning` mounted in full), so its blast radius really is limited to adding files under an existing mount, as claimed.
- Task 2's requirement that dashboards survive `docker compose up -d --force-recreate grafana` is a genuinely meaningful test — it's the one check that actually distinguishes "provisioned as code" from "happens to currently exist in the volume," and a UI-created dashboard would fail it.

**Concerns:**
- **LOW** — The provenance regex (`\b\d{3,7}\b` for a dashboard ID, `20\d\d-\d\d-\d\d` for a date) could false-positive against numbers already present in a vendor dashboard's own pre-existing `description` field (e.g., a version string). Self-correcting at execution time since the executor writes the description themselves, but worth noting the check isn't as precise as it could be.

**Suggestions:** None material — this is an appropriately narrow, well-verified plan.

**Risk Assessment:** LOW.

## 12-05

**Summary:** Faithfully reproduces this project's own established restart-ladder measurement discipline — I directly confirmed the required runbook section headers (`### Workload used to measure`, `### Iteration ladder`, `### Adopted floor`, `### Step below the floor`, `### Host coexistence`, `### Measurement date`) match the real precedent at `docs/INFRA_RUNBOOK.md:2764-2925` (Plan 11-03) almost exactly — but the grouped-descent method for the four "stateless scrapers" is a real methodological compromise the plan discloses but doesn't fully mitigate.

**Strengths:**
- The runbook section structure the plan requires is not invented — I verified it against the real Plan 11-03 section (`docs/INFRA_RUNBOOK.md`, headers at lines 2772/2785/2811/2828/2859/2879 relative to the section start) and it matches essentially one-for-one.
- The cgroup-accounting-overhead lesson ("a cap with no headroom above the process's real high-water mark has already broken startup on this box once") is grounded in a real, verbatim repo comment: `docker-compose.prod.yml:350-359` describes exactly this failure for Redpanda ("`insufficient physical memory: needed 2147483648 available 2078277632`... Seastar's own probe saw ~66MiB less than the 2048MiB it asked for and refused to start"), confirmed directly.
- The "day-one measurement caveat" for Prometheus/Loki (their working sets grow under 30-day retention, so a floor measured against an empty store may not hold in week three) is a genuinely sophisticated methodological point most restart-ladder writeups wouldn't think to separate out, and the plan requires the adopted value to state ladder-justified vs. growth-headroom portions separately rather than folding them together.
- Correctly separates Caddy's ladder into a later plan (12-06) specifically because it's the one container whose descent risks live production traffic — a sound risk-isolation decision.

**Concerns:**
- **MEDIUM** — The four "stateless scrapers" (node-exporter, cadvisor, postgres-exporter, promtail) are descended *together* at the same rung, rather than independently. This conflates four genuinely different memory profiles into one signal: cAdvisor (which scans every container's cgroups/proc continuously) is very likely to have a meaningfully larger real footprint than postgres-exporter or node-exporter (which mostly poll on a fixed interval). Group-descent discovers only "the highest common floor across all four fails together," which risks over-provisioning the lighter three to accommodate the heaviest one, or under-provisioning if the heaviest one's failure mode is masked by the other three's healthier margins in aggregate checks. The plan's own design_alternatives section acknowledges this trade-off explicitly ("A failure in one while another is also being changed makes the attribution ambiguous" — the stated reason for NOT doing this to the stateful three) but accepts the same ambiguity for the stateless four in the name of tractability, without offering a de-coupling escape hatch if the group result turns out to be a poor fit for an outlier.
- **LOW** — Task 1 concentrates seven services' worth of ladder work into a single long human checkpoint, which is efficient but means partial progress is hard to resume cleanly if the session is interrupted partway through.

**Suggestions:**
- Add an explicit note to Task 1: if the four stateless scrapers show meaningfully different memory profiles once burst-tested as a group (e.g., cAdvisor visibly dominates), treat that as a signal to re-run cAdvisor's ladder independently rather than accepting the group's lowest-common floor uncritically.

**Risk Assessment:** LOW-MEDIUM — the overall methodology is sound and well-precedented in this exact repo, with one disclosed-but-not-fully-mitigated compromise in the grouped scraper descent.

## 12-06

**Summary:** Correctly closes the phase's two folded todos and gives Caddy — the one container every request path on the host traverses — a measured cap via the highest-stakes ladder in the phase, with unusually careful attention to the actual security mechanism (the rate limiter's per-source-address state) driving the cap's real purpose. The diagram/doc-currency work is grounded in real, previously-established tooling and precedent I independently verified.

**Strengths:**
- Caddy's current total absence of any `mem_limit` is accurately described — I read the full `caddy` service block (`docker-compose.prod.yml:66-108`) and confirmed there is no `mem_limit` key anywhere in it, which is exactly what D-02's folded todo and this plan's premise claim.
- The adversarial workload design (many distinct source addresses against the rate-limited `/api/signin*` paths) correctly targets the actual mechanism that makes Caddy's memory "attacker-influenced": `Caddyfile:37` keys the auth zone on `{remote_host}`, confirmed verbatim, so distinct sources — not request volume from one source — are what would actually grow the limiter's state.
- The Let's-Encrypt-rate-limit stop condition is grounded in real, repo-documented risk: both `Caddyfile:131-138` and `docker-compose.prod.yml:104-105` independently state this exact risk in their own words, confirmed.
- Requiring `scripts/verify-caddy-image-tag.py` to still pass after this edit is a well-targeted regression guard — I read the script and confirmed it's a real, working invariant check (four numbered invariants I1-I4, gating the compose `image:` literal against `docker/caddy/Dockerfile`), not a stub; disturbing the adjacent `image:` line while editing `mem_limit` is a plausible copy-paste slip this catches.
- The diagram re-render discipline is accurately grounded: `scripts/render-diagrams.sh`'s own header (confirmed read directly) documents a real, pre-existing "+6.21%" height drift specifically for `infra-physical-deployment`, and the plan correctly instructs the executor not to treat that known drift as a new finding.
- The five numbered trust boundaries `[1]`-`[5]` the plan requires to survive are real and present exactly where claimed: `docs/diagrams/infra-physical-deployment.mmd` lines 5, 10, 16, 30-33 confirmed to carry `[1]` through `[5]` verbatim.

**Concerns:**
- **MEDIUM** — This is the highest-blast-radius ladder in the phase (every path — both app environments and Grafana — goes through this one container) and is correctly placed last, but the plan provides no explicit escalation/fallback if the ladder cannot find a comfortable passing rung below some reasonable ceiling. The existing idle baseline cited in the repo (`docker-compose.prod.yml:382-383`: "Caddy stayed flat (~20MiB, <0.2% CPU)") was measured under the *old*, non-adversarial 54-request burst — this plan's new adversarial component is a materially different load shape, and if it turns out Caddy's real peak under many-distinct-source rate-limiter pressure is much higher than ~20MiB extrapolation would suggest, the plan has no stated ceiling or decision point beyond "keep descending until you find a failing rung."
- **LOW** — The plan explicitly permits the operator to run the adversarial component with "fewer distinct source addresses than intended" and still accept the result, with only a disclosure requirement. This is honest design (better than silently pretending full coverage), but the actual security-relevant sizing evidence produced could end up thinner in practice than the elaborate design implies, since most single-operator setups won't have easy access to many genuinely distinct source IPs.

**Suggestions:**
- Add an explicit ceiling/escalation note to Task 1: if the ladder cannot find a passing rung with reasonable headroom below, say, 512m, that's itself a finding worth surfacing explicitly (possibly indicating the rate limiter's memory growth under this workload shape needs its own investigation) rather than silently adopting whatever rung happens to pass.

**Risk Assessment:** MEDIUM — correctly sequenced last and unusually well-instrumented for a resource-cap plan, but the highest production blast-radius of any plan in the phase and the one place where "we don't know what we don't know" (real attacker-shaped load) is least bounded by prior measurement.

---

## Cross-Plan Observations

- **Dependency chain is fully serial** (12-01 → 12-02 → 12-03 → 12-04 → 12-05 → 12-06), even though 12-02 (Loki/Promtail) has no *functional* dependency on 12-01's Prometheus/Grafana wiring beyond needing the shared `docker/grafana/provisioning` bind mount and `datasources.yaml` file to already exist. This is a deliberate, disclosed choice (tracer-first philosophy, explicitly argued in 12-01's design alternatives), and it does buy real safety — but it also means the phase's total wall-clock cost is the sum of six sequential human-operated VM checkpoints, each with its own live-verification burden. Given that plans 12-01 through 12-04 largely add independent scrape/log targets to an already-proven skeleton, a reasonable question for the operator is whether waves 2-4 could run with looser coupling once the tracer (12-01) is proven — the plans as written don't explore this, likely correctly given the project's demonstrated preference for one-thing-at-a-time infra changes.
- **Consistent, well-precedented two-phase task structure** across every plan (`type: auto` config-writing task with a structural verify script, followed by a `type: checkpoint:human-action` deploy-and-prove task) matches this project's own demonstrated track record — Phase 08-03's real crash-looping-rung-that-passed-a-startup-snapshot-check incident (referenced accurately by multiple plans here, e.g. 12-05's "a point-in-time 'it started healthy' check is not evidence") is exactly the kind of failure this two-phase structure is designed to catch, and I confirmed that incident's language is real by cross-referencing the actual 08-03 runbook section headers.
- **The Redpanda admin-API default-bind risk (flagged under 12-03)** is the one place across all six plans where my own domain knowledge suggests the plan's stated uncertainty ("this default has not been verified") understates how likely the fix will actually be needed. Since this affects a live broker recreate with a `depends_on` ripple into the production `app` container, tightening this plan's confidence level (or at minimum sequencing the compose changes so the `--admin-addr` fix and the network wiring land in the same recreate rather than a two-step "probe, discover it's needed, recreate again") would reduce one full round of live-production churn.

---

## Antigravity Review

## 12-01

**Summary**
This plan establishes the baseline topology for the observability stack by deploying Prometheus, `node_exporter`, and Grafana. It explicitly sets up a new Caddy site block for Grafana, avoiding Caddy-level authentication in favor of Grafana's built-in login, and structures the changes to pass existing repository invariant scripts.

**Strengths**
- Correctly aligns with `scripts/verify-compose-ports.py:98` (`ALLOWED_PUBLISHERS = {"docker-compose.prod.yml": {"caddy"}}`), guaranteeing no internal monitoring tools will be exposed to the host network directly.
- The plan acknowledges the `{$APP_DOMAIN_NONPROD}` block's existing structure (`Caddyfile:151-153`) as a model, ensuring consistency in the proxy configuration.
- Consciously avoids Let's Encrypt rate limiting by separating DNS propagation checks from the actual Caddyfile deployment, protecting the shared `caddy-data` volume logic.

**Concerns**
- [LOW] DNS propagation: The plan relies on a local `dig +short` check. DNS propagation for DuckDNS might be cached negatively by upstream resolvers, meaning a local successful `dig` doesn't 100% guarantee Let's Encrypt will see the IP immediately.
- [LOW] Appending to `.env.prod.example`: Recommends using a shell append redirect to avoid tripping secret-scanning tools, which can be brittle depending on the environment shell.

**Suggestions**
- Add a 30-60 second wait after `dig` succeeds before deploying Caddy to ensure wider DNS propagation.
- For `.env.prod.example` updates, verify the file's newline endings before appending to prevent concatenating onto an existing line.

**Risk Assessment**
LOW. The plan demonstrates a very high understanding of existing infrastructure guards and mitigates the only real external risk (Let's Encrypt rate limits) proactively.

## 12-02

**Summary**
This plan implements log aggregation via Loki and Promtail, configuring 30-day compactor retention for Loki and mounting `docker.sock` to Promtail to pull logs from all containers. It meticulously avoids common configuration pitfalls like filtering containers by label or omitting the Docker JSON-file pipeline stage.

**Strengths**
- Addresses Loki's 3.x retention mechanism properly (using `compactor` instead of the legacy `table_manager`), matching modern Loki requirements.
- Prevents the silent JSON-envelope logging failure (RESEARCH Pitfall 5) by explicitly mandating the `docker: {}` pipeline stage in `promtail-config.yaml`.
- Directly tests for information disclosure (`SHOW log_statement;` in Postgres) which could leak sensitive bind parameters into the centralized logs if left on `all` (as previously modified in Phase 11).

**Concerns**
- [HIGH] `docker.sock` access: As acknowledged in the plan, mounting `docker.sock` even read-only grants effective host-root. While Promtail isn't public-facing, any vulnerability in Promtail could compromise the entire host.
- [LOW] Promtail EOL: The plan uses Promtail despite it being EOL (March 2026). Deploying a soon-to-be-deprecated tool creates immediate technical debt.

**Suggestions**
- Consider migrating to Grafana Alloy sooner rather than later to avoid EOL software, though adhering to the locked user decision (D-01/D-08) takes precedence for now.
- Ensure Loki's disk usage is strictly monitored over the 30-day retention period, as a sudden spike in application errors could fill the remaining disk headroom faster than anticipated.

**Risk Assessment**
MEDIUM. The architectural choices are solid, but the inherent risk of mounting `docker.sock` and using an EOL log shipper elevates the risk slightly, even if explicitly accepted by the user decisions.

## 12-03

**Summary**
This plan introduces cAdvisor and `postgres_exporter` to gather container-level and database-internal metrics. It enforces strict least-privilege constraints, configuring cAdvisor with read-only mounts and no privileged flags, and explicitly creating a `monitoring` role for Postgres.

**Strengths**
- Adheres strictly to the least-privilege principle: cAdvisor mounts are read-only (`/rootfs`, `/var/run`, `/sys`, `/var/lib/docker`), and `--privileged`/`--pid=host` flags are explicitly omitted.
- The `postgres_exporter` uses a dedicated `monitoring` role rather than the superuser, mitigating database access risks, complementing the setup described in `docker-compose.prod.yml:110` (Postgres service).
- Network isolation is well-planned, creating a specific `kanban-metrics` network to bridge the observability stack with the nonprod environment securely without exposing internal networks unnecessarily.

**Concerns**
- [MEDIUM] Missing `kanban-metrics` definition in nonprod: The plan assumes adding `kanban-metrics` to `docker-compose.nonprod.yml` as an `external: true` network will work, but it must ensure the network actually exists on the host prior to nonprod deployment.
- [LOW] Redpanda recreate: Modifying Redpanda's networks will cause a container recreate, impacting the nonprod environment's availability briefly.

**Suggestions**
- Explicitly document the `docker network create kanban-metrics` command prior to deploying `docker-compose.nonprod.yml` to prevent deployment failures.
- Add a post-deployment verification step to ensure Redpanda nonprod successfully rejoins both `kanban-edge` and `kanban-metrics` networks.

**Risk Assessment**
LOW. The plan handles sensitive components (cAdvisor host mounts and database credentials) with appropriate strictness and extensive verification checks.

## 12-04

**Summary**
This plan provisions Grafana dashboards as code, fetching pre-built community dashboards and committing them to the repository. It avoids manual UI configuration to ensure the dashboards survive container recreates and remain version-controlled.

**Strengths**
- Strictly adheres to the "everything as code" philosophy by rejecting manual UI dashboard creation, ensuring resilience against container recreates.
- Properly accounts for Grafana's file provisioning limitations by manually resolving `${DS_PROMETHEUS}` datasource placeholders within the JSON before committing.
- Includes a rigorous forced container recreate test (`docker compose up -d --force-recreate grafana`) to prove dashboards are not trapped in the `grafana-data` volume.

**Concerns**
- [LOW] Manual JSON editing: Manually resolving datasource placeholders in large, minified third-party JSON files is error-prone.
- [LOW] Dashboard bitrot: Community dashboards might assume specific metric names or labels that diverge from the actual deployed exporters over time.

**Suggestions**
- Use a small script or `sed` command to perform the `${DS_PROMETHEUS}` substitution to reduce human error when modifying the JSON files.
- Periodically re-verify the community dashboard IDs against Grafana's marketplace to ensure they haven't been deprecated.

**Risk Assessment**
LOW. The risks are largely cosmetic (empty panels if a metric name mismatches) rather than structural or security-related.

## 12-05

**Summary**
This plan replaces the provisional memory limits for the seven new observability containers with measured floors, derived through a rigorous restart-ladder descent methodology under a simulated heavy workload.

**Strengths**
- The restart-ladder methodology correctly simulates peak transient loads (wide-range queries) rather than relying on idle steady-state metrics, ensuring realistic caps.
- Explicitly separates the ladder-derived memory floors from the deliberate 30-day retention growth headroom for Prometheus and Loki, ensuring future engineers understand the baseline measurements.
- Follows existing best practices, mirroring the detailed `mem_limit` comments seen for the Redpanda container (`docker-compose.prod.yml:343-360`).

**Concerns**
- [MEDIUM] Settle time: The 20-second post-workload settle time might be too short for Go (Prometheus/Loki) garbage collectors to release memory, potentially leading to slightly inflated RSS readings.
- [LOW] cAdvisor memory scaling: The plan correctly notes that cAdvisor's memory scales with the number of containers, meaning its memory usage might plateau early during load testing.

**Suggestions**
- Increase the post-workload settle time to 60 seconds to allow for more accurate steady-state RSS readings after GC cycles.
- If total memory exceeds the 1024 MiB headroom, explicitly document the steps to decommission cAdvisor as the fallback plan.

**Risk Assessment**
LOW. The memory measurement approach is highly disciplined, empirically driven, and explicitly accounts for future data growth.

## 12-06

**Summary**
This final plan measures and applies a memory limit to the existing Caddy container under an adversarial load, updates the infrastructure documentation/diagrams to reflect the new eleven-container reality, and correctly closes out the related project planning todos.

**Strengths**
- Correctly identifies that Caddy's memory is influenced by unauthenticated edge traffic (rate limiter state, TLS handshakes, defined in `Caddyfile:22-67`) and simulates this explicitly in the load test.
- Protects Let's Encrypt rate limits by explicitly mandating a halt if a low memory rung causes Caddy to crash-loop.
- Ensures the `scripts/verify-caddy-image-tag.py` invariant is respected, preventing unintentional image tag drift during compose file edits.

**Concerns**
- [MEDIUM] Adversarial load realism: Simulating a true multi-IP adversarial load from a single testing machine is difficult, meaning the actual memory peak might still be underestimated.
- [LOW] Documentation sync: The architecture diagram `.mmd` changes will generate a new `.png` with slightly different dimensions, which the plan accurately anticipates and accepts.

**Suggestions**
- Explicitly document the methodology used to generate the distinct-source-IP adversarial load (e.g., using different proxies or VPNs if available).
- Add a generous safety margin (e.g., +20%) to Caddy's measured memory peak to account for the limitations of the simulated adversarial load.

**Risk Assessment**
LOW. The plan treats the Caddy memory limit as a genuine security boundary rather than just a resource constraint, and ensures all documentation remains perfectly in sync with the deployment reality.

---

## Codex Review

## 12-01

### Summary

The vertical tracer is a good sequencing strategy, but the plan’s node_exporter configuration does not actually measure the host. As written, it can satisfy every automated and live check while exposing metrics for the node_exporter container itself. The plan also incorrectly interprets D-03 as forbidding Caddy rate limiting.

### Strengths

- The tracer isolates the full transport path before adding more exporters. The live verification checks exporter output, Prometheus target health, a non-empty query, Grafana authentication, datasource health, and an actual panel (`.planning/phases/12-self-hosted-observability-stack/12-01-PLAN.md:493`). This is substantially stronger than treating a running container as success.
- The no-published-port requirement is backed by the existing structural gate (`.planning/phases/12-self-hosted-observability-stack/12-01-PLAN.md:367`). That script allows only Caddy to publish ports and rejects both `ports:` and host networking on internal services (`scripts/verify-compose-ports.py:98`, `scripts/verify-compose-ports.py:152`).
- The plan correctly preserves certificates in the existing Caddy volumes (`.planning/phases/12-self-hosted-observability-stack/12-01-PLAN.md:289`); the production manifest already persists `/data` and `/config` (`docker-compose.prod.yml:101`).

### Concerns

- **HIGH — node_exporter will monitor its container, not the VPS.** The plan explicitly says node_exporter is stateless and needs no volume (`.planning/phases/12-self-hosted-observability-stack/12-01-PLAN.md:243`). Containerized node_exporter requires the host root filesystem mounted and `--path.rootfs` configured; otherwise the advertised “real host metric” does not prove host visibility. The official node_exporter container guidance uses `/:/host:ro,rslave` and `--path.rootfs=/host` ([node_exporter README](https://github.com/prometheus/node_exporter/blob/master/README.md)). This violates the phase’s host-metrics boundary (`.planning/phases/12-self-hosted-observability-stack/12-CONTEXT.md:9`).
- **MEDIUM — D-03 is misread as prohibiting rate limiting.** The plan bans `rate_limit` alongside authentication controls (`.planning/phases/12-self-hosted-observability-stack/12-01-PLAN.md:296`). D-03 prohibits `basic_auth` and an IP allowlist, not traffic controls (`.planning/phases/12-self-hosted-observability-stack/12-CONTEXT.md:41`). The existing production site distinguishes rate limiting from authentication and rate-limits sign-in traffic (`Caddyfile:22`). A public Grafana login with no edge throttling unnecessarily increases brute-force and resource-exhaustion exposure.
- **LOW — `RestartCount=0` does not prove existing containers were undisturbed.** A recreated container begins again at zero. The acceptance test at `.planning/phases/12-self-hosted-observability-stack/12-01-PLAN.md:514` should compare pre/post container IDs or `StartedAt`.

### Suggestions

- Add the node_exporter host-root mount and `--path.rootfs=/host`, then compare exported host memory/filesystem values with `free` and `df`. Avoid `network_mode: host`, which correctly conflicts with the repository’s port-isolation guard.
- Permit a monitoring-specific rate limit while retaining Grafana as the only authentication gate.
- Capture existing container IDs and start timestamps before deployment and compare them afterward.

### Risk Assessment

**HIGH.** The principal phase truth—host metrics—can falsely pass while observing only the exporter container.

## 12-02

### Summary

The log-pipeline verification is thoughtfully adversarial, especially its all-container and raw-envelope checks. However, the Loki schema configuration is incomplete, and the plan proceeds with a privileged, unsupported Promtail deployment without a documented post-research user decision.

### Strengths

- The plan checks every named application and infrastructure container individually and requires actual log content rather than mere target discovery (`.planning/phases/12-self-hosted-observability-stack/12-02-PLAN.md:23`). This directly implements D-08’s six-container scope (`.planning/phases/12-self-hosted-observability-stack/12-CONTEXT.md:74`).
- It correctly identifies Promtail’s Docker socket as a high-value host boundary and requires read-only access (`.planning/phases/12-self-hosted-observability-stack/12-02-PLAN.md:85`).
- The explicit Docker pipeline stage and absence of discovery filters address the two silent-failure modes identified by research.

### Concerns

- **HIGH — the specified Loki `schema_config` omits mandatory `from`.** The requested fields include schema, store and object store, but not the schema activation date (`.planning/phases/12-self-hosted-observability-stack/12-02-PLAN.md:140`). Every Loki schema period requires `from`, and for a new installation it must be a date in the past ([Loki schema documentation](https://grafana.com/docs/loki/latest/operations/storage/schema/)). The automated verification also never checks it.
- **HIGH — Promtail EOL is declared “accepted” without an explicit post-research decision.** The plan acknowledges EOL but treats its mention in CONTEXT as acceptance (`.planning/phases/12-self-hosted-observability-stack/12-02-PLAN.md:81`). Grafana states that Promtail reached EOL on 2026-03-02 and receives no further updates ([official Promtail notice](https://grafana.com/docs/loki/latest/send-data/promtail/)). That is material because this container receives a Docker socket mount. The context predates this being framed as a trade-off; it should be reopened rather than silently inferred.
- **MEDIUM — Promtail’s positions file is ephemeral.** The plan deliberately gives Promtail no persistent volume (`.planning/phases/12-self-hosted-observability-stack/12-02-PLAN.md:191`). Recreates can cause log replay or missed tailing depending on discovered offsets and rotation state. A small named volume would make restart behavior deterministic.
- **MEDIUM — the label used for container identity is unstable for production services.** Production services do not define `container_name` (`docker-compose.prod.yml:66`, `docker-compose.prod.yml:260`), while nonprod does (`docker-compose.nonprod.yml:54`, `docker-compose.nonprod.yml:134`). Relabeling only the Docker container name therefore produces project-prefixed names for production but fixed names for nonprod.

### Suggestions

- Add an explicit past `from` date and index prefix, and verify Loki’s pinned image configuration before deployment.
- Add a blocking choice: migrate to Grafana Alloy, following the [official migration path](https://grafana.com/docs/grafana-cloud/observe-and-act/send-data/alloy/set-up/migrate/from-promtail/), or record explicit acceptance of Promtail’s unsupported status and socket risk.
- Persist `/tmp/positions.yaml` in a small named volume.
- Add stable `service`, `compose_project`, and derived `env` labels from Compose metadata.
- Add a disk-volume risk bound. Thirty-day retention across all Caddy and infrastructure logs is not bounded by the existing per-container `json-file` rotation after logs enter Loki.

### Risk Assessment

**HIGH.** Loki may fail at startup or first ingestion, and the log shipper has an unresolved lifecycle/security decision.

## 12-03

### Summary

This plan does strong work around cross-project reachability and least-privilege Postgres monitoring, but several implementation details conflict with either the locked context or current exporter behavior. The cAdvisor mounts and environment labeling are the most consequential.

### Strengths

- Creating the monitoring role through a live operation is correct. The existing init script explicitly runs only when the Postgres data directory is empty (`docker/postgres-init/01-create-databases-and-roles.sh:29`), so adding another init script would silently do nothing.
- The plan includes positive monitoring access and negative application-table access tests for both databases, making least privilege an evidenced property rather than a role-name claim.
- A purpose-specific cross-project network is consistent with the existing network partitioning. The current manifest separates edge and database concerns (`docker-compose.prod.yml:42`), and nonprod Redpanda currently joins only its project network (`docker-compose.nonprod.yml:62`).
- Live verification requires every target individually healthy and checks cAdvisor series for containers other than itself, avoiding the common “endpoint responds but useful metrics are absent” false positive.

### Concerns

- **HIGH — cAdvisor’s mounts do not implement D-05.** The plan mounts the whole `/var/run` and omits `/proc` (`.planning/phases/12-self-hosted-observability-stack/12-03-PLAN.md:220`). D-05 explicitly names `/var/run/docker.sock`, `/sys`, and `/proc`, all read-only (`.planning/phases/12-self-hosted-observability-stack/12-CONTEXT.md:57`). Mounting all of `/var/run` broadens exposure while omitting a locked host-metric source. The verifier codifies this substituted design rather than detecting it.
- **HIGH — the Postgres password is interpolated into a URI without encoding.** The plan uses `DATA_SOURCE_NAME` with `${MONITORING_DB_PASS}` embedded (`.planning/phases/12-self-hosted-observability-stack/12-03-PLAN.md:237`). Password characters such as `@`, `:`, `/`, `?`, `#`, or `%` can alter or invalidate the URI. The project’s existing provisioning deliberately supports hostile credential characters using psql variables (`docker/postgres-init/01-create-databases-and-roles.sh:40`). The official exporter supports separate `DATA_SOURCE_URI`, `DATA_SOURCE_USER`, and `DATA_SOURCE_PASS` settings ([postgres_exporter documentation](https://github.com/prometheus-community/postgres_exporter)).
- **HIGH — cAdvisor cannot distinguish prod and nonprod with the proposed `env` labeling.** Its scrape target is assigned `env: prod` even though it observes every container on the shared host (`.planning/phases/12-self-hosted-observability-stack/12-03-PLAN.md:268`). D-04 requires production and nonprod containers to be distinguishable (`.planning/phases/12-self-hosted-observability-stack/12-CONTEXT.md:48`). All cAdvisor series would inherit the scrape target’s `prod` label unless a recording/relabeling rule derives environment from Compose metadata.
- **MEDIUM — the plan adopts deprecated database autodiscovery.** `--auto-discover-databases` is specified at `.planning/phases/12-self-hosted-observability-stack/12-03-PLAN.md:244`; the exporter marks that flag deprecated in its current documentation. A greenfield deployment should avoid relying on it without a migration plan.
- **MEDIUM — network membership is verified only in rendered YAML.** The “exactly two members” assertion does not detect manually attached or stale live endpoints. The production proof should inspect `docker network inspect kanban-metrics`.
- **MEDIUM — the documented recovery command is not idempotent.** The plan calls it “idempotent-by-inspection” (`.planning/phases/12-self-hosted-observability-stack/12-03-PLAN.md:512`), but an unconditional `CREATE ROLE` fails once the role exists.

### Suggestions

- Implement the exact D-05 mounts or formally amend the decision with a security rationale. Prefer the socket-specific bind over all of `/var/run`.
- Use separate exporter password configuration or a Docker secret/password file.
- Derive a stable per-container `env` label from `com.docker.compose.project`, and label host-level node metrics `env=shared`.
- Replace deprecated autodiscovery with an explicitly supported multi-database arrangement.
- Verify actual live network endpoints and make role reconciliation genuinely idempotent.
- Add a concrete Redpanda internal-metric query; target health alone does not establish useful broker telemetry.

### Risk Assessment

**HIGH.** The plan can leak environment classification, mishandle valid credentials, and ship a cAdvisor configuration that contradicts the locked decision.

## 12-04

### Summary

Provisioning dashboards from committed JSON is the right reproducibility choice. The plan is not yet robust against modern Grafana datasource references, and it omits the Redpanda dashboard/query surface implied by the phase’s metrics coverage.

### Strengths

- Dashboard provisioning is committed and survives volume replacement (`.planning/phases/12-self-hosted-observability-stack/12-04-PLAN.md:122`). This aligns with the existing whole-directory Grafana provisioning mount planned earlier.
- The plan understands the import-versus-provisioning distinction and explicitly removes unresolved `${DS_*}` inputs (`.planning/phases/12-self-hosted-observability-stack/12-04-PLAN.md:142`).
- It requires real plotted data and a forced Grafana recreation, providing stronger evidence than “dashboard appears in the sidebar.”

### Concerns

- **HIGH — datasource replacement handles only legacy string references.** The plan provisions Prometheus without a stable `uid`, then replaces placeholders with the display name (`.planning/phases/12-self-hosted-observability-stack/12-04-PLAN.md:142`). Modern dashboards commonly use objects such as `{"type":"prometheus","uid":"..."}`. Grafana generates a UID when provisioning omits one ([Grafana provisioning documentation](https://grafana.com/docs/grafana/latest/administration/provisioning/)). The verifier’s string regex (`.planning/phases/12-self-hosted-observability-stack/12-04-PLAN.md:216`) does not inspect object-form UIDs, so broken panels can pass Task 1.
- **HIGH — Redpanda’s internal metrics have no dashboard or equivalent human-facing proof.** The plan vendors only node_exporter, cAdvisor, and Postgres dashboards (`.planning/phases/12-self-hosted-observability-stack/12-04-PLAN.md:104`). The context explicitly leaves selection of node_exporter, cAdvisor, Postgres, and Redpanda dashboards to planning (`.planning/phases/12-self-hosted-observability-stack/12-CONTEXT.md:85`), while D-06 includes Redpanda metrics. Elsewhere, the plans prove only Redpanda target health, not an actual broker metric query.
- **MEDIUM — there is no repair loop for label-incompatible community dashboards.** Task 1 forbids PromQL edits and defers mismatches (`.planning/phases/12-self-hosted-observability-stack/12-04-PLAN.md:157`), while Task 2 requires working panels. A mismatched community dashboard therefore stops execution without an authorized committed fix.
- **MEDIUM — vendored JSON is treated as entirely inert.** Community dashboards can depend on plugins, contain external links, or execute expensive high-cardinality queries. Provenance alone does not validate compatibility or operational cost.

### Suggestions

- Assign fixed datasource UIDs in provisioning and recursively normalize every dashboard datasource reference—both strings and `{type, uid}` objects.
- Add a Redpanda dashboard or at least a small committed dashboard with verified `/public_metrics` broker health, storage, throughput, and error panels.
- Permit evidence-driven changes to dashboard variables and job selectors, with a diff and live re-verification.
- Validate plugin dependencies, variable queries, refresh intervals, and wide-range query cost before accepting each dashboard.

### Risk Assessment

**HIGH.** The automated checks can accept dashboards that fail datasource resolution, and one required stateful-service metrics surface remains unproven.

## 12-05

### Summary

The measurement discipline is unusually thorough and correctly recognizes that Prometheus and Loki grow over time. However, the procedure is not executable as written, and it evaluates services individually without establishing a safe aggregate memory budget for the actual host.

### Strengths

- A rung must remain functional across recreate, workload and settle, with target, dashboard and log-query checks (`.planning/phases/12-self-hosted-observability-stack/12-05-PLAN.md:166`). This correctly rejects “process is alive” as sufficient evidence.
- The plan explicitly separates day-one ladder evidence from retention-growth headroom for Prometheus and Loki (`.planning/phases/12-self-hosted-observability-stack/12-05-PLAN.md:185`). That is an important limitation to document.
- Re-verifying adopted values from a clean recreation and recording the rung below each adopted value makes the result falsifiable.

### Concerns

- **HIGH — the procedure simultaneously forbids and requires changing the limits.** Task 1 says not to change any Compose file (`.planning/phases/12-self-hosted-observability-stack/12-05-PLAN.md:140`) but instructs the operator to set each cap and force-recreate services (`.planning/phases/12-self-hosted-observability-stack/12-05-PLAN.md:155`). No temporary override file, `docker update` procedure, or other reproducible mechanism is defined.
- **HIGH — the host/container count is wrong throughout the plan.** The production manifest has four services (`docker-compose.prod.yml:66`, `docker-compose.prod.yml:110`, `docker-compose.prod.yml:260`, `docker-compose.prod.yml:337`) and nonprod has two (`docker-compose.nonprod.yml:54`, `docker-compose.nonprod.yml:134`). Seven new services produce thirteen host containers, not eleven as stated at `.planning/phases/12-self-hosted-observability-stack/12-05-PLAN.md:26`. This invalidates baseline wording and later architecture claims.
- **HIGH — individual passing caps do not establish aggregate OOM safety.** Existing configured ceilings already include 3 GiB for production app (`docker-compose.prod.yml:294`), 2200 MiB for Redpanda (`docker-compose.prod.yml:360`), 256 MiB for Postgres, 1 GiB for nonprod app (`docker-compose.nonprod.yml:151`), and 300 MiB for nonprod Redpanda (`docker-compose.nonprod.yml:125`). Adding eight more limits can substantially overcommit a 7.8 GiB host. `free -m` after one workload is useful, but it does not prove safety if Prometheus, Loki, JVM and brokers peak simultaneously. Docker memory limits are ceilings, not reservations.
- **MEDIUM — grouping four scrapers at each rung weakens attribution.** If more than one fails during a grouped descent, logs may not distinguish whether one failure caused missed scrapes for another.
- **MEDIUM — retention growth is acknowledged but not operationally controlled.** A generous discretionary margin is not evidence that the services will fit after 30 days. No scheduled remeasurement, size ceiling, filesystem quota, or day-30 acceptance check exists.

### Suggestions

- Specify a temporary Compose override stored outside the repository, show how it is generated, and require its removal after each ladder. Preserve the deployed source manifest until Task 2.
- Correct every baseline and coexistence statement to thirteen containers.
- Add an aggregate budget table for all thirteen container limits plus an OS reserve, and explicitly decide how much cgroup overcommit is acceptable.
- Record `State.OOMKilled` and cgroup `memory.events`, not only `RestartCount`, which resets on recreation.
- Add a day-7/day-30 remeasurement or storage-growth trigger for Prometheus and Loki.

### Risk Assessment

**HIGH.** The measurement cannot be reproduced safely as written, and passing individual ladders may still leave the host exposed to aggregate memory exhaustion.

## 12-06

### Summary

Separating Caddy’s ladder is sensible because it is the only monitoring-phase measurement that interrupts every public path. The documentation and todo hygiene are also strong. The plan nevertheless allows the Caddy security todo to close without exercising realistic source-address cardinality, and its deployment documentation is built around an incorrect container count.

### Strengths

- The plan correctly isolates Caddy’s measurement because its recreate affects production, nonprod and Grafana together (`.planning/phases/12-self-hosted-observability-stack/12-06-PLAN.md:85`).
- The workload connects the cap to the actual stateful mechanism: Caddy’s limiter uses `{remote_host}` (`Caddyfile:32`, `Caddyfile:49`), and the original todo identifies key cardinality as the concern (`.planning/todos/pending/2026-09-03-caddy-service-has-no-mem-limit.md:28`).
- The certificate stop condition is grounded in the persistent Caddy data volume (`docker-compose.prod.yml:101`) and appropriately prevents unattended crash loops.
- Todo handling is honest: the Caddy todo is completed, while the three-part logging todo stays pending with only the shipping portion marked resolved (`.planning/phases/12-self-hosted-observability-stack/12-06-PLAN.md:410`).
- Updating the architecture artifact is required by its own maintenance contract (`docs/INFRA_ARCHITECTURE.md:228`).

### Concerns

- **HIGH — the adversarial cardinality test has no minimum or executable source-generation mechanism.** The plan asks for “as many DISTINCT source addresses as you can produce” and permits weak coverage if disclosed (`.planning/phases/12-self-hosted-observability-stack/12-06-PLAN.md:149`, `.planning/phases/12-self-hosted-observability-stack/12-06-PLAN.md:210`). The original todo requires realistic key cardinality (`.planning/todos/pending/2026-09-03-caddy-service-has-no-mem-limit.md:36`). The existing load harness explicitly uses the runner’s single source address (`scripts/loadtest/run-rate-limit-verification.sh:17`), and its scenario has only one virtual user (`scripts/loadtest/rate-limit-prod.yml:23`). The todo could therefore be closed after testing only one limiter key—the exact axis that motivated it.
- **HIGH — architecture documentation would institutionalize the wrong topology.** The plan repeatedly says the VPS runs eleven containers (`.planning/phases/12-self-hosted-observability-stack/12-06-PLAN.md:28`, `.planning/phases/12-self-hosted-observability-stack/12-06-PLAN.md:398`). The actual final total is thirteen. Because the shared stack explicitly covers nonprod, omitting the two nonprod containers from the physical view is not a defensible production-only abstraction.
- **MEDIUM — the ladder has the same mutation contradiction as 12-05.** It forbids Compose edits (`.planning/phases/12-self-hosted-observability-stack/12-06-PLAN.md:140`) while requiring cap changes and force recreations (`.planning/phases/12-self-hosted-observability-stack/12-06-PLAN.md:159`), without defining a temporary override.
- **MEDIUM — the PNG verification overclaims provenance.** The plan treats “PNG newer than source” plus renderer `--check` as proof it was not hand-edited (`.planning/phases/12-self-hosted-observability-stack/12-06-PLAN.md:441`). The renderer explicitly says its check only compares dimensions and cannot detect label or content changes that preserve dimensions (`scripts/render-diagrams.sh:30`). File mtime is likewise not provenance.
- **MEDIUM — no minimum safe Caddy cap can be justified if the cardinality workload is unavailable.** Merely recording that limitation is valuable, but it should block closing D-02 rather than convert missing evidence into accepted evidence.

### Suggestions

- Define a concrete distributed-source test with a minimum cardinality. If that cannot be produced safely, keep the todo open or explicitly record that the cap covers TLS/request concurrency but not limiter-key exhaustion.
- Correct the architecture to thirteen containers and depict both nonprod containers or clearly define—and consistently enforce—a narrower diagram scope.
- Use a temporary Compose override for ladder rungs.
- Verify rendered PNG content through a clean regeneration and byte comparison against the committed output; do not use mtime as proof.
- Preserve the partial logging todo handling exactly as designed.

### Risk Assessment

**HIGH.** The plan can close its primary security debt without exercising the threat that created it, while committing an inaccurate physical architecture.

# Cross-plan assessment

Overall risk is **HIGH; revision is required before execution**. The plans have excellent verification instincts, but several checks currently prove the wrong property or encode an incomplete configuration.

The blocking corrections are:

1. Make node_exporter observe the VPS rather than its container.
2. Reopen the Promtail-versus-Alloy decision in light of Promtail’s EOL and Docker socket access.
3. Add a valid Loki schema activation date.
4. Bring cAdvisor mounts into agreement with D-05 and derive real per-container environment labels.
5. Remove raw password interpolation from the Postgres exporter URI and avoid deprecated autodiscovery.
6. Provision fixed Grafana datasource UIDs and add Redpanda’s human-queryable metrics surface.
7. Correct all eleven-container claims to thirteen and establish an aggregate memory budget.
8. Define a reproducible temporary-cap override mechanism for both ladders.
9. Require meaningful source-address cardinality before closing the Caddy todo.
10. Stop treating dimension-only diagram checks as content provenance.

Once those are addressed, the phase decomposition, live checkpoints, no-port enforcement, least-privilege testing, and evidence-oriented runbook work form a strong implementation plan.
