#!/usr/bin/env python3
"""Check that docs/diagrams/ keeps its view-folder layout, resolvable references and legible renders.

Subcommands: check [--diagram VIEW/SUBJECT ...] (stdlib-only, exit 1 on any violation) and report
(a markdown legibility table). With --diagram, only that diagram's own rules and the references to
it run; inline fences in the learning guide are then skipped.
Rules: outside-view-folder, missing-twin, manifest-mismatch, uniform-scale, bad-name, bad-png,
dangling-reference, legibility-width, legibility-height, embed-width, flowchart-rules,
class-level-label. Exit codes: 0 clean, 1 violations, 2 empty scan or bad usage.
Policy home: docs/DIAGRAM_CONVENTIONS.md.

Decisions:
  * Legibility is measured, not guessed. GitHub's markdown column was measured with headless
    Chromium on 2026-10-05: 838 px is the narrowest column at a viewport of 1280 px or wider
    (README view; blob view is 861-1012 px). Mermaid 11.17 draws every label at 16 px, so a
    diagram of natural width W shows its text at 16 * min(1, 838 / W) CSS px.
  * The 12 px text floor (three quarters of the 16 px body text) and the 1600 px displayed height
    (about two 800 px screens) are CHOSEN bounds, not standards. W_MAX follows from the floor.
  * Natural width is the PNG width divided by the manifest scale. The scale is pinned to 2 for
    every row, so a PNG is a 2x raster shown at an explicit CSS width through <img width=W>.
  * The gate enumerates files with `git ls-files`, never by walking the tree: a walk would open
    .env and .env.prod, and gitignored files are never indexed. Untracked drafts are out of scope
    for the same reason.
  * Docs scanned are tracked *.md minus the GSD, vendored-skill and wiki trees, the same set
    verify-comments.py excludes. docs/wiki and docs/raw hold migrated copies that already carry
    unresolved links; the originals are the authority.
  * Not wired into CI: invariant-checks ignores docs/**, and removing that ignore would pay a
    full deploy pipeline per documentation commit. Run it by hand, like render-diagrams.sh.
  * A scan that finds zero diagrams or zero docs fails with exit 2: an empty scan and a clean
    scan look the same.

Known holes:
  * A file that is not yet `git add`ed is invisible; stage before checking.
  * The gate trusts that a PNG came from render-diagrams.sh; `render-diagrams.sh --check --all`
    is what proves it, so run both.
  * class-level-label is a token heuristic (a CamelCase name ending in a role suffix), so judgment
    about what a lifeline should be called stays in review.
  * The target viewport is 1280 px or wider; narrower viewports shrink the text further.
"""

import argparse
import math
import os
import posixpath
import re
import struct
import subprocess
import sys
from collections import namedtuple

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DIAGRAMS_DIR = "docs/diagrams"
ALLOWED_TOP_LEVEL = ("render-manifest.tsv", "mermaid-config.json")
MANIFEST_PATH = DIAGRAMS_DIR + "/render-manifest.tsv"
VIEWS = ("logical", "process", "development", "physical", "scenarios")

# Measured 2026-10-05, see Decisions.
DISPLAY_COLUMN = 838
BODY_FONT = 16
TEXT_FLOOR = 12
W_MAX = math.floor(BODY_FONT * DISPLAY_COLUMN / TEXT_FLOOR)
H_MAX = 1600
SCALE = 2
EMBED_TOLERANCE = 1

# Byte-for-byte line 1 of the physical deployment diagram.
INIT_LINE = (
    '%%{init: {"flowchart": {"subGraphTitleMargin": {"top": 15, "bottom": 15}, "curve": "linear"}}}%%'
)

# GSD and vendored-skill trees, plus the wiki copies; same set verify-comments.py excludes.
EXCLUDED_PREFIXES = (".planning/", ".claude/", ".agents/", "docs/raw/", "docs/wiki/", ".dev/")

KEBAB_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
OLD_AREA_PREFIXES = ("architecture-", "auth-", "infra-")
TYPE_SUFFIXES = ("-scenario", "-flowchart", "-sequence", "-diagram")

CLASS_ROLES = (
    "Controller|Service|Repository|Mapper|Publisher|Consumer|Recorder|Provider|Handler|Resolver"
    "|Verifier|Filter"
)
CLASS_LABEL_RE = re.compile(r"\b[A-Z][a-z]+(?:[A-Z][a-z0-9]+)*(?:%s)\b" % CLASS_ROLES)

PATH_TOKEN_RE = re.compile(r"[\w./~*\-]*diagrams/[\w./*\-]*\.(?:mmd|png|svg)")
LINK_TARGET_RE = re.compile(r"\]\(\s*<?([^)\s>]+)")
SRC_ATTR_RE = re.compile(r"""\bsrc\s*=\s*["']([^"']+)["']""")
WIDTH_ATTR_RE = re.compile(r"""\bwidth\s*=\s*["']?(\d+)""")
IMG_TAG_RE = re.compile(r"<img\b[^>]*>", re.I | re.S)
MD_IMAGE_RE = re.compile(r"!\[[^\]]*\]\(\s*<?([^)\s>]+)")
CODE_SPAN_RE = re.compile(r"`([^`\n]+)`")
FENCE_OPEN_RE = re.compile(r"^\s*```+\s*(\w*)\s*$")
SCHEME_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.\-]*:")

Violation = namedtuple("Violation", "rule path line message")


class EmptyScan(Exception):
    """Zero diagrams or zero docs were in scope."""


class UsageError(Exception):
    """An argument names something that does not exist."""


class Tree:
    """Tracked files as path -> bytes (PNGs carry only their header)."""

    def __init__(self, files):
        self.files = files

    def text(self, path):
        return self.files[path].decode("utf-8", "replace")


def is_doc(path):
    return path.endswith(".md") and not path.startswith(EXCLUDED_PREFIXES)


def load_tree(root=ROOT):
    out = subprocess.run(
        ["git", "-C", root, "ls-files", "-z"], capture_output=True, check=True
    ).stdout.decode("utf-8")
    files = {}
    for path in filter(None, out.split("\0")):
        if not (path.startswith(DIAGRAMS_DIR + "/") or is_doc(path)):
            continue
        try:
            with open(os.path.join(root, path), "rb") as fh:
                files[path] = fh.read(64) if path.endswith(".png") else fh.read()
        except OSError:
            continue
    return Tree(files)


def read_png_size(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR" or len(data) < 24:
        return None
    return struct.unpack(">II", data[16:24])


def parse_manifest(tree):
    """Return ({key: scale}, [(line, message)]) for a manifest, tolerating its absence."""
    rows, problems = {}, []
    if MANIFEST_PATH not in tree.files:
        return rows, [(0, "render-manifest.tsv is not tracked")]
    for number, raw in enumerate(tree.text(MANIFEST_PATH).splitlines(), 1):
        if not raw.strip() or raw.startswith("#"):
            continue
        parts = raw.split("\t")
        if len(parts) != 2 or not parts[1].strip().isdigit() or int(parts[1]) < 1:
            problems.append((number, "malformed row %r, expected <key><TAB><integer scale>" % raw))
            continue
        rows[parts[0]] = int(parts[1])
    return rows, problems


def index_diagrams(tree):
    """Map key -> {ext: path} for every non-allowed file under docs/diagrams/."""
    prefix = DIAGRAMS_DIR + "/"
    found = {}
    for path in sorted(tree.files):
        if not path.startswith(prefix):
            continue
        rel = path[len(prefix):]
        if rel in ALLOWED_TOP_LEVEL:
            continue
        stem, ext = posixpath.splitext(rel)
        found.setdefault(stem, {})[ext] = path
    return found


def in_view_folder(key, ext_map):
    parts = key.split("/")
    return len(parts) == 2 and parts[0] in VIEWS and set(ext_map) <= {".mmd", ".png"}


def natural_size(png_w, png_h, scale):
    half = scale // 2
    return (png_w + half) // scale, (png_h + half) // scale


def shrink_factor(natural_w):
    return min(1.0, DISPLAY_COLUMN / natural_w)


def strip_fences(text):
    """Blank out fenced code blocks, keeping line numbers, so prose scans skip example code."""
    out, inside = [], False
    for line in text.split("\n"):
        if FENCE_OPEN_RE.match(line):
            inside = not inside
            out.append("")
        else:
            out.append("" if inside else line)
    return "\n".join(out)


def is_fence_close(line):
    match = FENCE_OPEN_RE.match(line)
    return bool(match) and not match.group(1)


def mermaid_fences(text):
    """Yield (first_content_line_number, [lines]) for every mermaid fence."""
    lines = text.split("\n")
    i = 0
    while i < len(lines):
        match = FENCE_OPEN_RE.match(lines[i])
        if match and match.group(1) == "mermaid":
            start = i + 1
            j = start
            while j < len(lines) and not is_fence_close(lines[j]):
                j += 1
            yield start + 1, lines[start:j]
            i = j + 1
        else:
            i += 1


def keyword_line(lines):
    for number, line in enumerate(lines):
        stripped = line.strip()
        if stripped and not stripped.startswith("%%"):
            return number, stripped
    return None, ""


def lint_mermaid(lines, first_line, path, where):
    """Flowchart and class-level rules for one diagram body; returns violations."""
    found = []
    offset, keyword = keyword_line(lines)
    if re.match(r"^(flowchart|graph)\b", keyword):
        if not lines or lines[0].rstrip() != INIT_LINE:
            found.append(
                Violation("flowchart-rules", path, first_line, "%s must open with the shared init line" % where)
            )
        if keyword != "flowchart TB":
            found.append(
                Violation(
                    "flowchart-rules",
                    path,
                    first_line + offset,
                    "%s uses %r, expected 'flowchart TB'" % (where, keyword),
                )
            )
    for number, line in enumerate(lines):
        if line.strip().startswith("%%"):
            continue
        match = CLASS_LABEL_RE.search(line)
        if match:
            found.append(
                Violation(
                    "class-level-label",
                    path,
                    first_line + number,
                    "%s names a class (%s); name the runtime role instead" % (where, match.group(0)),
                )
            )
    return found


def resolve(token, doc, tree):
    clean = re.split(r"[#?]", token, maxsplit=1)[0]
    candidates = [posixpath.normpath(posixpath.join(posixpath.dirname(doc), clean))]
    if clean.startswith("docs/"):
        candidates.append(posixpath.normpath(clean))
    for cand in candidates:
        if cand in tree.files:
            return cand
    return None


def token_key(token):
    """The diagram key a reference names, judged textually (works for dangling references)."""
    clean = posixpath.normpath(re.split(r"[#?]", token, maxsplit=1)[0])
    if "diagrams/" not in clean:
        return None
    rest = clean.rsplit("diagrams/", 1)[1]
    return posixpath.splitext(rest)[0]


def reference_tokens(text):
    """Yield (line_number, token) for diagram paths in link targets, img src values and code spans."""
    for regex in (LINK_TARGET_RE, SRC_ATTR_RE, CODE_SPAN_RE):
        for match in regex.finditer(text):
            candidate = match.group(1)
            if SCHEME_RE.match(candidate):
                continue
            path_match = PATH_TOKEN_RE.search(candidate)
            if path_match and "*" not in path_match.group(0):
                yield text.count("\n", 0, match.start(1)) + 1, path_match.group(0)


def check_inventory(tree, diagrams, rows, only):
    found = []
    wanted = (lambda key: only is None or key in only)
    for key, ext_map in sorted(diagrams.items()):
        if not wanted(key):
            continue
        for ext, path in sorted(ext_map.items()):
            if not in_view_folder(key, ext_map):
                found.append(
                    Violation(
                        "outside-view-folder",
                        path,
                        0,
                        "diagram files live directly in one of %s as <subject>.mmd/.png" % "/".join(VIEWS),
                    )
                )
        for ext, other in ((".mmd", ".png"), (".png", ".mmd")):
            if ext in ext_map and other not in ext_map:
                found.append(
                    Violation("missing-twin", ext_map[ext], 0, "no %s twin beside it" % other)
                )
        if in_view_folder(key, ext_map):
            stem = key.split("/")[1]
            problems = []
            if not KEBAB_RE.match(stem):
                problems.append("not kebab-case")
            if stem.startswith(OLD_AREA_PREFIXES):
                problems.append("carries an old area prefix")
            if stem.endswith(TYPE_SUFFIXES):
                problems.append("names its diagram type")
            if problems:
                found.append(
                    Violation("bad-name", next(iter(ext_map.values())), 0, "'%s' is %s" % (stem, " and ".join(problems)))
                )
        if ".mmd" in ext_map and key not in rows:
            found.append(
                Violation("manifest-mismatch", ext_map[".mmd"], 0, "'%s' has no render-manifest.tsv row" % key)
            )
    for key, scale in sorted(rows.items()):
        if not wanted(key):
            continue
        if ".mmd" not in diagrams.get(key, {}):
            found.append(
                Violation("manifest-mismatch", MANIFEST_PATH, 0, "row '%s' has no .mmd source" % key)
            )
        if scale != SCALE:
            found.append(
                Violation("uniform-scale", MANIFEST_PATH, 0, "row '%s' has scale %d, expected %d" % (key, scale, SCALE))
            )
    return found


def check_renders(tree, diagrams, rows, only):
    found = []
    for key, ext_map in sorted(diagrams.items()):
        if only is not None and key not in only:
            continue
        if ".png" in ext_map:
            png_path = ext_map[".png"]
            size = read_png_size(tree.files[png_path])
            if size is None:
                found.append(Violation("bad-png", png_path, 0, "not a readable PNG (bad signature or IHDR)"))
            else:
                natural_w, natural_h = natural_size(size[0], size[1], rows.get(key, SCALE))
                shown_h = natural_h * shrink_factor(natural_w)
                if natural_w > W_MAX:
                    found.append(
                        Violation(
                            "legibility-width",
                            png_path,
                            0,
                            "natural width %d px exceeds %d (text would show at %.1f px)"
                            % (natural_w, W_MAX, BODY_FONT * shrink_factor(natural_w)),
                        )
                    )
                if shown_h > H_MAX:
                    found.append(
                        Violation(
                            "legibility-height",
                            png_path,
                            0,
                            "displayed height %.0f px exceeds %d" % (shown_h, H_MAX),
                        )
                    )
        if ".mmd" in ext_map:
            lines = tree.text(ext_map[".mmd"]).split("\n")
            found.extend(lint_mermaid(lines, 1, ext_map[".mmd"], "diagram"))
    return found


def check_docs(tree, diagrams, rows, only):
    found = []
    for doc in sorted(p for p in tree.files if is_doc(p)):
        text = tree.text(doc)
        prose = strip_fences(text)
        for line, token in reference_tokens(prose):
            key = token_key(token)
            if only is not None and key not in only:
                continue
            if resolve(token, doc, tree) is None:
                found.append(Violation("dangling-reference", doc, line, "'%s' does not resolve" % token))
        found.extend(check_embeds(tree, doc, prose, rows, only))
        if only is None:
            for first_line, lines in mermaid_fences(text):
                found.extend(lint_mermaid(lines, first_line, doc, "inline diagram"))
    return found


def check_embeds(tree, doc, prose, rows, only):
    found = []

    def target(token):
        path = resolve(token, doc, tree)
        if path is None or not path.startswith(DIAGRAMS_DIR + "/") or not path.endswith(".png"):
            return None
        key = posixpath.splitext(path[len(DIAGRAMS_DIR) + 1:])[0]
        if only is not None and key not in only:
            return None
        return path, key

    for match in MD_IMAGE_RE.finditer(prose):
        hit = target(match.group(1))
        if hit:
            found.append(
                Violation(
                    "embed-width",
                    doc,
                    prose.count("\n", 0, match.start()) + 1,
                    "markdown image syntax for %s; use <img src=... width=N>" % hit[0],
                )
            )
    for match in IMG_TAG_RE.finditer(prose):
        tag = match.group(0)
        src = SRC_ATTR_RE.search(tag)
        hit = target(src.group(1)) if src else None
        if not hit:
            continue
        line = prose.count("\n", 0, match.start()) + 1
        size = read_png_size(tree.files[hit[0]])
        if size is None:
            continue
        natural_w = natural_size(size[0], size[1], rows.get(hit[1], SCALE))[0]
        width = WIDTH_ATTR_RE.search(tag)
        if not width:
            found.append(Violation("embed-width", doc, line, "<img> of %s has no width (expected %d)" % (hit[0], natural_w)))
        elif abs(int(width.group(1)) - natural_w) > EMBED_TOLERANCE:
            found.append(
                Violation(
                    "embed-width",
                    doc,
                    line,
                    "<img> of %s has width=%s, natural width is %d" % (hit[0], width.group(1), natural_w),
                )
            )
    return found


def check(tree, only=None):
    diagrams = index_diagrams(tree)
    if not diagrams:
        raise EmptyScan("no diagram files are tracked under %s/" % DIAGRAMS_DIR)
    if not any(is_doc(p) for p in tree.files):
        raise EmptyScan("no documentation files were scanned")
    rows, problems = parse_manifest(tree)
    wanted = set(only) if only is not None else None
    if wanted is not None:
        unknown = sorted(k for k in wanted if k not in diagrams and k not in rows)
        if unknown:
            raise UsageError("unknown diagram(s): %s" % ", ".join(unknown))
    found = [Violation("manifest-mismatch", MANIFEST_PATH, n, msg) for n, msg in problems] if wanted is None else []
    found += check_inventory(tree, diagrams, rows, wanted)
    found += check_renders(tree, diagrams, rows, wanted)
    found += check_docs(tree, diagrams, rows, wanted)
    return sorted(found, key=lambda v: (v.path, v.line, v.rule, v.message))


def report(tree, only=None):
    diagrams = index_diagrams(tree)
    rows, _ = parse_manifest(tree)
    out = []
    for key, ext_map in sorted(diagrams.items()):
        if ".png" not in ext_map or (only is not None and key not in only):
            continue
        size = read_png_size(tree.files[ext_map[".png"]])
        if size is None:
            continue
        natural_w, natural_h = natural_size(size[0], size[1], rows.get(key, SCALE))
        factor = shrink_factor(natural_w)
        out.append(
            {
                "key": key,
                "png_w": size[0],
                "png_h": size[1],
                "natural_w": natural_w,
                "natural_h": natural_h,
                "text_px": BODY_FONT * factor,
                "shown_h": natural_h * factor,
                "ok": natural_w <= W_MAX and natural_h * factor <= H_MAX,
            }
        )
    return out


def format_report(rows):
    lines = [
        "| Diagram | PNG W x H | Natural W | Text px shown (D=%d) | Shown height | Within limits |"
        % DISPLAY_COLUMN,
        "|---|---|---|---|---|---|",
    ]
    for row in rows:
        lines.append(
            "| %s | %dx%d | %d | %.1f | %.0f | %s |"
            % (
                row["key"],
                row["png_w"],
                row["png_h"],
                row["natural_w"],
                row["text_px"],
                row["shown_h"],
                "yes" if row["ok"] else "NO (W<=%d, H<=%d)" % (W_MAX, H_MAX),
            )
        )
    return "\n".join(lines)


def main(argv=None, tree=None):
    parser = argparse.ArgumentParser(description="Diagram layout, reference and legibility gate")
    sub = parser.add_subparsers(dest="cmd", required=True)
    for name in ("check", "report"):
        cmd = sub.add_parser(name)
        cmd.add_argument("--diagram", action="append", metavar="VIEW/SUBJECT", default=None)
    try:
        args = parser.parse_args(argv)
    except SystemExit as exit_:
        return exit_.code if isinstance(exit_.code, int) and exit_.code else 2
    try:
        if tree is None:
            tree = load_tree()
        if args.cmd == "report":
            print(format_report(report(tree, set(args.diagram) if args.diagram else None)))
            return 0
        violations = check(tree, args.diagram)
    except (EmptyScan, UsageError) as err:
        print("FAIL scan %s" % err, file=sys.stderr)
        return 2
    for v in violations:
        location = "%s:%d" % (v.path, v.line) if v.line else v.path
        print("FAIL %s %s %s" % (v.rule, location, v.message))
    diagrams = len(index_diagrams(tree))
    docs = sum(1 for p in tree.files if is_doc(p))
    if violations:
        print("%d violation(s); %d diagram(s), %d doc(s) scanned" % (len(violations), diagrams, docs))
        return 1
    print("OK: %d diagram(s), %d doc(s) scanned" % (diagrams, docs))
    return 0


if __name__ == "__main__":
    sys.exit(main())
