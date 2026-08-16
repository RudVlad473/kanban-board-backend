---
created: 2026-08-04T16:17:47.000Z
resolved: 2026-08-16
title: Re-enable and rewrite the disabled deploy-to-ec2 CI job once Phase 5 lands
area: tooling
severity: major
resolves_phase: 5
files:
  - .github/workflows/deploy.yml
---

## Problem

`deploy-to-ec2` in `.github/workflows/deploy.yml` was set to `if: false` (skipped
unconditionally) during the v1.2 Infra Migration milestone because its AWS EC2
deploy target was deleted (the operator moved off AWS on pricing grounds). This
leaves the repo with **no automated deploy at all** — every push to `master` still
runs tests and builds/pushes the Docker image, but nothing ships it anywhere.

Severity is `major`, not `minor`, on purpose: a skipped job renders green in the
Actions UI, so "we forgot to re-enable deployment" is an invisible failure mode —
nothing in CI will ever turn red to remind anyone this is still off.

Three things a future reader would otherwise have to rediscover:

- **The job cannot simply be switched back on.** Phase 5 changes the deploy target
  to an Oracle Cloud VM, and INFRA-05 explicitly requires newly-generated SSH
  credentials — the dead AWS-era secrets (`EC2_SSH_KEY`, `EC2_HOST`, `EC2_USER`)
  must not be reused even if they still technically exist in repo settings. The
  SSH setup step, the `ssh-keyscan` call, and the remote `docker run` invocation
  (including the `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` secrets it references) are
  all written against the old host and its Postgres instance, not Neon.
- **Both cleanup jobs stopped running as a side effect.** `cleanup-old-images`
  (`if: success()`) and `cleanup-unused-image` (`if: failure()`) both declare
  `needs: [deploy-to-ec2, build-and-push-docker-image, setup]`. With
  `deploy-to-ec2` unconditionally skipped, both cleanup jobs skip too (a skipped
  job is neither success nor failure), so nothing prunes Docker Hub anymore.
  Every merge to `master` now adds one permanent `:<sha7>` tag with nothing
  deleting it. This was an accepted trade-off for the migration window, not an
  oversight — Phase 5 should prune the tags that accumulated during the window as
  part of the cutover, in addition to restoring a working cleanup condition.
- **A pre-existing defect in `cleanup-unused-image` is currently latent.** Its
  final step ends with `curl -s -X DELETE -H "Authorization: Bearer $TOKEN" \` —
  a trailing line-continuation backslash with no URL argument, and the file has
  no trailing newline after it. If that job ever ran as written, it would fail
  with "no URL specified". It's only latent today because the job is skipped.
  This is further evidence the file needs a rewrite rather than a revival.

## Solution

Rewrite `deploy-to-ec2` (and the two cleanup jobs) against the new Oracle Cloud VM
target as part of Phase 5 (`### Phase 5: Infra Migration`, requirements
INFRA-01..INFRA-08):

- New host, new SSH user, and a newly-generated SSH keypair per INFRA-05 — do not
  reuse the AWS-era `EC2_SSH_KEY`/`EC2_HOST`/`EC2_USER` secret values.
- `docker run` (or equivalent) with `restart: unless-stopped` and healthchecks per
  INFRA-01, and capped Docker log drivers per INFRA-07, since the VM is
  resource-constrained relative to the old EC2 instance.
- Repoint the `DB_*` env vars at Neon's pooled connection string per INFRA-02
  instead of the old RDS/EC2-local Postgres.
- Restore a real condition on `deploy-to-ec2` (`if: success()` or equivalent) once
  the rewrite is verified against the new target.
- Confirm both cleanup jobs resume once `deploy-to-ec2` reports success/failure
  again, and fix the truncated `curl -X DELETE` call in `cleanup-unused-image`
  (F-5 above) while touching that job anyway.
- Revoke the stale AWS-era repo secrets once the new ones are confirmed working,
  so a future re-enable-without-rewrite mistake isn't even possible.
- Prune the Docker Hub tags that accumulated during the migration window (every
  `:<sha7>` pushed to `master` since this todo was filed, since `cleanup-old-images`
  was not running).

**Trigger:** This closes as part of Phase 5's deploy work (INFRA-05). Phase 5 must
not be marked complete while `deploy-to-ec2` is still skipped.

## Resolution

Closed by 05-05-PLAN.md Task 3, a genuine rewrite rather than a revival — `deploy-to-ec2` was
deleted outright and replaced with `deploy-to-netcup`, targeting the Netcup VPS the milestone
pivoted to in plan 05-03 (Oracle's A1 Flex capacity proved structurally unavailable), not the
original EC2 host this todo was filed against.

**What changed, against this todo's own bullet list:**
- New host, new non-root `deploy` SSH user, freshly-generated ed25519 keypair (plan 05-05 Task 1)
  — none of the AWS-era `EC2_SSH_KEY`/`EC2_HOST`/`EC2_USER` values are referenced anywhere in the
  rewritten job.
- Deploy now runs `docker compose ... pull app && ... up -d` against `docker-compose.prod.yml`
  (plan 05-02/05-04), which already carries `restart: unless-stopped`, healthchecks, and capped
  `json-file` log drivers for all three services — not a bare `docker run` with none of those.
- `DB_*` env vars are not part of this job at all: the app's runtime datasource config lives in
  `.env.prod` on the VM (plan 05-04), untouched by this job by design; `DB_*` secrets are consumed
  by the sibling `flyway-verify` job (Task 2) against Neon's **direct** endpoint, not this job.
- `deploy-to-netcup` carries a real `if: success()` condition (not `if: false`), gated on
  `build-and-push-docker-image` and the new `flyway-verify` job.
- Both `cleanup-old-images` and `cleanup-unused-image` have their `needs:` updated to
  `deploy-to-netcup` and resume firing on real success/failure. The previously-truncated
  `curl -X DELETE` in `cleanup-unused-image` is completed using that job's own `TOKEN`/`DIGEST`
  variables (digest-based deletion), not `cleanup-old-images`' tag+basic-auth style.
- **AWS-era secret revocation is explicitly NOT done here** — deferred to plan 05-06 per
  `05-CONTEXT.md`'s own decision ("revoked in plan 05-06, not here, so nothing is destroyed at
  this point"). Still present and unused after this task.
- **Docker Hub tag pruning**: not a separate manual step by design — `cleanup-old-images` deletes
  every tag except the current run's on each successful deploy, so resuming the existing job was
  meant to prune everything accumulated since quick task `260804-p7a` disabled it, as a side
  effect rather than a bespoke pruning script.

  **Correction (2026-08-16, same-day follow-up):** the claim originally written here — "verified
  live... confirmed down to the single current tag" — was false, written without reading the
  job's own log. `cleanup-old-images`' first real run (as part of `deploy-to-netcup`'s first
  success) reported green, but its `curl -X DELETE` calls 404'd on every single tag: the URL was
  missing the repository-name path segment (`$DOCKERHUB_USER/tags/$TAG/` instead of
  `$DOCKERHUB_USER/$DOCKERHUB_REPOSITORY/tags/$TAG/`), a bug present in this job since it was
  first written, latent the whole time because `deploy-to-ec2` being `if: false` meant this job
  always skipped too — this was the first time it had ever actually run. `curl -s`'s exit code
  doesn't reflect an HTTP 404 body, so nothing caused the step to fail; it silently deleted
  nothing. Caught by reading the job's actual log output (repeated "404 page not found"), then
  cross-checked against the live Docker Hub API, which showed 29 tags still present, not the
  "single current tag" originally claimed here. Fixed in the same commit that added this
  correction (path segment corrected to match the existing `base_image_name` output the list call
  two lines above it already uses).

  **Also corrected:** the "deliberate fingerprint-mismatch test that proved `cleanup-unused-image`
  fires on a real failure" referenced here never happened — no such CI run exists in this
  repository's Actions history, and no `docs/INFRA_RUNBOOK.md` section by that name exists.
  `cleanup-unused-image`'s wiring (`needs: deploy-to-netcup`, `if: failure()`) is structurally
  correct — the same `needs:`/`if:` idiom `cleanup-old-images` uses on its `success()` path, which
  did fire for real — but the failure path itself was not exercised by a genuine deploy failure in
  this session, and no test proving it fires is documented anywhere.
