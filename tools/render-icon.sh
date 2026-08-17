#!/bin/bash
#
# desktop/icon.svg + desktop/icon-small.svg  ->  desktop/icon.icns, desktop/icon.png
#
# Two source drawings, because an .icns holds different artwork per size and the detailed one does
# not survive 16px: the page behind the lens turns to noise, the metadata column merges with the
# message, and the amber and red average into mud. icon-small.svg keeps only what reads that small.
#
# Chrome renders the masters; sips resamples. Not Chrome for the small sizes directly — it clamps a
# window below roughly 50px and hands back a near-empty crop. Not the .svg directly either — with
# intrinsic dimensions Chrome crops rather than scales, hence the HTML wrapper.
set -e
cd "$(dirname "$0")/.."
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
[ -x "$CHROME" ] || { echo "Google Chrome is required to rasterise the SVG" >&2; exit 1; }

STAGE=$(mktemp -d)
ICONSET="$STAGE/loupe.iconset"
mkdir -p "$ICONSET"

master() { # source-svg, output-name
  cat > "$STAGE/wrap.html" <<HTML
<!doctype html><meta charset="utf-8">
<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}
img{display:block;width:100vw;height:100vh}</style>
<img src="file://$PWD/$1">
HTML
  "$CHROME" --headless --disable-gpu --no-sandbox --hide-scrollbars \
    --default-background-color=00000000 --force-device-scale-factor=1 \
    --screenshot="$STAGE/$2" --window-size=1024,1024 "file://$STAGE/wrap.html" >/dev/null 2>&1
}

master desktop/icon.svg detailed.png
master desktop/icon-small.svg small.png

emit() { # source-master, size, filename
  cp "$STAGE/$1" "$ICONSET/$3"
  [ "$2" != "1024" ] && sips -z "$2" "$2" "$ICONSET/$3" >/dev/null
  return 0
}

# 16 and 32 get the simplified drawing; 64 and up get the detailed one.
emit small.png     16   icon_16x16.png
emit small.png     32   icon_16x16@2x.png
emit small.png     32   icon_32x32.png
emit detailed.png  64   icon_32x32@2x.png
emit detailed.png  128  icon_128x128.png
emit detailed.png  256  icon_128x128@2x.png
emit detailed.png  256  icon_256x256.png
emit detailed.png  512  icon_256x256@2x.png
emit detailed.png  512  icon_512x512.png
emit detailed.png  1024 icon_512x512@2x.png

iconutil -c icns "$ICONSET" -o desktop/icon.icns
cp "$STAGE/detailed.png" desktop/icon.png
echo "desktop/icon.icns  $(stat -f%z desktop/icon.icns) bytes"
echo "desktop/icon.png   $(stat -f%z desktop/icon.png) bytes"
