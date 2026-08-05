---
created: 2026-08-03T15:55:00.346Z
rewritten: 2026-08-06
title: Evaluate NullAway for null-safety dataflow analysis
area: tooling
severity: minor
files:
  - build.gradle
---

## Problem

Nothing in this repo's lint stack performs null-dereference **dataflow** analysis. Error Prone
2.50.0 catches bug *patterns* at the AST level, but its default check set does not track whether a
possibly-null value reaches a dereference across method boundaries. Spotless covers formatting,
ArchUnit's `LayeringArchTest` covers layering, and `docs/CODE_STYLE.md` covers judgement-level
style — none of them touch nullability either.

This is a genuine gap, not a redundancy. It is worth naming explicitly because it was previously
filed under a heading ("evaluate PMD/Checkstyle/SpotBugs") whose verdict was *don't adopt* — which
had the effect of parking a real gap under a resolved decision. See the background section below.

## Background: the PMD/Checkstyle/SpotBugs question is settled (2026-08-03, revisited 2026-08-06)

The original form of this todo asked whether to add PMD, Checkstyle, or SpotBugs on top of the
existing stack. That review concluded **no — do not adopt any of the three wholesale**, and that
conclusion still holds on re-examination:

- **Checkstyle — fully redundant.** Its primary job is formatting and naming conventions.
  Spotless with `googleJavaFormat().aosp()` (`build.gradle:25`) *reformats* rather than reports,
  which is strictly stronger. Only naming rules and size metrics remain, neither of which has
  caused a problem here.
- **SpotBugs — redundant except for the gap this todo now tracks.** Error Prone explicitly
  targets the same compile-time bug-pattern space. The one thing SpotBugs does that Error Prone's
  default checks do not is null-dereference dataflow (its `NP_*` detectors) — and NullAway closes
  that specific gap inside the Error Prone plugin already wired up, without adding a second
  bytecode-analysis tool and its own suppression vocabulary to the build.
- **PMD — the weakest of the three claims, and still not worth adopting.** Its complexity and
  design metrics (cyclomatic complexity, god-class, excessive parameter lists) genuinely are not
  enforced by anything today. But that is a *style-judgement* gap that `docs/CODE_STYLE.md`
  addresses by convention, not a correctness gap. Revisit only if complexity actually becomes a
  review pain point in practice.

The operating principle from that review is retained: **scope any addition to the specific gap,
rather than adopting a tool wholesale.** NullAway is that scoped response.

## Solution

Evaluate adding **NullAway** as an Error Prone plugin (not a standalone tool). It attaches to the
`net.ltgt.errorprone` 5.1.0 plugin and `error_prone_core` 2.50.0 analyzer already configured in
`build.gradle:266-292`, so it adds a dependency and a check configuration rather than a new
build stage.

Follow the same conventions the existing Error Prone integration established:

- **Pin the NullAway version exactly**, matching the rationale documented at `build.gradle:243-247`
  — a floating version could red CI or the Docker build on an upstream release with zero local
  code changes.
- **Measure before gating.** Error Prone's gate strength was chosen from a measured run (5
  main-source findings, then a 27-finding test-source backlog triaged to zero). Do the same here:
  run NullAway in warning mode against `com.vrudenko.kanban_board` first, count the findings, and
  decide warn-vs-error from that baseline rather than up front.
- **Reuse the generated-code exclusions.** `excludedPaths` at `build.gradle:269` already covers
  MapStruct's `build/generated/**` and gradle-avro-plugin's `build/generated-main-avro-java/**`;
  neither is hand-written code anyone can act on a finding in.

Things to establish during the evaluation, not assumed:

- **Which nullability annotation set to adopt** (JSpecify, `org.springframework.lang.Nullable`, or
  `jakarta.annotation.Nullable`) — NullAway needs annotated code to be useful, and the repo
  currently has no nullability annotations to build on.
- **Lombok interaction.** Lombok 1.18.36 generates accessors and constructors in-place rather than
  into `build/generated/`, so the existing path exclusion does not cover it. Confirm whether a
  `lombok.config` adjustment is needed before judging the finding count as real.
- **Whether the annotated-package scope should start narrow** (e.g. `service` and `mapper` first)
  rather than the whole `com.vrudenko.kanban_board` tree, if the baseline count is large.

There is no urgency here — this is a preventive-correctness improvement on a codebase with no
known null-dereference defect, so it should not preempt milestone work.
