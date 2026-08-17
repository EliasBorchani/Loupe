#!/bin/bash
#
# Packages the app and names the result after the release, not after Apple's idea of a version.
#
#   tools/package-dmg.sh [version] [arch]
#
# Both arguments are optional: the version defaults to the git tag with its leading `v` removed,
# the architecture to whatever this machine is. macOS only — jpackage cannot produce a .dmg
# anywhere else.
#
# This exists so GitHub Actions and GitLab CI run the *same* steps. Two CI files that each spell out
# the packaging drift, and the one nobody watches is the one that breaks on release day.
set -euo pipefail
cd "$(dirname "$0")/.."

[ "$(uname -s)" = "Darwin" ] || { echo "package-dmg.sh: macOS only — jpackage cannot build a .dmg here" >&2; exit 1; }

VERSION="${1:-$(git describe --tags --exact-match 2>/dev/null | sed 's/^v//' || echo 0.0.0-dev)}"
ARCH="${2:-$(uname -m)}"
# uname reports arm64; the world calls the other one x64, not x86_64.
[ "$ARCH" = "x86_64" ] && ARCH="x64"

./gradlew :desktop:packageDmg

SRC=$(find desktop/build/compose/binaries/main/dmg -name '*.dmg' | head -1)
[ -n "$SRC" ] || { echo "package-dmg.sh: no .dmg was produced" >&2; exit 1; }

mkdir -p build/release
DEST="build/release/Loupe-${VERSION}-${ARCH}.dmg"
mv "$SRC" "$DEST"
shasum -a 256 "$DEST" | sed "s|build/release/||" > "$DEST.sha256"

echo "$DEST"
echo "  $(du -h "$DEST" | cut -f1)  $(cat "$DEST.sha256")" >&2
