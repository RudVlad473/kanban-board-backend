#!/usr/bin/env python3
"""Migrate docs/ into the karpathy-llm-wiki skill's docs/raw + docs/wiki layout.

Reads migration-map.tsv (src, dest, kind, summary), opens every src read-only,
and writes the corresponding dest with the skill's metadata header added. Then
regenerates docs/wiki/index.md and docs/wiki/log.md from the TSV. Never writes
to a src path. See verify_migration.py for the independent check of this
script's output.

Usage: migrate_docs.py [--only <src> [<src> ...]]
"""

import re
import subprocess
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TSV_PATH = Path(__file__).resolve().parent / "migration-map.tsv"
TODAY = date.today().isoformat()

# Same fence detection as check_evidence.py, so link rebasing skips exactly
# the spans the skill's own lint treats as non-prose.
FENCE_OPEN_RE = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
FENCE_CLOSE_RE = re.compile(r"^ {0,3}(`{3,}|~{3,})[ \t]*$")
BACKTICK_SPAN_RE = re.compile(r"`[^`\n]*`")

INLINE_LINK_RE = re.compile(r"\]\((<[^>]*>|[^)\s]+)(\s+\"[^\"]*\")?\)")
REF_DEF_RE = re.compile(r"^(\s*\[[^\]]+\]:\s*)(\S+)")
HTML_ATTR_RE = re.compile(r"""(src|href)=(["'])([^"']*)(["'])""")


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


def read_tsv():
    rows = []
    with TSV_PATH.open("r", encoding="utf-8", newline="") as fh:
        lines = fh.read().split("\n")
    header = lines[0].split("\t")
    assert header == ["src", "dest", "kind", "summary"], header
    for line in lines[1:]:
        if not line:
            continue
        parts = line.split("\t")
        while len(parts) < 4:
            parts.append("")
        src, dest, kind, summary = parts[0], parts[1], parts[2], parts[3]
        rows.append({"src": src, "dest": dest, "kind": kind, "summary": summary})
    return rows


def git_last_commit_date(rel_path: str) -> str:
    out = subprocess.run(
        ["git", "log", "-1", "--format=%ad", "--date=short", "--", rel_path],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    return out


def git_blob_short(rel_path: str) -> str:
    out = subprocess.run(
        ["git", "rev-parse", "--short", f"HEAD:{rel_path}"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    return out


def published_for(row: dict) -> str:
    """Derive the raw Published date per the map's per-group rule."""
    src = row["src"]
    dest_name = Path(row["dest"]).name
    if src.startswith("docs/history/"):
        if src == "docs/history/README.md":
            return "2026-09-25"
        stem = Path(src).stem
        m = re.match(r"^(\d{4}-\d{2}-\d{2})-", stem)
        if m:
            return m.group(1)
        raise ValueError(f"cannot derive Published date from filename: {src}")
    if src.startswith("docs/incidents/"):
        # Published = the incident directory's date.
        parts = Path(src).parts
        idx = parts.index("incidents")
        dirname = parts[idx + 1]
        m = re.match(r"^(\d{4}-\d{2}-\d{2})-", dirname)
        if m:
            return m.group(1)
        raise ValueError(f"cannot derive Published date from incident dir: {src}")
    if src.startswith("docs/plans/backend-modernization/"):
        return git_last_commit_date(src)
    if src == "docs/MOCKUP_FEATURE_GAP.md":
        return "2026-08-08"
    raise ValueError(f"no Published rule matched for {src}")


def build_dest_lookup(rows):
    """src (and src's containing dir, for README-as-dir-target) -> dest."""
    lookup = {}
    dir_lookup = {}
    for row in rows:
        lookup[row["src"]] = row["dest"]
        if Path(row["src"]).name == "README.md":
            dir_lookup[str(Path(row["src"]).parent)] = row["dest"]
    return lookup, dir_lookup


def split_fragment(target: str):
    if "#" in target:
        path_part, frag = target.split("#", 1)
        return path_part, "#" + frag
    return target, ""


def resolve_and_rebase(target: str, src_dir: Path, dest_dir: Path, lookup, dir_lookup) -> str | None:
    """Return the rebased target, or None if the link should be left alone."""
    stripped = target.strip()
    if stripped.startswith("<") and stripped.endswith(">"):
        inner = stripped[1:-1]
        rebased = resolve_and_rebase(inner, src_dir, dest_dir, lookup, dir_lookup)
        return f"<{rebased}>" if rebased is not None else None
    if stripped.startswith(("http:", "https:", "mailto:", "#")):
        return None
    path_part, frag = split_fragment(stripped)
    if not path_part:
        return None

    # Resolve against the src file's directory, project-root-relative.
    resolved = (src_dir / path_part).resolve()
    try:
        rel_to_root = resolved.relative_to(ROOT).as_posix()
    except ValueError:
        return None  # escapes the repo; leave unmoved

    new_dest = None
    if rel_to_root in lookup:
        new_dest = lookup[rel_to_root]
    elif resolved.is_dir() and rel_to_root in dir_lookup:
        new_dest = dir_lookup[rel_to_root]
    else:
        return None  # unmoved target (diagrams, demo, repo files)

    new_target_abs = ROOT / new_dest
    rel_path = Path(
        __import__("os").path.relpath(new_target_abs, start=dest_dir)
    ).as_posix()
    return rel_path + frag


def rebase_markdown_links(text: str, src_dir: Path, dest_dir: Path, lookup, dir_lookup) -> str:
    lines = text.split("\n")
    out_lines = []
    fence_char = None
    fence_len = 0
    for line in lines:
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
        out_lines.append(rebase_line(line, src_dir, dest_dir, lookup, dir_lookup))
    return "\n".join(out_lines)


def rebase_line(line: str, src_dir: Path, dest_dir: Path, lookup, dir_lookup) -> str:
    # Mask backtick spans so link-like text inside inline code is never touched.
    masks = []

    def mask(m):
        masks.append(m.group(0))
        return f"\x00MASK{len(masks) - 1}\x00"

    masked = BACKTICK_SPAN_RE.sub(mask, line)

    def repl_inline(m):
        target = m.group(1)
        title = m.group(2) or ""
        rebased = resolve_and_rebase(target, src_dir, dest_dir, lookup, dir_lookup)
        if rebased is None:
            return m.group(0)
        return f"]({rebased}{title})"

    masked = INLINE_LINK_RE.sub(repl_inline, masked)

    ref_match = REF_DEF_RE.match(masked)
    if ref_match:
        prefix, target = ref_match.groups()
        rebased = resolve_and_rebase(target, src_dir, dest_dir, lookup, dir_lookup)
        if rebased is not None:
            masked = prefix + rebased + masked[ref_match.end():]

    def repl_attr(m):
        attr, q1, target, q2 = m.groups()
        rebased = resolve_and_rebase(target, src_dir, dest_dir, lookup, dir_lookup)
        if rebased is None:
            return m.group(0)
        return f"{attr}={q1}{rebased}{q2}"

    masked = HTML_ATTR_RE.sub(repl_attr, masked)

    for i, original in enumerate(masks):
        masked = masked.replace(f"\x00MASK{i}\x00", original)
    return masked


def strip_leading_blank_lines(lines: list[str]) -> list[str]:
    i = 0
    while i < len(lines) and lines[i].strip() == "":
        i += 1
    return lines[i:]


def migrate_wiki(row: dict, lookup, dir_lookup):
    src_path = ROOT / row["src"]
    dest_path = ROOT / row["dest"]
    dest_path.parent.mkdir(parents=True, exist_ok=True)

    text = src_path.read_text(encoding="utf-8", newline="")
    lines = text.split("\n")
    h1 = lines[0]
    rest = strip_leading_blank_lines(lines[1:])
    body_text = "\n".join(rest)
    src_dir = src_path.parent
    dest_dir = dest_path.parent
    rebased_body = rebase_markdown_links(body_text, src_dir, dest_dir, lookup, dir_lookup)

    updated = git_last_commit_date(row["src"])
    header = [
        h1,
        "",
        f"> Sources: kanban-board-backend {row['src']}, {updated}",
        f"> Raw: none — primary document migrated as-is on {TODAY}, a one-time "
        "operator-approved exception to the Grounding Invariant",
        f"> Updated: {updated}",
        "",
        "## Overview",
        "",
    ]
    out_text = "\n".join(header) + "\n" + rebased_body
    if not out_text.endswith("\n"):
        out_text += "\n"
    dest_path.write_text(out_text, encoding="utf-8", newline="")
    return updated


def migrate_raw_md(row: dict, lookup, dir_lookup):
    src_path = ROOT / row["src"]
    dest_path = ROOT / row["dest"]
    dest_path.parent.mkdir(parents=True, exist_ok=True)

    text = src_path.read_text(encoding="utf-8", newline="")
    lines = text.split("\n")
    h1 = lines[0]
    rest = strip_leading_blank_lines(lines[1:])
    body_text = "\n".join(rest)
    src_dir = src_path.parent
    dest_dir = dest_path.parent
    rebased_body = rebase_markdown_links(body_text, src_dir, dest_dir, lookup, dir_lookup)

    blob = git_blob_short(row["src"])
    published = published_for(row)
    header = [
        h1,
        "",
        f"> Source: {row['src']} in the kanban-board-backend repository, git blob {blob}",
        f"> Collected: {TODAY}",
        f"> Published: {published}",
        "",
    ]
    out_text = "\n".join(header) + "\n" + rebased_body
    if not out_text.endswith("\n"):
        out_text += "\n"
    dest_path.write_text(out_text, encoding="utf-8", newline="")


def migrate_raw_code(row: dict):
    src_path = ROOT / row["src"]
    dest_path = ROOT / row["dest"]
    dest_path.parent.mkdir(parents=True, exist_ok=True)

    text = src_path.read_text(encoding="utf-8", newline="")
    parent_dir_name = src_path.parent.name
    filename = src_path.name
    blob = git_blob_short(row["src"])
    published = published_for(row)

    body_lines = text.split("\n")
    if body_lines and body_lines[-1] == "":
        body_lines = body_lines[:-1]
    indented = ["    " + line if line else "" for line in body_lines]

    header = [
        f"# {parent_dir_name}: {filename}",
        "",
        f"> Source: {row['src']} in the kanban-board-backend repository, git blob {blob}",
        f"> Collected: {TODAY}",
        f"> Published: {published}",
        "",
    ]
    out_text = "\n".join(header + indented) + "\n"
    dest_path.write_text(out_text, encoding="utf-8", newline="")


def h1_text_of(dest_path: Path) -> str:
    text = dest_path.read_text(encoding="utf-8")
    first = text.split("\n", 1)[0]
    return first[2:] if first.startswith("# ") else first


TOPIC_DESCRIPTIONS = {
    "architecture": "How the Spring Boot application is built: layering, access control, persistence, errors, and the authentication flows a client observes.",
    "conventions": "How work in this repo is written and run: Java code style, diagram conventions and GSD session lessons.",
    "infra": "Where and how the system runs: production infrastructure, its operations runbook and the local development stack.",
    "learning": "A numbered study guide explaining each layer's decisions, read in order from 00.",
}


def regenerate_index(rows):
    wiki_rows = [r for r in rows if r["kind"] == "wiki" and (ROOT / r["dest"]).is_file()]
    by_topic: dict[str, list[dict]] = {}
    for row in wiki_rows:
        topic = Path(row["dest"]).parent.name
        by_topic.setdefault(topic, []).append(row)

    lines = ["# Knowledge Base Index", ""]
    for topic in sorted(by_topic):
        lines.append(f"## {topic}")
        lines.append("")
        lines.append(TOPIC_DESCRIPTIONS.get(topic, ""))
        lines.append("")
        lines.append("| Article | Summary | Updated |")
        lines.append("|---------|---------|---------|")
        topic_rows = sorted(by_topic[topic], key=lambda r: Path(r["dest"]).name)
        for row in topic_rows:
            dest_path = ROOT / row["dest"]
            title = h1_text_of(dest_path)
            filename = Path(row["dest"]).name
            updated = git_last_commit_date(row["src"])
            lines.append(f"| [{title}]({topic}/{filename}) | {row['summary']} | {updated} |")
        lines.append("")
    index_path = ROOT / "docs" / "wiki" / "index.md"
    index_path.write_text("\n".join(lines).rstrip("\n") + "\n", encoding="utf-8", newline="")


def regenerate_log(rows):
    log_path = ROOT / "docs" / "wiki" / "log.md"
    if log_path.is_file():
        existing = log_path.read_text(encoding="utf-8")
        headings = [l for l in existing.split("\n") if l.startswith("## [")]
        non_migrate = [h for h in headings if "migrate" not in h]
        if non_migrate:
            print(
                "ABORT: log.md already has a non-migrate entry:", non_migrate,
                file=sys.stderr,
            )
            sys.exit(1)

    wiki_count = sum(1 for r in rows if r["kind"] == "wiki" and (ROOT / r["dest"]).is_file())
    raw_rows = [r for r in rows if r["kind"] != "wiki" and (ROOT / r["dest"]).is_file()]
    raw_count = len(raw_rows)

    areas = []
    if any(r["dest"].startswith("docs/raw/infra-history/") for r in raw_rows):
        areas.append("infra history")
    if any(r["dest"].startswith("docs/raw/incidents/") for r in raw_rows):
        areas.append("incidents")
    if any(r["dest"].startswith("docs/raw/backend-modernization-plan/") for r in raw_rows):
        areas.append("the backend modernization plan")
    if any(r["dest"].startswith("docs/raw/product/") for r in raw_rows):
        areas.append("product")
    area_text = ", ".join(areas) if areas else "no areas"

    lines = [
        "# Wiki Log",
        "",
        f"## [{TODAY}] migrate | docs/ relocated into raw/ and wiki/",
        "- Source: github.com/Astro-Han/karpathy-llm-wiki, commit "
        "eafcc77001e496cc43499e4923b663aec722c813",
        f"- {wiki_count} wiki article(s) migrated: metadata header and an Overview "
        "heading were added, the body is unchanged, and there are no Raw links "
        "(the operator-approved one-time exception).",
        f"- {raw_count} raw source(s) migrated across {area_text}; not yet compiled "
        "into any wiki article.",
        "- Relative link targets were rebased to the new locations; nothing else "
        "changed.",
        "- The originals under docs/ are left in place and stay authoritative until "
        "a follow-on cleanup.",
        "",
    ]
    log_path.write_text("\n".join(lines), encoding="utf-8", newline="")


def main(argv: list[str]) -> int:
    only = None
    if len(argv) > 1:
        if argv[1] != "--only":
            print("usage: migrate_docs.py [--only <src> [<src> ...]]", file=sys.stderr)
            return 2
        only = set(argv[2:])

    rows = read_tsv()
    lookup, dir_lookup = build_dest_lookup(rows)

    selected = [r for r in rows if only is None or r["src"] in only]

    for row in selected:
        if row["kind"] == "wiki":
            migrate_wiki(row, lookup, dir_lookup)
        elif row["kind"] == "raw-md":
            migrate_raw_md(row, lookup, dir_lookup)
        elif row["kind"] == "raw-code":
            migrate_raw_code(row)
        else:
            print(f"unknown kind {row['kind']!r} for {row['src']}", file=sys.stderr)
            return 1

    regenerate_index(rows)
    regenerate_log(rows)
    print(f"migrated {len(selected)} row(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
