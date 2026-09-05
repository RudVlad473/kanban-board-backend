---
quick_id: 260905-qxi
type: quick
autonomous: false
requirements: [F-01, D-01, D-02, D-03, D-04, D-05, D-06, D-07, D-08]
files_modified:
  - scripts/verify-compose-ports.py
  - scripts/verify-compose-ports-selftest.py
  - .github/workflows/invariant-checks.yml
  - docker-compose.prod.yml
  - docker-compose.nonprod.yml
  - docs/INFRA_RUNBOOK.md
  - .planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md

estimate:
  tokens: 60000
  raw_tokens: 40000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "Adding a `ports:` key to any service other than `caddy` in `docker-compose.prod.yml`, or to ANY service in `docker-compose.nonprod.yml`, fails CI on the pull request that introduces it — proven by running the gate against a deliberately added entry and observing a non-zero exit whose message names the offending file and service, then reverting and observing exit 0. A gate only ever run against the passing state is not verified."
    - "Setting `network_mode: host` on any service in either deployed compose file fails the same gate — that construct publishes every listening port on the host and never touches a `ports:` key, so a gate that reads only `ports:` would wave it through."
    - "`caddy` publishing a port other than 80 and 443 fails the gate. The allowlist is per-service, so without this the one service permitted to publish could publish anything."
    - "Every one of the gate's invariants is proven to fire in CI on every run, not just on the day it was written: a committed self-test feeds each invariant an in-memory violating document and asserts the violation is reported."
    - "`docs/INFRA_RUNBOOK.md` no longer claims the OS iptables layer and the Netcup Cloud Firewall enforce the identical policy. It records, dated, what was actually observed on the VM on 2026-09-05 and what that means for anything Docker publishes."
    - "The runtime half of the finding — an empty `DOCKER-USER` chain on the VM — is filed as a tracked item rather than silently carried in prose."
  artifacts:
    - "scripts/verify-compose-ports.py — the gate; a pure `find_violations` function plus a `main()` that applies it to the two deployed compose files"
    - "scripts/verify-compose-ports-selftest.py — asserts every invariant fires against an in-memory violating document, so a future edit cannot silently make one unfireable"
    - ".github/workflows/invariant-checks.yml — a second, independently named job running the gate on push and pull_request"
    - "docs/INFRA_RUNBOOK.md — a corrected, dated Firewall section"
    - ".planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md — the runtime follow-up, tracked not done"
  key_links:
    - "Docker's `-A PREROUTING -m addrtype --dst-type LOCAL -j DOCKER` DNAT rule sends published-port traffic through FORWARD, never INPUT. That single fact is why `-P INPUT DROP` plus its 80/443 ACCEPTs governs only host daemons (sshd on :22) and is decorative for every container port — and therefore why a `ports:` key added to a compose file is a public exposure with no second layer behind it in this repository. The gate exists because of this rule, and the docstring must say so; a reader who does not know it will read the gate as excessive."
    - "PyYAML's `safe_load` resolves YAML merge keys (`<<: *anchor`) — confirmed against PyYAML 6.0.3 on 2026-09-05, `services.app` receives `ports` from a merged `x-` anchor. This is what makes key-presence checking sufficient rather than naive: a `ports:` smuggled in through an anchor is already flattened into the service dict by the time the gate sees it. Re-confirm this rather than trusting the note, because the whole detection strategy rests on it."
    - "`docker-compose.nonprod.yml` has no `caddy` service — the nonprod stack shares production's Caddy container over the external `kanban-edge` network. Its allowlist is therefore EMPTY, not `{caddy}`. A single global allowlist would be a latent hole: it would permit a service named `caddy` in the nonprod file to publish freely."
    - "The workflow's existing `paths-ignore` (`docs/**`, `**/*.md`, `.planning/**`) is inherited by any job added to it. Neither compose file matches any of those patterns, so the new job triggers on every change that could break it — but this must be checked rather than assumed, because a gate that never runs is worse than no gate: it reports green."
---

<objective>
Turn three prose comments in `docker-compose.prod.yml` — each stating that a service deliberately
publishes nothing to the host — into an invariant CI enforces on every pull request, and correct the
runbook claim that made those comments look redundant.

Purpose: adding a `ports:` entry to any service in either deployed compose file silently exposes
that port to the internet. Verified on the production VM on 2026-09-05, the OS-level iptables layer
does not catch it (Docker DNATs published ports through `FORWARD`, bypassing the `INPUT` chain that
carries the DROP policy) and `DOCKER-USER` — the one chain Docker guarantees it will not touch — is
empty. The only layer that would catch it today is the Netcup Cloud Firewall, which is not in this
repository, not reviewed in pull requests, and not under version control.

Output: a committed, re-runnable gate; a committed self-test proving each of its invariants can
fire; a CI job that makes the violation unmergeable; and a runbook that says what is true.
</objective>

<finding>
## F-01 — the two firewall layers are not equivalent (verified live on the VM, 2026-09-05)

`docs/INFRA_RUNBOOK.md` § "Firewall — two independent layers" opens with "Both layers enforce the
identical policy". Observed on the VM:

| Observation | Command | Consequence |
|-------------|---------|-------------|
| `-A PREROUTING -m addrtype --dst-type LOCAL -j DOCKER` present; the `DOCKER` chain DNATs 80/443 to the Caddy container | `iptables -t nat -S PREROUTING` | DNAT'd traffic traverses `FORWARD`, **not** `INPUT` |
| `-P INPUT DROP` plus `--dport 80/443 ACCEPT` | `iptables -S INPUT` | Governs host-level daemons only (sshd on :22). Decorative for anything Docker publishes |
| `DOCKER-USER` empty | `iptables -S DOCKER-USER` | The only hook that governs container traffic carries no rules |

So a `ports:` key on any service is an internet exposure gated by exactly one layer, and that layer
lives outside this repository. **Current state is safe** — only `caddy` carries `ports:` in
`docker-compose.prod.yml`, and `docker-compose.nonprod.yml` carries none at all.

**Out of scope, tracked not fixed:** adding `DOCKER-USER` rules on the VM. Task 3 files it. Nothing
in this plan touches the VM.
</finding>

<design_alternatives>
## Alternate approaches considered

### (A) Which compose files the gate covers

Three compose files exist in the repo root. Covering the wrong set is either a hole or a
permanently-red gate.

| Approach | Pros / Cons | Why picked / rejected |
|----------|-------------|-----------------------|
| **`docker-compose.prod.yml` + `docker-compose.nonprod.yml`; `docker-compose.yml` explicitly excluded (D-01)** | + The mechanism is a property of the VM, and both files are deployed to that same public VM — covering one and not the other would gate the file that is already correct while leaving the other open. + The nonprod file's allowlist is genuinely EMPTY (no `caddy` service there; it shares production's over `kanban-edge`), so this is real coverage rather than a formality. − Two files to keep in the gate's own list; adding a third deployed compose file later is a silent omission unless someone remembers. | **PICKED.** The `− ` is mitigated by naming the exclusion and its reason in the docstring rather than leaving the reader to infer that a missing file was considered. |
| `docker-compose.prod.yml` only | + Matches the file the finding was made against; one path, one allowlist. − Leaves the nonprod stack — same VM, same DNAT, same absent second layer — ungated, and it is the file most likely to grow a debug port. | Rejected. |
| All three, including `docker-compose.yml` | + No judgement call about which file is "deployed". − Local dev MUST publish 5433/9092/8081; that is the documented host workflow in `.claude/CLAUDE.md`'s Local Development Server section. Including it means either a permanently red gate or an allowlist so wide it asserts nothing. − That file never runs on a public host, so there is no exposure to gate. | Rejected on both counts. |

### (B) Detection strategy: presence of the `ports:` key, or parsing its entries

| Approach | Pros / Cons | Why picked / rejected |
|----------|-------------|-----------------------|
| **Key presence for non-allowlisted services; an exact literal-set assertion for the one allowlisted service (D-03)** | + The short (`"80:80"`) and long (`target:`/`published:`) mapping syntaxes are covered identically and for free — there is nothing to parse, so there is no syntax to miss. + `ports: []` and a `127.0.0.1:`-bound publish both fail. Failing closed is right here: the gate's job is to make a publish a reviewed decision, and a loopback bind on a box whose only other access path is SSH buys nothing `docker exec` does not already give. + Merge-key smuggling (`<<: *anchor`) is already flattened by `safe_load` before the check sees it. − Strict: a legitimate reformat of `caddy`'s ports block fails the gate until this script is edited in the same PR. | **PICKED.** The strictness is the same trade `scripts/verify-caddy-image-tag.py` already makes and this repo already argued for in writing: the extra edit is the point, and the failure is loud, at merge time, in the PR that caused it. |
| Parse each entry and permit `127.0.0.1:`-bound publishes | + Fewer false refusals for a genuinely local-only bind. − Requires handling `HOST:CONTAINER`, `IP:HOST:CONTAINER`, port ranges, `/udp`, and the long syntax — five shapes, each a chance to accept something by failing to recognise it. − A parser that returns "allowed" on a shape it did not understand is exactly the silent gate this task exists to replace. | Rejected. The escape hatch is editing the allowlist in the PR, where a reviewer sees it. |
| `grep -c 'ports:'` over the file | + Two lines. − Counts the three prose comments that contain the token, so the gate would be self-invalidating from day one — the comments it was written to enforce would break it. | Rejected. |

### (C) Where the check runs in CI

| Approach | Pros / Cons | Why picked / rejected |
|----------|-------------|-----------------------|
| **A second, separately named job in `.github/workflows/invariant-checks.yml` (D-05)** | + An independently named required check: a red "Only Caddy publishes host ports" sends the reviewer to the right file. + Inherits the workflow's trigger block and its already-argued `paths-ignore`, and inherits the property that earns a blocking per-PR run there — this check is a pure function of the commit, reaching no network and no registry, so an unchanged tree cannot newly fail days later. − Duplicate checkout + `pip install pyyaml`, roughly 20 seconds. | **PICKED.** |
| A third step inside the existing `caddy-image-tag` job | + Saves the 20 seconds. − A check named "Caddy image tag matches its Dockerfile" going red because someone published a Postgres port sends the reviewer to the wrong file, and that misdirection costs more than 20 seconds the first time it happens. | Rejected. |
| A new workflow file | + Total independence. − Duplicates the trigger block and the carefully-argued header comment for no benefit; `invariant-checks.yml` was created for exactly this class of gate and says so in its own header. | Rejected. |

### (D) Proving the gate bites: a one-off run, a committed self-test, or both

| Approach | Pros / Cons | Why picked / rejected |
|----------|-------------|-----------------------|
| **Both (D-08): a committed self-test feeding each invariant an in-memory violating document, PLUS a one-off in-tree red/green run against a real added `ports:` entry** | + They prove different things. The self-test proves the LOGIC fires and keeps proving it — a future edit that makes an invariant unfireable turns CI red instead of turning the gate into a comment with a shebang. The one-off proves the WIRING: the script pointed at the real file paths, reading the real files, actually reports. + The self-test needs no file mutation, so nothing in CI edits the working tree. − Two artifacts instead of one. | **PICKED.** |
| One-off run only, matching `scripts/verify-caddy-image-tag.py`'s precedent | + One artifact; matches the sibling exactly. − This repo's own stated principle is that an uncommitted check is a silent gap, not a real gate. A gate whose bidirectionality was demonstrated once, in a session nobody can re-run, is that gap one level up. | Rejected on the repo's own argument. |
| Self-test only | + Cleanest; no working-tree mutation at any point. − Cannot catch a wrong hardcoded path or a typo'd service key: every assertion runs on dicts the test itself built, so the gate could pass its self-test while reading a file that does not exist. | Rejected. |

## Non-obvious trade-offs

**Security.** The gate closes a review-visibility gap, not an exposure. It cannot stop `docker run
-p` on the VM, a hand-edit of the deployed file, or a container started outside Compose — the
committed file is not the running host. That residue is the tracked `DOCKER-USER` follow-up, and the
docstring must name it rather than let the gate read as complete coverage.

**Escape hatch by design.** A service can still publish: add it to the allowlist in the same PR. The
gate converts a silent change into a reviewed one. Stating this is not a weakness disclosure — a
reader who thinks the gate is absolute will misread its purpose and eventually route around it.

**Performance.** Two `yaml.safe_load` calls; unmeasurable. The 20-second duplicate-job cost is
argued in (C).

**Commit-time cost, so it is not mistaken for a broken hook.** `.githooks/pre-commit` runs gitleaks,
then `spotlessCheck`, then the fast test suite on EVERY commit regardless of what changed — roughly
four minutes per commit, three commits here. That is expected. Do not reach for `--no-verify`.
</design_alternatives>

<decisions>
- **D-01** — The gate covers `docker-compose.prod.yml` and `docker-compose.nonprod.yml`.
  `docker-compose.yml` (local dev) is excluded, and the exclusion is stated in the docstring with
  its reason, so a future reader sees a decision rather than an oversight.
- **D-02** — The allowlist is PER FILE: prod maps to `{caddy}`, nonprod maps to the empty set. Not
  one global allowlist.
- **D-03** — Non-allowlisted services are checked by PRESENCE of the `ports:` key. No entry parsing,
  no bind-address exemption, no empty-list exemption.
- **D-04** — `network_mode: host` is a first-class invariant, not a documented hole. It publishes
  every listening port and never touches a `ports:` key, so the consequence is identical and the
  check costs three lines. It applies to `caddy` too — the allowlist does not exempt it.
- **D-05** — A second, separately named job in the existing `invariant-checks.yml`.
- **D-06** — `expose:` is OUT of scope and the docstring says why: it publishes nothing to the host,
  so it is not this gate's subject. Deliberate, not an omission.
- **D-07** — The runbook's "identical policy" claim is CORRECTED with dated evidence, not softened.
  The runtime `DOCKER-USER` work is filed as a tracked item; no VM change is made.
- **D-08** — Both proofs: a committed self-test (logic, forever) and a one-off in-tree red/green run
  (wiring, once).
</decisions>

<context>
@.claude/CLAUDE.md
@scripts/verify-caddy-image-tag.py
@scripts/verify-postgres-memory-invariant.py
@.github/workflows/invariant-checks.yml
@docker-compose.prod.yml
@docker-compose.nonprod.yml
@docs/CODE_STYLE.md
</context>

<comment_conventions>
Every comment written here follows `~/.claude/CODE_COMMENTS.md` and `docs/CODE_STYLE.md`:

- Never restate what the code does. State WHY, or state a fact the reader cannot get from the code.
- Cite only what a reader can open: a file path, a command, a version, a date. **No planning-system
  tokens in any committed comment** — no `D-01`, no `F-01`, no `260905-qxi`. Those live in this plan
  and in the SUMMARY. (Pre-existing citations of that shape in `invariant-checks.yml`'s header are
  left alone; churning them is not this task.)
- Long comments are permitted only as decision records: state the observation, date it, and say what
  would make it false. The gate's docstring is one, and its KNOWN HOLES list is the "what is
  deliberately NOT checked" category — silence there would read as coverage.
</comment_conventions>

<tasks>

<task type="tracer">
  <name>Task 1: The gate, and proof it fires — both directions, both proofs</name>
  <files>scripts/verify-compose-ports.py, scripts/verify-compose-ports-selftest.py</files>
  <precondition>`git status --porcelain docker-compose.prod.yml docker-compose.nonprod.yml` prints nothing. The red/green proof below mutates a tracked compose file in place and reverts with `git checkout --`; run against a dirty file, that revert destroys uncommitted work. Halt if either file is modified.</precondition>
  <action>
**Part A — `scripts/verify-compose-ports.py`.** New committed, re-runnable gate in the shape of
`scripts/verify-caddy-image-tag.py`: module docstring stating scope and enumerating known holes,
numbered invariants, `import yaml` inside `main()` with the same `ImportError` fail-closed message,
`FAIL: ` prefixed violation lines to stdout, exit 1 on any violation, a single human-readable
`invariants OK: ` line on success, `main()` returning an int, and `sys.exit(main())` at the bottom.

Structure it as a pure function plus a thin driver, because Part B has to call the logic without
touching a file:

- A module-level constant mapping each covered compose path to its allowed-publisher set — prod to a
  one-element set naming `caddy`, nonprod to an empty set. Declare it here rather than deriving it
  from the files being checked; a value read out of the file it guards cannot disagree with it.
- `find_violations(compose, allowed, label)` — takes an already-parsed document, the allowed set, and
  a label used in messages; returns a list of violation strings. No file access, no printing, no
  exit. Everything below is decided inside this function.
- `main()` — loads each covered path with `yaml.safe_load`, calls `find_violations`, prints and exits.

Invariants, each numbered in both the docstring and the emitted message so a red CI line says which
one broke and which file and service broke it:

- **I1** — no service outside that file's allowed set carries a `ports:` key. Presence only: do not
  inspect the entries. A `127.0.0.1:`-bound publish and an empty `ports: []` both violate, and both
  the short and long mapping syntaxes are covered by construction.
- **I2** — no service in either file sets `network_mode` to `host`. The allowed set does NOT exempt a
  service from this; it applies to `caddy` as well.
- **I3** — every name in a file's allowed set exists as a service in that file. Modest value: it
  catches an allowed name left dangling by a rename or removal, so the gate cannot quietly drift away
  from the file it guards.
- **I4** — the allowed service's own published set is exactly the two host ports 80 and 443, asserted
  as an exact literal set against the short-syntax strings. Deliberately strict: the allowed set is
  per-service, so without this the one service permitted to publish could publish anything. Any other
  form — an added port, an IP prefix, a range, a `/udp` suffix, the long syntax — fails and demands a
  deliberate edit here.

Fail loudly, never silently, on a malformed document: a missing or non-mapping `services` key, or a
service whose value is not a mapping, is a violation with its own message rather than a skipped
iteration. A gate that treats "I could not read this" as "nothing to report" is the failure mode
this whole task exists to remove.

Docstring content — a decision record, dated, at a higher abstraction level than the code:

- WHY this exists, in terms a reader can verify: `iptables -t nat -S PREROUTING` on the VM carries
  the `addrtype --dst-type LOCAL -j DOCKER` DNAT jump, so published-port traffic traverses `FORWARD`
  and never `INPUT`. The `-P INPUT DROP` policy therefore governs host daemons only and is
  decorative for anything Docker publishes; `DOCKER-USER`, the one chain Docker will not touch, was
  observed empty on 2026-09-05. Publishing a port is a public exposure whose only remaining layer
  lives outside this repository. State it as an observation with a date and the command that
  reproduces it, so it is falsifiable rather than an appeal to caution.
- SCOPE, stated precisely: which two files are covered, that local dev's compose file is
  deliberately excluded because it must publish for the documented host workflow and never runs on a
  public host, and that the nonprod file's allowed set is empty because that stack has no Caddy
  service of its own — it shares production's over the external edge network.
- KNOWN HOLES, enumerated now rather than left to be rediscovered:
  * This reads the COMMITTED file, not the running host. A `-p` flag on a hand-run `docker run`, an
    edit made directly on the VM, or a container started outside Compose is invisible here. Nothing
    in this repository can close that; the runtime chain rules are tracked separately.
  * `expose:` is not checked, because it publishes nothing to the host — container-to-container
    only. Not an omission.
  * The allowed set is editable in the same pull request. This gate makes a publish REVIEWED, not
    impossible, and that is the design.
  * `include:` and `extends:` are not resolved by `safe_load`, so a service defined in another file
    and pulled in would be unseen. Neither covered file uses either construct as of 2026-09-05; if
    one starts to, this gate quietly narrows.
  * Nothing here asserts that the edge is still reachable in the other direction beyond I4's exact
    set — this gate forbids publishes, it does not prove the intended ones serve traffic.
- What is NOT a hole, because the opposite is the natural assumption: a `ports:` key smuggled in
  through a YAML merge key is caught, since `safe_load` flattens `<<:` before the check sees the
  service. Record the confirmed version and date next to that claim (PyYAML 6.0.3, 2026-09-05) —
  re-confirm it as part of this task rather than copying the claim over.

**Part B — `scripts/verify-compose-ports-selftest.py`.** A committed self-test that imports
`find_violations` and feeds it in-memory documents. One case per invariant asserting the violation IS
reported and names the offending service, plus one clean document asserting no violation is reported.
No file access, no working-tree mutation. Same output conventions as the gate: `FAIL: ` lines, exit 1,
one summary line on success, `sys.exit(main())`.

Its docstring states the failure it exists to prevent: an edit to the gate that makes an invariant
unfireable is invisible against a compose file that satisfies every invariant — the gate goes green
and stays green, which is the exact shape of the problem this task was opened to fix, one level up.
  </action>
  <verify>
    <automated>python3 -c "import yaml; d=yaml.safe_load('x: &amp;a\n  ports: [\"9:9\"]\nservices:\n  app:\n    &lt;&lt;: *a\n'); assert 'ports' in d['services']['app']; print('merge-key flattening confirmed, PyYAML', yaml.__version__)" &amp;&amp; python3 scripts/verify-compose-ports-selftest.py &amp;&amp; python3 scripts/verify-compose-ports.py &amp;&amp; git diff --quiet docker-compose.prod.yml docker-compose.nonprod.yml</automated>
    <human-check>
Prove the gate fires against the REAL files, then prove it goes back to green. Run these as separate
steps and paste the actual output of each; a gate only ever run against the passing state is not
verified.

1. Confirm both compose files are unmodified (`git status --porcelain` on the two paths prints
   nothing). If not, STOP — step 3's revert would destroy uncommitted work.
2. Insert a `ports:` entry on a non-caddy service in `docker-compose.prod.yml` — the `app` service,
   publishing 8080. The gate MUST exit non-zero, and its message MUST name I1, the file, and `app`.
   Paste the exit code and the message. If it exits 0, stop: the gate does not work and no amount of
   CI wiring fixes that.
3. Revert with `git checkout -- docker-compose.prod.yml`, confirm `git diff --quiet` on it passes,
   and run the gate again. It MUST exit 0 and print its `invariants OK: ` line. Paste that line.

Report explicitly which direction was checked in the SUMMARY: the gate was observed FAILING against a
deliberately added entry and PASSING after the revert.
    </human-check>
  </verify>
  <done>
`scripts/verify-compose-ports.py` exits 0 against the real files with a summary line naming what it
checked. `scripts/verify-compose-ports-selftest.py` exits 0, and every invariant has an in-memory
case proving it reports. The gate was observed exiting non-zero, naming I1 and the `app` service,
against a real `ports:` entry added to `docker-compose.prod.yml`, and exiting 0 after the revert.
Both compose files are byte-identical to their committed state at the end of the task.
  </done>
</task>

<task type="auto">
  <name>Task 2: Make it unmergeable, and point the prose comments at the thing that enforces them</name>
  <files>.github/workflows/invariant-checks.yml, docker-compose.prod.yml, docker-compose.nonprod.yml</files>
  <action>
**Part A — `.github/workflows/invariant-checks.yml`.** Add a second job alongside `caddy-image-tag`,
following that job's existing shape exactly: checkout, the explicit PyYAML install step (installed
rather than assumed, for the reason that step's own comment already gives), then the gate. Give it a
job id of `compose-published-ports` and a `name:` that reads correctly as a red check on a pull
request — the check name is what tells a reviewer which file to open. Run the self-test in the same
job, ahead of the gate: if the gate's logic has been edited into something that cannot fire, that
must be the failure reported, not a green gate.

Do not touch the workflow's `on:` block. Its `paths-ignore` list is inherited by the new job and is
correct for it — neither compose file matches `docs/**`, `**/*.md`, or `.planning/**`, so any change
that could break this gate triggers it. Verify that rather than assuming it; a gate that never runs
reports green.

Extend the file's header comment where it already scopes itself, since that paragraph currently
describes one gate and the file will carry two. Follow that header's own documented habit of saying
why a difference exists. State what the new job covers and, in one line, the fact that makes it worth
a blocking per-PR run: on this host the OS firewall layer does not see container-published ports, so
the compose file is where the decision is actually made. Keep it short — the long form is the
script's docstring, and duplicating it here creates two things to keep in sync.

**Part B — the compose comments.** Three services in `docker-compose.prod.yml` (`postgres`, `app`,
`redpanda`) each carry a prose comment stating they deliberately publish nothing, and
`docker-compose.nonprod.yml` carries two of the same shape (`redpanda-nonprod`, `app-nonprod`).

Keep every one of them and keep what each already says about ITS OWN reason — the app comment's point
about bypassing TLS termination, and the two "admin access is SSH and `docker exec`" notes, are facts
about those services that the gate does not carry.

Add to each a single line recording that the absence is now mechanically enforced and naming the
script, in the same shape and for the same reason as the existing note on `caddy`'s `image:` line. A
comment that a script enforces is a pointer; a comment that nothing enforces is a hope, and the
difference is exactly what a future reader needs to know before deleting the line. Do not restate the
invariant in five places — one line, pointing at the enforceable home.

Change nothing else in either compose file: no service definition, no `ports:` block, no network,
volume, healthcheck, environment or `name:` pin.
  </action>
  <verify>
    <automated>python3 -c "import yaml,sys; [yaml.safe_load(open(p)) for p in ('docker-compose.prod.yml','docker-compose.nonprod.yml','.github/workflows/invariant-checks.yml')]; print('all three parse')" &amp;&amp; python3 scripts/verify-compose-ports-selftest.py &amp;&amp; python3 scripts/verify-compose-ports.py &amp;&amp; python3 -c "import yaml; w=yaml.safe_load(open('.github/workflows/invariant-checks.yml')); j=w['jobs']['compose-published-ports']; s=' '.join(str(x) for x in j['steps']); assert 'verify-compose-ports.py' in s and 'verify-compose-ports-selftest.py' in s and 'pyyaml' in s; t=w[True]; assert sorted(t['pull_request']['paths-ignore'])==sorted(['docs/**','**/*.md','.planning/**']); assert 'caddy-image-tag' in w['jobs']; print('job wired, trigger block unchanged, both gates run')" &amp;&amp; git diff -U0 docker-compose.prod.yml docker-compose.nonprod.yml | grep -E "^[+-]" | grep -vE "^(\+\+\+|---)" | grep -vE "^[+-][[:space:]]*#" | wc -l | grep -qx 0 &amp;&amp; echo "compose diff is comments only"</automated>
    <human-check>Paste the diff of the two compose files. Every changed line must be a comment line; if any non-comment line moved, the automated check above was satisfied by accident and the change must be reverted and redone.</human-check>
  </verify>
  <done>
`invariant-checks.yml` carries two independently named jobs; the new one installs PyYAML explicitly,
runs the self-test and then the gate, and inherits the unchanged trigger block whose `paths-ignore`
list provably cannot exclude either compose file. Both compose files parse, the gate still passes,
and their diff contains comment lines only. `./gradlew spotlessCheck` and `./gradlew test` are NOT
verification for this task — nothing Java changed, and Spotless targets `src/**/*.java` only. They
will still run as part of the pre-commit hook and must pass there unchanged; that is the hook working,
not a gate on this work, and it is never a reason to reach for `--no-verify`.
  </done>
</task>

<task type="auto">
  <name>Task 3: Correct the runbook claim the gate disproves, and file the runtime half</name>
  <files>docs/INFRA_RUNBOOK.md, .planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md</files>
  <action>
This task is the one item beyond the three scope bullets, and it is here for a specific reason:
shipping the gate while `docs/INFRA_RUNBOOK.md` still asserts the opposite means the next reader
trusts the runbook and reads the gate as excessive. The runbook is the artifact someone opens during
an incident, so a false claim there is the expensive kind.

**Part A — `docs/INFRA_RUNBOOK.md` § "Firewall — two independent layers".** That section opens with
"Both layers enforce the identical policy". Correct it; do not soften it. The two layers see
different traffic, and the difference is the whole point:

- State what was observed on the VM on 2026-09-05, with the three commands that reproduce it:
  `iptables -t nat -S PREROUTING` (the `addrtype --dst-type LOCAL -j DOCKER` DNAT jump),
  `iptables -S INPUT` (the DROP policy and its 80/443 ACCEPTs), and `iptables -S DOCKER-USER` (empty).
- State the consequence plainly: DNAT'd traffic traverses `FORWARD`, so Layer 1's policy governs
  host-level daemons — sshd on :22 — and does not govern any port Docker publishes. For those, the
  Netcup Cloud Firewall is currently the only enforcing layer.
- Say what this means operationally, since that is what a reader in an incident needs: publishing a
  port in a deployed compose file exposes it, and the compose file is now gated in CI. Name the
  script.
- Say what is still open — the empty `DOCKER-USER` chain — and point at the tracked item rather than
  describing a fix nobody has committed to.
- Leave the rest of the section alone: the Layer 1 rule listing, the persistence note, the ICMP note,
  and the Layer 2 gotcha from 2026-08-14 are all still accurate. Check whether the section HEADING
  and the later audit passage that references it need a wording touch for consistency; change them
  only if the correction makes them read as contradictory, and say which you changed.

**Part B — the tracked item.** File `.planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md`
in the format the existing pending todos use: frontmatter with `created`, `title`, `area` (security),
`severity`, and `files`; then a `## Problem` section carrying the observation, the date, and the
commands that reproduce it, and a `## Solution` section describing the `DOCKER-USER` rules and the
fact that they must be persisted the same way the existing rules are and re-verified across a reboot.
Record explicitly that this is the residue the CI gate cannot cover — the gate reads the committed
file, the chain governs the running host — so whoever picks it up knows why it was split rather than
folded in.

No VM change. Nothing in this task touches the server.
  </action>
  <verify>
    <automated>test -f .planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md &amp;&amp; python3 -c "import sys; t=open('.planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md').read(); assert t.startswith('---'); assert 'DOCKER-USER' in t and '## Problem' in t and '## Solution' in t; print('todo filed')" &amp;&amp; python3 -c "
import re,sys
t=open('docs/INFRA_RUNBOOK.md').read()
sec=t.split('## Firewall',1)[1].split('## Verified state',1)[0]
for token in ('DOCKER-USER','FORWARD','2026-09-05','verify-compose-ports.py'):
    assert token in sec, token
assert 'identical policy' not in sec
print('runbook firewall section corrected')" &amp;&amp; python3 scripts/verify-compose-ports-selftest.py &amp;&amp; python3 scripts/verify-compose-ports.py</automated>
    <human-check>Paste the rewritten Firewall section. It must read as a correction — what is true, dated, with the reproducing commands — not as a hedge on the old claim.</human-check>
  </verify>
  <done>
The runbook's Firewall section states what the two layers actually enforce, dated 2026-09-05, with
the three reproducing commands, and points at the CI gate for the committed-file half and at the
tracked item for the runtime half. The tracked item exists in `.planning/todos/pending/` in the
established format. No VM was touched.
  </done>
</task>

</tasks>

<threat_model>
## Trust boundaries

| Boundary | Description |
|----------|-------------|
| internet → VM host ports | Untrusted traffic reaches any port Docker publishes; DNAT'd through `FORWARD`, so the `INPUT` DROP policy does not see it |
| pull request → deployed compose file | A merged compose change becomes host configuration on the next deploy with no further human gate |

## STRIDE register

| Threat ID | Category | Component | Severity | Disposition | Mitigation |
|-----------|----------|-----------|----------|-------------|------------|
| T-qxi-01 | Information disclosure | A `ports:` key added to `postgres`, `app`, `redpanda` or any nonprod service | high | mitigate | I1 fails the pull request; the message names the file and the service |
| T-qxi-02 | Elevation of privilege | `network_mode: host` on any service — publishes every listening port without a `ports:` key | high | mitigate | I2, applied to every service including the allowed one |
| T-qxi-03 | Information disclosure | `caddy` publishing a port beyond 80/443 under cover of being the allowed service | medium | mitigate | I4's exact literal-set assertion |
| T-qxi-04 | Tampering | The gate edited into something that cannot fire; green against a compliant file forever | medium | mitigate | The committed self-test runs ahead of the gate in the same CI job |
| T-qxi-05 | Information disclosure | A port published on the running host outside the committed file — `docker run -p`, a VM-local edit, a non-Compose container | medium | transfer | Out of scope by constraint; filed as the `DOCKER-USER` tracked item and named in the gate's KNOWN HOLES |
| T-qxi-06 | Tampering | A third deployed compose file added later and never added to the gate's covered set | low | accept | The docstring names the covered set and the exclusion explicitly; a gate that discovers its own inputs would have to guess which files are deployed |
| T-qxi-SC | Tampering | Package installs | n/a | n/a | None. No new dependency: PyYAML is already installed by this workflow and already imported by `scripts/verify-caddy-image-tag.py` |
</threat_model>

<verification>
Applicable gates, all of which are pure functions of the commit:

- `python3 scripts/verify-compose-ports.py` exits 0 against both real files.
- `python3 scripts/verify-compose-ports-selftest.py` exits 0, with a case per invariant.
- The gate was observed exiting non-zero against a deliberately added `ports:` entry on `app` in
  `docker-compose.prod.yml`, naming I1 and the service, and exiting 0 after the revert. Both
  directions checked; both reported in the SUMMARY.
- Both compose files and the workflow file parse under `yaml.safe_load`.
- The compose diff contains comment lines only.
- PyYAML merge-key flattening re-confirmed, with the version recorded.

**Not applicable, stated so the absence does not read as an oversight:** `./gradlew spotlessCheck`
and `./gradlew test`. No Java changes here, and Spotless targets `src/**/*.java` only, so neither
would inspect a single file this task touches. They still run inside `.githooks/pre-commit` on every
commit — expect roughly four minutes per commit and expect them to pass unchanged. That is the hook
working. Never `--no-verify`.

**Not verifiable here, by constraint:** that CI actually goes red on a pull request carrying a
violation. That needs a push, and the merge gate is where a human sanctions it. The committed
self-test plus the observed in-tree failure are what stand in for it, and they cover the two things
that could be wrong — the logic and the wiring.
</verification>

<success_criteria>
- A `ports:` key on any non-allowed service in either deployed compose file, `network_mode: host`
  anywhere in them, or a change to `caddy`'s published set, each fails a named CI check on the pull
  request that introduces it.
- Every invariant is proven able to fire, by a check that re-runs in CI rather than by a session
  nobody can replay.
- The gate's docstring states what it does NOT catch, so no reader mistakes it for coverage of the
  running host.
- `docs/INFRA_RUNBOOK.md` no longer carries a claim the VM disproves, and the runtime residue is
  tracked rather than implied.
- The working tree is clean; no compose service definition changed.
</success_criteria>

<merge_gate>
Work stays on `quick/260905-qxi-compose-ports-invariant`. **Do not merge, and do not open the merge
yourself.** After the three tasks are committed, stop and hand over: a three-way review (Claude,
Gemini, Codex — one at a time, one worktree each, per `~/.claude/TOOLING_PREFERENCES.md` § Review)
runs first, and the human sanctions the merge.

Point the reviewers at the two questions worth their attention, because both are easy to answer
wrongly from a green run: whether any invariant can be satisfied by something the gate should have
caught, and whether the KNOWN HOLES list is honest or flattering.
</merge_gate>

<output>
Write `.planning/quick/260905-qxi-add-ci-invariant-that-only-caddy-may-pub/260905-qxi-SUMMARY.md`
when done. It must record: the exact FAIL output observed in Task 1's red direction, the
`invariants OK: ` line from the green direction, the confirmed PyYAML version behind the merge-key
claim, and which runbook passages were changed beyond the opening sentence.
</output>
</content>
</invoke>
