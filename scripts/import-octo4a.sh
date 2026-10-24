#!/usr/bin/env bash
set -Eeuo pipefail

# Import the upstream Android application into this repository while preserving
# upstream attribution. Run from the Miracle Klipper repository root.
UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/feelfreelinux/octo4a.git}"
UPSTREAM_REF="${UPSTREAM_REF:-master}"
DEST_ROOT="${DEST_ROOT:-.}"
WORK_DIR="$(mktemp -d)"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

if [[ ! -f "$DEST_ROOT/README.md" ]]; then
  echo "ERROR: run this script from the Miracle Klipper repository root." >&2
  exit 2
fi
if [[ -d "$DEST_ROOT/app" ]]; then
  echo "ERROR: destination app/ already exists. Refusing to overwrite it." >&2
  exit 3
fi

echo "[1/5] Cloning upstream Octo4a ($UPSTREAM_REF)"
git clone --filter=blob:none --no-checkout "$UPSTREAM_URL" "$WORK_DIR/octo4a"
git -C "$WORK_DIR/octo4a" checkout --detach "$UPSTREAM_REF"

echo "[2/5] Copying Android project sources and build wrapper"
mkdir -p "$DEST_ROOT"
cp -a "$WORK_DIR/octo4a/app" "$DEST_ROOT/app"

echo "[3/5] Preserving upstream license and attribution"
cp "$WORK_DIR/octo4a/LICENSE.md" "$DEST_ROOT/LICENSE-OCTO4A-AGPL-3.0.md"
cp "$WORK_DIR/octo4a/README.md" "$DEST_ROOT/README-OCTO4A-UPSTREAM.md"
git -C "$WORK_DIR/octo4a" rev-parse HEAD > "$DEST_ROOT/OCTO4A_UPSTREAM_COMMIT.txt"

echo "[4/5] Removing generated local build output"
find "$DEST_ROOT/app" -type d \( -name build -o -name .gradle -o -name .cxx \) -prune -exec rm -rf {} +

echo "[5/5] Import complete. No application code has been migrated yet."
echo "Next: inspect Android package/application IDs, then migrate OctoPrint repositories."
echo "Imported upstream commit: $(cat "$DEST_ROOT/OCTO4A_UPSTREAM_COMMIT.txt")"
