---
quick_id: 260820-euc
type: quick
files_modified:
  - .github/workflows/deploy.yml
  - .planning/todos/pending/*.md (7 files, moved)
  - .planning/todos/completed/*.md (7 files, moved + annotated)
---

<objective>
Close two small, well-scoped pending todos surfaced while auditing `.planning/todos/pending/`
after milestone v1.3's phases went green:

1. `2026-08-19-deploy-yml-has-no-path-filter-so-docs-only-pushes-trigger-a-full-deploy.md` —
   `deploy.yml`'s `push:` trigger has no path filter, so a pure docs/planning change (observed
   live during Phase 10: commit `00dd644`, README+`.planning/` only) triggers a full
   production+nonprod redeploy — harmless but wasteful (real deploy time, Docker Hub bandwidth,
   VM restart churn).
2. Seven other pending todos, all tagged `resolves_phase: 10`, turn out to already be satisfied
   by Phase 10's actual delivered work (verified by direct inspection of the current repo state,
   not assumed from the todo text) — they were never moved to `completed/` once the work landed.
</objective>

<task id="1">
  <name>Add a paths-ignore filter to deploy.yml's push trigger</name>
  <files>.github/workflows/deploy.yml</files>
  <action>
Add a `paths-ignore:` block under the `push:` trigger excluding `docs/**`, `**/*.md`, and
`.planning/**` — the todo's own recommended scope, deliberately NOT excluding `.github/**` as a
whole since a workflow or Dependabot config change genuinely needs a real deploy-pipeline run.
  </action>
  <verify>
`python -c "import yaml; yaml.safe_load(open('.github/workflows/deploy.yml'))"` parses clean.
Live proof (not just static): after this fix is pushed, a subsequent docs-only push (this same
quick task's own closing commit, which touches only `.planning/**`) is confirmed via
`gh run list --workflow=deploy.yml` to NOT trigger a new run.
  </verify>
  <done>`.github/workflows/deploy.yml`'s `on.push` carries the `paths-ignore` list; a real
  docs-only push after the fix landed produced no new `CI/CD with Docker` run.</done>
</task>

<task id="2">
  <name>Close 7 already-resolved pending todos</name>
  <files>.planning/todos/pending/*.md, .planning/todos/completed/*.md</files>
  <action>
For each of the 7 `resolves_phase: 10`-tagged pending todos confirmed already satisfied by direct
repo inspection (gradle cache in deploy.yml, dependabot github-actions ecosystem entry, appleboy
digest pins + D-05 risk-acceptance comment, security-scan.yml comment/version fix, gradle wrapper
checksum + wrapper-validation step in both workflows, gradle/verification-metadata.xml, README
architecture-showcase expansion): run `gsd-tools todo complete <filename>` to move it to
`completed/`, then append a short `## Resolution (2026-08-20)` section citing the specific
evidence found.
  </action>
  <verify>`.planning/todos/pending/` no longer contains any of the 7 files; each moved file in
  `.planning/todos/completed/` carries a `## Resolution (2026-08-20)` section.</verify>
  <done>7 files moved and annotated.</done>
</task>
