#!/usr/bin/env python3
"""Split selected 1-based pages of the kanban mock-up PDF into a small derived PDF.

Exists because the Read tool's page-rendering path refuses any source file over
100 MB (the source here is 115 MB), so a bounded subset of pages must be copied
into a much smaller derived file before it can be rendered as images.

Usage:
    python split-mockup-pages.py <comma-separated 1-based page numbers> <output_dir> <output_filename> [source_pdf]

Prints, on success:
    - the derived_page -> original_page mapping, one pair per line
    - the derived file's size in MB

Exits non-zero (without writing a derived file that would be unusable) if:
    - the source page count is not exactly 73 (wrong file)
    - the resulting derived file would reach 95 MB (over the Read tool's 100 MB cap)
"""

import sys
import os

EXPECTED_SOURCE_PAGE_COUNT = 73
MAX_DERIVED_MB = 95
DEFAULT_SOURCE = r"B:\downloads\claude_desktop\kanban-task-management-web-app.pdf"


def main() -> int:
    if len(sys.argv) < 4:
        print(
            "usage: split-mockup-pages.py <pages> <output_dir> <output_filename> [source_pdf]",
            file=sys.stderr,
        )
        return 2

    pages_arg = sys.argv[1]
    output_dir = sys.argv[2]
    output_filename = sys.argv[3]
    source_pdf = sys.argv[4] if len(sys.argv) > 4 else DEFAULT_SOURCE

    try:
        original_pages = [int(p.strip()) for p in pages_arg.split(",") if p.strip()]
    except ValueError:
        print(f"ERROR: could not parse page list '{pages_arg}' as integers", file=sys.stderr)
        return 2

    if not original_pages:
        print("ERROR: no pages requested", file=sys.stderr)
        return 2

    import pypdf

    reader = pypdf.PdfReader(source_pdf)
    actual_page_count = len(reader.pages)
    if actual_page_count != EXPECTED_SOURCE_PAGE_COUNT:
        print(
            f"ERROR: source has {actual_page_count} pages, expected "
            f"{EXPECTED_SOURCE_PAGE_COUNT} — this looks like the wrong file. Halting.",
            file=sys.stderr,
        )
        return 1

    writer = pypdf.PdfWriter()
    mapping = []
    for derived_index, original_page in enumerate(original_pages, start=1):
        if original_page < 1 or original_page > actual_page_count:
            print(
                f"ERROR: requested original page {original_page} is out of range "
                f"1..{actual_page_count}",
                file=sys.stderr,
            )
            return 1
        writer.add_page(reader.pages[original_page - 1])
        mapping.append((derived_index, original_page))

    os.makedirs(output_dir, exist_ok=True)
    output_path = os.path.join(output_dir, output_filename)
    with open(output_path, "wb") as f:
        writer.write(f)

    size_mb = os.path.getsize(output_path) / (1024 * 1024)

    for derived_index, original_page in mapping:
        print(f"{derived_index} -> {original_page}")
    print(f"derived file size: {size_mb:.2f} MB")
    print(f"written to: {output_path}")

    if size_mb >= MAX_DERIVED_MB:
        print(
            f"ERROR: derived file is {size_mb:.2f} MB, at or over the "
            f"{MAX_DERIVED_MB} MB safety threshold (Read tool caps at 100 MB). Halting.",
            file=sys.stderr,
        )
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
