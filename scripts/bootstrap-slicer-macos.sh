#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
tools="$root/.tools"
app="$tools/PrusaSlicer-2.7.2/PrusaSlicer.app"
if [[ ! -x "$app/Contents/MacOS/PrusaSlicer" ]]; then
    mkdir -p "$tools/downloads" "$tools/PrusaSlicer-2.7.2"
    archive="$tools/downloads/PrusaSlicer-2.7.2-macos.dmg"
    if [[ ! -f "$archive" ]]; then
        curl -fL --retry 3 'https://github.com/prusa3d/PrusaSlicer/releases/download/version_2.7.2/PrusaSlicer-2.7.2%2BMacOS-universal-202402291330.dmg' -o "$archive"
    fi
    echo "70c6225c42e7ec0ba104375a986e8167fcdf2ee074dec648b8daf4fc467ff279  $archive" | shasum -a 256 -c -
    mount="$tools/slicer-mount"
    mkdir -p "$mount"
    hdiutil attach -nobrowse -readonly -mountpoint "$mount" "$archive"
    trap 'hdiutil detach "$mount"' EXIT
    # This upstream release contains an installer package. Extract it locally;
    # do not install drivers or modify /Applications.
    expanded="$tools/slicer-expanded"
    if [[ ! -d "$expanded" ]]; then
        packages=("$mount"/*.pkg)
        pkgutil --expand-full "${packages[0]}" "$expanded"
    fi
    source_app=$(find "$expanded" -type d -name PrusaSlicer.app -print -quit)
    test -n "$source_app"
    ditto "$source_app" "$app"
fi
"$app/Contents/MacOS/PrusaSlicer" --help | sed -n '1p'
