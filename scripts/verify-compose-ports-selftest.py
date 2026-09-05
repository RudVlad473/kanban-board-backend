#!/usr/bin/env python3
"""Self-test for scripts/verify-compose-ports.py's `find_violations` (quick task 260905-qxi, D-08).

Prevents the failure this whole task was opened to fix, one level up: an edit to the gate that
makes an invariant unfireable is invisible against a compose file that already satisfies every
invariant -- the gate goes green and stays green. Each case below feeds `find_violations` an
in-memory document engineered to trip exactly one invariant and asserts the violation is reported
and names the offending service; one clean document asserts nothing is reported. No file access, no
working-tree mutation -- this proves the LOGIC fires, not the wiring against the real files (that is
what the one-off red/green run against the real compose files proves instead).
"""

import importlib.util
import os
import sys

# The gate's filename carries hyphens (matching this repo's other scripts/verify-*.py naming),
# which is not a valid `import` module name -- loaded explicitly by path instead of renaming the
# gate to fit `import`'s syntax.
_GATE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "verify-compose-ports.py")
_spec = importlib.util.spec_from_file_location("verify_compose_ports", _GATE_PATH)
_gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_gate)
find_violations = _gate.find_violations

PROD_ALLOWED = {"caddy"}
NONPROD_ALLOWED = set()


def clean_prod_doc():
    return {
        "services": {
            "caddy": {"ports": ["80:80", "443:443"]},
            "app": {"image": "app:latest"},
            "postgres": {"image": "postgres:16"},
        }
    }


def clean_nonprod_doc():
    return {
        "services": {
            "app-nonprod": {"image": "app-nonprod:latest"},
            "redpanda-nonprod": {"image": "redpanda:latest"},
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

    # I1 -- a non-allowed service in prod carries `ports:`.
    doc = clean_prod_doc()
    doc["services"]["app"]["ports"] = ["8080:8080"]
    expect_violation(
        "I1 (prod, non-allowed service publishes)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml"),
        "I1 violated",
    )

    # I1 -- nonprod's allowed set is EMPTY, so even an empty `ports: []` on any service violates.
    doc = clean_nonprod_doc()
    doc["services"]["app-nonprod"]["ports"] = []
    expect_violation(
        "I1 (nonprod, empty ports list still counts as publishing)",
        find_violations(doc, NONPROD_ALLOWED, "docker-compose.nonprod.yml"),
        "I1 violated",
    )

    # I2 -- network_mode: host on a non-allowed service.
    doc = clean_prod_doc()
    doc["services"]["postgres"]["network_mode"] = "host"
    expect_violation(
        "I2 (non-allowed service, network_mode: host)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml"),
        "I2 violated",
    )

    # I2 -- network_mode: host on the ALLOWED service too. The allowed set must not exempt it.
    doc = clean_prod_doc()
    doc["services"]["caddy"]["network_mode"] = "host"
    expect_violation(
        "I2 (allowed service caddy, network_mode: host)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml"),
        "I2 violated",
    )

    # I3 -- the allowed set names a service that does not exist in the file.
    doc = clean_prod_doc()
    del doc["services"]["caddy"]
    expect_violation(
        "I3 (allowed publisher renamed/removed)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml"),
        "I3 violated",
    )

    # I4 -- caddy publishes an extra port beyond 80/443.
    doc = clean_prod_doc()
    doc["services"]["caddy"]["ports"] = ["80:80", "443:443", "8443:8443"]
    expect_violation(
        "I4 (caddy publishes an extra port)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml"),
        "I4 violated",
    )

    # I4 -- caddy publishes fewer than the required two.
    doc = clean_prod_doc()
    doc["services"]["caddy"]["ports"] = ["80:80"]
    expect_violation(
        "I4 (caddy missing 443)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml"),
        "I4 violated",
    )

    # I4 -- caddy uses the long mapping syntax instead of the exact literal strings.
    doc = clean_prod_doc()
    doc["services"]["caddy"]["ports"] = [
        {"target": 80, "published": 80},
        {"target": 443, "published": 443},
    ]
    expect_violation(
        "I4 (caddy uses long mapping syntax)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml"),
        "I4 violated",
    )

    # Malformed document -- `services` missing entirely.
    expect_violation(
        "malformed (no services key)",
        find_violations({}, PROD_ALLOWED, "docker-compose.prod.yml"),
        "no mapping `services:` key found",
    )

    # Malformed document -- a service value that is not a mapping.
    doc = clean_prod_doc()
    doc["services"]["app"] = "not-a-mapping"
    expect_violation(
        "malformed (service value is not a mapping)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml"),
        "is not a mapping",
    )

    # Clean documents -- both files, satisfying every invariant, report nothing.
    expect_clean(
        "clean prod document",
        find_violations(clean_prod_doc(), PROD_ALLOWED, "docker-compose.prod.yml"),
    )
    expect_clean(
        "clean nonprod document",
        find_violations(clean_nonprod_doc(), NONPROD_ALLOWED, "docker-compose.nonprod.yml"),
    )

    return fails


def main():
    fails = run_cases()
    if fails:
        for line in fails:
            print(f"FAIL: {line}")
        return 1
    print("invariants OK: every invariant (I1-I4) proven to fire, plus malformed-document and clean-document cases")
    return 0


if __name__ == "__main__":
    sys.exit(main())
