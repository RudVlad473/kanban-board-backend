---
quick_id: 260903-dvp
slug: caddy-edge-rate-limiting
status: partial
tasks_completed: 6
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
