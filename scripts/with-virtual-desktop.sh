#!/usr/bin/env bash
# Run actual GUI checks with Mesa and a window manager on an isolated X display.
set -euo pipefail
if [[ $# -eq 0 ]]; then
    echo 'Usage: bash scripts/with-virtual-desktop.sh command [args...]' >&2
    exit 2
fi
if ! command -v openbox >/dev/null || ! command -v xvfb-run >/dev/null; then
    echo 'Install the GUI test desktop: sudo apt-get install xvfb xauth openbox libgtk-3-0t64 libgl1-mesa-dri' >&2
    exit 1
fi
exec xvfb-run -a -s '-screen 0 1600x1000x24' env LIBGL_ALWAYS_SOFTWARE=1 bash -c '
    set -euo pipefail
    openbox --sm-disable &
    manager=$!
    trap '\''kill "$manager" 2>/dev/null || true; wait "$manager" 2>/dev/null || true'\'' EXIT
    # Xvfb is ready before this shell starts; allow the WM to acquire its selection.
    sleep 1
    kill -0 "$manager"
    "$@"
' desktop "$@"
