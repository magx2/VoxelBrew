#!/usr/bin/env bash
set -euo pipefail

dmg="$1"
evidence="gui/build/package-artifacts/installer"
mount="$(mktemp -d "${TMPDIR:-/tmp}/voxelbrew-dmg.XXXXXX")"
installed="/Applications/VoxelBrew.app"
if [[ -e "$installed" ]]; then
    echo "Refusing to replace an existing $installed" >&2
    exit 1
fi
cleanup() {
    rm -rf "$installed"
    /usr/bin/hdiutil detach "$mount" -quiet || true
    rmdir "$mount" || true
}
trap cleanup EXIT
mkdir -p "$evidence"
/usr/bin/hdiutil attach "$dmg" -nobrowse -quiet -mountpoint "$mount"
test -L "$mount/Applications"
test "$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$mount/VoxelBrew.app/Contents/Info.plist")" = dev.voxelbrew.gui
/usr/bin/ditto "$mount/VoxelBrew.app" "$installed"
test -x "$installed/Contents/MacOS/VoxelBrew"
rm -f "$evidence/installed-gui.png" "$evidence/installed-gui.txt"
/usr/bin/open -n "$installed" --args --smoke-test --smoke-output "$PWD/$evidence/installed-gui.png"
for ((i=0; i<120; i++)); do
    if [[ -s "$evidence/installed-gui.png" && -s "$evidence/installed-gui.txt" ]]; then
        exit 0
    fi
    sleep 1
done
echo 'Installed GUI did not finish its render-and-pick smoke test' >&2
exit 1
