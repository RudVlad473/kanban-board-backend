#!/usr/bin/env python3
"""Independently re-derive migrate_docs.py's output contract and diff it against disk.

Deliberately does not import migrate_docs.py: the point of this script is a
second, separate derivation of the same contract, so a bug in one
implementation is unlikely to be mirrored in the other.

Usage: verify_migration.py [--only <src> [<src> ...]]
Exits non-zero on any failure. Always prints a final count line.
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TSV_PATH = Path(__file__).resolve().parent / "migration-map.tsv"

FENCE_OPEN_RE = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
FENCE_CLOSE_RE = re.compile(r"^ {0,3}(`{3,}|~{3,})[ \t]*$")
BACKTICK_SPAN_RE = re.compile(r"`[^`\n]*`")
INLINE_LINK_RE = re.compile(r"\]\((<[^>]*>|[^)\s]+)(?:\s+\"[^\"]*\")?\)")
REF_DEF_RE = re.compile(r"^\s*\[[^\]]+\]:\s*(\S+)")
HTML_ATTR_RE = re.compile(r"""(?:src|href)=["']([^"']*)["']""")
SLUG_RE = re.compile(r"^[a-z0-9-]+$")

failures = []


def fail(path, check, detail):
    failures.append((str(path), check, detail))
    print(f"FAIL {path} [{check}] {detail}")


def fence_opener(line: str):
    m = FENCE_OPEN_RE.match(line)
    if not m:
        return None
    marker, info = m.groups()
    if marker[0] == "`" and "`" in info:
        return None
    return marker[0], len(marker)


def is_fence_closer(line: str, char: str, length: int) -> bool:
    m = FENCE_CLOSE_RE.match(line)
    return bool(m and m.group(1)[0] == char and len(m.group(1)) >= length)


def strip_fences(text: str) -> str:
    out = []
    fence_char = None
    fence_len = 0
    for line in text.split("\n"):
        if fence_char:
            if is_fence_closer(line, fence_char, fence_len):
                fence_char = None
            continue
        opener = fence_opener(line)
        if opener:
            fence_char, fence_len = opener
            continue
        out.append(line)
    return "\n".join(out)


def read_tsv():
    with TSV_PATH.open("r", encoding="utf-8", newline="") as fh:
        lines = fh.read().split("\n")
    header = lines[0].split("\t")
    assert header == ["src", "dest", "kind", "summary"], header
    rows = []
    for line in lines[1:]:
        if not line:
            continue
        parts = line.split("\t")
        while len(parts) < 4:
            parts.append("")
        rows.append({"src": parts[0], "dest": parts[1], "kind": parts[2], "summary": parts[3]})
    return rows


def git(args):
    return subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, check=True
    ).stdout


def git_last_commit_date(rel_path: str) -> str:
    return git(["log", "-1", "--format=%ad", "--date=short", "--", rel_path]).strip()


def git_show_head(rel_path: str) -> bytes:
    return subprocess.run(
        ["git", "show", f"HEAD:{rel_path}"], cwd=ROOT, capture_output=True, check=True
    ).stdout


def enumerate_sources():
    out = git(
        [
            "ls-files",
            "docs/ARCHITECTURE.md",
            "docs/AUTH_FLOWS.md",
            "docs/CODE_STYLE.md",
            "docs/DIAGRAM_CONVENTIONS.md",
            "docs/INFRA_ARCHITECTURE.md",
            "docs/INFRA_RUNBOOK.md",
            "docs/LOCAL_DEV.md",
            "docs/MOCKUP_FEATURE_GAP.md",
            "docs/SESSION_LESSONS.md",
            "docs/learning",
            "docs/history",
            "docs/incidents",
            "docs/plans/backend-modernization",
        ]
    )
    return set(l for l in out.split("\n") if l)


def published_for(row: dict) -> str:
    src = row["src"]
    if src.startswith("docs/history/"):
        if src == "docs/history/README.md":
            return "2026-09-25"
        stem = Path(src).stem
        m = re.match(r"^(\d{4}-\d{2}-\d{2})-", stem)
        return m.group(1) if m else None
    if src.startswith("docs/incidents/"):
        parts = Path(src).parts
        idx = parts.index("incidents")
        dirname = parts[idx + 1]
        m = re.match(r"^(\d{4}-\d{2}-\d{2})-", dirname)
        return m.group(1) if m else None
    if src.startswith("docs/plans/backend-modernization/"):
        return git_last_commit_date(src)
    if src == "docs/MOCKUP_FEATURE_GAP.md":
        return "2026-08-08"
    return None


def build_lookup(rows):
    lookup = {}
    dir_lookup = {}
    for row in rows:
        lookup[row["src"]] = row["dest"]
        if Path(row["src"]).name == "README.md":
            dir_lookup[str(Path(row["src"]).parent)] = row["dest"]
    return lookup, dir_lookup


def split_fragment(target: str):
    if "#" in target:
        p, f = target.split("#", 1)
        return p, "#" + f
    return target, ""


def expected_rebase(target: str, src_dir: Path, lookup, dir_lookup):
    """Return (kind, value) where kind is 'skip' (unmoved, leave alone) or
    'mapped' (value is the expected new relative-to-repo-root dest path plus
    fragment), or None if target is empty/unparseable."""
    stripped = target.strip()
    if stripped.startswith("<") and stripped.endswith(">"):
        stripped = stripped[1:-1]
    if stripped.startswith(("http:", "https:", "mailto:", "#")) or not stripped:
        return ("skip", None)
    path_part, frag = split_fragment(stripped)
    if not path_part:
        return ("skip", None)
    resolved = (src_dir / path_part).resolve()
    try:
        rel_to_root = resolved.relative_to(ROOT).as_posix()
    except ValueError:
        return ("skip", None)
    if rel_to_root in lookup:
        return ("mapped", lookup[rel_to_root] + frag)
    if resolved.is_dir() and rel_to_root in dir_lookup:
        return ("mapped", dir_lookup[rel_to_root] + frag)
    return ("skip", None)


def extract_links(text: str):
    """Return list of raw target strings, in document order, skipping fences
    and inline code spans."""
    targets = []
    fence_char = None
    fence_len = 0
    for line in text.split("\n"):
        if fence_char:
            if is_fence_closer(line, fence_char, fence_len):
                fence_char = None
            continue
        opener = fence_opener(line)
        if opener:
            fence_char, fence_len = opener
            continue
        masked = BACKTICK_SPAN_RE.sub(lambda m: "\x00" * len(m.group(0)), line)
        for m in INLINE_LINK_RE.finditer(masked):
            targets.append(m.group(1))
        ref_m = REF_DEF_RE.match(masked)
        if ref_m:
            targets.append(ref_m.group(1))
        for m in HTML_ATTR_RE.finditer(masked):
            targets.append(m.group(1))
    return targets


def normalize_targets_removed(text: str) -> str:
    """For faithfulness comparison: blank out every link target (but keep
    link text / surrounding syntax) and fence/code-span content untouched."""
    out_lines = []
    fence_char = None
    fence_len = 0
    for line in text.split("\n"):
        if fence_char:
            out_lines.append(line)
            if is_fence_closer(line, fence_char, fence_len):
                fence_char = None
            continue
        opener = fence_opener(line)
        if opener:
            fence_char, fence_len = opener
            out_lines.append(line)
            continue
        masks = []

        def mask(m):
            masks.append(m.group(0))
            return f"\x00MASK{len(masks) - 1}\x00"

        masked = BACKTICK_SPAN_RE.sub(mask, line)
        masked = INLINE_LINK_RE.sub("]()", masked)
        ref_m = REF_DEF_RE.match(masked)
        if ref_m:
            prefix_end = ref_m.start(1)
            masked = masked[:prefix_end] + masked[ref_m.end(1):]
        masked = HTML_ATTR_RE.sub(lambda m: m.group(0).split("=")[0] + '=""', masked)
        for i, original in enumerate(masks):
            masked = masked.replace(f"\x00MASK{i}\x00", original)
        out_lines.append(masked)
    return "\n".join(out_lines)


def strip_leading_blank_lines(lines):
    i = 0
    while i < len(lines) and lines[i].strip() == "":
        i += 1
    return lines[i:]


def header_lines_and_body(text: str):
    """Split a generated file into (header_lines, body_text) where header
    ends at the blank line following the metadata blockquote (wiki also has
    an Overview heading to skip past)."""
    lines = text.split("\n")
    return lines


def check_completeness(rows, only):
    if only is not None:
        return
    tsv_srcs = set(r["src"] for r in rows)
    actual = enumerate_sources()
    if tsv_srcs != actual:
        missing_from_tsv = actual - tsv_srcs
        extra_in_tsv = tsv_srcs - actual
        if missing_from_tsv:
            fail(TSV_PATH, "completeness", f"tracked but not in TSV: {sorted(missing_from_tsv)}")
        if extra_in_tsv:
            fail(TSV_PATH, "completeness", f"in TSV but not tracked: {sorted(extra_in_tsv)}")
    dests = [r["dest"] for r in rows]
    dupes = {d for d in dests if dests.count(d) > 1}
    if dupes:
        fail(TSV_PATH, "completeness", f"duplicate dest paths: {sorted(dupes)}")


def check_layout(row):
    dest = row["dest"]
    if row["kind"] == "wiki":
        if not re.match(r"^docs/wiki/[^/]+/[^/]+\.md$", dest):
            fail(dest, "layout", "wiki dest must be docs/wiki/<topic>/<name>.md")
    else:
        m = re.match(r"^docs/raw/[^/]+/(\d{4}-\d{2}-\d{2})-([a-z0-9-]+)\.md$", dest)
        if not m:
            fail(dest, "layout", "raw dest must be docs/raw/<topic>/YYYY-MM-DD-<slug>.md")
        elif len(m.group(2)) > 60:
            fail(dest, "layout", f"slug exceeds 60 chars: {m.group(2)}")


def parse_wiki_header(dest_path: Path, row: dict, updated: str):
    text = dest_path.read_text(encoding="utf-8")
    lines = text.split("\n")
    if len(lines) < 8:
        fail(dest_path, "header", "too few lines for wiki header shape")
        return None
    if not lines[0].startswith("# "):
        fail(dest_path, "header", "missing H1 on line 1")
    if lines[1] != "":
        fail(dest_path, "header", "expected blank line after H1")
    sources_m = re.match(r"^> Sources: kanban-board-backend (\S+), (\d{4}-\d{2}-\d{2})$", lines[2])
    if not sources_m:
        fail(dest_path, "header", f"malformed Sources line: {lines[2]!r}")
    elif sources_m.group(2) != updated:
        fail(dest_path, "header", f"Sources U={sources_m.group(2)} != expected {updated}")
    raw_line = lines[3]
    if "(" in raw_line and raw_line.strip().startswith(">") and re.search(r"\([^)]*\.md[^)]*\)", raw_line):
        fail(dest_path, "header", "Raw line contains a parenthesised .md span")
    if not raw_line.startswith("> Raw: none"):
        fail(dest_path, "header", f"unexpected Raw line: {raw_line!r}")
    updated_m = re.match(r"^> Updated: (\d{4}-\d{2}-\d{2})$", lines[4])
    if not updated_m:
        fail(dest_path, "header", f"malformed Updated line: {lines[4]!r}")
    elif updated_m.group(1) != updated:
        fail(dest_path, "header", f"Updated={updated_m.group(1)} != expected {updated}")
    if lines[5] != "":
        fail(dest_path, "header", "expected blank line before Overview")
    if lines[6] != "## Overview":
        fail(dest_path, "header", f"expected '## Overview', got {lines[6]!r}")
    if lines[7] != "":
        fail(dest_path, "header", "expected blank line after Overview heading")
    return 8  # index of first body line


def parse_raw_md_header(dest_path: Path, row: dict, collected_date: str):
    text = dest_path.read_text(encoding="utf-8")
    lines = text.split("\n")
    if len(lines) < 6:
        fail(dest_path, "header", "too few lines for raw-md header shape")
        return None
    if not lines[0].startswith("# "):
        fail(dest_path, "header", "missing H1 on line 1")
    if lines[1] != "":
        fail(dest_path, "header", "expected blank line after H1")
    src_m = re.match(
        r"^> Source: (\S+) in the kanban-board-backend repository, git blob ([0-9a-f]+)$",
        lines[2],
    )
    if not src_m:
        fail(dest_path, "header", f"malformed Source line: {lines[2]!r}")
    elif src_m.group(1) != row["src"]:
        fail(dest_path, "header", f"Source path {src_m.group(1)} != {row['src']}")
    coll_m = re.match(r"^> Collected: (\d{4}-\d{2}-\d{2})$", lines[3])
    if not coll_m:
        fail(dest_path, "header", f"malformed Collected line: {lines[3]!r}")
    elif coll_m.group(1) != collected_date:
        fail(dest_path, "header", f"Collected={coll_m.group(1)} != log date {collected_date}")
    pub_m = re.match(r"^> Published: (\d{4}-\d{2}-\d{2}|Unknown)$", lines[4])
    expected_pub = published_for(row)
    if not pub_m:
        fail(dest_path, "header", f"malformed Published line: {lines[4]!r}")
    elif expected_pub is not None and pub_m.group(1) != expected_pub:
        fail(dest_path, "header", f"Published={pub_m.group(1)} != expected {expected_pub}")
    if lines[5] != "":
        fail(dest_path, "header", "expected blank line before body")
    return 6


def parse_raw_code_header(dest_path: Path, row: dict, collected_date: str):
    text = dest_path.read_text(encoding="utf-8")
    lines = text.split("\n")
    if len(lines) < 6:
        fail(dest_path, "header", "too few lines for raw-code header shape")
        return None
    src_path = Path(row["src"])
    expected_title = f"# {src_path.parent.name}: {src_path.name}"
    if lines[0] != expected_title:
        fail(dest_path, "header", f"expected title {expected_title!r}, got {lines[0]!r}")
    if lines[1] != "":
        fail(dest_path, "header", "expected blank line after title")
    src_m = re.match(
        r"^> Source: (\S+) in the kanban-board-backend repository, git blob ([0-9a-f]+)$",
        lines[2],
    )
    if not src_m:
        fail(dest_path, "header", f"malformed Source line: {lines[2]!r}")
    coll_m = re.match(r"^> Collected: (\d{4}-\d{2}-\d{2})$", lines[3])
    if not coll_m:
        fail(dest_path, "header", f"malformed Collected line: {lines[3]!r}")
    elif coll_m.group(1) != collected_date:
        fail(dest_path, "header", f"Collected={coll_m.group(1)} != log date {collected_date}")
    pub_m = re.match(r"^> Published: (\d{4}-\d{2}-\d{2}|Unknown)$", lines[4])
    expected_pub = published_for(row)
    if not pub_m:
        fail(dest_path, "header", f"malformed Published line: {lines[4]!r}")
    elif expected_pub is not None and pub_m.group(1) != expected_pub:
        fail(dest_path, "header", f"Published={pub_m.group(1)} != expected {expected_pub}")
    if lines[5] != "":
        fail(dest_path, "header", "expected blank line before body")
    return 6


def check_faithfulness_markdown(row, body_start_idx):
    dest_path = ROOT / row["dest"]
    src_path = ROOT / row["src"]
    dest_lines = dest_path.read_text(encoding="utf-8").split("\n")
    body_lines = dest_lines[body_start_idx:]
    body_text = "\n".join(body_lines)

    src_text = src_path.read_text(encoding="utf-8", newline="")
    src_lines = src_text.split("\n")
    orig_after_h1 = strip_leading_blank_lines(src_lines[1:])
    orig_text = "\n".join(orig_after_h1)

    normalized_new = normalize_targets_removed(body_text).rstrip("\n")
    normalized_orig = normalize_targets_removed(orig_text).rstrip("\n")
    if normalized_new != normalized_orig:
        fail(dest_path, "faithfulness", "body differs from original (modulo link targets)")


def check_faithfulness_raw_code(row, body_start_idx):
    dest_path = ROOT / row["dest"]
    src_path = ROOT / row["src"]
    dest_lines = dest_path.read_text(encoding="utf-8").split("\n")
    body_lines = dest_lines[body_start_idx:]
    if body_lines and body_lines[-1] == "":
        body_lines = body_lines[:-1]
    unindented = []
    for line in body_lines:
        if line == "":
            unindented.append("")
        elif line.startswith("    "):
            unindented.append(line[4:])
        else:
            fail(dest_path, "faithfulness", f"non-empty body line missing 4-space indent: {line!r}")
            unindented.append(line)
    reconstructed = "\n".join(unindented) + "\n"

    src_bytes = src_path.read_bytes()
    src_text = src_bytes.decode("utf-8")
    if reconstructed != src_text:
        fail(dest_path, "faithfulness", "de-indented body != source bytes")


def check_links(row, body_start_idx, lookup, dir_lookup, only):
    dest_path = ROOT / row["dest"]
    src_path = ROOT / row["src"]
    dest_lines = dest_path.read_text(encoding="utf-8").split("\n")
    body_lines = dest_lines[body_start_idx:]
    body_text = "\n".join(body_lines)

    src_text = src_path.read_text(encoding="utf-8", newline="")
    src_lines = src_text.split("\n")
    orig_after_h1 = "\n".join(strip_leading_blank_lines(src_lines[1:]))

    orig_targets = extract_links(orig_after_h1)
    new_targets = extract_links(body_text)

    link_count = len(orig_targets)
    pending = 0

    if len(orig_targets) != len(new_targets):
        fail(dest_path, "links", f"link count mismatch: orig={len(orig_targets)} new={len(new_targets)}")
        return link_count, pending

    src_dir = src_path.parent
    dest_dir = dest_path.parent
    for orig_target, new_target in zip(orig_targets, new_targets):
        kind, mapped = expected_rebase(orig_target, src_dir, lookup, dir_lookup)
        stripped_orig = orig_target.strip()
        if stripped_orig.startswith("<") and stripped_orig.endswith(">"):
            stripped_orig = stripped_orig[1:-1]
        if kind == "skip":
            if new_target != orig_target:
                fail(
                    dest_path,
                    "links",
                    f"unmoved target changed: {orig_target!r} -> {new_target!r}",
                )
            continue
        # mapped: compute the expected relative path from dest_dir
        target_path, frag = split_fragment(mapped)
        target_abs = ROOT / target_path
        if only is not None and not target_abs.is_file():
            pending += 1
            continue
        expected_rel = Path(
            __import__("os").path.relpath(target_abs, start=dest_dir)
        ).as_posix() + frag
        new_stripped = new_target.strip()
        if new_stripped.startswith("<") and new_stripped.endswith(">"):
            new_stripped = new_stripped[1:-1]
        if new_stripped != expected_rel:
            fail(
                dest_path,
                "links",
                f"expected {expected_rel!r}, got {new_stripped!r} (orig {orig_target!r})",
            )
        elif not target_abs.is_file():
            fail(dest_path, "links", f"rebased target does not exist on disk: {expected_rel!r}")

    return link_count, pending


def check_originals_untouched(rows):
    """Assert (6): every src is byte-identical to HEAD, and no commit that
    touched a src path (not the whole repo) carries this task's own tag —
    a commit elsewhere in history mentioning the tag is expected (this
    task's own commits) and is not evidence of a touched original."""
    for row in rows:
        src = row["src"]
        try:
            head_bytes = git_show_head(src)
        except subprocess.CalledProcessError:
            fail(src, "originals", "git show HEAD failed")
            continue
        wt_bytes = (ROOT / src).read_bytes()
        if wt_bytes != head_bytes:
            fail(src, "originals", "working tree differs from HEAD")
    for row in rows:
        touching = git(["log", "--format=%s", "--", row["src"]]).split("\n")
        for subj in touching:
            if subj and "260927-ryo" in subj:
                fail(row["src"], "originals", f"a commit touching this src has subject {subj!r}")


def check_index(rows, only):
    index_path = ROOT / "docs" / "wiki" / "index.md"
    if not index_path.is_file():
        fail(index_path, "index", "missing")
        return
    text = index_path.read_text(encoding="utf-8")

    # index.md always reflects every wiki dest present on disk, not just the
    # --only subset just processed: migrate_docs.py's regenerate_index does
    # the same (it globs existing dests, ignoring --only), so re-running a
    # narrow --only after a full migration must not shrink the index.
    wiki_rows = [r for r in rows if r["kind"] == "wiki" and (ROOT / r["dest"]).is_file()]

    linked = set(re.findall(r"\]\(([^)]+\.md)\)", text))
    linked = {str((index_path.parent / p).resolve().relative_to(ROOT).as_posix()) for p in linked}
    expected = {r["dest"] for r in wiki_rows}
    if linked != expected:
        fail(index_path, "index", f"linked={sorted(linked)} expected={sorted(expected)}")

    current_topic = None
    topic_has_desc = {}
    for line in text.split("\n"):
        m = re.match(r"^## (\S+)$", line)
        if m:
            current_topic = m.group(1)
            topic_has_desc[current_topic] = False
            continue
        if current_topic and line.strip() and not line.startswith("|") and not topic_has_desc[current_topic]:
            topic_has_desc[current_topic] = True
    for topic, has_desc in topic_has_desc.items():
        if not has_desc:
            fail(index_path, "index", f"topic {topic} has no description line")

    for row in wiki_rows:
        dest_path = ROOT / row["dest"]
        if not dest_path.is_file():
            continue
        dest_text = dest_path.read_text(encoding="utf-8")
        um = re.search(r"^> Updated: (\d{4}-\d{2}-\d{2})$", dest_text, re.MULTILINE)
        article_updated = um.group(1) if um else None
        filename = Path(row["dest"]).name
        topic = Path(row["dest"]).parent.name
        row_m = re.search(
            rf"\[[^\]]*\]\({re.escape(topic)}/{re.escape(filename)}\) \| [^|]* \| (\d{{4}}-\d{{2}}-\d{{2}}) \|",
            text,
        )
        if not row_m:
            fail(index_path, "index", f"no index row found for {row['dest']}")
        elif article_updated and row_m.group(1) != article_updated:
            fail(
                index_path,
                "index",
                f"{row['dest']} index Updated={row_m.group(1)} != article Updated={article_updated}",
            )


def check_log(rows, only):
    log_path = ROOT / "docs" / "wiki" / "log.md"
    if not log_path.is_file():
        fail(log_path, "log", "missing")
        return None
    text = log_path.read_text(encoding="utf-8")
    headings = [l for l in text.split("\n") if l.startswith("## [")]
    if len(headings) != 1:
        fail(log_path, "log", f"expected exactly one entry heading, found {len(headings)}")
        return None
    if "migrate" not in headings[0]:
        fail(log_path, "log", f"the one entry must be a migrate entry, got {headings[0]!r}")
    m = re.match(r"^## \[(\d{4}-\d{2}-\d{2})\]", headings[0])
    return m.group(1) if m else None


def check_wiki_depth():
    wiki_dir = ROOT / "docs" / "wiki"
    if not wiki_dir.is_dir():
        return
    for path in wiki_dir.rglob("*.md"):
        rel = path.relative_to(wiki_dir)
        if len(rel.parts) != 2 and path.name not in ("index.md", "log.md"):
            fail(path, "depth", f"deeper than docs/wiki/<topic>/<file>: {rel}")
        elif len(rel.parts) != 1 and path.name in ("index.md", "log.md"):
            fail(path, "depth", f"{path.name} must sit directly under docs/wiki/")


def main(argv: list[str]) -> int:
    only = None
    if len(argv) > 1:
        if argv[1] != "--only":
            print("usage: verify_migration.py [--only <src> [<src> ...]]", file=sys.stderr)
            return 2
        only = set(argv[2:])

    rows = read_tsv()
    lookup, dir_lookup = build_lookup(rows)

    check_completeness(rows, only)

    selected = [r for r in rows if only is None or r["src"] in only]

    for row in selected:
        check_layout(row)

    log_date = check_log(rows, only)
    collected_date = log_date

    wiki_count = 0
    raw_count = 0
    link_count = 0
    pending_count = 0

    for row in selected:
        dest_path = ROOT / row["dest"]
        if not dest_path.is_file():
            fail(dest_path, "missing", "dest not generated")
            continue
        updated = git_last_commit_date(row["src"])
        if row["kind"] == "wiki":
            wiki_count += 1
            body_start = parse_wiki_header(dest_path, row, updated)
            if body_start is None:
                continue
            check_faithfulness_markdown(row, body_start)
            lc, pc = check_links(row, body_start, lookup, dir_lookup, only)
            link_count += lc
            pending_count += pc
        elif row["kind"] == "raw-md":
            raw_count += 1
            body_start = parse_raw_md_header(dest_path, row, collected_date) if collected_date else None
            if body_start is None:
                continue
            check_faithfulness_markdown(row, body_start)
            lc, pc = check_links(row, body_start, lookup, dir_lookup, only)
            link_count += lc
            pending_count += pc
        elif row["kind"] == "raw-code":
            raw_count += 1
            body_start = parse_raw_code_header(dest_path, row, collected_date) if collected_date else None
            if body_start is None:
                continue
            check_faithfulness_raw_code(row, body_start)
        else:
            fail(dest_path, "kind", f"unknown kind {row['kind']!r}")

    check_originals_untouched(rows)
    check_index(rows, only)
    check_wiki_depth()

    print(
        f"checked: {wiki_count} wiki, {raw_count} raw, {link_count} links "
        f"({pending_count} pending); failures: {len(failures)}"
    )
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
