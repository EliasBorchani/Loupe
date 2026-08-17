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

# jpackage does not lay the .dmg out itself — it drives the **Finder**, through an AppleScript
# (DMGsetup.scpt). The /Applications drop target, the icon positions and the background image all
# come from that one step. A machine with no GUI session — a GitLab runner installed as a launchd
# daemon rather than an agent — has no Finder to drive: jpackage logs a warning, carries on, and
# ships a .dmg containing nothing but the .app, with no way for anyone to install it.
#
# So check, and repair with a plain symlink when it is missing. Same cause as the locked keychain in
# docs/packaging.md, and the same real fix: give the runner a session.
ensure_applications_link() {
  local dmg="$1" mount work
  mount=$(hdiutil attach "$dmg" -nobrowse -readonly -noautoopen | grep -o '/Volumes/.*' | head -1)
  # `:Applications` is how the POSIX layer spells the Finder alias jpackage makes; a symlink named
  # `Applications` is what this function makes. Either one is a working drop target.
  if ls -a "$mount" | grep -q '^:\?Applications$'; then
    hdiutil detach "$mount" -quiet
    return 0
  fi
  hdiutil detach "$mount" -quiet

  if [ "${NOTARISED:-0}" = "1" ]; then
    echo "package-dmg.sh: the notarised .dmg has no /Applications target, and editing it would" >&2
    echo "  break the staple. Install the runner as a launchd agent in a logged-in session." >&2
    return 1
  fi

  echo "package-dmg.sh: no /Applications target — the Finder never ran. Adding a symlink." >&2
  echo "  This machine has no GUI session, so the .dmg also has no background and no icon layout." >&2

  work=$(mktemp -d)
  hdiutil convert "$dmg" -format UDRW -quiet -o "$work/rw.dmg"
  # The image is sized to its contents, so a new directory entry needs room made for it first.
  hdiutil resize -size "$(( $(du -m "$work/rw.dmg" | cut -f1) + 20 ))m" "$work/rw.dmg" >/dev/null
  mount=$(hdiutil attach "$work/rw.dmg" -nobrowse -noautoopen | grep -o '/Volumes/.*' | head -1)
  ln -s /Applications "$mount/Applications"
  hdiutil detach "$mount" -quiet
  hdiutil convert "$work/rw.dmg" -format UDZO -imagekey zlib-level=9 -quiet -o "$work/out.dmg"
  mv "$work/out.dmg" "$dmg"
  rm -rf "$work"
}

# notarizeDmg when this machine holds a signing identity, packageDmg otherwise. Same script either
# way, so a self-hosted Mac runner with the certificate installed produces a signed build without
# the pipeline knowing anything about it, and every other machine keeps producing an unsigned one.
#
# Reading the properties file rather than asking Gradle: `gradlew properties` costs a full
# configuration to answer a question a grep answers. Set LOUPE_SIGN=1 to force it either way.
GRADLE_PROPERTIES="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
if [ "${LOUPE_SIGN:-}" = "1" ] || grep -qs '^loupe\.signing\.identity=' "$GRADLE_PROPERTIES"; then
  echo "package-dmg.sh: signing identity configured — notarising" >&2
  NOTARISED=1
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

# Before the checksum: this can rewrite the file.
ensure_applications_link "$DEST"

shasum -a 256 "$DEST" | sed "s|build/release/||" > "$DEST.sha256"

echo "$DEST"
echo "  $(du -h "$DEST" | cut -f1)  $(cat "$DEST.sha256")" >&2
