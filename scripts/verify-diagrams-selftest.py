#!/usr/bin/env python3
"""Self-test for scripts/verify-diagrams.py: every rule can fire, and stays silent on its lookalike.

A rule that never fires on a planted violation is invisible against a tree that already satisfies
it, so the gate stays green while protecting nothing. Each case plants one violation (or one
lookalike that must be ignored) in an in-memory fixture tree and asserts the verdict. Nothing here
touches the real repository except the final CLI-shape checks, which only pass fixtures through
main().
"""

import importlib.util
import os
import struct
import sys

_GATE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "verify-diagrams.py")
_spec = importlib.util.spec_from_file_location("verify_diagrams", _GATE_PATH)
_gate = importlib.util.module_from_spec(_spec)
sys.modules["verify_diagrams"] = _gate
_spec.loader.exec_module(_gate)

INIT = _gate.INIT_LINE
MANIFEST = "docs/diagrams/render-manifest.tsv"
CONFIG = "docs/diagrams/mermaid-config.json"


def png(width, height):
    """The first 33 bytes of a PNG: signature plus IHDR, which is all the gate reads."""
    return (
        b"\x89PNG\r\n\x1a\n"
        + struct.pack(">I", 13)
        + b"IHDR"
        + struct.pack(">II", width, height)
        + b"\x08\x06\x00\x00\x00"
    )


def flow(body="  A --> B\n", keyword="flowchart TB", init=INIT):
    return (init + "\n" if init is not None else "") + keyword + "\n" + body


def img(width, src="docs/diagrams/process/a.png"):
    return '<img src="%s" width="%s" alt="Flowchart: a">\n' % (src, width)


def clean_files():
    return {
        MANIFEST: "# header\nprocess/a\t2\n",
        CONFIG: "{}\n",
        "docs/diagrams/process/a.mmd": flow(),
        "docs/diagrams/process/a.png": png(1600, 1000),
        "README.md": img(800),
    }


def build(files):
    return _gate.Tree({p: (c if isinstance(c, bytes) else c.encode()) for p, c in files.items() if c is not None})


def with_files(extra=None, drop=()):
    files = clean_files()
    for path in drop:
        files.pop(path, None)
    files.update(extra or {})
    return build(files)


def rules(tree, only=None):
    return sorted({v.rule for v in _gate.check(tree, only)})


def messages(tree, rule):
    return [v for v in _gate.check(tree) if v.rule == rule]


# ---------------------------------------------------------------- clean tree


def test_clean_fixture_is_clean_and_exits_zero():
    tree = with_files()
    assert rules(tree) == [], rules(tree)
    assert _gate.main(["check"], tree=tree) == 0


# ---------------------------------------------------------------- inventory


def test_outside_view_folder_fires_on_root_and_unknown_folder():
    tree = with_files(
        {
            "docs/diagrams/loose.mmd": flow(),
            "docs/diagrams/misc/other.mmd": flow(),
            "docs/diagrams/misc/other.png": png(1000, 800),
            "docs/diagrams/loose.png": png(1000, 800),
        }
    )
    hits = sorted(v.path for v in messages(tree, "outside-view-folder"))
    assert hits == [
        "docs/diagrams/loose.mmd",
        "docs/diagrams/loose.png",
        "docs/diagrams/misc/other.mmd",
        "docs/diagrams/misc/other.png",
    ], hits


def test_outside_view_folder_silent_on_allowed_top_level_files():
    assert "outside-view-folder" not in rules(with_files())


def test_outside_view_folder_fires_on_nested_view_folder():
    tree = with_files({"docs/diagrams/process/deep/x.mmd": flow()})
    assert "outside-view-folder" in rules(tree)


def test_missing_twin_both_directions():
    tree = with_files(
        {
            "docs/diagrams/process/only-src.mmd": flow(),
            "docs/diagrams/process/only-img.png": png(1000, 800),
        }
    )
    hits = sorted(v.path for v in messages(tree, "missing-twin"))
    assert hits == ["docs/diagrams/process/only-img.png", "docs/diagrams/process/only-src.mmd"], hits


def test_manifest_mismatch_diagram_without_row():
    tree = with_files(
        {
            "docs/diagrams/process/b.mmd": flow(),
            "docs/diagrams/process/b.png": png(1000, 800),
        }
    )
    assert any("process/b" in v.message for v in messages(tree, "manifest-mismatch"))


def test_manifest_mismatch_row_without_source():
    tree = with_files({MANIFEST: "# h\nprocess/a\t2\nprocess/ghost\t2\n"})
    assert any("process/ghost" in v.message for v in messages(tree, "manifest-mismatch"))


def test_uniform_scale_fires_on_scale_four():
    tree = with_files({MANIFEST: "# h\nprocess/a\t4\n"})
    assert "uniform-scale" in rules(tree)


def test_bad_name_flags_prefix_suffix_and_case():
    for stem in (
        "architecture-thing",
        "auth-signin",
        "infra-delivery",
        "signin-scenario",
        "pipeline-flowchart",
        "pipeline-sequence",
        "pipeline-diagram",
        "Signin",
        "sign_in",
        "-lead",
    ):
        tree = with_files(
            {
                "docs/diagrams/scenarios/%s.mmd" % stem: flow(),
                "docs/diagrams/scenarios/%s.png" % stem: png(1000, 800),
            }
        )
        assert "bad-name" in rules(tree), stem


def test_bad_name_silent_on_good_names():
    for stem in ("signin", "error-status-split", "inbound-packet-path", "push-to-deploy", "a1"):
        tree = with_files(
            {
                "docs/diagrams/scenarios/%s.mmd" % stem: flow(),
                "docs/diagrams/scenarios/%s.png" % stem: png(1000, 800),
                MANIFEST: "# h\nprocess/a\t2\nscenarios/%s\t2\n" % stem,
            }
        )
        assert "bad-name" not in rules(tree), stem


# ---------------------------------------------------------------- references


def test_dangling_reference_in_link_img_and_code_span():
    for doc in (
        "See [pic](docs/diagrams/process/gone.png).\n",
        '<img src="docs/diagrams/process/gone.png" width="800" alt="x">\n',
        "Source: `docs/diagrams/process/gone.mmd`.\n",
        "See [pic](diagrams/process/gone.png).\n",
    ):
        tree = with_files({"docs/X.md": doc})
        assert "dangling-reference" in rules(tree), doc


def test_dangling_reference_resolves_relative_and_root_paths():
    tree = with_files(
        {
            "docs/X.md": "![x](diagrams/process/a.png) and `docs/diagrams/process/a.mmd`.\n",
            "docs/learning/Y.md": "[src](../diagrams/process/a.mmd)\n",
        }
    )
    assert "dangling-reference" not in rules(tree), [v for v in _gate.check(tree)]


def test_dangling_reference_silent_on_glob_and_wiki():
    tree = with_files(
        {
            "docs/X.md": "Every `docs/diagrams/*.mmd` file.\n",
            "docs/wiki/page.md": "[pic](diagrams/process/gone.png)\n",
            "docs/raw/note.md": "[pic](diagrams/process/gone.png)\n",
            ".planning/x/PLAN.md": "[pic](docs/diagrams/process/gone.png)\n",
        }
    )
    assert "dangling-reference" not in rules(tree)


def test_dangling_reference_silent_on_external_url():
    tree = with_files({"docs/X.md": "[p](https://example.com/diagrams/process/gone.png)\n"})
    assert "dangling-reference" not in rules(tree)


# ---------------------------------------------------------------- legibility


def test_legibility_width_fires_over_limit_and_boundary():
    assert _gate.W_MAX == 1117, _gate.W_MAX
    over = with_files({"docs/diagrams/process/a.png": png(2400, 1000), "README.md": img(1200)})
    assert "legibility-width" in rules(over)
    just_over = with_files({"docs/diagrams/process/a.png": png(2236, 1000), "README.md": img(1118)})
    assert "legibility-width" in rules(just_over)
    at_limit = with_files({"docs/diagrams/process/a.png": png(2234, 1000), "README.md": img(1117)})
    assert "legibility-width" not in rules(at_limit), rules(at_limit)


def test_legibility_height_fires_on_displayed_height():
    tall = with_files({"docs/diagrams/process/a.png": png(1600, 3400), "README.md": img(800)})
    assert "legibility-height" in rules(tall)
    at_limit = with_files({"docs/diagrams/process/a.png": png(1600, 3200), "README.md": img(800)})
    assert "legibility-height" not in rules(at_limit), rules(at_limit)


def test_legibility_height_uses_displayed_not_natural_height():
    # W=1117, H=1700: natural height is over the limit but the image is scaled down to 838 wide.
    scaled = with_files({"docs/diagrams/process/a.png": png(2234, 3400), "README.md": img(1117)})
    assert "legibility-height" not in rules(scaled), rules(scaled)


def test_bad_png_signature_is_reported():
    tree = with_files({"docs/diagrams/process/a.png": b"not a png at all, just bytes......"})
    assert "bad-png" in rules(tree)


# ---------------------------------------------------------------- embeds


def test_embed_width_markdown_image_syntax_fails():
    tree = with_files({"README.md": "![Flowchart: a](docs/diagrams/process/a.png)\n"})
    assert "embed-width" in rules(tree)


def test_embed_width_mismatch_and_tolerance():
    assert "embed-width" in rules(with_files({"README.md": img(802)}))
    assert "embed-width" in rules(with_files({"README.md": img(798)}))
    assert "embed-width" not in rules(with_files({"README.md": img(801)}))
    assert "embed-width" not in rules(with_files({"README.md": img(799)}))


def test_embed_width_missing_width_attribute_fails():
    tree = with_files({"README.md": '<img src="docs/diagrams/process/a.png" alt="x">\n'})
    assert "embed-width" in rules(tree)


def test_embed_width_silent_on_plain_png_link():
    tree = with_files({"README.md": "[PNG](docs/diagrams/process/a.png)\n"})
    assert "embed-width" not in rules(tree)


def test_embed_width_handles_multiline_img_tag():
    tree = with_files({"README.md": '<img\n  src="docs/diagrams/process/a.png"\n  width="500"\n  alt="x">\n'})
    hits = messages(tree, "embed-width")
    assert hits and hits[0].line == 1, hits


# ---------------------------------------------------------------- flowcharts


def test_flowchart_rules_standalone_violations():
    for src in (
        flow(keyword="flowchart TD"),
        flow(keyword="flowchart LR"),
        flow(keyword="graph TB"),
        flow(init=None),
        flow(init=INIT.replace("linear", "basis")),
        flow(init=INIT.replace("15", "20")),
    ):
        tree = with_files({"docs/diagrams/process/a.mmd": src})
        assert "flowchart-rules" in rules(tree), src.splitlines()[:2]


def test_flowchart_rules_silent_on_lookalikes():
    seq = "sequenceDiagram\n  A->>B: hi\n"
    er = "erDiagram\n  A ||--o{ B : has\n"
    inner = flow(body="  subgraph s\n    direction LR\n    A --> B\n  end\n")
    for src in (seq, er, inner):
        tree = with_files({"docs/diagrams/process/a.mmd": src})
        assert "flowchart-rules" not in rules(tree), src


def test_flowchart_rules_inline_fences():
    bad = "```mermaid\nflowchart LR\n  A --> B\n```\n"
    assert "flowchart-rules" in rules(with_files({"docs/learning/z.md": bad}))
    missing_init = "```mermaid\nflowchart TB\n  A --> B\n```\n"
    assert "flowchart-rules" in rules(with_files({"docs/learning/z.md": missing_init}))
    good = "```mermaid\n" + flow() + "```\n"
    assert "flowchart-rules" not in rules(with_files({"docs/learning/z.md": good}))
    er = "```mermaid\nerDiagram\n  A ||--o{ B : has\n```\n"
    assert "flowchart-rules" not in rules(with_files({"docs/learning/z.md": er}))
    seq = "```mermaid\nsequenceDiagram\n  A->>B: x\n```\n"
    assert "flowchart-rules" not in rules(with_files({"docs/learning/z.md": seq}))


def test_flowchart_rules_ignore_fences_in_excluded_docs():
    bad = "```mermaid\nflowchart LR\n  A --> B\n```\n"
    tree = with_files({"docs/wiki/w.md": bad, ".planning/p.md": bad})
    assert "flowchart-rules" not in rules(tree)


# ---------------------------------------------------------------- class-level labels


def test_class_level_label_fires_on_standalone_and_inline():
    for name in (
        "TaskService",
        "BoardController",
        "TaskRepository",
        "BoardMapper",
        "KafkaEventPublisher",
        "ActivityLogConsumer",
        "ActivityLogRecorder",
        "UserAuthenticationProvider",
        "GlobalExceptionHandler",
        "CurrentUserIdResolver",
        "OwnershipVerifierService",
        "JwtFilter",
    ):
        body = '  A["%s"] --> B\n' % name
        assert "class-level-label" in rules(with_files({"docs/diagrams/process/a.mmd": flow(body=body)})), name
        fence = "```mermaid\nsequenceDiagram\n  participant X as %s\n```\n" % name
        assert "class-level-label" in rules(with_files({"docs/learning/z.md": fence})), name


def test_class_level_label_silent_on_lookalikes():
    for text in ("@ControllerAdvice", "kustomize-controller", "Error mapper", "Service", "Filter chain", "Handler"):
        body = '  A["%s"] --> B\n' % text
        tree = with_files({"docs/diagrams/process/a.mmd": flow(body=body)})
        assert "class-level-label" not in rules(tree), text


def test_class_level_label_ignores_comment_lines():
    body = "  %% TaskService note\n  A --> B\n"
    assert "class-level-label" not in rules(with_files({"docs/diagrams/process/a.mmd": flow(body=body)}))


# ---------------------------------------------------------------- scan guards and CLI shape


def test_zero_diagrams_exits_two():
    tree = build({MANIFEST: "# h\n", CONFIG: "{}\n", "README.md": "hello\n"})
    assert _gate.main(["check"], tree=tree) == 2


def test_zero_docs_exits_two():
    files = clean_files()
    files.pop("README.md")
    assert _gate.main(["check"], tree=build(files)) == 2


def test_violation_exits_one():
    assert _gate.main(["check"], tree=with_files({"README.md": img(900)})) == 1


def test_bad_usage_exits_two():
    assert _gate.main(["frobnicate"], tree=with_files()) == 2
    assert _gate.main(["check", "--diagram", "process/nope"], tree=with_files()) == 2


def test_diagram_flag_restricts_scope():
    files = clean_files()
    files.update(
        {
            "docs/diagrams/process/b.mmd": flow(keyword="flowchart TD"),
            "docs/diagrams/process/b.png": png(2400, 800),
            MANIFEST: "# h\nprocess/a\t2\nprocess/b\t2\n",
            "docs/X.md": '<img src="diagrams/process/b.png" width="5" alt="x">\n',
            "docs/learning/z.md": "```mermaid\nflowchart LR\n  A-->B\n```\n",
            "docs/diagrams/loose.mmd": flow(),
            "docs/diagrams/loose.png": png(1000, 800),
        }
    )
    tree = build(files)
    assert rules(tree, only=["process/a"]) == [], rules(tree, only=["process/a"])
    only_b = rules(tree, only=["process/b"])
    assert "flowchart-rules" in only_b and "legibility-width" in only_b and "embed-width" in only_b, only_b
    assert "outside-view-folder" not in only_b
    assert "outside-view-folder" in rules(tree)


def test_diagram_flag_scopes_dangling_to_that_diagram():
    files = clean_files()
    files["docs/X.md"] = "[x](diagrams/process/gone.png)\n"
    tree = build(files)
    assert rules(tree, only=["process/a"]) == []
    assert "dangling-reference" in rules(tree)


def test_report_rows_carry_width_and_text_size():
    rows = _gate.report(with_files({"docs/diagrams/process/a.png": png(2234, 1000), "README.md": img(1117)}))
    assert len(rows) == 1
    row = rows[0]
    assert row["key"] == "process/a" and row["png_w"] == 2234 and row["png_h"] == 1000, row
    assert row["natural_w"] == 1117 and row["natural_h"] == 500, row
    assert abs(row["text_px"] - 16 * 838 / 1117) < 0.01, row
    assert abs(row["shown_h"] - 500 * 838 / 1117) < 0.5, row


def test_real_tree_enumerates_tracked_files_only():
    tree = _gate.load_tree()
    assert any(p.startswith("docs/diagrams/") for p in tree.files), "no diagrams loaded"
    assert not any(p.startswith(".env") for p in tree.files), "credential-shaped path loaded"


def main():
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_") and callable(f)]
    failed = 0
    for name, func in tests:
        try:
            func()
            print("PASS: %s" % name)
        except AssertionError as err:
            failed += 1
            print("FAIL: %s: %s" % (name, err))
        except Exception as err:  # a crash in the gate is a failed test, not a crashed run
            failed += 1
            print("FAIL: %s: %s: %s" % (name, type(err).__name__, err))
    print("%d/%d passed" % (len(tests) - failed, len(tests)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
