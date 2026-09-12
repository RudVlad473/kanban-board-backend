#!/usr/bin/env python3
r"""Gate: a publicly-shared Grafana dashboard must contain nothing the public renderer cannot resolve.

Same shape as scripts/verify-compose-ports.py: a committed, re-runnable check rather than a comment
restating an invariant nothing enforces.

WHY this exists, measured 2026-09-12 against grafana/grafana:13.2.1 (docs/INFRA_RUNBOOK.md,
"Public Grafana dashboards rendered no data"): all three dashboards shared through
Grafana's public-dashboard feature rendered their shell but every panel showed "Datasource was not
found", for over a month, while every exporter was healthy and every Prometheus target was up. The
public renderer is a DIFFERENT, stricter code path than the logged-in one, and two things that are
perfectly legal in an authenticated dashboard are fatal in a public one:

  1. It resolves a datasource by uid ONLY. A legacy name string ("Prometheus") and a datasource
     template variable ("${ds_prometheus}") both fail lookup, and the panel query returns HTTP 500
     with `publicdashboards.service ... error="data source not found"`.
  2. It interpolates built-in macros ($__rate_interval, $__interval) but NEVER dashboard template
     variables. A query holding $node reaches Prometheus as the literal text "$node", which matches
     no series -- so this one fails SILENTLY, as HTTP 200 with an empty result, indistinguishable
     in the UI from a genuinely idle metric.

Failure 2 is the reason this gate is worth its maintenance: nothing else in the stack notices it.
Grafana logs nothing, Prometheus answers normally, the container stays healthy, and the dashboard
looks merely quiet. The dashboards are vendored from grafana.com, where template variables are the
norm, so every future re-fetch reintroduces exactly this defect.

A THIRD failure, measured 2026-09-12 after the two above were fixed and this gate went green
(docs/INFRA_RUNBOOK.md, "Plugin graph not found"):

  3. A panel whose `type` names a plugin the running Grafana does not ship renders nothing at all.
     Grafana 13 removed Angular outright -- `graph` and `singlestat` are not disabled-by-default,
     they are absent from the image, and `angular_support_enabled` no longer exists as a setting,
     so no configuration can bring them back. The panel draws an error triangle whose tooltip
     reads "Plugin graph not found" verbatim.

Failure 3 is why I5 exists and why it is checked separately from I1-I3 rather than assumed away by
them: the two earlier invariants are about the DATA path, and on the very dashboards that tripped
this one the data path was provably healthy -- all six live public panel-query endpoints returned
HTTP 200 with 793-803 datapoints each while every panel rendered blank. A gate that only proves
queries are well-formed reports success on a dashboard no browser can draw. That is precisely what
happened: this file passed, CI was green, and two of the three public dashboards were still broken.

SCOPE: every *.json under docker/grafana/provisioning/dashboards/json/, split into two disjoint
sets so a new dashboard cannot land ungated (I4): PUBLIC_DASHBOARDS, checked against every
invariant, and DELIBERATELY_PRIVATE, documented and exempted from the public-only invariants. I1
(datasource refs resolve by uid) applies to BOTH sets -- a name-string ref happens to work in the
authenticated path, but it is the same latent defect one "share publicly" click away.

KNOWN HOLES, enumerated rather than left to be rediscovered:
  * This reads the COMMITTED JSON, not the running Grafana. A dashboard edited in the UI and saved
    into the grafana-data volume, or shared publicly from the UI without a matching repo change, is
    invisible here. PUBLIC_DASHBOARDS below is therefore a claim about intent that a human keeps
    true; the authoritative list lives at GET /api/dashboards/public-dashboards on the VM.
  * Passing I2 does NOT mean a panel renders. It means the query is free of the specific defect
    measured above. A query can still be wrong, reference a renamed metric, or match nothing --
    proving a panel returns data needs a live Prometheus, which this gate does not have.
  * The hardcoded label values that replaced the template variables (instance="node-exporter:9100",
    job="node", ...) are correct for a single-host deployment and are NOT checked against live
    Prometheus here. If the stack ever grows a second node or an exporter is renamed, this gate
    stays green while the dashboards quietly narrow to a host that no longer exists.
  * A dashboard can be moved into DELIBERATELY_PRIVATE in the same pull request that adds a
    variable to it. This gate makes that a REVIEWED choice, not an impossible one.
"""

import glob
import json
import os
import re
import sys

JSON_DIR = "docker/grafana/provisioning/dashboards/json"
DATASOURCES = "docker/grafana/provisioning/datasources/datasources.yaml"
COMPOSE = "docker-compose.prod.yml"

# Shared through Grafana's public-dashboard feature, so subject to every invariant below.
# Verified against GET /api/dashboards/public-dashboards on the VM, 2026-09-12.
PUBLIC_DASHBOARDS = {
    "node-exporter-full.json": "rYdddlPWk",
    "cadvisor.json": "pMEd7m0Mz",
    "postgres-exporter.json": "v5ciIbUZz",
}

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

# The image tag docker-compose.prod.yml pins, and therefore the only version PANEL_PLUGINS below
# describes. Checked against the Compose file at run time (I6) so a Grafana bump cannot silently
# leave this allowlist describing a version nothing runs any more.
PINNED_GRAFANA_IMAGE = "grafana/grafana:13.2.1"

# Derived, not recalled: every directory under /usr/share/grafana/public/app/plugins/panel/ that
# contains a plugin.json, read out of the pinned image itself on 2026-09-12. Exactly 30. The bare
# directory listing has 32 entries -- `AGENTS.md` and `test-utils.ts` ship there too and are not
# plugins, which is why the plugin.json filter is the definition rather than the listing.
#
# No GF_INSTALL_PLUGINS is set anywhere in docker-compose.prod.yml, so nothing widens this set at
# run time. If an external panel plugin is ever installed, add it here WITH the install mechanism
# named, or this gate will reject a dashboard that would in fact render.
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


def find_grafana_version_drift(compose_text):
    """I6: the allowlist above describes the image Compose actually pins, or this gate is fiction.

    A panel-plugin allowlist is only true of one Grafana version. Bumping the image without
    re-deriving it would leave I5 silently enforcing a former version's plugin set -- passing a
    dashboard that no longer renders, or failing one that does.
    """
    if re.search(rf"image:\s*{re.escape(PINNED_GRAFANA_IMAGE)}\s*$", compose_text, re.MULTILINE):
        return []
    found = re.findall(r"image:\s*(grafana/grafana:\S+)", compose_text)
    return [
        f"GRAFANA_PANEL_PLUGINS in {os.path.basename(__file__)} was derived from "
        f"{PINNED_GRAFANA_IMAGE}, but docker-compose.prod.yml pins {found or 'no grafana image'}. "
        "Re-derive the allowlist from the new image "
        "(`docker run --rm --entrypoint sh <image> -c 'cd /usr/share/grafana/public/app/plugins/"
        "panel && for d in */; do [ -f \"$d/plugin.json\" ] && echo \"${d%/}\"; done'`) "
        "and update PINNED_GRAFANA_IMAGE together with it."
    ]


def find_datasource_violations(dashboard, filename, known_uids):
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
                f"by uid only. Use {{'type': ..., 'uid': ...}} with a uid from {DATASOURCES}."
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
                f"{filename}: datasource uid {uid!r} is not declared in {DATASOURCES} "
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


def load_known_uids(path):
    import yaml

    with open(path) as f:
        doc = yaml.safe_load(f)
    return {ds["uid"] for ds in doc.get("datasources", []) if ds.get("uid")}


def main():
    try:
        import yaml  # noqa: F401
    except ImportError:
        print("FAIL: PyYAML is required (pip install pyyaml)")
        return 1

    known_uids = load_known_uids(DATASOURCES)
    if not known_uids:
        print(
            f"FAIL: {DATASOURCES} declares no explicit uid. Grafana would derive one, leaving the "
            "dashboards' hard-coded refs depending on an underived value."
        )
        return 1

    discovered = {os.path.basename(p) for p in glob.glob(os.path.join(JSON_DIR, "*.json"))}
    violations = find_uncovered_files(discovered, PUBLIC_DASHBOARDS, DELIBERATELY_PRIVATE)

    with open(COMPOSE) as f:
        violations.extend(find_grafana_version_drift(f.read()))

    for name in sorted(discovered):
        with open(os.path.join(JSON_DIR, name)) as f:
            dashboard = json.load(f)
        violations.extend(find_datasource_violations(dashboard, name, known_uids))
        # I5 applies to private dashboards too: a missing panel plugin breaks the AUTHENTICATED
        # renderer identically. Unlike I2/I3, nothing about it is public-path-specific.
        violations.extend(find_panel_type_violations(dashboard, name))
        if name in PUBLIC_DASHBOARDS:
            violations.extend(find_uid_mismatches(dashboard, name, PUBLIC_DASHBOARDS[name]))
            violations.extend(find_variable_violations(dashboard, name))
            violations.extend(find_templating_violations(dashboard, name))

    if violations:
        for line in violations:
            print(f"FAIL: {line}")
        return 1

    # Rendered from the sets themselves -- a hardcoded success string can claim a guarantee the
    # checks above stopped enforcing the moment those sets changed.
    print(
        f"invariants OK -- {len(discovered)} dashboard(s) checked; "
        f"every datasource ref resolves to a uid declared in {DATASOURCES} "
        f"({sorted(known_uids)}); every panel type is one of the "
        f"{len(GRAFANA_PANEL_PLUGINS)} plugins {PINNED_GRAFANA_IMAGE} ships (or a structural "
        f"{sorted(STRUCTURAL_PANEL_TYPES)}); public dashboards {sorted(PUBLIC_DASHBOARDS)} carry "
        f"no template variables in {QUERY_FIELDS} and declare none"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
