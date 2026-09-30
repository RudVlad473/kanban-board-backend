---
phase: 13-introduce-kubernetes
reviewed: 2026-09-30T00:00:00Z
depth: standard
files_reviewed: 14
files_reviewed_list:
  - .github/workflows/deploy.yml
  - .github/workflows/invariant-checks.yml
  - .github/dependabot.yml
  - scripts/verify-k8s-invariants.py
  - scripts/verify-k8s-invariants-selftest.py
  - scripts/verify-public-dashboards.py
  - scripts/verify-public-dashboards-selftest.py
  - scripts/verify-postgres-init-quoting.sh
  - infra/vm/README.md
  - .claude/CLAUDE.md
  - docs/INFRA_ARCHITECTURE.md
  - docs/INFRA_RUNBOOK.md
  - docs/LOCAL_DEV.md
  - src/main/resources/application.properties
findings:
  critical: 0
  warning: 6
  info: 4
  total: 10
status: issues_found
---

# Phase 13 (plan 13-10): Code Review Report

**Reviewed:** 2026-09-30
**Depth:** standard (with cross-file reference tracing for everything the deletions removed)
**Scope:** `git diff 7196dab~1 15d6b3f` in worktree `finish-13`
**Status:** issues_found

## Summary

The mechanical part of the decommission is sound. Confirmed by running or reading, not assumed:

- `deploy.yml`: every `needs:` target exists (`setup`, `run-tests`, `build-and-push-docker-image`); no
  `caddy` / `compose` / `base_image_name_caddy` reference remains in `.github/` except comments.
- `invariant-checks.yml`: no `needs:` at all, three jobs remain, and every script they call exists.
- `dependabot.yml`: the removed `/docker/caddy` entry was the only path-bearing entry; the two remaining
  ecosystems (`gradle`, `github-actions`, both `/`) are valid.
- `python3 scripts/verify-k8s-invariants-selftest.py`, `scripts/verify-k8s-invariants.py --no-provisional`,
  `scripts/verify-public-dashboards-selftest.py` and `scripts/verify-public-dashboards.py` all exit OK on
  the worktree. `rg` over `scripts/`, `.github/`, `.githooks/` finds no call to a deleted script.
- I9 removal leaves no stray caller: only the docstring and the runbook mention it.

No blocker found. What is left dangling is documentation, one source-code comment that names an enforced
coupling whose enforcer is gone, one dead k8s root, and one gate-record sentence that contradicts the
record around it. I did not run `verify-postgres-init-quoting.sh` (needs Docker); its "9 of 9" claim is
unverified by me.

## Warnings

### WR-01: Gate record says "No threshold was relaxed" for a gate whose D-08.1(b) floor was cut from 90 to 10

**File:** `docs/INFRA_RUNBOOK.md:832` (contradicted by `:794-797` and `:821-825`)
**Issue:** The 2026-09-30 PASS record opens its evidence with "No threshold was relaxed; D-08.1(b) uses
the floor amended on 2026-09-29". The 2026-09-29 record said the threshold was 90 and "unchanged here";
the follow-up then amends it to "count >= 10" after the FAIL. Going from 90 to 10 is a relaxation, and the
sentence that authorises the PASS verdict denies it. This is the record that clears an irreversible
deletion (D-04), so a reader who trusts only the second sentence is misled about why D-08.1b passed
(15 runs would have failed the original 90 by a wide margin). The amendment is disclosed elsewhere, so
this is an accuracy defect in the summary claim, not concealment. It is also a weakened gate that took
effect between a FAIL and the re-evaluation.
**Confirmed by:** reading lines 754, 794-797, 821-825, 832, 845 side by side.
**Fix:** Replace line 832's sentence with: "D-08.3 thresholds were not relaxed. D-08.1(b) was relaxed from
count >= 90 to count >= 10 by operator decision on 2026-09-29 (follow-up above); it passes at 15, and
would have failed the original threshold."

### WR-02: `application.properties` names a coupling to a deleted file, and the check that enforced it is gone

**File:** `src/main/resources/application.properties:67` (also `:9`, and
`src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java:100`)
**Issue:** Line 67 says "A change to either this value or docker-compose.prod.yml's max_connections is
now visibly coupled to the other by name." That file no longer exists. `max_connections=25` now lives at
`k8s/data/postgres/postgres.yaml:92`. Line 9 explains graceful shutdown by
"docker-compose.prod.yml's `restart: unless-stopped`", and `RandFlakeGenerator.java:100` justifies a
single-instance assumption by "(docker-compose.prod.yml)". The single-instance justification is the
dangerous one: the k8s app Deployment is now the artifact that must stay at one replica, and the comment
sends the next reader to a file that cannot answer. Only the Postgres memory inequalities were ported to
I6; nothing ties pool size to `max_connections`, so this comment was the only link.
**Confirmed by:** `rg -n "docker-compose\.prod\.yml"` over `src/` returns these three lines;
`ls` shows the file deleted; `rg -n "max_connections" k8s/` shows the new location.
**Fix:** Point the three comments at `k8s/data/postgres/postgres.yaml` (`max_connections`) and at the app
Deployment's `replicas`/`strategy` in `k8s/base/app/app.yaml`, and for the shutdown comment at the pod's
`terminationGracePeriodSeconds`.

### WR-03: `docs/LOCAL_DEV.md` now states a false deployment fact as current

**File:** `docs/LOCAL_DEV.md:10-25`
**Issue:** The "Local development only" section says production deploys "via `docker compose -f
docker-compose.prod.yml up -d`" and that `docker-compose.prod.yml` "adds `caddy`". Both files are deleted
and production is k3s + Flux. It also still says "Neon is the production database" (stale since Phase 11)
and cites `KAFKA-V2-01` as resolved "by the Redpanda service in `docker-compose.prod.yml`". The plan's
own "docs" edit list (runbook Part A) touched `INFRA_*` and `CLAUDE.md` but not this file, although it is
the one document the local-dev section of `CLAUDE.md` points a newcomer to.
**Confirmed by:** `rg -n "docker-compose\.(prod|nonprod)\.yml|Caddy|caddy" docs/LOCAL_DEV.md` (5 hits);
`git diff 7196dab~1 15d6b3f --stat` shows `LOCAL_DEV.md` was not edited.
**Fix:** Rewrite the paragraph to: `docker-compose.yml` is local dev only; production and nonprod run on
k3s (`k8s/`, `docs/INFRA_ARCHITECTURE.md`). Drop the Neon/caddy sentences.

### WR-04: `docs/INFRA_RUNBOOK.md` still presents the retired DOCKER-USER layer and the deleted compose-ports gate as live, with no banner on the sections

**File:** `docs/INFRA_RUNBOOK.md:53-59`, `:66-73`, `:129-210`
**Issue:** "Firewall — three layers" and "Layer 3: `DOCKER-USER`" are written in the present tense
("`scripts/verify-compose-ports.py` ... fails CI on a pull request that adds a `ports:` key", "source of
truth: `infra/vm/docker-user-firewall.sh`"). Both artifacts are deleted in this change. The only
disclaimer is the last paragraph of the file (line ~1064: "read those sections as history"), which nobody
reads before acting on the firewall section. The section's own preamble ("UPDATED 2026-09-06 ... this
section changes with it rather than being left describing a chain that no longer matches reality") states
the standard this change then fails to meet. A reader (or agent) following Layer 3 would try to reinstall
a script that no longer exists.
**Confirmed by:** `sed -n 53,59p`, `sed -n 66,73p`, and the `git diff` of the runbook, whose only
removals are two file-list lines (three deletions total); no heading was marked retired.
**Fix:** Add a one-line banner under `## Firewall — three layers` and under `### Layer 3`:
"RETIRED 2026-09-30 (Plan 13-10): Docker Engine and `docker-user-firewall.*` are gone; `KANBAN-INGRESS`
(section 'Edge hardening on k3s') is the live filter. Kept as history."

### WR-05: `k8s/data/postgres-bridge` is a dead, still-validated root that points at a Docker bridge that no longer exists

**File:** `k8s/data/postgres-bridge/postgres-bridge.yaml:36-44`, `k8s/data/postgres-bridge/kustomization.yaml`
**Issue:** The EndpointSlice targets `172.17.0.1:5432`, the docker0 gateway of the Compose Postgres,
"(docker-compose.prod.yml)". Compose and Docker are gone, and `k8s/flux-system/data.yaml:14` already
points Flux at `./k8s/data/postgres`. The bridge is not deployed but is still a kustomization root, so
`verify-k8s-manifests.sh` and I1-I8/I10 in `verify-k8s-invariants.py` keep rendering it (12 roots checked).
It also pins the same `clusterIP: 10.43.54.32` as the live Postgres Service, so applying both (e.g. a
future edit to `data.yaml`'s `path`) is a guaranteed conflict on the most critical Service in the cluster.
13-02 marked the interim as removed in 13-10 (the deleted `verify-compose-ports.py` docstring says
"removed in 13-10" of the 172.17.0.1 exception), so this is the same removal left half-done.
**Confirmed by:** `ls k8s/data`; `sed -n 1,40p k8s/flux-system/data.yaml`; `rg -n postgres-bridge k8s`
shows no live reference; `verify-k8s-invariants.py` reports 12 roots.
**Fix:** Delete `k8s/data/postgres-bridge/` and its mentions in `k8s/platform/namespaces.yaml:3` and
`k8s/data/postgres/postgres.yaml:6`, or record in the runbook why it is retained.

### WR-06: Nothing now stops a `ports:` publish or a new Compose deployment file from re-entering the repo

**File:** `.github/workflows/invariant-checks.yml:46-63` (deleted job `compose-published-ports`);
`scripts/verify-k8s-invariants.py` (scope is `k8s/` only)
**Issue:** The old gate's I7 forced every `docker-compose*.yml` at the repo root to be either gated or
explicitly excluded, so a new Compose file (e.g. someone resurrecting `docker-compose.prod.yml` from
history, or adding `docker-compose.staging.yml` with `ports: "5432:5432"`) failed CI. After 13-10 the
local-dev `docker-compose.yml` (which publishes 5433/9092/8081) is the only survivor and no gate looks at
any Compose file. This is a deliberate trade, but it is a genuine weakening of the exposure gate that the
runbook does not call out: the decommission record lists what was deleted, not that the "no unreviewed
published port" guarantee no longer covers non-`k8s/` runtimes. Low likelihood, high impact (a public
Postgres), and the Netcup Cloud Firewall that would catch it is documented as outside version control.
**Confirmed by:** `git show 7196dab~1:scripts/verify-compose-ports.py` SCOPE/I7 text versus the current
`verify-k8s-invariants.py`, which globs only `k8s/**`.
**Fix:** Either add a tiny guard to `verify-k8s-invariants.py` that fails when any `docker-compose*.yml`
other than `docker-compose.yml` exists at the repo root, or state the accepted gap in the decommission
record's "what is NOT protected any more".

## Info

### IN-01: Selftest and gate banners still say "I1-I10" although I9 no longer exists

**File:** `scripts/verify-k8s-invariants-selftest.py:323`, `scripts/verify-k8s-invariants.py:63-64`
**Issue:** The selftest prints "selftest OK -- I1-I10 each fire on an engineered violation", which is now
false for I9 (deliberately retired, number kept). The renumbering decision (keep I10) is sound and
documented in the gate docstring, but the success line overclaims. No FAIL-line consumer is broken.
**Confirmed by:** ran the selftest; output contains the string.
**Fix:** "I1-I8 and I10 each fire (I9 retired in 13-10)".

### IN-02: Docstring edits left over-long lines and one sentence fused mid-line

**File:** `scripts/verify-public-dashboards.py:6, 132`, `scripts/verify-k8s-invariants-selftest.py:4`,
`scripts/verify-k8s-invariants.py:20`
**Issue:** Search-and-replace of the removed Compose references reflowed nothing: several comment lines
run 120-190 columns (e.g. "...No GF_INSTALL_PLUGINS is set in the HelmRelease, so nothing widens this set
at run time. If an external panel plugin is ever installed, add it here WITH the install mechanism" on one
line). Cosmetic, but the rest of these files wrap at ~100.
**Fix:** Re-wrap.

### IN-03: Env example files and Compose-era provenance comments now cite deleted files

**File:** `.env.prod.example:1`, `.env.nonprod.example`, `scripts/loadtest/rate-limit-prod.yml:5`,
`scripts/loadtest/rate-limit-nonprod.yml:4`, `k8s/monitoring/configs/datasources.yaml:6`,
`k8s/monitoring/controllers/loki.yaml:2`, `k8s/base/**`, `k8s/overlays/**` comments
**Issue:** `.env.prod.example` still opens "Example environment file for docker-compose.prod.yml" and
carries `IMAGE_TAG`, `APP_DOMAIN`, `APP_DOMAIN_NONPROD`, `APP_DOMAIN_MONITORING`, which the decommission
record itself says are now unused. The load-test YAMLs still explain their assertions by the Caddyfile
that no longer exists. The k8s manifests cite the deleted `docker/**` files as "carried verbatim" sources,
which can no longer be diffed (history only). None of it affects behavior.
**Confirmed by:** `rg -n` over each file.
**Fix:** Drop the unused keys and retitle the env examples as the Secret inventory the runbook says they
are; leave `k8s/` provenance comments but add "(deleted in 13-10; see git history)".

### IN-04: `docs/learning/*` links to deleted files are now broken (about 120 references)

**File:** `docs/learning/10-infrastructure-and-deployment.md` (52), `docs/learning/11-observability.md` (35),
`docs/learning/09-build-quality-and-ci.md` (21), `docs/learning/06-security-and-sessions.md` (11), others
**Issue:** These study notes link with line anchors to `docker-compose.prod.yml`, `Caddyfile`,
`docker/**`, and describe `verify-compose-ports.py` etc. as running in CI. No link checker runs in CI,
so nothing fails, but the links 404 on GitHub. They are written as dated learning material, so rewriting is
not warranted; a banner is.
**Confirmed by:** `rg -c` counts over `docs/` excluding `raw/`, `wiki/`, `history/`, `incidents/`.
**Fix:** Add a top-of-file note in each: "Describes the Compose/Caddy runtime, removed in 13-10; linked
files exist only in git history at commit 7196dab~1 and earlier."

---

_Reviewed: 2026-09-30_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
