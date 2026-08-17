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

# notarizeDmg when this machine holds a signing identity, packageDmg otherwise. Same script either
# way, so a self-hosted Mac runner with the certificate installed produces a signed build without
# the pipeline knowing anything about it, and every other machine keeps producing an unsigned one.
#
# Reading the properties file rather than asking Gradle: `gradlew properties` costs a full
# configuration to answer a question a grep answers. Set LOUPE_SIGN=1 to force it either way.
GRADLE_PROPERTIES="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
if [ "${LOUPE_SIGN:-}" = "1" ] || grep -qs '^loupe\.signing\.identity=' "$GRADLE_PROPERTIES"; then
  echo "package-dmg.sh: signing identity configured — notarising" >&2
  ./gradlew :desktop:notarizeDmg
else
  echo "package-dmg.sh: no signing identity — building unsigned" >&2
  ./gradlew :desktop:packageDmg
fi

SRC=$(find desktop/build/compose/binaries/main/dmg -name '*.dmg' | head -1)
[ -n "$SRC" ] || { echo "package-dmg.sh: no .dmg was produced" >&2; exit 1; }

# Emptied, not just created. A shell-executor runner keeps the working directory between builds, so
# last release's .dmg would still be sitting here and the release job globs the whole folder.
rm -rf build/release
mkdir -p build/release
DEST="build/release/Loupe-${VERSION}-${ARCH}.dmg"
mv "$SRC" "$DEST"
shasum -a 256 "$DEST" | sed "s|build/release/||" > "$DEST.sha256"

echo "$DEST"
echo "  $(du -h "$DEST" | cut -f1)  $(cat "$DEST.sha256")" >&2
