#!/usr/bin/env python3
"""Lint every code comment against the comment policy and prove comment-only diffs change no code.

Subcommands: files, stats, report, check (stdlib-only, exit 1 on any violation) and
equiv (compares each changed file's code representation against a base ref).
Rules: planning-system ids are banned, a block of 4+ prose lines opens with a summary of at most
2 lines, narration past 8 prose lines sits behind a decision-record marker, task markers link out.
Policy home: docs/CODE_STYLE.md rule 14.

Decisions:
  * Flyway stores a CRC32 over every line of an applied migration, comments included, and
    validates it at boot (read from Flyway's ChecksumCalculator on 2026-10-05). Migrations under
    src/main/resources/db/migration/ are therefore FROZEN: never linted, and equiv refuses any
    byte change there. False if a Flyway release stops hashing comment lines.
  * The Postgres init scripts under k8s/data/postgres/init/ feed a configMapGenerator whose name
    carries a content hash (rendered as postgres-init-62tc4bg5h4 on 2026-10-05). A comment edit
    renames the ConfigMap and restarts the Postgres StatefulSet, so they are ROLLOUT_GATED and
    exempt from the lint. False if the generator stops hashing its inputs.
  * The lint is stdlib-only so the pre-commit hook needs no pip step; only equiv imports yaml and
    tomllib, lazily. The stdlib YAML comment detector is cross-checked against yaml.scan by the
    selftest on every in-scope YAML file.
  * A scan that finds zero in-scope files fails: an empty scan and a clean scan look the same.

Known holes:
  * Splitting a long block with an empty source line evades the narration rule.
  * Block-scalar content outside .github/workflows/ is content, so it is not linted.
  * docs/** changes match invariant-checks' paths-ignore, so CI skips them; pre-commit still runs.
  * The lint reads the working tree, not the index, the same as spotlessCheck.
  * Shell equivalence strips comments with this file's own quote-aware lexer, not a real shell
    parser; an unterminated quote or heredoc is reported as a failure rather than passed.
  * Judgement rules (should this comment exist, does it restate the code, imperative mood) are
    review-only; nothing here can check them.
"""

import argparse
import ast
import bisect
import fnmatch
import io
import os
import posixpath
import re
import subprocess
import sys
import tokenize
from collections import Counter, namedtuple
from dataclasses import dataclass, field

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

POLICY_REF = "docs/CODE_STYLE.md rule 14"

MIN_PROSE_FOR_SUMMARY_RULE = 4
MAX_SUMMARY_LINES = 2
MAX_NARRATION_LINES = 8

# GSD and vendored-skill trees; their content is not this repo's own comments.
EXCLUDED_PREFIXES = (".planning/", ".claude/", ".agents/", "docs/raw/", "docs/wiki/", ".dev/")
# Third-party files that carry their own comments.
VENDORED_FILES = ("gradlew", "gradlew.bat", "gradle/verification-metadata.xml")
VENDORED_PREFIXES = ("gradle/wrapper/",)
# Generated: only the hand-written header above the first `---` is in scope.
GENERATED_FILES = ("k8s/flux-system/gotk-components.yaml",)
# Flyway CRC32 covers comment lines; see Decisions.
FROZEN_PREFIX = "src/main/resources/db/migration/"
# ConfigMap content hash; see Decisions.
ROLLOUT_GATED_GLOBS = ("k8s/data/postgres/init/*.sh",)

REQUIREMENT_PREFIXES = (
    "ACTLOG", "API", "CI", "EVENT", "FULL", "GAP", "HARDEN", "INFRA", "KAFKA", "LOCK",
    "MOVE", "NONPROD", "READ", "RELY", "RESET", "SCHEMA", "TEST",
)  # fmt: skip

# Order matters: an artifact name swallows the quick-task id inside it before that id is counted.
GATED_PATTERNS = (
    (
        "artifact-name",
        re.compile(
            r"\b[\w.]+(?:-[\w.]+)*-(?:SUMMARY|PLAN|CONTEXT|RESEARCH|VERIFICATION|MEASUREMENTS|REVIEW|UAT|PATTERNS|VALIDATION|SPEC)\.md\b"
        ),
    ),
    ("decision-id", re.compile(r"\bD-\d{2}\b")),
    ("threat-id", re.compile(r"\bT-\d{2,6}-\d{2}\b")),
    ("quick-task-id", re.compile(r"\b\d{6}-(?=[a-z0-9]*[a-z])[a-z0-9]{3}\b")),
    ("phase-n", re.compile(r"\bPhase \d+\b")),
    ("plan-number", re.compile(r"\bplans? \d{2}(?:[-/]\d{2})+\b")),
    ("requirement-id", re.compile(r"\b(?:%s)-\d{2}\b" % "|".join(REQUIREMENT_PREFIXES))),
    ("epic-n", re.compile(r"\bEpic \d+\b")),
)
BARE_PLAN_RE = re.compile(r"(?<![\d\-.])\d{2}-\d{2}(?![\d\-])")
TASK_N_RE = re.compile(r"\bTask \d+\b")
URL_RE = re.compile(r"https?://\S+")
PATH_TOKEN_RE = re.compile(r"(?:[\w.\-]+/)+[\w.\-]*\w")
PATH_WITH_EXT_RE = re.compile(r"(?:[\w.\-]+/)+[\w.\-]+\.[A-Za-z]{1,5}\b")
TODO_RE = re.compile(r"\b(TODO|FIXME|XXX|HACK)\b")
TODO_OK_RE = re.compile(r"\b(?:TODO|FIXME|XXX|HACK): (\S+) - \S")
MARKER_RE = re.compile(
    r"^(?:<p>\s*)?(?:decisions|known holes|why this is the way it is)\b\s*(?::|[-─═—=]{2,})", re.I
)
TAG_RE = re.compile(r"^@\w+")
NONPROSE_TAG_RE = re.compile(r"^@(?:param|return|throws)\b")
RULE_LINE_RE = re.compile(r"^[\s\-─═=—_*~#+]+$")
AAA_RE = re.compile(r"^(?:arrange|act|assert)\b")
MEASURED_RE = re.compile(r"\b(?:MEASURED|PROVISIONAL)\b")

# Lines whose exact text is read by a tool; never edited, never linted.
FUNCTIONAL_RES = (
    re.compile(r"\$imagepolicy"),
    re.compile(r"shellcheck\s+(?:disable|source|shell)="),
    re.compile(r"\bnoqa\b"),
    re.compile(r"\bnosemgrep\b"),
    re.compile(r"gitleaks:allow"),
    re.compile(r"spotless:(?:off|on)\b"),
    re.compile(r"\btype:\s*ignore\b"),
    re.compile(r"\bpragma:"),
    re.compile(r"\bnosec\b"),
)
DOCKERFILE_DIRECTIVE_RE = re.compile(r"^(?:syntax|escape|check)\s*=")


Scope = namedtuple("Scope", "family kind exempt")


def classify(path):
    """Return the Scope of a repo-relative path, or None when it is out of scope."""
    if path.startswith(EXCLUDED_PREFIXES):
        return None
    if path in VENDORED_FILES or path.startswith(VENDORED_PREFIXES):
        return None
    name = posixpath.basename(path)
    ext = posixpath.splitext(name)[1]
    if ext == ".java":
        family, kind = ("java-test" if path.startswith("src/test/") else "java-main"), "java"
    elif ext == ".gradle":
        family, kind = "gradle", "groovy"
    elif ext == ".sh" or path == ".githooks/pre-commit":
        family, kind = "sh", "shell"
    elif ext == ".properties":
        family, kind = "properties", "properties"
    elif ext == ".sql":
        family, kind = "sql", "sql"
    elif ext == ".yml":
        family, kind = "yml", "yaml"
    elif ext == ".yaml":
        family, kind = "yaml", "yaml"
    elif ext == ".py":
        family, kind = "other", "python"
    elif ext == ".toml":
        family, kind = "other", "toml"
    elif (
        name == "Dockerfile"
        or (name.startswith(".env") and name.endswith(".example"))
        or name in (".dockerignore", ".gitignore", ".gitattributes", "lombok.config")
        or ext in (".conf", ".service", ".tsv")
    ):
        family, kind = "other", "line"
    else:
        return None
    exempt = None
    if path.startswith(FROZEN_PREFIX):
        exempt = "FROZEN"
    elif any(fnmatch.fnmatchcase(path, g) for g in ROLLOUT_GATED_GLOBS):
        exempt = "ROLLOUT_GATED"
    return Scope(family, kind, exempt)


# ------------------------------------------------------------------ repo access


def _git(*args, check=True):
    res = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, check=False)
    if check and res.returncode != 0:
        raise RuntimeError("git %s failed: %s" % (" ".join(args), res.stderr.decode(errors="replace")))
    return res.stdout


def tracked_paths(ref=None):
    if ref:
        out = _git("ls-tree", "-r", "--name-only", "-z", ref)
    else:
        out = _git("ls-files", "-z")
    return [p for p in out.decode().split("\0") if p]


def in_scope_paths(ref=None):
    return [p for p in tracked_paths(ref) if classify(p)]


def read_text(path, ref=None):
    if ref:
        res = subprocess.run(["git", "show", "%s:%s" % (ref, path)], cwd=ROOT, capture_output=True)
        if res.returncode != 0:
            return None
        data = res.stdout
    else:
        try:
            with open(os.path.join(ROOT, path), "rb") as fh:
                data = fh.read()
        except OSError:
            return None
    return data.decode("utf-8", errors="replace").replace("\r\n", "\n")


class View:
    """Answers whether a repo-relative path exists, at the working tree, a ref or in memory."""

    def __init__(self, paths=None, ref=None):
        if ref:
            paths = set(tracked_paths(ref))
        self.paths = None if paths is None else set(paths)
        self.dirs = set()
        for p in self.paths or ():
            parts = p.split("/")
            for i in range(1, len(parts)):
                self.dirs.add("/".join(parts[:i]))

    def exists(self, p):
        p = p.rstrip("/")
        if self.paths is not None:
            return p in self.paths or p in self.dirs
        return os.path.exists(os.path.join(ROOT, p))

    def exists_near(self, p, relpath):
        if self.exists(p):
            return True
        here = posixpath.dirname(relpath)
        return bool(here) and self.exists(posixpath.normpath(posixpath.join(here, p)))


def resolve_paths(args, ref=None):
    """Filter the tree to in-scope files under the given files or directories."""
    everything = in_scope_paths(ref)
    if not args:
        return everything
    wanted = []
    seen = set()
    for arg in args:
        rel = os.path.relpath(os.path.abspath(arg), ROOT).replace(os.sep, "/")
        matches = [p for p in everything if p == rel or p.startswith(rel.rstrip("/") + "/")]
        if not matches and not ref and os.path.isfile(os.path.join(ROOT, rel)) and classify(rel):
            matches = [rel]
        for p in matches:
            if p not in seen:
                seen.add(p)
                wanted.append(p)
    return wanted


# ------------------------------------------------------------------ extractors


@dataclass
class Ev:
    """One comment event: source lines [start, end], their marker-stripped text, mergeability."""

    start: int
    end: int
    lines: list
    full: bool
    merge: bool


def _line_index(text):
    return [0] + [i + 1 for i, c in enumerate(text) if c == "\n"]


_CODE_TOKEN_RE = re.compile(r"[A-Za-z_$][\w$]*|\d[\w.]*|\S")


def _block_lines(raw, first_line):
    parts = raw.split("\n")
    out = []
    for k, part in enumerate(parts):
        s = part.strip()
        if k == 0:
            s = s[2:]
            if s.startswith("*"):
                s = s[1:]
        elif s.startswith("*") and not s.startswith("*/"):
            s = s[1:]
        if k == len(parts) - 1 and s.rstrip().endswith("*/"):
            s = s.rstrip()[:-2]
        out.append((first_line + k, s.strip()))
    return out


def lex_cstyle(text, groovy=False):
    """Return (events, tokens) for Java or Groovy; tokens exclude comments and whitespace."""
    starts = _line_index(text)
    events, tokens = [], []
    i, n = 0, len(text)

    def lineno(idx):
        return bisect.bisect_right(starts, idx)

    while i < n:
        c = text[i]
        if text.startswith("//", i):
            eol = text.find("\n", i)
            eol = n if eol < 0 else eol
            ln = lineno(i)
            raw = text[i + 2 : eol]
            if raw.startswith("/"):
                raw = raw[1:]
            full = text[starts[ln - 1] : i].strip() == ""
            events.append(Ev(ln, ln, [(ln, raw.strip())], full, True))
            i = eol
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            ln = lineno(i)
            end_ln = lineno(end - 1)
            eol = text.find("\n", end)
            eol = n if eol < 0 else eol
            full = text[starts[ln - 1] : i].strip() == "" and text[end:eol].strip() == ""
            events.append(Ev(ln, end_ln, _block_lines(text[i:end], ln), full, False))
            i = end
        elif text.startswith('"""', i) or (groovy and text.startswith("'''", i)):
            q = text[i : i + 3]
            j = i + 3
            while j < n and not text.startswith(q, j):
                j += 2 if text[j] == "\\" else 1
            j = min(j + 3, n)
            tokens.append(("lit", text[i:j]))
            i = j
        elif c in "\"'":
            j = i + 1
            while j < n and text[j] != c and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            tokens.append(("lit", text[i:j]))
            i = j
        elif c.isspace():
            i += 1
        else:
            m = _CODE_TOKEN_RE.match(text, i)
            tokens.append(("tok", m.group(0)))
            i = m.end()
    return events, tokens


def lex_sql(text):
    starts = _line_index(text)
    events, tokens = [], []
    i, n = 0, len(text)

    def lineno(idx):
        return bisect.bisect_right(starts, idx)

    while i < n:
        c = text[i]
        if text.startswith("--", i):
            eol = text.find("\n", i)
            eol = n if eol < 0 else eol
            ln = lineno(i)
            full = text[starts[ln - 1] : i].strip() == ""
            events.append(Ev(ln, ln, [(ln, text[i + 2 : eol].strip())], full, True))
            i = eol
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            ln = lineno(i)
            eol = text.find("\n", end)
            eol = n if eol < 0 else eol
            full = text[starts[ln - 1] : i].strip() == "" and text[end:eol].strip() == ""
            events.append(Ev(ln, lineno(end - 1), _block_lines(text[i:end], ln), full, False))
            i = end
        elif c in "'\"":
            j = i + 1
            while j < n:
                if text[j] == c:
                    if text[j + 1 : j + 2] == c:
                        j += 2
                        continue
                    break
                j += 1
            j = min(j + 1, n)
            tokens.append(("lit", text[i:j]))
            i = j
        elif c == "$":
            m = re.compile(r"\$(?:[A-Za-z_]\w*)?\$").match(text, i)
            end = text.find(m.group(0), m.end()) if m else -1
            if m and end >= 0:
                j = end + len(m.group(0))
                tokens.append(("lit", text[i:j]))
                i = j
            else:
                tokens.append(("tok", c))
                i += 1
        elif c.isspace():
            i += 1
        else:
            m = _CODE_TOKEN_RE.match(text, i)
            tokens.append(("tok", m.group(0)))
            i = m.end()
    return events, tokens


_HEREDOC_RE = re.compile(r"<<(-?)\s*(?:([\"'])([A-Za-z_]\w*)\2|\\?([A-Za-z_]\w*))")


def lex_shell(text):
    """Return (events, code_lines, warnings); heredoc bodies and quoted text are content."""
    events, code_lines, warnings = [], [], []
    sq = dq = False
    queue = []  # pending heredocs: (terminator, strip_tabs)
    for ln, line in enumerate(text.split("\n"), 1):
        if queue:
            term, strip_tabs = queue[0]
            if (line.lstrip("\t") if strip_tabs else line) == term:
                queue.pop(0)
            code_lines.append((ln, line))
            continue
        pending = []
        comment_at = None
        i = 0
        while i < len(line):
            c = line[i]
            if sq:
                sq = c != "'"
            elif dq:
                if c == "\\":
                    i += 1
                elif c == '"':
                    dq = False
            elif c == "\\":
                i += 1
            elif c == "'":
                sq = True
            elif c == '"':
                dq = True
            elif c == "#" and (i == 0 or line[i - 1] in " \t;&|"):
                comment_at = i
                break
            elif c == "<" and line.startswith("<<", i) and not line.startswith("<<<", i):
                m = _HEREDOC_RE.match(line, i)
                if m:
                    pending.append((m.group(3) or m.group(4), m.group(1) == "-"))
                    i = m.end() - 1
            i += 1
        if comment_at is not None:
            full = line[:comment_at].strip() == ""
            events.append(Ev(ln, ln, [(ln, line[comment_at + 1 :].strip())], full, True))
            code_lines.append((ln, line[:comment_at].rstrip()))
        else:
            code_lines.append((ln, line.rstrip()))
        queue.extend(pending)
    if sq or dq or queue:
        warnings.append("shell lexer ended inside a quote or heredoc")
    return events, code_lines, warnings


_BLOCK_START_RE = re.compile(
    r"^(?P<lead>\s*)(?P<dashes>(?:-\s+)*)(?:(?P<key>[^\s#][^#]*?):\s+|)(?:[&!]\S*\s+)*[|>][+\-0-9]{0,2}\s*$"
)


def lex_yaml(text, workflow=False):
    """Detect comments without a YAML parser: quoted scalars and block-scalar bodies are content."""
    events = []
    in_block = False
    owner = 0
    in_dq = in_sq = False
    for ln, raw in enumerate(text.split("\n"), 1):
        line = raw.rstrip("\r")
        stripped = line.strip()
        indent = len(line) - len(line.lstrip(" "))
        if not in_dq and not in_sq and line.startswith(("---", "...")) and not stripped[3:].strip("- "):
            in_block = False
        if in_block:
            if stripped == "" or indent > owner:
                if workflow and stripped.startswith("#"):
                    events.append(Ev(ln, ln, [(ln, stripped[1:].strip())], True, True))
                continue
            in_block = False
        code_end = len(line)
        prev = None
        i = 0
        while i < len(line):
            c = line[i]
            if in_dq:
                if c == "\\":
                    i += 1
                elif c == '"':
                    in_dq = False
                    prev = '"'
            elif in_sq:
                if c == "'":
                    if line[i + 1 : i + 2] == "'":
                        i += 1
                    else:
                        in_sq = False
                        prev = "'"
            elif c == "#" and (i == 0 or line[i - 1] in " \t"):
                events.append(Ev(ln, ln, [(ln, line[i + 1 :].strip())], line[:i].strip() == "", True))
                code_end = i
                break
            else:
                if c in "\"'" and prev in (None, ":", "-", ",", "[", "{", "?") and (
                    i == 0 or line[i - 1] in " \t[{,"
                ):
                    in_dq, in_sq = c == '"', c == "'"
                if c not in " \t":
                    prev = c
            i += 1
        if not in_dq and not in_sq:
            m = _BLOCK_START_RE.match(line[:code_end])
            if m and (m.group("key") or m.group("dashes")):
                in_block = True
                lead = len(m.group("lead"))
                owner = lead + len(m.group("dashes")) if m.group("key") else lead
    return events


def lex_toml(text):
    events = []
    starts = _line_index(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith('"""', i) or text.startswith("'''", i):
            q = text[i : i + 3]
            j = i + 3
            while j < n and not text.startswith(q, j):
                j += 2 if (q[0] == '"' and text[j] == "\\") else 1
            i = min(j + 3, n)
        elif c in "\"'":
            j = i + 1
            while j < n and text[j] != c and text[j] != "\n":
                j += 2 if (c == '"' and text[j] == "\\") else 1
            i = min(j + 1, n)
        elif c == "#":
            eol = text.find("\n", i)
            eol = n if eol < 0 else eol
            ln = bisect.bisect_right(starts, i)
            events.append(Ev(ln, ln, [(ln, text[i + 1 : eol].strip())], text[starts[ln - 1] : i].strip() == "", True))
            i = eol
        else:
            i += 1
    return events


def lex_python(text):
    events = []
    try:
        for tok in tokenize.generate_tokens(io.StringIO(text).readline):
            if tok.type == tokenize.COMMENT:
                ln, col = tok.start
                events.append(Ev(ln, ln, [(ln, tok.string[1:].strip())], tok.line[:col].strip() == "", True))
        tree = ast.parse(text)
    except (tokenize.TokenError, SyntaxError, IndentationError):
        return events
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            body = node.body
            if body and isinstance(body[0], ast.Expr) and isinstance(getattr(body[0], "value", None), ast.Constant):
                value = body[0].value.value
                if isinstance(value, str):
                    first = body[0].lineno
                    lines = [(first + k, s.strip()) for k, s in enumerate(value.split("\n"))]
                    events.append(Ev(first, first + len(lines) - 1, lines, True, False))
    events.sort(key=lambda e: e.start)
    return events


def lex_linewise(text, markers):
    events = []
    for ln, line in enumerate(text.split("\n"), 1):
        s = line.lstrip()
        if s and s[0] in markers:
            events.append(Ev(ln, ln, [(ln, s[1:].strip())], True, True))
    return events


def extract(kind, text, workflow=False):
    if kind in ("java", "groovy"):
        return lex_cstyle(text, kind == "groovy")[0]
    if kind == "sql":
        return lex_sql(text)[0]
    if kind == "shell":
        return lex_shell(text)[0]
    if kind == "yaml":
        return lex_yaml(text, workflow)
    if kind == "toml":
        return lex_toml(text)
    if kind == "python":
        return lex_python(text)
    if kind == "properties":
        return lex_linewise(text, "#!")
    return lex_linewise(text, "#")


def yaml_scanner_comment_lines(text, yaml):
    """Lines holding a comment according to yaml.scan: an uncovered `#` after whitespace."""
    covered = bytearray(len(text) + 1)
    for tok in yaml.scan(text):
        for k in range(tok.start_mark.index, tok.end_mark.index):
            covered[k] = 1
    lines = set()
    offset = 0
    for ln, line in enumerate(text.split("\n"), 1):
        for col, ch in enumerate(line):
            if ch == "#" and not covered[offset + col] and (col == 0 or line[col - 1] in " \t"):
                lines.add(ln)
                break
        offset += len(line) + 1
    return lines


# ------------------------------------------------------------------ analysis


@dataclass
class Block:
    start: int
    end: int
    lines: list  # (lineno, text)

    def trimmed(self):
        lines = list(self.lines)
        while lines and lines[0][1] == "":
            lines.pop(0)
        while lines and lines[-1][1] == "":
            lines.pop()
        return lines


@dataclass
class FileInfo:
    blocks: list = field(default_factory=list)
    comment_lines: int = 0
    total_lines: int = 0
    functional: list = field(default_factory=list)
    keywords: Counter = field(default_factory=Counter)
    aaa: int = 0
    warnings: list = field(default_factory=list)


def is_functional(path, lineno, text):
    if lineno == 1 and text.startswith("!"):
        return True
    if posixpath.basename(path) == "Dockerfile" and DOCKERFILE_DIRECTIVE_RE.match(text):
        return True
    return any(rx.search(text) for rx in FUNCTIONAL_RES)


def analyze(path, text, scope):
    workflow = path.startswith(".github/workflows/") and scope.kind == "yaml"
    events = extract(scope.kind, text, workflow)
    info = FileInfo()
    info.total_lines = text.count("\n") + (0 if text.endswith("\n") or not text else 1)
    if path in GENERATED_FILES:
        cut = next((n for n, l in enumerate(text.split("\n"), 1) if l.strip() == "---"), None)
        if cut is not None:
            events = [e for e in events if e.end <= cut]
            info.total_lines = cut
    if scope.kind == "shell":
        info.warnings = lex_shell(text)[2]
    last = None
    comment_lines = set()
    for ev in events:
        if ev.full:
            comment_lines.update(range(ev.start, ev.end + 1))
        keep = []
        for ln, t in ev.lines:
            if MEASURED_RE.search(t):
                info.keywords[MEASURED_RE.search(t).group(0)] += 1
            if is_functional(path, ln, t):
                info.functional.append(t)
                continue
            if scope.family == "java-test" and ev.merge and AAA_RE.match(t.lower()):
                info.aaa += 1
                continue
            keep.append((ln, t))
        if not keep:
            last = None
            continue
        if last is not None and ev.merge and ev.full and last[1] and last[0].end == ev.start - 1:
            last[0].lines.extend(keep)
            last[0].end = ev.end
            continue
        block = Block(ev.start, ev.end, keep)
        info.blocks.append(block)
        last = (block, ev.merge and ev.full)
    info.comment_lines = len(comment_lines)
    return info


# ------------------------------------------------------------------ rules


Violation = namedtuple("Violation", "rule path line detail")


@dataclass
class LintResult:
    violations: list = field(default_factory=list)
    suspects: list = field(default_factory=list)  # (kind, line, detail)
    reports: list = field(default_factory=list)  # (Block, prose_count, flags)
    info: FileInfo = None
    id_hits: int = 0


def is_prose(t):
    return bool(t) and not RULE_LINE_RE.match(t) and not NONPROSE_TAG_RE.match(t) and t != "<p>"


def mask_paths(text, view, relpath):
    text = URL_RE.sub(lambda m: " " * len(m.group(0)), text)

    def repl(m):
        tok = m.group(0).rstrip(".-")
        return " " * len(m.group(0)) if view.exists_near(tok, relpath) else m.group(0)

    return PATH_TOKEN_RE.sub(repl, text)


def gated_hits(text, view, relpath):
    masked = mask_paths(text, view, relpath)
    hits = []
    for name, rx in GATED_PATTERNS:
        for m in rx.finditer(masked):
            hits.append((name, m.group(0)))
        masked = rx.sub(lambda m: " " * len(m.group(0)), masked)
    return hits, masked


def suspect_hits(text, view, relpath, masked):
    out = []
    for m in BARE_PLAN_RE.finditer(masked):
        out.append(("bare-plan-number", m.group(0)))
    for m in TASK_N_RE.finditer(masked):
        out.append(("task-n", m.group(0)))
    plain = URL_RE.sub(" ", text)
    for m in PATH_WITH_EXT_RE.finditer(plain):
        if not view.exists_near(m.group(0), relpath):
            out.append(("dangling-path", m.group(0)))
    return out


def lint(path, text, view):
    scope = classify(path)
    result = LintResult()
    if scope is None or scope.exempt:
        return result
    info = analyze(path, text, scope)
    result.info = info
    for block in info.blocks:
        flags = []
        for ln, t in block.lines:
            hits, masked = gated_hits(t, view, path)
            for name, tok in hits:
                result.violations.append(Violation("planning-id", path, ln, "%s %r in comment" % (name, tok)))
                result.id_hits += 1
                flags.append("planning-id")
            for kind, tok in suspect_hits(t, view, path, masked):
                result.suspects.append((kind, ln, tok))
                flags.append("suspect:" + kind)
            for m in TODO_RE.finditer(t):
                ok = TODO_OK_RE.match(t, m.start())
                target = ok.group(1).rstrip(".,;:)") if ok else None
                good = bool(ok) and (
                    re.match(r"https?://\S+$", target) or re.match(r"#\d+$", target) or view.exists_near(target, path)
                )
                if not good:
                    result.violations.append(
                        Violation(
                            "tracked-todo",
                            path,
                            ln,
                            "%s must read '%s: <URL | #N | existing repo path> - <what>'" % (m.group(1), m.group(1)),
                        )
                    )
                    flags.append("tracked-todo")
        lines = block.trimmed()
        prose = [t for _, t in lines if is_prose(t)]
        if len(prose) >= MIN_PROSE_FOR_SUMMARY_RULE and lines:
            first_para = 0
            for idx, (_, t) in enumerate(lines):
                if idx > 0 and (t == "" or TAG_RE.match(t) or t.startswith("<p>")):
                    break
                first_para += is_prose(t)
            if first_para > MAX_SUMMARY_LINES:
                result.violations.append(
                    Violation(
                        "summary-first",
                        path,
                        lines[0][0],
                        "first paragraph is %d lines, summary must be at most %d then a blank line"
                        % (first_para, MAX_SUMMARY_LINES),
                    )
                )
                flags.append("summary-first")
        narration = 0
        marker_seen = False
        for _, t in lines:
            if MARKER_RE.match(t):
                marker_seen = True
                break
            narration += is_prose(t)
        if narration > MAX_NARRATION_LINES:
            result.violations.append(
                Violation(
                    "segregated-narration",
                    path,
                    lines[0][0],
                    "%d prose lines before any Decisions:/Known holes:/Why this is the way it is: marker, "
                    "at most %d allowed" % (narration, MAX_NARRATION_LINES),
                )
            )
            flags.append("segregated-narration")
        result.reports.append((block, len(prose), flags))
    return result


def format_violation(v):
    return "FAIL: %s: %s:%d: %s (%s)" % (v.rule, v.path, v.line, v.detail, POLICY_REF)


# ------------------------------------------------------------------ equivalence


def _strip_hash_lines(obj):
    if isinstance(obj, str):
        if "\n" in obj:
            return "\n".join(l for l in obj.split("\n") if not l.lstrip().startswith("#"))
        return obj
    if isinstance(obj, list):
        return [_strip_hash_lines(o) for o in obj]
    if isinstance(obj, dict):
        return {_strip_hash_lines(k): _strip_hash_lines(v) for k, v in obj.items()}
    return obj


def _strip_docstrings(tree):
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            body = node.body
            if body and isinstance(body[0], ast.Expr) and isinstance(getattr(body[0], "value", None), ast.Constant):
                if isinstance(body[0].value.value, str):
                    node.body = body[1:] or [ast.Pass()]
    return tree


def code_repr(path, scope, text):
    kind = scope.kind
    if kind in ("java", "groovy"):
        return lex_cstyle(text, kind == "groovy")[1]
    if kind == "sql":
        return lex_sql(text)[1]
    if kind == "shell":
        return [l.rstrip() for _, l in lex_shell(text)[1] if l.strip()]
    if kind == "python":
        return ast.dump(_strip_docstrings(ast.parse(text)))
    if kind == "yaml":
        import yaml  # lazy: only equiv needs it

        docs = list(yaml.safe_load_all(text))
        return _strip_hash_lines(docs) if path.startswith(".github/workflows/") else docs
    if kind == "toml":
        import tomllib  # lazy: only equiv needs it

        return tomllib.loads(text)
    markers = "#!" if kind == "properties" else "#"
    return [l.rstrip() for l in text.split("\n") if l.strip() and l.lstrip()[0] not in markers]


def equiv_problems(path, old, new, allow=False):
    """Return the reasons old and new differ in code; empty means comment-only."""
    if path.startswith(FROZEN_PREFIX):
        return ["FROZEN migration changed: Flyway checksums comment lines, so any byte change breaks boot"] if old != new else []
    scope = classify(path)
    if scope is None or allow:
        return []
    problems = []
    old_info, new_info = analyze(path, old, scope), analyze(path, new, scope)
    if sorted(old_info.functional) != sorted(new_info.functional):
        problems.append("functional marker lines differ")
    if old_info.keywords != new_info.keywords:
        problems.append("MEASURED/PROVISIONAL keyword count differs")
    if old_info.aaa != new_info.aaa:
        problems.append("arrange/act/assert marker count %d -> %d" % (old_info.aaa, new_info.aaa))
    if new_info.warnings:
        problems.append("cannot verify: " + "; ".join(new_info.warnings))
    try:
        if code_repr(path, scope, old) != code_repr(path, scope, new):
            problems.append("code differs (not a comment-only change)")
    except ImportError as err:
        problems.append("cannot compare: %s (install PyYAML to compare YAML files)" % err)
    except Exception as err:  # parser errors name the file; report, never pass
        problems.append("cannot compare: %s: %s" % (type(err).__name__, err))
    return problems


# ------------------------------------------------------------------ commands


def cmd_files(args):
    for p in resolve_paths(args.paths):
        s = classify(p)
        print("%s\t%s\t%s\t%s" % (p, s.family, s.kind, s.exempt or "-"))
    return 0


def cmd_check(paths):
    files = resolve_paths(paths)
    if not files:
        print("FAIL: no-files: the scan found zero in-scope files (%s)" % POLICY_REF)
        return 1
    view = View()
    violations = []
    blocks = 0
    linted = 0
    for p in files:
        scope = classify(p)
        text = read_text(p)
        if text is None or scope.exempt:
            continue
        linted += 1
        res = lint(p, text, view)
        blocks += len(res.reports)
        violations.extend(res.violations)
    for v in violations:
        print(format_violation(v))
    if violations:
        print("%d violation(s) in %d files" % (len(violations), linted))
        return 1
    print("OK: %d files, %d comment blocks" % (linted, blocks))
    return 0


def cmd_report(args):
    view = View()
    for p in resolve_paths(args.paths):
        text = read_text(p)
        scope = classify(p)
        if text is None or scope.exempt:
            continue
        for block, prose, flags in lint(p, text, view).reports:
            if flags or args.all:
                first = next((t for _, t in block.lines if t), "")
                print("%s:%d-%d %s prose=%d flags=[%s]\n    %s" % (p, block.start, block.end, scope.family, prose, ",".join(sorted(set(flags))), first[:100]))
    return 0


def cmd_stats(args):
    ref = args.ref
    view = View(ref=ref)
    rows = {}
    for p in resolve_paths(args.paths, ref):
        scope = classify(p)
        if args.family and scope.family != args.family:
            continue
        text = read_text(p, ref)
        if text is None:
            continue
        row = rows.setdefault(scope.family, Counter())
        row["files"] += 1
        res = lint(p, text, view) if not scope.exempt else None
        info = res.info if res else analyze(p, text, scope)
        row["lines"] += info.total_lines
        row["comment"] += info.comment_lines
        row["aaa"] += info.aaa
        if res is None:
            hits = sum(len(gated_hits(t, view, p)[0]) for b in info.blocks for _, t in b.lines)
            row["ids_exempt"] += hits
            continue
        row["ids"] += res.id_hits
        row["suspects"] += len(res.suspects)
        row["long"] += sum(1 for _, prose, _ in res.reports if prose > MAX_NARRATION_LINES)
    cols = ("files", "lines", "comment", "pct", "long", "ids", "ids_exempt", "suspects", "aaa")
    print("ref=%s  comment = lines that are only comment; long = blocks over %d prose lines; ids = gated planning-id hits outside exempt files" % (ref or "working tree", MAX_NARRATION_LINES))
    print("%-12s" % "family" + "".join("%12s" % c for c in cols))
    total = Counter()
    for fam in sorted(rows):
        row = rows[fam]
        row["pct"] = 0
        total.update(row)
        print_row(fam, row, cols)
    if len(rows) > 1:
        print_row("TOTAL", total, cols)
    return 0


def print_row(name, row, cols):
    pct = "%.1f" % (100.0 * row["comment"] / row["lines"]) if row["lines"] else "-"
    cells = [pct if c == "pct" else str(row[c]) for c in cols]
    print("%-12s" % name + "".join("%12s" % c for c in cells))


def cmd_equiv(args):
    base = args.base
    out = _git("diff", "--name-status", "--no-renames", "-z", base).decode().split("\0")
    entries = []
    for k in range(0, len(out) - 1, 2):
        entries.append((out[k], out[k + 1]))
    wanted = set(resolve_paths(args.paths)) | {p for s, p in entries if s == "D"} if args.paths else None
    allow = set(args.allow or [])
    failed = 0
    checked = new = allowed = 0
    for status, path in entries:
        if wanted is not None and path not in wanted and not path.startswith(FROZEN_PREFIX):
            continue
        if path.startswith(FROZEN_PREFIX):
            print("FAIL: equiv: %s: FROZEN migration changed (%s); Flyway checksums comment lines" % (path, status))
            failed += 1
            continue
        scope = classify(path)
        if scope is None:
            continue
        if status == "D":
            print("NOTE: %s deleted since %s" % (path, base))
            continue
        if status == "A":
            print("NOTE: %s is new since %s, nothing to compare" % (path, base))
            new += 1
            continue
        old_text, new_text = read_text(path, base), read_text(path)
        if old_text is None or new_text is None:
            print("FAIL: equiv: %s: cannot read both versions" % path)
            failed += 1
            continue
        if path in allow:
            allowed += 1
            continue
        checked += 1
        for problem in equiv_problems(path, old_text, new_text):
            print("FAIL: equiv: %s: %s" % (path, problem))
            failed += 1
    if failed:
        return 1
    print("OK: equiv vs %s: %d changed in-scope files equivalent, %d new, %d allow-listed" % (base, checked, new, allowed))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="Comment policy lint and comment-only equivalence proof")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("files", help="list in-scope files with family and exemption")
    p.add_argument("paths", nargs="*")
    p = sub.add_parser("stats", help="per-family comment statistics")
    p.add_argument("--ref")
    p.add_argument("--family")
    p.add_argument("paths", nargs="*")
    p = sub.add_parser("report", help="worklist of comment blocks and their flags")
    p.add_argument("--all", action="store_true")
    p.add_argument("paths", nargs="*")
    p = sub.add_parser("check", help="exit 1 on any policy violation")
    p.add_argument("paths", nargs="*")
    p = sub.add_parser("equiv", help="prove changed files differ from BASE only in comments")
    p.add_argument("--base", required=True)
    p.add_argument("--allow", action="append")
    p.add_argument("paths", nargs="*")
    args = ap.parse_args(argv)
    if args.cmd == "files":
        return cmd_files(args)
    if args.cmd == "stats":
        return cmd_stats(args)
    if args.cmd == "report":
        return cmd_report(args)
    if args.cmd == "check":
        return cmd_check(args.paths)
    return cmd_equiv(args)


if __name__ == "__main__":
    sys.exit(main())
