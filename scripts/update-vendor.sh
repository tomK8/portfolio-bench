#!/usr/bin/env bash
set -euo pipefail

# Re-download the vendored front-end libraries listed in scripts/vendor-manifest.txt.
#
# These libraries are deliberately NOT auto-updating: they are files in git, baked into
# the jar at package time, and they change only when this script is run. That is the
# point — a CDN blip once left Chart undefined and every chart panel silently blank, and
# an unpinned adapter URL could change under us between two page loads.
#
# To upgrade something, edit its version in the manifest URL, then run this.
#
# Usage:
#   scripts/update-vendor.sh            re-download everything, install if all valid
#   scripts/update-vendor.sh --check    report drift only, write nothing
#   scripts/update-vendor.sh --only <filename>   restrict to one manifest entry

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
MANIFEST="$ROOT_DIR/scripts/vendor-manifest.txt"
VENDOR_DIR="$ROOT_DIR/src/main/resources/static/js/vendor"

CHECK_ONLY=0
ONLY_FILE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --check) CHECK_ONLY=1; shift ;;
        --only)  ONLY_FILE="${2:-}"; [ -n "$ONLY_FILE" ] || { echo "--only needs a filename" >&2; exit 2; }; shift 2 ;;
        -h|--help) sed -n '6,20p' "$0"; exit 0 ;;
        *) echo "Unknown argument: $1 (try --help)" >&2; exit 2 ;;
    esac
done

[ -f "$MANIFEST" ] || { echo "Manifest not found: $MANIFEST" >&2; exit 1; }
mkdir -p "$VENDOR_DIR"

# Stage every download first and only install once all of them validate. A partial
# install is the one outcome worth avoiding: a half-updated vendor dir can mix
# incompatible versions (e.g. a new zoom plugin against the old Chart.js) and the
# failure shows up as a blank panel in the browser, far from here.
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

sha() { shasum -a 256 "$1" | awk '{print $1}'; }

changed=0
checked=0
declare -a SUMMARY=()

while read -r name url; do
    case "$name" in ''|'#'*) continue ;; esac
    [ -n "$ONLY_FILE" ] && [ "$name" != "$ONLY_FILE" ] && continue
    checked=$((checked + 1))

    printf '  %-40s ' "$name"
    if ! curl -sSfL -m 120 -o "$STAGE/$name" "$url"; then
        echo "DOWNLOAD FAILED"
        echo "    $url" >&2
        exit 1
    fi

    # A CDN that 404s into a styled error page, or a truncated transfer, must not be
    # installed over a working library.
    size=$(wc -c < "$STAGE/$name" | tr -d ' ')
    if [ "$size" -lt 1000 ]; then
        echo "REJECTED (suspiciously small: ${size}B)"; exit 1
    fi
    if head -c 512 "$STAGE/$name" | grep -qi '<!doctype\|<html'; then
        echo "REJECTED (looks like an HTML error page)"; exit 1
    fi
    if command -v node >/dev/null 2>&1 && ! node --check "$STAGE/$name" >/dev/null 2>&1; then
        echo "REJECTED (not parseable as JavaScript)"; exit 1
    fi

    if [ ! -f "$VENDOR_DIR/$name" ]; then
        echo "NEW (${size}B)"
        SUMMARY+=("NEW      $name  ${size}B")
        changed=$((changed + 1))
    elif [ "$(sha "$STAGE/$name")" = "$(sha "$VENDOR_DIR/$name")" ]; then
        echo "unchanged"
    else
        old=$(wc -c < "$VENDOR_DIR/$name" | tr -d ' ')
        echo "UPDATED (${old}B -> ${size}B)"
        SUMMARY+=("UPDATED  $name  ${old}B -> ${size}B")
        SUMMARY+=("         was $(sha "$VENDOR_DIR/$name" | cut -c1-12)  now $(sha "$STAGE/$name" | cut -c1-12)")
        changed=$((changed + 1))
    fi
done < "$MANIFEST"

if [ "$checked" -eq 0 ]; then
    echo "No manifest entries matched." >&2
    exit 1
fi

echo
if [ "$changed" -eq 0 ]; then
    echo "All $checked file(s) already match the manifest. Nothing to do."
    exit 0
fi

if [ "$CHECK_ONLY" -eq 1 ]; then
    echo "$changed file(s) differ from the manifest (--check: nothing written):"
    printf '  %s\n' "${SUMMARY[@]}"
    exit 0
fi

for f in "$STAGE"/*; do
    cp "$f" "$VENDOR_DIR/$(basename "$f")"
done

echo "Installed $changed change(s) into src/main/resources/static/js/vendor:"
printf '  %s\n' "${SUMMARY[@]}"
cat <<'NEXT'

Next:
  mvn package -DskipTests && scripts/stop.sh && scripts/start.sh
  then hard-refresh the dashboard (Cmd+Shift+R) and click through the chart tabs.

A library upgrade can break rendering in ways no Java test covers, so check the
tabs in the browser before committing.
NEXT
