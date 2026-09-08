#!/usr/bin/env python3
"""Self-test for scripts/verify-deploy-scp-coverage.py's pure check functions.

Prevents the failure this whole quick task was opened to fix, one level up: an edit to the gate
that makes an invariant unfireable is invisible against a compose/workflow pair that already
satisfies every invariant -- the gate goes green and stays green. Each case below feeds the gate's
pure functions an in-memory document engineered to trip exactly one invariant and asserts the
violation is reported; clean inputs assert nothing is reported. No file access, no working-tree
mutation -- this proves the LOGIC fires, not the wiring against the real files (that is what the
one-off red/green run against the real compose/workflow files, and the pre-fix-string
falsification, both prove instead).
"""

import importlib.util
import os
import sys

# The gate's filename carries hyphens (matching this repo's other scripts/verify-*.py naming),
# which is not a valid `import` module name -- loaded explicitly by path instead of renaming it.
_GATE_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "verify-deploy-scp-coverage.py"
)
_spec = importlib.util.spec_from_file_location("verify_deploy_scp_coverage", _GATE_PATH)
_gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_gate)
find_repo_relative_mounts = _gate.find_repo_relative_mounts
find_scp_source_and_target = _gate.find_scp_source_and_target
check_coverage = _gate.check_coverage
check_sources_resolve = _gate.check_sources_resolve


def clean_compose_doc():
    return {
        "services": {
            "caddy": {"volumes": ["./Caddyfile:/etc/caddy/Caddyfile:ro", "caddy-data:/data"]},
            "postgres": {
                "volumes": [
                    "postgres-data:/var/lib/postgresql/data",
                    "./docker/postgres-init:/docker-entrypoint-initdb.d:ro",
                ]
            },
            "node-exporter": {"volumes": ["/:/host:ro,rslave"]},
        }
    }


def clean_workflow_doc():
    return {
        "jobs": {
            "deploy-to-netcup": {
                "steps": [
                    {"uses": "actions/checkout@v5"},
                    {
                        "uses": "appleboy/scp-action@abc123",
                        "with": {
                            "source": "docker-compose.prod.yml,Caddyfile,docker/postgres-init/01-create-databases-and-roles.sh",
                            "target": "/opt/deploy/kanban-board-backend/",
                        },
                    },
                ]
            }
        }
    }


def run_cases():
    fails = []

    def expect_violation(label, violations, must_contain):
        if not any(must_contain in v for v in violations):
            fails.append(
                f"{label}: expected a violation containing {must_contain!r}, got {violations!r}"
            )

    def expect_clean(label, violations):
        if violations:
            fails.append(f"{label}: expected no violations, got {violations!r}")

    # I1 -- an uncovered repo-relative mount.
    doc = clean_compose_doc()
    doc["services"]["grafana"] = {"volumes": ["./docker/grafana/provisioning:/etc/grafana/provisioning:ro"]}
    mount_fails, mounts = find_repo_relative_mounts(doc, "docker-compose.prod.yml")
    expect_clean("I1 setup: mount extraction itself", mount_fails)
    violations = check_coverage(
        mounts, ["docker-compose.prod.yml", "Caddyfile", "docker/postgres-init/01-create-databases-and-roles.sh"], "test-pair"
    )
    expect_violation("I1 (uncovered mount)", violations, "I1 violated")
    expect_violation("I1 (uncovered mount names the path)", violations, "docker/grafana/provisioning")

    # I1 -- the nested-under case (postgres-init) must NOT be flagged; only the single script is
    # transferred for a whole-directory mount, which is the deliberate, intended arrangement.
    _, mounts = find_repo_relative_mounts(clean_compose_doc(), "docker-compose.prod.yml")
    violations = check_coverage(
        mounts,
        ["Caddyfile", "docker/postgres-init/01-create-databases-and-roles.sh"],
        "test-pair",
    )
    expect_clean("I1 (nested-under case is covered, not laxity)", violations)

    # I1 -- a source directory covering a narrower mount inside it (the reverse nesting direction).
    violations = check_coverage(["docker/grafana/provisioning/dashboards"], ["docker/grafana/provisioning"], "test-pair")
    expect_clean("I1 (mount nested under a covering source directory)", violations)

    # I2 -- a source entry that does not exist in the checkout. Injects a fake `exists` so this
    # proves the LOGIC, not real disk state.
    violations = check_sources_resolve(
        ["docker-compose.prod.yml", "docker/typo-path"], "test-pair", exists=lambda p: p != "docker/typo-path"
    )
    expect_violation("I2 (non-existent source entry)", violations, "I2 violated")
    expect_violation("I2 (non-existent source entry names the path)", violations, "docker/typo-path")

    # I2 -- a clean list, all resolving, reports nothing.
    violations = check_sources_resolve(["a", "b"], "test-pair", exists=lambda p: True)
    expect_clean("I2 (all sources resolve)", violations)

    # I3 -- the named job does not exist in the workflow (a rename this gate must not silently miss).
    fails_i3, source_list, target = find_scp_source_and_target(
        clean_workflow_doc(), "deploy-to-renamed-job", "test-pair"
    )
    expect_violation("I3 (job renamed/removed)", fails_i3, "I3 violated")
    if source_list is not None:
        fails.append("I3 (job renamed/removed): expected source_list to be None, got a value")

    # I3 -- the scp-action step itself is missing from an otherwise-real job.
    doc = clean_workflow_doc()
    doc["jobs"]["deploy-to-netcup"]["steps"] = [{"uses": "actions/checkout@v5"}]
    fails_i3, source_list, target = find_scp_source_and_target(doc, "deploy-to-netcup", "test-pair")
    expect_violation("I3 (scp-action step removed)", fails_i3, "I3 violated")

    # I3 -- the scp-action step exists but has no `source:` key.
    doc = clean_workflow_doc()
    del doc["jobs"]["deploy-to-netcup"]["steps"][1]["with"]["source"]
    fails_i3, source_list, target = find_scp_source_and_target(doc, "deploy-to-netcup", "test-pair")
    expect_violation("I3 (missing source: key)", fails_i3, "I3 violated")

    # I3 -- the scp-action step exists but has no `target:` key.
    doc = clean_workflow_doc()
    del doc["jobs"]["deploy-to-netcup"]["steps"][1]["with"]["target"]
    fails_i3, source_list, target = find_scp_source_and_target(doc, "deploy-to-netcup", "test-pair")
    expect_violation("I3 (missing target: key)", fails_i3, "I3 violated")

    # I3 -- a malformed `jobs:` key.
    fails_i3, source_list, target = find_scp_source_and_target({}, "deploy-to-netcup", "test-pair")
    expect_violation("I3 (workflow has no jobs: key)", fails_i3, "I3 violated")

    # I3 -- a clean workflow resolves cleanly, with the expected source list and target.
    fails_i3, source_list, target = find_scp_source_and_target(
        clean_workflow_doc(), "deploy-to-netcup", "test-pair"
    )
    expect_clean("I3 (clean workflow resolves)", fails_i3)
    if source_list != [
        "docker-compose.prod.yml",
        "Caddyfile",
        "docker/postgres-init/01-create-databases-and-roles.sh",
    ]:
        fails.append(f"I3 (clean workflow resolves): unexpected source_list {source_list!r}")
    if target != "/opt/deploy/kanban-board-backend/":
        fails.append(f"I3 (clean workflow resolves): unexpected target {target!r}")

    # I4 -- absolute host paths and bare named volumes are excluded, not flagged as mounts at all.
    # clean_compose_doc() carries all three shapes at once (a repo-relative mount, a bare named
    # volume, and node-exporter's absolute `/:/host` mount) so this proves the exclusion, not just
    # the inclusion.
    mount_fails, mounts = find_repo_relative_mounts(clean_compose_doc(), "docker-compose.prod.yml")
    expect_clean("I4 setup: mount extraction itself", mount_fails)
    if "docker/postgres-init" not in mounts:
        fails.append(f"I4: expected docker/postgres-init among extracted mounts, got {mounts!r}")
    if any(m.startswith("/") for m in mounts):
        fails.append(f"I4: an absolute host path leaked into extracted mounts: {mounts!r}")
    if "data" in mounts or "caddy-data" in mounts or "postgres-data" in mounts:
        fails.append(f"I4: a bare named volume leaked into extracted mounts: {mounts!r}")

    # Malformed compose document -- no `services:` key.
    mount_fails, mounts = find_repo_relative_mounts({}, "docker-compose.prod.yml")
    expect_violation("malformed (no services key)", mount_fails, "no mapping `services:` key found")

    # Malformed compose document -- a service value that is not a mapping.
    doc = {"services": {"app": "not-a-mapping"}}
    mount_fails, mounts = find_repo_relative_mounts(doc, "docker-compose.prod.yml")
    expect_violation("malformed (service value is not a mapping)", mount_fails, "is not a mapping")

    # Clean end-to-end pairing -- covered mounts, resolving sources, no violations anywhere.
    _, mounts = find_repo_relative_mounts(clean_compose_doc(), "docker-compose.prod.yml")
    clean_source = ["Caddyfile", "docker/postgres-init/01-create-databases-and-roles.sh"]
    expect_clean("clean end-to-end coverage", check_coverage(mounts, clean_source, "test-pair"))
    expect_clean(
        "clean end-to-end resolution",
        check_sources_resolve(clean_source, "test-pair", exists=lambda p: True),
    )

    return fails


def main():
    fails = run_cases()
    if fails:
        for line in fails:
            print(f"FAIL: {line}")
        return 1
    print(
        "invariants OK: I1 (coverage, including the nested-under and reverse-nesting cases), I2 "
        "(source resolution), I3 (fail-closed on a renamed job/missing step/missing keys), and I4 "
        "(absolute/named-volume exclusion) all proven to fire, plus malformed-document and "
        "clean-pairing cases"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
