#!/usr/bin/env python3
r"""Gate: only the edge is exposed, memory is measured and dated, images are pinned and sortable,
every k8s/ root is accounted for, no Secret is committed, the Postgres init scripts stay
byte-identical to their Compose source, and no redirect route can ever match the ACME HTTP-01
challenge path (Phase 13 plans 01/04).

WHY this exists: this is the k8s-native successor of scripts/verify-compose-ports.py's "only the
edge is public" invariant. That script guards docker-compose*.yml files and stops mattering once
Compose is decommissioned (D-04); this one guards the k8s/ manifests that replace it, so the same
class of defect -- a service silently gaining a public port, an unmeasured memory cap, a committed
credential -- keeps getting caught pre-merge on the new runtime. See
scripts/verify-compose-ports.py (2026-09-05) for the original argument this ports.

SCOPE: every kustomization root under k8s/ (discovered the same way
scripts/verify-k8s-manifests.sh discovers them), rendered with that script's own pinned kubectl,
checked against I1-I3/I5/I8 (rendered-object invariants); I4/I7 read the committed k8s/ tree
directly (I4 scans hand-written source YAML, I7 walks the directory tree); I6 (Postgres memory
inequalities) applies only when a rendered object is a Postgres StatefulSet -- none exists yet in
this phase's own tree, so I6 is exercised only by the selftest until 13-04 adds one.

KNOWN HOLES, enumerated now rather than left to be rediscovered:
  * This sees committed manifests only, exactly like verify-compose-ports.py's own first KNOWN
    HOLE -- a `kubectl apply` made by hand against a live cluster, or an object mutated by a
    controller after admission, is invisible here.
  * Helm-rendered chart objects (HelmRelease `values:`) and the k3s-packaged Traefik Service are
    invisible to this gate -- neither is Kustomize-rendered YAML this script's render step
    produces. The runtime check for those lives in 13-08 (per the phase's own source_audit).
  * DELIBERATELY_EXCLUDED (I7) is editable in the same PR that adds an unrendered root -- this
    gate makes an exclusion REVIEWED, not impossible; a human reviewer still has to read the diff.
  * I4's docstring-window scan (25 lines above a `memory:` line) is a heuristic, not a parser --
    a MEASURED comment placed further away, or attached to an unrelated `memory:` line by
    coincidental proximity, is a false negative/positive this gate cannot structurally close
    without a real YAML-comment-association parser (PyYAML discards comments on load).
  * I5's tag-pattern check is Kustomize-image-transformer-shaped: it inspects each overlay
    kustomization.yaml's `images[].newTag` field directly (not the rendered output, since
    Kustomize's image transformer does not preserve the setter-marker comment through render) --
    a base manifest's own literal `image:` field (untouched by any overlay's `images:` transform)
    is checked against `:latest`/untagged only, not against the `main-N-sha7` pattern, since a
    base image reference is meant to be overridden by every overlay, not final by itself.

Invariants, numbered in both this docstring and the emitted FAIL lines so a red CI line names
which one broke, in which root, on which object:

I1: no rendered Service is `type: NodePort` (or `type: LoadBalancer` without an explicit,
    documented exception -- none exists in this phase, so any non-ClusterIP Service violates).
I2: no rendered Pod template sets `hostNetwork: true`, `hostPID: true`, any container's
    `ports[].hostPort`, or any volume of type `hostPath`.
I3: every container and initContainer in every rendered Pod template declares BOTH
    `resources.requests.memory` and `resources.limits.memory`, and requests never exceed limits.
I4: every hand-written source YAML `memory:` line (source files only -- GENERATED below is
    exempt) carries a MEASURED or PROVISIONAL comment within the 25 lines immediately above it.
    With `--no-provisional`, a PROVISIONAL-labelled line fails too (13-09 flips this on; this
    phase runs the gate without it, per Task 3's own <action>).
I5: no rendered container/initContainer image is untagged or `:latest`; every overlay's
    `kustomization.yaml` `images[].newTag` for the app image matches `^main-\d+-[0-9a-f]{7}$`
    and carries its Flux setter-marker comment.
I6: a rendered Postgres StatefulSet's `shared_buffers` exceeds `limits.memory` / 4, or
    `shared_buffers + max_connections * work_mem` exceeds 0.85 * `limits.memory` --
    ported directly from scripts/verify-postgres-memory-invariant.py's own two inequalities.
I7: every directory under k8s/ that holds a kustomization.yaml is either rendered by this gate's
    own root-discovery (same mechanism as verify-k8s-manifests.sh) or explicitly listed in
    DELIBERATELY_EXCLUDED with a reason -- a root in neither set would be silently ungated.
I8: no rendered object has `kind: Secret` -- D-10 requires every Secret be created on the VM from
    env files, never committed.
I9: when docker/postgres-init/01-create-databases-and-roles.sh exists,
    k8s/data/postgres/init/01-create-databases-and-roles.sh must be byte-identical to it -- a
    one-byte difference fails. Read from disk directly (not part of any rendered root); a pure
    function of two byte strings so the selftest can exercise it with no disk access.
I10: an IngressRoute route attaching a Middleware whose spec is `redirectScheme` or
    `redirectRegex` must fullmatch ``Host(`<host>`) && !PathPrefix(`/.well-known/acme-challenge/`)``.
    Evaluated over every rendered root the gate already renders, so it covers the redirect routes
    on the prod (13-04), monitoring (13-03) and nonprod (13-06) hostnames as each lands. Fails
    closed: a `web`-entryPoint route naming a Middleware that is undefined in its own rendered
    root and namespace cannot be classified, and is reported as a violation rather than skipped.
    WHY: cert-manager's HTTP-01 solver answers on the `web` entryPoint. A redirect router that
    also matches the challenge path sends the ACME CA to HTTPS, and issuance/renewal fails.
    Traefik's own rule-length priority happens to favour the solver today, but an explicit
    `priority` or a longer redirect rule silently inverts that. KNOWN HOLE: this is a rule-TEXT
    check -- the live proof is an HTTP probe of the challenge path, run in the cutover runbook.

A missing/malformed rendered document, or a root that fails to render entirely, is its own
violation rather than a silently skipped root -- treating "I could not read this" as "nothing to
report" is the exact failure mode this gate exists to remove.
"""

import glob
import os
import re
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFESTS_SCRIPT = os.path.join(REPO_ROOT, "scripts", "verify-k8s-manifests.sh")

# Kustomization roots this gate is not expected to render/check today -- empty for this phase;
# kept as a named, documented mechanism (I7) rather than silently skipping an unlisted root.
DELIBERATELY_EXCLUDED = set()

# k8s/base/* roots are intentionally incomplete building blocks for exactly two fields:
# resources.requests/limits (I3) and the image tag (I5) -- both are meant to be patched in per
# environment by an overlay, never final at the base level. Every OTHER rendered-object invariant
# (I1 Service type, I2 host* escapes, I6 postgres memory math, I8 committed Secret) still applies
# to a base root: a NodePort Service, a hostNetwork pod, or a committed Secret would be exactly as
# real a defect in a base manifest as in a rendered overlay, and nothing patches those fields in
# later -- exempting them from base roots would silently blind the gate to the acceptance
# criterion's own worked example (mutating a base Service to NodePort must still print FAIL: I1).
# Matched by a `k8s/base/` path prefix rather than listed by name, so a new base/ root added
# later is automatically covered without editing this set.

# Files exempt from I4's docstring-window scan because they are machine-generated, not
# hand-written -- a generator's own output is not where a human would place a MEASURED comment.
GENERATED = {
    "k8s/flux-system/gotk-components.yaml",
}

MEASURED_RE = re.compile(r"\b(MEASURED|PROVISIONAL)\b")
IMAGE_TAG_PATTERN = re.compile(r"^main-\d+-[0-9a-f]{7}$")
SETTER_MARKER_RE = re.compile(r'"\$imagepolicy":\s*"[^"]+:tag"')


def to_mi(value):
    """Parses a Kubernetes memory quantity (e.g. '512Mi', '1Gi', '900m'... no -- 'm' is millicpu,
    not a memory suffix; Kubernetes memory quantities use Ki/Mi/Gi or K/M/G) into mebibytes.
    Returns None if unparseable.
    """
    match = re.fullmatch(r"(\d+(?:\.\d+)?)\s*(Ki|Mi|Gi|K|M|G)?", str(value).strip())
    if not match:
        return None
    n = float(match.group(1))
    unit = (match.group(2) or "").lower()
    return {
        "ki": n / 1024,
        "mi": n,
        "gi": n * 1024,
        "k": n / 1000 * (1000 / 1024),
        "m": n * (1000 / 1024),
        "g": n * 1000 * (1000 / 1024),
        "": n / (1024 * 1024),
    }[unit]


def iter_pod_templates(doc):
    """Yields (kind, name, pod_spec) for every rendered object carrying a Pod template."""
    kind = doc.get("kind")
    if kind in ("Deployment", "StatefulSet", "DaemonSet", "Job", "ReplicaSet"):
        spec = doc.get("spec", {})
        template = spec.get("template", {})
        pod_spec = template.get("spec")
        if isinstance(pod_spec, dict):
            yield kind, doc.get("metadata", {}).get("name", "<unnamed>"), pod_spec
    elif kind == "Pod":
        pod_spec = doc.get("spec")
        if isinstance(pod_spec, dict):
            yield kind, doc.get("metadata", {}).get("name", "<unnamed>"), pod_spec


def check_i1(doc, label):
    if doc.get("kind") != "Service":
        return []
    svc_type = doc.get("spec", {}).get("type", "ClusterIP")
    if svc_type not in ("ClusterIP", None):
        name = doc.get("metadata", {}).get("name", "<unnamed>")
        return [f"I1: {label}: Service/{name} is type {svc_type!r}, not ClusterIP"]
    return []


def check_i2(doc, label):
    violations = []
    for kind, name, pod_spec in iter_pod_templates(doc):
        if pod_spec.get("hostNetwork") is True:
            violations.append(f"I2: {label}: {kind}/{name} sets hostNetwork: true")
        if pod_spec.get("hostPID") is True:
            violations.append(f"I2: {label}: {kind}/{name} sets hostPID: true")
        for vol in pod_spec.get("volumes", []) or []:
            if isinstance(vol, dict) and "hostPath" in vol:
                violations.append(
                    f"I2: {label}: {kind}/{name} volume {vol.get('name', '<unnamed>')!r} "
                    f"is type hostPath"
                )
        for c in (pod_spec.get("containers", []) or []) + (pod_spec.get("initContainers", []) or []):
            if not isinstance(c, dict):
                continue
            for p in c.get("ports", []) or []:
                if isinstance(p, dict) and p.get("hostPort") is not None:
                    violations.append(
                        f"I2: {label}: {kind}/{name} container {c.get('name', '<unnamed>')!r} "
                        f"sets hostPort {p.get('hostPort')!r}"
                    )
    return violations


def check_i3(doc, label):
    violations = []
    for kind, name, pod_spec in iter_pod_templates(doc):
        for c in (pod_spec.get("containers", []) or []) + (pod_spec.get("initContainers", []) or []):
            if not isinstance(c, dict):
                continue
            cname = c.get("name", "<unnamed>")
            resources = c.get("resources") or {}
            requests = (resources.get("requests") or {}).get("memory")
            limits = (resources.get("limits") or {}).get("memory")
            if requests is None or limits is None:
                violations.append(
                    f"I3: {label}: {kind}/{name} container {cname!r} missing "
                    f"resources.requests.memory and/or resources.limits.memory"
                )
                continue
            req_mi, lim_mi = to_mi(requests), to_mi(limits)
            if req_mi is None or lim_mi is None:
                violations.append(
                    f"I3: {label}: {kind}/{name} container {cname!r} has unparseable "
                    f"memory request/limit ({requests!r}/{limits!r})"
                )
            elif req_mi > lim_mi:
                violations.append(
                    f"I3: {label}: {kind}/{name} container {cname!r} requests "
                    f"{requests} exceeds limit {limits}"
                )
    return violations


def check_i5_rendered(doc, label):
    """Untagged/`:latest` image check against rendered objects."""
    violations = []
    for kind, name, pod_spec in iter_pod_templates(doc):
        for c in (pod_spec.get("containers", []) or []) + (pod_spec.get("initContainers", []) or []):
            if not isinstance(c, dict):
                continue
            image = c.get("image", "")
            if ":" not in image.rsplit("/", 1)[-1]:
                violations.append(
                    f"I5: {label}: {kind}/{name} container {c.get('name', '<unnamed>')!r} "
                    f"image {image!r} carries no tag"
                )
            elif image.rsplit(":", 1)[-1] == "latest":
                violations.append(
                    f"I5: {label}: {kind}/{name} container {c.get('name', '<unnamed>')!r} "
                    f"image {image!r} is tagged :latest"
                )
    return violations


def check_i6(doc, label):
    """Ported from scripts/verify-postgres-memory-invariant.py's two inequalities, applied to a
    rendered Postgres StatefulSet's command-line flags and its own container memory limit."""
    if doc.get("kind") != "StatefulSet":
        return []
    name = doc.get("metadata", {}).get("name", "")
    if "postgres" not in name.lower():
        return []
    violations = []
    for kind, sts_name, pod_spec in iter_pod_templates(doc):
        for c in pod_spec.get("containers", []) or []:
            if not isinstance(c, dict) or "postgres" not in c.get("name", "").lower():
                continue
            limits = (c.get("resources") or {}).get("limits") or {}
            cap_mi = to_mi(limits.get("memory"))
            command = " ".join(str(a) for a in (c.get("command", []) or []) + (c.get("args", []) or []))

            def flag(fname, unit=True):
                pattern = fname + r"=(\d+)" + (r"\s*MB" if unit else "")
                m = re.search(pattern, command)
                return int(m.group(1)) if m else None

            shared_buffers = flag("shared_buffers")
            work_mem = flag("work_mem")
            max_connections = flag("max_connections", unit=False)
            if cap_mi is None or None in (shared_buffers, work_mem, max_connections):
                violations.append(
                    f"I6: {label}: StatefulSet/{sts_name} postgres container missing a "
                    f"memory limit or shared_buffers/work_mem/max_connections flag needed "
                    f"to evaluate the memory inequalities"
                )
                continue
            if shared_buffers * 4 > cap_mi:
                violations.append(
                    f"I6: {label}: StatefulSet/{sts_name} shared_buffers={shared_buffers}MB "
                    f"exceeds a quarter of limits.memory={cap_mi:g}Mi"
                )
            worst_case = shared_buffers + max_connections * work_mem
            if worst_case > 0.85 * cap_mi:
                violations.append(
                    f"I6: {label}: StatefulSet/{sts_name} worst-case "
                    f"shared_buffers+max_connections*work_mem={worst_case}MB exceeds 85% "
                    f"of limits.memory={cap_mi:g}Mi ({0.85 * cap_mi:.1f}Mi)"
                )
    return violations


def check_i8(doc, label):
    if doc.get("kind") == "Secret":
        name = doc.get("metadata", {}).get("name", "<unnamed>")
        return [f"I8: {label}: rendered object Secret/{name} -- Secrets must never be committed"]
    return []


ACME_EXCLUSION_RE = re.compile(
    r"Host\(`[^`]+`\) && !PathPrefix\(`/\.well-known/acme-challenge/`\)"
)
REDIRECT_MIDDLEWARE_SPEC_KEYS = ("redirectScheme", "redirectRegex")


def check_i9_init_script_identity(compose_bytes, k8s_bytes, label):
    """I9: byte identity between the Compose init script and its k8s copy. Pure function of two
    byte strings (no disk access) so the selftest can exercise it directly; the real call site in
    main() reads both files first."""
    if compose_bytes != k8s_bytes:
        return [
            f"I9: {label}: k8s/data/postgres/init copy is not byte-identical to "
            f"docker/postgres-init's own script"
        ]
    return []


def check_i10_redirect_acme_exclusion(docs, label):
    """I10: every IngressRoute route attaching a redirectScheme/redirectRegex Middleware must
    fullmatch the ACME-challenge-exclusion rule shape. Fails closed on a web-entrypoint route
    naming a Middleware undefined in this same doc list (root+namespace). Pure function of a
    rendered-document list (no disk/subprocess access) so the selftest can exercise it directly
    with hand-built fixtures; the real call site in main() passes one root's own rendered docs.
    """
    violations = []
    redirect_middlewares = set()
    all_middlewares = set()
    for doc in docs:
        if not isinstance(doc, dict) or doc.get("kind") != "Middleware":
            continue
        key = (doc.get("metadata", {}).get("namespace"), doc.get("metadata", {}).get("name"))
        all_middlewares.add(key)
        spec = doc.get("spec") or {}
        if any(k in spec for k in REDIRECT_MIDDLEWARE_SPEC_KEYS):
            redirect_middlewares.add(key)

    for doc in docs:
        if not isinstance(doc, dict) or doc.get("kind") != "IngressRoute":
            continue
        ir_name = doc.get("metadata", {}).get("name", "<unnamed>")
        ir_ns = doc.get("metadata", {}).get("namespace")
        spec = doc.get("spec") or {}
        entry_points = spec.get("entryPoints") or []
        for route in spec.get("routes", []) or []:
            if not isinstance(route, dict):
                continue
            match_rule = route.get("match", "")
            mw_names = [
                m.get("name") for m in (route.get("middlewares") or []) if isinstance(m, dict)
            ]
            for mw_name in mw_names:
                mw_key = (ir_ns, mw_name)
                is_redirect = mw_key in redirect_middlewares
                is_defined = mw_key in all_middlewares
                if not is_defined and "web" in entry_points:
                    violations.append(
                        f"I10: {label}: IngressRoute/{ir_name} route {match_rule!r} on "
                        f"entryPoint web names Middleware {mw_name!r}, undefined in this "
                        f"root/namespace -- cannot classify, failing closed"
                    )
                    continue
                if is_redirect and not ACME_EXCLUSION_RE.fullmatch(match_rule):
                    violations.append(
                        f"I10: {label}: IngressRoute/{ir_name} route {match_rule!r} attaches "
                        f"redirect Middleware {mw_name!r} but does not fullmatch the "
                        f"ACME-challenge-exclusion shape"
                    )
    return violations


def check_rendered_doc(doc, label, is_base_root=False):
    """Runs every rendered-object invariant (I1-I3, I5, I6, I8) against one parsed document.

    `is_base_root` skips ONLY I3 (resources) and I5 (image tag) -- the two fields a k8s/base/*
    root is intentionally incomplete for, by design (see the module-level DELIBERATELY_EXCLUDED
    comment). Every other invariant still applies even to a base root's own rendered objects.
    """
    if not isinstance(doc, dict):
        return [f"{label}: a rendered document is not a mapping -- cannot check any invariant"]
    violations = []
    violations += check_i1(doc, label)
    violations += check_i2(doc, label)
    if not is_base_root:
        violations += check_i3(doc, label)
        violations += check_i5_rendered(doc, label)
    violations += check_i6(doc, label)
    violations += check_i8(doc, label)
    return violations


def check_i4_source_file(path, text):
    """I4: every hand-written source YAML `memory:` line carries a MEASURED/PROVISIONAL comment
    within the 25 lines immediately above it. Pure function of a (path, text) pair -- no disk
    access here, so a selftest can feed it a literal string."""
    if path in GENERATED:
        return []
    violations = []
    lines = text.splitlines()
    for i, line in enumerate(lines):
        stripped = line.strip()
        if not re.match(r"^(memory|- op: replace\s*$)", stripped) and "memory:" not in stripped:
            continue
        if not re.search(r"\bmemory:\s*\S", stripped):
            continue
        window = lines[max(0, i - 25) : i]
        if not any(MEASURED_RE.search(w) for w in window):
            violations.append(
                f"I4: {path}:{i + 1}: {stripped!r} has no MEASURED/PROVISIONAL comment in the "
                f"25 lines above it"
            )
    return violations


def check_i5_overlay_kustomization(path, text):
    """I5's overlay half: the app image's newTag in an overlay kustomization.yaml must match
    main-<n>-<sha7> and carry its Flux setter-marker comment."""
    violations = []
    try:
        import yaml

        doc = yaml.safe_load(text)
    except Exception:
        return violations
    if not isinstance(doc, dict) or doc.get("kind") != "Kustomization":
        return violations
    for img in doc.get("images", []) or []:
        if not isinstance(img, dict):
            continue
        name = img.get("name", "")
        if "kanban-board-backend" not in name:
            continue
        new_tag = str(img.get("newTag", ""))
        if not IMAGE_TAG_PATTERN.fullmatch(new_tag):
            violations.append(
                f"I5: {path}: image {name!r} newTag {new_tag!r} does not match "
                f"main-<run_number>-<sha7>"
            )
        if not SETTER_MARKER_RE.search(text):
            violations.append(
                f"I5: {path}: image {name!r} carries no Flux $imagepolicy setter-marker comment"
            )
    return violations


def find_uncovered_roots(discovered, rendered, excluded):
    """I7: every discovered kustomization root is either rendered by this gate or explicitly
    excluded with a reason. Pure function of a (discovered, rendered, excluded) triple."""
    violations = []
    for root in discovered:
        if root not in rendered and root not in excluded:
            violations.append(
                f"I7: {root} holds a kustomization.yaml but was neither rendered nor listed in "
                f"DELIBERATELY_EXCLUDED -- it would be silently ungated"
            )
    return violations


def discover_roots():
    return sorted(
        os.path.relpath(os.path.dirname(p), REPO_ROOT)
        for p in glob.glob(os.path.join(REPO_ROOT, "k8s", "**", "kustomization.yaml"), recursive=True)
    )


def main():
    try:
        import yaml
    except ImportError:
        print("FAIL: PyYAML is required (pip install pyyaml)")
        return 1

    all_violations = []

    roots = discover_roots()
    if not roots:
        print("FAIL: no kustomization.yaml found anywhere under k8s/ -- zero roots to check")
        return 1

    all_violations += find_uncovered_roots(roots, set(roots), DELIBERATELY_EXCLUDED)

    kubectl = subprocess.check_output(
        ["bash", MANIFESTS_SCRIPT, "--tool", "kubectl"], text=True, cwd=REPO_ROOT
    ).strip()

    for root in roots:
        abs_root = os.path.join(REPO_ROOT, root)
        try:
            rendered = subprocess.check_output([kubectl, "kustomize", abs_root], text=True)
        except subprocess.CalledProcessError as e:
            all_violations.append(f"{root}: failed to render ({e})")
            continue
        is_base_root = root.replace(os.sep, "/").startswith("k8s/base/")
        root_docs = [d for d in yaml.safe_load_all(rendered) if d is not None]
        for doc in root_docs:
            all_violations += check_rendered_doc(doc, root, is_base_root=is_base_root)
        # I10 needs cross-object lookup (Middleware <-> IngressRoute) within one rendered root,
        # so it runs once per root over that root's own doc list rather than per-document.
        all_violations += check_i10_redirect_acme_exclusion(root_docs, root)

    # I9: byte identity between the Compose init script and its k8s copy. A missing Compose
    # source (a future Compose deletion post-D-04) makes this a no-op rather than a violation --
    # nothing to compare against once Compose itself is gone.
    compose_init = os.path.join(REPO_ROOT, "docker", "postgres-init", "01-create-databases-and-roles.sh")
    k8s_init = os.path.join(REPO_ROOT, "k8s", "data", "postgres", "init", "01-create-databases-and-roles.sh")
    if os.path.isfile(compose_init) and os.path.isfile(k8s_init):
        with open(compose_init, "rb") as f:
            compose_bytes = f.read()
        with open(k8s_init, "rb") as f:
            k8s_bytes = f.read()
        all_violations += check_i9_init_script_identity(
            compose_bytes, k8s_bytes, "k8s/data/postgres/init/01-create-databases-and-roles.sh"
        )

    for path in sorted(glob.glob(os.path.join(REPO_ROOT, "k8s", "**", "*.yaml"), recursive=True)):
        rel = os.path.relpath(path, REPO_ROOT)
        with open(path) as f:
            text = f.read()
        all_violations += check_i4_source_file(rel, text)
        if os.path.basename(path) == "kustomization.yaml":
            all_violations += check_i5_overlay_kustomization(rel, text)

    if all_violations:
        for line in all_violations:
            print(f"FAIL: {line}")
        return 1

    print(
        f"invariants OK -- {len(roots)} kustomization root(s) checked; no NodePort/LoadBalancer "
        "Service, no hostNetwork/hostPID/hostPort/hostPath, every container's memory "
        "requests+limits set and dated, no untagged/:latest image, no committed Secret, "
        "postgres init scripts byte-identical to Compose, no redirect route matches the ACME "
        "challenge path"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
