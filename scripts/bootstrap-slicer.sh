#!/usr/bin/env bash
set -euo pipefail
if command -v prusa-slicer >/dev/null && prusa-slicer --help | sed -n '1p' | grep -q 'PrusaSlicer-2.7.2'; then
    exit 0
fi
# Qualified Ubuntu 24.04 package, upstream 2.7.2. Other distributions need their own qualification.
. /etc/os-release
if [[ "$ID" != ubuntu || "$VERSION_ID" != 24.04 ]]; then
    echo 'Use Ubuntu 24.04 or supply a separately installed PrusaSlicer 2.7.2 via VOXELBREW_SLICER.' >&2
    exit 1
fi
sudo apt-get update
sudo apt-get install -y prusa-slicer=2.7.2+dfsg-1build2
prusa-slicer --help | sed -n '1p'
