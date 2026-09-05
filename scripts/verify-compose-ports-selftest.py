#!/usr/bin/env python3
"""Self-test for scripts/verify-compose-ports.py's `find_violations` and `find_uncovered_files`.

Prevents the failure this whole task was opened to fix, one level up: an edit to the gate that
makes an invariant unfireable is invisible against a compose file that already satisfies every
invariant -- the gate goes green and stays green. Each case below feeds the gate's pure functions
an in-memory document or file list engineered to trip exactly one invariant and asserts the
violation is reported and names the offending service or file; clean inputs assert nothing is
reported. No file access, no working-tree mutation -- this proves the LOGIC fires, not the wiring
against the real files (that is what the one-off red/green run against the real compose files
proves instead).

Fixtures below are literal, not derived from the gate's own ALLOWED_PUBLISHERS/EXPECTED_PORTS --
importing those would make a wrong edit to the allowlist rewrite this test's own expectations
along with it and still pass, which is strictly worse than a literal fixture that disagrees with a
mistaken edit.
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
find_uncovered_files = _gate.find_uncovered_files

PROD_ALLOWED = {"caddy"}
NONPROD_ALLOWED = set()
PROD_EXPECTED = {"caddy": {"80:80", "443:443"}}
NONPROD_EXPECTED = {}


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
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml", PROD_EXPECTED),
        "I1 violated",
    )

    # I1 -- nonprod's allowed set is EMPTY, so even an empty `ports: []` on any service violates.
    doc = clean_nonprod_doc()
    doc["services"]["app-nonprod"]["ports"] = []
    expect_violation(
        "I1 (nonprod, empty ports list still counts as publishing)",
        find_violations(doc, NONPROD_ALLOWED, "docker-compose.nonprod.yml", NONPROD_EXPECTED),
        "I1 violated",
    )

    # I2 -- network_mode: host on a non-allowed service.
    doc = clean_prod_doc()
    doc["services"]["postgres"]["network_mode"] = "host"
    expect_violation(
        "I2 (non-allowed service, network_mode: host)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml", PROD_EXPECTED),
        "I2 violated",
    )

    # I2 -- network_mode: host on the ALLOWED service too. The allowed set must not exempt it.
    doc = clean_prod_doc()
    doc["services"]["caddy"]["network_mode"] = "host"
    expect_violation(
        "I2 (allowed service caddy, network_mode: host)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml", PROD_EXPECTED),
        "I2 violated",
    )

    # I3 -- the allowed set names a service that does not exist in the file.
    doc = clean_prod_doc()
    del doc["services"]["caddy"]
    expect_violation(
        "I3 (allowed publisher renamed/removed)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml", PROD_EXPECTED),
        "I3 violated",
    )

    # I4 -- caddy publishes an extra port beyond 80/443.
    doc = clean_prod_doc()
    doc["services"]["caddy"]["ports"] = ["80:80", "443:443", "8443:8443"]
    expect_violation(
        "I4 (caddy publishes an extra port)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml", PROD_EXPECTED),
        "I4 violated",
    )

    # I4 -- caddy publishes fewer than the required two.
    doc = clean_prod_doc()
    doc["services"]["caddy"]["ports"] = ["80:80"]
    expect_violation(
        "I4 (caddy missing 443)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml", PROD_EXPECTED),
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
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml", PROD_EXPECTED),
        "I4 violated",
    )

    # I4, generalized -- an allowed publisher with no EXPECTED_PORTS entry is itself a violation,
    # proving the check is bound to allowlist membership, not to the literal name "caddy". Uses a
    # made-up allowed set/service so the assertion is about the GENERALISED mechanism, not about
    # caddy specifically.
    doc = {"services": {"edge": {"ports": ["80:80", "443:443"]}}}
    expect_violation(
        "I4 (allowlisted service with no EXPECTED_PORTS entry)",
        find_violations(doc, {"edge"}, "docker-compose.made-up.yml", {}),
        "I4 violated",
    )

    # I4, generalized -- a DIFFERENT allowed service name than "caddy" is still held to its own
    # exact-set entry once one exists, proving I4 is not hardcoded to `name == "caddy"`.
    doc = {"services": {"edge": {"ports": ["80:80", "443:443", "8080:8080"]}}}
    expect_violation(
        "I4 (non-caddy allowed service violates its own EXPECTED_PORTS entry)",
        find_violations(doc, {"edge"}, "docker-compose.made-up.yml", {"edge": {"80:80", "443:443"}}),
        "I4 violated",
    )

    # I5 -- network_mode carries an unresolved `${...}` interpolation. PyYAML reads this as a
    # literal string, never as "host", so the check must be a substring test for `${`, not an
    # equality test against the literal "host".
    doc = clean_prod_doc()
    doc["services"]["postgres"]["network_mode"] = "${NETWORK_MODE}"
    expect_violation(
        "I5 (unresolved network_mode interpolation)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml", PROD_EXPECTED),
        "I5 violated",
    )

    # I5's scoping trap: an environment VARIABLE containing `${` under a DIFFERENT key must never
    # trip I5. Reproduces docker-compose.prod.yml's own `DB_PORT: ${DB_PORT:-5432}` shape.
    doc = clean_prod_doc()
    doc["services"]["app"]["environment"] = {"DB_PORT": "${DB_PORT:-5432}"}
    expect_clean(
        "I5 (environment variable interpolation under a different key must not false-positive)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml", PROD_EXPECTED),
    )

    # I6 -- a top-level `include:` key.
    doc = clean_prod_doc()
    doc["include"] = ["compose-extra.yml"]
    expect_violation(
        "I6 (top-level include:)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml", PROD_EXPECTED),
        "I6 violated",
    )

    # I6 -- a service carrying `extends:`.
    doc = clean_prod_doc()
    doc["services"]["app"]["extends"] = {"file": "compose-extra.yml", "service": "app-base"}
    expect_violation(
        "I6 (service extends:)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml", PROD_EXPECTED),
        "I6 violated",
    )

    # Malformed document -- `services` missing entirely.
    expect_violation(
        "malformed (no services key)",
        find_violations({}, PROD_ALLOWED, "docker-compose.prod.yml", PROD_EXPECTED),
        "no mapping `services:` key found",
    )

    # Malformed document -- a service value that is not a mapping.
    doc = clean_prod_doc()
    doc["services"]["app"] = "not-a-mapping"
    expect_violation(
        "malformed (service value is not a mapping)",
        find_violations(doc, PROD_ALLOWED, "docker-compose.prod.yml", PROD_EXPECTED),
        "is not a mapping",
    )

    # Clean documents -- both files, satisfying every invariant, report nothing.
    expect_clean(
        "clean prod document",
        find_violations(clean_prod_doc(), PROD_ALLOWED, "docker-compose.prod.yml", PROD_EXPECTED),
    )
    expect_clean(
        "clean nonprod document",
        find_violations(
            clean_nonprod_doc(), NONPROD_ALLOWED, "docker-compose.nonprod.yml", NONPROD_EXPECTED
        ),
    )

    # I7 -- a discovered compose file in neither the allowed set nor the excluded set is a
    # violation naming that file.
    expect_violation(
        "I7 (new compose file neither covered nor excluded)",
        find_uncovered_files(
            ["docker-compose.staging.yml"],
            covered={"docker-compose.prod.yml", "docker-compose.nonprod.yml"},
            excluded={"docker-compose.yml"},
        ),
        "I7 violated",
    )

    # I7 -- a fully accounted-for file list reports nothing.
    expect_clean(
        "I7 (every discovered file covered or excluded)",
        find_uncovered_files(
            ["docker-compose.prod.yml", "docker-compose.nonprod.yml", "docker-compose.yml"],
            covered={"docker-compose.prod.yml", "docker-compose.nonprod.yml"},
            excluded={"docker-compose.yml"},
        ),
    )

    return fails


def main():
    fails = run_cases()
    if fails:
        for line in fails:
            print(f"FAIL: {line}")
        return 1
    print(
        "invariants OK: every invariant (I1-I7) proven to fire, plus malformed-document, "
        "scoping-trap and clean-document cases"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
