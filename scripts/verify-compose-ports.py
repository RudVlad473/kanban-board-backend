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

SCOPE: docker-compose.prod.yml and docker-compose.nonprod.yml -- the two files actually deployed to
that public VM. docker-compose.yml (local dev) is deliberately excluded: it must publish
5433/9092/8081 for the documented host workflow (.claude/CLAUDE.md, "Local Development Server") and
never runs on a public host, so there is no exposure here to gate. The nonprod file's allowed set is
the EMPTY set, not `{caddy}` -- that stack has no Caddy service of its own; it shares production's
over the external `kanban-edge` network.

KNOWN HOLES, enumerated now rather than left to be rediscovered:
  * This reads the COMMITTED file, not the running host. A `-p` flag on a hand-run `docker run`, an
    edit made directly on the VM, or a container started outside Compose is invisible here. Nothing
    in this repository can close that; the runtime `DOCKER-USER` chain rules are tracked separately
    (.planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md).
  * `expose:` is not checked, because it publishes nothing to the host -- container-to-container
    only. Not an omission.
  * The allowed set (ALLOWED_PUBLISHERS below) is editable in the same pull request that adds a
    `ports:` entry. This gate makes a publish REVIEWED, not impossible, and that is the design.
  * `include:` and `extends:` are not resolved by `yaml.safe_load`, so a service defined in another
    file and pulled in would be unseen. Neither covered file uses either construct as of 2026-09-05;
    if one starts to, this gate quietly narrows.
  * I4's exact-set assertion on `caddy` proves nothing about the other direction -- it forbids
    publishes beyond 80/443, it does not prove 80/443 actually serve traffic.

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
I4: the allowed service's own published set is EXACTLY the two literal strings "80:80" and "443:443"
    -- deliberately strict. The allowed set is per-service, so without this the one service permitted
    to publish could publish anything: an added port, an IP prefix, a port range, a `/udp` suffix, or
    the long mapping syntax all fail and demand a deliberate edit here.

A missing/non-mapping `services` key, or a service whose value is not a mapping, is its own
violation rather than a silently skipped iteration -- treating "I could not read this" as "nothing
to report" is the exact failure mode this gate exists to remove.
"""

import sys

# Per-file allowed-publisher sets, declared here rather than derived from the files being checked --
# a value read out of the file it guards cannot disagree with it. The allowed set is PER FILE
# (each covered path maps to its own set), never one global set shared across both files.
ALLOWED_PUBLISHERS = {
    "docker-compose.prod.yml": {"caddy"},
    "docker-compose.nonprod.yml": set(),
}

# I4's exact literal set for the one service any covered file may allow to publish.
CADDY_ALLOWED_PORTS = {"80:80", "443:443"}


def find_violations(compose, allowed, label):
    """Pure check: an already-parsed compose document against its allowed-publisher set.

    No file access, no printing, no exit -- everything the gate decides happens here, so a
    self-test can feed it in-memory documents without touching disk.
    """
    violations = []

    if not isinstance(compose, dict) or not isinstance(compose.get("services"), dict):
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

        if service.get("network_mode") == "host":
            violations.append(
                f"I2 violated in {label}: service `{name}` sets `network_mode: host`, which "
                f"publishes every listening port on the host and bypasses `ports:` entirely"
            )

        has_ports = "ports" in service
        if name not in allowed:
            if has_ports:
                violations.append(
                    f"I1 violated in {label}: service `{name}` carries a `ports:` key but is not "
                    f"in this file's allowed set {sorted(allowed) or '{}'}"
                )
        elif name == "caddy":
            published = service.get("ports")
            # Exact SET equality against the two literal short-syntax strings -- not sequence
            # equality (order must not matter) and not subset/superset (a missing port or an
            # extra one, a duplicate entry, the long mapping syntax, or any other shape all fail).
            is_exact_set = (
                isinstance(published, list)
                and all(isinstance(p, str) for p in published)
                and len(published) == len(CADDY_ALLOWED_PORTS)
                and set(published) == CADDY_ALLOWED_PORTS
            )
            if not is_exact_set:
                violations.append(
                    f"I4 violated in {label}: `caddy`'s published set is {published!r}, not "
                    f"exactly {sorted(CADDY_ALLOWED_PORTS)}"
                )

    return violations


def main():
    try:
        import yaml
    except ImportError:
        print("FAIL: PyYAML is required (pip install pyyaml)")
        return 1

    all_violations = []
    for path, allowed in ALLOWED_PUBLISHERS.items():
        with open(path) as f:
            compose = yaml.safe_load(f)
        all_violations.extend(find_violations(compose, allowed, path))

    if all_violations:
        for line in all_violations:
            print(f"FAIL: {line}")
        return 1

    checked = ", ".join(ALLOWED_PUBLISHERS)
    print(f"invariants OK: only caddy publishes 80/443 in docker-compose.prod.yml; "
          f"no service in docker-compose.nonprod.yml publishes anything; "
          f"no network_mode: host anywhere ({checked})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
