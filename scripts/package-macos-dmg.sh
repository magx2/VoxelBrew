#!/usr/bin/env bash
set -euo pipefail

stage="$1"
output="$2"
app="$stage/VoxelBrew.app"
test -x "$app/Contents/MacOS/VoxelBrew" || {
    echo 'Missing executable VoxelBrew.app/Contents/MacOS/VoxelBrew' >&2
    exit 1
}
test -d "$app/Contents/runtime" || {
    echo 'Missing bundled runtime in VoxelBrew.app' >&2
    exit 1
}
ln -s /Applications "$stage/Applications"
trap 'rm -f "$stage/Applications"' EXIT
/usr/bin/hdiutil create -volname VoxelBrew -srcfolder "$stage" -format UDZO -ov "$output"
