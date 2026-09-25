# Automated deploy — Plan 05-05 Task 2 and Task 3 (2026-08-16)

CI/CD now builds, verifies the schema, and deploys automatically on every push to
`master` — the sequence plan 05-04 proved by hand is now the pipeline, not a
runbook a human follows.

## Task 2 — Flyway migration verification (INFRA-06)

A new `flyway-verify` job runs the official `flyway/flyway:11.7.2` CLI image
against Neon's **direct** connection string (`DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS`
secrets), applying `src/main/resources/db/migration`'s checked-in migrations, and
gates `deploy-to-netcup` in the dependency graph.

- **Guard proven by a deliberate failing run, then reverted** (not by reading the
  code): commit `125eebb` temporarily pointed the guard's comparison at a value
  containing the pooler marker; the job failed as designed (run `31961059446`,
  `CI/CD with Docker` red). Commit `0a8571e` reverted it immediately; the next run
  (`31961405448`) passed clean.
- **Idempotency proven by repeated real success**, not asserted: the job has now
  succeeded on four separate real pushes to `master` against the same,
  already-migrated Neon database — `77f02a0` (first run, applied the migrations),
  then `125eebb`'s revert, `56f093c`, and `595ec08` — Flyway's own
  `flyway_schema_history` tracking makes every run after the first a no-op
  success, exactly as designed.

## Task 3 — Deploy job rewrite and cleanup jobs (INFRA-05)

`deploy-to-ec2` (dead since 2026-08-04, `if: false`) was deleted outright and
replaced with `deploy-to-netcup`, targeting this document's Netcup VM via
`appleboy/scp-action@v1.0.0` (copies `docker-compose.prod.yml`/`Caddyfile`) and
`appleboy/ssh-action@v1.2.5` (runs `docker compose pull app && up -d`), both
authenticating as the non-root `deploy` user with the plan 05-05 Task 1 keypair,
host key pinned by fingerprint on both steps. Both action tags were re-confirmed
against their GitHub releases at execution time (`v1.2.5`/`v1.0.0` were each
still the latest release).

- **Proven green end-to-end on a real push** (commit `56f093c`, run
  `31962045626`): `build-and-push-docker-image` → `flyway-verify` →
  `deploy-to-netcup` → `cleanup-old-images` all succeeded. Confirmed by direct
  inspection, not by trusting the green checkmark alone:
  - `docker inspect` on the VM showed the running `app` container's image as
    `rudenkovladimir/kanban-board-backend:56f093c` — matching the pushed
    commit's short SHA exactly.
  - Off-VM `curl https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health`
    → `200 {"status":"UP"}`.
- **A second, genuinely independent push converged cleanly** (commit `595ec08`,
  run `31963539949`, triggered by an unrelated bug-fix documented below) —
  `flyway-verify` succeeded again (a third idempotent run), `deploy-to-netcup`
  succeeded again, and the off-VM health check still returned `200` afterward.
  This is the proof the plan's own acceptance criteria required: a push that
  changes nothing the deploy consumes still converges the VM rather than
  erroring, because the image tag itself always changes per commit even when
  the compose manifest and Caddyfile do not.
- Both cleanup jobs' `needs:` now name `deploy-to-netcup`. `cleanup-old-images`
  (`if: success()`) has fired for real on both pushes above, proving the
  dependency-graph rewiring actually resumed its gating (previously it
  permanently skipped, since `deploy-to-ec2` was `if: false`).
  `cleanup-unused-image` (`if: failure()`) is wired identically but was **not**
  exercised by a genuine deploy failure in this session — its correctness rests
  on the same `needs:`/`if:` idiom proven live on the success side, not on a
  live failure-path test. (An earlier version of this section's source todo
  claimed a deliberate fingerprint-mismatch test had proven this; that claim
  was false and has been corrected in place — see
  `.planning/todos/completed/2026-08-04-re-enable-and-rewrite-the-disabled-deploy-job-after-phase-5.md`.)

## Two real bugs found live during this task, one fixed and one deferred

Neither was caught by the workflow file simply parsing or by the job reporting
green — both were only visible by reading the job's own log output, which is
exactly why this section documents live evidence rather than describing intent.

1. **`cleanup-old-images`' DELETE URL was missing the repository-name path
   segment** (`$DOCKERHUB_USER/tags/$TAG/` instead of
   `$DOCKERHUB_USER/$DOCKERHUB_REPOSITORY/tags/$TAG/`) — every delete 404'd
   silently on the job's first-ever real run (commit `56f093c`'s run), a bug
   present since the job was first written but latent the whole time the job
   was permanently skipped. **Fixed** in commit `595ec08`, using the same
   `base_image_name` output the list call two lines above it already uses.
2. **After that fix, every delete is rejected `{"message":"unauthorized"}`**
   (confirmed live, run `31963539949`) — Docker Hub's Hub API v2 does not
   accept the job's `-u user:token` Basic auth for a mutating `DELETE`; the
   preceding `GET` list call "succeeding" with the same `-u` flag is not
   evidence Basic auth works, since it is a public repository and an
   unauthenticated `GET` on a public repo's tags succeeds regardless. **Not
   fixed this session** — the correct fix needs a JWT token exchange via
   `POST /v2/users/login/` that was not safely testable without live
   credential access. Filed as
   `.planning/todos/pending/2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md`,
   not gating Phase 5 (the plan's actual acceptance criteria required the
   cleanup jobs to be correctly wired and to fire, not that their deletes
   succeed). Practical effect: Docker Hub tags continue accumulating
   unbounded, same as while `deploy-to-ec2` was disabled — a tidiness/storage
   issue, not a deploy-correctness or security issue.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
