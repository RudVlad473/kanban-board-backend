# Digest-pinned deploy actions — Plan 10-01 Task 1, live tracer (2026-08-20)

Plan 10-01 Task 1 digest-pinned all six `appleboy/scp-action`/`appleboy/ssh-action` call sites in
`deploy.yml` (production and nonprod SCP/SSH steps) to immutable commit SHAs, closing the
supply-chain gap where a retargeted tag on either action would hand a compromised publisher direct
shell on the VM. The plan's own `<precondition>` for this proof was a real push to `master` and a
`gh run watch` of the resulting deploy, observed by the operator rather than a worktree agent (no
push authority) — deferred at the time (window ledger id 7) because Plan 10-04 still had further
edits queued for the same file, and re-pinning after a second edit would have meant repeating the
proof.

By the time this proof was run, master had already absorbed every remaining phase 10 plan (10-02
through 10-06) and been pushed to `origin` — commit `586bed2` (`docs(phase-10): complete phase
execution`) is the tip both locally and on `origin/master` (`git rev-list --left-right --count
origin/master...master` → `0  0`). Its own push already triggered a full `CI/CD with Docker` run
for that commit, which doubles as the tracer this task needed — a fresh throwaway push was not
required.

## Live run observed

`gh run view 32294906063` (triggered by the `586bed2` push) — every job green:

```
setup                          success
run-tests                      success
build-and-push-docker-image    success
flyway-verify                  success
flyway-verify-nonprod          success
deploy-to-netcup                success
deploy-to-nonprod              success
register-schemas-production    success
cleanup-old-images             success
cleanup-unused-image           skipped   (if: failure() — correct, nothing failed)
health-check-nonprod           success
cleanup-unused-image-nonprod   skipped   (if: failure() — correct, nothing failed)
cleanup-old-images-nonprod     success
```

`deploy-to-netcup`'s step list (`gh run view --job`) shows `Copy Compose manifest and Caddyfile to
the VM` and `Deploy via Docker Compose` both green, resolving `appleboy/scp-action@ff85246ac...`
and `appleboy/ssh-action@0ff4204d5...` — i.e. this run genuinely exercised the digest-pinned
references, not the pre-pin tags. `deploy-to-nonprod`'s equivalent steps are likewise green against
the same two pinned SHAs.

## Independent confirmation the pinned-action deploy actually landed

Off-VM health, both hosts:
```
curl -isS https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health         -> 200 {"status":"UP"}
curl -isS https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health  -> 200 {"status":"UP"}
```

On-VM image identity, confirming the *running* containers are this exact commit, not a stale
earlier deploy that merely left the health check passing:
```
$ docker inspect kanban-board-backend-app-1 --format '{{.Config.Image}}'
rudenkovladimir/kanban-board-backend:586bed2

$ docker inspect kanban-nonprod-app --format '{{.Config.Image}}'
rudenkovladimir/kanban-board-backend-nonprod:586bed2
```

Both match `586bed2` exactly — the same commit the digest-pinned run built and deployed. The pin
does not break the deploy path (the plan's own acceptance criterion), proven by a real push
already observed rather than a synthetic one manufactured for this proof alone.

## Window ledger

Closes window ledger id 7 (`unrun-verify`, phase 10, `.github/workflows/deploy.yml`). Also closes
id 3 (`unrun-verify`, phase 8, `docs/INFRA_RUNBOOK.md`) as a bookkeeping correction found while
reconciling the ledger against `git log`, not new verification: id 3's live proof was already
recorded above in "Nonprod reset endpoint — Plan 08-02", committed as `c83d36e` at
2026-08-18T13:36:52Z — 24 minutes *after* the window was recorded (2026-08-18T13:12:05Z) as the
Task 3 re-dispatch's own precondition-satisfied resolution. The ledger entry was simply never
flipped to `fixed` once that re-dispatch landed.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
