#!/usr/bin/env bash
# Render docs/diagrams/<view>/<subject>.mmd to its committed .png, and check the committed set for drift,
# through one digest-pinned renderer, so a PNG's exact pixels no longer depend on which machine rendered it.
#
# Decisions:
# No render command existed as of 2026-09-05 and `mmdc` was not installed (searched docs/, scripts/,
# .github/, .githooks/, build.gradle), so each of the nine committed PNGs was an artifact of an unknown
# renderer on an unknown machine: unreproducible, with nothing to detect drift from its `.mmd` source.
# The pin is load-bearing; re-resolve it before trusting it further. The digest was re-resolved
# 2026-09-05 against GHCR's registry API (the `docker-content-digest` response header for the
# manifest-LIST tag `11.17.0`, also npm's `@mermaid-js/mermaid-cli` `latest` dist-tag): a pin copied from
# a plan and never independently checked is folklore with a hash on it. `docker manifest inspect` alone
# is not sufficient, since it returns per-architecture manifest digests, not the top-level index digest
# an `@sha256:...` reference resolves against; use `docker buildx imagetools inspect`, or query the
# registry API directly for the `docker-content-digest` header.
readonly MERMAID_CLI_IMAGE="ghcr.io/mermaid-js/mermaid-cli/mermaid-cli@sha256:a6fb0574dded4086888b5e38476899c9aff8963196f689f11a0f8fceee588ce1"

# Match criterion: width must equal the committed width EXACTLY at the manifest's scale; height must
# be within 2%.
#
# Decisions:
# Measured 2026-09-05 against this pinned image at `-s 2`: `infra-physical-deployment` re-renders to the
# committed width EXACTLY (1568px) but +6.21% in height; `infra-delivery-scenario` matches width exactly
# and is -8.37% in height. Opposite signs rule out a scale artifact: this is genuine layout drift from a
# different mermaid version than produced the committed set, which is why height gets a tolerance and
# width does not.
# Natural width: docs/diagrams/mermaid-config.json turns off flowchart.useMaxWidth and sequence.useMaxWidth.
# Measured 2026-10-05, mmdc lays diagrams out in an 800 px viewport and, with the default useMaxWidth, shrinks
# every SVG to fit inside it (784 px) before rasterising, so `-s` multiplied pixels but never layout width: 784
# x 1, x 2 and x 4 were exactly the old committed widths. With the config the layout keeps its natural width,
# the scale is uniformly 2, and a PNG is a 2x raster of a diagram whose CSS width is the PNG width divided by 2.
# Because that width now tracks the content, a label edit that changes the bounding box fails the width check
# here. Styling stays in each flowchart's own init line, so inline blocks rendered by GitHub look the same.
# Blind spot, narrowed: this still reads only the PNG's IHDR chunk (width, height, bit depth, colour type). A
# label edit that leaves the bounding box unchanged passes. A perceptual pixel diff would close that gap; it is
# the upgrade path if docs/diagrams/ ever grows enough to justify the dependency (ImageMagick is not installed
# on this box; Pillow is present but this script does not depend on it).
# Colour mode, reported not gated: the committed PNGs are all colour type 2 (RGB, no alpha). `mmdc` has no
# flag to force that: Puppeteer's screenshot output is colour type 6 (RGBA) regardless of `-b white`.
# Flattening RGBA to RGB would need Pillow or ImageMagick, which this script deliberately does not depend
# on (see the dimension reader below), so a fresh render is accepted as RGBA going forward rather than
# growing the dependency footprint to buy back a match on a channel the criterion never claimed to check.
# Not wired into CI: `.github/workflows/invariant-checks.yml` ignores `docs/**`, so wiring this in would
# mean removing that ignore, which pays a full `deploy.yml` production+nonprod redeploy on every
# documentation commit plus a 2.36GB image pull per run, for a docs-only check. This pin plus the
# Maintenance Note in `docs/INFRA_ARCHITECTURE.md` is the mechanism instead; run this by hand.
# Fallback for a machine with no Docker: `pnpm dlx @mermaid-js/mermaid-cli@11.17.0 -i <name>.mmd -o
# <name>.png -s <scale> -b white -c docs/diagrams/mermaid-config.json` reproduces the same layout engine
# version but NOT the same geometry: Puppeteer renders text with the HOST's installed fonts, which the npm
# package cannot pin, so identical input produces different bounding boxes on a different machine. A
# documented, non-reproducible fallback, not a substitute for the pinned container (`pnpm dlx`, never `npx`).

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd -P)"
DIAGRAMS_DIR="${REPO_ROOT}/docs/diagrams"
MANIFEST="${DIAGRAMS_DIR}/render-manifest.tsv"

usage() {
  cat <<'USAGE'
Usage:
  render-diagrams.sh                 Render every diagram in the manifest in place.
  render-diagrams.sh --all           Same as above.
  render-diagrams.sh <name>          Render just <name> (view/subject, no .mmd suffix) in place.
  render-diagrams.sh --check --all   Render every diagram into a scratch dir and compare against
                                      the committed PNG; prints one row per diagram and exits
                                      non-zero on any mismatch. Writes nothing under docs/diagrams/.
  render-diagrams.sh --check <name>  Same, for a single diagram.
USAGE
}

# One name per non-comment, non-blank manifest line: avoids depending on any particular awk's
# field-splitting for a small two-column TSV.
manifest_names() {
  local line_name line_scale
  while IFS=$'\t' read -r line_name line_scale || [[ -n "$line_name" ]]; do
    [[ "$line_name" == \#* ]] && continue
    [[ -z "$line_name" ]] && continue
    echo "$line_name"
  done <"$MANIFEST"
}

scale_for() {
  local name="$1"
  local line_name line_scale
  while IFS=$'\t' read -r line_name line_scale || [[ -n "$line_name" ]]; do
    [[ "$line_name" == \#* ]] && continue
    [[ -z "$line_name" ]] && continue
    if [[ "$line_name" == "$name" ]]; then
      echo "$line_scale"
      return 0
    fi
  done <"$MANIFEST"
  echo "FAIL: no render-manifest.tsv entry for '${name}'" >&2
  return 1
}

# $1 = diagram name, $2 = output directory, $3 = "ro" to bind docs/diagrams read-only (check mode,
# so a check can never itself write a committed PNG).
render_one() {
  local name="$1" out_dir="$2" ro="${3:-}"
  local scale
  scale="$(scale_for "$name")" || return 1

  if [[ ! -f "${DIAGRAMS_DIR}/${name}.mmd" ]]; then
    echo "FAIL: no such diagram source '${DIAGRAMS_DIR}/${name}.mmd'" >&2
    return 1
  fi

  local src_mount="${DIAGRAMS_DIR}:/data"
  [[ "$ro" == "ro" ]] && src_mount="${src_mount}:ro"

  # A name is view/subject, so the scratch tree needs the view directory before mmdc writes into it.
  [[ "$out_dir" != "$DIAGRAMS_DIR" ]] && mkdir -p "${out_dir}/$(dirname "$name")"

  if [[ "$out_dir" == "$DIAGRAMS_DIR" ]]; then
    docker run --rm -u "$(id -u):$(id -g)" -v "$src_mount" \
      "$MERMAID_CLI_IMAGE" -i "/data/${name}.mmd" -o "/data/${name}.png" -s "$scale" -b white \
      -c /data/mermaid-config.json
  else
    docker run --rm -u "$(id -u):$(id -g)" -v "$src_mount" -v "${out_dir}:/out" \
      "$MERMAID_CLI_IMAGE" -i "/data/${name}.mmd" -o "/out/${name}.png" -s "$scale" -b white \
      -c /data/mermaid-config.json
  fi
}

do_render() {
  local name
  for name in "$@"; do
    echo "Rendering ${name} ..."
    render_one "$name" "$DIAGRAMS_DIR"
  done
}

# Reads both PNGs' IHDR chunks (stdlib only -- no Pillow, no ImageMagick; see header), compares
# width exactly and height within 2%, and prints one row. Exits 0 if this diagram matches, 1 if not.
compare_one() {
  local name="$1" committed="$2" rendered="$3"
  python3 - "$name" "$committed" "$rendered" <<'PYEOF'
import struct
import sys


def read_ihdr(path):
    with open(path, "rb") as fh:
        sig = fh.read(8)
        if sig != b"\x89PNG\r\n\x1a\n":
            raise ValueError(f"{path} is not a PNG (bad signature)")
        fh.read(4)  # chunk length, always 13 for IHDR
        fh.read(4)  # chunk type, "IHDR"
        width, height = struct.unpack(">II", fh.read(8))
        fh.read(1)  # bit depth
        color_type = fh.read(1)[0]
    mode = {0: "Grayscale", 2: "RGB", 3: "Palette", 4: "GrayAlpha", 6: "RGBA"}.get(
        color_type, f"type{color_type}"
    )
    return width, height, mode


name, committed_path, rendered_path = sys.argv[1], sys.argv[2], sys.argv[3]
cw, ch, cmode = read_ihdr(committed_path)
rw, rh, rmode = read_ihdr(rendered_path)

width_ok = cw == rw
height_delta_pct = ((rh - ch) / ch) * 100
height_ok = abs(height_delta_pct) <= 2.0
row_ok = width_ok and height_ok

print(
    f"{name:<44} committed={cw}x{ch:<6} rendered={rw}x{rh:<6} "
    f"width={'OK' if width_ok else 'FAIL'}({cw}v{rw}) "
    f"height={'OK' if height_ok else 'FAIL'}({height_delta_pct:+.2f}%) "
    f"mode={cmode}->{rmode}"
)
sys.exit(0 if row_ok else 1)
PYEOF
}

do_check() {
  local tmp_dir
  tmp_dir="$(mktemp -d)"
  # shellcheck disable=SC2064 -- tmp_dir is intentionally expanded now, not at trap time.
  trap "rm -rf '${tmp_dir}'" EXIT

  local overall_status=0
  local name committed
  for name in "$@"; do
    committed="${DIAGRAMS_DIR}/${name}.png"
    if [[ ! -f "$committed" ]]; then
      echo "${name}: FAIL missing committed PNG '${committed}'"
      overall_status=1
      continue
    fi
    if ! render_one "$name" "$tmp_dir" ro >/dev/null; then
      echo "${name}: FAIL render into scratch dir failed"
      overall_status=1
      continue
    fi
    if ! compare_one "$name" "$committed" "${tmp_dir}/${name}.png"; then
      overall_status=1
    fi
  done

  return $overall_status
}

if [[ $# -eq 0 ]]; then
  MODE="render"
  TARGET="--all"
elif [[ "$1" == "-h" || "$1" == "--help" ]]; then
  usage
  exit 0
elif [[ "$1" == "--check" ]]; then
  MODE="check"
  shift
  if [[ $# -eq 0 ]]; then
    echo "FAIL: --check requires a diagram name or --all" >&2
    usage
    exit 2
  fi
  TARGET="$1"
else
  MODE="render"
  TARGET="$1"
fi

if [[ "$TARGET" == "--all" ]]; then
  mapfile -t NAMES < <(manifest_names)
else
  NAMES=("$TARGET")
fi

if [[ "$MODE" == "render" ]]; then
  do_render "${NAMES[@]}"
else
  do_check "${NAMES[@]}"
fi
