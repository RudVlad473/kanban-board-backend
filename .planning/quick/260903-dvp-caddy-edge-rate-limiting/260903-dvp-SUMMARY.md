---
quick_id: 260903-dvp
slug: caddy-edge-rate-limiting
status: partial
tasks_completed: 6
review_rounds: 3
tasks_total: 7
---

# Caddy edge rate limiting — Summary (Tasks 1-6)

Per-client-IP rate limiting for `/api/signin` and `/api/signup` at the Caddy edge, via a CI-built,
version- and commit-pinned custom image carrying `github.com/mholt/caddy-ratelimit`, plus the
deploy-time reload that makes a Caddyfile change actually take effect (F-1). Task 7 (live
post-merge verification) is a blocking human checkpoint and was intentionally not run.

## What landed

1. `docker/caddy/Dockerfile` — two-stage build, `caddy:2.11.4-builder` → `caddy:2.11.4`, plugin
   pinned to `5625512f24f6f59d6f64fb3aafe5eecff0b286db`. Verified: `caddy version` → `v2.11.4`,
   `caddy list-modules` → `http.handlers.rate_limit` (exact match), `wget` present at
   `/usr/bin/wget`.
2. `Caddyfile` — `rate_limit` inside `{$APP_DOMAIN}` only, keyed on `{remote_host}`: `auth` zone
   (10/5m, `/api/signin[/]`, `/api/signup[/]`) + `general` zone (120/1m, whole host).
3. `scripts/verify-caddy-image-tag.py` + `docker-compose.prod.yml`'s `caddy.image` pinned to
   `rudenkovladimir/kanban-board-caddy:2.11.4-rl5625512f`.
4. `.github/workflows/deploy.yml` — new `build-and-push-caddy-image` job (14 jobs total, up from
   13); `deploy-to-netcup` now pulls `app caddy`, reloads Caddy after `up -d`, and asserts the
   handler is present in the *running* admin-API config.
5. `.github/dependabot.yml` — `docker` ecosystem scoped to `/docker/caddy`.
6. `docs/INFRA_ARCHITECTURE.md` + both `.mmd`/`.png` diagram pairs + `.claude/CLAUDE.md` updated;
   both PNGs re-rendered via `pnpm dlx @mermaid-js/mermaid-cli` and visually confirmed legible.

## Verification output — both directions of the 429 check (local, Task 2)

Against Task 1's image, `{$APP_DOMAIN}=":80"` (production) / `{$APP_DOMAIN_NONPROD}=":81"`
(nonprod), no backend — 502 proves "allowed", 429 proves "denied":

- **Production auth zone:** requests 1-10 → `502`, 11-12 → `429`, with `Retry-After: 297`.
- **Nonprod block, same container/binary:** all 12 requests → `502`. Zero `429`s — proves the
  directive is scoped to the production block, not leaking globally.
- **General zone, clean counter:** 120 × `502`, then 10 × `429` (out of 130 fired).

## Verification output — tag-gate negative test (Task 3)

`scripts/verify-caddy-image-tag.py` against the real files: `invariants OK: computed
tag=2.11.4-rl5625512f compose image=rudenkovladimir/kanban-board-caddy:2.11.4-rl5625512f`.
Then three deliberate mismatches, each reverted after:

- Compose tag → `2.11.4-rlDEADBEEF`: `FAIL: I3 violated: ... does not end in :2.11.4-rl5625512f`
- Runtime `FROM` → `caddy:2.11.3`: `FAIL: I1 violated: builder-stage tag (2.11.4) and
  runtime-stage tag (2.11.3) disagree`
- Plugin pin → `@v0.1.0`: `FAIL: I2 violated: ... is not a 40-character hex commit SHA`

All three failed as expected; both files diffed clean against the pre-edit backup afterward, and
the real check passed again.

## Deviations from plan

1. **[Rule 1 — bug]** The scenario diagram's corrected `cleanup-old-images` note originally still
   contained the literal word "unauthorized" (in a historical, quoted sense: *"was rejected
   'unauthorized' until [2026-08-17]"*). The plan's own automated verify does
   `! grep -q 'unauthorized' docs/diagrams/infra-delivery-scenario.mmd` — a literal string check
   with no exception for quoted/historical use. Reworded to state the same fact (Hub API v2
   rejected Basic auth on deletes with a 401 until the 2026-08-17 fix) without that token, then
   re-rendered the PNG. No commit landed with the failing wording.
2. **[Rule 3 — blocking]** `pnpm dlx @mermaid-js/mermaid-cli` failed on first run: no
   `chrome-headless-shell` was cached locally. Installed it by hand into
   `~/.cache/puppeteer/chrome-headless-shell/linux-152.0.7977.54/chrome-headless-shell-linux64/`
   (matching `@puppeteer/browsers`' own `<platform>-<buildId>/chrome-headless-shell-<folder>/`
   layout) via `curl` against the official Chrome-for-Testing JSON API, per this machine's
   documented workaround for this exact failure mode. Both diagrams then rendered normally; no
   further tooling was installed.
3. **[Rule 1 — bug]** Mermaid's sequence-diagram parser treats a bare `;` in message text as a
   statement separator; the first version of the `Comp--xCaddy` note used one and failed to
   parse (`Expecting 'NEWLINE' ... got 'INVALID'`). Replaced with a comma; no semantic change.

## Facts verified before writing the two corrected claims (per dispatch instructions)

- `cleanup-old-images`' fix is real: `deploy.yml`'s own comment (`BUG FIXED (todo
  2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md)`) documents the JWT
  token-exchange fix landing 2026-08-16 and the pagination fix 2026-08-17, both present in the
  current step.
- Job count: `deploy.yml` held 13 jobs before this change (`yaml.safe_load` count, confirmed),
  now 14 after adding `build-and-push-caddy-image` — not seven, as `INFRA_ARCHITECTURE.md`
  claimed.

Both corrections were genuine (not "turned out to be true after all"), so both were applied as
instructed.

## Could not verify / left for Task 7

- Whether GitHub actually accepts `deploy.yml`'s syntax — confirmed only via local
  `yaml.safe_load` + the plan's own structural Python assertion (14 jobs, correct `needs:`
  wiring, script contains the expected substrings). A GitHub Actions schema-level syntax error
  surfaces only on push, which is out of scope here.
- Whether `rudenkovladimir/kanban-board-caddy` exists on Docker Hub and is public — this plan's
  own `user_setup` precondition, not something this dispatch creates or checks.
- The three-reviewer (Claude+Gemini+Codex) security review the plan's `<verification>` section
  calls for "before push" — not run, since push is out of scope for this dispatch.
- Task 7 itself: live post-merge verification against the real VPS (module in the running edge,
  reload took effect, production/nonprod asymmetry, two-client independence, container-survival
  across a later app-only deploy) — a `gate="blocking-human"` checkpoint, per instructions not
  attempted or simulated.

## Verified, not just claimed

- `./gradlew spotlessCheck test` — `BUILD SUCCESSFUL` (7m55s, full suite including
  Testcontainers-backed integration tests, not just the pre-commit hook's fast subset).
- `.githooks/pre-commit` (gitleaks + spotlessCheck + fastTest) passed on all 6 commits with no
  findings — the plugin's 40-hex SHA never tripped it, so no `.gitleaks.toml` entry was needed.
- All local Docker test artifacts removed: `rl-test` container and `kanban-caddy:local` image
  tag both gone; `docker images kanban-caddy` returns empty.

## Self-Check: PASSED

All 13 files listed in this plan's `files_modified` (plus this SUMMARY) confirmed present on disk;
all 6 task commit hashes (`77ece35`, `a514c47`, `b1fcb40`, `ebab4a2`, `1427185`, `5485b30`)
confirmed present in `git log`.

---

# Review rounds (added after Tasks 1-6)

Three rounds, each running Claude / Gemini 3.1 Pro / Codex terra in parallel from one shared brief
(`REVIEW-BRIEF.md`, `-R2`, `-R3`). 11 findings acted on across 10 further commits, taking the branch
from `5b9487d` to 17 commits ahead of `main`. Every claim was re-verified locally before acting.

## Round 1 — 5 findings

| # | Sev | What | Fix |
|---|-----|------|-----|
| F1 | HIGH | `deploy.yml`'s post-reload readback used `localhost:2019`; Caddy's admin API is IPv4-only and the image's BusyBox `wget` treats the `::1` refusal as terminal. Failed 5/5 — and *after* `up -d`, so it would redden every deploy and let `cleanup-unused-image` delete the app manifest then running on the VM | `127.0.0.1` |
| F2 | MED | `/api/signin;x=1` evaded the exact-literal `path` matcher | `path /api/signin* /api/signup*` |
| F3 | MED | Tag gate matched a commented-out `--with`; docstring overclaimed the tag as content-derived | `strip_comments()`; scope corrected |
| F4 | MED | Gate only ran on push-to-`main` while two comments claimed drift was unmergeable | new `invariant-checks.yml` (push + PR) |
| F5 | — | auth budget 10/5m too tight for a shared NAT (human decision) | raised to 20/5m |

## Round 2 — 5 findings

| # | Sev | What | Fix |
|---|-----|------|-----|
| R2-1 | HIGH | The new Artillery harness could never pass: the probe password failed `@Password` (no uppercase, no digit), so every allowed request was 400 before authentication. Both halves unconditionally red, the nonprod half printing a false "limiter is not scoped" alarm | format-valid literal + a diagnostic naming the real cause |
| R2-2 | LOW | The comment justifying F2 claimed Spring routes `/api/signin;x=1` to the handler. Verified false — 401 at the entry point, handler never reached | rationale corrected; matcher kept as defence in depth |
| R2-4 | LOW | `invariant-checks.yml` claimed the gate was "stdlib-only"; it imports PyYAML | installs it explicitly |
| R2-5 | LOW | Doc said "every push" | corrected to PRs + pushes to `main`, with `paths-ignore` |
| new | MED | `endswith(":<tag>")` accepted `attacker/kanban-board-caddy:<correct tag>`; only `deploy.yml` compared repositories, and that runs post-merge | new invariant I4 in the script, so every caller inherits it |

**The zone-composition finding.** Two reviewers independently reported that a request rejected 429
by the auth zone still consumed a general-zone token; a third asserted the opposite. Measured:
30 signins (20 allowed + 10 rejected) left exactly 90 of 120 general events, and 130 signins made
`GET /api/boards` return 429 — one address locked off the entire API for the rest of the minute,
achievable by a single stuck client retrying signin. Resolved by a human decision to partition:
the general zone now excludes the auth paths.

## Round 3 — scoped to the partition, 2 findings

| # | Sev | What | Outcome |
|---|-----|------|---------|
| R3-1 | LOW | `OPTIONS *` matches neither zone and is unlimited (121 raw requests → 121x200) | Recorded, not fixed — Go's `net/http` answers it from `globalOptionsHandler` before Caddy's route chain, so no configuration here can reach it. Pre-existing; costs a TLS handshake and a zero-byte 200 |
| R3-2 | MED | The committed nonprod hostname did not exist (segments transposed), so the negative control could never run against the real deployment — and it fails as a 0 429-count, indistinguishable from a pass | corrected in 4 places; added a DNS preflight that fails loudly |

Round 3 verified every number in the partition commit in both directions, and established the
partition is sound *structurally* rather than by sampling: the adapted JSON shows `general.match`
is the exact boolean complement of `auth.match` over the same normalized path, so every request
reaching the handler matches exactly one zone — zone ordering cannot matter and double-counting is
impossible. 33 path forms were probed 135x each; only `OPTIONS *` escaped both zones.

## Measured behaviour as shipped

| Probe | Result |
|-------|--------|
| `/api/signin`, `/api/signup` (and prefix/parameter/encoded forms) | 20 allowed per 5 min, then 429 with `Retry-After` |
| Everything else on the production hostname | 120 per min, then 429 |
| Auth spam's effect on the general budget | none — 30 signins leave general at a full 120 |
| Ceiling for one address | 140 req/min (120 general + 20 auth) |
| Nonprod hostname, same container and binary | unthrottled — 200 signins, zero 429s |
| Prod throttled → nonprod from the same client | unaffected; nonprod has no limiter at all |

## Method note

The single highest-severity finding in each round came from the reviewer that could **execute**;
reviewers restricted to reading rated the same artefacts "FINE" twice. Codex was re-run mid-task
with progressively fuller sandboxes (`read-only` → `workspace-write` → `danger-full-access`) and
found strictly more each time — including R3-2, which required network access. Recorded in
`~/.claude/TOOLING_PREFERENCES.md`.

## Still open

- **Task 7** — live post-deploy verification against the real VPS. Blocking human checkpoint.
- `handle_errors 429` so a 429 carries the RFC 7807 envelope the rest of the API returns.
- `mem_limit` on the `caddy` service, once measured rather than guessed.
- Pre-existing and out of scope: `docs/INFRA_ARCHITECTURE.md` and the physical diagram still
  describe Neon; the stack migrated to self-hosted `postgres:16` in Phase 11.
