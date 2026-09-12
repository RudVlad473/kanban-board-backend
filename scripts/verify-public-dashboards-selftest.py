#!/usr/bin/env python3
"""Self-test for scripts/verify-public-dashboards.py's pure functions.

Prevents the failure this gate exists to catch, one level up: an edit that makes an invariant
unfireable is invisible against dashboards that already satisfy every invariant -- the gate goes
green and stays green, exactly as the real bug stayed invisible for a month. Each case below feeds
one pure function an in-memory dashboard engineered to trip exactly one invariant and asserts the
violation is reported and names the offending construct; clean inputs assert nothing is reported.

No file access, no working-tree mutation -- this proves the LOGIC fires, not the wiring against the
real dashboards (that is what the one-off red/green run against the pre-fix JSON proves instead).

Fixtures are literal, not derived from the gate's own PUBLIC_DASHBOARDS/BUILTIN_* constants --
importing those would let a wrong edit to a constant rewrite this test's expectations along with
it and still pass, which is strictly worse than a literal fixture that disagrees with the edit.
"""

import importlib.util
import os
import sys

# The gate's filename carries hyphens (matching this repo's other scripts/verify-*.py naming),
# which is not a valid `import` module name -- loaded explicitly by path instead.
_GATE_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "verify-public-dashboards.py"
)
_spec = importlib.util.spec_from_file_location("verify_public_dashboards", _GATE_PATH)
_gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_gate)

KNOWN_UIDS = {"PBFA97CFB590B2093", "P8E80F9AEF21F6940"}
GOOD_REF = {"type": "prometheus", "uid": "PBFA97CFB590B2093"}

failures = []


def check(label, condition, detail=""):
    if condition:
        print(f"  ok   {label}")
    else:
        print(f"  FAIL {label} {detail}")
        failures.append(label)


def panel(**overrides):
    p = {"id": 1, "title": "CPU", "datasource": dict(GOOD_REF), "targets": [{"refId": "A"}]}
    p.update(overrides)
    return p


def dashboard(panels, templating=None, uid="rYdddlPWk"):
    return {
        "uid": uid,
        "templating": {"list": templating or []},
        "panels": panels,
    }


print("I1 -- datasource refs must be a declared literal uid")

v = _gate.find_datasource_violations(
    dashboard([panel(datasource="Prometheus")]), "d.json", KNOWN_UIDS
)
check("legacy name string is rejected", len(v) == 1 and "by NAME" in v[0], v)

v = _gate.find_datasource_violations(
    dashboard([panel(datasource={"type": "prometheus", "uid": "${ds_prometheus}"})]),
    "d.json",
    KNOWN_UIDS,
)
check("datasource template variable is rejected", len(v) == 1 and "template variable" in v[0], v)

v = _gate.find_datasource_violations(
    dashboard([panel(datasource={"type": "prometheus", "uid": "prometheus"})]),
    "d.json",
    KNOWN_UIDS,
)
check("uid absent from datasources.yaml is rejected", len(v) == 1 and "not declared" in v[0], v)

v = _gate.find_datasource_violations(
    dashboard([panel(datasource={"type": "prometheus"})]), "d.json", KNOWN_UIDS
)
check("ref with no uid at all is rejected", len(v) == 1 and "no uid" in v[0], v)

v = _gate.find_datasource_violations(
    dashboard([panel(), panel(datasource={"type": "datasource", "uid": "grafana"})]),
    "d.json",
    KNOWN_UIDS,
)
check("declared uid and the built-in Grafana ref both pass", v == [], v)

# The real dashboards nest panels inside collapsed rows; a checker that only walks the top level
# would pass every one of them while the nested panels stayed broken.
v = _gate.find_datasource_violations(
    dashboard([{"type": "row", "panels": [panel(datasource="Prometheus")]}]),
    "d.json",
    KNOWN_UIDS,
)
check("a ref nested inside a row is still found", len(v) == 1 and "by NAME" in v[0], v)

print("I2 -- query fields must not carry dashboard variables")

v = _gate.find_variable_violations(
    dashboard([panel(targets=[{"expr": 'up{instance="$node"}'}])]), "d.json"
)
check("$node in expr is rejected", len(v) == 1 and "$node" in v[0], v)

v = _gate.find_variable_violations(
    dashboard([panel(targets=[{"expr": "up", "interval": "$Interval"}])]), "d.json"
)
check("$Interval in the interval field is rejected", len(v) == 1 and "$Interval" in v[0], v)

v = _gate.find_variable_violations(
    dashboard([panel(targets=[{"expr": "rate(x[$__rate_interval])"}])]), "d.json"
)
check("built-in $__rate_interval is allowed", v == [], v)

v = _gate.find_variable_violations(
    dashboard([panel(targets=[{"expr": 'up{instance="${node}"}'}])]), "d.json"
)
check("braced ${node} is rejected too", len(v) == 1 and "$node" in v[0], v)

v = _gate.find_variable_violations(
    dashboard([panel(targets=[{"expr": 'up{instance="node-exporter:9100",job="node"}'}])]),
    "d.json",
)
check("a fully substituted query passes", v == [], v)

print("I3 -- a public dashboard declares no template variables")

v = _gate.find_templating_violations(
    dashboard([panel()], templating=[{"name": "node", "type": "query"}]), "d.json"
)
check("a surviving variable is rejected", len(v) == 1 and "node" in v[0], v)

check("no variables passes", _gate.find_templating_violations(dashboard([panel()]), "d.json") == [])

print("I5 -- every panel type is a plugin the pinned Grafana ships")

v = _gate.find_panel_type_violations(dashboard([panel(type="graph")]), "d.json")
check(
    "legacy Angular 'graph' is rejected and names its replacement",
    len(v) == 1 and "graph" in v[0] and "timeseries" in v[0],
    v,
)

v = _gate.find_panel_type_violations(dashboard([panel(type="singlestat")]), "d.json")
check(
    "legacy Angular 'singlestat' is rejected and names its replacement",
    len(v) == 1 and "singlestat" in v[0] and "stat" in v[0],
    v,
)

v = _gate.find_panel_type_violations(dashboard([panel(type="grafana-piechart-panel")]), "d.json")
check(
    "an unshipped external plugin id is rejected",
    len(v) == 1 and "not a panel plugin shipped" in v[0],
    v,
)

v = _gate.find_panel_type_violations(
    dashboard([panel(type="timeseries"), panel(type="stat"), panel(type="table")]), "d.json"
)
check("modern shipped types pass", v == [], v)

# `row` has no plugin directory in the image, so an allowlist built from that listing alone would
# reject every dashboard that groups its panels -- which is all three of them.
v = _gate.find_panel_type_violations(dashboard([{"type": "row", "id": 99, "panels": []}]), "d.json")
check("structural 'row' passes despite shipping no plugin", v == [], v)

# The defect this gate missed the first time was inside collapsed rows as well as beside them.
v = _gate.find_panel_type_violations(
    dashboard([{"type": "row", "id": 99, "panels": [panel(type="graph")]}]), "d.json"
)
check("a legacy panel nested inside a row is still found", len(v) == 1 and "graph" in v[0], v)

# A datasource ref carries type="prometheus"; a checker walking every dict instead of the panel
# tree would report that as an unshipped panel plugin on a dashboard that is entirely fine.
v = _gate.find_panel_type_violations(
    dashboard([panel(type="timeseries", datasource={"type": "prometheus", "uid": "PBFA97CFB590B2093"})]),
    "d.json",
)
check("a datasource ref's own 'type' is not mistaken for a panel type", v == [], v)

print("I6 -- the plugin allowlist matches the image Compose pins")

check(
    "the pinned image passes",
    _gate.find_grafana_version_drift("  grafana:\n    image: grafana/grafana:13.2.1\n") == [],
)

v = _gate.find_grafana_version_drift("  grafana:\n    image: grafana/grafana:14.0.0\n")
check("a bumped image is rejected", len(v) == 1 and "14.0.0" in v[0], v)

print("I4 -- every dashboard file is accounted for")

v = _gate.find_uncovered_files({"a.json", "new.json"}, {"a.json": "u1"}, {})
check("an unlisted file is rejected", len(v) == 1 and "new.json" in v[0], v)

v = _gate.find_uncovered_files({"a.json", "b.json"}, {"a.json": "u1"}, {"b.json": "private"})
check("files split across both sets pass", v == [], v)

print("uid mapping -- the file must describe the dashboard actually shared")

v = _gate.find_uid_mismatches(dashboard([panel()], uid="CHANGED"), "d.json", "rYdddlPWk")
check("a changed dashboard uid is rejected", len(v) == 1 and "rYdddlPWk" in v[0], v)

check(
    "the expected uid passes",
    _gate.find_uid_mismatches(dashboard([panel()]), "d.json", "rYdddlPWk") == [],
)

print()
if failures:
    print(f"SELFTEST FAILED: {len(failures)} case(s): {failures}")
    sys.exit(1)
print("SELFTEST OK -- every invariant demonstrated to fire and to stay quiet on clean input")
