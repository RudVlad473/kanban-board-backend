#!/usr/bin/env python3
r"""Gate: a publicly-shared Grafana dashboard must contain nothing the public renderer cannot resolve.

Scope: every *.json under a scope's json_dir, where a scope is a (json_dir, uid_source,
grafana_version_source) triple. The only scope left is k8s: the dashboards under
k8s/monitoring/configs/dashboards/, checked against the datasource ConfigMap's uids and the
kube-prometheus-stack HelmRelease's Grafana tag. check_scope() is scope-generic, so a second scope
has a place to land.

Decisions:
Measured 2026-09-12 against grafana/grafana:13.2.1 (docs/INFRA_RUNBOOK.md, "Public Grafana dashboards
rendered no data"): all three dashboards shared through Grafana's public-dashboard feature rendered
their shell but every panel showed "Datasource was not found", for over a month, while every exporter
was healthy and every Prometheus target was up. The public renderer is a DIFFERENT, stricter code path
than the logged-in one, and two things legal in an authenticated dashboard are fatal in a public one:

  1. It resolves a datasource by uid ONLY. A legacy name string ("Prometheus") and a datasource
     template variable ("${ds_prometheus}") both fail lookup, and the panel query returns HTTP 500
     with `publicdashboards.service ... error="data source not found"`.
  2. It interpolates built-in macros ($__rate_interval, $__interval) but NEVER dashboard template
     variables. A query holding $node reaches Prometheus as the literal text "$node", which matches
     no series, so this one fails SILENTLY: HTTP 200 with an empty result, indistinguishable in the UI
     from a genuinely idle metric.

Failure 2 is why this gate is worth its maintenance: nothing else in the stack notices it. Grafana logs
nothing, Prometheus answers normally, the container stays healthy, and the dashboard looks merely
quiet. The dashboards are vendored from grafana.com, where template variables are the norm, so every
future re-fetch reintroduces this defect.

A THIRD failure, measured 2026-09-12 after the two above were fixed and this gate went green
(docs/INFRA_RUNBOOK.md, "Plugin graph not found"):

  3. A panel whose `type` names a plugin the running Grafana does not ship renders nothing at all.
     Grafana 13 removed Angular outright: `graph` and `singlestat` are not disabled-by-default, they
     are absent from the image, and `angular_support_enabled` no longer exists as a setting, so no
     configuration can bring them back. The panel draws an error triangle whose tooltip reads
     "Plugin graph not found" verbatim.

Failure 3 is why I5 exists and is checked separately from I1-I3: those are about the DATA path, and on
the dashboards that tripped it the data path was provably healthy (all six live public panel-query
endpoints returned HTTP 200 with 793-803 datapoints each while every panel rendered blank). A gate that
only proves queries are well-formed reports success on a dashboard no browser can draw: this file
passed, CI was green, and two of the three public dashboards were still broken.

Every *.json under the json_dir is in one of two disjoint sets, so a new dashboard cannot land ungated
(I4): PUBLIC_DASHBOARDS, checked against every invariant, and DELIBERATELY_PRIVATE, documented and
exempted from the public-only invariants. I1 (datasource refs resolve by uid) applies to BOTH sets: a
name-string ref works in the authenticated path but is the same latent defect one "share publicly"
click away. A dashboard file expected in a scope but not yet authored is listed as PENDING
(K8S_PENDING), so the gate names exactly what is missing rather than silently passing a scope with 1 of
3 dashboards. The Compose copies of these dashboards and the Compose scope were deleted with the
Compose stack.

Known holes:
  * This reads the COMMITTED JSON, not the running Grafana. A dashboard edited in the UI and saved into
    Grafana's own persistent volume, or shared publicly from the UI without a matching repo change, is
    invisible here. PUBLIC_DASHBOARDS is therefore a claim about intent that a human keeps true; the
    authoritative list is GET /api/dashboards/public-dashboards on the VM.
  * Passing I2 does NOT mean a panel renders; it means the query is free of the specific defect measured
    above. A query can still be wrong, reference a renamed metric, or match nothing, and proving a panel
    returns data needs a live Prometheus. "Renders data" was proven live when the k8s dashboards were
    activated at the prod cutover; this gate proves only that the query shape is public-renderer-safe.
  * The hardcoded label values that replaced the template variables (job="prometheus-node-exporter",
    instance=<k8s node name>) are correct for a single-host deployment and are NOT checked against live
    Prometheus. If the stack grows a second node or an exporter is renamed, this gate stays green while
    the dashboards quietly narrow to a host that no longer exists.
  * A dashboard can be moved into DELIBERATELY_PRIVATE in the same pull request that adds a variable to
    it. This gate makes that a REVIEWED choice, not an impossible one.
"""

import glob
import json
import os
import re
import sys

# k8s scope: the Kubernetes-label rewrite of the three original dashboards.
K8S_JSON_DIR = "k8s/monitoring/configs/dashboards"
K8S_DATASOURCES = "k8s/monitoring/configs/datasources.yaml"
K8S_HELMRELEASE = "k8s/monitoring/controllers/kube-prometheus-stack.yaml"

# Shared through Grafana's public-dashboard feature, so subject to every invariant below.
#
# Verified against GET /api/dashboards/public-dashboards on the k8s Grafana (kube-prometheus-stack,
# image 13.2.1), 2026-09-26: the three shares were recreated on the new Grafana after the prod cutover.
# Same file names and uids as the deleted Compose copies; the k8s copies are a runtime-label rewrite of
# the identical dashboard.
PUBLIC_DASHBOARDS = {
    "node-exporter-full.json": "rYdddlPWk",
    "cadvisor.json": "pMEd7m0Mz",
    "postgres-exporter.json": "v5ciIbUZz",
}

# k8s-scope files not yet authored; empty, as all three dashboards exist. Kept as a named mechanism so a
# future dashboard addition has a place to land and check_scope()'s "present pending" self-check stays
# exercised.
K8S_PENDING: set = set()

# Not shared publicly: exempt from I2/I3 (they may use template variables freely), still subject
# to I1. Empty today -- every provisioned dashboard is public. A future admin-only dashboard goes
# here with a one-line reason.
DELIBERATELY_PRIVATE: dict = {}

# Interpolated by the public renderer, so safe to leave in a query. Everything else beginning with
# `$` is a dashboard variable and is not.
BUILTIN_VAR = re.compile(r"^__")
VAR_REF = re.compile(r"\$\{?(\w+)")

# Fields whose contents are sent to the datasource verbatim.
QUERY_FIELDS = ("expr", "interval")

BUILTIN_DATASOURCE_UIDS = {"grafana", "-- Grafana --", "-- Mixed --", "-- Dashboard --"}

# Grafana image tag the HelmRelease pins (kube-prometheus-stack.yaml values.grafana.image.tag), the only
# version GRAFANA_PANEL_PLUGINS describes.
#
# Checked against that file at run time (I6) so a Grafana bump cannot leave this allowlist describing a
# version nothing runs any more.
PINNED_GRAFANA_IMAGE = "grafana/grafana:13.2.1"
PINNED_GRAFANA_TAG = "13.2.1"

# Derived, not recalled: every directory under /usr/share/grafana/public/app/plugins/panel/ that contains
# a plugin.json, read out of the pinned image on 2026-09-12 (exactly 30).
#
# The bare listing has 32 entries (`AGENTS.md` and `test-utils.ts` ship there too and are not plugins),
# hence the plugin.json filter. No GF_INSTALL_PLUGINS is set in the HelmRelease, so nothing widens this
# set at run time; if an external panel plugin is installed, add it here WITH the install mechanism named,
# or this gate rejects a dashboard that would in fact render.
GRAFANA_PANEL_PLUGINS = {
    "alertlist", "annolist", "barchart", "bargauge", "candlestick", "canvas", "dashlist", "debug",
    "flamegraph", "gauge", "geomap", "gettingstarted", "heatmap", "histogram", "live", "logs",
    "logstable", "news", "nodeGraph", "piechart", "stat", "state-timeline", "status-history",
    "table", "text", "timeseries", "traces", "trend", "welcome", "xychart",
}

# `row` is structural -- Grafana core handles it directly and it has no plugin directory, so it is
# legal despite being absent above.
STRUCTURAL_PANEL_TYPES = {"row"}

# Named so the failure message can say what to migrate TO. Everything not in GRAFANA_PANEL_PLUGINS
# is rejected regardless; these two get a specific replacement because they are what the vendored
# grafana.com dashboards actually carry.
ANGULAR_REPLACEMENTS = {"graph": "timeseries", "singlestat": "stat"}

# k8s-scope-only (I7 below): a Docker/Compose-era literal left in a k8s dashboard means the label rewrite
# was incomplete.
#
# The panel would silently render "No data" against the real chart-scraped series, a failure this gate's
# other invariants cannot see because the query is otherwise well-formed.
K8S_BANNED_LITERALS = (
    "cadvisor:8080",
    "node-exporter:9100",
    "postgres-exporter:9187",
    "container_label_com_docker",
)


def walk(node, fn):
    """Apply fn to every dict in the tree."""
    if isinstance(node, dict):
        fn(node)
        for value in node.values():
            walk(value, fn)
    elif isinstance(node, list):
        for value in node:
            walk(value, fn)


def iter_panels(dashboard):
    """Yield every panel, descending into the `panels` a collapsed row nests its children in.

    Deliberately NOT built on walk(): walk() visits every dict in the document, and `type` is a
    common key on objects that are not panels at all (a datasource ref carries type="prometheus",
    a field override carries its own type). Feeding those to a panel-plugin allowlist would report
    a violation for "prometheus" on a perfectly good dashboard.
    """

    def descend(panels):
        for panel in panels or []:
            if not isinstance(panel, dict):
                continue
            yield panel
            yield from descend(panel.get("panels"))

    yield from descend(dashboard.get("panels"))


def find_panel_type_violations(dashboard, filename):
    """I5: every panel type names a plugin the pinned Grafana actually ships."""
    violations = []
    for panel in iter_panels(dashboard):
        ptype = panel.get("type")
        if ptype in GRAFANA_PANEL_PLUGINS or ptype in STRUCTURAL_PANEL_TYPES:
            continue
        where = f"panel id={panel.get('id')!r} ({panel.get('title') or 'untitled'!r})"
        if ptype in ANGULAR_REPLACEMENTS:
            violations.append(
                f"{filename}: {where} has type {ptype!r}, an Angular-era panel REMOVED from "
                f"{PINNED_GRAFANA_IMAGE}. It cannot render at all -- the panel shows an error "
                f"triangle reading 'Plugin {ptype} not found' no matter how healthy its query is. "
                f"Migrate to {ANGULAR_REPLACEMENTS[ptype]!r} (import the dashboard into a "
                f"{PINNED_GRAFANA_IMAGE} instance and export it back, so Grafana's own "
                "DashboardMigrator does the conversion rather than a hand edit)."
            )
        else:
            violations.append(
                f"{filename}: {where} has type {ptype!r}, which is not a panel plugin shipped by "
                f"{PINNED_GRAFANA_IMAGE} and not a structural type. Shipped plugins: "
                f"{sorted(GRAFANA_PANEL_PLUGINS)}."
            )
    return violations


def find_grafana_version_drift_k8s(helmrelease_path):
    """I6: the HelmRelease pins the SAME Grafana tag PINNED_GRAFANA_IMAGE describes.

    A panel-plugin allowlist is only true of one Grafana version. Bumping the image without
    re-deriving it would leave I5 silently enforcing a former version's plugin set -- passing a
    dashboard that no longer renders, or failing one that does. To re-derive:
    `docker run --rm --entrypoint sh <image> -c 'cd /usr/share/grafana/public/app/plugins/panel &&
    for d in */; do [ -f "$d/plugin.json" ] && echo "${d%/}"; done'`.
    """
    import yaml

    with open(helmrelease_path) as f:
        hr = yaml.safe_load(f)
    tag = (
        (hr.get("spec", {}).get("values", {}) or {})
        .get("grafana", {})
        .get("image", {})
        .get("tag")
    )
    if tag == PINNED_GRAFANA_TAG:
        return []
    return [
        f"GRAFANA_PANEL_PLUGINS in {os.path.basename(__file__)} was derived from "
        f"{PINNED_GRAFANA_IMAGE}, but {helmrelease_path}'s values.grafana.image.tag is "
        f"{tag!r}. Re-derive the allowlist from the new image and update PINNED_GRAFANA_TAG "
        "together with it."
    ]


def find_datasource_violations(dashboard, filename, known_uids, datasources_path=K8S_DATASOURCES):
    """I1: every datasource ref is a literal uid that datasources.yaml actually declares."""
    violations = []

    def check(obj):
        if "datasource" not in obj:
            return
        ref = obj["datasource"]
        if ref is None:
            return
        if isinstance(ref, str):
            if ref in BUILTIN_DATASOURCE_UIDS:
                return
            violations.append(
                f"{filename}: datasource referenced by NAME {ref!r}; the public renderer resolves "
                f"by uid only. Use {{'type': ..., 'uid': ...}} with a uid from {datasources_path}."
            )
            return
        if not isinstance(ref, dict):
            violations.append(f"{filename}: datasource ref is neither a string nor an object: {ref!r}")
            return
        uid = ref.get("uid")
        if uid in BUILTIN_DATASOURCE_UIDS:
            return
        if not isinstance(uid, str) or not uid:
            violations.append(f"{filename}: datasource ref has no uid: {ref!r}")
        elif "$" in uid:
            violations.append(
                f"{filename}: datasource uid {uid!r} is a template variable; the public renderer "
                "does not interpolate it and the panel returns HTTP 500."
            )
        elif uid not in known_uids:
            violations.append(
                f"{filename}: datasource uid {uid!r} is not declared in {datasources_path} "
                f"(declared: {sorted(known_uids)})."
            )

    walk(dashboard, check)
    return violations


def find_variable_violations(dashboard, filename):
    """I2: no query field carries a dashboard variable (built-in $__ macros are fine)."""
    violations = []

    def check(obj):
        for field in QUERY_FIELDS:
            value = obj.get(field)
            if not isinstance(value, str):
                continue
            for name in VAR_REF.findall(value):
                if BUILTIN_VAR.match(name):
                    continue
                violations.append(
                    f"{filename}: {field} references template variable ${name} -- the public "
                    f"renderer sends it to Prometheus literally, which silently matches nothing. "
                    f"Substitute its concrete value. Query: {value[:90]!r}"
                )

    walk(dashboard, check)
    return violations


def find_templating_violations(dashboard, filename):
    """I3: a public dashboard declares no template variables at all."""
    names = [
        t.get("name") for t in (dashboard.get("templating", {}) or {}).get("list", []) or []
    ]
    if not names:
        return []
    return [
        f"{filename}: declares template variable(s) {names} -- a public dashboard cannot use them, "
        "so each is either dead (remove it) or a dropdown that silently does nothing."
    ]


def find_uncovered_files(discovered, public, private):
    """I4: no dashboard file is outside both sets, so a new one cannot land ungated."""
    accounted = set(public) | set(private)
    return [
        f"{name} is not listed in PUBLIC_DASHBOARDS or DELIBERATELY_PRIVATE in "
        f"{os.path.basename(__file__)} -- add it to whichever applies."
        for name in sorted(set(discovered) - accounted)
    ]


def find_uid_mismatches(dashboard, filename, expected_uid):
    """The file->uid mapping above is what ties this gate to the real public shares."""
    actual = dashboard.get("uid")
    if actual != expected_uid:
        return [
            f"{filename}: dashboard uid is {actual!r} but PUBLIC_DASHBOARDS expects "
            f"{expected_uid!r}; the public share link is bound to the uid, so this file no longer "
            "describes the dashboard that is actually shared."
        ]
    return []


def find_k8s_literal_violations(dashboard_text, filename):
    """k8s-scope-only: no Docker/Compose-era literal survived the label rewrite.

    A query holding one is well-formed but matches nothing the chart-scraped Kubernetes-labelled series
    ever produce, and renders a silent, empty panel.
    """
    return [
        f"{filename}: still contains Docker-era literal {literal!r} -- the Kubernetes-label "
        "rewrite is incomplete for this query; it will render \"No data\" against the real "
        "chart-scraped series."
        for literal in K8S_BANNED_LITERALS
        if literal in dashboard_text
    ]


def load_known_uids_k8s(path):
    """The k8s datasources ConfigMap embeds the same datasources.yaml shape as a string value
    under data.<key> rather than as top-level YAML -- one extra safe_load to unwrap it."""
    import yaml

    with open(path) as f:
        cm = yaml.safe_load(f)
    (embedded_yaml,) = cm["data"].values()
    doc = yaml.safe_load(embedded_yaml)
    return {ds["uid"] for ds in doc.get("datasources", []) if ds.get("uid")}


def check_scope(name, json_dir, known_uids, datasources_path, version_drift_violations, pending):
    """Runs every invariant for one scope, returns (violations, checked_count)."""
    violations = []
    if not os.path.isdir(json_dir):
        return (
            [f"scope {name!r}: json_dir {json_dir} does not exist -- 0 of "
             f"{len(PUBLIC_DASHBOARDS)} public dashboards checked"],
            0,
        )

    violations.extend(version_drift_violations)

    discovered = {os.path.basename(p) for p in glob.glob(os.path.join(json_dir, "*.json"))}
    expected_present = set(PUBLIC_DASHBOARDS) - pending
    missing = expected_present - discovered
    if missing:
        violations.append(
            f"scope {name!r}: expected public dashboard(s) {sorted(missing)} not found under "
            f"{json_dir} (not listed as pending in K8S_PENDING)."
        )

    accounted_discovered = discovered - pending
    violations.extend(
        find_uncovered_files(accounted_discovered, PUBLIC_DASHBOARDS, DELIBERATELY_PRIVATE)
    )

    checked = 0
    for filename in sorted(accounted_discovered):
        path = os.path.join(json_dir, filename)
        with open(path) as f:
            raw_text = f.read()
        dashboard = json.loads(raw_text)
        violations.extend(find_datasource_violations(dashboard, filename, known_uids, datasources_path))
        # I5 applies to private dashboards too: a missing panel plugin breaks the AUTHENTICATED
        # renderer identically. Unlike I2/I3, nothing about it is public-path-specific.
        violations.extend(find_panel_type_violations(dashboard, filename))
        if filename in PUBLIC_DASHBOARDS:
            violations.extend(find_uid_mismatches(dashboard, filename, PUBLIC_DASHBOARDS[filename]))
            violations.extend(find_variable_violations(dashboard, filename))
            violations.extend(find_templating_violations(dashboard, filename))
        if json_dir == K8S_JSON_DIR:
            violations.extend(find_k8s_literal_violations(raw_text, filename))
        checked += 1

    if pending:
        still_pending = pending & (expected_present | pending)
        present_pending = still_pending & discovered
        # A file listed as pending that HAS landed should have been removed from K8S_PENDING in
        # the same change -- caught here rather than silently under-checking it forever.
        if present_pending:
            violations.append(
                f"scope {name!r}: {sorted(present_pending)} exist under {json_dir} but are still "
                f"listed in K8S_PENDING -- remove them from that set now that they are authored."
            )

    return violations, checked


def main():
    try:
        import yaml  # noqa: F401
    except ImportError:
        print("FAIL: PyYAML is required (pip install pyyaml)")
        return 1

    violations = []
    summary_lines = []

    # k8s scope: fail loudly if the directory does not exist, rather than silently skipping; a missing
    # scope is a gate failure, not a no-op.
    if os.path.isdir(K8S_JSON_DIR):
        k8s_known_uids = load_known_uids_k8s(K8S_DATASOURCES)
        if not k8s_known_uids:
            violations.append(
                f"scope 'k8s': {K8S_DATASOURCES} declares no explicit uid."
            )
            k8s_version_violations = []
        else:
            k8s_version_violations = find_grafana_version_drift_k8s(K8S_HELMRELEASE)
        k8s_violations, k8s_checked = check_scope(
            "k8s", K8S_JSON_DIR, k8s_known_uids if k8s_known_uids else set(), K8S_DATASOURCES,
            k8s_version_violations, pending=K8S_PENDING,
        )
        violations.extend(k8s_violations)
        summary_lines.append(
            f"k8s: {k8s_checked} dashboard(s) checked "
            f"({len(K8S_PENDING)} pending: {sorted(K8S_PENDING)})" if K8S_PENDING
            else f"k8s: {k8s_checked} dashboard(s) checked"
        )
    else:
        violations.append(
            f"scope 'k8s': {K8S_JSON_DIR} does not exist -- 0 of {len(PUBLIC_DASHBOARDS)} public "
            "dashboards checked. A scope whose json_dir does not exist fails loudly rather than "
            "being skipped."
        )

    if violations:
        for line in violations:
            print(f"FAIL: {line}")
        return 1

    print("invariants OK -- " + "; ".join(summary_lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
