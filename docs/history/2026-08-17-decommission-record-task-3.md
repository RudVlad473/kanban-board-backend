# Decommission Record — Plan 05-06 Task 3 (2026-08-17)

Closes out INFRA-05 by removing the dead AWS-era credential surface and correcting the committed
documentation to describe the infrastructure that actually exists, now that the Netcup pipeline
has multiple proven-green end-to-end deploys (plan 05-05's SUMMARY).

## Part A — AWS-era secret revocation: already satisfied, nothing to delete

The plan's acceptance criteria call for deleting `EC2_SSH_KEY`, `EC2_HOST`, `EC2_USER`, and the
AWS-scoped database secrets. Verified directly, not assumed:

```
$ gh secret list --repo RudVlad473/kanban-board-backend
DB_HOST                  ...
DB_NAME                  ...
DB_PASS                  ...
DB_USER                  ...
DOCKERHUB_TOKEN          ...
NETCUP_DEPLOY_USER       ...
NETCUP_HOST              ...
NETCUP_HOST_FINGERPRINT  ...
NETCUP_SSH_KEY           ...
NVD_API_KEY              ...
```

Exactly 10 repository secrets exist. `EC2_SSH_KEY`, `EC2_HOST`, and `EC2_USER` are **not present**
— there is nothing to delete. `grep -rciE "EC2_SSH_KEY|EC2_HOST|EC2_USER" .github/` also returns 0
for every file, confirming no live reference exists either.

**This directly contradicts a prior session's `.planning/HANDOFF.json`/`.continue-here.md` claim**
that these secrets were "still present and unrevoked" — that claim was stale or simply wrong. The
live, re-verified state is that this acceptance criterion is already satisfied: no AWS-era secret
survives in repository settings, and none is referenced anywhere in the workflow. Recorded here as
an already-satisfied finding, not as a deletion action that did not actually happen.

## Part B — documentation correction

Corrected every committed file found still describing AWS EC2 as the current deployment target
(a repository-wide `grep -riE "EC2|AWS"` sweep across `*.md`/`*.sql`, narrowed to files making a
present-tense claim about where the app deploys today, as opposed to historical narration of the
AWS deletion itself, which is accurate and left untouched):

- `.claude/CLAUDE.md` — "Platform Requirements" section corrected from "AWS EC2 - Deployment
  target" to name Docker Compose, the Netcup VPS, Neon, self-hosted Redpanda, and Caddy.
- `.planning/codebase/STACK.md` — the same correction applied to the underlying GSD-managed source
  document this section of `.claude/CLAUDE.md` is generated from (the `<!-- GSD:stack-start
  source:codebase/STACK.md -->` marker), so a future stack-doc regeneration does not silently
  reintroduce the stale AWS EC2 line.
- `README.md` (repository root) — "Project status" section rewritten: it previously described the
  whole v1.2 infra migration as still "in flight" against an Oracle Cloud A1 Flex target, which was
  itself stale (the actual pivot landed on Netcup, not Oracle — see "Provider history" above) and
  understated what had actually shipped. Now states the migration is complete and names the real
  target.
- `docs/plans/backend-modernization/02-optimistic-locking-ddl.sql` — annotated (not rewritten) its
  "WHEN TO RUN" section's "master auto-deploys to EC2 on every push" reasoning with a note that
  this host no longer exists; original historical rationale left intact per the plan's own
  instruction not to rewrite superseded provenance text.
- `docs/plans/backend-modernization/STATUS.md` — same annotation applied to its parallel "master
  auto-deploys to EC2 on every push" line in the Key Decisions table, for the same reason.
- `docs/plans/backend-modernization/README.md` — its "Repo" summary line's "single-EC2 Docker
  deploy via GitHub Actions" phrase annotated with a historical note rather than deleted, since the
  surrounding sentence is otherwise a point-in-time description of this plan's original
  assumptions.
- `docs/LOCAL_DEV.md` — corrected two genuinely inaccurate functional claims, not just naming: it
  described the production pipeline as deploying via a single `docker run` of the built image with
  no Kafka broker standing up, and referenced "EC2's constraints" — neither matches the current
  Netcup deploy, which runs the full `docker-compose.prod.yml` stack (including `redpanda`) via
  `docker compose up -d` per plan 05-05.

`docs/INFRA_ARCHITECTURE.md`'s delivery diagram was **not modified by this task** — it was already
promoted from target-state to current-state language by quick task `260816-tqc` earlier the same
day (see that task's commits and `.planning/STATE.md`), independently of this plan. Verified
against the live file rather than assumed from the plan text before treating it as already done.

`.planning/`-scoped files other than `.planning/codebase/STACK.md` (phase summaries, quick-task
records, `.continue-here.md`, `.planning/research/*`, `.planning/milestones/*`) were deliberately
left untouched — they are historical records of what was true or planned at the time they were
written (e.g. "disabled `deploy-to-ec2`" describes a real action taken on a real date), not
present-tense claims about today's deploy target, and rewriting them would destroy the provenance
trail this project's own CLAUDE.md and `docs/SESSION_LESSONS.md` conventions rely on.
`.planning/codebase/STACK.md` was the one exception, corrected specifically because it is a live
generation source for `.claude/CLAUDE.md`, not a historical record.

## Part C — Docker Hub tag pruning: closed 2026-08-17, same-day follow-on

Originally halted here as a genuine human-only checkpoint (no Docker Hub console access in
this session). Resolved without needing that access after all: `cleanup-old-images`' DELETE
calls turned out to have two independent bugs in the job's own code, both found and fixed live
rather than guessed.

1. **`8a31d85`** — Hub API v2 rejects HTTP Basic auth on mutating requests (`DELETE`); added the
   documented login-token exchange (`POST /v2/users/login/`), sending the result as
   `Authorization: JWT <token>`. Its first live run (CI run `32016633112`) succeeded per its own
   status checks but deleted only 9 of 41 accumulated tags.
2. **`faacda4`** — root cause of the partial deletion: Hub API v2 paginates tag listings at
   10/page, and the list call only ever read page 1. Fixed by following the response's `next`
   cursor until `null`.

**Live proof:** CI run [`32017867204`](https://github.com/RudVlad473/kanban-board-backend/actions/runs/32017867204)
(2026-08-17T09:58Z, commit `faacda4`) — `cleanup-old-images` deleted all 32 remaining
non-current tags, the job's own `FAILED` counter (incremented on any non-204 delete response)
stayed at 0, job concluded success with zero `::warning`/`::error` lines.

INFRA-05's Docker Hub tag-pruning must-have is now fully satisfied. See
`.planning/phases/05-infra-migration/05-06-SUMMARY.md` (coverage item D4) and
`.planning/todos/completed/2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md`
for the full resolution writeup.

## Decommission date

2026-08-17.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
