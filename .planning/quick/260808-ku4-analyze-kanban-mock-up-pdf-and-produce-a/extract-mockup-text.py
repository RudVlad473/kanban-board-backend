#!/usr/bin/env python3
"""Extract per-page text from the Kanban mock-up PDF.

Reads the PDF with pypdf.PdfReader, extracts text per page (applying the
document's embedded /ToUnicode CMaps so glyph indices resolve to real
characters rather than octal garbage), collapses whitespace runs to single
spaces, and writes one delimited block per page to an output text file so
every later claim in the gap document can be traced back to a page number.

Usage:
    python extract-mockup-text.py [pdf_path] [output_path]

Defaults:
    pdf_path:    B:\\downloads\\claude_desktop\\kanban-task-management-web-app.pdf
    output_path: mockup-pages.txt (next to this script)
"""

import re
import sys
from pathlib import Path

import pypdf

DEFAULT_PDF_PATH = r"B:\downloads\claude_desktop\kanban-task-management-web-app.pdf"
DEFAULT_OUTPUT_PATH = Path(__file__).parent / "mockup-pages.txt"

WHITESPACE_RE = re.compile(r"\s+")


def extract(pdf_path: str, output_path: Path) -> tuple[int, int]:
    reader = pypdf.PdfReader(pdf_path)
    total_chars = 0
    with open(output_path, "w", encoding="utf-8") as out:
        for i, page in enumerate(reader.pages, start=1):
            text = page.extract_text() or ""
            text = WHITESPACE_RE.sub(" ", text).strip()
            total_chars += len(text)
            out.write(f"=== page {i} ===\n")
            out.write(text)
            out.write("\n\n")
    return len(reader.pages), total_chars


def main() -> None:
    pdf_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PDF_PATH
    output_path = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_OUTPUT_PATH

    page_count, total_chars = extract(pdf_path, output_path)

    print(f"Pages extracted: {page_count}")
    print(f"Total characters: {total_chars}")
    print(f"Output written to: {output_path}")


if __name__ == "__main__":
    main()
