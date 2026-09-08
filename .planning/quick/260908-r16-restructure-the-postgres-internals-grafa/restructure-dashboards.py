"""One-shot codemod: reorders Postgres Internals' 23 content panels into five expanded
topic rows, replacing the single "Global Statistics" row. Committed alongside the JSON
diff as the reviewable statement of a 29-entry array reorder that the raw diff alone does
not make legible. Depends on the file's round-trip property (json.dumps(indent=2) + "\n"
reproduces it byte-for-byte) to keep the diff confined to gridPos/order changes; re-checked
below rather than assumed.
"""

import json

FILE_PATH = "docker/grafana/provisioning/dashboards/json/postgres-exporter.json"

# (panel_id, x, y, w, h, row_title_or_None) -- transcribed from the plan's design-rationale
# "Target layout" table, in the exact array order that table specifies.
LAYOUT = [
    (18, 0, 0, 24, 1, "Health & Availability"),
    (11, 0, 1, 8, 4, None),
    (57, 8, 1, 8, 4, None),
    (84, 16, 1, 8, 4, None),
    (120, 0, 5, 24, 9, None),
    (200, 0, 14, 24, 1, "Connections"),
    (23, 0, 15, 8, 6, None),
    (9, 8, 15, 8, 6, None),
    (75, 16, 15, 8, 6, None),
    (24, 0, 21, 12, 9, None),
    (28, 12, 21, 12, 9, None),
    (201, 0, 30, 24, 1, "Query Performance"),
    (93, 0, 31, 8, 4, None),
    (14, 8, 31, 8, 4, None),
    (102, 16, 31, 8, 4, None),
    (12, 0, 35, 12, 9, None),
    (27, 12, 35, 12, 9, None),
    (111, 0, 44, 24, 9, None),
    (202, 0, 53, 24, 1, "Storage & I/O"),
    (37, 0, 54, 6, 6, None),
    (66, 6, 54, 6, 6, None),
    (16, 12, 54, 6, 6, None),
    (15, 18, 54, 6, 6, None),
    (26, 0, 60, 12, 9, None),
    (31, 12, 60, 12, 9, None),
    (203, 0, 69, 24, 1, "Locks"),
    (29, 0, 70, 12, 9, None),
    (30, 12, 70, 12, 9, None),
    (20, 0, 79, 24, 1, None),  # existing collapsed "Database: $Database" row, kept as-is
]

NEW_ROW_IDS = {18, 200, 201, 202, 203}


def make_row(row_id, x, y, w, h, title):
    return {
        "collapsed": False,
        "datasource": "Prometheus",
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "id": row_id,
        "panels": [],
        "title": title,
        "type": "row",
    }


def main():
    with open(FILE_PATH) as f:
        raw = f.read()
    obj = json.loads(raw)

    reserialized = json.dumps(obj, indent=2) + "\n"
    if reserialized != raw:
        raise SystemExit(
            "ABORT: round-trip precondition failed -- "
            "json.dumps(obj, indent=2) + '\\n' does not reproduce the file byte-for-byte. "
            "The codemod assumes this to keep the diff minimal; rewriting under a broken "
            "precondition would reformat the whole file instead of just the layout."
        )

    panels_by_id = {p["id"]: p for p in obj["panels"]}
    if len(panels_by_id) != len(obj["panels"]):
        raise SystemExit("ABORT: duplicate panel ids found in top-level panels array")

    new_panels = []
    for panel_id, x, y, w, h, row_title in LAYOUT:
        if panel_id in NEW_ROW_IDS:
            new_panels.append(make_row(panel_id, x, y, w, h, row_title))
            panels_by_id.pop(panel_id, None)
        else:
            panel = panels_by_id.pop(panel_id)
            panel["gridPos"] = {"h": h, "w": w, "x": x, "y": y}
            new_panels.append(panel)

    if panels_by_id:
        raise SystemExit(
            f"ABORT: {len(panels_by_id)} pre-existing panel(s) were not placed by the "
            f"layout table: {sorted(panels_by_id)}"
        )
    if len(new_panels) != 29:
        raise SystemExit(f"ABORT: expected 29 entries in the new panels array, got {len(new_panels)}")

    obj["panels"] = new_panels

    with open(FILE_PATH, "w") as f:
        f.write(json.dumps(obj, indent=2) + "\n")

    print(f"OK: wrote {len(new_panels)} top-level panels to {FILE_PATH}")


if __name__ == "__main__":
    main()
