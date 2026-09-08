#!/usr/bin/env bash
set -euo pipefail

# Proves the Postgres Internals row restructure and cAdvisor title rename preserve every
# panel's query/datasource/threshold data AND actually provision into a real Grafana --
# a schema-only check would pass a structurally broken dashboard that never renders.

BASE="${1:-HEAD}"
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

PG_FILE="docker/grafana/provisioning/dashboards/json/postgres-exporter.json"
CADVISOR_FILE="docker/grafana/provisioning/dashboards/json/cadvisor.json"

echo "== Step 1: JSON validity gate =="
python3 -m json.tool "$PG_FILE" > /dev/null
python3 -m json.tool "$CADVISOR_FILE" > /dev/null
echo "OK: both files are valid JSON"

echo "== Step 2: Panel invariance diff =="
BASE="$BASE" PG_FILE="$PG_FILE" CADVISOR_FILE="$CADVISOR_FILE" python3 <<'PYEOF'
import json
import os
import subprocess

BASE = os.environ["BASE"]
PG_FILE = os.environ["PG_FILE"]
CADVISOR_FILE = os.environ["CADVISOR_FILE"]


def git_show(ref, path):
    out = subprocess.run(
        ["git", "show", f"{ref}:{path}"], capture_output=True, text=True, check=True
    )
    return json.loads(out.stdout)


def load(path):
    with open(path) as f:
        return json.load(f)


base_cadvisor = git_show(BASE, CADVISOR_FILE)
new_cadvisor = load(CADVISOR_FILE)
base_sentinel = dict(base_cadvisor)
base_sentinel["title"] = "__SENTINEL__"
new_sentinel = dict(new_cadvisor)
new_sentinel["title"] = "__SENTINEL__"
assert base_sentinel == new_sentinel, "cadvisor.json: non-title fields changed"
print("OK: cadvisor.json title is the only change")

new_pg = load(PG_FILE)
has_global_stats = any(
    p.get("type") == "row" and p.get("title") == "Global Statistics"
    for p in new_pg["panels"]
)

if has_global_stats:
    print(
        "SKIPPED: postgres-exporter.json panel invariance "
        "(Global Statistics row still present)"
    )
else:
    base_pg = git_show(BASE, PG_FILE)

    def panel_map(obj):
        return {p["id"]: p for p in obj["panels"] if p.get("type") != "row"}

    base_panels = panel_map(base_pg)
    new_panels = panel_map(new_pg)
    assert set(base_panels) == set(new_panels), (
        f"panel id set mismatch: base only {set(base_panels) - set(new_panels)}, "
        f"new only {set(new_panels) - set(base_panels)}"
    )
    for pid, bp in base_panels.items():
        np = new_panels[pid]
        bp2 = dict(bp)
        bp2.pop("gridPos", None)
        np2 = dict(np)
        np2.pop("gridPos", None)
        assert bp2 == np2, f"panel id {pid} changed beyond gridPos"
    print(
        f"OK: all {len(base_panels)} non-row panels deep-equal to baseline "
        "(gridPos excluded)"
    )

    def row_by_id(obj, rid):
        for p in obj["panels"]:
            if p.get("type") == "row" and p.get("id") == rid:
                return p
        return None

    base_row20 = row_by_id(base_pg, 20)
    new_row20 = row_by_id(new_pg, 20)
    assert base_row20 is not None and new_row20 is not None, "row id 20 missing"
    b20 = dict(base_row20)
    b20.pop("gridPos", None)
    n20 = dict(new_row20)
    n20.pop("gridPos", None)
    assert b20 == n20, "row id 20 (including nested panels) changed beyond its own gridPos"
    print("OK: row id 20 and its 12 nested panels unchanged apart from its own gridPos")

    base_all_ids = {p["id"] for p in base_pg["panels"]}
    new_all_ids = {p["id"] for p in new_pg["panels"]}
    assert base_all_ids <= new_all_ids, f"ids dropped: {base_all_ids - new_all_ids}"
    print("OK: full baseline id set (rows included) is a subset of the new id set")

print("PASS: step 2")
PYEOF

echo "== Step 3: Structure gate =="
BASE="$BASE" PG_FILE="$PG_FILE" CADVISOR_FILE="$CADVISOR_FILE" python3 <<'PYEOF'
import json
import os
import subprocess

BASE = os.environ["BASE"]
PG_FILE = os.environ["PG_FILE"]
CADVISOR_FILE = os.environ["CADVISOR_FILE"]


def git_show(ref, path):
    out = subprocess.run(
        ["git", "show", f"{ref}:{path}"], capture_output=True, text=True, check=True
    )
    return json.loads(out.stdout)


def load(path):
    with open(path) as f:
        return json.load(f)


pg = load(PG_FILE)
cadvisor = load(CADVISOR_FILE)
base_cadvisor = git_show(BASE, CADVISOR_FILE)

assert cadvisor["title"] == "CPU/Memory & Network Usage - cAdvisor", (
    f"cadvisor title wrong: {cadvisor['title']!r}"
)
assert cadvisor["uid"] == "pMEd7m0Mz", f"cadvisor uid changed: {cadvisor['uid']!r}"
assert cadvisor["description"] == base_cadvisor["description"], (
    "cadvisor description changed"
)
print("OK: cadvisor title, uid, description assertions pass")

has_global_stats = any(
    p.get("type") == "row" and p.get("title") == "Global Statistics"
    for p in pg["panels"]
)
if has_global_stats:
    print(
        "SKIPPED: postgres-exporter.json structure assertions "
        "(Global Statistics row still present)"
    )
else:
    base_pg = git_show(BASE, PG_FILE)
    rows = [p for p in pg["panels"] if p.get("type") == "row"]
    expected_titles = [
        "Health & Availability",
        "Connections",
        "Query Performance",
        "Storage & I/O",
        "Locks",
        "Database: $Database",
    ]
    actual_titles = [r["title"] for r in rows]
    assert actual_titles == expected_titles, f"row titles/order mismatch: {actual_titles}"
    for r in rows[:5]:
        assert r.get("collapsed") is False, (
            f"row {r['title']!r} should be expanded (collapsed=False)"
        )
    assert rows[5].get("collapsed") is True, "Database: $Database row should stay collapsed"
    assert not any(p.get("title") == "Global Statistics" for p in pg["panels"]), (
        "Global Statistics row still present"
    )
    assert pg["uid"] == "v5ciIbUZz", f"postgres-exporter uid changed: {pg['uid']!r}"
    assert pg["description"] == base_pg["description"], (
        "postgres-exporter description changed"
    )
    print(
        "OK: postgres-exporter.json structure assertions pass "
        "(6 rows, correct order/collapsed state, uid/description unchanged)"
    )

print("PASS: step 3")
PYEOF

echo "== Step 4: Live render check =="

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

CADVISOR_JSON="$(fetch_dashboard pMEd7m0Mz)"
PG_JSON="$(fetch_dashboard v5ciIbUZz)"

echo "$CADVISOR_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
title = d['dashboard']['title']
assert title == 'CPU/Memory & Network Usage - cAdvisor', f'cadvisor live title wrong: {title!r}'
print('OK: live cadvisor dashboard title matches')
"

echo "$PG_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
dash = d['dashboard']
has_global_stats = any(p.get('type') == 'row' and p.get('title') == 'Global Statistics' for p in dash['panels'])
if has_global_stats:
    print('SKIPPED: live postgres-exporter row-title check (Global Statistics row still present)')
else:
    row_titles = {p['title'] for p in dash['panels'] if p.get('type') == 'row'}
    expected = {'Health & Availability', 'Connections', 'Query Performance', 'Storage & I/O', 'Locks'}
    missing = expected - row_titles
    assert not missing, f'missing expected row titles in live dashboard: {missing}'
    print('OK: live postgres-exporter dashboard carries all five new row titles')
"

# Scoped to logger=provisioning.dashboard specifically, not a bare 'provision|dashboard'
# substring match: this image also logs level=error for its plugins/ and alerting/
# provisioning sub-readers when those two directories don't exist under
# docker/grafana/provisioning (confirmed reproducible on every cold start, and present in
# docker-compose.prod.yml's identical mount too, since this repo intentionally provisions
# only dashboards/ and datasources/) -- that noise would false-fail this gate on every run
# regardless of whether the dashboard restructure itself is correct.
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
CHANGED_FILES="$(git diff --name-only "$BASE")"
if echo "$CHANGED_FILES" | grep -q '^docker/grafana/provisioning/dashboards/json/node-exporter-full.json$'; then
  echo "FAIL: node-exporter-full.json was modified" >&2
  exit 1
fi
echo "OK: node-exporter-full.json untouched"

echo "ALL GATES PASSED"
