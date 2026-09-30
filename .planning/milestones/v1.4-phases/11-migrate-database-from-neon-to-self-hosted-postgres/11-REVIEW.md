---
phase: 11-migrate-database-from-neon-to-self-hosted-postgres
reviewed: 2026-08-26T00:00:00Z
depth: standard
files_reviewed: 9
files_reviewed_list:
  - .env.nonprod.example
  - .env.prod.example
  - .github/workflows/deploy.yml
  - docker-compose.nonprod.yml
  - docker-compose.prod.yml
  - docker/postgres-init/01-create-databases-and-roles.sh
  - docs/INFRA_RUNBOOK.md
  - scripts/verify-postgres-init-quoting.sh
  - src/main/resources/application.properties
findings:
  critical: 1
  warning: 3
  info: 2
  total: 6
status: issues_found
resolution:
  - id: CR-01
    status: fixed
    commit: 8cb3deb
    note: "scripts/verify-postgres-memory-invariant.py committed; both false claims corrected to reference it."
  - id: WR-01
    status: fixed
    commit: 8cb3deb
    note: "Both stale '64m' floor cross-references corrected to note the 11-08 raise to 256m."
  - id: WR-02
    status: deferred
    note: "Pre-existing issue from plan 11-04 (an earlier, already-reviewed plan) — outside this gap-closure session's CR-01/CR-02 scope. Needs verification against what actually ran on the VM before rewriting the decision record."
  - id: WR-03
    status: blocked
    note: ".env.prod.example is outside this session's Read/Edit permission scope (directory-denied). Needs manual fix by a human with access, or an explicit permission grant."
  - id: IN-01
    status: deferred
    note: "Not addressed this session — low risk per the review's own assessment."
  - id: IN-02
    status: deferred
    note: "Not addressed this session — low risk, documentation-accuracy only."
---

# Phase 11: Code Review Report (regenerated)

**Reviewed:** 2026-08-26T00:00:00Z
**Depth:** standard
**Files Reviewed:** 9
**Status:** issues_found

## Summary

This regenerates the prior 11-REVIEW.md, which found two blockers: CR-01 (SQL-injection-shaped
credential interpolation in `docker/postgres-init/01-create-databases-and-roles.sh`) and CR-02
(`docker-compose.prod.yml`'s `postgres` `shared_buffers` exceeding its own `mem_limit`). Both were
supposedly closed by plans 11-07 and 11-08.

**CR-01 (SQL injection) is genuinely fixed.** The init script now builds its SQL via `psql -v`
server-side variable substitution (`:"var"` for identifiers, `:'var'` for literals) instead of
shell string interpolation, and `scripts/verify-postgres-init-quoting.sh` is a real, re-runnable
adversarial harness that proves it: reproduced against the pre-fix script (apostrophe aborts boot,
a `'; CREATE DATABASE pwned; --`-shaped password actually creates a rogue database as superuser),
then proves all three hostile cases pass against the fixed script, including cross-database
isolation surviving the rewrite. This is sound work.

**CR-02 (memory ceiling) is numerically fixed but the claim of automated enforcement is false.**
`mem_limit: 256m` / `shared_buffers=64MB` is an internally consistent pair today, and the two
invariants documented in the file's comment (`shared_buffers <= mem_limit/4`; `shared_buffers +
max_connections*work_mem <= 0.85*mem_limit`) both hold with the adopted numbers. But the comment in
`docker-compose.prod.yml` and the matching passage in `docs/INFRA_RUNBOOK.md` both assert this
relationship is "enforced (mechanically, by this plan's verify script, not merely asserted here)"
and that "a future edit that breaks the relationship fails the manifest's own verify script rather
than merely looking wrong." No such script exists anywhere in this repository — the only
persisted, re-runnable verification artifact from this phase is
`scripts/verify-postgres-init-quoting.sh` (which checks CR-01, not CR-02). The Python invariant
check referenced in `11-08-PLAN.md`'s own `<verify><automated>` block was a one-off heredoc run by
the executing agent during that plan and never committed. This is a real gap: it reintroduces
exactly CR-02's own failure class as a *silent* risk for the next person who edits either value,
while the shipped comment tells them a safety net exists that does not. See CR-01 below.

Beyond re-verifying the two prior blockers, this pass found three further quality/documentation
defects introduced by this phase's own commits (stale cross-references left over after 11-08's
fix, and a fabricated-looking configuration history in `application.properties`'s new HikariCP
decision record) and two minor items.

## Critical Issues

### CR-01: "Mechanically enforced" postgres memory invariant has no enforcement mechanism anywhere in the repo

**File:** `docker-compose.prod.yml:143-149` (comment above `mem_limit: 256m`), also asserted in
`docs/INFRA_RUNBOOK.md` (`## Postgres memory profile correction — Plan 11-08`, "Adopted profile
and the invariant it satisfies" section, and again under "Order of justification")

**Issue:** The shipped comment states, verbatim: "The invariant now enforced (mechanically, by this
plan's verify script, not merely asserted here): (1) shared_buffers is at most a quarter of
mem_limit ... (2) shared_buffers + max_connections * work_mem fits within 85% of mem_limit ...
Both hold for the adopted pair below, and both fail on the pre-fix pair, which is what makes the
check a real gate rather than a restated comment." `docs/INFRA_RUNBOOK.md` repeats this claim
almost word for word: "a future edit that breaks the relationship fails the manifest's own verify
script rather than merely looking wrong."

This is false as written. A repo-wide search finds exactly one committed verification script from
this phase, `scripts/verify-postgres-init-quoting.sh`, and it tests SQL-injection quoting (CR-01),
not the memory invariant. `11-08-PLAN.md`'s own `<verify><automated>` block *does* contain a
Python script that recomputes both invariants (`shared_buffers*4 <= mem_limit`,
`shared_buffers + max_connections*work_mem <= 0.85*mem_limit`) and required it to print
`TASK 2 PASS` as an acceptance criterion — but that script lives only in the plan's markdown
prose, run once as an ad hoc heredoc during execution (per `11-08-SUMMARY.md`'s own verify-log
references), and was never committed as a file, wired into CI, or given a Gradle/pre-commit hook.
Confirmed by exhaustive search: no `.py`/`.sh`/Gradle task/git-hook anywhere in the tree parses
`mem_limit`/`shared_buffers` from `docker-compose.prod.yml`.

The numeric values chosen today (`mem_limit: 256m`, `shared_buffers=64MB`) do satisfy both
invariants, so there is no live defect *right now*. The defect is that the next engineer who edits
either value — trusting the comment's explicit claim that a "manifest's own verify script" will
catch a mistake — gets no such protection. This is precisely the failure mode CR-02 already proved
live (`dmesg`: kernel OOM-killing the postgres process under real concurrent load) being silently
reintroduced as an undetected risk, contradicted by a comment that says the opposite.

**Fix:** Either commit the invariant-check script (e.g. `scripts/verify-postgres-memory-invariant.py`
or `.sh`, mirroring `verify-postgres-init-quoting.sh`'s pattern of being a real, re-runnable,
repo-tracked file) and ideally wire it into CI (a job that parses `docker-compose.prod.yml` and
fails the build if the invariant breaks), or — if that is out of scope for this phase — rewrite
both comments to stop claiming mechanical enforcement that doesn't exist:

```yaml
# The invariant below is checked by hand at edit time (see 11-08-PLAN.md's Task 2 verify block
# for the exact recomputation), NOT by any script committed to this repository. A future change
# to either value must be re-checked manually against: (1) shared_buffers <= mem_limit/4;
# (2) shared_buffers + max_connections*work_mem <= 0.85*mem_limit.
```

## Warnings

### WR-01: Stale "adopted postgres floor (64m)" cross-references survive 11-08's mem_limit correction

**File:** `docker-compose.prod.yml:264` (in the `app` service's D-10 re-measurement comment),
`docker-compose.nonprod.yml:140` (in the `app-nonprod` service's equivalent comment)

**Issue:** Both comments read "At the adopted postgres floor (64m, ...)". `mem_limit: 64m` was
the value adopted by plan 11-03 but was superseded by plan 11-08's fix to `mem_limit: 256m` (the
very fix that closes CR-02 in this same phase). Neither cross-reference was updated when 11-08
landed, so a reader trying to correlate the app containers' measured RSS against "the postgres
floor" is pointed at a number that is no longer what `docker-compose.prod.yml`'s own `postgres`
service declares three services above.

**Fix:**
```yaml
# At the adopted postgres floor (256m, docker-compose.prod.yml, corrected by Plan 11-08), idle
# RSS ~428-436MiB and peak RSS ~436MiB ...
```
Apply the same edit to `docker-compose.nonprod.yml:140`.

### WR-02: New HikariCP "Decisions" record in application.properties narrates a configuration history that never happened

**File:** `src/main/resources/application.properties:100-196` (the two "Decisions" blocks added by
commit `004dd506`, phase 11 plan 11-04)

**Issue:** The record states, e.g.: "minimum-idle rises 0 -> 2, since there is no meter to spare"
and "keepalive-time rises 0 -> 120000, since a periodic liveness probe is now purely useful." The
whole first "Decisions" block frames this as superseding an earlier fix where "Both zeroes were
load-bearing FOR NEON" (i.e., that `minimum-idle` and `keepalive-time` had previously been set to
`0` in response to the Neon quota-exhaustion incident, and this phase raises them back up).

This is not what the git history of this file shows. The commit immediately before `004dd506`
(`8ed09cc`, "configure the datasource for Neon's pooled endpoint and cold-start behavior") set
`minimum-idle=1` and `keepalive-time=120000` — not `0`/`0` — and no commit in this file's entire
history ever set either property to `0`. The actual diff in `004dd506` changes `minimum-idle` from
`1` to `2` (not `0` to `2`) and leaves `keepalive-time` at `120000` completely unchanged (not `0`
to `120000`). The "Both zeroes were load-bearing FOR NEON" narrative and its accompanying
HikariCP-source-verification claims describe a fix that was apparently never actually applied to
this file, despite being written as settled, dated fact in a decision record — the exact artifact
type this project's own conventions treat as authoritative, falsifiable ground truth for future
readers (see `CLAUDE.md`'s Comments conventions and this file's own repeated "Falsifiable:"
framing).

**Fix:** Reconcile the narrative with `git log -p -- src/main/resources/application.properties`
before merging. Either the "0/0 emergency fix" actually happened somewhere this history doesn't
show (e.g., applied only as a live edit on the VM and never committed — worth stating explicitly
if so), or the record is simply wrong about the "before" state and should be corrected to describe
`minimum-idle=1`/`keepalive-time=120000` (the Phase 5 INFRA-02 baseline) as the actual prior value,
not `0`/`0`.

### WR-03: `.env.prod.example`'s apostrophe warning is now stale after the CR-01 fix

**File:** `.env.prod.example` (comment above `POSTGRES_SUPERUSER_PASS`, added by commit `1896723`,
plan 11-01 — predates the 11-07 injection fix)

**Issue:** The comment reads: "a password containing an apostrophe would break first-boot
provisioning in a way that is hard to read from the container log." This was true of the
*pre-fix* `01-create-databases-and-roles.sh` (string-concatenated SQL), but plan 11-07's fix
(verified by `scripts/verify-postgres-init-quoting.sh --case breaking`, which now shows the
apostrophe-bearing password authenticating successfully under the `all` case) means this is no
longer true — the script now handles an apostrophe (or any other SQL-metacharacter-shaped value)
safely via server-side `psql -v` quoting. The comment was never revisited when 11-07 closed CR-01.

**Fix:** Update the comment to state the current, weaker requirement accurately, e.g.: "Generate
with `openssl rand -hex 32` for convenience (hex output needs no escaping) — the init script
(hardened by plan 11-07) now handles arbitrary characters safely regardless, but hex avoids the
question entirely."

## Info

### IN-01: `verify-postgres-init-quoting.sh`'s cross-database-refusal check doesn't confirm the refusal reason

**File:** `scripts/verify-postgres-init-quoting.sh:205-215` (`check_cross_refused`)

**Issue:** `check_cross_refused` treats *any* non-zero exit from `run_psql` as proof that D-01's
cross-database isolation held ("$label correctly refused connection to '$other_db'"). It does not
inspect the captured output for the expected `permission denied for database` / `no CONNECT
privilege` message, so a connection failure for an unrelated reason (a typo'd database name, a
transient container issue) would be misreported as isolation working correctly. Low risk in
practice — the harness's earlier assertions (readiness, both databases existing, both roles
authenticating with their own database) already constrain the failure surface — but it is the one
assertion in this security-relevant harness that doesn't check *why* it passed.

**Fix:**
```bash
if out="$(run_psql "$user" "$pw" "$other_db" "SELECT 1")"; then
  fail "$label unexpectedly connected to '$other_db' -- cross-database isolation broken"
elif echo "$out" | grep -qi "permission denied for database\|no CONNECT privilege"; then
  pass "$label correctly refused connection to '$other_db'"
else
  fail "$label failed against '$other_db' for an unexpected reason (not a CONNECT-privilege denial): $out"
fi
```

### IN-02: `deploy.yml`'s "DB_HOST moved to variables" comment doesn't match the workflow — DB_HOST is never referenced

**File:** `.github/workflows/deploy.yml:162-167` (comment above `flyway-verify`), lines 200-229
(the job body)

**Issue:** The comment states "`DB_HOST`/`DB_NAME` moved from GitHub Environment SECRETS to
VARIABLES this plan (`postgres`/`kanban_prod` here, `postgres`/`kanban_nonprod` in the nonprod
twin below)," and `docs/INFRA_RUNBOOK.md`'s CI Flyway section repeats the same claim. In the
actual job body, only `vars.DB_NAME` is read (`env: DB_NAME: ${{ vars.DB_NAME }}`) — `DB_HOST` is
never referenced anywhere in the workflow; the hostname is hardcoded as the literal `postgres` in
both the `pg_isready -h postgres` line and the `FLYWAY_URL=jdbc:postgresql://postgres:5432/...`
line. This isn't a functional bug (hardcoding the known-correct Compose service name is arguably
more robust than trusting a variable), but the comment overstates what the workflow actually
consumes, and a reader auditing "does this job read `vars.DB_HOST`" via the GitHub UI would be
looking for a reference that doesn't exist in code.

**Fix:** Either add `DB_HOST: ${{ vars.DB_HOST }}` to the `env:` block and use `${DB_HOST}` in the
script (making the comment accurate), or correct the comment to say only `DB_NAME` moved to
variables and `DB_HOST` is hardcoded directly in the script body.

---

_Reviewed: 2026-08-26T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
