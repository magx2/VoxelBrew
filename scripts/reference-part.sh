#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${1:-$repo_root/build/demo/reference.vxb}"
mkdir -p "$(dirname "$destination")"
cli="$repo_root/cli/build/install/cli/bin/cli"
if [[ ! -x "$cli" ]]; then echo 'Run ./gradlew :cli:installDist first' >&2; exit 1; fi
"$cli" document new "$destination"
"$cli" designer sketch rectangle "$destination" --id plate --width 60 --height 40
"$cli" designer solid extrude "$destination" --id thickness --sketch plate --distance 5
"$cli" designer sketch circle "$destination" --id hole --center-x 10 --center-y 10 --diameter 4
"$cli" designer solid cut "$destination" --id cut --sketch hole --body-feature thickness
"$cli" designer inspect "$destination" --point 10,10,2.5
# Each invocation reopens the definitions in another process.
"$cli" designer feature set "$destination" --feature thickness --field distance --value 8
"$cli" designer inspect "$destination" --point 10,10,4
"$cli" designer history cursor "$destination" --position 3
"$cli" designer inspect "$destination"
"$cli" designer history cursor "$destination" --position 4
"$cli" designer history list "$destination"
echo "Saved editable reference part: $destination"
