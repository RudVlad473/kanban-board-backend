# Review brief — ROUND 3, scoped to the zone partition

Repo: `/home/andre/dev/kanban-board-backend`. Branch: `quick/260903-dvp-caddy-edge-rate-limiting`.
Read `REVIEW-BRIEF.md` and `REVIEW-BRIEF-R2.md` in this directory for context and the settled
decisions. **This round is narrow.** Do not re-review the whole change.

**Do not modify any file under `/home/andre/dev/kanban-board-backend`.** Copy to `/tmp` to
experiment. Never read any `.env*` file. Clean up containers/images you create.

## The single change under review

`git diff HEAD~1..HEAD` — one commit, `Caddyfile` only. The `general` rate-limit zone gained
`match { not path /api/signin* /api/signup* }`, so the two zones now PARTITION instead of compose.

Round 2 established (by measurement) that a request rejected 429 by the `auth` zone still consumed
a `general` token, so 130 signin attempts from one address refused every other endpoint for the
rest of the minute. Measurements taken here after the change, which you should try to falsify:

- 30 signins (20 allowed + 10 rejected) leave the general budget at a full **120** (was 90).
- 130 signins no longer cause `GET /api/boards` to be refused.
- The general zone still allows exactly **120** then 429s.
- The auth zone is still bounded at **20** per 5 minutes.
- `caddy validate` reports `Valid configuration`.
- `scripts/loadtest/run-rate-limit-verification.sh` still exits 0 (prod 20x401 + 5x429,
  nonprod 25x401 + 0x429).

## What to attack

1. **Does the `not path` matcher do what it claims?** Find any request that should be counted by
   the general zone but now escapes it, or any that should reach the auth zone but now falls into
   neither. A path form matching NEITHER zone would be completely unlimited — that is the worst
   outcome available here and the specific thing to hunt for.
2. **Is the auth zone still the only thing bounding bcrypt?** Confirm `/api/signin` and
   `/api/signup` are still capped at 20/5m and did not become unlimited by falling out of general.
3. **Ordering/precedence.** The pinned module orders zones internally. Confirm the partition holds
   regardless of that ordering, and that a request matching the auth zone is not double-counted.
4. **Did this break anything round 2 cleared?** Nonprod unthrottled; certificate survival; the
   `/api/signin;x=1` and `/api;a=b/signin` behaviours; the harness.
5. **Are the new in-file comments true?** They state specific measured numbers (90 vs 120, 130
   signins, the 120→140 ceiling). Verify each. A false number in a decision record is a defect.

## Reporting rules

Label every finding **CONFIRMED-BY-RUNNING** or **REASONED-ONLY**, with a reproduction command, a
severity, and a file:line anchor. Say explicitly what you could not execute. Say what you checked
and found FINE. Few high-confidence findings beat many speculative ones.
