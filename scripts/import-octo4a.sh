#!/usr/bin/env bash
set -Eeuo pipefail

# Import the complete upstream Android/Gradle project while preserving
# attribution. Run from the Miracle Klipper repository root.
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
if [[ -e "$DEST_ROOT/app" || -e "$DEST_ROOT/gradlew" || -e "$DEST_ROOT/settings.gradle" || -e "$DEST_ROOT/settings.gradle.kts" ]]; then
  echo "ERROR: Android/Gradle project files already exist. Refusing to overwrite them." >&2
  exit 3
fi

echo "[1/5] Cloning upstream Octo4a ($UPSTREAM_REF)"
git clone --filter=blob:none --no-checkout "$UPSTREAM_URL" "$WORK_DIR/octo4a"
git -C "$WORK_DIR/octo4a" checkout --detach "$UPSTREAM_REF"
UPSTREAM="$WORK_DIR/octo4a"

if [[ ! -d "$UPSTREAM/app" ]]; then
  echo "ERROR: upstream ref does not contain app/." >&2
  exit 4
fi

echo "[2/5] Copying the complete Android project and Gradle build files"
# Copy project files while excluding Git metadata and upstream CI configuration.
# Keep the original README/license separately below for attribution.
tar -C "$UPSTREAM" --exclude='./.git' --exclude='./.github' --exclude='./README.md' --exclude='./LICENSE*' -cf - . | tar -C "$DEST_ROOT" -xf -

echo "[3/5] Preserving upstream license and attribution"
if [[ -f "$UPSTREAM/LICENSE.md" ]]; then
  cp "$UPSTREAM/LICENSE.md" "$DEST_ROOT/LICENSE-OCTO4A-AGPL-3.0.md"
elif [[ -f "$UPSTREAM/LICENSE" ]]; then
  cp "$UPSTREAM/LICENSE" "$DEST_ROOT/LICENSE-OCTO4A-AGPL-3.0.md"
else
  echo "ERROR: upstream license file was not found; refusing to import without it." >&2
  exit 5
fi
cp "$UPSTREAM/README.md" "$DEST_ROOT/README-OCTO4A-UPSTREAM.md"
git -C "$UPSTREAM" rev-parse HEAD > "$DEST_ROOT/OCTO4A_UPSTREAM_COMMIT.txt"
printf 'Upstream URL: %s\nRequested ref: %s\n' "$UPSTREAM_URL" "$UPSTREAM_REF" >> "$DEST_ROOT/OCTO4A_UPSTREAM_COMMIT.txt"

echo "[4/5] Removing generated local build output"
find "$DEST_ROOT" -type d \( -name build -o -name .gradle -o -name .cxx \) -prune -exec rm -rf {} +

echo "[5/5] Checking Gradle wrapper and app module"
if [[ ! -f "$DEST_ROOT/gradlew" || ! -d "$DEST_ROOT/gradle" || ! -d "$DEST_ROOT/app" ]]; then
  echo "ERROR: imported project is missing gradlew, gradle/, or app/." >&2
  exit 6
fi
chmod +x "$DEST_ROOT/gradlew"
echo "Import complete. Upstream Android code has been copied; Miracle Klipper migration is still required."
echo "Imported upstream commit: $(head -n 1 "$DEST_ROOT/OCTO4A_UPSTREAM_COMMIT.txt")"
