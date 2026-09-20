#!/usr/bin/env bash
# Rebuild the demo dataset from scratch and restart the demo instance.
#
# The rebuild reads upstream data (read-only) and so belongs OUTSIDE the demo process, which
# deliberately holds no upstream paths. It also wipes demo/db, which the running app has open,
# which is why the demo is stopped first.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT_DIR/scripts/demo-stop.sh"
python3 "$ROOT_DIR/scripts/make-demo-data.py"
"$ROOT_DIR/scripts/demo.sh"
