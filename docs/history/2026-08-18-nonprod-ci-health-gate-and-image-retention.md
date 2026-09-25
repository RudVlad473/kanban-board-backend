# Nonprod CI health gate and image retention — Plan 09-02 (2026-08-18)

Expands the tracer path Plan 09-01 built with the two behaviours that make a nonprod deploy
trustworthy rather than merely wired: a bounded health gate that fails the workflow when nonprod
does not come up (CI-04), and a per-repository image-retention pair that cannot reach production's
tags (CI-03). Task 1 (removing the last unscoped repository-level deploy secrets, CI-02) is
recorded separately above, beside the original "Repository secret inventory" table it supersedes.

## Final `deploy.yml` job graph after this plan

```
setup ─┬─> run-tests ─> build-and-push-docker-image ─┬─> flyway-verify ────────> deploy-to-netcup ─┬─> cleanup-old-images
       │                                             │                                             └─> cleanup-unused-image
       │                                             └─> flyway-verify-nonprod ─> deploy-to-nonprod ─> health-check-nonprod ─┬─> cleanup-old-images-nonprod
       │                                                                                      └──────────────────────────────┴─> cleanup-unused-image-nonprod
       └───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
```

Both vertical paths (production, nonprod) share only `setup` and the single
`build-and-push-docker-image` node (one build, two Docker Hub tags — Plan 09-01, part B). From
there they are fully independent: `flyway-verify`/`deploy-to-netcup`/`cleanup-old-images`/
`cleanup-unused-image` read only production's `environment: production` secrets and only
`base_image_name`; `flyway-verify-nonprod`/`deploy-to-nonprod`/`health-check-nonprod`/
`cleanup-old-images-nonprod`/`cleanup-unused-image-nonprod` read only `environment: staging`
secrets and only `base_image_name_nonprod`. Neither path's `needs:` chain names a job from the
other (CI-01's parallel-independence requirement, unchanged by this plan).

## `health-check-nonprod` — the poll bound and its arithmetic

`docker-compose.nonprod.yml`'s two healthchecks size the bound: `redpanda-nonprod` (`interval 5s`,
`retries 8`, `start_period 15s`) must report healthy before `app-nonprod`'s own clock starts
(`depends_on: redpanda-nonprod: service_healthy`); `app-nonprod` itself (`interval 10s`,
`retries 5`, `start_period 30s`) cannot be marked healthy by Compose before `start_period 30s`, and
can take up to `30s + (10s x 5) = 80s`. Add image-pull time on the VM, Flyway's migration pass, and
DNS/TLS latency from a GitHub-hosted runner, and `30 attempts x 10s = 300s` comfortably covers that
with margin — deliberately overriding 09-RESEARCH.md's illustrative `12 x 5s = 60s`, whose own
Assumption A3 flags that figure as never sized against real CI conditions. Every attempt prints its
observed HTTP status (or the `000` sentinel on outright connection failure).

**Measured live (2026-08-19):** two real green runs (`32233904310`, `32236428721`) both reported
`Nonprod healthy after 2/30 attempts` — roughly 10-20s real elapsed, comfortably inside the 300s
bound with wide margin. The red path was also proven live in the same session (run `32235116988`,
`NONPROD_HEALTH_URL` temporarily pointed at an unreachable `.invalid` host per this plan's own
documented alternative rather than racing a live container stop/start): the poll correctly exhausted
all 30 attempts, emitted `##[error]Nonprod did not answer 200 within 30 attempts (bound: 300s
elapsed). Health poll exhausted -- failing the run.`, and the job's conclusion was `failure`. The
same run also live-proved `cleanup-old-images-nonprod`'s `if: success()` gate on `health-check-nonprod`:
it stayed `skipped` rather than running against a stack that never came up.

## Nonprod image retention — semantics and the deliberate asymmetry

`cleanup-old-images-nonprod` and `cleanup-unused-image-nonprod` are line-for-line copies of
production's `cleanup-old-images`/`cleanup-unused-image`, with exactly one axis changed throughout:
every Docker Hub URL and Registry API scope parameter interpolates
`needs.setup.outputs.base_image_name_nonprod` instead of `base_image_name`, never hand-typed — the
production delete-URL bug fixed 2026-08-17 (missing repository path segment) is exactly what
hand-typing a "similar" nonprod URL would reintroduce. Both already-fixed Docker Hub bugs are
inherited in their fixed form: the JWT login-token exchange against `hub.docker.com/v2/users/login/`
(2026-08-16 fix) and the `while` loop following `next` until null (2026-08-17 fix). Both sweeps
preserve the same short SHA, since both consume the one
`build-and-push-docker-image.outputs.image_tag`.

One deliberate asymmetry with production: `cleanup-old-images-nonprod`'s `needs:` names
`health-check-nonprod`, not `deploy-to-nonprod` — "the nonprod deploy is complete" now materially
depends on the endpoint having actually answered, a strictly stronger retention gate than
production has (production has no health job to gate on). This buys a one-run retention overshoot
in exchange: a deploy that starts containers but never becomes healthy leaves its tag in place,
swept only by the next healthy run. `cleanup-unused-image-nonprod` mirrors production's `needs:`
shape literally instead (parity, D-06) — it fires on `deploy-to-nonprod` failure itself, not on a
health-check failure, and adds one further deliberate addition over production's original: a
`::warning::`-only HTTP status check on the DELETE call, which production's own copy performs no
check on at all (T-09-14, accepted low severity — fixing both copies for real is Phase 10
CI-hardening scope, not this plan's).

## Live verification (2026-08-19) — completed after merge to master

This plan's file-level deliverables (`health-check-nonprod`, `cleanup-old-images-nonprod`,
`cleanup-unused-image-nonprod`, and this section) were authored and statically verified inside an
isolated git worktree, the same execution context Plan 09-01 documented above under "Task 3
deliberately deferred." For the identical reason, the live-verification steps requiring a push to
`origin/master` and observation of a real GitHub Actions run could not run from that worktree. Once
the orchestrator merged the worktree's commits to `master`, the human operator drove each remaining
step directly:

1. **Task 1 (CI-02) — repository secret sweep:** nine repository-level deploy secrets deleted, plus
   one unreferenced orphan (`NONPROD_RESET_TOKEN`) found and deleted alongside them after explicit
   confirmation. `gh secret list` now returns exactly `NVD_API_KEY`. A push immediately after
   (`08b253b`, run `32233904310`) confirmed both deploy paths still resolve every secret through
   `environment:` alone — full green run. `security-scan.yml` was checked separately: its
   `dependency-check` job was already failing on `NVD_API_KEY repository secret is not set` two days
   *before* this sweep (run `32001604789`, 2026-08-17) — confirmed pre-existing and unaffected by the
   sweep, filed as its own todo
   (`2026-08-19-security-scan-yml-nvd-api-key-not-resolving.md`) rather than folded into this plan's
   scope.
2. **Task 2 (CI-04) — health-check-nonprod green and red paths:** both proven live — see the
   "Measured live" note in the previous section for the full detail (green: 2/30 attempts twice;
   red: full 300s bound exhausted via a temporarily unreachable poll target, `::error::` annotation,
   job and run conclusion `failure`, restored and re-verified green).
3. **Task 3 (CI-03) — retention idempotency and cross-repository isolation:** the same completed run
   (`32236428721`) was re-run via `gh run rerun` against the identical already-deployed commit/tag —
   `cleanup-old-images-nonprod` produced zero `Deleting tag:` lines, `FAILED=0`, exit 0. Both Docker
   Hub repositories (`kanban-board-backend`, `kanban-board-backend-nonprod`) were confirmed via the
   public tags API to list exactly one tag each — the current short SHA (`406893c`) — proving neither
   repository's sweep can see or touch the other's tags.

Every acceptance criterion in this plan's Task 1 Part C, Task 2, and Task 3 is now live-verified, not
just statically checked. See this plan's `09-02-SUMMARY.md` for the full commit/run reference list.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
