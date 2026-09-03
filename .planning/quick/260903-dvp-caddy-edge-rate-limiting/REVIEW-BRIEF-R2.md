# Review brief — ROUND 2, after fixes

Repository: `/home/andre/dev/kanban-board-backend`
Branch: `quick/260903-dvp-caddy-edge-rate-limiting`

**Round 1's brief is at `REVIEW-BRIEF.md` in this same directory — read it first for the deployment
context, the settled decisions (D-1..D-5) and the infra-breakage checklist. All of that still
applies.** This file describes only what changed since.

**Do not modify any file in this repository.** Copy to `/tmp` to experiment. Report findings only.

## What round 1 found and what was changed in response

Six commits since (`6a5c576..849a08b`). Review `git diff 5b9487d..HEAD` as the primary target, but
judge the result as a whole — a fix that is locally correct but breaks something round 1 cleared is
the most likely defect now.

- **F1 (was HIGH)** — `deploy.yml`'s post-reload readback used `http://localhost:2019/`, which fails
  every time: Caddy's admin API is IPv4-only and the image's BusyBox `wget` tries the `::1`
  `/etc/hosts` record and treats refusal as terminal. Changed to `127.0.0.1`.
- **F2 (was MEDIUM)** — `/api/signin;x=1` evaded the auth zone's exact-literal `path` matcher and
  spent only the general zone. Matcher changed to `path /api/signin* /api/signup*`.
- **F5 (human decision)** — auth zone budget raised from 10 to 20 per 5 minutes, because the key is
  an IP and a NAT/CGNAT pool shares one bucket.
- **F3 (was MEDIUM)** — `scripts/verify-caddy-image-tag.py` matched a commented-out `--with` line.
  Added `strip_comments()`; corrected the docstring, which overclaimed the tag as content-derived.
- **F4 (was MEDIUM)** — the tag gate only ran on push-to-`main`, while two comments claimed it made
  drift unmergeable. New workflow `.github/workflows/invariant-checks.yml` runs it on
  `push` + `pull_request`.
- **NEW** — `scripts/loadtest/` : an Artillery harness (`rate-limit-prod.yml`,
  `rate-limit-nonprod.yml`, `run-rate-limit-verification.sh`) for post-deploy verification.

## What to scrutinise hardest

1. **The `path /api/signin* /api/signup*` matcher.** It over-matches on purpose. Confirm it cannot
   *under*-match: find any URL form that reaches Spring's signin/signup handler while evading this
   matcher. Also confirm the over-match is harmless — that nothing legitimate and high-volume lives
   under a `/api/signin`- or `/api/signup`-prefixed path.
2. **Did raising the auth zone to 20 break the composition argument?** An auth request spends from
   both zones. Check the interaction of auth 20/5m with general 120/1m — including whether a client
   can now exhaust the general zone through auth requests alone in a way that matters.
3. **`invariant-checks.yml`.** New CI surface. Does it actually run on the PRs it claims to? Check
   the `paths-ignore` groups do not accidentally skip a PR that changes `docker/caddy/Dockerfile` or
   `docker-compose.prod.yml`. Confirm it cannot be green while the gate is failing.
4. **`strip_comments()` in the tag gate.** Try to construct a Dockerfile where it strips something
   it should not, or fails to strip something it should. Line continuations (`\`) and here-docs are
   the interesting cases.
5. **The Artillery harness.** Read it as production-touching code, because it is:
   - Can it ever hit `/api/signup` and create real rows? That is the worst outcome available here.
   - Is the pass/fail logic right in BOTH directions — does it fail against an unlimited endpoint
     and against a fully-throttled one?
   - The nonprod 429 count is asserted in the shell script, not in the YAML. Verify that assertion
     is actually reached and actually fails the run.
   - `run_half` returns a path on stdout and progress on stderr. Check nothing else writes to stdout.
   - Does the retry-after-310s path do the right thing, or can it mask a real failure?
6. **Documentation.** `docs/INFRA_ARCHITECTURE.md` and both `.mmd`/`.png` were updated. Verify every
   changed claim against the branch's own files — especially the new 20/5m and 120/1m numbers in the
   physical diagram, and the claim about where the tag invariant is enforced.

## Rules for your report (unchanged from round 1, and not optional)

1. Label every finding **CONFIRMED-BY-RUNNING** or **REASONED-ONLY**.
2. Give the exact reproduction command. No command = hypothesis, and say so.
3. Severity (HIGH / MEDIUM / LOW) and a file:line anchor per finding.
4. If you could not execute something, say so rather than asserting the outcome.
5. Say explicitly what you checked and found FINE, including any round-1 finding you re-tested.
6. Few high-confidence findings beat many speculative ones. **A regression introduced by one of the
   six fixes is the single most valuable thing you can find.**
