---
status: complete
quick_id: 261005-o6t
---

# Quick 261005-o6t: trim comments to the code-comments rubric, add a comment lint

Per-task detail: `261005-o6t-T1..T5-SUMMARY.md` beside this file.

## Result (`verify-comments.py stats`, `comment-pass-start` vs branch tip)

| | before | after |
|---|---|---|
| in-scope files | 299 | 301 |
| comment lines | 9346 (25.7%) | 7489 (20.8%) |
| blocks over 8 prose lines | 210 | 124 (all behind a `Decisions:`-style marker) |
| planning-id hits (D-NN, T-NN-NN, quick-task ids) | 761 | 0 (14 remain in frozen Flyway migrations) |

## Verified by running it
- `./gradlew spotlessCheck test` on the merged tree: BUILD SUCCESSFUL, jacoco verification passed.
- `verify-comments.py check` exits 0 on the whole tree; selftest 40/40.
- `equiv --base comment-pass-start` proves no code token changed; the only allow-listed files are the
  Alloy and Traefik embedded-config comments (T4's rollout commit).

## Not verified / open
- Rollout commit `8fec86e` renames ConfigMap `postgres-init-62tc4bg5h4` to `postgres-init-68f97kd279`
  (restarts the shared Postgres) and redeploys Alloy and Traefik. Keep or revert at the merge gate.
- Commits used `--no-verify` (user-authorised); gitleaks ran as `dir` scans, not on the staged diff.
- Only the YAML lexer was cross-checked against an independent parser.
- Rubric rules 0, 1, 2, 5, 6, 7 need judgment and are review-only, not lint-checked.

## Left for follow-up (deleted comments with no tracked item)
- `TaskService.findById`: "make a service interface". `TaskControllerTest`: cascade-deletion test.
- `application.properties`: "revisit broker bounds (KAFKA-V2-01)". `deploy.yml`: arm64 revisit.
- `ValidationConstants.TASK_DESCRIPTION_LENGTH_VALIDATION_MESSAGE` interpolates the title constants.
- docs/learning line-anchored links into tests, build.gradle and workflows have shifted.
- `.claude/CLAUDE.md` Comments section still recommends extensive Javadoc, contradicting CODE_STYLE rule 14.
