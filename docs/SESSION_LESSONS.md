# Session Lessons

This file records operational lessons from running GSD workflows in this repository — how work is *run*, the sibling to [`docs/CODE_STYLE.md`](./CODE_STYLE.md), which records how Java is *written*. It is additive: new lessons are appended over time as they come up, never rewritten wholesale.

The first two lessons below were captured during the v1.2 Phase 4 (Schema Registry) execution session on 2026-08-04. They previously existed only in an AI agent's machine-local external memory, which does not travel with this repository and is invisible to human contributors and to code review — which is why they are recorded here instead.

## Lessons

### 1. Push to origin periodically during an active phase-execution session

**What happened:** Wave 1 of Phase 4 dispatched its first worktree-isolated executor; that dispatch failed its own base check (`worktree-branch-check`, exit 42). The phase degraded to sequential execution for every remaining wave — including a wave of 2 plans with zero `files_modified` overlap, confirmed independently by the plan-checker, that was genuinely eligible for parallel execution. The cost was real wall-clock across the rest of the phase, not merely the one failed dispatch.

**Why:** worktree isolation forks the new worktree from `origin/HEAD`, not from live local `HEAD`. The working tree carried 139 commits that had never been pushed, so the forked worktree's base was 139 commits behind the state the plans were written against; the base check refused it, correctly.

**The rule:** push to origin before starting phase execution and again at wave boundaries during a long session — not only at milestone boundaries. Treat unpushed local commits as a hard precondition failure for worktree parallelism rather than as cosmetic backlog.

### 2. Do not run ad-hoc git commands on the main tree while a sequential executor is mid-task

**What happened:** during a sequential (non-worktree) executor's ~67-minute task, an ad-hoc `git add` of `.planning/todos/...` plus `.planning/STATE.md` was run on that same tree, followed by `git commit`. The commit's pre-commit hook was killed by a tool timeout mid-flight, and by then the `git add` had already landed those paths in the same index that held the executor's own in-progress staged files.

**Why:** a sequential executor works in the main working tree, so there is exactly one `.git/index` shared between it and any ad-hoc command — staging is global and there is no per-agent staging area. The executor's per-task commit is meant to be atomic (stage its own files, commit exactly those), so anything staged by anyone else inside that window either rides along into its commit or gets caught by a recovery aimed at something else.

Record the recovery that worked, because it is the reusable part: a `git reset` scoped explicitly to the ad-hoc paths only, never a bare `git reset`, and never touching the executor's staged paths. It recovered cleanly — but the exposure to the executor's atomic commit was real, and the clean outcome should not be read as evidence the sequence was safe.

**The rule:** writing files into the working tree is always safe during an executor run. `git add`, `git commit`, `git reset`, and `git stash` are not — hold them until the executor's completion notification confirms the tree is quiet. If something must be captured mid-run, write the file then and stage it after.

Timeout corollary, since it is what converted a safe commit into a recovery: a `git commit` in this repo triggers `.githooks/pre-commit` (`spotlessApply`, then `./gradlew test --exclude-tests '*E2ETest'`), which takes minutes — give it a generous timeout instead of letting a default kill it partway through.

## Adding a lesson

New lessons are appended as a new `###` section under `## Lessons`, numbered with the next integer. Each lesson carries exactly three bolded labels, in this order: **What happened**, **Why**, **The rule**. This differs from `CODE_STYLE.md`'s rule shape, which requires a bad-vs-good Java code example — that contract does not apply here, since these lessons describe process, not code. Do not copy the code-example requirement from the sibling file when adding a lesson.
