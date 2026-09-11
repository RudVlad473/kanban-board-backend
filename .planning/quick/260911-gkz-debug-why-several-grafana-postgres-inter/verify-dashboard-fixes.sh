#!/usr/bin/env bash
set -euo pipefail

# Proves quick task 260911-gkz's dashboard fixes (singlestat->stat migration for Max
# Connections/Shared Buffers, exact->regex datname matcher for the 9 panels inside row id 20)
# preserve every OTHER panel's query/datasource/threshold data untouched AND actually provision
# into a real Grafana -- a schema-only check would pass a structurally broken dashboard that
# never renders. Follows the method of the 260908-r16 precedent script
# (.planning/quick/260908-r16-restructure-the-postgres-internals-grafa/verify-dashboards.sh),
# adapted to this plan's specific, different transformation.

BASE="${1:-a6bd609}"
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

PG_FILE="docker/grafana/provisioning/dashboards/json/postgres-exporter.json"

echo "== Step 1: JSON validity gate =="
python3 -m json.tool "$PG_FILE" > /dev/null
echo "OK: $PG_FILE is valid JSON"

echo "== Step 2: Panel invariance diff =="
BASE="$BASE" PG_FILE="$PG_FILE" python3 <<'PYEOF'
import json
import os
import subprocess

BASE = os.environ["BASE"]
PG_FILE = os.environ["PG_FILE"]

# Panels this task's Task 3 deliberately changed -- everything else must be byte-identical
# (gridPos excluded, since Grafana itself may normalize gridPos on save).
MIGRATED_STAT_IDS = {75, 66}  # Max Connections, Shared Buffers: singlestat -> stat
MATCHER_FIXED_IDS = {43, 122, 47, 48, 124, 126, 128, 130, 132}  # row 20's exact->regex datname
ROW20_UNCHANGED_IDS = {46, 44, 45}  # already used =~, must stay byte-identical
# Discovered during local verification (not predicted by static reading): postgres_exporter
# v0.20.1's stat_statements collector emits pg_stat_statements_calls_total /
# pg_stat_statements_seconds_total, not the un-suffixed names these two panels originally
# queried -- confirmed against the exporter's own HELP text. Fixed alongside the collector
# flags so enabling the collector actually produces a rendering panel, not just a query
# that runs against a still-nonexistent metric name.
METRIC_RENAME_IDS = {93, 102}  # Query rate, Average query runtime


def git_show(ref, path):
    out = subprocess.run(
        ["git", "show", f"{ref}:{path}"], capture_output=True, text=True, check=True
    )
    return json.loads(out.stdout)


def load(path):
    with open(path) as f:
        return json.load(f)


base_pg = git_show(BASE, PG_FILE)
new_pg = load(PG_FILE)


def flat_panels(obj):
    """Yield (panel, is_top_level) for every panel, including nested ones inside a
    collapsed row's own "panels" array (row id 20)."""
    for p in obj["panels"]:
        yield p, True
        for child in p.get("panels", []):
            yield child, False


base_panels = {p["id"]: p for p, _ in flat_panels(base_pg)}
new_panels = {p["id"]: p for p, _ in flat_panels(new_pg)}

assert set(base_panels) == set(new_panels), (
    f"panel id set mismatch: base only {set(base_panels) - set(new_panels)}, "
    f"new only {set(new_panels) - set(base_panels)}"
)
print(f"OK: identical panel id set ({len(base_panels)} panels/rows)")

untouched_ids = set(base_panels) - MIGRATED_STAT_IDS - MATCHER_FIXED_IDS - METRIC_RENAME_IDS - {20}
for pid in sorted(untouched_ids):
    bp = dict(base_panels[pid])
    bp.pop("gridPos", None)
    np = dict(new_panels[pid])
    np.pop("gridPos", None)
    assert bp == np, f"panel/row id {pid} changed beyond gridPos but was not in scope for this task"
print(f"OK: all {len(untouched_ids)} out-of-scope panels/rows deep-equal to baseline (gridPos excluded)")

for pid in ROW20_UNCHANGED_IDS:
    assert pid in untouched_ids, f"panel id {pid} expected to be untouched but was reclassified"

for pid in MIGRATED_STAT_IDS:
    bp, np = base_panels[pid], new_panels[pid]
    assert bp["type"] == "singlestat", f"panel {pid} baseline type unexpectedly not singlestat"
    assert np["type"] == "stat", f"panel {pid} was not migrated to type 'stat'"
    assert bp["title"] == np["title"], f"panel {pid} title changed during migration"
    assert bp["targets"] == np["targets"], f"panel {pid} targets (queries) changed during migration"
    assert bp["gridPos"] == np["gridPos"], f"panel {pid} gridPos changed during migration"
print(f"OK: {len(MIGRATED_STAT_IDS)} panels migrated singlestat->stat with title/targets/gridPos preserved")

for pid in MATCHER_FIXED_IDS:
    bp, np = base_panels[pid], new_panels[pid]
    assert bp["type"] == np["type"], f"panel {pid} type changed (should only be a matcher fix)"
    assert bp["title"] == np["title"], f"panel {pid} title changed"
    assert bp["gridPos"] == np["gridPos"], f"panel {pid} gridPos changed"
    bexprs = [t.get("expr") for t in bp["targets"]]
    nexprs = [t.get("expr") for t in np["targets"]]
    assert len(bexprs) == len(nexprs), f"panel {pid} target count changed"
    changed_any = False
    for be, ne in zip(bexprs, nexprs):
        if be == ne:
            continue
        changed_any = True
        assert be.replace('datname="$Database"', 'datname=~"$Database"') == ne, (
            f"panel {pid} expr changed by more than the datname operator: {be!r} -> {ne!r}"
        )
    assert changed_any, f"panel {pid} was expected to have at least one datname operator fix"
    # Everything else on the panel object must be identical.
    b2, n2 = dict(bp), dict(np)
    b2.pop("targets"); n2.pop("targets")
    b2.pop("gridPos", None); n2.pop("gridPos", None)
    assert b2 == n2, f"panel {pid} changed fields beyond targets/gridPos"
print(f"OK: {len(MATCHER_FIXED_IDS)} panels in row 20 have ONLY their datname operator changed (= -> =~)")

RENAME_MAP = {
    "pg_stat_statements_calls": "pg_stat_statements_calls_total",
    "pg_stat_statements_total_time_seconds": "pg_stat_statements_seconds_total",
}
for pid in METRIC_RENAME_IDS:
    bp, np = base_panels[pid], new_panels[pid]
    assert bp["type"] == np["type"], f"panel {pid} type changed (should only be a metric rename)"
    assert bp["title"] == np["title"], f"panel {pid} title changed"
    assert bp["gridPos"] == np["gridPos"], f"panel {pid} gridPos changed"
    bexprs = [t.get("expr") for t in bp["targets"]]
    nexprs = [t.get("expr") for t in np["targets"]]
    assert len(bexprs) == len(nexprs), f"panel {pid} target count changed"
    for be, ne in zip(bexprs, nexprs):
        expected = be
        for old, new in RENAME_MAP.items():
            expected = expected.replace(old, new)
        assert expected == ne, f"panel {pid} expr changed by more than the metric rename: {be!r} -> {ne!r}"
    b2, n2 = dict(bp), dict(np)
    b2.pop("targets"); n2.pop("targets")
    b2.pop("gridPos", None); n2.pop("gridPos", None)
    assert b2 == n2, f"panel {pid} changed fields beyond targets/gridPos"
print(f"OK: {len(METRIC_RENAME_IDS)} panels have ONLY the pg_stat_statements metric rename applied")

# Row 20 itself (collapsed/repeat directive) must be untouched -- this task deliberately did NOT
# alter the collapsed/repeat structure, only the child panels' matchers.
base_row20 = base_panels[20]
new_row20 = new_panels[20]
b20 = {k: v for k, v in base_row20.items() if k != "panels"}
n20 = {k: v for k, v in new_row20.items() if k != "panels"}
assert b20 == n20, "row id 20's own fields (collapsed/repeat/title/gridPos) changed"
print("OK: row id 20's collapsed/repeat directive is untouched by this task")

print("PASS: step 2")
PYEOF

echo "== Step 3: Structure gate (uid/description/dashboard-level fields unchanged) =="
BASE="$BASE" PG_FILE="$PG_FILE" python3 <<'PYEOF'
import json, os, subprocess

BASE = os.environ["BASE"]
PG_FILE = os.environ["PG_FILE"]

def git_show(ref, path):
    out = subprocess.run(["git", "show", f"{ref}:{path}"], capture_output=True, text=True, check=True)
    return json.loads(out.stdout)

def load(path):
    with open(path) as f:
        return json.load(f)

base_pg = git_show(BASE, PG_FILE)
new_pg = load(PG_FILE)

assert new_pg["uid"] == base_pg["uid"] == "v5ciIbUZz", f"uid changed: {new_pg['uid']!r}"
assert new_pg["description"] == base_pg["description"], "dashboard description changed"
assert new_pg["templating"] == base_pg["templating"], "templating (template variables) changed"
print("OK: uid/description/templating unchanged")
print("PASS: step 3")
PYEOF

echo "== Step 4: Live render check (disposable Grafana container) =="

CONTAINER_NAME="gsd-verify-grafana-$$-$(date +%s)"
cleanup() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

ADMIN_PASS="$(openssl rand -hex 16)"

docker run -d --rm --name "$CONTAINER_NAME" \
  -v "$REPO_ROOT/docker/grafana/provisioning:/etc/grafana/provisioning:ro" \
  -e GF_AUTH_ANONYMOUS_ENABLED=true \
  -e GF_AUTH_ANONYMOUS_ORG_ROLE=Admin \
  -e GF_SECURITY_ADMIN_PASSWORD="$ADMIN_PASS" \
  grafana/grafana:13.2.1 >/dev/null

echo "Started disposable Grafana container $CONTAINER_NAME, waiting for health..."

HEALTHY=0
for _ in $(seq 1 30); do
  if HEALTH_JSON="$(docker exec "$CONTAINER_NAME" wget -q -O - http://localhost:3000/api/health 2>/dev/null)"; then
    if echo "$HEALTH_JSON" | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get('database')=='ok' else 1)" 2>/dev/null; then
      HEALTHY=1
      break
    fi
  fi
  sleep 2
done

if [ "$HEALTHY" -ne 1 ]; then
  echo "FAIL: Grafana did not become healthy within ~60s" >&2
  docker logs "$CONTAINER_NAME" 2>&1 | tail -50 >&2
  exit 1
fi
echo "OK: Grafana reports database ok"

fetch_dashboard() {
  local uid="$1"
  local result=""
  for _ in $(seq 1 15); do
    if result="$(docker exec "$CONTAINER_NAME" wget -q -O - "http://localhost:3000/api/dashboards/uid/$uid" 2>/dev/null)"; then
      printf '%s' "$result"
      return 0
    fi
    sleep 2
  done
  echo "FAIL: could not fetch dashboard uid=$uid" >&2
  return 1
}

PG_JSON="$(fetch_dashboard v5ciIbUZz)"

echo "$PG_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
dash = d['dashboard']
panels = {p['id']: p for p in dash['panels']}
panels.update({c['id']: c for p in dash['panels'] for c in p.get('panels', [])})
assert panels[75]['type'] == 'stat', f\"live Max Connections panel type: {panels[75]['type']!r}\"
assert panels[66]['type'] == 'stat', f\"live Shared Buffers panel type: {panels[66]['type']!r}\"
for pid in (43, 122, 47, 48, 124, 126, 128, 130, 132):
    for t in panels[pid]['targets']:
        expr = t.get('expr', '')
        assert 'datname=\"\$Database\"' not in expr, f'panel {pid} still has exact-match datname: {expr!r}'
for pid in (93, 102):
    for t in panels[pid]['targets']:
        expr = t.get('expr', '')
        assert 'pg_stat_statements_calls{' not in expr, f'panel {pid} still references un-suffixed pg_stat_statements_calls: {expr!r}'
        assert 'pg_stat_statements_total_time_seconds' not in expr, f'panel {pid} still references pg_stat_statements_total_time_seconds: {expr!r}'
print('OK: live dashboard reflects stat migration, regex matcher fix, and pg_stat_statements rename')
"

ERROR_LINES="$(docker logs "$CONTAINER_NAME" 2>&1 | grep 'logger=provisioning.dashboard' | grep -i 'level=error' || true)"
if [ -n "$ERROR_LINES" ]; then
  echo "FAIL: dashboard-provisioning error(s) found in container log:" >&2
  echo "$ERROR_LINES" >&2
  exit 1
fi
echo "OK: no dashboard-provisioning errors in container log"

docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
trap - EXIT
echo "PASS: step 4"

echo "== Step 5: Untouched-file gate =="
CHANGED_FILES="$(git diff --name-only "$BASE" -- docker/grafana/provisioning/)"
echo "$CHANGED_FILES"
if echo "$CHANGED_FILES" | grep -vq '^docker/grafana/provisioning/dashboards/json/postgres-exporter.json$'; then
  if [ -n "$CHANGED_FILES" ]; then
    echo "FAIL: unexpected files changed under docker/grafana/provisioning/" >&2
    exit 1
  fi
fi
echo "OK: only postgres-exporter.json changed under docker/grafana/provisioning/"

echo "ALL GATES PASSED"
