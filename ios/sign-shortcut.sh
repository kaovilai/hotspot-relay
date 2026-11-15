#!/bin/sh
# Sign a shortcut so iOS 15+ will import it.
#
#   ./ios/sign-shortcut.sh ios/iPhone-Hotspot-On.shortcut
#
# Requires macOS 12+ (the `shortcuts` CLI ships with Shortcuts). Produces
# ios/iPhone-Hotspot-On-signed.shortcut, which can be AirDropped to the iPhone
# or shared through iCloud Drive / Files.
set -eu

input="${1:-ios/iPhone-Hotspot-On.shortcut}"
output="${input%.shortcut}-signed.shortcut"

if ! command -v shortcuts >/dev/null 2>&1; then
    echo "the 'shortcuts' CLI is only available on macOS; sign the file there" >&2
    exit 1
fi

shortcuts sign --mode anyone --input "$input" --output "$output"
echo "wrote $output"
