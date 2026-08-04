---
created: 2026-08-04T16:17:47.000Z
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
