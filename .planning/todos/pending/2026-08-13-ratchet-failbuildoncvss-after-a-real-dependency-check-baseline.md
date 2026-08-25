---
created: 2026-08-13T20:05:00.000Z
title: Ratchet dependencyCheckAnalyze's failBuildOnCVSS after a real baseline exists
area: tooling
severity: minor
files:

  - build.gradle

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## ASVS 4.0.3 cross-reference

A 33-agent ASVS 4.0.3 Level 2 audit independently re-confirmed via **V1.14.3** (Configuration
Architecture) that `build.gradle`'s `failBuildOnCVSS = 11` is still functionally disabled (11 is
above the 0-10 CVSS scale) and `security-scan.yml`'s `dependencyCheckAnalyze` step is still
report-only with no `continue-on-error` gate change — no new information, corroborates the
existing todo is still accurate.

## Problem

`dependencyCheckAnalyze` (quick task 260813-q1i, Task 4) ships report-only:
`failBuildOnCVSS = 11` (never fails; no CVSS score reaches 11). This was a
deliberate rung, not laziness — even after the Spring Boot 3.5.0 -> 3.5.16 bump
(Task 2) cleared the bulk of the measured advisory baseline, dependency-check's
CPE fuzzy matching against NVD is expected to report *more* findings than the
OSV-measured baseline (5 findings: 0 CRITICAL / 1 HIGH / 4 MODERATE, per
`260813-q1i-MEASUREMENTS.md`), with the difference being false positives that
need triage before a hard gate would be honest.

No dependency-check run has actually happened against this repo yet — no
`NVD_API_KEY` was available in the local execution environment when Task 4 ran,
so the real, CPE-matched baseline (as opposed to OSV's exact-coordinate lower
bound) does not exist yet. It will be produced by the first `workflow_dispatch`
or scheduled run of `.github/workflows/security-scan.yml`.

## Solution

Once a real `dependencyCheckAnalyze` run has completed (the weekly schedule or a
manual `workflow_dispatch`) and its HTML/JSON report is available:

1. Review every finding. Suppress genuine false positives via the plugin's
   `suppressionFile` mechanism, matching on `<packageUrl>` (stable across
   rebuilds) rather than `<sha1>` (which changes on any version bump), and
   cite the evidence for each suppression — do not suppress on faith.

2. With the suppressed count as the real, actionable baseline, pick a
   `failBuildOnCVSS` rung from that number, the same measure-first-then-gate
   sequence Error Prone (quick task 260802-qr8) and JaCoCo (quick task
   260812-eg8) both followed.

3. Update `build.gradle`'s `dependencyCheck` block and its accompanying
   plugin-block comment to reflect the new rung and the baseline it was chosen
   from.

**Do not skip straight to a strict gate on an unreviewed report** — CPE fuzzy
matching over-reports, and a strict gate on day one would red `master` on
findings this repo neither caused nor can immediately fix (the same reasoning
that kept this rung at report-only in the first place).
