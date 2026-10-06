#!/usr/bin/env python3
"""Rewrite Javadoc HTML tags and {@...} inline tags in Java block comments as plain text.

  apply [paths...]            rewrite in place; print files, blocks and residue (left for hand fixing)
  verify --base REF [paths..] prove per-comment word sequences are unchanged against REF

Data flow: scripts/verify-comments.py's tokenizer says which line ranges are comments; apply
rewrites only those ranges, so string literals and text blocks are never reached. verify re-lexes
both versions and compares the words of every comment pair, so a deleted or replaced word fails.

stdlib only. Idempotent: a second apply over a swept tree changes nothing.

After apply, run ./gradlew spotlessApply: an import that only a removed link tag referenced is
unused afterwards and the formatter drops it. That is the one non-comment change a sweep can
cause, and verify-comments.py equiv reports it, so prove it is an import-only difference.
"""

import argparse
import difflib
import importlib.util
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
_spec = importlib.util.spec_from_file_location("verify_comments", os.path.join(REPO, "scripts", "verify-comments.py"))
vc = importlib.util.module_from_spec(_spec)
sys.modules["verify_comments"] = vc
_spec.loader.exec_module(vc)

INLINE_OPEN_RE = re.compile(r"\{@([A-Za-z]+)")
ENTITY_RE = re.compile(r"&(lt|gt|amp);")
ENTITIES = {"lt": "<", "gt": ">", "amp": "&"}
EMPHASIS_RE = re.compile(r"</?(?:b|i|em)>")
LIST_OPEN_RE = re.compile(r"^<(ul|ol)>$")
LIST_CLOSE_RE = re.compile(r"^</(ul|ol)>$")
LIST_ITEM_RE = re.compile(r"^<li>\s*")
WORD_RE = re.compile(r"[A-Za-z0-9_]+")
OPENING_BRACKET_ENDINGS = ("(", "[", "{", '"', "'")


def has_markup(text):
    return bool(vc.JAVADOC_HTML_RE.search(text) or vc.JAVADOC_INLINE_RE.search(text))


# ------------------------------------------------------------------ line anatomy


def split_lines(raw_lines):
    """Return rows (indent, opener, content, closer) for the raw source lines of one block comment."""
    rows = []
    last = len(raw_lines) - 1
    for k, raw in enumerate(raw_lines):
        indent = raw[: len(raw) - len(raw.lstrip())]
        rest = raw[len(indent) :]
        opener = ""
        if k == 0:
            opener = "/**" if rest.startswith("/**") and not rest.startswith("/**/") else "/*"
            rest = rest[len(opener) :]
        elif rest.startswith("*") and not rest.startswith("*/"):
            opener = "*"
            rest = rest[1:]
        closer = ""
        if k == last and rest.rstrip().endswith("*/"):
            closer = "*/"
            rest = rest.rstrip()[:-2]
        content = rest[1:] if rest.startswith(" ") else rest
        rows.append([indent, opener, content.rstrip() if closer else content, closer])
    return rows


def join_row(indent, opener, content, closer):
    out = indent + opener
    if content:
        out += " " + content
    if closer:
        out += (" " if content or opener else "") + closer
    return out


# ------------------------------------------------------------------ inline tags


def find_close(text, pos):
    """Index of the } closing the brace opened before pos, honouring nested braces; -1 if none."""
    depth = 1
    for i in range(pos, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return -1


def link_target(target):
    target = target[1:] if target.startswith("#") else target
    return target.replace("#", ".")


def split_target(body):
    """Split a link body into (target, label); whitespace inside parentheses does not split."""
    depth = 0
    for i, ch in enumerate(body):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch.isspace() and depth == 0:
            return body[:i], body[i:].strip()
    return body, ""


def replace_inline_tags(text, residue):
    """Replace code/literal/link/linkplain tags; report any other tag name or an unclosed tag."""
    pos = 0
    while True:
        m = INLINE_OPEN_RE.search(text, pos)
        if not m:
            return text
        name = m.group(1)
        close = find_close(text, m.end())
        if close < 0 or name not in ("code", "literal", "link", "linkplain"):
            residue.append("inline tag {@%s ...} left alone (%s)" % (name, "unclosed" if close < 0 else "unhandled"))
            pos = m.end()
            continue
        body = text[m.end() : close]
        stripped = body.lstrip()
        lead = "\n" if "\n" in body[: len(body) - len(stripped)] else ""
        if name in ("code", "literal"):
            new = lead + stripped
        else:
            target, label = split_target(stripped)
            new = lead + (label if label else link_target(target))
        if new.count("\n") != text[m.start() : close + 1].count("\n"):
            residue.append("inline tag {@%s ...} spans lines unevenly, left alone" % name)
            pos = m.end()
            continue
        text = text[: m.start()] + new + text[close + 1 :]
        pos = m.start()


# ------------------------------------------------------------------ block rewrite


def rewrite_block(rows, residue):
    """Return the new rows for one block comment; residue gets (row index, reason) entries."""
    contents = [r[2] for r in rows]
    original = list(contents)
    local = []
    text = replace_inline_tags("\n".join(contents), local)
    contents = text.split("\n")
    if len(contents) != len(original):  # a tag whose lead was a newline keeps the count; anything else is a bug
        raise RuntimeError("line count changed while replacing inline tags")

    drop = [False] * len(contents)
    for i, line in enumerate(contents):
        line = ENTITY_RE.sub(lambda m: ENTITIES[m.group(1)], line) if "&" in line else line
        line = re.sub(r"^(\s*)<p>\s*", r"\1", line)
        line = EMPHASIS_RE.sub("", line)
        line = line.replace("</li>", "")
        contents[i] = line.rstrip()
        core = line.strip()
        if LIST_OPEN_RE.match(core) or LIST_CLOSE_RE.match(core):
            drop[i] = True

    for i, line in enumerate(contents):  # line-leading @ repair
        if not line.lstrip().startswith("@") or original[i].lstrip().startswith("@"):
            continue
        prev = contents[i - 1] if i > 0 else ""
        tokens = prev.split()
        eligible = i > 0 and not drop[i - 1] and len(tokens) >= 2 and not rows[i - 1][1] == "/**" and not (
            prev.lstrip().startswith(("-", "<li>")) and len(tokens) <= 2
        )
        if not eligible:
            residue.append((i, "line starts with @ at a paragraph start: hand fix"))
            continue
        token = tokens[-1]
        head = prev[: prev.rstrip().rfind(token)].rstrip()
        contents[i - 1] = head
        body = line.lstrip()
        contents[i] = line[: len(line) - len(body)] + token + ("" if token.endswith(OPENING_BRACKET_ENDINGS) else " ") + body

    # lists: unordered "- ", ordered "N. ", dedented to the paragraph margin, continuations aligned
    out_rows, depth, kind, counter, item_pad = [], 0, None, 0, 0
    for i, line in enumerate(contents):
        core = line.strip()
        row = list(rows[i])
        if LIST_OPEN_RE.match(core):
            if drop[i] and (row[1] == "/**" or row[3]):
                residue.append((i, "list wrapper shares a line with a comment delimiter: hand fix"))
                out_rows.append(row[:2] + [line, row[3]])
                continue
            depth += 1
            if depth > 1:
                residue.append((i, "nested list: hand fix"))
            kind, counter, item_pad = LIST_OPEN_RE.match(core).group(1), 0, 0
            continue
        if LIST_CLOSE_RE.match(core):
            depth = max(0, depth - 1)
            item_pad = 0
            continue
        if depth >= 1 and LIST_ITEM_RE.match(core):
            counter += 1
            marker = "- " if kind == "ul" else "%d. " % counter
            item_pad = len(marker)
            row[2] = marker + LIST_ITEM_RE.sub("", core)
        elif depth >= 1 and core and item_pad:
            row[2] = " " * item_pad + core
        elif depth == 0 and LIST_ITEM_RE.match(core):
            residue.append((i, "<li> outside a list: hand fix"))
            row[2] = line
        else:
            row[2] = line
            if not core:
                item_pad = 0
        out_rows.append(row)

    final = []  # collapse the double blank a drop can leave, never touching a delimiter line
    for row in out_rows:
        blank = not row[2].strip() and not row[3] and row[1] != "/**" and row[1] != "/*"
        if blank and final and not final[-1][2].strip() and not final[-1][3] and final[-1][1] not in ("/**", "/*"):
            continue
        row[2] = row[2].rstrip()
        final.append(row)

    for i, row in enumerate(final):
        if has_markup(row[2]):
            residue.append((i, "markup still present: hand fix"))
    residue.extend((None, r) for r in local)
    return final


def sweep_file(path, text):
    """Return (new_text, blocks_changed, residue list of (line, reason, text))."""
    events = vc.extract("java", text)
    lines = text.split("\n")
    residue_out, changed = [], 0
    for ev in sorted(events, key=lambda e: e.start, reverse=True):
        hit = any(has_markup(t) for _, t in ev.lines)
        if not hit:
            continue
        if ev.merge or not ev.full:
            residue_out.append((ev.start, "markup in a non-block or trailing comment: hand fix", lines[ev.start - 1]))
            continue
        raw = lines[ev.start - 1 : ev.end]
        rows = split_lines(raw)
        residue = []
        new_rows = rewrite_block(rows, residue)
        new_raw = [join_row(*r) for r in new_rows]
        for idx, reason in residue:
            if idx is None:
                residue_out.append((ev.start, reason, ""))
            else:
                shown = new_raw[idx] if 0 <= idx < len(new_raw) else ""
                residue_out.append((ev.start + idx, reason, shown))
        if new_raw != raw:
            lines[ev.start - 1 : ev.end] = new_raw
            changed += 1
    return "\n".join(lines), changed, residue_out


def read_raw(path):
    with open(os.path.join(REPO, path), "r", encoding="utf-8", newline="") as fh:
        return fh.read()


def write_raw(path, text):
    with open(os.path.join(REPO, path), "w", encoding="utf-8", newline="") as fh:
        fh.write(text)


def java_files(paths):
    return [p for p in vc.resolve_paths(paths) if (s := vc.classify(p)) and s.kind == "java" and not s.exempt]


def cmd_apply(args):
    files_changed = blocks_changed = 0
    residue_all = []
    for path in java_files(args.paths):
        text = read_raw(path)
        if "\r" in text:
            residue_all.append((path, 0, "file has carriage returns: skipped", ""))
            continue
        new_text, changed, residue = sweep_file(path, text)
        residue_all.extend((path, ln, why, shown) for ln, why, shown in residue)
        if new_text != text:
            write_raw(path, new_text)
            files_changed += 1
            blocks_changed += changed
            print("changed %s (%d blocks)" % (path, changed))
    print("files changed: %d, blocks changed: %d, residue lines: %d" % (files_changed, blocks_changed, len(residue_all)))
    for path, ln, why, shown in sorted(residue_all):
        print("RESIDUE %s:%d: %s | %s" % (path, ln, why, shown.strip()))
    return 0


# ------------------------------------------------------------------ verify


def normalise(line):
    line = ENTITY_RE.sub(lambda m: ENTITIES[m.group(1)], line)
    line = vc.JAVADOC_HTML_RE.sub(" ", line)
    line = INLINE_OPEN_RE.sub(" ", line)
    return re.sub(r"^\s*(?:-|\d+\.)\s+", "", line)


def words_of(event):
    out = []
    for ln, t in event.lines:
        out.extend((w, ln) for w in WORD_RE.findall(normalise(t)))
    return out


def changed_java_files(base, paths):
    res = subprocess.run(
        ["git", "diff", "--name-only", "--no-renames", base], cwd=REPO, capture_output=True, text=True, check=True
    )
    changed = set(res.stdout.split("\n"))
    return [p for p in java_files(paths) if p in changed]


def cmd_verify(args):
    bad = added = checked = 0
    for path in changed_java_files(args.base, args.paths):
        old = vc.read_text(path, args.base)
        new = vc.read_text(path)
        if old is None or new is None:
            print("CHANGED %s: cannot read both versions" % path)
            bad += 1
            continue
        old_events, new_events = vc.extract("java", old), vc.extract("java", new)
        if len(old_events) != len(new_events):
            print("CHANGED %s: comment count %d -> %d" % (path, len(old_events), len(new_events)))
            bad += 1
            continue
        checked += 1
        for oe, ne in zip(old_events, new_events):
            ow, nw = words_of(oe), words_of(ne)
            sm = difflib.SequenceMatcher(None, [w for w, _ in ow], [w for w, _ in nw], autojunk=False)
            for op, i1, i2, j1, j2 in sm.get_opcodes():
                if op == "equal":
                    continue
                gone = " ".join(w for w, _ in ow[i1:i2])
                came = " ".join(w for w, _ in nw[j1:j2])
                line = nw[j1][1] if j1 < len(nw) else (nw[-1][1] if nw else ne.start)
                if op == "insert":
                    print("ADDED %s:%d: +[%s]" % (path, line, came))
                    added += 1
                else:
                    print("CHANGED %s:%d: -[%s] +[%s]" % (path, line, gone, came))
                    bad += 1
    print("verify vs %s: %d files compared, %d CHANGED, %d ADDED" % (args.base, checked, bad, added))
    return 1 if bad else 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("apply")
    p.add_argument("paths", nargs="*")
    p = sub.add_parser("verify")
    p.add_argument("--base", required=True)
    p.add_argument("paths", nargs="*")
    args = ap.parse_args(argv)
    return cmd_apply(args) if args.cmd == "apply" else cmd_verify(args)


if __name__ == "__main__":
    sys.exit(main())
