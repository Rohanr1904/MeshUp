#!/usr/bin/env bash

set -euo pipefail

RELEASE_DIR="${1:?usage: prepare-github-release.sh RELEASE_DIR}"

if [ ! -d "$RELEASE_DIR" ]; then
  echo "error: release directory not found" >&2
  exit 1
fi
RELEASE_DIR="$(cd "$RELEASE_DIR" && pwd)"

if command -v sha256sum >/dev/null 2>&1; then
  SHA256=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
  SHA256=(shasum -a 256)
else
  echo "error: sha256sum or shasum is required" >&2
  exit 1
fi

required=(
  BUILDINFO.json
  SHA256SUMS
  SHA256SUMS.unsigned
  nearbird-arm64-unsigned.apk
  nearbird-arm64.apk
  nearbird-armv7-unsigned.apk
  nearbird-release-unsigned.aab
  nearbird-universal-unsigned.apk
  nearbird-universal.apk
  nearbird-wear-release-unsigned.aab
  nearbird-wear-unsigned.apk
  nearbird-wear.apk
  nearbird-x86-unsigned.apk
  nearbird-x86_64-unsigned.apk
  nearbird-x86_64.apk
)
# The Play upload bundles (nearbird-play-upload.aab, nearbird-wear-play-upload.aab) are
# optional: NearBird is distributed through GitHub Releases without a Play listing
# (Decision 016). They are still accepted below when a Play release is prepared.
for artifact in "${required[@]}"; do
  if [ ! -f "$RELEASE_DIR/$artifact" ]; then
    echo "error: required release artifact missing: $artifact" >&2
    exit 1
  fi
done

for artifact_path in "$RELEASE_DIR"/*; do
  if [ ! -f "$artifact_path" ]; then
    echo "error: unexpected non-file in release directory: $(basename "$artifact_path")" >&2
    exit 1
  fi
  artifact="$(basename "$artifact_path")"
  case "$artifact" in
    BUILDINFO.json|SHA256SUMS|SHA256SUMS.unsigned|\
    nearbird-arm64-unsigned.apk|nearbird-arm64.apk|\
    nearbird-armv7-unsigned.apk|\
    nearbird-play-upload.aab|nearbird-release-unsigned.aab|\
    nearbird-universal-unsigned.apk|nearbird-universal.apk|\
    nearbird-wear-play-upload.aab|nearbird-wear-release-unsigned.aab|\
    nearbird-wear-unsigned.apk|nearbird-wear.apk|\
    nearbird-x86-unsigned.apk|\
    nearbird-x86_64-unsigned.apk|nearbird-x86_64.apk)
      ;;
    *)
      echo "error: unexpected release artifact: $artifact" >&2
      exit 1
      ;;
  esac
done

for destination in NEARBIRD_BUILDINFO.json NEARBIRD_SHA256SUMS NEARBIRD_SHA256SUMS.unsigned; do
  if [ -e "$RELEASE_DIR/$destination" ]; then
    echo "error: public release manifest already exists: $destination" >&2
    exit 1
  fi
done

(
  cd "$RELEASE_DIR"
  "${SHA256[@]}" -c SHA256SUMS
)

mv "$RELEASE_DIR/BUILDINFO.json" "$RELEASE_DIR/NEARBIRD_BUILDINFO.json"
mv "$RELEASE_DIR/SHA256SUMS.unsigned" "$RELEASE_DIR/NEARBIRD_SHA256SUMS.unsigned"
# sha256sum writes "  name" (text mode) or " *name" (binary mode, e.g. Git Bash on Windows).
sed \
  -e 's/\( [ *]\)BUILDINFO.json$/\1NEARBIRD_BUILDINFO.json/' \
  -e 's/\( [ *]\)SHA256SUMS.unsigned$/\1NEARBIRD_SHA256SUMS.unsigned/' \
  "$RELEASE_DIR/SHA256SUMS" > "$RELEASE_DIR/NEARBIRD_SHA256SUMS"
rm "$RELEASE_DIR/SHA256SUMS"

(
  cd "$RELEASE_DIR"
  "${SHA256[@]}" -c NEARBIRD_SHA256SUMS
)

echo "Release assets are checksummed and ready for manual GitHub publication."
