#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tool_root="$repo_root/.tools"
mkdir -p "$tool_root/downloads"
pin() { sed -n "s/^$1=//p" "$repo_root/scripts/native-versions.properties" | tr -d '\r'; }
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) platform=linux-x86_64; hash="$(pin cmake.linux.sha256)" ;;
  *) echo 'Unqualified native platform. See README.md.' >&2; exit 1 ;;
esac
verify() {
  local archive="$tool_root/downloads/$2"
  if [[ ! -f "$archive" ]]; then
    curl -fL --retry 3 "$1" -o "$archive.part"
    mv "$archive.part" "$archive"
  fi
  if command -v sha256sum >/dev/null; then
    echo "$3  $archive" | sha256sum -c -
  else
    echo "$3  $archive" | shasum -a 256 -c -
  fi
}
version="$(pin cmake.version)"
archive="cmake-$version-$platform.tar.gz"
verify "https://github.com/Kitware/CMake/releases/download/v$version/$archive" "$archive" "$hash"
if [[ ! -d "$tool_root/cmake-$version-$platform" ]]; then tar -xzf "$tool_root/downloads/$archive" -C "$tool_root"; fi
verify 'https://codeload.github.com/Open-Cascade-SAS/OCCT/tar.gz/refs/tags/V7_9_0' 'OCCT-7.9.0.tar.gz' "$(pin occt.sha256)"
if [[ ! -d "$tool_root/OCCT-7_9_0" ]]; then tar -xzf "$tool_root/downloads/OCCT-7.9.0.tar.gz" -C "$tool_root"; fi
echo 'Pinned native sources and CMake are ready. Run ./gradlew check'
