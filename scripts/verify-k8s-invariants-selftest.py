#!/usr/bin/env python3
"""Self-test for scripts/verify-k8s-invariants.py's pure check functions.

Same rationale as scripts/verify-compose-ports-selftest.py: prevents the failure this whole gate
exists to catch, one level up -- an edit to the gate that makes an invariant unfireable is
invisible against a tree that already satisfies every invariant, so the gate goes green and stays
green. Each case below builds an in-memory rendered document (or source-file text) engineered to
trip exactly one invariant and asserts the violation is reported; a clean fixture asserts nothing
is reported. No disk access, no kubectl, no working-tree mutation -- this proves the LOGIC fires,
not the wiring against the real k8s/ tree (that is what the gate's own real-tree run proves).
"""

import importlib.util
import os
import sys

_GATE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "verify-k8s-invariants.py")
_spec = importlib.util.spec_from_file_location("verify_k8s_invariants", _GATE_PATH)
_gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_gate)


def clean_app_deployment():
    return {
        "kind": "Deployment",
        "metadata": {"name": "app"},
        "spec": {
            "template": {
                "spec": {
                    "initContainers": [
                        {
                            "name": "register-schemas",
                            "image": "rudenkovladimir/kanban-board-backend-nonprod:main-1-abcdef1",
                            "resources": {
                                "requests": {"memory": "512Mi"},
                                "limits": {"memory": "1Gi"},
                            },
                        }
                    ],
                    "containers": [
                        {
                            "name": "app",
                            "image": "rudenkovladimir/kanban-board-backend-nonprod:main-1-abcdef1",
                            "resources": {
                                "requests": {"memory": "512Mi"},
                                "limits": {"memory": "1Gi"},
                            },
                        }
                    ],
                }
            }
        },
    }


def clean_service():
    return {"kind": "Service", "metadata": {"name": "app"}, "spec": {"type": "ClusterIP"}}


def clean_postgres_statefulset():
    return {
        "kind": "StatefulSet",
        "metadata": {"name": "postgres"},
        "spec": {
            "template": {
                "spec": {
                    "containers": [
                        {
                            "name": "postgres",
                            "image": "postgres:16",
                            "resources": {
                                "requests": {"memory": "128Mi"},
                                "limits": {"memory": "256Mi"},
                            },
                            "command": [
                                "postgres",
                                "-c",
                                "shared_buffers=32MB",
                                "-c",
                                "work_mem=4MB",
                                "-c",
                                "max_connections=25",
                            ],
                        }
                    ]
                }
            }
        },
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

    # Clean fixture: every rendered-object check reports nothing.
    for label, doc in (
        ("clean app Deployment", clean_app_deployment()),
        ("clean Service", clean_service()),
        ("clean postgres StatefulSet", clean_postgres_statefulset()),
    ):
        expect_clean(f"clean/{label}", _gate.check_rendered_doc(doc, "fixture"))

    # I1 -- a NodePort Service.
    doc = clean_service()
    doc["spec"]["type"] = "NodePort"
    expect_violation("I1", _gate.check_rendered_doc(doc, "fixture"), "I1:")

    # I2 -- hostNetwork.
    doc = clean_app_deployment()
    doc["spec"]["template"]["spec"]["hostNetwork"] = True
    expect_violation("I2 (hostNetwork)", _gate.check_rendered_doc(doc, "fixture"), "I2:")

    # I2 -- hostPort.
    doc = clean_app_deployment()
    doc["spec"]["template"]["spec"]["containers"][0]["ports"] = [{"hostPort": 8080}]
    expect_violation("I2 (hostPort)", _gate.check_rendered_doc(doc, "fixture"), "I2:")

    # I2 -- hostPath volume.
    doc = clean_app_deployment()
    doc["spec"]["template"]["spec"]["volumes"] = [{"name": "x", "hostPath": {"path": "/etc"}}]
    expect_violation("I2 (hostPath)", _gate.check_rendered_doc(doc, "fixture"), "I2:")

    # I3 -- missing memory requests/limits.
    doc = clean_app_deployment()
    del doc["spec"]["template"]["spec"]["containers"][0]["resources"]
    expect_violation("I3 (missing)", _gate.check_rendered_doc(doc, "fixture"), "I3:")

    # I3 -- requests exceed limits.
    doc = clean_app_deployment()
    doc["spec"]["template"]["spec"]["containers"][0]["resources"] = {
        "requests": {"memory": "2Gi"},
        "limits": {"memory": "1Gi"},
    }
    expect_violation("I3 (requests > limits)", _gate.check_rendered_doc(doc, "fixture"), "I3:")

    # I4 -- a memory: line with no MEASURED/PROVISIONAL comment in the 25 lines above it.
    unmeasured_text = "\n".join(["# just a comment"] * 30 + ["    memory: 512Mi"])
    expect_violation(
        "I4 (unmeasured)",
        _gate.check_i4_source_file("fixture.yaml", unmeasured_text),
        "I4:",
    )
    measured_text = "# MEASURED (fixture, 2026-01-01)\n    memory: 512Mi"
    expect_clean("I4 (measured, clean)", _gate.check_i4_source_file("fixture.yaml", measured_text))

    # I5 -- untagged image.
    doc = clean_app_deployment()
    doc["spec"]["template"]["spec"]["containers"][0]["image"] = "rudenkovladimir/kanban-board-backend"
    expect_violation("I5 (untagged)", _gate.check_rendered_doc(doc, "fixture"), "I5:")

    # I5 -- :latest image.
    doc = clean_app_deployment()
    doc["spec"]["template"]["spec"]["containers"][0]["image"] = "rudenkovladimir/kanban-board-backend:latest"
    expect_violation("I5 (:latest)", _gate.check_rendered_doc(doc, "fixture"), "I5:")

    # I5 -- overlay kustomization.yaml with a bad newTag.
    bad_overlay = (
        "apiVersion: kustomize.config.k8s.io/v1beta1\n"
        "kind: Kustomization\n"
        "images:\n"
        "  - name: rudenkovladimir/kanban-board-backend\n"
        "    newName: rudenkovladimir/kanban-board-backend-nonprod\n"
        "    newTag: not-a-sortable-tag\n"
    )
    expect_violation(
        "I5 (overlay bad tag)",
        _gate.check_i5_overlay_kustomization("fixture/kustomization.yaml", bad_overlay),
        "I5:",
    )
    good_overlay = (
        "apiVersion: kustomize.config.k8s.io/v1beta1\n"
        "kind: Kustomization\n"
        "images:\n"
        "  - name: rudenkovladimir/kanban-board-backend\n"
        "    newName: rudenkovladimir/kanban-board-backend-nonprod\n"
        '    newTag: main-1-abcdef1 # {"$imagepolicy": "flux-system:kanban-board-backend-nonprod:tag"}\n'
    )
    expect_clean(
        "I5 (overlay good tag, clean)",
        _gate.check_i5_overlay_kustomization("fixture/kustomization.yaml", good_overlay),
    )

    # I6 -- shared_buffers exceeds a quarter of the limit.
    doc = clean_postgres_statefulset()
    doc["spec"]["template"]["spec"]["containers"][0]["command"] = [
        "postgres",
        "-c",
        "shared_buffers=128MB",
        "-c",
        "work_mem=4MB",
        "-c",
        "max_connections=25",
    ]
    expect_violation("I6 (shared_buffers)", _gate.check_rendered_doc(doc, "fixture"), "I6:")

    # I6 -- worst-case connection memory exceeds 85% of the limit.
    doc = clean_postgres_statefulset()
    doc["spec"]["template"]["spec"]["containers"][0]["resources"]["limits"]["memory"] = "64Mi"
    doc["spec"]["template"]["spec"]["containers"][0]["command"] = [
        "postgres",
        "-c",
        "shared_buffers=8MB",
        "-c",
        "work_mem=4MB",
        "-c",
        "max_connections=25",
    ]
    expect_violation("I6 (worst-case)", _gate.check_rendered_doc(doc, "fixture"), "I6:")

    # I7 -- a discovered root neither rendered nor excluded.
    expect_violation(
        "I7",
        _gate.find_uncovered_roots(["k8s/base/orphan"], set(), set()),
        "I7:",
    )
    expect_clean(
        "I7 (accounted for, clean)",
        _gate.find_uncovered_roots(["k8s/base/app"], {"k8s/base/app"}, set()),
    )

    # I8 -- a rendered Secret.
    doc = {"kind": "Secret", "metadata": {"name": "app-env"}}
    expect_violation("I8", _gate.check_rendered_doc(doc, "fixture"), "I8:")

    return fails


def main():
    fails = run_cases()
    if fails:
        for line in fails:
            print(f"SELFTEST FAIL: {line}")
        return 1
    print("selftest OK -- I1-I8 each fire on an engineered violation; clean fixtures report nothing")
    return 0


if __name__ == "__main__":
    sys.exit(main())
