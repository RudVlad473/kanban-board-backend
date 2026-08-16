# 260816-hn1 Measurements — Task 1 (baseline triage)

Host: Windows 10, Docker Desktop, Git Bash (MSYS). Executed inside git worktree
`C:\Dev\Repos\kanban-board-backend\.claude\worktrees\agent-a9aeb46f634574b7e` (branch
`worktree-agent-a9aeb46f634574b7e`, HEAD `f46aa91c`).

## Version pin and image verification

- **Pinned version: `v8.30.1`** (published 2026-03-21, ~5 months field exposure as of
  today, 2026-08-16). Confirmed via GitHub API (`/repos/gitleaks/gitleaks/releases/latest`)
  that this is the current latest non-prerelease release — there is no newer, more-tested
  release being skipped in favor of it. Unlike `dependency-check-gradle`'s pin (Task 1's
  read-first precedent), there was no "reviewed-settled vs. bleeding-edge" tradeoff to make
  here: the latest release already carries several months of field exposure, so it *is* the
  reviewed-settled choice.
- **Registry: `ghcr.io/gitleaks/gitleaks`**, not `zricethezav/gitleaks` (Docker Hub). Both
  are linked directly from the upstream README's own "Docker" section as official images.
  Chose GHCR because it is published under the `gitleaks` GitHub organization itself (the
  same org that owns the source repository), built directly by the org's own CI, whereas the
  Docker Hub image is published under the individual maintainer's personal account
  (`zricethezav`) — a stronger provenance signal for the org-vs-individual distinction
  T-hn1-SC calls out. Not a claim that the Docker Hub image is untrustworthy, just that GHCR's
  org-level namespace is the tighter match to "the upstream-published image."
- **Resolved digest:**
  `ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f`
  (`docker inspect ghcr.io/gitleaks/gitleaks:v8.30.1 --format '{{.RepoDigests}}'`), platform
  `linux/amd64` — matches this dev host (Docker Desktop/WSL2, x86_64) and the `ubuntu-latest`
  CI runner Task 3 targets. The tag+digest pair is what `.gitleaks.toml`'s sibling files
  (`.githooks/pre-commit`, `.github/workflows/secret-scan.yml`) both reference, so an upstream
  retag of `v8.30.1` cannot silently swap the running binary out from under either gate.

## Docker-is-already-required claim (confirmed by hand, not inherited)

Read `build.gradle`'s `fastTest` task block and
`src/test/java/com/vrudenko/kanban_board/support/containers/AbstractPostgresContainerTest.java`
directly (not assumed from the plan text). Confirmed: `AbstractPostgresContainerTest`'s
`static { postgres.start(); }` block starts a real `PostgreSQLContainer` via Testcontainers,
and `fastTest` — the exact task `.githooks/pre-commit` already runs on every commit — depends
on `compileTestJava`, whose transitive test classes (`AbstractAppTest`, every
`*ServiceTest`/`*ControllerTest`) extend this class. So a running Docker daemon is already an
unconditional prerequisite for committing to this repo today, before this task added anything.
`docker info` exited 0 on this host at task start, confirming the precondition was met.

## Invocation shape — determined empirically, not by reasoning

Two non-obvious findings here, one anticipated by the plan text and one not.

**MSYS path rewriting (anticipated, confirmed real).** Git Bash silently rewrote a
Windows-style Docker mount/working-dir argument into a mangled path
(`docker: Error response from daemon: the working directory 'C:/Dev/...' is invalid, it needs
to be an absolute path`) until `MSYS_NO_PATHCONV=1` was set on the invocation. Every command
below carries it. Once set, both `C:/Dev/Repos/kanban-board-backend` (git's own
`--show-toplevel` output format) and `//c/Dev/Repos/kanban-board-backend` (POSIX form) work
as Docker bind-mount sources on this host — no `//c/...` translation is actually required,
the native `C:/...` form Git for Windows already emits is sufficient once MSYS stops mangling it.

**`safe.directory` / dubious-ownership (anticipated, NOT observed).** The plan's `<action>`
flagged this as expected friction. It did not occur on this host in any invocation below —
`git status`, `git diff --cached`, and `git log` all ran inside the container without a
dubious-ownership refusal. Recorded as a real negative result, not an oversight: this
repo's `.githooks/pre-commit` hook and Task 2/3's config should not pre-emptively add a
`safe.directory` workaround for a problem that was checked for and did not reproduce here —
if it surfaces on a different developer's machine, that is new evidence to act on then, not
now.

**Worktree `commondir` resolution — a genuinely new finding the plan did not anticipate.**
This project's working directory is itself a git worktree (`.git` is a redirect file:
`gitdir: C:/Dev/Repos/kanban-board-backend/.git/worktrees/agent-a9aeb46f634574b7e`). The
Windows-absolute-path text inside that redirect file is meaningless to a Linux container
(git inside the container tried to resolve it as a literal subdirectory path and failed).
The obvious fix — set `GIT_DIR`/`GIT_WORK_TREE` env vars to bypass the `.git`-file lookup
entirely — is necessary but **not sufficient alone**: mounting the worktree's private git-dir
(`.git/worktrees/<name>/`) as a *standalone* bind mount fails with
`fatal: not a git repository: '/gitdir'`, because that directory's own `commondir` file holds
a **relative** path (`../..`) back to the shared object database, and a standalone mount
severs that relative chain. The fix that actually works: mount the **entire main repository
root** (the directory containing `.git`) as a single volume, so the physical relationship
between the worktree's private git-dir and the shared `.git` is preserved, then set
`GIT_DIR`/`GIT_WORK_TREE` to absolute paths *inside that one mount*. This is the shape both
Task 2 and Task 3 must reuse; a naive "just mount the worktree" or "mount the git-dir alone"
attempt will silently produce `0 commits scanned` / `no leaks found` — a false-clean result,
not an error a hasty read would catch. Filed as a new todo below for developers who create
worktrees *outside* this repo's own `.claude/worktrees/<name>` convention (a worktree living
on a different drive, or entirely outside the main repo's directory tree, cannot be captured
by a single bind mount at all — out of scope for this quick task, documented as a known limit).

**Working command line (git-mode, reused verbatim by Tasks 2 and 3), full-history variant:**

```sh
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "<main-repo-root>:/repo" \
  -e GIT_DIR=/repo/.git/worktrees/<worktree-name> \
  -e GIT_WORK_TREE=/repo/<relative-path-to-worktree> \
  ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f \
  git --log-opts="--all" --redact --no-banner \
    -f json -r /repo/<relative-path-to-worktree>/.gitleaks-reports/full-history.json \
    /repo/<relative-path-to-worktree>
```

For a **plain (non-worktree) checkout** — which is what CI's `actions/checkout` produces, and
what most developers who don't use this repo's own worktree convention will have — `GIT_DIR`
and `GIT_WORK_TREE` collapse to `<repo>/.git` and `<repo>` respectively, and the mount is just
the repo root; the same command line works unmodified, the worktree-specific env vars simply
become redundant with git's own defaults rather than required overrides.

**Staged-diff variant (Task 2's hook uses this verbatim, swapping `git --log-opts=... -f json -r ...` for `git --staged`):**

```sh
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "<main-repo-root>:/repo" \
  -e GIT_DIR=/repo/.git/worktrees/<worktree-name> \
  -e GIT_WORK_TREE=/repo/<relative-path-to-worktree> \
  ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f \
  git --staged --redact --no-banner -v /repo/<relative-path-to-worktree>
```

Verified empirically (this task): with a benign staged change (the `.gitignore` edit below),
this command exits 0 with `no leaks found`, `0 commits scanned` is expected output for
`--staged` mode (it scans the index diff, not commit history) and is not an error signal.
Positive-detection falsification (a staged fake credential being refused) is Task 2's job and
is not repeated here.

## `.env` / `.env.prod` — read by the working-tree scan?

This project's own constraint text states real `.env`/`.env.prod` files with genuine
production credentials exist in *the working tree*. Checked directly in **this worktree**
(`test -f .env`, `test -f .env.prod`) — **neither file exists here.** This is expected, not a
contradiction: git worktrees do not share untracked/gitignored files with each other or with
the main checkout — each worktree's gitignored files are local to its own directory on disk.
The files referenced by the task's constraint live in the main checkout
(`C:/Dev/Repos/kanban-board-backend`), not in this isolated worktree, so there was nothing
real to accidentally read here.

To answer the underlying question — **does `gitleaks dir` (the working-tree scan mode)
respect `.gitignore`, or would it read a real `.env` if one were present?** — a synthetic
canary was used instead of a real secret: a scratch file matching the `.env*` gitignore
pattern (`.env.gitleaks-canary`), confirmed gitignored via `git check-ignore -v`, containing a
fake-but-correctly-shaped AWS access key (`AKIATESTFAKEKEY23456` — passes gitleaks'
`aws-access-token` rule's `[A-Z2-7]{16}` regex and 3.0 entropy floor, entropy measured at
3.508695; **not** a real credential, base32-shaped test data only). Result:

```
Finding:     AWS_ACCESS_KEY_ID=AKIATESTFAKEKEY23456
RuleID:      aws-access-token
File:        /repo/.claude/worktrees/agent-a9aeb46f634574b7e/.env.gitleaks-canary
```

**Confirmed: `gitleaks dir` does NOT respect `.gitignore` — it is a raw filesystem walk and
reads gitignored files.** If a real `.env`/`.env.prod` existed in a directory being scanned by
`dir` mode, that scan's report would itself be a live secret (T-hn1-02), exactly as the plan's
threat model anticipated. The canary file was deleted immediately after this one observation
(`rm -f .env.gitleaks-canary`; confirmed removed via `git status --short`).

This is precisely why Task 2's pre-commit hook design uses **`git --staged` mode, not `dir`
mode** — staged-diff mode only ever sees what is in git's index, and git never stages a
gitignored path. The hook is therefore structurally incapable of reading `.env`/`.env.prod`
regardless of whether they exist in a given developer's working tree, which this measurement
now confirms is a load-bearing property of the design, not an assumption.

## Full-history scan (all refs, redacted)

Command: git-mode, full-history variant above, `--log-opts="--all"`.

- **Commits scanned:** 614 (a second, later re-run of the identical command reported 615;
  the one-commit difference is unexplained — no commit was made between the two runs — and is
  recorded honestly rather than smoothed over. It did not change the finding set or count in
  either run and is far too small relative to 648 total commits-on-current-branch /
  652 commits-across-all-refs to indicate a scope error; most likely explanation is gitleaks'
  own reachability walk resolving a merge or duplicate-ref boundary slightly differently
  between invocations, not investigated further since it does not affect the triage below).
- **Wall-clock:** 13.6s scan time (13.9s total including container startup) for the
  no-config baseline run; 13.9s for the `.gitleaks.toml`-in-force re-run. ~9.01 MB scanned.
- **Total findings (no config):** 13
- **By rule:** `curl-auth-user`: 12, `generic-api-key`: 1
- **By path prefix:** `.github/`: 11, `.planning/`: 1, `src/`: 1
- **Redaction confirmed working:** every finding's `Secret` field reads literally `"REDACTED"`
  in the JSON report; `Match` field redacts the captured group inline
  (`"curl -s -X DELETE -u REDACTED "`). No unredacted secret value is present in
  `.gitleaks-reports/full-history.json` or any other report this task produced.

### Triage table (all 13 baseline findings, file:line + commit evidence)

| # | Rule | File:Line | Commit | Date | Verdict |
|---|------|-----------|--------|------|---------|
| 1 | curl-auth-user | `.planning/phases/05-infra-migration/05-PATTERNS.md:158` | `9dc8979a77` | 2026-08-04 | **False positive.** Prose documenting `deploy.yml`'s own `curl -u "$DOCKERHUB_USER:${{ secrets.DOCKERHUB_TOKEN }}"` idiom; `git show` confirms the literal text is the GitHub Actions secrets-template reference, not a resolved value. |
| 2 | curl-auth-user | `.github/workflows/deploy.yml:116` | `0d4e606429` | 2025-06-26 | **False positive.** Same `${{ secrets.DOCKERHUB_TOKEN }}` interpolation, confirmed via `git show 0d4e606429:.github/workflows/deploy.yml`. |
| 3 | curl-auth-user | `.github/workflows/deploy.yml:124` | `0d4e606429` | 2025-06-26 | **False positive.** Same as #2, second `curl -X DELETE` call in the same file/commit. |
| 4 | curl-auth-user | `.github/workflows/deploy.yml:141` | `0d4e606429` | 2025-06-26 | **False positive.** Same as #2, third call site (`cleanup-unused-image` job). |
| 5 | curl-auth-user | `.github/workflows/deploy.yml:91` | `55e2ddc456` | 2025-06-25 | **False positive.** Same interpolation pattern, earlier commit in the file's history. |
| 6 | curl-auth-user | `.github/workflows/deploy.yml:99` | `55e2ddc456` | 2025-06-25 | **False positive.** Same as #5, second call site. |
| 7 | curl-auth-user | `.github/workflows/deploy.yml:86` | `1e836f850e` | 2025-06-25 | **False positive.** Same pattern, earlier still in file history. |
| 8 | curl-auth-user | `.github/workflows/deploy.yml:94` | `1e836f850e` | 2025-06-25 | **False positive.** Same as #7, second call site. |
| 9 | curl-auth-user | `.github/workflows/deploy.yml:85` | `2f6982d727` | 2025-06-25 | **False positive.** Same pattern, at the commit that first added the Dockerfile/deploy scaffold. |
| 10 | curl-auth-user | `.github/workflows/deploy.yml:93` | `2f6982d727` | 2025-06-25 | **False positive.** Same as #9, second call site. |
| 11 | curl-auth-user | `.github/workflows/deploy.yml:91` | `4113d6b055` | 2025-06-25 | **False positive.** Same pattern, at the commit that first added the CI/CD workflow file. |
| 12 | curl-auth-user | `.github/workflows/deploy.yml:99` | `4113d6b055` | 2025-06-25 | **False positive.** Same as #11, second call site — this is the file's very first version. |
| 13 | generic-api-key | `src/main/resources/application.properties:10` | `5121740f61` | 2025-06-05 | **Genuine credential value — NOT a false positive.** `spring.datasource.password=<32-hex-char literal>` against `jdbc:postgresql://localhost:5432/test-kanban`. Confirmed via `git show 5121740f61:src/main/resources/application.properties`. Superseded: HEAD's `application.properties` uses `${DB_HOST}`/`${DB_USER}`/`${DB_PASS}` env-var placeholders (confirmed via `grep -n datasource src/main/resources/application.properties`), so this literal value has not been live in the tracked config since this commit. `git log --all -p -S "<the literal value>"` shows the string appears in exactly two commits total: `5121740f61` (added) and a later `fb9bda5` (the whole file deleted) — never reused, never reintroduced. It targets `localhost:5432` only, so even if the literal string were still a real credential somewhere, it grants at most local-machine Postgres access, not a reachable resource. This finding is deliberately **not allowlisted** in `.gitleaks.toml` (see that file's comment) — it is real, carried, and surfaced at the blocking checkpoint below as Fork A evidence, not silently resolved by this task. |

**11 of 13 findings share one root cause** (the GitHub Actions `${{ secrets.* }}` shape), which
is why `.gitleaks.toml` encodes it as a single narrow rule-scoped allowlist entry (one
`regexTarget = "match"` regex against `curl-auth-user`) rather than 12 near-identical
line-pinned entries that would silently stop matching the moment `deploy.yml` is edited again.

### Re-scan with `.gitleaks.toml` in force

Same command, `-c .gitleaks.toml` added. **Result: exactly 1 finding remains** —
`generic-api-key` at `src/main/resources/application.properties:10`, commit `5121740f61` — the
one deliberately-not-allowlisted, consciously-carried finding from the table above. All 12
`curl-auth-user` false positives are suppressed. Matches the plan's automated verify criterion
exactly ("a count whose every remaining member is listed in MEASUREMENTS.md as consciously
carried").

## Working-tree scan (current file contents on disk, redacted)

Command: `gitleaks dir --redact --no-banner -f json -r .../working-tree.json <worktree>`.

- **Wall-clock:** 5.65s scan time (11.3s total including container startup). ~7.90 MB scanned.
- **Total findings:** 3, all `curl-auth-user`, all the identical `${{ secrets.DOCKERHUB_TOKEN }}`
  false positive confirmed above, still present in the current tree:
  - `.github/workflows/deploy.yml:148`
  - `.github/workflows/deploy.yml:156`
  - `.planning/phases/05-infra-migration/05-PATTERNS.md:158`
- **No `application.properties` finding** — confirms the historical password (triage row #13)
  is genuinely absent from the current working tree, not merely unstaged.
- **No `.env`/`.env.prod` finding** — consistent with those files not existing in this
  worktree (see above); the canary test already independently confirmed `dir` mode *would*
  have read them had they been present.

## Redaction / baseline interaction — settled empirically, not assumed

Generated the full-history redacted JSON report, then re-ran the identical full-history scan
with `--baseline-path` pointed at that same **redacted** report.

- **Result: `no leaks found`, 0 findings, exit 0.** All 13 original findings were suppressed
  by a baseline file whose `Secret`/`Match` fields contain only the literal string
  `"REDACTED"`, not the original secret values.
- **Conclusion: redaction and baseline suppression are compatible.** Gitleaks fingerprints a
  finding by file/line/rule/commit identity (its `Fingerprint` field,
  `<path>:<rule-id>:<line>` for working-tree findings, plus commit SHA for history findings),
  not by the literal secret text — so `--redact` can be applied unconditionally to every report
  this project ever produces, with no loss of baseline-matching capability if a baseline file
  is ever adopted. This directly informs the checkpoint's Fork B decision: choosing
  option-b (baseline file) carries no redaction-compatibility risk, should that be the
  human's preferred rung.
- Wall-clock: 18.6s scan time (19.5s total) for this baseline-suppressed run — no meaningful
  speed difference from the no-baseline full-history run, since gitleaks still walks and
  matches every commit before consulting the baseline to filter results.

## Deliberately deferred (new todos filed, not fixed in this task)

- The `commondir`-relative-path mount finding above only handles worktrees nested under the
  main repository root (this repo's own `.claude/worktrees/<name>` convention). A worktree
  created entirely outside the main repo's directory tree (a different drive, a sibling
  directory reached only by `..`, etc.) cannot be captured by this task's single-mount
  strategy and would need its own fix if this repo's worktree convention ever changes.
