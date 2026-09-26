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

Timeout corollary, since it is what converted a safe commit into a recovery: a `git commit` in this repo triggers `.githooks/pre-commit` (`spotlessCheck`, then `./gradlew fastTest` — the `--exclude-tests '*E2ETest'` name-suffix filter this note originally described was retargeted to a `@Tag`-based exclusion in phase 07.1, D-21/D-22), which takes minutes — give it a generous timeout instead of letting a default kill it partway through.

### 3. Redirect stdin on any subprocess launched from a git hook

**What happened:** `.githooks/pre-commit` invokes `./gradlew spotlessCheck` and `./gradlew fastTest`. When git itself was invoked through a nested tool-invoked subprocess chain, those `gradlew` calls hung indefinitely — zero CPU usage, no Testcontainers ever started (`docker ps -a --filter label=org.testcontainers` showed zero containers created the entire time). Running the identical `gradlew` commands directly, outside that chain, completed in 17 seconds.

**Why:** the hook process inherits stdin from its parent chain. When that chain's stdin is an open-but-never-closed pipe, a child process that blocks on stdin availability — even one that never actually reads from it — can hang waiting for an EOF that never arrives.

**The rule:** any command invoked from a git hook that doesn't need interactive input should redirect stdin explicitly (`< /dev/null`) rather than rely on default inheritance. `.githooks/pre-commit` now does this for both `gradlew` calls; treat it as the template for any future hook command, not a one-off fix.

### 4. Worktree cleanup must wait for every agent inside it to confirm return, not just the one that finished last

**What happened:** after fast-forward-merging a worktree's branch into master, its directory was removed (`rm -rf`) while a separate, later-dispatched agent instance was still actively running commands inside that same worktree, recovering a stalled plan. That agent reported the worktree as "destroyed externally." No commits were actually lost — worktree-directory deletion never touches git's shared object database, and all commits remained reachable from master — but the near-miss was real.

**Why:** a worktree can have more than one agent instance dispatched against it over its lifetime (e.g. a stalled original executor followed by a recovery agent). Treating "the branch merged cleanly" as proof the worktree is idle skips checking whether a later-dispatched agent is still running inside it.

**The rule:** before removing a worktree directory or its branch, confirm independently that no agent — original or any recovery dispatch — is still active in it. Merge success is not evidence of idleness by itself; a worktree reported auto-cleaned by the harness after making zero commits is a stronger signal of true idleness than an assumption based on the merge.

### 5. Verify a silent or long-running executor by direct inspection, not by its own self-report

**What happened:** an executor went silent for over an hour with no new commits. A resume attempt reported progress that direct `git log`/`git status` inspection did not confirm, and messaging it directly returned that it "had no active task; resumed from transcript" — it had genuinely stalled, not merely gone quiet while working. Separately, in an earlier session, an agent self-reported "~2h10min" elapsed on a task the harness's own measured duration put at ~36 minutes.

**Why:** a genuine hang and a merely slow real workload (Testcontainers startup, a long Gradle run) look identical from the outside — flat elapsed time, nothing visible happening. Only the underlying state — live process CPU deltas, actual new commits or file mtimes, or the harness's own measured duration — distinguishes them; an agent's own narrative summary is not reliable evidence either way.

**The rule:** when a task looks stuck or its reported duration looks surprising, check the state the environment actually produced — new commits, process activity, container lifecycle — before accepting either "it's fine, just slow" or "it's stuck" on the strength of an agent's self-report alone.

### 6. Push at every quick task's closing commit when a session has no waves

**What happened:** at the start of the 2026-08-13 session, `origin/HEAD` (`fcaf81c`) was already 22 commits behind local `master` (`5a91775`), carried over unpushed from prior sessions. The session's first worktree-isolated quick task (`260813-h2f`) failed cleanup with `worktree.cleanup-wave`'s `base_mismatch`, even though `git merge-base --is-ancestor` independently confirmed the branch's parent genuinely was the correct local `master` commit. Recovery meant bypassing the tool: a manual `git rebase` onto local `master` (a no-op, since the branch's parent already was local `master`), then a manual `git merge --ff-only`, then manual worktree and branch cleanup. Every later quick task that session ran without worktree isolation — sequential, committing directly to `master` — specifically to avoid repeating that failure. By session end roughly a dozen quick tasks (`260813-h2f` through `260813-q1i`) had accumulated 54 local commits before a single push, which happened once, at the very end, and only because the operator asked for it.

No further concrete harm was observed: no commits were lost, and nothing beyond that one recovery broke. The cost that is real is the rest of the session running without worktree isolation by choice, and a 54-commit window in which `origin` held none of the session's work.

**Why:** the mechanism is the same one lesson 1 already documented: worktree isolation forks a new worktree from `origin/HEAD`, not from live local `HEAD`. What is genuinely new here is why lesson 1's rule did not catch it: the base check keys off `origin/HEAD`, so a stale `origin/HEAD` produces a `base_mismatch` even when the branch's actual parent is correct — the check is not wrong about its own question, it is asking about a ref nobody refreshed. And lesson 1's trigger ("starting phase execution", "wave boundaries") never fires in a quick-task session, which has neither, so a session can be fully compliant with lesson 1 and still walk into this.

**The rule:** this extends lesson 1's push-cadence rule rather than replacing it. The underlying invariant lesson 1 established is that `origin/HEAD` must not lag local `HEAD` whenever anything is about to fork from `origin/HEAD` — phase execution is only one of the session shapes that does. A session made of many worktree-isolated quick tasks has no waves to serve as a natural checkpoint, so it needs its own: push at each quick task's closing commit — the boundary such a session already has — and unconditionally before dispatching any worktree-isolated task, whatever kind of work it is. Practical corollary this session demonstrated: if a `base_mismatch` shows up despite a branch whose parent verifiably is local `master`, check whether `origin/HEAD` is stale before concluding the tool is broken.

### 7. Prefer a live local server over a throwaway test class for inspecting generated output (OpenAPI docs, HTTP responses)

**What happened:** to verify a per-operation OpenAPI `@ApiResponse` override rendered correctly, three iterations of writing-and-running a throwaway `@SpringBootTest` + `MockMvc` probe class were used — each cycle paying for `compileTestJava` across the *entire* test tree (so an unrelated file's formatting/lint error could block it), `spotlessCheck`, a full Spring context boot, and `jacocoTestCoverageVerification` failing on an irrelevant coverage-floor miss from running only one test class. Each iteration cost 2-4 minutes; three iterations (fixing a wrong base class, a wrong URL path, a missing schema ref) cost roughly 15 minutes total, on top of separately colliding with concurrent agent activity in the same working tree mid-session. Switching to `docker compose up -d postgres redpanda` + `./gradlew bootRun` + `curl` found and fixed the same three mistakes in under a minute combined, once the server was up.

**Why:** a throwaway test class pays the full CI-shaped pipeline cost (compile the whole tree, lint, boot Spring, verify coverage) just to answer "what does this one generated document look like right now" — none of which the question actually needs. A live server pays a one-time startup cost (~20-25s) and then answers any number of `curl` questions in the sub-second range, with no dependency on unrelated files in the tree being clean.

**The rule:** for inspecting live/generated output — OpenAPI docs, an actual HTTP response shape, anything you'd otherwise print-and-eyeball from a throwaway test — bring up the local dev stack and `curl` it directly instead of writing a probe test class. See `.claude/CLAUDE.md`'s "Local Development Server" section for the exact commands (env vars, ports, the `/api/docs` path gotcha). Reserve a real test class for an assertion that should live on permanently as regression coverage, not for one-off manual inspection.

### 8. Push a staged multi-commit cutover by explicit SHA per gate, never by branch tip

**What happened:** a plan (13-06, production cutover to k3s) staged four sequentially-dependent
commits (W1→W2→W3→W4) on one local branch, deliberately so each could be pushed to `main`
individually at its own gated point during a live maintenance window — W2 only after a `docker
ps` empty-guard proved Compose was fully stopped, W3 only after certificate staging-validation,
W4 only at the CI-retarget step. After pushing W1 alone and building a bug fix on top of it, the
fix was pushed with `git push origin <fix-sha>:main` — but `<fix-sha>` resolved to the tip of the
whole local branch, which by then already contained W2, W3 and W4 committed on top (built that
way deliberately for the plan's own gate-checking in the prior task). The push silently included
all three later commits. Flux reconciled everything within seconds: production's app pod started
against an empty, freshly-`initdb`'d database and ran Flyway against it before any real
data had been dumped or restored, and the public edge briefly served HTTP 502. See
`docs/history/2026-09-26-production-cutover-to-k3s.md`'s "Incident: premature multi-window push"
for the full account and recovery.

**Why:** a git branch tip is the accumulation of everything committed on it, not a marker for "the
next thing I meant to push." Staging W1→W2→W3→W4 sequentially on one branch is the right way to
build them (each later commit's diff review, and Task 2's own per-commit gate-checking, depend on
seeing the cumulative state), but that same sequencing means `<branch>` and `<branch's tip SHA>`
both name every commit on the branch, not just the one intended for this push. Nothing about `git
push origin <sha>:main` warns that `<sha>` might resolve further than expected — it is exactly as
valid a ref whether it names the commit meant for this gate or three gates further along.

**The rule:** when a plan stages multiple sequentially-dependent commits on one local branch
specifically so they can be pushed individually at separate gated points, push each one by its
own **explicit commit SHA** captured at the moment that gate is reached (`git push origin
<w1-sha>:main`, then later `git push origin <w2-sha>:main`, etc.), never by branch tip or `HEAD`.
Before every gated push, run `git log --oneline <last-pushed-sha>..<intended-sha>` and confirm the
list shown is exactly what that gate authorizes — if it shows more than one commit, or a commit
touching files outside that gate's scope, stop before pushing. This generalizes past this specific
cutover: any staged multi-commit rollout with sequential dependencies (a schema migration split
into gated steps, a feature flag rollout with per-stage commits) has the same failure mode.

### 9. Local memory contention on a shared/constrained host can kill every Gradle JVM, even a small single-use one

**What happened:** during 13-07 Task 1, `.githooks/pre-commit`'s `fastTest` step failed 20 consecutive times over roughly 25 minutes with the identical signature: `Gradle build daemon has been stopped: stop command received`, at a different point in the build each time (sometimes during `compileJava`, sometimes mid-`fastTest`). This happened on a WSL2 host capped at a 10GB memory ceiling (`.wslconfig`) that was concurrently running two other Claude Code sessions plus an unrelated root-owned Node service stack; `free -h` showed as little as 130Mi-1.7Gi free throughout, fluctuating. The first working theory — a second, colliding GSD worktree issuing `./gradlew --stop` — was wrong: `git worktree list` showed no live second worktree at any point during the incident; the misleading signal was a stale `~/.gradle/daemon/*/daemon-*.log` whose `currentDir` pointed at an already-discarded worktree from a prior session (rebased onto `main` and abandoned days earlier, per this file's own decision log), which looked exactly like evidence of a live collision but was not. Even a fresh, non-shared, single-use daemon forced via `GRADLE_OPTS=-Dorg.gradle.daemon=false` and a `gradle.properties` capping `-Xmx512m` still died the same way — ruling out "a long-lived daemon's own low-memory self-expiration" as the mechanism, since a brand-new small-heap JVM was killed too, sometimes before it even reached `compileJava`.

**Why:** on a memory-starved shared host, forking any new JVM (Gradle daemon, `--no-daemon` single-use process, or a worker) can be killed near-instantly regardless of its requested heap size, because the kill happens before or during the fork/startup itself — smaller `-Xmx` does not reliably buy survival when there is not enough free RAM for the process to even come up cleanly under contention from unrelated processes with no shared coordination. No OOM-killer trace appeared in `dmesg`/`journalctl` and no `earlyoom`/`systemd-oomd` service was present on this box, so this was not a classic OOM-kill; it presented purely as repeated build failures with no crash evidence to point at.

**The rule:** diagnose in this order before concluding "concurrent GSD collision": (1) `free -h`/`vmstat` for actual available memory — a number under roughly 1-2Gi free on a host this size is a real signal; (2) `git worktree list` to positively confirm a second live worktree exists, rather than inferring one from a Gradle daemon log alone; (3) if a daemon log looks like evidence of a collision, cross-reference its `currentDir` against the *live* worktree list from step 2 before trusting it — a `~/.gradle/daemon/*/daemon-*.log` can persist for days after the worktree it ran in was discarded, and its mere existence proves nothing about current concurrency. When the host genuinely cannot spare memory for a local Gradle JVM right now, the accepted fallback (never a default, only under **explicit operator authorization given fresh each time**) is `git commit --no-verify`, re-running the hook's non-JVM checks manually first (anything that doesn't fork a JVM, e.g. this project's own `verify-k8s-manifests.sh`/`verify-k8s-invariants.py` for a k8s-only change) and treating CI's own `spotlessCheck`/`fastTest` run as the real gate instead of the local one. This is NOT equivalent to skipping verification — CI still runs the checks, just later and remotely — but it does trade local-gate speed for CI-gate latency, and it should never be reached for without an explicit ask: this project's default stance is that `--no-verify` is not used (see `.claude/CLAUDE.md`'s Secret scanning / gitleaks pre-commit section, which documents the same pre-commit hook this lesson's workaround bypasses). Also worth checking before assuming CI actually re-verifies anything: whether the specific push's changed paths even trigger a workflow that runs `spotlessCheck`/`fastTest` at all — a `k8s/**`-only change is explicitly excluded from `deploy.yml`'s trigger via its own `paths-ignore`, so for that class of change there may be no CI job positioned to serve as the fallback gate, and that gap is worth surfacing rather than assuming CI silently covered it.

## Adding a lesson

New lessons are appended as a new `###` section under `## Lessons`, numbered with the next integer. Each lesson carries exactly three bolded labels, in this order: **What happened**, **Why**, **The rule**. This differs from `CODE_STYLE.md`'s rule shape, which requires a bad-vs-good Java code example — that contract does not apply here, since these lessons describe process, not code. Do not copy the code-example requirement from the sibling file when adding a lesson.
