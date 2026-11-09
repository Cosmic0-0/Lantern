#!/usr/bin/env bash
# Drives Lantern's screens on the phone over adb, for checks that need the real UI (Phase 3 steps 3, 6 and 8).
# The phone must be unlocked: a secure lock screen hides the app from uiautomator and from Compose UI tests.
#
#   tools/phone-ui.sh dump              list on-screen text with each element's centre point
#   tools/phone-ui.sh tap "<text>"      tap the first element whose text (or content description) is exactly <text>
#   tools/phone-ui.sh shot <file.png>   save a screenshot
#
# In a Compose list the tappable element can be a line below the text you see (for example "On NovelBuddy" on the
# switch screen), so look at dump's output before tapping.
set -euo pipefail

ADB=${ADB:-/c/Users/Caellum/Documents/Projects/Lantern/.bench/pt/platform-tools/adb.exe}
export MSYS_NO_PATHCONV=1 # Git Bash would otherwise rewrite phone paths
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

dump() {
  "$ADB" shell uiautomator dump /sdcard/lantern-ui.xml >/dev/null
  "$ADB" exec-out cat /sdcard/lantern-ui.xml > "$TMP/ui.xml"
  python -I - "$(cygpath -w "$TMP/ui.xml")" <<'EOF'
import re, sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter("node"):
    t = n.get("text") or n.get("content-desc") or ""
    if not t.strip():
        continue
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    print(f"{(x1+x2)//2},{(y1+y2)//2}\t{t[:80]}")
EOF
}

case "${1:-}" in
  dump) dump ;;
  tap)
    xy=$(dump | awk -F'\t' -v t="$2" '$2 == t { print $1; exit }')
    [ -n "$xy" ] || { echo "no element with text: $2" >&2; exit 1; }
    "$ADB" shell input tap "${xy%,*}" "${xy#*,}"
    echo "tapped $2 at $xy"
    ;;
  shot) "$ADB" exec-out screencap -p > "$2" ;;
  *) sed -n '2,10p' "$0"; exit 1 ;;
esac
