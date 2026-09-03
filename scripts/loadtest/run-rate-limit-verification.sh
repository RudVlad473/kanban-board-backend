#!/usr/bin/env bash
# Post-deploy verification that the Caddy edge rate limiter is live AND correctly scoped
# (quick task 260903-dvp, Task 7). Run this against the real deployment after a deploy lands.
#
#   ./scripts/loadtest/run-rate-limit-verification.sh
#   ./scripts/loadtest/run-rate-limit-verification.sh <prod-url> <nonprod-url>
#
# Exits 0 only when BOTH halves agree: production rejects with 429 once the auth budget is spent,
# and nonprod -- same container, same binary -- never does. Either half alone is not evidence; see
# each YAML's own header for why.
#
# Decisions ──────────────────────────────────────────────────────────────────────────────────────
#   * The nonprod verdict is computed HERE, from the JSON report, not by artillery's `ensure`
#     plugin. `ensure` thresholds are minimums, so there is no way to spell "at most zero 429s" in
#     that block -- asserting it there would look like coverage while enforcing nothing. This is
#     the one assertion in the pair that a reader would most reasonably assume the YAML handles.
#   * Sequential, never parallel. Both halves are per-address counts inside a time window; running
#     them concurrently from one runner shares one source address between them and makes the
#     production budget bleed into the nonprod run.
#   * A failed production half is retried ONCE after the window, not immediately. The most common
#     cause of a spurious failure is a previous run in the same 5-minute window having already
#     spent this address's budget, which makes the first request 429 and the 401 floor unmet.
set -euo pipefail

PROD_URL="${1:-https://kanban-board-rud-vlad-473.duckdns.org}"
NONPROD_URL="${2:-https://kanban-board-nonprod-rud-vlad-473.duckdns.org}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# Pinned deliberately: an unpinned load-test tool makes a red run ambiguous between "the limiter
# regressed" and "the tool changed".
#
# 2.0.24 and NOT 2.0.29 (observed 2026-09-03, reproduced under both pnpm dlx and npx, so this is
# the package and not the package manager): artillery 2.0.29 fails to load every subcommand with
# `[MODULE_NOT_FOUND] Cannot find module '@smithy/node-config-provider'`, then reports
# `Warning: run is not a artillery command`. That message is the trap -- it reads as a bad
# argument, so the natural response is to go re-check the invocation, which is fine. `--version`
# still works on 2.0.29 because it never loads run.js, so a smoke test of the binary does not
# catch this. 2.0.0, 2.0.24 and latest all run cleanly. Falsifier: `pnpm dlx artillery@<ver> run
# --help` printing help rather than a MODULE_NOT_FOUND stack is the entire test for any version.
ARTILLERY="pnpm dlx artillery@2.0.24"

run_half() {
  local name="$1" config="$2" url="$3"
  # Assigned on its own line, NOT folded into the `local` above: bash expands every argument of a
  # `local` statement before assigning any of them, so `report="$OUT/$name.json"` there would
  # expand $name while it is still unset -- silently empty without `set -u`, a hard abort with it.
  local report="$OUT/$name.json"
  # Progress to stderr: stdout is the function's return channel (the report path), and a progress
  # line on stdout ends up concatenated into the caller's "$(...)" capture.
  echo "── $name → $url" >&2
  $ARTILLERY run --target "$url" --output "$report" "$config" >&2 || return 1
  echo "$report"
}

count_429() {
  python3 -c "
import json,sys
d=json.load(open(sys.argv[1]))
codes=d.get('aggregate',{}).get('counters',{})
print(codes.get('http.codes.429',0))
" "$1"
}

echo "=== 1/2  Production: the limiter must ENGAGE ==="
if ! PROD_REPORT="$(run_half prod "$HERE/rate-limit-prod.yml" "$PROD_URL")"; then
  echo "!! production half failed; most likely this address's budget was already spent."
  echo "!! waiting out the 5m window and retrying once before calling it a real failure."
  sleep 310
  PROD_REPORT="$(run_half prod-retry "$HERE/rate-limit-prod.yml" "$PROD_URL")" || {
    echo "FAIL: production did not produce the expected 401-then-429 pattern on retry."
    exit 1
  }
fi
echo "   429s seen on production: $(count_429 "$PROD_REPORT")  (must be > 0)"

echo
echo "=== 2/2  Nonprod: the limiter must NOT engage (negative control) ==="
NONPROD_REPORT="$(run_half nonprod "$HERE/rate-limit-nonprod.yml" "$NONPROD_URL")" || {
  echo "FAIL: nonprod returned an unexpected status. If it was 429, the limiter is NOT scoped to"
  echo "      the production site block -- that throttles the e2e suites and must be fixed."
  exit 1
}
NONPROD_429="$(count_429 "$NONPROD_REPORT")"
echo "   429s seen on nonprod: $NONPROD_429  (must be exactly 0)"
if [ "$NONPROD_429" -ne 0 ]; then
  echo "FAIL: nonprod was rate-limited. The directive is not scoped to {\$APP_DOMAIN}."
  exit 1
fi

echo
echo "PASS: production rate-limits /api/signin and nonprod does not."
