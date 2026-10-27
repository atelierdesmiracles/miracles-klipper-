#!/usr/bin/env bash
# Download the verified Android PRoot bundle and install it as jniLibs, so the APK ships
# libproot.so / libproot-loader.so (the only executable location on Android 10+).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="${PROOT_BUNDLE_BASE_URL:-https://github.com/atelierdesmiracles/miracles-klipper-/releases/download/miracle-klipper-debian13}"

for item in "arm64:arm64-v8a" "x86_64:x86_64"; do
  arch="${item%%:*}"; abi="${item#*:}"
  name="miracle-klipper-debian13-${arch}.zip"
  tmp="$(mktemp -d)"
  curl --fail --location --retry 3 "${BASE}/${name}" -o "${tmp}/${name}"
  curl --fail --location --retry 3 "${BASE}/${name}.sha256" -o "${tmp}/${name}.sha256"
  expected="$(awk '{print $1; exit}' "${tmp}/${name}.sha256")"
  actual="$(sha256sum "${tmp}/${name}" | awk '{print $1}')"
  if [ "${expected,,}" != "${actual,,}" ]; then
    echo "SHA-256 mismatch for ${name}: expected ${expected}, got ${actual}" >&2
    exit 1
  fi
  unzip -q "${tmp}/${name}" -d "${tmp}/x"
  test -s "${tmp}/x/bin/proot"
  test -s "${tmp}/x/libexec/proot/loader"
  dest="${ROOT}/app/app/src/main/jniLibs/${abi}"
  mkdir -p "${dest}"
  cp "${tmp}/x/bin/proot" "${dest}/libproot.so"
  cp "${tmp}/x/libexec/proot/loader" "${dest}/libproot-loader.so"
  chmod 755 "${dest}/libproot.so" "${dest}/libproot-loader.so"
  rm -rf "${tmp}"
  echo "PRoot ${arch} -> ${dest}"
done
