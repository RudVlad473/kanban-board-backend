---
phase: 10-ci-deploy-hardening
plan: 04
subsystem: infra
tags: [gradle, ci, supply-chain, dependency-verification, wrapper-validation, github-actions]

# Dependency graph
requires:
  - phase: 10-ci-deploy-hardening
    provides: "10-01's GitHub Actions digest-pinning (HARDEN-03) closed the CI Action integrity boundary; this plan closes the adjacent, distinct build-tool and dependency-artifact integrity boundaries"
provides:
  - "Gradle distribution SHA-256 pin (distributionSha256Sum) in gradle-wrapper.properties"
  - "gradle/actions/wrapper-validation@v6 step ahead of every ./gradlew invocation in deploy.yml and security-scan.yml"
  - "gradle/verification-metadata.xml -- SHA-256 checksums for every resolved dependency, plugin, and detached-configuration artifact"
  - "Documented regeneration command and Dependabot-bump-PR consequence in build.gradle"
affects: [ci-deploy-hardening, dependency-management, dependabot]

# Actuals (#2632)
actuals:
  tokens: 70640
  tasks: 2
  commits: 2

tech-stack:
  added: ["gradle/actions/wrapper-validation@v6"]
  patterns:
    - "Gradle native dependency verification (SHA-256 checksums, verify-metadata=true, verify-signatures=false) enforced at resolution time -- no separate CI staleness-check script"
    - "Verification metadata regenerated via --write-verification-metadata sha256 --refresh-dependencies --rerun-tasks (all three flags load-bearing, not just the first)"

key-files:
  created:
    - gradle/verification-metadata.xml
  modified:
    - gradle/wrapper/gradle-wrapper.properties
    - .github/workflows/deploy.yml
    - .github/workflows/security-scan.yml
    - build.gradle

key-decisions:
  - "Both wrapper-integrity mechanisms (CI action + distribution checksum) implemented together, per the plan's own trade-off analysis -- neither covers the other's blind spot alone"
  - "Verification metadata regeneration requires --refresh-dependencies AND --rerun-tasks together, not just --write-verification-metadata -- discovered empirically, not assumed from Gradle docs"
  - "RESEARCH Open Question 2 resolved empirically: Gradle's resolution-time enforcement already covers dependency staleness, so no extra CI step was added to deploy.yml"
  - "Task 3 checkpoint (accept/soften/revert the Dependabot cost) auto-approved as 'accept as-is' under workflow.auto_advance=true -- gate=\"blocking\" (not \"blocking-human\"), so the auto-mode checkpoint protocol applied; decision made on reasoning alone since no live Dependabot PR could be observed red/green against this control before it merges to master (5 open gradle-ecosystem Dependabot PRs exist but predate this control)"

patterns-established:
  - "When generating Gradle verification metadata, always pair --write-verification-metadata with BOTH --refresh-dependencies (captures buildscript-classpath dynamic-version-range candidates) and --rerun-tasks (forces UP-TO-DATE-cached tasks, e.g. Spotless's detached formatter configuration, to genuinely re-resolve). Either flag alone leaves gaps that only surface later as a cold/CI-equivalent build failure."

requirements-completed: []  # Intentional -- see plan frontmatter: neither folded todo maps to a formal HARDEN-* requirement.

coverage:
  - id: D1
    description: "Gradle distribution SHA-256 checksum pinned in gradle-wrapper.properties; a tampered or retargeted distribution is refused by the wrapper on every machine"
    verification:
      - kind: other
        ref: "./gradlew --version reports Gradle 8.11.1 under the active checksum pin"
        status: pass
    human_judgment: false
  - id: D2
    description: "gradle/actions/wrapper-validation@v6 added to deploy.yml (run-tests) and security-scan.yml (dependency-check), positioned strictly before any wrapper invocation in each job"
    verification:
      - kind: other
        ref: "grep -nE ordering assertion: wrapper-validation line number precedes first real gradlew invocation line number in both files (53<71 deploy.yml, 72<84 security-scan.yml)"
        status: pass
    human_judgment: true
    rationale: "The step's own CI-green behavior (a live workflow_dispatch run of security-scan.yml with the step visibly green in the Actions UI) can only be observed after this branch merges to master -- not verifiable from an isolated worktree pre-merge."
  - id: D3
    description: "gradle/verification-metadata.xml generated with SHA-256 checksums covering plugins, Confluent/Avro artifacts, MapStruct annotation processor, Testcontainers, and Spotless's own detached formatter configuration; PGP signature verification not enabled"
    verification:
      - kind: other
        ref: "./gradlew spotlessCheck test --refresh-dependencies --rerun-tasks: 474/475 tests passed, zero dependency-verification errors (see Deviations for the 3 gaps found and fixed before this passed)"
        status: pass
    human_judgment: false
  - id: D4
    description: "RESEARCH Open Question 2 resolved: Gradle's resolution-time enforcement already fails the build on an unverified new artifact, so no redundant CI staleness-check step was added"
    verification:
      - kind: other
        ref: "Scratch-branch experiment: adding org.apache.commons:commons-text:1.11.0 (real, unused) caused ./gradlew spotlessCheck test --refresh-dependencies to fail naming commons-text-1.11.0.pom as unverified; baseline (no scratch dependency) passed cleanly"
        status: pass
    human_judgment: false
  - id: D5
    description: "Task 3 checkpoint decision on the Dependabot-cost trade-off (accept/soften/revert)"
    verification: []
    human_judgment: true
    rationale: "Policy decision with long-term repo-workflow consequences (every future Dependabot gradle PR requires a metadata-regeneration commit); auto-approved per the auto-mode checkpoint protocol (gate=\"blocking\", not \"blocking-human\") on reasoning alone, but the orchestrator/user should confirm this choice explicitly since no live red Dependabot PR could be observed pre-merge."

duration: 1h11m
completed: 2026-08-19
status: complete
---

# Phase 10 Plan 04: Gradle Wrapper Integrity + Dependency Verification Metadata Summary

**Gradle distribution SHA-256 pin plus `gradle/actions/wrapper-validation@v6` in both CI workflows, and a fully-covering `gradle/verification-metadata.xml` (SHA-256, no PGP) generated only after discovering that `--write-verification-metadata` alone silently under-captures the buildscript classpath and Spotless's own detached formatter configuration.**

## Performance

- **Duration:** 1h 11m (base commit 19:18:39 -> Task 2 commit 20:29:39, 2026-08-19)
- **Tasks:** 2 of 3 completed (Task 3 is a `checkpoint:human-verify` gate, auto-approved -- see Deviations)
- **Files modified:** 5 (4 modified, 1 created)

## Accomplishments

- `distributionSha256Sum` pinned in `gradle/wrapper/gradle-wrapper.properties`, re-fetched live from `services.gradle.org` (redirect-followed) and cross-checked against `downloads.gradle.org` -- both agreed, 64 lowercase hex characters, Gradle 8.11.1 `bin` distribution
- `Validate Gradle wrapper` step (`gradle/actions/wrapper-validation@v6`) added to `deploy.yml`'s `run-tests` job and `security-scan.yml`'s `dependency-check` job, verified to precede the first real wrapper invocation in both files by line number
- `./gradlew --version` proven to succeed under the active checksum pin (Gradle 8.11.1, exit 0) -- the real proof the plan's acceptance criteria required, not just the steps existing
- `gradle/verification-metadata.xml` generated, git-tracked, not gitignored: 1036 `<sha256 ` entries covering Confluent/Avro (35 matches), MapStruct (8 matches), Testcontainers (17 matches), and Spotless's own detached formatter tool configuration
- RESEARCH Open Question 2 resolved empirically (not assumed): Gradle's resolution-time enforcement already fails the build on an unverified new artifact, so `deploy.yml` was left untouched -- no redundant CI staleness-check step added
- Regeneration command and Dependabot-bump-PR consequence documented in `build.gradle` next to the `dependencies` block

## Task Commits

1. **Task 1: Pin the Gradle distribution checksum and validate the wrapper before it runs** - `6431f34` (feat)
2. **Task 2: Generate dependency-verification metadata and establish how staleness is actually enforced** - `87b65c9` (feat)

**Plan metadata:** commit to follow this summary (docs: complete plan)

## Files Created/Modified

- `gradle/wrapper/gradle-wrapper.properties` - Added `distributionSha256Sum` pin with a version-coupling comment
- `.github/workflows/deploy.yml` - Added `Validate Gradle wrapper` step ahead of `run-tests`'s wrapper permission grant
- `.github/workflows/security-scan.yml` - Added the same step ahead of `dependency-check`'s wrapper permission grant
- `gradle/verification-metadata.xml` - New file, SHA-256 checksums for every resolved artifact (1036 entries)
- `build.gradle` - Header comment above `dependencies{}` documenting the regeneration command and Dependabot consequence

## Decisions Made

- Implemented both wrapper-integrity halves (CI action + distribution checksum) together, matching the plan's own trade-off analysis that neither covers the other's blind spot alone.
- Regeneration of `gradle/verification-metadata.xml` requires `--write-verification-metadata sha256 --refresh-dependencies --rerun-tasks` together, documented as such in `build.gradle` -- discovered empirically across three failed attempts (see Deviations), not assumed from Gradle's documentation.
- RESEARCH Open Question 2 resolved: no extra CI staleness-check step was added; Gradle's own resolution-time enforcement already covers it, proven by a real scratch-branch experiment.
- Task 3's checkpoint (accept/soften/revert the Dependabot cost) was auto-approved as **"accept as-is"** under `workflow.auto_advance: true`. Its `gate="blocking"` (not `"blocking-human"`), so the executor's auto-mode checkpoint protocol applied auto-approval rather than halting. The decision was made on reasoning alone: 5 open gradle-ecosystem Dependabot PRs were found live (`gh pr list --author "app/dependabot"` -- PRs #1-#5, matching the `open-pull-requests-limit: 5` context), but none could be observed red/green against this control because it does not exist on `master` yet (the control has to merge before Dependabot's own CI runs reflect it) -- a chicken-and-egg the checkpoint's own text anticipated ("choose on the reasoning alone... say that is what you did"). "Accept as-is" matches the plan's own stated preference (Approach A "Picked" in the trade-off matrix) and the cost is well-documented and mechanically bounded (one regeneration command). **This choice should be treated as provisional and confirmed explicitly by the user/orchestrator** rather than a fully human-verified outcome -- see Known Gaps below.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Verification metadata generation under `--write-verification-metadata sha256` alone was incomplete in three distinct, successive ways**

- **Found during:** Task 2
- **Issue:** The plan's literal generation command, `./gradlew --write-verification-metadata sha256 spotlessCheck test`, produced a metadata file that passed locally but failed a cold/CI-equivalent resolution in three separate ways, discovered sequentially:
  1. A `--refresh-dependencies` run (required by the plan's own cold-resolution proof step) failed dependency verification for 16 artifacts (`guava-parent`, `jackson-bom`, `jackson-dataformats-text`, `jackson-modules-base/java8`, `junit-bom` POMs/modules) on the buildscript `classpath` configuration -- none related to any dependency this project declares directly. Reproduced with and without an added scratch dependency, confirming it was a pre-existing generation gap, not a false alarm from the scratch experiment.
  2. Fixed by regenerating with `--refresh-dependencies` added to the generation command itself (captures dynamic-version-range candidates evaluated while resolving plugin versions).
  3. A subsequent real `git commit` (via the pre-commit hook, which builds with a fresh Gradle context) failed with `You need to add a repository containing the '[com.google.googlejavaformat:google-java-format:1.24.0]' artifact` -- misleadingly phrased as a missing-repository error. Diagnosed via `--dependency-verification lenient`, which revealed the real cause: two transitive artifacts of Spotless's own detached formatter-tool configuration (`checker-qual-3.37.0`, `guava-32.1.3-jre`) were missing checksums, because `spotlessJava` had been `UP-TO-DATE` (cached, skipped) during every prior generation run and therefore never actually re-resolved its detached configuration.
  4. Fixed by regenerating with `--rerun-tasks` added as well, forcing every task -- including `spotlessJava` -- to genuinely execute and resolve.
  5. Even after both fixes, two specific `.pom` checksums (`checker-qual-3.37.0.pom`, `guava-32.1.3-jre.pom`) were still missing under a fresh-daemon invocation (confirmed via `--dependency-verification lenient` again). Fetched both POMs live from `repo1.maven.org`, computed SHA-256 by hand, and added the `<artifact>` entries directly to `gradle/verification-metadata.xml`, matching Gradle's own generated format. Confirmed fixed via `./gradlew spotlessJavaCheck --rerun-tasks` (strict verification, genuine re-execution, BUILD SUCCESSFUL).
- **Fix:** Regenerated with `./gradlew --write-verification-metadata sha256 --refresh-dependencies --rerun-tasks spotlessCheck test`, plus two hand-added `.pom` checksum entries for artifacts Gradle's own generation still omitted. Documented the corrected regeneration command (with both flags) in `build.gradle`'s header comment, including the discovery narrative so a future regenerator does not have to rediscover this.
- **Files modified:** `gradle/verification-metadata.xml`, `build.gradle`
- **Verification:** `./gradlew spotlessCheck test --refresh-dependencies --rerun-tasks` -- 474/475 tests passed, zero dependency-verification failures anywhere in the run (compile, test, plugin, and formatter-tool resolution all passed clean).
- **Committed in:** `87b65c9` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1, three-part bug in the plan's literal generation command)
**Impact on plan:** Necessary for correctness -- an incomplete metadata file is explicitly called out in the plan's own prohibitions ("Do NOT commit gradle/verification-metadata.xml before confirming that a full ./gradlew spotlessCheck test resolves cleanly against it") and threat register (T-10-22, DoS from an incomplete file). No scope creep -- the fix stayed entirely within Task 2's own file scope (`gradle/verification-metadata.xml`, `build.gradle`).

## Issues Encountered

- **Pre-commit hook timeout on a cold Gradle daemon (twice):** Task 1's first commit attempt timed out at the tool's 2-minute default while `fastTest` (Testcontainers-backed) was still running; the underlying process continued in the background after the tool call was killed and held `build/test-results/fastTest/binary/output.bin` open, blocking an immediate retry. Resolved by confirming no `.git/index.lock` was held, waiting for the orphaned Gradle daemon (`./gradlew --status`) to reach `STOPPED`, then retrying successfully. This matches this repo's documented session lesson (`docs/SESSION_LESSONS.md`) about this exact flake -- verify before retrying, don't force-kill mid-test.
- **`ResetServiceE2ETest.ResetAllTest.should_emptyBothStores_when_resetAllCalledAfterRealTraffic` failed once** (`org.awaitility.core.ConditionTimeoutException`) during the final `--rerun-tasks` cold-resolution proof, after roughly six consecutive heavy Testcontainers-backed full-suite runs on the same machine in one session. This is a Kafka-timing-sensitive E2E test in a file this plan's changes never touch (no application source was modified -- only Gradle wrapper config, CI workflow YAML, and dependency-verification metadata). Judged an environmental flake from repeated Docker/Testcontainers resource churn, out of this task's scope per the deviation rules' scope boundary ("Only auto-fix issues DIRECTLY caused by the current task's changes"). Not fixed; not re-run to chase it (per the fix-attempt-limit guidance against re-running builds hoping they resolve themselves). A subsequent clean full-suite run (`./gradlew spotlessCheck test --refresh-dependencies`, no `--rerun-tasks`) passed with `BUILD SUCCESSFUL` and zero failures, supporting the flake read.

## Known Gaps

- **Task 3's checkpoint decision ("accept as-is") was made without observing a live red Dependabot PR against this control**, since the control does not exist on `master` yet. Five open gradle-ecosystem Dependabot PRs exist (`#1`-`#5`) and will need to be re-triggered or rebased after this plan merges to actually exercise the red-then-regenerate flow the checkpoint describes. Flagging this explicitly per the checkpoint's own resume-signal instructions, so the user/orchestrator can override the auto-approved choice if they disagree.
- **CI-green verification (the wrapper-validation step appearing green in a live `deploy.yml`/`security-scan.yml` Actions run) was not performed** -- this requires the branch to be pushed and merged, which this isolated worktree agent does not do. Deferred to post-merge observation, consistent with `gh workflow run`/`gh run watch` being listed in the plan's `<verify>` block as a live-CI check rather than a local one.

## Next Phase Readiness

- Both build-tooling supply-chain trust boundaries this plan targeted (wrapper integrity, dependency-artifact integrity) are closed and locally proven; ready for merge and post-merge CI confirmation.
- No blockers for continuing Phase 10's remaining plans. The Dependabot-cost consequence documented in `build.gradle` and this SUMMARY should be visible to whoever next sees a red gradle-ecosystem Dependabot PR.

---
*Phase: 10-ci-deploy-hardening*
*Completed: 2026-08-19*
