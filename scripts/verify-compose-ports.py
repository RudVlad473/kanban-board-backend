#!/usr/bin/env python3
r"""Gate: only `caddy` may publish a host port, and only 80/443.
Same shape as scripts/verify-caddy-image-tag.py: a committed, re-runnable check, not a comment
restating an invariant that nothing enforces.

WHY this exists, verified live on the VM 2026-09-05: `iptables -t nat -S PREROUTING` carries the
`-A PREROUTING -m addrtype --dst-type LOCAL -j DOCKER` DNAT jump, so traffic to a published port
traverses `FORWARD`, never `INPUT`. `iptables -S INPUT`'s `-P INPUT DROP` plus its 80/443 ACCEPTs
therefore governs host-level daemons only (sshd on :22) and is decorative for anything Docker
publishes; `iptables -S DOCKER-USER` -- the one chain Docker guarantees it will not touch -- was
observed empty. A `ports:` entry is a public exposure whose only remaining layer, the Netcup Cloud
Firewall, lives outside this repository, is not reviewed in pull requests, and is not under version
control. This gate is what makes that exposure a reviewed decision instead of a silent one.

SCOPE: every `docker-compose*.yml` file at the repo root, split into two disjoint sets so a new
file cannot land ungated (I7): ALLOWED_PUBLISHERS (checked against every invariant below) and
DELIBERATELY_EXCLUDED (documented and skipped). docker-compose.yml (local dev) is the only current
member of the latter -- it must publish 5433/9092/8081 for the documented host workflow
(.claude/CLAUDE.md, "Local Development Server") and never runs on a public host, so there is no
exposure here to gate. The nonprod file's allowed set is the EMPTY set, not `{caddy}` -- that stack
has no Caddy service of its own; it shares production's over the external `kanban-edge` network.

KNOWN HOLES, enumerated now rather than left to be rediscovered:
  * This reads the COMMITTED file, not the running host. A `-p` flag on a hand-run `docker run`, an
    edit made directly on the VM, or a container started outside Compose is invisible here. This
    gate cannot close that; the runtime `DOCKER-USER` chain now carries its own version-controlled
    policy and drift check instead (infra/vm/docker-user-firewall.sh, quick task 260906-feq) --
    that script's `check` subcommand is what detects a live chain diverging from what is committed,
    which this gate structurally cannot see.
  * `expose:` is not checked, because it publishes nothing to the host -- container-to-container
    only. Not an omission.
  * The allowed set (ALLOWED_PUBLISHERS below) is editable in the same pull request that adds a
    `ports:` entry, and DELIBERATELY_EXCLUDED (I7) is editable the same way -- a new compose file
    could be dropped into the exclusion set with a false justification. This gate makes a publish
    REVIEWED, not impossible, and that is the design; a human reviewer still has to read the diff.
  * I5's unresolved-interpolation check fires on ANY `${...}` left in a `network_mode` value,
    including one that would resolve to a harmless literal like `bridge`. Deliberately conservative
    in the direction that costs a false positive (a one-line edit to hardcode the value) rather than
    a false negative (silently allowing host networking through). It does not need a matching check
    on `ports:` values, because I1 already fires on the KEY's presence regardless of what its
    entries resolve to.
  * I4's exact-set assertion on each allowed publisher proves nothing about the other direction --
    it forbids publishing beyond the expected set, it does not prove those ports actually serve
    traffic.

CLOSED, not merely narrowed: `include:` and `extends:` used to be unresolved by `yaml.safe_load`,
so a service defined in another file and pulled in would have been unseen. As of I6 below, a
top-level `include:` key or any service carrying an `extends:` key is itself a violation -- this
gate cannot resolve either construct, so it fails closed on them rather than silently passing.

NOT a hole, because the opposite is the natural assumption to make: a `ports:` key smuggled in
through a YAML merge key (`<<: *anchor`) is caught, because `yaml.safe_load` flattens `<<:` into the
service mapping before this script's checks ever see it -- confirmed against PyYAML 6.0.3 on
2026-09-05.

Invariants, numbered in both this docstring and the emitted FAIL lines so a red CI line names which
one broke, in which file, on which service:

I1: no service outside that file's allowed set carries a `ports:` key at all. Presence only -- the
    entries are never inspected. A `127.0.0.1:`-bound publish and an empty `ports: []` both violate,
    and the short (`"80:80"`) and long (`target:`/`published:`) mapping syntaxes are both covered by
    construction, since neither is parsed.
I2: no service in either file sets `network_mode: host`. Applies to the allowed service too --
    `network_mode: host` publishes every listening port on the host and never touches a `ports:`
    key, so the allowed set does not exempt anyone from this check.
I3: every name in a file's allowed set actually exists as a service in that file -- catches an
    allowed name left dangling by a rename or removal, so this gate cannot quietly drift away from
    the file it guards.
I4: every allowed publisher's own published set matches EXACTLY the set recorded for it in
    EXPECTED_PORTS -- bound to allowlist membership, not to a hardcoded service name, so any
    allowed publisher without an EXPECTED_PORTS entry is itself a violation rather than silently
    unconstrained. Deliberately strict on the comparison itself: an added port, a missing one, an
    IP prefix, a port range, a `/udp` suffix, or the long mapping syntax all fail and demand a
    deliberate edit here.
I5: no service in either file sets `network_mode` to a string still carrying an unresolved `${`
    environment interpolation. `yaml.safe_load` reads `${VAR}` as a literal string, so a gate that
    only compared against the literal `"host"` would certify a file that Docker could render to
    `network_mode: host` at deploy time -- the gate cannot know what an unresolved reference
    resolves to, and treating that as "nothing to report" is exactly the failure mode this whole
    gate exists to remove.
I6: no compose document carries a top-level `include:` key, and no service carries an `extends:`
    key. Both let a service's real definition live in a file this gate never opens.
I7: every `docker-compose*.yml` file at the repo root is accounted for -- either a key in
    ALLOWED_PUBLISHERS (and therefore checked against I1-I6) or a member of DELIBERATELY_EXCLUDED
    (and therefore, by name, deliberately not). A file in neither set would be silently ungated.

A missing/non-mapping `services` key, or a service whose value is not a mapping, is its own
violation rather than a silently skipped iteration -- treating "I could not read this" as "nothing
to report" is the exact failure mode this gate exists to remove.
"""

import glob
import sys

# Per-file allowed-publisher sets, declared here rather than derived from the files being checked --
# a value read out of the file it guards cannot disagree with it. The allowed set is PER FILE
# (each covered path maps to its own set), never one global set shared across both files.
ALLOWED_PUBLISHERS = {
    "docker-compose.prod.yml": {"caddy"},
    "docker-compose.nonprod.yml": set(),
}

# Every `docker-compose*.yml` file at the repo root NOT in ALLOWED_PUBLISHERS must be listed here,
# by name, with why (I7) -- the alternative is a new file landing ungated by simply not being
# mentioned anywhere.
DELIBERATELY_EXCLUDED = {
    # Local dev only; must publish 5433/9092/8081 for the documented host workflow
    # (.claude/CLAUDE.md, "Local Development Server") and never runs on a public host.
    "docker-compose.yml",
}

# I4's exact literal set required per allowed publisher, keyed by the file that allows it, then by
# service name -- allowlist membership alone leaves a service unconstrained without an entry here.
EXPECTED_PORTS = {
    "docker-compose.prod.yml": {"caddy": {"80:80", "443:443"}},
    "docker-compose.nonprod.yml": {},
}


def find_violations(compose, allowed, label, expected_ports):
    """Pure check: an already-parsed compose document against its allowed-publisher set.

    `expected_ports` maps each name in `allowed` to its own exact required `ports:` set (I4). No
    file access, no printing, no exit -- everything a single document can decide happens here, so a
    self-test can feed it in-memory documents without touching disk. I7 (file discovery across the
    repo root) is a different kind of check and lives in `find_uncovered_files` instead.
    """
    violations = []

    if not isinstance(compose, dict):
        violations.append(f"{label}: not a mapping -- cannot check any invariant")
        return violations

    if "include" in compose:
        violations.append(
            f"I6 violated in {label}: top-level `include:` key is present -- this gate cannot "
            f"resolve an included file's services, so a publish there would go unseen"
        )

    if not isinstance(compose.get("services"), dict):
        violations.append(f"{label}: no mapping `services:` key found -- cannot check any invariant")
        return violations

    services = compose["services"]

    for name in allowed:
        if name not in services:
            violations.append(
                f"I3 violated in {label}: allowed publisher `{name}` is not a service in this file"
            )

    for name, service in services.items():
        if not isinstance(service, dict):
            violations.append(f"{label}: service `{name}` is not a mapping -- cannot check its keys")
            continue

        network_mode = service.get("network_mode")
        if network_mode == "host":
            violations.append(
                f"I2 violated in {label}: service `{name}` sets `network_mode: host`, which "
                f"publishes every listening port on the host and bypasses `ports:` entirely"
            )
        elif isinstance(network_mode, str) and "${" in network_mode:
            violations.append(
                f"I5 violated in {label}: service `{name}` sets `network_mode: {network_mode!r}`, "
                f"an unresolved environment interpolation -- Docker could render this to `host` at "
                f"deploy time and this gate cannot know, so an unresolved value is itself a "
                f"violation"
            )

        if "extends" in service:
            violations.append(
                f"I6 violated in {label}: service `{name}` carries an `extends:` key -- this gate "
                f"cannot resolve the extended service's own keys, so a publish there would go "
                f"unseen"
            )

        has_ports = "ports" in service
        if name not in allowed:
            if has_ports:
                violations.append(
                    f"I1 violated in {label}: service `{name}` carries a `ports:` key but is not "
                    f"in this file's allowed set {sorted(allowed) or '{}'}"
                )
        else:
            expected = expected_ports.get(name)
            if expected is None:
                violations.append(
                    f"I4 violated in {label}: allowed publisher `{name}` has no entry in "
                    f"EXPECTED_PORTS -- allowlist membership alone leaves its published set "
                    f"unconstrained"
                )
                continue
            published = service.get("ports")
            # Exact SET equality against the recorded literal strings -- not sequence equality
            # (order must not matter) and not subset/superset (a missing port or an extra one, a
            # duplicate entry, the long mapping syntax, or any other shape all fail).
            is_exact_set = (
                isinstance(published, list)
                and all(isinstance(p, str) for p in published)
                and len(published) == len(expected)
                and set(published) == expected
            )
            if not is_exact_set:
                violations.append(
                    f"I4 violated in {label}: `{name}`'s published set is {published!r}, not "
                    f"exactly {sorted(expected)}"
                )

    return violations


def find_uncovered_files(discovered, covered, excluded):
    """Pure check (I7): every discovered filename is either covered or explicitly excluded.

    `discovered` is a list of bare filenames (no directory component), matching what
    `glob.glob("docker-compose*.yml")` returns when run from the repo root. Kept independent of
    disk access so a self-test can feed it a literal list.
    """
    violations = []
    for name in discovered:
        if name not in covered and name not in excluded:
            violations.append(
                f"I7 violated: `{name}` is a docker-compose*.yml file at the repo root but is in "
                f"neither ALLOWED_PUBLISHERS nor DELIBERATELY_EXCLUDED -- it would be silently "
                f"ungated"
            )
    return violations


def main():
    try:
        import yaml
    except ImportError:
        print("FAIL: PyYAML is required (pip install pyyaml)")
        return 1

    all_violations = []

    discovered = sorted(glob.glob("docker-compose*.yml"))
    all_violations.extend(
        find_uncovered_files(discovered, set(ALLOWED_PUBLISHERS), DELIBERATELY_EXCLUDED)
    )

    for path, allowed in ALLOWED_PUBLISHERS.items():
        with open(path) as f:
            compose = yaml.safe_load(f)
        all_violations.extend(
            find_violations(compose, allowed, path, EXPECTED_PORTS.get(path, {}))
        )

    if all_violations:
        for line in all_violations:
            print(f"FAIL: {line}")
        return 1

    # Rendered from ALLOWED_PUBLISHERS itself rather than hardcoded -- a hardcoded success string
    # can claim a guarantee the checks above no longer enforce the moment the allowlist changes.
    per_file = []
    for path, allowed in sorted(ALLOWED_PUBLISHERS.items()):
        if allowed:
            per_file.append(f"{path}: only {sorted(allowed)} may publish a host port")
        else:
            per_file.append(f"{path}: no service may publish a host port")
    print(
        "invariants OK -- "
        + "; ".join(per_file)
        + "; no network_mode: host or unresolved interpolation, no include/extends, "
        "every compose file at the repo root accounted for"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
