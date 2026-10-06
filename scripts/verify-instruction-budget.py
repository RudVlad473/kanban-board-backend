#!/usr/bin/env python3
"""Cap the bytes of every always-loaded agent instruction file and everything it imports.

These files load into every agent session, so every byte is paid in every session. The gate sums
the raw bytes of the tracked root files plus their @-imports (up to 5 hops) and fails when the
total exceeds CEILING_BYTES, when an import leaves the repository, or when no root is found.
Exit codes: 0 pass, 1 fail, 2 usage or git error. Stdlib only, so the pre-commit hook needs no pip step.

Decisions:
  * Bytes, not lines or tokens: one real line in the old file held 1,976 bytes, and bytes track
    tokens (about 3.5 to 4 bytes per token) far better than lines do.
  * Raw bytes, including HTML comments that Claude Code strips before injection. That overcounts
    a little, which is the conservative side and also right for runtimes that do not strip.
  * CEILING_BYTES is a reviewed constant. Nothing raises it automatically, and the advisory only
    suggests lowering it. Raising it is a deliberate one-number edit with a dated line below.
  * --ceiling may only tighten the limit, so a command-line override cannot hide growth.
  * Roots come from the tracked tree and contents from the working tree, the same as the
    comment lint, so an untracked personal file never counts against anyone else.
  * An import that leaves the repository (a home-relative, absolute or escaping path) is a
    violation: its size depends on the machine, so no ceiling could bound it.
  * An empty scan fails, because an empty scan and a clean scan look the same.
  * Ceiling history, newest last:
      2026-10-06  37,300  the 36,844-byte file measured that day plus a 400-byte margin, rounded
                          up to the next 100; it holds the pre-trim size until the trim lands.

Known holes:
  * Always-loaded context that is not an instruction file is not counted: SessionStart hook
    output, skill descriptions and MCP server instructions.
  * Nested per-directory CLAUDE.md files load on demand and are not counted.
  * An untracked personal CLAUDE.local.md is not counted.
  * Import detection approximates Claude Code's parser: an @ token that resolves to an existing
    file counts, so a prose mention of an existing relative path would count too.
  * A symlink that points outside the repository is read as absent, not reported.
  * CI's path filter skips growth of an imported file under docs/; pre-commit still catches it.
"""

import argparse
import collections
import math
import os
import posixpath
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CEILING_BYTES = 37300
MARGIN_BYTES = 400
RATCHET_ADVISORY_BYTES = 1000
MAX_HOPS = 5
ROOT_FILES = ("CLAUDE.md", ".claude/CLAUDE.md", "CLAUDE.local.md", "AGENTS.md", ".claude/AGENTS.md", "GEMINI.md")
RULES_PREFIX = ".claude/rules/"

TRAILING_PUNCTUATION = ".,;:!?)]}'\""

Row = collections.namedtuple("Row", "path size hop via")
Manifest = collections.namedtuple("Manifest", "rows violations notes")

_FENCE = re.compile(r"^ {0,3}(`{3,}|~{3,})")
_CODE_SPAN = re.compile(r"`[^`\n]*`")
_IMPORT = re.compile(r"(?:^|(?<=[^\w`@]))@(\S+)", re.MULTILINE)
_PATH_LIKE = re.compile(r"/|\.\w{1,5}$")


def import_tokens(text):
    """Return the text after every @ that Claude Code would try to import, outside code."""
    kept = []
    fence = None
    for line in text.splitlines():
        match = _FENCE.match(line)
        if fence is None:
            if match:
                fence = match.group(1)
                continue
            kept.append(_CODE_SPAN.sub(" ", line))
        elif match and match.group(1)[0] == fence[0] and len(match.group(1)) >= len(fence):
            if not line.strip().strip(fence[0]):
                fence = None
    return [m.group(1) for m in _IMPORT.finditer("\n".join(kept))]


def _has_paths_frontmatter(text):
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return False
    for line in lines[1:]:
        if line.strip() == "---":
            return False
        if re.match(r"^paths\s*:", line):
            return True
    return False


def _candidates(token):
    stripped = token.rstrip(TRAILING_PUNCTUATION)
    return [token] if stripped == token or not stripped else [token, stripped]


def build_manifest(tracked, read):
    """Resolve the roots and their imports into rows, violations and notes."""
    tracked = set(tracked)
    rows = collections.OrderedDict()
    violations = []
    notes = []
    queue = []

    def add(path, hop, via):
        data = read(path)
        if data is None or path in rows:
            return
        rows[path] = Row(path, len(data), hop, via)
        queue.append(path)

    for path in sorted(tracked):
        if path in ROOT_FILES:
            add(path, 0, "root")
        elif path.startswith(RULES_PREFIX) and path.endswith(".md"):
            data = read(path)
            if data is None:
                continue
            if _has_paths_frontmatter(data.decode("utf-8", "replace")):
                notes.append("%s is conditional (paths frontmatter), not counted" % path)
            else:
                add(path, 0, "root")

    while queue:
        importer = queue.pop(0)
        hop = rows[importer].hop
        text = read(importer).decode("utf-8", "replace")
        for token in import_tokens(text):
            resolved = _resolve(importer, token, read, violations, notes)
            if resolved is None:
                continue
            if hop + 1 <= MAX_HOPS:
                add(resolved, hop + 1, importer)

    return Manifest(list(rows.values()), violations, notes)


def _resolve(importer, token, read, violations, notes):
    """Return the repo-relative path the token names, or None. Records violations and notes."""
    seen_missing = None
    for candidate in _candidates(token):
        if candidate.startswith("~/") or candidate.startswith("/"):
            violations.append("%s imports @%s, which is outside the repository" % (importer, candidate))
            return None
        target = posixpath.normpath(posixpath.join(posixpath.dirname(importer), candidate))
        if target == ".." or target.startswith("../"):
            violations.append("%s imports @%s, which resolves outside the repository" % (importer, candidate))
            return None
        if read(target) is not None:
            return target
        seen_missing = seen_missing or (candidate, target)
    if seen_missing and _PATH_LIKE.search(seen_missing[0]):
        notes.append("%s imports @%s, which names no existing file (looked for %s)" % ((importer,) + seen_missing))
    return None



def suggested_ceiling(total):
    return int(math.ceil((total + MARGIN_BYTES) / 100.0)) * 100


def ratchet_advisory(total, ceiling):
    """Return the advisory text when the ceiling sits well above the total, else None."""
    if ceiling - total <= RATCHET_ADVISORY_BYTES:
        return None
    return (
        "ADVISORY: the total is %d bytes, %d under the ceiling. Lower CEILING_BYTES to %d "
        "(total + %d margin, rounded up to 100) with a dated line in this file's Decisions block, "
        "so the headroom cannot be spent unnoticed." % (total, ceiling - total, suggested_ceiling(total), MARGIN_BYTES)
    )


def evaluate(manifest, ceiling):
    """Return (ok, lines): the verdict lines for the manifest against the ceiling."""
    total = sum(row.size for row in manifest.rows)
    lines = []
    ok = True
    if not manifest.rows:
        ok = False
        lines.append("NO INSTRUCTION FILE FOUND: an empty scan cannot be told from a clean one, so this fails.")
    for violation in manifest.violations:
        ok = False
        lines.append("VIOLATION: %s" % violation)
    if total > ceiling:
        ok = False
        lines.append(
            "OVER BUDGET: %d bytes against a ceiling of %d (%d over)." % (total, ceiling, total - ceiling)
        )
        lines.append(
            "These files load into every agent session, so every byte is paid in every session. "
            "Keep only what an agent cannot find by looking: move reference into docs/ behind a "
            "one-line pointer, or say what comes out. Raising CEILING_BYTES is a deliberate "
            "one-number edit with a dated reason in scripts/verify-instruction-budget.py's Decisions block."
        )
    if ok:
        lines.append("OK: %d bytes within a ceiling of %d." % (total, ceiling))
    return ok, lines


def tracked_paths():
    result = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True)
    if result.returncode != 0:
        raise RuntimeError("git ls-files failed: %s" % result.stderr.decode(errors="replace").strip())
    return [p for p in result.stdout.decode().split("\0") if p]


def read_working_tree(path):
    full = os.path.join(ROOT, path)
    if not os.path.isfile(full):
        return None
    real = os.path.realpath(full)
    if real != ROOT and not real.startswith(os.path.realpath(ROOT) + os.sep):
        return None
    with open(full, "rb") as handle:
        return handle.read()


def main(argv=None):
    parser = argparse.ArgumentParser(description="Cap always-loaded agent instruction files.")
    parser.add_argument("--ceiling", type=int, default=None, help="tighten the limit for this run (never loosen)")
    try:
        args = parser.parse_args(argv)
    except SystemExit as exit_request:
        return 2 if exit_request.code else 0
    ceiling = CEILING_BYTES
    if args.ceiling is not None:
        if args.ceiling > CEILING_BYTES:
            print("usage error: --ceiling %d is above CEILING_BYTES %d; an override can only tighten." % (
                args.ceiling, CEILING_BYTES), file=sys.stderr)
            return 2
        ceiling = args.ceiling
    try:
        tracked = tracked_paths()
    except (RuntimeError, OSError) as err:
        print("error: %s" % err, file=sys.stderr)
        return 2
    manifest = build_manifest(tracked, read_working_tree)
    total = sum(row.size for row in manifest.rows)
    for row in manifest.rows:
        print("%7d  %s  hop %d" % (row.size, row.path, row.hop))
    for note in manifest.notes:
        print("note: %s" % note)
    print("TOTAL %d" % total)
    print("CEILING %d" % ceiling)
    print("about %d tokens (bytes/4, rough)" % (total // 4))
    ok, lines = evaluate(manifest, ceiling)
    for line in lines:
        print(line)
    if ok and args.ceiling is None:
        advisory = ratchet_advisory(total, ceiling)
        if advisory:
            print(advisory)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
